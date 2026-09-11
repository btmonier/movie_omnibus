package org.btmonier

import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.onChangeFunction
import kotlinx.html.js.onClickFunction
import kotlinx.html.js.onInputFunction
import org.w3c.dom.Element
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement

/**
 * Ways the wishlist can be sectioned on the page. Grouping is done in the
 * browser over the full filtered list, so switching is instant.
 */
private enum class GroupBy(val slug: String, val label: String) {
    STATUS("status", "Status"),
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
private const val BUDGET_EXPANDED_KEY = "wishlist-budget-expanded"

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
    private var budgetExpanded = localStorage.getItem(BUDGET_EXPANDED_KEY) != "false"
    private var budgetResult: BudgetPickResult? = null

    private var mediaTypeOptions: List<String> = emptyList()
    private var distributorOptions: List<String> = emptyList()
    private var tagOptions: List<String> = emptyList()
    private var isLoading = false
    private var isRefreshingAll = false
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
            syncBudgetPicks()
            renderSummary()
            renderBudgetPicker()
            renderFilterOptions()
            renderResults()
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
            onOpenRelease = { releaseId -> ReleaseDetail(container, releaseId = releaseId, onBack = { show() }).show() }
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

    private fun refreshAllPrices() {
        if (isRefreshingAll) return
        isRefreshingAll = true
        renderResults()
        mainScope.launch {
            try {
                val result = refreshAllWishlistPrices()
                val message = buildString {
                    append("Checked ${result.checked} item(s): ${result.priceChanges} price change(s) recorded.")
                    if (result.skipped > 0) append("\n\n${result.skipped} item(s) were checked very recently and were left alone.")
                    if (result.failed > 0) append("\n\n${result.failed} failed:\n" + result.errors.joinToString("\n"))
                }
                alertDialog.show(title = "Prices refreshed", message = message)
            } catch (e: Exception) {
                alertDialog.show(title = "Error", message = e.message ?: "Failed to refresh prices.")
            } finally {
                isRefreshingAll = false
                loadAll()
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
                    alertDialog.show(title = "Price check failed", message = response.error ?: "blu-ray.com could not be reached.")
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
                            style = outlineButtonStyle("#1a73e8") + " height: 40px;"
                            span { classes = setOf("mdi", "mdi-refresh"); style = "font-size: 18px;" }
                            +"Refresh all prices"
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
            }
        }
        renderBudgetPicker()
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

    /** Items a roll can draw from: still wanted, and with a price to spend. */
    private fun budgetCandidates(): List<WishlistItem> =
        items.filter { it.status == WishlistStatus.WISHLIST && it.currentPrice != null }

    /** A whole-dollar budget shows as "100" rather than "100.0". */
    private fun formatBudgetForInput(amount: Double): String =
        if (amount == amount.toLong().toDouble()) amount.toLong().toString() else formatMoney(amount).removePrefix("$")

    private fun roll() {
        budgetResult = pickWithinBudget(budgetCandidates(), budgetCount, budgetAmount)
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
                            +"Randomly picks items from your wishlist that fit a budget"
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
                style = "flex: 0 1 150px; min-width: 130px;"
                formLabel("How many items")
                input(type = InputType.number) {
                    value = budgetCount.toString()
                    attributes["min"] = "1"
                    attributes["max"] = "25"
                    attributes["step"] = "1"
                    style = formInputStyle()
                    onInputFunction = { event ->
                        val entered = (event.target as HTMLInputElement).value.toIntOrNull()
                        if (entered != null && entered in 1..25) {
                            budgetCount = entered
                            localStorage.setItem(BUDGET_COUNT_KEY, entered.toString())
                        }
                    }
                }
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
                    "None of your wishlist items have a price yet, so there is nothing to pick from."
                } else {
                    "Drawing from $candidateCount priced wishlist item${if (candidateCount == 1) "" else "s"}, whatever the filters below are set to."
                }
            }
            return
        }

        if (result.picks.isEmpty()) {
            div {
                style = "margin-top: 14px; padding: 16px; background-color: #fef7e0; border-radius: 8px; font-size: 14px; color: #b06000;"
                +when {
                    result.eligibleCount == 0 ->
                        "None of your wishlist items have a price yet, so there is nothing to pick from."
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

        if (!result.isComplete) {
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
            div {
                style = "font-size: 12px; color: #80868b; margin-top: 4px;"
                +"About ${formatMoney(result.total + computeTax(result.total, defaultTaxRate))} with ${formatPercent(defaultTaxRate)} tax, before shipping."
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
        }
    }

    private fun FlowContent.renderFilters() {
        div {
            style = """
                background-color: #f8f9fa; padding: 20px; border-radius: 12px; margin-bottom: 24px; border: 1px solid #e8eaed;
                display: flex; flex-wrap: wrap; gap: 16px; align-items: flex-end;
            """.trimIndent()

            div {
                style = "flex: 2 1 240px; min-width: 200px;"
                formLabel("Search")
                input(type = InputType.text) {
                    value = searchText
                    placeholder = "Title, film, distributor, tag or note"
                    style = formInputStyle()
                    onInputFunction = { event -> searchText = (event.target as HTMLInputElement).value }
                    onChangeFunction = { reload() }
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
                span { +"${visible.size} item${if (visible.size == 1) "" else "s"}" }
                if (isRefreshingAll || isLoading) {
                    span {
                        style = "display: inline-flex; align-items: center; gap: 6px; color: #1a73e8;"
                        span { classes = setOf("mdi", "mdi-loading", "mdi-spin"); style = "font-size: 16px;" }
                        +if (isRefreshingAll) "Checking blu-ray.com for every item..." else "Refreshing..."
                    }
                }
            }

            grouped(visible).forEach { (heading, groupItems) ->
                if (groupBy != GroupBy.NONE) {
                    div {
                        style = "display: flex; align-items: center; gap: 10px; margin: 24px 0 12px 0;"
                        h2 {
                            style = "font-family: 'Oswald', sans-serif; font-weight: 500; font-size: 18px; color: #202124; margin: 0; letter-spacing: 0.5px;"
                            +heading
                        }
                        span { style = chipStyle("#f1f3f4", "#5f6368"); +groupItems.size.toString() }
                        div { style = "flex: 1; height: 1px; background-color: #e8eaed;" }
                    }
                }
                div {
                    style = "display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 16px;"
                    groupItems.forEach { itemCard(it) }
                }
            }
        }
    }

    /**
     * Section the visible items. An item with several tags or formats appears
     * under each of them.
     */
    private fun grouped(visible: List<WishlistItem>): List<Pair<String, List<WishlistItem>>> {
        val today = todayIso()
        return when (groupBy) {
            GroupBy.NONE -> listOf("" to visible)
            GroupBy.STATUS -> WishlistStatus.entries.map { s -> statusLabel(s) to visible.filter { it.status == s } }
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
        div {
            style = """
                background-color: white; border: 1px solid #e8eaed; border-radius: 12px; overflow: hidden;
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
                if (!item.blurayComUrl.isNullOrBlank() && item.status != WishlistStatus.OWNED) {
                    if (item.id in refreshingIds) {
                        span {
                            style = "color: #1a73e8; padding: 6px; display: inline-flex;"
                            attributes["title"] = "Checking blu-ray.com..."
                            span { classes = setOf("mdi", "mdi-loading", "mdi-spin"); style = "font-size: 18px;" }
                        }
                    } else {
                        iconButton("mdi-refresh", "Check price on blu-ray.com") { quickRefresh(item) }
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
                span { style = "font-size: 12px; color: #5f6368;"; +("paid" + (purchase.vendor?.let { " at $it" } ?: "")) }
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
