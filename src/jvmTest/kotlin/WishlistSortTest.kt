package org.btmonier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WishlistSortTest {

    // A mixed shelf: discounted, full price, price unknown, price risen.
    private val bigDiscount = WishlistItem(id = 1, title = "Videodrome", listPrice = 40.0, currentPrice = 20.0, previousPrice = 30.0)
    private val smallDiscount = WishlistItem(id = 2, title = "The Thing", listPrice = 40.0, currentPrice = 36.0, previousPrice = 38.0)
    private val fullPrice = WishlistItem(id = 3, title = "Possession", listPrice = 40.0, currentPrice = 40.0, previousPrice = 40.0)
    private val preOrder = WishlistItem(id = 4, title = "Hausu", listPrice = 40.0, currentPrice = null, releaseDate = "2027-01-15")
    private val noList = WishlistItem(id = 5, title = "Suspiria", listPrice = null, currentPrice = 25.0, previousPrice = null)
    private val wentUp = WishlistItem(id = 6, title = "Tetsuo", listPrice = 40.0, currentPrice = 35.0, previousPrice = 30.0)

    private val shelf = listOf(preOrder, fullPrice, smallDiscount, noList, wentUp, bigDiscount)

    private fun ids(field: WishlistSortField, ascending: Boolean): List<Int?> =
        shelf.sortedWith(field.comparator(ascending)).map { it.id }

    @Test
    fun `percent off descending puts the biggest discount first and the unsortable items last`() {
        // 50%, 12%, 10%, then everything with no discount to speak of, by id
        assertEquals(listOf(1, 6, 2, 3, 4, 5), ids(WishlistSortField.PERCENT_OFF, ascending = false))
    }

    @Test
    fun `percent off ascending still keeps the unsortable items last`() {
        assertEquals(listOf(2, 6, 1, 3, 4, 5), ids(WishlistSortField.PERCENT_OFF, ascending = true))
    }

    @Test
    fun `items at or above list price sort with the unpriced ones`() {
        assertNull(fullPrice.percentOffList)
        assertNull(preOrder.percentOffList)
        assertNull(noList.percentOffList)
        val tail = ids(WishlistSortField.PERCENT_OFF, ascending = false).takeLast(3)
        assertEquals(listOf(3, 4, 5), tail)
    }

    @Test
    fun `biggest drop descending ranks rises below no change and unknowns last`() {
        // drops: 10, 2, 0, -5; then no previous price (4, 5)
        assertEquals(listOf(1, 2, 3, 6, 4, 5), ids(WishlistSortField.PRICE_DROP, ascending = false))
        assertEquals(listOf(6, 3, 2, 1, 4, 5), ids(WishlistSortField.PRICE_DROP, ascending = true))
    }

    @Test
    fun `price descending does not float unpriced items to the top`() {
        assertEquals(listOf(3, 2, 6, 5, 1, 4), ids(WishlistSortField.PRICE, ascending = false))
        assertEquals(listOf(1, 5, 6, 2, 3, 4), ids(WishlistSortField.PRICE, ascending = true))
    }

    @Test
    fun `release date puts undated items last in both directions`() {
        val dated = listOf(
            WishlistItem(id = 1, releaseDate = "2026-11-01"),
            WishlistItem(id = 2, releaseDate = null),
            WishlistItem(id = 3, releaseDate = "2026-09-15"),
            WishlistItem(id = 4, releaseDate = "")
        )
        assertEquals(listOf(3, 1, 2, 4), dated.sortedWith(WishlistSortField.RELEASE_DATE.comparator(true)).map { it.id })
        assertEquals(listOf(1, 3, 2, 4), dated.sortedWith(WishlistSortField.RELEASE_DATE.comparator(false)).map { it.id })
    }

    @Test
    fun `title sorts case-insensitively with untitled items last`() {
        val titled = listOf(
            WishlistItem(id = 1, title = "zardoz"),
            WishlistItem(id = 2, title = null),
            WishlistItem(id = 3, title = "Akira"),
            WishlistItem(id = 4, title = "  ")
        )
        assertEquals(listOf(3, 1, 2, 4), titled.sortedWith(WishlistSortField.TITLE.comparator(true)).map { it.id })
        assertEquals(listOf(1, 3, 2, 4), titled.sortedWith(WishlistSortField.TITLE.comparator(false)).map { it.id })
    }

    @Test
    fun `priority and date added reverse cleanly since every item has a value`() {
        val items = listOf(
            WishlistItem(id = 1, priority = WishlistPriority.LOW, createdAt = "2026-01-03T00:00:00"),
            WishlistItem(id = 2, priority = WishlistPriority.HIGH, createdAt = "2026-01-01T00:00:00"),
            WishlistItem(id = 3, priority = WishlistPriority.MEDIUM, createdAt = "2026-01-02T00:00:00")
        )
        assertEquals(listOf(2, 3, 1), items.sortedWith(WishlistSortField.PRIORITY.comparator(true)).map { it.id })
        assertEquals(listOf(1, 3, 2), items.sortedWith(WishlistSortField.PRIORITY.comparator(false)).map { it.id })
        assertEquals(listOf(2, 3, 1), items.sortedWith(WishlistSortField.DATE_ADDED.comparator(true)).map { it.id })
        assertEquals(listOf(1, 3, 2), items.sortedWith(WishlistSortField.DATE_ADDED.comparator(false)).map { it.id })
    }

    @Test
    fun `ties fall back to id so the order is stable`() {
        val tied = listOf(
            WishlistItem(id = 9, listPrice = 40.0, currentPrice = 20.0),
            WishlistItem(id = 4, listPrice = 40.0, currentPrice = 20.0),
            WishlistItem(id = 7, listPrice = 40.0, currentPrice = 20.0)
        )
        assertEquals(listOf(4, 7, 9), tied.sortedWith(WishlistSortField.PERCENT_OFF.comparator(false)).map { it.id })
        assertEquals(listOf(4, 7, 9), tied.sortedWith(WishlistSortField.PERCENT_OFF.comparator(true)).map { it.id })
    }

    @Test
    fun `fromSlug is case-insensitive and falls back to date added`() {
        assertEquals(WishlistSortField.PERCENT_OFF, WishlistSortField.fromSlug("percent_off"))
        assertEquals(WishlistSortField.PRICE_DROP, WishlistSortField.fromSlug("PRICE_DROP"))
        assertEquals(WishlistSortField.DATE_ADDED, WishlistSortField.fromSlug(null))
        assertEquals(WishlistSortField.DATE_ADDED, WishlistSortField.fromSlug("nonsense"))
    }
}
