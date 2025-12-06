package org.btmonier

import kotlinx.browser.document
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.onClickFunction
import org.w3c.dom.Element

/**
 * Modal dialog for displaying detailed movie metadata.
 */
class MovieMetadataModal(
    private val container: Element,
    private val onClose: () -> Unit
) {
    /**
     * Show the modal with movie metadata.
     */
    fun show(movie: MovieMetadata) {
        // Remove existing modal if any
        close()

        container.append {
            div {
                id = "movie-metadata-modal"
                style = """
                    position: fixed;
                    top: 0;
                    left: 0;
                    width: 100%;
                    height: 100%;
                    background-color: rgba(0, 0, 0, 0.5);
                    display: flex;
                    justify-content: center;
                    align-items: center;
                    z-index: 1000;
                """.trimIndent()

                // Close on background click
                onClickFunction = { event ->
                    if (event.target == document.getElementById("movie-metadata-modal")) {
                        close()
                        onClose()
                    }
                }

                div {
                    style = """
                        background-color: white;
                        padding: 30px;
                        border-radius: 8px;
                        max-width: 800px;
                        width: 90%;
                        max-height: 90vh;
                        overflow-y: auto;
                        box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
                    """.trimIndent()

                    // Prevent clicks inside the modal from closing it
                    onClickFunction = { event ->
                        event.stopPropagation()
                    }

                    // Header with title and close button
                    div {
                        style = "display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px;"
                        h2 {
                            style = "margin: 0; color: #202124;"
                            +(movie.title ?: "Movie Details")
                        }
                        button {
                            style = """
                                padding: 8px 12px;
                                font-size: 18px;
                                cursor: pointer;
                                background-color: transparent;
                                color: #5f6368;
                                border: none;
                                border-radius: 4px;
                                font-weight: bold;
                            """.trimIndent()
                            attributes["onmouseover"] = "this.style.backgroundColor='#f1f3f4'"
                            attributes["onmouseout"] = "this.style.backgroundColor='transparent'"
                            +"×"
                            onClickFunction = {
                                close()
                                onClose()
                            }
                        }
                    }

                    // Movie metadata content
                    div {
                        style = "display: grid; gap: 16px;"

                        // Description
                        if (!movie.description.isNullOrBlank()) {
                            metadataRow("Description") {
                                p {
                                    style = "margin: 0; line-height: 1.6; color: #202124; font-size: 14px;"
                                    +movie.description
                                }
                            }
                        }

                        // Alternate Titles
                        if (movie.alternateTitles.isNotEmpty()) {
                            metadataRow("Alternate Titles") {
                                div {
                                    style = "display: flex; flex-wrap: wrap; gap: 8px;"
                                    movie.alternateTitles.forEach { title ->
                                        span {
                                            style = "padding: 6px 12px; background-color: #f3e8ff; color: #7c3aed; border-radius: 16px; font-size: 13px; font-weight: 500;"
                                            +title
                                        }
                                    }
                                }
                            }
                        }

                        // Release Date & Runtime
                        if (movie.release_date != null || movie.runtime_mins != null) {
                            metadataRow("Basic Information") {
                                if (movie.release_date != null) {
                                    metadataField("Release Date", movie.release_date.toString())
                                }
                                if (movie.runtime_mins != null) {
                                    metadataField("Runtime", "${movie.runtime_mins} minutes")
                                }
                            }
                        }

                        // Genres
                        if (movie.genres.isNotEmpty()) {
                            metadataRow("Genres") {
                                div {
                                    style = "display: flex; flex-wrap: wrap; gap: 8px;"
                                    movie.genres.forEach { genre ->
                                        span {
                                            style = "padding: 6px 12px; background-color: #e8f0fe; color: #1967d2; border-radius: 16px; font-size: 13px; font-weight: 500;"
                                            +genre
                                        }
                                    }
                                }
                            }
                        }

                        // Subgenres
                        if (movie.subgenres.isNotEmpty()) {
                            metadataRow("Subgenres") {
                                div {
                                    style = "display: flex; flex-wrap: wrap; gap: 8px;"
                                    movie.subgenres.forEach { subgenre ->
                                        span {
                                            style = "padding: 6px 12px; background-color: #e3f2fd; color: #0d47a1; border-radius: 16px; font-size: 13px; font-weight: 500;"
                                            +subgenre
                                        }
                                    }
                                }
                            }
                        }

                        // Themes
                        if (movie.themes.isNotEmpty()) {
                            metadataRow("Themes") {
                                div {
                                    style = "display: flex; flex-wrap: wrap; gap: 8px;"
                                    movie.themes.forEach { theme ->
                                        span {
                                            style = "padding: 6px 12px; background-color: #fef7e0; color: #c5a600; border-radius: 16px; font-size: 13px; font-weight: 500;"
                                            +theme
                                        }
                                    }
                                }
                            }
                        }

                        // Countries
                        if (movie.country.isNotEmpty()) {
                            metadataRow("Countries") {
                                div {
                                    style = "display: flex; flex-wrap: wrap; gap: 8px;"
                                    movie.country.forEach { country ->
                                        span {
                                            style = "padding: 6px 12px; background-color: #e6f4ea; color: #137333; border-radius: 16px; font-size: 13px; font-weight: 500;"
                                            +country
                                        }
                                    }
                                }
                            }
                        }

                        // Cast
                        if (movie.cast.isNotEmpty()) {
                            metadataRow("Cast") {
                                div {
                                    style = "color: #5f6368; font-size: 14px; line-height: 1.6;"
                                    +movie.cast.joinToString(", ")
                                }
                            }
                        }

                        // Crew
                        if (movie.crew.isNotEmpty()) {
                            metadataRow("Crew") {
                                div {
                                    style = "color: #5f6368; font-size: 14px; line-height: 1.6;"
                                    movie.crew.entries.forEach { (role, people) ->
                                        div {
                                            style = "margin-bottom: 8px;"
                                            span {
                                                style = "font-weight: 500; color: #202124;"
                                                +"$role: "
                                            }
                                            +people.joinToString(", ")
                                        }
                                    }
                                }
                            }
                        }

                        // Physical Media
                        if (movie.physicalMedia.isNotEmpty()) {
                            metadataRow("Physical Media") {
                                div {
                                    style = "display: grid; gap: 12px;"
                                    movie.physicalMedia.forEach { media ->
                                        div {
                                            style = """
                                                padding: 12px;
                                                background-color: #f8f9fa;
                                                border-radius: 6px;
                                                border-left: 3px solid #1a73e8;
                                            """.trimIndent()

                                            // Entry letter, title, and media types
                                            div {
                                                style = "display: flex; align-items: center; gap: 8px; margin-bottom: 8px; flex-wrap: wrap;"
                                                if (media.entryLetter != null) {
                                                    span {
                                                        style = "padding: 4px 8px; background-color: #dadce0; color: #202124; border-radius: 4px; font-size: 11px; font-weight: 600;"
                                                        +media.entryLetter
                                                    }
                                                }
                                                if (media.title != null) {
                                                    span {
                                                        style = "padding: 4px 8px; background-color: #e8f0fe; color: #1967d2; border-radius: 4px; font-size: 12px; font-weight: 500;"
                                                        +media.title
                                                    }
                                                }
                                                media.mediaTypes.forEach { type ->
                                                    val mediaLabel = when (type) {
                                                        MediaType.VHS -> "VHS"
                                                        MediaType.DVD -> "DVD"
                                                        MediaType.BLURAY -> "Blu-ray"
                                                        MediaType.FOURK -> "4K"
                                                        MediaType.DIGITAL -> "Digital"
                                                    }
                                                    val badgeColor = when (type) {
                                                        MediaType.VHS -> "background-color: #e8f5e9; color: #1e8e3e;"
                                                        MediaType.DVD -> "background-color: #e3f2fd; color: #1565c0;"
                                                        MediaType.BLURAY -> "background-color: #f3e5f5; color: #6a1b9a;"
                                                        MediaType.FOURK -> "background-color: #fff3e0; color: #e65100;"
                                                        MediaType.DIGITAL -> "background-color: #fce4ec; color: #c2185b;"
                                                    }
                                                    span {
                                                        style = "padding: 4px 10px; $badgeColor border-radius: 12px; font-size: 12px; font-weight: 500;"
                                                        +mediaLabel
                                                    }
                                                }
                                            }

                                            // Media details
                                            div {
                                                style = "font-size: 13px; color: #5f6368;"
                                                if (!media.distributor.isNullOrBlank()) {
                                                    div {
                                                        style = "margin-bottom: 4px;"
                                                        strong { +"Distributor: " }
                                                        +media.distributor
                                                    }
                                                }
                                                if (!media.releaseDate.isNullOrBlank()) {
                                                    div {
                                                        style = "margin-bottom: 4px;"
                                                        strong { +"Release Date: " }
                                                        +media.releaseDate
                                                    }
                                                }
                                                if (!media.location.isNullOrBlank()) {
                                                    div {
                                                        style = "margin-bottom: 4px;"
                                                        strong { +"Location: " }
                                                        +media.location
                                                    }
                                                }
                                                if (!media.blurayComUrl.isNullOrBlank()) {
                                                    div {
                                                        a(href = media.blurayComUrl, target = "_blank") {
                                                            rel = "noopener noreferrer"
                                                            style = "color: #1a73e8; text-decoration: none;"
                                                            +"View on Blu-ray.com"
                                                        }
                                                    }
                                                }
                                                // Physical Media Images Gallery
                                                if (media.images.isNotEmpty()) {
                                                    div {
                                                        style = "margin-top: 12px; padding-top: 12px; border-top: 1px solid #e8eaed;"
                                                        
                                                        div {
                                                            style = "font-size: 12px; font-weight: 600; color: #5f6368; margin-bottom: 10px; display: flex; align-items: center; gap: 6px;"
                                                            span { +"📸" }
                                                            +"Images (${media.images.size})"
                                                        }

                                                        div {
                                                            style = "display: grid; grid-template-columns: repeat(auto-fill, minmax(100px, 1fr)); gap: 12px;"
                                                            media.images.forEach { image ->
                                                                div {
                                                                    style = "position: relative;"
                                                                    a {
                                                                        href = image.imageUrl
                                                                        target = "_blank"
                                                                        rel = "noopener noreferrer"
                                                                        
                                                                        img {
                                                                            src = image.imageUrl
                                                                            alt = image.description ?: "Physical media image"
                                                                            style = """
                                                                                width: 100%;
                                                                                height: 140px;
                                                                                object-fit: cover;
                                                                                border-radius: 6px;
                                                                                border: 1px solid #e8eaed;
                                                                                cursor: pointer;
                                                                                transition: transform 0.2s, box-shadow 0.2s;
                                                                            """.trimIndent()
                                                                            attributes["onmouseover"] = "this.style.transform='scale(1.03)'; this.style.boxShadow='0 4px 12px rgba(0,0,0,0.15)'"
                                                                            attributes["onmouseout"] = "this.style.transform='scale(1)'; this.style.boxShadow='none'"
                                                                            attributes["onerror"] = "this.style.display='none'; this.parentElement.innerHTML='<div style=\"width:100%;height:140px;background:#f1f3f4;border-radius:6px;display:flex;align-items:center;justify-content:center;color:#5f6368;font-size:12px;\">Image unavailable</div>'"
                                                                        }
                                                                    }
                                                                    if (!image.description.isNullOrBlank()) {
                                                                        div {
                                                                            style = "text-align: center; font-size: 11px; color: #5f6368; margin-top: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;"
                                                                            +image.description
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Watched Entries
                        if (movie.watchedEntries.isNotEmpty()) {
                            metadataRow("Watched") {
                                div {
                                    style = "display: grid; gap: 12px;"

                                    // Calculate stats
                                    val watchCount = movie.watchedEntries.size
                                    val ratingsWithValues = movie.watchedEntries.mapNotNull { it.rating }
                                    val avgRating = if (ratingsWithValues.isNotEmpty()) {
                                        ratingsWithValues.average()
                                    } else null

                                    // Stats summary
                                    div {
                                        style = "padding: 12px; background-color: #e8f0fe; border-radius: 6px; margin-bottom: 8px;"
                                        div {
                                            style = "font-size: 13px; color: #1967d2; font-weight: 500;"
                                            + "Watched $watchCount ${if (watchCount == 1) "time" else "times"}"
                                            if (avgRating != null) {
                                                span {
                                                    val formattedRating = (avgRating * 5).toInt() / 5.0 // Round to 1 decimal
                                                    + " \uD83D\uDF84 " // small circle
                                                    + "Average Rating: $formattedRating/5"
                                                }
                                            }
                                        }
                                    }

                                    // Individual entries
                                    movie.watchedEntries.sortedByDescending { it.watchedDate }.forEach { entry ->
                                        div {
                                            style = """
                                                padding: 10px;
                                                background-color: #f8f9fa;
                                                border-radius: 6px;
                                                border-left: 3px solid #34a853;
                                            """.trimIndent()

                                            div {
                                                style = "display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px;"
                                                span {
                                                    style = "font-weight: 500; color: #202124; font-size: 13px;"
                                                    +entry.watchedDate
                                                }
                                                if (entry.rating != null) {
                                                    span {
                                                        style = "padding: 2px 8px; background-color: #e8f0fe; color: #1967d2; border-radius: 12px; font-size: 12px; font-weight: 500;"
                                                        +"${entry.rating}/5"
                                                    }
                                                }
                                            }

                                            if (!entry.notes.isNullOrBlank()) {
                                                div {
                                                    style = "font-size: 12px; color: #5f6368; font-style: italic; margin-top: 4px;"
                                                    +"\"${entry.notes}\""
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Letterboxd URL
                        if (!movie.url.isNullOrBlank()) {
                            div {
                                style = "margin-top: 16px; padding-top: 16px; border-top: 1px solid #dadce0;"
                                a(href = movie.url, target = "_blank") {
                                    rel = "noopener noreferrer"
                                    style = """
                                        display: inline-flex;
                                        align-items: center;
                                        padding: 10px 20px;
                                        background-color: #1a73e8;
                                        color: white;
                                        text-decoration: none;
                                        border-radius: 4px;
                                        font-size: 14px;
                                        font-weight: 500;
                                        transition: background-color 0.2s;
                                    """.trimIndent()
                                    attributes["onmouseover"] = "this.style.backgroundColor='#1765cc'"
                                    attributes["onmouseout"] = "this.style.backgroundColor='#1a73e8'"
                                    +"View on Letterboxd →"
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Close and hide the modal.
     */
    fun close() {
        val modal = document.getElementById("movie-metadata-modal")
        modal?.remove()
    }

    private fun DIV.metadataRow(label: String, content: DIV.() -> Unit) {
        div {
            style = "padding: 12px 0; border-bottom: 1px solid #f1f3f4;"
            div {
                style = "font-size: 12px; font-weight: 600; color: #5f6368; text-transform: uppercase; letter-spacing: 0.5px; margin-bottom: 8px;"
                +label
            }
            content()
        }
    }

    private fun DIV.metadataField(label: String, value: String) {
        div {
            style = "margin-bottom: 8px;"
            span {
                style = "font-weight: 500; color: #202124; font-size: 14px;"
                +"$label: "
            }
            span {
                style = "color: #5f6368; font-size: 14px;"
                +value
            }
        }
    }
}
