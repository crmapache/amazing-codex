package io.github.crmapache.amazingcodex.webview

import com.intellij.ui.JBColor
import io.github.crmapache.amazingcodex.codex.CodexPreferences

/**
 * Which of its two themes the panel wears (see tokens.css): the one chosen in its settings, or the IDE's
 * when nothing has been chosen.
 *
 * The page resolves the choice itself, from the choice and the IDE's brightness sent side by side (see
 * CodexPanel.sendTheme) - the settings screen has to show "as in the IDE" as what it is rather than as
 * whichever theme it happens to amount to today. This side resolves it only once, for the page's address
 * (see WebviewHost.startUrl): the first frame is painted before any message can arrive, and a light IDE
 * must not open its panel on a flash of ink.
 */
internal object PanelTheme {

    /**
     * Whether the IDE is dressed dark right now. The look and feel's own answer, not the editor's colour
     * scheme: the panel is a tool window beside the project view and the terminal, and those follow the
     * look and feel - a light editor inside a dark IDE is still a dark IDE around the panel.
     */
    fun ideIsDark(): Boolean = !JBColor.isBright()

    /** The theme the panel wears for a choice - [CodexPreferences.THEME_DARK] or [CodexPreferences.THEME_LIGHT]. */
    fun resolve(choice: String, ideDark: Boolean): String = when (choice) {
        CodexPreferences.THEME_DARK, CodexPreferences.THEME_LIGHT -> choice
        else -> if (ideDark) CodexPreferences.THEME_DARK else CodexPreferences.THEME_LIGHT
    }

    /** The theme in force this moment, by the setting and the IDE as they stand. */
    fun current(): String = resolve(CodexPreferences.theme, ideIsDark())
}
