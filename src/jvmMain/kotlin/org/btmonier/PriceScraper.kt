package org.btmonier

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Price sources behind one interface, so a wishlist item can be tracked at
 * blu-ray.com and at any number of stores through the same refresh pass.
 *
 * Every scraper splits into a pure parse over a downloaded payload and a thin
 * [PriceScraper.fetch] that downloads and calls it. The parsing is what carries
 * the site-specific knowledge, so that is the half the fixture tests exercise.
 */

/** Default number of candidates a store search returns. */
const val DEFAULT_VENDOR_SEARCH_LIMIT = 10

/**
 * What one store's product page said about price. [listPrice] is only set when
 * the store shows a genuinely higher struck-through price; stores routinely
 * repeat the selling price there, which would otherwise read as a 0% discount.
 */
data class VendorPrice(
    val price: Double? = null,
    val listPrice: Double? = null,
    val inStock: Boolean? = null,
    val title: String? = null,
    val imageUrl: String? = null,
    val buyUrl: String
)

/**
 * The prices one source reported. blu-ray.com speaks for several sources at
 * once (MSRP, Amazon, new from, used from) while a store speaks only for
 * itself, so the two keep their own shapes rather than being flattened into a
 * lowest common denominator.
 */
sealed interface ScrapedPrices {
    data class BluRay(val prices: BluRayPrices) : ScrapedPrices
    data class Vendor(val vendor: String, val prices: VendorPrice) : ScrapedPrices
}

/**
 * Downloads pages. Separated from the scrapers so they can be driven from
 * saved fixtures in tests without touching the network.
 */
interface PageFetcher {
    suspend fun document(url: String): Document
    suspend fun text(url: String): String
}

/**
 * The production fetcher. JSoup handles both cases: HTML pages parsed into a
 * [Document], and JSON bodies read as text with content-type checking off.
 */
object JsoupPageFetcher : PageFetcher {
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    private const val TIMEOUT_MS = 15_000

    override suspend fun document(url: String): Document = withContext(Dispatchers.IO) {
        Jsoup.connect(url).userAgent(USER_AGENT).timeout(TIMEOUT_MS).get()
    }

    override suspend fun text(url: String): String = withContext(Dispatchers.IO) {
        Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .timeout(TIMEOUT_MS)
            .ignoreContentType(true)
            .maxBodySize(0)
            .execute()
            .body()
    }
}

/**
 * One site prices can be read from.
 */
interface PriceScraper {
    /** Name recorded against this source's observations, e.g. "GRUV". */
    val vendorName: String

    /** True when this scraper knows how to read the given product URL. */
    fun handles(url: String): Boolean

    /** Read the current prices from a product page. */
    suspend fun fetch(url: String): ScrapedPrices

    /**
     * Look for products matching [query]. Only stores that take part in the
     * "find on other stores" picker implement this.
     */
    suspend fun search(query: String, limit: Int = DEFAULT_VENDOR_SEARCH_LIMIT): List<VendorCandidate> = emptyList()

    /** Whether this scraper can be searched, and so belongs in the picker. */
    val searchable: Boolean get() = false
}

/**
 * blu-ray.com, wrapping the existing [BluRayComUtils] extractors.
 */
class BluRayComScraper(private val fetcher: PageFetcher = JsoupPageFetcher) : PriceScraper {
    override val vendorName: String = "blu-ray.com"

    override fun handles(url: String): Boolean = BluRayComUtils.isBluRayComUrl(url)

    override suspend fun fetch(url: String): ScrapedPrices =
        ScrapedPrices.BluRay(BluRayComUtils.extractPrices(fetcher.document(url)))
}

/**
 * The scrapers in use, and which one handles a given URL.
 *
 * The store list comes from [AppSettings.wishlistVendorStores], so a store
 * that starts refusing requests can be switched off without a rebuild.
 */
object PriceScrapers {
    val bluRay: PriceScraper = BluRayComScraper()

    /** The stores taking part in vendor price tracking. */
    val stores: List<PriceScraper> by lazy { enabledShopifyScrapers() }

    val all: List<PriceScraper> by lazy { listOf(bluRay) + stores }

    /** The scraper for a product URL, or null when no site is recognized. */
    fun forUrl(url: String): PriceScraper? = all.firstOrNull { it.handles(url) }

    /** The stores the "find on other stores" picker can query. */
    fun searchable(): List<PriceScraper> = stores.filter { it.searchable }

    /**
     * The reader for one tracked link. A registered store wins over whatever
     * was recorded when the link was added, so a site that later gets a reader
     * of its own is picked up by it; otherwise the recorded [reader] decides.
     * Null means nothing can read this page, which is the case for links whose
     * prices are typed in by hand.
     */
    fun forLink(
        url: String,
        vendorName: String? = null,
        reader: VendorLinkReader? = null,
        fetcher: PageFetcher = JsoupPageFetcher
    ): PriceScraper? {
        forUrl(url)?.let { return it }
        val name = vendorName?.takeIf { it.isNotBlank() } ?: storeNameFrom(url)
        return when (reader) {
            VendorLinkReader.SHOPIFY -> shopifyScraperFor(url, name, fetcher)
            VendorLinkReader.STRUCTURED -> StructuredDataPriceScraper(name, fetcher)
            VendorLinkReader.STORE, VendorLinkReader.MANUAL, null -> null
        }
    }

    /**
     * Work out how a page's price can be read, by trying to read it. A store
     * with a reader of its own is taken at its word; anything else has to
     * actually produce a price to be tracked automatically, so a page that
     * publishes nothing readable is recorded as manual rather than as a source
     * that fails on every refresh from then on.
     */
    suspend fun probe(
        url: String,
        vendorName: String,
        fetcher: PageFetcher = JsoupPageFetcher
    ): PriceProbe {
        forUrl(url)?.takeIf { it.vendorName != bluRay.vendorName }?.let { store ->
            val read = runCatching { readVendorPrice(store, url) }
            return PriceProbe(
                reader = VendorLinkReader.STORE,
                vendor = store.vendorName,
                prices = read.getOrNull(),
                error = read.exceptionOrNull()?.let { it.message?.takeIf(String::isNotBlank) ?: it::class.simpleName }
            )
        }

        val candidates = buildList {
            // Only a product page has a JSON twin to ask for; a collection page
            // would 404 and waste the attempt.
            if (url.contains("/products/", ignoreCase = true)) {
                add(VendorLinkReader.SHOPIFY to shopifyScraperFor(url, vendorName, fetcher))
            }
            add(VendorLinkReader.STRUCTURED to StructuredDataPriceScraper(vendorName, fetcher))
        }

        candidates.forEach { (reader, scraper) ->
            val prices = runCatching { readVendorPrice(scraper, url) }.getOrNull()
            if (prices?.price != null) return PriceProbe(reader, vendorName, prices)
        }
        return PriceProbe(VendorLinkReader.MANUAL, vendorName, null)
    }

    private suspend fun readVendorPrice(scraper: PriceScraper, url: String): VendorPrice? =
        (scraper.fetch(url) as? ScrapedPrices.Vendor)?.prices
}

/**
 * What a probe found: how the page can be read from now on, the name to file
 * it under, and the prices it already read (null when nothing could).
 *
 * [error] is only set for a registered store, the one case where failing to
 * read a page says the store is unreachable rather than that the page needs
 * its prices typed in.
 */
data class PriceProbe(
    val reader: VendorLinkReader,
    val vendor: String,
    val prices: VendorPrice?,
    val error: String? = null
)

/**
 * The host of a URL, lowercased, or the whole string when it does not parse.
 * Used to rate-limit refreshes per site.
 */
fun hostOf(url: String): String =
    runCatching { java.net.URI(url.trim()).host?.lowercase() }.getOrNull() ?: url.trim().lowercase()

/**
 * What to call a store that has no reader of its own: its bare hostname, which
 * is at least recognizable until it is renamed to something nicer by hand.
 */
fun storeNameFrom(url: String): String = hostOf(url).removePrefix("www.")
