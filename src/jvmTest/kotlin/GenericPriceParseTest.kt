package org.btmonier

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading a price off a shop nobody has written a reader for.
 *
 * The two fixtures are the two shapes worth handling: a schema.org `Product`
 * in a JSON-LD block, and a page carrying nothing but Open Graph and microdata
 * price tags. The point of these tests is the third case as much as the first
 * two - a page saying nothing readable must yield no price at all, since that
 * is what keeps its link honest about needing prices typed in rather than
 * inventing one from whatever number the page happens to contain.
 */
class GenericPriceParseTest {

    private fun fixture(name: String) =
        Jsoup.parse(
            this::class.java.getResource("/$name")?.readText() ?: error("Could not load $name"),
            "https://shop.example-video.com/"
        )

    @Test
    fun `reads a JSON-LD product inside a graph`() {
        val price = extractStructuredPrice(
            fixture("generic_product_jsonld.html"),
            "https://shop.example-video.com/products/the-bloodhound-blu-ray"
        )

        assertEquals(34.98, price.price)
        assertEquals(true, price.inStock)
        assertEquals("The Bloodhound (Blu-ray)", price.title)
        assertEquals("https://cdn.example-video.com/bloodhound-front.jpg", price.imageUrl, "The first image is the cover")
        assertEquals(
            "https://shop.example-video.com/products/the-bloodhound-blu-ray",
            price.buyUrl,
            "The link stays the one that was confirmed by hand"
        )
    }

    @Test
    fun `skips blocks that are not products or not valid JSON`() {
        // The fixture leads with an Organization block and a broken one; a
        // reader that gave up on either would find no price at all.
        val price = extractStructuredPrice(fixture("generic_product_jsonld.html"), "https://example.com/x")
        assertEquals(34.98, price.price)
    }

    @Test
    fun `falls back to the price tags when there is no JSON-LD`() {
        val price = extractStructuredPrice(
            fixture("generic_product_meta.html"),
            "https://shop.example-label.com/deadly-games"
        )

        assertEquals(49.99, price.price)
        assertEquals(false, price.inStock, "The page says it is sold out")
        assertEquals("Deadly Games (Limited Edition Blu-ray)", price.title)
    }

    @Test
    fun `a page with no price at all reports none`() {
        val doc = Jsoup.parse(
            """
            <html><head><title>Coming soon</title>
            <meta property="og:title" content="Untitled Boutique Release" />
            </head>
            <body><p>Pre-orders open in the spring. Sign up for the newsletter.</p></body></html>
            """.trimIndent()
        )

        val price = extractStructuredPrice(doc, "https://example.com/coming-soon")
        assertNull(price.price, "Nothing on the page is a price")
        assertNull(price.inStock)
        assertEquals("https://example.com/coming-soon", price.buyUrl)
    }

    @Test
    fun `a product block with no offer is not a price`() {
        // blu-ray.com publishes a Product for its review rating and keeps the
        // prices in its own Price block, which is what BluRayComUtils reads.
        // Taking the rating, or anything else on the page, for a price would
        // be worse than reporting none.
        val price = extractStructuredPrice(
            Jsoup.parse(this::class.java.getResource("/bluray_invaders.html")!!.readText()),
            "https://www.blu-ray.com/movies/Invaders-from-Mars-4K-Blu-ray/336476/"
        )

        assertNull(price.price)
    }

    @Test
    fun `reads a price dressed as money`() {
        val doc = Jsoup.parse(
            """
            <html><head>
            <script type="application/ld+json">
            {"@type":"Product","name":"Box Set","offers":{"@type":"Offer","price":"$1,299.00"}}
            </script>
            </head><body></body></html>
            """.trimIndent()
        )

        assertEquals(1299.00, extractStructuredPrice(doc, "https://example.com/box-set").price)
    }

    @Test
    fun `takes the low end of an aggregate offer and its high end as the list price`() {
        val doc = Jsoup.parse(
            """
            <html><head>
            <script type="application/ld+json">
            {"@type":"Product","name":"Two editions","offers":{"@type":"AggregateOffer",
             "lowPrice":"29.99","highPrice":"44.99","availability":"http://schema.org/OutOfStock"}}
            </script>
            </head><body></body></html>
            """.trimIndent()
        )

        val price = extractStructuredPrice(doc, "https://example.com/two-editions")
        assertEquals(29.99, price.price)
        assertEquals(44.99, price.listPrice)
        assertEquals(false, price.inStock)
    }

    @Test
    fun `ignores a list price that only repeats the selling price`() {
        val doc = Jsoup.parse(
            """
            <html><head>
            <script type="application/ld+json">
            {"@type":"Product","offers":{"@type":"AggregateOffer","lowPrice":"24.99","highPrice":"24.99"}}
            </script>
            </head><body></body></html>
            """.trimIndent()
        )

        assertNull(extractStructuredPrice(doc, "https://example.com/x").listPrice, "That is a 0% discount, not a discount")
    }

    @Test
    fun `never claims a URL on its own`() {
        val scraper = StructuredDataPriceScraper("vinegarsyndrome.com")

        // Generic reading is only ever done for a link that was confirmed by
        // hand, so a store with a reader of its own can never lose its page to
        // it.
        assertFalse(scraper.handles("https://vinegarsyndrome.com/products/the-bloodhound"))
        assertFalse(scraper.handles("https://gruv.com/products/magic"))
        assertFalse(scraper.handles("https://www.blu-ray.com/movies/Title/1234/"))
    }

    @Test
    fun `an ad-hoc Shopify reader covers a store that is not registered`() {
        val scraper = shopifyScraperFor("https://vinegarsyndrome.com/products/the-bloodhound", "Vinegar Syndrome")

        assertEquals("Vinegar Syndrome", scraper.vendorName)
        assertTrue(scraper.handles("https://vinegarsyndrome.com/products/the-bloodhound"))
        assertFalse(scraper.handles("https://gruv.com/products/magic"), "Another store's page")
        assertFalse(scraper.searchable, "Only the vetted stores belong in the picker")
        assertTrue(PriceScrapers.searchable().none { it.vendorName == "Vinegar Syndrome" })
    }

    @Test
    fun `storeNameFrom names a store after its bare host`() {
        assertEquals("vinegarsyndrome.com", storeNameFrom("https://vinegarsyndrome.com/products/the-bloodhound"))
        assertEquals("severin-films.com", storeNameFrom("https://www.severin-films.com/products/x?utm_source=news"))
    }

    @Test
    fun `a link with a recorded reader resolves to it`() {
        val url = "https://vinegarsyndrome.com/products/the-bloodhound"

        assertTrue(PriceScrapers.forLink(url, "Vinegar Syndrome", VendorLinkReader.SHOPIFY) is ShopifyPriceScraper)
        assertTrue(PriceScrapers.forLink(url, "Vinegar Syndrome", VendorLinkReader.STRUCTURED) is StructuredDataPriceScraper)
        assertNull(PriceScrapers.forLink(url, "Vinegar Syndrome", VendorLinkReader.MANUAL), "Nothing reads a manual link")

        // A registered store wins over the recorded reader, so a site that
        // later gets a reader of its own is picked up by it.
        val gruv = PriceScrapers.forLink("https://gruv.com/products/magic", "GRUV", VendorLinkReader.STRUCTURED)
        assertEquals("GRUV", gruv?.vendorName)
        assertTrue(gruv is ShopifyPriceScraper)
    }
}
