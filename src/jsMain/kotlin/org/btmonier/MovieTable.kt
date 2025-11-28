package org.btmonier

import kotlinx.browser.document
import kotlinx.coroutines.launch
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.onChangeFunction
import kotlinx.html.js.onClickFunction
import org.w3c.dom.*
import org.w3c.dom.events.Event

class MovieTable(private val container: Element, private var allMovies: List<MovieMetadata>) {
    private var searchText = ""
    private var selectedGenres = setOf<String>()
    private var selectedCountries = setOf<String>()
    private var selectedMediaTypes = setOf<String>()
    private var sortColumn = SortColumn.TITLE
    private var sortDirection = SortDirection.ASC

    // Pagination
    private var currentPage = 1
    private var itemsPerPage = 25

    private val allGenres = allMovies.flatMap { it.genres }.distinct().sorted()
    private val allCountries = allMovies.flatMap { it.country }.distinct().sorted()
    private val allMediaTypes = listOf("VHS", "DVD", "Blu-ray", "4K", "Digital")

    private val metadataModal = MovieMetadataModal(container) {
        // onClose callback - nothing special needed
    }

    private val confirmDialog = ConfirmDialog(container)
    private val alertDialog = AlertDialog(container)

    enum class SortColumn {
        TITLE, PHYSICAL_MEDIA
    }

    enum class SortDirection {
        ASC, DESC
    }

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

                // Filters
                renderFilters()

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

        updateTable()
    }

    private fun DIV.renderFilters() {
        div {
            style = "margin-bottom: 24px; padding: 24px; background-color: #f8f9fa; border: 1px solid #dadce0; border-radius: 8px; display: grid; grid-template-columns: 1fr 1fr 1fr 1fr; gap: 20px;"

            // Search input
            div {
                label {
                    style = "display: block; margin-bottom: 8px; font-weight: 500; font-size: 14px; color: #5f6368;"
                    +"Search by Title"
                }
                div {
                    style = "display: flex; gap: 8px;"
                    input(type = InputType.text) {
                        id = "search-input"
                        style = "flex: 1; padding: 10px 12px; font-size: 14px; border: 1px solid #dadce0; border-radius: 4px; box-sizing: border-box; font-family: 'Roboto', arial, sans-serif; transition: border-color 0.2s;"
                        placeholder = "Search movies..."
                        attributes["onfocus"] = "this.style.borderColor='#1a73e8'; this.style.outline='none'"
                        attributes["onblur"] = "this.style.borderColor='#dadce0'"
                        onChangeFunction = { event ->
                            searchText = (event.target as HTMLInputElement).value
                            currentPage = 1 // Reset to first page
                            updateTable()
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

            // Genre filter
            div {
                label {
                    style = "display: block; margin-bottom: 8px; font-weight: 500; font-size: 14px; color: #5f6368;"
                    +"Filter by Genre"
                }
                select {
                    id = "genre-select"
                    style = "width: 100%; padding: 8px; font-size: 14px; border: 1px solid #dadce0; border-radius: 4px; box-sizing: border-box; font-family: 'Roboto', arial, sans-serif; background-color: #ffffff;"
                    multiple = true
                    attributes["size"] = "6"
                    onChangeFunction = { event ->
                        val select = event.target as HTMLSelectElement
                        val selected = mutableSetOf<String>()
                        for (i in 0 until select.options.length) {
                            val option = select.options.item(i) as HTMLOptionElement?
                            if (option?.selected == true && option.value.isNotEmpty()) {
                                selected.add(option.value)
                            }
                        }
                        selectedGenres = selected
                        currentPage = 1 // Reset to first page
                        updateTable()
                        renderGenreTags()
                    }

                    option {
                        value = ""
                        +"All Genres"
                    }
                    allGenres.forEach { genre ->
                        option {
                            value = genre
                            +genre
                        }
                    }
                }
                div {
                    id = "genre-tags"
                    style = "margin-top: 5px; font-size: 12px;"
                }
            }

            // Country filter
            div {
                label {
                    style = "display: block; margin-bottom: 8px; font-weight: 500; font-size: 14px; color: #5f6368;"
                    +"Filter by Country"
                }
                select {
                    id = "country-select"
                    style = "width: 100%; padding: 8px; font-size: 14px; border: 1px solid #dadce0; border-radius: 4px; box-sizing: border-box; font-family: 'Roboto', arial, sans-serif; background-color: #ffffff;"
                    multiple = true
                    attributes["size"] = "6"
                    onChangeFunction = { event ->
                        val select = event.target as HTMLSelectElement
                        val selected = mutableSetOf<String>()
                        for (i in 0 until select.options.length) {
                            val option = select.options.item(i) as HTMLOptionElement?
                            if (option?.selected == true && option.value.isNotEmpty()) {
                                selected.add(option.value)
                            }
                        }
                        selectedCountries = selected
                        currentPage = 1 // Reset to first page
                        updateTable()
                        renderCountryTags()
                    }

                    option {
                        value = ""
                        +"All Countries"
                    }
                    allCountries.forEach { country ->
                        option {
                            value = country
                            +country
                        }
                    }
                }
                div {
                    id = "country-tags"
                    style = "margin-top: 5px; font-size: 12px;"
                }
            }

            // Media Type filter
            div {
                label {
                    style = "display: block; margin-bottom: 8px; font-weight: 500; font-size: 14px; color: #5f6368;"
                    +"Filter by Media Type"
                }
                select {
                    id = "media-type-select"
                    style = "width: 100%; padding: 8px; font-size: 14px; border: 1px solid #dadce0; border-radius: 4px; box-sizing: border-box; font-family: 'Roboto', arial, sans-serif; background-color: #ffffff;"
                    multiple = true
                    attributes["size"] = "6"
                    onChangeFunction = { event ->
                        val select = event.target as HTMLSelectElement
                        val selected = mutableSetOf<String>()
                        for (i in 0 until select.options.length) {
                            val option = select.options.item(i) as HTMLOptionElement?
                            if (option?.selected == true && option.value.isNotEmpty()) {
                                selected.add(option.value)
                            }
                        }
                        selectedMediaTypes = selected
                        currentPage = 1 // Reset to first page
                        updateTable()
                        renderMediaTypeTags()
                    }

                    option {
                        value = ""
                        +"All Media Types"
                    }
                    allMediaTypes.forEach { mediaType ->
                        option {
                            value = mediaType
                            +mediaType
                        }
                    }
                }
                div {
                    id = "media-type-tags"
                    style = "margin-top: 5px; font-size: 12px;"
                }
            }
        }
    }

    private fun renderGenreTags() {
        val tagsDiv = document.getElementById("genre-tags") ?: return
        tagsDiv.innerHTML = ""
        if (selectedGenres.isEmpty()) return

        tagsDiv.append {
            selectedGenres.forEach { genre ->
                span {
                    style = "display: inline-block; padding: 4px 12px; margin-right: 6px; margin-top: 6px; background-color: #e8f0fe; color: #1967d2; border-radius: 16px; font-size: 13px; cursor: pointer; transition: background-color 0.2s; font-family: 'Roboto', arial, sans-serif;"
                    attributes["onmouseover"] = "this.style.backgroundColor='#d2e3fc'"
                    attributes["onmouseout"] = "this.style.backgroundColor='#e8f0fe'"
                    +"$genre ×"
                    onClickFunction = {
                        selectedGenres = selectedGenres - genre
                        updateSelectOptions("genre-select", selectedGenres)
                        updateTable()
                        renderGenreTags()
                    }
                }
            }
            button {
                style = "margin-top: 6px; padding: 6px 16px; font-size: 13px; cursor: pointer; background-color: #ffffff; color: #1a73e8; border: 1px solid #dadce0; border-radius: 4px; font-weight: 500; transition: background-color 0.2s, box-shadow 0.2s; font-family: 'Roboto', arial, sans-serif;"
                attributes["onmouseover"] = "this.style.backgroundColor='#f8f9fa'; this.style.boxShadow='0 1px 2px 0 rgba(60,64,67,0.3), 0 1px 3px 1px rgba(60,64,67,0.15)'"
                attributes["onmouseout"] = "this.style.backgroundColor='#ffffff'; this.style.boxShadow='none'"
                +"Clear All"
                onClickFunction = {
                    selectedGenres = emptySet()
                    updateSelectOptions("genre-select", selectedGenres)
                    updateTable()
                    renderGenreTags()
                }
            }
        }
    }

    private fun renderCountryTags() {
        val tagsDiv = document.getElementById("country-tags") ?: return
        tagsDiv.innerHTML = ""
        if (selectedCountries.isEmpty()) return

        tagsDiv.append {
            selectedCountries.forEach { country ->
                span {
                    style = "display: inline-block; padding: 4px 12px; margin-right: 6px; margin-top: 6px; background-color: #e6f4ea; color: #137333; border-radius: 16px; font-size: 13px; cursor: pointer; transition: background-color 0.2s; font-family: 'Roboto', arial, sans-serif;"
                    attributes["onmouseover"] = "this.style.backgroundColor='#ceead6'"
                    attributes["onmouseout"] = "this.style.backgroundColor='#e6f4ea'"
                    +"$country ×"
                    onClickFunction = {
                        selectedCountries = selectedCountries - country
                        updateSelectOptions("country-select", selectedCountries)
                        updateTable()
                        renderCountryTags()
                    }
                }
            }
            button {
                style = "margin-top: 6px; padding: 6px 16px; font-size: 13px; cursor: pointer; background-color: #ffffff; color: #1a73e8; border: 1px solid #dadce0; border-radius: 4px; font-weight: 500; transition: background-color 0.2s, box-shadow 0.2s; font-family: 'Roboto', arial, sans-serif;"
                attributes["onmouseover"] = "this.style.backgroundColor='#f8f9fa'; this.style.boxShadow='0 1px 2px 0 rgba(60,64,67,0.3), 0 1px 3px 1px rgba(60,64,67,0.15)'"
                attributes["onmouseout"] = "this.style.backgroundColor='#ffffff'; this.style.boxShadow='none'"
                +"Clear All"
                onClickFunction = {
                    selectedCountries = emptySet()
                    updateSelectOptions("country-select", selectedCountries)
                    updateTable()
                    renderCountryTags()
                }
            }
        }
    }

    private fun renderMediaTypeTags() {
        val tagsDiv = document.getElementById("media-type-tags") ?: return
        tagsDiv.innerHTML = ""
        if (selectedMediaTypes.isEmpty()) return

        tagsDiv.append {
            selectedMediaTypes.forEach { mediaType ->
                span {
                    style = "display: inline-block; padding: 4px 12px; margin-right: 6px; margin-top: 6px; background-color: #fef7e0; color: #c5221f; border-radius: 16px; font-size: 13px; cursor: pointer; transition: background-color 0.2s; font-family: 'Roboto', arial, sans-serif;"
                    attributes["onmouseover"] = "this.style.backgroundColor='#fee9c3'"
                    attributes["onmouseout"] = "this.style.backgroundColor='#fef7e0'"
                    +"$mediaType ×"
                    onClickFunction = {
                        selectedMediaTypes = selectedMediaTypes - mediaType
                        updateSelectOptions("media-type-select", selectedMediaTypes)
                        updateTable()
                        renderMediaTypeTags()
                    }
                }
            }
            button {
                style = "margin-top: 6px; padding: 6px 16px; font-size: 13px; cursor: pointer; background-color: #ffffff; color: #1a73e8; border: 1px solid #dadce0; border-radius: 4px; font-weight: 500; transition: background-color 0.2s, box-shadow 0.2s; font-family: 'Roboto', arial, sans-serif;"
                attributes["onmouseover"] = "this.style.backgroundColor='#f8f9fa'; this.style.boxShadow='0 1px 2px 0 rgba(60,64,67,0.3), 0 1px 3px 1px rgba(60,64,67,0.15)'"
                attributes["onmouseout"] = "this.style.backgroundColor='#ffffff'; this.style.boxShadow='none'"
                +"Clear All"
                onClickFunction = {
                    selectedMediaTypes = emptySet()
                    updateSelectOptions("media-type-select", selectedMediaTypes)
                    updateTable()
                    renderMediaTypeTags()
                }
            }
        }
    }

    private fun updateSelectOptions(selectId: String, selected: Set<String>) {
        val select = document.getElementById(selectId) as? HTMLSelectElement ?: return
        for (i in 0 until select.options.length) {
            val option = select.options.item(i) as? HTMLOptionElement ?: continue
            option.selected = option.value in selected
        }
    }

    private fun resetAllFilters() {
        // Clear search text
        searchText = ""
        val searchInput = document.getElementById("search-input") as? HTMLInputElement
        searchInput?.value = ""

        // Clear genre filter
        selectedGenres = emptySet()
        updateSelectOptions("genre-select", selectedGenres)

        // Clear country filter
        selectedCountries = emptySet()
        updateSelectOptions("country-select", selectedCountries)

        // Clear media type filter
        selectedMediaTypes = emptySet()
        updateSelectOptions("media-type-select", selectedMediaTypes)

        // Reset pagination
        currentPage = 1

        // Update the table and tag displays
        updateTable()
        renderGenreTags()
        renderCountryTags()
        renderMediaTypeTags()
    }

    private fun updateTable() {
        val filtered = getFilteredMovies()

        // Calculate pagination
        val totalPages = kotlin.math.ceil(filtered.size.toDouble() / itemsPerPage).toInt()
        if (currentPage > totalPages && totalPages > 0) {
            currentPage = totalPages
        }
        if (currentPage < 1) {
            currentPage = 1
        }

        val startIndex = (currentPage - 1) * itemsPerPage
        val endIndex = kotlin.math.min(startIndex + itemsPerPage, filtered.size)
        val paginatedMovies = filtered.subList(startIndex, endIndex)

        // Update count
        val countDiv = document.getElementById("results-count")
        countDiv?.textContent = "Showing ${startIndex + 1}-$endIndex of ${filtered.size} movies (total: ${allMovies.size})"
        countDiv?.setAttribute("style", "margin-bottom: 16px; font-size: 14px; color: #5f6368; font-family: 'Roboto', arial, sans-serif;")

        // Update table
        val tableContainer = document.getElementById("table-container") ?: return
        tableContainer.innerHTML = ""
        tableContainer.append {
            table {
                style = "width: 100%; border-collapse: collapse; background-color: white; border: 1px solid #dadce0; border-radius: 8px; overflow: hidden; box-shadow: 0 1px 2px 0 rgba(60,64,67,0.3), 0 1px 3px 1px rgba(60,64,67,0.15);"

                thead {
                    style = "background-color: #f8f9fa; color: #202124; border-bottom: 1px solid #dadce0;"
                    tr {
                        renderSortableHeader("Title", SortColumn.TITLE)
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
                    paginatedMovies.forEach { movie ->
                        tr {
                            style = "border-bottom: 1px solid #e8eaed;"
                            attributes["onmouseover"] = "this.style.backgroundColor='#f8f9fa'"
                            attributes["onmouseout"] = "this.style.backgroundColor='#ffffff'"

                            td {
                                style = "padding: 12px 16px; cursor: pointer;"
                                onClickFunction = {
                                    metadataModal.show(movie)
                                }

                                // Movie title
                                div {
                                    style = "color: #1a73e8; font-size: 14px; margin-bottom: 4px;"
                                    attributes["onmouseover"] = "this.style.textDecoration='underline'"
                                    attributes["onmouseout"] = "this.style.textDecoration='none'"
                                    +movie.title
                                }
                            }
                            td {
                                style = "padding: 12px 16px;"
                                // Display physical media badges with entry letters and titles
                                movie.physicalMedia.forEach { media ->
                                    // Show entry letter if available
                                    if (media.entryLetter != null) {
                                        span {
                                            style = "display: inline-block; padding: 4px 8px; margin-right: 4px; margin-bottom: 4px; background-color: #f1f3f4; color: #202124; border-radius: 4px; font-size: 11px; font-weight: 600; font-family: 'Roboto', arial, sans-serif;"
                                            +media.entryLetter
                                        }
                                    }
                                    // Show title if available
                                    if (media.title != null) {
                                        span {
                                            style = "display: inline-block; padding: 4px 8px; margin-right: 4px; margin-bottom: 4px; background-color: #e8f0fe; color: #1967d2; border-radius: 4px; font-size: 11px; font-weight: 500; font-family: 'Roboto', arial, sans-serif;"
                                            +media.title
                                        }
                                    }
                                    // Show each media type badge
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

            // Pagination controls
            if (totalPages > 1) {
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
                                updateTable()
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
                                updateTable()
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
                                    currentPage = 1 // Reset to first page
                                    updateTable()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun TR.renderSortableHeader(label: String, column: SortColumn) {
        th {
            style = "padding: 12px 16px; text-align: left; cursor: pointer; user-select: none; font-weight: 500; font-size: 14px; color: #5f6368; transition: background-color 0.2s;"
            attributes["onmouseover"] = "this.style.backgroundColor='#e8eaed'"
            attributes["onmouseout"] = "this.style.backgroundColor=''"
            onClickFunction = {
                if (sortColumn == column) {
                    sortDirection = if (sortDirection == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
                } else {
                    sortColumn = column
                    sortDirection = SortDirection.ASC
                }
                updateTable()
            }

            +label
            if (sortColumn == column) {
                span {
                    style = "margin-left: 6px; color: #1a73e8;"
                    +(if (sortDirection == SortDirection.ASC) "▲" else "▼")
                }
            }
        }
    }

    private fun getFilteredMovies(): List<MovieMetadata> {
        var filtered = allMovies

        // Filter by search text
        if (searchText.isNotBlank()) {
            filtered = filtered.filter {
                it.title.contains(searchText, ignoreCase = true)
            }
        }

        // Filter by genres
        if (selectedGenres.isNotEmpty()) {
            filtered = filtered.filter { movie ->
                movie.genres.any { it in selectedGenres }
            }
        }

        // Filter by countries
        if (selectedCountries.isNotEmpty()) {
            filtered = filtered.filter { movie ->
                movie.country.any { it in selectedCountries }
            }
        }

        // Filter by media types
        if (selectedMediaTypes.isNotEmpty()) {
            filtered = filtered.filter { movie ->
                movie.physicalMedia.any { media ->
                    media.mediaTypes.any { type ->
                        val mediaTypeString = when (type) {
                            MediaType.VHS -> "VHS"
                            MediaType.DVD -> "DVD"
                            MediaType.BLURAY -> "Blu-ray"
                            MediaType.FOURK -> "4K"
                            MediaType.DIGITAL -> "Digital"
                        }
                        mediaTypeString in selectedMediaTypes
                    }
                }
            }
        }

        // Sort
        val sorted = when (sortColumn) {
            SortColumn.TITLE -> filtered.sortedBy { it.title.lowercase() }
            SortColumn.PHYSICAL_MEDIA -> filtered.sortedBy { it.physicalMedia.size }
        }

        return if (sortDirection == SortDirection.DESC) sorted.reversed() else sorted
    }

    private fun showAddMovieForm() {
        // First, show the Letterboxd URL form to scrape movie data
        val urlForm = LetterboxdUrlForm(
            container = container,
            onScrapedMovie = { scrapedMovie ->
                // After scraping, show the edit form with pre-populated data
                val form = MovieForm(container, onSave = { movie ->
                    createMovie(movie)
                }, onCancel = {})

                // If the scraped movie has data (URL is not blank), use it to pre-populate
                if (scrapedMovie.url.isNotBlank()) {
                    form.showCreateWithData(scrapedMovie)
                } else {
                    // User chose manual entry
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
            val createdMovie = org.btmonier.createMovie(movie)
            allMovies = allMovies + createdMovie
            render()
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
            val updatedMovie = org.btmonier.updateMovie(movie.id, movie)
            allMovies = allMovies.map { if (it.id == movie.id) updatedMovie else it }
            render()
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
                    allMovies = allMovies.filter { it.id != movie.id }
                    render()
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
            // On close, refresh the movie list to update genre filters
            render()
        }
        genreManagementUI.show()
    }

    private fun showRandomPicker() {
        val randomPicker = RandomMoviePicker(container, onBack = {
            // Return to the main collection view
            render()
        })
        randomPicker.show()
    }
}
