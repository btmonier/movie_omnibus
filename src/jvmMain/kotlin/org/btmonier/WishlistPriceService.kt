package org.btmonier

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.btmonier.database.RefreshTarget
import org.btmonier.database.WishlistDao
import org.jsoup.nodes.Document
import kotlin.random.Random

/**
 * Outcome of re-checking one price source for one item. [vendor] names the
 * store, or is null when the source was the item's blu-ray.com page.
 */
data class PriceRefreshResult(
    val itemId: Int,
    val url: String,
    val vendor: String? = null,
    val prices: BluRayPrices? = null,
    val vendorPrice: VendorPrice? = null,
    val observationsAdded: Int = 0,
    val error: String? = null
) {
    val succeeded: Boolean get() = error == null

    /** What to call this source in a message. */
    val sourceLabel: String get() = vendor ?: "blu-ray.com"

    /** The price this source reported, whichever kind of source it was. */
    val reportedPrice: Double?
        get() = vendorPrice?.price ?: prices?.let { it.amazonPrice ?: it.newFromPrice }
}

/**
 * Candidate store products for one search, with the stores that answered and
 * the ones that did not, so a store being down is visible rather than looking
 * like "no results".
 */
data class VendorSearchOutcome(
    val candidates: List<VendorCandidate> = emptyList(),
    val storesSearched: List<String> = emptyList(),
    val errors: List<String> = emptyList()
)

/**
 * Fetches the pages a wishlist item is tracked on and records what they say
 * about price. Shared by the import endpoint, the manual refresh endpoints, the
 * background refresher and the `refreshWishlistPrices` CLI so they all behave
 * the same.
 *
 * Which reader a page belongs to is decided by [PriceScrapers], so adding a
 * store does not touch this class.
 */
class WishlistPriceService(
    private val wishlistDao: WishlistDao,
    private val fetcher: PageFetcher = JsoupPageFetcher
) {

    /**
     * Download an HTML page. Used by the import endpoint, which needs the whole
     * blu-ray.com document rather than just its prices.
     */
    suspend fun fetchDocument(url: String): Document = fetcher.document(url)

    /**
     * Work out whether a hand-added URL can be read, and read it if it can.
     * Used when a link is created, so the item shows a price straight away and
     * the way to re-read it is settled once rather than guessed every pass.
     */
    suspend fun probeLink(url: String, vendorName: String): PriceProbe =
        PriceScrapers.probe(url, vendorName, fetcher)

    /**
     * Re-check one source and record any changed price. A failure is recorded
     * against the vendor link so the item can show which store went quiet.
     */
    suspend fun refresh(target: RefreshTarget): PriceRefreshResult {
        val scraper = PriceScrapers.forLink(target.url, target.vendor, target.reader, fetcher)
            ?: return PriceRefreshResult(
                target.itemId, target.url, target.vendor,
                error = "No price source recognizes ${hostOf(target.url)}"
            )

        return try {
            when (val scraped = scraper.fetch(target.url)) {
                is ScrapedPrices.BluRay -> {
                    val added = wishlistDao.recordScrapedPrices(target.itemId, scraped.prices)
                    if (added < 0) missingItem(target)
                    else PriceRefreshResult(
                        target.itemId, target.url, target.vendor,
                        prices = scraped.prices, observationsAdded = added
                    )
                }
                is ScrapedPrices.Vendor -> {
                    val vendorName = target.vendor ?: scraped.vendor
                    val added = wishlistDao.recordVendorPrice(target.itemId, vendorName, scraped.prices)
                    if (added < 0) missingItem(target)
                    else PriceRefreshResult(
                        target.itemId, target.url, vendorName,
                        vendorPrice = scraped.prices, observationsAdded = added
                    )
                }
            }
        } catch (e: Exception) {
            val message = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "Unknown error"
            target.vendor?.let { wishlistDao.recordVendorError(target.itemId, it, message) }
            PriceRefreshResult(target.itemId, target.url, target.vendor, error = message)
        }
    }

    /** Re-check every source one item is tracked on. */
    suspend fun refreshAllSources(itemId: Int): List<PriceRefreshResult> =
        wishlistDao.refreshTargetsFor(itemId).map { refresh(it) }

    /**
     * Look for [query] at every searchable store at once, cheapest first. The
     * results are candidates for the user to confirm, never links applied
     * automatically: stores carry several editions of the same film under
     * near-identical titles, so picking one by similarity would track the wrong
     * disc about as often as the right one.
     */
    suspend fun searchStores(
        query: String,
        limit: Int = DEFAULT_VENDOR_SEARCH_LIMIT
    ): VendorSearchOutcome = coroutineScope {
        val stores = PriceScrapers.searchable()
        if (stores.isEmpty()) return@coroutineScope VendorSearchOutcome()

        val outcomes = stores.map { store ->
            async {
                store.vendorName to runCatching { store.search(query, limit) }
            }
        }.awaitAll()

        VendorSearchOutcome(
            candidates = outcomes
                .flatMap { (_, result) -> result.getOrDefault(emptyList()) }
                .sortedWith(compareBy(nullsLast()) { it.price }),
            storesSearched = outcomes.filter { (_, result) -> result.isSuccess }.map { (name, _) -> name },
            errors = outcomes.mapNotNull { (name, result) ->
                result.exceptionOrNull()?.let { "$name: ${it.message ?: it::class.simpleName}" }
            }
        )
    }

    /**
     * Refresh every source that is due, at most [concurrency] pages per site at
     * a time and with the starts spread out, so no one site sees a burst. The
     * gate is per site, so several stores are checked in parallel while each
     * individually stays polite. [minAgeHours] of null refreshes regardless of
     * when an item was last checked, and [limit] counts items rather than
     * sources.
     */
    suspend fun refreshDue(
        minAgeHours: Double? = null,
        includeAll: Boolean = false,
        limit: Int? = null,
        concurrency: Int = AppSettings.wishlistPriceConcurrency,
        onEach: suspend (PriceRefreshResult) -> Unit = {}
    ): List<PriceRefreshResult> = coroutineScope {
        val due = firstItems(wishlistDao.itemsDueForRefresh(minAgeHours, includeAll), limit)
        val permits = concurrency.coerceAtLeast(1)
        val reporting = Mutex()

        due.groupBy { hostOf(it.url) }.map { (_, targets) ->
            val gate = Semaphore(permits)
            targets.mapIndexed { index, target ->
                async {
                    gate.withPermit {
                        // Stagger the starts so the first batch does not all land at once
                        if (index > 0) delay(Random.nextLong(200, 700))
                        val result = refresh(target)
                        reporting.withLock { onEach(result) }
                        result
                    }
                }
            }
        }.flatten().awaitAll()
    }

    private fun missingItem(target: RefreshTarget) =
        PriceRefreshResult(target.itemId, target.url, target.vendor, error = "Wishlist item no longer exists")

    companion object {
        /**
         * The targets belonging to the first [limit] items, keeping every source
         * of an item together so "refresh 5 items" never checks half of one.
         */
        internal fun firstItems(targets: List<RefreshTarget>, limit: Int?): List<RefreshTarget> {
            if (limit == null) return targets
            val keep = targets.map { it.itemId }.distinct().take(limit).toSet()
            return targets.filter { it.itemId in keep }
        }
    }
}
