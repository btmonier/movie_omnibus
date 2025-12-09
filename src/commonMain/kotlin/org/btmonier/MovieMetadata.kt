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
 * Data class for physical media entries
 */
@Serializable
data class PhysicalMedia(
    val mediaTypes: List<MediaType>,  // Can contain multiple types (e.g., Blu-ray + DVD combo)
    val entryLetter: String? = null,  // A-Z letter identifier for the entry
    val title: String? = null,  // Optional title (useful for box sets)
    val distributor: String? = null,
    val releaseDate: String? = null,  // ISO date string (YYYY-MM-DD)
    val blurayComUrl: String? = null,
    val location: String? = null,  // Archive or Shelf
    val images: List<PhysicalMediaImage> = emptyList(),
    val id: Int? = null,  // Database ID
    val createdAt: String? = null  // ISO datetime string, auto-set on insert
)

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
