package io.github.crmapache.amazingcodex.usage

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.util.SystemInfo
import io.github.crmapache.amazingcodex.codex.CodexPreferences
import io.github.crmapache.amazingcodex.codex.IdeLanguage
import io.github.crmapache.amazingcodex.codex.PermissionDefaultMode
import io.github.crmapache.amazingcodex.codex.PermissionModes
import io.github.crmapache.amazingcodex.codex.ProjectCatalog
import io.github.crmapache.amazingcodex.codex.accounts.CodexAccounts
import io.github.crmapache.amazingcodex.feedback.FeedbackEnvironment
import io.github.crmapache.amazingcodex.remote.RemoteState

/**
 * The two parts of a report that are about the machine rather than about a day: what it runs, and how the
 * panel is set up on it.
 *
 * Every value is either a version number, one of a handful of words the plugin itself uses, a count or a
 * yes-or-no. Where a setting holds something a person typed - a model's name, the improve-prompt text, a
 * relay's address - what travels is only whether it is set.
 */
internal object UsageFacts {

    fun environment(): UsageReport.Environment {
        val info = runCatching { ApplicationInfo.getInstance() }.getOrNull()
        return UsageReport.Environment(
            plugin = ProjectCatalog.pluginVersion.orEmpty(),
            ide = info?.build?.productCode.orEmpty(),
            ideVersion = info?.let { "${it.majorVersion}.${it.minorVersionMainPart}" }.orEmpty(),
            os = when {
                SystemInfo.isMac -> "mac"
                SystemInfo.isWindows -> "windows"
                SystemInfo.isLinux -> "linux"
                else -> "other"
            },
            // The JVM's own word for it rather than the platform's CpuArch, which is marked as low-level access
            // the verifier asks about - and the two words it can say here are all this needs.
            arch = when (System.getProperty("os.arch").orEmpty().lowercase()) {
                "aarch64", "arm64" -> "arm64"
                "amd64", "x86_64" -> "x64"
                else -> "other"
            },
            cli = FeedbackEnvironment.cliNumber(),
            // "zh-Hans" and "pt-BR" travel as "zh" and "pt": the service knows the ten by their first part.
            lang = IdeLanguage.inForce(CodexPreferences.language).substringBefore('-'),
        )
    }

    /** The panel's settings, by the short names the dashboard knows them by (see SETTING_TITLES there). */
    fun settings(): Map<String, Any> {
        val preferences = CodexPreferences
        return linkedMapOf(
            "remote" to preferences.remoteEnabled,
            "voice" to preferences.voiceEnabled,
            "layout" to preferences.composerLayout.takeIf { it in LAYOUTS }.orEmpty().ifEmpty { "bottom" },
            "sendKey" to if (preferences.sendKey == "modEnter") "modEnter" else "enter",
            "restoreTabs" to preferences.restoreTabs,
            "shareEditor" to preferences.shareEditor,
            "calmColors" to preferences.gaugeVivid,
            "hiddenIndicators" to preferences.hiddenIndicators.size,
            "customModels" to preferences.customModels.size,
            "improveCustom" to preferences.improveInstructions.isNotBlank(),
            "theme" to preferences.theme.takeIf { it == CodexPreferences.THEME_DARK || it == CodexPreferences.THEME_LIGHT }.orEmpty().ifEmpty { "auto" },
            "textSize" to (preferences.textSize != CodexPreferences.TEXT_SIZE_FOLLOW),
            "language" to preferences.language.isNotBlank(),
            "pasteCollapse" to preferences.pasteCollapse.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }.orEmpty().ifEmpty { "default" },
            "accounts" to (runCatching { CodexAccounts.getInstance().list().size }.getOrNull() ?: 0),
            "soundsMuted" to preferences.mutedSounds.size,
            "newChatModel" to preferences.newTabModel.isNotBlank(),
            "newChatEffort" to preferences.newTabEffort.isNotBlank(),
            "newChatMode" to newChatMode(preferences.mode),
            "pairedDevices" to (runCatching { RemoteState.getInstance().devices().size }.getOrNull() ?: 0),
        )
    }

    /**
     * The models added by hand in the panel - each of them travels as "Other" (see UsageReport.modelName).
     * Read afresh for every report: one added since the last makes the days it ran on go again without its
     * name.
     */
    fun ownModels(): List<String> = runCatching { CodexPreferences.customModels }.getOrDefault(emptyList())

    /**
     * The mode a new chat starts in, as one of the panel's own mode ids (see PermissionModes.KNOWN).
     *
     * Nothing chosen in the panel means "what Codex's own configuration says" (see PermissionDefaultMode),
     * so that is what is read then - the machine's configuration, without any project's on top of it, since
     * the report is the machine's and no one project speaks for it. Never the Claude-era "default": in this
     * plugin it is an old name for "manual", and a dashboard counting it apart would split one mode in two.
     */
    private fun newChatMode(stored: String): String = PermissionModes.resolve(
        stored,
        fallback = runCatching { PermissionDefaultMode.of(null) }.getOrDefault(PermissionModes.ACCEPT_EDITS),
    )

    private val LAYOUTS = setOf("left", "bottom", "right", "compact")
}
