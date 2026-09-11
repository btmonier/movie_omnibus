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
    fun `cleanReleaseTitle cuts the title at the first format keyword`() {
        assertEquals("Bad Ronald", BluRayComUtils.cleanReleaseTitle("Bad Ronald Blu-ray (Warner Archive)"))
        assertEquals("Invaders from Mars", BluRayComUtils.cleanReleaseTitle("Invaders from Mars 4K Blu-ray (Standard Edition)"))
        assertEquals("1-Ichi", BluRayComUtils.cleanReleaseTitle("1-Ichi DVD (Special Edition)"))
        assertEquals("The 'Burbs", BluRayComUtils.cleanReleaseTitle("The 'Burbs 4K Ultra HD"))
        assertEquals("Halloween", BluRayComUtils.cleanReleaseTitle("Halloween - VHS"))
    }

    @Test
    fun `cleanReleaseTitle leaves titles without a format keyword untouched`() {
        assertEquals("Bad Ronald", BluRayComUtils.cleanReleaseTitle("Bad Ronald"))
        assertEquals("Digital Man", BluRayComUtils.cleanReleaseTitle("Digital Man"))
        assertEquals(null, BluRayComUtils.cleanReleaseTitle(null))
        assertEquals(null, BluRayComUtils.cleanReleaseTitle("   "))
    }

    @Test
    fun `cleanReleaseTitle keeps titles that start with a format keyword`() {
        // Nothing precedes the keyword, so there is no film name to keep.
        assertEquals("DVD Hell", BluRayComUtils.cleanReleaseTitle("DVD Hell"))
        assertEquals("4K Blu-ray", BluRayComUtils.cleanReleaseTitle("4K Blu-ray"))
    }

    @Test
    fun `nextEntryLetter picks the first unused letter`() {
        fun entry(letter: String?, id: Int? = null) =
            PhysicalMedia(mediaTypes = listOf(MediaType.BLURAY), entryLetter = letter, id = id)

        assertEquals("A", nextEntryLetter(emptyList()))
        assertEquals("B", nextEntryLetter(listOf(entry("A"))))
        assertEquals("C", nextEntryLetter(listOf(entry("A"), entry("B"))))
        // Gaps are filled rather than skipped.
        assertEquals("B", nextEntryLetter(listOf(entry("A"), entry("C"))))
        // Entries with no letter yet do not consume one.
        assertEquals("A", nextEntryLetter(listOf(entry(null), entry("   "))))
        // Letters are compared case-insensitively.
        assertEquals("B", nextEntryLetter(listOf(entry("a"))))
        assertEquals(null, nextEntryLetter(('A'..'Z').map { entry(it.toString()) }))
    }

    @Test
    fun `nextEntryLetter ignores the entry being edited`() {
        fun entry(letter: String?, id: Int?) =
            PhysicalMedia(mediaTypes = listOf(MediaType.BLURAY), entryLetter = letter, id = id)

        val entries = listOf(entry("A", 1), entry("B", 2))
        assertEquals("A", nextEntryLetter(entries, excludingId = 1))
        assertEquals("B", nextEntryLetter(entries, excludingId = 2))
        assertEquals("C", nextEntryLetter(entries, excludingId = 99))
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
        assertEquals("Invaders from Mars", media.title)
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
        assertEquals("1-Ichi", media.title)
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

    @Test
    fun `parseMoney reads dollar amounts with thousands separators`() {
        assertEquals(31.32, BluRayComUtils.parseMoney("$31.32 (Save 37%)"))
        assertEquals(1299.99, BluRayComUtils.parseMoney("$1,299.99"))
        assertEquals(25.0, BluRayComUtils.parseMoney("$ 25"))
        assertEquals(null, BluRayComUtils.parseMoney("no price here"))
        assertEquals(null, BluRayComUtils.parseMoney(null))
    }

    @Test
    fun `extractPrices reads the Price block`() {
        val html = """
            <html><body><td>
            <span class="subheading">Price</span><br>
            List price: <strike>${'$'}49.95</strike><br>
            Amazon: <a href="#" title="Last price change: Jun 01, 2026"><b>${'$'}31.32</b> (Save 37%)</a>
            <br>New from: <a href="#"><b>${'$'}29.99</b> (Save 40%)</a><br>
            <font color="#006600">In Stock</font><br>
            <a id="movie_buylink" href="https://www.blu-ray.com/link/click.php?p=1&tid=022&c=7">buy</a>
            </td></body></html>
        """.trimIndent()
        val prices = BluRayComUtils.extractPrices(Jsoup.parse(html))

        assertEquals(49.95, prices.listPrice)
        assertEquals(31.32, prices.amazonPrice)
        assertEquals(29.99, prices.newFromPrice)
        assertEquals(true, prices.inStock)
        assertEquals("2026-06-01", prices.lastPriceChange)
        assertEquals("https://www.blu-ray.com/link/click.php?p=1&tid=022&c=7", prices.buyLink)
        assertFalse(prices.isEmpty)
    }

    @Test
    fun `extractPrices from real example HTML`() {
        val htmlContent = this::class.java.getResource("/bluray_invaders.html")?.readText()
            ?: error("Could not load bluray_invaders.html")
        val prices = BluRayComUtils.extractPrices(Jsoup.parse(htmlContent))

        assertEquals(49.95, prices.listPrice)
        assertEquals(31.32, prices.amazonPrice)
        assertEquals(31.32, prices.newFromPrice)
        assertEquals(true, prices.inStock)
        assertEquals("2026-06-01", prices.lastPriceChange)
        assertTrue(prices.buyLink?.contains("click.php") == true, "Should capture the buy link")
    }

    @Test
    fun `extractPrices tolerates a relative last-change date`() {
        val htmlContent = this::class.java.getResource("/bluray_burbs.html")?.readText()
            ?: error("Could not load bluray_burbs.html")
        val prices = BluRayComUtils.extractPrices(Jsoup.parse(htmlContent))

        assertEquals(44.98, prices.listPrice)
        assertEquals(31.54, prices.amazonPrice)
        assertEquals(31.54, prices.newFromPrice)
        assertEquals(true, prices.inStock)
        assertEquals(null, prices.lastPriceChange, "\"2 days ago\" is not a date")
    }

    @Test
    fun `extractPrices reads third-party prices when Amazon has none`() {
        val htmlContent = this::class.java.getResource("/bluray_dvd_ichi.html")?.readText()
            ?: error("Could not load bluray_dvd_ichi.html")
        val prices = BluRayComUtils.extractPrices(Jsoup.parse(htmlContent))

        assertEquals(14.99, prices.listPrice)
        assertEquals(null, prices.amazonPrice)
        assertEquals(20.34, prices.newFromPrice)
        assertEquals(9.98, prices.usedFromPrice)
        assertEquals(null, prices.inStock)
    }

    @Test
    fun `extractPrices is empty when the page has no Price block`() {
        val html = """
            <html><head><meta property="og:title" content="Some Film Blu-ray" /></head>
            <body><span class="subheading">Discs</span><br>Blu-ray Disc<br></body></html>
        """.trimIndent()
        val prices = BluRayComUtils.extractPrices(Jsoup.parse(html))

        assertEquals(null, prices.listPrice)
        assertEquals(null, prices.amazonPrice)
        assertEquals(null, prices.newFromPrice)
        assertEquals(null, prices.usedFromPrice)
        assertEquals(null, prices.inStock)
        assertTrue(prices.isEmpty)
    }
}
