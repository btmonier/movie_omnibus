package org.btmonier

import kotlinx.serialization.Serializable

/**
 * Enum for physical media types
 */
@Serializable
enum class MediaType {
    VHS,
    DVD,
    BLURAY,  // Stored as "Blu-ray" in database
    FOURK,   // Stored as "4K" in database
    DIGITAL  // Stored as "Digital" in database
}

/**
 * Data class for physical media images
 */
@Serializable
data class PhysicalMediaImage(
    val imageUrl: String,
    val description: String? = null,  // e.g., "Front Cover", "Back Cover", "Spine"
    val id: Int? = null  // Database ID
)

/**
 * One movie's copy of a physical release.
 *
 * This is a flattened view of a release joined with the link that ties it to a
 * single film: the release-level fields ([title], [distributor], [mediaTypes],
 * [images], ...) are shared with every other film on the same release, while
 * [entryLetter] and [alternateTitle] belong to this film alone.
 *
 * [id] identifies the film-to-release link, so editing or removing an entry
 * always addresses "this film's copy" rather than the release itself.
 */
@Serializable
data class PhysicalMedia(
    val mediaTypes: List<MediaType>,  // Can contain multiple types (e.g., Blu-ray + DVD combo)
    val entryLetter: String? = null,  // A-Z letter identifier for the entry
    val title: String? = null,  // Optional title (useful for box sets)
    val alternateTitle: String? = null,  // Title this film carries on this specific release
    val isCollection: Boolean = false,  // Unit holds 2+ films (box set, multi-feature disc)
    val distributor: String? = null,
    val releaseDate: String? = null,  // ISO date string (YYYY-MM-DD)
    val blurayComUrl: String? = null,
    val location: String? = null,  // Archive or Shelf
    val images: List<PhysicalMediaImage> = emptyList(),
    val id: Int? = null,  // Database ID of the film-to-release link
    val createdAt: String? = null,  // ISO datetime string, auto-set on insert
    val releaseId: Int? = null,  // The shared release this entry is a view of
    val sharedWithCount: Int = 0  // Other films on the same release
)

/**
 * A film on a release, as listed by the release browser.
 */
@Serializable
data class ReleaseFilm(
    val movieId: Int,
    val title: String,
    val releaseYear: Int? = null,
    val entryLetter: String? = null,
    val alternateTitle: String? = null,
    val linkId: Int? = null  // Database ID of the film-to-release link
)

/**
 * A physical release with its films. Used by the release detail view and by the
 * create/update endpoints.
 */
@Serializable
data class Release(
    val mediaTypes: List<MediaType> = emptyList(),
    val title: String? = null,
    val isCollection: Boolean = false,
    val distributor: String? = null,
    val releaseDate: String? = null,  // ISO date string (YYYY-MM-DD)
    val blurayComUrl: String? = null,
    val location: String? = null,  // Archive or Shelf
    val images: List<PhysicalMediaImage> = emptyList(),
    val films: List<ReleaseFilm> = emptyList(),
    val filmCount: Int = 0,
    val id: Int? = null,
    val createdAt: String? = null,
    val purchase: Purchase? = null  // What was paid for this unit, when recorded
)

/**
 * A release as shown in the browse grid. Deliberately carries only [filmCount]
 * rather than the films themselves, so listing a page of releases never ships
 * the several hundred film records a large box set would otherwise pull in.
 */
@Serializable
data class ReleaseSummary(
    val id: Int,
    val title: String? = null,
    val isCollection: Boolean = false,
    val distributor: String? = null,
    val releaseDate: String? = null,
    val blurayComUrl: String? = null,
    val location: String? = null,
    val mediaTypes: List<MediaType> = emptyList(),
    val coverUrl: String? = null,
    val filmCount: Int = 0,
    val sampleTitles: List<String> = emptyList()  // A few film titles, for the card subtitle
)

/**
 * Derives the blu-ray.com front cover image URL from a release URL.
 *
 * blu-ray.com release URLs embed the numeric release id, and the cover art is
 * served from a format-specific directory:
 * - Blu-ray/4K releases live under `/movies/` and their covers are served from
 *   `images.static-bluray.com/movies/covers/<id>_large.jpg`
 *   (e.g. `https://www.blu-ray.com/movies/Some-Title/337326/`).
 * - DVD releases live under `/dvd/` and their covers are served from
 *   `images.static-bluray.com/movies/dvdcovers/<id>_large.jpg`
 *   (e.g. `https://www.blu-ray.com/dvd/1-Ichi-DVD/91279/`).
 *
 * Returns null when the URL is not a recognizable blu-ray.com release URL.
 */
fun bluRayCoverImageUrl(blurayComUrl: String?): String? {
    if (blurayComUrl.isNullOrBlank()) return null
    val match = Regex("""blu-ray\.com/(movies|dvd)/[^/]+/(\d+)""", RegexOption.IGNORE_CASE)
        .find(blurayComUrl) ?: return null
    val coverDir = if (match.groupValues[1].equals("dvd", ignoreCase = true)) "dvdcovers" else "covers"
    val id = match.groupValues[2]
    return "https://images.static-bluray.com/movies/${coverDir}/${id}_large.jpg"
}

/**
 * Returns the images to display for a physical media entry: the stored images,
 * or — when none are stored but a blu-ray.com URL is present — the derived
 * blu-ray.com front cover so the cover always shows for blu-ray.com entries.
 */
fun PhysicalMedia.displayImages(): List<PhysicalMediaImage> {
    if (images.isNotEmpty()) return images
    val cover = bluRayCoverImageUrl(blurayComUrl) ?: return emptyList()
    return listOf(PhysicalMediaImage(imageUrl = cover, description = "Front Cover"))
}

/**
 * The images to display for a release, with the same blu-ray.com cover fallback
 * as [PhysicalMedia.displayImages].
 */
fun Release.displayImages(): List<PhysicalMediaImage> {
    if (images.isNotEmpty()) return images
    val cover = bluRayCoverImageUrl(blurayComUrl) ?: return emptyList()
    return listOf(PhysicalMediaImage(imageUrl = cover, description = "Front Cover"))
}

/**
 * Picks the entry letter to assign to a physical media entry, given the entries a
 * movie already has: the first letter of the alphabet not already in use, so a
 * movie with entries A and C gets B rather than D.
 *
 * Pass [excludingId] when re-assigning a letter for an existing entry so its own
 * letter does not count as taken. Returns null once all 26 letters are used.
 */
fun nextEntryLetter(existing: List<PhysicalMedia>, excludingId: Int? = null): String? {
    val used = existing
        .filter { excludingId == null || it.id != excludingId }
        .mapNotNull { entry ->
            entry.entryLetter?.trim()?.uppercase()?.takeIf { it.length == 1 && it[0] in 'A'..'Z' }
        }
        .toSet()
    return ('A'..'Z').firstOrNull { it.toString() !in used }?.toString()
}

// ---------------------------------------------------------------------------
// Wishlist and purchases
// ---------------------------------------------------------------------------

/**
 * Where a wishlist item is on its way from "want" to "own".
 */
@Serializable
enum class WishlistStatus {
    WISHLIST,
    ORDERED,
    SHIPPED,
    OWNED;

    /** The next step along the normal path, or null once owned. */
    fun next(): WishlistStatus? = when (this) {
        WISHLIST -> ORDERED
        ORDERED -> SHIPPED
        SHIPPED -> OWNED
        OWNED -> null
    }
}

@Serializable
enum class WishlistPriority {
    HIGH,
    MEDIUM,
    LOW
}

/**
 * Where a price observation came from. The blu-ray.com list price is the MSRP
 * and is kept apart from the sources that reflect what the item actually sells
 * for.
 */
@Serializable
enum class PriceSource {
    BLURAY_LIST,
    AMAZON,
    NEW_FROM,
    USED_FROM,
    MANUAL
}

/**
 * Prices read from a blu-ray.com release page. Every field is optional because
 * out-of-print releases carry no Price block.
 */
@Serializable
data class BluRayPrices(
    val listPrice: Double? = null,  // MSRP, shown struck through
    val amazonPrice: Double? = null,
    val newFromPrice: Double? = null,  // Cheapest new copy from any seller
    val usedFromPrice: Double? = null,
    val inStock: Boolean? = null,
    val lastPriceChange: String? = null,  // ISO date; null when the page only says "2 days ago"
    val buyLink: String? = null  // blu-ray.com click-through to the store
) {
    val isEmpty: Boolean
        get() = listPrice == null && amazonPrice == null && newFromPrice == null &&
            usedFromPrice == null && inStock == null
}

/**
 * One price seen for a wishlist item at one point in time.
 */
@Serializable
data class PriceObservation(
    val source: PriceSource,
    val price: Double,
    val vendor: String? = null,  // Store name for MANUAL observations
    val inStock: Boolean? = null,
    val observedAt: String? = null,  // ISO datetime, auto-set on insert
    val note: String? = null,
    val id: Int? = null
)

/**
 * What was paid for a physical unit. [taxRate] and [taxAmount] may be left
 * null when creating; the server then applies the configured default rate and
 * computes the amount from [subtotal].
 */
@Serializable
data class Purchase(
    val subtotal: Double,
    val taxRate: Double? = null,
    val taxAmount: Double? = null,
    val shipping: Double = 0.0,
    val vendor: String? = null,
    val orderDate: String? = null,  // ISO date
    val orderNumber: String? = null,
    val trackingUrl: String? = null,
    val shippedDate: String? = null,  // ISO date
    val receivedDate: String? = null,  // ISO date
    val notes: String? = null,
    val id: Int? = null,
    val releaseId: Int? = null,
    val wishlistItemId: Int? = null,
    val createdAt: String? = null
) {
    /** Subtotal plus tax plus shipping, using the computed tax when none is stored. */
    val total: Double
        get() = roundToCents(subtotal + (taxAmount ?: computeTax(subtotal, taxRate ?: DEFAULT_TAX_RATE)) + shipping)
}

/**
 * Body of `POST /api/wishlist/import`: everything needed to wishlist a
 * blu-ray.com release in one step.
 */
@Serializable
data class WishlistImportRequest(
    val url: String,
    val priority: WishlistPriority = WishlistPriority.MEDIUM,
    val targetPrice: Double? = null,
    val tags: List<String> = emptyList(),
    val movieIds: List<Int> = emptyList(),
    val notes: String? = null
)

/**
 * Body of `POST /api/wishlist/{id}/status`. Only the fields relevant to the
 * target status are read: [purchase] when ordering, [shippedDate] and
 * [trackingUrl] when shipped, and [receivedDate], [location], [movieIds] and
 * [releaseId] when the item arrives and becomes a release.
 */
@Serializable
data class WishlistTransitionRequest(
    val status: WishlistStatus,
    val purchase: Purchase? = null,
    val shippedDate: String? = null,
    val trackingUrl: String? = null,
    val receivedDate: String? = null,
    val location: String? = null,
    val movieIds: List<Int>? = null,
    val releaseId: Int? = null
)

/** Iowa state sales tax, used when nothing else is configured. */
const val DEFAULT_TAX_RATE: Double = 0.06

/**
 * Rounds a currency amount to whole cents, halves rounding up (the way a
 * receipt does), so 1.005 becomes 1.01 rather than 1.00.
 */
fun roundToCents(amount: Double): Double {
    val cents = kotlin.math.floor(amount * 100 + 0.5 + 1e-7)
    return cents / 100
}

/**
 * Sales tax on [subtotal] at [rate], rounded to cents. Shipping is not taxed.
 */
fun computeTax(subtotal: Double, rate: Double): Double = roundToCents(subtotal * rate)

/**
 * A film a wishlist item will be linked to once it is owned.
 */
@Serializable
data class WishlistMovie(
    val movieId: Int,
    val title: String,
    val releaseYear: Int? = null
)

/**
 * A physical release that is wanted, on order, or recently received. Carries
 * the same release-level fields as [Release] so it can become one without any
 * re-typing, plus the tracking data that only matters before it is owned.
 *
 * The derived price fields ([currentPrice], [previousPrice], [lowestPrice]) are
 * read from the observations in [priceHistory], ignoring the list price.
 */
@Serializable
data class WishlistItem(
    val mediaTypes: List<MediaType> = emptyList(),
    val title: String? = null,
    val isCollection: Boolean = false,
    val distributor: String? = null,
    val releaseDate: String? = null,  // ISO date
    val blurayComUrl: String? = null,
    val images: List<PhysicalMediaImage> = emptyList(),
    val status: WishlistStatus = WishlistStatus.WISHLIST,
    val priority: WishlistPriority = WishlistPriority.MEDIUM,
    val targetPrice: Double? = null,
    val listPrice: Double? = null,  // MSRP from blu-ray.com
    val asin: String? = null,
    val buyUrl: String? = null,
    val notes: String? = null,
    val tags: List<String> = emptyList(),
    val linkedMovies: List<WishlistMovie> = emptyList(),
    val currentPrice: Double? = null,
    val currentPriceSource: PriceSource? = null,
    val previousPrice: Double? = null,
    val lowestPrice: Double? = null,
    val inStock: Boolean? = null,
    val priceHistory: List<PriceObservation> = emptyList(),
    val purchase: Purchase? = null,
    val releaseId: Int? = null,  // Set once owned
    val lastPriceCheckAt: String? = null,
    val statusChangedAt: String? = null,
    val id: Int? = null,
    val createdAt: String? = null
) {
    /** True when a target is set and the current price meets it. */
    val atTarget: Boolean
        get() = targetPrice != null && currentPrice != null && currentPrice <= targetPrice

    /** True when the latest observation is lower than the one before it. */
    val priceDropped: Boolean
        get() = currentPrice != null && previousPrice != null && currentPrice < previousPrice

    /** Percent below list price, or null when either side is missing. */
    val percentOffList: Int?
        get() {
            val list = listPrice ?: return null
            val current = currentPrice ?: return null
            if (list <= 0.0 || current >= list) return null
            return ((list - current) / list * 100).toInt()
        }
}

/**
 * The images to display for a wishlist item, with the same blu-ray.com cover
 * fallback as [Release.displayImages].
 */
fun WishlistItem.displayImages(): List<PhysicalMediaImage> {
    if (images.isNotEmpty()) return images
    val cover = bluRayCoverImageUrl(blurayComUrl) ?: return emptyList()
    return listOf(PhysicalMediaImage(imageUrl = cover, description = "Front Cover"))
}

/**
 * Collection-level numbers shown at the top of the wishlist page.
 */
@Serializable
data class WishlistSummary(
    val countsByStatus: Map<String, Int> = emptyMap(),  // keyed by WishlistStatus name
    val wishlistTotalAtCurrentPrices: Double = 0.0,  // Sum of current prices for WISHLIST items that have one
    val wishlistPricedCount: Int = 0,
    val atTargetCount: Int = 0,
    val spentAllTime: Double = 0.0,
    val spentThisYear: Double = 0.0
)

/**
 * Price bracket labels used to group wishlist items.
 */
fun priceBracket(price: Double?): String = when {
    price == null -> "No price"
    price < 15.0 -> "Under \$15"
    price < 30.0 -> "\$15 – \$30"
    price < 50.0 -> "\$30 – \$50"
    else -> "\$50 and up"
}

/** Display order for [priceBracket] groups. */
val PRICE_BRACKET_ORDER: List<String> = listOf("Under \$15", "\$15 – \$30", "\$30 – \$50", "\$50 and up", "No price")

/**
 * Release-date bucket for grouping wishlist items. [today] is an ISO date so
 * the comparison is a plain string comparison.
 */
fun releaseDateBucket(releaseDate: String?, today: String): String {
    val date = releaseDate?.takeIf { it.length >= 10 } ?: return "Unknown date"
    return when {
        date <= today -> "Available"
        date.substring(0, 7) == today.substring(0, 7) -> "Out this month"
        else -> "Pre-order"
    }
}

/** Display order for [releaseDateBucket] groups. */
val RELEASE_BUCKET_ORDER: List<String> = listOf("Out this month", "Pre-order", "Available", "Unknown date")

/**
 * Data class for watched entries
 * A movie can be watched multiple times with different ratings
 */
@Serializable
data class WatchedEntry(
    val watchedDate: String,  // ISO date string (YYYY-MM-DD)
    val rating: Double? = null,  // Rating out of 10 (e.g., 8.5)
    val notes: String? = null,  // Optional viewing notes
    val id: Int? = null  // Database ID
)

@Serializable
data class MovieMetadata(
    val url: String,
    val title: String = "",
    val description: String? = null,  // Movie synopsis/description
    val alternateTitles: List<String> = emptyList(),  // Alternate titles from different regions
    val genres: List<String> = emptyList(),
    val subgenres: List<String> = emptyList(),  // More specific genre classifications
    val collections: List<String> = emptyList(),  // Groupings like franchises or thematic/vibe collections
    val themes: List<String> = emptyList(),
    val country: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val crew: Map<String, List<String>> = emptyMap(),
    val release_date: Int? = null,
    val runtime_mins: Int? = null,
    val physicalMedia: List<PhysicalMedia> = emptyList(),
    val watchedEntries: List<WatchedEntry> = emptyList(),
    val id: Int? = null,  // Database ID, only populated when reading from DB
    val createdAt: String? = null  // ISO datetime string, auto-set on insert
)
