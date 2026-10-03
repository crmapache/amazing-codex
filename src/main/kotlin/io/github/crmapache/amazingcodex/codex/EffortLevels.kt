package io.github.crmapache.amazingcodex.codex

/**
 * The reasoning efforts a conversation can be set to - Codex's own names for them.
 *
 * Named as a list, like the modes beside it, because the value travels to Codex as `effort` on every turn
 * and a name it does not know makes the turn fail rather than fall back. Which of them a given model
 * actually takes is the model's business (see `supportedReasoningEfforts` in the model list); `auto` is
 * the panel's word for "the model's own default", sent as no override at all.
 *
 * `ultra` is Codex's "maximum reasoning with automatic task delegation" - the one level that also changes
 * how the agent works, the way `ultracode` did on the Claude side, and a stored `ultracode` is read as it.
 */
internal object EffortLevels {

    const val AUTO = "auto"

    val KNOWN = setOf(AUTO, "minimal", "low", "medium", "high", "xhigh", "max", "ultra")

    fun normalize(effort: String): String {
        val trimmed = effort.trim()
        if (trimmed == "ultracode") return "ultra"
        return trimmed.takeIf { it in KNOWN }.orEmpty()
    }

    /** What goes to Codex for this choice: nothing for the model's own default. */
    fun wire(effort: String): String? = normalize(effort).takeIf { it.isNotEmpty() && it != AUTO }
}
