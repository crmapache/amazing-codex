package io.github.crmapache.amazingcodex.codex

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * What a message typed into the panel means before it goes to Codex.
 *
 * In Codex's terminal the slash commands are the terminal's own: the TUI reads `/compact` and calls
 * compaction, expands a custom prompt from `~/.codex/prompts` into its text, attaches a skill. The
 * app-server does none of that - a `/compact` sent to it is the word "/compact" said to the model. So the
 * panel, which stands where the terminal stood, does it here: the commands that are actions become
 * actions (see CodexSession.deliver), a custom prompt is expanded by the same rules, and a skill named
 * after the slash goes along as the skill item Codex attaches it by.
 *
 * Commands the panel answers without Codex at all (`/model`, `/status` and the like) never reach this -
 * the panel keeps them itself (see catalog.ts).
 */
internal object CodexCommands {

    sealed interface Command {
        data object Compact : Command
        data object Clear : Command
        data object Init : Command
        data class Review(val target: ReviewTarget) : Command
        /** Ordinary text - possibly an expanded prompt - with the skills it names. */
        data class Say(val text: String, val skills: List<Skill>) : Command
    }

    sealed interface ReviewTarget {
        data object Uncommitted : ReviewTarget
        data class BaseBranch(val branch: String) : ReviewTarget
        data class Commit(val sha: String) : ReviewTarget
        data class Custom(val instructions: String) : ReviewTarget
    }

    data class Skill(val name: String, val path: String)

    /** The names the panel's `/` list offers for the actions below - see ProjectCatalog.commandHints. */
    val BUILT_IN = listOf("compact", "clear", "new", "init", "review")

    fun parse(text: String, promptsDir: File?, skills: Map<String, String>): Command {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("/")) return Command.Say(text, mentionedSkills(text, skills))

        val head = trimmed.substring(1).substringBefore(' ').substringBefore('\n')
        val rest = trimmed.substring(1 + head.length).trim()

        return when (head) {
            "compact" -> Command.Compact
            "clear", "new" -> Command.Clear
            "init" -> Command.Init
            "review" -> Command.Review(reviewTarget(rest))
            else -> promptOrSkill(head, rest, text, promptsDir, skills)
        }
    }

    private fun promptOrSkill(
        head: String,
        rest: String,
        original: String,
        promptsDir: File?,
        skills: Map<String, String>,
    ): Command {
        val promptName = head.removePrefix(PROMPTS_PREFIX)
        val prompt = promptsDir?.resolve("$promptName.md")?.takeIf { it.isFile }
        if (prompt != null) {
            val body = runCatching { prompt.readText() }.getOrNull()
            if (body != null) {
                val expanded = expand(stripFrontmatter(body), rest)
                return Command.Say(expanded, mentionedSkills(expanded, skills))
            }
        }

        skills[head]?.let { path ->
            // The skill travels as an item and the words after it as the message - which is what the
            // terminal sends when a skill is picked out of its list.
            val words = rest.ifBlank { "Use the $head skill." }
            return Command.Say(words, listOf(Skill(head, path)))
        }

        // Nothing of ours by that name: the text goes as it was typed, and the model reads it.
        return Command.Say(original, mentionedSkills(original, skills))
    }

    /**
     * `/review` with nothing after it reviews what is not committed; `/review main` against a branch,
     * `/review commit <sha>` one commit, and anything else is taken as instructions for a custom review -
     * the same targets Codex's `review/start` knows.
     */
    internal fun reviewTarget(rest: String): ReviewTarget {
        if (rest.isBlank()) return ReviewTarget.Uncommitted
        val words = rest.split(Regex("\\s+"))
        if (words.first() == "commit" && words.size == 2) return ReviewTarget.Commit(words[1])
        if (words.size == 1 && BRANCH.matches(words.first())) return ReviewTarget.BaseBranch(words.first())
        return ReviewTarget.Custom(rest)
    }

    private val BRANCH = Regex("[A-Za-z0-9._/-]+")

    /**
     * `$name` anywhere in the message: the terminal's own way of naming a skill inline. Only names that are
     * skills count - a dollar before an environment variable is a dollar.
     */
    internal fun mentionedSkills(text: String, skills: Map<String, String>): List<Skill> =
        SKILL_MENTION.findAll(text)
            .map { it.groupValues[1] }
            .distinct()
            .mapNotNull { name -> skills[name]?.let { Skill(name, it) } }
            .toList()

    /**
     * The name ends on a letter, a digit or an underscore: "use `$save`." at the end of a sentence names
     * `save`, and the full stop is the sentence's, not the skill's.
     */
    private val SKILL_MENTION = Regex("(?<![\\w$])\\$([A-Za-z0-9](?:[A-Za-z0-9_.-]*[A-Za-z0-9_])?)")

    /**
     * A custom prompt's text with its placeholders filled, by the rules of Codex's own prompts: `$1`…`$9`
     * are the words after the name, `$ARGUMENTS` all of them, `$NAME` a `NAME=value` given in the call,
     * and `$$` a dollar sign.
     */
    internal fun expand(body: String, arguments: String): String {
        val words = splitArguments(arguments)
        val named = words.mapNotNull { word ->
            val eq = word.indexOf('=')
            if (eq > 0 && word.substring(0, eq).matches(NAMED_KEY)) word.substring(0, eq) to word.substring(eq + 1) else null
        }.toMap()
        val positional = words.filter { word -> named.keys.none { word.startsWith("$it=") } }

        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c != '$' || i + 1 >= body.length) {
                out.append(c)
                i += 1
                continue
            }

            val next = body[i + 1]
            when {
                next == '$' -> {
                    out.append('$')
                    i += 2
                }

                next in '1'..'9' -> {
                    out.append(positional.getOrNull(next - '1').orEmpty())
                    i += 2
                }

                else -> {
                    val match = NAMED_PLACEHOLDER.matchAt(body, i + 1)
                    if (match == null) {
                        out.append(c)
                        i += 1
                    } else {
                        val key = match.value
                        out.append(if (key == "ARGUMENTS") arguments else named[key] ?: "$$key")
                        i += 1 + key.length
                    }
                }
            }
        }
        return out.toString().trim()
    }

    private val NAMED_KEY = Regex("[A-Z][A-Z0-9_]*")
    private val NAMED_PLACEHOLDER = Regex("[A-Z][A-Z0-9_]*")

    private fun splitArguments(arguments: String): List<String> {
        val words = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (c in arguments) {
            when {
                quote != null && c == quote -> quote = null
                quote == null && (c == '"' || c == '\'') -> quote = c
                quote == null && c.isWhitespace() -> {
                    if (current.isNotEmpty()) words += current.toString().also { current.setLength(0) }
                }
                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) words += current.toString()
        return words
    }

    internal fun stripFrontmatter(body: String): String {
        if (!body.startsWith("---")) return body
        val end = body.indexOf("\n---", 3)
        if (end < 0) return body
        return body.substring(body.indexOf('\n', end + 1).takeIf { it >= 0 }?.plus(1) ?: body.length)
    }

    /** How Codex's terminal names a custom prompt: `/prompts:<file name>`. */
    const val PROMPTS_PREFIX = "prompts:"
}

/**
 * The skills Codex knows in this project, by name - filled from Codex's own `skills/list` (see
 * ProjectCatalog.refreshCommandHints) and read when a message names one (see CodexCommands).
 *
 * Kept per project directory: a skill of one repository is not a skill of another, and two projects are
 * often open at once.
 */
internal object CodexSkills {

    private val known = ConcurrentHashMap<String, Map<String, String>>()

    fun of(workingDirectory: String?): Map<String, String> = known[workingDirectory.orEmpty()].orEmpty()

    fun remember(workingDirectory: String?, skills: Map<String, String>) {
        known[workingDirectory.orEmpty()] = skills
    }
}
