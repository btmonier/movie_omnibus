package org.btmonier.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.btmonier.WatchedEntry
import org.btmonier.database.WatchedDao

/**
 * REST API routes for watched entries.
 */
fun Route.watchedRoutes() {
    val watchedDao = WatchedDao()

    route("/api") {
        // Get all watched entries for a movie
        get("/movies/{id}/watched") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid movie ID")
                return@get
            }

            val watchedEntries = watchedDao.getWatchedEntriesForMovie(id)
            call.respond(watchedEntries)
        }

        // Create a new watched entry
        post("/movies/{id}/watched") {
            val movieId = call.parameters["id"]?.toIntOrNull()
            if (movieId == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid movie ID")
                return@post
            }

            val watchedEntry = call.receive<WatchedEntry>()
            val watchedId = watchedDao.createWatchedEntry(movieId, watchedEntry)
            val createdEntry = watchedDao.getWatchedEntryById(watchedId)

            if (createdEntry != null) {
                call.respond(HttpStatusCode.Created, createdEntry)
            } else {
                call.respond(HttpStatusCode.InternalServerError, "Failed to create watched entry")
            }
        }

        // Update a watched entry
        put("/watched/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid watched entry ID")
                return@put
            }

            val watchedEntry = call.receive<WatchedEntry>()
            val success = watchedDao.updateWatchedEntry(id, watchedEntry)

            if (success) {
                val updatedEntry = watchedDao.getWatchedEntryById(id)
                if (updatedEntry != null) {
                    call.respond(updatedEntry)
                } else {
                    call.respond(HttpStatusCode.NotFound, "Watched entry not found")
                }
            } else {
                call.respond(HttpStatusCode.NotFound, "Watched entry not found")
            }
        }

        // Delete a watched entry
        delete("/watched/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid watched entry ID")
                return@delete
            }

            val success = watchedDao.deleteWatchedEntry(id)
            if (success) {
                call.respond(HttpStatusCode.OK, mapOf("success" to true))
            } else {
                call.respond(HttpStatusCode.NotFound, "Watched entry not found")
            }
        }

        // Get average rating for a movie
        get("/movies/{id}/rating/average") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid movie ID")
                return@get
            }

            val avgRating = watchedDao.getAverageRating(id)
            call.respond(mapOf("averageRating" to avgRating))
        }

        // Get watch count for a movie
        get("/movies/{id}/watch-count") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid movie ID")
                return@get
            }

            val watchCount = watchedDao.getWatchCount(id)
            call.respond(mapOf("watchCount" to watchCount))
        }
    }
}
