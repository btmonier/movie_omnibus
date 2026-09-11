package org.btmonier.database

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.CurrentDateTime
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.datetime

/**
 * Database table schema for movie metadata.
 */
object Movies : IntIdTable("movies") {
    val url = varchar("url", 500).uniqueIndex()
    val title = varchar("title", 500)
    val description = text("description").nullable() // Movie synopsis/description
    val releaseDate = date("release_date").nullable()
    val runtimeMins = integer("runtime_mins").nullable()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime) // Auto-set on insert
}

/**
 * Table for alternate movie titles (many-to-many relationship)
 */
object MovieAlternateTitles : IntIdTable("movie_alternate_titles") {
    val movieId = reference("movie_id", Movies)
    val alternateTitle = varchar("alternate_title", 500)
}

/**
 * Global genres table - master list of all available genres
 */
object Genres : IntIdTable("genres") {
    val name = varchar("name", 200).uniqueIndex()
}

/**
 * Global subgenres table - master list of all available subgenres
 */
object Subgenres : IntIdTable("subgenres") {
    val name = varchar("name", 200).uniqueIndex()
}

/**
 * Global collections table - master list of all available collections
 * (e.g. franchises, or thematic/vibe groupings). Each collection can carry
 * an optional description.
 */
object Collections : IntIdTable("collections") {
    val name = varchar("name", 200).uniqueIndex()
    val description = text("description").nullable()
}

/**
 * Global distributors table - master list of all physical media distributors
 */
object Distributors : IntIdTable("distributors") {
    val name = varchar("name", 200).uniqueIndex()
}

/**
 * Global themes table - master list of all available themes
 */
object Themes : IntIdTable("themes") {
    val name = varchar("name", 200).uniqueIndex()
}

/**
 * Global countries table - master list of all available countries
 */
object Countries : IntIdTable("countries") {
    val name = varchar("name", 200).uniqueIndex()
}

/**
 * Table for movie genres (many-to-many relationship)
 * Links movies to genres from the global Genres table
 */
object MovieGenres : IntIdTable("movie_genres") {
    val movieId = reference("movie_id", Movies)
    val genreId = reference("genre_id", Genres)
}

/**
 * Table for movie subgenres (many-to-many relationship)
 * Links movies to subgenres from the global Subgenres table
 */
object MovieSubgenres : IntIdTable("movie_subgenres") {
    val movieId = reference("movie_id", Movies)
    val subgenreId = reference("subgenre_id", Subgenres)
}

/**
 * Table for movie collections (many-to-many relationship)
 * Links movies to collections from the global Collections table
 */
object MovieCollections : IntIdTable("movie_collections") {
    val movieId = reference("movie_id", Movies)
    val collectionId = reference("collection_id", Collections)
}

/**
 * Table for movie themes (many-to-many relationship)
 * Links movies to themes from the global Themes table
 */
object MovieThemes : IntIdTable("movie_themes") {
    val movieId = reference("movie_id", Movies)
    val themeId = reference("theme_id", Themes)
}

/**
 * Table for movie countries (many-to-many relationship)
 * Links movies to countries from the global Countries table
 */
object MovieCountries : IntIdTable("movie_countries") {
    val movieId = reference("movie_id", Movies)
    val countryId = reference("country_id", Countries)
}

/**
 * Table for movie cast members (many-to-many relationship)
 */
object MovieCast : IntIdTable("movie_cast") {
    val movieId = reference("movie_id", Movies)
    val castMember = varchar("cast_member", 200)
}

/**
 * Table for movie crew (many-to-many relationship with role)
 */
object MovieCrew : IntIdTable("movie_crew") {
    val movieId = reference("movie_id", Movies)
    val role = varchar("role", 200)
    val crewMember = varchar("crew_member", 200)
}

/**
 * Table for physical releases - one row per owned physical unit, no matter how
 * many films it holds. A 200 film box set is a single row here, linked to each
 * of its films through [ReleaseMovies].
 */
object Releases : IntIdTable("releases") {
    val title = varchar("title", 500).nullable() // Optional title (useful for box sets)
    // Unit holds 2+ films (box set, multi-feature disc). Nullable so it reads the
    // same as the legacy physical_media column it was migrated from; null is false.
    val isCollection = bool("is_collection").nullable()
    val distributorId = optReference("distributor_id", Distributors)
    val releaseDate = date("release_date").nullable()
    val blurayComUrl = varchar("bluray_com_url", 500).nullable()
    val location = varchar("location", 50).nullable() // Archive, Shelf
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime) // Auto-set on insert
}

/**
 * Table linking films to the releases they appear on (many-to-many).
 * Carries the data that is specific to one film on one release rather than to
 * the release as a whole.
 */
object ReleaseMovies : IntIdTable("release_movies") {
    val releaseId = reference("release_id", Releases)
    val movieId = reference("movie_id", Movies)
    val entryLetter = varchar("entry_letter", 1).nullable() // A-Z identifier among a movie's copies
    val alternateTitle = varchar("alternate_title", 500).nullable() // Title this film carries on this release
}

/**
 * Table for release media types (many-to-many relationship with releases)
 * A single release can have multiple types (e.g., Blu-ray + DVD combo)
 */
object ReleaseMediaTypes : IntIdTable("release_media_types") {
    val releaseId = reference("release_id", Releases)
    val mediaType = varchar("media_type", 50) // VHS, DVD, Blu-ray, 4K
}

/**
 * Table for release images (one-to-many relationship with releases)
 */
object ReleaseImages : IntIdTable("release_images") {
    val releaseId = reference("release_id", Releases)
    // Use text (not a bounded varchar) so long values such as GCS signed URLs
    // can never overflow the column.
    val imageUrl = text("image_url")
    val description = varchar("description", 200).nullable()
}

/**
 * Table for watched entries (one-to-many relationship with movies)
 * A movie can be watched multiple times with different ratings
 */
object WatchedEntries : IntIdTable("watched_entries") {
    val movieId = reference("movie_id", Movies)
    val watchedDate = date("watched_date")
    val rating = double("rating").nullable() // Rating out of 10
    val notes = text("notes").nullable() // Optional viewing notes
}

/**
 * Physical releases that are wanted but not (yet) owned. Mirrors the
 * release-level fields of [Releases] so an item can be turned into a release
 * without re-typing anything once it arrives. [releaseId] is set at that point
 * and the row survives as the purchase history record.
 */
object WishlistItems : IntIdTable("wishlist_items") {
    val title = varchar("title", 500).nullable()
    val isCollection = bool("is_collection").default(false)
    val distributorId = optReference("distributor_id", Distributors)
    val releaseDate = date("release_date").nullable()
    val blurayComUrl = varchar("bluray_com_url", 500).nullable()
    val status = varchar("status", 20).default("WISHLIST") // WISHLIST, ORDERED, SHIPPED, OWNED
    val priority = varchar("priority", 10).default("MEDIUM") // HIGH, MEDIUM, LOW
    val targetPrice = decimal("target_price", 10, 2).nullable()
    val listPrice = decimal("list_price", 10, 2).nullable() // MSRP as scraped from blu-ray.com
    val asin = varchar("asin", 20).nullable() // Amazon id, for external price tracker links
    val buyUrl = text("buy_url").nullable() // blu-ray.com buy-now click-through
    val inStock = bool("in_stock").nullable() // From the most recent scrape
    val notes = text("notes").nullable()
    val releaseId = optReference("release_id", Releases) // Set once the item is owned
    val lastPriceCheckAt = datetime("last_price_check_at").nullable()
    val statusChangedAt = datetime("status_changed_at").defaultExpression(CurrentDateTime)
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
}

/**
 * Formats of a wishlist item (mirror of [ReleaseMediaTypes]).
 */
object WishlistItemMediaTypes : IntIdTable("wishlist_item_media_types") {
    val itemId = reference("item_id", WishlistItems)
    val mediaType = varchar("media_type", 50)
}

/**
 * Cover art of a wishlist item (mirror of [ReleaseImages]).
 */
object WishlistItemImages : IntIdTable("wishlist_item_images") {
    val itemId = reference("item_id", WishlistItems)
    val imageUrl = text("image_url")
    val description = varchar("description", 200).nullable()
}

/**
 * Films a wishlist item will be linked to once it becomes a release. A box set
 * can name several.
 */
object WishlistItemMovies : IntIdTable("wishlist_item_movies") {
    val itemId = reference("item_id", WishlistItems)
    val movieId = reference("movie_id", Movies)
}

/**
 * Sparse price change log for a wishlist item. A new row is only written when
 * a source reports a different value than its previous observation.
 */
object WishlistPriceHistory : IntIdTable("wishlist_price_history") {
    val itemId = reference("item_id", WishlistItems)
    val priceSource = varchar("source", 20) // BLURAY_LIST, AMAZON, NEW_FROM, USED_FROM, MANUAL
    val vendor = varchar("vendor", 200).nullable()
    val price = decimal("price", 10, 2)
    val inStock = bool("in_stock").nullable()
    val observedAt = datetime("observed_at").defaultExpression(CurrentDateTime)
    val note = text("note").nullable()
}

/**
 * User-defined wishlist tags ("Black Friday", "Criterion sale", ...). Managed
 * like every other category lookup table.
 */
object WishlistTags : IntIdTable("wishlist_tags") {
    val name = varchar("name", 200).uniqueIndex()
}

/**
 * Tags applied to a wishlist item (many-to-many).
 */
object WishlistItemTags : IntIdTable("wishlist_item_tags") {
    val itemId = reference("item_id", WishlistItems)
    val tagId = reference("tag_id", WishlistTags)
}

/**
 * What was paid for a physical unit. Attached to the wishlist item while the
 * order is in flight and to the release once it is owned; a release that was
 * never wishlisted has a purchase with only [releaseId] set.
 */
object Purchases : IntIdTable("purchases") {
    val wishlistItemId = optReference("wishlist_item_id", WishlistItems)
    val releaseId = optReference("release_id", Releases).uniqueIndex()
    val vendor = varchar("vendor", 200).nullable()
    val orderDate = date("order_date").nullable()
    val orderNumber = varchar("order_number", 100).nullable()
    val trackingUrl = text("tracking_url").nullable()
    val subtotal = decimal("subtotal", 10, 2)
    val taxRate = decimal("tax_rate", 6, 4) // e.g. 0.0600 for Iowa
    val taxAmount = decimal("tax_amount", 10, 2)
    val shipping = decimal("shipping", 10, 2)
    val shippedDate = date("shipped_date").nullable()
    val receivedDate = date("received_date").nullable()
    val notes = text("notes").nullable()
    val createdAt = datetime("created_at").defaultExpression(CurrentDateTime)
}
