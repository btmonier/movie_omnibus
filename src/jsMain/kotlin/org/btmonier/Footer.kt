package org.btmonier

import kotlinx.html.*

/**
 * Reusable footer component for all pages.
 */
fun FlowContent.appFooter() {
    footer {
        style = """
            margin-top: 48px;
            padding: 24px 0;
            border-top: 1px solid #e8eaed;
            text-align: center;
            color: #5f6368;
            font-size: 13px;
        """.trimIndent()

        div {
            style = "margin-bottom: 8px;"
            span {
                style = "font-weight: 500; color: #202124;"
                +BuildConfig.APP_NAME
            }
            span {
                style = "margin-left: 8px; padding: 2px 8px; background-color: #e8f0fe; color: #1967d2; border-radius: 4px; font-size: 11px; font-weight: 500;"
                +"v${BuildConfig.VERSION}"
            }
        }
        div {
            +"© ${js("new Date().getFullYear()")} Monier Lab."
        }
    }
}

