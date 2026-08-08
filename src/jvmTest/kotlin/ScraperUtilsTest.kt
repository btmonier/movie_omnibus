package org.btmonier

import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScraperUtilsTest {

    @Test
    fun `isLetterboxdFilmUrl accepts full and shortened URLs`() {
        assertTrue(ScraperUtils.isLetterboxdFilmUrl("https://letterboxd.com/film/the-godfather/"))
        assertTrue(ScraperUtils.isLetterboxdFilmUrl("http://www.letterboxd.com/film/the-godfather"))
        assertTrue(ScraperUtils.isLetterboxdFilmUrl("https://boxd.it/2aNK"))
        assertTrue(ScraperUtils.isLetterboxdFilmUrl("http://boxd.it/2aNK/"))
        assertTrue(!ScraperUtils.isLetterboxdFilmUrl("https://example.com/film/the-godfather/"))
        assertTrue(!ScraperUtils.isLetterboxdFilmUrl("https://letterboxd.com/list/some-list/"))
    }

    @Test
    fun `letterboxdUrlVariants includes trailing slash variants`() {
        val variants = ScraperUtils.letterboxdUrlVariants("https://boxd.it/2aNK")

        assertTrue(variants.contains("https://boxd.it/2aNK"))
        assertTrue(variants.contains("https://boxd.it/2aNK/"))
    }

    @Test
    fun `letterboxdUrlVariants normalizes full URLs`() {
        val variants = ScraperUtils.letterboxdUrlVariants("http://www.letterboxd.com/film/the-godfather")

        assertTrue(variants.contains("https://letterboxd.com/film/the-godfather/"))
        assertTrue(variants.contains("https://letterboxd.com/film/the-godfather"))
    }

    @Test
    fun `extractShortUrl finds boxd it URL in share widget`() {
        val html = """
            <html>
                <body>
                    <div class="urlgroup">
                        <input id="url-field-film-51818" type="text" value="https://boxd.it/2aNK" readonly>
                    </div>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        assertEquals("https://boxd.it/2aNK", ScraperUtils.extractShortUrl(doc))
    }

    @Test
    fun `extractShortUrl returns null when absent`() {
        val doc = Jsoup.parse("<html><body><p>No share widget</p></body></html>")
        assertEquals(null, ScraperUtils.extractShortUrl(doc))
    }

    @Test
    fun `extractCanonicalUrl reads og url meta tag`() {
        val html = """
            <html>
                <head><meta property="og:url" content="https://letterboxd.com/film/the-godfather/"></head>
                <body></body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        assertEquals("https://letterboxd.com/film/the-godfather/", ScraperUtils.extractCanonicalUrl(doc))
    }

    @Test
    fun `scrapeByHref extracts genres correctly`() {
        val html = """
            <html>
                <body>
                    <a href="/genre/drama/" class="text-slug">Drama</a>
                    <a href="/genre/crime/" class="text-slug">Crime</a>
                    <a href="/theme/prison/" class="text-slug">Prison</a>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val genres = ScraperUtils.scrapeByHref(doc, "/genre/")

        assertEquals(2, genres.size)
        assertTrue(genres.contains("Drama"))
        assertTrue(genres.contains("Crime"))
    }

    @Test
    fun `scrapeByHref extracts themes correctly`() {
        val html = """
            <html>
                <body>
                    <a href="/theme/prison/" class="text-slug">Prison</a>
                    <a href="/theme/friendship/" class="text-slug">Friendship</a>
                    <a href="/theme/hope/" class="text-slug">Hope</a>
                    <a href="/genre/drama/" class="text-slug">Drama</a>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val themes = ScraperUtils.scrapeByHref(doc, "/theme/")

        assertEquals(3, themes.size)
        assertTrue(themes.contains("Prison"))
        assertTrue(themes.contains("Friendship"))
        assertTrue(themes.contains("Hope"))
    }

    @Test
    fun `scrapeByHref extracts country correctly`() {
        val html = """
            <html>
                <body>
                    <a href="/films/country/usa/" class="text-slug">United States</a>
                    <a href="/films/country/uk/" class="text-slug">United Kingdom</a>
                    <a href="/genre/drama/" class="text-slug">Drama</a>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val countries = ScraperUtils.scrapeByHref(doc, "/films/country/")

        assertEquals(2, countries.size)
        assertTrue(countries.contains("United States"))
        assertTrue(countries.contains("United Kingdom"))
    }

    @Test
    fun `scrapeByHref returns empty list when no matches found`() {
        val html = """
            <html>
                <body>
                    <a href="/genre/drama/" class="text-slug">Drama</a>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val themes = ScraperUtils.scrapeByHref(doc, "/theme/")

        assertTrue(themes.isEmpty())
    }

    @Test
    fun `scrapeByHref removes duplicates`() {
        val html = """
            <html>
                <body>
                    <a href="/genre/drama/" class="text-slug">Drama</a>
                    <a href="/genre/drama/" class="text-slug">Drama</a>
                    <a href="/genre/crime/" class="text-slug">Crime</a>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val genres = ScraperUtils.scrapeByHref(doc, "/genre/")

        assertEquals(2, genres.size)
    }

    @Test
    fun `scrapeByHref ignores elements without text-slug class`() {
        val html = """
            <html>
                <body>
                    <a href="/genre/drama/" class="text-slug">Drama</a>
                    <a href="/genre/crime/" class="other-class">Crime</a>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val genres = ScraperUtils.scrapeByHref(doc, "/genre/")

        assertEquals(1, genres.size)
        assertEquals("Drama", genres[0])
    }

    @Test
    fun `scrapeByHref excludes Show All links`() {
        val html = """
            <html>
                <body>
                    <a href="/films/theme/epic-heroes/" class="text-slug">Epic heroes</a>
                    <a href="/films/mini-theme/monster-creature/" class="text-slug">Sci-fi monster adventures</a>
                    <a href="/films/theme/friendship/" class="text-slug">Friendship</a>
                    <a href="/film/test-movie/themes/" class="text-slug">Show All…</a>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)

        // Test both /theme/ and /mini-theme/ patterns (as used in extractMetadata)
        val regularThemes = ScraperUtils.scrapeByHref(doc, "/theme/")
        val miniThemes = ScraperUtils.scrapeByHref(doc, "/mini-theme/")
        val allThemes = (regularThemes + miniThemes).distinct()

        assertEquals(3, allThemes.size, "Expected 3 themes but got ${allThemes.size}: $allThemes")
        assertTrue(allThemes.contains("Epic heroes"))
        assertTrue(allThemes.contains("Sci-fi monster adventures"))
        assertTrue(allThemes.contains("Friendship"))
        assertTrue(!allThemes.contains("Show All…"))

        // Verify that "Show All…" link was filtered out for /theme/ pattern
        assertEquals(2, regularThemes.size, "Expected 2 regular themes")
        assertTrue(!regularThemes.contains("Show All…"))

        // Verify that mini-themes are extracted separately
        assertEquals(1, miniThemes.size, "Expected 1 mini-theme")
        assertTrue(miniThemes.contains("Sci-fi monster adventures"))
    }

    @Test
    fun `extractTitle extracts title from h1 tag`() {
        val html = """
            <html>
                <head><title>The Shawshank Redemption • Letterboxd</title></head>
                <body>
                    <h1>The Shawshank Redemption</h1>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val title = ScraperUtils.extractTitle(doc)

        assertEquals("The Shawshank Redemption", title)
    }

    @Test
    fun `extractTitle falls back to title tag when h1 is missing`() {
        val html = """
            <html>
                <head><title>The Godfather • Letterboxd</title></head>
                <body></body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val title = ScraperUtils.extractTitle(doc)

        assertEquals("The Godfather", title)
    }

    @Test
    fun `extractTitle extracts from og title meta tag`() {
        val html = """
            <html>
                <head>
                    <meta property="og:title" content="Pulp Fiction" />
                </head>
                <body></body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val title = ScraperUtils.extractTitle(doc)

        assertEquals("Pulp Fiction", title)
    }

    @Test
    fun `extractMetadata extracts all metadata with provided title`() {
        // Load sample HTML from test resources
        val htmlContent = this::class.java.getResource("/sample_movie_page.html")?.readText()
            ?: error("Could not load sample_movie_page.html")

        val doc = Jsoup.parse(htmlContent)
        val metadata = ScraperUtils.extractMetadata(doc, "https://letterboxd.com/film/test-movie/", "The Shawshank Redemption")

        assertEquals("https://letterboxd.com/film/test-movie/", metadata.url)
        assertEquals("The Shawshank Redemption", metadata.title)
        assertEquals(2, metadata.genres.size)
        assertTrue(metadata.genres.contains("Drama"))
        assertTrue(metadata.genres.contains("Crime"))
        assertEquals(3, metadata.themes.size)
        assertTrue(metadata.themes.contains("Prison"))
        assertTrue(metadata.themes.contains("Friendship"))
        assertTrue(metadata.themes.contains("Hope"))
        assertEquals(1, metadata.country.size)
        assertTrue(metadata.country.contains("United States"))

        // Test new fields
        assertEquals(5, metadata.cast.size)
        assertTrue(metadata.cast.contains("Tim Robbins"))
        assertTrue(metadata.cast.contains("Morgan Freeman"))
        assertTrue(metadata.cast.contains("Bob Gunton"))
        assertTrue(metadata.cast.contains("William Sadler"))
        assertTrue(metadata.cast.contains("Clancy Brown"))

        assertEquals(4, metadata.crew.size)
        assertTrue(metadata.crew.containsKey("Director"))
        assertEquals(listOf("Frank Darabont"), metadata.crew["Director"])
        assertTrue(metadata.crew.containsKey("Writer"))
        assertEquals(listOf("Frank Darabont"), metadata.crew["Writer"])
        assertTrue(metadata.crew.containsKey("Original Writer"))
        assertEquals(listOf("Stephen King"), metadata.crew["Original Writer"])
        assertTrue(metadata.crew.containsKey("Cinematography"))
        assertEquals(listOf("Roger Deakins"), metadata.crew["Cinematography"])

        assertEquals(1994, metadata.release_date)
        assertEquals(142, metadata.runtime_mins)
    }

    @Test
    fun `extractMetadataWithTitle extracts all metadata including title from page`() {
        // Load sample HTML from test resources
        val htmlContent = this::class.java.getResource("/sample_movie_page.html")?.readText()
            ?: error("Could not load sample_movie_page.html")

        val doc = Jsoup.parse(htmlContent)
        val metadata = ScraperUtils.extractMetadataWithTitle(doc, "https://letterboxd.com/film/test-movie/")

        assertEquals("https://letterboxd.com/film/test-movie/", metadata.url)
        assertEquals("The Shawshank Redemption", metadata.title)
        assertEquals(2, metadata.genres.size)
        assertTrue(metadata.genres.contains("Drama"))
        assertTrue(metadata.genres.contains("Crime"))
        assertEquals(3, metadata.themes.size)
        assertTrue(metadata.themes.contains("Prison"))
        assertTrue(metadata.themes.contains("Friendship"))
        assertTrue(metadata.themes.contains("Hope"))
        assertEquals(1, metadata.country.size)
        assertTrue(metadata.country.contains("United States"))

        // Test new fields
        assertEquals(5, metadata.cast.size)
        assertTrue(metadata.cast.contains("Tim Robbins"))
        assertTrue(metadata.cast.contains("Morgan Freeman"))
        assertTrue(metadata.cast.contains("Bob Gunton"))

        assertEquals(4, metadata.crew.size)
        assertTrue(metadata.crew.containsKey("Director"))
        assertEquals(listOf("Frank Darabont"), metadata.crew["Director"])

        assertEquals(1994, metadata.release_date)
        assertEquals(142, metadata.runtime_mins)
    }

    @Test
    fun `extractMetadata handles page with no metadata`() {
        val html = """
            <html>
                <body>
                    <h1>Movie Title</h1>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val metadata = ScraperUtils.extractMetadata(doc, "https://letterboxd.com/film/test-movie/", "Test Movie")

        assertEquals("https://letterboxd.com/film/test-movie/", metadata.url)
        assertEquals("Test Movie", metadata.title)
        assertTrue(metadata.genres.isEmpty())
        assertTrue(metadata.themes.isEmpty())
        assertTrue(metadata.country.isEmpty())
        assertTrue(metadata.cast.isEmpty())
        assertTrue(metadata.crew.isEmpty())
        assertEquals(null, metadata.release_date)
        assertEquals(null, metadata.runtime_mins)
    }

    @Test
    fun `extractCast extracts cast members correctly`() {
        val html = """
            <html>
                <body>
                    <div class="tablist" role="tablist">
                        <a id="tab-cast" class="trigger" href="/film/test-movie/cast/" role="tab" aria-controls="tab-panel-cast">
                            <span class="label">Cast</span>
                        </a>
                    </div>
                    <div id="tab-panel-cast" role="tabpanel">
                        <h3 class="hidden">Cast</h3>
                        <div class="cast-list text-sluglist">
                            <a title="Character 1" href="/actor/actor1/" class="text-slug tooltip">Actor One</a>
                            <a title="Character 2" href="/actor/actor2/" class="text-slug tooltip">Actor Two</a>
                            <a title="Character 3" href="/actor/actor3/" class="text-slug tooltip">Actor Three</a>
                        </div>
                    </div>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val cast = ScraperUtils.extractCast(doc)

        assertEquals(3, cast.size)
        assertTrue(cast.contains("Actor One"))
        assertTrue(cast.contains("Actor Two"))
        assertTrue(cast.contains("Actor Three"))
    }

    @Test
    fun `extractCast includes overflow members and excludes the Show All toggle`() {
        val html = """
            <html>
                <body>
                    <div id="tab-panel-cast" role="tabpanel">
                        <div class="cast-list text-sluglist">
                            <a title="Character 1" href="/actor/actor1/" class="text-slug tooltip">Actor One</a>
                            <a title="Character 2" href="/actor/actor2/" class="text-slug tooltip">Actor Two</a>
                            <a class="text-slug" id="has-cast-overflow">Show All&hellip;</a>
                            <span id="cast-overflow" class="sluglist-overflow" style="display:none;">
                                <a title="Character 3" href="/actor/actor3/" class="text-slug tooltip">Actor Three</a>
                                <a title="Character 4" href="/actor/actor4/" class="text-slug tooltip">Actor Four</a>
                            </span>
                        </div>
                    </div>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val cast = ScraperUtils.extractCast(doc)

        assertEquals(listOf("Actor One", "Actor Two", "Actor Three", "Actor Four"), cast)
        assertTrue(cast.none { it.contains("Show All", ignoreCase = true) })
    }

    @Test
    fun `extractCast falls back to legacy tab-cast container`() {
        val html = """
            <html>
                <body>
                    <div id="tab-cast" class="tabbed-content-block">
                        <div class="cast-list text-sluglist">
                            <p>
                                <a href="/actor/actor1/" class="text-slug tooltip" data-original-title="Character 1">Actor One</a>
                                <a href="/actor/actor2/" class="text-slug tooltip" data-original-title="Character 2">Actor Two</a>
                            </p>
                        </div>
                    </div>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val cast = ScraperUtils.extractCast(doc)

        assertEquals(listOf("Actor One", "Actor Two"), cast)
    }

    @Test
    fun `extractCast returns empty list when no cast found`() {
        val html = """
            <html>
                <body>
                    <h1>Movie Title</h1>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val cast = ScraperUtils.extractCast(doc)

        assertTrue(cast.isEmpty())
    }

    @Test
    fun `extractCrew extracts crew members with roles correctly`() {
        val html = """
            <html>
                <body>
                    <div class="tablist" role="tablist">
                        <a id="tab-crew" class="trigger" href="/film/test-movie/crew/" role="tab" aria-controls="tab-panel-crew">
                            <span class="label">Crew</span>
                        </a>
                    </div>
                    <div id="tab-panel-crew" class="column-block" role="tabpanel" hidden="until-found">
                        <h3>
                            <span class="crewrole -full">Director</span>
                            <span class="crewrole -short" aria-hidden="true">Director</span>
                        </h3>
                        <div class="text-sluglist">
                            <a href="/director/director1/" class="text-slug">Director One</a>
                        </div>
                        <h3>
                            <span class="crewrole -full">Assistant Directors</span>
                            <span class="crewrole -short" aria-hidden="true">Asst. Directors</span>
                        </h3>
                        <div class="text-sluglist">
                            <a href="/assistant-director/ad1/" class="text-slug">Assistant One</a>
                            <a href="/assistant-director/ad2/" class="text-slug">Assistant Two</a>
                        </div>
                    </div>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val crew = ScraperUtils.extractCrew(doc)

        assertEquals(2, crew.size)
        assertTrue(crew.containsKey("Director"))
        assertEquals(listOf("Director One"), crew["Director"])
        assertTrue(crew.containsKey("Assistant Directors"))
        assertEquals(listOf("Assistant One", "Assistant Two"), crew["Assistant Directors"])
    }

    @Test
    fun `extractCrew falls back to legacy tab-crew container`() {
        val html = """
            <html>
                <body>
                    <div id="tab-crew" class="tabbed-content-block">
                        <h3>
                            <span class="crewrole -full">Director</span>
                        </h3>
                        <div class="text-sluglist">
                            <p>
                                <a href="/director/director1/" class="text-slug">Director One</a>
                            </p>
                        </div>
                        <h3>
                            <span class="crewrole -full">Writers</span>
                        </h3>
                        <div class="text-sluglist">
                            <p>
                                <a href="/writer/writer1/" class="text-slug">Writer One</a>
                                <a href="/writer/writer2/" class="text-slug">Writer Two</a>
                            </p>
                        </div>
                    </div>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val crew = ScraperUtils.extractCrew(doc)

        assertEquals(2, crew.size)
        assertEquals(listOf("Director One"), crew["Director"])
        assertEquals(listOf("Writer One", "Writer Two"), crew["Writers"])
    }

    @Test
    fun `extractCrew returns empty map when only the crew tab trigger is present`() {
        val html = """
            <html>
                <body>
                    <div class="tablist" role="tablist">
                        <a id="tab-crew" class="trigger" href="/film/test-movie/crew/" role="tab" aria-controls="tab-panel-crew">
                            <span class="label">Crew</span>
                        </a>
                    </div>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)

        assertTrue(ScraperUtils.extractCrew(doc).isEmpty())
    }

    @Test
    fun `extractCrew returns empty map when no crew found`() {
        val html = """
            <html>
                <body>
                    <h1>Movie Title</h1>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val crew = ScraperUtils.extractCrew(doc)

        assertTrue(crew.isEmpty())
    }

    @Test
    fun `extractReleaseDate extracts year correctly`() {
        val html = """
            <html>
                <body>
                    <span class="releasedate">
                        <a href="/films/year/1999/">1999</a>
                    </span>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val releaseDate = ScraperUtils.extractReleaseDate(doc)

        assertEquals(1999, releaseDate)
    }

    @Test
    fun `extractReleaseDate returns null when not found`() {
        val html = """
            <html>
                <body>
                    <h1>Movie Title</h1>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val releaseDate = ScraperUtils.extractReleaseDate(doc)

        assertEquals(null, releaseDate)
    }

    @Test
    fun `extractRuntime extracts minutes correctly`() {
        val html = """
            <html>
                <body>
                    <p class="text-link text-footer">
                        120&nbsp;mins &nbsp; More at IMDb
                    </p>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val runtime = ScraperUtils.extractRuntime(doc)

        assertEquals(120, runtime)
    }

    @Test
    fun `extractRuntime handles runtime without nbsp`() {
        val html = """
            <html>
                <body>
                    <p class="text-link text-footer">
                        95 mins
                    </p>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val runtime = ScraperUtils.extractRuntime(doc)

        assertEquals(95, runtime)
    }

    @Test
    fun `extractRuntime returns null when not found`() {
        val html = """
            <html>
                <body>
                    <h1>Movie Title</h1>
                </body>
            </html>
        """.trimIndent()

        val doc = Jsoup.parse(html)
        val runtime = ScraperUtils.extractRuntime(doc)

        assertEquals(null, runtime)
    }
}
