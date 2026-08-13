package org.btmonier.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.btmonier.database.CategoryDao
import org.btmonier.database.CategoryEntry
import org.btmonier.database.CategoryType
import org.btmonier.database.RenameOutcome

@Serializable
data class CollectionResponse(
    val id: Int,
    val name: String,
    val description: String? = null
)

private fun CategoryEntry.toCollectionResponse() = CollectionResponse(id, name, description)

/**
 * Configure collection routes used by the movie form selector. Collections are
 * a category type like any other, but they also carry a description.
 */
fun Route.collectionRoutes(dao: CategoryDao) {

    route("/api/collections") {

        // GET /api/collections - Get all collections
        get {
            call.respond(HttpStatusCode.OK, dao.list(CategoryType.COLLECTION).map { it.toCollectionResponse() })
        }

        // GET /api/collections/{id} - Get a specific collection by ID
        get("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid collection ID"))
                return@get
            }

            val collection = dao.get(CategoryType.COLLECTION, id)
            if (collection != null) {
                call.respond(HttpStatusCode.OK, collection.toCollectionResponse())
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Collection not found"))
            }
        }

        // POST /api/collections - Create a new collection
        post {
            try {
                val request = call.receive<CategoryRequest>()

                if (request.name.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Collection name is required"))
                    return@post
                }

                val created = dao.create(
                    CategoryType.COLLECTION,
                    request.name,
                    request.description?.takeIf { it.isNotBlank() }
                )
                if (created != null) {
                    call.respond(HttpStatusCode.Created, created.toCollectionResponse())
                } else {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to "Collection already exists"))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
            }
        }

        // PUT /api/collections/{id} - Update a collection's name and description
        put("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid collection ID"))
                return@put
            }

            try {
                val request = call.receive<CategoryRequest>()

                if (request.name.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Collection name is required"))
                    return@put
                }

                val outcome = dao.rename(
                    type = CategoryType.COLLECTION,
                    id = id,
                    newName = request.name,
                    description = request.description?.takeIf { it.isNotBlank() }
                )

                when (outcome) {
                    is RenameOutcome.Renamed ->
                        call.respond(HttpStatusCode.OK, outcome.entry.toCollectionResponse())

                    is RenameOutcome.Merged ->
                        call.respond(HttpStatusCode.OK, outcome.entry.toCollectionResponse())

                    is RenameOutcome.NameTaken -> call.respond(
                        HttpStatusCode.Conflict,
                        mapOf("error" to "Collection \"${outcome.existing.name}\" already exists")
                    )

                    RenameOutcome.NotFound ->
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Collection not found"))
                }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
            }
        }

        // DELETE /api/collections/{id} - Delete a collection
        delete("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid collection ID"))
                return@delete
            }

            if (dao.delete(CategoryType.COLLECTION, id)) {
                call.respond(HttpStatusCode.OK, mapOf("message" to "Collection deleted successfully"))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Collection not found"))
            }
        }
    }
}
