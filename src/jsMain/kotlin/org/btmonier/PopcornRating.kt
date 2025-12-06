package org.btmonier

import kotlinx.browser.document
import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.onClickFunction
import kotlinx.html.js.onMouseOutFunction
import kotlinx.html.js.onMouseOverFunction
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

/**
 * Popcorn-based rating component with hover and click interactions.
 * Supports 0.5 increments from 0 to 5.
 */
class PopcornRating(
    private val containerId: String,
    private var currentRating: Double? = null,
    private val onRatingChanged: (Double?) -> Unit
) {
    private var hoverRating: Double? = null

    companion object {
        // Full popcorn icon SVG (value = 1)
        private const val POPCORN_FULL_SVG = """<svg viewBox="0 0 30 36.69484" width="24" height="29" xmlns="http://www.w3.org/2000/svg"><g transform="matrix(0.29856865,0,0,0.29856865,-6.4112671e-4,-0.00223382)"><path d="M 4.38,62.41 C 3.72,61.79 3.14,61.1 2.63,60.37 1.01,58.04 0.18,55.25 0.03,52.39 -0.12,49.65 0.34,46.82 1.31,44.27 2.11,42.16 3.27,40.22 4.73,38.63 3.7,35.95 3.36,33.15 3.61,30.44 c 0.29,-3.1 1.34,-6.07 3,-8.66 1.66,-2.58 3.93,-4.8 6.65,-6.39 2.47,-1.44 5.31,-2.35 8.4,-2.52 1.71,-4.66 4.97,-8.02 8.92,-10.15 2.43,-1.31 5.12,-2.14 7.87,-2.5 2.71,-0.36 5.48,-0.26 8.09,0.28 4.14,0.86 7.92,2.85 10.54,5.91 4.4,-1.39 9.32,-1.13 13.71,0.43 2.37,0.84 4.61,2.06 6.54,3.62 1.98,1.59 3.65,3.53 4.82,5.76 1.47,2.8 2.17,6.01 1.74,9.5 2.87,0.77 5.5,2.21 7.79,4.15 2.76,2.33 5.02,5.39 6.56,8.84 1.53,3.43 2.35,7.25 2.23,11.12 -0.15,4.86 -1.75,9.78 -5.23,14.1 L 83.05,122.91 H 66.57 l 2.77,-64.53 h 21.28 c 1.87,-2.75 2.75,-5.77 2.84,-8.74 0.09,-2.8 -0.51,-5.57 -1.62,-8.07 -1.11,-2.48 -2.73,-4.67 -4.69,-6.33 -2.2,-1.86 -4.84,-3.03 -7.63,-3.12 l -4.83,-0.15 1.61,-4.53 c 1.08,-3.05 0.79,-5.75 -0.36,-7.94 -0.71,-1.35 -1.75,-2.55 -3,-3.55 -1.3,-1.04 -2.83,-1.88 -4.47,-2.46 -3.67,-1.3 -7.78,-1.32 -11.01,0.37 l -3.08,1.62 -1.65,-3.06 C 51.32,9.8 48.44,8.1 45.13,7.41 43.28,7.03 41.31,6.96 39.38,7.21 37.46,7.43 35.6,8 33.92,8.91 c -2.99,1.61 -5.37,4.34 -6.18,8.23 l -0.64,3.07 -3.12,-0.29 c -2.69,-0.25 -5.15,0.36 -7.2,1.55 -1.73,1.01 -3.19,2.43 -4.25,4.1 -1.07,1.67 -1.75,3.56 -1.93,5.5 -0.21,2.22 0.23,4.53 1.47,6.67 l 1.63,2.8 -2.66,1.85 C 9.68,43.34 8.58,44.93 7.88,46.78 7.25,48.44 6.95,50.26 7.04,52 c 0.09,1.61 0.52,3.14 1.37,4.35 0.57,0.82 1.36,1.51 2.37,2 H 11.53 30 l 5.34,64.53 H 16.82 Z M 62.1,26.24 c 2.51,0 4.55,2.04 4.55,4.55 0,2.51 -2.04,4.55 -4.55,4.55 -2.51,0 -4.55,-2.04 -4.55,-4.55 0.01,-2.52 2.04,-4.55 4.55,-4.55 z M 48.16,40.33 c 2.51,0 4.55,2.04 4.55,4.55 0,2.51 -2.04,4.55 -4.55,4.55 -2.51,0 -4.55,-2.04 -4.55,-4.55 0,-2.51 2.04,-4.55 4.55,-4.55 z m -19.7,-8.64 c 2.51,0 4.55,2.04 4.55,4.55 0,2.51 -2.04,4.55 -4.55,4.55 -2.51,0 -4.55,-2.04 -4.55,-4.55 0,-2.51 2.03,-4.55 4.55,-4.55 z m 31.49,91.19 H 42 L 36.66,58.35 h 26.06 z"/></g></svg>"""

        // Half popcorn icon SVG (value = 0.5)
        private const val POPCORN_HALF_SVG = """<svg viewBox="0 0 30 36.69484" width="24" height="29" xmlns="http://www.w3.org/2000/svg"><path style="stroke-width:0.298569" d="M 12.697266 0.00390625 C 12.291959 -0.010275761 11.883077 0.008757645 11.478516 0.0625 C 10.657453 0.16998472 9.854428 0.41942195 9.1289062 0.81054688 C 7.9495601 1.4464981 6.9773493 2.4485139 6.4667969 3.8398438 C 5.5442198 3.8906004 4.6964489 4.161858 3.9589844 4.5917969 C 3.1468776 5.0665211 2.4682802 5.7296929 1.9726562 6.5 C 1.4770323 7.2732928 1.1647099 8.1603747 1.078125 9.0859375 C 1.0034828 9.8950585 1.1045837 10.731086 1.4121094 11.53125 C 0.97619914 12.005974 0.62947992 12.584864 0.390625 13.214844 C 0.10101341 13.976194 -0.03697279 14.822547 0.0078125 15.640625 C 0.0525978 16.494531 0.30147504 17.325819 0.78515625 18.021484 C 0.93742626 18.239439 1.1095853 18.445746 1.3066406 18.630859 L 5.0214844 36.685547 L 10.550781 36.685547 L 8.9570312 17.419922 L 3.4414062 17.419922 L 3.21875 17.419922 C 2.9171957 17.273623 2.6799498 17.067092 2.5097656 16.822266 C 2.2559823 16.460998 2.1284335 16.004133 2.1015625 15.523438 C 2.0746913 15.003929 2.1634643 14.460468 2.3515625 13.964844 C 2.5605606 13.412492 2.8888685 12.937937 3.2949219 12.654297 L 4.0898438 12.101562 L 3.6035156 11.265625 C 3.2332905 10.626688 3.101363 9.9362599 3.1640625 9.2734375 C 3.2178048 8.6942144 3.4207659 8.1314221 3.7402344 7.6328125 C 4.0567171 7.1342028 4.4932419 6.7097574 5.0097656 6.4082031 C 5.6218314 6.0529064 6.3550534 5.8706703 7.1582031 5.9453125 L 8.0898438 6.03125 L 8.28125 5.1152344 C 8.5230906 3.9538024 9.2342333 3.1388986 10.126953 2.6582031 C 10.628549 2.3865056 11.184561 2.2160757 11.757812 2.1503906 C 12.33405 2.0757485 12.922257 2.0974814 13.474609 2.2109375 C 14.046373 2.3301269 14.574316 2.5493728 15 2.8652344 L 15 0.48046875 C 14.646453 0.33776224 14.27549 0.22562084 13.894531 0.14648438 C 13.504899 0.065870835 13.102573 0.018088261 12.697266 0.00390625 z M 8.4960938 9.4589844 C 7.7437007 9.4589844 7.1386719 10.068952 7.1386719 10.818359 C 7.1386719 11.567766 7.7466864 12.175781 8.4960938 12.175781 C 9.245501 12.175781 9.8554688 11.567766 9.8554688 10.818359 C 9.8554688 10.068952 9.245501 9.4589844 8.4960938 9.4589844 z M 14.378906 12.039062 C 13.629499 12.039062 13.019531 12.649031 13.019531 13.398438 C 13.019531 14.147846 13.629499 14.755859 14.378906 14.755859 C 14.602788 14.755859 14.813664 14.70169 15 14.605469 L 15 12.189453 C 14.813664 12.093232 14.602788 12.039062 14.378906 12.039062 z M 10.945312 17.419922 L 12.539062 36.685547 L 15 36.685547 L 15 17.419922 L 10.945312 17.419922 z "/></svg>"""

        // Empty popcorn outline SVG (for unfilled slots)
        private const val POPCORN_EMPTY_SVG = """<svg viewBox="0 0 30 36.69484" width="24" height="29" xmlns="http://www.w3.org/2000/svg"><g transform="matrix(0.29856865,0,0,0.29856865,-6.4112671e-4,-0.00223382)" opacity="0.25"><path d="M 4.38,62.41 C 3.72,61.79 3.14,61.1 2.63,60.37 1.01,58.04 0.18,55.25 0.03,52.39 -0.12,49.65 0.34,46.82 1.31,44.27 2.11,42.16 3.27,40.22 4.73,38.63 3.7,35.95 3.36,33.15 3.61,30.44 c 0.29,-3.1 1.34,-6.07 3,-8.66 1.66,-2.58 3.93,-4.8 6.65,-6.39 2.47,-1.44 5.31,-2.35 8.4,-2.52 1.71,-4.66 4.97,-8.02 8.92,-10.15 2.43,-1.31 5.12,-2.14 7.87,-2.5 2.71,-0.36 5.48,-0.26 8.09,0.28 4.14,0.86 7.92,2.85 10.54,5.91 4.4,-1.39 9.32,-1.13 13.71,0.43 2.37,0.84 4.61,2.06 6.54,3.62 1.98,1.59 3.65,3.53 4.82,5.76 1.47,2.8 2.17,6.01 1.74,9.5 2.87,0.77 5.5,2.21 7.79,4.15 2.76,2.33 5.02,5.39 6.56,8.84 1.53,3.43 2.35,7.25 2.23,11.12 -0.15,4.86 -1.75,9.78 -5.23,14.1 L 83.05,122.91 H 66.57 l 2.77,-64.53 h 21.28 c 1.87,-2.75 2.75,-5.77 2.84,-8.74 0.09,-2.8 -0.51,-5.57 -1.62,-8.07 -1.11,-2.48 -2.73,-4.67 -4.69,-6.33 -2.2,-1.86 -4.84,-3.03 -7.63,-3.12 l -4.83,-0.15 1.61,-4.53 c 1.08,-3.05 0.79,-5.75 -0.36,-7.94 -0.71,-1.35 -1.75,-2.55 -3,-3.55 -1.3,-1.04 -2.83,-1.88 -4.47,-2.46 -3.67,-1.3 -7.78,-1.32 -11.01,0.37 l -3.08,1.62 -1.65,-3.06 C 51.32,9.8 48.44,8.1 45.13,7.41 43.28,7.03 41.31,6.96 39.38,7.21 37.46,7.43 35.6,8 33.92,8.91 c -2.99,1.61 -5.37,4.34 -6.18,8.23 l -0.64,3.07 -3.12,-0.29 c -2.69,-0.25 -5.15,0.36 -7.2,1.55 -1.73,1.01 -3.19,2.43 -4.25,4.1 -1.07,1.67 -1.75,3.56 -1.93,5.5 -0.21,2.22 0.23,4.53 1.47,6.67 l 1.63,2.8 -2.66,1.85 C 9.68,43.34 8.58,44.93 7.88,46.78 7.25,48.44 6.95,50.26 7.04,52 c 0.09,1.61 0.52,3.14 1.37,4.35 0.57,0.82 1.36,1.51 2.37,2 H 11.53 30 l 5.34,64.53 H 16.82 Z M 62.1,26.24 c 2.51,0 4.55,2.04 4.55,4.55 0,2.51 -2.04,4.55 -4.55,4.55 -2.51,0 -4.55,-2.04 -4.55,-4.55 0.01,-2.52 2.04,-4.55 4.55,-4.55 z M 48.16,40.33 c 2.51,0 4.55,2.04 4.55,4.55 0,2.51 -2.04,4.55 -4.55,4.55 -2.51,0 -4.55,-2.04 -4.55,-4.55 0,-2.51 2.04,-4.55 4.55,-4.55 z m -19.7,-8.64 c 2.51,0 4.55,2.04 4.55,4.55 0,2.51 -2.04,4.55 -4.55,4.55 -2.51,0 -4.55,-2.04 -4.55,-4.55 0,-2.51 2.03,-4.55 4.55,-4.55 z m 31.49,91.19 H 42 L 36.66,58.35 h 26.06 z"/></g></svg>"""
    }

    fun render() {
        val container = document.getElementById(containerId) ?: return
        container.innerHTML = ""

        container.append {
            div {
                style = """
                    display: flex;
                    align-items: center;
                    gap: 8px;
                """.trimIndent()

                // Rating icons container
                div {
                    id = "$containerId-icons"
                    style = """
                        display: flex;
                        align-items: center;
                        gap: 2px;
                        cursor: pointer;
                    """.trimIndent()

                    renderIcons()
                }

                // Numerical value display
                span {
                    id = "$containerId-value"
                    style = """
                        font-size: 14px;
                        color: #5f6368;
                        min-width: 45px;
                    """.trimIndent()
                    val displayRating = hoverRating ?: currentRating
                    if (displayRating != null) {
                        +"($displayRating)"
                    } else {
                        +"(—)"
                    }
                }

                // Clear button
                if (currentRating != null) {
                    button {
                        style = """
                            padding: 4px 8px;
                            font-size: 12px;
                            cursor: pointer;
                            background-color: transparent;
                            color: #5f6368;
                            border: 1px solid #dadce0;
                            border-radius: 4px;
                            margin-left: 8px;
                        """.trimIndent()
                        attributes["onmouseover"] = "this.style.backgroundColor='#f1f3f4'"
                        attributes["onmouseout"] = "this.style.backgroundColor='transparent'"
                        +"Clear"
                        onClickFunction = {
                            currentRating = null
                            hoverRating = null
                            onRatingChanged(null)
                            render()
                        }
                    }
                }
            }
        }

        setupIconInteractions()
    }

    private fun DIV.renderIcons() {
        val displayRating = hoverRating ?: currentRating ?: 0.0

        for (i in 1..5) {
            span {
                id = "$containerId-icon-$i"
                attributes["data-index"] = i.toString()
                style = """
                    display: inline-flex;
                    position: relative;
                    width: 24px;
                    height: 29px;
                """.trimIndent()

                // Determine what to show for this position
                when {
                    displayRating >= i -> {
                        // Full popcorn
                        unsafe { +POPCORN_FULL_SVG }
                    }
                    displayRating >= i - 0.5 -> {
                        // Half popcorn with gray underlay
                        // Gray underlay (full popcorn behind)
                        span {
                            style = "position: absolute; top: 0; left: 0;"
                            unsafe { +POPCORN_EMPTY_SVG }
                        }
                        // Half popcorn on top
                        span {
                            style = "position: absolute; top: 0; left: 0;"
                            unsafe { +POPCORN_HALF_SVG }
                        }
                    }
                    else -> {
                        // Empty (faded) popcorn
                        unsafe { +POPCORN_EMPTY_SVG }
                    }
                }
            }
        }
    }

    private fun setupIconInteractions() {
        val iconsContainer = document.getElementById("$containerId-icons") as? HTMLElement ?: return

        // Mouse move handler to detect half vs full based on mouse position
        iconsContainer.onmousemove = { event ->
            val mouseEvent = event as org.w3c.dom.events.MouseEvent
            val target = mouseEvent.target as? Element

            // Find the span containing the icon
            val iconSpan = if (target?.tagName?.lowercase() == "span" && target.id.startsWith("$containerId-icon-")) {
                target
            } else {
                // Might have clicked on SVG inside span, traverse up
                var parent = target?.parentElement
                while (parent != null && !(parent.tagName.lowercase() == "span" && parent.id.startsWith("$containerId-icon-"))) {
                    parent = parent.parentElement
                }
                parent
            }

            if (iconSpan != null) {
                val index = iconSpan.getAttribute("data-index")?.toIntOrNull()
                if (index != null) {
                    val rect = iconSpan.getBoundingClientRect()
                    val mouseX = mouseEvent.clientX
                    val iconMidpoint = rect.left + (rect.width / 2)

                    // If mouse is on left half, it's a half rating, otherwise full
                    hoverRating = if (mouseX < iconMidpoint) {
                        index - 0.5
                    } else {
                        index.toDouble()
                    }

                    updateIconsDisplay()
                    updateValueDisplay()
                }
            }
        }

        // Mouse leave handler
        iconsContainer.onmouseleave = {
            hoverRating = null
            updateIconsDisplay()
            updateValueDisplay()
        }

        // Click handler
        iconsContainer.onclick = { event ->
            val mouseEvent = event as org.w3c.dom.events.MouseEvent
            val target = mouseEvent.target as? Element

            // Find the span containing the icon
            val iconSpan = if (target?.tagName?.lowercase() == "span" && target.id.startsWith("$containerId-icon-")) {
                target
            } else {
                var parent = target?.parentElement
                while (parent != null && !(parent.tagName.lowercase() == "span" && parent.id.startsWith("$containerId-icon-"))) {
                    parent = parent.parentElement
                }
                parent
            }

            if (iconSpan != null) {
                val index = iconSpan.getAttribute("data-index")?.toIntOrNull()
                if (index != null) {
                    val rect = iconSpan.getBoundingClientRect()
                    val mouseX = mouseEvent.clientX
                    val iconMidpoint = rect.left + (rect.width / 2)

                    currentRating = if (mouseX < iconMidpoint) {
                        index - 0.5
                    } else {
                        index.toDouble()
                    }

                    onRatingChanged(currentRating)
                    render() // Re-render to show clear button if needed
                }
            }
        }
    }

    private fun updateIconsDisplay() {
        val displayRating = hoverRating ?: currentRating ?: 0.0

        for (i in 1..5) {
            val iconSpan = document.getElementById("$containerId-icon-$i") ?: continue

            val svgContent = when {
                displayRating >= i -> POPCORN_FULL_SVG
                displayRating >= i - 0.5 -> {
                    // Half popcorn with gray underlay
                    """<span style="position: absolute; top: 0; left: 0;">$POPCORN_EMPTY_SVG</span><span style="position: absolute; top: 0; left: 0;">$POPCORN_HALF_SVG</span>"""
                }
                else -> POPCORN_EMPTY_SVG
            }

            iconSpan.innerHTML = svgContent
        }
    }

    private fun updateValueDisplay() {
        val valueSpan = document.getElementById("$containerId-value") ?: return
        val displayRating = hoverRating ?: currentRating

        valueSpan.textContent = if (displayRating != null) {
            "($displayRating)"
        } else {
            "(—)"
        }
    }

    fun getCurrentRating(): Double? = currentRating

    fun setRating(rating: Double?) {
        currentRating = rating
        render()
    }
}

