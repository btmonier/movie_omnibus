package org.btmonier

/**
 * The orderings the wishlist page offers. Kept in commonMain, pure and
 * platform-independent, so the rules can be unit tested directly rather than
 * through the database, and so the client can order items the same way the
 * server does if it ever needs to.
 */
enum class WishlistSortField(val slug: String) {
    DATE_ADDED("date_added"),
    TITLE("title"),
    PRICE("price"),
    PERCENT_OFF("percent_off"),
    PRICE_DROP("price_drop"),
    RELEASE_DATE("release_date"),
    PRIORITY("priority");

    /**
     * A comparator for this field in the given direction.
     *
     * Items that have nothing to sort by - no price yet, no discount against
     * list, no previous price to have dropped from, no title - go **last in
     * both directions**. The direction only flips the order of the items that
     * do have a value, so sorting by "% off list" descending puts the biggest
     * discounts first and the pre-orders and full-price items at the bottom
     * rather than on top.
     */
    fun comparator(ascending: Boolean): Comparator<WishlistItem> {
        fun <K : Comparable<K>> by(key: (WishlistItem) -> K?): Comparator<WishlistItem> {
            val order: Comparator<K> = if (ascending) naturalOrder() else reverseOrder()
            return compareBy(nullsLast(order), key)
        }
        val base = when (this) {
            DATE_ADDED -> by { it.createdAt }
            TITLE -> by { it.title?.trim()?.takeIf { t -> t.isNotEmpty() }?.lowercase() }
            PRICE -> by { it.currentPrice }
            PERCENT_OFF -> by { it.percentOffList }
            PRICE_DROP -> by { it.priceDropAmount }
            RELEASE_DATE -> by { it.releaseDate?.takeIf { d -> d.isNotBlank() } }
            PRIORITY -> by { it.priority.ordinal }
        }
        // Ties (and the unsortable tail) stay in a stable, predictable order.
        return base.thenBy { it.id }
    }

    companion object {
        fun fromSlug(slug: String?): WishlistSortField =
            entries.firstOrNull { it.slug.equals(slug, ignoreCase = true) } ?: DATE_ADDED
    }
}

/**
 * How far the current price has fallen from the previous observation, or null
 * when either side is missing. Negative when the price went up.
 */
val WishlistItem.priceDropAmount: Double?
    get() {
        val current = currentPrice ?: return null
        val previous = previousPrice ?: return null
        return previous - current
    }
