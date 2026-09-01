package org.btmonier

import kotlinx.browser.document
import kotlinx.html.*
import org.w3c.dom.HTMLElement
import kotlin.math.roundToInt

private const val CARD_ID = "pm-progress-card"
private const val TRACK_ID = "pm-progress-track"
private const val FILL_ID = "pm-progress-fill"
private const val CAMERON_ID = "pm-progress-cameron"
private const val LABEL_ID = "pm-progress-label"
private const val PERCENT_ID = "pm-progress-percent"

/** Half the rendered width of the Cameron figure, used to keep him on the track at 0% and 100%. */
private const val CAMERON_HALF_WIDTH_PX = 22

/** Keeps the figure centered on the fill edge without letting him hang off either end. */
private fun cameronLeft(percent: Double): String =
    "max(${CAMERON_HALF_WIDTH_PX}px, min($percent%, calc(100% - ${CAMERON_HALF_WIDTH_PX}px)))"

private fun Int.grouped(): String = toString().reversed().chunked(3).joinToString(",").reversed()

/**
 * Progress meter showing how much of the collection has a physical media entry.
 * Renders an empty shell; call [loadPhysicalMediaProgress] to populate it.
 */
fun FlowContent.physicalMediaProgressMeter() {
    div {
        id = CARD_ID
        style = """
            padding: 16px 20px;
            background-color: #f8f9fa;
            border: 1px solid #dadce0;
            border-radius: 12px;
            margin-bottom: 16px;
        """.trimIndent()

        // Label row
        div {
            style = "display: flex; align-items: center; justify-content: space-between; gap: 12px;"

            div {
                style = "display: flex; align-items: center; gap: 8px; font-size: 14px; color: #5f6368;"
                span {
                    classes = setOf("mdi", "mdi-disc")
                    style = "font-size: 18px; color: #1a1a2e;"
                }
                span {
                    id = LABEL_ID
                    +"Loading collection stats..."
                }
            }

            span {
                id = PERCENT_ID
                style = "font-size: 16px; font-weight: 600; color: #202124;"
            }
        }

        // Track wrapper - the top padding reserves headroom for the figure
        div {
            style = "position: relative; padding-top: 46px;"

            div {
                id = TRACK_ID
                style = "height: 12px; border-radius: 999px; background-color: #e8eaed;"
                attributes["role"] = "progressbar"
                attributes["aria-valuemin"] = "0"
                attributes["aria-valuemax"] = "100"
                attributes["aria-valuenow"] = "0"

                div {
                    id = FILL_ID
                    style = """
                        width: 0%;
                        height: 100%;
                        border-radius: 999px;
                        background: linear-gradient(90deg, #1a1a2e 0%, #16213e 100%);
                        transition: width 700ms ease;
                    """.trimIndent()
                }
            }

            img(alt = "", src = "icons/cameron_mitchell.svg") {
                id = CAMERON_ID
                style = """
                    position: absolute;
                    bottom: 5px;
                    left: ${cameronLeft(0.0)};
                    height: 52px;
                    transform: translateX(-50%);
                    transition: left 700ms ease;
                    pointer-events: none;
                    filter: drop-shadow(0 2px 3px rgba(0, 0, 0, 0.25));
                """.trimIndent()
            }
        }
    }
}

/**
 * Fetch collection-wide physical media coverage and update the meter.
 * Always reflects the whole collection, ignoring the active filters.
 */
suspend fun loadPhysicalMediaProgress() {
    val card = document.getElementById(CARD_ID) as? HTMLElement ?: return
    try {
        val coverage = fetchPhysicalMediaCoverage()
        val total = coverage.totalMovies
        val withMedia = coverage.moviesWithPhysicalMedia
        val exactPercent = if (total > 0) withMedia * 100.0 / total else 0.0
        val roundedPercent = ((exactPercent * 100).roundToInt() / 100.0)

        (document.getElementById(FILL_ID) as? HTMLElement)
            ?.style?.setProperty("width", "$roundedPercent%")
        (document.getElementById(CAMERON_ID) as? HTMLElement)
            ?.style?.setProperty("left", cameronLeft(roundedPercent))
        document.getElementById(TRACK_ID)
            ?.setAttribute("aria-valuenow", exactPercent.roundToInt().toString())

        document.getElementById(LABEL_ID)?.textContent = if (total > 0) {
            "${withMedia.grouped()} of ${total.grouped()} films have a physical media entry"
        } else {
            "No films in the collection yet"
        }
        document.getElementById(PERCENT_ID)?.textContent =
            if (total > 0) "${exactPercent.roundToInt()}%" else ""

        card.style.removeProperty("display")
    } catch (e: Exception) {
        console.error("Failed to load physical media coverage:", e)
        card.style.setProperty("display", "none")
    }
}
