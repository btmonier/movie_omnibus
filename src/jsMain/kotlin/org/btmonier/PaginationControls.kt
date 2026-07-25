package org.btmonier

import kotlinx.browser.document
import kotlinx.html.*
import kotlinx.html.js.onChangeFunction
import kotlinx.html.js.onClickFunction
import kotlinx.html.js.onKeyDownFunction
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.events.KeyboardEvent

private const val JUMP_INPUT_ID = "pagination-jump-input"

/** Below this many pages the numbered buttons already reach everything, so the jump box is clutter. */
private const val JUMP_INPUT_MIN_PAGES = 6

private const val ACCENT = "#1a73e8"
private const val ACCENT_HOVER = "#1765cc"
private const val DISABLED_BG = "#dadce0"
private const val DISABLED_FG = "#5f6368"
private const val BORDER = "#dadce0"
private const val TEXT = "#3c4043"

/** An entry in the rendered page strip: either a clickable page number or a gap marker. */
sealed interface PageItem {
    data class Page(val number: Int) : PageItem
    data object Ellipsis : PageItem
}

/**
 * Builds the page strip shown between the arrow buttons, e.g. page 9 of 47 with the
 * default radius yields `1 … 7 8 9 10 11 … 47`.
 *
 * The first and last page are always present so the ends of the collection stay one
 * click away; an [PageItem.Ellipsis] marks each gap between non-consecutive numbers.
 */
fun paginationWindow(currentPage: Int, totalPages: Int, radius: Int = 2): List<PageItem> {
    if (totalPages <= 1) return listOf(PageItem.Page(1))

    val page = currentPage.coerceIn(1, totalPages)
    val numbers = mutableSetOf(1, totalPages)
    for (candidate in (page - radius)..(page + radius)) {
        if (candidate in 1..totalPages) numbers.add(candidate)
    }

    val items = mutableListOf<PageItem>()
    var previous = 0
    for (number in numbers.sorted()) {
        if (previous != 0 && number - previous > 1) items.add(PageItem.Ellipsis)
        items.add(PageItem.Page(number))
        previous = number
    }
    return items
}

private fun arrowButtonStyle(enabled: Boolean, horizontalPadding: Int): String = """
    padding: 10px ${horizontalPadding}px;
    font-size: 14px;
    cursor: ${if (enabled) "pointer" else "not-allowed"};
    background-color: ${if (enabled) ACCENT else DISABLED_BG};
    color: ${if (enabled) "white" else DISABLED_FG};
    border: none;
    border-radius: 24px;
    font-weight: 500;
    transition: all 0.2s;
""".trimIndent()

private fun numberButtonStyle(active: Boolean): String = """
    min-width: 40px;
    padding: 10px 12px;
    font-size: 14px;
    cursor: ${if (active) "default" else "pointer"};
    background-color: ${if (active) ACCENT else "white"};
    color: ${if (active) "white" else TEXT};
    border: ${if (active) "none" else "1px solid $BORDER"};
    border-radius: 24px;
    font-weight: 500;
    transition: all 0.2s;
""".trimIndent()

private fun BUTTON.accentHover(enabled: Boolean) {
    if (!enabled) return
    attributes["onmouseover"] = "this.style.backgroundColor='$ACCENT_HOVER'; this.style.transform='scale(1.02)'"
    attributes["onmouseout"] = "this.style.backgroundColor='$ACCENT'; this.style.transform='scale(1)'"
}

private fun submitJump(input: HTMLInputElement?, totalPages: Int, onPageChange: (Int) -> Unit) {
    val requested = input?.value?.trim()?.toIntOrNull()
    input?.value = ""
    if (requested != null) onPageChange(requested.coerceIn(1, totalPages))
}

/**
 * Renders the movie list pagination bar: first/previous, a numbered page strip, next/last,
 * a jump-to-page box for long collections, and the items-per-page selector.
 *
 * [onPageChange] receives a 1-based page number that is already clamped to [totalPages].
 */
fun FlowContent.paginationControls(
    currentPage: Int,
    totalPages: Int,
    itemsPerPage: Int,
    pageSizeOptions: List<Int> = listOf(12, 24, 48, 96),
    onPageChange: (Int) -> Unit,
    onPageSizeChange: (Int) -> Unit,
) {
    val hasPrevious = currentPage > 1
    val hasNext = currentPage < totalPages

    div {
        style = """
            margin-top: 32px;
            display: flex;
            justify-content: center;
            align-items: center;
            gap: 8px;
            flex-wrap: wrap;
        """.trimIndent()

        if (totalPages > 1) {
            // First page
            button {
                style = arrowButtonStyle(hasPrevious, horizontalPadding = 12)
                disabled = !hasPrevious
                attributes["title"] = "First page"
                attributes["aria-label"] = "First page"
                accentHover(hasPrevious)
                span {
                    classes = setOf("mdi", "mdi-page-first")
                    style = "font-size: 18px;"
                }
                onClickFunction = { if (hasPrevious) onPageChange(1) }
            }

            // Previous page
            button {
                style = arrowButtonStyle(hasPrevious, horizontalPadding = 20)
                disabled = !hasPrevious
                accentHover(hasPrevious)
                span {
                    classes = setOf("mdi", "mdi-chevron-left")
                    style = "font-size: 18px;"
                }
                +"Previous"
                onClickFunction = { if (hasPrevious) onPageChange(currentPage - 1) }
            }

            // Numbered page strip
            paginationWindow(currentPage, totalPages).forEach { item ->
                when (item) {
                    is PageItem.Ellipsis -> span {
                        style = "padding: 10px 4px; font-size: 14px; color: $DISABLED_FG;"
                        attributes["aria-hidden"] = "true"
                        +"…"
                    }

                    is PageItem.Page -> {
                        val active = item.number == currentPage
                        button {
                            style = numberButtonStyle(active)
                            disabled = active
                            attributes["aria-label"] = "Page ${item.number}"
                            if (active) {
                                attributes["aria-current"] = "page"
                            } else {
                                attributes["onmouseover"] = "this.style.backgroundColor='#f1f3f4'"
                                attributes["onmouseout"] = "this.style.backgroundColor='white'"
                            }
                            +item.number.toString()
                            onClickFunction = { if (!active) onPageChange(item.number) }
                        }
                    }
                }
            }

            // Next page
            button {
                style = arrowButtonStyle(hasNext, horizontalPadding = 20)
                disabled = !hasNext
                accentHover(hasNext)
                +"Next"
                span {
                    classes = setOf("mdi", "mdi-chevron-right")
                    style = "font-size: 18px;"
                }
                onClickFunction = { if (hasNext) onPageChange(currentPage + 1) }
            }

            // Last page
            button {
                style = arrowButtonStyle(hasNext, horizontalPadding = 12)
                disabled = !hasNext
                attributes["title"] = "Last page"
                attributes["aria-label"] = "Last page"
                accentHover(hasNext)
                span {
                    classes = setOf("mdi", "mdi-page-last")
                    style = "font-size: 18px;"
                }
                onClickFunction = { if (hasNext) onPageChange(totalPages) }
            }
        }

        if (totalPages >= JUMP_INPUT_MIN_PAGES) {
            div {
                style = "margin-left: 16px; display: flex; align-items: center; gap: 8px;"
                span {
                    style = "font-size: 13px; color: $DISABLED_FG;"
                    +"Go to page:"
                }
                input(type = InputType.number) {
                    id = JUMP_INPUT_ID
                    style = """
                        width: 72px;
                        padding: 8px 12px;
                        font-size: 14px;
                        border: 1px solid $BORDER;
                        border-radius: 24px;
                        background-color: white;
                    """.trimIndent()
                    placeholder = currentPage.toString()
                    attributes["min"] = "1"
                    attributes["max"] = totalPages.toString()
                    attributes["aria-label"] = "Go to page"
                    onKeyDownFunction = { event ->
                        if ((event as? KeyboardEvent)?.key == "Enter") {
                            submitJump(event.target as? HTMLInputElement, totalPages, onPageChange)
                        }
                    }
                }
                button {
                    style = """
                        padding: 8px 16px;
                        font-size: 14px;
                        cursor: pointer;
                        background-color: white;
                        color: $TEXT;
                        border: 1px solid $BORDER;
                        border-radius: 24px;
                        font-weight: 500;
                        transition: all 0.2s;
                    """.trimIndent()
                    attributes["onmouseover"] = "this.style.backgroundColor='#f1f3f4'"
                    attributes["onmouseout"] = "this.style.backgroundColor='white'"
                    +"Go"
                    onClickFunction = {
                        submitJump(
                            document.getElementById(JUMP_INPUT_ID) as? HTMLInputElement,
                            totalPages,
                            onPageChange
                        )
                    }
                }
            }
        }

        // Items per page selector
        div {
            style = "margin-left: 16px; display: flex; align-items: center; gap: 8px;"
            span {
                style = "font-size: 13px; color: $DISABLED_FG;"
                +"Per page:"
            }
            select {
                style = """
                    padding: 8px 14px;
                    font-size: 14px;
                    border: 1px solid $BORDER;
                    border-radius: 24px;
                    background-color: white;
                    cursor: pointer;
                """.trimIndent()
                attributes["aria-label"] = "Movies per page"

                pageSizeOptions.forEach { size ->
                    option {
                        value = size.toString()
                        selected = (itemsPerPage == size)
                        +size.toString()
                    }
                }

                onChangeFunction = { event ->
                    val select = event.target as? HTMLSelectElement
                    select?.value?.toIntOrNull()?.let(onPageSizeChange)
                }
            }
        }
    }
}
