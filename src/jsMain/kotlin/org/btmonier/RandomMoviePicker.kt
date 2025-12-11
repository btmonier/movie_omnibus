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
    // Multi-select filter state
    private var selectedGenres: MutableSet<String> = mutableSetOf()
    private var selectedSubgenres: MutableSet<String> = mutableSetOf()
    private var selectedCountries: MutableSet<String> = mutableSetOf()
    private var selectedMediaTypes: MutableSet<String> = mutableSetOf()
    
    private var genres: List<String> = emptyList()
    private var subgenres: List<String> = emptyList()
    private var countries: List<String> = emptyList()
    private var mediaTypes: List<String> = emptyList()
    
    // Dropdown open states
    private var genreDropdownOpen = false
    private var subgenreDropdownOpen = false
    private var countryDropdownOpen = false
    private var mediaTypeDropdownOpen = false
    
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
            // Navbar
            nav {
                style = """
                    position: sticky;
                    top: 0;
                    z-index: 1000;
                    background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
                    box-shadow: 0 2px 12px rgba(0,0,0,0.3);
                    padding: 0 24px;
                """.trimIndent()
                
                div {
                    style = """
                        max-width: 1400px;
                        margin: 0 auto;
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        height: 64px;
                    """.trimIndent()
                    
                    // Logo/Title (clickable to go back)
                    div {
                        style = """
                            display: flex;
                            align-items: center;
                            gap: 12px;
                            cursor: pointer;
                        """.trimIndent()
                        onClickFunction = {
                            onBack()
                        }
                        
                        span {
                            classes = setOf("mdi", "mdi-movie-open")
                            style = "font-size: 28px; color: #ffffff;"
                        }
                        h1 {
                            style = """
                                font-family: 'Oswald', sans-serif;
                                font-weight: 500;
                                font-size: 24px;
                                color: #ffffff;
                                margin: 0;
                                letter-spacing: 1px;
                            """.trimIndent()
                            +"The Movie Omnibus"
                        }
                    }
                    
                    // Nav links
                    div {
                        style = "display: flex; align-items: center; gap: 8px;"
                        
                        // Random button (active state)
                        a {
                            style = """
                                display: flex;
                                align-items: center;
                                gap: 6px;
                                padding: 10px 16px;
                                font-size: 14px;
                                font-weight: 500;
                                cursor: pointer;
                                background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                                color: white;
                                border: none;
                                border-radius: 6px;
                                text-decoration: none;
                                box-shadow: 0 2px 8px rgba(102, 126, 234, 0.4);
                            """.trimIndent()
                            span {
                                classes = setOf("mdi", "mdi-dice-multiple")
                                style = "font-size: 18px;"
                            }
                            span { +"Random" }
                        }
                    }
                }
            }

            // Main content
            div {
                style = "max-width: 900px; margin: 0 auto; padding: 40px 20px; font-family: 'Google Sans', 'Roboto', arial, sans-serif;"

                // Page title
                div {
                    style = "margin-bottom: 40px;"
                    h1 {
                        style = "font-family: 'Oswald', sans-serif; font-weight: 500; color: #202124; font-size: 28px; margin: 0; display: flex; align-items: center; gap: 12px; letter-spacing: 1px;"
                        span {
                            classes = setOf("mdi", "mdi-dice-multiple")
                            style = "font-size: 32px; color: #667eea;"
                        }
                        +"Random Movie Picker"
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
                        id = "filters-grid-container"
                        style = "display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 16px;"

                        // Genre filter
                        renderMultiSelectDropdown(
                            label = "Genre",
                            iconClass = "mdi-drama-masks",
                            options = genres,
                            selectedOptions = selectedGenres,
                            isOpen = genreDropdownOpen,
                            onToggle = {
                                genreDropdownOpen = !genreDropdownOpen
                                subgenreDropdownOpen = false
                                countryDropdownOpen = false
                                mediaTypeDropdownOpen = false
                                renderFiltersSection()
                            },
                            onOptionToggle = { value ->
                                if (value in selectedGenres) {
                                    selectedGenres.remove(value)
                                } else {
                                    selectedGenres.add(value)
                                }
                                renderFiltersSection()
                                mainScope.launch {
                                    updateCount()
                                    updateCountDisplay()
                                }
                            },
                            onClear = {
                                selectedGenres.clear()
                                renderFiltersSection()
                                mainScope.launch {
                                    updateCount()
                                    updateCountDisplay()
                                }
                            }
                        )
                        
                        // Subgenre filter
                        renderMultiSelectDropdown(
                            label = "Subgenre",
                            iconClass = "mdi-tag-outline",
                            options = subgenres,
                            selectedOptions = selectedSubgenres,
                            isOpen = subgenreDropdownOpen,
                            onToggle = {
                                subgenreDropdownOpen = !subgenreDropdownOpen
                                genreDropdownOpen = false
                                countryDropdownOpen = false
                                mediaTypeDropdownOpen = false
                                renderFiltersSection()
                            },
                            onOptionToggle = { value ->
                                if (value in selectedSubgenres) {
                                    selectedSubgenres.remove(value)
                                } else {
                                    selectedSubgenres.add(value)
                                }
                                renderFiltersSection()
                                mainScope.launch {
                                    updateCount()
                                    updateCountDisplay()
                                }
                            },
                            onClear = {
                                selectedSubgenres.clear()
                                renderFiltersSection()
                                mainScope.launch {
                                    updateCount()
                                    updateCountDisplay()
                                }
                            }
                        )
                        
                        // Country filter
                        renderMultiSelectDropdown(
                            label = "Country",
                            iconClass = "mdi-earth",
                            options = countries,
                            selectedOptions = selectedCountries,
                            isOpen = countryDropdownOpen,
                            onToggle = {
                                countryDropdownOpen = !countryDropdownOpen
                                genreDropdownOpen = false
                                subgenreDropdownOpen = false
                                mediaTypeDropdownOpen = false
                                renderFiltersSection()
                            },
                            onOptionToggle = { value ->
                                if (value in selectedCountries) {
                                    selectedCountries.remove(value)
                                } else {
                                    selectedCountries.add(value)
                                }
                                renderFiltersSection()
                                mainScope.launch {
                                    updateCount()
                                    updateCountDisplay()
                                }
                            },
                            onClear = {
                                selectedCountries.clear()
                                renderFiltersSection()
                                mainScope.launch {
                                    updateCount()
                                    updateCountDisplay()
                                }
                            }
                        )
                        
                        // Media Type filter
                        renderMultiSelectDropdown(
                            label = "Media Type",
                            iconClass = "mdi-disc",
                            options = mediaTypes,
                            selectedOptions = selectedMediaTypes,
                            isOpen = mediaTypeDropdownOpen,
                            onToggle = {
                                mediaTypeDropdownOpen = !mediaTypeDropdownOpen
                                genreDropdownOpen = false
                                subgenreDropdownOpen = false
                                countryDropdownOpen = false
                                renderFiltersSection()
                            },
                            onOptionToggle = { value ->
                                if (value in selectedMediaTypes) {
                                    selectedMediaTypes.remove(value)
                                } else {
                                    selectedMediaTypes.add(value)
                                }
                                renderFiltersSection()
                                mainScope.launch {
                                    updateCount()
                                    updateCountDisplay()
                                }
                            },
                            onClear = {
                                selectedMediaTypes.clear()
                                renderFiltersSection()
                                mainScope.launch {
                                    updateCount()
                                    updateCountDisplay()
                                }
                            }
                        )
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
                        span {
                            classes = setOf("mdi", "mdi-movie-open")
                            style = "font-size: 20px; margin-right: 8px;"
                        }
                        +"Pick a Random Movie"
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

    private fun DIV.renderMultiSelectDropdown(
        label: String,
        iconClass: String,
        options: List<String>,
        selectedOptions: Set<String>,
        isOpen: Boolean,
        onToggle: () -> Unit,
        onOptionToggle: (String) -> Unit,
        onClear: () -> Unit
    ) {
        div {
            style = "position: relative;"
            attributes["class"] = "filter-dropdown"

            label {
                style = "display: flex; align-items: center; gap: 4px; margin-bottom: 6px; font-weight: 500; font-size: 13px; color: #5f6368;"
                span {
                    classes = setOf("mdi", iconClass)
                    style = "font-size: 16px;"
                }
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
                    classes = setOf("mdi", if (isOpen) "mdi-chevron-up" else "mdi-chevron-down")
                    style = "color: #5f6368; font-size: 16px; margin-left: 8px; flex-shrink: 0;"
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
                        max-height: 280px;
                        overflow-y: auto;
                    """.trimIndent()

                    // Clear selection button (if any selected)
                    if (selectedOptions.isNotEmpty()) {
                        div {
                            style = """
                                padding: 10px 14px;
                                cursor: pointer;
                                display: flex;
                                align-items: center;
                                gap: 8px;
                                background-color: white;
                                color: #d93025;
                                font-size: 13px;
                                font-weight: 500;
                                border-bottom: 1px solid #e8eaed;
                                transition: background-color 0.15s;
                            """.trimIndent()
                            attributes["onmouseover"] = "this.style.backgroundColor='#fce8e6'"
                            attributes["onmouseout"] = "this.style.backgroundColor='white'"
                            onClickFunction = { event ->
                                event.stopPropagation()
                                onClear()
                            }

                            span {
                                classes = setOf("mdi", "mdi-close")
                                style = "font-size: 14px;"
                            }
                            span { +"Clear selection" }
                        }
                    }

                    // Options with checkboxes
                    options.forEach { opt ->
                        div {
                            val isSelected = opt in selectedOptions
                            style = """
                                padding: 10px 14px;
                                cursor: pointer;
                                display: flex;
                                align-items: center;
                                gap: 10px;
                                background-color: white;
                                color: #202124;
                                font-size: 14px;
                                transition: background-color 0.15s;
                            """.trimIndent()
                            attributes["onmouseover"] = "this.style.backgroundColor='#f8f9fa'"
                            attributes["onmouseout"] = "this.style.backgroundColor='white'"
                            onClickFunction = { event ->
                                event.stopPropagation()
                                onOptionToggle(opt)
                            }

                            // Checkbox
                            div {
                                style = """
                                    width: 18px;
                                    height: 18px;
                                    border: 2px solid ${if (isSelected) "#1a73e8" else "#5f6368"};
                                    border-radius: 3px;
                                    background-color: ${if (isSelected) "#1a73e8" else "white"};
                                    display: flex;
                                    align-items: center;
                                    justify-content: center;
                                    flex-shrink: 0;
                                    transition: all 0.15s;
                                """.trimIndent()

                                if (isSelected) {
                                    span {
                                        classes = setOf("mdi", "mdi-check")
                                        style = "color: white; font-size: 14px;"
                                    }
                                }
                            }

                            span { +opt }
                        }
                    }

                    // Empty state
                    if (options.isEmpty()) {
                        div {
                            style = "padding: 16px; text-align: center; color: #5f6368; font-size: 13px;"
                            +"No options available"
                        }
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
                    margin: 0 12px;
                """.trimIndent()

                h2 {
                    style = "margin: 0 0 8px 0; color: #202124; font-size: 28px; font-weight: 500; display: flex; align-items: center; justify-content: center; gap: 10px;"
                    span {
                        classes = setOf("mdi", "mdi-party-popper")
                        style = "font-size: 32px; color: #f9a825;"
                    }
                    +"Tonight's Pick"
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
                            span {
                                classes = setOf("mdi", "mdi-disc")
                                style = "font-size: 20px; color: #1a73e8;"
                            }
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
                                            span {
                                                classes = setOf("mdi", "mdi-map-marker")
                                                style = "font-size: 16px;"
                                            }
                                            span {
                                                style = "font-weight: 500;"
                                                +location
                                            }
                                        }
                                    }

                                    media.distributor?.let { distributor ->
                                        div {
                                            style = "display: flex; align-items: center; gap: 6px;"
                                            span {
                                                classes = setOf("mdi", "mdi-domain")
                                                style = "font-size: 16px;"
                                            }
                                            span { +distributor }
                                        }
                                    }

                                    media.releaseDate?.let { date ->
                                        div {
                                            style = "display: flex; align-items: center; gap: 6px;"
                                            span {
                                                classes = setOf("mdi", "mdi-calendar")
                                                style = "font-size: 16px;"
                                            }
                                            span { +date }
                                        }
                                    }
                                }

                                // Images
                                if (media.images.isNotEmpty()) {
                                    div {
                                        style = "margin-top: 16px;"
                                        
                                        div {
                                            style = "font-size: 13px; color: #5f6368; margin-bottom: 8px; font-weight: 500; display: flex; align-items: center; gap: 6px;"
                                            span {
                                                classes = setOf("mdi", "mdi-camera")
                                                style = "font-size: 16px;"
                                            }
                                            +"Images (${media.images.size})"
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

                // Action buttons - responsive layout
                div {
                    id = "action-buttons-container"
                    style = """
                        display: flex;
                        flex-wrap: wrap;
                        gap: 12px;
                        justify-content: center;
                        margin-top: 20px;
                    """.trimIndent()

                    button {
                        classes = setOf("action-btn")
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
                            flex: 1 1 auto;
                            min-width: 150px;
                            max-width: 200px;
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
                            classes = setOf("action-btn")
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
                                flex: 1 1 auto;
                                min-width: 150px;
                                max-width: 200px;
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
                        classes = setOf("action-btn")
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
                            flex: 1 1 auto;
                            min-width: 150px;
                            max-width: 200px;
                        """.trimIndent()
                        attributes["onmouseover"] = "this.style.backgroundColor='#e8eaed'"
                        attributes["onmouseout"] = "this.style.backgroundColor='#f1f3f4'"
                        span {
                            classes = setOf("mdi", "mdi-dice-multiple")
                            style = "font-size: 16px; margin-right: 6px;"
                        }
                        +"Pick Again"
                        onClickFunction = {
                            pickRandomMovie()
                        }
                    }

                    // Letterboxd link
                    if (movie.url.isNotBlank()) {
                        a {
                            href = movie.url
                            target = "_blank"
                            classes = setOf("action-btn")
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
                                display: flex;
                                align-items: center;
                                justify-content: center;
                                transition: background-color 0.2s;
                                flex: 1 1 auto;
                                min-width: 150px;
                                max-width: 200px;
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
        // Filter handlers are now managed via dropdown callbacks
        // Add click-outside listener to close dropdowns
        document.addEventListener("click", { event ->
            val target = event.target as? Element
            val clickedDropdown = target?.closest(".filter-dropdown")
            if (clickedDropdown == null && (genreDropdownOpen || subgenreDropdownOpen || countryDropdownOpen || mediaTypeDropdownOpen)) {
                genreDropdownOpen = false
                subgenreDropdownOpen = false
                countryDropdownOpen = false
                mediaTypeDropdownOpen = false
                renderFiltersSection()
            }
        })
    }

    private fun renderFiltersSection() {
        val filtersContainer = document.getElementById("filters-grid-container")
        filtersContainer?.innerHTML = ""
        filtersContainer?.append {
            div {
                style = "display: contents;"
                
                // Genre filter
                renderMultiSelectDropdown(
                    label = "Genre",
                    iconClass = "mdi-drama-masks",
                    options = genres,
                    selectedOptions = selectedGenres,
                    isOpen = genreDropdownOpen,
                    onToggle = {
                        genreDropdownOpen = !genreDropdownOpen
                        subgenreDropdownOpen = false
                        countryDropdownOpen = false
                        mediaTypeDropdownOpen = false
                        renderFiltersSection()
                    },
                    onOptionToggle = { value ->
                        if (value in selectedGenres) {
                            selectedGenres.remove(value)
                        } else {
                            selectedGenres.add(value)
                        }
                        renderFiltersSection()
                        mainScope.launch {
                            updateCount()
                            updateCountDisplay()
                        }
                    },
                    onClear = {
                        selectedGenres.clear()
                        renderFiltersSection()
                        mainScope.launch {
                            updateCount()
                            updateCountDisplay()
                        }
                    }
                )
                
                // Subgenre filter
                renderMultiSelectDropdown(
                    label = "Subgenre",
                    iconClass = "mdi-tag-outline",
                    options = subgenres,
                    selectedOptions = selectedSubgenres,
                    isOpen = subgenreDropdownOpen,
                    onToggle = {
                        subgenreDropdownOpen = !subgenreDropdownOpen
                        genreDropdownOpen = false
                        countryDropdownOpen = false
                        mediaTypeDropdownOpen = false
                        renderFiltersSection()
                    },
                    onOptionToggle = { value ->
                        if (value in selectedSubgenres) {
                            selectedSubgenres.remove(value)
                        } else {
                            selectedSubgenres.add(value)
                        }
                        renderFiltersSection()
                        mainScope.launch {
                            updateCount()
                            updateCountDisplay()
                        }
                    },
                    onClear = {
                        selectedSubgenres.clear()
                        renderFiltersSection()
                        mainScope.launch {
                            updateCount()
                            updateCountDisplay()
                        }
                    }
                )
                
                // Country filter
                renderMultiSelectDropdown(
                    label = "Country",
                    iconClass = "mdi-earth",
                    options = countries,
                    selectedOptions = selectedCountries,
                    isOpen = countryDropdownOpen,
                    onToggle = {
                        countryDropdownOpen = !countryDropdownOpen
                        genreDropdownOpen = false
                        subgenreDropdownOpen = false
                        mediaTypeDropdownOpen = false
                        renderFiltersSection()
                    },
                    onOptionToggle = { value ->
                        if (value in selectedCountries) {
                            selectedCountries.remove(value)
                        } else {
                            selectedCountries.add(value)
                        }
                        renderFiltersSection()
                        mainScope.launch {
                            updateCount()
                            updateCountDisplay()
                        }
                    },
                    onClear = {
                        selectedCountries.clear()
                        renderFiltersSection()
                        mainScope.launch {
                            updateCount()
                            updateCountDisplay()
                        }
                    }
                )
                
                // Media Type filter
                renderMultiSelectDropdown(
                    label = "Media Type",
                    iconClass = "mdi-disc",
                    options = mediaTypes,
                    selectedOptions = selectedMediaTypes,
                    isOpen = mediaTypeDropdownOpen,
                    onToggle = {
                        mediaTypeDropdownOpen = !mediaTypeDropdownOpen
                        genreDropdownOpen = false
                        subgenreDropdownOpen = false
                        countryDropdownOpen = false
                        renderFiltersSection()
                    },
                    onOptionToggle = { value ->
                        if (value in selectedMediaTypes) {
                            selectedMediaTypes.remove(value)
                        } else {
                            selectedMediaTypes.add(value)
                        }
                        renderFiltersSection()
                        mainScope.launch {
                            updateCount()
                            updateCountDisplay()
                        }
                    },
                    onClear = {
                        selectedMediaTypes.clear()
                        renderFiltersSection()
                        mainScope.launch {
                            updateCount()
                            updateCountDisplay()
                        }
                    }
                )
            }
        }
    }

    private suspend fun updateCount() {
        availableCount = fetchUnwatchedMovieCount(
            genres = selectedGenres,
            subgenres = selectedSubgenres,
            countries = selectedCountries,
            mediaTypes = selectedMediaTypes
        )
    }

    private fun updateCountDisplay() {
        val countSpan = document.getElementById("available-count")
        countSpan?.textContent = "$availableCount unwatched movie${if (availableCount != 1) "s" else ""} available"
    }

    private fun clearFilters() {
        selectedGenres.clear()
        selectedSubgenres.clear()
        selectedCountries.clear()
        selectedMediaTypes.clear()

        // Close all dropdowns
        genreDropdownOpen = false
        subgenreDropdownOpen = false
        countryDropdownOpen = false
        mediaTypeDropdownOpen = false

        // Re-render filters and update count
        renderFiltersSection()
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
                    genres = selectedGenres,
                    subgenres = selectedSubgenres,
                    countries = selectedCountries,
                    mediaTypes = selectedMediaTypes
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

