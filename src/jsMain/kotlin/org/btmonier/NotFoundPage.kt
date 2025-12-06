package org.btmonier

import kotlinx.html.*
import kotlinx.html.dom.append
import kotlinx.html.js.onClickFunction
import org.w3c.dom.Element

/**
 * 404 Not Found page component following the app's design principles.
 * Can be used when a requested resource isn't found on the frontend.
 */
class NotFoundPage(
    private val container: Element,
    private val onBackToCollection: () -> Unit
) {
    fun show() {
        container.innerHTML = ""
        container.append {
            div {
                style = """
                    min-height: 100vh;
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    justify-content: center;
                    font-family: 'Google Sans', 'Roboto', arial, sans-serif;
                    background: linear-gradient(135deg, #f5f7fa 0%, #e4e8ed 100%);
                    padding: 40px 20px;
                """.trimIndent()

                div {
                    style = "text-align: center; max-width: 600px;"

                    // Animated movie icon
                    div {
                        style = """
                            font-size: 120px;
                            margin-bottom: 24px;
                            animation: float 3s ease-in-out infinite;
                        """.trimIndent()
                        +"🎬"
                    }

                    // CSS animation keyframes
                    style {
                        unsafe {
                            +"""
                                @keyframes float {
                                    0%, 100% { transform: translateY(0px); }
                                    50% { transform: translateY(-20px); }
                                }
                            """.trimIndent()
                        }
                    }

                    // Error code with gradient
                    div {
                        style = """
                            font-size: 96px;
                            font-weight: 600;
                            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                            -webkit-background-clip: text;
                            -webkit-text-fill-color: transparent;
                            background-clip: text;
                            margin-bottom: 16px;
                            letter-spacing: -2px;
                        """.trimIndent()
                        +"404"
                    }

                    // Title
                    h1 {
                        style = """
                            font-size: 28px;
                            font-weight: 500;
                            color: #202124;
                            margin: 0 0 16px 0;
                        """.trimIndent()
                        +"Reel Not Found"
                    }

                    // Message
                    p {
                        style = """
                            font-size: 16px;
                            color: #5f6368;
                            line-height: 1.6;
                            margin-bottom: 32px;
                        """.trimIndent()
                        +"Looks like this scene ended up on the cutting room floor. The page you're looking for doesn't exist or has been moved to another archive."
                    }

                    // Suggestions box
                    div {
                        style = """
                            background-color: rgba(255, 255, 255, 0.8);
                            backdrop-filter: blur(10px);
                            border-radius: 16px;
                            padding: 24px;
                            margin-bottom: 32px;
                            box-shadow: 0 4px 20px rgba(0, 0, 0, 0.08);
                            border: 1px solid rgba(255, 255, 255, 0.9);
                            text-align: left;
                        """.trimIndent()

                        h3 {
                            style = """
                                font-size: 14px;
                                font-weight: 500;
                                color: #5f6368;
                                margin: 0 0 16px 0;
                                text-transform: uppercase;
                                letter-spacing: 0.5px;
                            """.trimIndent()
                            +"Here's what you can do"
                        }

                        ul {
                            style = """
                                list-style: none;
                                padding: 0;
                                margin: 0;
                                display: flex;
                                flex-direction: column;
                                gap: 12px;
                            """.trimIndent()

                            listOf(
                                "Check the URL for any typos",
                                "Head back to the collection and browse movies",
                                "Try the random movie picker for a surprise",
                                "Make sure the server is running on port 8080"
                            ).forEach { suggestion ->
                                li {
                                    style = """
                                        display: flex;
                                        align-items: center;
                                        gap: 12px;
                                        color: #5f6368;
                                        font-size: 14px;
                                    """.trimIndent()

                                    span {
                                        style = "color: #1a73e8; font-weight: 500;"
                                        +"→"
                                    }
                                    span { +suggestion }
                                }
                            }
                        }
                    }

                    // Buttons
                    div {
                        style = """
                            display: flex;
                            gap: 16px;
                            justify-content: center;
                            flex-wrap: wrap;
                        """.trimIndent()

                        button {
                            style = """
                                padding: 14px 32px;
                                font-size: 15px;
                                font-weight: 500;
                                border-radius: 8px;
                                cursor: pointer;
                                background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                                color: white;
                                border: none;
                                box-shadow: 0 4px 14px rgba(102, 126, 234, 0.4);
                                transition: all 0.2s ease;
                                display: inline-flex;
                                align-items: center;
                                gap: 8px;
                            """.trimIndent()
                            attributes["onmouseover"] = "this.style.transform='translateY(-2px)'; this.style.boxShadow='0 6px 20px rgba(102, 126, 234, 0.5)'"
                            attributes["onmouseout"] = "this.style.transform='translateY(0)'; this.style.boxShadow='0 4px 14px rgba(102, 126, 234, 0.4)'"
                            +"🏠 Back to Collection"
                            onClickFunction = {
                                onBackToCollection()
                            }
                        }

                        a {
                            href = "/api"
                            style = """
                                padding: 14px 32px;
                                font-size: 15px;
                                font-weight: 500;
                                border-radius: 8px;
                                cursor: pointer;
                                background-color: white;
                                color: #1a73e8;
                                border: 1px solid #dadce0;
                                transition: all 0.2s ease;
                                display: inline-flex;
                                align-items: center;
                                gap: 8px;
                                text-decoration: none;
                            """.trimIndent()
                            attributes["onmouseover"] = "this.style.backgroundColor='#f8f9fa'; this.style.borderColor='#1a73e8'"
                            attributes["onmouseout"] = "this.style.backgroundColor='white'; this.style.borderColor='#dadce0'"
                            +"📚 View API Docs"
                        }
                    }
                }

                // Footer
                appFooter()
            }
        }
    }
}

