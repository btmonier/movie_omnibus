package org.btmonier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Parsing of the two Shopify JSON endpoints, against bodies captured from the
 * live stores.
 *
 * The encodings the fixtures pin down are the point of these tests: the
 * product endpoint reports integer cents and the search endpoint reports
 * dollar strings, so reading one with the other's rules would be wrong by a
 * factor of a hundred.
 */
class ShopifyPriceParseTest {

    private val orbit = ShopifyStore("Orbit DVD", "www.orbitdvd.com")
    private val gruv = ShopifyStore("GRUV", "gruv.com")
    private val atomic = ShopifyStore("Atomic Movie Store", "www.atomicmoviestore.com")
    private val mvd = ShopifyStore("MVD Shop", "mvdshop.com")

    private fun fixture(name: String): String =
        this::class.java.getResource("/$name")?.readText() ?: error("Could not load $name")

    // --- Product pages ---

    @Test
    fun `parseProduct reads cents as dollars`() {
        val price = ShopifyPriceScraper.parseProduct(
            fixture("shopify_product_orbitdvd.json"),
            orbit,
            "https://www.orbitdvd.com/products/shallow-grave-4k-uhd-uk-le-steelbook-region-free-b"
        )

        assertEquals(46.99, price.price, "4699 cents is \$46.99, not \$4699")
        assertEquals(true, price.inStock)
        assertEquals("Shallow Grave (4K UHD, UK LE Steelbook, Region Free/B)", price.title)
        assertEquals(
            "https://www.orbitdvd.com/products/shallow-grave-4k-uhd-uk-le-steelbook-region-free-b",
            price.buyUrl
        )
    }

    @Test
    fun `parseProduct leaves the list price unset when the store shows none`() {
        val price = ShopifyPriceScraper.parseProduct(fixture("shopify_product_orbitdvd.json"), orbit, "")
        assertNull(price.listPrice, "compare_at_price is null on this release")
    }

    @Test
    fun `parseProduct reads a genuine discount as a list price`() {
        val price = ShopifyPriceScraper.parseProduct(fixture("shopify_product_atomic.json"), atomic, "")

        assertEquals(34.99, price.price)
        assertEquals(39.99, price.listPrice)
        assertEquals(false, price.inStock, "This release is sold out")
    }

    @Test
    fun `parseProduct ignores a list price that only repeats the selling price`() {
        val price = ShopifyPriceScraper.parseProduct(fixture("shopify_product_gruv.json"), gruv, "")

        assertEquals(39.99, price.price)
        assertNull(price.listPrice, "compare_at_price equal to price is not a discount")
    }

    @Test
    fun `parseProduct makes protocol-relative image URLs absolute`() {
        val price = ShopifyPriceScraper.parseProduct(fixture("shopify_product_gruv.json"), gruv, "")
        assertTrue(
            price.imageUrl?.startsWith("https://cdn.shopify.com/") == true,
            "Expected an absolute cover URL, got ${price.imageUrl}"
        )
    }

    // --- Product JSON addresses ---

    @Test
    fun `productJsonUrl appends js and drops tracking parameters`() {
        assertEquals(
            "https://gruv.com/products/magic.js",
            ShopifyPriceScraper.productJsonUrl("https://gruv.com/products/magic?_pos=1&_psq=magic&_ss=e")
        )
        assertEquals(
            "https://gruv.com/products/magic.js",
            ShopifyPriceScraper.productJsonUrl("https://gruv.com/products/magic/")
        )
        assertEquals(
            "https://gruv.com/products/magic.js",
            ShopifyPriceScraper.productJsonUrl("https://gruv.com/products/magic#reviews")
        )
    }

    @Test
    fun `productJsonUrl is idempotent`() {
        val once = ShopifyPriceScraper.productJsonUrl("https://gruv.com/products/magic")
        assertEquals(once, ShopifyPriceScraper.productJsonUrl(once))
    }

    // --- Search suggestions ---

    @Test
    fun `parseSuggestions reads dollar strings and strips tracking parameters`() {
        val candidates = ShopifyPriceScraper.parseSuggestions(fixture("shopify_suggest_mvdshop.json"), mvd)
        assertTrue(candidates.isNotEmpty(), "The fixture holds several Basket Case releases")

        val first = candidates.first()
        assertEquals("MVD Shop", first.vendor)
        assertEquals("Basket Case [Standard Edition] (Blu-ray)", first.title)
        assertEquals(25.97, first.price)
        assertEquals(39.95, first.listPrice)
        assertEquals(true, first.inStock)
        assertEquals("https://mvdshop.com/products/basket-case-standard-edition-blu-ray", first.url)
        assertFalse(first.url.contains("_pos"), "Tracking parameters must not be stored")
    }

    @Test
    fun `parseSuggestions offers the different editions separately`() {
        val candidates = ShopifyPriceScraper.parseSuggestions(fixture("shopify_suggest_mvdshop.json"), mvd)
        val titles = candidates.map { it.title }

        // The whole reason a human confirms the link: these are three
        // different discs whose titles differ by a couple of words.
        assertTrue(titles.any { it.contains("Blu-ray") }, "Expected a Blu-ray edition")
        assertTrue(titles.any { it.contains("4K") }, "Expected a 4K edition")
        assertEquals(titles.size, titles.distinct().size, "Candidates should not repeat")
    }

    @Test
    fun `parseSuggestions carries a cover image for every candidate`() {
        val candidates = ShopifyPriceScraper.parseSuggestions(fixture("shopify_suggest_gruv.json"), gruv)
        assertTrue(candidates.isNotEmpty())
        candidates.forEach { candidate ->
            assertNotNull(candidate.imageUrl, "${candidate.title} has no cover to show in the picker")
            assertTrue(candidate.imageUrl!!.startsWith("https://"), "Cover URL should be absolute")
        }
    }

    @Test
    fun `parseSuggestions ignores a list price equal to the price`() {
        val candidates = ShopifyPriceScraper.parseSuggestions(fixture("shopify_suggest_gruv.json"), gruv)
        val complete = candidates.first { it.title.contains("Complete Original Series") }

        assertEquals(36.49, complete.price)
        assertNull(complete.listPrice, "compare_at_price_max repeats the price here")
    }

    @Test
    fun `parseSuggestions tolerates an empty result`() {
        assertTrue(ShopifyPriceScraper.parseSuggestions("""{"resources":{"results":{"products":[]}}}""", gruv).isEmpty())
        assertTrue(ShopifyPriceScraper.parseSuggestions("""{"resources":{}}""", gruv).isEmpty())
        assertTrue(ShopifyPriceScraper.parseSuggestions("{}", gruv).isEmpty())
    }

    // --- URL ownership ---

    @Test
    fun `handles accepts this store's product pages only`() {
        val scraper = ShopifyPriceScraper(gruv)

        assertTrue(scraper.handles("https://gruv.com/products/magic-uhd"))
        assertTrue(scraper.handles("https://www.gruv.com/products/magic-uhd"), "www should not matter")
        assertTrue(scraper.handles("https://gruv.com/collections/shout-factory/products/magic-uhd"))

        assertFalse(scraper.handles("https://gruv.com/collections/shout-factory"), "Not a product page")
        assertFalse(scraper.handles("https://gruv.com/"), "Not a product page")
        assertFalse(scraper.handles("https://mvdshop.com/products/basket-case"), "Another store")
        assertFalse(scraper.handles("https://www.blu-ray.com/movies/Title/1234/"))
    }

    @Test
    fun `the registry routes each URL to its own reader`() {
        val scrapers = SHOPIFY_STORES.map { ShopifyPriceScraper(it) }

        SHOPIFY_STORES.forEach { store ->
            val url = "https://${store.host}/products/some-release"
            val matched = scrapers.filter { it.handles(url) }
            assertEquals(1, matched.size, "${store.host} should match exactly one reader")
            assertEquals(store.vendorName, matched.single().vendorName)
        }
    }
}
