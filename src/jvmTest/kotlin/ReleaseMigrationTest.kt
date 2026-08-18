package org.btmonier

import org.btmonier.database.LegacyPhysicalMediaRow
import org.btmonier.database.ReleaseMigration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ReleaseMigrationTest {

    private fun row(
        id: Int,
        movieId: Int = id,
        title: String? = null,
        blurayComUrl: String? = null,
        distributorId: Int? = null,
        releaseDate: String? = null,
        mediaTypes: List<String> = emptyList()
    ) = LegacyPhysicalMediaRow(
        id = id,
        movieId = movieId,
        title = title,
        blurayComUrl = blurayComUrl,
        distributorId = distributorId,
        releaseDate = releaseDate,
        mediaTypes = mediaTypes
    )

    @Test
    fun `rows sharing a blu-ray com release are one group`() {
        val a = row(1, blurayComUrl = "https://www.blu-ray.com/dvd/Drive-In-Cult-Classics-DVD/66826/")
        val b = row(2, blurayComUrl = "http://blu-ray.com/dvd/Drive-In-Cult-Classics-DVD/66826")
        val c = row(3, blurayComUrl = "https://www.blu-ray.com/dvd/Different-Slug-DVD/66826/#Packaging")

        assertEquals(ReleaseMigration.groupKey(a), ReleaseMigration.groupKey(b))
        assertEquals(ReleaseMigration.groupKey(a), ReleaseMigration.groupKey(c))
    }

    @Test
    fun `the movies and dvd sections stay apart even on a shared id`() {
        val movies = row(1, blurayComUrl = "https://www.blu-ray.com/movies/Some-Title/66826/")
        val dvd = row(2, blurayComUrl = "https://www.blu-ray.com/dvd/Some-Title-DVD/66826/")

        assertNotEquals(ReleaseMigration.groupKey(movies), ReleaseMigration.groupKey(dvd))
    }

    @Test
    fun `a url always wins over the title`() {
        val withUrl = row(1, title = "Box Set", blurayComUrl = "https://www.blu-ray.com/movies/Box-Set/123/")
        val withoutUrl = row(2, title = "Box Set")

        assertNotEquals(ReleaseMigration.groupKey(withUrl), ReleaseMigration.groupKey(withoutUrl))
    }

    @Test
    fun `an unrecognized url still groups after normalization`() {
        val a = row(1, blurayComUrl = "https://example.com/Release/  ".trim())
        val b = row(2, blurayComUrl = "HTTPS://EXAMPLE.COM/Release?ref=x")

        assertEquals(ReleaseMigration.groupKey(a), ReleaseMigration.groupKey(b))
    }

    @Test
    fun `titles group when distributor date and formats agree`() {
        val a = row(1, title = " Drive-In Cult Classics ", distributorId = 7, releaseDate = "2007-05-01", mediaTypes = listOf("DVD"))
        val b = row(2, title = "drive-in cult classics", distributorId = 7, releaseDate = "2007-05-01", mediaTypes = listOf("DVD"))

        assertEquals(ReleaseMigration.groupKey(a), ReleaseMigration.groupKey(b))
    }

    @Test
    fun `format order does not affect the title key`() {
        val a = row(1, title = "Combo", mediaTypes = listOf("Blu-ray", "DVD"))
        val b = row(2, title = "Combo", mediaTypes = listOf("DVD", "Blu-ray"))

        assertEquals(ReleaseMigration.groupKey(a), ReleaseMigration.groupKey(b))
    }

    @Test
    fun `titles with different distributors stay apart`() {
        val a = row(1, title = "Nosferatu", distributorId = 1)
        val b = row(2, title = "Nosferatu", distributorId = 2)

        assertNotEquals(ReleaseMigration.groupKey(a), ReleaseMigration.groupKey(b))
    }

    @Test
    fun `rows with neither a url nor a title are never merged`() {
        val a = row(1, distributorId = 3, releaseDate = "2010-01-01", mediaTypes = listOf("DVD"))
        val b = row(2, distributorId = 3, releaseDate = "2010-01-01", mediaTypes = listOf("DVD"))
        val blank = row(3, title = "   ", distributorId = 3)

        assertNotEquals(ReleaseMigration.groupKey(a), ReleaseMigration.groupKey(b))
        assertNotEquals(ReleaseMigration.groupKey(a), ReleaseMigration.groupKey(blank))
        assertEquals(3, ReleaseMigration.planGroups(listOf(a, b, blank)).size)
    }

    @Test
    fun `planGroups collapses a box set into a single release`() {
        val url = "https://www.blu-ray.com/dvd/Drive-In-Cult-Classics-DVD/66826/"
        val boxSet = (1..200).map { row(it, blurayComUrl = url) }
        val standalone = row(500, title = "Standalone", distributorId = 9)

        val groups = ReleaseMigration.planGroups(boxSet + standalone)

        assertEquals(2, groups.size)
        assertEquals(200, groups.first { it.size > 1 }.size)
    }
}
