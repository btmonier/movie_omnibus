package org.btmonier

import kotlinx.browser.document
import kotlinx.coroutines.launch
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.onChangeFunction
import kotlinx.html.js.onClickFunction
import org.w3c.dom.HTMLInputElement

/**
 * Dropdown selector for distributors with search and add functionality.
 */
class DistributorSelector(
    private val containerId: String,
    private val selectedDistributor: String?,
    private val onDistributorChanged: (String?) -> Unit
) {
    private var allDistributors: List<DistributorResponse> = emptyList()
    private var filteredDistributors: List<DistributorResponse> = emptyList()
    private var currentSelection: String? = selectedDistributor
    private var searchQuery: String = ""
    private var isDropdownOpen: Boolean = false

    suspend fun render() {
        // Fetch all distributors
        allDistributors = fetchAllDistributors()
        filteredDistributors = allDistributors

        val container = document.getElementById(containerId) ?: return
        container.innerHTML = ""
        container.append {
            div {
                style = "margin-bottom: 16px;"

                // Label
                label {
                    style = "display: block; margin-bottom: 6px; font-weight: 500; font-size: 14px; color: #5f6368;"
                    +"Distributor"
                }

                // Selected value display / search input
                div {
                    style = "position: relative;"

                    input(type = InputType.text) {
                        id = "$containerId-input"
                        value = currentSelection ?: ""
                        placeholder = "Search or add distributor..."
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
                        onChangeFunction = { event ->
                            val value = (event.target as HTMLInputElement).value
                            searchQuery = value
                            currentSelection = value.takeIf { it.isNotBlank() }
                            filterDistributors()
                            onDistributorChanged(currentSelection)
                        }
                    }

                    // Dropdown toggle button
                    button {
                        id = "$containerId-toggle"
                        style = """
                            position: absolute;
                            right: 4px;
                            top: 50%;
                            transform: translateY(-50%);
                            padding: 6px 10px;
                            background: transparent;
                            border: none;
                            cursor: pointer;
                            font-size: 12px;
                            color: #5f6368;
                        """.trimIndent()
                        +"▼"
                        onClickFunction = {
                            isDropdownOpen = !isDropdownOpen
                            updateDropdownVisibility()
                        }
                    }
                }

                // Dropdown list
                div {
                    id = "$containerId-dropdown"
                    style = """
                        display: none;
                        position: absolute;
                        z-index: 100;
                        width: 100%;
                        max-height: 200px;
                        overflow-y: auto;
                        background-color: white;
                        border: 1px solid #dadce0;
                        border-radius: 4px;
                        box-shadow: 0 2px 8px rgba(0,0,0,0.15);
                        margin-top: 4px;
                    """.trimIndent()
                }

                // Add new button container
                div {
                    id = "$containerId-add-new"
                    style = "display: none; margin-top: 8px;"
                }
            }
        }

        renderDropdownItems()
        setupInputListener()
    }

    private fun setupInputListener() {
        val input = document.getElementById("$containerId-input") as? HTMLInputElement ?: return
        input.addEventListener("input", {
            searchQuery = input.value
            currentSelection = input.value.takeIf { it.isNotBlank() }
            filterDistributors()
            onDistributorChanged(currentSelection)
            isDropdownOpen = true
            updateDropdownVisibility()
        })

        input.addEventListener("focus", {
            isDropdownOpen = true
            updateDropdownVisibility()
        })
    }

    private fun filterDistributors() {
        filteredDistributors = if (searchQuery.isBlank()) {
            allDistributors
        } else {
            allDistributors.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
        renderDropdownItems()
        updateAddNewButton()
    }

    private fun renderDropdownItems() {
        val dropdown = document.getElementById("$containerId-dropdown") ?: return
        dropdown.innerHTML = ""

        dropdown.append {
            // Clear option
            div {
                style = """
                    padding: 10px 12px;
                    cursor: pointer;
                    font-size: 14px;
                    color: #5f6368;
                    font-style: italic;
                    border-bottom: 1px solid #e8eaed;
                """.trimIndent()
                attributes["onmouseover"] = "this.style.backgroundColor='#f1f3f4'"
                attributes["onmouseout"] = "this.style.backgroundColor='white'"
                +"Clear selection"
                onClickFunction = {
                    selectDistributor(null)
                }
            }

            filteredDistributors.forEach { distributor ->
                div {
                    style = """
                        padding: 10px 12px;
                        cursor: pointer;
                        font-size: 14px;
                        color: #202124;
                        ${if (distributor.name == currentSelection) "background-color: #e8f0fe;" else ""}
                    """.trimIndent()
                    attributes["onmouseover"] = "this.style.backgroundColor='#f1f3f4'"
                    attributes["onmouseout"] = "this.style.backgroundColor='${if (distributor.name == currentSelection) "#e8f0fe" else "white"}'"
                    +distributor.name
                    onClickFunction = {
                        selectDistributor(distributor.name)
                    }
                }
            }

            if (filteredDistributors.isEmpty() && searchQuery.isNotBlank()) {
                div {
                    style = "padding: 12px; text-align: center; color: #5f6368; font-size: 13px;"
                    +"No matches. Type to add \"$searchQuery\""
                }
            }
        }
    }

    private fun updateAddNewButton() {
        val addNewContainer = document.getElementById("$containerId-add-new") as? org.w3c.dom.HTMLElement ?: return
        val exactMatch = allDistributors.any { it.name.equals(searchQuery, ignoreCase = true) }

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
                    """.trimIndent()
                    attributes["onmouseover"] = "this.style.backgroundColor='#1765cc'"
                    attributes["onmouseout"] = "this.style.backgroundColor='#1a73e8'"
                    +"+ Add \"$searchQuery\" as new distributor"
                    onClickFunction = {
                        addNewDistributor(searchQuery)
                    }
                }
            }
        } else {
            addNewContainer.style.display = "none"
        }
    }

    private fun addNewDistributor(name: String) {
        mainScope.launch {
            try {
                val newDistributor = createDistributor(name)
                allDistributors = allDistributors + newDistributor
                currentSelection = name
                
                val input = document.getElementById("$containerId-input") as? HTMLInputElement
                input?.value = name
                
                searchQuery = ""
                filterDistributors()
                isDropdownOpen = false
                updateDropdownVisibility()
                onDistributorChanged(currentSelection)
            } catch (e: Exception) {
                console.error("Error adding distributor: ${e.message}")
            }
        }
    }

    private fun selectDistributor(name: String?) {
        currentSelection = name
        val input = document.getElementById("$containerId-input") as? HTMLInputElement
        input?.value = name ?: ""
        searchQuery = ""
        isDropdownOpen = false
        updateDropdownVisibility()
        filterDistributors()
        onDistributorChanged(currentSelection)
    }

    private fun updateDropdownVisibility() {
        val dropdown = document.getElementById("$containerId-dropdown") as? org.w3c.dom.HTMLElement
        dropdown?.style?.display = if (isDropdownOpen) "block" else "none"
    }

    fun getSelectedDistributor(): String? = currentSelection
}

