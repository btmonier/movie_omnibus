package org.btmonier.database

import org.btmonier.AppSettings
import org.btmonier.Purchase
import org.btmonier.computeTax
import org.btmonier.roundToCents
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Data Access Object for what was paid for a physical unit.
 *
 * A purchase belongs to a wishlist item while the order is in flight, and to a
 * release once the item is owned (or from the start, for releases that were
 * never wishlisted). The two references coexist so the wishlist row keeps its
 * history after conversion.
 */
class PurchaseDao {

    suspend fun getForRelease(releaseId: Int): Purchase? = DatabaseFactory.dbQuery {
        getForReleaseInTransaction(releaseId)
    }

    /**
     * Create or replace the purchase recorded on a release. Returns null when
     * the release does not exist.
     */
    suspend fun upsertForRelease(releaseId: Int, purchase: Purchase): Purchase? = DatabaseFactory.dbQuery {
        val exists = Releases.selectAll().where { Releases.id eq releaseId }.any()
        if (!exists) return@dbQuery null

        val normalized = normalize(purchase)
        val existingId = Purchases.selectAll()
            .where { Purchases.releaseId eq releaseId }
            .map { it[Purchases.id].value }
            .firstOrNull()

        if (existingId != null) {
            Purchases.update({ Purchases.id eq existingId }) { write(it, normalized) }
        } else {
            Purchases.insert {
                it[Purchases.releaseId] = releaseId
                write(it, normalized)
            }
        }
        getForReleaseInTransaction(releaseId)
    }

    suspend fun deleteForRelease(releaseId: Int): Boolean = DatabaseFactory.dbQuery {
        Purchases.deleteWhere { Purchases.releaseId eq releaseId } > 0
    }

    /**
     * Every purchase, for the spend totals on the wishlist summary.
     */
    internal fun allInTransaction(): List<Purchase> =
        Purchases.selectAll().map(::rowToPurchase)

    internal fun getForReleaseInTransaction(releaseId: Int): Purchase? =
        Purchases.selectAll().where { Purchases.releaseId eq releaseId }
            .map(::rowToPurchase)
            .firstOrNull()

    internal fun getForItemInTransaction(itemId: Int): Purchase? =
        Purchases.selectAll().where { Purchases.wishlistItemId eq itemId }
            .map(::rowToPurchase)
            .firstOrNull()

    /**
     * Create or replace the purchase on a wishlist item. Shipping and receipt
     * dates already stored are kept unless the new purchase names them.
     */
    internal fun upsertForItemInTransaction(itemId: Int, purchase: Purchase): Purchase {
        val existing = getForItemInTransaction(itemId)
        val merged = normalize(
            purchase.copy(
                shippedDate = purchase.shippedDate ?: existing?.shippedDate,
                receivedDate = purchase.receivedDate ?: existing?.receivedDate,
                trackingUrl = purchase.trackingUrl ?: existing?.trackingUrl
            )
        )

        if (existing?.id != null) {
            Purchases.update({ Purchases.id eq existing.id }) { write(it, merged) }
        } else {
            Purchases.insert {
                it[wishlistItemId] = itemId
                write(it, merged)
            }
        }
        return getForItemInTransaction(itemId)!!
    }

    /**
     * Record shipping details on an item's purchase, creating a zero-cost
     * purchase when nothing has been recorded yet so the dates are not lost.
     */
    internal fun updateShippingInTransaction(itemId: Int, shippedDate: String?, trackingUrl: String?) {
        val existing = getForItemInTransaction(itemId)
        if (existing == null) {
            if (shippedDate == null && trackingUrl == null) return
            upsertForItemInTransaction(
                itemId,
                Purchase(subtotal = 0.0, taxAmount = 0.0, shippedDate = shippedDate, trackingUrl = trackingUrl)
            )
            return
        }
        Purchases.update({ Purchases.id eq existing.id!! }) {
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
        val existing = getForItemInTransaction(itemId) ?: return
        Purchases.deleteWhere { (Purchases.releaseId eq releaseId) and (Purchases.id neq existing.id!!) }
        Purchases.update({ Purchases.id eq existing.id!! }) {
            it[Purchases.releaseId] = releaseId
            if (receivedDate != null) it[Purchases.receivedDate] = parseDate(receivedDate)
        }
    }

    /**
     * Forget that a release existed: purchases that also belong to a wishlist
     * item keep their cost but lose the release link, the rest are deleted.
     */
    internal fun detachFromReleaseInTransaction(releaseId: Int) {
        Purchases.update({ (Purchases.releaseId eq releaseId) and Purchases.wishlistItemId.isNotNull() }) {
            it[Purchases.releaseId] = null
        }
        Purchases.deleteWhere { Purchases.releaseId eq releaseId }
    }

    /**
     * Remove an item's purchase, unless it has already been attached to a
     * release, in which case only the item link is dropped.
     */
    internal fun detachFromItemInTransaction(itemId: Int) {
        val existing = getForItemInTransaction(itemId) ?: return
        if (existing.releaseId != null) {
            Purchases.update({ Purchases.id eq existing.id!! }) { it[wishlistItemId] = null }
        } else {
            Purchases.deleteWhere { Purchases.id eq existing.id!! }
        }
    }

    /**
     * Fill in the tax rate and amount when the client left them out, and round
     * every money field to cents.
     */
    internal fun normalize(purchase: Purchase): Purchase {
        val rate = purchase.taxRate ?: AppSettings.defaultTaxRate
        val subtotal = roundToCents(purchase.subtotal.coerceAtLeast(0.0))
        return purchase.copy(
            subtotal = subtotal,
            taxRate = rate,
            taxAmount = roundToCents(purchase.taxAmount ?: computeTax(subtotal, rate)),
            shipping = roundToCents(purchase.shipping.coerceAtLeast(0.0)),
            vendor = purchase.vendor?.trim()?.takeIf(String::isNotEmpty),
            orderNumber = purchase.orderNumber?.trim()?.takeIf(String::isNotEmpty),
            trackingUrl = purchase.trackingUrl?.trim()?.takeIf(String::isNotEmpty),
            notes = purchase.notes?.trim()?.takeIf(String::isNotEmpty)
        )
    }

    private fun write(statement: UpdateBuilder<*>, purchase: Purchase) {
        statement[Purchases.vendor] = purchase.vendor
        statement[Purchases.orderDate] = parseDate(purchase.orderDate)
        statement[Purchases.orderNumber] = purchase.orderNumber
        statement[Purchases.trackingUrl] = purchase.trackingUrl
        statement[Purchases.subtotal] = BigDecimal.valueOf(purchase.subtotal)
        statement[Purchases.taxRate] = BigDecimal.valueOf(purchase.taxRate ?: AppSettings.defaultTaxRate)
        statement[Purchases.taxAmount] = BigDecimal.valueOf(purchase.taxAmount ?: 0.0)
        statement[Purchases.shipping] = BigDecimal.valueOf(purchase.shipping)
        statement[Purchases.shippedDate] = parseDate(purchase.shippedDate)
        statement[Purchases.receivedDate] = parseDate(purchase.receivedDate)
        statement[Purchases.notes] = purchase.notes
    }

    private fun parseDate(value: String?): LocalDate? =
        value?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    internal fun rowToPurchase(row: ResultRow): Purchase = Purchase(
        subtotal = row[Purchases.subtotal].toDouble(),
        taxRate = row[Purchases.taxRate].toDouble(),
        taxAmount = row[Purchases.taxAmount].toDouble(),
        shipping = row[Purchases.shipping].toDouble(),
        vendor = row[Purchases.vendor],
        orderDate = row[Purchases.orderDate]?.toString(),
        orderNumber = row[Purchases.orderNumber],
        trackingUrl = row[Purchases.trackingUrl],
        shippedDate = row[Purchases.shippedDate]?.toString(),
        receivedDate = row[Purchases.receivedDate]?.toString(),
        notes = row[Purchases.notes],
        id = row[Purchases.id].value,
        releaseId = row[Purchases.releaseId]?.value,
        wishlistItemId = row[Purchases.wishlistItemId]?.value,
        createdAt = row[Purchases.createdAt].toString()
    )
}
