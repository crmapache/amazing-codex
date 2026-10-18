package io.github.crmapache.amazingcodex.webview

import io.github.crmapache.amazingcodex.webview.PanelContextMenu.Entry
import kotlin.test.Test
import kotlin.test.assertEquals
import org.cef.callback.CefMenuModel.MenuId

/** The right-click menu loses the browser's own entries and keeps an interface's (see PanelContextMenu). */
class PanelContextMenuTest {

    private fun item(command: Int) = Entry(command, separator = false)
    private val separator = Entry(-1, separator = true)

    /** What is left of [entries] once [PanelContextMenu.dropped] is applied. */
    private fun left(entries: List<Entry>): List<Entry> {
        val gone = PanelContextMenu.dropped(entries).toSet()
        return entries.filterIndexed { index, _ -> index !in gone }
    }

    @Test
    fun `a page's menu over the feed is left empty`() {
        // What the browser offers over a page with nothing selected: Back, Forward, a line, Print, View Source.
        val page = listOf(
            item(MenuId.MENU_ID_BACK),
            item(MenuId.MENU_ID_FORWARD),
            separator,
            item(MenuId.MENU_ID_PRINT),
            item(MenuId.MENU_ID_VIEW_SOURCE),
        )

        assertEquals(emptyList(), left(page))
    }

    @Test
    fun `the editing entries and the spelling stay, without a line left hanging`() {
        val field = listOf(
            item(MenuId.MENU_ID_SPELLCHECK_SUGGESTION_0),
            item(MenuId.MENU_ID_NO_SPELLING_SUGGESTIONS),
            separator,
            item(MenuId.MENU_ID_UNDO),
            item(MenuId.MENU_ID_REDO),
            separator,
            item(MenuId.MENU_ID_CUT),
            item(MenuId.MENU_ID_COPY),
            item(MenuId.MENU_ID_PASTE),
            item(MenuId.MENU_ID_SELECT_ALL),
            separator,
            item(MenuId.MENU_ID_PRINT),
        )

        assertEquals(field.dropLast(2), left(field))
    }

    @Test
    fun `a selection keeps its Copy and loses the page around it`() {
        val selection = listOf(
            item(MenuId.MENU_ID_COPY),
            separator,
            item(MenuId.MENU_ID_BACK),
            item(MenuId.MENU_ID_FORWARD),
            separator,
            item(MenuId.MENU_ID_VIEW_SOURCE),
        )

        assertEquals(listOf(item(MenuId.MENU_ID_COPY)), left(selection))
    }

    @Test
    fun `two lines in a row become one, and an entry nobody decided about stays`() {
        val unknown = 50_000
        val menu = listOf(
            separator,
            item(MenuId.MENU_ID_COPY),
            separator,
            item(MenuId.MENU_ID_RELOAD),
            separator,
            item(unknown),
            separator,
        )

        assertEquals(listOf(item(MenuId.MENU_ID_COPY), separator, item(unknown)), left(menu))
    }
}
