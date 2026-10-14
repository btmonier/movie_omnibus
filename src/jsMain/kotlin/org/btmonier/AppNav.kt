package org.btmonier

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.html.*
import kotlinx.html.js.onClickFunction
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

/** The top-level pages reachable from the navbar. */
enum class NavSection(
    val label: String,
    val icon: String,
    val gradient: String,
    /** RGB of the button's glow, for `rgba(...)`. */
    val glow: String
) {
    COLLECTION("Collection", "mdi-filmstrip-box-multiple", "#1a73e8 0%, #3949ab 100%", "26, 115, 232"),
    RELEASES("Releases", "mdi-package-variant-closed", "#667eea 0%, #764ba2 100%", "102, 126, 234"),
    WISHLIST("Wishlist", "mdi-heart-outline", "#e91e63 0%, #9c27b0 100%", "233, 30, 99"),
    RANDOM("Random", "mdi-dice-multiple", "#667eea 0%, #764ba2 100%", "102, 126, 234")
}

private const val EDIT_TOOLS_ID = "edit-tools-dropdown"
private const val EDIT_TOOLS_MENU_ID = "edit-tools-menu"

/**
 * Moves between the app's pages. The collection page is kept as one instance,
 * so its filters, sort and page survive a trip to another page and back.
 */
object AppNav {
    private lateinit var container: Element
    private lateinit var collection: MovieTable

    fun start(root: Element) {
        container = root
        collection = MovieTable(root)
        installMenuDismissal()
        collection.render()
    }

    fun go(section: NavSection) {
        window.scrollTo(0.0, 0.0)
        when (section) {
            NavSection.COLLECTION -> collection.render()
            NavSection.RELEASES -> ReleaseBrowser(container).show()
            NavSection.WISHLIST -> WishlistPage(container).show()
            NavSection.RANDOM -> RandomMoviePicker(container).show()
        }
    }

    /** Adding a movie lands on the collection, where the new movie shows up. */
    fun addMovie() {
        go(NavSection.COLLECTION)
        collection.showAddMovieForm()
    }

    /**
     * Opens over whichever page is showing. Renames and merges change what
     * that page displays, so [onClose] is its chance to reload.
     */
    fun manageCategories(onClose: () -> Unit) {
        CategoryManagementUI(container) {
            mainScope.launch {
                StoreOptions.reload()
                onClose()
            }
        }.show()
    }

    private fun installMenuDismissal() {
        document.addEventListener("click", { event ->
            val target = event.target as? Element ?: return@addEventListener
            val dropdown = document.getElementById(EDIT_TOOLS_ID) ?: return@addEventListener
            if (!dropdown.contains(target)) setEditToolsOpen(false)
        })
    }
}

private fun setEditToolsOpen(open: Boolean) {
    (document.getElementById(EDIT_TOOLS_MENU_ID) as? HTMLElement)?.style?.display = if (open) "block" else "none"
}

private fun isEditToolsOpen(): Boolean =
    (document.getElementById(EDIT_TOOLS_MENU_ID) as? HTMLElement)?.style?.display == "block"

/**
 * The navbar shared by every page. [active] is the section the page belongs
 * to; clicking it scrolls back to the top, unless [onSubpage] (a page opened
 * from within that section, like one release), where it returns to the
 * section's own page.
 */
fun TagConsumer<*>.appNavbar(
    active: NavSection?,
    onSubpage: Boolean = false,
    onCategoriesClosed: () -> Unit = {}
) {
    nav {
        style = """
            position: sticky;
            top: 0;
            z-index: 1000;
            background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
            box-shadow: 0 2px 12px rgba(0,0,0,0.3);
            padding: 0 24px;
        """.trimIndent()

        div {
            style = """
                max-width: 1400px;
                margin: 0 auto;
                display: flex;
                flex-wrap: wrap;
                justify-content: space-between;
                align-items: center;
                gap: 8px 16px;
                min-height: 64px;
                padding: 8px 0;
                box-sizing: border-box;
            """.trimIndent()

            div {
                style = "display: flex; align-items: center; gap: 12px; cursor: pointer;"
                attributes["title"] = "Back to the collection"
                onClickFunction = { navigate(NavSection.COLLECTION, active, onSubpage) }

                span {
                    classes = setOf("mdi", "mdi-movie-open")
                    style = "font-size: 28px; color: #ffffff;"
                }
                h1 {
                    style = """
                        font-family: 'Oswald', sans-serif;
                        font-weight: 500;
                        font-size: 24px;
                        color: #ffffff;
                        margin: 0;
                        letter-spacing: 1px;
                        white-space: nowrap;
                    """.trimIndent()
                    +"The Movie Omnibus"
                }
            }

            div {
                style = "display: flex; flex-wrap: wrap; align-items: center; gap: 8px;"

                NavSection.entries.forEach { section ->
                    navButton(section, isActive = section == active) {
                        navigate(section, active, onSubpage)
                    }
                }

                editToolsMenu(onCategoriesClosed)
            }
        }
    }
}

private fun navigate(section: NavSection, active: NavSection?, onSubpage: Boolean) {
    if (section == active && !onSubpage) {
        window.scrollTo(0.0, 0.0)
    } else {
        AppNav.go(section)
    }
}

private fun FlowContent.navButton(section: NavSection, isActive: Boolean, onClick: () -> Unit) {
    val restShadow = "0 2px 8px rgba(${section.glow}, 0.4)"
    val hoverShadow = "0 4px 16px rgba(${section.glow}, 0.5)"
    // The current page's button carries a white ring, so it reads as "you are here"
    val ring = if (isActive) "0 0 0 2px #ffffff, " else ""
    a {
        href = "#"
        if (isActive) attributes["aria-current"] = "page"
        style = """
            display: flex;
            align-items: center;
            gap: 6px;
            padding: 10px 16px;
            font-size: 14px;
            font-weight: 500;
            cursor: pointer;
            background: linear-gradient(135deg, ${section.gradient});
            color: white;
            border: none;
            border-radius: 6px;
            text-decoration: none;
            transition: transform 0.2s, box-shadow 0.2s;
            box-shadow: $ring$restShadow;
        """.trimIndent()
        attributes["onmouseover"] = "this.style.transform='translateY(-2px)'; this.style.boxShadow='$ring$hoverShadow'"
        attributes["onmouseout"] = "this.style.transform='translateY(0)'; this.style.boxShadow='$ring$restShadow'"
        onClickFunction = { e ->
            e.preventDefault()
            onClick()
        }
        span {
            classes = setOf("mdi", section.icon)
            style = "font-size: 18px;"
        }
        span { +section.label }
    }
}

private fun FlowContent.editToolsMenu(onCategoriesClosed: () -> Unit) {
    div {
        id = EDIT_TOOLS_ID
        style = "position: relative;"

        a {
            href = "#"
            attributes["aria-haspopup"] = "menu"
            style = """
                display: flex;
                align-items: center;
                gap: 6px;
                padding: 10px 16px;
                font-size: 14px;
                font-weight: 500;
                cursor: pointer;
                background-color: transparent;
                color: #e0e0e0;
                border: 1px solid rgba(255,255,255,0.2);
                border-radius: 6px;
                text-decoration: none;
                transition: all 0.2s;
            """.trimIndent()
            attributes["onmouseover"] = "this.style.backgroundColor='rgba(255,255,255,0.1)'; this.style.borderColor='rgba(255,255,255,0.4)'; this.style.color='#ffffff'"
            attributes["onmouseout"] = "this.style.backgroundColor='transparent'; this.style.borderColor='rgba(255,255,255,0.2)'; this.style.color='#e0e0e0'"
            onClickFunction = { e ->
                e.preventDefault()
                e.stopPropagation()
                setEditToolsOpen(!isEditToolsOpen())
            }
            span {
                classes = setOf("mdi", "mdi-wrench")
                style = "font-size: 18px;"
            }
            span { +"Editor Tools" }
            span {
                classes = setOf("mdi", "mdi-chevron-down")
                style = "font-size: 16px; margin-left: 2px;"
            }
        }

        div {
            id = EDIT_TOOLS_MENU_ID
            attributes["role"] = "menu"
            style = """
                display: none;
                position: absolute;
                top: 100%;
                right: 0;
                margin-top: 8px;
                background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
                border: 1px solid rgba(255,255,255,0.15);
                border-radius: 8px;
                box-shadow: 0 8px 24px rgba(0,0,0,0.4);
                min-width: 180px;
                z-index: 1001;
                overflow: hidden;
            """.trimIndent()

            editToolsItem("mdi-tag-multiple", "Manage Categories", divider = true) {
                AppNav.manageCategories(onCategoriesClosed)
            }
            editToolsItem("mdi-plus", "Add Movie") {
                AppNav.addMovie()
            }
        }
    }
}

private fun FlowContent.editToolsItem(icon: String, label: String, divider: Boolean = false, onClick: () -> Unit) {
    a {
        href = "#"
        attributes["role"] = "menuitem"
        style = """
            display: flex;
            align-items: center;
            gap: 10px;
            padding: 12px 16px;
            font-size: 14px;
            font-weight: 500;
            cursor: pointer;
            color: #e0e0e0;
            text-decoration: none;
            transition: all 0.2s;
            ${if (divider) "border-bottom: 1px solid rgba(255,255,255,0.1);" else ""}
        """.trimIndent()
        attributes["onmouseover"] = "this.style.backgroundColor='rgba(255,255,255,0.1)'; this.style.color='#ffffff'"
        attributes["onmouseout"] = "this.style.backgroundColor='transparent'; this.style.color='#e0e0e0'"
        onClickFunction = { e ->
            e.preventDefault()
            setEditToolsOpen(false)
            onClick()
        }
        span {
            classes = setOf("mdi", icon)
            style = "font-size: 18px;"
        }
        span { +label }
    }
}
