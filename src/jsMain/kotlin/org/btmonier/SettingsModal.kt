package org.btmonier

import kotlinx.browser.document
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.onClickFunction
import org.w3c.dom.Element
import org.w3c.dom.events.Event
import org.w3c.dom.events.KeyboardEvent

private const val MODAL_ID = "settings-modal"

/**
 * Miscellaneous settings, opened from the gear in the navbar. Each entry
 * closes this modal and opens its own.
 */
class SettingsModal(
    private val container: Element,
    private val onCategoriesClosed: () -> Unit
) {
    private class Entry(val icon: String, val label: String, val description: String, val open: () -> Unit)

    private val entries = listOf(
        Entry(
            icon = "mdi-tag-multiple",
            label = "Manage Categories",
            description = "Rename, merge or delete genres, collections, distributors, themes, countries, tags and stores."
        ) { AppNav.manageCategories(onCategoriesClosed) }
    )

    private val escListener: (Event) -> Unit = { event ->
        if ((event as? KeyboardEvent)?.key == "Escape") close()
    }

    fun show() {
        close()
        container.append {
            div {
                id = MODAL_ID
                style = """
                    position: fixed;
                    top: 0;
                    left: 0;
                    width: 100%;
                    height: 100%;
                    background-color: rgba(0, 0, 0, 0.5);
                    z-index: 1000;
                    display: flex;
                    justify-content: center;
                    align-items: center;
                    font-family: 'Roboto', arial, sans-serif;
                """.trimIndent()
                onClickFunction = { event ->
                    if ((event.target as? Element)?.id == MODAL_ID) close()
                }

                div {
                    attributes["role"] = "dialog"
                    attributes["aria-label"] = "Settings"
                    style = """
                        background-color: white;
                        border-radius: 8px;
                        width: 92%;
                        max-width: 520px;
                        max-height: 90vh;
                        overflow-y: auto;
                        box-shadow: 0 4px 6px rgba(0,0,0,0.1), 0 2px 4px rgba(0,0,0,0.06);
                    """.trimIndent()

                    div {
                        style = "padding: 20px 24px; border-bottom: 1px solid #dadce0; display: flex; justify-content: space-between; align-items: center;"
                        h2 {
                            style = "margin: 0; font-size: 20px; color: #202124; font-weight: 500; display: flex; align-items: center; gap: 10px;"
                            span {
                                classes = setOf("mdi", "mdi-cog")
                                style = "font-size: 22px; color: #5f6368;"
                            }
                            +"Settings"
                        }
                        button {
                            attributes["type"] = "button"
                            style = """
                                padding: 8px 12px;
                                font-size: 14px;
                                cursor: pointer;
                                background-color: #f1f3f4;
                                color: #5f6368;
                                border: none;
                                border-radius: 4px;
                                font-weight: 500;
                                transition: background-color 0.2s;
                            """.trimIndent()
                            attributes["onmouseover"] = "this.style.backgroundColor='#e8eaed'"
                            attributes["onmouseout"] = "this.style.backgroundColor='#f1f3f4'"
                            +"✕ Close"
                            onClickFunction = { close() }
                        }
                    }

                    div {
                        style = "padding: 8px 0;"
                        entries.forEach { entry -> entryRow(entry) }
                    }
                }
            }
        }
        document.addEventListener("keydown", escListener)
    }

    fun close() {
        document.removeEventListener("keydown", escListener)
        document.getElementById(MODAL_ID)?.remove()
    }

    private fun FlowContent.entryRow(entry: Entry) {
        button {
            attributes["type"] = "button"
            style = """
                display: flex;
                align-items: center;
                gap: 16px;
                width: 100%;
                padding: 14px 24px;
                background-color: white;
                border: none;
                text-align: left;
                cursor: pointer;
                font-family: inherit;
                transition: background-color 0.2s;
            """.trimIndent()
            attributes["onmouseover"] = "this.style.backgroundColor='#f8f9fa'"
            attributes["onmouseout"] = "this.style.backgroundColor='white'"
            onClickFunction = {
                close()
                entry.open()
            }
            span {
                classes = setOf("mdi", entry.icon)
                style = "font-size: 24px; color: #1a73e8; flex-shrink: 0;"
            }
            div {
                style = "flex: 1; min-width: 0;"
                div {
                    style = "font-size: 15px; font-weight: 500; color: #202124;"
                    +entry.label
                }
                div {
                    style = "margin-top: 2px; font-size: 13px; color: #5f6368; line-height: 1.4;"
                    +entry.description
                }
            }
            span {
                classes = setOf("mdi", "mdi-chevron-right")
                style = "font-size: 20px; color: #80868b;"
            }
        }
    }
}
