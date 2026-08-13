package org.btmonier.database

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * Converts the categorical columns that used to hold a raw string on every row
 * (movie_themes.theme, movie_countries.country, physical_media.distributor) into
 * foreign keys pointing at lookup tables, so each value has a single primary
 * source that can be renamed in one place.
 *
 * Each step is guarded on the presence of the legacy column, so running this
 * against an already migrated database does nothing. SchemaUtils.create never
 * alters existing tables, which is why this has to be done explicitly.
 */
object CategoryMigration {

    /**
     * Describes one legacy string column and the lookup table it moves into.
     *
     * @param dedupeOwnerColumn Column identifying the owning entity, used to drop
     *   duplicate pairs created when two spellings collapse onto one lookup row.
     *   Null for one-to-one columns such as physical_media.distributor.
     * @param nullable Whether the new foreign key column allows nulls. Non-nullable
     *   columns belong to junction tables, where rows holding a blank value are
     *   meaningless and get deleted.
     */
    private data class LegacyColumn(
        val table: String,
        val legacyColumn: String,
        val lookupTable: String,
        val fkColumn: String,
        val dedupeOwnerColumn: String?,
        val nullable: Boolean
    )

    private val legacyColumns = listOf(
        LegacyColumn("movie_themes", "theme", "themes", "theme_id", "movie_id", nullable = false),
        LegacyColumn("movie_countries", "country", "countries", "country_id", "movie_id", nullable = false),
        LegacyColumn("physical_media", "distributor", "distributors", "distributor_id", null, nullable = true)
    )

    /**
     * Apply any pending category migrations. Safe to call on every startup.
     */
    fun migrateCategoriesToLookupTables(database: Database? = null) {
        transaction(database) {
            legacyColumns.forEach { migrate(it) }
        }
    }

    /**
     * Legacy columns still awaiting migration, as "table.column" strings.
     */
    fun pendingLegacyColumns(database: Database? = null): List<String> = transaction(database) {
        legacyColumns
            .filter { columnExists(it.table, it.legacyColumn) }
            .map { "${it.table}.${it.legacyColumn}" }
    }

    /**
     * Row counts of every lookup table, for reporting.
     */
    fun lookupTableCounts(database: Database? = null): Map<String, Int> = transaction(database) {
        listOf("genres", "subgenres", "collections", "distributors", "themes", "countries")
            .associateWith { table -> selectInt("SELECT count(*) FROM $table") }
    }

    private fun Transaction.migrate(spec: LegacyColumn) {
        if (!columnExists(spec.table, spec.legacyColumn)) return

        println("Migrating ${spec.table}.${spec.legacyColumn} into the ${spec.lookupTable} lookup table...")

        logCollapsedVariants(spec)

        val lookupRowsBefore = selectInt("SELECT count(*) FROM ${spec.lookupTable}")
        exec(insertCanonicalNamesSql(spec))
        val lookupRowsAfter = selectInt("SELECT count(*) FROM ${spec.lookupTable}")
        println("  ${lookupRowsAfter - lookupRowsBefore} new ${spec.lookupTable} entries (${lookupRowsAfter} total)")

        exec("ALTER TABLE ${spec.table} ADD COLUMN IF NOT EXISTS ${spec.fkColumn} INT")
        exec(
            """
            UPDATE ${spec.table} src
            SET ${spec.fkColumn} = lookup.id
            FROM ${spec.lookupTable} lookup
            WHERE lower(lookup.name) = lower(btrim(src.${spec.legacyColumn}))
              AND src.${spec.fkColumn} IS NULL
            """.trimIndent()
        )

        if (!spec.nullable) {
            val blankRows = selectInt("SELECT count(*) FROM ${spec.table} WHERE ${spec.fkColumn} IS NULL")
            if (blankRows > 0) {
                exec("DELETE FROM ${spec.table} WHERE ${spec.fkColumn} IS NULL")
                println("  removed $blankRows row(s) that held a blank ${spec.legacyColumn}")
            }
        }

        spec.dedupeOwnerColumn?.let { owner ->
            val before = selectInt("SELECT count(*) FROM ${spec.table}")
            exec(
                """
                DELETE FROM ${spec.table} a
                USING ${spec.table} b
                WHERE a.$owner = b.$owner
                  AND a.${spec.fkColumn} = b.${spec.fkColumn}
                  AND a.id > b.id
                """.trimIndent()
            )
            val removed = before - selectInt("SELECT count(*) FROM ${spec.table}")
            if (removed > 0) {
                println("  removed $removed duplicate assignment(s) created by collapsing spellings")
            }
        }

        if (!spec.nullable) {
            exec("ALTER TABLE ${spec.table} ALTER COLUMN ${spec.fkColumn} SET NOT NULL")
        }

        val constraintName = "fk_${spec.table}_${spec.fkColumn}"
        if (!constraintExists(constraintName)) {
            exec(
                "ALTER TABLE ${spec.table} ADD CONSTRAINT $constraintName " +
                    "FOREIGN KEY (${spec.fkColumn}) REFERENCES ${spec.lookupTable}(id)"
            )
        }

        exec("ALTER TABLE ${spec.table} DROP COLUMN ${spec.legacyColumn}")
        println("  done: ${spec.table}.${spec.legacyColumn} replaced by ${spec.fkColumn}")
    }

    /**
     * Inserts one row per distinct value, ignoring surrounding whitespace and
     * letter case. When spellings differ only by case, the most frequently used
     * one wins.
     */
    private fun insertCanonicalNamesSql(spec: LegacyColumn) = """
        INSERT INTO ${spec.lookupTable} (name)
        SELECT candidates.name FROM (
            SELECT btrim(${spec.legacyColumn}) AS name,
                   row_number() OVER (
                       PARTITION BY lower(btrim(${spec.legacyColumn}))
                       ORDER BY count(*) DESC, btrim(${spec.legacyColumn})
                   ) AS variant_rank
            FROM ${spec.table}
            WHERE ${spec.legacyColumn} IS NOT NULL AND btrim(${spec.legacyColumn}) <> ''
            GROUP BY btrim(${spec.legacyColumn})
        ) candidates
        WHERE candidates.variant_rank = 1
          AND NOT EXISTS (
              SELECT 1 FROM ${spec.lookupTable} existing
              WHERE lower(existing.name) = lower(candidates.name)
          )
    """.trimIndent()

    private fun Transaction.logCollapsedVariants(spec: LegacyColumn) {
        val variants = exec(
            """
            SELECT btrim(${spec.legacyColumn}) AS name, count(*) AS uses
            FROM ${spec.table}
            WHERE ${spec.legacyColumn} IS NOT NULL AND btrim(${spec.legacyColumn}) <> ''
            GROUP BY btrim(${spec.legacyColumn})
            """.trimIndent()
        ) { rs ->
            buildList {
                while (rs.next()) add(rs.getString("name") to rs.getInt("uses"))
            }
        } ?: emptyList()

        variants
            .groupBy { it.first.lowercase() }
            .values
            .filter { it.size > 1 }
            .forEach { group ->
                val winner = group.maxByOrNull { it.second }!!.first
                val losers = group.map { it.first }.filter { it != winner }
                println("  collapsing ${losers.joinToString(", ") { "\"$it\"" }} into \"$winner\"")
            }
    }

    private fun Transaction.columnExists(table: String, column: String): Boolean = selectInt(
        """
        SELECT count(*) FROM information_schema.columns
        WHERE table_name = '$table' AND column_name = '$column'
        """.trimIndent()
    ) > 0

    private fun Transaction.constraintExists(name: String): Boolean =
        selectInt("SELECT count(*) FROM pg_constraint WHERE conname = '$name'") > 0

    private fun Transaction.selectInt(sql: String): Int =
        exec(sql) { rs -> if (rs.next()) rs.getInt(1) else 0 } ?: 0
}

/**
 * CLI tool that applies the category migration and reports the result.
 * DatabaseFactory.init() already runs this on server startup; this task exists
 * for running it (and inspecting the outcome) on its own.
 */
class CategoryMigrationCommand : CliktCommand(name = "migrate-categories") {
    override fun run() {
        val pendingBefore = try {
            DatabaseFactory.init()
            CategoryMigration.pendingLegacyColumns()
        } catch (e: Exception) {
            echo("Failed to initialize the database: ${e.message}", err = true)
            throw e
        }

        if (pendingBefore.isEmpty()) {
            echo("No legacy category columns remain - all categories use lookup tables.")
        } else {
            echo("Legacy columns still present after migration: ${pendingBefore.joinToString(", ")}", err = true)
        }

        echo("")
        echo("Lookup table entry counts:")
        CategoryMigration.lookupTableCounts().forEach { (table, count) ->
            echo("  $table: $count")
        }
    }
}

fun main(args: Array<String>) = CategoryMigrationCommand().main(args)
