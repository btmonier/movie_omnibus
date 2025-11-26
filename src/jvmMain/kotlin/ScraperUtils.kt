package org.btmonier

import org.jsoup.nodes.Document

/**
 * Utility functions for scraping Letterboxd pages.
 */
object ScraperUtils {
    /**
     * Scrapes elements with class "text-slug" that have href attributes containing the given pattern.
     *
     * @param doc The JSoup document to scrape
     * @param pattern The pattern to match in href attributes (e.g., "/genre/", "/theme/")
     * @return List of unique text content from matching elements
     */
    fun scrapeByHref(doc: Document, pattern: String): List<String> {
        return doc.select(".text-slug")
            .filter { it.attr("href").contains(pattern) }
            .filter { !it.text().contains("Show All", ignoreCase = true) }
            .map { it.text() }
            .distinct()
    }

    /**
     * Extracts the movie title from a Letterboxd movie page.
     *
     * @param doc The JSoup document of a Letterboxd movie page
     * @return The movie title, or empty string if not found
     */
    fun extractTitle(doc: Document): String {
        // Try the primary Letterboxd movie title selector first
        // The main title is in h1 with classes "headline-1" and "primaryname"
        doc.select("h1.headline-1.primaryname").firstOrNull()?.text()?.takeIf { it.isNotBlank() }
            ?.let { return it.trim() }

        // Alternative: try just the filmtitle class
        doc.select("h1.filmtitle span.name").firstOrNull()?.text()?.takeIf { it.isNotBlank() }
            ?.let { return it.trim() }

        // Try og:title meta tag (usually contains "Movie Name (Year)")
        doc.select("meta[property=og:title]").attr("content").takeIf { it.isNotBlank() }
            ?.let { title ->
                // Remove the year suffix if present, e.g., "The Godfather (1972)" -> "The Godfather"
                val cleanTitle = title.replace(Regex("""\s*\(\d{4}\)\s*$"""), "").trim()
                if (cleanTitle.isNotBlank()) return cleanTitle
            }

        // Fall back to title tag and remove Letterboxd suffix
        val titleText = doc.select("title").text()
        val cleanTitle = titleText
            .substringBefore(" • Letterboxd")
            .substringBefore(" - Letterboxd")
            .replace(Regex("""\s*\(\d{4}\)\s*$"""), "") // Remove year if present
            .trim()

        return cleanTitle
    }

    /**
     * Extracts cast members from a Letterboxd movie page.
     *
     * @param doc The JSoup document of a Letterboxd movie page
     * @return List of cast member names
     */
    fun extractCast(doc: Document): List<String> {
        return doc.select("#tab-cast .cast-list a.text-slug")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    /**
     * Extracts crew members organized by role from a Letterboxd movie page.
     *
     * @param doc The JSoup document of a Letterboxd movie page
     * @return Map of role names to lists of crew member names
     */
    fun extractCrew(doc: Document): Map<String, List<String>> {
        val crewMap = mutableMapOf<String, List<String>>()
        val crewTab = doc.select("#tab-crew").firstOrNull() ?: return emptyMap()

        val headers = crewTab.select("h3")
        for (header in headers) {
            val role = header.select("span.crewrole.-full").text().trim()
            if (role.isNotBlank()) {
                val crewMembers = header.nextElementSibling()
                    ?.select("a.text-slug")
                    ?.map { it.text().trim() }
                    ?.filter { it.isNotBlank() }
                    ?: emptyList()

                if (crewMembers.isNotEmpty()) {
                    crewMap[role] = crewMembers
                }
            }
        }

        return crewMap
    }

    /**
     * Extracts the release date from a Letterboxd movie page.
     *
     * @param doc The JSoup document of a Letterboxd movie page
     * @return The release year as an integer, or null if not found
     */
    fun extractReleaseDate(doc: Document): Int? {
        val yearText = doc.select("span.releasedate a")
            .firstOrNull()
            ?.text()
            ?.trim()
            ?: return null

        return yearText.toIntOrNull()
    }

    /**
     * Extracts the runtime in minutes from a Letterboxd movie page.
     *
     * @param doc The JSoup document of a Letterboxd movie page
     * @return The runtime in minutes, or null if not found
     */
    fun extractRuntime(doc: Document): Int? {
        val text = doc.select("p.text-link.text-footer")
            .firstOrNull()
            ?.text()
            ?: return null

        // Extract number before "mins"
        val regex = """(\d+)\s*mins""".toRegex()
        val match = regex.find(text)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Extracts the movie description/synopsis from a Letterboxd movie page.
     *
     * @param doc The JSoup document of a Letterboxd movie page
     * @return The movie description, or null if not found
     */
    fun extractDescription(doc: Document): String? {
        // Look for the synopsis in the review body with class "review body-text -prose -hero prettify"
        val descriptionElement = doc.select("div.review.body-text.-prose.-hero.prettify div.truncate p")
            .firstOrNull()
            ?: return null

        val description = descriptionElement.text().trim()
        return if (description.isNotBlank()) description else null
    }

    /**
     * Extracts alternate titles from a Letterboxd movie page.
     *
     * @param doc The JSoup document of a Letterboxd movie page
     * @return List of alternate titles
     */
    fun extractAlternateTitles(doc: Document): List<String> {
        // Look for alternate titles in div.text-indentedlist
        // The alternate titles are usually in a section with h3 containing "Alternative Title"
        val alternateTitlesSection = doc.select("div.text-indentedlist")
            .firstOrNull { section ->
                // Check if this section is for alternate titles by looking for nearby h3
                val prevH3 = section.previousElementSibling()
                prevH3 != null && prevH3.tagName() == "h3" &&
                prevH3.text().contains("Alternative Title", ignoreCase = true)
            }

        // Get the text content and split by commas
        val titlesText = alternateTitlesSection?.text()?.trim() ?: return emptyList()

        return titlesText
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    /**
     * Extracts all metadata (genres, themes, country) from a Letterboxd movie page.
     * Uses provided title from CSV instead of extracting from page.
     *
     * @param doc The JSoup document of a Letterboxd movie page
     * @param url The URL of the movie
     * @param title The movie title from CSV
     * @return MovieMetadata object with extracted information
     */
    fun extractMetadata(doc: Document, url: String, title: String): MovieMetadata {
        // Extract both regular themes (/theme/) and mini-themes (/mini-theme/)
        val themes = (scrapeByHref(doc, "/theme/") + scrapeByHref(doc, "/mini-theme/")).distinct()

        return MovieMetadata(
            url = url,
            title = title,
            description = extractDescription(doc),
            alternateTitles = extractAlternateTitles(doc),
            genres = scrapeByHref(doc, "/genre/"),
            themes = themes,
            country = scrapeByHref(doc, "/films/country/"),
            cast = extractCast(doc),
            crew = extractCrew(doc),
            release_date = extractReleaseDate(doc),
            runtime_mins = extractRuntime(doc)
        )
    }

    /**
     * Extracts all metadata (title, genres, themes, country) from a Letterboxd movie page.
     * This version extracts the title from the page HTML.
     *
     * @param doc The JSoup document of a Letterboxd movie page
     * @param url The URL of the movie
     * @return MovieMetadata object with extracted information
     */
    fun extractMetadataWithTitle(doc: Document, url: String): MovieMetadata {
        // Extract both regular themes (/theme/) and mini-themes (/mini-theme/)
        val themes = (scrapeByHref(doc, "/theme/") + scrapeByHref(doc, "/mini-theme/")).distinct()

        return MovieMetadata(
            url = url,
            title = extractTitle(doc),
            description = extractDescription(doc),
            alternateTitles = extractAlternateTitles(doc),
            genres = scrapeByHref(doc, "/genre/"),
            themes = themes,
            country = scrapeByHref(doc, "/films/country/"),
            cast = extractCast(doc),
            crew = extractCrew(doc),
            release_date = extractReleaseDate(doc),
            runtime_mins = extractRuntime(doc)
        )
    }
}
