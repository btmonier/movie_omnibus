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
    // Filter state (now supporting multiple selections)
    private var searchText = ""
    private var selectedGenres: MutableSet<String> = mutableSetOf()
    private var selectedSubgenres: MutableSet<String> = mutableSetOf()
    private var selectedCountries: MutableSet<String> = mutableSetOf()
    private var selectedMediaTypes: MutableSet<String> = mutableSetOf()

    // Sorting state
    private var sortField: SortField = SortField.TITLE
    private var sortDirection: SortDirection = SortDirection.ASC

    enum class SortField { TITLE, RELEASE_DATE, DATE_ADDED }
    enum class SortDirection { ASC, DESC }

    // Pagination state (server-provided)
    private var currentPage = 1
    private var itemsPerPage = 24
    private var totalCount = 0
    private var totalPages = 1

    // Current page data
    private var currentMovies: List<MovieMetadata> = emptyList()
    private var displayedMovies: List<MovieMetadata> = emptyList()

    // Filter options (loaded from API)
    private var allGenres: List<String> = emptyList()
    private var allSubgenres: List<String> = emptyList()
    private var allCountries: List<String> = emptyList()
    private val allMediaTypes = listOf("VHS", "DVD", "Blu-ray", "4K", "Digital")

    // Dropdown open states
    private var genreDropdownOpen = false
    private var subgenreDropdownOpen = false
    private var countryDropdownOpen = false
    private var mediaTypeDropdownOpen = false

    // Dropdown search text
    private var genreSearchText = ""
    private var subgenreSearchText = ""
    private var countrySearchText = ""
    private var mediaTypeSearchText = ""

    // Loading state
    private var isLoading = false
    
    // Track if we're using client-side filtering (for pagination)
    private var usingClientSideFiltering = false

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
                    style = """
                        display: flex;
                        flex-wrap: wrap;
                        justify-content: space-between;
                        align-items: center;
                        gap: 16px;
                        margin-bottom: 24px;
                    """.trimIndent()
                    h1 {
                        style = "color: #202124; font-weight: 400; font-size: 26px; margin: 0;"
                        +"🎬 The Movie Omnibus"
                    }
                    div {
                        style = "display: flex; flex-wrap: wrap; gap: 10px;"
                        button {
                            style = """
                                padding: 10px 18px;
                                font-size: 13px;
                                cursor: pointer;
                                background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                                color: white;
                                border: none;
                                border-radius: 8px;
                                font-weight: 500;
                                transition: transform 0.2s, box-shadow 0.2s;
                                box-shadow: 0 2px 8px rgba(102, 126, 234, 0.3);
                            """.trimIndent()
                            attributes["onmouseover"] = "this.style.transform='scale(1.02)'; this.style.boxShadow='0 4px 12px rgba(102, 126, 234, 0.4)'"
                            attributes["onmouseout"] = "this.style.transform='scale(1)'; this.style.boxShadow='0 2px 8px rgba(102, 126, 234, 0.3)'"
                            +"🎲 Random"
                            onClickFunction = {
                                showRandomPicker()
                            }
                        }
                        button {
                            style = """
                                padding: 10px 18px;
                                font-size: 13px;
                                cursor: pointer;
                                background-color: #ffffff;
                                color: #1a73e8;
                                border: 1px solid #dadce0;
                                border-radius: 8px;
                                font-weight: 500;
                                transition: background-color 0.2s;
                            """.trimIndent()
                            attributes["onmouseover"] = "this.style.backgroundColor='#f8f9fa'"
                            attributes["onmouseout"] = "this.style.backgroundColor='#ffffff'"
                            +"⚙ Genres"
                            onClickFunction = {
                                showGenreManagement()
                            }
                        }
                        button {
                            style = """
                                padding: 10px 18px;
                                font-size: 13px;
                                cursor: pointer;
                                background-color: #1a73e8;
                                color: white;
                                border: none;
                                border-radius: 8px;
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

                // Cards container
                div {
                    id = "cards-container"
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
            allSubgenres = fetchSubgenreOptions()
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
                style = """
                    padding: 20px;
                    background-color: #f8f9fa;
                    border: 1px solid #dadce0;
                    border-radius: 12px;
                """.trimIndent()

                // Row 1: Search and Sort (responsive)
                div {
                    style = """
                        display: grid;
                        grid-template-columns: 1fr auto;
                        gap: 16px;
                        margin-bottom: 16px;
                    """.trimIndent()
                    attributes["class"] = "filter-row-1"

                    // Search input
                    div {
                        style = "min-width: 0;"
                        label {
                            htmlFor = "search-input"
                            style = "display: block; margin-bottom: 6px; font-weight: 500; font-size: 13px; color: #5f6368;"
                            +"🔍 Search"
                        }
                        input(type = InputType.text) {
                            id = "search-input"
                            style = """
                                width: 100%;
                                padding: 10px 14px;
                                font-size: 14px;
                                border: 1px solid #dadce0;
                                border-radius: 8px;
                                box-sizing: border-box;
                                font-family: 'Roboto', arial, sans-serif;
                                transition: border-color 0.2s, box-shadow 0.2s;
                                background-color: white;
                            """.trimIndent()
                            placeholder = "Search by title..."
                            value = searchText
                            attributes["onfocus"] = "this.style.borderColor='#1a73e8'; this.style.boxShadow='0 0 0 3px rgba(26,115,232,0.1)'; this.style.outline='none'"
                            attributes["onblur"] = "this.style.borderColor='#dadce0'; this.style.boxShadow='none'"
                            onInputFunction = { event ->
                                searchText = (event.target as HTMLInputElement).value
                            }
                            onChangeFunction = {
                                currentPage = 1
                                renderFilters()
                                mainScope.launch { loadMovies() }
                            }
                        }
                    }

                    // Sort controls
                    div {
                        style = "display: flex; gap: 8px; align-items: flex-end;"

                        div {
                            label {
                                style = "display: block; margin-bottom: 6px; font-weight: 500; font-size: 13px; color: #5f6368;"
                                +"↕ Sort by"
                            }
                            select {
                                id = "sort-field-select"
                                style = """
                                    padding: 10px 14px;
                                    font-size: 14px;
                                    border: 1px solid #dadce0;
                                    border-radius: 8px;
                                    background-color: white;
                                    cursor: pointer;
                                    font-family: 'Roboto', arial, sans-serif;
                                    min-width: 140px;
                                """.trimIndent()
                                onChangeFunction = { event ->
                                    val select = event.target as HTMLSelectElement
                                    sortField = when (select.value) {
                                        "title" -> SortField.TITLE
                                        "release_date" -> SortField.RELEASE_DATE
                                        "date_added" -> SortField.DATE_ADDED
                                        else -> SortField.TITLE
                                    }
                                    currentPage = 1
                                    mainScope.launch { loadMovies() }
                                }

                                option {
                                    value = "title"
                                    selected = sortField == SortField.TITLE
                                    +"Title"
                                }
                                option {
                                    value = "release_date"
                                    selected = sortField == SortField.RELEASE_DATE
                                    +"Release Year"
                                }
                                option {
                                    value = "date_added"
                                    selected = sortField == SortField.DATE_ADDED
                                    +"Date Added"
                                }
                            }
                        }

                        button {
                            id = "sort-direction-btn"
                            style = """
                                padding: 10px 14px;
                                font-size: 16px;
                                cursor: pointer;
                                background-color: white;
                                color: #5f6368;
                                border: 1px solid #dadce0;
                                border-radius: 8px;
                                transition: background-color 0.2s;
                            """.trimIndent()
                            attributes["onmouseover"] = "this.style.backgroundColor='#f1f3f4'"
                            attributes["onmouseout"] = "this.style.backgroundColor='white'"
                            +(if (sortDirection == SortDirection.ASC) "↑" else "↓")
                            onClickFunction = {
                                sortDirection = if (sortDirection == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
                                currentPage = 1
                                renderFilters()
                                mainScope.launch { loadMovies() }
                            }
                        }
                    }
                }

                // Row 2: Filter dropdowns (responsive grid)
                div {
                    style = """
                        display: grid;
                        grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
                        gap: 12px;
                        margin-bottom: 16px;
                    """.trimIndent()

                    // Genre multi-select dropdown
                    renderMultiSelectDropdown(
                        id = "genre",
                        label = "🎭 Genres",
                        options = allGenres,
                        selectedOptions = selectedGenres,
                        isOpen = genreDropdownOpen,
                        searchText = genreSearchText,
                        onToggle = {
                            genreDropdownOpen = !genreDropdownOpen
                            if (!genreDropdownOpen) genreSearchText = ""
                            subgenreDropdownOpen = false
                            subgenreSearchText = ""
                            countryDropdownOpen = false
                            countrySearchText = ""
                            mediaTypeDropdownOpen = false
                            mediaTypeSearchText = ""
                            renderFilters()
                        },
                        onOptionToggle = { option ->
                            if (selectedGenres.contains(option)) {
                                selectedGenres.remove(option)
                            } else {
                                selectedGenres.add(option)
                            }
                            currentPage = 1
                            renderFilters()
                            mainScope.launch { loadMovies() }
                        },
                        onClear = {
                            selectedGenres.clear()
                            currentPage = 1
                            renderFilters()
                            mainScope.launch { loadMovies() }
                        },
                        onSearchChange = { newText ->
                            genreSearchText = newText
                            renderFilters()
                            // Refocus the search input after re-render
                            (document.getElementById("genre-search") as? HTMLInputElement)?.let { input ->
                                input.focus()
                                input.setSelectionRange(newText.length, newText.length)
                            }
                        }
                    )

                    // Subgenre multi-select dropdown
                    renderMultiSelectDropdown(
                        id = "subgenre",
                        label = "\uD83D\uDD2C Subgenres",
                        options = allSubgenres,
                        selectedOptions = selectedSubgenres,
                        isOpen = subgenreDropdownOpen,
                        searchText = subgenreSearchText,
                        onToggle = {
                            subgenreDropdownOpen = !subgenreDropdownOpen
                            if (!subgenreDropdownOpen) subgenreSearchText = ""
                            genreDropdownOpen = false
                            genreSearchText = ""
                            countryDropdownOpen = false
                            countrySearchText = ""
                            mediaTypeDropdownOpen = false
                            mediaTypeSearchText = ""
                            renderFilters()
                        },
                        onOptionToggle = { option ->
                            if (selectedSubgenres.contains(option)) {
                                selectedSubgenres.remove(option)
                            } else {
                                selectedSubgenres.add(option)
                            }
                            currentPage = 1
                            renderFilters()
                            mainScope.launch { loadMovies() }
                        },
                        onClear = {
                            selectedSubgenres.clear()
                            currentPage = 1
                            renderFilters()
                            mainScope.launch { loadMovies() }
                        },
                        onSearchChange = { newText ->
                            subgenreSearchText = newText
                            renderFilters()
                            // Refocus the search input after re-render
                            (document.getElementById("subgenre-search") as? HTMLInputElement)?.let { input ->
                                input.focus()
                                input.setSelectionRange(newText.length, newText.length)
                            }
                        }
                    )

                    // Country multi-select dropdown
                    renderMultiSelectDropdown(
                        id = "country",
                        label = "🌍 Countries",
                        options = allCountries,
                        selectedOptions = selectedCountries,
                        isOpen = countryDropdownOpen,
                        searchText = countrySearchText,
                        onToggle = {
                            countryDropdownOpen = !countryDropdownOpen
                            if (!countryDropdownOpen) countrySearchText = ""
                            genreDropdownOpen = false
                            genreSearchText = ""
                            subgenreDropdownOpen = false
                            subgenreSearchText = ""
                            mediaTypeDropdownOpen = false
                            mediaTypeSearchText = ""
                            renderFilters()
                        },
                        onOptionToggle = { option ->
                            if (selectedCountries.contains(option)) {
                                selectedCountries.remove(option)
                            } else {
                                selectedCountries.add(option)
                            }
                            currentPage = 1
                            renderFilters()
                            mainScope.launch { loadMovies() }
                        },
                        onClear = {
                            selectedCountries.clear()
                            currentPage = 1
                            renderFilters()
                            mainScope.launch { loadMovies() }
                        },
                        onSearchChange = { newText ->
                            countrySearchText = newText
                            renderFilters()
                            // Refocus the search input after re-render
                            (document.getElementById("country-search") as? HTMLInputElement)?.let { input ->
                                input.focus()
                                input.setSelectionRange(newText.length, newText.length)
                            }
                        }
                    )

                    // Media Type multi-select dropdown
                    renderMultiSelectDropdown(
                        id = "media-type",
                        label = "💿 Media Type",
                        options = allMediaTypes,
                        selectedOptions = selectedMediaTypes,
                        isOpen = mediaTypeDropdownOpen,
                        searchText = mediaTypeSearchText,
                        onToggle = {
                            mediaTypeDropdownOpen = !mediaTypeDropdownOpen
                            if (!mediaTypeDropdownOpen) mediaTypeSearchText = ""
                            genreDropdownOpen = false
                            genreSearchText = ""
                            subgenreDropdownOpen = false
                            subgenreSearchText = ""
                            countryDropdownOpen = false
                            countrySearchText = ""
                            renderFilters()
                        },
                        onOptionToggle = { option ->
                            if (selectedMediaTypes.contains(option)) {
                                selectedMediaTypes.remove(option)
                            } else {
                                selectedMediaTypes.add(option)
                            }
                            currentPage = 1
                            renderFilters()
                            mainScope.launch { loadMovies() }
                        },
                        onClear = {
                            selectedMediaTypes.clear()
                            currentPage = 1
                            renderFilters()
                            mainScope.launch { loadMovies() }
                        },
                        onSearchChange = { newText ->
                            mediaTypeSearchText = newText
                            renderFilters()
                            // Refocus the search input after re-render
                            (document.getElementById("media-type-search") as? HTMLInputElement)?.let { input ->
                                input.focus()
                                input.setSelectionRange(newText.length, newText.length)
                            }
                        }
                    )
                }

                // Row 3: Active filters display and reset button
                val hasActiveFilters = searchText.isNotBlank() || selectedGenres.isNotEmpty() || 
                    selectedSubgenres.isNotEmpty() || selectedCountries.isNotEmpty() || selectedMediaTypes.isNotEmpty()

                if (hasActiveFilters) {
                    div {
                        style = """
                            display: flex;
                            flex-wrap: wrap;
                            align-items: center;
                            gap: 8px;
                            padding-top: 12px;
                            border-top: 1px solid #e8eaed;
                        """.trimIndent()

                        span {
                            style = "font-size: 13px; color: #5f6368; font-weight: 500;"
                            +"Active filters:"
                        }

                        // Show selected genre tags
                        selectedGenres.forEach { genre ->
                            renderFilterTag(genre, "#e8f0fe", "#1967d2") {
                                selectedGenres.remove(genre)
                                currentPage = 1
                                renderFilters()
                                mainScope.launch { loadMovies() }
                            }
                        }

                        // Show selected subgenre tags
                        selectedSubgenres.forEach { subgenre ->
                            renderFilterTag(subgenre, "#e3f2fd", "#0d47a1") {
                                selectedSubgenres.remove(subgenre)
                                currentPage = 1
                                renderFilters()
                                mainScope.launch { loadMovies() }
                            }
                        }

                        // Show selected country tags
                        selectedCountries.forEach { country ->
                            renderFilterTag(country, "#e6f4ea", "#137333") {
                                selectedCountries.remove(country)
                                currentPage = 1
                                renderFilters()
                                mainScope.launch { loadMovies() }
                            }
                        }

                        // Show selected media type tags
                        selectedMediaTypes.forEach { mediaType ->
                            renderFilterTag(mediaType, "#fef7e0", "#b06000") {
                                selectedMediaTypes.remove(mediaType)
                                currentPage = 1
                                renderFilters()
                                mainScope.launch { loadMovies() }
                            }
                        }

                        // Search text tag
                        if (searchText.isNotBlank()) {
                            renderFilterTag("\"$searchText\"", "#f3e5f5", "#6a1b9a") {
                                searchText = ""
                                (document.getElementById("search-input") as? HTMLInputElement)?.value = ""
                                currentPage = 1
                                renderFilters()
                                mainScope.launch { loadMovies() }
                            }
                        }

                        // Clear all button
                        button {
                            style = """
                                padding: 6px 12px;
                                font-size: 12px;
                                cursor: pointer;
                                background-color: #d93025;
                                color: white;
                                border: none;
                                border-radius: 16px;
                                font-weight: 500;
                                transition: background-color 0.2s;
                                margin-left: auto;
                            """.trimIndent()
                            attributes["onmouseover"] = "this.style.backgroundColor='#c5221f'"
                            attributes["onmouseout"] = "this.style.backgroundColor='#d93025'"
                            +"✕ Clear All"
                            onClickFunction = {
                                resetAllFilters()
                            }
                        }
                    }
                }
            }

            // Add responsive CSS via style tag
            style {
                unsafe {
                    +"""
                        @media (max-width: 768px) {
                            .filter-row-1 {
                                grid-template-columns: 1fr !important;
                            }
                        }
                    """.trimIndent()
                }
            }
        }

        // Close dropdowns when clicking outside
        document.addEventListener("click", { event ->
            val target = event.target as? Element
            if (target != null) {
                val isInsideDropdown = target.closest(".multi-select-dropdown") != null
                if (!isInsideDropdown && (genreDropdownOpen || subgenreDropdownOpen || countryDropdownOpen || mediaTypeDropdownOpen)) {
                    genreDropdownOpen = false
                    subgenreDropdownOpen = false
                    countryDropdownOpen = false
                    mediaTypeDropdownOpen = false
                    genreSearchText = ""
                    subgenreSearchText = ""
                    countrySearchText = ""
                    mediaTypeSearchText = ""
                    renderFilters()
                }
            }
        })
    }

    private fun DIV.renderMultiSelectDropdown(
        id: String,
        label: String,
        options: List<String>,
        selectedOptions: Set<String>,
        isOpen: Boolean,
        searchText: String,
        onToggle: () -> Unit,
        onOptionToggle: (String) -> Unit,
        onClear: () -> Unit,
        onSearchChange: (String) -> Unit
    ) {
        // Filter options based on search text
        val filteredOptions = if (searchText.isBlank()) {
            options
        } else {
            options.filter { it.lowercase().contains(searchText.lowercase()) }
        }

        div {
            style = "position: relative;"
            attributes["class"] = "multi-select-dropdown"

            label {
                style = "display: block; margin-bottom: 6px; font-weight: 500; font-size: 13px; color: #5f6368;"
                +label
            }

            // Dropdown trigger button
            div {
                style = """
                    padding: 10px 14px;
                    font-size: 14px;
                    border: 1px solid ${if (isOpen) "#1a73e8" else "#dadce0"};
                    border-radius: 8px;
                    background-color: white;
                    cursor: pointer;
                    display: flex;
                    justify-content: space-between;
                    align-items: center;
                    transition: border-color 0.2s;
                    ${if (isOpen) "box-shadow: 0 0 0 3px rgba(26,115,232,0.1);" else ""}
                """.trimIndent()
                attributes["onmouseover"] = if (!isOpen) "this.style.borderColor='#bdc1c6'" else ""
                attributes["onmouseout"] = if (!isOpen) "this.style.borderColor='#dadce0'" else ""
                onClickFunction = { event ->
                    event.stopPropagation()
                    onToggle()
                }

                span {
                    style = "color: ${if (selectedOptions.isEmpty()) "#5f6368" else "#202124"}; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;"
                    if (selectedOptions.isEmpty()) {
                        +"All"
                    } else if (selectedOptions.size <= 2) {
                        +selectedOptions.joinToString(", ")
                    } else {
                        +"${selectedOptions.size} selected"
                    }
                }

                span {
                    style = "color: #5f6368; font-size: 12px; margin-left: 8px; flex-shrink: 0;"
                    +(if (isOpen) "▲" else "▼")
                }
            }

            // Dropdown menu
            if (isOpen) {
                div {
                    style = """
                        position: absolute;
                        top: 100%;
                        left: 0;
                        right: 0;
                        margin-top: 4px;
                        background-color: white;
                        border: 1px solid #dadce0;
                        border-radius: 8px;
                        box-shadow: 0 4px 12px rgba(0,0,0,0.15);
                        z-index: 100;
                        max-height: 320px;
                        display: flex;
                        flex-direction: column;
                    """.trimIndent()
                    onClickFunction = { event ->
                        event.stopPropagation()
                    }

                    // Search input
                    div {
                        style = "padding: 8px; border-bottom: 1px solid #e8eaed; flex-shrink: 0;"
                        input(type = InputType.text) {
                            this.id = "$id-search"
                            style = """
                                width: 100%;
                                padding: 8px 10px;
                                font-size: 13px;
                                border: 1px solid #dadce0;
                                border-radius: 6px;
                                outline: none;
                                box-sizing: border-box;
                            """.trimIndent()
                            placeholder = "Search..."
                            value = searchText
                            attributes["onfocus"] = "this.style.borderColor='#1a73e8'"
                            attributes["onblur"] = "this.style.borderColor='#dadce0'"
                            onInputFunction = { event ->
                                val newValue = (event.target as HTMLInputElement).value
                                onSearchChange(newValue)
                            }
                            onClickFunction = { event ->
                                event.stopPropagation()
                            }
                        }
                    }

                    // Scrollable options container
                    div {
                        style = "overflow-y: auto; flex: 1; max-height: 240px;"

                        // Clear selection option
                        if (selectedOptions.isNotEmpty()) {
                            div {
                                style = """
                                    padding: 10px 14px;
                                    font-size: 13px;
                                    color: #d93025;
                                    cursor: pointer;
                                    border-bottom: 1px solid #e8eaed;
                                    font-weight: 500;
                                    transition: background-color 0.15s;
                                """.trimIndent()
                                attributes["onmouseover"] = "this.style.backgroundColor='#fce8e6'"
                                attributes["onmouseout"] = "this.style.backgroundColor='white'"
                                +"✕ Clear selection"
                                onClickFunction = { event ->
                                    event.stopPropagation()
                                    onClear()
                                }
                            }
                        }

                        // Options list
                        filteredOptions.forEach { option ->
                            val isSelected = selectedOptions.contains(option)
                            div {
                                style = """
                                    padding: 10px 14px;
                                    font-size: 14px;
                                    cursor: pointer;
                                    display: flex;
                                    align-items: center;
                                    gap: 10px;
                                    transition: background-color 0.15s;
                                    ${if (isSelected) "background-color: #e8f0fe;" else ""}
                                """.trimIndent()
                                attributes["onmouseover"] = "this.style.backgroundColor='${if (isSelected) "#d2e3fc" else "#f8f9fa"}'"
                                attributes["onmouseout"] = "this.style.backgroundColor='${if (isSelected) "#e8f0fe" else "white"}'"
                                onClickFunction = { event ->
                                    event.stopPropagation()
                                    onOptionToggle(option)
                                }

                                // Checkbox
                                div {
                                    style = """
                                        width: 18px;
                                        height: 18px;
                                        border: 2px solid ${if (isSelected) "#1a73e8" else "#5f6368"};
                                        border-radius: 4px;
                                        background-color: ${if (isSelected) "#1a73e8" else "white"};
                                        display: flex;
                                        align-items: center;
                                        justify-content: center;
                                        flex-shrink: 0;
                                    """.trimIndent()

                                    if (isSelected) {
                                        span {
                                            style = "color: white; font-size: 12px; font-weight: bold;"
                                            +"✓"
                                        }
                                    }
                                }

                                span {
                                    style = "color: #202124;"
                                    +option
                                }
                            }
                        }

                        if (filteredOptions.isEmpty()) {
                            div {
                                style = "padding: 16px; text-align: center; color: #5f6368; font-size: 13px;"
                                if (searchText.isNotBlank()) {
                                    +"No matches found"
                                } else {
                                    +"No options available"
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun DIV.renderFilterTag(text: String, bgColor: String, textColor: String, onRemove: () -> Unit) {
        span {
            style = """
                display: inline-flex;
                align-items: center;
                gap: 6px;
                padding: 4px 10px;
                background-color: $bgColor;
                color: $textColor;
                border-radius: 16px;
                font-size: 12px;
                font-weight: 500;
            """.trimIndent()

            +text

            span {
                style = """
                    cursor: pointer;
                    font-size: 14px;
                    line-height: 1;
                    opacity: 0.7;
                    transition: opacity 0.15s;
                """.trimIndent()
                attributes["onmouseover"] = "this.style.opacity='1'"
                attributes["onmouseout"] = "this.style.opacity='0.7'"
                +"×"
                onClickFunction = { onRemove() }
            }
        }
    }

    private fun applyFilters() {
        // Apply client-side filtering only (sorting is done server-side)
        var filtered = currentMovies.toList()

        // Filter by search text
        if (searchText.isNotBlank()) {
            val lowerSearch = searchText.lowercase()
            filtered = filtered.filter { movie ->
                movie.title.lowercase().contains(lowerSearch) ||
                movie.alternateTitles.any { it.lowercase().contains(lowerSearch) }
            }
        }

        // Filter by genres (movie must have ALL selected genres)
        if (selectedGenres.isNotEmpty()) {
            filtered = filtered.filter { movie ->
                selectedGenres.all { genre -> movie.genres.contains(genre) }
            }
        }

        // Filter by subgenres (movie must have ALL selected subgenres)
        if (selectedSubgenres.isNotEmpty()) {
            filtered = filtered.filter { movie ->
                selectedSubgenres.all { subgenre -> movie.subgenres.contains(subgenre) }
            }
        }

        // Filter by countries (movie must have ANY of the selected countries)
        if (selectedCountries.isNotEmpty()) {
            filtered = filtered.filter { movie ->
                movie.country.any { country -> selectedCountries.contains(country) }
            }
        }

        // Filter by media types (movie must have ANY of the selected media types)
        if (selectedMediaTypes.isNotEmpty()) {
            filtered = filtered.filter { movie ->
                movie.physicalMedia.any { media ->
                    media.mediaTypes.any { type ->
                        val typeStr = when (type) {
                            MediaType.VHS -> "VHS"
                            MediaType.DVD -> "DVD"
                            MediaType.BLURAY -> "Blu-ray"
                            MediaType.FOURK -> "4K"
                            MediaType.DIGITAL -> "Digital"
                        }
                        selectedMediaTypes.contains(typeStr)
                    }
                }
            }
        }

        // Sorting is already done server-side, no need to sort here
        displayedMovies = filtered
        renderCards(useClientSidePagination = true)
    }

    private fun resetAllFilters() {
        searchText = ""
        selectedGenres.clear()
        selectedSubgenres.clear()
        selectedCountries.clear()
        selectedMediaTypes.clear()
        sortField = SortField.TITLE
        sortDirection = SortDirection.ASC
        currentPage = 1

        // Close all dropdowns and clear search text
        genreDropdownOpen = false
        subgenreDropdownOpen = false
        countryDropdownOpen = false
        mediaTypeDropdownOpen = false
        genreSearchText = ""
        subgenreSearchText = ""
        countrySearchText = ""
        mediaTypeSearchText = ""

        // Update UI
        (document.getElementById("search-input") as? HTMLInputElement)?.value = ""

        renderFilters()
        mainScope.launch { loadMovies() }
    }

    private suspend fun loadMovies() {
        if (isLoading) return
        isLoading = true

        showLoadingState()

        try {
            // Convert sort field enum to API string
            val sortFieldStr = when (sortField) {
                SortField.TITLE -> "title"
                SortField.RELEASE_DATE -> "release_date"
                SortField.DATE_ADDED -> "date_added"
            }
            val sortDirectionStr = if (sortDirection == SortDirection.ASC) "asc" else "desc"

            // Check if we need client-side filtering (only when multiple items selected for any filter)
            val needsClientSideFiltering = selectedGenres.size > 1 || 
                selectedSubgenres.size > 1 ||
                selectedCountries.size > 1 || 
                selectedMediaTypes.size > 1

            if (needsClientSideFiltering) {
                // Load all movies for client-side filtering when multi-select is active
                // Server still handles sorting for us
                usingClientSideFiltering = true
                val response = fetchMoviesPaginated(
                    page = 1,
                    pageSize = 10000,
                    search = null,
                    genre = null,
                    subgenre = null,
                    country = null,
                    mediaType = null,
                    sortField = sortFieldStr,
                    sortDirection = sortDirectionStr
                )
                currentMovies = response.movies
                totalCount = response.totalCount
                applyFilters()
            } else {
                // Use fast server-side pagination and sorting
                usingClientSideFiltering = false
                val response = fetchMoviesPaginated(
                    page = currentPage,
                    pageSize = itemsPerPage,
                    search = searchText.takeIf { it.isNotBlank() },
                    genre = selectedGenres.firstOrNull(),
                    subgenre = selectedSubgenres.firstOrNull(),
                    country = selectedCountries.firstOrNull(),
                    mediaType = selectedMediaTypes.firstOrNull(),
                    sortField = sortFieldStr,
                    sortDirection = sortDirectionStr
                )
                currentMovies = response.movies
                displayedMovies = response.movies
                totalCount = response.totalCount
                totalPages = response.totalPages
                currentPage = response.page
                renderCards()
            }
        } catch (e: Exception) {
            console.error("Failed to load movies:", e)
            showErrorState("Failed to load movies: ${e.message}")
        } finally {
            isLoading = false
        }
    }

    private fun showLoadingState() {
        val cardsContainer = document.getElementById("cards-container") ?: return
        cardsContainer.innerHTML = ""
        cardsContainer.append {
            div {
                style = "text-align: center; padding: 60px; color: #5f6368;"
                div {
                    style = "font-size: 48px; margin-bottom: 16px; animation: pulse 1.5s ease-in-out infinite;"
                    +"🎬"
                }
                +"Loading movies..."
            }
        }
    }

    private fun showErrorState(message: String) {
        val cardsContainer = document.getElementById("cards-container") ?: return
        cardsContainer.innerHTML = ""
        cardsContainer.append {
            div {
                style = "text-align: center; padding: 40px; color: #d93025; background-color: #fce8e6; border-radius: 8px;"
                +message
            }
        }
    }

    private fun renderCards(useClientSidePagination: Boolean = false) {
        // Determine pagination and count based on mode
        val paginatedMovies: List<MovieMetadata>
        val displayCount: Int
        val startIdx: Int
        val endIdx: Int

        if (useClientSidePagination) {
            // Client-side filtering: paginate displayedMovies locally
            val filteredCount = displayedMovies.size
            totalPages = if (filteredCount == 0) 1 else (filteredCount + itemsPerPage - 1) / itemsPerPage
            if (currentPage > totalPages) currentPage = totalPages

            startIdx = (currentPage - 1) * itemsPerPage
            endIdx = kotlin.math.min(startIdx + itemsPerPage, filteredCount)
            paginatedMovies = if (filteredCount > 0) displayedMovies.subList(startIdx, endIdx) else emptyList()
            displayCount = filteredCount
        } else {
            // Server-side pagination: displayedMovies is already the current page
            paginatedMovies = displayedMovies
            displayCount = totalCount
            startIdx = (currentPage - 1) * itemsPerPage
            endIdx = startIdx + paginatedMovies.size
        }

        // Update count display
        val countDiv = document.getElementById("results-count")
        if (displayCount > 0) {
            countDiv?.textContent = "Showing ${startIdx + 1}-$endIdx of $displayCount movies"
        } else {
            countDiv?.textContent = "No movies found"
        }
        countDiv?.setAttribute("style", "margin-bottom: 16px; font-size: 14px; color: #5f6368; font-family: 'Roboto', arial, sans-serif;")

        // Update cards
        val cardsContainer = document.getElementById("cards-container") ?: return
        cardsContainer.innerHTML = ""
        cardsContainer.append {
            if (paginatedMovies.isEmpty()) {
                div {
                    style = "text-align: center; padding: 60px; color: #5f6368; background-color: #f8f9fa; border-radius: 12px;"
                    div {
                        style = "font-size: 48px; margin-bottom: 16px;"
                        +"🎬"
                    }
                    +"No movies match your search criteria."
                }
            } else {
                // Cards grid
                div {
                    style = "display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 20px;"

                    paginatedMovies.forEach { movie ->
                        // Movie card
                        div {
                            style = """
                                background: linear-gradient(145deg, #ffffff 0%, #fafbfc 100%);
                                border-radius: 12px;
                                box-shadow: 0 2px 8px rgba(0,0,0,0.08), 0 1px 2px rgba(0,0,0,0.04);
                                overflow: hidden;
                                transition: transform 0.2s ease, box-shadow 0.2s ease;
                                display: flex;
                                flex-direction: column;
                            """.trimIndent()
                            attributes["onmouseover"] = "this.style.transform='translateY(-4px)'; this.style.boxShadow='0 8px 24px rgba(0,0,0,0.12), 0 4px 8px rgba(0,0,0,0.08)'"
                            attributes["onmouseout"] = "this.style.transform='translateY(0)'; this.style.boxShadow='0 2px 8px rgba(0,0,0,0.08), 0 1px 2px rgba(0,0,0,0.04)'"

                            // Card header with title and year
                            div {
                                style = """
                                    padding: 20px 20px 16px 20px;
                                    background: linear-gradient(135deg, #1a73e8 0%, #1557b0 100%);
                                    color: white;
                                    cursor: pointer;
                                """.trimIndent()
                                onClickFunction = {
                                    metadataModal.show(movie)
                                }

                                // Title
                                h3 {
                                    style = "margin: 0 0 8px 0; font-size: 18px; font-weight: 500; line-height: 1.3; font-family: 'Google Sans', 'Roboto', sans-serif; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;"
                                    +movie.title
                                }

                                // Year and runtime row
                                div {
                                    style = "display: flex; align-items: center; gap: 12px; font-size: 13px; opacity: 0.9;"
                                    if (movie.release_date != null) {
                                        span {
                                            style = "display: flex; align-items: center; gap: 4px;"
                                            +"📅 ${movie.release_date}"
                                        }
                                    }
                                    if (movie.runtime_mins != null) {
                                        span {
                                            style = "display: flex; align-items: center; gap: 4px;"
                                            val hours = movie.runtime_mins / 60
                                            val mins = movie.runtime_mins % 60
                                            val runtimeStr = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
                                            +"⏱ $runtimeStr"
                                        }
                                    }
                                }

                                // Date added (createdAt)
                                if (!movie.createdAt.isNullOrBlank()) {
                                    div {
                                        style = "font-size: 11px; opacity: 0.75; margin-top: 6px;"
                                        val dateAdded = movie.createdAt.substringBefore("T") // Extract date part from ISO datetime
                                        +"Added: $dateAdded"
                                    }
                                }
                            }

                            // Card body
                            div {
                                style = "padding: 16px 20px; flex: 1; display: flex; flex-direction: column; gap: 12px;"

                                // Genres
                                if (movie.genres.isNotEmpty()) {
                                    div {
                                        style = "display: flex; flex-wrap: wrap; gap: 6px;"
                                        movie.genres.take(4).forEach { genre ->
                                            span {
                                                style = "display: inline-block; padding: 4px 10px; background-color: #e8f0fe; color: #1967d2; border-radius: 16px; font-size: 11px; font-weight: 500;"
                                                +genre
                                            }
                                        }
                                        if (movie.genres.size > 4) {
                                            span {
                                                style = "display: inline-block; padding: 4px 10px; background-color: #f1f3f4; color: #5f6368; border-radius: 16px; font-size: 11px; font-weight: 500;"
                                                +"+${movie.genres.size - 4}"
                                            }
                                        }
                                    }
                                }

                                // Countries
                                if (movie.country.isNotEmpty()) {
                                    div {
                                        style = "display: flex; align-items: center; gap: 6px; font-size: 12px; color: #5f6368;"
                                        span { +"🌍" }
                                        span {
                                            +movie.country.take(3).joinToString(" • ")
                                            if (movie.country.size > 3) {
                                                +" +${movie.country.size - 3}"
                                            }
                                        }
                                    }
                                }

                                // Description preview
                                if (!movie.description.isNullOrBlank()) {
                                    p {
                                        style = """
                                            margin: 0;
                                            font-size: 13px;
                                            color: #5f6368;
                                            line-height: 1.5;
                                            display: -webkit-box;
                                            -webkit-line-clamp: 2;
                                            -webkit-box-orient: vertical;
                                            overflow: hidden;
                                            text-overflow: ellipsis;
                                        """.trimIndent()
                                        +movie.description
                                    }
                                }

                                // Physical media count badge
                                if (movie.physicalMedia.isNotEmpty()) {
                                    div {
                                        style = "display: flex; align-items: center; gap: 6px; margin-top: auto; padding-top: 8px; border-top: 1px solid #e8eaed;"
                                        span {
                                            style = "display: inline-flex; align-items: center; gap: 6px; padding: 4px 10px; background: linear-gradient(135deg, #e3f2fd 0%, #bbdefb 100%); color: #1565c0; border-radius: 12px; font-size: 11px; font-weight: 600;"
                                            +"💿 ${movie.physicalMedia.size} ${if (movie.physicalMedia.size == 1) "entry" else "entries"}"
                                        }
                                    }
                                }
                            }

                            // Card actions
                            div {
                                style = "padding: 12px 20px; background-color: #f8f9fa; border-top: 1px solid #e8eaed; display: flex; justify-content: flex-end; gap: 8px;"

                                button {
                                    style = """
                                        padding: 8px 16px;
                                        font-size: 13px;
                                        cursor: pointer;
                                        background-color: transparent;
                                        color: #1a73e8;
                                        border: none;
                                        border-radius: 4px;
                                        font-weight: 500;
                                        transition: background-color 0.2s;
                                    """.trimIndent()
                                    attributes["onmouseover"] = "this.style.backgroundColor='#e8f0fe'"
                                    attributes["onmouseout"] = "this.style.backgroundColor='transparent'"
                                    +"Edit"
                                    onClickFunction = {
                                        showEditMovieForm(movie)
                                    }
                                }
                                button {
                                    style = """
                                        padding: 8px 16px;
                                        font-size: 13px;
                                        cursor: pointer;
                                        background-color: transparent;
                                        color: #d93025;
                                        border: none;
                                        border-radius: 4px;
                                        font-weight: 500;
                                        transition: background-color 0.2s;
                                    """.trimIndent()
                                    attributes["onmouseover"] = "this.style.backgroundColor='#fce8e6'"
                                    attributes["onmouseout"] = "this.style.backgroundColor='transparent'"
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
            if (totalPages > 1 || paginatedMovies.isNotEmpty()) {
                div {
                    style = """
                        margin-top: 32px;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                        gap: 8px;
                        flex-wrap: wrap;
                    """.trimIndent()

                    // Previous button
                    button {
                        style = """
                            padding: 10px 20px;
                            font-size: 14px;
                            cursor: ${if (currentPage > 1) "pointer" else "not-allowed"};
                            background-color: ${if (currentPage > 1) "#1a73e8" else "#dadce0"};
                            color: ${if (currentPage > 1) "white" else "#5f6368"};
                            border: none;
                            border-radius: 24px;
                            font-weight: 500;
                            transition: all 0.2s;
                        """.trimIndent()
                        disabled = (currentPage <= 1)
                        if (currentPage > 1) {
                            attributes["onmouseover"] = "this.style.backgroundColor='#1765cc'; this.style.transform='scale(1.02)'"
                            attributes["onmouseout"] = "this.style.backgroundColor='#1a73e8'; this.style.transform='scale(1)'"
                        }
                        +"← Previous"
                        onClickFunction = {
                            if (currentPage > 1) {
                                currentPage--
                                renderCards(useClientSidePagination = usingClientSideFiltering)
                            }
                        }
                    }

                    // Page info
                    span {
                        style = "padding: 10px 20px; font-size: 14px; color: #5f6368; background-color: white; border-radius: 24px; border: 1px solid #dadce0;"
                        +"Page $currentPage of $totalPages"
                    }

                    // Next button
                    button {
                        style = """
                            padding: 10px 20px;
                            font-size: 14px;
                            cursor: ${if (currentPage < totalPages) "pointer" else "not-allowed"};
                            background-color: ${if (currentPage < totalPages) "#1a73e8" else "#dadce0"};
                            color: ${if (currentPage < totalPages) "white" else "#5f6368"};
                            border: none;
                            border-radius: 24px;
                            font-weight: 500;
                            transition: all 0.2s;
                        """.trimIndent()
                        disabled = (currentPage >= totalPages)
                        if (currentPage < totalPages) {
                            attributes["onmouseover"] = "this.style.backgroundColor='#1765cc'; this.style.transform='scale(1.02)'"
                            attributes["onmouseout"] = "this.style.backgroundColor='#1a73e8'; this.style.transform='scale(1)'"
                        }
                        +"Next →"
                        onClickFunction = {
                            if (currentPage < totalPages) {
                                currentPage++
                                renderCards(useClientSidePagination = usingClientSideFiltering)
                            }
                        }
                    }

                    // Items per page selector
                    div {
                        style = "margin-left: 16px; display: flex; align-items: center; gap: 8px;"
                        span {
                            style = "font-size: 13px; color: #5f6368;"
                            +"Per page:"
                        }
                        select {
                            style = """
                                padding: 8px 14px;
                                font-size: 14px;
                                border: 1px solid #dadce0;
                                border-radius: 24px;
                                background-color: white;
                                cursor: pointer;
                            """.trimIndent()

                            listOf(12, 24, 48, 96).forEach { size ->
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
                                    renderCards(useClientSidePagination = usingClientSideFiltering)
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
