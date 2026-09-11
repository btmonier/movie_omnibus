package org.btmonier

import kotlinx.html.FlowContent
import kotlinx.html.label
import kotlinx.html.span
import kotlinx.html.style
import kotlin.js.Date
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Small formatting and styling helpers shared by the wishlist screens.
 */

/** "$31.32" style, always two decimals. */
fun formatMoney(amount: Double?): String {
    if (amount == null) return "—"
    val cents = (abs(amount) * 100).roundToLong()
    val whole = cents / 100
    val frac = (cents % 100).toString().padStart(2, '0')
    val sign = if (amount < 0) "-" else ""
    return "$sign\$$whole.$frac"
}

/** "6%" or "6.5%" from a rate such as 0.06. */
fun formatPercent(rate: Double): String {
    val pct = rate * 100
    val rounded = (pct * 100).roundToLong() / 100.0
    return if (rounded == rounded.toLong().toDouble()) "${rounded.toLong()}%" else "$rounded%"
}

/** Today as an ISO date in local time. */
fun todayIso(): String {
    val now = Date()
    val month = (now.getMonth() + 1).toString().padStart(2, '0')
    val day = now.getDate().toString().padStart(2, '0')
    return "${now.getFullYear()}-$month-$day"
}

/** "Sep 5, 2026" from an ISO date or datetime, or the input when unparseable. */
fun formatDate(iso: String?): String {
    if (iso.isNullOrBlank()) return "—"
    val datePart = iso.take(10)
    val parts = datePart.split("-")
    if (parts.size != 3) return iso
    val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val month = parts[1].toIntOrNull()?.let { months.getOrNull(it - 1) } ?: return iso
    return "$month ${parts[2].toIntOrNull() ?: parts[2]}, ${parts[0]}"
}

fun statusLabel(status: WishlistStatus): String = when (status) {
    WishlistStatus.WISHLIST -> "Wishlist"
    WishlistStatus.ORDERED -> "Ordered"
    WishlistStatus.SHIPPED -> "Shipped"
    WishlistStatus.OWNED -> "Owned"
}

/** Background and text color for a status chip. */
fun statusColors(status: WishlistStatus): Pair<String, String> = when (status) {
    WishlistStatus.WISHLIST -> "#fce8f3" to "#b3157a"
    WishlistStatus.ORDERED -> "#fef7e0" to "#b06000"
    WishlistStatus.SHIPPED -> "#e8f0fe" to "#1a73e8"
    WishlistStatus.OWNED -> "#e6f4ea" to "#188038"
}

fun statusIcon(status: WishlistStatus): String = when (status) {
    WishlistStatus.WISHLIST -> "mdi-heart-outline"
    WishlistStatus.ORDERED -> "mdi-cart-outline"
    WishlistStatus.SHIPPED -> "mdi-truck-outline"
    WishlistStatus.OWNED -> "mdi-check-circle-outline"
}

/** Button text for advancing to a status. */
fun advanceLabel(target: WishlistStatus): String = when (target) {
    WishlistStatus.WISHLIST -> "Back to wishlist"
    WishlistStatus.ORDERED -> "Mark as ordered"
    WishlistStatus.SHIPPED -> "Mark as shipped"
    WishlistStatus.OWNED -> "Mark as owned"
}

fun priorityLabel(priority: WishlistPriority): String = when (priority) {
    WishlistPriority.HIGH -> "High"
    WishlistPriority.MEDIUM -> "Medium"
    WishlistPriority.LOW -> "Low"
}

fun priorityColors(priority: WishlistPriority): Pair<String, String> = when (priority) {
    WishlistPriority.HIGH -> "#fce8e6" to "#c5221f"
    WishlistPriority.MEDIUM -> "#f1f3f4" to "#5f6368"
    WishlistPriority.LOW -> "#f1f3f4" to "#80868b"
}

fun priceSourceLabel(source: PriceSource): String = when (source) {
    PriceSource.BLURAY_LIST -> "List price"
    PriceSource.AMAZON -> "Amazon"
    PriceSource.NEW_FROM -> "New from"
    PriceSource.USED_FROM -> "Used from"
    PriceSource.MANUAL -> "Logged"
}

fun FlowContent.statusChip(status: WishlistStatus) {
    val (bg, fg) = statusColors(status)
    span {
        style = chipStyle(bg, fg)
        +statusLabel(status)
    }
}

fun FlowContent.priorityChip(priority: WishlistPriority) {
    if (priority == WishlistPriority.MEDIUM) return
    val (bg, fg) = priorityColors(priority)
    span {
        style = chipStyle(bg, fg)
        +"${priorityLabel(priority)} priority"
    }
}

fun FlowContent.tagChip(tag: String) {
    span {
        style = chipStyle("#f3e8fd", "#7627bb")
        +tag
    }
}

fun FlowContent.formLabel(text: String, forId: String? = null) {
    label {
        style = "display: block; margin-bottom: 6px; font-weight: 500; font-size: 13px; color: #5f6368;"
        forId?.let { attributes["for"] = it }
        +text
    }
}

fun formInputStyle(): String = """
    width: 100%;
    padding: 10px 12px;
    font-size: 14px;
    border: 1px solid #dadce0;
    border-radius: 4px;
    box-sizing: border-box;
    background-color: white;
    font-family: 'Roboto', arial, sans-serif;
""".trimIndent()

fun primaryButtonStyle(color: String = "#1a73e8"): String = """
    padding: 10px 20px;
    font-size: 14px;
    cursor: pointer;
    background-color: $color;
    color: white;
    border: none;
    border-radius: 4px;
    font-weight: 500;
    display: inline-flex;
    align-items: center;
    gap: 6px;
""".trimIndent()

fun secondaryButtonStyle(): String = """
    padding: 10px 16px;
    font-size: 14px;
    cursor: pointer;
    background-color: #f1f3f4;
    color: #202124;
    border: none;
    border-radius: 4px;
    font-weight: 500;
    display: inline-flex;
    align-items: center;
    gap: 6px;
""".trimIndent()

fun outlineButtonStyle(color: String = "#3c4043"): String = """
    padding: 8px 12px;
    font-size: 13px;
    cursor: pointer;
    background-color: white;
    color: $color;
    border: 1px solid #dadce0;
    border-radius: 4px;
    font-weight: 500;
    display: inline-flex;
    align-items: center;
    gap: 6px;
""".trimIndent()

fun modalOverlayStyle(zIndex: Int = 1400): String = """
    position: fixed;
    top: 0;
    left: 0;
    width: 100%;
    height: 100%;
    background-color: rgba(0, 0, 0, 0.5);
    display: flex;
    justify-content: center;
    align-items: center;
    z-index: $zIndex;
""".trimIndent()

fun modalPanelStyle(maxWidth: Int = 640): String = """
    background-color: white;
    padding: 24px;
    border-radius: 8px;
    max-width: ${maxWidth}px;
    width: 94%;
    max-height: 90vh;
    overflow-y: auto;
    box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
    font-family: 'Google Sans', 'Roboto', arial, sans-serif;
    box-sizing: border-box;
""".trimIndent()

/**
 * Where to look up an ASIN on an external price tracker.
 */
fun camelCamelCamelUrl(asin: String): String = "https://camelcamelcamel.com/product/${asin.trim()}"

fun amazonUrl(asin: String): String = "https://www.amazon.com/dp/${asin.trim()}"
