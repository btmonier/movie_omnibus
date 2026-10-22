package org.btmonier

import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.coroutines.launch
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.onChangeFunction
import kotlinx.html.js.onClickFunction
import kotlinx.html.js.onInputFunction
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement

private const val SEARCH_INPUT_ID = "release-search-input"
private const val SEARCH_CLEAR_ID = "release-search-clear"
private const val CLEAR_FILTERS_ID = "release-clear-filters"
private const val VIEW_MODE_STORAGE_KEY = "releaseViewMode"

/**
 * Browse the physical shelf by release rather than by film: every box set, disc
 * and tape is one card, showing how many films it holds. Clicking a card opens
 * [ReleaseDetail], which lists the films on it.
 */
class ReleaseBrowser(private val container: Element) {
    private var releases: List<ReleaseSummary> = emptyList()
    private var totalCount: Int = 0
    private var totalPages: Int = 1
    private var currentPage: Int = 1
    private var itemsPerPage: Int = 24

    private var searchText: String = ""
    private var selectedMediaType: String = ""
    private var selectedDistributor: String = ""
    private var selectedLocation: String = ""
    private var collectionsOnly: Boolean = false
    private var sortField: String = "title"
    private var sortDirection: String = "asc"
    private var viewMode: ReleaseViewMode = ReleaseViewMode.entries
        .firstOrNull { it.name == localStorage.getItem(VIEW_MODE_STORAGE_KEY) } ?: ReleaseViewMode.GRID

    private var mediaTypeOptions: List<String> = emptyList()
    private var distributorOptions: List<String> = emptyList()
    private var isLoading: Boolean = false

    private val alertDialog = AlertDialog(container)

    fun show() {
        render()
        mainScope.launch {
            try {
                mediaTypeOptions = fetchAllMediaTypes()
                distributorOptions = fetchAllDistributors().map { it.name }
                renderFilterBar()
            } catch (e: Exception) {
                console.error("Error loading release filter options:", e)
            }
            loadReleases()
        }
    }

    private suspend fun loadReleases() {
        isLoading = true
        renderResults()
        try {
            val response = fetchReleasesPaginated(
                page = currentPage,
                pageSize = itemsPerPage,
                search = searchText.takeIf { it.isNotBlank() },
                mediaType = selectedMediaType.takeIf { it.isNotBlank() },
                distributor = selectedDistributor.takeIf { it.isNotBlank() },
                location = selectedLocation.takeIf { it.isNotBlank() },
                collectionsOnly = collectionsOnly,
                sortField = sortField,
                sortDirection = sortDirection
            )
            releases = response.releases
            totalCount = response.totalCount
            totalPages = response.totalPages
            currentPage = response.page
        } catch (e: Exception) {
            releases = emptyList()
            totalCount = 0
            totalPages = 1
            alertDialog.show(title = "Error", message = "Failed to load releases: ${e.message}")
        } finally {
            isLoading = false
            renderResults()
        }
    }

    private fun reload(resetPage: Boolean = true) {
        if (resetPage) currentPage = 1
        mainScope.launch { loadReleases() }
    }

    private fun openRelease(id: Int) {
        ReleaseDetail(container, releaseId = id, onBack = { show() }).show()
    }

    /**
     * Enter a release by hand, without a film or a wishlist item behind it. The
     * new release opens once saved, so its films can be added from there.
     */
    fun showAddForm() {
        PhysicalMediaForm(
            container,
            onSave = { media, purchaseEdit ->
                val created = createRelease(
                    Release(
                        mediaTypes = media.mediaTypes,
                        title = media.title,
                        isCollection = media.isCollection,
                        distributor = media.distributor,
                        releaseDate = media.releaseDate,
                        blurayComUrl = media.blurayComUrl,
                        location = media.location,
                        images = media.images
                    )
                )
                val id = created.id ?: throw Exception("The server did not return the new release's id.")
                val purchaseError = (purchaseEdit as? PurchaseEdit.Save)?.let { save ->
                    runCatching { saveReleasePurchase(id, save.purchase) }.exceptionOrNull()
                }
                if (purchaseError != null) {
                    alertDialog.show(
                        title = "Purchase not saved",
                        message = "The release was added, but its purchase could not be saved: ${purchaseError.message}\n\n" +
                            "You can record it from the release's Purchase panel.",
                        onClose = { openRelease(id) }
                    )
                } else {
                    openRelease(id)
                }
            },
            onCancel = {},
            onOpenExisting = { existing -> openRelease(existing.id) }
        ).showCreateRelease()
    }

    private fun render() {
        container.innerHTML = ""
        container.append {
            // Renamed distributors change both the filter options and the cards
            appNavbar(NavSection.PHYSICAL_MEDIA, onCategoriesClosed = { show() })

            div {
                style = "max-width: 1400px; margin: 0 auto; padding: 32px 20px; font-family: 'Google Sans', 'Roboto', arial, sans-serif;"

                div {
                    style = "margin-bottom: 8px; display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap;"
                    h1 {
                        style = "font-family: 'Oswald', sans-serif; font-weight: 500; color: #202124; font-size: 28px; margin: 0; display: flex; align-items: center; gap: 12px; letter-spacing: 1px;"
                        span {
                            classes = setOf("mdi", NavSection.PHYSICAL_MEDIA.icon)
                            style = "font-size: 32px; color: #667eea;"
                        }
                        +NavSection.PHYSICAL_MEDIA.label
                    }
                    addItemButton { showAddForm() }
                }

                p {
                    style = "color: #5f6368; font-size: 15px; margin: 0 0 24px 0; line-height: 1.6;"
                    +"Every physical unit you own, listed once. Open a release to see which films are on it."
                }

                div { id = "release-filters" }

                div { id = "release-results" }
            }
        }
        renderFilterBar()
    }

    private fun renderFilterBar() {
        val bar = document.getElementById("release-filters") ?: return
        bar.innerHTML = ""
        bar.append { div { renderFilters() } }
    }

    private fun FlowContent.renderFilters() {
        div {
            style = """
                background-color: #f8f9fa;
                padding: 20px;
                border-radius: 12px;
                margin-bottom: 24px;
                border: 1px solid #e8eaed;
                display: flex;
                flex-wrap: wrap;
                gap: 16px;
                align-items: flex-end;
            """.trimIndent()

            div {
                style = "flex: 2 1 260px; min-width: 220px;"
                filterLabel("Search")
                div {
                    style = "position: relative;"
                    input(type = InputType.text) {
                        id = SEARCH_INPUT_ID
                        value = searchText
                        placeholder = "Release title, film, distributor or URL"
                        style = textInputStyle() + " padding-right: 34px;"
                        onInputFunction = { event ->
                            searchText = (event.target as HTMLInputElement).value
                            syncClearControls()
                        }
                        onChangeFunction = { reload() }
                    }
                    button {
                        id = SEARCH_CLEAR_ID
                        attributes["title"] = "Clear the search"
                        attributes["type"] = "button"
                        style = searchClearStyle(searchText.isNotBlank())
                        span { classes = setOf("mdi", "mdi-close"); style = "font-size: 18px;" }
                        onClickFunction = { clearSearch() }
                    }
                }
            }

            div {
                style = "flex: 1 1 150px; min-width: 140px;"
                filterLabel("Format")
                select {
                    style = textInputStyle()
                    option {
                        value = ""
                        selected = selectedMediaType.isEmpty()
                        +"All formats"
                    }
                    mediaTypeOptions.forEach { type ->
                        option {
                            value = type
                            selected = selectedMediaType == type
                            +type
                        }
                    }
                    onChangeFunction = { event ->
                        selectedMediaType = (event.target as HTMLSelectElement).value
                        syncClearControls()
                        reload()
                    }
                }
            }

            div {
                style = "flex: 1 1 180px; min-width: 160px;"
                filterLabel("Distributor")
                select {
                    style = textInputStyle()
                    option {
                        value = ""
                        selected = selectedDistributor.isEmpty()
                        +"All distributors"
                    }
                    distributorOptions.forEach { name ->
                        option {
                            value = name
                            selected = selectedDistributor == name
                            +name
                        }
                    }
                    onChangeFunction = { event ->
                        selectedDistributor = (event.target as HTMLSelectElement).value
                        syncClearControls()
                        reload()
                    }
                }
            }

            div {
                style = "flex: 1 1 130px; min-width: 120px;"
                filterLabel("Location")
                select {
                    style = textInputStyle()
                    listOf("" to "Anywhere", "Archive" to "Archive", "Shelf" to "Shelf").forEach { (value, label) ->
                        option {
                            this.value = value
                            selected = selectedLocation == value
                            +label
                        }
                    }
                    onChangeFunction = { event ->
                        selectedLocation = (event.target as HTMLSelectElement).value
                        syncClearControls()
                        reload()
                    }
                }
            }

            div {
                style = "flex: 1 1 150px; min-width: 140px;"
                filterLabel("Sort by")
                select {
                    style = textInputStyle()
                    listOf(
                        "title" to "Title",
                        "film_count" to "Film count",
                        "release_date" to "Release date",
                        "date_added" to "Date added"
                    ).forEach { (value, label) ->
                        option {
                            this.value = value
                            selected = sortField == value
                            +label
                        }
                    }
                    onChangeFunction = { event ->
                        sortField = (event.target as HTMLSelectElement).value
                        reload()
                    }
                }
            }

            button {
                style = """
                    padding: 10px 14px;
                    font-size: 14px;
                    cursor: pointer;
                    background-color: white;
                    color: #3c4043;
                    border: 1px solid #dadce0;
                    border-radius: 4px;
                    font-weight: 500;
                    height: 40px;
                """.trimIndent()
                attributes["title"] = if (sortDirection == "asc") "Ascending" else "Descending"
                span {
                    classes = setOf("mdi", if (sortDirection == "asc") "mdi-sort-ascending" else "mdi-sort-descending")
                    style = "font-size: 18px;"
                }
                onClickFunction = {
                    sortDirection = if (sortDirection == "asc") "desc" else "asc"
                    renderFilterBar()
                    reload(resetPage = false)
                }
            }

            label {
                style = "display: flex; align-items: center; gap: 8px; font-size: 14px; color: #202124; cursor: pointer; height: 40px;"
                input(type = InputType.checkBox) {
                    checked = collectionsOnly
                    style = "cursor: pointer;"
                    onChangeFunction = { event ->
                        collectionsOnly = (event.target as HTMLInputElement).checked
                        syncClearControls()
                        reload()
                    }
                }
                +"Box sets only"
            }

            button {
                id = CLEAR_FILTERS_ID
                attributes["type"] = "button"
                attributes["title"] = "Reset every filter"
                style = clearFiltersStyle(hasActiveFilters())
                span { classes = setOf("mdi", "mdi-filter-remove-outline"); style = "font-size: 18px;" }
                +"Clear filters"
                onClickFunction = { clearFilters() }
            }
        }
    }

    private fun hasActiveFilters(): Boolean =
        searchText.isNotBlank() || selectedMediaType.isNotEmpty() || selectedDistributor.isNotEmpty() ||
            selectedLocation.isNotEmpty() || collectionsOnly

    private fun clearSearch() {
        if (searchText.isBlank()) return
        searchText = ""
        (document.getElementById(SEARCH_INPUT_ID) as? HTMLInputElement)?.value = ""
        syncClearControls()
        reload()
    }

    private fun clearFilters() {
        if (!hasActiveFilters()) return
        searchText = ""
        selectedMediaType = ""
        selectedDistributor = ""
        selectedLocation = ""
        collectionsOnly = false
        renderFilterBar()
        reload()
    }

    /**
     * Show the clear controls only when there is something to clear. Patched in
     * place rather than re-rendered, which would take the focus out of the
     * search field mid-word.
     */
    private fun syncClearControls() {
        (document.getElementById(SEARCH_CLEAR_ID) as? HTMLElement)
            ?.setAttribute("style", searchClearStyle(searchText.isNotBlank()))
        (document.getElementById(CLEAR_FILTERS_ID) as? HTMLElement)
            ?.setAttribute("style", clearFiltersStyle(hasActiveFilters()))
    }

    private fun searchClearStyle(visible: Boolean): String = """
        position: absolute; right: 4px; top: 50%; transform: translateY(-50%);
        background: none; border: none; padding: 4px; cursor: pointer; color: #5f6368;
        display: ${if (visible) "flex" else "none"}; align-items: center;
    """.trimIndent()

    private fun clearFiltersStyle(visible: Boolean): String = """
        display: ${if (visible) "flex" else "none"}; align-items: center; gap: 6px;
        padding: 0 14px; height: 40px; font-size: 14px; font-weight: 500; cursor: pointer;
        background-color: white; color: #1a73e8; border: 1px solid #dadce0; border-radius: 4px;
    """.trimIndent()

    private fun FlowContent.filterLabel(text: String) {
        label {
            style = "display: block; margin-bottom: 6px; font-weight: 500; font-size: 13px; color: #5f6368;"
            +text
        }
    }

    private fun textInputStyle(): String = """
        width: 100%;
        padding: 10px 12px;
        font-size: 14px;
        border: 1px solid #dadce0;
        border-radius: 4px;
        box-sizing: border-box;
        background-color: white;
        font-family: 'Roboto', arial, sans-serif;
    """.trimIndent()

    private fun renderResults() {
        val results = document.getElementById("release-results") ?: return
        results.innerHTML = ""

        results.append {
            if (isLoading) {
                div {
                    style = "padding: 60px 20px; text-align: center; color: #5f6368; font-size: 15px;"
                    +"Loading releases..."
                }
                return@append
            }

            div {
                style = "margin-bottom: 16px; display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap;"
                span {
                    style = "color: #5f6368; font-size: 14px;"
                    +resultsSummary()
                }
                viewModeToggle()
            }

            if (releases.isEmpty()) {
                div {
                    style = """
                        padding: 60px 20px;
                        text-align: center;
                        color: #5f6368;
                        background-color: #f8f9fa;
                        border: 1px dashed #dadce0;
                        border-radius: 12px;
                    """.trimIndent()
                    +"No releases match these filters."
                }
                return@append
            }

            when (viewMode) {
                ReleaseViewMode.GRID -> div {
                    style = "display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 20px;"
                    releases.forEach { release -> releaseCard(release) }
                }
                ReleaseViewMode.COMPACT -> div {
                    style = "display: grid; grid-template-columns: repeat(auto-fill, minmax(140px, 1fr)); gap: 14px;"
                    releases.forEach { release -> compactReleaseCard(release) }
                }
                ReleaseViewMode.LIST -> div {
                    style = "display: flex; flex-direction: column; border: 1px solid #e8eaed; border-radius: 12px; overflow: hidden; background-color: white;"
                    releases.forEachIndexed { index, release -> releaseRow(release, isFirst = index == 0) }
                }
            }

            div {
                paginationControls(
                    currentPage = currentPage,
                    totalPages = totalPages,
                    itemsPerPage = itemsPerPage,
                    onPageChange = { page ->
                        currentPage = page
                        reload(resetPage = false)
                    },
                    onPageSizeChange = { size ->
                        itemsPerPage = size
                        reload()
                    }
                )
            }
        }
    }

    private fun resultsSummary(): String {
        if (totalCount == 0) return "No releases"
        val first = (currentPage - 1) * itemsPerPage + 1
        val last = minOf(currentPage * itemsPerPage, totalCount)
        return "Showing $first-$last of $totalCount releases"
    }

    private fun FlowContent.releaseCard(release: ReleaseSummary) {
        div {
            style = """
                background-color: white;
                border: 1px solid #e8eaed;
                border-radius: 12px;
                overflow: hidden;
                cursor: pointer;
                display: flex;
                flex-direction: column;
                transition: transform 0.15s, box-shadow 0.15s;
            """.trimIndent()
            attributes["onmouseover"] = "this.style.transform='translateY(-3px)'; this.style.boxShadow='0 8px 20px rgba(0,0,0,0.12)'"
            attributes["onmouseout"] = "this.style.transform='translateY(0)'; this.style.boxShadow='none'"
            onClickFunction = { release.id.let(::openRelease) }

            div {
                style = """
                    height: 260px;
                    background-color: #f1f3f4;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    position: relative;
                """.trimIndent()

                releaseCover(release, iconSize = 56)
                filmCountBadge(release, top = 10, fontSize = 12)
            }

            div {
                style = "padding: 14px; display: flex; flex-direction: column; gap: 6px; flex: 1;"

                div {
                    style = "font-weight: 500; font-size: 15px; color: #202124; line-height: 1.35;"
                    +displayTitle(release)
                }

                subtitle(release)?.let { text ->
                    div {
                        style = "font-size: 12px; color: #80868b; line-height: 1.4;"
                        +text
                    }
                }

                div {
                    style = "display: flex; flex-wrap: wrap; gap: 6px; margin-top: 4px;"
                    releaseChips(release)
                }

                div {
                    style = "font-size: 12px; color: #5f6368; margin-top: auto; padding-top: 6px;"
                    +detailsLine(release)
                }
            }
        }
    }

    /** Small cover-first tile: many more releases on screen, details on hover. */
    private fun FlowContent.compactReleaseCard(release: ReleaseSummary) {
        div {
            style = """
                background-color: white;
                border: 1px solid #e8eaed;
                border-radius: 8px;
                overflow: hidden;
                cursor: pointer;
                display: flex;
                flex-direction: column;
                transition: transform 0.15s, box-shadow 0.15s;
            """.trimIndent()
            attributes["title"] = listOf(displayTitle(release), detailsLine(release))
                .filter { it.isNotBlank() }
                .joinToString("\n")
            attributes["onmouseover"] = "this.style.transform='translateY(-2px)'; this.style.boxShadow='0 4px 12px rgba(0,0,0,0.12)'"
            attributes["onmouseout"] = "this.style.transform='translateY(0)'; this.style.boxShadow='none'"
            onClickFunction = { openRelease(release.id) }

            div {
                style = """
                    height: 180px;
                    background-color: #f1f3f4;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    position: relative;
                """.trimIndent()
                releaseCover(release, iconSize = 40)
                filmCountBadge(release, top = 6, fontSize = 11)
            }

            div {
                style = "padding: 8px 10px; display: flex; flex-direction: column; gap: 2px;"
                div {
                    style = "font-weight: 500; font-size: 13px; color: #202124; line-height: 1.3; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;"
                    +displayTitle(release)
                }
                div {
                    style = "font-size: 11px; color: #80868b; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;"
                    +release.mediaTypes.joinToString(" · ") { mediaTypeLabel(it) }
                }
            }
        }
    }

    /** One release per row: a thumbnail and everything else read across. */
    private fun FlowContent.releaseRow(release: ReleaseSummary, isFirst: Boolean) {
        div {
            style = """
                display: flex;
                align-items: center;
                gap: 16px;
                padding: 10px 16px;
                cursor: pointer;
                ${if (isFirst) "" else "border-top: 1px solid #e8eaed;"}
            """.trimIndent()
            attributes["onmouseover"] = "this.style.backgroundColor='#f8f9fa'"
            attributes["onmouseout"] = "this.style.backgroundColor='white'"
            onClickFunction = { openRelease(release.id) }

            div {
                style = """
                    width: 48px;
                    height: 64px;
                    flex-shrink: 0;
                    background-color: #f1f3f4;
                    border-radius: 4px;
                    overflow: hidden;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                """.trimIndent()
                releaseCover(release, iconSize = 24)
            }

            div {
                style = "flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4px;"
                div {
                    style = "font-weight: 500; font-size: 15px; color: #202124; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;"
                    +displayTitle(release)
                }
                subtitle(release)?.let { text ->
                    div {
                        style = "font-size: 12px; color: #80868b; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;"
                        +text
                    }
                }
                div {
                    style = "font-size: 12px; color: #5f6368;"
                    +detailsLine(release)
                }
            }

            div {
                style = "display: flex; flex-wrap: wrap; gap: 6px; justify-content: flex-end; max-width: 260px;"
                releaseChips(release)
            }

            div {
                style = "width: 72px; flex-shrink: 0; text-align: right; font-size: 13px; font-weight: 500; color: ${if (release.filmCount > 1) "#1a73e8" else "#5f6368"};"
                +filmCountLabel(release)
            }
        }
    }

    private fun FlowContent.viewModeToggle() {
        div {
            style = "display: flex; border: 1px solid #dadce0; border-radius: 8px; overflow: hidden;"
            ReleaseViewMode.entries.forEach { mode ->
                val isActive = viewMode == mode
                button {
                    attributes["type"] = "button"
                    attributes["title"] = mode.label
                    style = """
                        padding: 8px 12px;
                        font-size: 18px;
                        cursor: pointer;
                        background-color: ${if (isActive) "#1a73e8" else "white"};
                        color: ${if (isActive) "white" else "#5f6368"};
                        border: none;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                    """.trimIndent()
                    if (!isActive) {
                        attributes["onmouseover"] = "this.style.backgroundColor='#f1f3f4'"
                        attributes["onmouseout"] = "this.style.backgroundColor='white'"
                    }
                    span { classes = setOf("mdi", mode.icon) }
                    onClickFunction = {
                        if (viewMode != mode) {
                            viewMode = mode
                            localStorage.setItem(VIEW_MODE_STORAGE_KEY, mode.name)
                            renderResults()
                        }
                    }
                }
            }
        }
    }

    private fun FlowContent.releaseCover(release: ReleaseSummary, iconSize: Int) {
        val cover = release.coverUrl
        if (cover != null) {
            img {
                src = cover
                alt = release.title ?: "Release cover"
                style = "max-width: 100%; max-height: 100%; object-fit: contain;"
                attributes["onerror"] = "this.style.display='none'"
            }
        } else {
            span {
                classes = setOf("mdi", "mdi-disc")
                style = "font-size: ${iconSize}px; color: #bdc1c6;"
            }
        }
    }

    private fun FlowContent.filmCountBadge(release: ReleaseSummary, top: Int, fontSize: Int) {
        span {
            style = """
                position: absolute;
                top: ${top}px;
                right: ${top}px;
                padding: 3px 9px;
                border-radius: 12px;
                font-size: ${fontSize}px;
                font-weight: 500;
                color: white;
                background-color: ${if (release.filmCount > 1) "#1a73e8" else "#5f6368"};
            """.trimIndent()
            +filmCountLabel(release)
        }
    }

    private fun FlowContent.releaseChips(release: ReleaseSummary) {
        release.mediaTypes.forEach { type -> mediaTypeChip(type) }
        if (release.isCollection) {
            span {
                style = chipStyle("#fef7e0", "#b06000")
                +"Box set"
            }
        }
    }

    private fun displayTitle(release: ReleaseSummary): String =
        release.title?.takeIf { it.isNotBlank() } ?: release.sampleTitles.firstOrNull() ?: "Untitled release"

    private fun subtitle(release: ReleaseSummary): String? = when {
        release.title.isNullOrBlank() && release.sampleTitles.isNotEmpty() -> "Untitled release"
        release.filmCount > 1 && release.sampleTitles.isNotEmpty() -> {
            val extra = release.filmCount - release.sampleTitles.size
            release.sampleTitles.joinToString(", ") + if (extra > 0) " and $extra more" else ""
        }
        else -> null
    }

    private fun detailsLine(release: ReleaseSummary): String =
        listOfNotNull(release.distributor, release.releaseDate?.take(4), release.location).joinToString(" · ")

    private fun filmCountLabel(release: ReleaseSummary): String =
        if (release.filmCount == 1) "1 film" else "${release.filmCount} films"
}

private enum class ReleaseViewMode(val label: String, val icon: String) {
    GRID("Large grid", "mdi-view-grid"),
    COMPACT("Compact grid", "mdi-view-comfy"),
    LIST("List", "mdi-view-list")
}

/**
 * Colored chip for a media type, matching the accent used elsewhere in the app.
 */
internal fun FlowContent.mediaTypeChip(type: MediaType) {
    span {
        style = chipStyle("#e8f0fe", "#1a73e8")
        +mediaTypeLabel(type)
    }
}

internal fun chipStyle(background: String, color: String): String = """
    padding: 2px 8px;
    background-color: $background;
    color: $color;
    border-radius: 12px;
    font-size: 11px;
    font-weight: 500;
""".trimIndent()

internal fun mediaTypeLabel(type: MediaType): String = when (type) {
    MediaType.VHS -> "VHS"
    MediaType.DVD -> "DVD"
    MediaType.BLURAY -> "Blu-ray"
    MediaType.FOURK -> "4K"
    MediaType.DIGITAL -> "Digital"
}
