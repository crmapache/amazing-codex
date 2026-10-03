package io.github.crmapache.amazingcodex.codex

import java.io.File

/**
 * Codex's own configuration files, read as far as the panel needs them.
 *
 * Three layers, in Codex's own order, the one that wins first: a project's `.codex/config.toml`, the
 * person's `~/.codex/config.toml`, and the system-wide `config.toml` an administrator may have left
 * defaults in. The project's counts only once the project is trusted - Codex reads nothing of an
 * untrusted project's own settings (measured on 0.152: `config/read` reports the layer as disabled until
 * `[projects."<path>"] trust_level = "trusted"` is in the person's file), and a panel that read it anyway
 * would start a new tab in a mode no Codex process here would come up in. The panel reads only top-level
 * keys - the model, the effort, the approval policy and the sandbox a new tab should start on - so this is
 * a reader of `key = value` lines above the first table, not a TOML parser; anything it cannot read it
 * treats as not said, and Codex itself stays the judge of the rest (see CodexConfigDesk, which asks it).
 *
 * Beside them, `requirements.toml`: what an administrator allows at all. A mode the requirements forbid is
 * a mode the MODE chip must not offer.
 */
internal object CodexSettings {

    enum class Layer { PROJECT, USER, POLICY }

    data class Source(val layer: Layer, val file: File)

    /** The layers Codex reads for [projectDirectory], the one that wins first. */
    fun sources(projectDirectory: String?): List<Source> = buildList {
        val home = CodexHome.of(projectDirectory)
        projectDirectory?.takeIf { trusted(home.configFile, it) }?.let { add(Source(Layer.PROJECT, File(it, ".codex/$CONFIG"))) }
        add(Source(Layer.USER, home.configFile))
        add(Source(Layer.POLICY, File(home.managedSettingsDirectory, CONFIG)))
    }

    /**
     * Whether the person's config trusts [projectDirectory] - its `[projects."<path>"]` table says
     * `trust_level = "trusted"`, under the path as the IDE knows it or as the disk resolves it (Codex
     * writes whichever it was started in; macOS's /tmp and /private/tmp are one folder).
     */
    internal fun trusted(userConfig: File, projectDirectory: String): Boolean {
        val text = runCatching { userConfig.readText() }.getOrNull() ?: return false
        val paths = setOfNotNull(projectDirectory, runCatching { File(projectDirectory).canonicalPath }.getOrNull())
            .map { it.trimEnd('/', '\\') }

        var inProject = false
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith("[")) {
                val header = PROJECT_TABLE.matchEntire(line)
                inProject = header != null && header.groupValues[1].replace("\\\\", "\\").trimEnd('/', '\\') in paths
                continue
            }
            if (!inProject) continue
            val eq = line.indexOf('=')
            if (eq > 0 && line.substring(0, eq).trim() == "trust_level") return unquote(stripComment(line.substring(eq + 1).trim())) == "trusted"
        }
        return false
    }

    private val PROJECT_TABLE = Regex("""^\[projects\."(.*)"]\s*(#.*)?$""")

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
