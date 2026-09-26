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
 * (current, list, lowest, target), the stores it is tracked at, external
 * links, tags, films, the purchase once there is one, and the full price
 * history with a sparkline. Prices can be refreshed from every source at
 * once, or logged by hand from here.
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

    // The store a logged price belongs to, and its page, when the form was
    // opened from one of the linked stores rather than the history header.
    private var logVendor: String? = null
    private var logUrl: String? = null

    // The logged price being corrected, when the form is in edit mode.
    private var editingObservation: PriceObservation? = null

    // "Find on other stores" state
    private var showStoreSearch = false
    private var isSearchingStores = false
    private var storeCandidates: List<VendorCandidate> = emptyList()
    private var storeSearchMessage: String? = null
    private var storeQuery: String? = null
    private var searchedOnce = false
    private var linkingUrl: String? = null

    // "Add by URL" state, for stores the search does not cover
    private var showAddLink = false
    private var isAddingLink = false
    private var addLinkMessage: String? = null

    fun show() {
        render()
        mainScope.launch {
            StoreOptions.ensureLoaded()
            if (document.getElementById(DETAIL_ID) != null && (showLogForm || showAddLink)) render()
        }
    }

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
                        storesBlock()
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
        // Derived here as well as on the server so the "Current" figure can be
        // credited to the store that is actually asking it.
        val cheapest = derivePrices(item.priceHistory).current

        div {
            style = "display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 12px; margin-bottom: 16px;"
            priceStat("Current", item.currentPrice, cheapest?.let { priceOriginLabel(it) }, highlight = true)
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

    /**
     * The stores this item is tracked at, each with what it is asking, and the
     * two ways to add another: the search across the stores with readers, and
     * a URL from anywhere else. A store that failed its last check says so here
     * rather than quietly serving a stale price.
     */
    private fun FlowContent.storesBlock() {
        div {
            style = "border: 1px solid #e8eaed; border-radius: 8px; padding: 14px 16px; margin-bottom: 16px;"
            div {
                style = "display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap;"
                h3 {
                    style = "margin: 0; font-size: 15px; color: #202124; display: flex; align-items: center; gap: 8px;"
                    span { classes = setOf("mdi", "mdi-storefront-outline"); style = "color: #1a73e8; font-size: 18px;" }
                    +"Stores"
                }
                div {
                    style = "display: flex; gap: 8px; flex-wrap: wrap;"
                    button {
                        style = outlineButtonStyle("#1a73e8")
                        span { classes = setOf("mdi", "mdi-magnify"); style = "font-size: 16px;" }
                        +if (showStoreSearch) "Hide search" else "Find on other stores"
                        onClickFunction = {
                            showStoreSearch = !showStoreSearch
                            render()
                            if (showStoreSearch && !searchedOnce) searchStores(item.title)
                        }
                    }
                    button {
                        style = outlineButtonStyle("#7627bb")
                        span { classes = setOf("mdi", "mdi-link-plus"); style = "font-size: 16px;" }
                        +if (showAddLink) "Hide" else "Add by URL"
                        onClickFunction = {
                            showAddLink = !showAddLink
                            addLinkMessage = null
                            render()
                        }
                    }
                }
            }

            if (item.vendorLinks.isEmpty()) {
                div {
                    style = "padding: 10px 0 2px 0; color: #80868b; font-size: 13px;"
                    +"Not tracked at any store yet. Search for it and pick the right edition, or paste the URL of a shop the search does not cover."
                }
            } else {
                table {
                    style = "width: 100%; border-collapse: collapse; font-size: 13px; margin-top: 10px;"
                    tbody {
                        item.vendorLinks.forEach { link -> vendorLinkRow(link) }
                    }
                }
            }

            if (showAddLink) addLinkPanel()
            if (showStoreSearch) storeSearchPanel()
        }
    }

    /**
     * Track a page the store search cannot reach - a label's own shop, an
     * exclusive sold nowhere else. The price is read from the page when it
     * publishes one in a form that can be read; the price field is for the
     * pages that do not, so the link is still worth something.
     */
    private fun FlowContent.addLinkPanel() {
        div {
            style = "margin-top: 14px; padding: 12px; background-color: #f8f9fa; border-radius: 6px;"
            div {
                style = "display: grid; grid-template-columns: 2fr 1fr 100px auto; gap: 8px; align-items: end;"
                div {
                    formLabel("Product page URL")
                    input(type = InputType.text) {
                        id = "$DETAIL_ID-link-url"
                        placeholder = "https://..."
                        style = formInputStyle()
                    }
                }
                div {
                    formLabel("Store name")
                    storeSelect(
                        idPrefix = "$DETAIL_ID-link-vendor",
                        stores = storeOptions(),
                        emptyLabel = "From the address"
                    )
                }
                div {
                    formLabel("Price")
                    input(type = InputType.number) {
                        id = "$DETAIL_ID-link-price"
                        attributes["step"] = "0.01"
                        attributes["min"] = "0"
                        placeholder = "0.00"
                        style = formInputStyle()
                    }
                }
                button {
                    style = primaryButtonStyle("#7627bb") + " height: 40px;"
                    disabled = isAddingLink
                    span {
                        classes = setOf("mdi", if (isAddingLink) "mdi-loading mdi-spin" else "mdi-plus")
                        style = "font-size: 16px;"
                    }
                    +if (isAddingLink) "Checking..." else "Track"
                    onClickFunction = { addLinkByUrl() }
                }
            }
            div {
                style = "margin-top: 8px; font-size: 12px; color: #80868b;"
                +(addLinkMessage
                    ?: "The page is read for a price if it publishes one. If it does not, the store is kept as a link and its prices are typed in by hand.")
            }
        }
    }

    private fun TBODY.vendorLinkRow(link: VendorLink) {
        val latest = item.priceHistory.latestFromVendor(link.vendor)
        tr {
            style = "border-top: 1px solid #f1f3f4;"
            td {
                style = "padding: 8px 4px;"
                a(href = link.url, target = "_blank") {
                    style = "color: #1a73e8; text-decoration: none; font-weight: 500;"
                    attributes["rel"] = "noopener"
                    +link.vendor
                }
                link.lastError?.let {
                    div {
                        style = "color: #c5221f; font-size: 12px; margin-top: 2px;"
                        span { classes = setOf("mdi", "mdi-alert-outline"); style = "font-size: 13px;" }
                        +" $it"
                    }
                }
            }
            td {
                style = "padding: 8px 4px; text-align: right; white-space: nowrap;"
                if (latest == null) {
                    span { style = "color: #80868b;"; +"No price yet" }
                } else {
                    span { style = "font-weight: 600; color: #202124;"; +formatMoney(latest.price) }
                    if (latest.inStock == false) {
                        span { style = "color: #c5221f; font-size: 12px;"; +" out of stock" }
                    }
                }
            }
            td {
                style = "padding: 8px 4px; text-align: right; color: #80868b; font-size: 12px; white-space: nowrap;"
                if (link.reader == VendorLinkReader.MANUAL) {
                    span { style = chipStyle("#f1f3f4", "#5f6368"); +"entered by hand" }
                } else {
                    +(link.lastCheckedAt?.let { "checked ${formatDate(it)}" } ?: "never checked")
                }
            }
            td {
                style = "padding: 8px 4px; text-align: right; white-space: nowrap; width: 56px;"
                if (link.reader == VendorLinkReader.MANUAL) {
                    button {
                        style = "background: none; border: none; cursor: pointer; color: #7627bb; padding: 2px;"
                        attributes["title"] = "Log a price at ${link.vendor}"
                        span { classes = setOf("mdi", "mdi-tag-plus-outline"); style = "font-size: 16px;" }
                        onClickFunction = {
                            logVendor = link.vendor
                            logUrl = link.url
                            editingObservation = null
                            showLogForm = true
                            render()
                        }
                    }
                }
                button {
                    style = "background: none; border: none; cursor: pointer; color: #9aa0a6; padding: 2px;"
                    attributes["title"] = "Stop tracking ${link.vendor}"
                    span { classes = setOf("mdi", "mdi-link-variant-off"); style = "font-size: 16px;" }
                    onClickFunction = { removeVendorLink(link) }
                }
            }
        }
    }

    /**
     * The candidate picker. Nothing is linked without being chosen here: the
     * stores carry several editions of the same film under nearly identical
     * titles, so the cover and price are what make the right one obvious.
     */
    private fun FlowContent.storeSearchPanel() {
        div {
            style = "margin-top: 14px; padding: 12px; background-color: #f8f9fa; border-radius: 6px;"
            div {
                style = "display: flex; gap: 8px; align-items: end;"
                div {
                    style = "flex: 1;"
                    formLabel("Search the stores for")
                    input(type = InputType.text) {
                        id = "$DETAIL_ID-store-query"
                        placeholder = "Release title"
                        // Kept in state so a re-render after searching does not
                        // throw away a query that was narrowed by hand.
                        value = storeQuery ?: item.title ?: ""
                        style = formInputStyle()
                    }
                }
                button {
                    style = primaryButtonStyle("#1a73e8") + " height: 40px;"
                    disabled = isSearchingStores
                    span {
                        classes = setOf("mdi", if (isSearchingStores) "mdi-loading mdi-spin" else "mdi-magnify")
                        style = "font-size: 16px;"
                    }
                    +if (isSearchingStores) "Searching..." else "Search"
                    onClickFunction = {
                        val query = (document.getElementById("$DETAIL_ID-store-query") as? HTMLInputElement)?.value
                        searchStores(query)
                    }
                }
            }

            storeSearchMessage?.let {
                div { style = "margin-top: 10px; font-size: 12px; color: #80868b;"; +it }
            }

            if (storeCandidates.isNotEmpty()) {
                div {
                    style = "margin-top: 12px; display: flex; flex-direction: column; gap: 8px; max-height: 320px; overflow-y: auto;"
                    storeCandidates.forEach { candidate -> candidateRow(candidate) }
                }
            }
        }
    }

    private fun FlowContent.candidateRow(candidate: VendorCandidate) {
        val alreadyLinked = item.vendorLinks.any { it.url == candidate.url }
        div {
            style = "display: flex; gap: 10px; align-items: center; background-color: #ffffff; " +
                "border: 1px solid #e8eaed; border-radius: 6px; padding: 8px 10px;"

            div {
                style = "width: 40px; height: 54px; flex-shrink: 0; background-color: #f1f3f4; border-radius: 4px; overflow: hidden; display: flex; align-items: center; justify-content: center;"
                candidate.imageUrl?.let {
                    img {
                        src = it
                        alt = candidate.title
                        style = "max-width: 100%; max-height: 100%; object-fit: contain;"
                        attributes["onerror"] = "this.style.display='none'"
                    }
                }
            }

            div {
                style = "flex: 1; min-width: 0;"
                div {
                    style = "font-size: 13px; color: #202124; font-weight: 500; line-height: 1.35;"
                    +candidate.title
                }
                div {
                    style = "font-size: 12px; color: #5f6368; margin-top: 3px; display: flex; flex-wrap: wrap; gap: 6px; align-items: center;"
                    span { +candidate.vendor }
                    candidate.price?.let { span { style = "font-weight: 600; color: #202124;"; +formatMoney(it) } }
                    candidate.listPrice?.let { span { style = "text-decoration: line-through;"; +formatMoney(it) } }
                    if (candidate.inStock == false) span { style = "color: #c5221f;"; +"out of stock" }
                }
            }

            if (alreadyLinked) {
                span { style = chipStyle("#e6f4ea", "#188038"); +"Tracked" }
            } else {
                button {
                    style = outlineButtonStyle("#188038")
                    disabled = linkingUrl != null
                    span {
                        classes = setOf("mdi", if (linkingUrl == candidate.url) "mdi-loading mdi-spin" else "mdi-plus")
                        style = "font-size: 16px;"
                    }
                    +if (linkingUrl == candidate.url) "Linking..." else "Track"
                    onClickFunction = { linkCandidate(candidate) }
                }
            }
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
                    // Nothing to check when the only sources are typed in.
                    if (!item.blurayComUrl.isNullOrBlank() || item.vendorLinks.any { it.reader.isAutomatic }) {
                        button {
                            id = "$DETAIL_ID-refresh"
                            style = outlineButtonStyle("#1a73e8")
                            disabled = isRefreshing
                            span { classes = setOf("mdi", if (isRefreshing) "mdi-loading mdi-spin" else "mdi-refresh"); style = "font-size: 16px;" }
                            +if (isRefreshing) "Checking..." else "Check prices"
                            onClickFunction = { refreshPrice() }
                        }
                    }
                    button {
                        style = outlineButtonStyle("#7627bb")
                        span { classes = setOf("mdi", "mdi-tag-plus-outline"); style = "font-size: 16px;" }
                        +"Log a price"
                        onClickFunction = {
                            if (showLogForm) closeLogForm() else {
                                showLogForm = true
                                editingObservation = null
                                logVendor = null
                                logUrl = null
                                render()
                            }
                        }
                    }
                }
            }

            if (showLogForm) logPriceForm()

            val selling = item.priceHistory.filter { it.source in SELLING_PRICE_SOURCES }
            if (selling.size >= 2) {
                div {
                    style = "margin: 14px 0 4px 0;"
                    unsafe { raw(sparklineSvg(selling.map { it.price })) }
                }
            }

            if (item.priceHistory.isEmpty()) {
                div {
                    style = "padding: 16px 0 4px 0; color: #80868b; font-size: 13px;"
                    +if (item.blurayComUrl.isNullOrBlank() && item.vendorLinks.isEmpty())
                        "No prices yet. Add a store by URL, or log a price when you see one somewhere."
                    else "No prices recorded yet. Check prices or log one by hand."
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
                                    +priceOriginLabel(obs)
                                    if (obs.source == PriceSource.MANUAL) obs.vendor?.let { +" · $it" }
                                    // Where it was seen, for the prices logged
                                    // by hand against a page of their own.
                                    obs.url?.takeIf { it.isNotBlank() }?.let { href ->
                                        a(href = href, target = "_blank") {
                                            style = "color: #1a73e8; text-decoration: none; margin-left: 4px;"
                                            attributes["rel"] = "noopener"
                                            attributes["title"] = href
                                            span { classes = setOf("mdi", "mdi-open-in-new"); style = "font-size: 14px;" }
                                        }
                                    }
                                    obs.note?.let { span { style = "color: #80868b;"; +" — $it" } }
                                    if (obs.inStock == false) span { style = "color: #c5221f;"; +" (out of stock)" }
                                }
                                td {
                                    style = "padding: 6px 4px; text-align: right; font-weight: 500; color: ${if (obs.source == PriceSource.BLURAY_LIST) "#80868b" else "#202124"};"
                                    +formatMoney(obs.price)
                                }
                                td {
                                    style = "padding: 6px 4px; text-align: right; white-space: nowrap; width: 56px;"
                                    // Only a price that was typed in can be
                                    // corrected; a scraped one is a record of
                                    // what a site said.
                                    if (obs.source == PriceSource.MANUAL) {
                                        button {
                                            style = "background: none; border: none; cursor: pointer; color: #9aa0a6; padding: 2px;"
                                            attributes["title"] = "Edit this price"
                                            span { classes = setOf("mdi", "mdi-pencil-outline"); style = "font-size: 16px;" }
                                            onClickFunction = { editObservation(obs) }
                                        }
                                    }
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

    /**
     * The form for a price typed in by hand, used both to log a new one and to
     * correct one already logged. The date a price was seen is not editable:
     * it is when the sighting happened, not a field of it.
     */
    private fun FlowContent.logPriceForm() {
        val editing = editingObservation

        div {
            style = "margin-top: 14px; padding: 12px; background-color: #f8f9fa; border-radius: 6px;"
            editing?.let {
                div {
                    style = "font-size: 12px; color: #5f6368; margin-bottom: 8px;"
                    +"Editing the price logged ${formatDate(it.observedAt)}"
                }
            }
            div {
                style = "display: grid; grid-template-columns: 1fr 1fr 1.5fr 1.5fr auto; gap: 8px; align-items: end;"
                div {
                    formLabel("Price")
                    input(type = InputType.number) {
                        id = "$DETAIL_ID-log-price"
                        attributes["step"] = "0.01"
                        attributes["min"] = "0"
                        placeholder = "0.00"
                        style = formInputStyle()
                        attributes["autofocus"] = "true"
                        editing?.let { value = it.price.toString() }
                    }
                }
                div {
                    formLabel("Where")
                    // Opened from a linked store, the price belongs to that
                    // store's own series, so the name is not up for editing.
                    storeSelect(
                        idPrefix = "$DETAIL_ID-log-vendor",
                        stores = storeOptions(logVendor ?: editing?.vendor),
                        selected = editing?.vendor ?: logVendor,
                        emptyLabel = "Store or site",
                        locked = editing == null && logVendor != null
                    )
                }
                div {
                    formLabel("Link")
                    input(type = InputType.text) {
                        id = "$DETAIL_ID-log-url"
                        placeholder = "https://..."
                        style = formInputStyle()
                        // Logging against a linked store starts from that
                        // store's page, which is where the price was seen.
                        (editing?.url ?: logUrl)?.let { value = it }
                    }
                }
                div {
                    formLabel("Note")
                    input(type = InputType.text) {
                        id = "$DETAIL_ID-log-note"
                        placeholder = "Sale ends Friday, used copy..."
                        style = formInputStyle()
                        editing?.note?.let { value = it }
                    }
                }
                div {
                    style = "display: flex; gap: 8px;"
                    button {
                        style = primaryButtonStyle("#7627bb") + " height: 40px;"
                        +if (editing == null) "Save" else "Save changes"
                        onClickFunction = { logPrice() }
                    }
                    if (editing != null) {
                        button {
                            style = outlineButtonStyle() + " height: 40px;"
                            +"Cancel"
                            onClickFunction = { closeLogForm() }
                        }
                    }
                }
            }
        }
    }

    /**
     * The stores to offer, with the item's own linked stores folded in so a
     * price can be logged against one before the store list has loaded, and
     * [extra] (the store the form was opened for) guaranteed to be there.
     */
    private fun storeOptions(extra: String? = null): List<String> =
        (StoreOptions.names + item.vendorLinks.map { it.vendor } + listOfNotNull(extra))
            .distinctBy { it.lowercase() }
            .sortedBy { it.lowercase() }

    /** Put the log form away, forgetting whatever it was opened for. */
    private fun closeLogForm() {
        showLogForm = false
        editingObservation = null
        logVendor = null
        logUrl = null
        render()
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
                    alertDialog.show(
                        title = "Price check failed",
                        message = response.error ?: "None of this item's price sources could be reached."
                    )
                }
            } catch (e: Exception) {
                alertDialog.show(title = "Error", message = e.message ?: "Failed to refresh price.")
            } finally {
                isRefreshing = false
                render()
            }
        }
    }

    private fun searchStores(query: String?) {
        if (isSearchingStores) return
        isSearchingStores = true
        searchedOnce = true
        storeQuery = query?.trim()?.takeIf { it.isNotEmpty() }
        render()
        mainScope.launch {
            try {
                val response = searchVendorCandidates(item.id!!, query)
                storeCandidates = response.candidates
                storeSearchMessage = when {
                    response.error != null -> response.error
                    response.candidates.isEmpty() && response.storesSearched.isEmpty() ->
                        "No stores are switched on for price tracking."
                    response.candidates.isEmpty() ->
                        "Nothing matching \"${response.query}\" at ${response.storesSearched.joinToString(", ")}."
                    else -> buildString {
                        append("${response.candidates.size} found at ${response.storesSearched.joinToString(", ")}")
                        if (response.errors.isNotEmpty()) append(". Could not reach ${response.errors.joinToString("; ")}")
                    }
                }
            } catch (e: Exception) {
                storeCandidates = emptyList()
                storeSearchMessage = e.message ?: "The store search failed."
            } finally {
                isSearchingStores = false
                render()
            }
        }
    }

    private fun linkCandidate(candidate: VendorCandidate) {
        if (linkingUrl != null) return
        linkingUrl = candidate.url
        render()
        mainScope.launch {
            try {
                item = addWishlistVendorLink(item.id!!, candidate.url)
                onChanged(item)
            } catch (e: Exception) {
                alertDialog.show(title = "Could not link that store", message = e.message ?: "Unknown error.")
            } finally {
                linkingUrl = null
                render()
            }
        }
    }

    /**
     * Track a URL the store search does not cover. What comes back says how it
     * ended up being tracked, which is the one thing worth reporting: a page
     * that could be read updates its price now, and one that could not is kept
     * as a link to type prices against.
     */
    private fun addLinkByUrl() {
        if (isAddingLink) return
        val url = (document.getElementById("$DETAIL_ID-link-url") as? HTMLInputElement)?.value?.trim().orEmpty()
        if (url.isEmpty()) {
            alertDialog.show(title = "Enter a URL", message = "Paste the address of the product page you want to track.")
            return
        }
        val vendor = readStoreSelection("$DETAIL_ID-link-vendor")
        val price = (document.getElementById("$DETAIL_ID-link-price") as? HTMLInputElement)?.value
            ?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()

        val knownLinks = item.vendorLinks.mapNotNull { it.id }.toSet()
        isAddingLink = true
        addLinkMessage = null
        render()
        mainScope.launch {
            try {
                item = addWishlistVendorLink(item.id!!, url, vendor, price)
                val added = item.vendorLinks.firstOrNull { it.id !in knownLinks }
                    ?: item.vendorLinks.firstOrNull { it.url == url }
                val latest = added?.let { item.priceHistory.latestFromVendor(it.vendor) }
                addLinkMessage = when {
                    added == null -> "Store added."
                    added.reader == VendorLinkReader.MANUAL ->
                        "Nothing readable on that page, so ${added.vendor} is kept as a link - log its price with the tag button."
                    latest != null -> "Tracking ${added.vendor} at ${formatMoney(latest.price)}."
                    else -> "Tracking ${added.vendor}, though the page is not quoting a price right now."
                }
                // The store may have named itself from the URL
                added?.vendor?.takeIf { name -> StoreOptions.names.none { it.equals(name, true) } }
                    ?.let { StoreOptions.reload() }
                onChanged(item)
            } catch (e: Exception) {
                addLinkMessage = null
                alertDialog.show(title = "Could not add that store", message = e.message ?: "Unknown error.")
            } finally {
                isAddingLink = false
                render()
            }
        }
    }

    private fun removeVendorLink(link: VendorLink) {
        val linkId = link.id ?: return
        mainScope.launch {
            try {
                item = deleteWishlistVendorLink(item.id!!, linkId)
                onChanged(item)
                render()
            } catch (e: Exception) {
                alertDialog.show(title = "Error", message = e.message ?: "Failed to remove that store.")
            }
        }
    }

    private fun logPrice() {
        val price = (document.getElementById("$DETAIL_ID-log-price") as? HTMLInputElement)?.value?.trim()?.toDoubleOrNull()
        if (price == null || price < 0) {
            alertDialog.show(title = "Enter a price", message = "The price must be a number of dollars, like 24.99.")
            return
        }
        val editing = editingObservation
        val chosenVendor = readStoreSelection("$DETAIL_ID-log-vendor")
        // The picker is locked to the store the form was opened for, so read
        // that back rather than trusting a disabled select
        val vendor = if (editing != null) chosenVendor else logVendor ?: chosenVendor
        val note = (document.getElementById("$DETAIL_ID-log-note") as? HTMLInputElement)?.value?.trim()?.takeIf { it.isNotEmpty() }
        val url = (document.getElementById("$DETAIL_ID-log-url") as? HTMLInputElement)?.value?.trim()?.takeIf { it.isNotEmpty() }
        if (url != null && !url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            alertDialog.show(title = "Check the link", message = "A link has to start with http:// or https://.")
            return
        }

        val observation = PriceObservation(PriceSource.MANUAL, price, vendor = vendor, note = note, url = url)
        mainScope.launch {
            try {
                item = when (val obsId = editing?.id) {
                    null -> addWishlistPriceObservation(item.id!!, observation)
                    else -> updateWishlistPriceObservation(item.id!!, obsId, observation)
                }
                // A store named for the first time here belongs in the picker
                if (vendor != null && StoreOptions.names.none { it.equals(vendor, true) }) {
                    StoreOptions.reload()
                }
                onChanged(item)
                closeLogForm()
            } catch (e: Exception) {
                alertDialog.show(
                    title = "Error",
                    message = e.message ?: if (editing == null) "Failed to log price." else "Failed to update the price."
                )
            }
        }
    }

    /** Open the log form on an already logged price, to correct it. */
    private fun editObservation(obs: PriceObservation) {
        if (obs.id == null) return
        editingObservation = obs
        logVendor = null
        logUrl = null
        showLogForm = true
        render()
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
