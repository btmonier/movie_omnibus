package org.btmonier

import kotlinx.browser.document
import kotlinx.coroutines.launch
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.onClickFunction
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLInputElement

private const val DETAIL_ID = "wishlist-item-detail"

/**
 * Everything about one wishlist item: cover, formats, the price picture
 * (current, list, lowest, target), external links, tags, films, the purchase
 * once there is one, and the full price history with a sparkline. Prices can
 * be refreshed from blu-ray.com or logged by hand from here.
 */
class WishlistItemDetailModal(
    private val container: Element,
    private var item: WishlistItem,
    private val onChanged: (WishlistItem) -> Unit,
    private val onEdit: (WishlistItem) -> Unit,
    private val onAdvance: (WishlistItem, WishlistStatus) -> Unit,
    private val onDelete: (WishlistItem) -> Unit,
    private val onOpenRelease: (Int) -> Unit
) {
    private val alertDialog = AlertDialog(container)
    private var isRefreshing = false
    private var showLogForm = false

    fun show() = render()

    fun close() {
        document.getElementById(DETAIL_ID)?.remove()
    }

    fun update(updated: WishlistItem) {
        item = updated
        if (document.getElementById(DETAIL_ID) != null) render()
    }

    private fun render() {
        val scrollTop = document.getElementById("$DETAIL_ID-panel")?.scrollTop ?: 0.0
        close()
        container.append {
            div {
                id = DETAIL_ID
                style = modalOverlayStyle(1300)
                onClickFunction = { event ->
                    if (event.target == document.getElementById(DETAIL_ID)) close()
                }

                div {
                    id = "$DETAIL_ID-panel"
                    style = modalPanelStyle(760) + " padding: 0;"

                    header()

                    div {
                        style = "padding: 20px 24px 24px 24px;"
                        priceBlock()
                        linksRow()
                        if (item.tags.isNotEmpty() || item.linkedMovies.isNotEmpty() || !item.notes.isNullOrBlank()) metaBlock()
                        item.purchase?.let { purchaseBlock(it) }
                        historyBlock()
                        actionsRow()
                    }
                }
            }
        }
        document.getElementById("$DETAIL_ID-panel")?.scrollTop = scrollTop
    }

    private fun FlowContent.header() {
        div {
            style = "display: flex; gap: 20px; padding: 24px 24px 0 24px;"

            val cover = item.displayImages().firstOrNull()?.imageUrl
            div {
                style = "width: 120px; height: 160px; flex-shrink: 0; background-color: #f1f3f4; border-radius: 6px; display: flex; align-items: center; justify-content: center; overflow: hidden;"
                if (cover != null) {
                    img {
                        src = cover
                        alt = item.title ?: "Cover"
                        style = "max-width: 100%; max-height: 100%; object-fit: contain; cursor: zoom-in;"
                        attributes["onerror"] = "this.style.display='none'"
                        onClickFunction = { ImageLightbox.show(item.displayImages()) }
                    }
                } else {
                    span { classes = setOf("mdi", "mdi-disc"); style = "font-size: 48px; color: #bdc1c6;" }
                }
            }

            div {
                style = "flex: 1; min-width: 0;"
                div {
                    style = "display: flex; justify-content: space-between; align-items: flex-start; gap: 12px;"
                    h2 {
                        style = "margin: 0; font-size: 22px; color: #202124; line-height: 1.3;"
                        +(item.title ?: "Untitled release")
                    }
                    button {
                        style = "background: none; border: none; cursor: pointer; color: #5f6368; padding: 4px; flex-shrink: 0;"
                        attributes["title"] = "Close"
                        span { classes = setOf("mdi", "mdi-close"); style = "font-size: 22px;" }
                        onClickFunction = { close() }
                    }
                }
                div {
                    style = "display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px;"
                    statusChip(item.status)
                    item.mediaTypes.forEach { mediaTypeChip(it) }
                    if (item.isCollection) span { style = chipStyle("#fef7e0", "#b06000"); +"Box set" }
                    priorityChip(item.priority)
                    if (item.atTarget) span { style = chipStyle("#e6f4ea", "#188038"); +"At target price" }
                    else if (item.priceDropped) span { style = chipStyle("#e6f4ea", "#188038"); +"Price drop" }
                    if (item.inStock == false) span { style = chipStyle("#fce8e6", "#c5221f"); +"Out of stock" }
                }
                div {
                    style = "font-size: 13px; color: #5f6368; margin-top: 10px; line-height: 1.6;"
                    val parts = listOfNotNull(
                        item.distributor,
                        item.releaseDate?.let { "Released ${formatDate(it)}" },
                        "Added ${formatDate(item.createdAt)}"
                    )
                    +parts.joinToString(" · ")
                }
            }
        }
    }

    private fun FlowContent.priceBlock() {
        div {
            style = "display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 12px; margin-bottom: 16px;"
            priceStat("Current", item.currentPrice, item.currentPriceSource?.let { priceSourceLabel(it) }, highlight = true)
            priceStat("List price", item.listPrice, item.percentOffList?.let { "$it% off now" })
            priceStat("Lowest seen", item.lowestPrice, null)
            priceStat("Target", item.targetPrice, if (item.targetPrice == null) "Not set" else if (item.atTarget) "Reached" else null)
        }
        item.lastPriceCheckAt?.let {
            div {
                style = "font-size: 12px; color: #80868b; margin: -8px 0 16px 0;"
                +"Last checked ${formatDate(it)}"
            }
        }
    }

    private fun FlowContent.priceStat(label: String, value: Double?, sub: String?, highlight: Boolean = false) {
        div {
            style = "padding: 12px 14px; border-radius: 8px; background-color: ${if (highlight) "#fce8f3" else "#f8f9fa"};"
            div { style = "font-size: 12px; color: #5f6368; margin-bottom: 4px;"; +label }
            div {
                style = "font-size: 20px; font-weight: 600; color: ${if (highlight) "#b3157a" else "#202124"};"
                +formatMoney(value)
            }
            sub?.let { div { style = "font-size: 11px; color: #80868b; margin-top: 2px;"; +it } }
        }
    }

    private fun FlowContent.linksRow() {
        val links = buildList {
            item.blurayComUrl?.let { add(Triple("blu-ray.com", it, "mdi-open-in-new")) }
            item.buyUrl?.let { add(Triple("Buy now", it, "mdi-cart-outline")) }
            item.asin?.takeIf { it.isNotBlank() }?.let {
                add(Triple("Amazon", amazonUrl(it), "mdi-shopping-outline"))
                add(Triple("CamelCamelCamel", camelCamelCamelUrl(it), "mdi-chart-line"))
            }
        }
        if (links.isEmpty()) return
        div {
            style = "display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 16px;"
            links.forEach { (label, href, icon) ->
                a(href = href, target = "_blank") {
                    style = outlineButtonStyle("#1a73e8") + " text-decoration: none;"
                    attributes["rel"] = "noopener"
                    span { classes = setOf("mdi", icon); style = "font-size: 16px;" }
                    +label
                }
            }
        }
    }

    private fun FlowContent.metaBlock() {
        div {
            style = "margin-bottom: 16px; display: flex; flex-direction: column; gap: 10px; font-size: 14px;"
            if (item.tags.isNotEmpty()) {
                div {
                    style = "display: flex; flex-wrap: wrap; gap: 6px; align-items: center;"
                    span { style = "color: #5f6368; font-size: 13px; margin-right: 4px;"; +"Tags:" }
                    item.tags.forEach { tagChip(it) }
                }
            }
            if (item.linkedMovies.isNotEmpty()) {
                div {
                    span { style = "color: #5f6368; font-size: 13px;"; +"Films: " }
                    +item.linkedMovies.joinToString(", ") { m -> m.title + (m.releaseYear?.let { " ($it)" } ?: "") }
                }
            }
            item.notes?.takeIf { it.isNotBlank() }?.let {
                div {
                    style = "color: #3c4043; white-space: pre-wrap; background-color: #f8f9fa; padding: 10px 12px; border-radius: 6px;"
                    +it
                }
            }
        }
    }

    private fun FlowContent.purchaseBlock(purchase: Purchase) {
        div {
            style = "border: 1px solid #e8eaed; border-radius: 8px; padding: 14px 16px; margin-bottom: 16px;"
            div {
                style = "display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px;"
                h3 {
                    style = "margin: 0; font-size: 15px; color: #202124; display: flex; align-items: center; gap: 8px;"
                    span { classes = setOf("mdi", "mdi-receipt-text-outline"); style = "color: #1a73e8; font-size: 18px;" }
                    +"Purchase"
                }
                span { style = "font-size: 18px; font-weight: 600; color: #202124;"; +formatMoney(purchase.total) }
            }
            div {
                style = "display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 8px 16px; font-size: 13px; color: #3c4043;"
                purchase.vendor?.let { detail("Vendor", it) }
                purchase.orderDate?.let { detail("Ordered", formatDate(it)) }
                detail("Subtotal", formatMoney(purchase.subtotal))
                detail("Tax (${formatPercent(purchase.taxRate ?: DEFAULT_TAX_RATE)})", formatMoney(purchase.taxAmount ?: 0.0))
                if (purchase.shipping > 0) detail("Shipping", formatMoney(purchase.shipping))
                purchase.orderNumber?.let { detail("Order #", it) }
                purchase.shippedDate?.let { detail("Shipped", formatDate(it)) }
                purchase.receivedDate?.let { detail("Received", formatDate(it)) }
            }
            purchase.trackingUrl?.let {
                a(href = it, target = "_blank") {
                    style = "display: inline-flex; align-items: center; gap: 4px; margin-top: 10px; font-size: 13px; color: #1a73e8; text-decoration: none;"
                    span { classes = setOf("mdi", "mdi-truck-outline"); style = "font-size: 16px;" }
                    +"Track shipment"
                }
            }
        }
    }

    private fun FlowContent.detail(label: String, value: String) {
        div {
            span { style = "color: #80868b;"; +"$label: " }
            +value
        }
    }

    private fun FlowContent.historyBlock() {
        div {
            style = "border: 1px solid #e8eaed; border-radius: 8px; padding: 14px 16px; margin-bottom: 16px;"
            div {
                style = "display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap;"
                h3 {
                    style = "margin: 0; font-size: 15px; color: #202124; display: flex; align-items: center; gap: 8px;"
                    span { classes = setOf("mdi", "mdi-chart-timeline-variant"); style = "color: #1a73e8; font-size: 18px;" }
                    +"Price history"
                }
                div {
                    style = "display: flex; gap: 8px;"
                    if (!item.blurayComUrl.isNullOrBlank()) {
                        button {
                            id = "$DETAIL_ID-refresh"
                            style = outlineButtonStyle("#1a73e8")
                            disabled = isRefreshing
                            span { classes = setOf("mdi", if (isRefreshing) "mdi-loading mdi-spin" else "mdi-refresh"); style = "font-size: 16px;" }
                            +if (isRefreshing) "Checking..." else "Check blu-ray.com"
                            onClickFunction = { refreshPrice() }
                        }
                    }
                    button {
                        style = outlineButtonStyle("#7627bb")
                        span { classes = setOf("mdi", "mdi-tag-plus-outline"); style = "font-size: 16px;" }
                        +"Log a price"
                        onClickFunction = {
                            showLogForm = !showLogForm
                            render()
                        }
                    }
                }
            }

            if (showLogForm) logPriceForm()

            val selling = item.priceHistory.filter { it.source != PriceSource.BLURAY_LIST && it.source != PriceSource.USED_FROM }
            if (selling.size >= 2) {
                div {
                    style = "margin: 14px 0 4px 0;"
                    unsafe { raw(sparklineSvg(selling.map { it.price })) }
                }
            }

            if (item.priceHistory.isEmpty()) {
                div {
                    style = "padding: 16px 0 4px 0; color: #80868b; font-size: 13px;"
                    +if (item.blurayComUrl.isNullOrBlank()) "No prices yet. Log one when you see it somewhere."
                    else "No prices recorded yet. Check blu-ray.com or log one by hand."
                }
            } else {
                table {
                    style = "width: 100%; border-collapse: collapse; font-size: 13px; margin-top: 10px;"
                    thead {
                        tr {
                            style = "color: #5f6368; text-align: left;"
                            th { style = "padding: 6px 4px; font-weight: 500;"; +"When" }
                            th { style = "padding: 6px 4px; font-weight: 500;"; +"Source" }
                            th { style = "padding: 6px 4px; font-weight: 500; text-align: right;"; +"Price" }
                            th { style = "padding: 6px 4px;"; +"" }
                        }
                    }
                    tbody {
                        item.priceHistory.sortedByDescending { it.observedAt ?: "" }.forEach { obs ->
                            tr {
                                style = "border-top: 1px solid #f1f3f4;"
                                td { style = "padding: 6px 4px; color: #3c4043;"; +formatDate(obs.observedAt) }
                                td {
                                    style = "padding: 6px 4px; color: #3c4043;"
                                    +priceSourceLabel(obs.source)
                                    obs.vendor?.let { +" · $it" }
                                    obs.note?.let { span { style = "color: #80868b;"; +" — $it" } }
                                    if (obs.inStock == false) span { style = "color: #c5221f;"; +" (out of stock)" }
                                }
                                td {
                                    style = "padding: 6px 4px; text-align: right; font-weight: 500; color: ${if (obs.source == PriceSource.BLURAY_LIST) "#80868b" else "#202124"};"
                                    +formatMoney(obs.price)
                                }
                                td {
                                    style = "padding: 6px 4px; text-align: right; width: 28px;"
                                    button {
                                        style = "background: none; border: none; cursor: pointer; color: #9aa0a6; padding: 2px;"
                                        attributes["title"] = "Delete this observation"
                                        span { classes = setOf("mdi", "mdi-delete-outline"); style = "font-size: 16px;" }
                                        onClickFunction = { deleteObservation(obs) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun FlowContent.logPriceForm() {
        div {
            style = "margin-top: 14px; padding: 12px; background-color: #f8f9fa; border-radius: 6px; display: grid; grid-template-columns: 1fr 1fr 2fr auto; gap: 8px; align-items: end;"
            div {
                formLabel("Price")
                input(type = InputType.number) {
                    id = "$DETAIL_ID-log-price"
                    attributes["step"] = "0.01"
                    attributes["min"] = "0"
                    placeholder = "0.00"
                    style = formInputStyle()
                    attributes["autofocus"] = "true"
                }
            }
            div {
                formLabel("Where")
                input(type = InputType.text) {
                    id = "$DETAIL_ID-log-vendor"
                    placeholder = "Store or site"
                    style = formInputStyle()
                }
            }
            div {
                formLabel("Note")
                input(type = InputType.text) {
                    id = "$DETAIL_ID-log-note"
                    placeholder = "Sale ends Friday, used copy..."
                    style = formInputStyle()
                }
            }
            button {
                style = primaryButtonStyle("#7627bb") + " height: 40px;"
                +"Save"
                onClickFunction = { logPrice() }
            }
        }
    }

    private fun FlowContent.actionsRow() {
        div {
            style = "display: flex; flex-wrap: wrap; gap: 8px; justify-content: space-between; align-items: center; padding-top: 4px;"
            div {
                style = "display: flex; flex-wrap: wrap; gap: 8px;"
                item.status.next()?.let { next ->
                    button {
                        style = primaryButtonStyle(statusColors(next).second)
                        span { classes = setOf("mdi", statusIcon(next)); style = "font-size: 18px;" }
                        +advanceLabel(next)
                        onClickFunction = { onAdvance(item, next) }
                    }
                }
                if (item.status != WishlistStatus.OWNED && item.status != WishlistStatus.SHIPPED) {
                    button {
                        style = outlineButtonStyle("#188038")
                        span { classes = setOf("mdi", "mdi-check-circle-outline"); style = "font-size: 16px;" }
                        +"Already own it"
                        onClickFunction = { onAdvance(item, WishlistStatus.OWNED) }
                    }
                }
                if (item.status != WishlistStatus.WISHLIST) {
                    button {
                        style = outlineButtonStyle()
                        span { classes = setOf("mdi", "mdi-undo"); style = "font-size: 16px;" }
                        +"Back to wishlist"
                        onClickFunction = { onAdvance(item, WishlistStatus.WISHLIST) }
                    }
                }
                item.releaseId?.let { releaseId ->
                    button {
                        style = outlineButtonStyle("#1a73e8")
                        span { classes = setOf("mdi", "mdi-package-variant-closed"); style = "font-size: 16px;" }
                        +"Open release"
                        onClickFunction = { close(); onOpenRelease(releaseId) }
                    }
                }
            }
            div {
                style = "display: flex; gap: 8px;"
                button {
                    style = outlineButtonStyle()
                    span { classes = setOf("mdi", "mdi-pencil-outline"); style = "font-size: 16px;" }
                    +"Edit"
                    onClickFunction = { onEdit(item) }
                }
                button {
                    style = outlineButtonStyle("#d93025")
                    span { classes = setOf("mdi", "mdi-delete-outline"); style = "font-size: 16px;" }
                    +"Delete"
                    onClickFunction = { onDelete(item) }
                }
            }
        }
    }

    private fun refreshPrice() {
        if (isRefreshing) return
        isRefreshing = true
        render()
        mainScope.launch {
            try {
                val response = refreshWishlistItemPrice(item.id!!)
                response.item?.let { item = it; onChanged(it) }
                if (!response.success) {
                    alertDialog.show(title = "Price check failed", message = response.error ?: "blu-ray.com could not be reached.")
                }
            } catch (e: Exception) {
                alertDialog.show(title = "Error", message = e.message ?: "Failed to refresh price.")
            } finally {
                isRefreshing = false
                render()
            }
        }
    }

    private fun logPrice() {
        val price = (document.getElementById("$DETAIL_ID-log-price") as? HTMLInputElement)?.value?.trim()?.toDoubleOrNull()
        if (price == null || price < 0) {
            alertDialog.show(title = "Enter a price", message = "The price must be a number of dollars, like 24.99.")
            return
        }
        val vendor = (document.getElementById("$DETAIL_ID-log-vendor") as? HTMLInputElement)?.value?.trim()?.takeIf { it.isNotEmpty() }
        val note = (document.getElementById("$DETAIL_ID-log-note") as? HTMLInputElement)?.value?.trim()?.takeIf { it.isNotEmpty() }

        mainScope.launch {
            try {
                item = addWishlistPriceObservation(item.id!!, PriceObservation(PriceSource.MANUAL, price, vendor = vendor, note = note))
                showLogForm = false
                onChanged(item)
                render()
            } catch (e: Exception) {
                alertDialog.show(title = "Error", message = e.message ?: "Failed to log price.")
            }
        }
    }

    private fun deleteObservation(obs: PriceObservation) {
        val obsId = obs.id ?: return
        mainScope.launch {
            if (deleteWishlistPriceObservation(item.id!!, obsId)) {
                item = fetchWishlistItem(item.id!!)
                onChanged(item)
                render()
            } else {
                alertDialog.show(title = "Error", message = "Failed to delete that observation.")
            }
        }
    }
}

/**
 * A small inline SVG line chart of [values], oldest first. Low prices sit at
 * the bottom; the last point is marked.
 */
internal fun sparklineSvg(values: List<Double>, width: Int = 640, height: Int = 60): String {
    if (values.size < 2) return ""
    val min = values.min()
    val max = values.max()
    val range = (max - min).takeIf { it > 0.0 } ?: 1.0
    val padX = 6.0
    val padY = 6.0
    val stepX = (width - 2 * padX) / (values.size - 1)

    val points = values.mapIndexed { index, value ->
        val x = padX + index * stepX
        val y = padY + (height - 2 * padY) * (1 - (value - min) / range)
        x to y
    }
    val path = points.joinToString(" ") { (x, y) -> "${x.asFixed(1)},${y.asFixed(1)}" }
    val (lastX, lastY) = points.last()
    val color = if (values.last() <= values.first()) "#188038" else "#c5221f"

    return """
        <svg viewBox="0 0 $width $height" width="100%" height="$height" preserveAspectRatio="none" style="display:block;">
          <polyline fill="none" stroke="$color" stroke-width="2" points="$path" vector-effect="non-scaling-stroke"/>
          <circle cx="${lastX.asFixed(1)}" cy="${lastY.asFixed(1)}" r="3.5" fill="$color"/>
        </svg>
    """.trimIndent()
}

private fun Double.asFixed(digits: Int): String = this.asDynamic().toFixed(digits) as String
