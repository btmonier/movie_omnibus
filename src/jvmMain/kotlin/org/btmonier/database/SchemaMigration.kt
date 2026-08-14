package org.btmonier.database

import org.jetbrains.exposed.sql.Database
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

    private val additiveColumns = listOf(
        AdditiveColumn("physical_media", "alternate_title", "varchar(500)"),
        AdditiveColumn("physical_media", "is_collection", "boolean")
    )

    fun addMissingColumns(database: Database? = null) {
        transaction(database) {
            additiveColumns.forEach { spec ->
                exec("ALTER TABLE ${spec.table} ADD COLUMN IF NOT EXISTS ${spec.column} ${spec.type}")
            }
        }
    }
}
