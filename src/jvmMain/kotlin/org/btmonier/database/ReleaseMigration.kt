package org.btmonier.database

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.insertAndGetId
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * One row of the legacy `physical_media` table, with its media types and images
 * already gathered from the child tables.
 */
data class LegacyPhysicalMediaRow(
    val id: Int,
    val movieId: Int,
    val entryLetter: String? = null,
    val title: String? = null,
    val alternateTitle: String? = null,
    val isCollection: Boolean? = null,
    val distributorId: Int? = null,
    val releaseDate: String? = null,
    val blurayComUrl: String? = null,
    val location: String? = null,
    val createdAt: String? = null,
    val mediaTypes: List<String> = emptyList(),
    val images: List<Pair<String, String?>> = emptyList()
)

/**
 * Folds the old one-row-per-film `physical_media` table into shared releases.
 *
 * Before this migration a box set was stored once per film it contained, so a
 * 200 film set meant 200 rows repeating the same title, distributor, date, cover
 * art and shelf location. Afterwards there is a single `releases` row per
 * physical unit and a `release_movies` link per film on it.
 *
 * The migration is guarded on the legacy table still being present, so it does
 * nothing on a freshly created database or on one that has already been
 * migrated. The legacy tables are renamed rather than dropped, which keeps the
 * original data available for verification.
 */
object ReleaseMigration {

    private const val LEGACY_TABLE = "physical_media"

    /**
     * Builds the key that decides which legacy rows describe the same physical
     * unit. Ordered from the strongest signal to the weakest:
     *
     * 1. A blu-ray.com URL identifies exactly one release, so rows sharing one
     *    are the same unit. This is what catches box sets.
     * 2. Otherwise a title, together with the distributor, date and formats.
     * 3. Rows with neither a URL nor a title stay on their own. Merging those
     *    would collapse unrelated films that happen to share a distributor.
     */
    fun groupKey(row: LegacyPhysicalMediaRow): String {
        val url = row.blurayComUrl?.trim().orEmpty()
        if (url.isNotEmpty()) {
            val match = Regex("""blu-ray\.com/(movies|dvd)/[^/]+/(\d+)""", RegexOption.IGNORE_CASE).find(url)
            if (match != null) {
                return "url:${match.groupValues[1].lowercase()}:${match.groupValues[2]}"
            }
            return "url:${url.lowercase().substringBefore('?').trimEnd('/')}"
        }

        val title = row.title?.trim().orEmpty()
        if (title.isNotEmpty()) {
            val formats = row.mediaTypes.map { it.trim().lowercase() }.distinct().sorted().joinToString(",")
            return "title:${title.lowercase()}|d:${row.distributorId ?: ""}|r:${row.releaseDate ?: ""}|t:$formats"
        }

        return "row:${row.id}"
    }

    /**
     * Groups legacy rows into the releases they will become, preserving the
     * order in which the groups were first seen so reports read predictably.
     */
    fun planGroups(rows: List<LegacyPhysicalMediaRow>): List<List<LegacyPhysicalMediaRow>> =
        rows.sortedBy { it.id }.groupBy { groupKey(it) }.values.toList()

    /**
     * True when the legacy table is still present and needs folding into releases.
     */
    fun isPending(database: Database? = null): Boolean = transaction(database) {
        tableExists(LEGACY_TABLE)
    }

    /**
     * Apply the migration. Safe to call on every startup.
     *
     * @param dryRun Report what would happen without writing anything.
     * @param verbose Print a line for every group that collapses several rows.
     */
    fun migratePhysicalMediaToReleases(
        database: Database? = null,
        dryRun: Boolean = false,
        verbose: Boolean = false
    ) {
        transaction(database) {
            if (!tableExists(LEGACY_TABLE)) return@transaction

            val rows = readLegacyRows()
            if (rows.isEmpty()) {
                println("No physical media rows to migrate.")
                if (!dryRun) renameLegacyTables()
                return@transaction
            }

            val groups = planGroups(rows)
            val collapsed = groups.filter { it.size > 1 }

            println("Folding ${rows.size} physical media row(s) into ${groups.size} release(s)...")
            if (collapsed.isNotEmpty()) {
                val filmsSaved = rows.size - groups.size
                println("  ${collapsed.size} release(s) are shared by more than one film ($filmsSaved duplicate row(s) removed)")
                val shown = if (verbose) collapsed else collapsed.sortedByDescending { it.size }.take(10)
                shown.sortedByDescending { it.size }.forEach { group ->
                    val label = group.firstNotNullOfOrNull { it.title?.trim()?.takeIf(String::isNotEmpty) }
                        ?: group.firstNotNullOfOrNull { it.blurayComUrl }
                        ?: "(untitled)"
                    println("    ${group.size} films: $label")
                }
                if (!verbose && collapsed.size > shown.size) {
                    println("    ... and ${collapsed.size - shown.size} more (run with --verbose to list them all)")
                }
            }

            reportConflicts(groups)

            if (dryRun) {
                println("Dry run: no changes written.")
                return@transaction
            }

            groups.forEach { writeRelease(it) }
            renameLegacyTables()
            println("  done: releases, release_movies, release_media_types and release_images populated")
            println("  the legacy tables are still available as ${LEGACY_TABLE}_legacy and can be dropped once verified")
        }
    }

    private fun Transaction.writeRelease(group: List<LegacyPhysicalMediaRow>) {
        val releaseId = Releases.insertAndGetId {
            it[title] = winner(group) { row -> row.title?.trim()?.takeIf(String::isNotEmpty) }
            it[isCollection] = winner(group) { row -> row.isCollection } ?: (group.size > 1)
            it[distributorId] = winner(group) { row -> row.distributorId }?.let { id -> EntityID(id, Distributors) }
            it[releaseDate] = winner(group) { row -> row.releaseDate }?.let(LocalDate::parse)
            it[blurayComUrl] = winner(group) { row -> row.blurayComUrl?.trim()?.takeIf(String::isNotEmpty) }
            it[location] = winner(group) { row -> row.location?.trim()?.takeIf(String::isNotEmpty) }
            group.mapNotNull { row -> row.createdAt }.minOrNull()?.let { earliest ->
                it[createdAt] = LocalDateTime.parse(earliest.replace(' ', 'T').substringBefore('+'))
            }
        }.value

        group.flatMap { it.mediaTypes }.distinct().forEach { type ->
            ReleaseMediaTypes.insert {
                it[ReleaseMediaTypes.releaseId] = releaseId
                it[mediaType] = type
            }
        }

        group.flatMap { it.images }
            .distinctBy { it.first }
            .forEach { (url, description) ->
                ReleaseImages.insert {
                    it[ReleaseImages.releaseId] = releaseId
                    it[imageUrl] = url
                    it[ReleaseImages.description] = description
                }
            }

        // A film could in principle appear twice in one group (two identical rows
        // on the same movie); keep only the first so the link stays unique.
        group.distinctBy { it.movieId }.forEach { row ->
            ReleaseMovies.insert {
                it[ReleaseMovies.releaseId] = releaseId
                it[movieId] = row.movieId
                it[entryLetter] = row.entryLetter
                it[alternateTitle] = row.alternateTitle
            }
        }
    }

    /**
     * Picks the value to keep for a release-level field when the rows being
     * folded together disagree: the most common non-null value wins.
     */
    private fun <T : Any> winner(group: List<LegacyPhysicalMediaRow>, select: (LegacyPhysicalMediaRow) -> T?): T? =
        group.mapNotNull(select)
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key

    private fun reportConflicts(groups: List<List<LegacyPhysicalMediaRow>>) {
        val fields = listOf<Pair<String, (LegacyPhysicalMediaRow) -> Any?>>(
            "location" to { row -> row.location?.trim()?.takeIf(String::isNotEmpty) },
            "release date" to { row -> row.releaseDate },
            "distributor" to { row -> row.distributorId },
            "title" to { row -> row.title?.trim()?.takeIf(String::isNotEmpty) }
        )

        groups.filter { it.size > 1 }.forEach { group ->
            fields.forEach { (name, select) ->
                val values = group.mapNotNull(select).distinct()
                if (values.size > 1) {
                    val kept = winner(group) { row -> select(row) }
                    val label = group.firstNotNullOfOrNull { it.title } ?: group.firstNotNullOfOrNull { it.blurayComUrl } ?: "(untitled)"
                    println("  conflict on $name for \"$label\": ${values.joinToString(", ")} - keeping \"$kept\"")
                }
            }
        }
    }

    private fun Transaction.readLegacyRows(): List<LegacyPhysicalMediaRow> {
        val hasAlternateTitle = columnExists(LEGACY_TABLE, "alternate_title")
        val hasIsCollection = columnExists(LEGACY_TABLE, "is_collection")

        val mediaTypesByMedia = mutableMapOf<Int, MutableList<String>>()
        exec("SELECT physical_media_id, media_type FROM physical_media_types") { rs ->
            while (rs.next()) {
                mediaTypesByMedia.getOrPut(rs.getInt(1)) { mutableListOf() }.add(rs.getString(2))
            }
        }

        val imagesByMedia = mutableMapOf<Int, MutableList<Pair<String, String?>>>()
        exec("SELECT physical_media_id, image_url, description FROM physical_media_images") { rs ->
            while (rs.next()) {
                imagesByMedia.getOrPut(rs.getInt(1)) { mutableListOf() }.add(rs.getString(2) to rs.getString(3))
            }
        }

        val alternateTitleColumn = if (hasAlternateTitle) "alternate_title" else "NULL AS alternate_title"
        val isCollectionColumn = if (hasIsCollection) "is_collection" else "NULL AS is_collection"

        return exec(
            """
            SELECT id, movie_id, entry_letter, title, $alternateTitleColumn, $isCollectionColumn,
                   distributor_id, release_date, bluray_com_url, location, created_at
            FROM $LEGACY_TABLE
            ORDER BY id
            """.trimIndent()
        ) { rs ->
            buildList {
                while (rs.next()) {
                    val id = rs.getInt("id")
                    add(
                        LegacyPhysicalMediaRow(
                            id = id,
                            movieId = rs.getInt("movie_id"),
                            entryLetter = rs.getString("entry_letter"),
                            title = rs.getString("title"),
                            alternateTitle = rs.getString("alternate_title"),
                            isCollection = rs.getBoolean("is_collection").takeUnless { rs.wasNull() },
                            distributorId = rs.getInt("distributor_id").takeUnless { rs.wasNull() },
                            releaseDate = rs.getDate("release_date")?.toLocalDate()?.toString(),
                            blurayComUrl = rs.getString("bluray_com_url"),
                            location = rs.getString("location"),
                            createdAt = rs.getTimestamp("created_at")?.toLocalDateTime()?.toString(),
                            mediaTypes = mediaTypesByMedia[id].orEmpty().distinct(),
                            images = imagesByMedia[id].orEmpty()
                        )
                    )
                }
            }
        } ?: emptyList()
    }

    private fun Transaction.renameLegacyTables() {
        listOf("physical_media_types", "physical_media_images", LEGACY_TABLE).forEach { table ->
            if (tableExists(table)) {
                exec("ALTER TABLE $table RENAME TO ${table}_legacy")
            }
        }
    }

    private fun Transaction.tableExists(table: String): Boolean = selectInt(
        """
        SELECT count(*) FROM information_schema.tables
        WHERE table_schema = current_schema() AND table_name = '$table'
        """.trimIndent()
    ) > 0

    private fun Transaction.columnExists(table: String, column: String): Boolean = selectInt(
        """
        SELECT count(*) FROM information_schema.columns
        WHERE table_name = '$table' AND column_name = '$column'
        """.trimIndent()
    ) > 0

    private fun Transaction.selectInt(sql: String): Int =
        exec(sql) { rs -> if (rs.next()) rs.getInt(1) else 0 } ?: 0
}

/**
 * CLI tool that folds physical media rows into shared releases and reports the
 * result. DatabaseFactory.init() already runs this on server startup; this task
 * exists for previewing the grouping before it happens.
 */
class ReleaseMigrationCommand : CliktCommand(name = "migrate-releases") {
    private val dryRun by option("--dry-run", help = "Report the grouping without writing anything").flag()
    private val verbose by option("--verbose", help = "List every release shared by more than one film").flag()

    override fun run() {
        try {
            DatabaseFactory.init(skipReleaseMigration = true)
        } catch (e: Exception) {
            echo("Failed to initialize the database: ${e.message}", err = true)
            throw e
        }

        if (!ReleaseMigration.isPending()) {
            echo("No physical_media table remains - physical media already uses shared releases.")
            return
        }

        ReleaseMigration.migratePhysicalMediaToReleases(dryRun = dryRun, verbose = verbose)
    }
}

fun main(args: Array<String>) = ReleaseMigrationCommand().main(args)
