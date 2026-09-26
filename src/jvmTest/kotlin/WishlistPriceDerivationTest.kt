package org.btmonier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How a price history collapses into the numbers on a wishlist card, now that
 * an item can be tracked at several stores at once.
 *
 * The rule that matters: each store is its own series, whether its prices were
 * scraped or typed in. Checking GRUV must not make Orbit DVD look like it
 * changed, and a drop must mean "this store lowered its price", not "a
 * different store answered this time".
 */
class WishlistPriceDerivationTest {

    private var clock = 0

    /** An observation stamped in call order, so history reads oldest-first. */
    private fun observation(
        source: PriceSource,
        price: Double,
        vendor: String? = null
    ): PriceObservation {
        clock++
        return PriceObservation(
            source = source,
            price = price,
            vendor = vendor,
            observedAt = "2026-01-01T00:00:${clock.toString().padStart(2, '0')}"
        )
    }

    private fun vendor(name: String, price: Double) = observation(PriceSource.VENDOR, price, name)

    @Test
    fun `no selling prices leaves everything unset`() {
        val derived = derivePrices(emptyList())
        assertNull(derived.current)
        assertNull(derived.previous)
        assertNull(derived.lowest)
    }

    @Test
    fun `the cheapest store wins`() {
        val derived = derivePrices(
            listOf(
                vendor("Orbit DVD", 44.99),
                vendor("GRUV", 39.99),
                vendor("MVD Shop", 41.99)
            )
        )

        assertEquals(39.99, derived.current?.price)
        assertEquals("GRUV", derived.current?.vendor)
        assertEquals(PriceSource.VENDOR, derived.current?.source)
    }

    @Test
    fun `each store keeps its own series`() {
        // GRUV is checked twice and Orbit once; GRUV's older price must not be
        // read as Orbit's previous value.
        val derived = derivePrices(
            listOf(
                vendor("GRUV", 49.99),
                vendor("Orbit DVD", 34.99),
                vendor("GRUV", 44.99)
            )
        )

        assertEquals(34.99, derived.current?.price, "Orbit is still the cheapest")
        assertEquals("Orbit DVD", derived.current?.vendor)
        assertNull(derived.previous, "Orbit has only ever reported one price")
        assertEquals(34.99, derived.lowest)
    }

    @Test
    fun `a drop is measured against the same store`() {
        val derived = derivePrices(
            listOf(
                vendor("GRUV", 39.99),
                vendor("Orbit DVD", 59.99),
                vendor("GRUV", 29.99)
            )
        )

        assertEquals(29.99, derived.current?.price)
        assertEquals(39.99, derived.previous, "GRUV's own previous price, not Orbit's")
    }

    @Test
    fun `the lowest is the cheapest ever seen anywhere`() {
        val derived = derivePrices(
            listOf(
                vendor("GRUV", 19.99),
                vendor("GRUV", 44.99),
                vendor("Orbit DVD", 39.99)
            )
        )

        assertEquals(39.99, derived.current?.price, "GRUV has gone back up to 44.99")
        assertEquals(19.99, derived.lowest, "GRUV was 19.99 once")
    }

    @Test
    fun `the MSRP and used copies are never the current price`() {
        val derived = derivePrices(
            listOf(
                observation(PriceSource.BLURAY_LIST, 49.95),
                observation(PriceSource.USED_FROM, 8.99),
                vendor("GRUV", 39.99)
            )
        )

        assertEquals(39.99, derived.current?.price)
        assertEquals(39.99, derived.lowest, "The 8.99 used copy is not a price for a new one")
    }

    @Test
    fun `blu-ray only histories behave as they did before stores existed`() {
        // Amazon and "new from" carry no vendor, so they group exactly as they
        // did when source alone was the key.
        val derived = derivePrices(
            listOf(
                observation(PriceSource.BLURAY_LIST, 49.95),
                observation(PriceSource.AMAZON, 34.99),
                observation(PriceSource.NEW_FROM, 36.50),
                observation(PriceSource.AMAZON, 31.32)
            )
        )

        assertEquals(31.32, derived.current?.price)
        assertEquals(PriceSource.AMAZON, derived.current?.source)
        assertEquals(34.99, derived.previous)
        assertEquals(31.32, derived.lowest)
    }

    @Test
    fun `a store price can undercut blu-ray com`() {
        val derived = derivePrices(
            listOf(
                observation(PriceSource.AMAZON, 34.99),
                vendor("GRUV", 27.99)
            )
        )

        assertEquals(27.99, derived.current?.price)
        assertEquals("GRUV", derived.current?.vendor)
    }

    @Test
    fun `a hand-logged price is kept apart from the store that was scraped`() {
        val derived = derivePrices(
            listOf(
                vendor("GRUV", 39.99),
                observation(PriceSource.MANUAL, 25.00, vendor = "eBay")
            )
        )

        assertEquals(25.00, derived.current?.price)
        assertEquals(PriceSource.MANUAL, derived.current?.source)
        assertNull(derived.previous, "The eBay sighting was the first of its own series")
    }

    @Test
    fun `a price typed in for a store continues that store's series`() {
        // A boutique shop whose page cannot be read is tracked by hand, so its
        // second typed price has to read as a drop rather than as the first
        // sighting of something new.
        val derived = derivePrices(
            listOf(
                observation(PriceSource.MANUAL, 39.99, vendor = "Vinegar Syndrome"),
                observation(PriceSource.MANUAL, 29.99, vendor = "Vinegar Syndrome")
            )
        )

        assertEquals(29.99, derived.current?.price)
        assertEquals(39.99, derived.previous, "The same shop's earlier price")
    }

    @Test
    fun `typed and scraped prices for one store are one series`() {
        val derived = derivePrices(
            listOf(
                vendor("GRUV", 39.99),
                observation(PriceSource.MANUAL, 29.99, vendor = "GRUV")
            )
        )

        assertEquals(29.99, derived.current?.price)
        assertEquals(39.99, derived.previous, "GRUV lowered its price, whoever wrote the number down")
    }

    @Test
    fun `a stale typed price stops counting once the store is seen asking more`() {
        val derived = derivePrices(
            listOf(
                observation(PriceSource.MANUAL, 19.99, vendor = "GRUV"),
                vendor("GRUV", 44.99)
            )
        )

        assertEquals(44.99, derived.current?.price, "The sale is over; the old figure is not still on offer")
        assertEquals(19.99, derived.lowest, "It was that cheap once, which is worth remembering")
    }

    @Test
    fun `latestFromVendor reads a hand-entered price too`() {
        val history = listOf(
            vendor("GRUV", 44.99),
            observation(PriceSource.MANUAL, 34.99, vendor = "Vinegar Syndrome")
        )

        assertEquals(34.99, history.latestFromVendor("Vinegar Syndrome")?.price)
        assertEquals(44.99, history.latestFromVendor("GRUV")?.price)
    }

    @Test
    fun `equal prices resolve to the fresher observation`() {
        val derived = derivePrices(
            listOf(
                vendor("Orbit DVD", 29.99),
                vendor("GRUV", 29.99)
            )
        )

        assertEquals("GRUV", derived.current?.vendor, "Same price, later check")
    }

    @Test
    fun `latestFromVendor reads one store's most recent price`() {
        val history = listOf(
            vendor("GRUV", 44.99),
            vendor("Orbit DVD", 39.99),
            vendor("GRUV", 34.99)
        )

        assertEquals(34.99, history.latestFromVendor("GRUV")?.price)
        assertEquals(39.99, history.latestFromVendor("Orbit DVD")?.price)
        assertNull(history.latestFromVendor("MVD Shop"))
    }

    @Test
    fun `an item at target through one store counts as at target`() {
        val item = WishlistItem(
            title = "Magic",
            targetPrice = 30.0,
            currentPrice = derivePrices(listOf(vendor("GRUV", 27.99))).current?.price
        )

        assertTrue(item.atTarget)
    }
}
