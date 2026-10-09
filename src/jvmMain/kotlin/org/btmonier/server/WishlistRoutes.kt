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
import org.btmonier.VendorCandidate
import org.btmonier.VendorLinkReader
import org.btmonier.WishlistImportRequest
import org.btmonier.WishlistItem
import org.btmonier.WishlistPriceService
import org.btmonier.WishlistPriority
import org.btmonier.WishlistRefreshJob
import org.btmonier.WishlistStatus
import org.btmonier.WishlistTransitionRequest
import org.btmonier.database.ReleaseDao
import org.btmonier.database.TransitionOutcome
import org.btmonier.database.WishlistDao
import org.btmonier.storeNameFrom
import org.btmonier.database.WishlistFilters
import org.btmonier.WishlistSortField

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

/**
 * Body of `POST /api/wishlist/{id}/vendor-links`.
 *
 * For one of the registered stores only [url] matters: the store is named by
 * the reader that recognizes it, so a link can never be filed under a name the
 * refresher does not know. Anywhere else - a boutique label's own shop, an
 * exclusive with no listing on blu-ray.com - [vendor] names it and [price]
 * records what it is asking, for the pages that publish nothing readable.
 * [manual] skips the probe for a page known not to be readable.
 */
@Serializable
data class VendorLinkRequest(
    val url: String,
    val vendor: String? = null,
    val price: Double? = null,
    val manual: Boolean = false
)

/**
 * Candidates for the "find on other stores" picker. [storesSearched] and
 * [errors] together say which stores the result actually covers.
 */
@Serializable
data class VendorSearchResponse(
    val query: String,
    val candidates: List<VendorCandidate> = emptyList(),
    val storesSearched: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val error: String? = null
)

/**
 * What one price source said during a refresh, so the client can report "GRUV
 * $39.99, blu-ray.com unchanged, Orbit DVD failed" rather than a single
 * pass/fail for the whole item.
 */
@Serializable
data class SourceRefreshResult(
    val source: String,
    val success: Boolean,
    val price: Double? = null,
    val observationsAdded: Int = 0,
    val error: String? = null
)

@Serializable
data class PriceRefreshResponse(
    val success: Boolean,
    val item: WishlistItem? = null,
    val prices: BluRayPrices? = null,
    val observationsAdded: Int = 0,
    val sources: List<SourceRefreshResult> = emptyList(),
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
 * How far a background "refresh all prices" pass has got. [total] and
 * [completed] count pages, which is what the time is spent on; [checkedItems]
 * counts the things the user asked about.
 */
@Serializable
data class RefreshProgressResponse(
    val jobId: Long,
    val total: Int,
    val completed: Int,
    val checkedItems: Int,
    val failed: Int,
    val priceChanges: Int,
    val skipped: Int,
    val lastSource: String? = null,
    val done: Boolean,
    val alreadyRunning: Boolean = false,
    val errors: List<String> = emptyList()
)

private fun WishlistRefreshJob.Progress.toResponse(alreadyRunning: Boolean = false) = RefreshProgressResponse(
    jobId = jobId,
    total = total,
    completed = completed,
    checkedItems = checkedItems,
    failed = failed,
    priceChanges = priceChanges,
    skipped = skipped,
    lastSource = lastSource,
    done = done,
    alreadyRunning = alreadyRunning,
    errors = errors
)

/** A URL that can be linked out to, as opposed to a bare hostname or a note. */
private fun isWebLink(url: String): Boolean =
    url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

private fun cleanLink(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

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

        // Counted in items, not pages: an item tracked at three stores was
        // still only one thing the user asked about.
        val eligible = wishlistDao.itemsDueForRefresh(minAgeHours = null).map { it.itemId }.distinct().size
        val results = priceService.refreshDue(minAgeHours = minAge)
        val checkedItems = results.map { it.itemId }.distinct().size

        call.respond(HttpStatusCode.OK, BulkPriceRefreshResponse(
            checked = checkedItems,
            failed = results.count { !it.succeeded },
            priceChanges = results.sumOf { it.observationsAdded },
            skipped = (eligible - checkedItems).coerceAtLeast(0),
            errors = results.filter { !it.succeeded }.map { "#${it.itemId} ${it.sourceLabel}: ${it.error}" }
        ))
    }

    // POST /api/wishlist/refresh-prices/start - Begin a pass in the background
    // and report progress through the endpoint below, so a refresh spanning
    // dozens of pages can be watched rather than waited on blindly
    post("/api/wishlist/refresh-prices/start") {
        val force = call.request.queryParameters["force"] == "true"
        val (progress, alreadyRunning) = WishlistRefreshJob.start(
            scope = call.application,
            service = priceService,
            dao = wishlistDao,
            force = force
        )
        call.respond(HttpStatusCode.OK, progress.toResponse(alreadyRunning))
    }

    // GET /api/wishlist/refresh-prices/progress - How far the current (or last)
    // pass got. 404 when no pass has ever been started.
    get("/api/wishlist/refresh-prices/progress") {
        val progress = WishlistRefreshJob.progress()
        if (progress == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "No price refresh has been started"))
        } else {
            call.respond(HttpStatusCode.OK, progress.toResponse())
        }
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
            val seenAt = cleanLink(observation.url)
            if (seenAt != null && !isWebLink(seenAt)) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "A link has to start with http:// or https://"))
                return@post
            }
            val manual = observation.copy(source = PriceSource.MANUAL, url = seenAt)
            if (wishlistDao.addObservation(id, manual) == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Wishlist item not found"))
            } else {
                call.respond(HttpStatusCode.Created, wishlistDao.get(id)!!)
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
        }
    }

    // PUT /api/wishlist/{id}/prices/{obsId} - Correct a price logged by hand
    put("/api/wishlist/{id}/prices/{obsId}") {
        val id = call.parameters["id"]?.toIntOrNull()
        val obsId = call.parameters["obsId"]?.toIntOrNull()
        if (id == null || obsId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid ID"))
            return@put
        }
        val observation = try {
            call.receive<PriceObservation>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
            return@put
        }
        if (observation.price < 0) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Price must not be negative"))
            return@put
        }
        val seenAt = cleanLink(observation.url)
        if (seenAt != null && !isWebLink(seenAt)) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "A link has to start with http:// or https://"))
            return@put
        }

        val existing = wishlistDao.get(id)?.priceHistory?.firstOrNull { it.id == obsId }
        if (existing == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Observation not found"))
            return@put
        }
        // A scraped row records what a site said at a moment, so it can be
        // deleted but not rewritten into something the site never said.
        if (existing.source != PriceSource.MANUAL) {
            call.respond(HttpStatusCode.BadRequest, mapOf(
                "error" to "Only prices logged by hand can be edited. Delete this one to drop it."
            ))
            return@put
        }

        if (wishlistDao.updateObservation(id, obsId, observation.copy(url = seenAt))) {
            call.respond(HttpStatusCode.OK, wishlistDao.get(id)!!)
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Observation not found"))
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

    // POST /api/wishlist/{id}/refresh-price - Re-check every source for this item
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

        val results = priceService.refreshAllSources(id)
        if (results.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, PriceRefreshResponse(
                false, item = item,
                error = if (item.vendorLinks.isNotEmpty())
                    "Every store on this item has its prices typed in by hand, so there is nothing to check."
                else "This item has no pages to check. Add a blu-ray.com URL or link a store."
            ))
            return@post
        }

        val failures = results.filter { !it.succeeded }
        call.respond(HttpStatusCode.OK, PriceRefreshResponse(
            success = results.any { it.succeeded },
            item = wishlistDao.get(id),
            prices = results.firstOrNull { it.vendor == null }?.prices,
            observationsAdded = results.sumOf { it.observationsAdded },
            sources = results.map {
                SourceRefreshResult(it.sourceLabel, it.succeeded, it.reportedPrice, it.observationsAdded, it.error)
            },
            // Only an outright error when nothing could be reached; a single
            // store being down is reported per source instead.
            error = failures.takeIf { it.size == results.size }
                ?.joinToString("; ") { "${it.sourceLabel}: ${it.error}" }
        ))
    }

    // GET /api/wishlist/{id}/vendor-search - Find this item at the other stores
    get("/api/wishlist/{id}/vendor-search") {
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid wishlist item ID"))
            return@get
        }
        val item = wishlistDao.get(id)
        if (item == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Wishlist item not found"))
            return@get
        }

        val query = call.request.queryParameters["q"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: item.title?.trim()?.takeIf { it.isNotEmpty() }
        if (query == null) {
            call.respond(HttpStatusCode.BadRequest, VendorSearchResponse(
                query = "", error = "Nothing to search for - this item has no title."
            ))
            return@get
        }

        val searched = priceService.searchStores(query)
        call.respond(HttpStatusCode.OK, VendorSearchResponse(
            query = query,
            candidates = searched.candidates,
            storesSearched = searched.storesSearched,
            errors = searched.errors
        ))
    }

    // POST /api/wishlist/{id}/vendor-links - Track this item at a store
    post("/api/wishlist/{id}/vendor-links") {
        val id = call.parameters["id"]?.toIntOrNull()
        if (id == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid wishlist item ID"))
            return@post
        }
        val request = try {
            call.receive<VendorLinkRequest>()
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body: ${e.message}"))
            return@post
        }

        val url = request.url.trim()
        if (url.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "A product URL is required"))
            return@post
        }
        if (!isWebLink(url)) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "A product URL has to start with http:// or https://"))
            return@post
        }
        if (BluRayComUtils.isBluRayComUrl(url)) {
            call.respond(HttpStatusCode.BadRequest, mapOf(
                "error" to "A blu-ray.com page belongs on the item itself, not in its list of stores."
            ))
            return@post
        }
        if (request.price != null && request.price < 0) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Price must not be negative"))
            return@post
        }

        // An unrecognized site is not a reason to refuse the URL: probing may
        // still find a price on it, and a page that publishes none is worth
        // keeping as somewhere to type prices in against.
        val requestedName = request.vendor?.trim()?.takeIf { it.isNotEmpty() }
        val probe = if (request.manual) null else priceService.probeLink(url, requestedName ?: storeNameFrom(url))
        val reader = probe?.reader ?: VendorLinkReader.MANUAL
        val vendorName = when (reader) {
            // A store with a reader of its own names itself, so its links
            // cannot be filed under a name the refresher does not know.
            VendorLinkReader.STORE -> probe!!.vendor
            else -> requestedName ?: storeNameFrom(url)
        }

        if (wishlistDao.setVendorLink(id, vendorName, url, reader) == null) {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Wishlist item not found"))
            return@post
        }

        // Record a price straight away so the card updates now rather than at
        // the next scheduled pass - the one the probe read, or the one that
        // was typed in for a page nothing can read.
        val probedPrices = probe?.prices
        when {
            probedPrices != null -> wishlistDao.recordVendorPrice(id, vendorName, probedPrices)
            probe?.error != null -> wishlistDao.recordVendorError(id, vendorName, probe.error!!)
            request.price != null -> wishlistDao.addObservation(
                id, PriceObservation(PriceSource.MANUAL, request.price, vendor = vendorName, url = url)
            )
        }
        call.respond(HttpStatusCode.Created, wishlistDao.get(id)!!)
    }

    // DELETE /api/wishlist/{id}/vendor-links/{linkId} - Stop tracking a store
    delete("/api/wishlist/{id}/vendor-links/{linkId}") {
        val id = call.parameters["id"]?.toIntOrNull()
        val linkId = call.parameters["linkId"]?.toIntOrNull()
        if (id == null || linkId == null) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid ID"))
            return@delete
        }
        if (wishlistDao.deleteVendorLink(id, linkId)) {
            call.respond(HttpStatusCode.OK, wishlistDao.get(id) ?: mapOf("message" to "Store link removed"))
        } else {
            call.respond(HttpStatusCode.NotFound, mapOf("error" to "Store link not found"))
        }
    }
}
