package org.btmonier

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

/**
 * Utility functions for scraping blu-ray.com physical media release pages.
 *
 * Mirrors [ScraperUtils]: every function is a pure transformation over a JSoup
 * [Document] with no network access, so the extractors can be unit-tested
 * against saved HTML fixtures.
 */
object BluRayComUtils {

    private val MONTHS = mapOf(
        "jan" to "01", "feb" to "02", "mar" to "03", "apr" to "04",
        "may" to "05", "jun" to "06", "jul" to "07", "aug" to "08",
        "sep" to "09", "oct" to "10", "nov" to "11", "dec" to "12"
    )

    private val FORMAT_KEYWORD = Regex(
        """\b(?:4K|Ultra\s*HD|Blu-?ray|DVD|VHS)\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Returns true if the URL points to a blu-ray.com release page. This covers
     * both Blu-ray/4K releases under `/movies/`, e.g.
     * `https://www.blu-ray.com/movies/Invaders-from-Mars-4K-Blu-ray/336476/`,
     * and DVD releases under `/dvd/`, e.g.
     * `https://www.blu-ray.com/dvd/1-Ichi-DVD/91279/`.
     */
    fun isBluRayComUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return false
        return Regex("""^https?://(www\.)?blu-ray\.com/(movies|dvd)/[^/]+/\d+/?.*$""", RegexOption.IGNORE_CASE)
            .containsMatchIn(trimmed)
    }

    /**
     * Extracts the release title (including edition) from the og:title meta tag,
     * e.g. "Invaders from Mars 4K Blu-ray (Standard Edition)".
     *
     * The format and edition text is kept here because [extractMediaTypes] scans
     * this string as a fallback signal; use [cleanReleaseTitle] for the film name.
     */
    fun extractTitle(doc: Document): String? {
        doc.select("meta[property=og:title]").attr("content").trim().takeIf { it.isNotBlank() }
            ?.let { return it }

        // Fall back to the <title> tag, dropping the "Blu-ray.com" suffix.
        val titleText = doc.select("title").text()
            .substringBefore(" | Blu-ray.com")
            .substringBefore(" - Blu-ray.com")
            .trim()
        return titleText.takeIf { it.isNotBlank() }
    }

    /**
     * Trims a release title down to the film name by cutting it at the first
     * physical format keyword, so "Bad Ronald Blu-ray (Warner Archive)" becomes
     * "Bad Ronald" and "1-Ichi DVD (Special Edition)" becomes "1-Ichi".
     *
     * "Digital" is not a cut keyword: it only ever shows up as a secondary combo
     * token (e.g. "... Blu-ray + Digital"), which the earlier "Blu-ray" match
     * already covers, and cutting on it would mangle films whose name starts with
     * the word. Titles with no format keyword, or where the keyword is the very
     * first word, are returned unchanged.
     */
    fun cleanReleaseTitle(raw: String?): String? {
        val title = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val match = FORMAT_KEYWORD.find(title) ?: return title
        if (match.range.first == 0) return title
        return title.substring(0, match.range.first)
            .trimEnd(' ', '\t', '-', '–', '—', ':', ',')
            .takeIf { it.isNotBlank() } ?: title
    }

    /**
     * Reads the text of an info section identified by its `span.subheading` label
     * (e.g. "Discs"), accumulating the sibling text/`<br>` nodes that follow the
     * label up to the next subheading. Returns an empty string when not found.
     */
    fun sectionText(doc: Document, label: String): String {
        val heading = doc.select("span.subheading").firstOrNull {
            it.text().trim().equals(label, ignoreCase = true)
        } ?: return ""

        val builder = StringBuilder()
        var node = heading.nextSibling()
        while (node != null) {
            if (node is Element && node.tagName() == "span" && node.hasClass("subheading")) break
            when (node) {
                is TextNode -> builder.append(node.text()).append(" ")
                is Element -> if (node.tagName() == "br") builder.append(" ") else builder.append(node.text()).append(" ")
            }
            node = node.nextSibling()
        }
        return builder.toString().replace(Regex("\\s+"), " ").trim()
    }

    /**
     * Determines the physical media formats for the release.
     *
     * The reliable signal is the headline edition line (`.subheadingtitle`),
     * formatted as "<edition> / <formats>" where multiple formats are joined with
     * a "+", e.g. "Standard Edition / 4K Ultra HD" (4K only) or
     * "Collector's Edition / 4K Ultra HD + Blu-ray" (a 4K + Blu-ray combo). The
     * "Discs" section is not reliable here because it lists "4K Ultra HD" and
     * "Blu-ray Disc" for a single 4K disc as well as for a combo.
     *
     * Falls back to scanning the title and "Discs" section when the headline line
     * is missing.
     */
    fun extractMediaTypes(doc: Document): List<MediaType> {
        val subheadingTitle = doc.select(".subheadingtitle").firstOrNull()?.text()?.trim() ?: ""
        val formatPortion = if (subheadingTitle.contains("/")) {
            subheadingTitle.substringAfterLast("/").trim()
        } else {
            subheadingTitle
        }

        val fromHeadline = parseFormatString(formatPortion)
        if (fromHeadline.isNotEmpty()) return fromHeadline

        // Fallback: combine the og:title and the "Discs" section, stripping the
        // combined 4K format phrases so a 4K disc is not misreported as a 1080p
        // Blu-ray.
        val title = extractTitle(doc) ?: ""
        val discsText = sectionText(doc, "Discs")
        val formatText = listOf(title, discsText).joinToString(" ").trim()
        if (formatText.isBlank()) return emptyList()

        val types = mutableListOf<MediaType>()
        if (Regex("""\b4K\b|Ultra\s*HD""", RegexOption.IGNORE_CASE).containsMatchIn(formatText)) {
            types.add(MediaType.FOURK)
        }
        val strippedForBluray = formatText
            .replace(Regex("""4K\s*Ultra\s*HD\s*Blu-?ray""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""Ultra\s*HD\s*Blu-?ray""", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("""4K\s*Blu-?ray""", RegexOption.IGNORE_CASE), " ")
        if (Regex("""Blu-?ray""", RegexOption.IGNORE_CASE).containsMatchIn(strippedForBluray)) {
            types.add(MediaType.BLURAY)
        }
        if (Regex("""\bDVD\b""", RegexOption.IGNORE_CASE).containsMatchIn(formatText)) types.add(MediaType.DVD)
        if (Regex("""\bDigital\b""", RegexOption.IGNORE_CASE).containsMatchIn(formatText)) types.add(MediaType.DIGITAL)
        if (Regex("""\bVHS\b""", RegexOption.IGNORE_CASE).containsMatchIn(formatText)) types.add(MediaType.VHS)

        return types.distinct()
    }

    /**
     * Parses a "+"-joined format string (e.g. "4K Ultra HD + Blu-ray") into the
     * corresponding media types. Each token maps to a single format; "4K"/"Ultra
     * HD" takes precedence over "Blu-ray" within a token so "4K Ultra HD" is not
     * also counted as a 1080p Blu-ray.
     */
    private fun parseFormatString(formats: String): List<MediaType> {
        if (formats.isBlank()) return emptyList()
        val types = mutableListOf<MediaType>()
        for (token in formats.split("+")) {
            val t = token.trim()
            if (t.isBlank()) continue
            when {
                Regex("""4K|Ultra\s*HD""", RegexOption.IGNORE_CASE).containsMatchIn(t) -> types.add(MediaType.FOURK)
                Regex("""Blu-?ray""", RegexOption.IGNORE_CASE).containsMatchIn(t) -> types.add(MediaType.BLURAY)
                Regex("""\bDVD\b""", RegexOption.IGNORE_CASE).containsMatchIn(t) -> types.add(MediaType.DVD)
                Regex("""\bDigital\b""", RegexOption.IGNORE_CASE).containsMatchIn(t) -> types.add(MediaType.DIGITAL)
                Regex("""\bVHS\b""", RegexOption.IGNORE_CASE).containsMatchIn(t) -> types.add(MediaType.VHS)
            }
        }
        return types.distinct()
    }

    /**
     * Extracts the distributor / studio label (e.g. "Ignite Films") from the
     * release info line, which links to the studio listing via `studioid`.
     */
    fun extractDistributor(doc: Document): String? {
        doc.select("span.subheading.grey a[href*=studioid]").firstOrNull()?.text()?.trim()
            ?.takeIf { it.isNotBlank() }?.let { return it }

        return doc.select("a[href*=studioid]").firstOrNull()?.text()?.trim()?.takeIf { it.isNotBlank() }
    }

    /**
     * Extracts the release date and converts it to ISO `YYYY-MM-DD`.
     *
     * The date appears as link text like "Jul 11, 2023" in the release info line.
     */
    fun extractReleaseDate(doc: Document): String? {
        val dateText = doc.select("span.subheading.grey a[href*=releasedates.php]").firstOrNull()?.text()?.trim()
            ?: doc.select("a[href*=releasedates.php]").firstOrNull()?.text()?.trim()
            ?: return null

        return parseHumanDate(dateText)
    }

    /**
     * Parses a human-readable date such as "Jul 11, 2023" or "July 11, 2023" into
     * ISO `YYYY-MM-DD`, or null when it cannot be parsed.
     */
    fun parseHumanDate(text: String): String? {
        val match = Regex("""([A-Za-z]+)\s+(\d{1,2}),\s*(\d{4})""").find(text) ?: return null
        val (monthName, day, year) = match.destructured
        val month = MONTHS[monthName.take(3).lowercase()] ?: return null
        return "$year-$month-${day.padStart(2, '0')}"
    }

    /**
     * Extracts cover images for the release. Uses the og:image meta tag, which
     * points at the front cover art.
     */
    fun extractImages(doc: Document): List<PhysicalMediaImage> {
        val frontCover = doc.select("meta[property=og:image]").attr("content").trim()
            .takeIf { it.isNotBlank() }
            ?: doc.select("img#frontimage_overlay").attr("src").trim().takeIf { it.isNotBlank() }

        return if (frontCover != null) {
            listOf(PhysicalMediaImage(imageUrl = frontCover, description = "Front Cover"))
        } else {
            emptyList()
        }
    }

    /**
     * Aggregates all scrapable fields into a [PhysicalMedia] preview (no database
     * id). The supplied [url] is stored as the blu-ray.com link.
     */
    fun extractPhysicalMedia(doc: Document, url: String): PhysicalMedia {
        return PhysicalMedia(
            mediaTypes = extractMediaTypes(doc),
            title = cleanReleaseTitle(extractTitle(doc)),
            distributor = extractDistributor(doc),
            releaseDate = extractReleaseDate(doc),
            blurayComUrl = url,
            images = extractImages(doc)
        )
    }
}
