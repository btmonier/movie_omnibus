package org.btmonier

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.btmonier.database.WishlistDao
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlin.random.Random

/**
 * Outcome of refreshing one item's price from blu-ray.com.
 */
data class PriceRefreshResult(
    val itemId: Int,
    val url: String,
    val prices: BluRayPrices? = null,
    val observationsAdded: Int = 0,
    val error: String? = null
) {
    val succeeded: Boolean get() = error == null
}

/**
 * Fetches blu-ray.com release pages and records what they say about price.
 * Shared by the import endpoint, the manual refresh endpoints, the background
 * refresher and the `refreshWishlistPrices` CLI so they all behave the same.
 */
class WishlistPriceService(private val wishlistDao: WishlistDao) {

    /**
     * Download a blu-ray.com release page. Runs on the IO dispatcher because
     * JSoup blocks.
     */
    suspend fun fetchDocument(url: String): Document = withContext(Dispatchers.IO) {
        Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .timeout(15_000)
            .get()
    }

    /**
     * Re-scrape one item's page and record any changed prices.
     */
    suspend fun refreshItem(itemId: Int, url: String): PriceRefreshResult {
        if (!BluRayComUtils.isBluRayComUrl(url)) {
            return PriceRefreshResult(itemId, url, error = "Not a blu-ray.com release URL")
        }
        return try {
            val doc = fetchDocument(url)
            val prices = BluRayComUtils.extractPrices(doc)
            val added = wishlistDao.recordScrapedPrices(itemId, prices)
            if (added < 0) {
                PriceRefreshResult(itemId, url, prices, error = "Wishlist item no longer exists")
            } else {
                PriceRefreshResult(itemId, url, prices, observationsAdded = added)
            }
        } catch (e: Exception) {
            PriceRefreshResult(itemId, url, error = e.message ?: e::class.simpleName)
        }
    }

    /**
     * Refresh every item that is due, at most [concurrency] pages at a time and
     * with the starts spread out, so blu-ray.com sees a trickle rather than a
     * burst. [minAgeHours] of null refreshes regardless of when an item was
     * last checked.
     */
    suspend fun refreshDue(
        minAgeHours: Double? = null,
        includeAll: Boolean = false,
        limit: Int? = null,
        concurrency: Int = AppSettings.wishlistPriceConcurrency,
        onEach: suspend (PriceRefreshResult) -> Unit = {}
    ): List<PriceRefreshResult> = coroutineScope {
        val due = wishlistDao.itemsDueForRefresh(minAgeHours, includeAll)
            .let { if (limit != null) it.take(limit) else it }

        val gate = Semaphore(concurrency.coerceAtLeast(1))
        val reporting = Mutex()

        due.mapIndexed { index, (id, url) ->
            async {
                gate.withPermit {
                    // Stagger the starts so the first batch does not all land at once
                    if (index > 0) delay(Random.nextLong(200, 700))
                    val result = refreshItem(id, url)
                    reporting.withLock { onEach(result) }
                    result
                }
            }
        }.awaitAll()
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }
}
