package org.btmonier

import kotlinx.browser.document
import kotlinx.coroutines.launch
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.onClickFunction
import org.w3c.dom.Element

/**
 * Standalone UI for managing the global genre and subgenre lists.
 * Allows adding, editing, and deleting genres/subgenres.
 */
class GenreManagementUI(private val container: Element, private val onClose: () -> Unit) {
    private var allGenres: List<GenreResponse> = emptyList()
    private var allSubgenres: List<SubgenreResponse> = emptyList()
    private val confirmDialog = ConfirmDialog(container)
    private val alertDialog = AlertDialog(container)

    fun show() {
        mainScope.launch {
            loadData()
            render()
        }
    }

    private suspend fun loadData() {
        try {
            allGenres = fetchAllGenres()
            allSubgenres = fetchAllSubgenres()
        } catch (e: Exception) {
            console.error("Error loading genre/subgenre data: ${e.message}")
        }
    }

    private fun render() {
        // Remove existing modal if any
        document.getElementById("genre-management-modal")?.remove()

        container.append {
            div {
                id = "genre-management-modal"
                style = """
                    position: fixed;
                    top: 0;
                    left: 0;
                    width: 100%;
                    height: 100%;
                    background-color: rgba(0, 0, 0, 0.5);
                    z-index: 1000;
                    display: flex;
                    justify-content: center;
                    align-items: center;
                    font-family: 'Roboto', arial, sans-serif;
                """.trimIndent()

                div {
                    style = """
                        background-color: white;
                        border-radius: 8px;
                        width: 90%;
                        max-width: 900px;
                        max-height: 90vh;
                        overflow-y: auto;
                        box-shadow: 0 4px 6px rgba(0,0,0,0.1), 0 2px 4px rgba(0,0,0,0.06);
                    """.trimIndent()

                    // Header
                    div {
                        style = "padding: 20px 24px; border-bottom: 1px solid #dadce0; display: flex; justify-content: space-between; align-items: center;"
                        h2 {
                            style = "margin: 0; font-size: 20px; color: #202124; font-weight: 500;"
                            +"Manage Genres & Subgenres"
                        }
                        button {
                            style = """
                                padding: 8px 12px;
                                font-size: 14px;
                                cursor: pointer;
                                background-color: #f1f3f4;
                                color: #5f6368;
                                border: none;
                                border-radius: 4px;
                                font-weight: 500;
                                transition: background-color 0.2s;
                            """.trimIndent()
                            attributes["onmouseover"] = "this.style.backgroundColor='#e8eaed'"
                            attributes["onmouseout"] = "this.style.backgroundColor='#f1f3f4'"
                            +"✕ Close"
                            onClickFunction = {
                                close()
                            }
                        }
                    }

                    // Content
                    div {
                        style = "padding: 24px; display: grid; grid-template-columns: 1fr 1fr; gap: 24px;"

                        // Genres column
                        div {
                            renderGenreList()
                        }

                        // Subgenres column
                        div {
                            renderSubgenreList()
                        }
                    }
                }
            }
        }
    }

    private fun DIV.renderGenreList() {
        div {
            style = "border: 1px solid #dadce0; border-radius: 8px; padding: 16px; background-color: #f8f9fa;"

            h3 {
                style = "margin-top: 0; margin-bottom: 16px; font-size: 16px; color: #202124; font-weight: 500;"
                +"Genres (${allGenres.size})"
            }

            // Add new genre button
            button {
                id = "add-genre-button"
                style = """
                    width: 100%;
                    padding: 10px;
                    font-size: 14px;
                    cursor: pointer;
                    background-color: #1a73e8;
                    color: white;
                    border: none;
                    border-radius: 4px;
                    font-weight: 500;
                    transition: background-color 0.2s;
                    margin-bottom: 12px;
                """.trimIndent()
                attributes["onmouseover"] = "this.style.backgroundColor='#1765cc'"
                attributes["onmouseout"] = "this.style.backgroundColor='#1a73e8'"
                +"+ Add New Genre"
                onClickFunction = {
                    showAddGenrePrompt()
                }
            }

            // Genre list
            div {
                id = "genre-list-container"
                style = "max-height: 400px; overflow-y: auto;"

                if (allGenres.isEmpty()) {
                    p {
                        style = "text-align: center; color: #5f6368; font-size: 14px; margin: 20px 0;"
                        +"No genres yet"
                    }
                } else {
                    allGenres.sortedBy { it.name }.forEach { genre ->
                        div {
                            style = """
                                display: flex;
                                justify-content: space-between;
                                align-items: center;
                                padding: 10px;
                                margin-bottom: 4px;
                                background-color: white;
                                border: 1px solid #dadce0;
                                border-radius: 4px;
                            """.trimIndent()

                            span {
                                style = "font-size: 14px; color: #202124;"
                                +genre.name
                            }

                            button {
                                style = """
                                    padding: 4px 12px;
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
                                    handleDeleteGenre(genre)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun DIV.renderSubgenreList() {
        div {
            style = "border: 1px solid #dadce0; border-radius: 8px; padding: 16px; background-color: #f8f9fa;"

            h3 {
                style = "margin-top: 0; margin-bottom: 16px; font-size: 16px; color: #202124; font-weight: 500;"
                +"Subgenres (${allSubgenres.size})"
            }

            // Add new subgenre button
            button {
                id = "add-subgenre-button"
                style = """
                    width: 100%;
                    padding: 10px;
                    font-size: 14px;
                    cursor: pointer;
                    background-color: #1a73e8;
                    color: white;
                    border: none;
                    border-radius: 4px;
                    font-weight: 500;
                    transition: background-color 0.2s;
                    margin-bottom: 12px;
                """.trimIndent()
                attributes["onmouseover"] = "this.style.backgroundColor='#1765cc'"
                attributes["onmouseout"] = "this.style.backgroundColor='#1a73e8'"
                +"+ Add New Subgenre"
                onClickFunction = {
                    showAddSubgenrePrompt()
                }
            }

            // Subgenre list
            div {
                id = "subgenre-list-container"
                style = "max-height: 400px; overflow-y: auto;"

                if (allSubgenres.isEmpty()) {
                    p {
                        style = "text-align: center; color: #5f6368; font-size: 14px; margin: 20px 0;"
                        +"No subgenres yet"
                    }
                } else {
                    allSubgenres.sortedBy { it.name }.forEach { subgenre ->
                        div {
                            style = """
                                display: flex;
                                justify-content: space-between;
                                align-items: center;
                                padding: 10px;
                                margin-bottom: 4px;
                                background-color: white;
                                border: 1px solid #dadce0;
                                border-radius: 4px;
                            """.trimIndent()

                            span {
                                style = "font-size: 14px; color: #202124;"
                                +subgenre.name
                            }

                            button {
                                style = """
                                    padding: 4px 12px;
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
                                    handleDeleteSubgenre(subgenre)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showAddGenrePrompt() {
        val name = kotlinx.browser.window.prompt("Enter new genre name:")
        if (!name.isNullOrBlank()) {
            mainScope.launch {
                try {
                    val newGenre = createGenre(name.trim())
                    allGenres = allGenres + newGenre
                    render()
                    alertDialog.show(
                        title = "Success",
                        message = "Genre \"${newGenre.name}\" added successfully!"
                    )
                } catch (e: Exception) {
                    alertDialog.show(
                        title = "Error",
                        message = "Failed to add genre: ${e.message}"
                    )
                }
            }
        }
    }

    private fun showAddSubgenrePrompt() {
        val name = kotlinx.browser.window.prompt("Enter new subgenre name:")
        if (!name.isNullOrBlank()) {
            mainScope.launch {
                try {
                    val newSubgenre = createSubgenre(name.trim())
                    allSubgenres = allSubgenres + newSubgenre
                    render()
                    alertDialog.show(
                        title = "Success",
                        message = "Subgenre \"${newSubgenre.name}\" added successfully!"
                    )
                } catch (e: Exception) {
                    alertDialog.show(
                        title = "Error",
                        message = "Failed to add subgenre: ${e.message}"
                    )
                }
            }
        }
    }

    private fun handleDeleteGenre(genre: GenreResponse) {
        confirmDialog.show(
            title = "Delete Genre",
            message = "Are you sure you want to delete \"${genre.name}\"?\n\nThis will remove it from all movies that currently use it.",
            confirmText = "Delete",
            cancelText = "Cancel",
            onConfirm = {
                mainScope.launch {
                    try {
                        val success = deleteGenre(genre.id)
                        if (success) {
                            allGenres = allGenres.filter { it.id != genre.id }
                            render()
                            alertDialog.show(
                                title = "Success",
                                message = "Genre \"${genre.name}\" deleted successfully!"
                            )
                        } else {
                            alertDialog.show(
                                title = "Error",
                                message = "Failed to delete genre"
                            )
                        }
                    } catch (e: Exception) {
                        alertDialog.show(
                            title = "Error",
                            message = "Error deleting genre: ${e.message}"
                        )
                    }
                }
            }
        )
    }

    private fun handleDeleteSubgenre(subgenre: SubgenreResponse) {
        confirmDialog.show(
            title = "Delete Subgenre",
            message = "Are you sure you want to delete \"${subgenre.name}\"?\n\nThis will remove it from all movies that currently use it.",
            confirmText = "Delete",
            cancelText = "Cancel",
            onConfirm = {
                mainScope.launch {
                    try {
                        val success = deleteSubgenre(subgenre.id)
                        if (success) {
                            allSubgenres = allSubgenres.filter { it.id != subgenre.id }
                            render()
                            alertDialog.show(
                                title = "Success",
                                message = "Subgenre \"${subgenre.name}\" deleted successfully!"
                            )
                        } else {
                            alertDialog.show(
                                title = "Error",
                                message = "Failed to delete subgenre"
                            )
                        }
                    } catch (e: Exception) {
                        alertDialog.show(
                            title = "Error",
                            message = "Error deleting subgenre: ${e.message}"
                        )
                    }
                }
            }
        )
    }

    private fun close() {
        document.getElementById("genre-management-modal")?.remove()
        onClose()
    }
}
