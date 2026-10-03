package io.github.crmapache.amazingcodex.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Codex's settings as the screen `/config` opens shows them, out of Codex's own answers - shaped after
 * what `config/read`, `configRequirements/read` and `experimentalFeature/list` returned live on 0.152.
 */
class CodexConfigTest {

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private val read = json(
        """
        {"config": {"model": "gpt-5.6-sol", "model_verbosity": "low", "web_search": "cached",
                    "sandbox_workspace_write": {"network_access": true}, "approval_policy": "on-request"},
         "origins": {
            "model": {"name": {"type": "user", "file": "/u/.codex/config.toml"}, "version": "sha256:u"},
            "model_verbosity": {"name": {"type": "project", "dotCodexFolder": "/p/.codex"}, "version": "sha256:p"},
            "approval_policy": {"name": {"type": "mdm", "domain": "com.openai.codex", "key": "x"}, "version": "sha256:m"}
         },
         "layers": [
            {"name": {"type": "project", "dotCodexFolder": "/p/.codex"}, "version": "sha256:p", "disabledReason": null,
             "config": {"model_verbosity": "low", "forced_login_method": "chatgpt", "tui": {"theme": "dark"}}},
            {"name": {"type": "user", "file": "/u/.codex/config.toml"}, "version": "sha256:u", "config": {"model": "gpt-5.6-sol"}}
         ]}
        """,
    )

    private val requirements = json("""{"allowedApprovalPolicies": ["on-request", "never"], "allowedSandboxModes": null}""")

    private val features = json(
        """
        {"data": [
            {"name": "prevent_idle_sleep", "stage": "beta", "displayName": "Prevent sleep while running", "enabled": false},
            {"name": "shell_tool", "stage": "stable", "enabled": true},
            {"name": "transcript_v2", "stage": "underDevelopment", "enabled": false}
        ]}
        """,
    )

    private fun setting(key: String) = CodexConfig.settings(read, requirements, features).first { it.key == key }

    @Test
    fun `values are read by their dotted keys, nested tables included`() {
        assertEquals("gpt-5.6-sol", setting("model").value)
        assertEquals("true", setting("sandbox_workspace_write.network_access").value)
        assertNull(setting("personality").value)
    }

    @Test
    fun `a value the project or a policy sets is locked, and says by whom`() {
        assertEquals("project", setting("model_verbosity").lockedBy)
        assertEquals("policy", setting("approval_policy").lockedBy)
        assertNull(setting("model").lockedBy)
    }

    @Test
    fun `an organisation narrows the options it allows, and only those`() {
        assertEquals(listOf("on-request", "never"), setting("approval_policy").options)
        assertEquals(listOf("read-only", "workspace-write", "danger-full-access"), setting("sandbox_mode").options)
    }

    @Test
    fun `only the experimental features a person is offered are rows`() {
        val keys = CodexConfig.settings(read, requirements, features).map { it.key }

        assertTrue("features.prevent_idle_sleep" in keys)
        assertFalse("features.shell_tool" in keys)
        assertFalse("features.transcript_v2" in keys)
        assertEquals("false", setting("features.prevent_idle_sleep").value)
    }

    @Test
    fun `a value is written as the setting takes it, and nothing else is written at all`() {
        assertEquals(JsonPrimitive(true), CodexConfig.wireValue("sandbox_workspace_write.network_access", "true", listOf("true", "false")))
        assertEquals(JsonPrimitive(200000L), CodexConfig.wireValue("model_auto_compact_token_limit", "200000", emptyList()))
        assertEquals(JsonPrimitive("low"), CodexConfig.wireValue("model_verbosity", "low", listOf("low", "medium", "high")))
        assertEquals(JsonNull, CodexConfig.wireValue("model", "  ", emptyList()))

        assertNull(CodexConfig.wireValue("model_verbosity", "loud", listOf("low", "medium", "high")))
        assertNull(CodexConfig.wireValue("model_auto_compact_token_limit", "a lot", emptyList()))
        assertFalse(CodexConfig.writable("developer_instructions", emptySet()))
        assertFalse(CodexConfig.writable("features.shell_tool", setOf("prevent_idle_sleep")))
        assertTrue(CodexConfig.writable("features.prevent_idle_sleep", setOf("prevent_idle_sleep")))
    }

    @Test
    fun `the project's own layer is told by name, with what it sets and what it demands`() {
        val layer = CodexConfig.projectLayer(read)

        assertTrue(layer.present)
        assertTrue(layer.trusted)
        assertEquals(listOf("forced_login_method", "model_verbosity", "tui.theme"), layer.sets)
        assertEquals("chatgpt", layer.forcedLogin)
        assertEquals("sha256:u", CodexConfig.userVersion(read))
    }

    @Test
    fun `an untrusted project's layer is present and not read`() {
        val untrusted = json(
            """{"config": {}, "origins": {}, "layers": [{"name": {"type": "project", "dotCodexFolder": "/p/.codex"}, "version": "v",
                "disabledReason": "To load project-local config, add /p as a trusted project.", "config": {"model": "x"}}]}""",
        )

        val layer = CodexConfig.projectLayer(untrusted)

        assertTrue(layer.present)
        assertFalse(layer.trusted)
        assertEquals(emptyList(), CodexConfig.demands(layer, method = "api", workspace = ""))
        assertFalse(CodexConfig.projectLayer(json("""{"config": {}, "origins": {}, "layers": []}""")).present)
    }

    @Test
    fun `a trusted project demanding another sign-in names what stands in the way`() {
        val layer = CodexConfig.ProjectLayer(present = true, trusted = true, sets = emptyList(), forcedLogin = "chatgpt", forcedWorkspace = "ws-1")

        assertEquals(listOf("forced_login_method"), CodexConfig.demands(layer, method = "api", workspace = "key-abc"))
        assertEquals(listOf("forced_chatgpt_workspace_id"), CodexConfig.demands(layer, method = "chatgpt", workspace = "ws-2"))
        assertEquals(emptyList(), CodexConfig.demands(layer, method = "chatgpt", workspace = "ws-1"))
        // A credential that would not say is not judged.
        assertEquals(emptyList(), CodexConfig.demands(layer, method = "", workspace = ""))
    }
}
