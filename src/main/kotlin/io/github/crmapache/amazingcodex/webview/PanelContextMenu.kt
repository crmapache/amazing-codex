package io.github.crmapache.amazingcodex.webview

import com.intellij.openapi.application.ApplicationManager
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefContextMenuParams
import org.cef.callback.CefMenuModel
import org.cef.callback.CefMenuModel.MenuId
import org.cef.handler.CefContextMenuHandlerAdapter

/**
 * The conversation on screen, as the right-click menu offers it: its id to copy, under the words the panel
 * chose for it in its own language (see the `sessionMenu` message in CodexPanel). The IDE says nothing in
 * any language of its own here - the label travels with the id.
 */
internal data class SessionMenu(val label: String, val conversationId: String)

/**
 * The menu a right click opens over the panel.
 *
 * The embedded browser fills it by itself, as for any web page: Back, Forward, Print, View Page Source. The
 * panel is an interface rather than a page, and those are a browser's - Back leads nowhere, Print prints a
 * screen of the chat, the source is a bundle nobody reads. So they are taken out, and what stays is what a
 * right click is for in an interface: the editing entries over the input field and a selection, the spelling
 * suggestions over a misspelled word, and the id of the conversation on screen to copy - for another session
 * to send to, or a terminal to resume (see SessionMenu).
 *
 * What goes is named one by one rather than what stays: an entry the browser adds tomorrow - the spelling,
 * the editing, the IDE's own developer tools in an internal build - stays until somebody decides about it.
 */
internal class PanelContextMenu(
    /** The conversation on screen, or null where there is none (a new tab, the statistics, the scenarios). */
    private val session: () -> SessionMenu?,
    /** The id went to the clipboard - for the usage counts. */
    private val onCopied: () -> Unit,
) : CefContextMenuHandlerAdapter() {

    override fun onBeforeContextMenu(browser: CefBrowser?, frame: CefFrame?, params: CefContextMenuParams?, model: CefMenuModel) {
        val entries = (0 until model.count).map { index ->
            Entry(model.getCommandIdAt(index), model.getTypeAt(index) == CefMenuModel.MenuItemType.MENUITEMTYPE_SEPARATOR)
        }
        dropped(entries).sortedDescending().forEach { model.removeAt(it) }

        val shown = session() ?: return
        if (model.count > 0) model.addSeparator()
        model.addItem(COPY_SESSION_ID, shown.label)
    }

    override fun onContextMenuCommand(
        browser: CefBrowser?,
        frame: CefFrame?,
        params: CefContextMenuParams?,
        commandId: Int,
        eventFlags: Int,
    ): Boolean {
        if (commandId != COPY_SESSION_ID) return false

        val id = session()?.conversationId ?: return true
        ApplicationManager.getApplication().invokeLater { WebviewClipboard.write(id, "") }
        onCopied()
        return true
    }

    /** One line of the browser's menu: its command, or a separator. */
    data class Entry(val command: Int, val separator: Boolean)

    companion object {
        /** Ours, from the range the browser leaves to its host. */
        const val COPY_SESSION_ID = MenuId.MENU_ID_USER_FIRST + 1

        /** What a browser offers over a page and an interface has no use for. */
        val BROWSER_ONLY: Set<Int> = setOf(
            MenuId.MENU_ID_BACK,
            MenuId.MENU_ID_FORWARD,
            MenuId.MENU_ID_RELOAD,
            MenuId.MENU_ID_RELOAD_NOCACHE,
            MenuId.MENU_ID_STOPLOAD,
            MenuId.MENU_ID_FIND,
            MenuId.MENU_ID_PRINT,
            MenuId.MENU_ID_VIEW_SOURCE,
        )

        /**
         * The positions to take out of [entries]: the browser's own entries, and the separators that would be
         * left with nothing on one side of them - at either end, or two in a row - once those are gone.
         */
        fun dropped(entries: List<Entry>): List<Int> {
            val kept = entries.withIndex().filterNot { (_, entry) -> !entry.separator && entry.command in BROWSER_ONLY }
            val gone = entries.indices.toMutableSet().apply { removeAll(kept.map { it.index }.toSet()) }

            var previousWasItem = false
            kept.forEachIndexed { at, (index, entry) ->
                if (!entry.separator) {
                    previousWasItem = true
                    return@forEachIndexed
                }
                val itemFollows = kept.drop(at + 1).firstOrNull()?.value?.separator == false
                if (!previousWasItem || !itemFollows) gone += index else previousWasItem = false
            }

            return gone.sorted()
        }
    }
}
