package org.btmonier.database

import org.btmonier.MovieMetadata
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.LocalDate

/**
 * Data Access Object for movie database operations.
 */
class MovieDao {
    private val physicalMediaDao = PhysicalMediaDao()
    private val watchedDao = WatchedDao()
    private val genreDao = GenreDao()

    /**
     * Get all movies from the database.
     */
    suspend fun getAllMovies(): List<MovieMetadata> = DatabaseFactory.dbQuery {
        Movies.selectAll().map { rowToMovieMetadata(it) }
    }

    /**
     * Get a movie by its ID.
     */
    suspend fun getMovieById(id: Int): MovieMetadata? = DatabaseFactory.dbQuery {
        Movies.selectAll().where { Movies.id eq id }
            .map { rowToMovieMetadata(it) }
            .singleOrNull()
    }

    /**
     * Get a movie by its URL.
     */
    suspend fun getMovieByUrl(url: String): MovieMetadata? = DatabaseFactory.dbQuery {
        Movies.selectAll().where { Movies.url eq url }
            .map { rowToMovieMetadata(it) }
            .singleOrNull()
    }

    /**
     * Get a movie ID by its URL.
     */
    suspend fun getMovieIdByUrl(url: String): Int? = DatabaseFactory.dbQuery {
        Movies.selectAll().where { Movies.url eq url }
            .map { it[Movies.id].value }
            .singleOrNull()
    }

    /**
     * Search movies by title (case-insensitive partial match).
     * Searches both the main title and alternate titles.
     */
    suspend fun searchMoviesByTitle(query: String): List<MovieMetadata> = DatabaseFactory.dbQuery {
        val lowerQuery = query.lowercase()

        // Find movie IDs that match the main title
        val mainTitleMatches = Movies.selectAll()
            .where { Movies.title.lowerCase() like "%$lowerQuery%" }
            .map { it[Movies.id].value }

        // Find movie IDs that match any alternate title
        val alternateTitleMatches = MovieAlternateTitles.selectAll()
            .where { MovieAlternateTitles.alternateTitle.lowerCase() like "%$lowerQuery%" }
            .map { it[MovieAlternateTitles.movieId].value }

        // Combine both sets of movie IDs (distinct to avoid duplicates)
        val allMatchingIds = (mainTitleMatches + alternateTitleMatches).distinct()

        // Fetch and return the movies
        Movies.selectAll()
            .where { Movies.id inList allMatchingIds }
            .map { rowToMovieMetadata(it) }
    }

    /**
     * Filter movies by genre.
     */
    suspend fun getMoviesByGenre(genre: String): List<MovieMetadata> = DatabaseFactory.dbQuery {
        // Find the genre ID
        val genreRow = Genres.selectAll().where { Genres.name eq genre }.singleOrNull()
        if (genreRow == null) {
            return@dbQuery emptyList()
        }
        val genreId = genreRow[Genres.id].value

        val movieIds = MovieGenres.selectAll().where { MovieGenres.genreId eq genreId }
            .map { it[MovieGenres.movieId].value }

        Movies.selectAll().where { Movies.id inList movieIds }
            .map { rowToMovieMetadata(it) }
    }

    /**
     * Filter movies by country.
     */
    suspend fun getMoviesByCountry(country: String): List<MovieMetadata> = DatabaseFactory.dbQuery {
        val movieIds = MovieCountries.selectAll().where { MovieCountries.country eq country }
            .map { it[MovieCountries.movieId].value }

        Movies.selectAll().where { Movies.id inList movieIds }
            .map { rowToMovieMetadata(it) }
    }

    /**
     * Create a new movie entry.
     */
    suspend fun createMovie(movie: MovieMetadata): Int = DatabaseFactory.dbQuery {
        val movieId = Movies.insertAndGetId {
            it[url] = movie.url
            it[title] = movie.title
            it[description] = movie.description
            it[releaseDate] = movie.release_date?.let { year -> LocalDate.of(year, 1, 1) }
            it[runtimeMins] = movie.runtime_mins
        }

        // Insert related data
        insertAlternateTitles(movieId.value, movie.alternateTitles)
        insertGenres(movieId.value, movie.genres)
        insertSubgenres(movieId.value, movie.subgenres)
        insertThemes(movieId.value, movie.themes)
        insertCountries(movieId.value, movie.country)
        insertCast(movieId.value, movie.cast)
        insertCrew(movieId.value, movie.crew)

        movieId.value
    }

    /**
     * Update an existing movie entry.
     */
    suspend fun updateMovie(id: Int, movie: MovieMetadata): Boolean = DatabaseFactory.dbQuery {
        val updated = Movies.update({ Movies.id eq id }) {
            it[url] = movie.url
            it[title] = movie.title
            it[description] = movie.description
            it[releaseDate] = movie.release_date?.let { year -> LocalDate.of(year, 1, 1) }
            it[runtimeMins] = movie.runtime_mins
        }

        if (updated > 0) {
            // Delete old related data
            MovieAlternateTitles.deleteWhere(op = { MovieAlternateTitles.movieId.eq(id) })
            MovieGenres.deleteWhere(op = { MovieGenres.movieId.eq(id) })
            MovieSubgenres.deleteWhere(op = { MovieSubgenres.movieId.eq(id) })
            MovieThemes.deleteWhere(op = { MovieThemes.movieId.eq(id) })
            MovieCountries.deleteWhere(op = { MovieCountries.movieId.eq(id) })
            MovieCast.deleteWhere(op = { MovieCast.movieId.eq(id) })
            MovieCrew.deleteWhere(op = { MovieCrew.movieId.eq(id) })

            // Insert new related data
            insertAlternateTitles(id, movie.alternateTitles)
            insertGenres(id, movie.genres)
            insertSubgenres(id, movie.subgenres)
            insertThemes(id, movie.themes)
            insertCountries(id, movie.country)
            insertCast(id, movie.cast)
            insertCrew(id, movie.crew)
        }

        updated > 0
    }

    /**
     * Delete a movie entry.
     */
    suspend fun deleteMovie(id: Int): Boolean = DatabaseFactory.dbQuery {
        // Delete related data first (due to foreign key constraints)
        MovieAlternateTitles.deleteWhere(op = { MovieAlternateTitles.movieId.eq(id) })
        MovieGenres.deleteWhere(op = { MovieGenres.movieId.eq(id) })
        MovieSubgenres.deleteWhere(op = { MovieSubgenres.movieId.eq(id) })
        MovieThemes.deleteWhere(op = { MovieThemes.movieId.eq(id) })
        MovieCountries.deleteWhere(op = { MovieCountries.movieId.eq(id) })
        MovieCast.deleteWhere(op = { MovieCast.movieId.eq(id) })
        MovieCrew.deleteWhere(op = { MovieCrew.movieId.eq(id) })

        // Delete physical media and associated images
        physicalMediaDao.deleteAllPhysicalMediaForMovie(id)

        // Delete watched entries
        watchedDao.deleteAllWatchedEntriesForMovie(id)

        // Delete the movie
        val deleted = Movies.deleteWhere(op = { Movies.id.eq(id) })
        deleted > 0
    }

    /**
     * Check if a movie with the given URL already exists.
     */
    suspend fun movieExists(url: String): Boolean = DatabaseFactory.dbQuery {
        Movies.selectAll().where { Movies.url eq url }.count() > 0
    }

    /**
     * Get all unique genres from the database (for autocomplete).
     */
    suspend fun getAllGenres(): List<String> = DatabaseFactory.dbQuery {
        Genres.selectAll()
            .orderBy(Genres.name to SortOrder.ASC)
            .map { it[Genres.name] }
    }

    /**
     * Get all unique subgenres from the database (for autocomplete).
     */
    suspend fun getAllSubgenres(): List<String> = DatabaseFactory.dbQuery {
        Subgenres.selectAll()
            .orderBy(Subgenres.name to SortOrder.ASC)
            .map { it[Subgenres.name] }
    }

    /**
     * Get all unique countries from the database (for filtering).
     */
    suspend fun getAllCountries(): List<String> = DatabaseFactory.dbQuery {
        MovieCountries.selectAll()
            .map { it[MovieCountries.country] }
            .distinct()
            .sorted()
    }

    /**
     * Get all unique media types from the database (for filtering).
     */
    suspend fun getAllMediaTypes(): List<String> = DatabaseFactory.dbQuery {
        PhysicalMediaTypes.selectAll()
            .map { it[PhysicalMediaTypes.mediaType] }
            .distinct()
            .sorted()
    }

    /**
     * Get a random unwatched movie with optional filters.
     * @param genre Optional genre filter
     * @param subgenre Optional subgenre filter
     * @param country Optional country filter
     * @param mediaType Optional media type filter
     * @return A random movie matching the criteria, or null if none found
     */
    suspend fun getRandomUnwatchedMovie(
        genre: String? = null,
        subgenre: String? = null,
        country: String? = null,
        mediaType: String? = null
    ): MovieMetadata? = DatabaseFactory.dbQuery {
        // Start with all movie IDs
        var candidateMovieIds = Movies.selectAll().map { it[Movies.id].value }.toSet()

        // Exclude movies that have been watched
        val watchedMovieIds = WatchedEntries.selectAll()
            .map { it[WatchedEntries.movieId].value }
            .toSet()
        candidateMovieIds = candidateMovieIds - watchedMovieIds

        // Filter by genre if specified
        if (!genre.isNullOrBlank()) {
            val genreRow = Genres.selectAll().where { Genres.name eq genre }.singleOrNull()
            if (genreRow != null) {
                val genreId = genreRow[Genres.id].value
                val movieIdsWithGenre = MovieGenres.selectAll()
                    .where { MovieGenres.genreId eq genreId }
                    .map { it[MovieGenres.movieId].value }
                    .toSet()
                candidateMovieIds = candidateMovieIds.intersect(movieIdsWithGenre)
            } else {
                return@dbQuery null // Genre not found
            }
        }

        // Filter by subgenre if specified
        if (!subgenre.isNullOrBlank()) {
            val subgenreRow = Subgenres.selectAll().where { Subgenres.name eq subgenre }.singleOrNull()
            if (subgenreRow != null) {
                val subgenreId = subgenreRow[Subgenres.id].value
                val movieIdsWithSubgenre = MovieSubgenres.selectAll()
                    .where { MovieSubgenres.subgenreId eq subgenreId }
                    .map { it[MovieSubgenres.movieId].value }
                    .toSet()
                candidateMovieIds = candidateMovieIds.intersect(movieIdsWithSubgenre)
            } else {
                return@dbQuery null // Subgenre not found
            }
        }

        // Filter by country if specified
        if (!country.isNullOrBlank()) {
            val movieIdsWithCountry = MovieCountries.selectAll()
                .where { MovieCountries.country eq country }
                .map { it[MovieCountries.movieId].value }
                .toSet()
            candidateMovieIds = candidateMovieIds.intersect(movieIdsWithCountry)
        }

        // Filter by media type if specified
        if (!mediaType.isNullOrBlank()) {
            val physicalMediaIdsWithType = PhysicalMediaTypes.selectAll()
                .where { PhysicalMediaTypes.mediaType eq mediaType }
                .map { it[PhysicalMediaTypes.physicalMediaId].value }
                .toSet()

            val movieIdsWithMediaType = PhysicalMedia.selectAll()
                .where { PhysicalMedia.id inList physicalMediaIdsWithType }
                .map { it[PhysicalMedia.movieId].value }
                .toSet()

            candidateMovieIds = candidateMovieIds.intersect(movieIdsWithMediaType)
        }

        // Pick a random movie from candidates
        if (candidateMovieIds.isEmpty()) {
            return@dbQuery null
        }

        val randomId = candidateMovieIds.random()
        Movies.selectAll().where { Movies.id eq randomId }
            .map { rowToMovieMetadata(it) }
            .singleOrNull()
    }

    /**
     * Count unwatched movies with optional filters (for UI display).
     */
    suspend fun countUnwatchedMovies(
        genre: String? = null,
        subgenre: String? = null,
        country: String? = null,
        mediaType: String? = null
    ): Int = DatabaseFactory.dbQuery {
        var candidateMovieIds = Movies.selectAll().map { it[Movies.id].value }.toSet()

        // Exclude watched movies
        val watchedMovieIds = WatchedEntries.selectAll()
            .map { it[WatchedEntries.movieId].value }
            .toSet()
        candidateMovieIds = candidateMovieIds - watchedMovieIds

        // Apply same filters as getRandomUnwatchedMovie
        if (!genre.isNullOrBlank()) {
            val genreRow = Genres.selectAll().where { Genres.name eq genre }.singleOrNull()
            if (genreRow != null) {
                val genreId = genreRow[Genres.id].value
                val movieIdsWithGenre = MovieGenres.selectAll()
                    .where { MovieGenres.genreId eq genreId }
                    .map { it[MovieGenres.movieId].value }
                    .toSet()
                candidateMovieIds = candidateMovieIds.intersect(movieIdsWithGenre)
            } else {
                return@dbQuery 0
            }
        }

        if (!subgenre.isNullOrBlank()) {
            val subgenreRow = Subgenres.selectAll().where { Subgenres.name eq subgenre }.singleOrNull()
            if (subgenreRow != null) {
                val subgenreId = subgenreRow[Subgenres.id].value
                val movieIdsWithSubgenre = MovieSubgenres.selectAll()
                    .where { MovieSubgenres.subgenreId eq subgenreId }
                    .map { it[MovieSubgenres.movieId].value }
                    .toSet()
                candidateMovieIds = candidateMovieIds.intersect(movieIdsWithSubgenre)
            } else {
                return@dbQuery 0
            }
        }

        if (!country.isNullOrBlank()) {
            val movieIdsWithCountry = MovieCountries.selectAll()
                .where { MovieCountries.country eq country }
                .map { it[MovieCountries.movieId].value }
                .toSet()
            candidateMovieIds = candidateMovieIds.intersect(movieIdsWithCountry)
        }

        if (!mediaType.isNullOrBlank()) {
            val physicalMediaIdsWithType = PhysicalMediaTypes.selectAll()
                .where { PhysicalMediaTypes.mediaType eq mediaType }
                .map { it[PhysicalMediaTypes.physicalMediaId].value }
                .toSet()

            val movieIdsWithMediaType = PhysicalMedia.selectAll()
                .where { PhysicalMedia.id inList physicalMediaIdsWithType }
                .map { it[PhysicalMedia.movieId].value }
                .toSet()

            candidateMovieIds = candidateMovieIds.intersect(movieIdsWithMediaType)
        }

        candidateMovieIds.size
    }

    // Helper functions to insert related data
    private fun insertAlternateTitles(movieId: Int, alternateTitles: List<String>) {
        alternateTitles.forEach { title ->
            MovieAlternateTitles.insert {
                it[MovieAlternateTitles.movieId] = movieId
                it[alternateTitle] = title
            }
        }
    }

    private suspend fun insertGenres(movieId: Int, genres: List<String>) {
        genres.forEach { genreName ->
            // Get or create the genre
            val genreId = genreDao.getOrCreateGenre(genreName)
            MovieGenres.insert {
                it[MovieGenres.movieId] = movieId
                it[MovieGenres.genreId] = genreId
            }
        }
    }

    private suspend fun insertSubgenres(movieId: Int, subgenres: List<String>) {
        subgenres.forEach { subgenreName ->
            // Get or create the subgenre
            val subgenreId = genreDao.getOrCreateSubgenre(subgenreName)
            MovieSubgenres.insert {
                it[MovieSubgenres.movieId] = movieId
                it[MovieSubgenres.subgenreId] = subgenreId
            }
        }
    }

    private fun insertThemes(movieId: Int, themes: List<String>) {
        themes.forEach { theme ->
            MovieThemes.insert {
                it[MovieThemes.movieId] = movieId
                it[MovieThemes.theme] = theme
            }
        }
    }

    private fun insertCountries(movieId: Int, countries: List<String>) {
        countries.forEach { country ->
            MovieCountries.insert {
                it[MovieCountries.movieId] = movieId
                it[MovieCountries.country] = country
            }
        }
    }

    private fun insertCast(movieId: Int, cast: List<String>) {
        cast.forEach { member ->
            MovieCast.insert {
                it[MovieCast.movieId] = movieId
                it[castMember] = member
            }
        }
    }

    private fun insertCrew(movieId: Int, crew: Map<String, List<String>>) {
        crew.forEach { (role, members) ->
            members.forEach { member ->
                MovieCrew.insert {
                    it[MovieCrew.movieId] = movieId
                    it[MovieCrew.role] = role
                    it[crewMember] = member
                }
            }
        }
    }

    // Helper function to convert a database row to MovieMetadata
    private suspend fun rowToMovieMetadata(row: ResultRow): MovieMetadata {
        val movieId = row[Movies.id].value

        val alternateTitles = MovieAlternateTitles.selectAll().where { MovieAlternateTitles.movieId eq movieId }
            .map { it[MovieAlternateTitles.alternateTitle] }

        val genres = (MovieGenres innerJoin Genres)
            .select(Genres.name)
            .where { MovieGenres.movieId eq movieId }
            .map { it[Genres.name] }

        val subgenres = (MovieSubgenres innerJoin Subgenres)
            .select(Subgenres.name)
            .where { MovieSubgenres.movieId eq movieId }
            .map { it[Subgenres.name] }

        val themes = MovieThemes.selectAll().where { MovieThemes.movieId eq movieId }
            .map { it[MovieThemes.theme] }

        val countries = MovieCountries.selectAll().where { MovieCountries.movieId eq movieId }
            .map { it[MovieCountries.country] }

        val cast = MovieCast.selectAll().where { MovieCast.movieId eq movieId }
            .map { it[MovieCast.castMember] }

        val crew = MovieCrew.selectAll().where { MovieCrew.movieId eq movieId }
            .groupBy({ it[MovieCrew.role] }, { it[MovieCrew.crewMember] })

        val physicalMedia = physicalMediaDao.getPhysicalMediaForMovie(movieId)
        val watchedEntries = watchedDao.getWatchedEntriesForMovie(movieId)

        return MovieMetadata(
            url = row[Movies.url],
            title = row[Movies.title],
            description = row[Movies.description],
            alternateTitles = alternateTitles,
            genres = genres,
            subgenres = subgenres,
            themes = themes,
            country = countries,
            cast = cast,
            crew = crew,
            release_date = row[Movies.releaseDate]?.year,
            runtime_mins = row[Movies.runtimeMins],
            physicalMedia = physicalMedia,
            watchedEntries = watchedEntries,
            id = movieId
        )
    }
}
