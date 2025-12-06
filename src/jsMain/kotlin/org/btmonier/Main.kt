package org.btmonier

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import org.w3c.fetch.RequestInit

val mainScope = MainScope()

// API base URL - change this for production
const val API_BASE_URL = "http://localhost:8080/api"

fun main() {
    mainScope.launch {
        try {
            // Initialize the movie table - it will load data itself
            val root = document.getElementById("root") ?: error("Couldn't find root container!")
            val movieTable = MovieTable(root)
            movieTable.render()
        } catch (e: Exception) {
            console.error("Error loading movie data:", e)
            val root = document.getElementById("root")
            root?.innerHTML = """
                <div style="padding: 20px; background-color: #f8d7da; color: #721c24; border-radius: 4px; margin: 20px;">
                    Failed to load movie data: ${e.message}
                    <br><br>
                    Make sure the API server is running on port 8080.
                    <br>
                    Run: <code style="background-color: #f5c6cb; padding: 2px 6px; border-radius: 3px;">./gradlew runServer</code>
                </div>
            """.trimIndent()
        }
    }
}

/**
 * Fetch all movies from the API (legacy - use fetchMoviesPaginated for large datasets).
 */
suspend fun fetchMoviesFromApi(): List<MovieMetadata> {
    val response = window.fetch("$API_BASE_URL/movies").await()
    if (!response.ok) {
        throw Exception("Failed to fetch movies: ${response.status} ${response.statusText}")
    }
    val jsonText = response.text().await()
    // Handle new paginated response format
    val paginatedResponse: PaginatedMoviesResponse = Json.decodeFromString(jsonText)
    return paginatedResponse.movies
}

// ==================== Pagination API ====================

/**
 * Paginated response for movie list.
 */
@kotlinx.serialization.Serializable
data class PaginatedMoviesResponse(
    val movies: List<MovieMetadata>,
    val totalCount: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int
)

/**
 * Fetch paginated movies with optional filters.
 */
suspend fun fetchMoviesPaginated(
    page: Int = 1,
    pageSize: Int = 25,
    search: String? = null,
    genre: String? = null,
    country: String? = null,
    mediaType: String? = null
): PaginatedMoviesResponse {
    val params = mutableListOf<String>()
    params.add("page=$page")
    params.add("pageSize=$pageSize")
    search?.let { if (it.isNotBlank()) params.add("search=$it") }
    genre?.let { if (it.isNotBlank()) params.add("genre=$it") }
    country?.let { if (it.isNotBlank()) params.add("country=$it") }
    mediaType?.let { if (it.isNotBlank()) params.add("mediaType=$it") }

    val queryString = "?" + params.joinToString("&")
    val response = window.fetch("$API_BASE_URL/movies$queryString").await()

    if (!response.ok) {
        throw Exception("Failed to fetch movies: ${response.status} ${response.statusText}")
    }

    val json = response.text().await()
    return Json.decodeFromString(json)
}

/**
 * Fetch filter options for genres (as strings).
 */
suspend fun fetchGenreOptions(): List<String> {
    val response = window.fetch("$API_BASE_URL/movies/genres").await()
    val json = response.text().await()
    return Json.decodeFromString(json)
}

/**
 * Create a new movie via the API.
 */
suspend fun createMovie(movie: MovieMetadata): MovieMetadata {
    val response = window.fetch("$API_BASE_URL/movies", RequestInit(
        method = "POST",
        headers = js("({'Content-Type': 'application/json'})"),
        body = Json.encodeToString(MovieMetadata.serializer(), movie)
    )).await()

    if (!response.ok) {
        val errorText = response.text().await()
        throw Exception("Failed to create movie: $errorText")
    }

    val jsonText = response.text().await()
    return Json.decodeFromString(MovieMetadata.serializer(), jsonText)
}

/**
 * Update an existing movie via the API.
 * Note: ID parameter will need to be determined from the existing movie URL
 */
suspend fun updateMovie(id: Int, movie: MovieMetadata): MovieMetadata {
    val response = window.fetch("$API_BASE_URL/movies/$id", RequestInit(
        method = "PUT",
        headers = js("({'Content-Type': 'application/json'})"),
        body = Json.encodeToString(MovieMetadata.serializer(), movie)
    )).await()

    if (!response.ok) {
        val errorText = response.text().await()
        throw Exception("Failed to update movie: $errorText")
    }

    val jsonText = response.text().await()
    return Json.decodeFromString(MovieMetadata.serializer(), jsonText)
}

/**
 * Delete a movie via the API.
 */
suspend fun deleteMovie(id: Int): Boolean {
    val response = window.fetch("$API_BASE_URL/movies/$id", RequestInit(
        method = "DELETE"
    )).await()

    return response.ok
}

/**
 * Fetch physical media entries for a movie.
 */
suspend fun fetchPhysicalMediaForMovie(movieId: Int): List<PhysicalMedia> {
    val response = window.fetch("$API_BASE_URL/movies/$movieId/physical-media").await()
    if (!response.ok) {
        throw Exception("Failed to fetch physical media: ${response.status} ${response.statusText}")
    }
    val jsonText = response.text().await()
    return Json.decodeFromString(ListSerializer(PhysicalMedia.serializer()), jsonText)
}

/**
 * Create a new physical media entry for a movie.
 */
suspend fun createPhysicalMedia(movieId: Int, physicalMedia: PhysicalMedia): PhysicalMedia {
    val response = window.fetch("$API_BASE_URL/movies/$movieId/physical-media", RequestInit(
        method = "POST",
        headers = js("({'Content-Type': 'application/json'})"),
        body = Json.encodeToString(PhysicalMedia.serializer(), physicalMedia)
    )).await()

    if (!response.ok) {
        val errorText = response.text().await()
        throw Exception("Failed to create physical media: $errorText")
    }

    val jsonText = response.text().await()
    return Json.decodeFromString(PhysicalMedia.serializer(), jsonText)
}

/**
 * Update an existing physical media entry.
 */
suspend fun updatePhysicalMedia(id: Int, physicalMedia: PhysicalMedia): PhysicalMedia {
    val response = window.fetch("$API_BASE_URL/physical-media/$id", RequestInit(
        method = "PUT",
        headers = js("({'Content-Type': 'application/json'})"),
        body = Json.encodeToString(PhysicalMedia.serializer(), physicalMedia)
    )).await()

    if (!response.ok) {
        val errorText = response.text().await()
        throw Exception("Failed to update physical media: $errorText")
    }

    val jsonText = response.text().await()
    return Json.decodeFromString(PhysicalMedia.serializer(), jsonText)
}

/**
 * Delete a physical media entry.
 */
suspend fun deletePhysicalMedia(id: Int): Boolean {
    val response = window.fetch("$API_BASE_URL/physical-media/$id", RequestInit(
        method = "DELETE"
    )).await()

    return response.ok
}

// ==================== Watched Entries API ====================

suspend fun fetchWatchedEntriesForMovie(movieId: Int): List<WatchedEntry> {
    val response = window.fetch("$API_BASE_URL/movies/$movieId/watched").await()
    val json = response.text().await()
    return Json.decodeFromString(json)
}

suspend fun createWatchedEntry(movieId: Int, watchedEntry: WatchedEntry): WatchedEntry {
    val response = window.fetch("$API_BASE_URL/movies/$movieId/watched", RequestInit(
        method = "POST",
        headers = js("({'Content-Type': 'application/json'})"),
        body = Json.encodeToString(WatchedEntry.serializer(), watchedEntry)
    )).await()

    val json = response.text().await()
    return Json.decodeFromString(json)
}

suspend fun updateWatchedEntry(id: Int, watchedEntry: WatchedEntry): WatchedEntry {
    val response = window.fetch("$API_BASE_URL/watched/$id", RequestInit(
        method = "PUT",
        headers = js("({'Content-Type': 'application/json'})"),
        body = Json.encodeToString(WatchedEntry.serializer(), watchedEntry)
    )).await()

    val json = response.text().await()
    return Json.decodeFromString(json)
}

suspend fun deleteWatchedEntry(id: Int): Boolean {
    val response = window.fetch("$API_BASE_URL/watched/$id", RequestInit(
        method = "DELETE"
    )).await()

    return response.ok
}

// ==================== Genre/Subgenre API ====================

@kotlinx.serialization.Serializable
data class GenreResponse(
    val id: Int,
    val name: String
)

@kotlinx.serialization.Serializable
data class SubgenreResponse(
    val id: Int,
    val name: String
)

suspend fun fetchAllGenres(): List<GenreResponse> {
    val response = window.fetch("$API_BASE_URL/genres").await()
    val json = response.text().await()
    return Json.decodeFromString(json)
}

suspend fun fetchAllSubgenres(): List<SubgenreResponse> {
    val response = window.fetch("$API_BASE_URL/subgenres").await()
    val json = response.text().await()
    return Json.decodeFromString(json)
}

suspend fun createGenre(name: String): GenreResponse {
    val response = window.fetch("$API_BASE_URL/genres", RequestInit(
        method = "POST",
        headers = js("({'Content-Type': 'application/json'})"),
        body = """{"name": "$name"}"""
    )).await()

    val json = response.text().await()
    return Json.decodeFromString(json)
}

suspend fun createSubgenre(name: String): SubgenreResponse {
    val response = window.fetch("$API_BASE_URL/subgenres", RequestInit(
        method = "POST",
        headers = js("({'Content-Type': 'application/json'})"),
        body = """{"name": "$name"}"""
    )).await()

    val json = response.text().await()
    return Json.decodeFromString(json)
}

suspend fun deleteGenre(id: Int): Boolean {
    val response = window.fetch("$API_BASE_URL/genres/$id", RequestInit(
        method = "DELETE"
    )).await()

    return response.ok
}

suspend fun deleteSubgenre(id: Int): Boolean {
    val response = window.fetch("$API_BASE_URL/subgenres/$id", RequestInit(
        method = "DELETE"
    )).await()

    return response.ok
}

// ==================== Distributor API ====================

@kotlinx.serialization.Serializable
data class DistributorResponse(
    val id: Int,
    val name: String
)

suspend fun fetchAllDistributors(): List<DistributorResponse> {
    val response = window.fetch("$API_BASE_URL/distributors").await()
    val json = response.text().await()
    return Json.decodeFromString(json)
}

suspend fun createDistributor(name: String): DistributorResponse {
    val response = window.fetch("$API_BASE_URL/distributors", RequestInit(
        method = "POST",
        headers = js("({'Content-Type': 'application/json'})"),
        body = """{"name": "$name"}"""
    )).await()

    val json = response.text().await()
    return Json.decodeFromString(json)
}

suspend fun deleteDistributor(id: Int): Boolean {
    val response = window.fetch("$API_BASE_URL/distributors/$id", RequestInit(
        method = "DELETE"
    )).await()

    return response.ok
}

// ==================== Scraping API ====================

@kotlinx.serialization.Serializable
data class ScrapeRequest(val url: String)

@kotlinx.serialization.Serializable
data class ScrapeResponse(
    val success: Boolean,
    val movie: MovieMetadata? = null,
    val exists: Boolean = false,
    val existingMovieId: Int? = null,
    val error: String? = null
)

/**
 * Scrape movie data from a Letterboxd URL.
 * Returns scraped movie data or error information if the movie already exists.
 */
suspend fun scrapeLetterboxdUrl(url: String): ScrapeResponse {
    val response = window.fetch("$API_BASE_URL/movies/scrape", RequestInit(
        method = "POST",
        headers = js("({'Content-Type': 'application/json'})"),
        body = Json.encodeToString(ScrapeRequest.serializer(), ScrapeRequest(url))
    )).await()

    val json = response.text().await()
    return Json.decodeFromString(ScrapeResponse.serializer(), json)
}

// ==================== Random Movie Picker API ====================

/**
 * Get all unique countries for filtering.
 */
suspend fun fetchAllCountries(): List<String> {
    val response = window.fetch("$API_BASE_URL/movies/countries").await()
    val json = response.text().await()
    return Json.decodeFromString(json)
}

/**
 * Get all unique media types for filtering.
 */
suspend fun fetchAllMediaTypes(): List<String> {
    val response = window.fetch("$API_BASE_URL/movies/media-types").await()
    val json = response.text().await()
    return Json.decodeFromString(json)
}

/**
 * Get a random unwatched movie with optional filters.
 */
suspend fun fetchRandomUnwatchedMovie(
    genre: String? = null,
    subgenre: String? = null,
    country: String? = null,
    mediaType: String? = null
): MovieMetadata? {
    val params = mutableListOf<String>()
    genre?.let { params.add("genre=$it") }
    subgenre?.let { params.add("subgenre=$it") }
    country?.let { params.add("country=$it") }
    mediaType?.let { params.add("mediaType=$it") }
    
    val queryString = if (params.isNotEmpty()) "?" + params.joinToString("&") else ""
    val response = window.fetch("$API_BASE_URL/movies/random$queryString").await()
    
    if (!response.ok) return null
    
    val json = response.text().await()
    return Json.decodeFromString(MovieMetadata.serializer(), json)
}

@kotlinx.serialization.Serializable
data class CountResponse(val count: Int)

/**
 * Get count of unwatched movies matching filters.
 */
suspend fun fetchUnwatchedMovieCount(
    genre: String? = null,
    subgenre: String? = null,
    country: String? = null,
    mediaType: String? = null
): Int {
    val params = mutableListOf<String>()
    genre?.let { params.add("genre=$it") }
    subgenre?.let { params.add("subgenre=$it") }
    country?.let { params.add("country=$it") }
    mediaType?.let { params.add("mediaType=$it") }
    
    val queryString = if (params.isNotEmpty()) "?" + params.joinToString("&") else ""
    val response = window.fetch("$API_BASE_URL/movies/random/count$queryString").await()
    
    val json = response.text().await()
    val result: CountResponse = Json.decodeFromString(json)
    return result.count
}
