package org.btmonier.database

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.transactions.TransactionManager

/**
 * The kinds of categorical data that live in their own lookup table and can be
 * renamed, merged, or deleted in one place.
 */
enum class CategoryType(val slug: String, val label: String) {
    GENRE("genres", "Genres"),
    SUBGENRE("subgenres", "Subgenres"),
    COLLECTION("collections", "Collections"),
    DISTRIBUTOR("distributors", "Distributors"),
    THEME("themes", "Themes"),
    COUNTRY("countries", "Countries");

    companion object {
        fun fromSlug(slug: String): CategoryType? =
            entries.firstOrNull { it.slug.equals(slug, ignoreCase = true) }
    }
}

/**
 * One entry of a category lookup table, plus how many movies reference it.
 */
data class CategoryEntry(
    val id: Int,
    val name: String,
    val description: String? = null,
    val usageCount: Int = 0
)

/**
 * Outcome of renaming a category entry. A rename onto a name that is already
 * taken is only carried out when merging was explicitly allowed.
 */
sealed interface RenameOutcome {
    data class Renamed(val entry: CategoryEntry) : RenameOutcome
    data class Merged(val entry: CategoryEntry, val mergedName: String, val moviesUpdated: Int) : RenameOutcome
    data class NameTaken(val existing: CategoryEntry) : RenameOutcome
    data object NotFound : RenameOutcome
}

/**
 * Data Access Object covering every category lookup table with one
 * implementation. Renaming an entry here is all it takes to update every movie
 * that uses it, because movies only ever reference the lookup row.
 */
class CategoryDao {

    /**
     * Maps a category to its lookup table and to the rows that reference it.
     *
     * Lookup columns are typed so that user-supplied names are always bound as
     * parameters. The referencing side is addressed by name because the six
     * category types disagree on nullability, and the operations there only ever
     * interpolate integer ids.
     *
     * @param usageOwnerColumn Column identifying the movie that uses the entry.
     * @param usageIsOptional True when the reference can be nulled out instead of
     *   deleting the referencing row (physical media outlives its distributor).
     * @param usageIsUnique True when (owner, entry) pairs must stay unique, which
     *   requires dropping duplicates after a merge.
     */
    private data class Spec(
        val table: IntIdTable,
        val nameColumn: Column<String>,
        val descriptionColumn: Column<String?>?,
        val usageTable: String,
        val usageForeignKey: String,
        val usageOwnerColumn: String,
        val usageIsOptional: Boolean,
        val usageIsUnique: Boolean
    )

    private val specs: Map<CategoryType, Spec> = mapOf(
        CategoryType.GENRE to Spec(
            Genres, Genres.name, null,
            "movie_genres", "genre_id", "movie_id", usageIsOptional = false, usageIsUnique = true
        ),
        CategoryType.SUBGENRE to Spec(
            Subgenres, Subgenres.name, null,
            "movie_subgenres", "subgenre_id", "movie_id", usageIsOptional = false, usageIsUnique = true
        ),
        CategoryType.COLLECTION to Spec(
            Collections, Collections.name, Collections.description,
            "movie_collections", "collection_id", "movie_id", usageIsOptional = false, usageIsUnique = true
        ),
        // Counted per release rather than per movie: a distributor's reach is the
        // number of physical units it published, and a box set is one of those no
        // matter how many films it holds.
        CategoryType.DISTRIBUTOR to Spec(
            Distributors, Distributors.name, null,
            "releases", "distributor_id", "id", usageIsOptional = true, usageIsUnique = false
        ),
        CategoryType.THEME to Spec(
            Themes, Themes.name, null,
            "movie_themes", "theme_id", "movie_id", usageIsOptional = false, usageIsUnique = true
        ),
        CategoryType.COUNTRY to Spec(
            Countries, Countries.name, null,
            "movie_countries", "country_id", "movie_id", usageIsOptional = false, usageIsUnique = true
        )
    )

    private fun spec(type: CategoryType): Spec = specs.getValue(type)

    /**
     * All entries of a category, alphabetically, each with the number of movies
     * that use it.
     */
    suspend fun list(type: CategoryType): List<CategoryEntry> = DatabaseFactory.dbQuery {
        val spec = spec(type)
        val usageCounts = usageCounts(spec)

        spec.table.selectAll()
            .orderBy(spec.nameColumn to SortOrder.ASC)
            .map { row ->
                val id = row[spec.table.id].value
                CategoryEntry(
                    id = id,
                    name = row[spec.nameColumn],
                    description = spec.descriptionColumn?.let { row[it] },
                    usageCount = usageCounts[id] ?: 0
                )
            }
    }

    /**
     * A single entry, including its usage count.
     */
    suspend fun get(type: CategoryType, id: Int): CategoryEntry? = DatabaseFactory.dbQuery {
        readEntry(spec(type), id)
    }

    /**
     * Find an entry by name, ignoring case and surrounding whitespace.
     */
    suspend fun findByName(type: CategoryType, name: String): CategoryEntry? = DatabaseFactory.dbQuery {
        val spec = spec(type)
        val target = name.trim().lowercase()
        spec.table.selectAll()
            .where { spec.nameColumn.lowerCase() eq target }
            .map { it[spec.table.id].value }
            .firstOrNull()
            ?.let { readEntry(spec, it) }
    }

    /**
     * Create a new entry. Returns null when the name is already taken.
     */
    suspend fun create(type: CategoryType, name: String, description: String? = null): CategoryEntry? =
        DatabaseFactory.dbQuery {
            val spec = spec(type)
            val trimmed = name.trim()

            if (existingIdWithName(spec, trimmed) != null) {
                return@dbQuery null
            }

            val id = spec.table.insertAndGetId { statement ->
                statement[spec.nameColumn] = trimmed
                spec.descriptionColumn?.let { statement[it] = description }
            }.value

            readEntry(spec, id)
        }

    /**
     * Rename an entry, which updates every movie that references it.
     *
     * When the new name already belongs to another entry the two are merged (all
     * references are repointed at the surviving entry and the renamed one is
     * removed) - but only if [allowMerge] is set, so callers can confirm first.
     */
    suspend fun rename(
        type: CategoryType,
        id: Int,
        newName: String,
        description: String? = null,
        allowMerge: Boolean = false
    ): RenameOutcome = DatabaseFactory.dbQuery {
        val spec = spec(type)
        val current = readEntry(spec, id) ?: return@dbQuery RenameOutcome.NotFound
        val trimmed = newName.trim()
        val conflictId = existingIdWithName(spec, trimmed)?.takeIf { it != id }

        if (conflictId != null) {
            val target = readEntry(spec, conflictId)!!
            if (!allowMerge) {
                return@dbQuery RenameOutcome.NameTaken(target)
            }

            val moviesUpdated = repoint(spec, listOf(id), conflictId)
            return@dbQuery RenameOutcome.Merged(
                entry = readEntry(spec, conflictId)!!,
                mergedName = current.name,
                moviesUpdated = moviesUpdated
            )
        }

        spec.table.update({ spec.table.id eq id }) { statement ->
            statement[spec.nameColumn] = trimmed
            spec.descriptionColumn?.let { statement[it] = description }
        }

        RenameOutcome.Renamed(readEntry(spec, id)!!)
    }

    /**
     * Merge entries into one: every reference to a source entry is repointed at
     * the target and the source entries are deleted. Returns the number of movies
     * whose references changed, or null when the target does not exist.
     */
    suspend fun merge(type: CategoryType, sourceIds: List<Int>, targetId: Int): Int? = DatabaseFactory.dbQuery {
        val spec = spec(type)
        readEntry(spec, targetId) ?: return@dbQuery null

        val sources = sourceIds.filter { it != targetId }.filter { readEntry(spec, it) != null }
        if (sources.isEmpty()) return@dbQuery 0

        repoint(spec, sources, targetId)
    }

    /**
     * Delete an entry. Movie references are removed; physical media entries keep
     * existing with no distributor.
     */
    suspend fun delete(type: CategoryType, id: Int): Boolean = DatabaseFactory.dbQuery {
        val spec = spec(type)

        if (spec.usageIsOptional) {
            execute("UPDATE ${spec.usageTable} SET ${spec.usageForeignKey} = NULL WHERE ${spec.usageForeignKey} = $id")
        } else {
            execute("DELETE FROM ${spec.usageTable} WHERE ${spec.usageForeignKey} = $id")
        }

        spec.table.deleteWhere { spec.table.id eq id } > 0
    }

    /**
     * Get the id of an entry by name, creating it when it does not exist yet.
     * Matching ignores case and surrounding whitespace so scraped values reuse
     * the entry that is already there instead of adding a near-duplicate.
     *
     * Must be called from inside an existing transaction: a new entry has to be
     * written in the same transaction as the row that will reference it, or the
     * foreign key does not yet see it.
     */
    fun getOrCreateInTransaction(type: CategoryType, name: String): Int {
        val spec = spec(type)
        val trimmed = name.trim()

        return existingIdWithName(spec, trimmed)
            ?: spec.table.insertAndGetId { statement -> statement[spec.nameColumn] = trimmed }.value
    }

    private fun readEntry(spec: Spec, id: Int): CategoryEntry? =
        spec.table.selectAll()
            .where { spec.table.id eq id }
            .map { row ->
                CategoryEntry(
                    id = row[spec.table.id].value,
                    name = row[spec.nameColumn],
                    description = spec.descriptionColumn?.let { row[it] },
                    usageCount = usageCount(spec, id)
                )
            }
            .singleOrNull()

    private fun existingIdWithName(spec: Spec, name: String): Int? =
        spec.table.selectAll()
            .where { spec.nameColumn.lowerCase() eq name.lowercase() }
            .map { it[spec.table.id].value }
            .firstOrNull()

    /**
     * Repoint every reference from [sourceIds] to [targetId] and delete the source
     * entries. Returns how many movies were affected.
     */
    private fun repoint(spec: Spec, sourceIds: List<Int>, targetId: Int): Int {
        val idList = sourceIds.joinToString(", ")

        val affectedMovies = selectInt(
            """
            SELECT count(DISTINCT ${spec.usageOwnerColumn}) FROM ${spec.usageTable}
            WHERE ${spec.usageForeignKey} IN ($idList)
            """.trimIndent()
        )

        execute(
            """
            UPDATE ${spec.usageTable} SET ${spec.usageForeignKey} = $targetId
            WHERE ${spec.usageForeignKey} IN ($idList)
            """.trimIndent()
        )

        if (spec.usageIsUnique) {
            execute(
                """
                DELETE FROM ${spec.usageTable} a
                USING ${spec.usageTable} b
                WHERE a.${spec.usageOwnerColumn} = b.${spec.usageOwnerColumn}
                  AND a.${spec.usageForeignKey} = b.${spec.usageForeignKey}
                  AND a.id > b.id
                """.trimIndent()
            )
        }

        spec.table.deleteWhere { spec.table.id inList sourceIds }

        return affectedMovies
    }

    private fun usageCount(spec: Spec, id: Int): Int = selectInt(
        """
        SELECT count(DISTINCT ${spec.usageOwnerColumn}) FROM ${spec.usageTable}
        WHERE ${spec.usageForeignKey} = $id
        """.trimIndent()
    )

    private fun usageCounts(spec: Spec): Map<Int, Int> = TransactionManager.current().exec(
        """
        SELECT ${spec.usageForeignKey} AS entry_id, count(DISTINCT ${spec.usageOwnerColumn}) AS uses
        FROM ${spec.usageTable}
        WHERE ${spec.usageForeignKey} IS NOT NULL
        GROUP BY ${spec.usageForeignKey}
        """.trimIndent()
    ) { rs ->
        buildMap {
            while (rs.next()) put(rs.getInt("entry_id"), rs.getInt("uses"))
        }
    } ?: emptyMap()

    private fun selectInt(sql: String): Int =
        TransactionManager.current().exec(sql) { rs -> if (rs.next()) rs.getInt(1) else 0 } ?: 0

    private fun execute(sql: String) {
        TransactionManager.current().exec(sql)
    }
}
