package org.btmonier

import kotlinx.browser.document
import kotlinx.coroutines.launch
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.onChangeFunction
import kotlinx.html.js.onClickFunction
import kotlinx.html.js.onInputFunction
import org.w3c.dom.*

class MovieTable(private val container: Element) {
    // Filter state
    private var searchText = ""
    private var selectedGenre: String? = null
    private var selectedCountry: String? = null
    private var selectedMediaType: String? = null

    // Pagination state (server-provided)
    private var currentPage = 1
    private var itemsPerPage = 25
    private var totalCount = 0
    private var totalPages = 1

    // Current page data
    private var currentMovies: List<MovieMetadata> = emptyList()

    // Filter options (loaded from API)
    private var allGenres: List<String> = emptyList()
    private var allCountries: List<String> = emptyList()
    private val allMediaTypes = listOf("VHS", "DVD", "Blu-ray", "4K", "Digital")

    // Loading state
    private var isLoading = false

    private val metadataModal = MovieMetadataModal(container) {
        // onClose callback - nothing special needed
    }

    private val confirmDialog = ConfirmDialog(container)
    private val alertDialog = AlertDialog(container)

    fun render() {
        container.innerHTML = ""
        container.append {
            div {
                style = "max-width: 1400px; margin: 0 auto; padding: 20px; font-family: 'Google Sans', 'Roboto', arial, sans-serif; background-color: #ffffff;"

                div {
                    style = "display: flex; justify-content: space-between; align-items: center; margin-bottom: 30px;"
                    h1 {
                        style = "color: #202124; font-weight: 400; font-size: 28px; margin: 0;"
                        +"The Movie Omnibus"
                    }
                    div {
                        style = "display: flex; gap: 12px;"
                        button {
                            style = """
                                padding: 10px 24px;
                                font-size: 14px;
                                cursor: pointer;
                                background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                                color: white;
                                border: none;
                                border-radius: 4px;
                                font-weight: 500;
                                transition: transform 0.2s, box-shadow 0.2s;
                                box-shadow: 0 2px 8px rgba(102, 126, 234, 0.3);
                            """.trimIndent()
                            attributes["onmouseover"] = "this.style.transform='scale(1.02)'; this.style.boxShadow='0 4px 12px rgba(102, 126, 234, 0.4)'"
                            attributes["onmouseout"] = "this.style.transform='scale(1)'; this.style.boxShadow='0 2px 8px rgba(102, 126, 234, 0.3)'"
                            +"🎲 Random Pick"
                            onClickFunction = {
                                showRandomPicker()
                            }
                        }
                        button {
                            style = """
                                padding: 10px 24px;
                                font-size: 14px;
                                cursor: pointer;
                                background-color: #ffffff;
                                color: #1a73e8;
                                border: 1px solid #dadce0;
                                border-radius: 4px;
                                font-weight: 500;
                                transition: background-color 0.2s;
                            """.trimIndent()
                            attributes["onmouseover"] = "this.style.backgroundColor='#f8f9fa'"
                            attributes["onmouseout"] = "this.style.backgroundColor='#ffffff'"
                            +"⚙ Manage Genres"
                            onClickFunction = {
                                showGenreManagement()
                            }
                        }
                        button {
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
                            +"+ Add Movie"
                            onClickFunction = {
                                showAddMovieForm()
                            }
                        }
                    }
                }

                // Filters container (will be populated after loading options)
                div {
                    id = "filters-container"
                    style = "margin-bottom: 24px;"
                }

                // Results count
                div {
                    id = "results-count"
                    style = "margin-bottom: 15px; font-size: 14px; color: #666;"
                }

                // Table container
                div {
                    id = "table-container"
                    style = "overflow-x: auto;"
                }

                // Footer
                appFooter()
            }
        }

        // Load filter options and initial data
        mainScope.launch {
            loadFilterOptions()
            renderFilters()
            loadMovies()
        }
    }

    private suspend fun loadFilterOptions() {
        try {
            allGenres = fetchGenreOptions()
            allCountries = fetchAllCountries()
        } catch (e: Exception) {
            console.error("Failed to load filter options:", e)
        }
    }

    private fun renderFilters() {
        val filtersContainer = document.getElementById("filters-container") ?: return
        filtersContainer.innerHTML = ""
        filtersContainer.append {
            div {
                style = "padding: 24px; background-color: #f8f9fa; border: 1px solid #dadce0; border-radius: 8px; display: grid; grid-template-columns: 1fr 1fr 1fr 1fr; gap: 20px;"

                // Search input
                div {
                    label {
                        htmlFor = "search-input"
                        style = "display: block; margin-bottom: 8px; font-weight: 500; font-size: 14px; color: #5f6368;"
                        +"Search by Title"
                    }
                    div {
                        style = "display: flex; gap: 8px;"
                        input(type = InputType.text) {
                            id = "search-input"
                            style = "flex: 1; padding: 10px 12px; font-size: 14px; border: 1px solid #dadce0; border-radius: 4px; box-sizing: border-box; font-family: 'Roboto', arial, sans-serif; transition: border-color 0.2s;"
                            placeholder = "Search movies..."
                            value = searchText
                            attributes["onfocus"] = "this.style.borderColor='#1a73e8'; this.style.outline='none'"
                            attributes["onblur"] = "this.style.borderColor='#dadce0'"
                            onInputFunction = { event ->
                                searchText = (event.target as HTMLInputElement).value
                            }
                            onChangeFunction = {
                                currentPage = 1
                                mainScope.launch { loadMovies() }
                            }
                        }
                        button {
                            id = "reset-filters-button"
                            style = """
                                padding: 10px 16px;
                                font-size: 14px;
                                cursor: pointer;
                                background-color: #f1f3f4;
                                color: #5f6368;
                                border: 1px solid #dadce0;
                                border-radius: 4px;
                                font-weight: 500;
                                transition: background-color 0.2s;
                                white-space: nowrap;
                            """.trimIndent()
                            attributes["onmouseover"] = "this.style.backgroundColor='#e8eaed'"
                            attributes["onmouseout"] = "this.style.backgroundColor='#f1f3f4'"
                            +"Reset All"
                            onClickFunction = {
                                resetAllFilters()
                            }
                        }
                    }
                }

                // Genre filter (single select for server-side filtering)
                div {
                    label {
                        htmlFor = "genre-select"
                        style = "display: block; margin-bottom: 8px; font-weight: 500; font-size: 14px; color: #5f6368;"
                        +"Filter by Genre"
                    }
                    select {
                        id = "genre-select"
                        style = "width: 100%; padding: 10px 12px; font-size: 14px; border: 1px solid #dadce0; border-radius: 4px; box-sizing: border-box; font-family: 'Roboto', arial, sans-serif; background-color: #ffffff;"
                        onChangeFunction = { event ->
                            val select = event.target as HTMLSelectElement
                            selectedGenre = select.value.takeIf { it.isNotBlank() }
                            currentPage = 1
                            mainScope.launch { loadMovies() }
                        }

                        option {
                            value = ""
                            selected = selectedGenre == null
                            +"All Genres"
                        }
                        allGenres.forEach { genre ->
                            option {
                                value = genre
                                selected = selectedGenre == genre
                                +genre
                            }
                        }
                    }
                }

                // Country filter (single select for server-side filtering)
                div {
                    label {
                        htmlFor = "country-select"
                        style = "display: block; margin-bottom: 8px; font-weight: 500; font-size: 14px; color: #5f6368;"
                        +"Filter by Country"
                    }
                    select {
                        id = "country-select"
                        style = "width: 100%; padding: 10px 12px; font-size: 14px; border: 1px solid #dadce0; border-radius: 4px; box-sizing: border-box; font-family: 'Roboto', arial, sans-serif; background-color: #ffffff;"
                        onChangeFunction = { event ->
                            val select = event.target as HTMLSelectElement
                            selectedCountry = select.value.takeIf { it.isNotBlank() }
                            currentPage = 1
                            mainScope.launch { loadMovies() }
                        }

                        option {
                            value = ""
                            selected = selectedCountry == null
                            +"All Countries"
                        }
                        allCountries.forEach { country ->
                            option {
                                value = country
                                selected = selectedCountry == country
                                +country
                            }
                        }
                    }
                }

                // Media Type filter (single select for server-side filtering)
                div {
                    label {
                        htmlFor = "media-type-select"
                        style = "display: block; margin-bottom: 8px; font-weight: 500; font-size: 14px; color: #5f6368;"
                        +"Filter by Media Type"
                    }
                    select {
                        id = "media-type-select"
                        style = "width: 100%; padding: 10px 12px; font-size: 14px; border: 1px solid #dadce0; border-radius: 4px; box-sizing: border-box; font-family: 'Roboto', arial, sans-serif; background-color: #ffffff;"
                        onChangeFunction = { event ->
                            val select = event.target as HTMLSelectElement
                            selectedMediaType = select.value.takeIf { it.isNotBlank() }
                            currentPage = 1
                            mainScope.launch { loadMovies() }
                        }

                        option {
                            value = ""
                            selected = selectedMediaType == null
                            +"All Media Types"
                        }
                        allMediaTypes.forEach { mediaType ->
                            option {
                                value = mediaType
                                selected = selectedMediaType == mediaType
                                +mediaType
                            }
                        }
                    }
                }
            }
        }
    }

    private fun resetAllFilters() {
        searchText = ""
        selectedGenre = null
        selectedCountry = null
        selectedMediaType = null
        currentPage = 1

        // Update UI
        (document.getElementById("search-input") as? HTMLInputElement)?.value = ""
        (document.getElementById("genre-select") as? HTMLSelectElement)?.value = ""
        (document.getElementById("country-select") as? HTMLSelectElement)?.value = ""
        (document.getElementById("media-type-select") as? HTMLSelectElement)?.value = ""

        mainScope.launch { loadMovies() }
    }

    private suspend fun loadMovies() {
        if (isLoading) return
        isLoading = true

        showLoadingState()

        try {
            val response = fetchMoviesPaginated(
                page = currentPage,
                pageSize = itemsPerPage,
                search = searchText.takeIf { it.isNotBlank() },
                genre = selectedGenre,
                country = selectedCountry,
                mediaType = selectedMediaType
            )

            currentMovies = response.movies
            totalCount = response.totalCount
            totalPages = response.totalPages
            currentPage = response.page

            renderTable()
        } catch (e: Exception) {
            console.error("Failed to load movies:", e)
            showErrorState("Failed to load movies: ${e.message}")
        } finally {
            isLoading = false
        }
    }

    private fun showLoadingState() {
        val tableContainer = document.getElementById("table-container") ?: return
        tableContainer.innerHTML = ""
        tableContainer.append {
            div {
                style = "text-align: center; padding: 40px; color: #5f6368;"
                +"Loading movies..."
            }
        }
    }

    private fun showErrorState(message: String) {
        val tableContainer = document.getElementById("table-container") ?: return
        tableContainer.innerHTML = ""
        tableContainer.append {
            div {
                style = "text-align: center; padding: 40px; color: #d93025; background-color: #fce8e6; border-radius: 8px;"
                +message
            }
        }
    }

    private fun renderTable() {
        // Update count
        val countDiv = document.getElementById("results-count")
        val startIndex = (currentPage - 1) * itemsPerPage + 1
        val endIndex = kotlin.math.min(currentPage * itemsPerPage, totalCount)
        if (totalCount > 0) {
            countDiv?.textContent = "Showing $startIndex-$endIndex of $totalCount movies"
        } else {
            countDiv?.textContent = "No movies found"
        }
        countDiv?.setAttribute("style", "margin-bottom: 16px; font-size: 14px; color: #5f6368; font-family: 'Roboto', arial, sans-serif;")

        // Update table
        val tableContainer = document.getElementById("table-container") ?: return
        tableContainer.innerHTML = ""
        tableContainer.append {
            if (currentMovies.isEmpty()) {
                div {
                    style = "text-align: center; padding: 40px; color: #5f6368; background-color: #f8f9fa; border-radius: 8px;"
                    +"No movies match your search criteria."
                }
            } else {
                table {
                    style = "width: 100%; border-collapse: collapse; background-color: white; border: 1px solid #dadce0; border-radius: 8px; overflow: hidden; box-shadow: 0 1px 2px 0 rgba(60,64,67,0.3), 0 1px 3px 1px rgba(60,64,67,0.15);"

                    thead {
                        style = "background-color: #f8f9fa; color: #202124; border-bottom: 1px solid #dadce0;"
                        tr {
                            th {
                                style = "padding: 12px 16px; text-align: left; font-weight: 500; font-size: 14px; color: #5f6368;"
                                +"Title"
                            }
                            th {
                                style = "padding: 12px 16px; text-align: left; font-weight: 500; font-size: 14px; color: #5f6368;"
                                +"Physical Media"
                            }
                            th {
                                style = "padding: 12px 16px; text-align: center; font-weight: 500; font-size: 14px; color: #5f6368; width: 180px;"
                                +"Actions"
                            }
                        }
                    }

                    tbody {
                        currentMovies.forEach { movie ->
                            tr {
                                style = "border-bottom: 1px solid #e8eaed;"
                                attributes["onmouseover"] = "this.style.backgroundColor='#f8f9fa'"
                                attributes["onmouseout"] = "this.style.backgroundColor='#ffffff'"

                                td {
                                    style = "padding: 12px 16px; cursor: pointer;"
                                    onClickFunction = {
                                        metadataModal.show(movie)
                                    }

                                    div {
                                        style = "color: #1a73e8; font-size: 14px; margin-bottom: 4px;"
                                        attributes["onmouseover"] = "this.style.textDecoration='underline'"
                                        attributes["onmouseout"] = "this.style.textDecoration='none'"
                                        +movie.title
                                    }
                                }
                                td {
                                    style = "padding: 12px 16px;"
                                    movie.physicalMedia.forEach { media ->
                                        if (media.entryLetter != null) {
                                            span {
                                                style = "display: inline-block; padding: 4px 8px; margin-right: 4px; margin-bottom: 4px; background-color: #f1f3f4; color: #202124; border-radius: 4px; font-size: 11px; font-weight: 600; font-family: 'Roboto', arial, sans-serif;"
                                                +media.entryLetter
                                            }
                                        }
                                        if (media.title != null) {
                                            span {
                                                style = "display: inline-block; padding: 4px 8px; margin-right: 4px; margin-bottom: 4px; background-color: #e8f0fe; color: #1967d2; border-radius: 4px; font-size: 11px; font-weight: 500; font-family: 'Roboto', arial, sans-serif;"
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
                                                style = "display: inline-block; padding: 4px 10px; margin-right: 6px; margin-bottom: 4px; $badgeColor border-radius: 12px; font-size: 12px; font-weight: 500; font-family: 'Roboto', arial, sans-serif;"
                                                +mediaLabel
                                            }
                                        }
                                    }
                                }
                                td {
                                    style = "padding: 12px 16px; text-align: center;"
                                    button {
                                        style = """
                                            padding: 6px 16px;
                                            margin-right: 8px;
                                            font-size: 13px;
                                            cursor: pointer;
                                            background-color: #ffffff;
                                            color: #1a73e8;
                                            border: 1px solid #dadce0;
                                            border-radius: 4px;
                                            font-weight: 500;
                                            transition: background-color 0.2s;
                                        """.trimIndent()
                                        attributes["onmouseover"] = "this.style.backgroundColor='#f8f9fa'"
                                        attributes["onmouseout"] = "this.style.backgroundColor='#ffffff'"
                                        +"Edit"
                                        onClickFunction = {
                                            showEditMovieForm(movie)
                                        }
                                    }
                                    button {
                                        style = """
                                            padding: 6px 16px;
                                            font-size: 13px;
                                            cursor: pointer;
                                            background-color: #ffffff;
                                            color: #d93025;
                                            border: 1px solid #dadce0;
                                            border-radius: 4px;
                                            font-weight: 500;
                                            transition: background-color 0.2s;
                                        """.trimIndent()
                                        attributes["onmouseover"] = "this.style.backgroundColor='#fce8e6'"
                                        attributes["onmouseout"] = "this.style.backgroundColor='#ffffff'"
                                        +"Delete"
                                        onClickFunction = {
                                            handleDeleteMovie(movie)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Pagination controls
            if (totalPages > 1 || totalCount > 0) {
                div {
                    style = "margin-top: 20px; display: flex; justify-content: center; align-items: center; gap: 8px;"

                    // Previous button
                    button {
                        style = """
                            padding: 8px 16px;
                            font-size: 14px;
                            cursor: ${if (currentPage > 1) "pointer" else "not-allowed"};
                            background-color: ${if (currentPage > 1) "#1a73e8" else "#dadce0"};
                            color: ${if (currentPage > 1) "white" else "#5f6368"};
                            border: none;
                            border-radius: 4px;
                            font-weight: 500;
                            transition: background-color 0.2s;
                        """.trimIndent()
                        disabled = (currentPage <= 1)
                        if (currentPage > 1) {
                            attributes["onmouseover"] = "this.style.backgroundColor='#1765cc'"
                            attributes["onmouseout"] = "this.style.backgroundColor='#1a73e8'"
                        }
                        +"← Previous"
                        onClickFunction = {
                            if (currentPage > 1) {
                                currentPage--
                                mainScope.launch { loadMovies() }
                            }
                        }
                    }

                    // Page info
                    span {
                        style = "padding: 8px 16px; font-size: 14px; color: #5f6368;"
                        +"Page $currentPage of $totalPages"
                    }

                    // Next button
                    button {
                        style = """
                            padding: 8px 16px;
                            font-size: 14px;
                            cursor: ${if (currentPage < totalPages) "pointer" else "not-allowed"};
                            background-color: ${if (currentPage < totalPages) "#1a73e8" else "#dadce0"};
                            color: ${if (currentPage < totalPages) "white" else "#5f6368"};
                            border: none;
                            border-radius: 4px;
                            font-weight: 500;
                            transition: background-color 0.2s;
                        """.trimIndent()
                        disabled = (currentPage >= totalPages)
                        if (currentPage < totalPages) {
                            attributes["onmouseover"] = "this.style.backgroundColor='#1765cc'"
                            attributes["onmouseout"] = "this.style.backgroundColor='#1a73e8'"
                        }
                        +"Next →"
                        onClickFunction = {
                            if (currentPage < totalPages) {
                                currentPage++
                                mainScope.launch { loadMovies() }
                            }
                        }
                    }

                    // Items per page selector
                    div {
                        style = "margin-left: 24px; display: flex; align-items: center; gap: 8px;"
                        span {
                            style = "font-size: 14px; color: #5f6368;"
                            +"Items per page:"
                        }
                        select {
                            style = """
                                padding: 6px 12px;
                                font-size: 14px;
                                border: 1px solid #dadce0;
                                border-radius: 4px;
                                background-color: white;
                                cursor: pointer;
                            """.trimIndent()

                            listOf(10, 25, 50, 100).forEach { size ->
                                option {
                                    value = size.toString()
                                    selected = (itemsPerPage == size)
                                    +size.toString()
                                }
                            }

                            onChangeFunction = {
                                val select = it.target as? HTMLSelectElement
                                select?.value?.toIntOrNull()?.let { newSize ->
                                    itemsPerPage = newSize
                                    currentPage = 1
                                    mainScope.launch { loadMovies() }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showAddMovieForm() {
        val urlForm = LetterboxdUrlForm(
            container = container,
            onScrapedMovie = { scrapedMovie ->
                val form = MovieForm(container, onSave = { movie ->
                    createMovie(movie)
                }, onCancel = {})

                if (scrapedMovie.url.isNotBlank()) {
                    form.showCreateWithData(scrapedMovie)
                } else {
                    form.showCreate()
                }
            },
            onCancel = {}
        )
        urlForm.show()
    }

    private fun showEditMovieForm(movie: MovieMetadata) {
        val form = MovieForm(container, onSave = { updatedMovie ->
            updateExistingMovie(updatedMovie)
        }, onCancel = {})
        form.showEdit(movie)
    }

    private fun handleDeleteMovie(movie: MovieMetadata) {
        confirmDialog.show(
            title = "Delete Movie",
            message = "Are you sure you want to delete \"${movie.title}\"?\n\nThis action cannot be undone.",
            confirmText = "Delete",
            cancelText = "Cancel",
            onConfirm = {
                deleteExistingMovie(movie)
            }
        )
    }

    private suspend fun createMovie(movie: MovieMetadata) {
        try {
            org.btmonier.createMovie(movie)
            // Reload current page to show updated data
            loadFilterOptions()
            renderFilters()
            loadMovies()
            alertDialog.show(
                title = "Success",
                message = "Movie created successfully!"
            )
        } catch (e: Exception) {
            alertDialog.show(
                title = "Error",
                message = "Failed to create movie: ${e.message}"
            )
            throw e
        }
    }

    private suspend fun updateExistingMovie(movie: MovieMetadata) {
        if (movie.id == null) {
            throw Exception("Cannot update movie without ID")
        }
        try {
            org.btmonier.updateMovie(movie.id, movie)
            // Reload current page to show updated data
            loadFilterOptions()
            renderFilters()
            loadMovies()
            alertDialog.show(
                title = "Success",
                message = "Movie updated successfully!"
            )
        } catch (e: Exception) {
            alertDialog.show(
                title = "Error",
                message = "Failed to update movie: ${e.message}"
            )
            throw e
        }
    }

    private fun deleteExistingMovie(movie: MovieMetadata) {
        if (movie.id == null) {
            alertDialog.show(
                title = "Error",
                message = "Cannot delete movie without ID"
            )
            return
        }

        mainScope.launch {
            try {
                val success = org.btmonier.deleteMovie(movie.id)
                if (success) {
                    // Reload current page to show updated data
                    loadMovies()
                    alertDialog.show(
                        title = "Success",
                        message = "Movie deleted successfully!"
                    )
                } else {
                    alertDialog.show(
                        title = "Error",
                        message = "Failed to delete movie"
                    )
                }
            } catch (e: Exception) {
                alertDialog.show(
                    title = "Error",
                    message = "Error deleting movie: ${e.message}"
                )
            }
        }
    }

    private fun showGenreManagement() {
        val genreManagementUI = GenreManagementUI(container) {
            // On close, refresh filter options and data
            mainScope.launch {
                loadFilterOptions()
                renderFilters()
                loadMovies()
            }
        }
        genreManagementUI.show()
    }

    private fun showRandomPicker() {
        val randomPicker = RandomMoviePicker(container, onBack = {
            render()
        })
        randomPicker.show()
    }
}
