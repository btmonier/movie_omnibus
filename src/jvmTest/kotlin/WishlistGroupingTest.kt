package org.btmonier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WishlistGroupingTest {

    @Test
    fun `priceBracket buckets by current price`() {
        assertEquals("No price", priceBracket(null))
        assertEquals("Under \$15", priceBracket(9.98))
        assertEquals("Under \$15", priceBracket(14.99))
        assertEquals("\$15 – \$30", priceBracket(15.0))
        assertEquals("\$15 – \$30", priceBracket(29.99))
        assertEquals("\$30 – \$50", priceBracket(31.32))
        assertEquals("\$50 and up", priceBracket(50.0))
        assertEquals("\$50 and up", priceBracket(199.99))
    }

    @Test
    fun `every bracket has a display order`() {
        listOf(null, 5.0, 20.0, 40.0, 80.0).forEach { price ->
            assertTrue(priceBracket(price) in PRICE_BRACKET_ORDER, "Missing order for ${priceBracket(price)}")
        }
    }

    @Test
    fun `releaseDateBucket separates pre-orders from available releases`() {
        val today = "2026-09-05"
        assertEquals("Unknown date", releaseDateBucket(null, today))
        assertEquals("Unknown date", releaseDateBucket("", today))
        assertEquals("Available", releaseDateBucket("2026-09-05", today))
        assertEquals("Available", releaseDateBucket("2023-07-11", today))
        assertEquals("Available", releaseDateBucket("2026-09-01", today))
        assertEquals("Out this month", releaseDateBucket("2026-09-30", today))
        assertEquals("Pre-order", releaseDateBucket("2026-10-01", today))
        assertEquals("Pre-order", releaseDateBucket("2027-01-15", today))
    }

    @Test
    fun `every release bucket has a display order`() {
        listOf(null, "2020-01-01", "2026-09-20", "2030-01-01").forEach { date ->
            assertTrue(releaseDateBucket(date, "2026-09-05") in RELEASE_BUCKET_ORDER)
        }
    }

    @Test
    fun `derived flags on a wishlist item`() {
        val item = WishlistItem(
            title = "Invaders from Mars",
            listPrice = 49.95,
            currentPrice = 31.32,
            previousPrice = 34.99,
            targetPrice = 32.0
        )
        assertTrue(item.atTarget)
        assertTrue(item.priceDropped)
        assertEquals(37, item.percentOffList)

        val noTarget = item.copy(targetPrice = null, previousPrice = 29.99)
        assertFalse(noTarget.atTarget)
        assertFalse(noTarget.priceDropped)

        val aboveList = item.copy(currentPrice = 55.0)
        assertEquals(null, aboveList.percentOffList)
    }

    @Test
    fun `status path advances to owned and stops`() {
        assertEquals(WishlistStatus.ORDERED, WishlistStatus.WISHLIST.next())
        assertEquals(WishlistStatus.SHIPPED, WishlistStatus.ORDERED.next())
        assertEquals(WishlistStatus.OWNED, WishlistStatus.SHIPPED.next())
        assertEquals(null, WishlistStatus.OWNED.next())
    }
}
