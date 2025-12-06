package org.btmonier

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.onClickFunction
import org.w3c.dom.Element
import org.w3c.dom.events.KeyboardEvent

/**
 * Image lightbox with slideshow capabilities.
 * Displays images in a fullscreen overlay with navigation controls.
 */
object ImageLightbox {
    private var currentImages: List<PhysicalMediaImage> = emptyList()
    private var currentIndex: Int = 0
    private var keyboardListener: ((org.w3c.dom.events.Event) -> Unit)? = null

    /**
     * Show the lightbox with a list of images starting at the specified index.
     * @param images List of images to display
     * @param startIndex Index of the image to show first (default: 0)
     */
    fun show(images: List<PhysicalMediaImage>, startIndex: Int = 0) {
        if (images.isEmpty()) return

        currentImages = images
        currentIndex = startIndex.coerceIn(0, images.size - 1)

        render()
        setupKeyboardNavigation()
    }

    /**
     * Close the lightbox.
     */
    fun close() {
        removeKeyboardNavigation()
        document.getElementById("image-lightbox-overlay")?.remove()
        currentImages = emptyList()
        currentIndex = 0
    }

    private fun render() {
        // Remove existing lightbox if any
        document.getElementById("image-lightbox-overlay")?.remove()

        val image = currentImages.getOrNull(currentIndex) ?: return
        val hasMultiple = currentImages.size > 1

        document.body?.append {
            div {
                id = "image-lightbox-overlay"
                style = """
                    position: fixed;
                    top: 0;
                    left: 0;
                    width: 100%;
                    height: 100%;
                    background-color: rgba(0, 0, 0, 0.9);
                    display: flex;
                    flex-direction: column;
                    justify-content: center;
                    align-items: center;
                    z-index: 3000;
                    animation: lightboxFadeIn 0.2s ease-in;
                """.trimIndent()

                // Close on background click
                onClickFunction = { event ->
                    if (event.target == document.getElementById("image-lightbox-overlay")) {
                        close()
                    }
                }

                // Close button (X)
                button {
                    style = """
                        position: absolute;
                        top: 20px;
                        right: 20px;
                        width: 44px;
                        height: 44px;
                        background-color: rgba(255, 255, 255, 0.1);
                        border: none;
                        border-radius: 50%;
                        color: white;
                        font-size: 24px;
                        cursor: pointer;
                        display: flex;
                        justify-content: center;
                        align-items: center;
                        transition: background-color 0.2s;
                        z-index: 3001;
                    """.trimIndent()
                    attributes["onmouseover"] = "this.style.backgroundColor='rgba(255, 255, 255, 0.2)'"
                    attributes["onmouseout"] = "this.style.backgroundColor='rgba(255, 255, 255, 0.1)'"
                    attributes["aria-label"] = "Close"
                    +"×"
                    onClickFunction = { event ->
                        event.stopPropagation()
                        close()
                    }
                }

                // Image counter (e.g., "2 / 5")
                if (hasMultiple) {
                    div {
                        style = """
                            position: absolute;
                            top: 20px;
                            left: 50%;
                            transform: translateX(-50%);
                            color: white;
                            font-size: 14px;
                            font-weight: 500;
                            background-color: rgba(0, 0, 0, 0.5);
                            padding: 8px 16px;
                            border-radius: 20px;
                            font-family: 'Roboto', arial, sans-serif;
                        """.trimIndent()
                        +"${currentIndex + 1} / ${currentImages.size}"
                    }
                }

                // Main content area
                div {
                    style = """
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        gap: 20px;
                    """.trimIndent()

                    // Previous button
                    if (hasMultiple) {
                        button {
                            style = """
                                width: 50px;
                                height: 50px;
                                background-color: rgba(255, 255, 255, 0.1);
                                border: none;
                                border-radius: 50%;
                                color: white;
                                font-size: 28px;
                                cursor: pointer;
                                display: flex;
                                justify-content: center;
                                align-items: center;
                                transition: background-color 0.2s;
                                flex-shrink: 0;
                            """.trimIndent()
                            attributes["onmouseover"] = "this.style.backgroundColor='rgba(255, 255, 255, 0.2)'"
                            attributes["onmouseout"] = "this.style.backgroundColor='rgba(255, 255, 255, 0.1)'"
                            attributes["aria-label"] = "Previous image"
                            +"‹"
                            onClickFunction = { event ->
                                event.stopPropagation()
                                navigatePrevious()
                            }
                        }
                    }

                    // Image container
                    div {
                        style = """
                            display: flex;
                            flex-direction: column;
                            align-items: center;
                            max-width: calc(90vw - 160px);
                        """.trimIndent()
                        onClickFunction = { event -> event.stopPropagation() }

                        img {
                            src = image.imageUrl
                            alt = image.description ?: "Physical media image"
                            style = """
                                max-width: 100%;
                                max-height: 75vh;
                                object-fit: contain;
                                border-radius: 4px;
                                box-shadow: 0 4px 20px rgba(0, 0, 0, 0.5);
                            """.trimIndent()
                        }

                        // Image description
                        image.description?.let { desc ->
                            div {
                                style = """
                                    margin-top: 16px;
                                    color: white;
                                    font-size: 14px;
                                    text-align: center;
                                    font-family: 'Roboto', arial, sans-serif;
                                    background-color: rgba(0, 0, 0, 0.5);
                                    padding: 8px 16px;
                                    border-radius: 4px;
                                    max-width: 100%;
                                """.trimIndent()
                                +desc
                            }
                        }
                    }

                    // Next button
                    if (hasMultiple) {
                        button {
                            style = """
                                width: 50px;
                                height: 50px;
                                background-color: rgba(255, 255, 255, 0.1);
                                border: none;
                                border-radius: 50%;
                                color: white;
                                font-size: 28px;
                                cursor: pointer;
                                display: flex;
                                justify-content: center;
                                align-items: center;
                                transition: background-color 0.2s;
                                flex-shrink: 0;
                            """.trimIndent()
                            attributes["onmouseover"] = "this.style.backgroundColor='rgba(255, 255, 255, 0.2)'"
                            attributes["onmouseout"] = "this.style.backgroundColor='rgba(255, 255, 255, 0.1)'"
                            attributes["aria-label"] = "Next image"
                            +"›"
                            onClickFunction = { event ->
                                event.stopPropagation()
                                navigateNext()
                            }
                        }
                    }
                }

                // Keyboard hint
                if (hasMultiple) {
                    div {
                        style = """
                            position: absolute;
                            bottom: 20px;
                            left: 50%;
                            transform: translateX(-50%);
                            color: rgba(255, 255, 255, 0.5);
                            font-size: 12px;
                            font-family: 'Roboto', arial, sans-serif;
                        """.trimIndent()
                        +"Use ← → arrow keys to navigate, Esc to close"
                    }
                }
            }
        }

        // Add CSS animations if not already present
        if (document.getElementById("lightbox-animations") == null) {
            val style = document.createElement("style")
            style.id = "lightbox-animations"
            style.textContent = """
                @keyframes lightboxFadeIn {
                    from { opacity: 0; }
                    to { opacity: 1; }
                }
            """.trimIndent()
            document.head?.appendChild(style)
        }
    }

    private fun navigatePrevious() {
        if (currentImages.size <= 1) return
        currentIndex = if (currentIndex > 0) currentIndex - 1 else currentImages.size - 1
        render()
    }

    private fun navigateNext() {
        if (currentImages.size <= 1) return
        currentIndex = if (currentIndex < currentImages.size - 1) currentIndex + 1 else 0
        render()
    }

    private fun setupKeyboardNavigation() {
        removeKeyboardNavigation()

        keyboardListener = { event: org.w3c.dom.events.Event ->
            val keyEvent = event as? KeyboardEvent
            when (keyEvent?.key) {
                "Escape" -> close()
                "ArrowLeft" -> navigatePrevious()
                "ArrowRight" -> navigateNext()
            }
        }

        window.addEventListener("keydown", keyboardListener)
    }

    private fun removeKeyboardNavigation() {
        keyboardListener?.let { listener ->
            window.removeEventListener("keydown", listener)
        }
        keyboardListener = null
    }
}

