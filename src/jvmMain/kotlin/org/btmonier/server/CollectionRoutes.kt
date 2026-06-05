package org.btmonier.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.btmonier.database.CollectionDao

@Serializable
data class CollectionRequest(
    val name: String,
    val description: String? = null
)

@Serializable
data class CollectionResponse(
    val id: Int,
    val name: String,
    val description: String? = null
)

/**
 * Configure collection management API routes.
 */
fun Route.collectionRoutes(dao: CollectionDao) {

    route("/api/collections") {

        // GET /api/collections - Get all collections
        get {
            val collections = dao.getAllCollections()
            val response = collections.map { CollectionResponse(it.id, it.name, it.description) }
            call.respond(HttpStatusCode.OK, response)
        }

        // GET /api/collections/{id} - Get a specific collection by ID
        get("/{id}") {
            val id = call.parameters["id"]?.toIntOrNull()
            if (id == null) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid collection ID"))
                return@get
            }

            val collection = dao.getCollectionById(id)
            if (collection != null) {
                call.respond(HttpStatusCode.OK, CollectionResponse(collection.id, collection.name, collection.description))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Collection not found"))
            }
        }

        // POST /api/collections - Create a new collection
        post {
            try {
                val request = call.receive<CollectionRequest>()

                if (request.name.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Collection name is required"))
                    return@post
                }

                val collectionId = dao.createCollection(request.name, request.description?.takeIf { it.isNotBlank() })
                if (collectionId != null) {
                    val collection = dao.getCollectionById(collectionId)
                    if (collection != null) {
                        call.respond(HttpStatusCode.Created, CollectionResponse(collection.id, collection.name, collection.description))
                    } else {
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to create collection"))
                    }
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
                val request = call.receive<CollectionRequest>()

                if (request.name.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Collection name is required"))
                    return@put
                }

                val success = dao.updateCollection(id, request.name, request.description?.takeIf { it.isNotBlank() })
                if (success) {
                    val collection = dao.getCollectionById(id)
                    if (collection != null) {
                        call.respond(HttpStatusCode.OK, CollectionResponse(collection.id, collection.name, collection.description))
                    } else {
                        call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to update collection"))
                    }
                } else {
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

            val success = dao.deleteCollection(id)
            if (success) {
                call.respond(HttpStatusCode.OK, mapOf("message" to "Collection deleted successfully"))
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Collection not found"))
            }
        }
    }
}
