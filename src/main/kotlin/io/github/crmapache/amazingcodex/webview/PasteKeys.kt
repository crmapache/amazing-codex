package io.github.crmapache.amazingcodex.webview

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.Shortcut
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.SystemInfo
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JComponent
import javax.swing.KeyStroke

/**
 * Paste shortcuts on Linux, taken by the IDE before they reach the embedded browser.
 *
 * Reported from Ubuntu 26.04 (Wayland, GNOME, German layout, PhpStorm 2026.2.3): Ctrl+V in the panel typed a
 * "v" and Ctrl+Shift+V a "V", while Paste from the context menu worked and Ctrl+V in the editor worked. The
 * page never saw a Ctrl+V at all, so nothing in the page could mend it. The IDE does see the shortcut whole,
 * so on Linux the panel holds the paste shortcuts as an action of its own - a component's shortcuts win over
 * the keymap while the focus is inside it - and the action asks the page to paste from the IDE clipboard, the
 * road the context menu already takes (see clipboard.ts). The IDE swallows the typed key that follows a press
 * it handled, so the letter does not come through either. Confirmed on the reporter's machine.
 *
 * The shortcuts are the keymap's own Paste plus Ctrl+V, Ctrl+Shift+V (the terminal's paste) and Shift+Insert,
 * whatever the keymap says: in a text field these mean paste, and the page itself never pastes on a key press
 * any more (a Ctrl nobody held rides into the browser on a Shift+V, see clipboardKeys.ts) - so a paste key
 * this action did not take would do nothing at all. Mac and Windows are left alone: there the browser pastes
 * by itself and can do more than this road (see clipboard.ts).
 */
internal object PasteKeys {

    fun install(component: JComponent, parent: Disposable, paste: (letter: String) -> Unit) {
        if (!SystemInfo.isLinux) return

        val action = object : DumbAwareAction() {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT

            override fun actionPerformed(event: AnActionEvent) {
                val key = event.inputEvent as? KeyEvent
                paste(letterOf(key?.keyCode ?: KeyEvent.VK_UNDEFINED, key?.isShiftDown == true))
            }
        }
        action.registerCustomShortcutSet(CustomShortcutSet(*shortcuts()), component, parent)
    }

    /** What the key writes on its own - for the page, which swallows that letter if it leaks after all. */
    internal fun letterOf(keyCode: Int, shift: Boolean): String = when {
        keyCode != KeyEvent.VK_V -> ""
        shift -> "V"
        else -> "v"
    }

    private fun shortcuts(): Array<Shortcut> {
        val usual = listOf(
            KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK),
            KeyStroke.getKeyStroke(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK),
            KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.SHIFT_DOWN_MASK),
        ).map { KeyboardShortcut(it, null) }

        return (CommonShortcuts.getPaste().shortcuts.toList() + usual).distinct().toTypedArray()
    }
}
