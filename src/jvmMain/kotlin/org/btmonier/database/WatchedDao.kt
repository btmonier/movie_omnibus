package org.btmonier.database

import org.btmonier.WatchedEntry
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.LocalDate

/**
 * Data Access Object for watched entries database operations.
 */
class WatchedDao {

    /**
     * Get all watched entries for a specific movie.
     */
    suspend fun getWatchedEntriesForMovie(movieId: Int): List<WatchedEntry> = DatabaseFactory.dbQuery {
        WatchedEntries.selectAll().where { WatchedEntries.movieId eq movieId }
            .orderBy(WatchedEntries.watchedDate to SortOrder.DESC)
            .map { rowToWatchedEntry(it) }
    }

    /**
     * Get a specific watched entry by its ID.
     */
    suspend fun getWatchedEntryById(id: Int): WatchedEntry? = DatabaseFactory.dbQuery {
        WatchedEntries.selectAll().where { WatchedEntries.id eq id }
            .map { rowToWatchedEntry(it) }
            .singleOrNull()
    }

    /**
     * Create a new watched entry for a movie.
     */
    suspend fun createWatchedEntry(movieId: Int, watchedEntry: WatchedEntry): Int = DatabaseFactory.dbQuery {
        WatchedEntries.insertAndGetId {
            it[WatchedEntries.movieId] = movieId
            it[watchedDate] = LocalDate.parse(watchedEntry.watchedDate)
            it[rating] = watchedEntry.rating
            it[notes] = watchedEntry.notes
        }.value
    }

    /**
     * Update an existing watched entry.
     */
    suspend fun updateWatchedEntry(id: Int, watchedEntry: WatchedEntry): Boolean = DatabaseFactory.dbQuery {
        val updated = WatchedEntries.update({ WatchedEntries.id eq id }) {
            it[watchedDate] = LocalDate.parse(watchedEntry.watchedDate)
            it[rating] = watchedEntry.rating
            it[notes] = watchedEntry.notes
        }
        updated > 0
    }

    /**
     * Delete a watched entry.
     */
    suspend fun deleteWatchedEntry(id: Int): Boolean = DatabaseFactory.dbQuery {
        val deleted = WatchedEntries.deleteWhere { WatchedEntries.id.eq(id) }
        deleted > 0
    }

    /**
     * Delete all watched entries for a specific movie.
     */
    suspend fun deleteAllWatchedEntriesForMovie(movieId: Int): Int = DatabaseFactory.dbQuery {
        WatchedEntries.deleteWhere { WatchedEntries.movieId.eq(movieId) }
    }

    /**
     * Get average rating for a movie across all watched entries.
     */
    suspend fun getAverageRating(movieId: Int): Double? = DatabaseFactory.dbQuery {
        WatchedEntries
            .select(WatchedEntries.rating.avg())
            .where { (WatchedEntries.movieId eq movieId) and WatchedEntries.rating.isNotNull() }
            .map { it[WatchedEntries.rating.avg()]?.toDouble() }
            .firstOrNull()
    }

    /**
     * Get watch count for a movie.
     */
    suspend fun getWatchCount(movieId: Int): Long = DatabaseFactory.dbQuery {
        WatchedEntries.selectAll().where { WatchedEntries.movieId eq movieId }
            .count()
    }

    // Helper function to convert a database row to WatchedEntry
    private fun rowToWatchedEntry(row: ResultRow): WatchedEntry {
        return WatchedEntry(
            watchedDate = row[WatchedEntries.watchedDate].toString(),
            rating = row[WatchedEntries.rating],
            notes = row[WatchedEntries.notes],
            id = row[WatchedEntries.id].value
        )
    }
}
