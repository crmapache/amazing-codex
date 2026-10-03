package io.github.crmapache.amazingcodex.codex

/**
 * Telling model names apart.
 *
 * Codex names its models by exact ids (`gpt-5.6-sol`), and the id the menu picks is the id a thread runs
 * on and the id its answers are signed with - there are no aliases, no dated snapshots and no window
 * marks to see through, which is what most of the Claude-era version of this file was about. What is left
 * is an honest comparison: case and surrounding whitespace do not make two models different.
 */
internal object ModelNames {

    fun key(model: String): String = model.trim().lowercase()

    fun same(one: String, other: String): Boolean = key(one) == key(other)

    /** Whether a catalogue names this model. */
    fun holds(names: Collection<String>, model: String): Boolean {
        val wanted = key(model)
        return names.any { key(it) == wanted }
    }

    /** A model without a window mark - Codex has none, so this is the model itself. */
    fun unmarked(model: String): String = model

    /**
     * A model's family in one word, for the places that sort models into kinds (the statistics card):
     * `gpt-5.6-sol` is "sol", `gpt-5.1-codex-max` is "codex-max", an unknown shape is the name itself.
     */
    fun familyOf(model: String): String {
        val bare = key(model)
        val parts = bare.split('-')
        val version = parts.indexOfFirst { part -> part.isNotEmpty() && part.all { it.isDigit() || it == '.' } }
        return if (version >= 0 && version < parts.lastIndex) parts.drop(version + 1).joinToString("-") else bare
    }
}
