package org.btmonier

/**
 * Turning a wishlist item's price history into the few numbers shown on its
 * card. Kept here, pure and platform-independent, so the rules can be unit
 * tested directly rather than through the database.
 */

/**
 * Sources that reflect what an item sells for right now. The MSRP
 * ([PriceSource.BLURAY_LIST]) and used copies ([PriceSource.USED_FROM]) are
 * shown but never counted as a price you could pay for a new copy.
 */
val SELLING_PRICE_SOURCES: Set<PriceSource> = setOf(
    PriceSource.AMAZON,
    PriceSource.NEW_FROM,
    PriceSource.MANUAL,
    PriceSource.VENDOR
)

/**
 * Identifies one independent price series within an item's history. Two stores
 * are tracked separately even though both report a `VENDOR` price, so neither
 * appears to have changed when the other is checked.
 *
 * A named store is one series however its prices arrived: a price typed in for
 * a store whose page cannot be read is the same series as one scraped from it,
 * so a hand-entered drop reads as a drop and a stale typed price stops
 * counting once the store is seen asking something else.
 */
private data class SeriesKey(val source: PriceSource?, val vendor: String?)

private val PriceObservation.seriesKey: SeriesKey
    get() = vendor?.takeIf { it.isNotBlank() }
        ?.let { SeriesKey(source = null, vendor = it) }
        ?: SeriesKey(source = source, vendor = null)

/**
 * The numbers derived from a price history: what the item costs now, what that
 * same series said before, and the cheapest it has ever been seen.
 */
data class DerivedPrices(
    val current: PriceObservation? = null,
    val previous: Double? = null,
    val lowest: Double? = null
)

/**
 * The current price is the cheapest of every series' latest value, so an item
 * tracked at four stores reports whichever is cheapest today. The previous
 * price is that same series' observation before it, which is what makes a drop
 * a drop rather than a switch to a different store. The lowest is the cheapest
 * selling price ever seen anywhere.
 *
 * [history] is expected oldest-first, the order the database returns it in.
 * Ties on price go to the more recent observation.
 */
fun derivePrices(history: List<PriceObservation>): DerivedPrices {
    val selling = history.filter { it.source in SELLING_PRICE_SOURCES }
    if (selling.isEmpty()) return DerivedPrices()

    val latestPerSeries = selling.groupBy { it.seriesKey }.map { (_, observations) -> observations.last() }
    val current = latestPerSeries.minWith(
        compareBy<PriceObservation> { it.price }.thenByDescending { it.observedAt ?: "" }
    )

    return DerivedPrices(
        current = current,
        previous = selling.filter { it.seriesKey == current.seriesKey }.dropLast(1).lastOrNull()?.price,
        lowest = selling.minOf { it.price }
    )
}

/**
 * The most recent price seen at one store, for listing what each linked store
 * is asking. Scraped and hand-entered prices count alike, since a store whose
 * page cannot be read has only the latter. Returns null when that store has
 * not reported a price yet.
 */
fun List<PriceObservation>.latestFromVendor(vendor: String): PriceObservation? =
    lastOrNull {
        (it.source == PriceSource.VENDOR || it.source == PriceSource.MANUAL) &&
            it.vendor.equals(vendor, ignoreCase = true)
    }
