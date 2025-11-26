package org.btmonier

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MovieMetadataTest {

    private val json = Json { prettyPrint = true }

    @Test
    fun `MovieMetadata serializes to JSON correctly`() {
        val metadata = MovieMetadata(
            url = "https://letterboxd.com/film/the-shawshank-redemption/",
            title = "The Shawshank Redemption",
            genres = listOf("Drama", "Crime"),
            themes = listOf("Prison", "Friendship"),
            country = listOf("United States")
        )

        val jsonString = json.encodeToString(metadata)

        assertTrue(jsonString.contains("\"url\""))
        assertTrue(jsonString.contains("\"title\""))
        assertTrue(jsonString.contains("The Shawshank Redemption"))
        assertTrue(jsonString.contains("\"genres\""))
        assertTrue(jsonString.contains("\"themes\""))
        assertTrue(jsonString.contains("\"country\""))
        assertTrue(jsonString.contains("Drama"))
        assertTrue(jsonString.contains("Prison"))
        assertTrue(jsonString.contains("United States"))
    }

    @Test
    fun `MovieMetadata deserializes from JSON correctly`() {
        val jsonString = """
            {
                "url": "https://letterboxd.com/film/the-shawshank-redemption/",
                "title": "The Shawshank Redemption",
                "genres": ["Drama", "Crime"],
                "themes": ["Prison", "Friendship"],
                "country": ["United States"]
            }
        """.trimIndent()

        val metadata = Json.decodeFromString<MovieMetadata>(jsonString)

        assertEquals("https://letterboxd.com/film/the-shawshank-redemption/", metadata.url)
        assertEquals("The Shawshank Redemption", metadata.title)
        assertEquals(listOf("Drama", "Crime"), metadata.genres)
        assertEquals(listOf("Prison", "Friendship"), metadata.themes)
        assertEquals(listOf("United States"), metadata.country)
    }

    @Test
    fun `MovieMetadata with empty lists serializes and deserializes correctly`() {
        val metadata = MovieMetadata(
            url = "https://letterboxd.com/film/test-movie/",
            title = "Test Movie"
        )

        val jsonString = json.encodeToString(metadata)
        val decoded = Json.decodeFromString<MovieMetadata>(jsonString)

        // Verify the round-trip maintains data integrity
        assertEquals(metadata.url, decoded.url)
        assertEquals(metadata.title, decoded.title)
        assertEquals(metadata.genres, decoded.genres)
        assertEquals(metadata.themes, decoded.themes)
        assertEquals(metadata.country, decoded.country)
        assertTrue(decoded.genres.isEmpty())
        assertTrue(decoded.themes.isEmpty())
        assertTrue(decoded.country.isEmpty())
    }

    @Test
    fun `List of MovieMetadata serializes to JSON array`() {
        val metadataList = listOf(
            MovieMetadata(
                url = "https://letterboxd.com/film/movie-1/",
                title = "Movie One",
                genres = listOf("Drama")
            ),
            MovieMetadata(
                url = "https://letterboxd.com/film/movie-2/",
                title = "Movie Two",
                genres = listOf("Comedy")
            )
        )

        val jsonString = json.encodeToString(metadataList)

        assertTrue(jsonString.startsWith("["))
        assertTrue(jsonString.endsWith("]"))
        assertTrue(jsonString.contains("movie-1"))
        assertTrue(jsonString.contains("movie-2"))
        assertTrue(jsonString.contains("Movie One"))
        assertTrue(jsonString.contains("Movie Two"))
        assertTrue(jsonString.contains("Drama"))
        assertTrue(jsonString.contains("Comedy"))
    }

    @Test
    fun `MovieMetadata handles special characters in text`() {
        val metadata = MovieMetadata(
            url = "https://letterboxd.com/film/amélie/",
            title = "Amélie",
            genres = listOf("Drama", "Romance"),
            themes = listOf("Love & Relationships"),
            country = listOf("France")
        )

        val jsonString = json.encodeToString(metadata)
        val decoded = Json.decodeFromString<MovieMetadata>(jsonString)

        assertEquals(metadata.url, decoded.url)
        assertEquals(metadata.title, decoded.title)
        assertEquals(metadata.genres, decoded.genres)
        assertEquals(metadata.themes, decoded.themes)
        assertEquals(metadata.country, decoded.country)
    }
}
