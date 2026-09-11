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

private const val PURCHASE_DIALOG_ID = "purchase-dialog"

/**
 * A modal wrapping [PurchaseFormFields], used to record or edit what was paid
 * for a release from its detail page.
 */
class PurchaseDialog(
    private val container: Element,
    private val existing: Purchase?,
    private val defaultTaxRate: Double,
    private val subtitle: String? = null,
    private val onSave: suspend (Purchase) -> Unit
) {
    private val alertDialog = AlertDialog(container)
    private val fields = PurchaseFormFields(PURCHASE_DIALOG_ID, existing, defaultTaxRate)
    private var isSaving = false

    fun show() {
        close()
        container.append {
            div {
                id = PURCHASE_DIALOG_ID
                style = modalOverlayStyle(1450)
                onClickFunction = { event ->
                    if (event.target == document.getElementById(PURCHASE_DIALOG_ID)) close()
                }
                div {
                    style = modalPanelStyle(620)
                    h2 {
                        style = "margin: 0 0 4px 0; color: #202124; font-size: 20px; display: flex; align-items: center; gap: 10px;"
                        span { classes = setOf("mdi", "mdi-receipt-text-outline"); style = "color: #1a73e8; font-size: 24px;" }
                        +if (existing == null) "Record purchase" else "Edit purchase"
                    }
                    p {
                        style = "margin: 0 0 20px 0; color: #5f6368; font-size: 14px;"
                        +(subtitle ?: "What you paid for this release, tax included.")
                    }
                    with(fields) { render(showHeading = false) }

                    div {
                        style = "display: flex; justify-content: flex-end; gap: 12px; margin-top: 24px;"
                        button {
                            style = secondaryButtonStyle()
                            +"Cancel"
                            onClickFunction = { close() }
                        }
                        button {
                            id = "$PURCHASE_DIALOG_ID-save"
                            style = primaryButtonStyle()
                            span { classes = setOf("mdi", "mdi-content-save"); style = "font-size: 18px;" }
                            +"Save"
                            onClickFunction = { save() }
                        }
                    }
                }
            }
        }
    }

    fun close() {
        document.getElementById(PURCHASE_DIALOG_ID)?.remove()
    }

    private fun save() {
        if (isSaving) return
        val purchase = fields.read()
        if (purchase == null) {
            alertDialog.show(title = "Subtotal required", message = "Enter what you paid before tax and shipping.")
            return
        }
        isSaving = true
        val button = document.getElementById("$PURCHASE_DIALOG_ID-save") as? HTMLButtonElement
        button?.disabled = true
        mainScope.launch {
            try {
                onSave(purchase)
                close()
            } catch (e: Exception) {
                alertDialog.show(title = "Error", message = e.message ?: "Failed to save the purchase.")
            } finally {
                isSaving = false
                button?.disabled = false
            }
        }
    }
}

/**
 * The fields for recording what was paid for a physical unit: vendor, dates,
 * subtotal, tax (rate pre-filled from the server's default, amount computed
 * live but editable), shipping, and a running total.
 *
 * Rendered into any form with [render], read back with [read]. Ids are
 * prefixed so two instances can never collide.
 */
class PurchaseFormFields(
    private val idPrefix: String,
    private val initial: Purchase?,
    private val defaultTaxRate: Double
) {
    private var taxAmountEdited = initial?.taxAmount != null &&
        initial.taxAmount != computeTax(initial.subtotal, initial.taxRate ?: defaultTaxRate)

    private fun id(field: String) = "$idPrefix-$field"

    fun FlowContent.render(showHeading: Boolean = true) {
        if (showHeading) {
            h3 {
                style = "margin: 0 0 12px 0; font-size: 15px; color: #202124; display: flex; align-items: center; gap: 8px;"
                span { classes = setOf("mdi", "mdi-receipt-text-outline"); style = "color: #1a73e8; font-size: 18px;" }
                +"Purchase"
            }
        }

        div {
            style = "display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 12px;"

            field("Vendor") {
                input(type = InputType.text) {
                    id = id("vendor")
                    value = initial?.vendor ?: ""
                    placeholder = "Amazon, Criterion, local shop..."
                    style = formInputStyle()
                }
            }
            field("Order date") {
                input(type = InputType.date) {
                    id = id("order-date")
                    value = initial?.orderDate ?: todayIso()
                    style = formInputStyle()
                }
            }
            field("Order number") {
                input(type = InputType.text) {
                    id = id("order-number")
                    value = initial?.orderNumber ?: ""
                    style = formInputStyle()
                }
            }
            field("Tracking URL") {
                input(type = InputType.url) {
                    id = id("tracking-url")
                    value = initial?.trackingUrl ?: ""
                    placeholder = "https://"
                    style = formInputStyle()
                }
            }
        }

        div {
            style = "display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap: 12px; margin-top: 12px;"

            field("Subtotal") {
                moneyInput(id("subtotal"), initial?.subtotal) { recalc() }
            }
            field("Tax rate") {
                div {
                    style = "position: relative;"
                    input(type = InputType.number) {
                        id = id("tax-rate")
                        value = formatRateForInput(initial?.taxRate ?: defaultTaxRate)
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
            field("Tax") {
                moneyInput(id("tax-amount"), initial?.taxAmount ?: initial?.let { computeTax(it.subtotal, it.taxRate ?: defaultTaxRate) }) {
                    taxAmountEdited = true
                    recalc(recomputeTax = false)
                }
            }
            field("Shipping") {
                moneyInput(id("shipping"), initial?.shipping ?: 0.0) { recalc(recomputeTax = false) }
            }
        }

        div {
            style = """
                margin-top: 12px;
                padding: 12px 14px;
                background-color: #f8f9fa;
                border-radius: 6px;
                display: flex;
                justify-content: space-between;
                align-items: center;
                font-size: 14px;
                color: #202124;
            """.trimIndent()
            span { +"Total" }
            span {
                id = id("total")
                style = "font-weight: 600; font-size: 18px;"
                +formatMoney(initial?.total ?: 0.0)
            }
        }

        div {
            style = "margin-top: 12px;"
            formLabel("Notes")
            textArea {
                id = id("notes")
                rows = "2"
                style = formInputStyle() + " resize: vertical;"
                +(initial?.notes ?: "")
            }
        }
    }

    /**
     * The purchase as entered, with the tax amount left null when it was not
     * hand-edited so the server computes it from the rate. Returns null when
     * the subtotal is missing or not a number.
     */
    fun read(): Purchase? {
        val subtotal = numberValue(id("subtotal")) ?: return null
        val ratePercent = numberValue(id("tax-rate")) ?: (defaultTaxRate * 100)
        val rate = ratePercent / 100.0
        val taxAmount = numberValue(id("tax-amount"))

        return Purchase(
            subtotal = subtotal,
            taxRate = rate,
            taxAmount = if (taxAmountEdited) taxAmount else null,
            shipping = numberValue(id("shipping")) ?: 0.0,
            vendor = textValue(id("vendor")).takeIf { it.isNotBlank() },
            orderDate = textValue(id("order-date")).takeIf { it.isNotBlank() },
            orderNumber = textValue(id("order-number")).takeIf { it.isNotBlank() },
            trackingUrl = textValue(id("tracking-url")).takeIf { it.isNotBlank() },
            shippedDate = initial?.shippedDate,
            receivedDate = initial?.receivedDate,
            notes = (document.getElementById(id("notes")) as? HTMLTextAreaElement)?.value?.takeIf { it.isNotBlank() }
        )
    }

    /** True when a subtotal has been entered. */
    fun hasSubtotal(): Boolean = numberValue(id("subtotal")) != null

    private fun recalc(recomputeTax: Boolean = true) {
        val subtotal = numberValue(id("subtotal")) ?: 0.0
        val rate = (numberValue(id("tax-rate")) ?: (defaultTaxRate * 100)) / 100.0

        if (recomputeTax && !taxAmountEdited) {
            (document.getElementById(id("tax-amount")) as? HTMLInputElement)?.value = formatMoneyForInput(computeTax(subtotal, rate))
        }
        val tax = numberValue(id("tax-amount")) ?: computeTax(subtotal, rate)
        val shipping = numberValue(id("shipping")) ?: 0.0

        document.getElementById(id("total"))?.textContent = formatMoney(roundToCents(subtotal + tax + shipping))
    }

    private fun FlowContent.field(labelText: String, content: FlowContent.() -> Unit) {
        div {
            formLabel(labelText)
            content()
        }
    }

    private fun FlowContent.moneyInput(inputId: String, value: Double?, onInput: () -> Unit) {
        div {
            style = "position: relative;"
            span {
                style = "position: absolute; left: 10px; top: 50%; transform: translateY(-50%); color: #80868b; font-size: 13px;"
                +"$"
            }
            input(type = InputType.number) {
                id = inputId
                this.value = value?.let { formatMoneyForInput(it) } ?: ""
                attributes["step"] = "0.01"
                attributes["min"] = "0"
                placeholder = "0.00"
                style = formInputStyle() + " padding-left: 22px;"
                onInputFunction = { onInput() }
            }
        }
    }

    private fun numberValue(elementId: String): Double? =
        (document.getElementById(elementId) as? HTMLInputElement)?.value?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()

    private fun textValue(elementId: String): String =
        (document.getElementById(elementId) as? HTMLInputElement)?.value?.trim() ?: ""

    private fun formatMoneyForInput(value: Double): String = formatMoney(value).removePrefix("$")

    private fun formatRateForInput(rate: Double): String {
        val pct = roundToCents(rate * 100)
        return if (pct == pct.toLong().toDouble()) pct.toLong().toString() else pct.toString()
    }
}
