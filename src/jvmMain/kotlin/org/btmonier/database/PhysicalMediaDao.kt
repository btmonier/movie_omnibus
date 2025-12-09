package org.btmonier.database

import org.btmonier.MediaType
import org.btmonier.PhysicalMediaImage
import org.btmonier.storage.GcsService
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.LocalDate
import org.btmonier.PhysicalMedia as PhysicalMediaEntity

/**
 * Data Access Object for physical media database operations.
 * 
 * @param gcsService Optional GCS service for transforming image URLs to signed URLs.
 *                   If null, image URLs are returned as-is from the database.
 */
class PhysicalMediaDao(private val gcsService: GcsService? = null) {

    /**
     * Get all physical media entries for a specific movie.
     */
    suspend fun getPhysicalMediaForMovie(movieId: Int): List<PhysicalMediaEntity> = DatabaseFactory.dbQuery {
        PhysicalMedia.selectAll().where { PhysicalMedia.movieId eq movieId }
            .map { rowToPhysicalMedia(it) }
    }

    /**
     * Get a specific physical media entry by its ID.
     */
    suspend fun getPhysicalMediaById(id: Int): PhysicalMediaEntity? = DatabaseFactory.dbQuery {
        PhysicalMedia.selectAll().where { PhysicalMedia.id eq id }
            .map { rowToPhysicalMedia(it) }
            .singleOrNull()
    }

    /**
     * Get all movies that have a specific media type.
     */
    suspend fun getMovieIdsByMediaType(mediaType: MediaType): List<Int> = DatabaseFactory.dbQuery {
        val mediaTypeString = mediaTypeToString(mediaType)

        // Get all physical media IDs that have this media type
        val physicalMediaIds = PhysicalMediaTypes.selectAll()
            .where { PhysicalMediaTypes.mediaType eq mediaTypeString }
            .map { it[PhysicalMediaTypes.physicalMediaId].value }
            .distinct()

        // Get all movie IDs for these physical media entries
        PhysicalMedia.selectAll()
            .where { PhysicalMedia.id inList physicalMediaIds }
            .map { it[PhysicalMedia.movieId].value }
            .distinct()
    }

    /**
     * Create a new physical media entry for a movie.
     */
    suspend fun createPhysicalMedia(movieId: Int, physicalMedia: PhysicalMediaEntity): Int = DatabaseFactory.dbQuery {
        val mediaId = PhysicalMedia.insertAndGetId {
            it[PhysicalMedia.movieId] = movieId
            it[entryLetter] = physicalMedia.entryLetter
            it[title] = physicalMedia.title
            it[distributor] = physicalMedia.distributor
            it[releaseDate] = physicalMedia.releaseDate?.let { date -> LocalDate.parse(date) }
            it[blurayComUrl] = physicalMedia.blurayComUrl
            it[location] = physicalMedia.location
        }

        // Insert media types
        insertMediaTypes(mediaId.value, physicalMedia.mediaTypes)

        // Insert images
        insertImages(mediaId.value, physicalMedia.images)

        mediaId.value
    }

    /**
     * Update an existing physical media entry.
     */
    suspend fun updatePhysicalMedia(id: Int, physicalMedia: PhysicalMediaEntity): Boolean = DatabaseFactory.dbQuery {
        val updated = PhysicalMedia.update({ PhysicalMedia.id eq id }) {
            it[entryLetter] = physicalMedia.entryLetter
            it[title] = physicalMedia.title
            it[distributor] = physicalMedia.distributor
            it[releaseDate] = physicalMedia.releaseDate?.let { date -> LocalDate.parse(date) }
            it[blurayComUrl] = physicalMedia.blurayComUrl
            it[location] = physicalMedia.location
        }

        if (updated > 0) {
            // Delete old media types and insert new ones
            PhysicalMediaTypes.deleteWhere(op = { PhysicalMediaTypes.physicalMediaId.eq(id) })
            insertMediaTypes(id, physicalMedia.mediaTypes)

            // Delete old images and insert new ones
            PhysicalMediaImages.deleteWhere(op = { PhysicalMediaImages.physicalMediaId.eq(id) })
            insertImages(id, physicalMedia.images)
        }

        updated > 0
    }

    /**
     * Delete a physical media entry and its associated images.
     */
    suspend fun deletePhysicalMedia(id: Int): Boolean = DatabaseFactory.dbQuery {
        // Delete related data first (due to foreign key constraints)
        PhysicalMediaTypes.deleteWhere(op = { PhysicalMediaTypes.physicalMediaId.eq(id) })
        PhysicalMediaImages.deleteWhere(op = { PhysicalMediaImages.physicalMediaId.eq(id) })

        // Delete the physical media entry
        val deleted = PhysicalMedia.deleteWhere(op = { PhysicalMedia.id.eq(id) })
        deleted > 0
    }

    /**
     * Delete all physical media entries for a specific movie.
     */
    suspend fun deleteAllPhysicalMediaForMovie(movieId: Int): Int = DatabaseFactory.dbQuery {
        // Get all physical media IDs for this movie
        val mediaIds = PhysicalMedia.selectAll().where { PhysicalMedia.movieId eq movieId }
            .map { it[PhysicalMedia.id].value }

        // Delete all related data for these media entries
        mediaIds.forEach { mediaId ->
            PhysicalMediaTypes.deleteWhere(op = { PhysicalMediaTypes.physicalMediaId.eq(mediaId) })
            PhysicalMediaImages.deleteWhere(op = { PhysicalMediaImages.physicalMediaId.eq(mediaId) })
        }

        // Delete all physical media entries for this movie
        PhysicalMedia.deleteWhere(op = { PhysicalMedia.movieId.eq(movieId) })
    }

    // Helper function to insert media types for a physical media entry
    private fun insertMediaTypes(physicalMediaId: Int, mediaTypes: List<MediaType>) {
        mediaTypes.forEach { type ->
            PhysicalMediaTypes.insert {
                it[PhysicalMediaTypes.physicalMediaId] = physicalMediaId
                it[mediaType] = mediaTypeToString(type)
            }
        }
    }

    // Helper function to insert images for a physical media entry
    private fun insertImages(physicalMediaId: Int, images: List<PhysicalMediaImage>) {
        images.forEach { image ->
            // Clean the image URL before saving - converts signed GCS URLs back to storable paths
            val cleanedUrl = gcsService?.cleanUrlForStorage(image.imageUrl) ?: image.imageUrl
            PhysicalMediaImages.insert {
                it[PhysicalMediaImages.physicalMediaId] = physicalMediaId
                it[imageUrl] = cleanedUrl
                it[description] = image.description
            }
        }
    }

    // Helper function to convert a database row to PhysicalMedia
    private fun rowToPhysicalMedia(row: ResultRow): PhysicalMediaEntity {
        val mediaId = row[PhysicalMedia.id].value

        val mediaTypes = PhysicalMediaTypes.selectAll().where { PhysicalMediaTypes.physicalMediaId eq mediaId }
            .map { stringToMediaType(it[PhysicalMediaTypes.mediaType]) }

        val images = PhysicalMediaImages.selectAll().where { PhysicalMediaImages.physicalMediaId eq mediaId }
            .map {
                val rawImageUrl = it[PhysicalMediaImages.imageUrl]
                // Transform GCS paths to signed URLs if GCS service is configured
                val transformedUrl = gcsService?.transformUrl(rawImageUrl) ?: rawImageUrl
                PhysicalMediaImage(
                    imageUrl = transformedUrl,
                    description = it[PhysicalMediaImages.description],
                    id = it[PhysicalMediaImages.id].value
                )
            }

        return PhysicalMediaEntity(
            mediaTypes = mediaTypes,
            entryLetter = row[PhysicalMedia.entryLetter],
            title = row[PhysicalMedia.title],
            distributor = row[PhysicalMedia.distributor],
            releaseDate = row[PhysicalMedia.releaseDate]?.toString(),
            blurayComUrl = row[PhysicalMedia.blurayComUrl],
            location = row[PhysicalMedia.location],
            images = images,
            id = mediaId,
            createdAt = row[PhysicalMedia.createdAt].toString()
        )
    }

    // Helper functions to convert between MediaType enum and String
    private fun mediaTypeToString(mediaType: MediaType): String = when (mediaType) {
        MediaType.VHS -> "VHS"
        MediaType.DVD -> "DVD"
        MediaType.BLURAY -> "Blu-ray"
        MediaType.FOURK -> "4K"
        MediaType.DIGITAL -> "Digital"
    }

    private fun stringToMediaType(str: String): MediaType = when (str) {
        "VHS" -> MediaType.VHS
        "DVD" -> MediaType.DVD
        "Blu-ray" -> MediaType.BLURAY
        "4K" -> MediaType.FOURK
        "Digital" -> MediaType.DIGITAL
        else -> throw IllegalArgumentException("Unknown media type: $str")
    }
}
