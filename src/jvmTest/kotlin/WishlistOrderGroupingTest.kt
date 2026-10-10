package org.btmonier

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WishlistOrderGroupingTest {

    private fun item(
        id: Int,
        orderId: Int?,
        vendor: String? = null,
        orderNumber: String? = null,
        trackingUrl: String? = null,
        orderDate: String? = null,
        orderItemCount: Int = 1,
        orderTotal: Double = 10.0
    ) = WishlistItem(
        id = id,
        title = "Item $id",
        status = WishlistStatus.ORDERED,
        purchase = Purchase(
            subtotal = 10.0,
            taxAmount = 0.0,
            vendor = vendor,
            orderNumber = orderNumber,
            trackingUrl = trackingUrl,
            orderDate = orderDate,
            id = 100 + id,
            order = orderId?.let {
                PurchaseOrder(id = it, itemCount = orderItemCount, subtotal = orderTotal, taxAmount = 0.0, total = orderTotal)
            }
        )
    )

    private fun List<OrderGroup>.ids() = map { group -> group.items.map { it.id } }

    @Test
    fun `items on the same order are grouped together`() {
        val groups = groupByOrder(listOf(item(1, 7), item(2, 8), item(3, 7)))
        assertEquals(listOf(listOf(1, 3), listOf(2)), groups.ids())
        assertEquals(listOf(7), groups.first().orderIds)
    }

    @Test
    fun `separate orders with the same store and order number are one group`() {
        val groups = groupByOrder(
            listOf(
                item(1, 7, vendor = "GRUV", orderNumber = "#1001"),
                item(2, 8, vendor = "gruv", orderNumber = "#1001 ")
            )
        )
        assertEquals(listOf(listOf(1, 2)), groups.ids())
        assertEquals(listOf(7, 8), groups.single().orderIds)
        assertEquals(listOf("#1001"), groups.single().orderNumbers)
    }

    @Test
    fun `the same order number at different stores is not merged`() {
        val groups = groupByOrder(
            listOf(
                item(1, 7, vendor = "GRUV", orderNumber = "1001"),
                item(2, 8, vendor = "Orbit DVD", orderNumber = "1001")
            )
        )
        assertEquals(listOf(listOf(1), listOf(2)), groups.ids())
    }

    @Test
    fun `items sharing a tracking URL are grouped even across orders`() {
        val groups = groupByOrder(
            listOf(
                item(1, 7, trackingUrl = "https://ups.com/track?n=1Z999"),
                item(2, 8),
                item(3, 9, trackingUrl = "https://ups.com/track?n=1Z999/")
            )
        )
        assertEquals(listOf(listOf(1, 3), listOf(2)), groups.ids())
    }

    @Test
    fun `an order in several boxes is split into shipments, untracked last`() {
        val group = groupByOrder(
            listOf(
                item(1, 7),
                item(2, 7, trackingUrl = "https://usps.com/a"),
                item(3, 7, trackingUrl = "https://ups.com/b"),
                item(4, 7, trackingUrl = "https://usps.com/a")
            )
        ).single()
        assertEquals(
            listOf(listOf(2, 4), listOf(3), listOf(1)),
            group.shipments.map { s -> s.items.map { it.id } }
        )
        assertNull(group.shipments.last().trackingUrl)
    }

    @Test
    fun `groups follow the order their first item appears in`() {
        val groups = groupByOrder(listOf(item(1, 8), item(2, 7), item(3, 8)))
        assertEquals(listOf(listOf(1, 3), listOf(2)), groups.ids())
    }

    @Test
    fun `totals cover each whole order once, including items not shown`() {
        val groups = groupByOrder(
            listOf(
                item(1, 7, vendor = "GRUV", orderNumber = "A", orderItemCount = 3, orderTotal = 45.5),
                item(2, 7, vendor = "GRUV", orderNumber = "A", orderItemCount = 3, orderTotal = 45.5),
                item(3, 8, vendor = "GRUV", orderNumber = "A", orderItemCount = 1, orderTotal = 12.25)
            )
        )
        val group = groups.single()
        assertEquals(4, group.itemCount)
        assertEquals(57.75, group.total)
    }

    @Test
    fun `earliest order date is the group's`() {
        val group = groupByOrder(
            listOf(
                item(1, 7, orderNumber = "A", orderDate = "2026-10-03"),
                item(2, 8, orderNumber = "A", orderDate = "2026-09-28")
            )
        ).single()
        assertEquals("2026-09-28", group.orderDate)
    }

    @Test
    fun `items with no purchase come last, together`() {
        val unrecorded = WishlistItem(id = 9, status = WishlistStatus.ORDERED)
        val groups = groupByOrder(listOf(unrecorded, item(1, 7)))
        assertEquals(listOf(listOf(1), listOf(9)), groups.ids())
        assertTrue(groups.first().hasOrder)
        assertFalse(groups.last().hasOrder)
    }
}
