package org.btmonier

import kotlinx.browser.document
import kotlinx.coroutines.launch
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.onChangeFunction
import kotlinx.html.js.onClickFunction
import kotlinx.html.js.onKeyDownFunction
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLTextAreaElement
import org.w3c.dom.events.KeyboardEvent

private const val FORM_ID = "wishlist-item-form"

/**
 * Add or edit a wishlist item.
 *
 * Adding normally means pasting a blu-ray.com URL: the server scrapes the
 * release details and first prices in one step, so the form only asks for the
 * tracking fields (priority, target price, tags, notes, films). Details can
 * also be typed by hand for releases that are not on blu-ray.com, and every
 * field is editable afterwards.
 */
class WishlistItemForm(
    private val container: Element,
    private val existing: WishlistItem?,
    private val availableTags: List<String>,
    private val onSaved: (WishlistItem) -> Unit
) {
    private val alertDialog = AlertDialog(container)
    private val isEdit = existing != null
    private var manualEntry = isEdit
    private var tags: MutableList<String> = existing?.tags?.toMutableList() ?: mutableListOf()
    private var linkedMovies: MutableList<WishlistMovie> = existing?.linkedMovies?.toMutableList() ?: mutableListOf()
    private var selectedMediaTypes: MutableSet<MediaType> = existing?.mediaTypes?.toMutableSet() ?: mutableSetOf()
    private var distributor: String? = existing?.distributor
    private var isSaving = false

    fun show() {
        render()
        renderTags()
        renderMovies()
        if (manualEntry) mountDistributorSelector()
    }

    fun close() {
        document.getElementById(FORM_ID)?.remove()
    }

    private fun render() {
        close()
        container.append {
            div {
                id = FORM_ID
                style = modalOverlayStyle(1400)
                onClickFunction = { event ->
                    if (event.target == document.getElementById(FORM_ID)) close()
                }

                div {
                    style = modalPanelStyle(680)

                    h2 {
                        style = "margin: 0 0 4px 0; color: #202124; font-size: 20px; display: flex; align-items: center; gap: 10px;"
                        span { classes = setOf("mdi", "mdi-heart-outline"); style = "color: #b3157a; font-size: 24px;" }
                        +if (isEdit) "Edit wishlist item" else "Add to wishlist"
                    }
                    p {
                        style = "margin: 0 0 20px 0; color: #5f6368; font-size: 13px;"
                        +if (isEdit) "Change anything about this release or how you are tracking it."
                        else "Paste a blu-ray.com release URL and the details and current price are filled in for you."
                    }

                    if (!isEdit) urlSection()

                    div {
                        id = "$FORM_ID-details"
                        style = if (manualEntry) "" else "display: none;"
                        detailsSection()
                    }

                    trackingSection()

                    div {
                        style = "display: flex; justify-content: flex-end; gap: 12px; margin-top: 24px;"
                        button {
                            style = secondaryButtonStyle()
                            +"Cancel"
                            onClickFunction = { close() }
                        }
                        button {
                            id = "$FORM_ID-save"
                            style = primaryButtonStyle("#b3157a")
                            span { classes = setOf("mdi", if (isEdit) "mdi-content-save" else "mdi-heart-plus"); style = "font-size: 18px;" }
                            +if (isEdit) "Save changes" else "Add to wishlist"
                            onClickFunction = { save() }
                        }
                    }
                }
            }
        }
    }

    private fun FlowContent.sectionHeading(icon: String, text: String) {
        h3 {
            style = "margin: 20px 0 12px 0; font-size: 15px; color: #202124; display: flex; align-items: center; gap: 8px;"
            span { classes = setOf("mdi", icon); style = "color: #b3157a; font-size: 18px;" }
            +text
        }
    }

    private fun FlowContent.urlSection() {
        div {
            formLabel("blu-ray.com URL", "$FORM_ID-url")
            input(type = InputType.url) {
                id = "$FORM_ID-url"
                placeholder = "https://www.blu-ray.com/movies/Movie-Title/123456/"
                style = formInputStyle()
                attributes["autofocus"] = "true"
            }
            label {
                style = "display: flex; align-items: center; gap: 8px; font-size: 13px; color: #5f6368; margin-top: 10px; cursor: pointer;"
                input(type = InputType.checkBox) {
                    checked = manualEntry
                    onChangeFunction = { event ->
                        manualEntry = (event.target as HTMLInputElement).checked
                        val details = document.getElementById("$FORM_ID-details") as? org.w3c.dom.HTMLElement
                        details?.style?.display = if (manualEntry) "" else "none"
                        if (manualEntry) mountDistributorSelector()
                    }
                }
                +"Enter the release details by hand (no blu-ray.com page, or fix what it says)"
            }
        }
    }

    private fun FlowContent.detailsSection() {
        sectionHeading("mdi-disc", "Release details")

        div {
            style = "display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px;"
            div {
                style = "grid-column: 1 / -1;"
                formLabel("Title")
                input(type = InputType.text) {
                    id = "$FORM_ID-title"
                    value = existing?.title ?: ""
                    placeholder = "Release or box set title"
                    style = formInputStyle()
                }
            }
            div { id = "$FORM_ID-distributor" }
            div {
                formLabel("Release date")
                input(type = InputType.date) {
                    id = "$FORM_ID-release-date"
                    value = existing?.releaseDate ?: ""
                    style = formInputStyle()
                }
            }
            if (isEdit) {
                div {
                    formLabel("blu-ray.com URL")
                    input(type = InputType.url) {
                        id = "$FORM_ID-url"
                        value = existing?.blurayComUrl ?: ""
                        style = formInputStyle()
                    }
                }
                div {
                    formLabel("List price (MSRP)")
                    input(type = InputType.number) {
                        id = "$FORM_ID-list-price"
                        value = existing?.listPrice?.toString() ?: ""
                        attributes["step"] = "0.01"
                        attributes["min"] = "0"
                        style = formInputStyle()
                    }
                }
            }
        }

        div {
            style = "margin-top: 12px;"
            formLabel("Formats")
            div {
                style = "display: flex; flex-wrap: wrap; gap: 14px;"
                MediaType.entries.forEach { type ->
                    label {
                        style = "display: flex; align-items: center; gap: 6px; font-size: 14px; cursor: pointer;"
                        input(type = InputType.checkBox) {
                            checked = type in selectedMediaTypes
                            onChangeFunction = { event ->
                                if ((event.target as HTMLInputElement).checked) selectedMediaTypes.add(type) else selectedMediaTypes.remove(type)
                            }
                        }
                        +mediaTypeLabel(type)
                    }
                }
            }
        }

        label {
            style = "display: flex; align-items: center; gap: 8px; font-size: 14px; margin-top: 12px; cursor: pointer;"
            input(type = InputType.checkBox) {
                id = "$FORM_ID-collection"
                checked = existing?.isCollection ?: false
            }
            +"Box set / holds several films"
        }
    }

    private fun FlowContent.trackingSection() {
        sectionHeading("mdi-bell-ring-outline", "Tracking")

        div {
            style = "display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 12px;"
            div {
                formLabel("Priority")
                select {
                    id = "$FORM_ID-priority"
                    style = formInputStyle()
                    WishlistPriority.entries.forEach { p ->
                        option {
                            value = p.name
                            selected = (existing?.priority ?: WishlistPriority.MEDIUM) == p
                            +priorityLabel(p)
                        }
                    }
                }
            }
            div {
                formLabel("Target price")
                div {
                    style = "position: relative;"
                    span { style = "position: absolute; left: 10px; top: 50%; transform: translateY(-50%); color: #80868b; font-size: 13px;"; +"$" }
                    input(type = InputType.number) {
                        id = "$FORM_ID-target"
                        value = existing?.targetPrice?.toString() ?: ""
                        attributes["step"] = "0.01"
                        attributes["min"] = "0"
                        placeholder = "Buy when at or below"
                        style = formInputStyle() + " padding-left: 22px;"
                    }
                }
            }
            div {
                formLabel("Amazon ASIN")
                input(type = InputType.text) {
                    id = "$FORM_ID-asin"
                    value = existing?.asin ?: ""
                    placeholder = "B0..."
                    style = formInputStyle()
                    attributes["title"] = "Optional. Enables CamelCamelCamel and Amazon links."
                }
            }
        }

        div {
            style = "margin-top: 12px;"
            formLabel("Tags")
            div {
                style = "display: flex; gap: 8px;"
                input(type = InputType.text) {
                    id = "$FORM_ID-tag-input"
                    placeholder = "Black Friday, Criterion sale, Gift idea..."
                    style = formInputStyle()
                    attributes["list"] = "$FORM_ID-tag-options"
                    onKeyDownFunction = { event ->
                        if ((event as KeyboardEvent).key == "Enter") {
                            event.preventDefault()
                            addTagFromInput()
                        }
                    }
                }
                dataList {
                    id = "$FORM_ID-tag-options"
                    availableTags.filter { it !in tags }.forEach { option { value = it } }
                }
                button {
                    style = outlineButtonStyle("#7627bb")
                    type = ButtonType.button
                    +"Add"
                    onClickFunction = { addTagFromInput() }
                }
            }
            div {
                id = "$FORM_ID-tags"
                style = "display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; min-height: 22px;"
            }
        }

        div {
            style = "margin-top: 12px;"
            formLabel("Films in your collection this release is for")
            div { id = "$FORM_ID-movies" }
            button {
                style = outlineButtonStyle("#1a73e8") + " margin-top: 8px;"
                type = ButtonType.button
                span { classes = setOf("mdi", "mdi-plus"); style = "font-size: 16px;" }
                +"Add film"
                onClickFunction = {
                    MoviePicker(container, excludedMovieIds = linkedMovies.map { it.movieId }.toSet()) { movie ->
                        movie.id?.let { linkedMovies.add(WishlistMovie(it, movie.title, movie.release_date)) }
                        renderMovies()
                    }.show()
                }
            }
        }

        div {
            style = "margin-top: 12px;"
            formLabel("Notes")
            textArea {
                id = "$FORM_ID-notes"
                rows = "2"
                placeholder = "Edition to look for, why you want it, where you saw it..."
                style = formInputStyle() + " resize: vertical;"
                +(existing?.notes ?: "")
            }
        }
    }

    private fun mountDistributorSelector() {
        if (document.getElementById("$FORM_ID-distributor-input") != null) return
        mainScope.launch {
            try {
                DistributorSelector("$FORM_ID-distributor", distributor) { distributor = it }.render()
            } catch (e: Exception) {
                console.error("Failed to load distributors:", e)
            }
        }
    }

    private fun addTagFromInput() {
        val input = document.getElementById("$FORM_ID-tag-input") as? HTMLInputElement ?: return
        val value = input.value.trim()
        if (value.isEmpty()) return
        if (tags.none { it.equals(value, ignoreCase = true) }) tags.add(value)
        input.value = ""
        renderTags()
    }

    private fun renderTags() {
        val list = document.getElementById("$FORM_ID-tags") ?: return
        list.innerHTML = ""
        list.append {
            if (tags.isEmpty()) {
                span { style = "font-size: 12px; color: #80868b;"; +"No tags yet. Tags group items on the wishlist page." }
                return@append
            }
            tags.forEach { tag ->
                span {
                    style = chipStyle("#f3e8fd", "#7627bb") + " display: inline-flex; align-items: center; gap: 4px; font-size: 12px; padding: 4px 10px;"
                    +tag
                    span {
                        classes = setOf("mdi", "mdi-close")
                        style = "cursor: pointer; font-size: 14px;"
                        onClickFunction = {
                            tags.remove(tag)
                            renderTags()
                        }
                    }
                }
            }
        }
    }

    private fun renderMovies() {
        val list = document.getElementById("$FORM_ID-movies") ?: return
        list.innerHTML = ""
        list.append {
            if (linkedMovies.isEmpty()) {
                div {
                    style = "padding: 10px 12px; color: #80868b; font-size: 13px; background-color: #f8f9fa; border-radius: 6px; border: 1px dashed #dadce0;"
                    +"Optional. When the item arrives, these films get it as a physical media entry."
                }
                return@append
            }
            div {
                style = "display: flex; flex-direction: column; gap: 6px;"
                linkedMovies.forEach { movie ->
                    div {
                        style = "display: flex; align-items: center; justify-content: space-between; padding: 8px 12px; background-color: #f8f9fa; border-radius: 6px; font-size: 14px;"
                        span {
                            +movie.title
                            movie.releaseYear?.let { span { style = "color: #80868b; margin-left: 6px;"; +"($it)" } }
                        }
                        button {
                            style = "background: none; border: none; cursor: pointer; color: #5f6368; padding: 4px;"
                            type = ButtonType.button
                            span { classes = setOf("mdi", "mdi-close"); style = "font-size: 18px;" }
                            onClickFunction = {
                                linkedMovies.removeAll { it.movieId == movie.movieId }
                                renderMovies()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun save() {
        if (isSaving) return
        val url = inputValue("$FORM_ID-url")
        val priority = (document.getElementById("$FORM_ID-priority") as? HTMLSelectElement)?.value
            ?.let { v -> WishlistPriority.entries.firstOrNull { it.name == v } } ?: WishlistPriority.MEDIUM
        val targetPrice = numberValue("$FORM_ID-target")
        val notes = (document.getElementById("$FORM_ID-notes") as? HTMLTextAreaElement)?.value?.trim()?.takeIf { it.isNotEmpty() }
        val asin = inputValue("$FORM_ID-asin")

        val saveButton = document.getElementById("$FORM_ID-save") as? HTMLButtonElement

        if (!isEdit && !manualEntry) {
            if (url == null) {
                alertDialog.show(title = "URL required", message = "Paste a blu-ray.com release URL, or tick the box to enter details by hand.")
                return
            }
            isSaving = true
            saveButton?.disabled = true
            mainScope.launch {
                try {
                    val response = importWishlistItem(
                        WishlistImportRequest(
                            url = url,
                            priority = priority,
                            targetPrice = targetPrice,
                            tags = tags,
                            movieIds = linkedMovies.map { it.movieId },
                            notes = notes
                        )
                    )
                    when {
                        response.success && response.item != null -> {
                            // ASIN is not part of the import request; apply it as a follow-up edit
                            val saved = if (asin != null) updateWishlistItem(response.item.id!!, response.item.copy(asin = asin)) else response.item
                            close()
                            onSaved(saved)
                        }
                        response.existingItem != null -> alertDialog.show(
                            title = "Already on your wishlist",
                            message = "\"${response.existingItem.title ?: "This release"}\" is already on the wishlist (${statusLabel(response.existingItem.status).lowercase()})."
                        )
                        response.existingRelease != null -> alertDialog.show(
                            title = "Already in your collection",
                            message = "\"${response.existingRelease.title ?: "This release"}\" is already recorded as a release you own."
                        )
                        else -> alertDialog.show(title = "Import failed", message = response.error ?: "Unknown error")
                    }
                } catch (e: Exception) {
                    alertDialog.show(title = "Error", message = e.message ?: "Failed to import.")
                } finally {
                    isSaving = false
                    saveButton?.disabled = false
                }
            }
            return
        }

        val title = inputValue("$FORM_ID-title")
        if (title == null && url == null) {
            alertDialog.show(title = "Title required", message = "Give the release a title, or supply a blu-ray.com URL.")
            return
        }

        val item = (existing ?: WishlistItem()).copy(
            title = title,
            isCollection = (document.getElementById("$FORM_ID-collection") as? HTMLInputElement)?.checked ?: false,
            mediaTypes = selectedMediaTypes.toList(),
            distributor = distributor,
            releaseDate = inputValue("$FORM_ID-release-date"),
            blurayComUrl = url,
            listPrice = numberValue("$FORM_ID-list-price") ?: existing?.listPrice,
            priority = priority,
            targetPrice = targetPrice,
            asin = asin,
            notes = notes,
            tags = tags.toList(),
            linkedMovies = linkedMovies.toList()
        )

        isSaving = true
        saveButton?.disabled = true
        mainScope.launch {
            try {
                val saved = if (isEdit) updateWishlistItem(existing!!.id!!, item) else createWishlistItem(item)
                close()
                onSaved(saved)
            } catch (e: Exception) {
                alertDialog.show(title = "Error", message = e.message ?: "Failed to save.")
            } finally {
                isSaving = false
                saveButton?.disabled = false
            }
        }
    }

    private fun inputValue(id: String): String? =
        (document.getElementById(id) as? HTMLInputElement)?.value?.trim()?.takeIf { it.isNotEmpty() }

    private fun numberValue(id: String): Double? = inputValue(id)?.toDoubleOrNull()
}
