package io.github.crmapache.amazingcodex.codex

import com.intellij.util.EnvironmentUtil
import java.io.File

/**
 * The machine the IDE runs on, as far as Codex's files are concerned.
 */
internal object HostOs {

    private val name: String get() = System.getProperty("os.name").orEmpty()

    val isWindows: Boolean get() = name.startsWith("Windows", ignoreCase = true)

    val isMac: Boolean get() = name.startsWith("Mac")

    /**
     * Where Codex keeps everything: `$CODEX_HOME`, or `~/.codex`.
     *
     * The variable is read from the environment a login shell would give Codex (the IDE launched from the
     * Dock does not have the person's shell variables of its own), which is the environment the plugin
     * launches Codex in.
     */
    fun configDirectory(): File {
        val fromShell = runCatching { EnvironmentUtil.getValue(HOME_VARIABLE) }.getOrNull()
        val configured = (fromShell ?: System.getenv(HOME_VARIABLE))?.takeIf { it.isNotBlank() }
        return configured?.let(::File) ?: File(System.getProperty("user.home"), ".codex")
    }

    /** Where an administrator puts Codex's system-wide config and requirements. */
    fun managedSettingsDirectory(): File = when {
        isWindows -> File("C:\\ProgramData\\OpenAI\\Codex")
        else -> File("/etc/codex")
    }

    const val HOME_VARIABLE = "CODEX_HOME"
}
