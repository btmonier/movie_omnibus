package org.btmonier

import kotlinx.browser.document
import kotlinx.coroutines.launch
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.onChangeFunction
import kotlinx.html.js.onClickFunction
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement

/**
 * Custom checkbox-based selector for genres or subgenres with search and add functionality.
 */
class GenreSelector(
    private val containerId: String,
    private val label: String,
    private val type: SelectorType,
    private val selectedItems: List<String>,
    private val onItemsChanged: (List<String>) -> Unit
) {
    enum class SelectorType {
        GENRE, SUBGENRE
    }

    private var allItems: List<GenreResponse> = emptyList()
    private var filteredItems: List<GenreResponse> = emptyList()
    private var currentSelection: MutableSet<String> = selectedItems.toMutableSet()
    private var searchQuery: String = ""

    suspend fun render() {
        // Fetch all genres/subgenres
        allItems = when (type) {
            SelectorType.GENRE -> fetchAllGenres().map { GenreResponse(it.id, it.name) }
            SelectorType.SUBGENRE -> fetchAllSubgenres().map { GenreResponse(it.id, it.name) }
        }
        filteredItems = allItems

        val container = document.getElementById(containerId) ?: return
        container.innerHTML = ""
        container.append {
            div {
                style = "margin-bottom: 16px;"

                // Label
                label {
                    style = "display: block; margin-bottom: 6px; font-weight: 500; font-size: 14px; color: #5f6368;"
                    +label
                }

                // Search box
                div {
                    style = "margin-bottom: 8px;"
                    input(type = InputType.text) {
                        id = "$containerId-search"
                        placeholder = "Search or add new..."
                        style = """
                            width: 100%;
                            padding: 8px 12px;
                            font-size: 14px;
                            border: 1px solid #dadce0;
                            border-radius: 4px;
                            box-sizing: border-box;
                            font-family: 'Roboto', arial, sans-serif;
                        """.trimIndent()
                        attributes["onfocus"] = "this.style.borderColor='#1a73e8'"
                        attributes["onblur"] = "this.style.borderColor='#dadce0'"
                        onChangeFunction = { event ->
                            searchQuery = (event.target as HTMLInputElement).value
                            filterItems()
                        }
                    }
                }

                // Add new button (shows when search query doesn't match any existing items)
                div {
                    id = "$containerId-add-new-container"
                    style = "margin-bottom: 8px; display: none;"
                }

                // Checkbox list container
                div {
                    id = "$containerId-list"
                    style = """
                        max-height: 200px;
                        overflow-y: auto;
                        border: 1px solid #dadce0;
                        border-radius: 4px;
                        padding: 8px;
                        background-color: #f8f9fa;
                    """.trimIndent()
                }

                // Selected count
                div {
                    id = "$containerId-count"
                    style = "margin-top: 4px; font-size: 12px; color: #5f6368;"
                }
            }
        }

        renderList()
        updateAddNewButton()
        updateCount()
    }

    private fun filterItems() {
        filteredItems = if (searchQuery.isBlank()) {
            allItems
        } else {
            allItems.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
        renderList()
        updateAddNewButton()
    }

    private fun renderList() {
        val listContainer = document.getElementById("$containerId-list") ?: return
        listContainer.innerHTML = ""

        if (filteredItems.isEmpty() && searchQuery.isBlank()) {
            listContainer.append {
                div {
                    style = "text-align: center; padding: 12px; color: #5f6368; font-size: 13px;"
                    +"No ${type.name.lowercase()}s available"
                }
            }
            return
        }

        if (filteredItems.isEmpty() && searchQuery.isNotBlank()) {
            listContainer.append {
                div {
                    style = "text-align: center; padding: 12px; color: #5f6368; font-size: 13px;"
                    +"No matches found. Use the button above to add \"$searchQuery\"."
                }
            }
            return
        }

        listContainer.append {
            filteredItems.forEach { item ->
                div {
                    style = "margin-bottom: 4px;"
                    label {
                        style = """
                            display: flex;
                            align-items: center;
                            padding: 6px 8px;
                            cursor: pointer;
                            border-radius: 4px;
                            transition: background-color 0.2s;
                        """.trimIndent()
                        attributes["onmouseover"] = "this.style.backgroundColor='#e8eaed'"
                        attributes["onmouseout"] = "this.style.backgroundColor='transparent'"

                        input(type = InputType.checkBox) {
                            id = "$containerId-item-${item.id}"
                            checked = item.name in currentSelection
                            style = "margin-right: 8px; cursor: pointer;"
                            onChangeFunction = { event ->
                                val isChecked = (event.target as HTMLInputElement).checked
                                if (isChecked) {
                                    currentSelection.add(item.name)
                                } else {
                                    currentSelection.remove(item.name)
                                }
                                updateCount()
                                onItemsChanged(currentSelection.toList())
                            }
                        }
                        span {
                            style = "font-size: 14px; color: #202124;"
                            +item.name
                        }
                    }
                }
            }
        }
    }

    private fun updateAddNewButton() {
        val addNewContainer = document.getElementById("$containerId-add-new-container") as? org.w3c.dom.HTMLElement ?: return

        // Show add new button if search query doesn't match any existing items
        val exactMatch = allItems.any { it.name.equals(searchQuery, ignoreCase = true) }

        if (searchQuery.isNotBlank() && !exactMatch) {
            addNewContainer.style.display = "block"
            addNewContainer.innerHTML = ""
            addNewContainer.append {
                button {
                    style = """
                        width: 100%;
                        padding: 8px 12px;
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
                    +"+ Add \"$searchQuery\""
                    onClickFunction = {
                        addNewItem(searchQuery)
                    }
                }
            }
        } else {
            addNewContainer.style.display = "none"
        }
    }

    private fun addNewItem(name: String) {
        mainScope.launch {
            try {
                val newItem = when (type) {
                    SelectorType.GENRE -> createGenre(name).let { GenreResponse(it.id, it.name) }
                    SelectorType.SUBGENRE -> createSubgenre(name).let { GenreResponse(it.id, it.name) }
                }

                // Add to our local list
                allItems = allItems + newItem
                currentSelection.add(name)

                // Clear search and refresh
                val searchInput = document.getElementById("$containerId-search") as? HTMLInputElement
                searchInput?.value = ""
                searchQuery = ""

                filterItems()
                updateCount()
                onItemsChanged(currentSelection.toList())
            } catch (e: Exception) {
                console.error("Error adding new ${type.name.lowercase()}: ${e.message}")
            }
        }
    }

    private fun updateCount() {
        val countContainer = document.getElementById("$containerId-count") ?: return
        val count = currentSelection.size
        countContainer.textContent = if (count > 0) {
            "$count ${type.name.lowercase()}${if (count != 1) "s" else ""} selected"
        } else {
            "No ${type.name.lowercase()}s selected"
        }
    }

    /**
     * Get the currently selected items.
     */
    fun getSelectedItems(): List<String> {
        return currentSelection.toList()
    }
}
