package org.btmonier.database

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

/**
 * Data class for a Collection (e.g. a franchise, or a thematic/vibe grouping).
 * Named MovieCollection to avoid clashing with kotlin.collections.Collection.
 */
data class MovieCollection(
    val id: Int,
    val name: String,
    val description: String? = null
)

/**
 * Data Access Object for collection operations.
 */
class CollectionDao {

    /**
     * Get all collections.
     */
    suspend fun getAllCollections(): List<MovieCollection> = DatabaseFactory.dbQuery {
        Collections.selectAll()
            .orderBy(Collections.name to SortOrder.ASC)
            .map { MovieCollection(it[Collections.id].value, it[Collections.name], it[Collections.description]) }
    }

    /**
     * Get a collection by ID.
     */
    suspend fun getCollectionById(id: Int): MovieCollection? = DatabaseFactory.dbQuery {
        Collections.selectAll().where { Collections.id eq id }
            .map { MovieCollection(it[Collections.id].value, it[Collections.name], it[Collections.description]) }
            .singleOrNull()
    }

    /**
     * Get a collection by name.
     */
    suspend fun getCollectionByName(name: String): MovieCollection? = DatabaseFactory.dbQuery {
        Collections.selectAll().where { Collections.name eq name }
            .map { MovieCollection(it[Collections.id].value, it[Collections.name], it[Collections.description]) }
            .singleOrNull()
    }

    /**
     * Create a new collection.
     * Returns the collection ID, or null if it already exists.
     */
    suspend fun createCollection(name: String, description: String? = null): Int? = DatabaseFactory.dbQuery {
        val existing = Collections.selectAll().where { Collections.name eq name }.singleOrNull()
        if (existing != null) {
            return@dbQuery null
        }

        Collections.insertAndGetId {
            it[Collections.name] = name
            it[Collections.description] = description
        }.value
    }

    /**
     * Update a collection's name and description.
     */
    suspend fun updateCollection(id: Int, newName: String, description: String? = null): Boolean = DatabaseFactory.dbQuery {
        Collections.update({ Collections.id eq id }) {
            it[name] = newName
            it[Collections.description] = description
        } > 0
    }

    /**
     * Delete a collection.
     * This will also remove all movie-collection associations.
     */
    suspend fun deleteCollection(id: Int): Boolean = DatabaseFactory.dbQuery {
        // First delete all movie-collection associations
        MovieCollections.deleteWhere { MovieCollections.collectionId.eq(id) }

        // Then delete the collection itself
        Collections.deleteWhere { Collections.id.eq(id) } > 0
    }

    /**
     * Get or create a collection by name.
     * Returns the collection ID.
     */
    suspend fun getOrCreateCollection(name: String): Int = DatabaseFactory.dbQuery {
        val existing = Collections.selectAll().where { Collections.name eq name }.singleOrNull()
        if (existing != null) {
            existing[Collections.id].value
        } else {
            Collections.insertAndGetId {
                it[Collections.name] = name
            }.value
        }
    }
}
