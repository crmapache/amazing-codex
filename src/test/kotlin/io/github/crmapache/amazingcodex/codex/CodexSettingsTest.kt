package io.github.crmapache.amazingcodex.codex

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The few top-level keys the panel reads out of Codex's `config.toml` - by line, not by a TOML parser, so
 * the rules of what is read and what is left alone are held here.
 */
class CodexSettingsTest {

    @Test
    fun `top-level keys are read, unquoted, and the first table ends them`() {
        val values = CodexSettings.topLevel(
            """
            # a comment line
            model = "gpt-5.6-sol"
            model_reasoning_effort = 'high'
            approval_policy = "on-request" # trailing comment
            "sandbox_mode" = "workspace-write"
            hide_agent_reasoning = true

            [profiles.fast]
            model = "gpt-5.1-codex-mini"
            """.trimIndent(),
        )

        assertEquals("gpt-5.6-sol", values["model"])
        assertEquals("high", values["model_reasoning_effort"])
        assertEquals("on-request", values["approval_policy"])
        assertEquals("workspace-write", values["sandbox_mode"])
        assertEquals("true", values["hide_agent_reasoning"])
        assertEquals(5, values.size)
    }

    @Test
    fun `a hash inside quotes is part of the value`() {
        val values = CodexSettings.topLevel("""notify = "say #done" # not this""")

        assertEquals("say #done", values["notify"])
    }

    @Test
    fun `a line that is not a key and a value is left alone`() {
        val values = CodexSettings.topLevel("just words\n= orphan\nkey = \"value\"")

        assertEquals(mapOf("key" to "value"), values)
    }

    @Test
    fun `a file that is not there says nothing`() {
        val missing = File(Files.createTempDirectory("acx-settings").toFile(), "config.toml")

        assertEquals("", CodexSettings.value(missing, "model"))
    }

    @Test
    fun `a key a file does not hold is empty`() {
        val file = Files.createTempFile("acx-settings", ".toml").toFile().apply {
            deleteOnExit()
            writeText("model = \"gpt-5.6-sol\"\n")
        }

        assertEquals("gpt-5.6-sol", CodexSettings.value(file, "model"))
        assertEquals("", CodexSettings.value(file, "approval_policy"))
    }

    /** An untrusted project's own settings are read by nobody - Codex skips the layer, so does the panel. */
    @Test
    fun `an untrusted project has no layer, and the person's file outranks the system's`() {
        val project = Files.createTempDirectory("acx-settings-project").toFile()
        val sources = CodexSettings.sources(project.absolutePath)

        assertEquals(listOf(CodexSettings.Layer.USER, CodexSettings.Layer.POLICY), sources.map { it.layer })
        assertEquals(File(HostOs.managedSettingsDirectory(), "config.toml"), sources[1].file)
    }

    @Test
    fun `without a project there is no project layer`() {
        assertEquals(
            listOf(CodexSettings.Layer.USER, CodexSettings.Layer.POLICY),
            CodexSettings.sources(null).map { it.layer },
        )
    }

    @Test
    fun `a project is trusted by its own table in the person's file, and by nothing else`() {
        val project = Files.createTempDirectory("acx-trusted.project").toFile()
        val config = Files.createTempFile("acx-settings", ".toml").toFile().apply { deleteOnExit() }

        config.writeText("model = \"gpt-5.5\"\n\n[projects.\"/somewhere/else\"]\ntrust_level = \"trusted\"\n")
        assertFalse(CodexSettings.trusted(config, project.absolutePath))

        config.writeText("[projects.\"${project.absolutePath}\"]\ntrust_level = \"untrusted\"\n")
        assertFalse(CodexSettings.trusted(config, project.absolutePath))

        // Under the path the disk resolves it to, as Codex writes it when it was started there.
        config.writeText("[projects]\n\n[projects.\"${project.canonicalPath}\"]\ntrust_level = \"trusted\" # by codex\n[tui]\n")
        assertTrue(CodexSettings.trusted(config, project.absolutePath))
    }
}
