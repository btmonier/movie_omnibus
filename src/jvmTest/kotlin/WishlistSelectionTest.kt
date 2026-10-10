package org.btmonier

import kotlin.test.Test
import kotlin.test.assertEquals

class WishlistSelectionTest {

    // Item 3 is drawn twice, as it would be under two tags
    private val order = listOf(10, 11, 3, 12, 13, 3, 14)

    @Test
    fun `a forward range covers both ends`() {
        assertEquals(setOf(11, 3, 12), selectionRange(order, 1, 3))
    }

    @Test
    fun `a backward range is the same as a forward one`() {
        assertEquals(selectionRange(order, 1, 4), selectionRange(order, 4, 1))
    }

    @Test
    fun `an item drawn twice in the range is counted once`() {
        assertEquals(setOf(3, 12, 13), selectionRange(order, 2, 5))
    }

    @Test
    fun `positions past either end are clamped to the drawn cards`() {
        assertEquals(order.toSet(), selectionRange(order, -4, 99))
        assertEquals(emptySet(), selectionRange(emptyList(), 0, 3))
    }

    @Test
    fun `a range from a selected anchor selects`() {
        assertEquals(setOf(10, 11, 3, 12, 99), applyRange(setOf(10, 99), order, 0, 3))
    }

    @Test
    fun `a range from a deselected anchor deselects`() {
        // The anchor (11) was just cleared, so the rest of the range follows it
        assertEquals(setOf(10, 14), applyRange(setOf(10, 3, 12, 14), order, 1, 4))
    }

    @Test
    fun `an anchor outside the drawn cards changes nothing`() {
        assertEquals(setOf(10), applyRange(setOf(10), order, 42, 2))
    }
}
