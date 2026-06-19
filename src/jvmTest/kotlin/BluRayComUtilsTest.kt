package org.btmonier

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BluRayComUtilsTest {

    @Test
    fun `isBluRayComUrl accepts valid release URLs`() {
        assertTrue(BluRayComUtils.isBluRayComUrl("https://www.blu-ray.com/movies/Invaders-from-Mars-4K-Blu-ray/336476/"))
        assertTrue(BluRayComUtils.isBluRayComUrl("http://blu-ray.com/movies/Some-Title/12345"))
        assertTrue(BluRayComUtils.isBluRayComUrl("https://www.blu-ray.com/movies/Title/999/#Packaging"))
    }

    @Test
    fun `isBluRayComUrl accepts DVD release URLs`() {
        assertTrue(BluRayComUtils.isBluRayComUrl("https://www.blu-ray.com/dvd/1-Ichi-DVD/91279/"))
        assertTrue(BluRayComUtils.isBluRayComUrl("http://blu-ray.com/dvd/Some-Title-DVD/12345"))
        assertTrue(BluRayComUtils.isBluRayComUrl("https://www.blu-ray.com/dvd/Title-DVD/999/#Packaging"))
    }

    @Test
    fun `isBluRayComUrl rejects invalid URLs`() {
        assertFalse(BluRayComUtils.isBluRayComUrl(""))
        assertFalse(BluRayComUtils.isBluRayComUrl("https://letterboxd.com/film/the-godfather/"))
        assertFalse(BluRayComUtils.isBluRayComUrl("https://www.blu-ray.com/movies/"))
        assertFalse(BluRayComUtils.isBluRayComUrl("https://www.blu-ray.com/dvd/"))
        assertFalse(BluRayComUtils.isBluRayComUrl("https://www.blu-ray.com/deals/"))
    }

    @Test
    fun `extractTitle reads og title`() {
        val html = """
            <html><head>
                <meta property="og:title" content="Invaders from Mars 4K Blu-ray (Standard Edition)" />
            </head><body></body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html)
        assertEquals("Invaders from Mars 4K Blu-ray (Standard Edition)", BluRayComUtils.extractTitle(doc))
    }

    @Test
    fun `extractDistributor reads studio link`() {
        val html = """
            <html><body>
                <span class="subheading grey">
                <a class="grey" href="https://www.blu-ray.com/movies/movies.php?studioid=5359">Ignite Films</a> | 1953
                </span>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html)
        assertEquals("Ignite Films", BluRayComUtils.extractDistributor(doc))
    }

    @Test
    fun `extractReleaseDate parses to ISO`() {
        val html = """
            <html><body>
                <span class="subheading grey">
                <a class="grey noline" href="https://www.blu-ray.com/movies/releasedates.php?year=2023&month=7#July11">Jul 11, 2023</a>
                </span>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html)
        assertEquals("2023-07-11", BluRayComUtils.extractReleaseDate(doc))
    }

    @Test
    fun `parseHumanDate handles abbreviated and full month names`() {
        assertEquals("2023-07-11", BluRayComUtils.parseHumanDate("Jul 11, 2023"))
        assertEquals("2023-07-11", BluRayComUtils.parseHumanDate("July 11, 2023"))
        assertEquals("1999-01-05", BluRayComUtils.parseHumanDate("January 5, 1999"))
        assertEquals(null, BluRayComUtils.parseHumanDate("no date here"))
    }

    @Test
    fun `extractImages reads og image as front cover`() {
        val html = """
            <html><head>
                <meta property="og:image" content="https://images.static-bluray.com/movies/covers/336476_large.jpg" />
            </head><body></body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html)
        val images = BluRayComUtils.extractImages(doc)
        assertEquals(1, images.size)
        assertEquals("https://images.static-bluray.com/movies/covers/336476_large.jpg", images[0].imageUrl)
        assertEquals("Front Cover", images[0].description)
    }

    @Test
    fun `extractMediaTypes detects 4K only without false Blu-ray for a 4K disc`() {
        val html = """
            <html><body>
                <span class="subheadingtitle">Standard Edition / 4K Ultra HD</span>
                <span class="subheading">Discs</span>4K Ultra HD<br>Blu-ray Disc<br>Single disc (1 BD-100)<br>
                <span class="subheading">Packaging</span>Booklet<br>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html)
        val types = BluRayComUtils.extractMediaTypes(doc)
        assertTrue(types.contains(MediaType.FOURK), "Should detect 4K")
        assertFalse(types.contains(MediaType.BLURAY), "Should not falsely detect a 1080p Blu-ray for a 4K disc")
    }

    @Test
    fun `extractMediaTypes detects standalone Blu-ray`() {
        val html = """
            <html><body>
                <span class="subheadingtitle">Standard Edition / Blu-ray</span>
                <span class="subheading">Discs</span>Blu-ray<br>Single disc (1 BD-50)<br>
                <span class="subheading">Packaging</span>Keep Case<br>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html)
        val types = BluRayComUtils.extractMediaTypes(doc)
        assertTrue(types.contains(MediaType.BLURAY))
        assertFalse(types.contains(MediaType.FOURK))
    }

    @Test
    fun `extractMediaTypes detects 4K and Blu-ray combo from headline format`() {
        // Real blu-ray.com combos join formats with "+" in the subheading title.
        val html = """
            <html><body>
                <span class="subheadingtitle">Collector's Edition / 4K Ultra HD + Blu-ray</span>
                <span class="subheading">Discs</span>4K Ultra HD<br>Blu-ray Disc<br>Two-disc set (1 BD-100, 1 BD-50)<br>
                <span class="subheading">Packaging</span>Slipcover<br>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html)
        val types = BluRayComUtils.extractMediaTypes(doc)
        assertTrue(types.contains(MediaType.FOURK))
        assertTrue(types.contains(MediaType.BLURAY))
    }

    @Test
    fun `extract physical media from real example HTML`() {
        val htmlContent = this::class.java.getResource("/bluray_invaders.html")?.readText()
            ?: error("Could not load bluray_invaders.html")

        val url = "https://www.blu-ray.com/movies/Invaders-from-Mars-4K-Blu-ray/336476/"
        val doc = Jsoup.parse(htmlContent)
        val media = BluRayComUtils.extractPhysicalMedia(doc, url)

        assertEquals(url, media.blurayComUrl)
        assertEquals("Invaders from Mars 4K Blu-ray (Standard Edition)", media.title)
        assertEquals("Ignite Films", media.distributor)
        assertEquals("2023-07-11", media.releaseDate)
        assertTrue(media.mediaTypes.contains(MediaType.FOURK), "Should detect 4K")
        assertFalse(media.mediaTypes.contains(MediaType.BLURAY), "Should not falsely detect 1080p Blu-ray")
        assertEquals(1, media.images.size)
        assertTrue(media.images[0].imageUrl.contains("336476"), "Front cover should reference the release id")

        println("=== Blu-ray.com Extraction ===")
        println("Title: ${media.title}")
        println("Distributor: ${media.distributor}")
        println("Release date: ${media.releaseDate}")
        println("Media types: ${media.mediaTypes}")
        println("Images: ${media.images}")
    }

    @Test
    fun `bluRayCoverImageUrl derives cover from release URL`() {
        assertEquals(
            "https://images.static-bluray.com/movies/covers/337326_large.jpg",
            bluRayCoverImageUrl("https://www.blu-ray.com/movies/Blood-Money-4-Classic-Westerns-Blu-ray/337326/")
        )
        assertEquals(
            "https://images.static-bluray.com/movies/covers/336476_large.jpg",
            bluRayCoverImageUrl("https://www.blu-ray.com/movies/Invaders-from-Mars-4K-Blu-ray/336476/")
        )
        assertEquals(null, bluRayCoverImageUrl(null))
        assertEquals(null, bluRayCoverImageUrl(""))
        assertEquals(null, bluRayCoverImageUrl("https://letterboxd.com/film/the-godfather/"))
    }

    @Test
    fun `bluRayCoverImageUrl derives DVD cover from dvd release URL`() {
        // DVD covers are served from the "dvdcovers" directory, not "covers".
        assertEquals(
            "https://images.static-bluray.com/movies/dvdcovers/91279_large.jpg",
            bluRayCoverImageUrl("https://www.blu-ray.com/dvd/1-Ichi-DVD/91279/")
        )
    }

    @Test
    fun `displayImages falls back to derived cover when none stored`() {
        val withUrl = PhysicalMedia(
            mediaTypes = listOf(MediaType.BLURAY),
            blurayComUrl = "https://www.blu-ray.com/movies/Blood-Money-4-Classic-Westerns-Blu-ray/337326/"
        )
        val derived = withUrl.displayImages()
        assertEquals(1, derived.size)
        assertEquals("https://images.static-bluray.com/movies/covers/337326_large.jpg", derived[0].imageUrl)

        // Stored images take precedence over the derived cover.
        val stored = withUrl.copy(images = listOf(PhysicalMediaImage("https://example.com/a.jpg", "Front")))
        assertEquals(listOf(PhysicalMediaImage("https://example.com/a.jpg", "Front")), stored.displayImages())

        // No URL and no images -> empty.
        assertTrue(PhysicalMedia(mediaTypes = listOf(MediaType.DVD)).displayImages().isEmpty())
    }

    @Test
    fun `extract physical media from real DVD example HTML`() {
        val htmlContent = this::class.java.getResource("/bluray_dvd_ichi.html")?.readText()
            ?: error("Could not load bluray_dvd_ichi.html")

        val url = "https://www.blu-ray.com/dvd/1-Ichi-DVD/91279/"
        val doc = Jsoup.parse(htmlContent)
        val media = BluRayComUtils.extractPhysicalMedia(doc, url)

        assertEquals(url, media.blurayComUrl)
        assertEquals("1-Ichi DVD (Special Edition)", media.title)
        assertEquals("Unearthed Films", media.distributor)
        assertEquals("2007-10-30", media.releaseDate)
        assertTrue(media.mediaTypes.contains(MediaType.DVD), "Should detect DVD")
        assertFalse(media.mediaTypes.contains(MediaType.BLURAY), "Should not falsely detect Blu-ray for a DVD")
        assertEquals(1, media.images.size)
        assertTrue(media.images[0].imageUrl.contains("dvdcovers/91279"), "Front cover should reference the DVD release id")
    }

    @Test
    fun `extract physical media from real combo example HTML`() {
        val htmlContent = this::class.java.getResource("/bluray_burbs.html")?.readText()
            ?: error("Could not load bluray_burbs.html")

        val url = "https://www.blu-ray.com/movies/The-Burbs-4K-Blu-ray/409687/"
        val doc = Jsoup.parse(htmlContent)
        val media = BluRayComUtils.extractPhysicalMedia(doc, url)

        assertEquals("Shout Factory", media.distributor)
        assertEquals("2026-06-09", media.releaseDate)
        assertTrue(media.mediaTypes.contains(MediaType.FOURK), "Should detect 4K")
        assertTrue(media.mediaTypes.contains(MediaType.BLURAY), "Combo should also detect Blu-ray")
    }
}
