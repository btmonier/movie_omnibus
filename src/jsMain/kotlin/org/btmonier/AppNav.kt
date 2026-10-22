package org.btmonier

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.launch
import kotlinx.html.*
import kotlinx.html.js.onClickFunction
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement

/** A dropdown in the navbar that holds several sections. */
enum class NavGroup(
    val label: String,
    val icon: String,
    val gradient: String,
    /** RGB of the button's glow, for `rgba(...)`. */
    val glow: String
) {
    COLLECTION("Collection", "mdi-bookshelf", "#1a73e8 0%, #3949ab 100%", "26, 115, 232");

    val dropdownId: String get() = "nav-${name.lowercase()}-dropdown"
    val menuId: String get() = "nav-${name.lowercase()}-menu"
}

/** The top-level pages reachable from the navbar. [group] is the dropdown a section is listed under, if any. */
enum class NavSection(
    val label: String,
    val icon: String,
    val gradient: String,
    /** RGB of the button's glow, for `rgba(...)`. */
    val glow: String,
    val group: NavGroup? = null
) {
    FILM("Film", "mdi-filmstrip-box-multiple", "#1a73e8 0%, #3949ab 100%", "26, 115, 232", NavGroup.COLLECTION),
    PHYSICAL_MEDIA("Physical Media", "mdi-package-variant-closed", "#667eea 0%, #764ba2 100%", "102, 126, 234", NavGroup.COLLECTION),
    WISHLIST("Wishlist", "mdi-heart-outline", "#e91e63 0%, #9c27b0 100%", "233, 30, 99"),
    RANDOM("Random", "mdi-dice-multiple", "#667eea 0%, #764ba2 100%", "102, 126, 234")
}

/**
 * Moves between the app's pages. The film page is kept as one instance,
 * so its filters, sort and page survive a trip to another page and back.
 */
object AppNav {
    private lateinit var container: Element
    private lateinit var films: MovieTable

    fun start(root: Element) {
        container = root
        films = MovieTable(root)
        installMenuDismissal()
        films.render()
    }

    fun go(section: NavSection) {
        window.scrollTo(0.0, 0.0)
        when (section) {
            NavSection.FILM -> films.render()
            NavSection.PHYSICAL_MEDIA -> ReleaseBrowser(container).show()
            NavSection.WISHLIST -> WishlistPage(container).show()
            NavSection.RANDOM -> RandomMoviePicker(container).show()
        }
    }

    /** Adding a movie lands on the film page, where the new movie shows up. */
    fun addMovie() {
        go(NavSection.FILM)
        films.showAddMovieForm()
    }

    /** Adding a release lands on the physical media page, then on the new release. */
    fun addPhysicalMedia() {
        window.scrollTo(0.0, 0.0)
        ReleaseBrowser(container).apply {
            show()
            showAddForm()
        }
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

    /** The settings modal; [onCategoriesClosed] is passed on to [manageCategories]. */
    fun openSettings(onCategoriesClosed: () -> Unit) {
        SettingsModal(container, onCategoriesClosed).show()
    }

    private fun installMenuDismissal() {
        document.addEventListener("click", { event ->
            val target = event.target as? Element ?: return@addEventListener
            NavGroup.entries.forEach { group ->
                val dropdown = document.getElementById(group.dropdownId) ?: return@forEach
                if (!dropdown.contains(target)) setMenuOpen(group, false)
            }
        })
    }
}

private fun setMenuOpen(group: NavGroup, open: Boolean) {
    (document.getElementById(group.menuId) as? HTMLElement)?.style?.display = if (open) "block" else "none"
}

private fun isMenuOpen(group: NavGroup): Boolean =
    (document.getElementById(group.menuId) as? HTMLElement)?.style?.display == "block"

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
                attributes["title"] = "Back to the films"
                onClickFunction = { navigate(NavSection.FILM, active, onSubpage) }

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

                val standalone = NavSection.entries.filter { it.group == null }
                    .map { section -> section.label to { navButton(section, isActive = section == active) { navigate(section, active, onSubpage) } } }
                val groups = NavGroup.entries
                    .map { group -> group.label to { navDropdown(group, active, onSubpage) } }

                (standalone + groups).sortedBy { it.first }.forEach { (_, draw) -> draw() }

                settingsButton(onCategoriesClosed)
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
    gradientNavLink(section.gradient, section.glow, isActive) {
        if (isActive) attributes["aria-current"] = "page"
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

private fun FlowContent.gradientNavLink(gradient: String, glow: String, isActive: Boolean, block: A.() -> Unit) {
    val restShadow = "0 2px 8px rgba($glow, 0.4)"
    val hoverShadow = "0 4px 16px rgba($glow, 0.5)"
    // The current page's button carries a white ring, so it reads as "you are here"
    val ring = if (isActive) "0 0 0 2px #ffffff, " else ""
    a {
        href = "#"
        style = """
            display: flex;
            align-items: center;
            gap: 6px;
            padding: 10px 16px;
            font-size: 14px;
            font-weight: 500;
            cursor: pointer;
            background: linear-gradient(135deg, $gradient);
            color: white;
            border: none;
            border-radius: 6px;
            text-decoration: none;
            transition: transform 0.2s, box-shadow 0.2s;
            box-shadow: $ring$restShadow;
        """.trimIndent()
        attributes["onmouseover"] = "this.style.transform='translateY(-2px)'; this.style.boxShadow='$ring$hoverShadow'"
        attributes["onmouseout"] = "this.style.transform='translateY(0)'; this.style.boxShadow='$ring$restShadow'"
        block()
    }
}

private fun FlowContent.navDropdown(group: NavGroup, active: NavSection?, onSubpage: Boolean) {
    val sections = NavSection.entries.filter { it.group == group }
    div {
        id = group.dropdownId
        style = "position: relative;"

        gradientNavLink(group.gradient, group.glow, isActive = active?.group == group) {
            attributes["aria-haspopup"] = "menu"
            onClickFunction = { e ->
                e.preventDefault()
                e.stopPropagation()
                val open = !isMenuOpen(group)
                NavGroup.entries.forEach { setMenuOpen(it, false) }
                setMenuOpen(group, open)
            }
            span {
                classes = setOf("mdi", group.icon)
                style = "font-size: 18px;"
            }
            span { +group.label }
            span {
                classes = setOf("mdi", "mdi-chevron-down")
                style = "font-size: 16px; margin-left: 2px;"
            }
        }

        div {
            id = group.menuId
            attributes["role"] = "menu"
            style = """
                display: none;
                position: absolute;
                top: 100%;
                left: 0;
                margin-top: 8px;
                background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
                border: 1px solid rgba(255,255,255,0.15);
                border-radius: 8px;
                box-shadow: 0 8px 24px rgba(0,0,0,0.4);
                min-width: 190px;
                z-index: 1001;
                overflow: hidden;
            """.trimIndent()

            sections.forEachIndexed { index, section ->
                navMenuItem(
                    icon = section.icon,
                    label = section.label,
                    isActive = section == active,
                    divider = index < sections.lastIndex
                ) {
                    setMenuOpen(group, false)
                    navigate(section, active, onSubpage)
                }
            }
        }
    }
}

private fun FlowContent.navMenuItem(
    icon: String,
    label: String,
    isActive: Boolean,
    divider: Boolean,
    onClick: () -> Unit
) {
    val restBackground = if (isActive) "rgba(255,255,255,0.12)" else "transparent"
    val restColor = if (isActive) "#ffffff" else "#e0e0e0"
    a {
        href = "#"
        attributes["role"] = "menuitem"
        if (isActive) attributes["aria-current"] = "page"
        style = """
            display: flex;
            align-items: center;
            gap: 10px;
            padding: 12px 16px;
            font-size: 14px;
            font-weight: 500;
            cursor: pointer;
            color: $restColor;
            background-color: $restBackground;
            text-decoration: none;
            transition: all 0.2s;
            ${if (divider) "border-bottom: 1px solid rgba(255,255,255,0.1);" else ""}
        """.trimIndent()
        attributes["onmouseover"] = "this.style.backgroundColor='rgba(255,255,255,0.1)'; this.style.color='#ffffff'"
        attributes["onmouseout"] = "this.style.backgroundColor='$restBackground'; this.style.color='$restColor'"
        onClickFunction = { e ->
            e.preventDefault()
            onClick()
        }
        span {
            classes = setOf("mdi", icon)
            style = "font-size: 18px;"
        }
        span { +label }
    }
}

/** The "Add Item" button in a section page's heading row. */
internal fun FlowContent.addItemButton(onClick: () -> Unit) {
    button {
        attributes["type"] = "button"
        style = """
            display: flex;
            align-items: center;
            gap: 6px;
            padding: 10px 18px;
            font-size: 14px;
            font-weight: 500;
            cursor: pointer;
            background-color: #1a73e8;
            color: white;
            border: none;
            border-radius: 6px;
            font-family: 'Google Sans', 'Roboto', arial, sans-serif;
            transition: background-color 0.2s;
        """.trimIndent()
        attributes["onmouseover"] = "this.style.backgroundColor='#1765cc'"
        attributes["onmouseout"] = "this.style.backgroundColor='#1a73e8'"
        onClickFunction = { onClick() }
        span {
            classes = setOf("mdi", "mdi-plus")
            style = "font-size: 18px;"
        }
        +"Add Item"
    }
}

private fun FlowContent.settingsButton(onCategoriesClosed: () -> Unit) {
    a {
        href = "#"
        attributes["title"] = "Settings"
        attributes["aria-label"] = "Settings"
        style = """
            display: flex;
            align-items: center;
            justify-content: center;
            width: 40px;
            height: 40px;
            box-sizing: border-box;
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
            AppNav.openSettings(onCategoriesClosed)
        }
        span {
            classes = setOf("mdi", "mdi-cog")
            style = "font-size: 20px;"
        }
    }
}
