package org.btmonier.server

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.btmonier.AppSettings
import org.btmonier.BluRayComUtils
import org.btmonier.BluRayPrices
import org.btmonier.PriceObservation
import org.btmonier.PriceSource
import org.btmonier.ReleaseSummary
import org.btmonier.WishlistImportRequest
import org.btmonier.WishlistItem
import org.btmonier.WishlistPriceService
import org.btmonier.WishlistPriority
import org.btmonier.WishlistStatus
import org.btmonier.WishlistTransitionRequest
import org.btmonier.database.ReleaseDao
import org.btmonier.database.TransitionOutcome
import org.btmonier.database.WishlistDao
import org.btmonier.database.WishlistFilters
import org.btmonier.database.WishlistSortField

/**
 * Response for the wishlist list endpoint. Not paginated: a personal wishlist
 * is small enough to group and sort client-side in one go.
 */
@Serializable
data class WishlistListResponse(
    val items: List<WishlistItem>,
    val totalCount: Int,
    val mediaTypes: List<String> = emptyList(),
    val distributors: List<String> = emptyList(),
    val tags: List<String> = emptyList()
)

/**
 * Response for importing a blu-ray.com URL. When the URL is already on the
 * wishlist or already owned as a release, nothing is created and the existing
 * record is returned instead so the client can offer to open it.
 */
@Serializable
data class WishlistImportResponse(
    val success: Boolean,
    val item: WishlistItem? = null,
    val existingItem: WishlistItem? = null,
    val existingRelease: ReleaseSummary? = null,
    val prices: BluRayPrices? = null,
    val error: String? = null
)

@Serializable
data class WishlistMoviesRequest(val movieIds: List<Int>)

@Serializable
data class PriceRefreshResponse(
    val success: Boolean,
    val item: WishlistItem? = null,
    val prices: BluRayPrices? = null,
    val observationsAdded: Int = 0,
    val error: String? = null
)

@Serializable
data class BulkPriceRefreshResponse(
    val checked: Int,
    val failed: Int,
    val priceChanges: Int,
    val skipped: Int = 0,
    val errors: List<String> = emptyList()
)

/**
 * Wishlist routes: physical releases that are wanted, on order, or arriving.
 */
fun Route.wishlistRoutes(wishlistDao: WishlistDao, releaseDao: ReleaseDao, priceService: WishlistPriceService) {

    // GET /api/wishlist - Every item matching the filters, sorted
    get("/api/wishlist") {
        val params = call.request.queryParameters
        val filters = WishlistFilters(
            search = params["search"],
            status = params["status"]?.let { s -> WishlistStatus.entries.firstOrNull { it.name.equals(s, ignoreCase = true) } },
            mediaType = params["mediaType"],
            distributor = params["distributor"],
            tag = params["tag"],
            priority = params["priority"]?.let { p -> WishlistPriority.entries.firstOrNull { it.name.equals(p, ignoreCase = true) } },
            atTargetOnly = params["atTarget"] == "true",
            inStockOnly = params["inStock"] == "true"
        )
        val sortField = WishlistSortField.fromSlug(params["sortField"])
        val ascending = params["sortDirection"].equals("asc", ignoreCase = true)

        val items = wishlistDao.list(filters, sortField, ascending)
        val (mediaTypes, distributors, tags) = wishlistDao.filterOptions()
        call.respond(HttpStatusCode.OK, WishlistListResponse(items, items.size, mediaTypes, distributors, tags))
    }

    // GET /api/wishlist/summary - Counts and spend totals
    get("/api/wishlist/summary") {
        call.respond(HttpStatusCode.OK, wishlistDao.summary())
    }

    // POST /api/wishlist/import - Wishlist a blu-ray.com release in one step
    post("/api/wishlist/import") {
        val request = try {
            call.receive<WishlistImportRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, WishlistImportResponse(false, error = "Invalid request: ${e.message}"))
            return@post
        }

        val url = request.url.trim()
        if (!BluRayComUtils.isBluRayComUrl(url)) {
            call.respond(HttpStatusCode.BadRequest, WishlistImportResponse(
                false,
                error = "URL must be a blu-ray.com release URL (e.g. https://www.blu-ray.com/movies/Movie-Title/123456/)"
            ))
            return@post
        }

        wishlistDao.findByBluRayUrl(url)?.let { existing ->
            call.respond(HttpStatusCode.OK, WishlistImportResponse(false, existingItem = existing, error = "Already on the wishlist"))
            return@post
        }
        releaseDao.findByBluRayUrl(url)?.let { owned ->
            call.respond(HttpStatusCode.OK, WishlistImportResponse(false, existingRelease = owned, error = "Already in your collection"))
            return@post
        }

        val doc = try {
            priceService.fetchDocument(url)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.OK, WishlistImportResponse(false, error = "Failed to fetch blu-ray.com page: ${e.message}"))
            return@post
        }

        val scraped = BluRayComUtils.extractPhysicalMedia(doc, url)
        val prices = BluRayComUtils.extractPrices(doc)

        val id = wishlistDao.create(
            WishlistItem(
                mediaTypes = scraped.mediaTypes,
                title = scraped.title,
                distributor = scraped.distributor,
                releaseDate = scraped.releaseDate,
                blurayComUrl = url,
                images = scraped.images,
                priority = request.priority,
                targetPrice = request.targetPrice,
                listPrice = prices.listPrice,
                buyUrl = prices.buyLink,
                notes = request.notes,
                tags = request.tags,
                linkedMovies = request.movieIds.map { org.btmonier.WishlistMovie(it, "") }
            )
        )
        wishlistDao.recordScrapedPrices(id, prices)

        call.respond(HttpStatusCode.Created, WishlistImportResponse(true, item = wishlistDao.get(id), prices = prices))
    }

    // POST /api/wishlist - Create an item by hand
    post("/api/wishlist") {
        try {
            val item = call.receive<WishlistItem>()
            val id = wishlistDao.create(item)
            call.respond(HttpStatusCode.Created, wishlistDao.get(id) ?: item)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
        }
    }

    // POST /api/wishlist/refresh-prices - Re-scrape every wanted/ordered item
    // that has not just been checked; ?force=true checks them all
    post("/api/wishlist/refresh-prices") {
        val force = call.request.queryParameters["force"] == "true"
        val minAge = if (force) null else AppSettings.wishlistPriceManualMinAgeHours.takeIf { it > 0.0 }

        val eligible = wishlistDao.itemsDueForRefresh(minAgeHours = null).size
        val results = priceService.refreshDue(minAgeHours = minAge)

        call.respond(HttpStatusCode.OK, BulkPriceRefreshResponse(
            checked = results.size,
            failed = results.count { !it.succeeded },
            priceChanges = results.sumOf { it.observationsAdded },
            skipped = (eligible - results.size).coerceAtLeast(0),
            errors = results.filter { !it.succeeded }.map { "#${it.itemId}: ${it.error}" }
        ))
    }

    // GET /api/wishlist/{id}
    get("/api/wishlist/{id}") {
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid wishlist item ID"))
            return@get
        }
        val item = wishlistDao.get(id)
        if (item == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Wishlist item not found"))
        } else {
            call.respond(HttpStatusCode.OK, item)
        }
    }

    // PUT /api/wishlist/{id} - Edit the item's fields (not its status)
    put("/api/wishlist/{id}") {
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid wishlist item ID"))
            return@put
        }
        try {
            val item = call.receive<WishlistItem>()
            if (wishlistDao.update(id, item)) {
                call.respond(HttpStatusCode.OK, wishlistDao.get(id) ?: item)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Wishlist item not found"))
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
        }
    }

    // DELETE /api/wishlist/{id}
    delete("/api/wishlist/{id}") {
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid wishlist item ID"))
            return@delete
        }
        if (wishlistDao.delete(id)) {
            call.respond(HttpStatusCode.OK, mapOf("message" to "Wishlist item deleted"))
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Wishlist item not found"))
        }
    }

    // PUT /api/wishlist/{id}/movies - Replace the films this item will link to
    put("/api/wishlist/{id}/movies") {
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid wishlist item ID"))
            return@put
        }
        try {
            val request = call.receive<WishlistMoviesRequest>()
            if (wishlistDao.setMovies(id, request.movieIds)) {
                call.respond(HttpStatusCode.OK, wishlistDao.get(id)!!)
            } else {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Wishlist item not found"))
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
        }
    }

    // POST /api/wishlist/{id}/status - Move along wishlist -> ordered -> shipped -> owned
    post("/api/wishlist/{id}/status") {
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid wishlist item ID"))
            return@post
        }
        val request = try {
            call.receive<WishlistTransitionRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
            return@post
        }
        when (val outcome = wishlistDao.transition(id, request)) {
            is TransitionOutcome.Done -> call.respond(HttpStatusCode.OK, outcome.item)
            is TransitionOutcome.NotFound -> call.respond(HttpStatusCode.NotFound, mapOf("error" to "Wishlist item not found"))
            is TransitionOutcome.Invalid -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to outcome.message))
        }
    }

    // GET /api/wishlist/{id}/prices - Price history
    get("/api/wishlist/{id}/prices") {
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid wishlist item ID"))
            return@get
        }
        val item = wishlistDao.get(id)
        if (item == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Wishlist item not found"))
        } else {
            call.respond(HttpStatusCode.OK, item.priceHistory)
        }
    }

    // POST /api/wishlist/{id}/prices - Log a price seen somewhere
    post("/api/wishlist/{id}/prices") {
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid wishlist item ID"))
            return@post
        }
        try {
            val observation = call.receive<PriceObservation>()
            if (observation.price < 0) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Price must not be negative"))
                return@post
            }
            val manual = observation.copy(source = PriceSource.MANUAL)
            if (wishlistDao.addObservation(id, manual) == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Wishlist item not found"))
            } else {
                call.respond(HttpStatusCode.Created, wishlistDao.get(id)!!)
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
        }
    }

    // DELETE /api/wishlist/{id}/prices/{obsId}
    delete("/api/wishlist/{id}/prices/{obsId}") {
        val id = call.parameters["id"]?.toIntOrNull()
        val obsId = call.parameters["obsId"]?.toIntOrNull()
        if (id == null || obsId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid ID"))
            return@delete
        }
        if (wishlistDao.deleteObservation(id, obsId)) {
            call.respond(HttpStatusCode.OK, wishlistDao.get(id) ?: mapOf("message" to "Observation deleted"))
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Observation not found"))
        }
    }

    // POST /api/wishlist/{id}/refresh-price - Re-scrape this item now
    post("/api/wishlist/{id}/refresh-price") {
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, PriceRefreshResponse(false, error = "Invalid wishlist item ID"))
            return@post
        }
        val item = wishlistDao.get(id)
        if (item == null) {
            call.respond(HttpStatusCode.NotFound, PriceRefreshResponse(false, error = "Wishlist item not found"))
            return@post
        }
        val url = item.blurayComUrl
        if (url.isNullOrBlank()) {
            call.respond(HttpStatusCode.BadRequest, PriceRefreshResponse(false, item = item, error = "This item has no blu-ray.com URL to check"))
            return@post
        }

        val result = priceService.refreshItem(id, url)
        call.respond(HttpStatusCode.OK, PriceRefreshResponse(
            success = result.succeeded,
            item = wishlistDao.get(id),
            prices = result.prices,
            observationsAdded = result.observationsAdded,
            error = result.error
        ))
    }
}
