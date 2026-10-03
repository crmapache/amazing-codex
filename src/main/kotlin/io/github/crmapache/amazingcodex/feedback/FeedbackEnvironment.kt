package io.github.crmapache.amazingcodex.feedback

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationNamesInfo
import io.github.crmapache.amazingcodex.codex.CodexCli
import io.github.crmapache.amazingcodex.codex.ProjectCatalog

/**
 * The four lines at the top of a debug report: what the plugin is, what it is running inside, on what,
 * and against which Claude Code.
 *
 * Every one of them is the first thing anybody reading a bug asks for, and the one thing a person
 * reporting it usually cannot answer - especially the last: the CLI's version lives in the CLI, and
 * finding it means leaving the IDE for a terminal.
 *
 * The CLI's version is the only one that costs a process to learn, so it is asked for once and kept.
 * It is asked for when the feedback screen is opened (see [warmUp]) rather than when the report is
 * built: opening a screen can afford to wait a second, and a report a person is looking at cannot.
 */
internal object FeedbackEnvironment {

    @Volatile
    private var cliVersion: String? = null

    @Volatile
    private var asked = false

    /**
     * Start finding out what the CLI's version is, if that has not been done yet. Cheap to call more than
     * once; the answer never changes while the IDE runs, and an executable that has been replaced under
     * us is not worth a second process on every visit to this screen.
     */
    fun warmUp(workingDirectory: String?) {
        if (asked) return
        asked = true

        CodexCli.run(
            workingDirectory = workingDirectory,
            args = listOf("--version"),
            timeoutMs = VERSION_TIMEOUT_MS,
            // A version that will not come is not an error worth showing anybody: the report simply says
            // it could not be read, which is itself a fact about the machine.
            onError = { cliVersion = "" },
            onResult = { output -> cliVersion = output.trim().lines().firstOrNull()?.trim().orEmpty() },
        )
    }

    /** The lines themselves, in the order they are read. */
    fun lines(): List<String> = listOf(
        "Amazing Codex GUI ${ProjectCatalog.pluginVersion ?: "(version unknown)"}",
        ide(),
        os(),
        codex(),
    )

    private fun ide(): String {
        val names = runCatching { ApplicationNamesInfo.getInstance().fullProductName }.getOrNull()
        val info = runCatching { ApplicationInfo.getInstance() }.getOrNull()
        val version = info?.fullVersion.orEmpty()
        val build = info?.build?.asString().orEmpty()

        return listOfNotNull(
            names ?: "IDE",
            version.takeIf { it.isNotEmpty() },
            build.takeIf { it.isNotEmpty() }?.let { "($it)" },
        ).joinToString(" ")
    }

    private fun os(): String {
        val name = System.getProperty("os.name").orEmpty()
        val version = System.getProperty("os.version").orEmpty()
        val arch = System.getProperty("os.arch").orEmpty()

        return listOfNotNull(
            name.ifEmpty { "unknown OS" },
            version.takeIf { it.isNotEmpty() },
            arch.takeIf { it.isNotEmpty() }?.let { "- $it" },
        ).joinToString(" ")
    }

    /**
     * The CLI's version alone - "0.152.0" - or empty while it is still being read or could not be. The
     * usage report sends this rather than the line above: a number, with nothing around it - and Codex
     * puts its own name in front of it ("codex-cli 0.152.0"), which is not part of the number either.
     */
    fun cliNumber(): String = cliVersion.orEmpty().substringBefore(" (").trim().removePrefix("codex-cli").trim()

    private fun codex(): String = when (val version = cliVersion) {
        null -> "Codex (still reading its version)"
        "" -> "Codex (version could not be read)"
        // Codex answers "codex-cli 0.152.0" - the name is already on the line.
        else -> "Codex " + version.removePrefix("codex-cli").trim()
    }

    private const val VERSION_TIMEOUT_MS = 8_000
}
