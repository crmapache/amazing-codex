package io.github.crmapache.amazingcodex.codex

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeProjectConfigTest {

    private fun project(): File = Files.createTempDirectory("acx-claude-project").toFile()

    @Test
    fun `a project without Claude configuration keeps its briefing untouched`() {
        val project = project()

        assertEquals("role", ClaudeProjectConfig.appendTo("role", project.absolutePath))
        assertNull(ClaudeProjectConfig.skillsRoot(project.absolutePath))
    }

    @Test
    fun `the shared configuration contract is attached without loading every rule`() {
        val project = project()
        File(project, ".claude/rules").mkdirs()

        val briefing = ClaudeProjectConfig.appendTo("role", project.absolutePath)

        assertTrue(briefing.startsWith("role\n\n"))
        assertTrue(".claude/rules" in briefing)
        assertTrue("Do not load unrelated rule bodies" in briefing)
        assertTrue(".claude/skills" in briefing)
        assertTrue("permission mode selected in\nthis panel remains authoritative" in briefing)
        assertFalse("source text of a rule" in briefing)
    }

    @Test
    fun `the live shared skill shelf is registered by its absolute path`() {
        val project = project()
        File(project, ".claude/skills/deploy").mkdirs()

        assertEquals(
            File(project, ".claude/skills").absolutePath,
            ClaudeProjectConfig.skillsRoot(project.absolutePath),
        )
    }

    @Test
    fun `old thread without the contract marker needs one injection`() {
        val project = project()
        val old = File(project, "old.jsonl").apply { writeText("{\"developerInstructions\":\"old\"}\n") }
        val current = File(project, "current.jsonl").apply {
            writeText("{\"developerInstructions\":\"Shared-config contract: amazing-codex:.claude:v1\"}\n")
        }

        assertTrue(ClaudeProjectConfig.lacksContract(null))
        assertTrue(ClaudeProjectConfig.lacksContract(old))
        assertFalse(ClaudeProjectConfig.lacksContract(current))
    }
}
