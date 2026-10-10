package org.btmonier.database

import org.btmonier.AppSettings
import org.btmonier.Order
import org.btmonier.OrderLine
import org.btmonier.Purchase
import org.btmonier.PurchaseOrder
import org.btmonier.WishlistOrderRequest
import org.btmonier.allocateOrderCosts
import org.btmonier.orderTax
import org.btmonier.roundToCents
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Data Access Object for what was paid: orders, and each item's line on one.
 *
 * A line belongs to a wishlist item while the order is in flight, and to a
 * release once the item is owned (or from the start, for releases that were
 * never wishlisted). The two references coexist so the wishlist row keeps its
 * history after conversion.
 *
 * Purchases are read back as a flat per-item view (see [Purchase]), with the
 * order's tax and shipping split across its lines.
 */
class PurchaseDao {

    private val categoryDao = CategoryDao()

    suspend fun getForRelease(releaseId: Int): Purchase? = DatabaseFactory.dbQuery {
        getForReleaseInTransaction(releaseId)
    }

    /**
     * Create or replace the purchase recorded on a release. On an order shared
     * with other items, the order-level fields change for all of them. Returns
     * null when the release does not exist.
     */
    suspend fun upsertForRelease(releaseId: Int, purchase: Purchase): Purchase? = DatabaseFactory.dbQuery {
        val exists = Releases.selectAll().where { Releases.id eq releaseId }.any()
        if (!exists) return@dbQuery null

        val existing = Purchases.selectAll().where { Purchases.releaseId eq releaseId }.firstOrNull()
        if (existing != null) {
            Purchases.update({ Purchases.id eq existing[Purchases.id] }) { writeLine(it, purchase) }
            writeOrder(existing[Purchases.orderId].value, purchase.toOrder())
        } else {
            val orderId = insertOrder(purchase.toOrder())
            Purchases.insert {
                it[Purchases.releaseId] = releaseId
                it[Purchases.orderId] = orderId
                writeLine(it, purchase)
            }
        }
        getForReleaseInTransaction(releaseId)
    }

    suspend fun deleteForRelease(releaseId: Int): Boolean = DatabaseFactory.dbQuery {
        val orderIds = orderIdsWhere { Purchases.releaseId eq releaseId }
        val deleted = Purchases.deleteWhere { Purchases.releaseId eq releaseId } > 0
        deleteOrdersIfEmpty(orderIds)
        deleted
    }

    suspend fun getOrder(id: Int): Order? = DatabaseFactory.dbQuery { getOrderInTransaction(id) }

    /**
     * Update an order's shared fields, and the subtotal of each line named in
     * [order]'s lines by purchase id. A line's tracking URL is changed when it
     * is given (blank clears it) and kept when null. Returns null when the
     * order does not exist.
     */
    suspend fun updateOrder(id: Int, order: Order): Order? = DatabaseFactory.dbQuery {
        if (Orders.selectAll().where { Orders.id eq id }.empty()) return@dbQuery null
        writeOrder(id, order)
        order.lines.forEach { line ->
            val purchaseId = line.purchaseId ?: return@forEach
            Purchases.update({ (Purchases.id eq purchaseId) and (Purchases.orderId eq id) }) {
                it[subtotal] = money(line.subtotal.coerceAtLeast(0.0))
                line.trackingUrl?.let { url -> it[trackingUrl] = url.trim().takeIf(String::isNotEmpty) }
            }
        }
        getOrderInTransaction(id)
    }

    // --- In-transaction API used by the wishlist and release DAOs ---

    /**
     * What every order cost, with the date it counts against (the order date,
     * or when it was recorded), for the spend totals on the wishlist summary.
     */
    internal fun orderTotalsInTransaction(): List<Pair<String, Double>> {
        val subtotals = Purchases.select(Purchases.orderId, Purchases.subtotal)
            .groupBy({ it[Purchases.orderId].value }) { it[Purchases.subtotal].toDouble() }
        return Orders.selectAll().map { row ->
            val lines = subtotals[row[Orders.id].value].orEmpty()
            val tax = orderTax(lines, row[Orders.taxRate].toDouble(), row[Orders.taxAmount]?.toDouble())
            val total = roundToCents(lines.sum() + tax + row[Orders.shipping].toDouble())
            val date = row[Orders.orderDate]?.toString() ?: row[Orders.createdAt].toString()
            date to total
        }
    }

    internal fun getForReleaseInTransaction(releaseId: Int): Purchase? =
        load(Purchases.releaseId eq releaseId).firstOrNull()

    internal fun getForItemInTransaction(itemId: Int): Purchase? =
        load(Purchases.wishlistItemId eq itemId).firstOrNull()

    /** Purchases keyed by wishlist item id, for a batched item load. */
    internal fun forItemsInTransaction(itemIds: List<Int>): Map<Int, Purchase> {
        if (itemIds.isEmpty()) return emptyMap()
        return load(Purchases.wishlistItemId inList itemIds).associateBy { it.wishlistItemId!! }
    }

    /**
     * Create or replace the purchase on a wishlist item. Shipping and receipt
     * dates already stored are kept unless the new purchase names them. On an
     * order shared with other items, the order-level fields change for all.
     */
    internal fun upsertForItemInTransaction(itemId: Int, purchase: Purchase): Purchase {
        val existing = lineForItem(itemId)
        if (existing != null) {
            Purchases.update({ Purchases.id eq existing[Purchases.id] }) {
                it[subtotal] = money(purchase.subtotal.coerceAtLeast(0.0))
                purchase.shippedDate?.let { date -> it[shippedDate] = parseDate(date) }
                purchase.receivedDate?.let { date -> it[receivedDate] = parseDate(date) }
                purchase.trackingUrl?.let { url -> it[trackingUrl] = url.trim().takeIf(String::isNotEmpty) }
            }
            writeOrder(existing[Purchases.orderId].value, purchase.toOrder())
        } else {
            val orderId = insertOrder(purchase.toOrder())
            Purchases.insert {
                it[wishlistItemId] = itemId
                it[Purchases.orderId] = orderId
                writeLine(it, purchase)
            }
        }
        return getForItemInTransaction(itemId)!!
    }

    /**
     * Put several wishlist items on one new order, at the subtotals given. An
     * item already on another order moves to this one, and an order that
     * leaves empty is deleted. Returns the new order's id.
     */
    internal fun createOrderInTransaction(request: WishlistOrderRequest): Int {
        val orderId = insertOrder(
            Order(
                vendor = request.vendor,
                orderDate = request.orderDate,
                orderNumber = request.orderNumber,
                taxRate = request.taxRate,
                taxAmount = request.taxAmount,
                shipping = request.shipping,
                notes = request.notes
            )
        )
        val tracking = request.trackingUrl?.trim()?.takeIf(String::isNotEmpty)

        val previousOrders = mutableSetOf<Int>()
        request.items.forEach { line ->
            val existing = lineForItem(line.itemId)
            if (existing != null) {
                previousOrders += existing[Purchases.orderId].value
                Purchases.update({ Purchases.id eq existing[Purchases.id] }) {
                    it[Purchases.orderId] = orderId
                    it[subtotal] = money(line.subtotal.coerceAtLeast(0.0))
                    if (tracking != null) it[trackingUrl] = tracking
                }
            } else {
                Purchases.insert {
                    it[wishlistItemId] = line.itemId
                    it[Purchases.orderId] = orderId
                    it[subtotal] = money(line.subtotal.coerceAtLeast(0.0))
                    it[trackingUrl] = tracking
                }
            }
        }
        deleteOrdersIfEmpty(previousOrders - orderId)
        return orderId
    }

    /**
     * Record shipping details on an item's purchase, creating a zero-cost
     * purchase when nothing has been recorded yet so the dates are not lost.
     */
    internal fun updateShippingInTransaction(itemId: Int, shippedDate: String?, trackingUrl: String?) {
        val existing = lineForItem(itemId)
        if (existing == null) {
            if (shippedDate == null && trackingUrl == null) return
            upsertForItemInTransaction(
                itemId,
                Purchase(subtotal = 0.0, shippedDate = shippedDate, trackingUrl = trackingUrl)
            )
            return
        }
        Purchases.update({ Purchases.id eq existing[Purchases.id] }) {
            if (shippedDate != null) it[Purchases.shippedDate] = parseDate(shippedDate)
            if (trackingUrl != null) it[Purchases.trackingUrl] = trackingUrl.trim().takeIf(String::isNotEmpty)
        }
    }

    /**
     * Point an item's purchase at the release it became, and note when it was
     * received. Any purchase already sitting on the release (from an earlier
     * join) is replaced.
     */
    internal fun attachToReleaseInTransaction(itemId: Int, releaseId: Int, receivedDate: String?) {
        val existing = lineForItem(itemId) ?: return
        val lineId = existing[Purchases.id].value
        val displaced = orderIdsWhere { (Purchases.releaseId eq releaseId) and (Purchases.id neq lineId) }
        Purchases.deleteWhere { (Purchases.releaseId eq releaseId) and (Purchases.id neq lineId) }
        Purchases.update({ Purchases.id eq lineId }) {
            it[Purchases.releaseId] = releaseId
            if (receivedDate != null) it[Purchases.receivedDate] = parseDate(receivedDate)
        }
        deleteOrdersIfEmpty(displaced)
    }

    /**
     * Forget that a release existed: purchases that also belong to a wishlist
     * item keep their cost but lose the release link, the rest are deleted.
     */
    internal fun detachFromReleaseInTransaction(releaseId: Int) {
        val orderIds = orderIdsWhere { Purchases.releaseId eq releaseId }
        Purchases.update({ (Purchases.releaseId eq releaseId) and Purchases.wishlistItemId.isNotNull() }) {
            it[Purchases.releaseId] = null
        }
        Purchases.deleteWhere { Purchases.releaseId eq releaseId }
        deleteOrdersIfEmpty(orderIds)
    }

    /**
     * Remove an item's purchase, unless it has already been attached to a
     * release, in which case only the item link is dropped.
     */
    internal fun detachFromItemInTransaction(itemId: Int) {
        val existing = lineForItem(itemId) ?: return
        val lineId = existing[Purchases.id].value
        if (existing[Purchases.releaseId] != null) {
            Purchases.update({ Purchases.id eq lineId }) { it[wishlistItemId] = null }
        } else {
            Purchases.deleteWhere { Purchases.id eq lineId }
            deleteOrdersIfEmpty(setOf(existing[Purchases.orderId].value))
        }
    }

    internal fun getOrderInTransaction(id: Int): Order? {
        val row = Orders.selectAll().where { Orders.id eq id }.firstOrNull() ?: return null
        val lineRows = Purchases.selectAll().where { Purchases.orderId eq id }
            .orderBy(Purchases.id to SortOrder.ASC).toList()

        val itemIds = lineRows.mapNotNull { it[Purchases.wishlistItemId]?.value }
        val releaseIds = lineRows.mapNotNull { it[Purchases.releaseId]?.value }
        val itemTitles = if (itemIds.isEmpty()) emptyMap() else {
            WishlistItems.select(WishlistItems.id, WishlistItems.title).where { WishlistItems.id inList itemIds }
                .associate { it[WishlistItems.id].value to it[WishlistItems.title] }
        }
        val releaseTitles = if (releaseIds.isEmpty()) emptyMap() else {
            Releases.select(Releases.id, Releases.title).where { Releases.id inList releaseIds }
                .associate { it[Releases.id].value to it[Releases.title] }
        }

        val lines = lineRows.map { line ->
            val itemId = line[Purchases.wishlistItemId]?.value
            val releaseId = line[Purchases.releaseId]?.value
            OrderLine(
                subtotal = line[Purchases.subtotal].toDouble(),
                purchaseId = line[Purchases.id].value,
                wishlistItemId = itemId,
                releaseId = releaseId,
                title = itemId?.let { itemTitles[it] } ?: releaseId?.let { releaseTitles[it] },
                shippedDate = line[Purchases.shippedDate]?.toString(),
                trackingUrl = line[Purchases.trackingUrl],
                receivedDate = line[Purchases.receivedDate]?.toString()
            )
        }

        return Order(
            vendor = row[Orders.storeId]?.let { storeNamesInTransaction()[it.value] },
            orderDate = row[Orders.orderDate]?.toString(),
            orderNumber = row[Orders.orderNumber],
            taxRate = row[Orders.taxRate].toDouble(),
            taxAmount = row[Orders.taxAmount]?.toDouble(),
            shipping = row[Orders.shipping].toDouble(),
            notes = row[Orders.notes],
            lines = lines,
            id = id,
            createdAt = row[Orders.createdAt].toString()
        )
    }

    /** Store names by id, for turning a store reference into a name. */
    internal fun storeNamesInTransaction(): Map<Int, String> =
        Stores.selectAll().associate { it[Stores.id].value to it[Stores.name] }

    // --- Internals ---

    /** An order's figures and each of its lines' share of the tax and shipping. */
    private class OrderCosts(
        val order: PurchaseOrder,
        val shares: Map<Int, Pair<Double, Double>>
    )

    private fun load(where: Op<Boolean>): List<Purchase> {
        val rows = Purchases.selectAll().where(where).toList()
        if (rows.isEmpty()) return emptyList()

        val orderIds = rows.map { it[Purchases.orderId].value }.distinct()
        val orders = Orders.selectAll().where { Orders.id inList orderIds }.associateBy { it[Orders.id].value }
        val linesByOrder = Purchases.select(Purchases.id, Purchases.orderId, Purchases.subtotal)
            .where { Purchases.orderId inList orderIds }
            .orderBy(Purchases.id to SortOrder.ASC)
            .groupBy({ it[Purchases.orderId].value }) { it[Purchases.id].value to it[Purchases.subtotal].toDouble() }
        val storeNames = storeNamesInTransaction()

        val costs = orderIds.associateWith { orderId ->
            costsOf(orders.getValue(orderId), linesByOrder[orderId].orEmpty())
        }

        return rows.map { row ->
            val orderId = row[Purchases.orderId].value
            val order = orders.getValue(orderId)
            val orderCosts = costs.getValue(orderId)
            val lineId = row[Purchases.id].value
            val (taxShare, shippingShare) = orderCosts.shares[lineId] ?: (0.0 to 0.0)
            Purchase(
                subtotal = row[Purchases.subtotal].toDouble(),
                taxRate = order[Orders.taxRate].toDouble(),
                taxAmount = taxShare,
                shipping = shippingShare,
                vendor = order[Orders.storeId]?.let { storeNames[it.value] },
                orderDate = order[Orders.orderDate]?.toString(),
                orderNumber = order[Orders.orderNumber],
                trackingUrl = row[Purchases.trackingUrl],
                shippedDate = row[Purchases.shippedDate]?.toString(),
                receivedDate = row[Purchases.receivedDate]?.toString(),
                notes = order[Orders.notes],
                id = lineId,
                releaseId = row[Purchases.releaseId]?.value,
                wishlistItemId = row[Purchases.wishlistItemId]?.value,
                createdAt = row[Purchases.createdAt].toString(),
                order = orderCosts.order
            )
        }
    }

    private fun costsOf(order: ResultRow, lines: List<Pair<Int, Double>>): OrderCosts {
        val subtotals = lines.map { it.second }
        val override = order[Orders.taxAmount]?.toDouble()
        val tax = orderTax(subtotals, order[Orders.taxRate].toDouble(), override)
        val shipping = order[Orders.shipping].toDouble()
        val subtotal = roundToCents(subtotals.sum())
        val shares = lines.map { it.first }.zip(allocateOrderCosts(subtotals, tax, shipping)).toMap()
        return OrderCosts(
            PurchaseOrder(
                id = order[Orders.id].value,
                itemCount = lines.size,
                subtotal = subtotal,
                taxAmount = tax,
                taxOverridden = override != null,
                shipping = shipping,
                total = roundToCents(subtotal + tax + shipping)
            ),
            shares
        )
    }

    private fun lineForItem(itemId: Int): ResultRow? =
        Purchases.selectAll().where { Purchases.wishlistItemId eq itemId }.firstOrNull()

    private fun orderIdsWhere(where: SqlExpressionBuilder.() -> Op<Boolean>): Set<Int> =
        Purchases.select(Purchases.orderId).where(where).map { it[Purchases.orderId].value }.toSet()

    /** Delete whichever of [orderIds] no longer have any line on them. */
    private fun deleteOrdersIfEmpty(orderIds: Collection<Int>) {
        if (orderIds.isEmpty()) return
        val stillUsed = orderIdsWhere { Purchases.orderId inList orderIds }
        val empty = orderIds - stillUsed
        if (empty.isNotEmpty()) Orders.deleteWhere { Orders.id inList empty }
    }

    private fun Purchase.toOrder() = Order(
        vendor = vendor,
        orderDate = orderDate,
        orderNumber = orderNumber,
        taxRate = taxRate,
        taxAmount = taxAmount,
        shipping = shipping,
        notes = notes
    )

    private fun insertOrder(order: Order): Int =
        Orders.insertAndGetId { writeOrderFields(it, normalize(order)) }.value

    private fun writeOrder(id: Int, order: Order) {
        Orders.update({ Orders.id eq id }) { writeOrderFields(it, normalize(order)) }
    }

    /**
     * Fill in the default tax rate when the client left it out, round every
     * money field to cents, and blank out empty strings. A null tax amount is
     * kept: it means "compute from the lines".
     */
    private fun normalize(order: Order): Order = order.copy(
        taxRate = order.taxRate ?: AppSettings.defaultTaxRate,
        taxAmount = order.taxAmount?.let { roundToCents(it.coerceAtLeast(0.0)) },
        shipping = roundToCents(order.shipping.coerceAtLeast(0.0)),
        vendor = order.vendor?.trim()?.takeIf(String::isNotEmpty),
        orderNumber = order.orderNumber?.trim()?.takeIf(String::isNotEmpty),
        notes = order.notes?.trim()?.takeIf(String::isNotEmpty)
    )

    private fun writeOrderFields(statement: UpdateBuilder<*>, order: Order) {
        statement[Orders.storeId] = order.vendor
            ?.let { EntityID(categoryDao.getOrCreateInTransaction(CategoryType.STORE, it), Stores) }
        statement[Orders.orderDate] = parseDate(order.orderDate)
        statement[Orders.orderNumber] = order.orderNumber
        statement[Orders.taxRate] = BigDecimal.valueOf(order.taxRate ?: AppSettings.defaultTaxRate)
        statement[Orders.taxAmount] = order.taxAmount?.let { money(it) }
        statement[Orders.shipping] = money(order.shipping)
        statement[Orders.notes] = order.notes
    }

    private fun writeLine(statement: UpdateBuilder<*>, purchase: Purchase) {
        statement[Purchases.subtotal] = money(purchase.subtotal.coerceAtLeast(0.0))
        statement[Purchases.trackingUrl] = purchase.trackingUrl?.trim()?.takeIf(String::isNotEmpty)
        statement[Purchases.shippedDate] = parseDate(purchase.shippedDate)
        statement[Purchases.receivedDate] = parseDate(purchase.receivedDate)
    }

    private fun money(value: Double): BigDecimal = BigDecimal.valueOf(roundToCents(value))

    private fun parseDate(value: String?): LocalDate? =
        value?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
}
