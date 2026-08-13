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
data class CategoryEntryResponse(
    val id: Int,
    val name: String,
    val description: String? = null,
    val usageCount: Int = 0
)

@Serializable
data class CategoryTypeResponse(
    val type: String,
    val label: String,
    val entryCount: Int
)

@Serializable
data class CategoryRequest(
    val name: String,
    val description: String? = null
)

@Serializable
data class CategoryMergeRequest(
    val sourceIds: List<Int>,
    val targetId: Int
)

/**
 * Result of creating, renaming, or merging an entry. [merged] tells the client
 * that the edit collapsed two entries into the one returned here.
 */
@Serializable
data class CategorySaveResponse(
    val entry: CategoryEntryResponse,
    val merged: Boolean = false,
    val mergedName: String? = null,
    val moviesUpdated: Int = 0
)

@Serializable
data class CategoryConflictResponse(
    val error: String,
    val existing: CategoryEntryResponse
)

private fun CategoryEntry.toResponse() = CategoryEntryResponse(id, name, description, usageCount)

/**
 * Configure management API routes shared by every category type (genres,
 * subgenres, collections, distributors, themes, countries).
 */
fun Route.categoryRoutes(dao: CategoryDao) {

    route("/api/categories") {

        // GET /api/categories - List the category types and how many entries each has
        get {
            val types = CategoryType.entries.map { type ->
                CategoryTypeResponse(type.slug, type.label, dao.list(type).size)
            }
            call.respond(HttpStatusCode.OK, types)
        }

        route("/{type}") {

            // GET /api/categories/{type} - All entries with usage counts
            get {
                val type = call.categoryType() ?: return@get call.respondUnknownType()
                call.respond(HttpStatusCode.OK, dao.list(type).map { it.toResponse() })
            }

            // POST /api/categories/{type} - Create a new entry
            post {
                val type = call.categoryType() ?: return@post call.respondUnknownType()

                val request = try {
                    call.receive<CategoryRequest>()
                } catch (e: Exception) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request body: ${e.message}")
                    )
                }

                if (request.name.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Name is required"))
                }

                val created = dao.create(type, request.name, request.description?.takeIf { it.isNotBlank() })
                if (created != null) {
                    call.respond(HttpStatusCode.Created, CategorySaveResponse(created.toResponse()))
                } else {
                    val existing = dao.findByName(type, request.name)
                    call.respond(
                        HttpStatusCode.Conflict,
                        CategoryConflictResponse(
                            error = "\"${request.name.trim()}\" already exists",
                            existing = (existing ?: CategoryEntry(0, request.name.trim())).toResponse()
                        )
                    )
                }
            }

            // POST /api/categories/{type}/merge - Fold entries into a single one
            post("/merge") {
                val type = call.categoryType() ?: return@post call.respondUnknownType()

                val request = try {
                    call.receive<CategoryMergeRequest>()
                } catch (e: Exception) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request body: ${e.message}")
                    )
                }

                if (request.sourceIds.isEmpty()) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "At least one entry to merge is required")
                    )
                }

                val moviesUpdated = dao.merge(type, request.sourceIds, request.targetId)
                if (moviesUpdated == null) {
                    return@post call.respond(HttpStatusCode.NotFound, mapOf("error" to "Target entry not found"))
                }

                val target = dao.get(type, request.targetId)!!
                call.respond(
                    HttpStatusCode.OK,
                    CategorySaveResponse(
                        entry = target.toResponse(),
                        merged = true,
                        moviesUpdated = moviesUpdated
                    )
                )
            }

            // PUT /api/categories/{type}/{id} - Rename an entry, updating every movie that uses it.
            // Pass ?allowMerge=true to fold it into an existing entry with the new name.
            put("/{id}") {
                val type = call.categoryType() ?: return@put call.respondUnknownType()
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid entry ID"))

                val request = try {
                    call.receive<CategoryRequest>()
                } catch (e: Exception) {
                    return@put call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Invalid request body: ${e.message}")
                    )
                }

                if (request.name.isBlank()) {
                    return@put call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Name is required"))
                }

                val allowMerge = call.request.queryParameters["allowMerge"]?.toBoolean() ?: false
                val outcome = dao.rename(
                    type = type,
                    id = id,
                    newName = request.name,
                    description = request.description?.takeIf { it.isNotBlank() },
                    allowMerge = allowMerge
                )

                when (outcome) {
                    is RenameOutcome.Renamed ->
                        call.respond(HttpStatusCode.OK, CategorySaveResponse(outcome.entry.toResponse()))

                    is RenameOutcome.Merged -> call.respond(
                        HttpStatusCode.OK,
                        CategorySaveResponse(
                            entry = outcome.entry.toResponse(),
                            merged = true,
                            mergedName = outcome.mergedName,
                            moviesUpdated = outcome.moviesUpdated
                        )
                    )

                    is RenameOutcome.NameTaken -> call.respond(
                        HttpStatusCode.Conflict,
                        CategoryConflictResponse(
                            error = "\"${outcome.existing.name}\" already exists",
                            existing = outcome.existing.toResponse()
                        )
                    )

                    RenameOutcome.NotFound ->
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "Entry not found"))
                }
            }

            // DELETE /api/categories/{type}/{id} - Delete an entry and its movie references
            delete("/{id}") {
                val type = call.categoryType() ?: return@delete call.respondUnknownType()
                val id = call.parameters["id"]?.toIntOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid entry ID"))

                if (dao.delete(type, id)) {
                    call.respond(HttpStatusCode.OK, mapOf("message" to "Entry deleted successfully"))
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Entry not found"))
                }
            }
        }
    }
}

private fun ApplicationCall.categoryType(): CategoryType? =
    CategoryType.fromSlug(parameters["type"].orEmpty())

private suspend fun ApplicationCall.respondUnknownType() = respond(
    HttpStatusCode.BadRequest,
    mapOf(
        "error" to "Unknown category type",
        "supported" to CategoryType.entries.joinToString(", ") { it.slug }
    )
)
