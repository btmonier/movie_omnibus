package org.btmonier.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.btmonier.BluRayComUtils
import org.btmonier.MediaType
import org.btmonier.PhysicalMedia
import org.btmonier.database.MovieDao
import org.btmonier.database.PhysicalMediaDao
import org.jsoup.Jsoup

/**
 * Request body for scraping a blu-ray.com release URL.
 */
@Serializable
data class BluRayScrapeRequest(val url: String)

/**
 * Response for the physical media scrape endpoint. Returns a preview
 * [PhysicalMedia] (no database id) that the client uses to prefill the form.
 */
@Serializable
data class PhysicalMediaScrapeResponse(
    val success: Boolean,
    val physicalMedia: PhysicalMedia? = null,
    val error: String? = null
)

/**
 * Configure physical media-related API routes.
 */
fun Route.physicalMediaRoutes(movieDao: MovieDao, physicalMediaDao: PhysicalMediaDao) {

    // POST /api/physical-media/scrape - Scrape physical media details from a
    // blu-ray.com release URL. This is a preview only; nothing is persisted.
    post("/api/physical-media/scrape") {
        try {
            val request = call.receive<BluRayScrapeRequest>()
            val url = request.url.trim()

            if (url.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, PhysicalMediaScrapeResponse(
                    success = false,
                    error = "URL is required"
                ))
                return@post
            }

            if (!BluRayComUtils.isBluRayComUrl(url)) {
                call.respond(HttpStatusCode.BadRequest, PhysicalMediaScrapeResponse(
                    success = false,
                    error = "URL must be a valid blu-ray.com release URL (e.g., https://www.blu-ray.com/movies/Movie-Title/123456/ or https://www.blu-ray.com/dvd/Movie-Title-DVD/123456/)"
                ))
                return@post
            }

            try {
                val doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(15000)
                    .get()

                val physicalMedia = BluRayComUtils.extractPhysicalMedia(doc, url)

                call.respond(HttpStatusCode.OK, PhysicalMediaScrapeResponse(
                    success = true,
                    physicalMedia = physicalMedia
                ))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.OK, PhysicalMediaScrapeResponse(
                    success = false,
                    error = "Failed to scrape physical media data: ${e.message}"
                ))
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, PhysicalMediaScrapeResponse(
                success = false,
                error = "Invalid request: ${e.message}"
            ))
        }
    }

    // GET /api/movies/{id}/physical-media - Get all physical media for a movie
    get("/api/movies/{id}/physical-media") {
        val movieId = call.parameters["id"]?.toIntOrNull()
        if (movieId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid movie ID"))
            return@get
        }

        // Check if movie exists
        val movie = movieDao.getMovieById(movieId)
        if (movie == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Movie not found"))
            return@get
        }

        val physicalMedia = physicalMediaDao.getPhysicalMediaForMovie(movieId)
        call.respond(HttpStatusCode.OK, physicalMedia)
    }

    // POST /api/movies/{id}/physical-media - Create new physical media for a movie
    post("/api/movies/{id}/physical-media") {
        val movieId = call.parameters["id"]?.toIntOrNull()
        if (movieId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid movie ID"))
            return@post
        }

        // Check if movie exists
        val movie = movieDao.getMovieById(movieId)
        if (movie == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Movie not found"))
            return@post
        }

        try {
            val physicalMedia = call.receive<PhysicalMedia>()

            val mediaId = physicalMediaDao.createPhysicalMedia(movieId, physicalMedia)
            val createdMedia = physicalMediaDao.getPhysicalMediaById(mediaId)

            if (createdMedia != null) {
                call.respond(HttpStatusCode.Created, createdMedia)
            } else {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to create physical media"))
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
        }
    }

    // PUT /api/physical-media/{id} - Update a physical media entry
    put("/api/physical-media/{id}") {
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid physical media ID"))
            return@put
        }

        try {
            val physicalMedia = call.receive<PhysicalMedia>()

            val success = physicalMediaDao.updatePhysicalMedia(id, physicalMedia)
            if (success) {
                val updatedMedia = physicalMediaDao.getPhysicalMediaById(id)
                call.respond(HttpStatusCode.OK, updatedMedia ?: physicalMedia)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Physical media not found"))
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
        }
    }

    // DELETE /api/physical-media/{id} - Delete a physical media entry
    delete("/api/physical-media/{id}") {
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid physical media ID"))
            return@delete
        }

        val success = physicalMediaDao.deletePhysicalMedia(id)
        if (success) {
            call.respond(HttpStatusCode.OK, mapOf("message" to "Physical media deleted successfully"))
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Physical media not found"))
        }
    }

    // GET /api/movies/by-media-type/{type} - Filter movies by physical media type
    get("/api/movies/by-media-type/{type}") {
        val typeString = call.parameters["type"]
        if (typeString.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Media type is required"))
            return@get
        }

        try {
            val mediaType = when (typeString.uppercase()) {
                "VHS" -> MediaType.VHS
                "DVD" -> MediaType.DVD
                "BLURAY", "BLU-RAY" -> MediaType.BLURAY
                "4K", "FOURK" -> MediaType.FOURK
                "DIGITAL" -> MediaType.DIGITAL
                else -> {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid media type. Valid types: VHS, DVD, Blu-ray, 4K, Digital"))
                    return@get
                }
            }

            val movieIds = physicalMediaDao.getMovieIdsByMediaType(mediaType)
            val movies = movieIds.mapNotNull { movieDao.getMovieById(it) }
            call.respond(HttpStatusCode.OK, movies)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Error processing request: ${e.message}"))
        }
    }
}
