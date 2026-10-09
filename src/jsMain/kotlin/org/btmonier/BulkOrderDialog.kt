package org.btmonier

import kotlinx.browser.document
import kotlinx.coroutines.launch
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.onClickFunction
import kotlinx.html.js.onInputFunction
import org.w3c.dom.Element
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLTextAreaElement

private const val BULK_ORDER_DIALOG_ID = "wishlist-bulk-order-dialog"

/**
 * Records several wishlist items bought in one checkout: the store, dates,
 * tax and shipping are entered once for the order, and each item only needs
 * what was paid for it. Items still on the wishlist move to Ordered.
 *
 * [skipped] is how many selected items were left out because they are
 * already further along, so the dialog can say so.
 */
class BulkOrderDialog(
    private val container: Element,
    items: List<WishlistItem>,
    private val defaultTaxRate: Double,
    private val skipped: Int = 0,
    private val onDone: (List<WishlistItem>) -> Unit
) {
    private val alertDialog = AlertDialog(container)
    private val lines: MutableList<WishlistItem> = items.filter { it.id != null }.toMutableList()
    private val subtotals: MutableMap<Int, String> = lines.associate { item ->
        item.id!! to (item.currentPrice?.let { formatMoneyForInput(it) } ?: "")
    }.toMutableMap()
    private var taxEdited = false
    private var isSaving = false

    private fun id(field: String) = "$BULK_ORDER_DIALOG_ID-$field"

    fun show() {
        close()
        container.append {
            div {
                id = BULK_ORDER_DIALOG_ID
                style = modalOverlayStyle(1450)
                onClickFunction = { event ->
                    if (event.target == document.getElementById(BULK_ORDER_DIALOG_ID)) close()
                }
                div {
                    style = modalPanelStyle(760)

                    h2 {
                        style = "margin: 0 0 4px 0; color: #202124; font-size: 20px; display: flex; align-items: center; gap: 10px;"
                        span { classes = setOf("mdi", statusIcon(WishlistStatus.ORDERED)); style = "color: ${statusColors(WishlistStatus.ORDERED).second}; font-size: 24px;" }
                        +"Record order"
                    }
                    p {
                        id = id("subtitle")
                        style = "margin: 0 0 20px 0; color: #5f6368; font-size: 14px;"
                    }

                    sectionHeading("mdi-receipt-text-outline", "Order")
                    div {
                        style = "display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px;"
                        div {
                            formLabel("Vendor")
                            storeSelect(
                                idPrefix = id("vendor"),
                                stores = StoreOptions.names,
                                selected = sharedStore(lines),
                                emptyLabel = "Amazon, Criterion, local shop..."
                            )
                        }
                        div {
                            formLabel("Order date")
                            input(type = InputType.date) {
                                id = id("order-date")
                                value = todayIso()
                                style = formInputStyle()
                            }
                        }
                        div {
                            formLabel("Order number")
                            input(type = InputType.text) {
                                id = id("order-number")
                                style = formInputStyle()
                            }
                        }
                        div {
                            formLabel("Tracking URL")
                            input(type = InputType.url) {
                                id = id("tracking-url")
                                placeholder = "https://"
                                style = formInputStyle()
                            }
                        }
                    }

                    hr { style = "border: none; border-top: 1px solid #e8eaed; margin: 20px 0;" }
                    sectionHeading("mdi-format-list-bulleted", "Items")
                    p {
                        style = "margin: -6px 0 10px 0; font-size: 12px; color: #80868b;"
                        +"Pre-filled from each item's latest tracked price. Adjust to match your receipt, before tax and shipping."
                    }
                    div { id = id("lines") }

                    hr { style = "border: none; border-top: 1px solid #e8eaed; margin: 20px 0;" }
                    sectionHeading("mdi-cash-multiple", "Tax and shipping for the whole order")
                    div {
                        style = "display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 12px;"
                        div {
                            formLabel("Tax rate")
                            div {
                                style = "position: relative;"
                                input(type = InputType.number) {
                                    id = id("tax-rate")
                                    value = formatRateForInput(defaultTaxRate)
                                    attributes["step"] = "0.01"
                                    attributes["min"] = "0"
                                    attributes["max"] = "100"
                                    style = formInputStyle() + " padding-right: 28px;"
                                    onInputFunction = { recalc() }
                                }
                                span {
                                    style = "position: absolute; right: 10px; top: 50%; transform: translateY(-50%); color: #80868b; font-size: 13px;"
                                    +"%"
                                }
                            }
                        }
                        div {
                            formLabel("Tax")
                            bulkMoneyInput(id("tax-amount"), null) {
                                // Clearing the field goes back to computing it
                                taxEdited = numberValue(id("tax-amount")) != null
                                recalc(recomputeTax = false)
                            }
                        }
                        div {
                            formLabel("Shipping")
                            bulkMoneyInput(id("shipping"), 0.0) { recalc(recomputeTax = false) }
                        }
                    }
                    p {
                        style = "margin: 8px 0 0 0; font-size: 12px; color: #80868b;"
                        +"Tax is computed once on the order's subtotal unless you type it in. Shipping is not taxed. Each item is shown its share of both, split by price."
                    }

                    div { id = id("totals") }

                    div {
                        style = "margin-top: 12px;"
                        formLabel("Notes")
                        textArea {
                            id = id("notes")
                            rows = "2"
                            style = formInputStyle() + " resize: vertical;"
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
                            id = id("save")
                            style = primaryButtonStyle(statusColors(WishlistStatus.ORDERED).second)
                            span { classes = setOf("mdi", statusIcon(WishlistStatus.ORDERED)); style = "font-size: 18px;" }
                            span { id = id("save-label") }
                            onClickFunction = { save() }
                        }
                    }
                }
            }
        }
        renderLines()
    }

    fun close() {
        document.getElementById(BULK_ORDER_DIALOG_ID)?.remove()
    }

    private fun FlowContent.sectionHeading(icon: String, text: String) {
        h3 {
            style = "margin: 0 0 12px 0; font-size: 15px; color: #202124; display: flex; align-items: center; gap: 8px;"
            span { classes = setOf("mdi", icon); style = "color: #1a73e8; font-size: 18px;" }
            +text
        }
    }

    private fun renderLines() {
        val target = document.getElementById(id("lines")) ?: return
        target.innerHTML = ""
        target.append {
            if (lines.isEmpty()) {
                div {
                    style = "padding: 12px; color: #80868b; font-size: 13px; background-color: #f8f9fa; border-radius: 6px; border: 1px dashed #dadce0;"
                    +"No items left on this order."
                }
                return@append
            }
            div {
                style = "display: flex; flex-direction: column; gap: 6px;"
                lines.forEach { item ->
                    val itemId = item.id!!
                    div {
                        style = "display: flex; align-items: center; gap: 12px; padding: 8px 12px; background-color: #f8f9fa; border-radius: 6px;"
                        val cover = item.displayImages().firstOrNull()?.imageUrl
                        if (cover != null) {
                            img(src = cover, alt = "") {
                                style = "width: 36px; height: 48px; object-fit: cover; border-radius: 3px; flex-shrink: 0;"
                            }
                        } else {
                            span {
                                classes = setOf("mdi", "mdi-disc")
                                style = "width: 36px; text-align: center; font-size: 24px; color: #bdc1c6; flex-shrink: 0;"
                            }
                        }
                        div {
                            style = "flex: 1; min-width: 0;"
                            div {
                                style = "font-size: 14px; color: #202124; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;"
                                +(item.title ?: "Untitled release")
                            }
                            div {
                                id = id("share-$itemId")
                                style = "font-size: 12px; color: #80868b; margin-top: 2px;"
                            }
                        }
                        div {
                            style = "width: 120px; flex-shrink: 0;"
                            bulkMoneyInput(id("subtotal-$itemId"), null, subtotals[itemId]) {
                                subtotals[itemId] = (document.getElementById(id("subtotal-$itemId")) as? HTMLInputElement)?.value.orEmpty()
                                recalc()
                            }
                        }
                        button {
                            style = "background: none; border: none; cursor: pointer; color: #5f6368; padding: 4px;"
                            attributes["title"] = "Leave off this order"
                            span { classes = setOf("mdi", "mdi-close"); style = "font-size: 18px;" }
                            onClickFunction = {
                                lines.removeAll { it.id == itemId }
                                subtotals.remove(itemId)
                                renderLines()
                            }
                        }
                    }
                }
            }
        }
        recalc()
    }

    private fun recalc(recomputeTax: Boolean = true) {
        val lineSubtotals = lines.map { subtotals[it.id!!]?.trim()?.toDoubleOrNull() ?: 0.0 }
        val subtotal = roundToCents(lineSubtotals.sum())
        val rate = (numberValue(id("tax-rate")) ?: (defaultTaxRate * 100)) / 100.0

        if (recomputeTax && !taxEdited) {
            (document.getElementById(id("tax-amount")) as? HTMLInputElement)?.value = formatMoneyForInput(computeTax(subtotal, rate))
        }
        val tax = orderTax(lineSubtotals, rate, if (taxEdited) numberValue(id("tax-amount")) else null)
        val shipping = roundToCents(numberValue(id("shipping")) ?: 0.0)
        val total = roundToCents(subtotal + tax + shipping)

        allocateOrderCosts(lineSubtotals, tax, shipping).forEachIndexed { index, (taxShare, shippingShare) ->
            val itemId = lines[index].id!!
            document.getElementById(id("share-$itemId"))?.textContent =
                "${formatMoney(roundToCents(lineSubtotals[index] + taxShare + shippingShare))} with its share of tax and shipping"
        }

        document.getElementById(id("subtitle"))?.textContent = buildString {
            append("${lines.size} item(s) bought in one checkout. Tax and shipping are entered once for the whole order.")
            if (skipped > 0) append(" $skipped selected item(s) are already ordered or further along and are left out.")
        }
        document.getElementById(id("save-label"))?.textContent = "Mark ${lines.size} ordered"
        (document.getElementById(id("save")) as? HTMLButtonElement)?.disabled = lines.isEmpty() || isSaving

        val totals = document.getElementById(id("totals")) ?: return
        totals.innerHTML = ""
        totals.append {
            div {
                style = """
                    margin-top: 12px; padding: 12px 14px; background-color: #f8f9fa; border-radius: 6px;
                    display: grid; grid-template-columns: 1fr auto; gap: 4px 16px; font-size: 14px; color: #202124;
                """.trimIndent()
                span { +"Subtotal" }; span { +formatMoney(subtotal) }
                span { +"Tax (${formatPercent(rate)})${if (taxEdited) ", entered" else ""}" }; span { +formatMoney(tax) }
                span { +"Shipping" }; span { +formatMoney(shipping) }
                span { style = "font-weight: 600;"; +"Order total" }
                span { style = "font-weight: 600; font-size: 18px;"; +formatMoney(total) }
            }
        }
    }

    private fun save() {
        if (isSaving || lines.isEmpty()) return

        val missing = lines.filter { subtotals[it.id!!]?.trim()?.toDoubleOrNull()?.takeIf { v -> v >= 0 } == null }
        if (missing.isNotEmpty()) {
            alertDialog.show(
                title = "Check the prices",
                message = "Enter what you paid for ${missing.joinToString(", ") { "\"${it.title ?: "Untitled release"}\"" }}, or take it off the order."
            )
            return
        }

        val rate = (numberValue(id("tax-rate")) ?: (defaultTaxRate * 100)) / 100.0
        val request = WishlistOrderRequest(
            items = lines.map { WishlistOrderItem(it.id!!, subtotals.getValue(it.id!!).trim().toDouble()) },
            vendor = readStoreSelection(id("vendor")),
            orderDate = textValue(id("order-date")),
            orderNumber = textValue(id("order-number")),
            trackingUrl = textValue(id("tracking-url")),
            taxRate = rate,
            taxAmount = if (taxEdited) numberValue(id("tax-amount")) else null,
            shipping = numberValue(id("shipping")) ?: 0.0,
            notes = (document.getElementById(id("notes")) as? HTMLTextAreaElement)?.value?.trim()?.takeIf { it.isNotEmpty() }
        )

        isSaving = true
        val button = document.getElementById(id("save")) as? HTMLButtonElement
        button?.disabled = true
        mainScope.launch {
            try {
                val response = createWishlistOrder(request)
                close()
                onDone(response.items)
            } catch (e: Exception) {
                isSaving = false
                button?.disabled = false
                alertDialog.show(title = "Error", message = e.message ?: "Failed to record the order.")
            }
        }
    }

    private fun numberValue(elementId: String): Double? =
        (document.getElementById(elementId) as? HTMLInputElement)?.value?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    private fun textValue(elementId: String): String? =
        (document.getElementById(elementId) as? HTMLInputElement)?.value?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * The store all of [items] are currently cheapest at, or null when they
 * disagree or any of them has no store to name.
 */
private fun sharedStore(items: List<WishlistItem>): String? {
    val stores = items.map { currentPriceStore(it) }
    val first = stores.firstOrNull() ?: return null
    return first.takeIf { stores.all { it.equals(first, ignoreCase = true) } }
}

private fun currentPriceStore(item: WishlistItem): String? = when (item.currentPriceSource) {
    PriceSource.AMAZON, PriceSource.NEW_FROM -> "Amazon"
    PriceSource.VENDOR, PriceSource.MANUAL -> derivePrices(item.priceHistory).current?.vendor?.takeIf { it.isNotBlank() }
    else -> null
}

private fun FlowContent.bulkMoneyInput(inputId: String, value: Double?, text: String? = null, onInput: () -> Unit) {
    div {
        style = "position: relative;"
        span {
            style = "position: absolute; left: 10px; top: 50%; transform: translateY(-50%); color: #80868b; font-size: 13px;"
            +"$"
        }
        input(type = InputType.number) {
            id = inputId
            this.value = text ?: value?.let { formatMoneyForInput(it) } ?: ""
            attributes["step"] = "0.01"
            attributes["min"] = "0"
            placeholder = "0.00"
            style = formInputStyle() + " padding-left: 22px;"
            onInputFunction = { onInput() }
        }
    }
}

private fun formatMoneyForInput(value: Double): String = formatMoney(value).removePrefix("$")

private fun formatRateForInput(rate: Double): String {
    val pct = roundToCents(rate * 100)
    return if (pct == pct.toLong().toDouble()) pct.toLong().toString() else pct.toString()
}
