package org.btmonier

import org.jsoup.Jsoup

/**
 * Simple test utility to debug alternate titles extraction from a Letterboxd URL.
 * Usage: Update the URL below and run this file to see what's being extracted.
 */
fun main() {
    // Example URL - replace with your own
    val url = "https://letterboxd.com/film/the-lord-of-the-rings-the-fellowship-of-the-ring/"

    println("Fetching: $url")
    val doc = Jsoup.connect(url)
        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
        .timeout(10000)
        .get()

    // Test description extraction
    println("\n=== DESCRIPTION ===")
    val description = ScraperUtils.extractDescription(doc)
    if (description != null) {
        println("Found: $description")
    } else {
        println("No description found")
        // Debug: Show what's in the description area
        val descArea = doc.select("div.review.body-text.-prose.-hero.prettify")
        println("Review sections found: ${descArea.size}")
        descArea.forEachIndexed { i, elem ->
            println("Section $i: ${elem.text().take(100)}")
        }
    }

    // Test alternate titles extraction
    println("\n=== ALTERNATE TITLES ===")
    val alternateTitles = ScraperUtils.extractAlternateTitles(doc)
    if (alternateTitles.isNotEmpty()) {
        println("Found ${alternateTitles.size} alternate titles:")
        alternateTitles.forEach { println("  - $it") }
    } else {
        println("No alternate titles found")

        // Debug: Show all div.text-indentedlist sections
        val indentedLists = doc.select("div.text-indentedlist")
        println("\nFound ${indentedLists.size} indented list sections:")
        indentedLists.forEachIndexed { i, list ->
            val prevH3 = list.previousElementSibling()
            println("\nSection $i:")
            println("  Previous sibling: ${prevH3?.tagName()} - ${prevH3?.text()}")
            println("  Content: ${list.text().take(200)}")
            val spans = list.select("span")
            println("  Spans found: ${spans.size}")
            spans.forEach { span ->
                println("    - ${span.text()}")
            }
        }
    }

    // Test full metadata extraction
    println("\n=== FULL METADATA ===")
    val metadata = ScraperUtils.extractMetadata(doc, url, "Test Movie")
    println("Title: ${metadata.title}")
    println("Description: ${metadata.description?.take(100) ?: "None"}")
    println("Alternate Titles: ${metadata.alternateTitles}")
    println("Genres: ${metadata.genres}")
    println("Themes: ${metadata.themes.take(5)}")
    println("Countries: ${metadata.country}")
}
