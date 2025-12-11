package org.btmonier

import kotlinx.browser.document
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.onClickFunction
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement

/**
 * Modal form for creating and editing movies.
 */
class MovieForm(private val container: Element, private val onSave: suspend (MovieMetadata) -> Unit, private val onCancel: () -> Unit) {
    private var editingMovie: MovieMetadata? = null
    private val confirmDialog = ConfirmDialog(container)
    private val alertDialog = AlertDialog(container)
    private var genreSelector: GenreSelector? = null
    private var subgenreSelector: GenreSelector? = null
    private var selectedGenres: List<String> = emptyList()
    private var selectedSubgenres: List<String> = emptyList()

    /**
     * Show the form for creating a new movie.
     */
    fun showCreate() {
        editingMovie = null
        selectedGenres = emptyList()
        selectedSubgenres = emptyList()
        render()
    }

    /**
     * Show the form for creating a new movie with pre-populated data (e.g., from scraping).
     * This is used when adding a new movie after fetching data from Letterboxd.
     */
    fun showCreateWithData(scrapedMovie: MovieMetadata) {
        // Set as a new movie (no ID), but pre-populate fields
        editingMovie = scrapedMovie.copy(id = null)
        selectedGenres = scrapedMovie.genres
        selectedSubgenres = scrapedMovie.subgenres
        render()
    }

    /**
     * Show the form for editing an existing movie.
     */
    fun showEdit(movie: MovieMetadata) {
        editingMovie = movie
        selectedGenres = movie.genres
        selectedSubgenres = movie.subgenres
        render()
    }

    /**
     * Close and hide the form.
     */
    fun close() {
        val modal = document.getElementById("movie-form-modal")
        modal?.remove()
    }

    private fun render() {
        // Remove existing modal if any
        close()

        container.append {
            div {
                id = "movie-form-modal"
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

                div {
                    style = """
                        background-color: white;
                        padding: 30px;
                        border-radius: 8px;
                        max-width: 900px;
                        width: 90%;
                        max-height: 90vh;
                        overflow-y: auto;
                        box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
                    """.trimIndent()

                    h2 {
                        style = "margin-top: 0; color: #202124;"
                        // Show "Edit Movie" only when editing an existing movie (with ID)
                        // Show "Add New Movie" for new movies, even if pre-populated with scraped data
                        +if (editingMovie?.id != null) "Edit Movie" else "Add New Movie"
                    }

                    renderFormFields()

                    div {
                        style = "margin-top: 24px; display: flex; gap: 12px; justify-content: flex-end;"

                        button {
                            style = """
                                padding: 10px 24px;
                                font-size: 14px;
                                cursor: pointer;
                                background-color: #f1f3f4;
                                color: #202124;
                                border: none;
                                border-radius: 4px;
                                font-weight: 500;
                                transition: background-color 0.2s;
                            """.trimIndent()
                            attributes["onmouseover"] = "this.style.backgroundColor='#e8eaed'"
                            attributes["onmouseout"] = "this.style.backgroundColor='#f1f3f4'"
                            +"Cancel"
                            onClickFunction = {
                                close()
                                onCancel()
                            }
                        }

                        button {
                            id = "save-button"
                            style = """
                                padding: 10px 24px;
                                font-size: 14px;
                                cursor: pointer;
                                background-color: #1a73e8;
                                color: white;
                                border: none;
                                border-radius: 4px;
                                font-weight: 500;
                                transition: background-color 0.2s;
                            """.trimIndent()
                            attributes["onmouseover"] = "this.style.backgroundColor='#1765cc'"
                            attributes["onmouseout"] = "this.style.backgroundColor='#1a73e8'"
                            +"Save"
                            onClickFunction = {
                                handleSave()
                            }
                        }
                    }
                }
            }
        }

        // Initialize genre and subgenre selectors after DOM is ready
        mainScope.launch {
            initializeGenreSelectors()
        }
    }

    private suspend fun initializeGenreSelectors() {
        // Initialize genre selector
        genreSelector = GenreSelector(
            containerId = "form-genres-container",
            label = "Genres",
            type = GenreSelector.SelectorType.GENRE,
            selectedItems = selectedGenres,
            onItemsChanged = { items ->
                selectedGenres = items
            }
        )
        genreSelector?.render()

        // Initialize subgenre selector
        subgenreSelector = GenreSelector(
            containerId = "form-subgenres-container",
            label = "Subgenres",
            type = GenreSelector.SelectorType.SUBGENRE,
            selectedItems = selectedSubgenres,
            onItemsChanged = { items ->
                selectedSubgenres = items
            }
        )
        subgenreSelector?.render()
    }

    private fun DIV.renderFormFields() {
        fun inputField(labelText: String, id: String, value: String = "", placeholder: String = "", required: Boolean = true) {
            div {
                style = "margin-bottom: 16px;"
                label {
                    htmlFor = id
                    style = "display: block; margin-bottom: 6px; font-weight: 500; font-size: 14px; color: #5f6368;"
                    +labelText
                    if (required) {
                        span {
                            style = "color: #d93025;"
                            +" *"
                        }
                    }
                }
                input(type = InputType.text) {
                    this.id = id
                    this.value = value
                    this.placeholder = placeholder
                    style = """
                        width: 100%;
                        padding: 10px 12px;
                        font-size: 14px;
                        border: 1px solid #dadce0;
                        border-radius: 4px;
                        box-sizing: border-box;
                        font-family: 'Roboto', arial, sans-serif;
                    """.trimIndent()
                    attributes["onfocus"] = "this.style.borderColor='#1a73e8'"
                    attributes["onblur"] = "this.style.borderColor='#dadce0'"
                }
            }
        }

        fun textAreaField(labelText: String, id: String, value: String = "", placeholder: String = "") {
            div {
                style = "margin-bottom: 16px;"
                label {
                    htmlFor = id
                    style = "display: block; margin-bottom: 6px; font-weight: 500; font-size: 14px; color: #5f6368;"
                    +labelText
                }
                textArea {
                    this.id = id
                    this.placeholder = placeholder
                    +value
                    rows = "3"
                    style = """
                        width: 100%;
                        padding: 10px 12px;
                        font-size: 14px;
                        border: 1px solid #dadce0;
                        border-radius: 4px;
                        box-sizing: border-box;
                        font-family: 'Roboto', arial, sans-serif;
                        resize: vertical;
                    """.trimIndent()
                    attributes["onfocus"] = "this.style.borderColor='#1a73e8'"
                    attributes["onblur"] = "this.style.borderColor='#dadce0'"
                }
            }
        }

        val movie = editingMovie

        inputField("Title", "form-title", movie?.title ?: "", "Movie Title")
        textAreaField("Description (optional)", "form-description", movie?.description ?: "", "Movie synopsis or description")
        textAreaField("Alternate Titles (optional)", "form-alternate-titles", movie?.alternateTitles?.joinToString(", ") ?: "", "Other titles (comma-separated)")
        inputField("Letterboxd URL", "form-url", movie?.url ?: "", "https://letterboxd.com/film/...")

        // Genre selector placeholder
        div {
            id = "form-genres-container"
        }

        // Subgenre selector placeholder
        div {
            id = "form-subgenres-container"
        }

        textAreaField("Themes", "form-themes", movie?.themes?.joinToString(", ") ?: "", "Coming of Age, Love, Revenge (comma-separated)")
        textAreaField("Countries", "form-countries", movie?.country?.joinToString(", ") ?: "", "USA, France, Japan (comma-separated)")
        textAreaField("Cast", "form-cast", movie?.cast?.joinToString(", ") ?: "", "Actor 1, Actor 2 (comma-separated)")
        inputField("Release Year", "form-release-year", movie?.release_date?.toString() ?: "", "2024", required = false)
        inputField("Runtime", "form-runtime", formatRuntimeForInput(movie?.runtime_mins), "1h 57m or 117", required = false)

        // Physical Media Section (only show when editing existing movie)
        if (movie?.id != null) {
            h3 {
                style = "margin-top: 24px; margin-bottom: 12px; color: #5f6368; font-size: 16px; border-top: 1px solid #dadce0; padding-top: 20px;"
                +"Physical Media"
            }

            div {
                id = "physical-media-list"
                style = "margin-bottom: 16px;"

                // Render the physical media list inline
                if (movie.physicalMedia.isEmpty()) {
                    p {
                        style = "color: #5f6368; font-size: 14px; font-style: italic;"
                        +"No physical media entries yet. Click the button below to add one."
                    }
                } else {
                    table {
                        style = """
                            width: 100%;
                            border-collapse: collapse;
                            border: 1px solid #dadce0;
                            border-radius: 4px;
                            overflow: hidden;
                            font-size: 13px;
                        """.trimIndent()

                        thead {
                            style = "background-color: #f8f9fa;"
                            tr {
                                th {
                                    style = "padding: 10px 12px; text-align: left; font-weight: 600; color: #5f6368; border-bottom: 1px solid #dadce0; width: 50px;"
                                    +"Entry"
                                }
                                th {
                                    style = "padding: 10px 12px; text-align: left; font-weight: 600; color: #5f6368; border-bottom: 1px solid #dadce0;"
                                    +"Title"
                                }
                                th {
                                    style = "padding: 10px 12px; text-align: left; font-weight: 600; color: #5f6368; border-bottom: 1px solid #dadce0;"
                                    +"Media Types"
                                }
                                th {
                                    style = "padding: 10px 12px; text-align: left; font-weight: 600; color: #5f6368; border-bottom: 1px solid #dadce0;"
                                    +"Distributor"
                                }
                                th {
                                    style = "padding: 10px 12px; text-align: left; font-weight: 600; color: #5f6368; border-bottom: 1px solid #dadce0;"
                                    +"Release Date"
                                }
                                th {
                                    style = "padding: 10px 12px; text-align: left; font-weight: 600; color: #5f6368; border-bottom: 1px solid #dadce0;"
                                    +"Location"
                                }
                                th {
                                    style = "padding: 10px 12px; text-align: left; font-weight: 600; color: #5f6368; border-bottom: 1px solid #dadce0;"
                                    +"Images"
                                }
                                th {
                                    style = "padding: 10px 12px; text-align: center; font-weight: 600; color: #5f6368; border-bottom: 1px solid #dadce0; width: 140px;"
                                    +"Actions"
                                }
                            }
                        }

                        tbody {
                            movie.physicalMedia.forEach { media ->
                                tr {
                                    style = "border-bottom: 1px solid #e8eaed;"
                                    attributes["onmouseover"] = "this.style.backgroundColor='#f8f9fa'"
                                    attributes["onmouseout"] = "this.style.backgroundColor='#ffffff'"

                                    td {
                                        style = "padding: 10px 12px; font-weight: 600; color: #202124; font-size: 14px;"
                                        +(media.entryLetter ?: "—")
                                    }
                                    td {
                                        style = "padding: 10px 12px; color: #202124;"
                                        +(media.title ?: "—")
                                    }
                                    td {
                                        style = "padding: 10px 12px;"
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
                                                style = "display: inline-block; padding: 4px 10px; margin-right: 4px; margin-bottom: 4px; $badgeColor border-radius: 12px; font-size: 12px; font-weight: 500;"
                                                +mediaLabel
                                            }
                                        }
                                    }
                                    td {
                                        style = "padding: 10px 12px; color: #5f6368;"
                                        +(media.distributor ?: "—")
                                    }
                                    td {
                                        style = "padding: 10px 12px; color: #5f6368;"
                                        +(media.releaseDate ?: "—")
                                    }
                                    td {
                                        style = "padding: 10px 12px; color: #5f6368;"
                                        +(media.location ?: "—")
                                    }
                                    td {
                                        style = "padding: 10px 12px; color: #5f6368;"
                                        if (media.images.isNotEmpty()) {
                                            val mediaImages = media.images
                                            span {
                                                style = """
                                                    cursor: pointer;
                                                    color: #1a73e8;
                                                    text-decoration: none;
                                                """.trimIndent()
                                                attributes["onmouseover"] = "this.style.textDecoration='underline'"
                                                attributes["onmouseout"] = "this.style.textDecoration='none'"
                                                +"${media.images.size} image${if (media.images.size != 1) "s" else ""}"
                                                onClickFunction = {
                                                    ImageLightbox.show(mediaImages, 0)
                                                }
                                            }
                                        } else {
                                            +"0 images"
                                        }
                                    }
                                    td {
                                        style = "padding: 10px 12px; text-align: center;"
                                        button {
                                            style = """
                                                padding: 6px 12px;
                                                margin-right: 6px;
                                                font-size: 12px;
                                                cursor: pointer;
                                                background-color: #ffffff;
                                                color: #1a73e8;
                                                border: 1px solid #dadce0;
                                                border-radius: 3px;
                                                font-weight: 500;
                                                transition: background-color 0.2s;
                                            """.trimIndent()
                                            attributes["onmouseover"] = "this.style.backgroundColor='#f8f9fa'"
                                            attributes["onmouseout"] = "this.style.backgroundColor='#ffffff'"
                                            +"Edit"
                                            onClickFunction = {
                                                if (media.id != null) {
                                                    showEditPhysicalMediaForm(movie.id, media)
                                                }
                                            }
                                        }
                                        button {
                                            style = """
                                                padding: 6px 12px;
                                                font-size: 12px;
                                                cursor: pointer;
                                                background-color: #ffffff;
                                                color: #d93025;
                                                border: 1px solid #dadce0;
                                                border-radius: 3px;
                                                font-weight: 500;
                                                transition: background-color 0.2s;
                                            """.trimIndent()
                                            attributes["onmouseover"] = "this.style.backgroundColor='#fce8e6'"
                                            attributes["onmouseout"] = "this.style.backgroundColor='#ffffff'"
                                            +"Delete"
                                            onClickFunction = {
                                                if (media.id != null) {
                                                    handleDeletePhysicalMedia(media)
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

            button {
                id = "add-physical-media-button"
                style = """
                    padding: 8px 16px;
                    font-size: 14px;
                    cursor: pointer;
                    background-color: #34a853;
                    color: white;
                    border: none;
                    border-radius: 4px;
                    font-weight: 500;
                    transition: background-color 0.2s;
                """.trimIndent()
                attributes["onmouseover"] = "this.style.backgroundColor='#2d8e47'"
                attributes["onmouseout"] = "this.style.backgroundColor='#34a853'"
                +"+ Add Physical Media"
                onClickFunction = {
                    showAddPhysicalMediaForm(movie.id)
                }
            }
        }

        // Watched Entries Section (only show when editing existing movie)
        if (movie != null && movie.id != null) {
            h3 {
                style = "margin-top: 32px; margin-bottom: 16px; color: #5f6368; font-size: 18px;"
                +"Watched Entries"
            }

            div {
                id = "watched-entries-list"
                style = "margin-bottom: 16px;"

                if (movie.watchedEntries.isEmpty()) {
                    p {
                        style = "color: #5f6368; font-size: 14px;"
                        +"No watched entries yet. Click the button below to record a viewing."
                    }
                } else {
                    table {
                        style = "width: 100%; border-collapse: collapse; margin-bottom: 16px;"
                        thead {
                            style = "background-color: #f8f9fa;"
                            tr {
                                th { style = "padding: 8px; text-align: left; border: 1px solid #dadce0; font-size: 13px; color: #5f6368;"; +"Date" }
                                th { style = "padding: 8px; text-align: left; border: 1px solid #dadce0; font-size: 13px; color: #5f6368;"; +"Rating" }
                                th { style = "padding: 8px; text-align: left; border: 1px solid #dadce0; font-size: 13px; color: #5f6368;"; +"Notes" }
                                th { style = "padding: 8px; text-align: center; border: 1px solid #dadce0; font-size: 13px; color: #5f6368; width: 140px;"; +"Actions" }
                            }
                        }
                        tbody {
                            movie.watchedEntries.sortedByDescending { it.watchedDate }.forEach { entry ->
                                tr {
                                    td {
                                        style = "padding: 8px; border: 1px solid #dadce0; font-size: 13px;"
                                        +entry.watchedDate
                                    }
                                    td {
                                        style = "padding: 8px; border: 1px solid #dadce0; font-size: 13px;"
                                        if (entry.rating != null) {
                                            span {
                                                style = "padding: 2px 8px; background-color: #e8f0fe; color: #1967d2; border-radius: 12px; font-weight: 500;"
                                                +"${entry.rating}/5"
                                            }
                                        } else {
                                            +"—"
                                        }
                                    }
                                    td {
                                        style = "padding: 8px; border: 1px solid #dadce0; font-size: 13px; color: #5f6368;"
                                        +(entry.notes?.take(50)?.let { if (entry.notes.length > 50) "$it..." else it } ?: "—")
                                    }
                                    td {
                                        style = "padding: 8px; border: 1px solid #dadce0; text-align: center;"
                                        button {
                                            style = "padding: 4px 12px; margin-right: 4px; font-size: 12px; background-color: #fff; color: #1a73e8; border: 1px solid #dadce0; border-radius: 3px; cursor: pointer;"
                                            +"Edit"
                                            onClickFunction = {
                                                showEditWatchedEntryForm(entry)
                                            }
                                        }
                                        button {
                                            style = "padding: 4px 12px; font-size: 12px; background-color: #fff; color: #d93025; border: 1px solid #dadce0; border-radius: 3px; cursor: pointer;"
                                            +"Delete"
                                            onClickFunction = {
                                                handleDeleteWatchedEntry(entry)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            button {
                style = """
                    padding: 8px 16px;
                    font-size: 14px;
                    cursor: pointer;
                    background-color: #34a853;
                    color: white;
                    border: none;
                    border-radius: 4px;
                    font-weight: 500;
                    transition: background-color 0.2s;
                """.trimIndent()
                attributes["onmouseover"] = "this.style.backgroundColor='#2d8e47'"
                attributes["onmouseout"] = "this.style.backgroundColor='#34a853'"
                +"+ Add Watched Entry"
                onClickFunction = {
                    showAddWatchedEntryForm(movie.id)
                }
            }
        }
    }

    private fun handleSave() {
        try {
            val title = (document.getElementById("form-title") as HTMLInputElement).value.trim()
            val url = (document.getElementById("form-url") as HTMLInputElement).value.trim()

            if (title.isBlank() || url.isBlank()) {
                alertDialog.show(
                    title = "Validation Error",
                    message = "Title and URL are required fields!"
                )
                return
            }

            val description = (document.getElementById("form-description") as HTMLTextAreaElement).value.trim()
                .takeIf { it.isNotBlank() }

            val alternateTitles = (document.getElementById("form-alternate-titles") as HTMLTextAreaElement).value
                .split(",").map { it.trim() }.filter { it.isNotBlank() }

            // Get genres and subgenres from the selectors
            val genres = selectedGenres
            val subgenres = selectedSubgenres

            val themes = (document.getElementById("form-themes") as HTMLTextAreaElement).value
                .split(",").map { it.trim() }.filter { it.isNotBlank() }
            val countries = (document.getElementById("form-countries") as HTMLTextAreaElement).value
                .split(",").map { it.trim() }.filter { it.isNotBlank() }
            val cast = (document.getElementById("form-cast") as HTMLTextAreaElement).value
                .split(",").map { it.trim() }.filter { it.isNotBlank() }

            val releaseYearStr = (document.getElementById("form-release-year") as HTMLInputElement).value.trim()
            val releaseYear = if (releaseYearStr.isNotBlank()) releaseYearStr.toIntOrNull() else null

            val runtimeStr = (document.getElementById("form-runtime") as HTMLInputElement).value.trim()
            val runtime = if (runtimeStr.isNotBlank()) parseRuntime(runtimeStr) else null

            val movie = MovieMetadata(
                url = url,
                title = title,
                description = description,
                alternateTitles = alternateTitles,
                genres = genres,
                subgenres = subgenres,
                themes = themes,
                country = countries,
                cast = cast,
                crew = editingMovie?.crew ?: emptyMap(), // Keep existing crew or empty
                release_date = releaseYear,
                runtime_mins = runtime,
                id = editingMovie?.id
            )

            mainScope.launch {
                try {
                    onSave(movie)
                    close()
                } catch (e: Exception) {
                    alertDialog.show(
                        title = "Error",
                        message = "Error saving movie: ${e.message}"
                    )
                }
            }
        } catch (e: Exception) {
            alertDialog.show(
                title = "Error",
                message = "Error processing form: ${e.message}"
            )
        }
    }


    private fun showAddPhysicalMediaForm(movieId: Int) {
        val physicalMediaForm = PhysicalMediaForm(container, onSave = { physicalMedia ->
            createPhysicalMediaEntry(movieId, physicalMedia)
        }, onCancel = {})
        physicalMediaForm.showCreate()
    }

    private fun showEditPhysicalMediaForm(movieId: Int, media: PhysicalMedia) {
        val physicalMediaForm = PhysicalMediaForm(container, onSave = { updatedMedia ->
            updatePhysicalMediaEntry(media.id!!, updatedMedia)
        }, onCancel = {})
        physicalMediaForm.showEdit(media)
    }

    private fun handleDeletePhysicalMedia(media: PhysicalMedia) {
        val mediaLabels = media.mediaTypes.map { type ->
            when (type) {
                MediaType.VHS -> "VHS"
                MediaType.DVD -> "DVD"
                MediaType.BLURAY -> "Blu-ray"
                MediaType.FOURK -> "4K"
                MediaType.DIGITAL -> "Digital"
            }
        }.joinToString(" + ")

        val entryLabel = if (media.entryLetter != null) {
            "Entry ${media.entryLetter} ($mediaLabels)"
        } else {
            mediaLabels
        }

        if (media.id != null) {
            confirmDialog.show(
                title = "Delete Physical Media",
                message = "Are you sure you want to delete $entryLabel?\n\nThis action cannot be undone.",
                confirmText = "Delete",
                cancelText = "Cancel",
                onConfirm = {
                    deletePhysicalMediaEntry(media.id)
                }
            )
        }
    }

    private suspend fun createPhysicalMediaEntry(movieId: Int, physicalMedia: PhysicalMedia) {
        try {
            createPhysicalMedia(movieId, physicalMedia)
            // Refresh the movie data to show updated physical media
            val updatedMovie = editingMovie?.id?.let { getMovieById(it) }
            if (updatedMovie != null) {
                editingMovie = updatedMovie
                render()
            }
            alertDialog.show(
                title = "Success",
                message = "Physical media added successfully!"
            )
        } catch (e: Exception) {
            alertDialog.show(
                title = "Error",
                message = "Failed to add physical media: ${e.message}"
            )
            throw e
        }
    }

    private suspend fun updatePhysicalMediaEntry(id: Int, physicalMedia: PhysicalMedia) {
        try {
            updatePhysicalMedia(id, physicalMedia)
            // Refresh the movie data to show updated physical media
            val updatedMovie = editingMovie?.id?.let { getMovieById(it) }
            if (updatedMovie != null) {
                editingMovie = updatedMovie
                render()
            }
            alertDialog.show(
                title = "Success",
                message = "Physical media updated successfully!"
            )
        } catch (e: Exception) {
            alertDialog.show(
                title = "Error",
                message = "Failed to update physical media: ${e.message}"
            )
            throw e
        }
    }

    private fun deletePhysicalMediaEntry(id: Int) {
        mainScope.launch {
            try {
                val success = deletePhysicalMedia(id)
                if (success) {
                    // Refresh the movie data to show updated physical media
                    val updatedMovie = editingMovie?.id?.let { getMovieById(it) }
                    if (updatedMovie != null) {
                        editingMovie = updatedMovie
                        render()
                    }
                    alertDialog.show(
                        title = "Success",
                        message = "Physical media deleted successfully!"
                    )
                } else {
                    alertDialog.show(
                        title = "Error",
                        message = "Failed to delete physical media"
                    )
                }
            } catch (e: Exception) {
                alertDialog.show(
                    title = "Error",
                    message = "Error deleting physical media: ${e.message}"
                )
            }
        }
    }

    // Watched Entry functions
    private fun showAddWatchedEntryForm(movieId: Int) {
        val form = WatchedForm(container, onSave = { watchedEntry ->
            createWatchedEntryForMovie(movieId, watchedEntry)
        }, onCancel = {})
        form.showCreate()
    }

    private fun showEditWatchedEntryForm(entry: WatchedEntry) {
        val form = WatchedForm(container, onSave = { watchedEntry ->
            if (entry.id != null) {
                updateWatchedEntryForMovie(entry.id, watchedEntry)
            }
        }, onCancel = {})
        form.showEdit(entry)
    }

    private fun handleDeleteWatchedEntry(entry: WatchedEntry) {
        if (entry.id != null) {
            confirmDialog.show(
                title = "Delete Watched Entry",
                message = "Are you sure you want to delete this watched entry from ${entry.watchedDate}?\n\nThis action cannot be undone.",
                confirmText = "Delete",
                cancelText = "Cancel",
                onConfirm = {
                    deleteWatchedEntryFromMovie(entry.id)
                }
            )
        }
    }

    private suspend fun createWatchedEntryForMovie(movieId: Int, watchedEntry: WatchedEntry) {
        try {
            createWatchedEntry(movieId, watchedEntry)
            // Refresh the movie data
            val updatedMovie = editingMovie?.id?.let { getMovieById(it) }
            if (updatedMovie != null) {
                editingMovie = updatedMovie
                render()
            }
            alertDialog.show(
                title = "Success",
                message = "Watched entry added successfully!"
            )
        } catch (e: Exception) {
            alertDialog.show(
                title = "Error",
                message = "Failed to add watched entry: ${e.message}"
            )
            throw e
        }
    }

    private suspend fun updateWatchedEntryForMovie(id: Int, watchedEntry: WatchedEntry) {
        try {
            updateWatchedEntry(id, watchedEntry)
            // Refresh the movie data
            val updatedMovie = editingMovie?.id?.let { getMovieById(it) }
            if (updatedMovie != null) {
                editingMovie = updatedMovie
                render()
            }
            alertDialog.show(
                title = "Success",
                message = "Watched entry updated successfully!"
            )
        } catch (e: Exception) {
            alertDialog.show(
                title = "Error",
                message = "Failed to update watched entry: ${e.message}"
            )
            throw e
        }
    }

    private fun deleteWatchedEntryFromMovie(id: Int) {
        mainScope.launch {
            try {
                val success = deleteWatchedEntry(id)
                if (success) {
                    // Refresh the movie data
                    val updatedMovie = editingMovie?.id?.let { getMovieById(it) }
                    if (updatedMovie != null) {
                        editingMovie = updatedMovie
                        render()
                    }
                    alertDialog.show(
                        title = "Success",
                        message = "Watched entry deleted successfully!"
                    )
                } else {
                    alertDialog.show(
                        title = "Error",
                        message = "Failed to delete watched entry"
                    )
                }
            } catch (e: Exception) {
                alertDialog.show(
                    title = "Error",
                    message = "Error deleting watched entry: ${e.message}"
                )
            }
        }
    }

    private suspend fun getMovieById(id: Int): MovieMetadata? {
        return try {
            val response = kotlinx.browser.window.fetch("$API_BASE_URL/movies/$id").await()
            if (!response.ok) return null
            val jsonText = response.text().await()
            kotlinx.serialization.json.Json.decodeFromString(MovieMetadata.serializer(), jsonText)
        } catch (e: Exception) {
            console.error("Error fetching movie:", e)
            null
        }
    }

    /**
     * Parses a runtime string into total minutes.
     * Supports formats: "1h 57m", "1h57m", "2h", "90m", "117", etc.
     */
    private fun parseRuntime(input: String): Int? {
        val trimmed = input.trim().lowercase()
        
        // Try parsing as plain number (minutes)
        trimmed.toIntOrNull()?.let { return it }
        
        // Parse hours and minutes format
        var totalMinutes = 0
        
        // Match hours: "1h", "2h", etc.
        val hoursRegex = """(\d+)\s*h""".toRegex()
        hoursRegex.find(trimmed)?.let { match ->
            totalMinutes += match.groupValues[1].toInt() * 60
        }
        
        // Match minutes: "57m", "30m", etc.
        val minutesRegex = """(\d+)\s*m""".toRegex()
        minutesRegex.find(trimmed)?.let { match ->
            totalMinutes += match.groupValues[1].toInt()
        }
        
        return if (totalMinutes > 0) totalMinutes else null
    }

    /**
     * Formats runtime minutes for display in the input field.
     * Returns "Xh Ym" format or empty string if null.
     */
    private fun formatRuntimeForInput(minutes: Int?): String {
        if (minutes == null) return ""
        val hours = minutes / 60
        val mins = minutes % 60
        return when {
            hours > 0 && mins > 0 -> "${hours}h ${mins}m"
            hours > 0 -> "${hours}h"
            else -> "${mins}m"
        }
    }
}
