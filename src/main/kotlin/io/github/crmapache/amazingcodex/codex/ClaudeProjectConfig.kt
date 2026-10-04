package io.github.crmapache.amazingcodex.codex

import java.io.File

/**
 * The project's Claude Code configuration, shared with Codex without making a second copy of it.
 *
 * Codex discovers AGENTS.md on its own, but it does not interpret Claude Code's `.claude/rules`, and
 * its ordinary skill shelves do not include `.claude/skills`. The panel bridges both gaps: the compact
 * briefing below tells the agent when to read the shared files, while [skillsRoot] registers the live
 * skill directory with app-server. The files remain where their author edits them, so Claude and Codex
 * cannot quietly drift apart.
 */
internal object ClaudeProjectConfig {

    /** Instructions appended only in projects that actually carry a `.claude` directory. */
    private val BRIEFING = """
        This project keeps shared AI configuration in `.claude`, and that directory is a source of truth
        for this Codex session too.

        At the start of a task, inspect the names and frontmatter in `.claude/rules`. Before acting, read
        every rule whose topic or `paths` can apply to the task or to files you may touch, and treat every
        matching rule as project instructions. Do not load unrelated rule bodies into context.

        Skills under `.claude/skills` are registered as Codex skills. Follow their SKILL.md trigger and
        invocation rules just like other skills. When a task concerns an agent, workflow, hook, setting,
        command, or any other AI configuration, inspect the corresponding source under `.claude` and use
        it directly. Do not create a parallel `.codex` copy unless the person explicitly asks for one.

        Claude Code permission allowlists do not grant Codex permissions. The permission mode selected in
        this panel remains authoritative.

        Shared-config contract: $MARKER
    """.trimIndent()

    /** Add the shared-config contract to any role the panel gives a session. */
    fun appendTo(briefing: String, workingDirectory: String?): String {
        if (MARKER in briefing) return briefing
        val base = workingDirectory ?: return briefing
        if (!File(base, DIRECTORY).isDirectory) return briefing
        return "$briefing\n\n$BRIEFING"
    }

    /**
     * A thread started before this bridge existed keeps its old developer instructions on resume. The
     * marker is stored in the rollout with new threads, and an old rollout without it needs one developer
     * item injected before the next turn.
     */
    fun needsInjection(workingDirectory: String?, conversationId: String): Boolean {
        val base = workingDirectory ?: return false
        if (!File(base, DIRECTORY).isDirectory) return false
        return lacksContract(CodexHistory.transcriptFile(base, conversationId))
    }

    internal fun lacksContract(transcript: File?): Boolean {
        if (transcript?.isFile != true) return true
        return runCatching { transcript.useLines { lines -> lines.none { MARKER in it } } }.getOrDefault(true)
    }

    /**
     * The live skill shelf as the CLI itself names it. The IDE path and the CLI path differ for a WSL
     * project; [CodexHome.projectPaths] already resolves that boundary for every other Codex reader.
     */
    fun skillsRoot(workingDirectory: String?): String? {
        val base = workingDirectory ?: return null
        if (!File(base, "$DIRECTORY/skills").isDirectory) return null
        val project = CodexHome.of(base).projectPaths.firstOrNull() ?: return null
        return project.trimEnd('/', '\\') + "/$DIRECTORY/skills"
    }

    private const val DIRECTORY = ".claude"
    private const val MARKER = "amazing-codex:.claude:v1"
}
