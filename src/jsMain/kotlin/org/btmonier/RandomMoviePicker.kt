package org.btmonier

import kotlinx.browser.document
import kotlinx.coroutines.launch
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.onChangeFunction
import kotlinx.html.js.onClickFunction
import org.w3c.dom.*

/**
 * Random movie picker page that selects an unwatched movie with optional filters.
 */
class RandomMoviePicker(
    private val container: Element,
    private val onBack: () -> Unit
) {
    private var selectedGenre: String? = null
    private var selectedSubgenre: String? = null
    private var selectedCountry: String? = null
    private var selectedMediaType: String? = null
    
    private var genres: List<String> = emptyList()
    private var subgenres: List<String> = emptyList()
    private var countries: List<String> = emptyList()
    private var mediaTypes: List<String> = emptyList()
    
    private var pickedMovie: MovieMetadata? = null
    private var availableCount: Int = 0
    private var isLoading: Boolean = false

    private val metadataModal = MovieMetadataModal(container) {}
    private val alertDialog = AlertDialog(container)

    fun show() {
        mainScope.launch {
            // Load filter options
            try {
                genres = fetchGenreOptions()
                subgenres = fetchSubgenreOptions()
                countries = fetchAllCountries()
                mediaTypes = fetchAllMediaTypes()
                updateCount()
            } catch (e: Exception) {
                console.error("Error loading filter options:", e)
            }
            render()
        }
    }

    private fun render() {
        container.innerHTML = ""
        container.append {
            div {
                style = "max-width: 900px; margin: 0 auto; padding: 40px 20px; font-family: 'Google Sans', 'Roboto', arial, sans-serif;"

                // Header with back button
                div {
                    style = "display: flex; align-items: center; margin-bottom: 40px;"
                    button {
                        style = """
                            padding: 8px 16px;
                            font-size: 14px;
                            cursor: pointer;
                            background-color: #f1f3f4;
                            color: #202124;
                            border: none;
                            border-radius: 4px;
                            font-weight: 500;
                            margin-right: 20px;
                            transition: background-color 0.2s;
                        """.trimIndent()
                        attributes["onmouseover"] = "this.style.backgroundColor='#e8eaed'"
                        attributes["onmouseout"] = "this.style.backgroundColor='#f1f3f4'"
                        +"← Back to Collection"
                        onClickFunction = { onBack() }
                    }
                    h1 {
                        style = "color: #202124; font-weight: 400; font-size: 28px; margin: 0;"
                        +"🎲 Random Movie Picker"
                    }
                }

                // Description
                p {
                    style = "color: #5f6368; font-size: 16px; margin-bottom: 32px; line-height: 1.6;"
                    +"Can't decide what to watch? Let fate decide! This picker will randomly select from your unwatched movies. Use the filters below to narrow down your options."
                }

                // Filters section
                div {
                    style = """
                        background-color: #f8f9fa;
                        padding: 24px;
                        border-radius: 12px;
                        margin-bottom: 32px;
                        border: 1px solid #e8eaed;
                    """.trimIndent()

                    h3 {
                        style = "margin: 0 0 20px 0; color: #202124; font-size: 16px; font-weight: 500;"
                        +"Filters (Optional)"
                    }

                    div {
                        style = "display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px;"

                        // Genre filter
                        renderFilterSelect("Genre", "filter-genre", genres, selectedGenre)
                        
                        // Subgenre filter
                        renderFilterSelect("Subgenre", "filter-subgenre", subgenres, selectedSubgenre)
                        
                        // Country filter
                        renderFilterSelect("Country", "filter-country", countries, selectedCountry)
                        
                        // Media Type filter
                        renderFilterSelect("Media Type", "filter-media-type", mediaTypes, selectedMediaType)
                    }

                    // Available count and clear button
                    div {
                        style = "margin-top: 20px; display: flex; justify-content: space-between; align-items: center;"
                        
                        span {
                            id = "available-count"
                            style = "color: #5f6368; font-size: 14px;"
                            +"$availableCount unwatched movie${if (availableCount != 1) "s" else ""} available"
                        }
                        
                        button {
                            style = """
                                padding: 8px 16px;
                                font-size: 13px;
                                cursor: pointer;
                                background-color: transparent;
                                color: #1a73e8;
                                border: none;
                                font-weight: 500;
                            """.trimIndent()
                            +"Clear Filters"
                            onClickFunction = {
                                clearFilters()
                            }
                        }
                    }
                }

                // Pick button
                div {
                    style = "text-align: center; margin-bottom: 40px;"
                    button {
                        id = "pick-button"
                        style = """
                            padding: 16px 48px;
                            font-size: 18px;
                            cursor: pointer;
                            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                            color: white;
                            border: none;
                            border-radius: 8px;
                            font-weight: 500;
                            box-shadow: 0 4px 14px rgba(102, 126, 234, 0.4);
                            transition: transform 0.2s, box-shadow 0.2s;
                        """.trimIndent()
                        attributes["onmouseover"] = "this.style.transform='scale(1.02)'; this.style.boxShadow='0 6px 20px rgba(102, 126, 234, 0.5)'"
                        attributes["onmouseout"] = "this.style.transform='scale(1)'; this.style.boxShadow='0 4px 14px rgba(102, 126, 234, 0.4)'"
                        +"🎬 Pick a Random Movie"
                        onClickFunction = {
                            pickRandomMovie()
                        }
                    }
                }

                // Result section
                div {
                    id = "result-section"
                    renderResultSection()
                }

                // Footer
                appFooter()
            }
        }

        // Setup filter change handlers
        setupFilterHandlers()
    }

    private fun DIV.renderFilterSelect(labelText: String, id: String, options: List<String>, selectedValue: String?) {
        div {
            label {
                attributes["for"] = id
                style = "display: block; margin-bottom: 6px; font-size: 13px; color: #5f6368; font-weight: 500;"
                +labelText
            }
            select {
                this.id = id
                style = """
                    width: 100%;
                    padding: 10px 12px;
                    font-size: 14px;
                    border: 1px solid #dadce0;
                    border-radius: 6px;
                    background-color: white;
                    cursor: pointer;
                    font-family: 'Roboto', arial, sans-serif;
                """.trimIndent()
                
                option {
                    value = ""
                    selected = selectedValue == null
                    +"Any $labelText"
                }
                options.forEach { opt ->
                    option {
                        value = opt
                        selected = opt == selectedValue
                        +opt
                    }
                }
            }
        }
    }

    private fun DIV.renderResultSection() {
        if (isLoading) {
            div {
                style = "text-align: center; padding: 40px;"
                div {
                    style = """
                        display: inline-block;
                        width: 40px;
                        height: 40px;
                        border: 4px solid #e8eaed;
                        border-top: 4px solid #667eea;
                        border-radius: 50%;
                        animation: spin 1s linear infinite;
                    """.trimIndent()
                }
                style {
                    unsafe {
                        raw("@keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }")
                    }
                }
            }
        } else if (pickedMovie != null) {
            val movie = pickedMovie!!
            div {
                style = """
                    background: linear-gradient(135deg, #f5f7fa 0%, #c3cfe2 100%);
                    padding: 32px;
                    border-radius: 16px;
                    text-align: center;
                    box-shadow: 0 4px 20px rgba(0, 0, 0, 0.1);
                """.trimIndent()

                h2 {
                    style = "margin: 0 0 8px 0; color: #202124; font-size: 28px; font-weight: 500;"
                    +"🎉 Tonight's Pick"
                }
                
                div {
                    style = """
                        background-color: white;
                        padding: 24px;
                        border-radius: 12px;
                        margin: 20px 0;
                        box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
                    """.trimIndent()

                    h3 {
                        style = "margin: 0 0 12px 0; color: #1a73e8; font-size: 24px; cursor: pointer;"
                        attributes["onmouseover"] = "this.style.textDecoration='underline'"
                        attributes["onmouseout"] = "this.style.textDecoration='none'"
                        +movie.title
                        onClickFunction = {
                            metadataModal.show(movie)
                        }
                    }

                    // Year and runtime
                    div {
                        style = "color: #5f6368; font-size: 14px; margin-bottom: 16px;"
                        movie.release_date?.let { 
                            span { +"$it" }
                        }
                        movie.runtime_mins?.let { mins ->
                            if (movie.release_date != null) span { +" • " }
                            span { +"${mins} min" }
                        }
                    }

                    // Genres
                    if (movie.genres.isNotEmpty()) {
                        div {
                            style = "margin-bottom: 12px;"
                            movie.genres.forEach { genre ->
                                span {
                                    style = "display: inline-block; padding: 4px 12px; margin: 2px 4px; background-color: #e8f0fe; color: #1967d2; border-radius: 16px; font-size: 12px; font-weight: 500;"
                                    +genre
                                }
                            }
                        }
                    }

                    // Countries
                    if (movie.country.isNotEmpty()) {
                        div {
                            style = "color: #5f6368; font-size: 13px;"
                            +movie.country.joinToString(" • ")
                        }
                    }

                    // Description preview
                    movie.description?.let { desc ->
                        p {
                            style = "color: #5f6368; font-size: 14px; margin-top: 16px; line-height: 1.6; text-align: left;"
                            +if (desc.length > 300) desc.take(300) + "..." else desc
                        }
                    }
                }

                // Physical Media Section
                if (movie.physicalMedia.isNotEmpty()) {
                    div {
                        style = """
                            background-color: white;
                            padding: 24px;
                            border-radius: 12px;
                            margin: 20px 0;
                            box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
                        """.trimIndent()

                        h4 {
                            style = "margin: 0 0 16px 0; color: #202124; font-size: 16px; font-weight: 500; display: flex; align-items: center; gap: 8px;"
                            span { +"📀" }
                            +"Physical Media"
                        }

                        movie.physicalMedia.forEach { media ->
                            div {
                                style = """
                                    padding: 16px;
                                    background-color: #f8f9fa;
                                    border-radius: 8px;
                                    margin-bottom: 12px;
                                    border-left: 4px solid #1a73e8;
                                """.trimIndent()

                                // Entry letter and title row
                                div {
                                    style = "display: flex; align-items: center; gap: 12px; margin-bottom: 12px;"
                                    
                                    // Entry letter badge
                                    media.entryLetter?.let { letter ->
                                        span {
                                            style = """
                                                display: inline-flex;
                                                align-items: center;
                                                justify-content: center;
                                                width: 32px;
                                                height: 32px;
                                                background-color: #1a73e8;
                                                color: white;
                                                border-radius: 50%;
                                                font-weight: 600;
                                                font-size: 14px;
                                            """.trimIndent()
                                            +letter
                                        }
                                    }

                                    // Title if present
                                    media.title?.let { title ->
                                        span {
                                            style = "font-weight: 500; color: #202124; font-size: 15px;"
                                            +title
                                        }
                                    }
                                }

                                // Media type badges
                                div {
                                    style = "display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 12px;"
                                    media.mediaTypes.forEach { type ->
                                        val (label, bgColor, textColor) = when (type) {
                                            MediaType.VHS -> Triple("VHS", "#e8f5e9", "#1e8e3e")
                                            MediaType.DVD -> Triple("DVD", "#e3f2fd", "#1565c0")
                                            MediaType.BLURAY -> Triple("Blu-ray", "#f3e5f5", "#6a1b9a")
                                            MediaType.FOURK -> Triple("4K UHD", "#fff3e0", "#e65100")
                                            MediaType.DIGITAL -> Triple("Digital", "#fce4ec", "#c2185b")
                                        }
                                        span {
                                            style = "display: inline-block; padding: 6px 14px; background-color: $bgColor; color: $textColor; border-radius: 20px; font-size: 13px; font-weight: 500;"
                                            +label
                                        }
                                    }
                                }

                                // Location and distributor info
                                div {
                                    style = "display: flex; flex-wrap: wrap; gap: 16px; color: #5f6368; font-size: 13px;"
                                    
                                    media.location?.let { location ->
                                        div {
                                            style = "display: flex; align-items: center; gap: 6px;"
                                            span { +"📍" }
                                            span {
                                                style = "font-weight: 500;"
                                                +location
                                            }
                                        }
                                    }

                                    media.distributor?.let { distributor ->
                                        div {
                                            style = "display: flex; align-items: center; gap: 6px;"
                                            span { +"🏭" }
                                            span { +distributor }
                                        }
                                    }

                                    media.releaseDate?.let { date ->
                                        div {
                                            style = "display: flex; align-items: center; gap: 6px;"
                                            span { +"📅" }
                                            span { +date }
                                        }
                                    }
                                }

                                // Images
                                if (media.images.isNotEmpty()) {
                                    div {
                                        style = "margin-top: 16px;"
                                        
                                        div {
                                            style = "font-size: 13px; color: #5f6368; margin-bottom: 8px; font-weight: 500;"
                                            +"📸 Images (${media.images.size})"
                                        }

                                        div {
                                            style = "display: flex; flex-wrap: wrap; gap: 12px;"
                                            media.images.forEachIndexed { imageIndex, image ->
                                                div {
                                                    style = "position: relative;"
                                                    val allImages = media.images
                                                    img {
                                                        src = image.imageUrl
                                                        alt = image.description ?: "Physical media image"
                                                        style = """
                                                            width: 120px;
                                                            height: 160px;
                                                            object-fit: cover;
                                                            border-radius: 8px;
                                                            border: 1px solid #e8eaed;
                                                            cursor: pointer;
                                                            transition: transform 0.2s, box-shadow 0.2s;
                                                        """.trimIndent()
                                                        attributes["onmouseover"] = "this.style.transform='scale(1.05)'; this.style.boxShadow='0 4px 12px rgba(0,0,0,0.15)'"
                                                        attributes["onmouseout"] = "this.style.transform='scale(1)'; this.style.boxShadow='none'"
                                                        onClickFunction = {
                                                            ImageLightbox.show(allImages, imageIndex)
                                                        }
                                                    }
                                                    image.description?.let { desc ->
                                                        div {
                                                            style = "text-align: center; font-size: 11px; color: #5f6368; margin-top: 4px;"
                                                            +desc
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // Blu-ray.com link
                                media.blurayComUrl?.let { url ->
                                    div {
                                        style = "margin-top: 12px;"
                                        a {
                                            href = url
                                            target = "_blank"
                                            style = "color: #1a73e8; font-size: 13px; text-decoration: none;"
                                            attributes["onmouseover"] = "this.style.textDecoration='underline'"
                                            attributes["onmouseout"] = "this.style.textDecoration='none'"
                                            +"View on Blu-ray.com →"
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Action buttons
                div {
                    style = "display: flex; gap: 12px; justify-content: center; margin-top: 20px;"

                    button {
                        style = """
                            padding: 12px 24px;
                            font-size: 14px;
                            cursor: pointer;
                            background-color: #1a73e8;
                            color: white;
                            border: none;
                            border-radius: 6px;
                            font-weight: 500;
                            transition: background-color 0.2s;
                        """.trimIndent()
                        attributes["onmouseover"] = "this.style.backgroundColor='#1765cc'"
                        attributes["onmouseout"] = "this.style.backgroundColor='#1a73e8'"
                        +"View Details"
                        onClickFunction = {
                            metadataModal.show(movie)
                        }
                    }

                    // Add Watched Entry button
                    if (movie.id != null) {
                        val movieId = movie.id
                        button {
                            style = """
                                padding: 12px 24px;
                                font-size: 14px;
                                cursor: pointer;
                                background-color: #34a853;
                                color: white;
                                border: none;
                                border-radius: 6px;
                                font-weight: 500;
                                transition: background-color 0.2s;
                            """.trimIndent()
                            attributes["onmouseover"] = "this.style.backgroundColor='#2d8e47'"
                            attributes["onmouseout"] = "this.style.backgroundColor='#34a853'"
                            +"+ Add Watched Entry"
                            onClickFunction = {
                                showAddWatchedEntryForm(movieId)
                            }
                        }
                    }

                    button {
                        style = """
                            padding: 12px 24px;
                            font-size: 14px;
                            cursor: pointer;
                            background-color: #f1f3f4;
                            color: #202124;
                            border: none;
                            border-radius: 6px;
                            font-weight: 500;
                            transition: background-color 0.2s;
                        """.trimIndent()
                        attributes["onmouseover"] = "this.style.backgroundColor='#e8eaed'"
                        attributes["onmouseout"] = "this.style.backgroundColor='#f1f3f4'"
                        +"🎲 Pick Again"
                        onClickFunction = {
                            pickRandomMovie()
                        }
                    }

                    // Letterboxd link
                    if (movie.url.isNotBlank()) {
                        a {
                            href = movie.url
                            target = "_blank"
                            style = """
                                padding: 12px 24px;
                                font-size: 14px;
                                cursor: pointer;
                                background-color: #40bcf4;
                                color: white;
                                border: none;
                                border-radius: 6px;
                                font-weight: 500;
                                text-decoration: none;
                                display: inline-block;
                                transition: background-color 0.2s;
                            """.trimIndent()
                            attributes["onmouseover"] = "this.style.backgroundColor='#2aa8e0'"
                            attributes["onmouseout"] = "this.style.backgroundColor='#40bcf4'"
                            +"Open on Letterboxd"
                        }
                    }
                }
            }
        }
    }

    private fun setupFilterHandlers() {
        listOf(
            "filter-genre" to { value: String -> selectedGenre = value.ifBlank { null } },
            "filter-subgenre" to { value: String -> selectedSubgenre = value.ifBlank { null } },
            "filter-country" to { value: String -> selectedCountry = value.ifBlank { null } },
            "filter-media-type" to { value: String -> selectedMediaType = value.ifBlank { null } }
        ).forEach { (id, setter) ->
            val select = document.getElementById(id) as? HTMLSelectElement
            select?.addEventListener("change", {
                setter(select.value)
                mainScope.launch {
                    updateCount()
                    updateCountDisplay()
                }
            })
        }
    }

    private suspend fun updateCount() {
        availableCount = fetchUnwatchedMovieCount(
            genre = selectedGenre,
            subgenre = selectedSubgenre,
            country = selectedCountry,
            mediaType = selectedMediaType
        )
    }

    private fun updateCountDisplay() {
        val countSpan = document.getElementById("available-count")
        countSpan?.textContent = "$availableCount unwatched movie${if (availableCount != 1) "s" else ""} available"
    }

    private fun clearFilters() {
        selectedGenre = null
        selectedSubgenre = null
        selectedCountry = null
        selectedMediaType = null

        // Reset select elements
        listOf("filter-genre", "filter-subgenre", "filter-country", "filter-media-type").forEach { id ->
            val select = document.getElementById(id) as? HTMLSelectElement
            select?.value = ""
        }

        mainScope.launch {
            updateCount()
            updateCountDisplay()
        }
    }

    private fun pickRandomMovie() {
        if (availableCount == 0) {
            // Show alert that no movies match
            val alertDialog = AlertDialog(container)
            alertDialog.show(
                title = "No Movies Available",
                message = "No unwatched movies match your current filters. Try adjusting or clearing the filters."
            )
            return
        }

        isLoading = true
        updateResultSection()

        mainScope.launch {
            try {
                val movie = fetchRandomUnwatchedMovie(
                    genre = selectedGenre,
                    subgenre = selectedSubgenre,
                    country = selectedCountry,
                    mediaType = selectedMediaType
                )
                pickedMovie = movie
                isLoading = false
                
                if (movie == null) {
                    val alertDialog = AlertDialog(container)
                    alertDialog.show(
                        title = "No Movies Found",
                        message = "No unwatched movies match your current filters."
                    )
                }
            } catch (e: Exception) {
                isLoading = false
                console.error("Error picking random movie:", e)
                val alertDialog = AlertDialog(container)
                alertDialog.show(
                    title = "Error",
                    message = "Failed to pick a random movie: ${e.message}"
                )
            }
            updateResultSection()
        }
    }

    private fun updateResultSection() {
        val resultSection = document.getElementById("result-section")
        resultSection?.innerHTML = ""
        resultSection?.append {
            div {
                renderResultSection()
            }
        }
    }

    private fun showAddWatchedEntryForm(movieId: Int) {
        val form = WatchedForm(container, onSave = { watchedEntry ->
            createWatchedEntryForMovie(movieId, watchedEntry)
        }, onCancel = {})
        form.showCreate()
    }

    private suspend fun createWatchedEntryForMovie(movieId: Int, watchedEntry: WatchedEntry) {
        try {
            createWatchedEntry(movieId, watchedEntry)
            alertDialog.show(
                title = "Success",
                message = "Watched entry added successfully!"
            )
            // Refresh the picked movie data to show the new entry
            val updatedMovie = getMovieById(movieId)
            if (updatedMovie != null) {
                pickedMovie = updatedMovie
                updateResultSection()
            }
        } catch (e: Exception) {
            alertDialog.show(
                title = "Error",
                message = "Failed to add watched entry: ${e.message}"
            )
        }
    }
}

