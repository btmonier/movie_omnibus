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
    val alternateTitle: String? = null,  // Title this film carries on this specific release
    val distributor: String? = null,
    val releaseDate: String? = null,  // ISO date string (YYYY-MM-DD)
    val blurayComUrl: String? = null,
    val location: String? = null,  // Archive or Shelf
    val images: List<PhysicalMediaImage> = emptyList(),
    val id: Int? = null,  // Database ID
    val createdAt: String? = null  // ISO datetime string, auto-set on insert
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
