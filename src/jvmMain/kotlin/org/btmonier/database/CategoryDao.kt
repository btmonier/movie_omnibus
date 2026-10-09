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
    COUNTRY("countries", "Countries"),
    WISHLIST_TAG("wishlist-tags", "Wishlist Tags"),
    STORE("stores", "Stores");

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
     * One set of rows that reference a category entry.
     *
     * The referencing side is addressed by name because the category types
     * disagree on nullability, and the operations there only ever interpolate
     * integer ids.
     *
     * @param ownerColumn Column identifying the entity that uses the entry.
     * @param isOptional True when the reference can be nulled out instead of
     *   deleting the referencing row (a release outlives its distributor).
     * @param isUnique True when (owner, entry) pairs must stay unique, which
     *   requires dropping duplicates after a merge.
     * @param counts Whether these rows are what the entry's usage count reports.
     *   Set false for a second kind of owner, so the count stays one number
     *   about one kind of thing.
     */
    private data class Usage(
        val table: String,
        val foreignKey: String,
        val ownerColumn: String,
        val isOptional: Boolean,
        val isUnique: Boolean,
        val counts: Boolean = true
    )

    /**
     * Maps a category to its lookup table and to every table that references it.
     *
     * Lookup columns are typed so that user-supplied names are always bound as
     * parameters. Most categories are referenced from one place; a store is
     * named by vendor links, price history and purchases alike.
     */
    private data class Spec(
        val table: IntIdTable,
        val nameColumn: Column<String>,
        val descriptionColumn: Column<String?>?,
        val usages: List<Usage>
    )

    private val specs: Map<CategoryType, Spec> = mapOf(
        CategoryType.GENRE to Spec(
            Genres, Genres.name, null,
            listOf(Usage("movie_genres", "genre_id", "movie_id", isOptional = false, isUnique = true))
        ),
        CategoryType.SUBGENRE to Spec(
            Subgenres, Subgenres.name, null,
            listOf(Usage("movie_subgenres", "subgenre_id", "movie_id", isOptional = false, isUnique = true))
        ),
        CategoryType.COLLECTION to Spec(
            Collections, Collections.name, Collections.description,
            listOf(Usage("movie_collections", "collection_id", "movie_id", isOptional = false, isUnique = true))
        ),
        // Counted per release rather than per movie: a distributor's reach is the
        // number of physical units it published, and a box set is one of those no
        // matter how many films it holds.
        CategoryType.DISTRIBUTOR to Spec(
            Distributors, Distributors.name, null,
            listOf(Usage("releases", "distributor_id", "id", isOptional = true, isUnique = false))
        ),
        CategoryType.THEME to Spec(
            Themes, Themes.name, null,
            listOf(Usage("movie_themes", "theme_id", "movie_id", isOptional = false, isUnique = true))
        ),
        CategoryType.COUNTRY to Spec(
            Countries, Countries.name, null,
            listOf(Usage("movie_countries", "country_id", "movie_id", isOptional = false, isUnique = true))
        ),
        // Counted per wishlist item rather than per movie.
        CategoryType.WISHLIST_TAG to Spec(
            WishlistTags, WishlistTags.name, null,
            listOf(Usage("wishlist_item_tags", "tag_id", "item_id", isOptional = false, isUnique = true))
        ),
        // Counted per wishlist item: how many things are tracked at or were
        // logged from the store. Deleting a store stops the tracking (a link
        // with no store is not a link) but leaves the prices and purchases it
        // recorded, which are history rather than configuration.
        CategoryType.STORE to Spec(
            Stores, Stores.name, null,
            listOf(
                Usage("wishlist_item_vendor_links", "store_id", "item_id", isOptional = false, isUnique = true),
                Usage("wishlist_price_history", "store_id", "item_id", isOptional = true, isUnique = false),
                Usage("orders", "store_id", "id", isOptional = true, isUnique = false, counts = false)
            )
        )
    )

    init {
        // A type added to the enum without a spec would otherwise only fail the
        // first time somebody opened that tab
        val unmapped = CategoryType.entries - specs.keys
        require(unmapped.isEmpty()) { "Category types with no lookup mapping: $unmapped" }
    }

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
     * Delete an entry. Movie references are removed; rows that can outlive the
     * entry (a release without a distributor, a price without a store) keep
     * existing with the reference nulled.
     */
    suspend fun delete(type: CategoryType, id: Int): Boolean = DatabaseFactory.dbQuery {
        val spec = spec(type)

        spec.usages.forEach { usage ->
            if (usage.isOptional) {
                execute("UPDATE ${usage.table} SET ${usage.foreignKey} = NULL WHERE ${usage.foreignKey} = $id")
            } else {
                execute("DELETE FROM ${usage.table} WHERE ${usage.foreignKey} = $id")
            }
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
     * entries. Returns how many owners were affected.
     */
    private fun repoint(spec: Spec, sourceIds: List<Int>, targetId: Int): Int {
        val idList = sourceIds.joinToString(", ")

        val owners = countingOwnersSql(spec) { fk -> "$fk IN ($idList)" }
        val affectedOwners = selectInt("SELECT count(DISTINCT owner_id) FROM ($owners) owners")

        spec.usages.forEach { usage ->
            execute(
                """
                UPDATE ${usage.table} SET ${usage.foreignKey} = $targetId
                WHERE ${usage.foreignKey} IN ($idList)
                """.trimIndent()
            )

            if (usage.isUnique) {
                execute(
                    """
                    DELETE FROM ${usage.table} a
                    USING ${usage.table} b
                    WHERE a.${usage.ownerColumn} = b.${usage.ownerColumn}
                      AND a.${usage.foreignKey} = b.${usage.foreignKey}
                      AND a.id > b.id
                    """.trimIndent()
                )
            }
        }

        spec.table.deleteWhere { spec.table.id inList sourceIds }

        return affectedOwners
    }

    private fun usageCount(spec: Spec, id: Int): Int {
        val owners = countingOwnersSql(spec) { fk -> "$fk = $id" }
        return selectInt("SELECT count(DISTINCT owner_id) FROM ($owners) owners")
    }

    private fun usageCounts(spec: Spec): Map<Int, Int> {
        val owners = countingOwnersSql(spec) { fk -> "$fk IS NOT NULL" }
        return TransactionManager.current().exec(
            "SELECT entry_id, count(DISTINCT owner_id) AS uses FROM ($owners) owners GROUP BY entry_id"
        ) { rs ->
            buildMap {
                while (rs.next()) put(rs.getInt("entry_id"), rs.getInt("uses"))
            }
        } ?: emptyMap()
    }

    /**
     * The (entry, owner) pairs from every table that counts toward usage, as one
     * union so an owner naming the entry twice is still counted once.
     * [condition] builds the filter from the foreign key column name, which
     * differs from table to table.
     */
    private fun countingOwnersSql(spec: Spec, condition: (fkColumn: String) -> String): String =
        spec.usages.filter { it.counts }.joinToString("\n    UNION\n") { usage ->
            "    SELECT ${usage.foreignKey} AS entry_id, ${usage.ownerColumn} AS owner_id " +
                "FROM ${usage.table} WHERE ${condition(usage.foreignKey)}"
        }

    private fun selectInt(sql: String): Int =
        TransactionManager.current().exec(sql) { rs -> if (rs.next()) rs.getInt(1) else 0 } ?: 0

    private fun execute(sql: String) {
        TransactionManager.current().exec(sql)
    }
}
