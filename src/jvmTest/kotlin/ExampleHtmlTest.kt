package org.btmonier

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExampleHtmlTest {

    @Test
    fun `extract metadata from real example HTML`() {
        // Load the actual example HTML file
        val htmlContent = this::class.java.getResource("/exp/example.html")?.readText()
            ?: error("Could not load example.html")

        val doc = Jsoup.parse(htmlContent)
        val metadata = ScraperUtils.extractMetadataWithTitle(doc, "https://letterboxd.com/film/prisoners-of-the-lost-universe/")

        // Verify basic metadata
        assertEquals("https://letterboxd.com/film/prisoners-of-the-lost-universe/", metadata.url)
        assertTrue(metadata.title.isNotEmpty(), "Title should be extracted")

        // Verify release date
        assertEquals(1983, metadata.release_date)

        // Verify runtime
        assertEquals(90, metadata.runtime_mins)

        // Verify cast
        assertTrue(metadata.cast.isNotEmpty(), "Cast should be extracted")
        assertTrue(metadata.cast.contains("Richard Hatch"), "Should contain Richard Hatch")
        assertTrue(metadata.cast.contains("Kay Lenz"), "Should contain Kay Lenz")
        assertTrue(metadata.cast.contains("John Saxon"), "Should contain John Saxon")

        // Verify crew
        assertTrue(metadata.crew.isNotEmpty(), "Crew should be extracted")
        assertTrue(metadata.crew.containsKey("Director"), "Should have Director role")
        assertTrue(metadata.crew["Director"]?.contains("Terry Marcel") == true, "Director should be Terry Marcel")

        assertTrue(metadata.crew.containsKey("Writers"), "Should have Writers role")
        assertTrue(metadata.crew["Writers"]?.contains("Harry Robertson") == true)
        assertTrue(metadata.crew["Writers"]?.contains("Terry Marcel") == true)

        // Verify genres
        assertTrue(metadata.genres.contains("Adventure"))
        assertTrue(metadata.genres.contains("Science Fiction"))
        assertTrue(metadata.genres.contains("Action"))

        // Verify country
        assertTrue(metadata.country.contains("UK"))

        // Print summary for verification
        println("=== Extraction Results ===")
        println("Title: ${metadata.title}")
        println("Release Date: ${metadata.release_date}")
        println("Runtime: ${metadata.runtime_mins} mins")
        println("Cast (${metadata.cast.size}): ${metadata.cast.take(5)}")
        println("Crew roles: ${metadata.crew.keys}")
        println("Genres: ${metadata.genres}")
        println("Country: ${metadata.country}")
    }
}
