package org.btmonier

import kotlinx.browser.document
import kotlinx.coroutines.launch
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.onClickFunction
import kotlinx.html.js.onInputFunction
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement

private const val MODAL_ID = "movie-picker-modal"

/**
 * Searchable modal for choosing a film already in the collection, used when
 * putting another film on a release.
 *
 * [excludedMovieIds] are shown greyed out with an "already added" note rather
 * than hidden, so it is obvious the search did find them.
 */
class MoviePicker(
    private val container: Element,
    private val excludedMovieIds: Set<Int> = emptySet(),
    private val onPick: suspend (MovieMetadata) -> Unit
) {
    private var results: List<MovieMetadata> = emptyList()
    private var query: String = ""
    private var isSearching: Boolean = false
    private var searchToken: Int = 0

    fun show() {
        render()
        runSearch()
    }

    fun close() {
        document.getElementById(MODAL_ID)?.remove()
    }

    private fun runSearch() {
        val token = ++searchToken
        isSearching = true
        renderResults()

        mainScope.launch {
            try {
                val response = fetchMoviesPaginated(
                    page = 1,
                    pageSize = 30,
                    search = query.takeIf { it.isNotBlank() },
                    sortField = "title",
                    sortDirection = "asc"
                )
                // Ignore responses from searches the user has already typed past.
                if (token != searchToken) return@launch
                results = response.movies
            } catch (e: Exception) {
                if (token != searchToken) return@launch
                results = emptyList()
                console.error("Movie search failed:", e)
            } finally {
                if (token == searchToken) {
                    isSearching = false
                    renderResults()
                }
            }
        }
    }

    private fun render() {
        close()

        container.append {
            div {
                id = MODAL_ID
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
                    z-index: 1500;
                """.trimIndent()

                onClickFunction = { event ->
                    if (event.target == document.getElementById(MODAL_ID)) close()
                }

                div {
                    style = """
                        background-color: white;
                        padding: 24px;
                        border-radius: 8px;
                        max-width: 560px;
                        width: 92%;
                        max-height: 80vh;
                        display: flex;
                        flex-direction: column;
                        box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
                        font-family: 'Google Sans', 'Roboto', arial, sans-serif;
                    """.trimIndent()

                    h2 {
                        style = "margin: 0 0 6px 0; color: #202124; font-size: 20px;"
                        +"Add a film to this release"
                    }
                    p {
                        style = "margin: 0 0 16px 0; color: #5f6368; font-size: 13px;"
                        +"Search your collection and pick the film that appears on this release."
                    }

                    input(type = InputType.text) {
                        id = "movie-picker-search"
                        placeholder = "Search by title"
                        style = """
                            width: 100%;
                            padding: 10px 12px;
                            font-size: 14px;
                            border: 1px solid #dadce0;
                            border-radius: 4px;
                            box-sizing: border-box;
                            margin-bottom: 12px;
                            font-family: 'Roboto', arial, sans-serif;
                        """.trimIndent()
                        attributes["autofocus"] = "true"
                        onInputFunction = { event ->
                            query = (event.target as HTMLInputElement).value
                            runSearch()
                        }
                    }

                    div {
                        id = "movie-picker-results"
                        style = "flex: 1; overflow-y: auto; border: 1px solid #e8eaed; border-radius: 6px;"
                    }

                    div {
                        style = "margin-top: 16px; display: flex; justify-content: flex-end;"
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
                            """.trimIndent()
                            +"Cancel"
                            onClickFunction = { close() }
                        }
                    }
                }
            }
        }
    }

    private fun renderResults() {
        val list = document.getElementById("movie-picker-results") ?: return
        list.innerHTML = ""

        list.append {
            if (isSearching) {
                div {
                    style = "padding: 24px; text-align: center; color: #5f6368; font-size: 14px;"
                    +"Searching..."
                }
                return@append
            }

            if (results.isEmpty()) {
                div {
                    style = "padding: 24px; text-align: center; color: #5f6368; font-size: 14px;"
                    +if (query.isBlank()) "Start typing to find a film." else "No films match \"$query\"."
                }
                return@append
            }

            results.forEach { movie ->
                val alreadyAdded = movie.id != null && movie.id in excludedMovieIds
                div {
                    style = """
                        display: flex;
                        align-items: center;
                        justify-content: space-between;
                        gap: 12px;
                        padding: 10px 14px;
                        border-bottom: 1px solid #f1f3f4;
                        cursor: ${if (alreadyAdded) "default" else "pointer"};
                        opacity: ${if (alreadyAdded) "0.55" else "1"};
                    """.trimIndent()
                    if (!alreadyAdded) {
                        attributes["onmouseover"] = "this.style.backgroundColor='#f8f9fa'"
                        attributes["onmouseout"] = "this.style.backgroundColor='transparent'"
                        onClickFunction = { pick(movie) }
                    }

                    div {
                        div {
                            style = "font-size: 14px; color: #202124; font-weight: 500;"
                            +movie.title
                        }
                        movie.release_date?.let { year ->
                            div {
                                style = "font-size: 12px; color: #5f6368;"
                                +year.toString()
                            }
                        }
                    }

                    if (alreadyAdded) {
                        span {
                            style = chipStyle("#e6f4ea", "#188038")
                            +"Already added"
                        }
                    }
                }
            }
        }
    }

    private fun pick(movie: MovieMetadata) {
        mainScope.launch {
            onPick(movie)
            close()
        }
    }
}
