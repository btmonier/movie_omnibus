package org.btmonier.database

import org.btmonier.MediaType
import org.btmonier.Release
import org.btmonier.storage.GcsService
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.btmonier.PhysicalMedia as PhysicalMediaEntity

/**
 * Data Access Object for one movie's copies of physical releases.
 *
 * A [PhysicalMediaEntity] is a flattened view of a release joined with the link
 * that ties it to a single film, so its `id` is the link id: editing or deleting
 * an entry always addresses "this film's copy". Release-level edits write
 * through to the release and therefore affect every film on it.
 *
 * @param gcsService Optional GCS service for transforming image URLs to signed URLs.
 *                   If null, image URLs are returned as-is from the database.
 */
class PhysicalMediaDao(gcsService: GcsService? = null) {
    private val releaseDao = ReleaseDao(gcsService)

    /**
     * Get all physical media entries for a specific movie.
     */
    suspend fun getPhysicalMediaForMovie(movieId: Int): List<PhysicalMediaEntity> = DatabaseFactory.dbQuery {
        (ReleaseMovies innerJoin Releases)
            .selectAll()
            .where { ReleaseMovies.movieId eq movieId }
            .map { rowToPhysicalMedia(it) }
            .sortedBy { it.entryLetter ?: "\uFFFF" }
    }

    /**
     * Get a specific entry by its link id.
     */
    suspend fun getPhysicalMediaById(id: Int): PhysicalMediaEntity? = DatabaseFactory.dbQuery {
        (ReleaseMovies innerJoin Releases)
            .selectAll()
            .where { ReleaseMovies.id eq id }
            .map { rowToPhysicalMedia(it) }
            .singleOrNull()
    }

    /**
     * Get all movies that have a specific media type.
     */
    suspend fun getMovieIdsByMediaType(mediaType: MediaType): List<Int> = DatabaseFactory.dbQuery {
        val releaseIds = ReleaseMediaTypes.selectAll()
            .where { ReleaseMediaTypes.mediaType eq mediaTypeToString(mediaType) }
            .map { it[ReleaseMediaTypes.releaseId].value }
            .distinct()

        ReleaseMovies.selectAll()
            .where { ReleaseMovies.releaseId inList releaseIds }
            .map { it[ReleaseMovies.movieId].value }
            .distinct()
    }

    /**
     * Add a copy of a release to a movie.
     *
     * When [physicalMedia] carries a `releaseId` the movie is simply linked to
     * that existing release, so a box set is entered once and shared from then
     * on. Otherwise a new release is created from the supplied fields.
     *
     * Returns the link id.
     */
    suspend fun createPhysicalMedia(movieId: Int, physicalMedia: PhysicalMediaEntity): Int = DatabaseFactory.dbQuery {
        with(releaseDao) {
            val releaseId = physicalMedia.releaseId
                ?.takeIf { Releases.selectAll().where { Releases.id eq it }.any() }
                ?: createReleaseInTransaction(physicalMedia.toRelease())

            linkMovieInTransaction(
                releaseId = releaseId,
                movieId = movieId,
                entryLetter = physicalMedia.entryLetter,
                alternateTitle = physicalMedia.alternateTitle
            ) ?: error("Release $releaseId disappeared while linking movie $movieId")
        }
    }

    /**
     * Update an entry by its link id.
     *
     * The entry letter and alternate title are written to this film's link; every
     * other field belongs to the release and so is shared with any other film on
     * it.
     */
    suspend fun updatePhysicalMedia(id: Int, physicalMedia: PhysicalMediaEntity): Boolean = DatabaseFactory.dbQuery {
        val link = ReleaseMovies.selectAll().where { ReleaseMovies.id eq id }.singleOrNull()
            ?: return@dbQuery false

        ReleaseMovies.update({ ReleaseMovies.id eq id }) {
            it[entryLetter] = physicalMedia.entryLetter
            it[alternateTitle] = physicalMedia.alternateTitle
        }

        with(releaseDao) {
            updateReleaseInTransaction(link[ReleaseMovies.releaseId].value, physicalMedia.toRelease())
        }
    }

    /**
     * Take this film off the release. The release itself survives so the other
     * films on a box set are untouched.
     */
    suspend fun deletePhysicalMedia(id: Int): Boolean = DatabaseFactory.dbQuery {
        ReleaseMovies.deleteWhere(op = { ReleaseMovies.id.eq(id) }) > 0
    }

    /**
     * Take a movie off every release it appears on. Releases left without films
     * remain, and stay visible (and deletable) in the release browser.
     */
    suspend fun deleteAllPhysicalMediaForMovie(movieId: Int): Int = DatabaseFactory.dbQuery {
        ReleaseMovies.deleteWhere(op = { ReleaseMovies.movieId.eq(movieId) })
    }

    /**
     * Projects a joined release/link row into the flat per-film view.
     */
    private fun rowToPhysicalMedia(row: ResultRow): PhysicalMediaEntity {
        val releaseId = row[Releases.id].value
        return with(releaseDao) {
            PhysicalMediaEntity(
                mediaTypes = mediaTypesFor(releaseId),
                entryLetter = row[ReleaseMovies.entryLetter],
                title = row[Releases.title],
                alternateTitle = row[ReleaseMovies.alternateTitle],
                isCollection = row[Releases.isCollection] ?: false,
                distributor = distributorName(row[Releases.distributorId]),
                releaseDate = row[Releases.releaseDate]?.toString(),
                blurayComUrl = row[Releases.blurayComUrl],
                location = row[Releases.location],
                images = imagesFor(releaseId),
                id = row[ReleaseMovies.id].value,
                createdAt = row[Releases.createdAt].toString(),
                releaseId = releaseId,
                sharedWithCount = (filmCountFor(releaseId) - 1).coerceAtLeast(0)
            )
        }
    }
}

/**
 * The release-level half of a per-film entry, for writing back to the shared
 * release. Films are left empty: linking is handled separately so an update
 * never disturbs the other films on the release.
 */
private fun PhysicalMediaEntity.toRelease(): Release = Release(
    mediaTypes = mediaTypes,
    title = title,
    isCollection = isCollection,
    distributor = distributor,
    releaseDate = releaseDate,
    blurayComUrl = blurayComUrl,
    location = location,
    images = images,
    id = releaseId
)
