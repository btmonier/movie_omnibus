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
