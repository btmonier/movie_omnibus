package org.btmonier.database

import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.javatime.date

/**
 * Database table schema for movie metadata.
 */
object Movies : IntIdTable("movies") {
    val url = varchar("url", 500).uniqueIndex()
    val title = varchar("title", 500)
    val description = text("description").nullable() // Movie synopsis/description
    val releaseDate = date("release_date").nullable()
    val runtimeMins = integer("runtime_mins").nullable()
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
 * Global distributors table - master list of all physical media distributors
 */
object Distributors : IntIdTable("distributors") {
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
 * Table for movie themes (many-to-many relationship)
 */
object MovieThemes : IntIdTable("movie_themes") {
    val movieId = reference("movie_id", Movies)
    val theme = varchar("theme", 200)
}

/**
 * Table for movie countries (many-to-many relationship)
 */
object MovieCountries : IntIdTable("movie_countries") {
    val movieId = reference("movie_id", Movies)
    val country = varchar("country", 200)
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
 * Table for physical media entries (one-to-many relationship)
 */
object PhysicalMedia : IntIdTable("physical_media") {
    val movieId = reference("movie_id", Movies)
    val entryLetter = varchar("entry_letter", 1).nullable() // A-Z identifier
    val title = varchar("title", 500).nullable() // Optional title (useful for box sets)
    val distributor = varchar("distributor", 200).nullable()
    val releaseDate = date("release_date").nullable()
    val blurayComUrl = varchar("bluray_com_url", 500).nullable()
    val location = varchar("location", 50).nullable() // Archive, Shelf
}

/**
 * Table for physical media types (many-to-many relationship with physical_media)
 * A single physical media entry can have multiple types (e.g., Blu-ray + DVD combo)
 */
object PhysicalMediaTypes : IntIdTable("physical_media_types") {
    val physicalMediaId = reference("physical_media_id", PhysicalMedia)
    val mediaType = varchar("media_type", 50) // VHS, DVD, Blu-ray, 4K
}

/**
 * Table for physical media images (one-to-many relationship with physical_media)
 */
object PhysicalMediaImages : IntIdTable("physical_media_images") {
    val physicalMediaId = reference("physical_media_id", PhysicalMedia)
    val imageUrl = varchar("image_url", 500)
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
