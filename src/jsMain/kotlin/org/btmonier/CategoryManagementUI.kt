package org.btmonier

import kotlinx.browser.document
import kotlinx.coroutines.launch
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.onClickFunction
import kotlinx.html.js.onInputFunction
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement

/**
 * The category lists that can be managed centrally.
 */
private enum class CategoryKind(
    val slug: String,
    val plural: String,
    val singular: String,
    val icon: String,
    val hasDescription: Boolean = false
) {
    GENRES("genres", "Genres", "Genre", "mdi-tag-multiple"),
    SUBGENRES("subgenres", "Subgenres", "Subgenre", "mdi-tag-outline"),
    COLLECTIONS("collections", "Collections", "Collection", "mdi-bookmark-multiple", hasDescription = true),
    DISTRIBUTORS("distributors", "Distributors", "Distributor", "mdi-factory"),
    THEMES("themes", "Themes", "Theme", "mdi-lightbulb-outline"),
    COUNTRIES("countries", "Countries", "Country", "mdi-earth")
}

/**
 * Standalone UI for managing every kind of categorical data in one place.
 *
 * Entries live in lookup tables that movies reference, so renaming one here
 * corrects it on every movie at once. When a rename collides with an existing
 * entry the two are merged after confirmation, which is how misspelled
 * duplicates get folded back into the real entry.
 */
class CategoryManagementUI(private val container: Element, private val onClose: () -> Unit) {
    private val confirmDialog = ConfirmDialog(container)
    private val alertDialog = AlertDialog(container)

    private var activeKind = CategoryKind.GENRES
    private var entries: List<CategoryEntryResponse> = emptyList()
    private var entryCounts: Map<String, Int> = emptyMap()
    private var searchQuery = ""

    // Row state: at most one row is being renamed or merged at a time
    private var renamingId: Int? = null
    private var mergingId: Int? = null
    private var addFormVisible = false

    fun show() {
        mainScope.launch {
            loadData()
            render()
        }
    }

    private suspend fun loadData() {
        try {
            entryCounts = fetchCategoryTypes().associate { it.type to it.entryCount }
            entries = fetchCategoryEntries(activeKind.slug)
        } catch (e: Exception) {
            console.error("Error loading category data: ${e.message}")
            alertDialog.show(title = "Error", message = "Failed to load categories: ${e.message}")
        }
    }

    private fun render() {
        document.getElementById("category-management-modal")?.remove()

        container.append {
            div {
                id = "category-management-modal"
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
                        width: 92%;
                        max-width: 960px;
                        max-height: 90vh;
                        display: flex;
                        flex-direction: column;
                        overflow: hidden;
                        box-shadow: 0 4px 6px rgba(0,0,0,0.1), 0 2px 4px rgba(0,0,0,0.06);
                    """.trimIndent()

                    // Header
                    div {
                        style = "padding: 20px 24px; border-bottom: 1px solid #dadce0; display: flex; justify-content: space-between; align-items: center; flex-shrink: 0;"
                        div {
                            h2 {
                                style = "margin: 0; font-size: 20px; color: #202124; font-weight: 500;"
                                +"Manage Categories"
                            }
                            div {
                                style = "margin-top: 4px; font-size: 13px; color: #5f6368;"
                                +"Renaming an entry updates every movie that uses it."
                            }
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
                            onClickFunction = { close() }
                        }
                    }

                    // Body: type rail on the left, entries on the right
                    div {
                        style = "display: flex; min-height: 0; flex: 1;"

                        renderTypeRail()

                        div {
                            style = "flex: 1; min-width: 0; padding: 20px 24px; display: flex; flex-direction: column; overflow: hidden;"
                            renderPanelHeader()

                            if (addFormVisible) {
                                renderAddForm()
                            }

                            div {
                                id = "category-entry-list"
                                style = "overflow-y: auto; flex: 1; min-height: 120px;"
                            }
                        }
                    }
                }
            }
        }

        renderEntryList()
    }

    private fun FlowContent.renderTypeRail() {
        div {
            style = """
                width: 200px;
                flex-shrink: 0;
                border-right: 1px solid #dadce0;
                background-color: #f8f9fa;
                padding: 12px 0;
                overflow-y: auto;
            """.trimIndent()

            CategoryKind.entries.forEach { kind ->
                val selected = kind == activeKind
                div {
                    style = """
                        display: flex;
                        align-items: center;
                        gap: 10px;
                        padding: 12px 16px;
                        font-size: 14px;
                        cursor: pointer;
                        color: ${if (selected) "#1a73e8" else "#3c4043"};
                        background-color: ${if (selected) "#e8f0fe" else "transparent"};
                        font-weight: ${if (selected) "500" else "400"};
                        border-left: 3px solid ${if (selected) "#1a73e8" else "transparent"};
                    """.trimIndent()
                    if (!selected) {
                        attributes["onmouseover"] = "this.style.backgroundColor='#f1f3f4'"
                        attributes["onmouseout"] = "this.style.backgroundColor='transparent'"
                    }
                    onClickFunction = { switchKind(kind) }

                    span {
                        classes = setOf("mdi", kind.icon)
                        style = "font-size: 18px;"
                    }
                    span {
                        style = "flex: 1;"
                        +kind.plural
                    }
                    span {
                        style = "font-size: 12px; color: #5f6368;"
                        +(entryCounts[kind.slug]?.toString() ?: "")
                    }
                }
            }
        }
    }

    private fun FlowContent.renderPanelHeader() {
        val unused = entries.count { it.usageCount == 0 }
        val duplicateCount = entries.count { isPossibleDuplicate(it) }

        div {
            style = "flex-shrink: 0;"

            div {
                style = "display: flex; align-items: baseline; gap: 10px; margin-bottom: 4px;"
                h3 {
                    style = "margin: 0; font-size: 16px; color: #202124; font-weight: 500;"
                    +"${activeKind.plural} (${entries.size})"
                }
                if (unused > 0 || duplicateCount > 0) {
                    span {
                        style = "font-size: 13px; color: #5f6368;"
                        val notes = buildList {
                            if (duplicateCount > 0) add("$duplicateCount possible duplicate${if (duplicateCount == 1) "" else "s"}")
                            if (unused > 0) add("$unused unused")
                        }
                        +notes.joinToString(", ")
                    }
                }
            }

            // Search and add
            div {
                style = "display: flex; gap: 8px; margin: 12px 0 16px 0;"

                input(type = InputType.text) {
                    id = "category-search"
                    value = searchQuery
                    placeholder = "Search ${activeKind.plural.lowercase()}..."
                    style = """
                        flex: 1;
                        padding: 9px 12px;
                        font-size: 14px;
                        border: 1px solid #dadce0;
                        border-radius: 4px;
                        box-sizing: border-box;
                        font-family: 'Roboto', arial, sans-serif;
                    """.trimIndent()
                    attributes["onfocus"] = "this.style.borderColor='#1a73e8'"
                    attributes["onblur"] = "this.style.borderColor='#dadce0'"
                    onInputFunction = { event ->
                        searchQuery = (event.target as? HTMLInputElement)?.value ?: ""
                        renderEntryList()
                    }
                }

                button {
                    style = """
                        padding: 9px 16px;
                        font-size: 14px;
                        cursor: pointer;
                        background-color: #1a73e8;
                        color: white;
                        border: none;
                        border-radius: 4px;
                        font-weight: 500;
                        white-space: nowrap;
                        transition: background-color 0.2s;
                    """.trimIndent()
                    attributes["onmouseover"] = "this.style.backgroundColor='#1765cc'"
                    attributes["onmouseout"] = "this.style.backgroundColor='#1a73e8'"
                    +"+ Add ${activeKind.singular}"
                    onClickFunction = {
                        addFormVisible = !addFormVisible
                        renamingId = null
                        mergingId = null
                        render()
                    }
                }
            }
        }
    }

    private fun FlowContent.renderAddForm() {
        div {
            style = """
                border: 1px solid #dadce0;
                border-radius: 8px;
                padding: 16px;
                background-color: #f8f9fa;
                margin-bottom: 16px;
                flex-shrink: 0;
            """.trimIndent()

            div {
                style = "font-size: 14px; font-weight: 500; color: #202124; margin-bottom: 12px;"
                +"New ${activeKind.singular}"
            }

            input(type = InputType.text) {
                id = "category-new-name"
                placeholder = "${activeKind.singular} name"
                style = textInputStyle()
                attributes["onfocus"] = "this.style.borderColor='#1a73e8'"
                attributes["onblur"] = "this.style.borderColor='#dadce0'"
            }

            if (activeKind.hasDescription) {
                textArea {
                    id = "category-new-description"
                    placeholder = "Description (optional)"
                    rows = "2"
                    style = textInputStyle() + " margin-top: 8px; resize: vertical;"
                    attributes["onfocus"] = "this.style.borderColor='#1a73e8'"
                    attributes["onblur"] = "this.style.borderColor='#dadce0'"
                }
            }

            div {
                style = "display: flex; gap: 8px; justify-content: flex-end; margin-top: 12px;"
                secondaryButton("Cancel") {
                    addFormVisible = false
                    render()
                }
                primaryButton("Add") { handleAdd() }
            }
        }
    }

    /**
     * Rebuild only the entry rows, so typing in the search box keeps focus.
     */
    private fun renderEntryList() {
        val listContainer = document.getElementById("category-entry-list") ?: return
        listContainer.innerHTML = ""

        val visible = entries.filter { it.name.contains(searchQuery.trim(), ignoreCase = true) }

        listContainer.append {
            div {
                if (visible.isEmpty()) {
                    p {
                        style = "text-align: center; color: #5f6368; font-size: 14px; margin: 24px 0;"
                        +if (entries.isEmpty()) {
                            "No ${activeKind.plural.lowercase()} yet"
                        } else {
                            "No ${activeKind.plural.lowercase()} match \"${searchQuery.trim()}\""
                        }
                    }
                } else {
                    visible.forEach { renderEntryRow(it) }
                }
            }
        }
    }

    private fun FlowContent.renderEntryRow(entry: CategoryEntryResponse) {
        div {
            style = """
                padding: 12px;
                margin-bottom: 8px;
                background-color: white;
                border: 1px solid ${if (isPossibleDuplicate(entry)) "#f9ab00" else "#dadce0"};
                border-radius: 4px;
            """.trimIndent()

            when (entry.id) {
                renamingId -> renderRenameForm(entry)
                mergingId -> renderMergeForm(entry)
                else -> renderEntrySummary(entry)
            }
        }
    }

    private fun FlowContent.renderEntrySummary(entry: CategoryEntryResponse) {
        div {
            style = "display: flex; justify-content: space-between; align-items: flex-start; gap: 12px;"

            div {
                style = "flex: 1; min-width: 0;"

                div {
                    style = "display: flex; align-items: center; gap: 8px; flex-wrap: wrap;"
                    span {
                        style = "font-size: 14px; color: #202124; font-weight: 500;"
                        +entry.name
                    }
                    if (isPossibleDuplicate(entry)) {
                        span {
                            style = chipStyle("#fef7e0", "#b06000")
                            +"possible duplicate"
                        }
                    }
                    if (entry.usageCount == 0) {
                        span {
                            style = chipStyle("#f1f3f4", "#5f6368")
                            +"unused"
                        }
                    }
                }

                div {
                    style = "font-size: 13px; color: #5f6368; margin-top: 4px;"
                    +when (entry.usageCount) {
                        0 -> "Not used by any movie"
                        1 -> "Used by 1 movie"
                        else -> "Used by ${entry.usageCount} movies"
                    }
                }

                if (!entry.description.isNullOrBlank()) {
                    div {
                        style = "font-size: 13px; color: #5f6368; margin-top: 4px; line-height: 1.5;"
                        +entry.description
                    }
                }
            }

            div {
                style = "display: flex; gap: 6px; flex-shrink: 0;"

                rowButton("Rename", color = "#1a73e8", hoverBackground = "#e8f0fe") {
                    renamingId = entry.id
                    mergingId = null
                    renderEntryList()
                }

                if (entries.size > 1) {
                    rowButton("Merge", color = "#3c4043", hoverBackground = "#f1f3f4") {
                        mergingId = entry.id
                        renamingId = null
                        renderEntryList()
                    }
                }

                rowButton("Delete", color = "#d93025", hoverBackground = "#fce8e6") {
                    handleDelete(entry)
                }
            }
        }
    }

    private fun FlowContent.renderRenameForm(entry: CategoryEntryResponse) {
        div {
            div {
                style = "font-size: 13px; color: #5f6368; margin-bottom: 8px;"
                +"Rename \"${entry.name}\""
            }

            input(type = InputType.text) {
                id = "category-rename-input"
                value = entry.name
                style = textInputStyle()
                attributes["onfocus"] = "this.style.borderColor='#1a73e8'"
                attributes["onblur"] = "this.style.borderColor='#dadce0'"
            }

            if (activeKind.hasDescription) {
                textArea {
                    id = "category-rename-description"
                    placeholder = "Description (optional)"
                    rows = "2"
                    style = textInputStyle() + " margin-top: 8px; resize: vertical;"
                    +(entry.description ?: "")
                    attributes["onfocus"] = "this.style.borderColor='#1a73e8'"
                    attributes["onblur"] = "this.style.borderColor='#dadce0'"
                }
            }

            div {
                style = "display: flex; gap: 8px; justify-content: flex-end; margin-top: 12px;"
                secondaryButton("Cancel") {
                    renamingId = null
                    renderEntryList()
                }
                primaryButton("Save") { handleRename(entry) }
            }
        }
    }

    private fun FlowContent.renderMergeForm(entry: CategoryEntryResponse) {
        div {
            div {
                style = "font-size: 13px; color: #5f6368; margin-bottom: 8px;"
                +"Merge \"${entry.name}\" into another ${activeKind.singular.lowercase()}"
            }

            select {
                id = "category-merge-target"
                style = textInputStyle()
                entries.filter { it.id != entry.id }.forEach { candidate ->
                    option {
                        value = candidate.id.toString()
                        +candidate.name
                    }
                }
            }

            div {
                style = "display: flex; gap: 8px; justify-content: flex-end; margin-top: 12px;"
                secondaryButton("Cancel") {
                    mergingId = null
                    renderEntryList()
                }
                primaryButton("Merge") { handleMerge(entry) }
            }
        }
    }

    private fun switchKind(kind: CategoryKind) {
        if (kind == activeKind) return

        activeKind = kind
        searchQuery = ""
        renamingId = null
        mergingId = null
        addFormVisible = false

        mainScope.launch {
            entries = try {
                fetchCategoryEntries(kind.slug)
            } catch (e: Exception) {
                alertDialog.show(title = "Error", message = "Failed to load ${kind.plural.lowercase()}: ${e.message}")
                emptyList()
            }
            render()
        }
    }

    private fun handleAdd() {
        val name = (document.getElementById("category-new-name") as? HTMLInputElement)?.value?.trim() ?: ""
        val description = (document.getElementById("category-new-description") as? HTMLTextAreaElement)
            ?.value?.trim()?.takeIf { it.isNotBlank() }

        if (name.isBlank()) {
            alertDialog.show(title = "Validation Error", message = "${activeKind.singular} name is required.")
            return
        }

        mainScope.launch {
            try {
                val saved = createCategoryEntry(activeKind.slug, name, description)
                addFormVisible = false
                refresh()
                alertDialog.show(
                    title = "Success",
                    message = "${activeKind.singular} \"${saved.entry.name}\" added."
                )
            } catch (e: Exception) {
                alertDialog.show(title = "Error", message = "Failed to add ${activeKind.singular.lowercase()}: ${e.message}")
            }
        }
    }

    private fun handleRename(entry: CategoryEntryResponse) {
        val newName = (document.getElementById("category-rename-input") as? HTMLInputElement)?.value?.trim() ?: ""
        val description = (document.getElementById("category-rename-description") as? HTMLTextAreaElement)
            ?.value?.trim()?.takeIf { it.isNotBlank() }

        if (newName.isBlank()) {
            alertDialog.show(title = "Validation Error", message = "${activeKind.singular} name is required.")
            return
        }

        val existing = entries.firstOrNull { it.id != entry.id && it.name.equals(newName, ignoreCase = true) }
        if (existing != null) {
            confirmDialog.show(
                title = "Merge ${activeKind.plural.lowercase()}?",
                message = "\"${existing.name}\" already exists. Merging \"${entry.name}\" into it will update " +
                    "${movieCountLabel(entry.usageCount)} and remove \"${entry.name}\".",
                confirmText = "Merge",
                cancelText = "Cancel",
                onConfirm = { applyRename(entry, newName, description, allowMerge = true) }
            )
            return
        }

        applyRename(entry, newName, description, allowMerge = false)
    }

    private fun applyRename(
        entry: CategoryEntryResponse,
        newName: String,
        description: String?,
        allowMerge: Boolean
    ) {
        mainScope.launch {
            try {
                val saved = renameCategoryEntry(activeKind.slug, entry.id, newName, description, allowMerge)
                renamingId = null
                refresh()
                alertDialog.show(
                    title = "Success",
                    message = if (saved.merged) {
                        "\"${saved.mergedName ?: entry.name}\" merged into \"${saved.entry.name}\", " +
                            "updating ${movieCountLabel(saved.moviesUpdated)}."
                    } else {
                        "Renamed to \"${saved.entry.name}\" on ${movieCountLabel(saved.entry.usageCount)}."
                    }
                )
            } catch (e: Exception) {
                alertDialog.show(title = "Error", message = "Failed to rename: ${e.message}")
            }
        }
    }

    private fun handleMerge(entry: CategoryEntryResponse) {
        val targetId = (document.getElementById("category-merge-target") as? HTMLSelectElement)
            ?.value?.toIntOrNull()
        val target = entries.firstOrNull { it.id == targetId }

        if (target == null) {
            alertDialog.show(title = "Validation Error", message = "Pick a ${activeKind.singular.lowercase()} to merge into.")
            return
        }

        confirmDialog.show(
            title = "Merge ${activeKind.plural.lowercase()}?",
            message = "\"${entry.name}\" will be merged into \"${target.name}\". This updates " +
                "${movieCountLabel(entry.usageCount)} and removes \"${entry.name}\".",
            confirmText = "Merge",
            cancelText = "Cancel",
            onConfirm = {
                mainScope.launch {
                    try {
                        val saved = mergeCategoryEntries(activeKind.slug, listOf(entry.id), target.id)
                        mergingId = null
                        refresh()
                        alertDialog.show(
                            title = "Success",
                            message = "\"${entry.name}\" merged into \"${saved.entry.name}\", " +
                                "updating ${movieCountLabel(saved.moviesUpdated)}."
                        )
                    } catch (e: Exception) {
                        alertDialog.show(title = "Error", message = "Failed to merge: ${e.message}")
                    }
                }
            }
        )
    }

    private fun handleDelete(entry: CategoryEntryResponse) {
        val consequence = when {
            entry.usageCount == 0 -> "It is not used by any movie."
            activeKind == CategoryKind.DISTRIBUTORS ->
                "The ${movieCountLabel(entry.usageCount)} using it will keep their physical media entries, but without a distributor."
            else -> "It will be removed from the ${movieCountLabel(entry.usageCount)} using it."
        }

        confirmDialog.show(
            title = "Delete ${activeKind.singular}",
            message = "Are you sure you want to delete \"${entry.name}\"? $consequence",
            confirmText = "Delete",
            cancelText = "Cancel",
            onConfirm = {
                mainScope.launch {
                    try {
                        if (deleteCategoryEntry(activeKind.slug, entry.id)) {
                            renamingId = null
                            mergingId = null
                            refresh()
                            alertDialog.show(
                                title = "Success",
                                message = "${activeKind.singular} \"${entry.name}\" deleted."
                            )
                        } else {
                            alertDialog.show(title = "Error", message = "Failed to delete ${activeKind.singular.lowercase()}")
                        }
                    } catch (e: Exception) {
                        alertDialog.show(title = "Error", message = "Error deleting ${activeKind.singular.lowercase()}: ${e.message}")
                    }
                }
            }
        )
    }

    private suspend fun refresh() {
        loadData()
        render()
    }

    /**
     * True when another entry only differs by case, spacing, or punctuation -
     * the shape a misspelling or reformatting usually takes.
     */
    private fun isPossibleDuplicate(entry: CategoryEntryResponse): Boolean {
        val key = comparisonKey(entry.name)
        return entries.any { it.id != entry.id && comparisonKey(it.name) == key }
    }

    private fun comparisonKey(name: String): String =
        name.lowercase().filter { it.isLetterOrDigit() }

    private fun movieCountLabel(count: Int): String = if (count == 1) "1 movie" else "$count movies"

    private fun textInputStyle() = """
        width: 100%;
        padding: 9px 12px;
        font-size: 14px;
        border: 1px solid #dadce0;
        border-radius: 4px;
        box-sizing: border-box;
        font-family: 'Roboto', arial, sans-serif;
    """.trimIndent()

    private fun FlowContent.primaryButton(label: String, onClick: () -> Unit) {
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
            +label
            onClickFunction = { onClick() }
        }
    }

    private fun FlowContent.secondaryButton(label: String, onClick: () -> Unit) {
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
            +label
            onClickFunction = { onClick() }
        }
    }

    private fun FlowContent.rowButton(
        label: String,
        color: String,
        hoverBackground: String,
        onClick: () -> Unit
    ) {
        button {
            style = """
                padding: 4px 12px;
                font-size: 13px;
                cursor: pointer;
                background-color: #ffffff;
                color: $color;
                border: 1px solid #dadce0;
                border-radius: 4px;
                font-weight: 500;
                transition: background-color 0.2s;
            """.trimIndent()
            attributes["onmouseover"] = "this.style.backgroundColor='$hoverBackground'"
            attributes["onmouseout"] = "this.style.backgroundColor='#ffffff'"
            +label
            onClickFunction = { onClick() }
        }
    }

    private fun chipStyle(background: String, color: String) = """
        font-size: 11px;
        font-weight: 500;
        padding: 2px 8px;
        border-radius: 10px;
        background-color: $background;
        color: $color;
        text-transform: uppercase;
        letter-spacing: 0.3px;
    """.trimIndent()

    private fun close() {
        document.getElementById("category-management-modal")?.remove()
        onClose()
    }
}
