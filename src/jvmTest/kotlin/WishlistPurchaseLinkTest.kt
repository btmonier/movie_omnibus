package org.btmonier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which page "Open purchase links" sends a wishlist item to. Amazon comes
 * first whenever there is one; otherwise the URL a price was logged at, then
 * the store asking the current price, then blu-ray.com.
 */
class WishlistPurchaseLinkTest {

    private var clock = 0

    private fun observation(
        source: PriceSource,
        price: Double,
        vendor: String? = null,
        url: String? = null
    ): PriceObservation {
        clock++
        return PriceObservation(
            source = source,
            price = price,
            vendor = vendor,
            url = url,
            observedAt = "2026-01-01T00:00:${clock.toString().padStart(2, '0')}"
        )
    }

    private val gruvLink = VendorLink(vendor = "GRUV", url = "https://www.gruv.com/products/basket-case")
    private val blurayUrl = "https://www.blu-ray.com/movies/Basket-Case-Blu-ray/12345/"

    @Test
    fun `an ASIN opens Amazon`() {
        val link = purchaseLink(WishlistItem(asin = " B000TEST ", buyUrl = "https://example.com/buy"))
        assertEquals("https://www.amazon.com/dp/B000TEST", link?.url)
        assertEquals("Amazon", link?.label)
    }

    @Test
    fun `Amazon wins even when another store is cheaper`() {
        val item = WishlistItem(
            asin = "B000TEST",
            vendorLinks = listOf(gruvLink),
            priceHistory = listOf(
                observation(PriceSource.AMAZON, 30.0),
                observation(PriceSource.VENDOR, 12.0, vendor = "GRUV")
            )
        )
        assertEquals("https://www.amazon.com/dp/B000TEST", purchaseLink(item)?.url)
    }

    @Test
    fun `the blu-ray com buy link stands in for a missing ASIN`() {
        val item = WishlistItem(
            buyUrl = "https://www.blu-ray.com/link/click.php?id=1",
            priceHistory = listOf(observation(PriceSource.MANUAL, 10.0, url = "https://shop.example/p"))
        )
        assertEquals("https://www.blu-ray.com/link/click.php?id=1", purchaseLink(item)?.url)
    }

    @Test
    fun `without Amazon the newest logged URL is used`() {
        val item = WishlistItem(
            blurayComUrl = blurayUrl,
            priceHistory = listOf(
                observation(PriceSource.MANUAL, 20.0, vendor = "Old Shop", url = "https://old.example/p"),
                observation(PriceSource.MANUAL, 18.0, vendor = "New Shop", url = "https://new.example/p"),
                observation(PriceSource.MANUAL, 15.0, vendor = "No Link")
            )
        )
        val link = purchaseLink(item)
        assertEquals("https://new.example/p", link?.url)
        assertEquals("New Shop", link?.label)
    }

    @Test
    fun `without a logged URL the store asking the current price is used`() {
        val item = WishlistItem(
            blurayComUrl = blurayUrl,
            vendorLinks = listOf(VendorLink(vendor = "MVD Shop", url = "https://mvdshop.com/p"), gruvLink),
            priceHistory = listOf(
                observation(PriceSource.VENDOR, 25.0, vendor = "MVD Shop"),
                observation(PriceSource.VENDOR, 19.0, vendor = "gruv")
            )
        )
        val link = purchaseLink(item)
        assertEquals(gruvLink.url, link?.url)
        assertEquals("GRUV", link?.label)
    }

    @Test
    fun `blu-ray com is the last resort`() {
        val item = WishlistItem(
            blurayComUrl = blurayUrl,
            priceHistory = listOf(observation(PriceSource.NEW_FROM, 22.0))
        )
        assertEquals(PurchaseLink(blurayUrl, "blu-ray.com"), purchaseLink(item))
    }

    @Test
    fun `an item with nowhere to buy it has no link`() {
        assertNull(purchaseLink(WishlistItem(title = "Hand-entered", asin = " ")))
    }
}
