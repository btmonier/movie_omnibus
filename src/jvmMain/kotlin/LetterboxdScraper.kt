package org.btmonier

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.types.int
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.tongfei.progressbar.ProgressBarBuilder
import me.tongfei.progressbar.ProgressBarStyle
import org.jetbrains.kotlinx.dataframe.DataFrame
import org.jetbrains.kotlinx.dataframe.io.readCSV
import org.jsoup.Jsoup
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

class LetterboxdScraper : CliktCommand(name = "letterboxd_scraper") {

    private val input by option("--input")
        .required()
        .help("Path to input CSV with a 'URL' column (e.g., data/movie_collection_20251110.csv).")

    private val skip by option("--skip")
        .int()
        .default(4)
        .help("Number of header lines to skip when reading CSV (default: 4).")

    private val workers by option("--workers")
        .int()
        .default(8)
        .help("Parallel workers for scraping (default: 8).")

    private val outdir by option("--outdir")
        .default("output")
        .help("Directory to write JSON (default: 'output').")

    private val prefix by option("--prefix")
        .default("movie_collection_meta")
        .help("Output filename prefix (default: 'movie_collection_meta').")

    private val datetime by option("--datetime")
        .flag(default = false)
        .help("Use datetime stamp (YYYYMMDD_HHMM) instead of date stamp (YYYYMMDD).")

    private val limit by option("--limit")
        .int()
        .help("Optional: only process the first N rows (useful for testing).")

    private val progress by option("--progress")
        .flag(default = false)
        .help("Show visual progress bar during scraping.")

    override fun run() = runBlocking {
        // Create output directory
        File(outdir).mkdirs()

        // Generate timestamp
        val timestamp = if (datetime) {
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))
        } else {
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
        }

        val outfile = File(outdir, "${prefix}_${timestamp}.json")

        // Read CSV
        echo("Reading CSV from: $input")
        val df = DataFrame.readCSV(input, skipLines = skip)

        if ("URL" !in df.columnNames()) {
            throw IllegalArgumentException("Input CSV must contain a column named 'URL'.")
        }

        // Extract URLs and titles from CSV
        val urls = df["URL"].toList().map { it.toString() }
        val titles = if ("Name" in df.columnNames()) {
            df["Name"].toList().map { it.toString() }
        } else if ("Title" in df.columnNames()) {
            df["Title"].toList().map { it.toString() }
        } else {
            List(urls.size) { "" } // Empty titles if column not found
        }

        val moviesData = urls.zip(titles)
        val moviesToProcess = limit?.let { moviesData.take(it) } ?: moviesData

        echo("Processing ${moviesToProcess.size} URLs with $workers workers...")

        // Scrape in parallel
        val results = scrapeParallel(moviesToProcess, workers, progress)

        // Sort results by title
        val sortedResults = results.sortedBy { it.title }

        // Write JSON output
        val json = Json { prettyPrint = true }
        outfile.writeText(json.encodeToString(sortedResults))

        echo("Wrote ${sortedResults.size} rows to ${outfile.absolutePath}")
    }

    private suspend fun scrapeParallel(
        moviesData: List<Pair<String, String>>,
        workers: Int,
        showProgress: Boolean
    ): List<MovieMetadata> = coroutineScope {
        val progressBar = if (showProgress) {
            ProgressBarBuilder()
                .setTaskName("Scraping movies")
                .setInitialMax(moviesData.size.toLong())
                .setStyle(ProgressBarStyle.ASCII)
                .setMaxRenderedLength(120)  // Fixed width for the entire bar
                .build()
        } else null

        val results = mutableListOf<Deferred<MovieMetadata>>()
        val completedResults = mutableListOf<MovieMetadata>()

        try {
            moviesData.forEachIndexed { index, (url, title) ->
                val deferred = async(Dispatchers.IO) {
                    try {
                        val result = scrapeMetadata(url, title)
                        progressBar?.step()
                        // Pad title to fixed length to prevent bar resizing
                        progressBar?.extraMessage = title.take(40).padEnd(40)
                        result
                    } catch (e: Exception) {
                        progressBar?.step()
                        echo("Error scraping $url: ${e.message}", err = true)
                        MovieMetadata(url = url, title = title)
                    }
                }
                results.add(deferred)

                // Limit concurrent requests
                if (results.size >= workers) {
                    val completed = results.first().await()
                    completedResults.add(completed)
                    results.removeAt(0)
                }
            }

            // Wait for remaining results
            val remaining = results.awaitAll()
            completedResults.addAll(remaining)
        } finally {
            progressBar?.close()
        }

        completedResults
    }

    private suspend fun scrapeMetadata(url: String, title: String): MovieMetadata {
        // Politeness pause to stagger requests
        delay(Random.nextLong(250, 750))

        val doc = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .timeout(10000)
            .get()

        return ScraperUtils.extractMetadata(doc, url, title)
    }
}

fun main(args: Array<String>) = LetterboxdScraper().main(args)
