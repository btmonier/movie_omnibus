package org.btmonier

import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.onChangeFunction
import kotlinx.html.js.onClickFunction
import kotlinx.html.js.onInputFunction
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement

/**
 * Ways the wishlist can be sectioned on the page. Grouping is done in the
 * browser over the full filtered list, so switching is instant.
 */
private enum class GroupBy(val slug: String, val label: String) {
    STATUS("status", "Status"),
    ORDER("order", "Order / shipment"),
    FORMAT("format", "Format"),
    DISTRIBUTOR("distributor", "Distributor"),
    PRIORITY("priority", "Priority"),
    TAG("tag", "Tag"),
    PRICE("price", "Price bracket"),
    RELEASE_DATE("release_date", "Release date"),
    VENDOR("vendor", "Vendor (purchased)"),
    NONE("none", "No grouping")
}

private const val BUDGET_AMOUNT_KEY = "wishlist-budget-amount"
private const val BUDGET_COUNT_KEY = "wishlist-budget-count"
private const val BUDGET_GOAL_KEY = "wishlist-budget-goal"
private const val BUDGET_ANY_COUNT_KEY = "wishlist-budget-any-count"
private const val BUDGET_SKIP_PREORDERS_KEY = "wishlist-budget-skip-preorders"
private const val BUDGET_EXPANDED_KEY = "wishlist-budget-expanded"

/** How often to ask the server how far the refresh pass has got. */
private const val POLL_INTERVAL_MS = 1000L

private const val SEARCH_INPUT_ID = "wishlist-search-input"
private const val SELECTION_BAR_ID = "wishlist-selection-bar"

/**
 * The wishlist: physical releases you want, imported from blu-ray.com with
 * their prices tracked over time, moving through ordered and shipped until they
 * arrive and join the collection as real releases.
 */
class WishlistPage(
    private val container: Element,
    private val onBack: () -> Unit
) {
    private var items: List<WishlistItem> = emptyList()
    private var summary: WishlistSummary? = null
    private var defaultTaxRate: Double = DEFAULT_TAX_RATE

    private var searchText = ""
    private var selectedStatuses: MutableSet<WishlistStatus> = mutableSetOf(WishlistStatus.WISHLIST, WishlistStatus.ORDERED, WishlistStatus.SHIPPED)
    private var selectedMediaType = ""
    private var selectedDistributor = ""
    private var selectedTag = ""
    private var selectedPriority: WishlistPriority? = null
    private var atTargetOnly = false
    private var inStockOnly = false
    private var sortField = "date_added"
    private var sortDirection = "desc"
    private var groupBy = GroupBy.STATUS

    private var budgetAmount = localStorage.getItem(BUDGET_AMOUNT_KEY)?.toDoubleOrNull() ?: 100.0
    private var budgetCount = localStorage.getItem(BUDGET_COUNT_KEY)?.toIntOrNull() ?: 3
    private var budgetGoal = BudgetGoal.fromSlug(localStorage.getItem(BUDGET_GOAL_KEY))
        ?: if (localStorage.getItem(BUDGET_ANY_COUNT_KEY) == "true") BudgetGoal.FILL_BUDGET else BudgetGoal.COUNT
    private var budgetSkipPreorders = localStorage.getItem(BUDGET_SKIP_PREORDERS_KEY) == "true"
    private var budgetExpanded = localStorage.getItem(BUDGET_EXPANDED_KEY) != "false"
    private var budgetResult: BudgetPickResult? = null

    private val selectedIds = mutableSetOf<Int>()

    private var mediaTypeOptions: List<String> = emptyList()
    private var distributorOptions: List<String> = emptyList()
    private var tagOptions: List<String> = emptyList()
    private var isLoading = false
    private var isRefreshingAll = false
    private var refreshProgress: RefreshProgressResponse? = null
    private val refreshingIds = mutableSetOf<Int>()

    private val alertDialog = AlertDialog(container)
    private val confirmDialog = ConfirmDialog(container)
    private var openDetail: WishlistItemDetailModal? = null

    fun show() {
        render()
        mainScope.launch {
            // The tax rate is only needed once a status dialog opens, so it is
            // fetched alongside the wishlist rather than ahead of it
            coroutineScope {
                launch { defaultTaxRate = runCatching { fetchSettings().defaultTaxRate }.getOrDefault(DEFAULT_TAX_RATE) }
                // The store pickers in the status and price forms render synchronously
                launch { StoreOptions.ensureLoaded() }
                launch { loadAll() }
            }
        }
    }

    private suspend fun loadAll() {
        // The cards stay on screen while loading and the scroll position is put
        // back afterwards, so a reload does not throw you to the top of the page
        val scrollY = window.scrollY
        isLoading = true
        renderResults()
        try {
            coroutineScope {
                // Status chips are applied client-side so counts per status stay visible
                val listRequest = async {
                    fetchWishlist(
                        search = searchText.takeIf { it.isNotBlank() },
                        mediaType = selectedMediaType.takeIf { it.isNotBlank() },
                        distributor = selectedDistributor.takeIf { it.isNotBlank() },
                        tag = selectedTag.takeIf { it.isNotBlank() },
                        priority = selectedPriority,
                        atTarget = atTargetOnly,
                        inStock = inStockOnly,
                        sortField = sortField,
                        sortDirection = sortDirection
                    )
                }
                val summaryRequest = async { runCatching { fetchWishlistSummary() }.getOrNull() }

                val response = listRequest.await()
                items = response.items
                mediaTypeOptions = response.mediaTypes
                distributorOptions = response.distributors
                tagOptions = response.tags
                summaryRequest.await()?.let { summary = it }
            }
        } catch (e: Exception) {
            items = emptyList()
            alertDialog.show(title = "Error", message = "Failed to load the wishlist: ${e.message}")
        } finally {
            isLoading = false
            selectedIds.retainAll(items.mapNotNull { it.id }.toSet())
            syncBudgetPicks()
            renderSummary()
            renderBudgetPicker()
            renderFilterOptions()
            renderResults()
            renderSelectionBar()
            window.scrollTo(0.0, scrollY)
        }
    }

    private fun reload() {
        mainScope.launch { loadAll() }
    }

    /** Re-fetch the counts and totals, leaving the last good ones on a failure. */
    private suspend fun refreshSummary() {
        runCatching { fetchWishlistSummary() }.getOrNull()?.let {
            summary = it
            renderSummary()
        }
    }

    // --- Actions ---

    private fun openAddForm() {
        WishlistItemForm(container, existing = null, availableTags = tagOptions) { saved ->
            reload()
            openDetailFor(saved)
        }.show()
    }

    private fun openEditForm(item: WishlistItem) {
        WishlistItemForm(container, existing = item, availableTags = tagOptions) { saved ->
            reload()
            openDetail?.update(saved)
        }.show()
    }

    private fun openDetailFor(item: WishlistItem) {
        val modal = WishlistItemDetailModal(
            container,
            item,
            onChanged = { updated ->
                items = items.map { if (it.id == updated.id) updated else it }
                renderResults()
                mainScope.launch { refreshSummary() }
            },
            onEdit = { openEditForm(it) },
            onAdvance = { current, target -> openStatusDialog(current, target) },
            onDelete = { confirmDelete(it) },
            onOpenRelease = { releaseId -> ReleaseDetail(container, releaseId = releaseId, onBack = { show() }).show() },
            // Other items on the order changed too, so the whole list is reloaded
            onOrderEdited = { reload() }
        )
        openDetail = modal
        modal.show()
    }

    private fun openStatusDialog(item: WishlistItem, target: WishlistStatus) {
        WishlistStatusDialog(container, item, target, defaultTaxRate) { updated ->
            reload()
            openDetail?.update(updated)
            if (target == WishlistStatus.OWNED) {
                alertDialog.show(
                    title = "Added to your collection",
                    message = "\"${updated.title ?: "This release"}\" is now a release in your collection" +
                        (if (updated.linkedMovies.isNotEmpty()) " on ${updated.linkedMovies.size} film(s)." else ". Add films to it from the release page.")
                )
            }
        }.show()
    }

    private fun confirmDelete(item: WishlistItem) {
        confirmDialog.show(
            title = "Remove from wishlist?",
            message = "\"${item.title ?: "This item"}\" and its price history will be deleted." +
                (if (item.releaseId != null) " The release in your collection is not affected." else ""),
            confirmText = "Delete",
            onConfirm = {
                mainScope.launch {
                    if (deleteWishlistItem(item.id!!)) {
                        openDetail?.close()
                        openDetail = null
                        reload()
                    } else {
                        alertDialog.show(title = "Error", message = "Failed to delete the item.")
                    }
                }
            }
        )
    }

    /**
     * Check every price source of every wanted or ordered item. The pass runs
     * on the server and is watched here, so a refresh spanning dozens of pages
     * shows how far along it is instead of spinning for minutes.
     */
    private fun refreshAllPrices() {
        if (isRefreshingAll) return
        isRefreshingAll = true
        refreshProgress = null
        renderRefreshState()
        mainScope.launch {
            try {
                var progress = startWishlistPriceRefresh()
                refreshProgress = progress
                renderRefreshState()

                while (!progress.done) {
                    delay(POLL_INTERVAL_MS)
                    progress = fetchWishlistPriceRefreshProgress() ?: break
                    refreshProgress = progress
                    renderRefreshState()
                }

                alertDialog.show(title = "Prices refreshed", message = refreshSummaryMessage(progress))
            } catch (e: Exception) {
                alertDialog.show(title = "Error", message = e.message ?: "Failed to refresh prices.")
            } finally {
                isRefreshingAll = false
                refreshProgress = null
                renderRefreshState()
                loadAll()
            }
        }
    }

    /**
     * Redraw the two things a running refresh changes: the progress bar above
     * the results, and the button that started it. The button lives in the page
     * shell, which is not re-rendered mid-refresh (that would throw away the
     * filter fields), so it is patched in place.
     */
    private fun renderRefreshState() {
        renderResults()

        val button = document.getElementById("wishlist-refresh-all") as? HTMLButtonElement ?: return
        button.disabled = isRefreshingAll
        button.style.opacity = if (isRefreshingAll) "0.6" else "1"
        button.innerHTML = ""
        button.append {
            span {
                classes = setOf("mdi", if (isRefreshingAll) "mdi-loading mdi-spin" else "mdi-refresh")
                style = "font-size: 18px;"
            }
            span { +if (isRefreshingAll) "Checking prices..." else "Refresh all prices" }
        }
    }

    private fun refreshSummaryMessage(progress: RefreshProgressResponse): String = buildString {
        if (progress.total == 0) {
            append("Nothing was due for a price check.")
        } else {
            append("Checked ${progress.checkedItems} item(s) across ${progress.completed} page(s): ")
            append("${progress.priceChanges} price change(s) recorded.")
        }
        if (progress.skipped > 0) {
            append("\n\n${progress.skipped} item(s) were checked very recently and were left alone.")
        }
        if (progress.failed > 0) {
            append("\n\n${progress.failed} failed:\n" + progress.errors.joinToString("\n"))
        }
    }

    /** The determinate bar shown while a refresh pass is running. */
    private fun FlowContent.refreshProgressBar(progress: RefreshProgressResponse) {
        // Clamped: the total is counted when the pass starts, so a link added
        // in the moment between could otherwise push it past 100%
        val fraction = if (progress.total > 0) {
            (progress.completed.toDouble() / progress.total).coerceIn(0.0, 1.0)
        } else 0.0
        div {
            style = """
                margin-bottom: 16px; padding: 12px 14px; background-color: #e8f0fe;
                border-radius: 8px; font-size: 13px; color: #1a56c4;
            """.trimIndent()
            div {
                style = "display: flex; justify-content: space-between; gap: 12px; margin-bottom: 8px;"
                span {
                    +if (progress.total == 0) "Nothing is due for a price check."
                    else "Checked ${progress.completed} of ${progress.total} price source" +
                        (if (progress.total == 1) "" else "s") +
                        (progress.lastSource?.let { " - $it" } ?: "")
                }
                span {
                    style = "font-weight: 600; white-space: nowrap;"
                    +"${(fraction * 100).toInt()}%"
                }
            }
            div {
                style = "height: 6px; background-color: #c6dafc; border-radius: 3px; overflow: hidden;"
                div {
                    style = "height: 100%; width: ${(fraction * 100).toInt()}%; " +
                        "background-color: #1a73e8; border-radius: 3px; transition: width 0.3s ease;"
                }
            }
            if (progress.priceChanges > 0 || progress.failed > 0) {
                div {
                    style = "margin-top: 8px; font-size: 12px;"
                    +buildString {
                        if (progress.priceChanges > 0) append("${progress.priceChanges} price change(s) so far")
                        if (progress.priceChanges > 0 && progress.failed > 0) append(", ")
                        if (progress.failed > 0) append("${progress.failed} failed")
                    }
                }
            }
        }
    }

    /**
     * Check one card's price. The response carries the updated item, so only
     * that card is replaced - reloading the whole list would collapse the page
     * and throw away the scroll position.
     */
    private fun quickRefresh(item: WishlistItem) {
        val id = item.id ?: return
        if (!refreshingIds.add(id)) return
        renderResults()
        mainScope.launch {
            try {
                val response = refreshWishlistItemPrice(id)
                response.item?.let { updated -> items = items.map { if (it.id == id) updated else it } }
                if (!response.success) {
                        alertDialog.show(
                            title = "Price check failed",
                            message = response.error ?: "None of this item's price sources could be reached."
                        )
                }
            } catch (e: Exception) {
                alertDialog.show(title = "Error", message = e.message ?: "Failed to refresh.")
            } finally {
                refreshingIds.remove(id)
                renderResults()
                refreshSummary()
            }
        }
    }

    // --- Rendering ---

    private fun render() {
        container.innerHTML = ""
        container.append {
            nav {
                style = """
                    position: sticky;
                    top: 0;
                    z-index: 1000;
                    background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
                    box-shadow: 0 2px 12px rgba(0,0,0,0.3);
                    padding: 0 24px;
                """.trimIndent()

                div {
                    style = "max-width: 1400px; margin: 0 auto; display: flex; justify-content: space-between; align-items: center; height: 64px;"

                    div {
                        style = "display: flex; align-items: center; gap: 12px; cursor: pointer;"
                        onClickFunction = { onBack() }
                        span { classes = setOf("mdi", "mdi-movie-open"); style = "font-size: 28px; color: #ffffff;" }
                        h1 {
                            style = "font-family: 'Oswald', sans-serif; font-weight: 500; font-size: 24px; color: #ffffff; margin: 0; letter-spacing: 1px;"
                            +"The Movie Omnibus"
                        }
                    }

                    div {
                        style = "display: flex; align-items: center; gap: 8px;"
                        a {
                            style = """
                                display: flex; align-items: center; gap: 6px; padding: 10px 16px; font-size: 14px; font-weight: 500;
                                cursor: pointer; background: linear-gradient(135deg, #e91e63 0%, #9c27b0 100%); color: white; border: none;
                                border-radius: 6px; text-decoration: none; box-shadow: 0 2px 8px rgba(233, 30, 99, 0.4);
                            """.trimIndent()
                            span { classes = setOf("mdi", "mdi-heart"); style = "font-size: 18px;" }
                            span { +"Wishlist" }
                        }
                    }
                }
            }

            div {
                style = "max-width: 1400px; margin: 0 auto; padding: 32px 20px; font-family: 'Google Sans', 'Roboto', arial, sans-serif;"

                div {
                    style = "display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; flex-wrap: wrap; margin-bottom: 8px;"
                    div {
                        h1 {
                            style = "font-family: 'Oswald', sans-serif; font-weight: 500; color: #202124; font-size: 28px; margin: 0; display: flex; align-items: center; gap: 12px; letter-spacing: 1px;"
                            span { classes = setOf("mdi", "mdi-heart-outline"); style = "font-size: 32px; color: #e91e63;" }
                            +"Wishlist"
                        }
                        p {
                            style = "color: #5f6368; font-size: 15px; margin: 8px 0 0 0; line-height: 1.6;"
                            +"Releases you want, with their prices tracked. Order them, watch them ship, and they join your collection when they arrive."
                        }
                    }
                    div {
                        style = "display: flex; gap: 8px; flex-wrap: wrap;"
                        button {
                            id = "wishlist-refresh-all"
                            style = outlineButtonStyle("#1a73e8") + " height: 40px;" +
                                if (isRefreshingAll) " opacity: 0.6; cursor: default;" else ""
                            disabled = isRefreshingAll
                            span {
                                classes = setOf("mdi", if (isRefreshingAll) "mdi-loading mdi-spin" else "mdi-refresh")
                                style = "font-size: 18px;"
                            }
                            +if (isRefreshingAll) "Checking prices..." else "Refresh all prices"
                            onClickFunction = { refreshAllPrices() }
                        }
                        button {
                            style = primaryButtonStyle("#e91e63") + " height: 40px;"
                            span { classes = setOf("mdi", "mdi-heart-plus"); style = "font-size: 18px;" }
                            +"Add from blu-ray.com"
                            onClickFunction = { openAddForm() }
                        }
                    }
                }

                div { id = "wishlist-summary"; style = "margin: 20px 0 24px 0;" }
                div { id = "wishlist-budget"; style = "margin-bottom: 24px;" }
                renderFilters()
                div { id = "wishlist-results" }
                div { id = SELECTION_BAR_ID; style = "position: sticky; bottom: 16px; z-index: 900; margin-top: 24px;" }
            }
        }
        renderBudgetPicker()
        renderSelectionBar()
    }

    // --- Selection ---

    private fun selectedItems(): List<WishlistItem> = items.filter { it.id in selectedIds }

    private fun cardBorder(selected: Boolean): String =
        if (selected) "2px solid #e91e63" else "1px solid #e8eaed"

    /**
     * Patches just the one card and the bar, so ticking a box does not rebuild
     * the grid under the cursor.
     */
    private fun toggleSelection(item: WishlistItem, selected: Boolean) {
        val id = item.id ?: return
        if (selected) selectedIds.add(id) else selectedIds.remove(id)
        (document.getElementById("wishlist-card-$id") as? HTMLElement)?.style?.border = cardBorder(selected)
        renderSelectionBar()
    }

    private fun setSelection(ids: Collection<Int>, selected: Boolean) {
        if (selected) selectedIds.addAll(ids) else selectedIds.removeAll(ids.toSet())
        renderResults()
        renderSelectionBar()
    }

    private fun renderSelectionBar() {
        val target = document.getElementById(SELECTION_BAR_ID) ?: return
        target.innerHTML = ""
        val selected = selectedItems()
        if (selected.isEmpty()) return

        val priced = selected.mapNotNull { it.currentPrice }
        val unpriced = selected.size - priced.size
        val total = roundToCents(priced.sum())
        val linkCount = selected.count { purchaseLink(it) != null }

        target.append {
            div {
                style = """
                    display: flex; align-items: center; gap: 16px; flex-wrap: wrap; padding: 14px 20px;
                    background-color: #202124; color: white; border-radius: 12px; box-shadow: 0 6px 20px rgba(0,0,0,0.25);
                """.trimIndent()
                span { classes = setOf("mdi", "mdi-checkbox-multiple-marked-outline"); style = "font-size: 24px; color: #f48fb1;" }
                div {
                    style = "flex: 1; min-width: 200px;"
                    div {
                        style = "font-size: 16px; font-weight: 500;"
                        +"${selected.size} selected · ${formatMoney(total)}"
                        if (unpriced > 0) {
                            span { style = "font-size: 13px; color: #bdc1c6; font-weight: 400;"; +" ($unpriced without a price)" }
                        }
                    }
                    div {
                        style = "font-size: 12px; color: #bdc1c6; margin-top: 2px;"
                        +"About ${formatMoney(total + computeTax(total, defaultTaxRate))} with ${formatPercent(defaultTaxRate)} tax, before shipping."
                    }
                }
                button {
                    style = primaryButtonStyle("#e91e63") + if (linkCount == 0) " opacity: 0.55; cursor: default;" else ""
                    disabled = linkCount == 0
                    attributes["title"] = "Amazon where there is one, otherwise the page a price was logged at, the store with the current price, or blu-ray.com"
                    span { classes = setOf("mdi", "mdi-open-in-new"); style = "font-size: 18px;" }
                    +"Open purchase links ($linkCount)"
                    onClickFunction = { openPurchaseLinks(selected, container) }
                }
                bulkStepButton(selected, WishlistStatus.ORDERED, "Mark ordered")
                bulkStepButton(selected, WishlistStatus.SHIPPED, "Mark shipped")
                bulkStepButton(selected, WishlistStatus.OWNED, "Mark received")
                button {
                    style = "background: none; border: 1px solid #5f6368; color: white; padding: 9px 14px; border-radius: 4px; cursor: pointer; font-size: 14px;"
                    +"Clear"
                    onClickFunction = { setSelection(selectedIds.toList(), false) }
                }
            }
        }
    }

    /** The selected items a bulk step applies to: the ones at the step before it. */
    private fun eligibleFor(target: WishlistStatus, selected: List<WishlistItem>): List<WishlistItem> = when (target) {
        WishlistStatus.ORDERED -> selected.filter { it.status == WishlistStatus.WISHLIST }
        WishlistStatus.SHIPPED -> selected.filter { it.status == WishlistStatus.ORDERED }
        WishlistStatus.OWNED -> selected.filter { it.status == WishlistStatus.ORDERED || it.status == WishlistStatus.SHIPPED }
        WishlistStatus.WISHLIST -> emptyList()
    }

    private fun FlowContent.bulkStepButton(selected: List<WishlistItem>, target: WishlistStatus, label: String) {
        val eligible = eligibleFor(target, selected)
        if (eligible.isEmpty()) return
        button {
            style = "background: none; border: 1px solid #5f6368; color: white; padding: 9px 14px; border-radius: 4px; cursor: pointer; font-size: 14px; display: inline-flex; align-items: center; gap: 6px;"
            if (eligible.size < selected.size) {
                attributes["title"] = "${selected.size - eligible.size} of the selected items are not at this step and are left out"
            }
            span { classes = setOf("mdi", statusIcon(target)); style = "font-size: 18px; color: ${statusColors(target).first};" }
            +"$label (${eligible.size})"
            onClickFunction = { openBulkStep(target) }
        }
    }

    private fun openBulkStep(target: WishlistStatus) {
        val selected = selectedItems()
        val eligible = eligibleFor(target, selected)
        if (eligible.isEmpty()) return
        val skipped = selected.size - eligible.size

        val onDone: (List<WishlistItem>) -> Unit = { updated ->
            // The step is done with these, so they leave the selection; items it
            // skipped (or that were taken off the order in the dialog) stay
            // selected for whatever is next
            selectedIds.removeAll(updated.mapNotNull { it.id }.toSet())
            renderSelectionBar()
            reload()
            if (target == WishlistStatus.OWNED) {
                alertDialog.show(
                    title = "Added to your collection",
                    message = "${updated.size} item(s) are now releases in your collection."
                )
            }
        }
        if (target == WishlistStatus.ORDERED) {
            BulkOrderDialog(container, eligible, defaultTaxRate, skipped, onDone).show()
        } else {
            BulkStatusDialog(container, eligible, target, skipped, onDone).show()
        }
    }

    private fun renderSummary() {
        val target = document.getElementById("wishlist-summary") ?: return
        target.innerHTML = ""
        val s = summary ?: return
        target.append {
            div {
                style = "display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 12px;"
                WishlistStatus.entries.forEach { status ->
                    val count = s.countsByStatus[status.name] ?: 0
                    val (bg, fg) = statusColors(status)
                    summaryCard(statusLabel(status), count.toString(), statusIcon(status), bg, fg, selected = status in selectedStatuses) {
                        if (status in selectedStatuses) selectedStatuses.remove(status) else selectedStatuses.add(status)
                        renderSummary()
                        renderResults()
                    }
                }
                summaryCard(
                    "Wishlist at today's prices",
                    formatMoney(s.wishlistTotalAtCurrentPrices),
                    "mdi-cash-multiple", "#f8f9fa", "#202124",
                    sub = "${s.wishlistPricedCount} priced" + (if (s.atTargetCount > 0) " · ${s.atTargetCount} at target" else "")
                )
                summaryCard("Spent this year", formatMoney(s.spentThisYear), "mdi-calendar-check", "#f8f9fa", "#202124",
                    sub = "${formatMoney(s.spentAllTime)} all time")
            }
        }
    }

    private fun FlowContent.summaryCard(
        label: String,
        value: String,
        icon: String,
        bg: String,
        fg: String,
        sub: String? = null,
        selected: Boolean? = null,
        onClick: (() -> Unit)? = null
    ) {
        div {
            style = """
                padding: 14px 16px;
                border-radius: 10px;
                background-color: $bg;
                border: 2px solid ${if (selected == true) fg else "transparent"};
                opacity: ${if (selected == false) "0.55" else "1"};
                cursor: ${if (onClick != null) "pointer" else "default"};
                display: flex;
                flex-direction: column;
                gap: 4px;
            """.trimIndent()
            if (onClick != null) {
                attributes["title"] = if (selected == true) "Click to hide" else "Click to show"
                onClickFunction = { onClick() }
            }
            div {
                style = "display: flex; align-items: center; gap: 6px; font-size: 12px; color: $fg; font-weight: 500;"
                span { classes = setOf("mdi", icon); style = "font-size: 16px;" }
                +label
            }
            div { style = "font-size: 22px; font-weight: 600; color: $fg;"; +value }
            sub?.let { div { style = "font-size: 11px; color: #80868b;"; +it } }
        }
    }

    // --- Budget picker ---

    /**
     * Items a roll can draw from: still wanted, and with a price to spend.
     * Pre-orders are money committed now for a disc that arrives later, so
     * they can be left out of a "what can I buy this month" roll.
     */
    private fun budgetCandidates(): List<WishlistItem> {
        val today = todayIso()
        return items.filter {
            it.status == WishlistStatus.WISHLIST &&
                it.currentPrice != null &&
                !(budgetSkipPreorders && isPreorder(it.releaseDate, today))
        }
    }

    /** A whole-dollar budget shows as "100" rather than "100.0". */
    private fun formatBudgetForInput(amount: Double): String =
        if (amount == amount.toLong().toDouble()) amount.toLong().toString() else formatMoney(amount).removePrefix("$")

    private fun roll() {
        val candidates = budgetCandidates()
        budgetResult = when (budgetGoal) {
            BudgetGoal.COUNT -> pickWithinBudget(candidates, budgetCount, budgetAmount)
            BudgetGoal.FILL_BUDGET -> pickWithinBudget(candidates, null, budgetAmount)
            BudgetGoal.MOST_ITEMS -> pickMostWithinBudget(candidates, budgetAmount)
        }
        renderBudgetPicker()
    }

    /**
     * Put the freshly loaded copies of the picked items back into the last
     * roll, so a price check or an edit is reflected without re-rolling.
     * Items that are gone, or no longer on the wishlist, drop out.
     */
    private fun syncBudgetPicks() {
        val current = budgetResult ?: return
        val candidates = budgetCandidates().associateBy { it.id }
        val picks = current.picks.mapNotNull { pick -> pick.id?.let { candidates[it] } }
        val total = roundToCents(picks.sumOf { it.currentPrice ?: 0.0 })
        budgetResult = current.copy(
            picks = picks,
            total = total,
            leftover = roundToCents(budgetAmount - total),
            eligibleCount = candidates.size
        )
    }

    private fun renderBudgetPicker() {
        val target = document.getElementById("wishlist-budget") ?: return
        target.innerHTML = ""
        target.append {
            div {
                style = "background-color: white; border: 1px solid #e8eaed; border-radius: 12px; overflow: hidden;"

                div {
                    style = "display: flex; align-items: center; gap: 12px; padding: 16px 20px; cursor: pointer;"
                    onClickFunction = {
                        budgetExpanded = !budgetExpanded
                        localStorage.setItem(BUDGET_EXPANDED_KEY, budgetExpanded.toString())
                        renderBudgetPicker()
                    }
                    span { classes = setOf("mdi", "mdi-dice-multiple"); style = "font-size: 24px; color: #e91e63;" }
                    div {
                        style = "flex: 1; min-width: 0;"
                        div {
                            style = "font-family: 'Oswald', sans-serif; font-weight: 500; font-size: 17px; color: #202124; letter-spacing: 0.5px;"
                            +"Budget picker"
                        }
                        div {
                            style = "font-size: 13px; color: #5f6368; margin-top: 2px;"
                            +"Randomly picks items from your wishlist that fit a budget - a set number, the most spent, or the most items"
                        }
                    }
                    span {
                        classes = setOf("mdi", if (budgetExpanded) "mdi-chevron-up" else "mdi-chevron-down")
                        style = "font-size: 22px; color: #5f6368;"
                    }
                }

                if (budgetExpanded) {
                    div {
                        style = "padding: 0 20px 20px 20px; border-top: 1px solid #f1f3f4;"
                        renderBudgetControls()
                        renderBudgetResult()
                    }
                }
            }
        }
    }

    private fun FlowContent.renderBudgetControls() {
        div {
            style = "display: flex; flex-wrap: wrap; gap: 16px; align-items: flex-end; padding-top: 16px;"

            div {
                style = "flex: 0 1 160px; min-width: 130px;"
                formLabel("Budget")
                input(type = InputType.number) {
                    value = formatBudgetForInput(budgetAmount)
                    attributes["min"] = "0"
                    attributes["step"] = "5"
                    style = formInputStyle()
                    onInputFunction = { event ->
                        val entered = (event.target as HTMLInputElement).value.toDoubleOrNull()
                        if (entered != null && entered >= 0) {
                            budgetAmount = entered
                            localStorage.setItem(BUDGET_AMOUNT_KEY, entered.toString())
                        }
                    }
                }
            }

            div {
                style = "flex: 0 1 190px; min-width: 160px;"
                formLabel("Goal")
                select {
                    style = formInputStyle()
                    attributes["title"] = "Set number: exactly that many items. Spend the most: use as much of the budget as possible. " +
                        "Most items: as many physical units as the budget covers."
                    BudgetGoal.entries.forEach { g ->
                        option { value = g.slug; selected = budgetGoal == g; +g.label }
                    }
                    onChangeFunction = { event ->
                        budgetGoal = BudgetGoal.fromSlug((event.target as HTMLSelectElement).value) ?: BudgetGoal.COUNT
                        localStorage.setItem(BUDGET_GOAL_KEY, budgetGoal.slug)
                        renderBudgetPicker()
                    }
                }
            }

            div {
                val countDisabled = budgetGoal != BudgetGoal.COUNT
                style = "flex: 0 1 150px; min-width: 130px;"
                formLabel("How many items")
                input(type = InputType.number) {
                    value = budgetCount.toString()
                    attributes["min"] = "1"
                    attributes["max"] = "25"
                    attributes["step"] = "1"
                    disabled = countDisabled
                    style = formInputStyle() + if (countDisabled) " opacity: 0.55;" else ""
                    onInputFunction = { event ->
                        val entered = (event.target as HTMLInputElement).value.toIntOrNull()
                        if (entered != null && entered in 1..25) {
                            budgetCount = entered
                            localStorage.setItem(BUDGET_COUNT_KEY, entered.toString())
                        }
                    }
                }
            }

            label {
                style = """
                    display: flex; align-items: center; gap: 8px; height: 40px; font-size: 14px;
                    color: #202124; cursor: pointer; user-select: none;
                """.trimIndent()
                attributes["title"] = "Leave out anything whose release date is still ahead"
                input(type = InputType.checkBox) {
                    checked = budgetSkipPreorders
                    onChangeFunction = { event ->
                        budgetSkipPreorders = (event.target as HTMLInputElement).checked
                        localStorage.setItem(BUDGET_SKIP_PREORDERS_KEY, budgetSkipPreorders.toString())
                        // The last roll may hold pre-orders that no longer qualify
                        syncBudgetPicks()
                        renderBudgetPicker()
                    }
                }
                +"Skip pre-orders"
            }

            button {
                style = primaryButtonStyle("#e91e63") + " height: 40px;"
                span { classes = setOf("mdi", "mdi-dice-5-outline"); style = "font-size: 18px;" }
                +if (budgetResult == null) "Roll" else "Roll again"
                onClickFunction = { roll() }
            }

            if (budgetResult != null) {
                button {
                    style = outlineButtonStyle() + " height: 40px;"
                    +"Clear"
                    onClickFunction = {
                        budgetResult = null
                        renderBudgetPicker()
                    }
                }
            }
        }
    }

    private fun FlowContent.renderBudgetResult() {
        val result = budgetResult
        val candidateCount = budgetCandidates().size

        if (result == null) {
            div {
                style = "margin-top: 14px; font-size: 13px; color: #5f6368;"
                +if (candidateCount == 0) {
                    if (budgetSkipPreorders) {
                        "No priced wishlist items are out yet, so there is nothing to pick from - untick \"Skip pre-orders\" to include them."
                    } else {
                        "None of your wishlist items have a price yet, so there is nothing to pick from."
                    }
                } else {
                    "Drawing from $candidateCount priced wishlist item${if (candidateCount == 1) "" else "s"}" +
                        (if (budgetSkipPreorders) " that are out" else "") +
                        ", whatever the filters below are set to."
                }
            }
            return
        }

        if (result.picks.isEmpty()) {
            div {
                style = "margin-top: 14px; padding: 16px; background-color: #fef7e0; border-radius: 8px; font-size: 14px; color: #b06000;"
                +when {
                    result.eligibleCount == 0 && budgetSkipPreorders ->
                        "No priced wishlist items are out yet - untick \"Skip pre-orders\" to include them."
                    result.eligibleCount == 0 ->
                        "None of your wishlist items have a price yet, so there is nothing to pick from."
                    result.requestedCount == null && result.minimumBudgetForCount != null ->
                        "${formatMoney(budgetAmount)} is below the cheapest priced item at ${formatMoney(result.minimumBudgetForCount)}."
                    result.requestedCount == null ->
                        "Enter a budget above ${formatMoney(0.0)} to roll."
                    result.minimumBudgetForCount == null ->
                        "Only ${result.eligibleCount} priced wishlist item${if (result.eligibleCount == 1) "" else "s"} to choose from, fewer than the ${result.requestedCount} asked for."
                    else ->
                        "${formatMoney(budgetAmount)} will not cover ${result.requestedCount} items - the cheapest ${result.requestedCount} come to ${formatMoney(result.minimumBudgetForCount)}."
                }
            }
            return
        }

        div {
            style = "margin-top: 16px; display: flex; flex-direction: column; gap: 8px;"
            result.picks.forEach { budgetPickRow(it) }
        }

        if (!result.isComplete && result.requestedCount != null) {
            div {
                style = "margin-top: 12px; font-size: 13px; color: #b06000;"
                +("Only ${result.picks.size} of ${result.requestedCount} items fit in ${formatMoney(budgetAmount)}" +
                    (result.minimumBudgetForCount?.let { " - ${result.requestedCount} would need ${formatMoney(it)}." } ?: "."))
            }
        }

        div {
            style = "margin-top: 14px; padding-top: 14px; border-top: 1px solid #f1f3f4;"
            div {
                style = "font-size: 15px; color: #202124; font-weight: 500;"
                +"${result.picks.size} pick${if (result.picks.size == 1) "" else "s"} · ${formatMoney(result.total)} of ${formatMoney(budgetAmount)} · ${formatMoney(result.leftover)} left"
            }
            if (budgetGoal == BudgetGoal.MOST_ITEMS && result.isComplete) {
                div {
                    style = "font-size: 12px; color: #80868b; margin-top: 4px;"
                    +"${result.picks.size} is the most items ${formatMoney(budgetAmount)} covers. Roll again for a different set of that size."
                }
            }
            div {
                style = "font-size: 12px; color: #80868b; margin-top: 4px;"
                +"About ${formatMoney(result.total + computeTax(result.total, defaultTaxRate))} with ${formatPercent(defaultTaxRate)} tax, before shipping."
            }
            div {
                style = "display: flex; gap: 8px; flex-wrap: wrap; margin-top: 12px;"
                val linkCount = result.picks.count { purchaseLink(it) != null }
                button {
                    style = outlineButtonStyle("#e91e63") + if (linkCount == 0) " opacity: 0.55; cursor: default;" else ""
                    disabled = linkCount == 0
                    attributes["title"] = "Amazon where there is one, otherwise the page a price was logged at, the store with the current price, or blu-ray.com"
                    span { classes = setOf("mdi", "mdi-open-in-new"); style = "font-size: 16px;" }
                    +"Open purchase links ($linkCount)"
                    onClickFunction = { openPurchaseLinks(result.picks, container) }
                }
                button {
                    style = outlineButtonStyle()
                    attributes["title"] = "Add these picks to the items selected below"
                    span { classes = setOf("mdi", "mdi-checkbox-multiple-marked-outline"); style = "font-size: 16px;" }
                    +"Select these"
                    onClickFunction = { setSelection(result.picks.mapNotNull { it.id }, true) }
                }
            }
        }
    }

    private fun FlowContent.budgetPickRow(item: WishlistItem) {
        div {
            style = """
                display: flex; align-items: center; gap: 12px; padding: 8px; border: 1px solid #f1f3f4;
                border-radius: 8px; cursor: pointer;
            """.trimIndent()
            attributes["onmouseover"] = "this.style.backgroundColor='#f8f9fa'"
            attributes["onmouseout"] = "this.style.backgroundColor='transparent'"
            onClickFunction = { openDetailFor(item) }

            div {
                style = "width: 40px; height: 54px; flex-shrink: 0; background-color: #f1f3f4; border-radius: 4px; display: flex; align-items: center; justify-content: center; overflow: hidden;"
                val cover = item.displayImages().firstOrNull()?.imageUrl
                if (cover != null) {
                    img {
                        src = cover
                        alt = item.title ?: "Cover"
                        style = "max-width: 100%; max-height: 100%; object-fit: contain;"
                        attributes["onerror"] = "this.style.display='none'"
                    }
                } else {
                    span { classes = setOf("mdi", "mdi-disc"); style = "font-size: 20px; color: #bdc1c6;" }
                }
            }

            div {
                style = "flex: 1; min-width: 0;"
                div {
                    style = "font-size: 14px; color: #202124; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;"
                    +(item.title ?: "Untitled release")
                }
                div {
                    style = "display: flex; flex-wrap: wrap; align-items: center; gap: 4px; margin-top: 4px; font-size: 12px; color: #5f6368;"
                    item.mediaTypes.forEach { mediaTypeChip(it) }
                    item.distributor?.let { +it }
                }
            }

            span {
                style = "font-size: 15px; font-weight: 600; color: ${if (item.atTarget) "#188038" else "#202124"}; flex-shrink: 0;"
                +formatMoney(item.currentPrice)
            }

            purchaseLink(item)?.let { link ->
                a(href = link.url, target = "_blank") {
                    attributes["rel"] = "noopener"
                    attributes["title"] = "Buy at ${link.label}"
                    style = "color: #1a73e8; padding: 4px; display: inline-flex; flex-shrink: 0;"
                    onClickFunction = { it.stopPropagation() }
                    span { classes = setOf("mdi", "mdi-open-in-new"); style = "font-size: 18px;" }
                }
            }
        }
    }

    /**
     * Empty the search and reload. Typing does not reload on its own (the
     * field reloads on change), but clearing does: the point of the button is
     * to get the whole list back in one click.
     */
    private fun clearSearch() {
        if (searchText.isBlank()) return
        searchText = ""
        (document.getElementById(SEARCH_INPUT_ID) as? HTMLInputElement)?.value = ""
        syncSearchClear()
        reload()
    }

    /**
     * Show the clear button only when there is something to clear. Patched in
     * place rather than re-rendered, which would take the focus out of the
     * field mid-word.
     */
    private fun syncSearchClear() {
        (document.getElementById("$SEARCH_INPUT_ID-clear") as? HTMLElement)
            ?.setAttribute("style", searchClearStyle(searchText.isNotBlank()))
    }

    private fun searchClearStyle(visible: Boolean): String = """
        position: absolute; right: 4px; top: 50%; transform: translateY(-50%);
        background: none; border: none; padding: 4px; cursor: pointer; color: #5f6368;
        display: ${if (visible) "flex" else "none"}; align-items: center;
    """.trimIndent()

    private fun FlowContent.renderFilters() {
        div {
            style = """
                background-color: #f8f9fa; padding: 20px; border-radius: 12px; margin-bottom: 24px; border: 1px solid #e8eaed;
                display: flex; flex-wrap: wrap; gap: 16px; align-items: flex-end;
            """.trimIndent()

            div {
                style = "flex: 2 1 240px; min-width: 200px;"
                formLabel("Search")
                div {
                    style = "position: relative;"
                    input(type = InputType.text) {
                        id = SEARCH_INPUT_ID
                        value = searchText
                        placeholder = "Title, film, distributor, tag or note"
                        // Room on the right for the clear button
                        style = formInputStyle() + " padding-right: 34px;"
                        onInputFunction = { event ->
                            searchText = (event.target as HTMLInputElement).value
                            syncSearchClear()
                        }
                        onChangeFunction = { reload() }
                    }
                    button {
                        id = "$SEARCH_INPUT_ID-clear"
                        attributes["title"] = "Clear the search"
                        attributes["type"] = "button"
                        style = searchClearStyle(searchText.isNotBlank())
                        span { classes = setOf("mdi", "mdi-close"); style = "font-size: 18px;" }
                        onClickFunction = { clearSearch() }
                    }
                }
            }

            div {
                style = "flex: 1 1 150px; min-width: 140px;"
                formLabel("Group by")
                select {
                    style = formInputStyle()
                    GroupBy.entries.forEach { g ->
                        option { value = g.slug; selected = groupBy == g; +g.label }
                    }
                    onChangeFunction = { event ->
                        val slug = (event.target as HTMLSelectElement).value
                        groupBy = GroupBy.entries.firstOrNull { it.slug == slug } ?: GroupBy.STATUS
                        renderResults()
                    }
                }
            }

            div {
                style = "flex: 1 1 130px; min-width: 120px;"
                formLabel("Format")
                select {
                    id = "wishlist-filter-format"
                    style = formInputStyle()
                    option { value = ""; selected = selectedMediaType.isEmpty(); +"All formats" }
                    mediaTypeOptions.forEach { t -> option { value = t; selected = selectedMediaType == t; +t } }
                    onChangeFunction = { event -> selectedMediaType = (event.target as HTMLSelectElement).value; reload() }
                }
            }

            div {
                style = "flex: 1 1 160px; min-width: 150px;"
                formLabel("Distributor")
                select {
                    id = "wishlist-filter-distributor"
                    style = formInputStyle()
                    option { value = ""; selected = selectedDistributor.isEmpty(); +"All distributors" }
                    distributorOptions.forEach { d -> option { value = d; selected = selectedDistributor == d; +d } }
                    onChangeFunction = { event -> selectedDistributor = (event.target as HTMLSelectElement).value; reload() }
                }
            }

            div {
                style = "flex: 1 1 140px; min-width: 130px;"
                formLabel("Tag")
                select {
                    id = "wishlist-filter-tag"
                    style = formInputStyle()
                    option { value = ""; selected = selectedTag.isEmpty(); +"All tags" }
                    tagOptions.forEach { t -> option { value = t; selected = selectedTag == t; +t } }
                    onChangeFunction = { event -> selectedTag = (event.target as HTMLSelectElement).value; reload() }
                }
            }

            div {
                style = "flex: 1 1 120px; min-width: 110px;"
                formLabel("Priority")
                select {
                    style = formInputStyle()
                    option { value = ""; selected = selectedPriority == null; +"Any" }
                    WishlistPriority.entries.forEach { p -> option { value = p.name; selected = selectedPriority == p; +priorityLabel(p) } }
                    onChangeFunction = { event ->
                        val v = (event.target as HTMLSelectElement).value
                        selectedPriority = WishlistPriority.entries.firstOrNull { it.name == v }
                        reload()
                    }
                }
            }

            div {
                style = "flex: 1 1 150px; min-width: 140px;"
                formLabel("Sort by")
                select {
                    style = formInputStyle()
                    listOf(
                        "date_added" to "Date added",
                        "price" to "Current price",
                        "percent_off" to "% off list",
                        "price_drop" to "Biggest drop",
                        "release_date" to "Release date",
                        "priority" to "Priority",
                        "title" to "Title"
                    ).forEach { (v, l) -> option { value = v; selected = sortField == v; +l } }
                    onChangeFunction = { event ->
                        sortField = (event.target as HTMLSelectElement).value
                        // Sensible default direction per field
                        sortDirection = when (sortField) {
                            "title", "price", "release_date", "priority" -> "asc"
                            else -> "desc"
                        }
                        reload()
                    }
                }
            }

            button {
                style = outlineButtonStyle() + " height: 40px;"
                attributes["title"] = if (sortDirection == "asc") "Ascending" else "Descending"
                span { classes = setOf("mdi", if (sortDirection == "asc") "mdi-sort-ascending" else "mdi-sort-descending"); style = "font-size: 18px;" }
                onClickFunction = {
                    sortDirection = if (sortDirection == "asc") "desc" else "asc"
                    reload()
                }
            }

            label {
                style = "display: flex; align-items: center; gap: 8px; font-size: 14px; color: #202124; cursor: pointer; height: 40px;"
                input(type = InputType.checkBox) {
                    checked = atTargetOnly
                    onChangeFunction = { event -> atTargetOnly = (event.target as HTMLInputElement).checked; reload() }
                }
                +"At target price"
            }
            label {
                style = "display: flex; align-items: center; gap: 8px; font-size: 14px; color: #202124; cursor: pointer; height: 40px;"
                input(type = InputType.checkBox) {
                    checked = inStockOnly
                    onChangeFunction = { event -> inStockOnly = (event.target as HTMLInputElement).checked; reload() }
                }
                +"In stock"
            }
        }
    }

    /** Refill the option lists once the server reports what is in use. */
    private fun renderFilterOptions() {
        fun refill(id: String, options: List<String>, selected: String, allLabel: String) {
            val select = document.getElementById(id) as? HTMLSelectElement ?: return
            select.innerHTML = ""
            select.append {
                option { value = ""; this.selected = selected.isEmpty(); +allLabel }
                options.forEach { o -> option { value = o; this.selected = selected == o; +o } }
            }
        }
        refill("wishlist-filter-format", mediaTypeOptions, selectedMediaType, "All formats")
        refill("wishlist-filter-distributor", distributorOptions, selectedDistributor, "All distributors")
        refill("wishlist-filter-tag", tagOptions, selectedTag, "All tags")
    }

    private fun visibleItems(): List<WishlistItem> = items.filter { it.status in selectedStatuses }

    private fun renderResults() {
        val results = document.getElementById("wishlist-results") ?: return
        results.innerHTML = ""

        results.append {
            // Only take over the page on the first load; a reload keeps the
            // cards up so the page does not collapse under the scroll position
            if (isLoading && items.isEmpty()) {
                div { style = "padding: 60px 20px; text-align: center; color: #5f6368; font-size: 15px;"; +"Loading wishlist..." }
                return@append
            }

            refreshProgress?.let { progress -> div { refreshProgressBar(progress) } }

            val visible = visibleItems()
            if (visible.isEmpty()) {
                div {
                    style = "padding: 60px 20px; text-align: center; color: #5f6368; background-color: #f8f9fa; border: 1px dashed #dadce0; border-radius: 12px;"
                    if (items.isEmpty() && searchText.isBlank() && selectedMediaType.isBlank() && selectedDistributor.isBlank() && selectedTag.isBlank()) {
                        div { style = "font-size: 40px; margin-bottom: 8px;"; span { classes = setOf("mdi", "mdi-heart-outline"); style = "color: #e91e63;" } }
                        div { style = "font-size: 16px; margin-bottom: 4px; color: #202124;"; +"Your wishlist is empty" }
                        div { style = "font-size: 14px;"; +"Paste a blu-ray.com URL with \"Add from blu-ray.com\" to start tracking a release." }
                    } else if (selectedStatuses.isEmpty()) {
                        +"Every status is hidden. Click a status card above to show it."
                    } else {
                        +"Nothing matches these filters."
                    }
                }
                return@append
            }

            div {
                style = "margin-bottom: 16px; color: #5f6368; font-size: 14px; display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap;"
                span {
                    style = "display: inline-flex; align-items: center; gap: 12px; flex-wrap: wrap;"
                    span { +"${visible.size} item${if (visible.size == 1) "" else "s"}" }
                    val visibleIds = visible.mapNotNull { it.id }
                    val linkStyle = "background: none; border: none; padding: 0; color: #1a73e8; cursor: pointer; font-size: 14px;"
                    if (!selectedIds.containsAll(visibleIds)) {
                        button {
                            style = linkStyle
                            +"Select all shown"
                            onClickFunction = { setSelection(visibleIds, true) }
                        }
                    }
                    if (selectedIds.isNotEmpty()) {
                        button {
                            style = linkStyle
                            +"Clear selection"
                            onClickFunction = { setSelection(selectedIds.toList(), false) }
                        }
                    }
                }
                // The refresh pass has a bar of its own above; this is only for
                // the list reloading
                if (isLoading) {
                    span {
                        style = "display: inline-flex; align-items: center; gap: 6px; color: #1a73e8;"
                        span { classes = setOf("mdi", "mdi-loading", "mdi-spin"); style = "font-size: 16px;" }
                        +"Refreshing..."
                    }
                }
            }

            div { renderGroups(visible) }
        }
    }

    private fun FlowContent.renderGroups(visible: List<WishlistItem>) {
        if (groupBy == GroupBy.ORDER) {
            // One band for everything on order, so an order that has partly
            // shipped stays together, then the rest by status
            val inFlight = visible.filter { it.status in IN_FLIGHT_STATUSES }
                .sortedBy { WISHLIST_STATUS_DISPLAY_ORDER.indexOf(it.status) }
            if (inFlight.isNotEmpty()) {
                groupHeading("On order", inFlight.size)
                orderSections(inFlight)
            }
            WISHLIST_STATUS_DISPLAY_ORDER.filter { it !in IN_FLIGHT_STATUSES }.forEach { status ->
                val rest = visible.filter { it.status == status }
                if (rest.isNotEmpty()) {
                    groupHeading(statusLabel(status), rest.size)
                    cardGrid(rest)
                }
            }
            return
        }

        grouped(visible).forEach { (heading, groupItems) ->
            if (groupBy != GroupBy.NONE) groupHeading(heading, groupItems.size)
            if (groupBy == GroupBy.STATUS && groupItems.first().status in IN_FLIGHT_STATUSES) {
                orderSections(groupItems)
            } else {
                cardGrid(groupItems)
            }
        }
    }

    private fun FlowContent.groupHeading(heading: String, count: Int) {
        div {
            style = "display: flex; align-items: center; gap: 10px; margin: 24px 0 12px 0;"
            h2 {
                style = "font-family: 'Oswald', sans-serif; font-weight: 500; font-size: 18px; color: #202124; margin: 0; letter-spacing: 0.5px;"
                +heading
            }
            span { style = chipStyle("#f1f3f4", "#5f6368"); +count.toString() }
            div { style = "flex: 1; height: 1px; background-color: #e8eaed;" }
        }
    }

    private fun FlowContent.cardGrid(groupItems: List<WishlistItem>) {
        div {
            style = "display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 16px;"
            groupItems.forEach { itemCard(it) }
        }
    }

    // --- Orders ---

    /** Items on order, one panel per checkout and, within it, per shipment. */
    private fun FlowContent.orderSections(groupItems: List<WishlistItem>) {
        div {
            style = "display: flex; flex-direction: column; gap: 16px;"
            groupByOrder(groupItems).forEach { orderPanel(it) }
        }
    }

    private fun FlowContent.orderPanel(group: OrderGroup) {
        div {
            style = "border: 1px solid #e8eaed; border-radius: 12px; background-color: #fafafa; padding: 14px;"

            div {
                style = "display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 12px;"
                span {
                    classes = setOf("mdi", if (group.hasOrder) "mdi-receipt-text-outline" else "mdi-help-circle-outline")
                    style = "font-size: 22px; color: #5f6368;"
                }
                div {
                    style = "flex: 1; min-width: 200px;"
                    div {
                        style = "font-size: 15px; font-weight: 500; color: #202124; display: flex; align-items: center; gap: 8px; flex-wrap: wrap;"
                        +orderTitle(group)
                        if (group.hasOrder && group.orderNumbers.isEmpty()) {
                            span { style = chipStyle("#fef7e0", "#b06000"); +"No order number" }
                        }
                    }
                    div {
                        style = "font-size: 12px; color: #5f6368; margin-top: 2px;"
                        +orderSubtitle(group)
                    }
                }

                // With a single box the tracking link belongs in the header;
                // with several, each shipment below carries its own
                group.shipments.singleOrNull()?.trackingUrl?.let { trackingLink(it) }

                val ids = group.items.mapNotNull { it.id }
                if (!selectedIds.containsAll(ids)) {
                    button {
                        style = outlineButtonStyle()
                        attributes["title"] = "Select every item on this order, to mark them shipped or received together"
                        span { classes = setOf("mdi", "mdi-checkbox-multiple-marked-outline"); style = "font-size: 16px;" }
                        +"Select"
                        onClickFunction = { setSelection(ids, true) }
                    }
                }
                if (group.hasOrder) {
                    button {
                        style = outlineButtonStyle("#1a73e8")
                        span { classes = setOf("mdi", "mdi-pencil-outline"); style = "font-size: 16px;" }
                        +if (group.orderNumbers.isEmpty()) "Add order number" else "Edit order"
                        onClickFunction = { openOrderEditor(group.orderIds) }
                    }
                }
            }

            if (group.shipments.size > 1) {
                group.shipments.forEachIndexed { index, shipment ->
                    div {
                        style = "display: flex; align-items: center; gap: 8px; margin: ${if (index == 0) "0" else "16px"} 0 8px 0; font-size: 13px; color: #3c4043;"
                        span {
                            classes = setOf("mdi", if (shipment.trackingUrl != null) "mdi-truck-outline" else "mdi-package-variant")
                            style = "font-size: 18px; color: #5f6368;"
                        }
                        span {
                            style = "font-weight: 500;"
                            +(shipment.trackingUrl?.let { "Shipment ${index + 1}" + (hostOf(it)?.let { host -> " via $host" } ?: "") }
                                ?: "No tracking yet")
                        }
                        span { style = chipStyle("#f1f3f4", "#5f6368"); +shipment.items.size.toString() }
                        shipment.trackingUrl?.let { trackingLink(it) }
                    }
                    cardGrid(shipment.items)
                }
            } else {
                cardGrid(group.items)
            }
        }
    }

    private fun orderTitle(group: OrderGroup): String {
        if (!group.hasOrder) return "No purchase recorded"
        val store = group.vendors.joinToString(" / ").ifEmpty { "Unknown store" }
        return when (group.orderNumbers.size) {
            0 -> store
            1 -> "$store · Order #${group.orderNumbers.single().removePrefix("#")}"
            else -> "$store · Orders " + group.orderNumbers.joinToString(", ") { "#${it.removePrefix("#")}" }
        }
    }

    private fun orderSubtitle(group: OrderGroup): String {
        if (!group.hasOrder) return "${group.items.size} item${if (group.items.size == 1) "" else "s"} marked ordered with nothing paid recorded"
        val shown = group.items.size
        return listOfNotNull(
            group.orderDate?.let { "Ordered ${formatDate(it)}" },
            "${group.itemCount} item${if (group.itemCount == 1) "" else "s"}" +
                (if (shown < group.itemCount) " ($shown shown here)" else ""),
            "${formatMoney(group.total)} total",
            group.orderIds.size.takeIf { it > 1 }?.let { "recorded as $it separate orders" }
        ).joinToString(" · ")
    }

    private fun FlowContent.trackingLink(url: String) {
        a(href = url, target = "_blank") {
            attributes["rel"] = "noopener"
            style = "display: inline-flex; align-items: center; gap: 4px; font-size: 13px; color: #1a73e8; text-decoration: none;"
            span { classes = setOf("mdi", "mdi-truck-fast-outline"); style = "font-size: 16px;" }
            +"Track"
        }
    }

    /** "ups.com" from a tracking URL, for telling shipments apart at a glance. */
    private fun hostOf(url: String): String? =
        Regex("^[a-z]+://(?:www\\.)?([^/?#]+)", RegexOption.IGNORE_CASE).find(url.trim())?.groupValues?.get(1)

    private fun openOrderEditor(orderIds: List<Int>) {
        OrderEditDialog(container, orderIds) { reload() }.show()
    }

    /**
     * Section the visible items. An item with several tags or formats appears
     * under each of them.
     */
    private fun grouped(visible: List<WishlistItem>): List<Pair<String, List<WishlistItem>>> {
        val today = todayIso()
        return when (groupBy) {
            // Ungrouped still floats the things already paid for to the top,
            // keeping the sort within each status band
            GroupBy.NONE -> listOf("" to visible.sortedBy { WISHLIST_STATUS_DISPLAY_ORDER.indexOf(it.status) })
            GroupBy.STATUS -> WISHLIST_STATUS_DISPLAY_ORDER.map { s -> statusLabel(s) to visible.filter { it.status == s } }
            // Sectioned by orderSections, which needs more than a heading per group
            GroupBy.ORDER -> emptyList()
            GroupBy.PRIORITY -> WishlistPriority.entries.map { p -> "${priorityLabel(p)} priority" to visible.filter { it.priority == p } }
            GroupBy.FORMAT -> {
                val byFormat = MediaType.entries.map { t -> mediaTypeLabel(t) to visible.filter { t in it.mediaTypes } }
                byFormat + ("No format" to visible.filter { it.mediaTypes.isEmpty() })
            }
            GroupBy.DISTRIBUTOR -> visible.groupBy { it.distributor ?: "Unknown distributor" }
                .toList().sortedWith(compareBy({ it.first == "Unknown distributor" }, { it.first.lowercase() }))
            GroupBy.TAG -> {
                val tagged = tagOptions.map { tag -> tag to visible.filter { i -> i.tags.any { it.equals(tag, ignoreCase = true) } } }
                tagged + ("Untagged" to visible.filter { it.tags.isEmpty() })
            }
            GroupBy.PRICE -> PRICE_BRACKET_ORDER.map { b -> b to visible.filter { priceBracket(it.currentPrice) == b } }
            GroupBy.RELEASE_DATE -> RELEASE_BUCKET_ORDER.map { b -> b to visible.filter { releaseDateBucket(it.releaseDate, today) == b } }
            GroupBy.VENDOR -> {
                val purchased = visible.filter { it.purchase != null }
                purchased.groupBy { it.purchase?.vendor ?: "Unknown vendor" }.toList().sortedBy { it.first.lowercase() } +
                    ("Not purchased" to visible.filter { it.purchase == null })
            }
        }.filter { it.second.isNotEmpty() }
    }

    private fun FlowContent.itemCard(item: WishlistItem) {
        val isSelected = item.id in selectedIds
        div {
            item.id?.let { id = "wishlist-card-$it" }
            style = """
                background-color: white; border: ${cardBorder(isSelected)}; border-radius: 12px; overflow: hidden;
                display: flex; flex-direction: column; transition: transform 0.15s, box-shadow 0.15s;
            """.trimIndent()
            attributes["onmouseover"] = "this.style.transform='translateY(-2px)'; this.style.boxShadow='0 8px 20px rgba(0,0,0,0.10)'"
            attributes["onmouseout"] = "this.style.transform='translateY(0)'; this.style.boxShadow='none'"

            div {
                style = "display: flex; gap: 14px; padding: 14px; cursor: pointer; flex: 1;"
                onClickFunction = { openDetailFor(item) }

                div {
                    style = "width: 90px; height: 120px; flex-shrink: 0; background-color: #f1f3f4; border-radius: 6px; display: flex; align-items: center; justify-content: center; overflow: hidden; position: relative;"
                    val cover = item.displayImages().firstOrNull()?.imageUrl
                    if (cover != null) {
                        img {
                            src = cover
                            alt = item.title ?: "Cover"
                            style = "max-width: 100%; max-height: 100%; object-fit: contain;"
                            attributes["onerror"] = "this.style.display='none'"
                        }
                    } else {
                        span { classes = setOf("mdi", "mdi-disc"); style = "font-size: 36px; color: #bdc1c6;" }
                    }
                    if (item.priority == WishlistPriority.HIGH) {
                        span {
                            classes = setOf("mdi", "mdi-star")
                            style = "position: absolute; top: 4px; left: 4px; color: #f9ab00; font-size: 18px; text-shadow: 0 0 3px white;"
                            attributes["title"] = "High priority"
                        }
                    }
                    if (item.id != null) {
                        label {
                            style = """
                                position: absolute; top: 2px; right: 2px; padding: 4px; display: flex;
                                background-color: rgba(255,255,255,0.85); border-radius: 4px; cursor: pointer;
                            """.trimIndent()
                            attributes["title"] = "Select to total up and open purchase links"
                            // Keeps the click from also opening the detail view
                            onClickFunction = { it.stopPropagation() }
                            input(type = InputType.checkBox) {
                                checked = isSelected
                                style = "margin: 0; width: 16px; height: 16px; cursor: pointer; accent-color: #e91e63;"
                                onChangeFunction = { event ->
                                    toggleSelection(item, (event.target as HTMLInputElement).checked)
                                }
                            }
                        }
                    }
                }

                div {
                    style = "flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6px;"
                    div {
                        style = "font-weight: 500; font-size: 15px; color: #202124; line-height: 1.35; overflow: hidden; text-overflow: ellipsis;"
                        +(item.title ?: "Untitled release")
                    }
                    div {
                        style = "display: flex; flex-wrap: wrap; gap: 4px;"
                        if (groupBy != GroupBy.STATUS) statusChip(item.status)
                        item.mediaTypes.forEach { mediaTypeChip(it) }
                        if (item.isCollection) span { style = chipStyle("#fef7e0", "#b06000"); +"Box set" }
                    }
                    div {
                        style = "font-size: 12px; color: #5f6368;"
                        +listOfNotNull(item.distributor, item.releaseDate?.take(4)).joinToString(" · ")
                    }

                    priceLine(item)

                    if (item.tags.isNotEmpty()) {
                        div {
                            style = "display: flex; flex-wrap: wrap; gap: 4px; margin-top: auto;"
                            item.tags.take(3).forEach { tagChip(it) }
                            if (item.tags.size > 3) span { style = chipStyle("#f1f3f4", "#5f6368"); +"+${item.tags.size - 3}" }
                        }
                    }
                }
            }

            div {
                style = "display: flex; gap: 6px; padding: 10px 14px; border-top: 1px solid #f1f3f4; background-color: #fafafa; flex-wrap: wrap;"
                item.status.next()?.let { next ->
                    button {
                        style = outlineButtonStyle(statusColors(next).second)
                        span { classes = setOf("mdi", statusIcon(next)); style = "font-size: 16px;" }
                        +advanceLabel(next)
                        onClickFunction = { openStatusDialog(item, next) }
                    }
                }
                if (item.status == WishlistStatus.OWNED && item.releaseId != null) {
                    button {
                        style = outlineButtonStyle("#1a73e8")
                        span { classes = setOf("mdi", "mdi-package-variant-closed"); style = "font-size: 16px;" }
                        +"Release"
                        onClickFunction = { ReleaseDetail(container, releaseId = item.releaseId, onBack = { show() }).show() }
                    }
                }
                div { style = "flex: 1;" }
                // Stores whose prices are typed in have nothing to fetch, so an
                // item tracked only that way gets no check button.
                val checkable = item.vendorLinks.filter { it.reader.isAutomatic }
                val hasPriceSource = !item.blurayComUrl.isNullOrBlank() || checkable.isNotEmpty()
                if (hasPriceSource && item.status != WishlistStatus.OWNED) {
                    if (item.id in refreshingIds) {
                        span {
                            style = "color: #1a73e8; padding: 6px; display: inline-flex;"
                            attributes["title"] = "Checking prices..."
                            span { classes = setOf("mdi", "mdi-loading", "mdi-spin"); style = "font-size: 18px;" }
                        }
                    } else {
                        val where = listOfNotNull(
                            item.blurayComUrl?.takeIf { it.isNotBlank() }?.let { "blu-ray.com" },
                            *checkable.map { it.vendor }.toTypedArray()
                        )
                        iconButton("mdi-refresh", "Check price at ${where.joinToString(", ")}") { quickRefresh(item) }
                    }
                }
                iconButton("mdi-pencil-outline", "Edit") { openEditForm(item) }
                iconButton("mdi-delete-outline", "Delete", "#d93025") { confirmDelete(item) }
            }
        }
    }

    private fun FlowContent.iconButton(icon: String, title: String, color: String = "#5f6368", onClick: () -> Unit) {
        button {
            style = "background: none; border: none; cursor: pointer; color: $color; padding: 6px; border-radius: 4px; display: inline-flex;"
            attributes["title"] = title
            attributes["onmouseover"] = "this.style.backgroundColor='#f1f3f4'"
            attributes["onmouseout"] = "this.style.backgroundColor='transparent'"
            span { classes = setOf("mdi", icon); style = "font-size: 18px;" }
            onClickFunction = { onClick() }
        }
    }

    private fun FlowContent.priceLine(item: WishlistItem) {
        div {
            style = "display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap; margin-top: 2px;"
            val purchase = item.purchase
            if (item.status != WishlistStatus.WISHLIST && purchase != null) {
                span { style = "font-size: 17px; font-weight: 600; color: #202124;"; +formatMoney(purchase.total) }
                span {
                    style = "font-size: 12px; color: #5f6368;"
                    +("paid" + (purchase.vendor?.let { " at $it" } ?: ""))
                    purchase.order?.takeIf { it.itemCount > 1 }?.let { +", 1 of ${it.itemCount} on the order" }
                }
                return@div
            }

            val current = item.currentPrice
            if (current == null) {
                span { style = "font-size: 13px; color: #80868b;"; +if (item.listPrice != null) "List ${formatMoney(item.listPrice)}" else "No price yet" }
                return@div
            }

            span {
                style = "font-size: 17px; font-weight: 600; color: ${if (item.atTarget) "#188038" else "#202124"};"
                +formatMoney(current)
            }
            item.listPrice?.takeIf { it > current }?.let {
                span { style = "font-size: 12px; color: #80868b; text-decoration: line-through;"; +formatMoney(it) }
            }
            item.percentOffList?.let { span { style = chipStyle("#e6f4ea", "#188038"); +"$it% off" } }
            when {
                item.atTarget -> span { style = chipStyle("#e6f4ea", "#188038"); +"At target" }
                item.priceDropped -> span { style = chipStyle("#e8f0fe", "#1a73e8"); +"Price drop" }
                item.lowestPrice != null && current == item.lowestPrice && item.priceHistory.size > 1 ->
                    span { style = chipStyle("#e8f0fe", "#1a73e8"); +"Lowest ever" }
            }
            if (item.inStock == false) span { style = chipStyle("#fce8e6", "#c5221f"); +"Out of stock" }
        }
    }
}
