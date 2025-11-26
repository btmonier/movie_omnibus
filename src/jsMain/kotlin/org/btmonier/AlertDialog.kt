package org.btmonier

import kotlinx.browser.document
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.onClickFunction
import org.w3c.dom.Element

/**
 * Material Design alert dialog.
 */
class AlertDialog(
    private val container: Element
) {
    /**
     * Show an alert dialog with Material Design styling.
     * @param title Dialog title
     * @param message Dialog message
     * @param buttonText Text for close button (default: "OK")
     * @param onClose Callback when dialog is closed (optional)
     */
    fun show(
        title: String,
        message: String,
        buttonText: String = "OK",
        onClose: (() -> Unit)? = null
    ) {
        // Remove existing dialog if any
        close()

        container.append {
            div {
                id = "alert-dialog-overlay"
                style = """
                    position: fixed;
                    top: 0;
                    left: 0;
                    width: 100%;
                    height: 100%;
                    background-color: rgba(0, 0, 0, 0.5);
                    display: flex;
                    justify-content: center;
                    align-items: center;
                    z-index: 2000;
                    animation: fadeIn 0.2s ease-in;
                """.trimIndent()

                // Close on background click
                onClickFunction = { event ->
                    if (event.target == document.getElementById("alert-dialog-overlay")) {
                        close()
                        onClose?.invoke()
                    }
                }

                div {
                    style = """
                        background-color: white;
                        padding: 0;
                        border-radius: 8px;
                        max-width: 500px;
                        width: 90%;
                        box-shadow: 0 8px 10px 1px rgba(0,0,0,0.14), 0 3px 14px 2px rgba(0,0,0,0.12), 0 5px 5px -3px rgba(0,0,0,0.2);
                        animation: slideIn 0.2s ease-out;
                    """.trimIndent()

                    // Prevent clicks inside dialog from closing it
                    onClickFunction = { event ->
                        event.stopPropagation()
                    }

                    // Title section
                    div {
                        style = """
                            padding: 24px 24px 20px 24px;
                        """.trimIndent()
                        h2 {
                            style = """
                                margin: 0;
                                font-size: 20px;
                                font-weight: 500;
                                color: #202124;
                                font-family: 'Google Sans', 'Roboto', arial, sans-serif;
                            """.trimIndent()
                            +title
                        }
                    }

                    // Message section
                    div {
                        style = """
                            padding: 0 24px 24px 24px;
                            color: #5f6368;
                            font-size: 14px;
                            line-height: 1.6;
                            font-family: 'Roboto', arial, sans-serif;
                        """.trimIndent()
                        +message
                    }

                    // Actions section
                    div {
                        style = """
                            padding: 8px 16px 16px 16px;
                            display: flex;
                            justify-content: flex-end;
                        """.trimIndent()

                        // OK button
                        button {
                            style = """
                                padding: 10px 24px;
                                font-size: 14px;
                                font-weight: 500;
                                cursor: pointer;
                                background-color: #1a73e8;
                                color: white;
                                border: none;
                                border-radius: 4px;
                                font-family: 'Google Sans', 'Roboto', arial, sans-serif;
                                transition: background-color 0.2s;
                            """.trimIndent()
                            attributes["onmouseover"] = "this.style.backgroundColor='#1765cc'"
                            attributes["onmouseout"] = "this.style.backgroundColor='#1a73e8'"
                            +buttonText
                            onClickFunction = {
                                close()
                                onClose?.invoke()
                            }
                        }
                    }
                }
            }
        }

        // Add CSS animations if not already present
        if (document.getElementById("dialog-animations") == null) {
            val style = document.createElement("style")
            style.id = "dialog-animations"
            style.textContent = """
                @keyframes fadeIn {
                    from { opacity: 0; }
                    to { opacity: 1; }
                }
                @keyframes slideIn {
                    from {
                        transform: translateY(-20px);
                        opacity: 0;
                    }
                    to {
                        transform: translateY(0);
                        opacity: 1;
                    }
                }
            """.trimIndent()
            document.head?.appendChild(style)
        }
    }

    /**
     * Close the dialog.
     */
    private fun close() {
        document.getElementById("alert-dialog-overlay")?.remove()
    }
}
