package org.btmonier

import kotlinx.browser.document
import kotlinx.coroutines.launch
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.onChangeFunction
import kotlinx.html.js.onClickFunction
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement

/**
 * Browse the physical shelf by release rather than by film: every box set, disc
 * and tape is one card, showing how many films it holds. Clicking a card opens
 * [ReleaseDetail], which lists the films on it.
 */
class ReleaseBrowser(
    private val container: Element,
    private val onBack: () -> Unit
) {
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

    private fun render() {
        container.innerHTML = ""
        container.append {
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

                    div {
                        style = "display: flex; align-items: center; gap: 12px; cursor: pointer;"
                        onClickFunction = { onBack() }

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

                    div {
                        style = "display: flex; align-items: center; gap: 8px;"
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
                                classes = setOf("mdi", "mdi-package-variant-closed")
                                style = "font-size: 18px;"
                            }
                            span { +"Releases" }
                        }
                    }
                }
            }

            div {
                style = "max-width: 1400px; margin: 0 auto; padding: 32px 20px; font-family: 'Google Sans', 'Roboto', arial, sans-serif;"

                div {
                    style = "margin-bottom: 8px;"
                    h1 {
                        style = "font-family: 'Oswald', sans-serif; font-weight: 500; color: #202124; font-size: 28px; margin: 0; display: flex; align-items: center; gap: 12px; letter-spacing: 1px;"
                        span {
                            classes = setOf("mdi", "mdi-package-variant-closed")
                            style = "font-size: 32px; color: #667eea;"
                        }
                        +"Physical Releases"
                    }
                }

                p {
                    style = "color: #5f6368; font-size: 15px; margin: 0 0 24px 0; line-height: 1.6;"
                    +"Every physical unit you own, listed once. Open a release to see which films are on it."
                }

                renderFilters()

                div { id = "release-results" }
            }
        }
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
                input(type = InputType.text) {
                    id = "release-search-input"
                    value = searchText
                    placeholder = "Release title, film, distributor or URL"
                    style = textInputStyle()
                    onChangeFunction = { event ->
                        searchText = (event.target as HTMLInputElement).value
                        reload()
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
                        reload()
                    }
                }
                +"Box sets only"
            }
        }
    }

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
                style = "margin-bottom: 16px; color: #5f6368; font-size: 14px;"
                +resultsSummary()
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

            div {
                style = "display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 20px;"
                releases.forEach { release -> releaseCard(release) }
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
                        style = "font-size: 56px; color: #bdc1c6;"
                    }
                }

                span {
                    style = """
                        position: absolute;
                        top: 10px;
                        right: 10px;
                        padding: 4px 10px;
                        border-radius: 12px;
                        font-size: 12px;
                        font-weight: 500;
                        color: white;
                        background-color: ${if (release.filmCount > 1) "#1a73e8" else "#5f6368"};
                    """.trimIndent()
                    +if (release.filmCount == 1) "1 film" else "${release.filmCount} films"
                }
            }

            div {
                style = "padding: 14px; display: flex; flex-direction: column; gap: 6px; flex: 1;"

                div {
                    style = "font-weight: 500; font-size: 15px; color: #202124; line-height: 1.35;"
                    +(release.title?.takeIf { it.isNotBlank() } ?: release.sampleTitles.firstOrNull() ?: "Untitled release")
                }

                if (release.title.isNullOrBlank() && release.sampleTitles.isNotEmpty()) {
                    div {
                        style = "font-size: 12px; color: #80868b;"
                        +"Untitled release"
                    }
                } else if (release.filmCount > 1 && release.sampleTitles.isNotEmpty()) {
                    div {
                        style = "font-size: 12px; color: #80868b; line-height: 1.4;"
                        val extra = release.filmCount - release.sampleTitles.size
                        +(release.sampleTitles.joinToString(", ") + if (extra > 0) " and $extra more" else "")
                    }
                }

                div {
                    style = "display: flex; flex-wrap: wrap; gap: 6px; margin-top: 4px;"
                    release.mediaTypes.forEach { type -> mediaTypeChip(type) }
                    if (release.isCollection) {
                        span {
                            style = chipStyle("#fef7e0", "#b06000")
                            +"Box set"
                        }
                    }
                }

                div {
                    style = "font-size: 12px; color: #5f6368; margin-top: auto; padding-top: 6px;"
                    val parts = listOfNotNull(
                        release.distributor,
                        release.releaseDate?.take(4),
                        release.location
                    )
                    +parts.joinToString(" · ")
                }
            }
        }
    }
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
