package org.btmonier

import kotlinx.browser.document
import kotlinx.coroutines.launch
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.onClickFunction
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement

private const val STATUS_DIALOG_ID = "wishlist-status-dialog"

/**
 * One dialog for every status change, showing only what the target status
 * needs: the purchase when ordering, shipping details when shipped, and the
 * receipt date, shelf location and films to link when the item arrives and
 * becomes a release.
 */
class WishlistStatusDialog(
    private val container: Element,
    private val item: WishlistItem,
    private val target: WishlistStatus,
    private val defaultTaxRate: Double,
    private val onDone: (WishlistItem) -> Unit
) {
    private val alertDialog = AlertDialog(container)
    private var purchaseFields: PurchaseFormFields? = null
    private var linkedMovies: MutableList<WishlistMovie> = item.linkedMovies.toMutableList()
    private var existingRelease: ReleaseSummary? = null
    private var isSaving = false

    fun show() {
        render()
        if (target == WishlistStatus.OWNED) renderMovies()
        if (target == WishlistStatus.OWNED && item.releaseId == null && !item.blurayComUrl.isNullOrBlank()) {
            mainScope.launch {
                existingRelease = runCatching { findReleaseByBluRayUrl(item.blurayComUrl) }.getOrNull()
                renderReleaseNotice()
            }
        }
    }

    fun close() {
        document.getElementById(STATUS_DIALOG_ID)?.remove()
    }

    private val needsPurchase: Boolean
        get() = item.purchase == null && target != WishlistStatus.WISHLIST

    private fun render() {
        close()
        container.append {
            div {
                id = STATUS_DIALOG_ID
                style = modalOverlayStyle(1450)
                onClickFunction = { event ->
                    if (event.target == document.getElementById(STATUS_DIALOG_ID)) close()
                }

                div {
                    style = modalPanelStyle(620)

                    h2 {
                        style = "margin: 0 0 4px 0; color: #202124; font-size: 20px; display: flex; align-items: center; gap: 10px;"
                        span { classes = setOf("mdi", statusIcon(target)); style = "color: ${statusColors(target).second}; font-size: 24px;" }
                        +advanceLabel(target)
                    }
                    p {
                        style = "margin: 0 0 20px 0; color: #5f6368; font-size: 14px;"
                        +(item.title ?: "Untitled release")
                    }

                    when (target) {
                        WishlistStatus.WISHLIST -> p {
                            style = "font-size: 14px; color: #202124;"
                            +"Move this item back to the wishlist? Anything you recorded about the purchase is kept."
                        }
                        WishlistStatus.ORDERED -> purchaseSection()
                        WishlistStatus.SHIPPED -> {
                            if (needsPurchase) {
                                purchaseSection()
                                divider()
                            }
                            shippingSection()
                        }
                        WishlistStatus.OWNED -> {
                            if (needsPurchase) {
                                purchaseSection()
                                divider()
                            }
                            receiptSection()
                        }
                    }

                    div {
                        style = "display: flex; justify-content: flex-end; gap: 12px; margin-top: 24px;"
                        button {
                            style = secondaryButtonStyle()
                            +"Cancel"
                            onClickFunction = { close() }
                        }
                        button {
                            id = "$STATUS_DIALOG_ID-save"
                            style = primaryButtonStyle(statusColors(target).second)
                            span { classes = setOf("mdi", statusIcon(target)); style = "font-size: 18px;" }
                            +advanceLabel(target)
                            onClickFunction = { save() }
                        }
                    }
                }
            }
        }
    }

    private fun FlowContent.divider() {
        hr { style = "border: none; border-top: 1px solid #e8eaed; margin: 20px 0;" }
    }

    private fun FlowContent.purchaseSection() {
        val suggested = item.purchase ?: item.currentPrice?.let { price ->
            Purchase(
                subtotal = price,
                vendor = when (item.currentPriceSource) {
                    PriceSource.AMAZON, PriceSource.NEW_FROM -> "Amazon"
                    else -> null
                }
            )
        }
        val fields = PurchaseFormFields("$STATUS_DIALOG_ID-purchase", suggested, defaultTaxRate)
        purchaseFields = fields
        with(fields) { render() }
        if (item.purchase == null && item.currentPrice != null) {
            p {
                style = "margin: 8px 0 0 0; font-size: 12px; color: #80868b;"
                +"Pre-filled from the latest tracked price (${formatMoney(item.currentPrice)}). Adjust to match your receipt."
            }
        }
    }

    private fun FlowContent.shippingSection() {
        h3 {
            style = "margin: 0 0 12px 0; font-size: 15px; color: #202124; display: flex; align-items: center; gap: 8px;"
            span { classes = setOf("mdi", "mdi-truck-outline"); style = "color: #1a73e8; font-size: 18px;" }
            +"Shipping"
        }
        div {
            style = "display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px;"
            div {
                formLabel("Shipped on")
                input(type = InputType.date) {
                    id = "$STATUS_DIALOG_ID-shipped-date"
                    value = item.purchase?.shippedDate ?: todayIso()
                    style = formInputStyle()
                }
            }
            div {
                formLabel("Tracking URL")
                input(type = InputType.url) {
                    id = "$STATUS_DIALOG_ID-tracking"
                    value = item.purchase?.trackingUrl ?: ""
                    placeholder = "https://"
                    style = formInputStyle()
                }
            }
        }
    }

    private fun FlowContent.receiptSection() {
        h3 {
            style = "margin: 0 0 12px 0; font-size: 15px; color: #202124; display: flex; align-items: center; gap: 8px;"
            span { classes = setOf("mdi", "mdi-package-variant"); style = "color: #188038; font-size: 18px;" }
            +"Add to your collection"
        }

        div { id = "$STATUS_DIALOG_ID-release-notice" }

        div {
            style = "display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px;"
            div {
                formLabel("Received on")
                input(type = InputType.date) {
                    id = "$STATUS_DIALOG_ID-received-date"
                    value = item.purchase?.receivedDate ?: todayIso()
                    style = formInputStyle()
                }
            }
            div {
                formLabel("Location")
                select {
                    id = "$STATUS_DIALOG_ID-location"
                    style = formInputStyle()
                    listOf("Shelf", "Archive").forEach { loc ->
                        option { value = loc; +loc }
                    }
                }
            }
        }

        div {
            style = "margin-top: 16px;"
            formLabel("Films on this release")
            div { id = "$STATUS_DIALOG_ID-movies" }
            button {
                style = outlineButtonStyle("#1a73e8") + " margin-top: 8px;"
                span { classes = setOf("mdi", "mdi-plus"); style = "font-size: 16px;" }
                +"Add film"
                onClickFunction = {
                    MoviePicker(container, excludedMovieIds = linkedMovies.map { it.movieId }.toSet()) { movie ->
                        movie.id?.let { linkedMovies.add(WishlistMovie(it, movie.title, movie.release_date)) }
                        renderMovies()
                    }.show()
                }
            }
            p {
                style = "margin: 8px 0 0 0; font-size: 12px; color: #80868b;"
                +"Each film here gets this release as a physical media entry. You can add more later from the release page."
            }
        }
    }

    private fun renderReleaseNotice() {
        val notice = document.getElementById("$STATUS_DIALOG_ID-release-notice") ?: return
        notice.innerHTML = ""
        val existing = existingRelease ?: return
        notice.append {
            div {
                style = """
                    background-color: #e8f0fe;
                    border: 1px solid #c6dafc;
                    border-radius: 6px;
                    padding: 10px 14px;
                    margin-bottom: 12px;
                    font-size: 13px;
                    color: #174ea6;
                    display: flex;
                    gap: 8px;
                    align-items: flex-start;
                """.trimIndent()
                span { classes = setOf("mdi", "mdi-link-variant"); style = "font-size: 18px;" }
                span {
                    +"This blu-ray.com release is already in your collection as "
                    b { +(existing.title ?: "an untitled release") }
                    +" with ${existing.filmCount} film(s). The films below will be added to it rather than to a new copy."
                }
            }
        }
    }

    private fun renderMovies() {
        val list = document.getElementById("$STATUS_DIALOG_ID-movies") ?: return
        list.innerHTML = ""
        list.append {
            if (linkedMovies.isEmpty()) {
                div {
                    style = "padding: 12px; color: #80868b; font-size: 13px; background-color: #f8f9fa; border-radius: 6px; border: 1px dashed #dadce0;"
                    +"No films chosen yet. The release will be created without films; you can add them later."
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
                            attributes["title"] = "Remove"
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

        val purchase = purchaseFields?.let { fields ->
            if (fields.hasSubtotal()) {
                fields.read() ?: run {
                    alertDialog.show(title = "Check the subtotal", message = "Enter what you paid before tax and shipping, or leave it blank to skip.")
                    return
                }
            } else null
        }

        if (target == WishlistStatus.ORDERED && purchase == null) {
            alertDialog.show(title = "Subtotal required", message = "Enter what you paid so the purchase can be recorded.")
            return
        }

        val request = WishlistTransitionRequest(
            status = target,
            purchase = purchase,
            shippedDate = inputValue("$STATUS_DIALOG_ID-shipped-date"),
            trackingUrl = inputValue("$STATUS_DIALOG_ID-tracking"),
            receivedDate = inputValue("$STATUS_DIALOG_ID-received-date"),
            location = (document.getElementById("$STATUS_DIALOG_ID-location") as? HTMLSelectElement)?.value,
            movieIds = if (target == WishlistStatus.OWNED) linkedMovies.map { it.movieId } else null,
            releaseId = if (target == WishlistStatus.OWNED) existingRelease?.id else null
        )

        isSaving = true
        (document.getElementById("$STATUS_DIALOG_ID-save") as? org.w3c.dom.HTMLButtonElement)?.disabled = true
        mainScope.launch {
            try {
                val updated = transitionWishlistItem(item.id!!, request)
                close()
                onDone(updated)
            } catch (e: Exception) {
                isSaving = false
                (document.getElementById("$STATUS_DIALOG_ID-save") as? org.w3c.dom.HTMLButtonElement)?.disabled = false
                alertDialog.show(title = "Error", message = e.message ?: "Failed to update the item.")
            }
        }
    }

    private fun inputValue(id: String): String? =
        (document.getElementById(id) as? HTMLInputElement)?.value?.trim()?.takeIf { it.isNotEmpty() }
}
