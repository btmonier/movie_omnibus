package org.btmonier

import org.jsoup.Jsoup
import java.io.File

/**
 * Test utility to verify extraction from the example.html file
 */
fun main() {
    val htmlFile = File("src/jvmMain/resources/exp/example.html")

    if (!htmlFile.exists()) {
        println("Error: example.html not found at ${htmlFile.absolutePath}")
        return
    }

    println("Parsing: ${htmlFile.absolutePath}")
    val doc = Jsoup.parse(htmlFile, "UTF-8")

    // Test alternate titles extraction
    println("\n=== ALTERNATE TITLES ===")
    val alternateTitles = ScraperUtils.extractAlternateTitles(doc)
    if (alternateTitles.isNotEmpty()) {
        println("✅ Found ${alternateTitles.size} alternate titles:")
        alternateTitles.forEachIndexed { i, title ->
            println("  ${i + 1}. $title")
        }
    } else {
        println("❌ No alternate titles found")
    }

    // Test description extraction
    println("\n=== DESCRIPTION ===")
    val description = ScraperUtils.extractDescription(doc)
    if (description != null) {
        println("✅ Found description:")
        println("  ${description.take(200)}...")
    } else {
        println("❌ No description found")
    }

    // Test title extraction
    println("\n=== TITLE ===")
    val title = ScraperUtils.extractTitle(doc)
    println("Title: $title")

    // Test genres
    println("\n=== GENRES ===")
    val genres = ScraperUtils.scrapeByHref(doc, "/genre/")
    println("Genres: $genres")

    // Test countries
    println("\n=== COUNTRIES ===")
    val countries = ScraperUtils.scrapeByHref(doc, "/films/country/")
    println("Countries: $countries")
}
