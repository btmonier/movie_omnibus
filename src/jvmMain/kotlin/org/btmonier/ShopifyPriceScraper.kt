package org.btmonier

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder

/**
 * Reads prices from Shopify storefronts.
 *
 * Every Shopify store exposes the same two JSON endpoints, so one
 * implementation parameterized by hostname covers all of them and there is no
 * HTML to parse or theme markup to break:
 *
 * - `/products/<handle>.js` for one product's current price
 * - `/search/suggest.json?q=` for the "find on other stores" picker
 *
 * The two endpoints encode money differently, which is the one thing to get
 * right here: the product endpoint reports **integer cents** (`"price":4699`)
 * while the search endpoint reports **dollar strings** (`"price":"25.97"`).
 * They are parsed by separate functions for that reason, and the fixture tests
 * pin both down.
 */

/** One Shopify storefront to track prices at. */
data class ShopifyStore(val vendorName: String, val host: String)

/**
 * The stores with working JSON endpoints, verified by hand.
 *
 * Barnes & Noble is a Shopify storefront too but has the JSON catalog turned
 * off, and Diabolik DVD, Oldies.com and Hamilton Book are not Shopify at all;
 * all four need their own readers and are deliberately absent.
 */
val SHOPIFY_STORES: List<ShopifyStore> = listOf(
    ShopifyStore("MVD Shop", "mvdshop.com"),
    ShopifyStore("Orbit DVD", "www.orbitdvd.com"),
    ShopifyStore("Atomic Movie Store", "www.atomicmoviestore.com"),
    ShopifyStore("GRUV", "gruv.com")
)

/** The stores left switched on by [AppSettings.wishlistVendorStores]. */
fun enabledShopifyScrapers(fetcher: PageFetcher = JsoupPageFetcher): List<PriceScraper> {
    val enabled = AppSettings.wishlistVendorStores
    return SHOPIFY_STORES
        .filter { store -> enabled.any { it.equals(store.vendorName, ignoreCase = true) } }
        .map { ShopifyPriceScraper(it, fetcher) }
}

/**
 * A reader for a Shopify storefront that is not one of the registered stores.
 *
 * Most boutique labels sell from Shopify, so a hand-added product URL usually
 * turns out to be readable by exactly the same code; only the store's name has
 * to be supplied, since there is no registry entry to take it from. Ad-hoc
 * stores are not searchable: the picker offers stores that were vetted by
 * hand, not whichever ones happen to be linked on one item.
 */
fun shopifyScraperFor(
    url: String,
    vendorName: String,
    fetcher: PageFetcher = JsoupPageFetcher
): ShopifyPriceScraper = ShopifyPriceScraper(
    store = ShopifyStore(vendorName, hostOf(url)),
    fetcher = fetcher,
    searchable = false
)

class ShopifyPriceScraper(
    private val store: ShopifyStore,
    private val fetcher: PageFetcher = JsoupPageFetcher,
    override val searchable: Boolean = true
) : PriceScraper {

    override val vendorName: String get() = store.vendorName

    /**
     * True for a product page on this store. Collection and home pages are
     * rejected, since only a product page has a price to read.
     */
    override fun handles(url: String): Boolean =
        bareHost(hostOf(url)) == bareHost(store.host) && url.contains("/products/", ignoreCase = true)

    override suspend fun fetch(url: String): ScrapedPrices =
        ScrapedPrices.Vendor(store.vendorName, parseProduct(fetcher.text(productJsonUrl(url)), store, url))

    override suspend fun search(query: String, limit: Int): List<VendorCandidate> {
        val capped = limit.coerceIn(1, MAX_SUGGEST_LIMIT)
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "https://${store.host}/search/suggest.json" +
            "?q=$encoded&resources%5Btype%5D=product&resources%5Blimit%5D=$capped"
        return parseSuggestions(fetcher.text(url), store)
    }

    companion object {
        /** Shopify will not return more suggestions than this. */
        const val MAX_SUGGEST_LIMIT = 10

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The JSON address of a product page: `/products/x` becomes
         * `/products/x.js`. Query strings are dropped, so the tracking
         * parameters the search results carry do not travel into the stored
         * URL or the request.
         */
        fun productJsonUrl(productUrl: String): String {
            val bare = productUrl.trim().substringBefore('?').substringBefore('#').trimEnd('/')
            return if (bare.endsWith(".js", ignoreCase = true)) bare else "$bare.js"
        }

        /**
         * Parse a `/products/<handle>.js` body, whose prices are integer cents.
         *
         * [requestedUrl] is only a fallback for the buy link; the store's own
         * canonical path is preferred so the stored URL survives a rename.
         */
        fun parseProduct(body: String, store: ShopifyStore, requestedUrl: String): VendorPrice {
            val product = json.decodeFromString<ShopifyProductJson>(body)
            val price = product.price?.let { centsToDollars(it) }
            val compareAt = product.compareAtPrice?.let { centsToDollars(it) }

            return VendorPrice(
                price = price,
                // Stores routinely repeat the selling price here, which would
                // read as a 0% discount, so only a genuinely higher figure
                // counts as a list price.
                listPrice = compareAt?.takeIf { price == null || it > price },
                inStock = product.available,
                title = product.title,
                imageUrl = absoluteUrl(product.featuredImage, store.host),
                buyUrl = product.url?.let { absoluteUrl(it, store.host) } ?: productUrl(requestedUrl)
            )
        }

        /**
         * Parse a `/search/suggest.json` body, whose prices are dollar strings.
         * Results with no usable URL are dropped rather than offered as
         * candidates that could not be tracked.
         */
        fun parseSuggestions(body: String, store: ShopifyStore): List<VendorCandidate> {
            val products = json.decodeFromString<ShopifySuggestJson>(body)
                .resources?.results?.products.orEmpty()

            return products.mapNotNull { product ->
                val url = product.url?.let { absoluteUrl(productUrl(it), store.host) } ?: return@mapNotNull null
                val price = product.price?.toDoubleOrNull()
                val compareAt = product.compareAtPriceMax?.toDoubleOrNull()

                VendorCandidate(
                    vendor = store.vendorName,
                    title = product.title?.trim().orEmpty().ifEmpty { url.substringAfterLast('/') },
                    url = url,
                    price = price?.let { roundToCents(it) },
                    listPrice = compareAt?.takeIf { price == null || it > price }?.let { roundToCents(it) },
                    imageUrl = (product.image ?: product.featuredImage?.url)?.let { absoluteUrl(it, store.host) },
                    inStock = product.available
                )
            }
        }

        /** Strip a product URL back to its path, dropping tracking parameters. */
        private fun productUrl(url: String): String =
            url.trim().substringBefore('?').substringBefore('#').trimEnd('/')

        private fun centsToDollars(cents: Long): Double = roundToCents(cents / 100.0)

        /**
         * Shopify serves image URLs protocol-relative (`//cdn.shopify.com/...`)
         * and product URLs host-relative (`/products/x`).
         */
        private fun absoluteUrl(url: String?, host: String): String? {
            val trimmed = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return when {
                trimmed.startsWith("//") -> "https:$trimmed"
                trimmed.startsWith("/") -> "https://$host$trimmed"
                else -> trimmed
            }
        }

        /** Hostname without a leading `www.`, so both spellings match. */
        private fun bareHost(host: String): String = host.removePrefix("www.")
    }
}

// --- Wire formats ---

@Serializable
private data class ShopifyProductJson(
    val title: String? = null,
    val price: Long? = null,  // Cents
    @SerialName("compare_at_price") val compareAtPrice: Long? = null,  // Cents
    val available: Boolean? = null,
    @SerialName("featured_image") val featuredImage: String? = null,
    val url: String? = null
)

@Serializable
private data class ShopifySuggestJson(val resources: ShopifySuggestResources? = null)

@Serializable
private data class ShopifySuggestResources(val results: ShopifySuggestResults? = null)

@Serializable
private data class ShopifySuggestResults(val products: List<ShopifySuggestProduct> = emptyList())

@Serializable
private data class ShopifySuggestProduct(
    val title: String? = null,
    val url: String? = null,
    val price: String? = null,  // Dollars
    @SerialName("compare_at_price_max") val compareAtPriceMax: String? = null,  // Dollars
    val available: Boolean? = null,
    val image: String? = null,
    @SerialName("featured_image") val featuredImage: ShopifySuggestImage? = null
)

@Serializable
private data class ShopifySuggestImage(val url: String? = null)
