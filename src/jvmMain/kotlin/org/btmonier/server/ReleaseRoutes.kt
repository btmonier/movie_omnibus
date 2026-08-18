package org.btmonier.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.btmonier.Release
import org.btmonier.ReleaseFilm
import org.btmonier.ReleaseSummary
import org.btmonier.database.MovieDao
import org.btmonier.database.ReleaseDao
import org.btmonier.database.ReleaseFilters
import org.btmonier.database.ReleaseSortField

/**
 * Paginated response for the release list endpoint.
 */
@Serializable
data class PaginatedReleasesResponse(
    val releases: List<ReleaseSummary>,
    val totalCount: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int
)

/**
 * Paginated response for the films on one release. Large box sets are read a
 * page at a time rather than all at once.
 */
@Serializable
data class PaginatedReleaseFilmsResponse(
    val films: List<ReleaseFilm>,
    val totalCount: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int
)

/**
 * Request body for putting a film on a release.
 */
@Serializable
data class LinkMovieRequest(
    val movieId: Int,
    val entryLetter: String? = null,
    val alternateTitle: String? = null
)

private fun totalPages(totalCount: Int, pageSize: Int) =
    if (totalCount == 0) 1 else (totalCount + pageSize - 1) / pageSize

/**
 * Configure routes for physical releases - the shared unit that one or more
 * films sit on.
 */
fun Route.releaseRoutes(releaseDao: ReleaseDao, movieDao: MovieDao) {

    // GET /api/releases - Browse releases, paginated and filtered
    get("/api/releases") {
        val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull()?.coerceIn(1, 500) ?: 24

        val filters = ReleaseFilters(
            search = call.request.queryParameters["search"],
            mediaType = call.request.queryParameters["mediaType"],
            distributor = call.request.queryParameters["distributor"],
            location = call.request.queryParameters["location"],
            collectionsOnly = call.request.queryParameters["collectionsOnly"] == "true",
            emptyOnly = call.request.queryParameters["emptyOnly"] == "true"
        )

        val sortField = ReleaseSortField.fromSlug(call.request.queryParameters["sortField"])
        val ascending = !call.request.queryParameters["sortDirection"].equals("desc", ignoreCase = true)

        val (releases, totalCount) = releaseDao.listReleases(page, pageSize, filters, sortField, ascending)

        call.respond(
            HttpStatusCode.OK,
            PaginatedReleasesResponse(
                releases = releases,
                totalCount = totalCount,
                page = page,
                pageSize = pageSize,
                totalPages = totalPages(totalCount, pageSize)
            )
        )
    }

    // GET /api/releases/search?q= - Typeahead for the "link an existing release" picker
    get("/api/releases/search") {
        val query = call.request.queryParameters["q"]
        if (query.isNullOrBlank()) {
            call.respond(HttpStatusCode.OK, emptyList<ReleaseSummary>())
            return@get
        }

        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 20
        call.respond(HttpStatusCode.OK, releaseDao.searchReleases(query, limit))
    }

    // GET /api/releases/by-bluray-url?url= - Find a release already recorded under
    // a blu-ray.com URL, so it can be linked instead of entered a second time
    get("/api/releases/by-bluray-url") {
        val url = call.request.queryParameters["url"]
        if (url.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "url is required"))
            return@get
        }

        val existing = releaseDao.findByBluRayUrl(url)
        if (existing == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "No release recorded under that URL"))
        } else {
            call.respond(HttpStatusCode.OK, existing)
        }
    }

    // POST /api/releases - Create a release, optionally with its films
    post("/api/releases") {
        try {
            val release = call.receive<Release>()
            val id = releaseDao.createRelease(release)
            val created = releaseDao.getRelease(id)

            if (created != null) {
                call.respond(HttpStatusCode.Created, created)
            } else {
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to create release"))
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
        }
    }

    // GET /api/releases/{id} - One release with its films
    get("/api/releases/{id}") {
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid release ID"))
            return@get
        }

        val release = releaseDao.getRelease(id)
        if (release == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Release not found"))
        } else {
            call.respond(HttpStatusCode.OK, release)
        }
    }

    // PUT /api/releases/{id} - Update the shared fields. Affects every film on it.
    put("/api/releases/{id}") {
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid release ID"))
            return@put
        }

        try {
            val release = call.receive<Release>()
            if (releaseDao.updateRelease(id, release)) {
                call.respond(HttpStatusCode.OK, releaseDao.getRelease(id) ?: release)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Release not found"))
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
        }
    }

    // DELETE /api/releases/{id} - Delete a release and take every film off it
    delete("/api/releases/{id}") {
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid release ID"))
            return@delete
        }

        if (releaseDao.deleteRelease(id)) {
            call.respond(HttpStatusCode.OK, mapOf("message" to "Release deleted successfully"))
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Release not found"))
        }
    }

    // GET /api/releases/{id}/movies - Paginated film list, searchable by title
    get("/api/releases/{id}/movies") {
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid release ID"))
            return@get
        }

        if (releaseDao.getRelease(id, includeFilms = false) == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Release not found"))
            return@get
        }

        val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull()?.coerceIn(1, 500) ?: 24
        val search = call.request.queryParameters["search"]

        val (films, totalCount) = releaseDao.getFilms(id, page, pageSize, search)

        call.respond(
            HttpStatusCode.OK,
            PaginatedReleaseFilmsResponse(
                films = films,
                totalCount = totalCount,
                page = page,
                pageSize = pageSize,
                totalPages = totalPages(totalCount, pageSize)
            )
        )
    }

    // POST /api/releases/{id}/movies - Put a film on the release
    post("/api/releases/{id}/movies") {
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid release ID"))
            return@post
        }

        try {
            val request = call.receive<LinkMovieRequest>()

            if (movieDao.getMovieById(request.movieId) == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Movie not found"))
                return@post
            }

            val linkId = releaseDao.linkMovie(id, request.movieId, request.entryLetter, request.alternateTitle)
            if (linkId == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Release not found"))
            } else {
                call.respond(HttpStatusCode.Created, releaseDao.getRelease(id) ?: return@post)
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
        }
    }

    // DELETE /api/releases/{id}/movies/{movieId} - Take a film off the release
    delete("/api/releases/{id}/movies/{movieId}") {
        val id = call.parameters["id"]?.toIntOrNull()
        val movieId = call.parameters["movieId"]?.toIntOrNull()
        if (id == null || movieId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid release or movie ID"))
            return@delete
        }

        if (releaseDao.unlinkMovie(id, movieId)) {
            call.respond(HttpStatusCode.OK, mapOf("message" to "Movie removed from release"))
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "That movie is not on this release"))
        }
    }
}
