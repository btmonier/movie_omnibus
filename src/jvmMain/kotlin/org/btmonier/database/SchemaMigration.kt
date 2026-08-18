package org.btmonier.database

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Adds nullable columns that were introduced after a table already existed.
 *
 * SchemaUtils.create only creates missing tables, never missing columns, so a
 * new column on an existing table has to be applied explicitly. Every entry
 * here must be nullable and have no default, which makes ADD COLUMN IF NOT
 * EXISTS both idempotent and cheap enough to run on every startup.
 */
object SchemaMigration {

    private data class AdditiveColumn(val table: String, val column: String, val type: String)

    // physical_media only exists until ReleaseMigration folds it into releases,
    // so these two entries are no-ops on any database that has been migrated and
    // on every database created from scratch afterwards. They stay because the
    // release migration reads both columns off the legacy table.
    private val additiveColumns = listOf(
        AdditiveColumn("physical_media", "alternate_title", "varchar(500)"),
        AdditiveColumn("physical_media", "is_collection", "boolean")
    )

    fun addMissingColumns(database: Database? = null) {
        transaction(database) {
            additiveColumns
                .filter { tableExists(it.table) }
                .forEach { spec ->
                    exec("ALTER TABLE ${spec.table} ADD COLUMN IF NOT EXISTS ${spec.column} ${spec.type}")
                }
        }
    }

    private fun Transaction.tableExists(table: String): Boolean = exec(
        """
        SELECT count(*) FROM information_schema.tables
        WHERE table_schema = current_schema() AND table_name = '$table'
        """.trimIndent()
    ) { rs -> if (rs.next()) rs.getInt(1) > 0 else false } ?: false
}
