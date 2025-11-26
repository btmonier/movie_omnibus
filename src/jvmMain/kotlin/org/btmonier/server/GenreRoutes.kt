package org.btmonier.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.btmonier.database.GenreDao

@Serializable
data class GenreRequest(
    val name: String
)

@Serializable
data class GenreResponse(
    val id: Int,
    val name: String
)

@Serializable
data class SubgenreRequest(
    val name: String
)

@Serializable
data class SubgenreResponse(
    val id: Int,
    val name: String
)

/**
 * Configure genre and subgenre management API routes.
 */
fun Route.genreRoutes(dao: GenreDao) {

    route("/api/genres") {

        // GET /api/genres - Get all genres
        get {
            val genres = dao.getAllGenres()
            val response = genres.map { GenreResponse(it.id, it.name) }
            call.respond(HttpStatusCode.OK, response)
        }

        // GET /api/genres/{id} - Get a specific genre by ID
        get("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid genre ID"))
                return@get
            }

            val genre = dao.getGenreById(id)
            if (genre != null) {
                call.respond(HttpStatusCode.OK, GenreResponse(genre.id, genre.name))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Genre not found"))
            }
        }

        // POST /api/genres - Create a new genre
        post {
            try {
                val request = call.receive<GenreRequest>()

                if (request.name.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Genre name is required"))
                    return@post
                }

                val genreId = dao.createGenre(request.name)
                if (genreId != null) {
                    val genre = dao.getGenreById(genreId)
                    if (genre != null) {
                        call.respond(HttpStatusCode.Created, GenreResponse(genre.id, genre.name))
                    } else {
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to create genre"))
                    }
                } else {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to "Genre already exists"))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
            }
        }

        // PUT /api/genres/{id} - Update a genre name
        put("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid genre ID"))
                return@put
            }

            try {
                val request = call.receive<GenreRequest>()

                if (request.name.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Genre name is required"))
                    return@put
                }

                val success = dao.updateGenre(id, request.name)
                if (success) {
                    val genre = dao.getGenreById(id)
                    if (genre != null) {
                        call.respond(HttpStatusCode.OK, GenreResponse(genre.id, genre.name))
                    } else {
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to update genre"))
                    }
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Genre not found"))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
            }
        }

        // DELETE /api/genres/{id} - Delete a genre
        delete("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid genre ID"))
                return@delete
            }

            val success = dao.deleteGenre(id)
            if (success) {
                call.respond(HttpStatusCode.OK, mapOf("message" to "Genre deleted successfully"))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Genre not found"))
            }
        }
    }

    route("/api/subgenres") {

        // GET /api/subgenres - Get all subgenres
        get {
            val subgenres = dao.getAllSubgenres()
            val response = subgenres.map { SubgenreResponse(it.id, it.name) }
            call.respond(HttpStatusCode.OK, response)
        }

        // GET /api/subgenres/{id} - Get a specific subgenre by ID
        get("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid subgenre ID"))
                return@get
            }

            val subgenre = dao.getSubgenreById(id)
            if (subgenre != null) {
                call.respond(HttpStatusCode.OK, SubgenreResponse(subgenre.id, subgenre.name))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Subgenre not found"))
            }
        }

        // POST /api/subgenres - Create a new subgenre
        post {
            try {
                val request = call.receive<SubgenreRequest>()

                if (request.name.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Subgenre name is required"))
                    return@post
                }

                val subgenreId = dao.createSubgenre(request.name)
                if (subgenreId != null) {
                    val subgenre = dao.getSubgenreById(subgenreId)
                    if (subgenre != null) {
                        call.respond(HttpStatusCode.Created, SubgenreResponse(subgenre.id, subgenre.name))
                    } else {
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to create subgenre"))
                    }
                } else {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to "Subgenre already exists"))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
            }
        }

        // PUT /api/subgenres/{id} - Update a subgenre name
        put("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid subgenre ID"))
                return@put
            }

            try {
                val request = call.receive<SubgenreRequest>()

                if (request.name.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Subgenre name is required"))
                    return@put
                }

                val success = dao.updateSubgenre(id, request.name)
                if (success) {
                    val subgenre = dao.getSubgenreById(id)
                    if (subgenre != null) {
                        call.respond(HttpStatusCode.OK, SubgenreResponse(subgenre.id, subgenre.name))
                    } else {
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to update subgenre"))
                    }
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Subgenre not found"))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
            }
        }

        // DELETE /api/subgenres/{id} - Delete a subgenre
        delete("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid subgenre ID"))
                return@delete
            }

            val success = dao.deleteSubgenre(id)
            if (success) {
                call.respond(HttpStatusCode.OK, mapOf("message" to "Subgenre deleted successfully"))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Subgenre not found"))
            }
        }
    }
}
