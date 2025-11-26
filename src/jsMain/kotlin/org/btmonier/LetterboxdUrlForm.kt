package org.btmonier

import kotlinx.browser.document
import kotlinx.coroutines.launch
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.onClickFunction
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLInputElement

/**
 * Modal form for entering a Letterboxd URL to scrape movie data.
 * This is shown first when adding a new movie.
 */
class LetterboxdUrlForm(
    private val container: Element,
    private val onScrapedMovie: (MovieMetadata) -> Unit,
    private val onCancel: () -> Unit
) {
    private val alertDialog = AlertDialog(container)
    private var isLoading = false

    /**
     * Show the URL input form.
     */
    fun show() {
        render()
    }

    /**
     * Close and hide the form.
     */
    fun close() {
        val modal = document.getElementById("letterboxd-url-modal")
        modal?.remove()
    }

    private fun render() {
        // Remove existing modal if any
        close()

        container.append {
            div {
                id = "letterboxd-url-modal"
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
                        max-width: 500px;
                        width: 90%;
                        box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
                    """.trimIndent()

                    h2 {
                        style = "margin-top: 0; color: #202124;"
                        +"Add New Movie"
                    }

                    p {
                        style = "color: #5f6368; font-size: 14px; margin-bottom: 20px;"
                        +"Enter a Letterboxd URL to automatically fetch movie details."
                    }

                    // URL input field
                    div {
                        style = "margin-bottom: 20px;"
                        label {
                            htmlFor = "letterboxd-url-input"
                            style = "display: block; margin-bottom: 6px; font-weight: 500; font-size: 14px; color: #5f6368;"
                            +"Letterboxd URL"
                            span {
                                style = "color: #d93025;"
                                +" *"
                            }
                        }
                        input(type = InputType.url) {
                            id = "letterboxd-url-input"
                            placeholder = "https://letterboxd.com/film/movie-name/"
                            style = """
                                width: 100%;
                                padding: 12px 14px;
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

                    // Example URL hint
                    p {
                        style = "color: #80868b; font-size: 12px; margin-top: -12px; margin-bottom: 24px;"
                        +"Example: https://letterboxd.com/film/the-godfather/"
                    }

                    // Loading indicator (hidden by default)
                    div {
                        id = "scrape-loading"
                        style = "display: none; text-align: center; padding: 20px;"
                        div {
                            style = """
                                display: inline-block;
                                width: 24px;
                                height: 24px;
                                border: 3px solid #dadce0;
                                border-top: 3px solid #1a73e8;
                                border-radius: 50%;
                                animation: spin 1s linear infinite;
                            """.trimIndent()
                        }
                        p {
                            style = "color: #5f6368; font-size: 14px; margin-top: 12px;"
                            +"Fetching movie data..."
                        }
                        // Add CSS animation
                        style {
                            unsafe {
                                raw("""
                                    @keyframes spin {
                                        0% { transform: rotate(0deg); }
                                        100% { transform: rotate(360deg); }
                                    }
                                """.trimIndent())
                            }
                        }
                    }

                    // Buttons
                    div {
                        id = "url-form-buttons"
                        style = "display: flex; gap: 12px; justify-content: flex-end;"

                        button {
                            id = "cancel-url-button"
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
                            id = "fetch-button"
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
                            +"Fetch Movie Data"
                            onClickFunction = {
                                handleFetch()
                            }
                        }
                    }

                    // Manual entry option
                    div {
                        style = "margin-top: 20px; padding-top: 20px; border-top: 1px solid #e8eaed; text-align: center;"
                        span {
                            style = "color: #5f6368; font-size: 13px;"
                            +"or "
                        }
                        a {
                            href = "#"
                            style = "color: #1a73e8; font-size: 13px; text-decoration: none;"
                            attributes["onmouseover"] = "this.style.textDecoration='underline'"
                            attributes["onmouseout"] = "this.style.textDecoration='none'"
                            +"enter details manually"
                            onClickFunction = { e ->
                                e.preventDefault()
                                close()
                                // Create an empty movie and pass to the edit form
                                onScrapedMovie(MovieMetadata(url = ""))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleFetch() {
        if (isLoading) return

        val urlInput = document.getElementById("letterboxd-url-input") as? HTMLInputElement
        val url = urlInput?.value?.trim() ?: ""

        if (url.isBlank()) {
            alertDialog.show(
                title = "Validation Error",
                message = "Please enter a Letterboxd URL"
            )
            return
        }

        if (!url.startsWith("https://letterboxd.com/film/")) {
            alertDialog.show(
                title = "Invalid URL",
                message = "Please enter a valid Letterboxd film URL.\n\nExample: https://letterboxd.com/film/the-godfather/"
            )
            return
        }

        // Show loading state
        setLoadingState(true)

        mainScope.launch {
            try {
                val response = scrapeLetterboxdUrl(url)

                if (response.exists) {
                    setLoadingState(false)
                    alertDialog.show(
                        title = "Movie Already Exists",
                        message = response.error ?: "This movie is already in your collection."
                    )
                } else if (response.success && response.movie != null) {
                    close()
                    onScrapedMovie(response.movie)
                } else {
                    setLoadingState(false)
                    alertDialog.show(
                        title = "Scraping Failed",
                        message = response.error ?: "Failed to fetch movie data. Please try again or enter details manually."
                    )
                }
            } catch (e: Exception) {
                setLoadingState(false)
                alertDialog.show(
                    title = "Error",
                    message = "An error occurred: ${e.message}"
                )
            }
        }
    }

    private fun setLoadingState(loading: Boolean) {
        isLoading = loading

        val loadingDiv = document.getElementById("scrape-loading")
        val buttonsDiv = document.getElementById("url-form-buttons")
        val fetchButton = document.getElementById("fetch-button") as? HTMLButtonElement

        if (loading) {
            loadingDiv?.setAttribute("style", "text-align: center; padding: 20px;")
            buttonsDiv?.setAttribute("style", "display: none;")
        } else {
            loadingDiv?.setAttribute("style", "display: none;")
            buttonsDiv?.setAttribute("style", "display: flex; gap: 12px; justify-content: flex-end;")
        }

        fetchButton?.disabled = loading
    }
}



