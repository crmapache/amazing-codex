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

    /**
     * The menu's name for "whatever Codex runs" - a tab that never picked a model is on it, and it is never sent
     * as one (see DEFAULT_MODEL in catalog.ts).
     */
    const val DEFAULT = "default"

    fun key(model: String): String = model.trim().lowercase()

    fun same(one: String, other: String): Boolean = key(one) == key(other)

    /** Whether a catalogue names this model. */
    fun holds(names: Collection<String>, model: String): Boolean {
        val wanted = key(model)
        return names.any { key(it) == wanted }
    }

    /**
     * Whether the signature under an answer names a model at all.
     *
     * Two kinds of signature do not. The panel signs the words it puts in the model's mouth itself with a mark in
     * angle brackets - `<synthetic>` closes a turn a limit or a dead sign-in refused (see CodexStream). And some
     * proxies and model routers sign part of their answers with the bare word `custom`, between answers signed with
     * the real model (ACC PR #25). Neither is a model anybody can be launched on, and taking one for a model gets the
     * statistics wrong: the models card grows a row for something nobody ran and takes the turn away from the model
     * that did the work. The panel's feed drops the same signatures (realModel in feed/build.ts).
     */
    fun isModel(signature: String): Boolean =
        signature.isNotEmpty() && !signature.startsWith("<") && !signature.equals(PLACEHOLDER, ignoreCase = true)

    private const val PLACEHOLDER = "custom"

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
