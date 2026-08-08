package org.btmonier

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.btmonier.database.DatabaseFactory
import org.btmonier.database.MovieDao
import org.jsoup.Jsoup
import kotlin.random.Random

/**
 * Re-scrapes cast and crew for movies already stored in the database.
 *
 * Letterboxd moved the cast and crew markup into new containers, which left the
 * scraper returning nothing for those two fields. Movies added during that window
 * are in the database with empty cast/crew, and this command repopulates them
 * without disturbing any other stored data.
 */
class BackfillCastCrew : CliktCommand(name = "backfill_cast_crew") {

    private val workers by option("--workers")
        .int()
        .default(4)
        .help("Parallel workers for scraping (default: 4).")

    private val limit by option("--limit")
        .int()
        .help("Optional: only process the first N candidate movies.")

    private val dryRun by option("--dry-run")
        .flag(default = false)
        .help("Report what would change without writing to the database.")

    private val all by option("--all")
        .flag(default = false)
        .help("Re-scrape every movie, not just those missing cast or crew.")

    override fun run() = runBlocking {
        DatabaseFactory.init()
        val dao = MovieDao()

        val movies = dao.getAllMovies()
        val candidates = movies
            .filter { it.id != null && it.url.isNotBlank() }
            .filter { all || it.cast.isEmpty() || it.crew.isEmpty() }
            .let { candidates -> limit?.let { candidates.take(it) } ?: candidates }

        if (candidates.isEmpty()) {
            echo("No movies need a cast/crew backfill (${movies.size} movies checked).")
            return@runBlocking
        }

        echo("Found ${candidates.size} of ${movies.size} movies to re-scrape with $workers workers.")
        if (dryRun) echo("Dry run: no changes will be written.")

        val outcomes = candidates.chunked(workers).flatMap { chunk ->
            coroutineScope {
                chunk.map { movie ->
                    async(Dispatchers.IO) { backfill(dao, movie) }
                }.awaitAll()
            }
        }

        val updated = outcomes.count { it is Outcome.Updated }
        val unchanged = outcomes.count { it is Outcome.Unchanged }
        val failed = outcomes.filterIsInstance<Outcome.Failed>()

        echo("")
        echo("Updated: $updated, unchanged: $unchanged, failed: ${failed.size}")
        failed.forEach { echo("  ${it.title}: ${it.message}", err = true) }
    }

    private suspend fun backfill(dao: MovieDao, movie: MovieMetadata): Outcome {
        val id = movie.id ?: return Outcome.Unchanged

        // Politeness pause to stagger requests
        delay(Random.nextLong(250, 750))

        val doc = try {
            Jsoup.connect(movie.url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .timeout(15000)
                .get()
        } catch (e: Exception) {
            return Outcome.Failed(movie.title, e.message ?: e::class.simpleName ?: "unknown error")
        }

        val cast = ScraperUtils.extractCast(doc)
        val crew = ScraperUtils.extractCrew(doc)

        if (cast == movie.cast && crew == movie.crew) {
            return Outcome.Unchanged
        }

        if (cast.isEmpty() && crew.isEmpty()) {
            return Outcome.Failed(
                movie.title,
                "page returned no cast or crew - the Letterboxd markup may have changed again"
            )
        }

        echo("${movie.title}: ${cast.size} cast, ${crew.size} crew roles")
        if (dryRun) return Outcome.Updated

        return if (dao.updateMovie(id, movie.copy(cast = cast, crew = crew))) {
            Outcome.Updated
        } else {
            Outcome.Failed(movie.title, "database update affected no rows")
        }
    }

    private sealed interface Outcome {
        data object Updated : Outcome
        data object Unchanged : Outcome
        data class Failed(val title: String, val message: String) : Outcome
    }
}

fun main(args: Array<String>) = BackfillCastCrew().main(args)
