package org.btmonier

import kotlinx.browser.document
import kotlinx.coroutines.launch
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.onClickFunction
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement

/**
 * Searchable picker for attaching a film to a release that already exists.
 *
 * This is the answer to entering the same box set over and over: find the
 * release once, and every film after that points at the same record instead of
 * a hand-typed copy of it.
 */
class ReleaseSelector(
    private val containerId: String,
    private var selected: ReleaseSummary?,
    private val onSelectionChanged: (ReleaseSummary?) -> Unit
) {
    private var matches: List<ReleaseSummary> = emptyList()
    private var query: String = ""
    private var isSearching: Boolean = false
    private var searchToken: Int = 0

    fun render() {
        val container = document.getElementById(containerId) ?: return
        container.innerHTML = ""

        container.append {
            div {
                style = """
                    margin-bottom: 20px;
                    padding: 16px;
                    border: 1px solid #dadce0;
                    border-radius: 8px;
                    background-color: #f8f9fa;
                """.trimIndent()

                label {
                    style = "display: block; margin-bottom: 4px; font-weight: 500; font-size: 14px; color: #5f6368;"
                    +"Existing release"
                }
                p {
                    style = "margin: 0 0 10px 0; font-size: 12px; color: #80868b; line-height: 1.5;"
                    +"Already own this disc under another film? Find it here and this film joins it, instead of you re-typing the details."
                }

                div { id = "$containerId-selection" }

                div {
                    id = "$containerId-search-row"
                    input(type = InputType.text) {
                        id = "$containerId-input"
                        placeholder = "Search releases by title, distributor or blu-ray.com URL"
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

                    div {
                        id = "$containerId-results"
                        style = """
                            display: none;
                            margin-top: 6px;
                            max-height: 240px;
                            overflow-y: auto;
                            background-color: white;
                            border: 1px solid #dadce0;
                            border-radius: 4px;
                        """.trimIndent()
                    }
                }
            }
        }

        setupInputListener()
        renderSelection()
    }

    /**
     * Select a release from outside the picker, e.g. after the blu-ray.com fetch
     * recognizes a URL that is already recorded. Passing null clears the link.
     *
     * Deliberately not called `select`: inside a kotlinx-html block that name
     * resolves to the `<select>` tag builder, and `select(null)` matches its
     * `classes` overload, so the call compiles and silently does nothing.
     */
    fun selectRelease(release: ReleaseSummary?) {
        selected = release
        query = ""
        matches = emptyList()
        renderSelection()
        onSelectionChanged(release)
    }

    private fun setupInputListener() {
        val input = document.getElementById("$containerId-input") as? HTMLInputElement ?: return
        input.addEventListener("input", {
            query = input.value
            runSearch()
        })
    }

    private fun runSearch() {
        val token = ++searchToken
        if (query.isBlank()) {
            matches = emptyList()
            isSearching = false
            renderResults()
            return
        }

        isSearching = true
        renderResults()

        mainScope.launch {
            val found = try {
                searchReleases(query)
            } catch (e: Exception) {
                console.error("Release search failed:", e)
                emptyList()
            }
            // Ignore responses the user has already typed past.
            if (token != searchToken) return@launch
            matches = found
            isSearching = false
            renderResults()
        }
    }

    private fun renderSelection() {
        val holder = document.getElementById("$containerId-selection") ?: return
        val searchRow = document.getElementById("$containerId-search-row") as? HTMLElement

        holder.innerHTML = ""
        val current = selected

        if (current == null) {
            searchRow?.style?.display = "block"
            return
        }

        searchRow?.style?.display = "none"
        holder.append {
            div {
                style = """
                    display: flex;
                    gap: 12px;
                    align-items: center;
                    padding: 12px;
                    background-color: #e8f0fe;
                    border: 1px solid #c6dafc;
                    border-radius: 6px;
                """.trimIndent()

                current.coverUrl?.let { cover ->
                    img {
                        src = cover
                        alt = current.title ?: "Release cover"
                        style = "width: 44px; max-height: 60px; object-fit: contain; border-radius: 3px;"
                        attributes["onerror"] = "this.style.display='none'"
                    }
                }

                div {
                    style = "flex: 1; min-width: 0;"
                    div {
                        style = "font-size: 14px; font-weight: 500; color: #202124;"
                        +(current.title?.takeIf { it.isNotBlank() } ?: "Untitled release")
                    }
                    div {
                        style = "font-size: 12px; color: #5f6368; margin-top: 2px;"
                        val parts = listOfNotNull(
                            current.distributor,
                            current.releaseDate?.take(4),
                            if (current.filmCount == 1) "1 film" else "${current.filmCount} films"
                        )
                        +parts.joinToString(" · ")
                    }
                }

                button {
                    type = ButtonType.button
                    style = """
                        padding: 6px 12px;
                        font-size: 13px;
                        cursor: pointer;
                        background-color: white;
                        color: #3c4043;
                        border: 1px solid #dadce0;
                        border-radius: 4px;
                        font-weight: 500;
                        white-space: nowrap;
                    """.trimIndent()
                    +"Unlink"
                    onClickFunction = {
                        selectRelease(null)
                        (document.getElementById("$containerId-input") as? HTMLInputElement)?.value = ""
                        renderResults()
                    }
                }
            }
        }
    }

    private fun renderResults() {
        val results = document.getElementById("$containerId-results") as? HTMLElement ?: return
        results.innerHTML = ""

        if (query.isBlank()) {
            results.style.display = "none"
            return
        }

        results.style.display = "block"
        results.append {
            if (isSearching) {
                div {
                    style = "padding: 12px; color: #5f6368; font-size: 13px; text-align: center;"
                    +"Searching..."
                }
                return@append
            }

            if (matches.isEmpty()) {
                div {
                    style = "padding: 12px; color: #5f6368; font-size: 13px; text-align: center;"
                    +"No existing release matches \"$query\". Fill in the fields below to create a new one."
                }
                return@append
            }

            matches.forEach { release ->
                div {
                    style = """
                        display: flex;
                        gap: 10px;
                        align-items: center;
                        padding: 10px 12px;
                        cursor: pointer;
                        border-bottom: 1px solid #f1f3f4;
                    """.trimIndent()
                    attributes["onmouseover"] = "this.style.backgroundColor='#f1f3f4'"
                    attributes["onmouseout"] = "this.style.backgroundColor='white'"
                    onClickFunction = { selectRelease(release) }

                    div {
                        style = "flex: 1; min-width: 0;"
                        div {
                            style = "font-size: 14px; color: #202124; font-weight: 500;"
                            +(release.title?.takeIf { it.isNotBlank() } ?: "Untitled release")
                        }
                        div {
                            style = "font-size: 12px; color: #5f6368; margin-top: 2px;"
                            val parts = listOfNotNull(
                                release.distributor,
                                release.releaseDate?.take(4),
                                release.mediaTypes.joinToString("/") { mediaTypeLabel(it) }.takeIf { it.isNotEmpty() }
                            )
                            +parts.joinToString(" · ")
                        }
                    }

                    span {
                        style = chipStyle(
                            if (release.filmCount > 1) "#e8f0fe" else "#f1f3f4",
                            if (release.filmCount > 1) "#1a73e8" else "#5f6368"
                        )
                        +if (release.filmCount == 1) "1 film" else "${release.filmCount} films"
                    }
                }
            }
        }
    }
}
