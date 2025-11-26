package org.btmonier.database

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq

/**
 * Data class for Genre
 */
data class Genre(
    val id: Int,
    val name: String
)

/**
 * Data class for Subgenre
 */
data class Subgenre(
    val id: Int,
    val name: String
)

/**
 * Data Access Object for genre and subgenre operations.
 */
class GenreDao {

    // ==================== Genre Operations ====================

    /**
     * Get all genres.
     */
    suspend fun getAllGenres(): List<Genre> = DatabaseFactory.dbQuery {
        Genres.selectAll()
            .orderBy(Genres.name to SortOrder.ASC)
            .map { Genre(it[Genres.id].value, it[Genres.name]) }
    }

    /**
     * Get a genre by ID.
     */
    suspend fun getGenreById(id: Int): Genre? = DatabaseFactory.dbQuery {
        Genres.selectAll().where { Genres.id eq id }
            .map { Genre(it[Genres.id].value, it[Genres.name]) }
            .singleOrNull()
    }

    /**
     * Get a genre by name.
     */
    suspend fun getGenreByName(name: String): Genre? = DatabaseFactory.dbQuery {
        Genres.selectAll().where { Genres.name eq name }
            .map { Genre(it[Genres.id].value, it[Genres.name]) }
            .singleOrNull()
    }

    /**
     * Create a new genre.
     * Returns the genre ID, or null if it already exists.
     */
    suspend fun createGenre(name: String): Int? = DatabaseFactory.dbQuery {
        // Check if genre already exists
        val existing = Genres.selectAll().where { Genres.name eq name }.singleOrNull()
        if (existing != null) {
            return@dbQuery null
        }

        Genres.insertAndGetId {
            it[Genres.name] = name
        }.value
    }

    /**
     * Update a genre name.
     */
    suspend fun updateGenre(id: Int, newName: String): Boolean = DatabaseFactory.dbQuery {
        Genres.update({ Genres.id eq id }) {
            it[name] = newName
        } > 0
    }

    /**
     * Delete a genre.
     * This will also remove all movie-genre associations.
     */
    suspend fun deleteGenre(id: Int): Boolean = DatabaseFactory.dbQuery {
        // First delete all movie-genre associations
        MovieGenres.deleteWhere { MovieGenres.genreId.eq(id) }

        // Then delete the genre itself
        Genres.deleteWhere { Genres.id.eq(id) } > 0
    }

    /**
     * Get or create a genre by name.
     * Returns the genre ID.
     */
    suspend fun getOrCreateGenre(name: String): Int = DatabaseFactory.dbQuery {
        val existing = Genres.selectAll().where { Genres.name eq name }.singleOrNull()
        if (existing != null) {
            existing[Genres.id].value
        } else {
            Genres.insertAndGetId {
                it[Genres.name] = name
            }.value
        }
    }

    // ==================== Subgenre Operations ====================

    /**
     * Get all subgenres.
     */
    suspend fun getAllSubgenres(): List<Subgenre> = DatabaseFactory.dbQuery {
        Subgenres.selectAll()
            .orderBy(Subgenres.name to SortOrder.ASC)
            .map { Subgenre(it[Subgenres.id].value, it[Subgenres.name]) }
    }

    /**
     * Get a subgenre by ID.
     */
    suspend fun getSubgenreById(id: Int): Subgenre? = DatabaseFactory.dbQuery {
        Subgenres.selectAll().where { Subgenres.id eq id }
            .map { Subgenre(it[Subgenres.id].value, it[Subgenres.name]) }
            .singleOrNull()
    }

    /**
     * Get a subgenre by name.
     */
    suspend fun getSubgenreByName(name: String): Subgenre? = DatabaseFactory.dbQuery {
        Subgenres.selectAll().where { Subgenres.name eq name }
            .map { Subgenre(it[Subgenres.id].value, it[Subgenres.name]) }
            .singleOrNull()
    }

    /**
     * Create a new subgenre.
     * Returns the subgenre ID, or null if it already exists.
     */
    suspend fun createSubgenre(name: String): Int? = DatabaseFactory.dbQuery {
        // Check if subgenre already exists
        val existing = Subgenres.selectAll().where { Subgenres.name eq name }.singleOrNull()
        if (existing != null) {
            return@dbQuery null
        }

        Subgenres.insertAndGetId {
            it[Subgenres.name] = name
        }.value
    }

    /**
     * Update a subgenre name.
     */
    suspend fun updateSubgenre(id: Int, newName: String): Boolean = DatabaseFactory.dbQuery {
        Subgenres.update({ Subgenres.id eq id }) {
            it[name] = newName
        } > 0
    }

    /**
     * Delete a subgenre.
     * This will also remove all movie-subgenre associations.
     */
    suspend fun deleteSubgenre(id: Int): Boolean = DatabaseFactory.dbQuery {
        // First delete all movie-subgenre associations
        MovieSubgenres.deleteWhere { MovieSubgenres.subgenreId.eq(id) }

        // Then delete the subgenre itself
        Subgenres.deleteWhere { Subgenres.id.eq(id) } > 0
    }

    /**
     * Get or create a subgenre by name.
     * Returns the subgenre ID.
     */
    suspend fun getOrCreateSubgenre(name: String): Int = DatabaseFactory.dbQuery {
        val existing = Subgenres.selectAll().where { Subgenres.name eq name }.singleOrNull()
        if (existing != null) {
            existing[Subgenres.id].value
        } else {
            Subgenres.insertAndGetId {
                it[Subgenres.name] = name
            }.value
        }
    }
}
