package org.btmonier.database

import org.btmonier.BluRayPrices
import org.btmonier.MediaType
import org.btmonier.PhysicalMediaImage
import org.btmonier.PriceObservation
import org.btmonier.PriceSource
import org.btmonier.Purchase
import org.btmonier.Release
import org.btmonier.WishlistItem
import org.btmonier.WishlistMovie
import org.btmonier.WishlistPriority
import org.btmonier.WishlistStatus
import org.btmonier.WishlistSummary
import org.btmonier.WishlistTransitionRequest
import org.btmonier.storage.GcsService
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * How the wishlist should be ordered.
 */
enum class WishlistSortField(val slug: String) {
    DATE_ADDED("date_added"),
    TITLE("title"),
    PRICE("price"),
    PERCENT_OFF("percent_off"),
    PRICE_DROP("price_drop"),
    RELEASE_DATE("release_date"),
    PRIORITY("priority");

    companion object {
        fun fromSlug(slug: String?): WishlistSortField =
            entries.firstOrNull { it.slug.equals(slug, ignoreCase = true) } ?: DATE_ADDED
    }
}

/**
 * Filters applied when listing wishlist items.
 */
data class WishlistFilters(
    val search: String? = null,
    val status: WishlistStatus? = null,
    val mediaType: String? = null,
    val distributor: String? = null,
    val tag: String? = null,
    val priority: WishlistPriority? = null,
    val atTargetOnly: Boolean = false,
    val inStockOnly: Boolean = false
)

/**
 * Result of moving an item to a new status.
 */
sealed interface TransitionOutcome {
    data class Done(val item: WishlistItem) : TransitionOutcome
    data object NotFound : TransitionOutcome
    data class Invalid(val message: String) : TransitionOutcome
}

/**
 * Data Access Object for the wishlist: physical releases that are wanted, on
 * order, or just arrived.
 *
 * Items are few enough that filtering and sorting happen in memory after one
 * batched load, which keeps the many-to-many joins (formats, tags, films,
 * price history) to one query each rather than one per item.
 */
class WishlistDao(
    private val gcsService: GcsService? = null,
    private val releaseDao: ReleaseDao = ReleaseDao(gcsService),
    private val purchaseDao: PurchaseDao = PurchaseDao()
) {
    private val categoryDao = CategoryDao()

    /** Sources that reflect what an item sells for right now. */
    private val sellingSources = setOf(PriceSource.AMAZON, PriceSource.NEW_FROM, PriceSource.MANUAL)

    // --- Reads ---

    suspend fun list(
        filters: WishlistFilters = WishlistFilters(),
        sortField: WishlistSortField = WishlistSortField.DATE_ADDED,
        ascending: Boolean = false
    ): List<WishlistItem> = DatabaseFactory.dbQuery {
        loadItems(WishlistItems.selectAll().toList())
            .filter { matches(it, filters) }
            .sortedWith(comparator(sortField, ascending))
    }

    suspend fun get(id: Int): WishlistItem? = DatabaseFactory.dbQuery { getInTransaction(id) }

    /**
     * The item, if any, already wishlisted under a blu-ray.com URL.
     */
    suspend fun findByBluRayUrl(url: String): WishlistItem? = DatabaseFactory.dbQuery {
        val key = bluRayKey(url) ?: return@dbQuery null
        val row = WishlistItems.selectAll()
            .where { WishlistItems.blurayComUrl.isNotNull() }
            .firstOrNull { bluRayKey(it[WishlistItems.blurayComUrl]) == key }
            ?: return@dbQuery null
        loadItems(listOf(row)).firstOrNull()
    }

    /**
     * Distinct values for the filter dropdowns: formats, distributors and tags
     * currently in use on the wishlist.
     */
    suspend fun filterOptions(): Triple<List<String>, List<String>, List<String>> = DatabaseFactory.dbQuery {
        val types = WishlistItemMediaTypes.selectAll().map { it[WishlistItemMediaTypes.mediaType] }.distinct().sorted()
        val distributors = (WishlistItems innerJoin Distributors).select(Distributors.name)
            .map { it[Distributors.name] }.distinct().sortedBy { it.lowercase() }
        val tags = (WishlistItemTags innerJoin WishlistTags).select(WishlistTags.name)
            .map { it[WishlistTags.name] }.distinct().sortedBy { it.lowercase() }
        Triple(types, distributors, tags)
    }

    suspend fun summary(): WishlistSummary = DatabaseFactory.dbQuery {
        val items = loadItems(WishlistItems.selectAll().toList())
        val wanted = items.filter { it.status == WishlistStatus.WISHLIST }
        val priced = wanted.mapNotNull { it.currentPrice }

        val purchases = purchaseDao.allInTransaction()
        val thisYear = LocalDate.now().year.toString()

        WishlistSummary(
            countsByStatus = WishlistStatus.entries.associate { status -> status.name to items.count { it.status == status } },
            wishlistTotalAtCurrentPrices = org.btmonier.roundToCents(priced.sum()),
            wishlistPricedCount = priced.size,
            atTargetCount = wanted.count { it.atTarget },
            spentAllTime = org.btmonier.roundToCents(purchases.sumOf { it.total }),
            spentThisYear = org.btmonier.roundToCents(
                purchases.filter { (it.orderDate ?: it.createdAt ?: "").startsWith(thisYear) }.sumOf { it.total }
            )
        )
    }

    /**
     * Items whose price should be re-scraped: those with a blu-ray.com URL that
     * are not yet owned (or every item, with [includeAll]) and were last checked
     * more than [minAgeHours] ago (or never).
     */
    suspend fun itemsDueForRefresh(minAgeHours: Double? = null, includeAll: Boolean = false): List<Pair<Int, String>> =
        DatabaseFactory.dbQuery {
            val cutoff = minAgeHours?.let { LocalDateTime.now().minusMinutes((it * 60).toLong()) }
            WishlistItems.selectAll()
                .where { WishlistItems.blurayComUrl.isNotNull() }
                .filter { row ->
                    val status = statusOf(row[WishlistItems.status])
                    val eligible = includeAll || status == WishlistStatus.WISHLIST || status == WishlistStatus.ORDERED
                    val lastCheck = row[WishlistItems.lastPriceCheckAt]
                    eligible && (cutoff == null || lastCheck == null || lastCheck.isBefore(cutoff))
                }
                .map { it[WishlistItems.id].value to it[WishlistItems.blurayComUrl]!! }
        }

    // --- Writes ---

    /**
     * Create an item. Formats, images, tags and films on [item] are written;
     * price observations are not - use [recordScrapedPrices] or [addObservation].
     */
    suspend fun create(item: WishlistItem): Int = DatabaseFactory.dbQuery {
        val id = WishlistItems.insertAndGetId {
            writeFields(it, item)
            it[status] = item.status.name
            it[statusChangedAt] = LocalDateTime.now()
        }.value

        writeMediaTypes(id, item.mediaTypes)
        writeImages(id, item.images)
        writeTags(id, item.tags)
        writeMovies(id, item.linkedMovies.map { it.movieId })
        id
    }

    /**
     * Update the editable fields. Status, price history and purchase are left
     * alone; those have their own operations.
     */
    suspend fun update(id: Int, item: WishlistItem): Boolean = DatabaseFactory.dbQuery {
        val updated = WishlistItems.update({ WishlistItems.id eq id }) { writeFields(it, item) }
        if (updated == 0) return@dbQuery false

        WishlistItemMediaTypes.deleteWhere { itemId eq id }
        writeMediaTypes(id, item.mediaTypes)

        WishlistItemImages.deleteWhere { itemId eq id }
        writeImages(id, item.images)

        WishlistItemTags.deleteWhere { itemId eq id }
        writeTags(id, item.tags)

        WishlistItemMovies.deleteWhere { itemId eq id }
        writeMovies(id, item.linkedMovies.map { it.movieId })
        true
    }

    suspend fun delete(id: Int): Boolean = DatabaseFactory.dbQuery {
        purchaseDao.detachFromItemInTransaction(id)
        WishlistItemMediaTypes.deleteWhere { itemId eq id }
        WishlistItemImages.deleteWhere { itemId eq id }
        WishlistItemTags.deleteWhere { itemId eq id }
        WishlistItemMovies.deleteWhere { itemId eq id }
        WishlistPriceHistory.deleteWhere { itemId eq id }
        WishlistItems.deleteWhere { WishlistItems.id eq id } > 0
    }

    /**
     * Replace the films this item will be linked to.
     */
    suspend fun setMovies(id: Int, movieIds: List<Int>): Boolean = DatabaseFactory.dbQuery {
        if (!exists(id)) return@dbQuery false
        WishlistItemMovies.deleteWhere { itemId eq id }
        writeMovies(id, movieIds)
        true
    }

    /**
     * Log a price seen somewhere (a store, eBay, a sale email). Returns the
     * observation id, or null when the item does not exist.
     */
    suspend fun addObservation(id: Int, observation: PriceObservation): Int? = DatabaseFactory.dbQuery {
        if (!exists(id)) return@dbQuery null
        WishlistPriceHistory.insertAndGetId {
            it[itemId] = id
            it[priceSource] = observation.source.name
            it[vendor] = observation.vendor?.trim()?.takeIf(String::isNotEmpty)
            it[price] = BigDecimal.valueOf(org.btmonier.roundToCents(observation.price))
            it[inStock] = observation.inStock
            it[note] = observation.note?.trim()?.takeIf(String::isNotEmpty)
            observation.observedAt?.let { at ->
                runCatching { LocalDateTime.parse(at) }.getOrNull()?.let { parsed -> it[observedAt] = parsed }
            }
        }.value
    }

    suspend fun deleteObservation(id: Int, observationId: Int): Boolean = DatabaseFactory.dbQuery {
        WishlistPriceHistory.deleteWhere {
            (WishlistPriceHistory.id eq observationId) and (itemId eq id)
        } > 0
    }

    /**
     * Store the prices from a blu-ray.com scrape. The list price, stock status
     * and buy link on the item are refreshed, and a history row is appended for
     * each source whose value differs from its last observation. Returns how
     * many rows were appended, or -1 when the item does not exist.
     */
    suspend fun recordScrapedPrices(id: Int, prices: BluRayPrices): Int = DatabaseFactory.dbQuery {
        if (!exists(id)) return@dbQuery -1

        WishlistItems.update({ WishlistItems.id eq id }) {
            it[lastPriceCheckAt] = LocalDateTime.now()
            prices.listPrice?.let { value -> it[listPrice] = BigDecimal.valueOf(value) }
            prices.buyLink?.let { value -> it[buyUrl] = value }
            prices.inStock?.let { value -> it[inStock] = value }
        }

        val scraped = listOf(
            PriceSource.BLURAY_LIST to prices.listPrice,
            PriceSource.AMAZON to prices.amazonPrice,
            PriceSource.NEW_FROM to prices.newFromPrice,
            PriceSource.USED_FROM to prices.usedFromPrice
        )

        var added = 0
        scraped.forEach { (source, value) ->
            if (value == null) return@forEach
            val last = WishlistPriceHistory.selectAll()
                .where { (WishlistPriceHistory.itemId eq id) and (WishlistPriceHistory.priceSource eq source.name) }
                .orderBy(WishlistPriceHistory.observedAt to SortOrder.DESC, WishlistPriceHistory.id to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
            val lastPrice = last?.get(WishlistPriceHistory.price)?.toDouble()
            if (lastPrice == null || lastPrice != value) {
                WishlistPriceHistory.insert {
                    it[itemId] = id
                    it[priceSource] = source.name
                    it[price] = BigDecimal.valueOf(value)
                    it[inStock] = if (source == PriceSource.BLURAY_LIST) null else prices.inStock
                }
                added++
            }
        }
        added
    }

    /**
     * Move an item along the wishlist -> ordered -> shipped -> owned path (or
     * back). Reaching OWNED turns the item into a release: the release recorded
     * under the same blu-ray.com URL is joined when there is one, otherwise a
     * release is created from the item's fields, and the chosen films are put on
     * it. The item survives, pointing at the release, as the purchase record.
     */
    suspend fun transition(id: Int, request: WishlistTransitionRequest): TransitionOutcome = DatabaseFactory.dbQuery {
        val item = getInTransaction(id) ?: return@dbQuery TransitionOutcome.NotFound

        request.purchase?.let { purchaseDao.upsertForItemInTransaction(id, it) }

        if (request.shippedDate != null || request.trackingUrl != null) {
            purchaseDao.updateShippingInTransaction(id, request.shippedDate, request.trackingUrl)
        }

        var releaseId: Int? = item.releaseId
        if (request.status == WishlistStatus.OWNED) {
            if (request.releaseId != null && !releaseDao.releaseExistsInTransaction(request.releaseId)) {
                return@dbQuery TransitionOutcome.Invalid("Release ${request.releaseId} does not exist")
            }

            releaseId = request.releaseId
                ?: item.releaseId
                ?: item.blurayComUrl?.let { url -> releaseDao.findByBluRayUrlInTransaction(url)?.id }
                ?: releaseDao.createReleaseInTransaction(
                    Release(
                        mediaTypes = item.mediaTypes,
                        title = item.title,
                        isCollection = item.isCollection,
                        distributor = item.distributor,
                        releaseDate = item.releaseDate,
                        blurayComUrl = item.blurayComUrl,
                        location = request.location?.trim()?.takeIf(String::isNotEmpty) ?: "Shelf",
                        images = item.images
                    )
                )

            val movieIds = (request.movieIds ?: item.linkedMovies.map { it.movieId }).distinct()
            movieIds.forEach { movieId ->
                releaseDao.linkMovieInTransaction(releaseId, movieId, entryLetter = null, alternateTitle = null)
            }
            if (request.movieIds != null) {
                WishlistItemMovies.deleteWhere { itemId eq id }
                writeMovies(id, movieIds)
            }

            purchaseDao.attachToReleaseInTransaction(id, releaseId, request.receivedDate)
        }

        WishlistItems.update({ WishlistItems.id eq id }) {
            it[status] = request.status.name
            it[statusChangedAt] = LocalDateTime.now()
            it[WishlistItems.releaseId] = releaseId?.let { rid -> EntityID(rid, Releases) }
        }

        TransitionOutcome.Done(getInTransaction(id)!!)
    }

    // --- Internals ---

    internal fun getInTransaction(id: Int): WishlistItem? =
        loadItems(WishlistItems.selectAll().where { WishlistItems.id eq id }.toList()).firstOrNull()

    private fun exists(id: Int): Boolean =
        WishlistItems.selectAll().where { WishlistItems.id eq id }.any()

    private fun writeFields(statement: UpdateBuilder<*>, item: WishlistItem) {
        statement[WishlistItems.title] = item.title?.trim()?.takeIf(String::isNotEmpty)
        statement[WishlistItems.isCollection] = item.isCollection
        statement[WishlistItems.distributorId] = resolveDistributor(item.distributor)
        statement[WishlistItems.releaseDate] = item.releaseDate?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        statement[WishlistItems.blurayComUrl] = item.blurayComUrl?.trim()?.takeIf(String::isNotEmpty)
        statement[WishlistItems.priority] = item.priority.name
        statement[WishlistItems.targetPrice] = item.targetPrice?.let { BigDecimal.valueOf(org.btmonier.roundToCents(it)) }
        statement[WishlistItems.listPrice] = item.listPrice?.let { BigDecimal.valueOf(org.btmonier.roundToCents(it)) }
        statement[WishlistItems.asin] = item.asin?.trim()?.takeIf(String::isNotEmpty)
        item.buyUrl?.trim()?.takeIf(String::isNotEmpty)?.let { statement[WishlistItems.buyUrl] = it }
        statement[WishlistItems.notes] = item.notes?.trim()?.takeIf(String::isNotEmpty)
    }

    private fun writeMediaTypes(id: Int, mediaTypes: List<MediaType>) {
        mediaTypes.distinct().forEach { type ->
            WishlistItemMediaTypes.insert {
                it[itemId] = id
                it[mediaType] = mediaTypeToString(type)
            }
        }
    }

    private fun writeImages(id: Int, images: List<PhysicalMediaImage>) {
        images.forEach { image ->
            val cleaned = gcsService?.cleanUrlForStorage(image.imageUrl) ?: image.imageUrl
            WishlistItemImages.insert {
                it[itemId] = id
                it[imageUrl] = cleaned
                it[description] = image.description
            }
        }
    }

    private fun writeTags(id: Int, tags: List<String>) {
        tags.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.forEach { name ->
            val tagId = categoryDao.getOrCreateInTransaction(CategoryType.WISHLIST_TAG, name)
            WishlistItemTags.insert {
                it[itemId] = id
                it[WishlistItemTags.tagId] = tagId
            }
        }
    }

    private fun writeMovies(id: Int, movieIds: List<Int>) {
        val existing = Movies.selectAll().where { Movies.id inList movieIds }.map { it[Movies.id].value }.toSet()
        movieIds.distinct().filter { it in existing }.forEach { movieId ->
            WishlistItemMovies.insert {
                it[itemId] = id
                it[WishlistItemMovies.movieId] = movieId
            }
        }
    }

    private fun resolveDistributor(name: String?): EntityID<Int>? =
        name?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { EntityID(categoryDao.getOrCreateInTransaction(CategoryType.DISTRIBUTOR, it), Distributors) }

    /**
     * Build full items for a set of rows, loading each child table once.
     */
    private fun loadItems(rows: List<ResultRow>): List<WishlistItem> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it[WishlistItems.id].value }

        val mediaTypes = WishlistItemMediaTypes.selectAll().where { WishlistItemMediaTypes.itemId inList ids }
            .groupBy({ it[WishlistItemMediaTypes.itemId].value }) { stringToMediaTypeOrNull(it[WishlistItemMediaTypes.mediaType]) }

        val images = WishlistItemImages.selectAll().where { WishlistItemImages.itemId inList ids }
            .groupBy({ it[WishlistItemImages.itemId].value }) {
                val raw = it[WishlistItemImages.imageUrl]
                PhysicalMediaImage(
                    imageUrl = gcsService?.transformUrl(raw) ?: raw,
                    description = it[WishlistItemImages.description],
                    id = it[WishlistItemImages.id].value
                )
            }

        val tags = (WishlistItemTags innerJoin WishlistTags).selectAll().where { WishlistItemTags.itemId inList ids }
            .groupBy({ it[WishlistItemTags.itemId].value }) { it[WishlistTags.name] }

        val movies = (WishlistItemMovies innerJoin Movies).selectAll().where { WishlistItemMovies.itemId inList ids }
            .groupBy({ it[WishlistItemMovies.itemId].value }) {
                WishlistMovie(
                    movieId = it[Movies.id].value,
                    title = it[Movies.title],
                    releaseYear = it[Movies.releaseDate]?.year
                )
            }

        val history = WishlistPriceHistory.selectAll().where { WishlistPriceHistory.itemId inList ids }
            .orderBy(WishlistPriceHistory.observedAt to SortOrder.ASC, WishlistPriceHistory.id to SortOrder.ASC)
            .groupBy({ it[WishlistPriceHistory.itemId].value }) {
                PriceObservation(
                    source = PriceSource.entries.firstOrNull { s -> s.name == it[WishlistPriceHistory.priceSource] }
                        ?: PriceSource.MANUAL,
                    price = it[WishlistPriceHistory.price].toDouble(),
                    vendor = it[WishlistPriceHistory.vendor],
                    inStock = it[WishlistPriceHistory.inStock],
                    observedAt = it[WishlistPriceHistory.observedAt].toString(),
                    note = it[WishlistPriceHistory.note],
                    id = it[WishlistPriceHistory.id].value
                )
            }

        val purchases = Purchases.selectAll().where { Purchases.wishlistItemId inList ids }
            .associate { it[Purchases.wishlistItemId]!!.value to purchaseDao.rowToPurchase(it) }

        val distributorIds = rows.mapNotNull { it[WishlistItems.distributorId] }.distinct()
        val distributors = if (distributorIds.isEmpty()) emptyMap() else {
            Distributors.selectAll().where { Distributors.id inList distributorIds }
                .associate { it[Distributors.id].value to it[Distributors.name] }
        }

        return rows.map { row ->
            val id = row[WishlistItems.id].value
            val itemHistory = history[id].orEmpty()
            val derived = derivePrices(itemHistory)

            WishlistItem(
                mediaTypes = mediaTypes[id].orEmpty().filterNotNull().distinct(),
                title = row[WishlistItems.title],
                isCollection = row[WishlistItems.isCollection],
                distributor = row[WishlistItems.distributorId]?.let { distributors[it.value] },
                releaseDate = row[WishlistItems.releaseDate]?.toString(),
                blurayComUrl = row[WishlistItems.blurayComUrl],
                images = images[id].orEmpty(),
                status = statusOf(row[WishlistItems.status]),
                priority = priorityOf(row[WishlistItems.priority]),
                targetPrice = row[WishlistItems.targetPrice]?.toDouble(),
                listPrice = row[WishlistItems.listPrice]?.toDouble(),
                asin = row[WishlistItems.asin],
                buyUrl = row[WishlistItems.buyUrl],
                notes = row[WishlistItems.notes],
                tags = tags[id].orEmpty().sortedBy { it.lowercase() },
                linkedMovies = movies[id].orEmpty().sortedBy { it.title.lowercase() },
                currentPrice = derived.current?.price,
                currentPriceSource = derived.current?.source,
                previousPrice = derived.previous,
                lowestPrice = derived.lowest,
                inStock = row[WishlistItems.inStock],
                priceHistory = itemHistory,
                purchase = purchases[id],
                releaseId = row[WishlistItems.releaseId]?.value,
                lastPriceCheckAt = row[WishlistItems.lastPriceCheckAt]?.toString(),
                statusChangedAt = row[WishlistItems.statusChangedAt].toString(),
                id = id,
                createdAt = row[WishlistItems.createdAt].toString()
            )
        }
    }

    private data class DerivedPrices(val current: PriceObservation?, val previous: Double?, val lowest: Double?)

    /**
     * The current price is the best of each selling source's latest value; the
     * previous price is that source's observation before it; the lowest is the
     * cheapest selling price ever seen. Used prices and the MSRP are ignored.
     */
    private fun derivePrices(history: List<PriceObservation>): DerivedPrices {
        val selling = history.filter { it.source in sellingSources }
        if (selling.isEmpty()) return DerivedPrices(null, null, null)

        val latestPerSource = selling.groupBy { it.source }.mapValues { (_, obs) -> obs.last() }
        val current = latestPerSource.values.minBy { it.price }
        val previous = selling.filter { it.source == current.source }.dropLast(1).lastOrNull()?.price
        val lowest = selling.minOf { it.price }
        return DerivedPrices(current, previous, lowest)
    }

    private fun statusOf(value: String): WishlistStatus =
        WishlistStatus.entries.firstOrNull { it.name == value } ?: WishlistStatus.WISHLIST

    private fun priorityOf(value: String): WishlistPriority =
        WishlistPriority.entries.firstOrNull { it.name == value } ?: WishlistPriority.MEDIUM

    private fun matches(item: WishlistItem, filters: WishlistFilters): Boolean {
        filters.search?.trim()?.takeIf { it.isNotEmpty() }?.let { query ->
            val haystack = buildList {
                add(item.title)
                add(item.distributor)
                add(item.blurayComUrl)
                add(item.notes)
                addAll(item.tags)
                addAll(item.linkedMovies.map { it.title })
            }
            if (haystack.none { it?.contains(query, ignoreCase = true) == true }) return false
        }
        if (filters.status != null && item.status != filters.status) return false
        filters.mediaType?.trim()?.takeIf { it.isNotEmpty() }?.let { type ->
            if (item.mediaTypes.none { mediaTypeToString(it).equals(type, ignoreCase = true) }) return false
        }
        filters.distributor?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
            if (!item.distributor.equals(name, ignoreCase = true)) return false
        }
        filters.tag?.trim()?.takeIf { it.isNotEmpty() }?.let { tag ->
            if (item.tags.none { it.equals(tag, ignoreCase = true) }) return false
        }
        if (filters.priority != null && item.priority != filters.priority) return false
        if (filters.atTargetOnly && !item.atTarget) return false
        if (filters.inStockOnly && item.inStock != true) return false
        return true
    }

    private fun comparator(field: WishlistSortField, ascending: Boolean): Comparator<WishlistItem> {
        val base: Comparator<WishlistItem> = when (field) {
            WishlistSortField.DATE_ADDED -> compareBy { it.createdAt }
            WishlistSortField.TITLE -> compareBy(nullsLast()) { it.title?.lowercase() }
            WishlistSortField.PRICE -> compareBy(nullsLast()) { it.currentPrice }
            WishlistSortField.PERCENT_OFF -> compareBy(nullsLast()) { it.percentOffList }
            WishlistSortField.PRICE_DROP -> compareBy(nullsLast()) {
                val current = it.currentPrice
                val previous = it.previousPrice
                if (current != null && previous != null) previous - current else null
            }
            WishlistSortField.RELEASE_DATE -> compareBy(nullsLast()) { it.releaseDate }
            WishlistSortField.PRIORITY -> compareBy { it.priority.ordinal }
        }
        val tieBroken = base.thenBy { it.id }
        return if (ascending) tieBroken else tieBroken.reversed()
    }

    private fun bluRayKey(url: String?): String? {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val match = Regex("""blu-ray\.com/(movies|dvd)/[^/]+/(\d+)""", RegexOption.IGNORE_CASE).find(trimmed)
            ?: return trimmed.lowercase().substringBefore('?').trimEnd('/')
        return "${match.groupValues[1].lowercase()}:${match.groupValues[2]}"
    }
}
