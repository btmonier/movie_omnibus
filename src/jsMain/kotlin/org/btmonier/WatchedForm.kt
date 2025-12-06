package org.btmonier

import kotlinx.browser.document
import kotlinx.coroutines.launch
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.onClickFunction
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement

/**
 * Modal form for creating and editing watched entries.
 */
class WatchedForm(
    private val container: Element,
    private val onSave: suspend (WatchedEntry) -> Unit,
    private val onCancel: () -> Unit
) {
    private var editingEntry: WatchedEntry? = null
    private val alertDialog = AlertDialog(container)
    private var popcornRating: PopcornRating? = null
    private var selectedRating: Double? = null

    /**
     * Show the form for creating a new watched entry.
     */
    fun showCreate() {
        editingEntry = null
        selectedRating = null
        render()
        initializePopcornRating()
    }

    /**
     * Show the form for editing an existing watched entry.
     */
    fun showEdit(entry: WatchedEntry) {
        editingEntry = entry
        selectedRating = entry.rating
        render()
        initializePopcornRating()
    }

    private fun initializePopcornRating() {
        popcornRating = PopcornRating(
            containerId = "popcorn-rating-container",
            currentRating = selectedRating,
            onRatingChanged = { rating ->
                selectedRating = rating
            }
        )
        popcornRating?.render()
    }

    /**
     * Close and hide the form.
     */
    fun close() {
        val modal = document.getElementById("watched-form-modal")
        modal?.remove()
    }

    private fun render() {
        // Remove existing modal if any
        close()

        container.append {
            div {
                id = "watched-form-modal"
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
                        max-height: 90vh;
                        overflow-y: auto;
                        box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
                    """.trimIndent()

                    h2 {
                        style = "margin-top: 0; color: #202124;"
                        +if (editingEntry != null) "Edit Watched Entry" else "Add Watched Entry"
                    }

                    renderFormFields()

                    div {
                        style = "margin-top: 24px; display: flex; gap: 12px; justify-content: flex-end;"

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
                            id = "save-watched-button"
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
                            +"Save"
                            onClickFunction = {
                                handleSave()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun DIV.renderFormFields() {
        val entry = editingEntry

        // Watched Date
        div {
            style = "margin-bottom: 16px;"
            label {
                htmlFor = "form-watched-date"
                style = "display: block; margin-bottom: 6px; font-weight: 500; font-size: 14px; color: #5f6368;"
                +"Watched Date"
                span {
                    style = "color: #d93025;"
                    +" *"
                }
            }
            input(type = InputType.date) {
                id = "form-watched-date"
                value = entry?.watchedDate ?: ""
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
        }

        // Rating
        div {
            style = "margin-bottom: 16px;"
            label {
                style = "display: block; margin-bottom: 6px; font-weight: 500; font-size: 14px; color: #5f6368;"
                +"Rating (out of 5)"
            }
            div {
                id = "popcorn-rating-container"
                style = """
                    padding: 10px 12px;
                    border: 1px solid #dadce0;
                    border-radius: 4px;
                    background-color: #fafafa;
                """.trimIndent()
            }
        }

        // Notes
        div {
            style = "margin-bottom: 16px;"
            label {
                htmlFor = "form-notes"
                style = "display: block; margin-bottom: 6px; font-weight: 500; font-size: 14px; color: #5f6368;"
                +"Notes"
            }
            textArea {
                id = "form-notes"
                rows = "4"
                placeholder = "Optional viewing notes..."
                style = """
                    width: 100%;
                    padding: 10px 12px;
                    font-size: 14px;
                    border: 1px solid #dadce0;
                    border-radius: 4px;
                    box-sizing: border-box;
                    font-family: 'Roboto', arial, sans-serif;
                    resize: vertical;
                """.trimIndent()
                attributes["onfocus"] = "this.style.borderColor='#1a73e8'"
                attributes["onblur"] = "this.style.borderColor='#dadce0'"
                +(entry?.notes ?: "")
            }
        }
    }

    private fun handleSave() {
        try {
            val watchedDate = (document.getElementById("form-watched-date") as HTMLInputElement).value.trim()

            if (watchedDate.isBlank()) {
                alertDialog.show(
                    title = "Validation Error",
                    message = "Watched date is required!"
                )
                return
            }

            // Get rating from popcorn rating component
            val rating = selectedRating

            val notes = (document.getElementById("form-notes") as HTMLTextAreaElement).value.trim()
                .takeIf { it.isNotBlank() }

            val watchedEntry = WatchedEntry(
                watchedDate = watchedDate,
                rating = rating,
                notes = notes,
                id = editingEntry?.id
            )

            mainScope.launch {
                try {
                    onSave(watchedEntry)
                    close()
                } catch (e: Exception) {
                    alertDialog.show(
                        title = "Error",
                        message = "Error saving watched entry: ${e.message}"
                    )
                }
            }
        } catch (e: Exception) {
            alertDialog.show(
                title = "Error",
                message = "Error processing form: ${e.message}"
            )
        }
    }
}
