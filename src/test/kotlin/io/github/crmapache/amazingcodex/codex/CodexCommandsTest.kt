package io.github.crmapache.amazingcodex.codex

import io.github.crmapache.amazingcodex.codex.CodexCommands.Command
import io.github.crmapache.amazingcodex.codex.CodexCommands.ReviewTarget
import io.github.crmapache.amazingcodex.codex.CodexCommands.Skill
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a typed message means before it goes to Codex: the app-server has none of the terminal's slash
 * commands, so the panel turns them into actions, expands custom prompts by Codex's own rules and sends a
 * named skill along as the item Codex attaches it by (see CodexCommands).
 */
class CodexCommandsTest {

    private val skills = mapOf("save" to "/home/me/.codex/skills/save/SKILL.md", "fix" to "/p/.codex/skills/fix/SKILL.md")

    private fun parse(text: String, prompts: File? = null) = CodexCommands.parse(text, prompts, skills)

    private fun promptsWith(vararg files: Pair<String, String>): File =
        Files.createTempDirectory("acx-prompts").toFile().also { folder ->
            for ((name, text) in files) File(folder, name).writeText(text)
        }

    @Test
    fun `the commands that are actions become actions`() {
        assertEquals(Command.Compact, parse("/compact"))
        assertEquals(Command.Compact, parse("  /compact keep the tests"))
        assertEquals(Command.Clear, parse("/clear"))
        assertEquals(Command.Clear, parse("/new"))
        assertEquals(Command.Init, parse("/init"))
        assertEquals(listOf("compact", "clear", "new", "init", "review"), CodexCommands.BUILT_IN)
    }

    @Test
    fun `a review names what it reviews`() {
        assertEquals(Command.Review(ReviewTarget.Uncommitted), parse("/review"))
        assertEquals(Command.Review(ReviewTarget.BaseBranch("main")), parse("/review main"))
        assertEquals(Command.Review(ReviewTarget.BaseBranch("feature/login-2")), parse("/review feature/login-2"))
        assertEquals(Command.Review(ReviewTarget.Commit("abc123")), parse("/review commit abc123"))
        assertEquals(Command.Review(ReviewTarget.Custom("look for races in the queue")), parse("/review look for races in the queue"))
        assertEquals(ReviewTarget.Uncommitted, CodexCommands.reviewTarget("   "))
    }

    @Test
    fun `ordinary text goes as it was typed, with the skills it names`() {
        val said = parse("Fix the bug, then use \$save to write it down.")

        assertEquals(Command.Say("Fix the bug, then use \$save to write it down.", listOf(Skill("save", skills.getValue("save")))), said)
    }

    // The full stop at the end of a sentence is the sentence's: "use $save." names `save`. A name may
    // still hold a dot or a hyphen inside it.
    @Test
    fun `sentence punctuation after a skill is not part of its name`() {
        val more = skills + ("code-review" to "/p/.codex/skills/code-review/SKILL.md")

        assertEquals(listOf(Skill("save", skills.getValue("save"))), CodexCommands.mentionedSkills("When done, use \$save.", more))
        assertEquals(
            listOf(Skill("code-review", more.getValue("code-review"))),
            CodexCommands.mentionedSkills("Run \$code-review, then stop.", more),
        )
    }

    // A dollar before an environment variable, or inside a word, is a dollar.
    @Test
    fun `only names that are skills count as skills`() {
        assertEquals(emptyList(), CodexCommands.mentionedSkills("echo \$HOME and cost\$save and \$\$save", skills))
        assertEquals(
            listOf(Skill("fix", skills.getValue("fix")), Skill("save", skills.getValue("save"))),
            CodexCommands.mentionedSkills("\$fix it, \$save it, \$fix again", skills),
        )
    }

    @Test
    fun `a skill named after the slash goes as the skill, with the words after it as the message`() {
        assertEquals(Command.Say("the findings", listOf(Skill("save", skills.getValue("save")))), parse("/save the findings"))
        assertEquals(Command.Say("Use the save skill.", listOf(Skill("save", skills.getValue("save")))), parse("/save"))
    }

    @Test
    fun `a slash nobody knows goes to the model as typed`() {
        assertEquals(Command.Say("/nothing here", emptyList()), parse("/nothing here"))
        assertEquals(Command.Say("/nothing \$save", listOf(Skill("save", skills.getValue("save")))), parse("/nothing \$save"))
    }

    /** A custom prompt is expanded by the rules of Codex's own prompts, and its frontmatter is not said. */
    @Test
    fun `a custom prompt is expanded, by its terminal name or its bare one`() {
        val prompts = promptsWith(
            "review-pr.md" to "---\ndescription: Review a PR\nargument-hint: PR=<n>\n---\nReview PR \$PR, focus on \$1 then \$2. All: \$ARGUMENTS. Budget \$\$5.\n",
        )

        val expected = "Review PR 42, focus on speed then safety. All: PR=42 speed safety. Budget \$5."
        assertEquals(Command.Say(expected, emptyList()), parse("/prompts:review-pr PR=42 speed safety", prompts))
        assertEquals(Command.Say(expected, emptyList()), parse("/review-pr PR=42 speed safety", prompts))
    }

    @Test
    fun `an expanded prompt's skills go along with it`() {
        val prompts = promptsWith("wrap.md" to "Do \$1, then use \$save on the result")

        assertEquals(
            Command.Say("Do it, then use \$save on the result", listOf(Skill("save", skills.getValue("save")))),
            parse("/prompts:wrap it", prompts),
        )
    }

    // A prompt is the person's own text for this exact name; a skill of the same name is the second guess.
    @Test
    fun `a prompt outranks a skill of the same name`() {
        val prompts = promptsWith("save.md" to "Save everything.")

        assertEquals(Command.Say("Save everything.", emptyList()), parse("/save", prompts))
    }

    @Test
    fun `placeholders follow Codex's rules`() {
        assertEquals("a b", CodexCommands.expand("\$1 \$2", "a b"))
        // Quotes keep a word together.
        assertEquals("two words|x", CodexCommands.expand("\$1|\$2", "\"two words\" x"))
        // A position with nothing in it is empty, a name nobody gave stays as written.
        assertEquals("[] [\$MISSING]", CodexCommands.expand("[\$9] [\$MISSING]", ""))
        // Lower case is not a placeholder, and neither is a dollar at the very end.
        assertEquals("\$lower costs \$", CodexCommands.expand("\$lower costs \$", ""))
        // Named values are not positional ones.
        assertEquals("v|a", CodexCommands.expand("\$KEY|\$1", "KEY=v a"))
    }

    @Test
    fun `the frontmatter is taken off, and only a closed one`() {
        assertEquals("Body\n", CodexCommands.stripFrontmatter("---\ndescription: x\n---\nBody\n"))
        assertEquals("", CodexCommands.stripFrontmatter("---\ndescription: x\n---"))
        assertEquals("No frontmatter", CodexCommands.stripFrontmatter("No frontmatter"))
        assertEquals("---\nnever closed\n", CodexCommands.stripFrontmatter("---\nnever closed\n"))
    }

    @Test
    fun `the skills Codex knows are kept per project`() {
        CodexSkills.remember("/project/one", mapOf("save" to "/a"))
        CodexSkills.remember("/project/two", mapOf("fix" to "/b"))

        assertEquals(mapOf("save" to "/a"), CodexSkills.of("/project/one"))
        assertEquals(mapOf("fix" to "/b"), CodexSkills.of("/project/two"))
        assertEquals(emptyMap(), CodexSkills.of("/project/three"))
    }
}
