package org.btmonier

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WishlistBudgetTest {

    private fun item(title: String, price: Double?): WishlistItem =
        WishlistItem(title = title, currentPrice = price, id = title.hashCode())

    private val shelf = listOf(
        item("Invaders from Mars", 31.32),
        item("The Thing", 24.99),
        item("Videodrome", 19.99),
        item("Possession", 39.95),
        item("Suspiria", 14.99),
        item("Hausu", 22.50),
        item("Repo Man", 17.75),
        item("Barbarella", 12.25)
    )

    @Test
    fun `a roll returns the requested count within the budget`() {
        repeat(50) { seed ->
            val result = pickWithinBudget(shelf, count = 3, budget = 80.0, random = Random(seed))
            assertTrue(result.isComplete, "Seed $seed produced ${result.picks.size} picks")
            assertEquals(3, result.picks.size)
            assertTrue(result.total <= 80.0, "Seed $seed spent ${result.total}")
            assertEquals(3, result.picks.map { it.id }.toSet().size, "Seed $seed repeated an item")
            assertEquals(roundToCents(80.0 - result.total), result.leftover)
            assertNull(result.minimumBudgetForCount)
        }
    }

    @Test
    fun `the same seed rolls the same picks and other seeds differ`() {
        val first = pickWithinBudget(shelf, count = 3, budget = 80.0, random = Random(7))
        val again = pickWithinBudget(shelf, count = 3, budget = 80.0, random = Random(7))
        assertEquals(first.picks.map { it.title }, again.picks.map { it.title })

        val distinct = (1..25).map { seed ->
            pickWithinBudget(shelf, count = 3, budget = 80.0, random = Random(seed)).picks.map { it.title }.toSet()
        }.toSet()
        assertTrue(distinct.size > 1, "Every seed produced the same set of picks")
    }

    @Test
    fun `items without a usable price are never picked`() {
        val withUnpriced = shelf + listOf(item("No price yet", null), item("Free", 0.0))
        val result = pickWithinBudget(withUnpriced, count = 4, budget = 200.0, random = Random(3))
        assertEquals(shelf.size, result.eligibleCount)
        assertTrue(result.picks.none { it.currentPrice == null || it.currentPrice == 0.0 })
    }

    @Test
    fun `a budget too small for the count reports what would be needed`() {
        // The three cheapest are 12.25 + 14.99 + 17.75 = 44.99.
        val result = pickWithinBudget(shelf, count = 3, budget = 40.0, random = Random(1))
        assertFalse(result.isComplete)
        assertEquals(2, result.picks.size)
        assertTrue(result.total <= 40.0)
        assertEquals(44.99, assertNotNull(result.minimumBudgetForCount))

        val exactly = pickWithinBudget(shelf, count = 3, budget = 44.99, random = Random(1))
        assertTrue(exactly.isComplete, "A budget equal to the cheapest total should fit")
        assertEquals(44.99, exactly.total)
    }

    @Test
    fun `asking for more items than exist reports no minimum budget`() {
        val result = pickWithinBudget(shelf.take(2), count = 5, budget = 500.0, random = Random(1))
        assertFalse(result.isComplete)
        assertEquals(2, result.picks.size)
        assertEquals(2, result.eligibleCount)
        assertNull(result.minimumBudgetForCount)
    }

    @Test
    fun `a barely feasible budget still fills the count`() {
        // The two fives are the only pair that fits, and a shuffle that starts
        // with the six cannot reach it, so the roll has to try again.
        val tight = listOf(
            item("Six", 6.0),
            item("Cheap A", 5.0),
            item("Cheap B", 5.0),
            item("Pricey", 90.0)
        )
        repeat(25) { seed ->
            val result = pickWithinBudget(tight, count = 2, budget = 10.0, random = Random(seed))
            assertTrue(result.isComplete, "Seed $seed produced ${result.picks.size} picks")
            assertEquals(10.0, result.total)
        }
    }

    @Test
    fun `nothing to spend or nothing asked for gives an empty roll`() {
        assertTrue(pickWithinBudget(shelf, count = 0, budget = 100.0).picks.isEmpty())
        assertTrue(pickWithinBudget(shelf, count = 3, budget = 0.0).picks.isEmpty())
        assertTrue(pickWithinBudget(emptyList(), count = 3, budget = 100.0).picks.isEmpty())
        assertFalse(pickWithinBudget(shelf, count = 0, budget = 100.0).isComplete)
    }
}
