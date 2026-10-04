package io.github.crmapache.amazingcodex.codex

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shelves the `/` hint is read from before Codex has said anything: the project's `.codex/skills`,
 * `.agents/skills` and shared `.claude/skills`, the person's `~/.codex/skills` (with its `.system`
 * folder), `~/.agents/skills`, the custom prompts in `~/.codex/prompts`, and every installed plugin's
 * skills.
 */
class CodexCommandHintsTest {

    /**
     * A Codex home of our own, so that the scan under test reads nothing but the temporary directories
     * this file makes. The home is `<root>/.codex` rather than the temporary directory itself, because
     * `~/.agents/skills` is looked for beside the home - and beside a bare temporary directory is the
     * machine's shared temp folder.
     */
    private fun homeAt(root: File): CodexHome = CodexHome(
        configDirectory = File(root, ".codex"),
        managedSettingsDirectory = File(root, "managed"),
        projectPaths = emptyList(),
        remote = false,
        toHost = { it },
    )

    private fun newRoot(prefix: String): File = Files.createTempDirectory(prefix).toFile()

    private fun emptyHome(): CodexHome = homeAt(newRoot("acx-hints-home"))

    private fun write(base: File, path: String, text: String): File =
        File(base, path).apply {
            parentFile.mkdirs()
            writeText(text)
        }

    private fun scanWith(name: String, frontmatter: String): CommandHint? {
        val base = newRoot("acx-hints")
        write(base, ".codex/skills/$name/SKILL.md", frontmatter)

        return CodexCommandHints.scan(emptyHome(), base.absolutePath, installed = emptyList()).hints[name]
    }

    private fun projectWith(vararg files: Pair<String, String>): Map<String, CommandHint> {
        val base = newRoot("acx-hints-project")
        for ((path, text) in files) write(base, path, text)

        return CodexCommandHints.scan(emptyHome(), base.absolutePath, installed = emptyList()).hints
    }

    // --- The frontmatter ------------------------------------------------------------

    @Test
    fun `a one-line description is read as it is`() {
        val hint = scanWith(
            "one-line",
            """
            ---
            name: one-line
            description: Open a pull request
            argument-hint: "[number]"
            ---

            # body
            """.trimIndent(),
        )

        assertEquals("Open a pull request", hint?.description)
        assertEquals("[number]", hint?.argumentHint)
    }

    @Test
    fun `a folded block is joined into one line rather than turning into an arrow`() {
        // Exactly the case where the command hint ended up holding a single ">": everything after the
        // colon was taken, and there is nothing there but the block indicator.
        val hint = scanWith(
            "folded",
            """
            ---
            name: folded
            description: >
              Check the CI status for a pull request
              and explain the failures in plain words.
            argument-hint: 'opt. [PR number]'
            ---

            # body
            """.trimIndent(),
        )

        assertEquals(
            "Check the CI status for a pull request and explain the failures in plain words.",
            hint?.description,
        )
        assertEquals("opt. [PR number]", hint?.argumentHint)
    }

    @Test
    fun `a literal block keeps its newlines`() {
        val hint = scanWith(
            "literal",
            """
            ---
            name: literal
            description: |
              First line.
              Second line.
            ---

            # body
            """.trimIndent(),
        )

        assertEquals("First line.\nSecond line.", hint?.description)
    }

    @Test
    fun `a field after a block is read rather than swallowed by it`() {
        val hint = scanWith(
            "after-block",
            """
            ---
            description: >
              A long one
              over two lines.
            argument-hint: "[what]"
            ---

            # body
            """.trimIndent(),
        )

        assertEquals("A long one over two lines.", hint?.description)
        assertEquals("[what]", hint?.argumentHint)
    }

    /**
     * The two doors into a skill (see CommandHint): `disable-model-invocation` shuts the model's own way
     * in, `user-invocable: false` shuts the slash command. Both default to open, and a skill's file is
     * named so that whoever reads the rule can read the rest.
     */
    @Test
    fun `the invocation flags are read, and default to open`() {
        val hints = projectWith(
            ".codex/skills/person-only/SKILL.md" to "---\ndescription: only by hand\ndisable-model-invocation: yes\n---\nbody\n",
            ".codex/skills/model-only/SKILL.md" to "---\ndescription: only by the model\nuser-invocable: false\n---\nbody\n",
            ".codex/skills/open/SKILL.md" to "---\ndescription: either\n---\nbody\n",
        )

        assertEquals(false, hints["person-only"]?.modelInvocable)
        assertEquals(true, hints["person-only"]?.userInvocable)
        assertEquals(true, hints["model-only"]?.modelInvocable)
        assertEquals(false, hints["model-only"]?.userInvocable)
        assertEquals(true, hints["open"]?.modelInvocable)
        assertEquals(true, hints["open"]?.userInvocable)
        assertTrue(hints["open"]?.file.orEmpty().endsWith("SKILL.md"))
    }

    @Test
    fun `a skill without frontmatter keeps its name`() {
        // Such a skill used to fall out of the scan whole - name and all, which is the greater half of
        // what the hint is for.
        val hints = projectWith(".codex/skills/plain/SKILL.md" to "just say hi\n")

        val plain = hints["plain"]
        assertNotNull(plain)
        assertEquals("", plain.description)
        assertEquals("", plain.argumentHint)
        assertTrue(plain.modelInvocable)
        assertTrue(plain.file.endsWith("SKILL.md"))
    }

    // --- The shelves ------------------------------------------------------------------

    @Test
    fun `all of the project's shelves are read`() {
        val hints = projectWith(
            ".codex/skills/from-codex/SKILL.md" to "---\ndescription: codex shelf\n---\n",
            ".agents/skills/from-agents/SKILL.md" to "---\ndescription: agents shelf\n---\n",
            ".claude/skills/from-claude/SKILL.md" to "---\ndescription: shared Claude shelf\n---\n",
        )

        assertEquals("codex shelf", hints["from-codex"]?.description)
        assertEquals("agents shelf", hints["from-agents"]?.description)
        assertEquals("shared Claude shelf", hints["from-claude"]?.description)
    }

    @Test
    fun `native project shelves outrank the shared Claude shelf`() {
        val hints = projectWith(
            ".codex/skills/same/SKILL.md" to "---\ndescription: native\n---\n",
            ".claude/skills/same/SKILL.md" to "---\ndescription: shared\n---\n",
        )

        assertEquals("native", hints["same"]?.description)
    }

    @Test
    fun `a skill is a folder with a SKILL file in it and nothing else`() {
        val hints = projectWith(
            ".codex/skills/no-skill-file/README.md" to "---\ndescription: not a skill\n---\n",
            ".codex/skills/loose.md" to "---\ndescription: not a skill either\n---\n",
            ".codex/skills/nested/deeper/SKILL.md" to "---\ndescription: too deep\n---\n",
        )

        assertTrue(hints.isEmpty(), "found $hints")
    }

    @Test
    fun `the person's skills are read from every shelf Codex keeps them on`() {
        val root = newRoot("acx-hints-home")
        write(root, ".codex/skills/mine/SKILL.md", "---\ndescription: personal\n---\n")
        write(root, ".codex/skills/.system/built-in/SKILL.md", "---\ndescription: shipped with Codex\n---\n")
        write(root, ".agents/skills/shared/SKILL.md", "---\ndescription: beside the home\n---\n")

        val hints = CodexCommandHints.scan(homeAt(root), newRoot("acx-hints-project").absolutePath, installed = emptyList()).hints

        assertEquals("personal", hints["mine"]?.description)
        assertEquals("shipped with Codex", hints["built-in"]?.description)
        assertEquals("beside the home", hints["shared"]?.description)
        // The `.system` folder is a shelf, not a skill of its own.
        assertTrue(".system" !in hints)
    }

    /**
     * A custom prompt is named the way Codex's terminal names it, and it is text the panel pastes in for
     * the person: the model can never start one, whatever its frontmatter says.
     */
    @Test
    fun `custom prompts are named through prompts and are never the model's to call`() {
        val root = newRoot("acx-hints-home")
        write(root, ".codex/prompts/review-pr.md", "---\ndescription: Review a PR\nargument-hint: PR=<number>\n---\nReview \$PR\n")
        write(root, ".codex/prompts/bare.md", "Say hello to \$1\n")
        write(root, ".codex/prompts/notes.txt", "not a prompt\n")

        val hints = CodexCommandHints.scan(homeAt(root), null, installed = emptyList()).hints

        assertEquals("Review a PR", hints["prompts:review-pr"]?.description)
        assertEquals("PR=<number>", hints["prompts:review-pr"]?.argumentHint)
        assertEquals(false, hints["prompts:review-pr"]?.modelInvocable)
        assertEquals(false, hints["prompts:bare"]?.modelInvocable)
        assertTrue(hints.keys.none { it.contains("notes") })
    }

    /** Codex reads no subfolder of `prompts`, so a name offered from one would never be expanded. */
    @Test
    fun `a prompt in a subfolder is not offered`() {
        val root = newRoot("acx-hints-home")
        write(root, ".codex/prompts/top.md", "Top\n")
        write(root, ".codex/prompts/team/nested.md", "Nested\n")

        val hints = CodexCommandHints.scan(homeAt(root), null, installed = emptyList()).hints

        assertTrue("prompts:top" in hints)
        assertTrue(hints.keys.none { it.contains("nested") })
    }

    @Test
    fun `a plugin's skill is named through its plugin`() {
        val pluginHome = newRoot("acx-hints-plugin")
        write(pluginHome, "skills/deploy/SKILL.md", "---\ndescription: theirs\n---\nbody\n")

        val hints = CodexCommandHints.scan(
            emptyHome(),
            projectWithDeploy().absolutePath,
            installed = listOf(
                InstalledPlugin(
                    id = "someone@market",
                    version = "1",
                    scope = "user",
                    enabled = true,
                    installPath = pluginHome.absolutePath,
                ),
                // A plugin that says nothing about where it lives has no shelf to read.
                InstalledPlugin(id = "nowhere@market", version = "1", scope = "user", enabled = true),
            ),
        ).hints

        assertEquals("ours", hints["deploy"]?.description)
        assertEquals("theirs", hints["someone:deploy"]?.description)
    }

    private fun projectWithDeploy(): File =
        newRoot("acx-hints-project").also { write(it, ".codex/skills/deploy/SKILL.md", "---\ndescription: ours\n---\nbody\n") }

    @Test
    fun `the project's own skill outranks a personal one of the same name`() {
        // The walk order IS the precedence rule, so nothing in it may be sorted - the fingerprint sorts
        // a copy. Reachable as a test only because the home is a parameter.
        val root = newRoot("acx-hints-home")
        write(root, ".codex/skills/deploy/SKILL.md", "---\ndescription: the person's\n---\nbody\n")

        val hints = CodexCommandHints.scan(homeAt(root), projectWithDeploy().absolutePath, installed = emptyList()).hints

        assertEquals("ours", hints["deploy"]?.description)
    }

    @Test
    fun `within one side the Codex shelf comes before the shared one`() {
        val base = newRoot("acx-hints-project")
        write(base, ".codex/skills/twin/SKILL.md", "---\ndescription: codex\n---\n")
        write(base, ".agents/skills/twin/SKILL.md", "---\ndescription: agents\n---\n")
        val root = newRoot("acx-hints-home")
        write(root, ".codex/skills/pair/SKILL.md", "---\ndescription: personal codex\n---\n")
        write(root, ".agents/skills/pair/SKILL.md", "---\ndescription: personal agents\n---\n")

        val hints = CodexCommandHints.scan(homeAt(root), base.absolutePath, installed = emptyList()).hints

        assertEquals("codex", hints["twin"]?.description)
        assertEquals("personal codex", hints["pair"]?.description)
    }

    @Test
    fun `the map keeps the order of the walk`() {
        // The order the map is built in is what the scenario writer's catalogue is handed in, so it is
        // the walk's - the project first, then the person's skills, then their prompts.
        val base = newRoot("acx-hints-project")
        write(base, ".codex/skills/zulu/SKILL.md", "---\ndescription: the project's\n---\nbody\n")
        val root = newRoot("acx-hints-home")
        write(root, ".codex/skills/alpha/SKILL.md", "---\ndescription: the person's\n---\nbody\n")
        write(root, ".codex/prompts/aardvark.md", "a prompt\n")

        val hints = CodexCommandHints.scan(homeAt(root), base.absolutePath, installed = emptyList()).hints

        assertEquals(listOf("zulu", "alpha", "prompts:aardvark"), hints.keys.toList())
    }

    @Test
    fun `two different homes give two different scans`() {
        // The home is a parameter rather than something resolved inside, and nothing caches it here: on
        // a WSL project the first answer arrives late, and a scan frozen on the earlier one would read
        // the wrong machine's personal skills for the rest of the session.
        val base = newRoot("acx-hints-project")

        val one = newRoot("acx-hints-home-one")
        write(one, ".codex/skills/here/SKILL.md", "---\ndescription: the first home\n---\nbody\n")

        val two = newRoot("acx-hints-home-two")
        write(two, ".codex/skills/there/SKILL.md", "---\ndescription: the second home\n---\nbody\n")

        val first = CodexCommandHints.scan(homeAt(one), base.absolutePath, installed = emptyList())
        val second = CodexCommandHints.scan(homeAt(two), base.absolutePath, installed = emptyList())

        assertEquals("the first home", first.hints["here"]?.description)
        assertNull(first.hints["there"])
        assertEquals("the second home", second.hints["there"]?.description)
        assertTrue(first.stamp != second.stamp)
    }

    // --- The fingerprint ------------------------------------------------------------

    @Test
    fun `an unchanged disk is not read again`() {
        val base = newRoot("acx-hints-stamp")
        val home = emptyHome()
        write(base, ".codex/skills/probe/SKILL.md", "---\ndescription: one\n---\nbody\n")

        val first = CodexCommandHints.scan(home, base.absolutePath, installed = emptyList())
        val again = CodexCommandHints.scanIfChanged(home, base.absolutePath, installed = emptyList(), since = first.stamp)

        assertNull(again.scan)
        // And it says what looking cost even so. That is the half the round paces itself by, and a
        // quiet disk is nearly every round.
        assertTrue(again.walkNanos > 0)
    }

    @Test
    fun `a skill added after the first walk moves the fingerprint`() {
        val base = newRoot("acx-hints-stamp")
        val home = emptyHome()
        write(base, ".codex/skills/probe/SKILL.md", "---\ndescription: one\n---\nbody\n")
        val first = CodexCommandHints.scan(home, base.absolutePath, installed = emptyList())

        write(base, ".agents/skills/fresh/SKILL.md", "---\ndescription: brand new\n---\nbody\n")
        val second = CodexCommandHints.scanIfChanged(home, base.absolutePath, installed = emptyList(), since = first.stamp)

        val scan = assertNotNull(second.scan)
        assertEquals("brand new", scan.hints["fresh"]?.description)
        assertTrue(scan.whole)
    }

    @Test
    fun `a skill deleted after the first walk moves the fingerprint`() {
        val base = newRoot("acx-hints-stamp")
        val home = emptyHome()
        write(base, ".codex/skills/keep/SKILL.md", "---\ndescription: staying\n---\nbody\n")
        write(base, ".codex/skills/going/SKILL.md", "---\ndescription: leaving\n---\nbody\n")
        val first = CodexCommandHints.scan(home, base.absolutePath, installed = emptyList())

        assertTrue(File(base, ".codex/skills/going").deleteRecursively())
        val second = CodexCommandHints.scanIfChanged(home, base.absolutePath, installed = emptyList(), since = first.stamp)

        val scan = assertNotNull(second.scan)
        assertTrue("going" !in scan.hints)
        assertTrue("keep" in scan.hints)
    }

    @Test
    fun `an edited description moves the fingerprint`() {
        val base = newRoot("acx-hints-stamp")
        val home = emptyHome()
        val skill = write(base, ".codex/skills/probe/SKILL.md", "---\ndescription: one\n---\nbody\n")
        val first = CodexCommandHints.scan(home, base.absolutePath, installed = emptyList())

        skill.writeText("---\ndescription: one, rather longer than before\n---\nbody\n")
        val second = CodexCommandHints.scanIfChanged(home, base.absolutePath, installed = emptyList(), since = first.stamp)

        val scan = assertNotNull(second.scan)
        assertEquals("one, rather longer than before", scan.hints["probe"]?.description)
    }

    @Test
    fun `an edit of the same length at the same moment is invisible to the fingerprint, and the full read still brings it`() {
        // The fingerprint is a throttle rather than the truth: a file system with whole-second
        // timestamps (exFAT, an SMB share, the 9P share a WSL project is read through) does not move it
        // when a word is replaced by one of the same length. That is what the unconditional minute round
        // is for, and this is the case that would otherwise never arrive at all.
        val base = newRoot("acx-hints-stamp")
        val home = emptyHome()
        val skill = write(base, ".codex/skills/probe/SKILL.md", "---\ndescription: one\n---\nbody\n")
        val first = CodexCommandHints.scan(home, base.absolutePath, installed = emptyList())
        val was = skill.lastModified()

        skill.writeText("---\ndescription: two\n---\nbody\n")
        assertTrue(skill.setLastModified(was))

        assertNull(CodexCommandHints.scanIfChanged(home, base.absolutePath, installed = emptyList(), since = first.stamp).scan)
        assertEquals("two", CodexCommandHints.scan(home, base.absolutePath, installed = emptyList()).hints["probe"]?.description)
    }

    @Test
    fun `the fingerprint is taken in name order rather than in the order the disk listed`() {
        // `listFiles` promises no order at all. Folded in whatever order it happened to answer with, the
        // fingerprint would move on its own, and the fast round would read every file on every tick.
        val base = newRoot("acx-hints-stamp")
        write(base, ".codex/skills/zulu/SKILL.md", "---\ndescription: last\n---\nbody\n")
        write(base, ".codex/skills/alpha/SKILL.md", "---\ndescription: first\n---\nbody\n")
        write(base, ".codex/skills/mike/SKILL.md", "---\ndescription: middle\n---\nbody\n")

        val stamp = CodexCommandHints.scan(emptyHome(), base.absolutePath, installed = emptyList()).stamp
        val names = stamp.lines().map { it.substringBefore('\t') }

        assertEquals(listOf("alpha", "mike", "zulu"), names)
    }

    // --- Whole and partial walks ------------------------------------------------------

    @Test
    fun `a file where a directory is expected is not a failed walk`() {
        // `listFiles` answers null there too, and it is not a failure: there is nothing to read and
        // nothing for the hint to lose.
        val base = newRoot("acx-hints-stamp")
        write(base, ".codex/skills", "not a directory\n")
        write(base, ".agents/skills", "not a directory either\n")

        val scan = CodexCommandHints.scan(emptyHome(), base.absolutePath, installed = emptyList())

        assertTrue(scan.whole)
        assertTrue(scan.hints.isEmpty())
    }

    @Test
    fun `a project with no shelves at all is a whole walk`() {
        val scan = CodexCommandHints.scan(emptyHome(), newRoot("acx-hints-empty").absolutePath, installed = emptyList())

        assertTrue(scan.whole)
        assertTrue(scan.hints.isEmpty())
    }

    @Test
    fun `a shelf whose anchor is gone too is a disk that stopped answering, not an empty project`() {
        // The whole difference the anchor buys. A project directory that is not there at all is what a
        // dead share, a sleeping WSL distribution or an unmounted volume look like from here - and read
        // as "this project simply has no skills" that is an empty map broadcast as the truth, every two
        // seconds, for as long as the share is out.
        val base = newRoot("acx-hints-stamp")
        write(base, ".codex/skills/keep/SKILL.md", "---\ndescription: readable\n---\nbody\n")
        val home = emptyHome()
        val vanished = File(base.parentFile, "acx-hints-never-existed-${System.nanoTime()}")

        val alive = CodexCommandHints.scan(home, base.absolutePath, installed = emptyList())
        val dead = CodexCommandHints.scan(home, vanished.absolutePath, installed = emptyList())

        assertTrue(alive.whole)
        assertFalse(dead.whole)
    }

    @Test
    fun `a plugin folder that is gone is not a whole walk`() {
        val scan = CodexCommandHints.scan(
            emptyHome(),
            newRoot("acx-hints-project").absolutePath,
            installed = listOf(
                InstalledPlugin(
                    id = "gone@market",
                    version = "1",
                    scope = "user",
                    enabled = true,
                    installPath = File(newRoot("acx-hints-plugin"), "never-there").absolutePath,
                ),
            ),
        )

        assertFalse(scan.whole)
    }

    @Test
    fun `a shelf that was simply deleted is still a whole walk`() {
        // The other half of the same rule, and the criterion that a removed skill leaves the hint at
        // once depends on it: after the deletion the anchor goes on answering, so nothing is held back.
        val base = newRoot("acx-hints-stamp")
        write(base, ".agents/skills/keep/SKILL.md", "---\ndescription: readable\n---\nbody\n")
        write(base, ".codex/skills/probe/SKILL.md", "---\ndescription: here\n---\nbody\n")
        val home = emptyHome()

        assertEquals("here", CodexCommandHints.scan(home, base.absolutePath, installed = emptyList()).hints["probe"]?.description)

        assertTrue(File(base, ".codex/skills").deleteRecursively())
        val after = CodexCommandHints.scan(home, base.absolutePath, installed = emptyList())

        assertTrue(after.whole)
        assertNull(after.hints["probe"])
        assertEquals("readable", after.hints["keep"]?.description)
    }

    @Test
    fun `a directory that will not list itself is a failed walk, and the rest is still read`() {
        val base = newRoot("acx-hints-stamp")
        val home = emptyHome()
        write(base, ".agents/skills/keep/SKILL.md", "---\ndescription: readable\n---\nbody\n")
        write(base, ".codex/skills/probe/SKILL.md", "---\ndescription: hidden away\n---\nbody\n")

        // Executable but not readable: the directory is plainly there and gives no listing - a share
        // that hiccupped, a folder owned by somebody else. Windows does not do POSIX modes.
        val skills = File(base, ".codex/skills")
        val locked = skills.setReadable(false, false)
        if (!locked || skills.listFiles() != null) return

        // Given back whatever happens, so that a failing check does not leave behind a directory nothing
        // can clean up.
        try {
            val scan = CodexCommandHints.scan(home, base.absolutePath, installed = emptyList())

            assertFalse(scan.whole)
            // Not an excuse to forget everything else: what could be read is read, and what to do about
            // an incomplete walk is decided by whoever broadcasts (see ProjectCatalog).
            assertEquals("readable", scan.hints["keep"]?.description)
            assertNull(scan.hints["probe"])
        } finally {
            skills.setReadable(true, false)
        }
    }

    /**
     * Two open projects walk their own disks, and what the ceiling is worth saying about is said on the
     * edge - so the memory of that edge belongs to whoever repeats the walk. Held as one for the whole
     * IDE, a project over the ceiling and one under it flipped it back and forth between them.
     *
     * The other half of the same rule is here too: a walk cut off by the ceiling is not a whole walk. The
     * shelves are walked in Codex's order of precedence, so it never reached the later ones at all.
     */
    @Test
    fun `the ceiling is remembered by whoever walks, not by the walk`() {
        val crowded = newRoot("acx-hints-crowded")
        val prompts = File(crowded, ".codex/prompts")
        prompts.mkdirs()
        // Exactly the walk's own ceiling of candidates - one more file changes nothing, one fewer is an
        // ordinary walk.
        repeat(4000) { File(prompts, "c$it.md").writeText("x") }

        val small = newRoot("acx-hints-small")
        write(small, ".codex/prompts/one.md", "x")

        val crowdedSaid = AtomicBoolean(false)
        val smallSaid = AtomicBoolean(false)

        val over = CodexCommandHints.scan(homeAt(crowded), null, emptyList(), crowdedSaid)
        val under = CodexCommandHints.scan(homeAt(small), null, emptyList(), smallSaid)

        assertFalse(over.whole)
        assertTrue(under.whole)
        assertEquals(4000, over.hints.size)
        assertTrue(crowdedSaid.get())
        assertFalse(smallSaid.get())
    }
}
