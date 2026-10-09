package org.btmonier.database

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import org.btmonier.computeTax
import org.btmonier.roundToCents
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * One purchase as it was stored before orders existed, carrying its own copy
 * of the order-level fields.
 */
data class LegacyPurchaseRow(
    val id: Int,
    val storeId: Int?,
    val orderDate: LocalDate?,
    val orderNumber: String?,
    val subtotal: Double,
    val taxRate: Double,
    val taxAmount: Double,
    val shipping: Double,
    val notes: String?,
    val createdAt: LocalDateTime?
)

/**
 * Moves the order-level fields (store, date, order number, tax, shipping,
 * notes) off each purchase and onto an `orders` row, leaving the purchase as
 * one item's line on that order.
 *
 * Purchases sharing a store and a non-blank order number were one checkout
 * typed in item by item, so they become one order whose tax and shipping are
 * the sums of theirs - every historical total stays exactly what it was.
 * Every other purchase becomes an order of one.
 *
 * Guarded on the legacy `tax_amount` column, so it does nothing on a fresh
 * database or one already migrated. The moved columns are renamed with a
 * `_legacy` suffix rather than dropped, so the original values can be checked.
 */
object OrderMigration {

    private val movedColumns = listOf(
        "store_id", "order_date", "order_number", "tax_rate", "tax_amount", "shipping", "notes"
    )

    /** Which purchases were one checkout: same store and same order number. */
    fun groupKey(row: LegacyPurchaseRow): String {
        val number = row.orderNumber?.trim().orEmpty()
        if (number.isEmpty()) return "row:${row.id}"
        return "store:${row.storeId ?: ""}|order:${number.lowercase()}"
    }

    fun isPending(database: Database? = null): Boolean = transaction(database) {
        columnExists("purchases", "tax_amount")
    }

    fun migratePurchasesToOrders(database: Database? = null, dryRun: Boolean = false) {
        transaction(database) {
            if (!columnExists("purchases", "tax_amount")) return@transaction

            val rows = readLegacyRows()
            val groups = rows.groupBy { groupKey(it) }.values.toList()
            val shared = groups.filter { it.size > 1 }

            println("Moving ${rows.size} purchase(s) onto ${groups.size} order(s)...")
            shared.forEach { group ->
                val first = group.first()
                println("  order ${first.orderNumber?.trim()}: ${group.size} purchases (ids ${group.joinToString(", ") { it.id.toString() }})")
            }

            if (dryRun) {
                println("Dry run - nothing written.")
                return@transaction
            }

            groups.forEach { group -> insertOrder(group) }
            retireLegacyColumns()
            println("  done: ${groups.size} order(s), ${shared.size} of them holding more than one item")
        }
    }

    private fun Transaction.readLegacyRows(): List<LegacyPurchaseRow> = exec(
        """
        SELECT id, store_id, order_date, order_number, subtotal, tax_rate, tax_amount, shipping, notes, created_at
        FROM purchases
        WHERE order_id IS NULL
        ORDER BY id
        """.trimIndent()
    ) { rs ->
        buildList {
            while (rs.next()) {
                add(
                    LegacyPurchaseRow(
                        id = rs.getInt("id"),
                        storeId = rs.getInt("store_id").takeUnless { rs.wasNull() },
                        orderDate = rs.getDate("order_date")?.toLocalDate(),
                        orderNumber = rs.getString("order_number"),
                        subtotal = rs.getBigDecimal("subtotal")?.toDouble() ?: 0.0,
                        taxRate = rs.getBigDecimal("tax_rate")?.toDouble() ?: 0.0,
                        taxAmount = rs.getBigDecimal("tax_amount")?.toDouble() ?: 0.0,
                        shipping = rs.getBigDecimal("shipping")?.toDouble() ?: 0.0,
                        notes = rs.getString("notes"),
                        createdAt = rs.getTimestamp("created_at")?.toLocalDateTime()
                    )
                )
            }
        }
    } ?: emptyList()

    private fun Transaction.insertOrder(group: List<LegacyPurchaseRow>) {
        val rate = group.groupingBy { it.taxRate }.eachCount().maxByOrNull { it.value }!!.key
        val subtotal = roundToCents(group.sumOf { it.subtotal })
        val tax = roundToCents(group.sumOf { it.taxAmount })
        // Only kept as an override when it is not what the rate would give anyway
        val override = tax.takeUnless { group.all { it.taxRate == rate } && computeTax(subtotal, rate) == tax }
        val notes = group.mapNotNull { it.notes?.trim()?.takeIf(String::isNotEmpty) }.distinct()

        val orderId = Orders.insertAndGetId {
            it[storeId] = group.firstNotNullOfOrNull { row -> row.storeId }?.let { id -> EntityID(id, Stores) }
            it[orderDate] = group.mapNotNull { row -> row.orderDate }.minOrNull()
            it[orderNumber] = group.firstNotNullOfOrNull { row -> row.orderNumber?.trim()?.takeIf(String::isNotEmpty) }
            it[taxRate] = BigDecimal.valueOf(rate)
            it[taxAmount] = override?.let(BigDecimal::valueOf)
            it[shipping] = BigDecimal.valueOf(roundToCents(group.sumOf { row -> row.shipping }))
            it[Orders.notes] = notes.joinToString("\n").takeIf(String::isNotEmpty)
            group.mapNotNull { row -> row.createdAt }.minOrNull()?.let { created -> it[createdAt] = created }
        }.value

        exec("UPDATE purchases SET order_id = $orderId WHERE id IN (${group.joinToString(",") { it.id.toString() }})")
    }

    private fun Transaction.retireLegacyColumns() {
        // The store reference would otherwise keep blocking a store's deletion
        foreignKeysOn("purchases", "store_id").forEach { name ->
            exec("ALTER TABLE purchases DROP CONSTRAINT \"$name\"")
        }
        movedColumns.filter { columnExists("purchases", it) }.forEach { column ->
            exec("ALTER TABLE purchases RENAME COLUMN $column TO ${column}_legacy")
            exec("ALTER TABLE purchases ALTER COLUMN ${column}_legacy DROP NOT NULL")
        }

        exec("ALTER TABLE purchases ALTER COLUMN order_id SET NOT NULL")
        if (foreignKeysOn("purchases", "order_id").isEmpty()) {
            exec("ALTER TABLE purchases ADD CONSTRAINT fk_purchases_order_id FOREIGN KEY (order_id) REFERENCES orders(id)")
        }
    }

    private fun Transaction.foreignKeysOn(table: String, column: String): List<String> = exec(
        """
        SELECT tc.constraint_name
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
          ON tc.constraint_name = kcu.constraint_name AND tc.table_name = kcu.table_name
        WHERE tc.table_name = '$table' AND tc.constraint_type = 'FOREIGN KEY' AND kcu.column_name = '$column'
        """.trimIndent()
    ) { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } ?: emptyList()

    private fun Transaction.columnExists(table: String, column: String): Boolean = exec(
        """
        SELECT count(*) FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = '$table' AND column_name = '$column'
        """.trimIndent()
    ) { rs -> if (rs.next()) rs.getInt(1) > 0 else false } ?: false
}

/**
 * CLI tool that moves purchases onto orders and reports the grouping.
 * DatabaseFactory.init() already runs this on server startup; this task exists
 * for previewing which purchases will be merged before it happens.
 */
class OrderMigrationCommand : CliktCommand(name = "migrate-orders") {
    private val dryRun by option("--dry-run", help = "Report the grouping without writing anything").flag()

    override fun run() {
        try {
            DatabaseFactory.init(skipOrderMigration = true)
        } catch (e: Exception) {
            echo("Failed to initialize the database: ${e.message}", err = true)
            throw e
        }

        if (!OrderMigration.isPending()) {
            echo("No legacy purchase columns remain - purchases already belong to orders.")
            return
        }

        OrderMigration.migratePurchasesToOrders(dryRun = dryRun)
    }
}

fun main(args: Array<String>) = OrderMigrationCommand().main(args)
