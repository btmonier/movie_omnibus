package org.btmonier

import kotlinx.browser.document
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.onClickFunction
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement

private const val BULK_STATUS_DIALOG_ID = "wishlist-bulk-status-dialog"

/**
 * Moves several items to Shipped or Owned at once, with one shipped date and
 * tracking URL, or one received date and shelf location, for all of them.
 * Films are still chosen per item when receiving, since each release holds
 * different ones.
 *
 * [skipped] is how many selected items were left out because the step does
 * not apply to them, so the dialog can say so.
 */
class BulkStatusDialog(
    private val container: Element,
    items: List<WishlistItem>,
    private val target: WishlistStatus,
    private val skipped: Int = 0,
    private val onDone: (List<WishlistItem>) -> Unit
) {
    init {
        require(target == WishlistStatus.SHIPPED || target == WishlistStatus.OWNED) { "Only shipping and receiving work in bulk" }
    }

    private val alertDialog = AlertDialog(container)
    private val lines: MutableList<WishlistItem> = items.filter { it.id != null }.toMutableList()
    private val films: MutableMap<Int, MutableList<WishlistMovie>> =
        lines.associate { it.id!! to it.linkedMovies.toMutableList() }.toMutableMap()
    private val existingReleases = mutableMapOf<Int, ReleaseSummary>()
    private var isSaving = false

    private fun id(field: String) = "$BULK_STATUS_DIALOG_ID-$field"

    fun show() {
        close()
        container.append {
            div {
                id = BULK_STATUS_DIALOG_ID
                style = modalOverlayStyle(1450)
                onClickFunction = { event ->
                    if (event.target == document.getElementById(BULK_STATUS_DIALOG_ID)) close()
                }
                div {
                    style = modalPanelStyle(720)

                    h2 {
                        style = "margin: 0 0 4px 0; color: #202124; font-size: 20px; display: flex; align-items: center; gap: 10px;"
                        span { classes = setOf("mdi", statusIcon(target)); style = "color: ${statusColors(target).second}; font-size: 24px;" }
                        +advanceLabel(target)
                    }
                    p {
                        id = id("subtitle")
                        style = "margin: 0 0 20px 0; color: #5f6368; font-size: 14px;"
                    }

                    div { id = id("no-purchase") }

                    div {
                        style = "display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px;"
                        if (target == WishlistStatus.SHIPPED) {
                            div {
                                formLabel("Shipped on")
                                input(type = InputType.date) {
                                    id = id("shipped-date")
                                    value = todayIso()
                                    style = formInputStyle()
                                }
                            }
                            div {
                                formLabel("Tracking URL")
                                input(type = InputType.url) {
                                    id = id("tracking")
                                    placeholder = "https://"
                                    style = formInputStyle()
                                }
                            }
                        } else {
                            div {
                                formLabel("Received on")
                                input(type = InputType.date) {
                                    id = id("received-date")
                                    value = todayIso()
                                    style = formInputStyle()
                                }
                            }
                            div {
                                formLabel("Location")
                                select {
                                    id = id("location")
                                    style = formInputStyle()
                                    listOf("Shelf", "Archive").forEach { loc -> option { value = loc; +loc } }
                                }
                            }
                        }
                    }
                    if (target == WishlistStatus.SHIPPED) {
                        p {
                            style = "margin: 8px 0 0 0; font-size: 12px; color: #80868b;"
                            +"Leave the tracking URL blank to keep any already recorded on each item."
                        }
                    }

                    hr { style = "border: none; border-top: 1px solid #e8eaed; margin: 20px 0;" }
                    h3 {
                        style = "margin: 0 0 12px 0; font-size: 15px; color: #202124;"
                        +(if (target == WishlistStatus.OWNED) "Items and the films on each" else "Items")
                    }
                    div { id = id("lines") }

                    div {
                        style = "display: flex; justify-content: flex-end; gap: 12px; margin-top: 24px;"
                        button {
                            style = secondaryButtonStyle()
                            +"Cancel"
                            onClickFunction = { close() }
                        }
                        button {
                            id = id("save")
                            style = primaryButtonStyle(statusColors(target).second)
                            span { classes = setOf("mdi", statusIcon(target)); style = "font-size: 18px;" }
                            span { id = id("save-label") }
                            onClickFunction = { save() }
                        }
                    }
                }
            }
        }
        renderLines()

        if (target == WishlistStatus.OWNED) {
            mainScope.launch {
                val found = coroutineScope {
                    lines.filter { it.releaseId == null && !it.blurayComUrl.isNullOrBlank() }.map { item ->
                        async { item.id!! to runCatching { findReleaseByBluRayUrl(item.blurayComUrl!!) }.getOrNull() }
                    }.awaitAll()
                }
                found.forEach { (itemId, release) -> if (release != null) existingReleases[itemId] = release }
                if (existingReleases.isNotEmpty()) renderLines()
            }
        }
    }

    fun close() {
        document.getElementById(BULK_STATUS_DIALOG_ID)?.remove()
    }

    private fun renderLines() {
        document.getElementById(id("subtitle"))?.textContent = buildString {
            append(
                if (target == WishlistStatus.OWNED) "${lines.size} item(s) arriving together. Each becomes a release in your collection."
                else "${lines.size} item(s) shipping together."
            )
            if (skipped > 0) append(" $skipped selected item(s) are not at this step and are left out.")
        }
        document.getElementById(id("save-label"))?.textContent =
            if (target == WishlistStatus.OWNED) "Mark ${lines.size} received" else "Mark ${lines.size} shipped"
        (document.getElementById(id("save")) as? HTMLButtonElement)?.disabled = lines.isEmpty() || isSaving

        renderNoPurchaseNotice()

        val list = document.getElementById(id("lines")) ?: return
        list.innerHTML = ""
        list.append {
            if (lines.isEmpty()) {
                div {
                    style = "padding: 12px; color: #80868b; font-size: 13px; background-color: #f8f9fa; border-radius: 6px; border: 1px dashed #dadce0;"
                    +"No items left."
                }
                return@append
            }
            div {
                style = "display: flex; flex-direction: column; gap: 8px;"
                lines.forEach { item -> line(item) }
            }
        }
    }

    private fun FlowContent.line(item: WishlistItem) {
        val itemId = item.id!!
        div {
            style = "padding: 10px 12px; background-color: #f8f9fa; border-radius: 6px;"
            div {
                style = "display: flex; align-items: center; gap: 12px;"
                div {
                    style = "flex: 1; min-width: 0; font-size: 14px; color: #202124; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;"
                    +(item.title ?: "Untitled release")
                }
                if (target == WishlistStatus.OWNED) {
                    button {
                        style = outlineButtonStyle("#1a73e8")
                        span { classes = setOf("mdi", "mdi-plus"); style = "font-size: 16px;" }
                        +"Add film"
                        onClickFunction = {
                            val current = films.getOrPut(itemId) { mutableListOf() }
                            MoviePicker(container, excludedMovieIds = current.map { it.movieId }.toSet()) { movie ->
                                movie.id?.let { current.add(WishlistMovie(it, movie.title, movie.release_date)) }
                                renderLines()
                            }.show()
                        }
                    }
                }
                button {
                    style = "background: none; border: none; cursor: pointer; color: #5f6368; padding: 4px;"
                    attributes["title"] = "Leave this item out"
                    span { classes = setOf("mdi", "mdi-close"); style = "font-size: 18px;" }
                    onClickFunction = {
                        lines.removeAll { it.id == itemId }
                        renderLines()
                    }
                }
            }

            if (target != WishlistStatus.OWNED) return@div

            existingReleases[itemId]?.let { existing ->
                div {
                    style = "margin-top: 6px; font-size: 12px; color: #174ea6; display: flex; gap: 6px; align-items: center;"
                    span { classes = setOf("mdi", "mdi-link-variant"); style = "font-size: 14px;" }
                    +"Joins \"${existing.title ?: "an untitled release"}\" already in your collection (${existing.filmCount} film(s))."
                }
            }

            val chosen = films[itemId].orEmpty()
            div {
                style = "display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px;"
                if (chosen.isEmpty()) {
                    span {
                        style = "font-size: 12px; color: #80868b;"
                        +"No films chosen - the release is created without any; add them later from the release page."
                    }
                }
                chosen.forEach { movie ->
                    span {
                        style = "display: inline-flex; align-items: center; gap: 4px; padding: 3px 4px 3px 10px; background-color: white; border: 1px solid #dadce0; border-radius: 12px; font-size: 12px; color: #202124;"
                        +movie.title
                        movie.releaseYear?.let { span { style = "color: #80868b;"; +"($it)" } }
                        button {
                            style = "background: none; border: none; cursor: pointer; color: #5f6368; padding: 0 2px; line-height: 1;"
                            attributes["title"] = "Remove"
                            span { classes = setOf("mdi", "mdi-close"); style = "font-size: 14px;" }
                            onClickFunction = {
                                films[itemId]?.removeAll { it.movieId == movie.movieId }
                                renderLines()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun renderNoPurchaseNotice() {
        val notice = document.getElementById(id("no-purchase")) ?: return
        notice.innerHTML = ""
        val unpaid = lines.filter { it.purchase == null }
        if (unpaid.isEmpty()) return
        notice.append {
            div {
                style = """
                    background-color: #fef7e0; border: 1px solid #feefc3; border-radius: 6px; padding: 10px 14px;
                    margin-bottom: 16px; font-size: 13px; color: #8a6d00; display: flex; gap: 8px; align-items: flex-start;
                """.trimIndent()
                span { classes = setOf("mdi", "mdi-alert-outline"); style = "font-size: 18px;" }
                span {
                    +"No purchase is recorded for ${unpaid.joinToString(", ") { "\"${it.title ?: "Untitled release"}\"" }}. "
                    +"They will move anyway; record what was paid later from the item or the release page."
                }
            }
        }
    }

    private fun save() {
        if (isSaving || lines.isEmpty()) return

        val ids = lines.map { it.id!! }
        val request = if (target == WishlistStatus.SHIPPED) {
            WishlistBulkTransitionRequest(
                itemIds = ids,
                status = target,
                shippedDate = inputValue(id("shipped-date")),
                trackingUrl = inputValue(id("tracking"))
            )
        } else {
            WishlistBulkTransitionRequest(
                itemIds = ids,
                status = target,
                receivedDate = inputValue(id("received-date")),
                location = (document.getElementById(id("location")) as? HTMLSelectElement)?.value,
                movieIds = ids.associateWith { itemId -> films[itemId].orEmpty().map { it.movieId } }
            )
        }

        isSaving = true
        val button = document.getElementById(id("save")) as? HTMLButtonElement
        button?.disabled = true
        mainScope.launch {
            try {
                val updated = bulkTransitionWishlistItems(request)
                close()
                onDone(updated)
            } catch (e: Exception) {
                isSaving = false
                button?.disabled = false
                alertDialog.show(title = "Error", message = e.message ?: "Failed to update the items.")
            }
        }
    }

    private fun inputValue(elementId: String): String? =
        (document.getElementById(elementId) as? HTMLInputElement)?.value?.trim()?.takeIf { it.isNotEmpty() }
}
