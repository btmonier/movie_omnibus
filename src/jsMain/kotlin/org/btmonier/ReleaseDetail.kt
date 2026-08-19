package org.btmonier

import kotlinx.browser.document
import kotlinx.coroutines.launch
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.onClickFunction
import kotlinx.html.js.onInputFunction
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement

/**
 * One release and the films on it - the inverse of looking at a film and seeing
 * which discs it sits on.
 *
 * The film list is paged and searchable in its own right, so a set holding
 * several hundred films stays browsable.
 */
class ReleaseDetail(
    private val container: Element,
    private val releaseId: Int,
    private val onBack: () -> Unit
) {
    private var release: Release? = null
    private var films: List<ReleaseFilm> = emptyList()
    private var filmSearch: String = ""
    private var filmPage: Int = 1
    private var filmsPerPage: Int = 24
    private var filmTotal: Int = 0
    private var filmTotalPages: Int = 1
    private var isLoadingFilms: Boolean = false

    private val alertDialog = AlertDialog(container)
    private val confirmDialog = ConfirmDialog(container)
    private val metadataModal = MovieMetadataModal(container) {}

    fun show() {
        mainScope.launch {
            try {
                release = fetchRelease(releaseId)
            } catch (e: Exception) {
                alertDialog.show(title = "Error", message = "Failed to load release: ${e.message}")
                onBack()
                return@launch
            }
            render()
            loadFilms()
        }
    }

    private suspend fun loadFilms() {
        isLoadingFilms = true
        renderFilmList()
        try {
            val response = fetchReleaseFilms(
                releaseId = releaseId,
                page = filmPage,
                pageSize = filmsPerPage,
                search = filmSearch.takeIf { it.isNotBlank() }
            )
            films = response.films
            filmTotal = response.totalCount
            filmTotalPages = response.totalPages
            filmPage = response.page
        } catch (e: Exception) {
            films = emptyList()
            alertDialog.show(title = "Error", message = "Failed to load films: ${e.message}")
        } finally {
            isLoadingFilms = false
            renderFilmList()
        }
    }

    private fun reloadFilms(resetPage: Boolean = true) {
        if (resetPage) filmPage = 1
        mainScope.launch { loadFilms() }
    }

    private fun render() {
        val current = release ?: return
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
                            classes = setOf("mdi", "mdi-arrow-left")
                            style = "font-size: 24px; color: #ffffff;"
                        }
                        h1 {
                            style = """
                                font-family: 'Oswald', sans-serif;
                                font-weight: 500;
                                font-size: 22px;
                                color: #ffffff;
                                margin: 0;
                                letter-spacing: 1px;
                            """.trimIndent()
                            +"Back to Releases"
                        }
                    }
                }
            }

            div {
                style = "max-width: 1100px; margin: 0 auto; padding: 32px 20px; font-family: 'Google Sans', 'Roboto', arial, sans-serif;"

                releaseHeader(current)

                div {
                    style = "margin-top: 36px;"

                    div {
                        style = "display: flex; flex-wrap: wrap; gap: 12px; align-items: center; justify-content: space-between; margin-bottom: 16px;"

                        h2 {
                            style = "font-family: 'Oswald', sans-serif; font-weight: 500; font-size: 22px; color: #202124; margin: 0; letter-spacing: 0.5px;"
                            +"Films on this release"
                        }

                        div {
                            style = "display: flex; gap: 10px; align-items: center;"

                            input(type = InputType.text) {
                                id = "release-film-search"
                                value = filmSearch
                                placeholder = "Search films on this release"
                                style = """
                                    padding: 9px 12px;
                                    font-size: 14px;
                                    border: 1px solid #dadce0;
                                    border-radius: 24px;
                                    min-width: 260px;
                                    font-family: 'Roboto', arial, sans-serif;
                                """.trimIndent()
                                onInputFunction = { event ->
                                    filmSearch = (event.target as HTMLInputElement).value
                                    reloadFilms()
                                }
                            }

                            button {
                                style = """
                                    padding: 9px 16px;
                                    font-size: 14px;
                                    cursor: pointer;
                                    background-color: #34a853;
                                    color: white;
                                    border: none;
                                    border-radius: 24px;
                                    font-weight: 500;
                                """.trimIndent()
                                attributes["onmouseover"] = "this.style.backgroundColor='#2d8e47'"
                                attributes["onmouseout"] = "this.style.backgroundColor='#34a853'"
                                +"+ Add film"
                                onClickFunction = { showAddFilmPicker() }
                            }
                        }
                    }

                    div { id = "release-film-list" }
                }
            }
        }
    }

    private fun FlowContent.releaseHeader(current: Release) {
        div {
            style = """
                display: flex;
                flex-wrap: wrap;
                gap: 28px;
                background-color: white;
                border: 1px solid #e8eaed;
                border-radius: 12px;
                padding: 24px;
            """.trimIndent()

            val images = current.displayImages()
            div {
                style = """
                    flex: 0 0 200px;
                    min-height: 260px;
                    background-color: #f1f3f4;
                    border-radius: 8px;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    overflow: hidden;
                """.trimIndent()

                val cover = images.firstOrNull()
                if (cover != null) {
                    img {
                        src = cover.imageUrl
                        alt = current.title ?: "Release cover"
                        style = "max-width: 100%; max-height: 320px; object-fit: contain; cursor: zoom-in;"
                        attributes["onerror"] = "this.style.display='none'"
                        onClickFunction = { ImageLightbox.show(images, 0) }
                    }
                } else {
                    span {
                        classes = setOf("mdi", "mdi-disc")
                        style = "font-size: 64px; color: #bdc1c6;"
                    }
                }
            }

            div {
                style = "flex: 1 1 380px; min-width: 300px;"

                h1 {
                    style = "font-family: 'Oswald', sans-serif; font-weight: 500; font-size: 30px; color: #202124; margin: 0 0 10px 0; letter-spacing: 0.5px;"
                    +(current.title?.takeIf { it.isNotBlank() } ?: "Untitled release")
                }

                div {
                    style = "display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 18px;"
                    current.mediaTypes.forEach { type -> mediaTypeChip(type) }
                    if (current.isCollection) {
                        span {
                            style = chipStyle("#fef7e0", "#b06000")
                            +"Box set"
                        }
                    }
                    span {
                        style = chipStyle("#e6f4ea", "#188038")
                        +if (current.filmCount == 1) "1 film" else "${current.filmCount} films"
                    }
                }

                detailRow("Distributor", current.distributor)
                detailRow("Release date", current.releaseDate)
                detailRow("Location", current.location)

                current.blurayComUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    div {
                        style = "display: flex; gap: 10px; margin-bottom: 8px; font-size: 14px;"
                        span {
                            style = "color: #5f6368; min-width: 110px; font-weight: 500;"
                            +"Blu-ray.com"
                        }
                        a {
                            href = url
                            target = "_blank"
                            style = "color: #1a73e8; text-decoration: none; word-break: break-all;"
                            +url
                        }
                    }
                }

                div {
                    style = "margin-top: 20px; display: flex; gap: 10px; flex-wrap: wrap;"
                    button {
                        style = """
                            padding: 9px 16px;
                            font-size: 14px;
                            cursor: pointer;
                            background-color: #fce8e6;
                            color: #d93025;
                            border: none;
                            border-radius: 4px;
                            font-weight: 500;
                        """.trimIndent()
                        +"Delete release"
                        onClickFunction = { confirmDeleteRelease(current) }
                    }
                }
            }
        }
    }

    private fun FlowContent.detailRow(label: String, value: String?) {
        if (value.isNullOrBlank()) return
        div {
            style = "display: flex; gap: 10px; margin-bottom: 8px; font-size: 14px;"
            span {
                style = "color: #5f6368; min-width: 110px; font-weight: 500;"
                +label
            }
            span {
                style = "color: #202124;"
                +value
            }
        }
    }

    private fun renderFilmList() {
        val list = document.getElementById("release-film-list") ?: return
        list.innerHTML = ""

        list.append {
            if (isLoadingFilms) {
                div {
                    style = "padding: 40px; text-align: center; color: #5f6368;"
                    +"Loading films..."
                }
                return@append
            }

            if (films.isEmpty()) {
                div {
                    style = """
                        padding: 40px 20px;
                        text-align: center;
                        color: #5f6368;
                        background-color: #f8f9fa;
                        border: 1px dashed #dadce0;
                        border-radius: 12px;
                    """.trimIndent()
                    +if (filmSearch.isBlank()) {
                        "No films are on this release yet. Use \"+ Add film\" to put one on it."
                    } else {
                        "No films on this release match \"$filmSearch\"."
                    }
                }
                return@append
            }

            div {
                style = "margin-bottom: 12px; color: #5f6368; font-size: 14px;"
                val first = (filmPage - 1) * filmsPerPage + 1
                val last = minOf(filmPage * filmsPerPage, filmTotal)
                +"Showing $first-$last of $filmTotal films"
            }

            div {
                style = "display: flex; flex-direction: column; border: 1px solid #e8eaed; border-radius: 12px; overflow: hidden;"
                films.forEachIndexed { index, film -> filmRow(film, index) }
            }

            div {
                paginationControls(
                    currentPage = filmPage,
                    totalPages = filmTotalPages,
                    itemsPerPage = filmsPerPage,
                    onPageChange = { page ->
                        filmPage = page
                        reloadFilms(resetPage = false)
                    },
                    onPageSizeChange = { size ->
                        filmsPerPage = size
                        reloadFilms()
                    }
                )
            }
        }
    }

    private fun FlowContent.filmRow(film: ReleaseFilm, index: Int) {
        div {
            style = """
                display: flex;
                align-items: center;
                gap: 14px;
                padding: 12px 16px;
                background-color: ${if (index % 2 == 0) "white" else "#fafbfc"};
                border-bottom: 1px solid #f1f3f4;
            """.trimIndent()

            span {
                style = """
                    width: 28px;
                    height: 28px;
                    border-radius: 50%;
                    background-color: #e8f0fe;
                    color: #1a73e8;
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    font-size: 13px;
                    font-weight: 500;
                    flex-shrink: 0;
                """.trimIndent()
                +(film.entryLetter ?: "-")
            }

            div {
                style = "flex: 1; min-width: 0; cursor: pointer;"
                onClickFunction = { openMovie(film.movieId) }

                div {
                    style = "font-size: 15px; color: #1a73e8; font-weight: 500;"
                    +film.title
                }

                val subtitle = listOfNotNull(
                    film.releaseYear?.toString(),
                    film.alternateTitle?.takeIf { it.isNotBlank() }?.let { "listed as \"$it\"" }
                )
                if (subtitle.isNotEmpty()) {
                    div {
                        style = "font-size: 12px; color: #5f6368; margin-top: 2px;"
                        +subtitle.joinToString(" · ")
                    }
                }
            }

            button {
                style = """
                    padding: 6px 12px;
                    font-size: 13px;
                    cursor: pointer;
                    background-color: transparent;
                    color: #d93025;
                    border: 1px solid #f3c1bd;
                    border-radius: 4px;
                    font-weight: 500;
                    flex-shrink: 0;
                """.trimIndent()
                attributes["onmouseover"] = "this.style.backgroundColor='#fce8e6'"
                attributes["onmouseout"] = "this.style.backgroundColor='transparent'"
                +"Remove"
                onClickFunction = { confirmRemoveFilm(film) }
            }
        }
    }

    private fun openMovie(movieId: Int) {
        mainScope.launch {
            val movie = getMovieById(movieId)
            if (movie == null) {
                alertDialog.show(title = "Error", message = "Could not load that movie.")
            } else {
                metadataModal.show(movie)
            }
        }
    }

    private fun confirmRemoveFilm(film: ReleaseFilm) {
        confirmDialog.show(
            title = "Remove film from release",
            message = "Take \"${film.title}\" off this release? The film itself stays in your collection, and the release keeps its other films.",
            confirmText = "Remove",
            onConfirm = {
                mainScope.launch {
                    try {
                        unlinkMovieFromRelease(releaseId, film.movieId)
                        release = fetchRelease(releaseId)
                        render()
                        loadFilms()
                    } catch (e: Exception) {
                        alertDialog.show(title = "Error", message = "Failed to remove the film: ${e.message}")
                    }
                }
            }
        )
    }

    private fun confirmDeleteRelease(current: Release) {
        val name = current.title?.takeIf { it.isNotBlank() } ?: "this release"
        confirmDialog.show(
            title = "Delete release",
            message = "Delete $name? It will be taken off all ${current.filmCount} of its films. The films themselves stay in your collection.",
            confirmText = "Delete",
            onConfirm = {
                mainScope.launch {
                    if (deleteRelease(releaseId)) {
                        onBack()
                    } else {
                        alertDialog.show(title = "Error", message = "Failed to delete the release.")
                    }
                }
            }
        )
    }

    private fun showAddFilmPicker() {
        MoviePicker(
            container = container,
            excludedMovieIds = films.map { it.movieId }.toSet(),
            onPick = { movie ->
                val movieId = movie.id ?: return@MoviePicker
                try {
                    linkMovieToRelease(
                        releaseId = releaseId,
                        movieId = movieId,
                        entryLetter = nextEntryLetterFor(movieId),
                        alternateTitle = null
                    )
                    release = fetchRelease(releaseId)
                    render()
                    loadFilms()
                } catch (e: Exception) {
                    alertDialog.show(title = "Error", message = e.message ?: "Failed to add the film.")
                }
            }
        ).show()
    }

    /**
     * The letter to stamp on a film joining this release: the first one free
     * among the copies that film already has, the same suggestion the Add
     * Physical Media form makes. If the lookup fails the film is still added,
     * just without a letter.
     */
    private suspend fun nextEntryLetterFor(movieId: Int): String? = try {
        nextEntryLetter(fetchPhysicalMediaForMovie(movieId))
    } catch (e: Exception) {
        console.error("Could not determine an entry letter for movie $movieId:", e)
        null
    }
}
