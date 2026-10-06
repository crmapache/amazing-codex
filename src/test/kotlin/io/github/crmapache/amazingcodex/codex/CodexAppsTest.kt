package io.github.crmapache.amazingcodex.codex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodexAppsTest {
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    private val plugin = InstalledPlugin("example@catalog", "1", "user", true)

    @Test
    fun `membership and URL come from Codex not from plugin name or tools`() {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        var snapshot: List<PluginAppGroup>? = null
        CodexApps.read(listOf(plugin), "/project", { method, params, success, _ ->
            calls += method to params
            success(json(when (method) {
                "plugin/list" -> """{"marketplaces":[{"name":"catalog","path":"/marketplace.json"}]}"""
                "plugin/read" -> """{"plugin":{"apps":[{"id":"connector-42","name":"Different app","installUrl":"https://chatgpt.com/apps/real/42"}],"appTemplates":[]}}"""
                "app/read" -> """{"apps":[{"id":"connector-42","name":"Canonical name","installUrl":"https://chatgpt.com/apps/real/42"}]}"""
                "app/list" -> if (params["cursor"] == null) """{"data":[{"id":"other"}],"nextCursor":"second"}""" else
                    """{"data":[{"id":"connector-42","isAccessible":true,"isEnabled":true}],"nextCursor":null}"""
                else -> """{"apps":[{"id":"connector-42","enabled":true,"callable":true}]}"""
            }))
        }, { snapshot = it }, { error("Unexpected failure: $it") })
        val app = snapshot!!.single().apps.single()
        assertEquals("connector-42", app.id)
        assertEquals("Canonical name", app.name)
        assertEquals("https://chatgpt.com/apps/real/42", app.installUrl)
        assertEquals(true, app.callable)
        assertFalse(app.needsAuth)
        assertEquals("/marketplace.json", calls.first { it.first == "plugin/read" }.second["marketplacePath"]?.toString()?.trim('"'))
        assertEquals(2, calls.count { it.first == "app/list" })
        assertEquals(listOf("true", "false"), calls.filter { it.first == "app/list" }.map { it.second["forceRefetch"].toString() })
        assertEquals("true", calls.last().second["forceRefresh"].toString())
    }

    @Test
    fun `workspace templates retain explicit blockers and materialized memberships`() {
        val group = CodexApps.group(plugin.id, json("""{"plugin":{"apps":[],"appTemplates":[
          {"templateId":"blocked","name":"Workspace files","materializedAppIds":[],"reason":"NO_ACTIVE_WORKSPACE"},
          {"templateId":"configured","name":"Knowledge","materializedAppIds":["a","b"],"reason":null}
        ]}}"""))
        assertEquals(listOf("blocked", "a", "b"), group.apps.map { it.id })
        assertEquals("NO_ACTIVE_WORKSPACE", group.apps.first().reason)
        assertNull(group.apps.first().installUrl)
    }

    @Test
    fun `administrator restrictions also apply to materialized template apps`() {
        val group = CodexApps.group(plugin.id, json("""{"plugin":{"apps":[{"id":"a","name":"App"}],
          "appTemplates":[{"templateId":"t","name":"Knowledge","materializedAppIds":["b"]}],
          "summary":{"availability":"DISABLED_BY_ADMIN","disabledReason":null}}}"""))
        assertEquals(listOf("disabled_by_admin", "disabled_by_admin"), group.apps.map { it.reason })
    }

    @Test
    fun `runtime policy wins over local app enablement`() {
        var snapshot: List<PluginAppGroup>? = null
        CodexApps.read(listOf(plugin), null, { method, _, success, _ ->
            success(json(when (method) {
                "plugin/list" -> """{"marketplaces":[]}"""
                "plugin/read" -> """{"apps":[{"id":"a","name":"App"}],"appTemplates":[]}"""
                "app/read" -> """{"apps":[]}"""
                "app/list" -> """{"data":[{"id":"a","isAccessible":true,"isEnabled":true}]}"""
                else -> """{"apps":[{"id":"a","enabled":false,"callable":false}]}"""
            }))
        }, { snapshot = it }, { error(it) })
        assertEquals(false, snapshot!!.single().apps.single().enabled)
        assertEquals(false, snapshot!!.single().apps.single().callable)
    }

    @Test
    fun `failed fresh directory does not reuse cached access as proof`() {
        var snapshot: List<PluginAppGroup>? = null
        CodexApps.read(listOf(plugin), null, { method, _, success, failure ->
            when (method) {
                "plugin/list" -> success(json("""{"marketplaces":[]}"""))
                "plugin/read" -> success(json("""{"apps":[{"id":"a","name":"App"}],"appTemplates":[]}"""))
                "app/read" -> success(json("""{"apps":[]}"""))
                "app/list" -> failure("Workspace denied access")
                else -> success(json("""{"apps":[]}"""))
            }
        }, { snapshot = it }, { error(it) })
        val app = snapshot!!.single().apps.single()
        assertNull(app.accessible)
        assertNull(app.enabled)
        assertEquals(false, app.callable)
        assertEquals("Workspace denied access", app.reason)
    }

    @Test
    fun `invalid snapshot and repeating cursor report failure`() {
        for (repeatCursor in listOf(false, true)) {
            var error: String? = null
            CodexApps.read(listOf(plugin), null, { method, _, success, _ ->
                success(json(when (method) {
                    "plugin/list" -> """{"marketplaces":[]}"""
                    "plugin/read" -> """{"apps":[{"id":"a","name":"App"}],"appTemplates":[]}"""
                    "app/read" -> """{"apps":[]}"""
                    "app/list" -> if (repeatCursor) """{"data":[],"nextCursor":"again"}""" else """{"data":[]}"""
                    else -> "{}"
                }))
            }, { error("Malformed result was accepted") }, { error = it })
            assertTrue(error?.contains(if (repeatCursor) "cursor" else "snapshot") == true)
        }
    }

    @Test
    fun `only app authentication errors mark sign-in needed`() {
        val failed = json("""{"type":"mcpToolCall","appContext":{"connectorId":"a"},"result":{"content":[],"structuredContent":{"error_code":"USER_NOT_LOGGED_IN"}}}""") as JsonObject
        assertEquals("a", CodexApps.authenticationFailure(failed))
        assertNull(CodexApps.authenticationFailure(json("""{"type":"mcpToolCall","appContext":{"connectorId":"a"},"result":{"content":[{"text":"The documentation describes USER_NOT_LOGGED_IN"}]}}""") as JsonObject))
        assertNull(CodexApps.authenticationFailure(json("""{"type":"mcpToolCall","error":{"message":"USER_NOT_LOGGED_IN"}}""") as JsonObject))
    }

    @Test
    fun `browser addresses must be HTTPS with no credentials`() {
        for (address in listOf("javascript:alert(1)", "file:///etc/passwd", "http://localhost:99", "https://user:secret@example.com", "broken")) {
            assertNull(CodexApps.connectionUrl(JsonPrimitive(address)))
        }
        assertEquals("https://example.com/oauth?state=abc", CodexApps.connectionUrl(json("\"https://example.com/oauth?state=abc\"")))
    }
}
