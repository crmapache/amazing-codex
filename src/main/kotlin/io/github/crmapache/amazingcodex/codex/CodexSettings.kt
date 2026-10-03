package io.github.crmapache.amazingcodex.codex

import java.io.File

/**
 * Codex's own configuration files, read as far as the panel needs them.
 *
 * Three layers, highest first: what an administrator put in the system-wide `config.toml`, a project's
 * `.codex/config.toml`, and the person's `~/.codex/config.toml`. The panel reads only top-level keys - the
 * model, the effort, the approval policy and the sandbox a new tab should start on - so this is a reader
 * of `key = value` lines above the first table, not a TOML parser; anything it cannot read it treats as
 * not said, and Codex itself stays the judge of the rest.
 *
 * Beside them, `requirements.toml`: what an administrator allows at all. A mode the requirements forbid is
 * a mode the MODE chip must not offer.
 */
internal object CodexSettings {

    enum class Layer { POLICY, PROJECT, USER }

    data class Source(val layer: Layer, val file: File)

    fun sources(projectDirectory: String?): List<Source> = buildList {
        val home = CodexHome.of(projectDirectory)
        add(Source(Layer.POLICY, File(home.managedSettingsDirectory, CONFIG)))
        projectDirectory?.let { add(Source(Layer.PROJECT, File(it, ".codex/$CONFIG"))) }
        add(Source(Layer.USER, home.configFile))
    }

    /** A top-level key of one file, or empty when the file does not say it. */
    fun value(file: File, key: String): String = runCatching {
        if (!file.isFile) return ""
        topLevel(file.readText())[key].orEmpty()
    }.getOrDefault("")

    /** The first layer that says [key], highest first. */
    fun effective(projectDirectory: String?, key: String): String =
        sources(projectDirectory).firstNotNullOfOrNull { source -> value(source.file, key).takeIf { it.isNotEmpty() } }.orEmpty()

    /**
     * What `requirements.toml` allows for a list-valued key (`allowed_sandbox_modes`, say), or null when
     * nothing restricts it.
     */
    fun allowed(projectDirectory: String?, key: String): List<String>? {
        val file = File(CodexHome.of(projectDirectory).managedSettingsDirectory, REQUIREMENTS)
        if (!file.isFile) return null
        val raw = runCatching { topLevelRaw(file.readText())[key] }.getOrNull() ?: return null
        if (!raw.startsWith("[")) return null
        return STRING_ITEM.findAll(raw).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()
    }

    /** The top-level `key = value` pairs, values unquoted. */
    internal fun topLevel(text: String): Map<String, String> =
        topLevelRaw(text).mapValues { (_, raw) -> unquote(raw) }

    private fun topLevelRaw(text: String): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith("[")) break
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim().trim('"')
            val value = stripComment(line.substring(eq + 1).trim())
            values[key] = value
        }
        return values
    }

    private fun stripComment(value: String): String {
        var quote: Char? = null
        for ((index, c) in value.withIndex()) {
            when {
                quote != null && c == quote -> quote = null
                quote == null && (c == '"' || c == '\'') -> quote = c
                quote == null && c == '#' -> return value.substring(0, index).trim()
            }
        }
        return value
    }

    private fun unquote(value: String): String = when {
        value.length >= 2 && value.startsWith('"') && value.endsWith('"') -> value.substring(1, value.length - 1).replace("\\\"", "\"")
        value.length >= 2 && value.startsWith('\'') && value.endsWith('\'') -> value.substring(1, value.length - 1)
        else -> value
    }

    private val STRING_ITEM = Regex("\"([^\"]*)\"|'([^']*)'")

    private const val CONFIG = "config.toml"
    private const val REQUIREMENTS = "requirements.toml"
}
