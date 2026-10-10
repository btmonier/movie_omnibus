package org.btmonier

/** Statuses whose items are paid for but have not arrived yet. */
val IN_FLIGHT_STATUSES: Set<WishlistStatus> = setOf(WishlistStatus.ORDERED, WishlistStatus.SHIPPED)

/** Items expected in the same box: one tracking URL, or none recorded yet. */
data class OrderShipment(
    val trackingUrl: String?,
    val items: List<WishlistItem>
)

/**
 * Items bought in one checkout, as the wishlist page shows them.
 *
 * Usually exactly one order, but separate orders recorded at the same store
 * under the same order number, or whose items share a tracking URL, are one
 * checkout entered in pieces, so they are shown together and [orderIds] lists
 * every order involved. Empty [orderIds] is the group of items with no
 * purchase recorded at all.
 *
 * [itemCount] and [total] cover the whole of each order, including items on
 * it that are not in [items] (already owned, or filtered out).
 */
data class OrderGroup(
    val items: List<WishlistItem>,
    val orderIds: List<Int>,
    val vendors: List<String>,
    val orderNumbers: List<String>,
    val orderDate: String?,
    val itemCount: Int,
    val total: Double,
    val shipments: List<OrderShipment>
) {
    val hasOrder: Boolean get() = orderIds.isNotEmpty()
}

/**
 * Section [items] by the checkout they were bought in. Groups come in the
 * order their first item appears in, so the page's sort still decides which
 * order is shown first; items keep their order within a group. Items with no
 * purchase come last, together.
 */
fun groupByOrder(items: List<WishlistItem>): List<OrderGroup> {
    val (bought, unrecorded) = items.partition { it.purchase != null }

    val parent = IntArray(bought.size) { it }
    fun find(i: Int): Int {
        var root = i
        while (parent[root] != root) root = parent[root]
        var node = i
        while (parent[node] != root) {
            val next = parent[node]
            parent[node] = root
            node = next
        }
        return root
    }

    val firstWithKey = mutableMapOf<String, Int>()
    bought.forEachIndexed { index, item ->
        orderKeys(item.purchase!!).forEach { key ->
            val first = firstWithKey.getOrPut(key) { index }
            parent[find(index)] = find(first)
        }
    }

    val members = bought.indices.groupBy { find(it) }.values.sortedBy { it.first() }
    val groups = members.map { indices -> buildGroup(indices.map { bought[it] }) }

    return if (unrecorded.isEmpty()) groups else groups + OrderGroup(
        items = unrecorded,
        orderIds = emptyList(),
        vendors = emptyList(),
        orderNumbers = emptyList(),
        orderDate = null,
        itemCount = unrecorded.size,
        total = 0.0,
        shipments = listOf(OrderShipment(null, unrecorded))
    )
}

/**
 * What ties a purchase to others bought with it. An order number only
 * matches within the same store: small shops' numbering starts from the same
 * place (#1001 is every new Shopify store's first order).
 */
private fun orderKeys(purchase: Purchase): List<String> = listOfNotNull(
    purchase.order?.id?.let { "order:$it" } ?: purchase.id?.let { "line:$it" },
    purchase.orderNumber?.trim()?.takeIf { it.isNotEmpty() }?.let {
        "number:${purchase.vendor?.trim()?.lowercase().orEmpty()}:${it.lowercase()}"
    },
    trackingKey(purchase.trackingUrl)?.let { "tracking:$it" }
)

private fun trackingKey(url: String?): String? =
    url?.trim()?.trimEnd('/')?.lowercase()?.takeIf { it.isNotEmpty() }

private fun buildGroup(items: List<WishlistItem>): OrderGroup {
    val purchases = items.map { it.purchase!! }
    val orders = purchases.mapNotNull { it.order }.distinctBy { it.id }
    // A purchase read back without its order figures counts as an order of one
    val loose = purchases.filter { it.order == null }

    val shipments = items.groupBy { trackingKey(it.purchase?.trackingUrl) }
        .entries
        .sortedBy { it.key == null }
        .map { (_, shipped) -> OrderShipment(shipped.first().purchase?.trackingUrl?.trim(), shipped) }

    return OrderGroup(
        items = items,
        orderIds = orders.map { it.id },
        vendors = purchases.mapNotNull { it.vendor?.trim()?.takeIf(String::isNotEmpty) }.distinctBy { it.lowercase() },
        orderNumbers = purchases.mapNotNull { it.orderNumber?.trim()?.takeIf(String::isNotEmpty) }.distinctBy { it.lowercase() },
        orderDate = purchases.mapNotNull { it.orderDate?.takeIf(String::isNotBlank) }.minOrNull(),
        itemCount = orders.sumOf { it.itemCount } + loose.size,
        total = roundToCents(orders.sumOf { it.total } + loose.sumOf { it.total }),
        shipments = shipments
    )
}
