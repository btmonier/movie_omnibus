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

private const val ORDER_EDIT_DIALOG_ID = "wishlist-order-edit-dialog"

/**
 * Corrects what was recorded about a checkout after the fact: the store,
 * order date, order number and notes shared by everything on it, and each
 * item's tracking URL. Prices, tax and shipping are left as they are.
 *
 * [orderIds] is usually one order. A group the wishlist page shows together
 * can span several, and then a field the orders disagree on starts blank and
 * is only changed on each of them if something is entered.
 */
class OrderEditDialog(
    private val container: Element,
    private val orderIds: List<Int>,
    private val onSaved: () -> Unit
) {
    private val alertDialog = AlertDialog(container)
    private var orders: List<Order> = emptyList()
    private var isSaving = false

    private val vendorVaries get() = varies { it.vendor }
    private val dateVaries get() = varies { it.orderDate }
    private val numberVaries get() = varies { it.orderNumber }
    private val notesVaries get() = varies { it.notes }

    private fun id(field: String) = "$ORDER_EDIT_DIALOG_ID-$field"

    fun show() {
        val ids = orderIds.distinct()
        if (ids.isEmpty()) return
        mainScope.launch {
            try {
                StoreOptions.ensureLoaded()
                orders = ids.map { fetchOrder(it) }
                render()
            } catch (e: Exception) {
                alertDialog.show(title = "Error", message = e.message ?: "Failed to load the order.")
            }
        }
    }

    fun close() {
        document.getElementById(ORDER_EDIT_DIALOG_ID)?.remove()
    }

    private fun normalized(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    private fun varies(field: (Order) -> String?): Boolean =
        orders.map { normalized(field(it))?.lowercase() }.distinct().size > 1

    /** The value every order has, or null when they disagree. */
    private fun shared(field: (Order) -> String?): String? =
        if (varies(field)) null else orders.firstNotNullOfOrNull { normalized(field(it)) }

    private val variesHint = "Differs between the orders - leave blank to keep each"

    private fun render() {
        close()
        val lines = orders.flatMap { it.lines }.filter { it.purchaseId != null }
        container.append {
            div {
                id = ORDER_EDIT_DIALOG_ID
                style = modalOverlayStyle(1450)
                onClickFunction = { event ->
                    if (event.target == document.getElementById(ORDER_EDIT_DIALOG_ID)) close()
                }
                div {
                    style = modalPanelStyle(640)

                    h2 {
                        style = "margin: 0 0 4px 0; color: #202124; font-size: 20px; display: flex; align-items: center; gap: 10px;"
                        span { classes = setOf("mdi", "mdi-receipt-text-outline"); style = "color: #1a73e8; font-size: 24px;" }
                        +"Edit order"
                    }
                    p {
                        style = "margin: 0 0 20px 0; color: #5f6368; font-size: 14px;"
                        +if (orders.size > 1) {
                            "These ${lines.size} items were recorded as ${orders.size} separate orders. " +
                                "What you change here is changed on each of them; prices, tax and shipping are not touched."
                        } else {
                            "Shared by the ${lines.size} item${if (lines.size == 1) "" else "s"} on this order. " +
                                "Prices, tax and shipping are not touched."
                        }
                    }

                    div {
                        style = "display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px;"
                        div {
                            formLabel("Vendor")
                            storeSelect(
                                idPrefix = id("vendor"),
                                stores = StoreOptions.names,
                                selected = shared { it.vendor },
                                emptyLabel = if (vendorVaries) "Differs - keep each" else "Amazon, Criterion, local shop..."
                            )
                        }
                        div {
                            formLabel("Order date")
                            input(type = InputType.date) {
                                id = id("order-date")
                                value = shared { it.orderDate }.orEmpty()
                                style = formInputStyle()
                                if (dateVaries) attributes["title"] = variesHint
                            }
                        }
                        div {
                            formLabel("Order number")
                            input(type = InputType.text) {
                                id = id("order-number")
                                value = shared { it.orderNumber }.orEmpty()
                                placeholder = if (numberVaries) variesHint else "As on the receipt"
                                style = formInputStyle()
                            }
                        }
                    }
                    if (vendorVaries || dateVaries || numberVaries || notesVaries) {
                        p {
                            style = "margin: 6px 0 0 0; font-size: 12px; color: #80868b;"
                            +"Fields the orders disagree on start blank; leave them blank to keep each order's own."
                        }
                    }

                    div {
                        style = "margin-top: 12px;"
                        formLabel("Notes")
                        textArea {
                            id = id("notes")
                            rows = "2"
                            style = formInputStyle() + " resize: vertical;"
                            if (notesVaries) placeholder = variesHint
                            +shared { it.notes }.orEmpty()
                        }
                    }

                    hr { style = "border: none; border-top: 1px solid #e8eaed; margin: 20px 0;" }
                    h3 {
                        style = "margin: 0 0 4px 0; font-size: 15px; color: #202124; display: flex; align-items: center; gap: 8px;"
                        span { classes = setOf("mdi", "mdi-truck-outline"); style = "color: #1a73e8; font-size: 18px;" }
                        +"Tracking"
                    }
                    p {
                        style = "margin: 0 0 10px 0; font-size: 12px; color: #80868b;"
                        +"Items sharing a tracking URL are shown as one shipment. An order that arrives in several boxes can have a different one per item."
                    }
                    if (lines.size > 1) {
                        div {
                            style = "margin-bottom: 10px;"
                            formLabel("Same tracking URL for every item")
                            input(type = InputType.url) {
                                id = id("tracking-all")
                                placeholder = "https://"
                                style = formInputStyle()
                                onInputFunction = {
                                    val value = (document.getElementById(id("tracking-all")) as? HTMLInputElement)?.value.orEmpty()
                                    lines.forEach { line ->
                                        (document.getElementById(id("tracking-${line.purchaseId}")) as? HTMLInputElement)?.value = value
                                    }
                                }
                            }
                        }
                    }
                    div {
                        style = "display: flex; flex-direction: column; gap: 6px;"
                        lines.forEach { line ->
                            div {
                                style = "display: flex; align-items: center; gap: 12px; padding: 8px 12px; background-color: #f8f9fa; border-radius: 6px; flex-wrap: wrap;"
                                div {
                                    style = "flex: 1 1 180px; min-width: 0;"
                                    div {
                                        style = "font-size: 14px; color: #202124; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;"
                                        +(line.title ?: "Untitled release")
                                    }
                                    div {
                                        style = "font-size: 12px; color: #80868b; margin-top: 2px;"
                                        +listOfNotNull(
                                            formatMoney(line.subtotal),
                                            line.shippedDate?.let { "shipped ${formatDate(it)}" },
                                            line.receivedDate?.let { "received ${formatDate(it)}" }
                                        ).joinToString(" · ")
                                    }
                                }
                                input(type = InputType.url) {
                                    id = id("tracking-${line.purchaseId}")
                                    value = line.trackingUrl.orEmpty()
                                    placeholder = "Tracking URL"
                                    style = formInputStyle() + " flex: 2 1 220px; width: auto;"
                                }
                            }
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
                            style = primaryButtonStyle("#1a73e8")
                            span { classes = setOf("mdi", "mdi-content-save-outline"); style = "font-size: 18px;" }
                            +"Save"
                            onClickFunction = { save() }
                        }
                    }
                }
            }
        }
    }

    private fun save() {
        if (isSaving) return

        // A field the orders disagree on is only written when something was
        // entered; otherwise an empty field clears it, which is how a wrong
        // order number is removed.
        fun pick(entered: String?, varies: Boolean, own: String?): String? =
            if (varies && entered == null) own else entered

        val vendor = readStoreSelection(id("vendor"))
        val orderDate = textValue(id("order-date"))
        val orderNumber = textValue(id("order-number"))
        val notes = (document.getElementById(id("notes")) as? HTMLTextAreaElement)?.value?.trim()?.takeIf { it.isNotEmpty() }

        val updated = orders.map { order ->
            order.copy(
                vendor = pick(vendor, vendorVaries, order.vendor),
                orderDate = pick(orderDate, dateVaries, order.orderDate),
                orderNumber = pick(orderNumber, numberVaries, order.orderNumber),
                notes = pick(notes, notesVaries, order.notes),
                lines = order.lines.map { line ->
                    val input = line.purchaseId?.let { document.getElementById(id("tracking-$it")) as? HTMLInputElement }
                    // Blank is sent as blank, which clears the line's tracking URL
                    if (input == null) line else line.copy(trackingUrl = input.value.trim())
                }
            )
        }

        isSaving = true
        val button = document.getElementById(id("save")) as? HTMLButtonElement
        button?.disabled = true
        mainScope.launch {
            try {
                updated.forEach { order -> updateOrder(order.id!!, order) }
                close()
                onSaved()
            } catch (e: Exception) {
                isSaving = false
                button?.disabled = false
                alertDialog.show(title = "Error", message = e.message ?: "Failed to save the order.")
            }
        }
    }

    private fun textValue(elementId: String): String? =
        (document.getElementById(elementId) as? HTMLInputElement)?.value?.trim()?.takeIf { it.isNotEmpty() }
}
