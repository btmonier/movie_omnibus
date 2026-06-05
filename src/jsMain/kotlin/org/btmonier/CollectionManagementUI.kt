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
 * Standalone UI for managing the global collection list.
 * Allows adding, editing (name + description), and deleting collections.
 */
class CollectionManagementUI(private val container: Element, private val onClose: () -> Unit) {
    private var allCollections: List<CollectionResponse> = emptyList()
    private val confirmDialog = ConfirmDialog(container)
    private val alertDialog = AlertDialog(container)

    // Form state: when formVisible, show the add/edit form. editingId == null means "add new".
    private var formVisible = false
    private var editingId: Int? = null

    fun show() {
        mainScope.launch {
            loadData()
            render()
        }
    }

    private suspend fun loadData() {
        try {
            allCollections = fetchAllCollections()
        } catch (e: Exception) {
            console.error("Error loading collection data: ${e.message}")
        }
    }

    private fun render() {
        document.getElementById("collection-management-modal")?.remove()

        container.append {
            div {
                id = "collection-management-modal"
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
                        max-width: 700px;
                        max-height: 90vh;
                        overflow-y: auto;
                        box-shadow: 0 4px 6px rgba(0,0,0,0.1), 0 2px 4px rgba(0,0,0,0.06);
                    """.trimIndent()

                    // Header
                    div {
                        style = "padding: 20px 24px; border-bottom: 1px solid #dadce0; display: flex; justify-content: space-between; align-items: center;"
                        h2 {
                            style = "margin: 0; font-size: 20px; color: #202124; font-weight: 500;"
                            +"Manage Collections"
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
                        style = "padding: 24px;"

                        h3 {
                            style = "margin-top: 0; margin-bottom: 16px; font-size: 16px; color: #202124; font-weight: 500;"
                            +"Collections (${allCollections.size})"
                        }

                        // Add new / edit form
                        if (formVisible) {
                            renderForm()
                        } else {
                            button {
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
                                    margin-bottom: 16px;
                                """.trimIndent()
                                attributes["onmouseover"] = "this.style.backgroundColor='#1765cc'"
                                attributes["onmouseout"] = "this.style.backgroundColor='#1a73e8'"
                                +"+ Add New Collection"
                                onClickFunction = {
                                    formVisible = true
                                    editingId = null
                                    render()
                                }
                            }
                        }

                        // Collection list
                        div {
                            id = "collection-list-container"
                            style = "max-height: 420px; overflow-y: auto;"

                            if (allCollections.isEmpty()) {
                                p {
                                    style = "text-align: center; color: #5f6368; font-size: 14px; margin: 20px 0;"
                                    +"No collections yet"
                                }
                            } else {
                                allCollections.sortedBy { it.name }.forEach { collection ->
                                    div {
                                        style = """
                                            display: flex;
                                            justify-content: space-between;
                                            align-items: flex-start;
                                            padding: 12px;
                                            margin-bottom: 8px;
                                            background-color: white;
                                            border: 1px solid #dadce0;
                                            border-radius: 4px;
                                        """.trimIndent()

                                        div {
                                            style = "flex: 1; min-width: 0; padding-right: 12px;"
                                            div {
                                                style = "font-size: 14px; color: #202124; font-weight: 500;"
                                                +collection.name
                                            }
                                            if (!collection.description.isNullOrBlank()) {
                                                div {
                                                    style = "font-size: 13px; color: #5f6368; margin-top: 4px; line-height: 1.5;"
                                                    +collection.description
                                                }
                                            }
                                        }

                                        div {
                                            style = "display: flex; gap: 6px; flex-shrink: 0;"
                                            button {
                                                style = """
                                                    padding: 4px 12px;
                                                    font-size: 13px;
                                                    cursor: pointer;
                                                    background-color: #ffffff;
                                                    color: #1a73e8;
                                                    border: 1px solid #dadce0;
                                                    border-radius: 4px;
                                                    font-weight: 500;
                                                    transition: background-color 0.2s;
                                                """.trimIndent()
                                                attributes["onmouseover"] = "this.style.backgroundColor='#e8f0fe'"
                                                attributes["onmouseout"] = "this.style.backgroundColor='#ffffff'"
                                                +"Edit"
                                                onClickFunction = {
                                                    formVisible = true
                                                    editingId = collection.id
                                                    render()
                                                }
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
                                                    handleDeleteCollection(collection)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun DIV.renderForm() {
        val editing = editingId?.let { id -> allCollections.find { it.id == id } }

        div {
            style = """
                border: 1px solid #dadce0;
                border-radius: 8px;
                padding: 16px;
                background-color: #f8f9fa;
                margin-bottom: 16px;
            """.trimIndent()

            div {
                style = "font-size: 14px; font-weight: 500; color: #202124; margin-bottom: 12px;"
                +if (editing != null) "Edit Collection" else "New Collection"
            }

            // Name field
            div {
                style = "margin-bottom: 12px;"
                label {
                    htmlFor = "collection-form-name"
                    style = "display: block; margin-bottom: 6px; font-weight: 500; font-size: 13px; color: #5f6368;"
                    +"Name"
                    span { style = "color: #d93025;"; +" *" }
                }
                input(type = InputType.text) {
                    id = "collection-form-name"
                    value = editing?.name ?: ""
                    placeholder = "e.g. Toho Godzilla Franchise"
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

            // Description field
            div {
                style = "margin-bottom: 12px;"
                label {
                    htmlFor = "collection-form-description"
                    style = "display: block; margin-bottom: 6px; font-weight: 500; font-size: 13px; color: #5f6368;"
                    +"Description (optional)"
                }
                textArea {
                    id = "collection-form-description"
                    placeholder = "What ties these movies together (franchise, vibe, theme)?"
                    +(editing?.description ?: "")
                    rows = "3"
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
                }
            }

            // Form actions
            div {
                style = "display: flex; gap: 8px; justify-content: flex-end;"
                button {
                    style = """
                        padding: 8px 20px;
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
                        formVisible = false
                        editingId = null
                        render()
                    }
                }
                button {
                    style = """
                        padding: 8px 20px;
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
                        handleSaveForm()
                    }
                }
            }
        }
    }

    private fun handleSaveForm() {
        val name = (document.getElementById("collection-form-name") as? HTMLInputElement)?.value?.trim() ?: ""
        val description = (document.getElementById("collection-form-description") as? HTMLTextAreaElement)?.value?.trim()
            ?.takeIf { it.isNotBlank() }

        if (name.isBlank()) {
            alertDialog.show(title = "Validation Error", message = "Collection name is required.")
            return
        }

        val id = editingId
        mainScope.launch {
            try {
                if (id != null) {
                    val updated = updateCollection(id, name, description)
                    allCollections = allCollections.map { if (it.id == id) updated else it }
                    formVisible = false
                    editingId = null
                    render()
                    alertDialog.show(title = "Success", message = "Collection \"${updated.name}\" updated successfully!")
                } else {
                    val created = createCollection(name, description)
                    allCollections = allCollections + created
                    formVisible = false
                    editingId = null
                    render()
                    alertDialog.show(title = "Success", message = "Collection \"${created.name}\" added successfully!")
                }
            } catch (e: Exception) {
                alertDialog.show(title = "Error", message = "Failed to save collection: ${e.message}")
            }
        }
    }

    private fun handleDeleteCollection(collection: CollectionResponse) {
        confirmDialog.show(
            title = "Delete Collection",
            message = "Are you sure you want to delete \"${collection.name}\"?\n\nThis will remove it from all movies that currently use it.",
            confirmText = "Delete",
            cancelText = "Cancel",
            onConfirm = {
                mainScope.launch {
                    try {
                        val success = deleteCollection(collection.id)
                        if (success) {
                            allCollections = allCollections.filter { it.id != collection.id }
                            // If we were editing the deleted collection, close the form
                            if (editingId == collection.id) {
                                formVisible = false
                                editingId = null
                            }
                            render()
                            alertDialog.show(title = "Success", message = "Collection \"${collection.name}\" deleted successfully!")
                        } else {
                            alertDialog.show(title = "Error", message = "Failed to delete collection")
                        }
                    } catch (e: Exception) {
                        alertDialog.show(title = "Error", message = "Error deleting collection: ${e.message}")
                    }
                }
            }
        )
    }

    private fun close() {
        document.getElementById("collection-management-modal")?.remove()
        onClose()
    }
}
