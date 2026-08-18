package org.btmonier.database

import org.btmonier.MediaType
import org.btmonier.PhysicalMediaImage
import org.btmonier.ReleaseFilm
import org.btmonier.ReleaseSummary
import org.btmonier.bluRayCoverImageUrl
import org.btmonier.storage.GcsService
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.time.LocalDate
import org.btmonier.Release as ReleaseEntity

/**
 * Converts a [MediaType] to the string stored in the database.
 */
internal fun mediaTypeToString(mediaType: MediaType): String = when (mediaType) {
    MediaType.VHS -> "VHS"
    MediaType.DVD -> "DVD"
    MediaType.BLURAY -> "Blu-ray"
    MediaType.FOURK -> "4K"
    MediaType.DIGITAL -> "Digital"
}

/**
 * Converts a stored media type string back to a [MediaType], ignoring values
 * that predate the current enum rather than failing the whole read.
 */
internal fun stringToMediaTypeOrNull(str: String): MediaType? = when (str) {
    "VHS" -> MediaType.VHS
    "DVD" -> MediaType.DVD
    "Blu-ray" -> MediaType.BLURAY
    "4K" -> MediaType.FOURK
    "Digital" -> MediaType.DIGITAL
    else -> null
}

/**
 * How a page of releases should be ordered.
 */
enum class ReleaseSortField(val slug: String) {
    TITLE("title"),
    RELEASE_DATE("release_date"),
    DATE_ADDED("date_added"),
    FILM_COUNT("film_count");

    companion object {
        fun fromSlug(slug: String?): ReleaseSortField =
            entries.firstOrNull { it.slug.equals(slug, ignoreCase = true) } ?: TITLE
    }
}

/**
 * Filters applied when listing releases.
 */
data class ReleaseFilters(
    val search: String? = null,
    val mediaType: String? = null,
    val distributor: String? = null,
    val location: String? = null,
    val collectionsOnly: Boolean = false,
    val emptyOnly: Boolean = false
)

/**
 * Data Access Object for physical releases - the shared unit that one or more
 * films sit on.
 *
 * @param gcsService Optional GCS service for transforming image URLs to signed
 *   URLs. If null, image URLs are returned as-is from the database.
 */
class ReleaseDao(private val gcsService: GcsService? = null) {
    private val categoryDao = CategoryDao()

    /**
     * A page of releases, ordered as requested, together with the total number
     * of releases matching the filters.
     *
     * Film titles are only sampled (a handful per release) so listing a page
     * never pulls in the hundreds of films a large box set holds.
     */
    suspend fun listReleases(
        page: Int,
        pageSize: Int,
        filters: ReleaseFilters = ReleaseFilters(),
        sortField: ReleaseSortField = ReleaseSortField.TITLE,
        ascending: Boolean = true
    ): Pair<List<ReleaseSummary>, Int> = DatabaseFactory.dbQuery {
        val matchingIds = matchingReleaseIds(filters)
        val filmCounts = filmCountsByRelease()

        val ordered = Releases.selectAll()
            .let { query -> matchingIds?.let { query.where { Releases.id inList it } } ?: query }
            .map { row ->
                val id = row[Releases.id].value
                id to row
            }
            .sortedWith(releaseComparator(sortField, ascending, filmCounts))

        val total = ordered.size
        val fromIndex = ((page - 1).coerceAtLeast(0)) * pageSize
        val pageRows = if (fromIndex >= total) emptyList() else ordered.subList(fromIndex, minOf(fromIndex + pageSize, total))

        val summaries = pageRows.map { (id, row) ->
            ReleaseSummary(
                id = id,
                title = row[Releases.title],
                isCollection = row[Releases.isCollection] ?: false,
                distributor = distributorName(row[Releases.distributorId]),
                releaseDate = row[Releases.releaseDate]?.toString(),
                blurayComUrl = row[Releases.blurayComUrl],
                location = row[Releases.location],
                mediaTypes = mediaTypesFor(id),
                coverUrl = coverUrlFor(id, row[Releases.blurayComUrl]),
                filmCount = filmCounts[id] ?: 0,
                sampleTitles = sampleTitles(id)
            )
        }

        summaries to total
    }

    /**
     * A single release with every film on it. Callers rendering a large box set
     * should page through [getFilms] instead of reading [ReleaseEntity.films].
     */
    suspend fun getRelease(id: Int, includeFilms: Boolean = true): ReleaseEntity? = DatabaseFactory.dbQuery {
        Releases.selectAll().where { Releases.id eq id }
            .map { rowToRelease(it, includeFilms) }
            .singleOrNull()
    }

    /**
     * A page of the films on a release, optionally narrowed by a title search.
     */
    suspend fun getFilms(
        releaseId: Int,
        page: Int,
        pageSize: Int,
        search: String? = null
    ): Pair<List<ReleaseFilm>, Int> = DatabaseFactory.dbQuery {
        val all = filmsFor(releaseId)
        val filtered = search?.trim()?.takeIf { it.isNotEmpty() }?.let { query ->
            all.filter {
                it.title.contains(query, ignoreCase = true) ||
                    it.alternateTitle?.contains(query, ignoreCase = true) == true
            }
        } ?: all

        val total = filtered.size
        val fromIndex = ((page - 1).coerceAtLeast(0)) * pageSize
        val pageItems = if (fromIndex >= total) emptyList() else filtered.subList(fromIndex, minOf(fromIndex + pageSize, total))
        pageItems to total
    }

    /**
     * Releases matching a free-text query, for the "link to an existing release"
     * picker. Matches on title, blu-ray.com URL and distributor.
     */
    suspend fun searchReleases(query: String, limit: Int = 20): List<ReleaseSummary> = DatabaseFactory.dbQuery {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@dbQuery emptyList()

        val ids = matchingReleaseIds(ReleaseFilters(search = trimmed)).orEmpty()
        val filmCounts = filmCountsByRelease()

        Releases.selectAll().where { Releases.id inList ids }
            .map { row ->
                val id = row[Releases.id].value
                ReleaseSummary(
                    id = id,
                    title = row[Releases.title],
                    isCollection = row[Releases.isCollection] ?: false,
                    distributor = distributorName(row[Releases.distributorId]),
                    releaseDate = row[Releases.releaseDate]?.toString(),
                    blurayComUrl = row[Releases.blurayComUrl],
                    location = row[Releases.location],
                    mediaTypes = mediaTypesFor(id),
                    coverUrl = coverUrlFor(id, row[Releases.blurayComUrl]),
                    filmCount = filmCounts[id] ?: 0,
                    sampleTitles = sampleTitles(id)
                )
            }
            .sortedWith(compareByDescending<ReleaseSummary> { it.filmCount }.thenBy { it.title ?: "" })
            .take(limit)
    }

    /**
     * The release, if any, already recorded under a blu-ray.com URL. Used to
     * offer linking instead of creating a second copy of the same release.
     */
    suspend fun findByBluRayUrl(url: String): ReleaseSummary? = DatabaseFactory.dbQuery {
        val key = bluRayKey(url) ?: return@dbQuery null
        val filmCounts = filmCountsByRelease()

        Releases.selectAll()
            .where { Releases.blurayComUrl.isNotNull() }
            .firstOrNull { bluRayKey(it[Releases.blurayComUrl]) == key }
            ?.let { row ->
                val id = row[Releases.id].value
                ReleaseSummary(
                    id = id,
                    title = row[Releases.title],
                    isCollection = row[Releases.isCollection] ?: false,
                    distributor = distributorName(row[Releases.distributorId]),
                    releaseDate = row[Releases.releaseDate]?.toString(),
                    blurayComUrl = row[Releases.blurayComUrl],
                    location = row[Releases.location],
                    mediaTypes = mediaTypesFor(id),
                    coverUrl = coverUrlFor(id, row[Releases.blurayComUrl]),
                    filmCount = filmCounts[id] ?: 0,
                    sampleTitles = sampleTitles(id)
                )
            }
    }

    /**
     * Create a release. Any films listed on [release] are linked to it.
     */
    suspend fun createRelease(release: ReleaseEntity): Int = DatabaseFactory.dbQuery {
        createReleaseInTransaction(release)
    }

    /**
     * Update the release-level fields, media types and images. Film links are
     * left alone; use [linkMovie] and [unlinkMovie] for those.
     */
    suspend fun updateRelease(id: Int, release: ReleaseEntity): Boolean = DatabaseFactory.dbQuery {
        updateReleaseInTransaction(id, release)
    }

    /**
     * Delete a release along with its film links, media types and images.
     */
    suspend fun deleteRelease(id: Int): Boolean = DatabaseFactory.dbQuery {
        ReleaseMovies.deleteWhere(op = { ReleaseMovies.releaseId.eq(id) })
        ReleaseMediaTypes.deleteWhere(op = { ReleaseMediaTypes.releaseId.eq(id) })
        ReleaseImages.deleteWhere(op = { ReleaseImages.releaseId.eq(id) })
        Releases.deleteWhere(op = { Releases.id.eq(id) }) > 0
    }

    /**
     * Put a film on a release. Returns the link id, or null when the release
     * does not exist. Linking a film that is already on the release updates the
     * link rather than adding a second one.
     */
    suspend fun linkMovie(
        releaseId: Int,
        movieId: Int,
        entryLetter: String? = null,
        alternateTitle: String? = null
    ): Int? = DatabaseFactory.dbQuery {
        linkMovieInTransaction(releaseId, movieId, entryLetter, alternateTitle)
    }

    /**
     * Take a film off a release. The release itself survives, so a box set does
     * not disappear when one of its films is removed.
     */
    suspend fun unlinkMovie(releaseId: Int, movieId: Int): Boolean = DatabaseFactory.dbQuery {
        ReleaseMovies.deleteWhere {
            (ReleaseMovies.releaseId eq releaseId) and (ReleaseMovies.movieId eq movieId)
        } > 0
    }

    // --- Shared with PhysicalMediaDao, which writes through to the same tables ---

    internal fun createReleaseInTransaction(release: ReleaseEntity): Int {
        val releaseId = Releases.insertAndGetId {
            it[title] = release.title?.trim()?.takeIf(String::isNotEmpty)
            it[isCollection] = release.isCollection
            it[distributorId] = resolveDistributor(release.distributor)
            it[releaseDate] = release.releaseDate?.takeIf { date -> date.isNotBlank() }?.let(LocalDate::parse)
            it[blurayComUrl] = release.blurayComUrl?.trim()?.takeIf(String::isNotEmpty)
            it[location] = release.location?.trim()?.takeIf(String::isNotEmpty)
        }.value

        writeMediaTypes(releaseId, release.mediaTypes)
        writeImages(releaseId, release.images)

        release.films.forEach { film ->
            linkMovieInTransaction(releaseId, film.movieId, film.entryLetter, film.alternateTitle)
        }

        return releaseId
    }

    internal fun updateReleaseInTransaction(id: Int, release: ReleaseEntity): Boolean {
        val updated = Releases.update({ Releases.id eq id }) {
            it[title] = release.title?.trim()?.takeIf(String::isNotEmpty)
            it[isCollection] = release.isCollection
            it[distributorId] = resolveDistributor(release.distributor)
            it[releaseDate] = release.releaseDate?.takeIf { date -> date.isNotBlank() }?.let(LocalDate::parse)
            it[blurayComUrl] = release.blurayComUrl?.trim()?.takeIf(String::isNotEmpty)
            it[location] = release.location?.trim()?.takeIf(String::isNotEmpty)
        }

        if (updated == 0) return false

        ReleaseMediaTypes.deleteWhere(op = { ReleaseMediaTypes.releaseId.eq(id) })
        writeMediaTypes(id, release.mediaTypes)

        ReleaseImages.deleteWhere(op = { ReleaseImages.releaseId.eq(id) })
        writeImages(id, release.images)

        return true
    }

    internal fun linkMovieInTransaction(
        releaseId: Int,
        movieId: Int,
        entryLetter: String?,
        alternateTitle: String?
    ): Int? {
        val releaseExists = Releases.selectAll().where { Releases.id eq releaseId }.any()
        if (!releaseExists) return null

        val existing = ReleaseMovies.selectAll()
            .where { (ReleaseMovies.releaseId eq releaseId) and (ReleaseMovies.movieId eq movieId) }
            .map { it[ReleaseMovies.id].value }
            .firstOrNull()

        if (existing != null) {
            ReleaseMovies.update({ ReleaseMovies.id eq existing }) {
                it[ReleaseMovies.entryLetter] = entryLetter
                it[ReleaseMovies.alternateTitle] = alternateTitle
            }
            return existing
        }

        return ReleaseMovies.insertAndGetId {
            it[ReleaseMovies.releaseId] = releaseId
            it[ReleaseMovies.movieId] = movieId
            it[ReleaseMovies.entryLetter] = entryLetter
            it[ReleaseMovies.alternateTitle] = alternateTitle
        }.value
    }

    internal fun writeMediaTypes(releaseId: Int, mediaTypes: List<MediaType>) {
        mediaTypes.distinct().forEach { type ->
            ReleaseMediaTypes.insert {
                it[ReleaseMediaTypes.releaseId] = releaseId
                it[mediaType] = mediaTypeToString(type)
            }
        }
    }

    internal fun writeImages(releaseId: Int, images: List<PhysicalMediaImage>) {
        images.forEach { image ->
            // Clean the image URL before saving - converts signed GCS URLs back to storable paths
            val cleanedUrl = gcsService?.cleanUrlForStorage(image.imageUrl) ?: image.imageUrl
            ReleaseImages.insert {
                it[ReleaseImages.releaseId] = releaseId
                it[imageUrl] = cleanedUrl
                it[description] = image.description
            }
        }
    }

    internal fun rowToRelease(row: ResultRow, includeFilms: Boolean = true): ReleaseEntity {
        val id = row[Releases.id].value
        return ReleaseEntity(
            mediaTypes = mediaTypesFor(id),
            title = row[Releases.title],
            isCollection = row[Releases.isCollection] ?: false,
            distributor = distributorName(row[Releases.distributorId]),
            releaseDate = row[Releases.releaseDate]?.toString(),
            blurayComUrl = row[Releases.blurayComUrl],
            location = row[Releases.location],
            images = imagesFor(id),
            films = if (includeFilms) filmsFor(id) else emptyList(),
            filmCount = filmCountFor(id),
            id = id,
            createdAt = row[Releases.createdAt].toString()
        )
    }

    internal fun mediaTypesFor(releaseId: Int): List<MediaType> =
        ReleaseMediaTypes.selectAll().where { ReleaseMediaTypes.releaseId eq releaseId }
            .mapNotNull { stringToMediaTypeOrNull(it[ReleaseMediaTypes.mediaType]) }
            .distinct()

    internal fun imagesFor(releaseId: Int): List<PhysicalMediaImage> =
        ReleaseImages.selectAll().where { ReleaseImages.releaseId eq releaseId }
            .map {
                val rawImageUrl = it[ReleaseImages.imageUrl]
                // Transform GCS paths to signed URLs if GCS service is configured
                PhysicalMediaImage(
                    imageUrl = gcsService?.transformUrl(rawImageUrl) ?: rawImageUrl,
                    description = it[ReleaseImages.description],
                    id = it[ReleaseImages.id].value
                )
            }

    internal fun filmsFor(releaseId: Int): List<ReleaseFilm> =
        (ReleaseMovies innerJoin Movies)
            .selectAll()
            .where { ReleaseMovies.releaseId eq releaseId }
            .map {
                ReleaseFilm(
                    movieId = it[ReleaseMovies.movieId].value,
                    title = it[Movies.title],
                    releaseYear = it[Movies.releaseDate]?.year,
                    entryLetter = it[ReleaseMovies.entryLetter],
                    alternateTitle = it[ReleaseMovies.alternateTitle],
                    linkId = it[ReleaseMovies.id].value
                )
            }
            .sortedBy { it.title.lowercase() }

    internal fun filmCountFor(releaseId: Int): Int =
        ReleaseMovies.selectAll().where { ReleaseMovies.releaseId eq releaseId }.count().toInt()

    internal fun distributorName(id: EntityID<Int>?): String? = id?.let { distributorId ->
        Distributors.selectAll().where { Distributors.id eq distributorId.value }
            .map { it[Distributors.name] }
            .singleOrNull()
    }

    internal fun resolveDistributor(name: String?): EntityID<Int>? =
        name?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { EntityID(categoryDao.getOrCreateInTransaction(CategoryType.DISTRIBUTOR, it), Distributors) }

    // --- Internals ---

    private fun filmCountsByRelease(): Map<Int, Int> =
        ReleaseMovies.selectAll()
            .groupingBy { it[ReleaseMovies.releaseId].value }
            .eachCount()

    private fun sampleTitles(releaseId: Int, limit: Int = 3): List<String> =
        (ReleaseMovies innerJoin Movies)
            .select(Movies.title)
            .where { ReleaseMovies.releaseId eq releaseId }
            .orderBy(Movies.title to SortOrder.ASC)
            .limit(limit)
            .map { it[Movies.title] }

    private fun coverUrlFor(releaseId: Int, blurayComUrl: String?): String? =
        imagesFor(releaseId).firstOrNull()?.imageUrl ?: bluRayCoverImageUrl(blurayComUrl)

    private fun releaseComparator(
        field: ReleaseSortField,
        ascending: Boolean,
        filmCounts: Map<Int, Int>
    ): Comparator<Pair<Int, ResultRow>> {
        val base: Comparator<Pair<Int, ResultRow>> = when (field) {
            ReleaseSortField.TITLE -> compareBy(nullsLast()) { (_, row) -> row[Releases.title]?.lowercase() }
            ReleaseSortField.RELEASE_DATE -> compareBy(nullsLast()) { (_, row) -> row[Releases.releaseDate] }
            ReleaseSortField.DATE_ADDED -> compareBy { (_, row) -> row[Releases.createdAt] }
            ReleaseSortField.FILM_COUNT -> compareBy { (id, _) -> filmCounts[id] ?: 0 }
        }
        val tieBroken = base.thenBy { (id, _) -> id }
        return if (ascending) tieBroken else tieBroken.reversed()
    }

    /**
     * The ids matching [filters], or null when nothing narrows the result.
     */
    private fun matchingReleaseIds(filters: ReleaseFilters): List<Int>? {
        var candidates: Set<Int>? = null

        fun narrow(ids: Set<Int>) {
            candidates = candidates?.intersect(ids) ?: ids
        }

        filters.search?.trim()?.takeIf { it.isNotEmpty() }?.let { query ->
            val lower = query.lowercase()

            val titleMatches = Releases.selectAll()
                .where { Releases.title.lowerCase() like "%$lower%" }
                .map { it[Releases.id].value }

            val urlMatches = Releases.selectAll()
                .where { Releases.blurayComUrl.lowerCase() like "%$lower%" }
                .map { it[Releases.id].value }

            val distributorIds = Distributors.selectAll()
                .where { Distributors.name.lowerCase() like "%$lower%" }
                .map { it[Distributors.id] }
            val distributorMatches = if (distributorIds.isEmpty()) emptyList() else {
                Releases.selectAll()
                    .where { Releases.distributorId inList distributorIds }
                    .map { it[Releases.id].value }
            }

            val filmMatches = (ReleaseMovies innerJoin Movies)
                .selectAll()
                .where {
                    (Movies.title.lowerCase() like "%$lower%") or
                        (ReleaseMovies.alternateTitle.lowerCase() like "%$lower%")
                }
                .map { it[ReleaseMovies.releaseId].value }

            narrow((titleMatches + urlMatches + distributorMatches + filmMatches).toSet())
        }

        filters.mediaType?.trim()?.takeIf { it.isNotEmpty() }?.let { type ->
            narrow(
                ReleaseMediaTypes.selectAll()
                    .where { ReleaseMediaTypes.mediaType eq type }
                    .map { it[ReleaseMediaTypes.releaseId].value }
                    .toSet()
            )
        }

        filters.distributor?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
            val distributorIds = Distributors.selectAll()
                .where { Distributors.name.lowerCase() eq name.lowercase() }
                .map { it[Distributors.id] }
            narrow(
                if (distributorIds.isEmpty()) emptySet() else {
                    Releases.selectAll()
                        .where { Releases.distributorId inList distributorIds }
                        .map { it[Releases.id].value }
                        .toSet()
                }
            )
        }

        filters.location?.trim()?.takeIf { it.isNotEmpty() }?.let { location ->
            narrow(
                Releases.selectAll()
                    .where { Releases.location eq location }
                    .map { it[Releases.id].value }
                    .toSet()
            )
        }

        if (filters.collectionsOnly) {
            narrow(
                Releases.selectAll()
                    .where { Releases.isCollection eq true }
                    .map { it[Releases.id].value }
                    .toSet()
            )
        }

        if (filters.emptyOnly) {
            val linked = ReleaseMovies.selectAll().map { it[ReleaseMovies.releaseId].value }.toSet()
            narrow(
                Releases.selectAll()
                    .map { it[Releases.id].value }
                    .filterNot { it in linked }
                    .toSet()
            )
        }

        return candidates?.toList()
    }

    /**
     * Normalizes a blu-ray.com URL to the section and numeric id that identify
     * the release, so trailing slashes and differing title slugs still match.
     */
    private fun bluRayKey(url: String?): String? {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val match = Regex("""blu-ray\.com/(movies|dvd)/[^/]+/(\d+)""", RegexOption.IGNORE_CASE).find(trimmed)
            ?: return trimmed.lowercase().substringBefore('?').trimEnd('/')
        return "${match.groupValues[1].lowercase()}:${match.groupValues[2]}"
    }
}
