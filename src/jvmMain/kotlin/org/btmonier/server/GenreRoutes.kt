package org.btmonier.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.btmonier.database.CategoryDao
import org.btmonier.database.CategoryType
import org.btmonier.database.RenameOutcome

/**
 * A category entry as the form selectors consume it: just an id and a name.
 */
@Serializable
data class NamedCategoryResponse(
    val id: Int,
    val name: String
)

/**
 * Configure the name-only reference list routes used by the movie and physical
 * media form selectors. These are thin wrappers over the shared category API;
 * `/api/categories/{type}` additionally reports usage counts and can merge.
 */
fun Route.genreRoutes(dao: CategoryDao) {
    simpleCategoryRoutes(dao, CategoryType.GENRE, "Genre")
    simpleCategoryRoutes(dao, CategoryType.SUBGENRE, "Subgenre")
    simpleCategoryRoutes(dao, CategoryType.DISTRIBUTOR, "Distributor")
}

/**
 * CRUD over one category type, responding with plain {id, name} objects.
 */
private fun Route.simpleCategoryRoutes(dao: CategoryDao, type: CategoryType, label: String) {

    route("/api/${type.slug}") {

        // GET - Get all entries
        get {
            call.respond(HttpStatusCode.OK, dao.list(type).map { NamedCategoryResponse(it.id, it.name) })
        }

        // GET /{id} - Get a specific entry by ID
        get("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid $label ID"))
                return@get
            }

            val entry = dao.get(type, id)
            if (entry != null) {
                call.respond(HttpStatusCode.OK, NamedCategoryResponse(entry.id, entry.name))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "$label not found"))
            }
        }

        // POST - Create a new entry
        post {
            try {
                val request = call.receive<CategoryRequest>()

                if (request.name.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "$label name is required"))
                    return@post
                }

                val created = dao.create(type, request.name)
                if (created != null) {
                    call.respond(HttpStatusCode.Created, NamedCategoryResponse(created.id, created.name))
                } else {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to "$label already exists"))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
            }
        }

        // PUT /{id} - Rename an entry, which updates every movie that uses it
        put("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid $label ID"))
                return@put
            }

            try {
                val request = call.receive<CategoryRequest>()

                if (request.name.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "$label name is required"))
                    return@put
                }

                when (val outcome = dao.rename(type, id, request.name)) {
                    is RenameOutcome.Renamed ->
                        call.respond(HttpStatusCode.OK, NamedCategoryResponse(outcome.entry.id, outcome.entry.name))

                    is RenameOutcome.Merged ->
                        call.respond(HttpStatusCode.OK, NamedCategoryResponse(outcome.entry.id, outcome.entry.name))

                    is RenameOutcome.NameTaken -> call.respond(
                        HttpStatusCode.Conflict,
                        mapOf("error" to "$label \"${outcome.existing.name}\" already exists")
                    )

                    RenameOutcome.NotFound ->
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "$label not found"))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
            }
        }

        // DELETE /{id} - Delete an entry
        delete("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid $label ID"))
                return@delete
            }

            if (dao.delete(type, id)) {
                call.respond(HttpStatusCode.OK, mapOf("message" to "$label deleted successfully"))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "$label not found"))
            }
        }
    }
}
