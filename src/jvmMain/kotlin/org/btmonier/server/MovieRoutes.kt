package org.btmonier.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.btmonier.MovieMetadata
import org.btmonier.ScraperUtils
import org.btmonier.database.MovieDao
import org.jsoup.Jsoup

/**
 * Request body for scraping a Letterboxd URL.
 */
@Serializable
data class ScrapeRequest(val url: String)

/**
 * Response for scrape endpoint.
 */
@Serializable
data class ScrapeResponse(
    val success: Boolean,
    val movie: MovieMetadata? = null,
    val exists: Boolean = false,
    val existingMovieId: Int? = null,
    val error: String? = null
)

/**
 * Paginated response for movie list endpoint.
 */
@Serializable
data class PaginatedMoviesResponse(
    val movies: List<MovieMetadata>,
    val totalCount: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int
)

/**
 * Configure movie-related API routes.
 */
fun Route.movieRoutes(dao: MovieDao) {

    route("/api/movies") {

        // POST /api/movies/scrape - Scrape movie data from Letterboxd URL
        post("/scrape") {
            try {
                val request = call.receive<ScrapeRequest>()
                val url = request.url.trim()

                // Validate URL format
                if (url.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ScrapeResponse(
                        success = false,
                        error = "URL is required"
                    ))
                    return@post
                }

                if (!url.startsWith("https://letterboxd.com/film/")) {
                    call.respond(HttpStatusCode.BadRequest, ScrapeResponse(
                        success = false,
                        error = "URL must be a valid Letterboxd film URL (e.g., https://letterboxd.com/film/movie-name/)"
                    ))
                    return@post
                }

                // Check if movie already exists
                val existingMovie = dao.getMovieByUrl(url)
                if (existingMovie != null) {
                    call.respond(HttpStatusCode.OK, ScrapeResponse(
                        success = false,
                        exists = true,
                        existingMovieId = existingMovie.id,
                        error = "Movie \"${existingMovie.title}\" already exists in your collection"
                    ))
                    return@post
                }

                // Scrape the movie data from Letterboxd
                try {
                    val doc = Jsoup.connect(url)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                        .timeout(15000)
                        .get()

                    val scrapedMetadata = ScraperUtils.extractMetadataWithTitle(doc, url)

                    call.respond(HttpStatusCode.OK, ScrapeResponse(
                        success = true,
                        movie = scrapedMetadata
                    ))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.OK, ScrapeResponse(
                        success = false,
                        error = "Failed to scrape movie data: ${e.message}"
                    ))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ScrapeResponse(
                    success = false,
                    error = "Invalid request: ${e.message}"
                ))
            }
        }

        // GET /api/movies - Get paginated movies with optional filters
        get {
            val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
            val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull() ?: 25
            val search = call.request.queryParameters["search"]
            val genre = call.request.queryParameters["genre"]
            val subgenre = call.request.queryParameters["subgenre"]
            val country = call.request.queryParameters["country"]
            val mediaType = call.request.queryParameters["mediaType"]
            val sortField = call.request.queryParameters["sortField"]
            val sortDirection = call.request.queryParameters["sortDirection"]

            val (movies, totalCount) = dao.getMoviesPaginated(
                page = page,
                pageSize = pageSize,
                search = search,
                genre = genre,
                subgenre = subgenre,
                country = country,
                mediaType = mediaType,
                sortField = sortField,
                sortDirection = sortDirection
            )

            val totalPages = if (totalCount == 0) 1 else (totalCount + pageSize - 1) / pageSize

            call.respond(HttpStatusCode.OK, PaginatedMoviesResponse(
                movies = movies,
                totalCount = totalCount,
                page = page,
                pageSize = pageSize,
                totalPages = totalPages
            ))
        }

        // GET /api/movies/{id} - Get a specific movie by ID
        get("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid movie ID"))
                return@get
            }

            val movie = dao.getMovieById(id)
            if (movie != null) {
                call.respond(HttpStatusCode.OK, movie)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Movie not found"))
            }
        }

        // GET /api/movies/search?q={query} - Search movies by title
        get("/search") {
            val query = call.request.queryParameters["q"]
            if (query.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Search query is required"))
                return@get
            }

            val movies = dao.searchMoviesByTitle(query)
            call.respond(HttpStatusCode.OK, movies)
        }

        // GET /api/movies/by-genre/{genre} - Filter movies by genre
        get("/by-genre/{genre}") {
            val genre = call.parameters["genre"]
            if (genre.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Genre is required"))
                return@get
            }

            val movies = dao.getMoviesByGenre(genre)
            call.respond(HttpStatusCode.OK, movies)
        }

        // GET /api/movies/by-country/{country} - Filter movies by country
        get("/by-country/{country}") {
            val country = call.parameters["country"]
            if (country.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Country is required"))
                return@get
            }

            val movies = dao.getMoviesByCountry(country)
            call.respond(HttpStatusCode.OK, movies)
        }

        // GET /api/movies/genres - Get all unique genres (for autocomplete)
        get("/genres") {
            val genres = dao.getAllGenres()
            call.respond(HttpStatusCode.OK, genres)
        }

        // GET /api/movies/subgenres - Get all unique subgenres (for autocomplete)
        get("/subgenres") {
            val subgenres = dao.getAllSubgenres()
            call.respond(HttpStatusCode.OK, subgenres)
        }

        // GET /api/movies/countries - Get all unique countries (for filtering)
        get("/countries") {
            val countries = dao.getAllCountries()
            call.respond(HttpStatusCode.OK, countries)
        }

        // GET /api/movies/media-types - Get all unique media types (for filtering)
        get("/media-types") {
            val mediaTypes = dao.getAllMediaTypes()
            call.respond(HttpStatusCode.OK, mediaTypes)
        }

        // GET /api/movies/random - Get a random unwatched movie with optional filters
        // Supports multiple values per filter (OR logic) via repeated query params
        get("/random") {
            val genres = call.request.queryParameters.getAll("genre") ?: emptyList()
            val subgenres = call.request.queryParameters.getAll("subgenre") ?: emptyList()
            val countries = call.request.queryParameters.getAll("country") ?: emptyList()
            val mediaTypes = call.request.queryParameters.getAll("mediaType") ?: emptyList()

            val movie = dao.getRandomUnwatchedMovie(
                genres = genres,
                subgenres = subgenres,
                countries = countries,
                mediaTypes = mediaTypes
            )

            if (movie != null) {
                call.respond(HttpStatusCode.OK, movie)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "No unwatched movies found matching the criteria"))
            }
        }

        // GET /api/movies/random/count - Count unwatched movies with optional filters
        // Supports multiple values per filter (OR logic) via repeated query params
        get("/random/count") {
            val genres = call.request.queryParameters.getAll("genre") ?: emptyList()
            val subgenres = call.request.queryParameters.getAll("subgenre") ?: emptyList()
            val countries = call.request.queryParameters.getAll("country") ?: emptyList()
            val mediaTypes = call.request.queryParameters.getAll("mediaType") ?: emptyList()

            val count = dao.countUnwatchedMovies(
                genres = genres,
                subgenres = subgenres,
                countries = countries,
                mediaTypes = mediaTypes
            )

            call.respond(HttpStatusCode.OK, mapOf("count" to count))
        }

        // POST /api/movies - Create a new movie
        post {
            try {
                val movie = call.receive<MovieMetadata>()

                // Validate required fields
                if (movie.url.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "URL is required"))
                    return@post
                }

                // Check if movie already exists
                if (dao.movieExists(movie.url)) {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to "Movie with this URL already exists"))
                    return@post
                }

                val movieId = dao.createMovie(movie)
                val createdMovie = dao.getMovieById(movieId)

                if (createdMovie != null) {
                    call.respond(HttpStatusCode.Created, createdMovie)
                } else {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to create movie"))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
            }
        }

        // PUT /api/movies/{id} - Update an existing movie
        put("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid movie ID"))
                return@put
            }

            try {
                val movie = call.receive<MovieMetadata>()

                // Validate required fields
                if (movie.url.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "URL is required"))
                    return@put
                }

                val success = dao.updateMovie(id, movie)
                if (success) {
                    val updatedMovie = dao.getMovieById(id)
                    call.respond(HttpStatusCode.OK, updatedMovie ?: movie)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Movie not found"))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
            }
        }

        // DELETE /api/movies/{id} - Delete a movie
        delete("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid movie ID"))
                return@delete
            }

            val success = dao.deleteMovie(id)
            if (success) {
                call.respond(HttpStatusCode.OK, mapOf("message" to "Movie deleted successfully"))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Movie not found"))
            }
        }
    }
}
