package io.github.crmapache.amazingcodex.codex

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Codex's answers about the account and the session, in the shapes the usage rings, the model menu and
 * the MCP screen read (see CodexShapes).
 */
class CodexShapesTest {

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content

    // --- Models -----------------------------------------------------------------------

    @Test
    fun `the model list keeps what the menu draws and drops what Codex hides`() {
        val models = CodexShapes.models(
            json(
                """{"data":[
                  {"id":"gpt-5.6-sol","model":"gpt-5.6-sol","displayName":"GPT-5.6 Sol","description":"Frontier",
                   "isDefault":true,"defaultReasoningEffort":"medium",
                   "supportedReasoningEfforts":[{"reasoningEffort":"low","description":"l"},{"reasoningEffort":"high","description":"h"}]},
                  {"id":"gpt-5.1-codex-mini","model":"","displayName":"","hidden":false},
                  {"id":"secret","model":"secret","hidden":true},
                  {"displayName":"no id at all"}
                ],"nextCursor":null}""",
            ),
        )["models"]!!.jsonArray.map { it.jsonObject }

        assertEquals(listOf("gpt-5.6-sol", "gpt-5.1-codex-mini"), models.map { it.text("value") })

        val sol = models[0]
        assertEquals("GPT-5.6 Sol", sol.text("displayName"))
        assertEquals("Frontier", sol.text("description"))
        assertEquals("gpt-5.6-sol", sol.text("resolvedModel"))
        assertEquals("true", sol.text("isDefault"))
        assertEquals("medium", sol.text("defaultEffort"))
        assertEquals(listOf("low", "high"), (sol["efforts"] as JsonArray).map { (it as JsonPrimitive).content })

        // The id stands in for a name that was not given, and a model that says nothing is not the default.
        val mini = models[1]
        assertEquals("gpt-5.1-codex-mini", mini.text("displayName"))
        assertEquals("false", mini.text("isDefault"))
        assertEquals(0, (mini["efforts"] as JsonArray).size)
    }

    @Test
    fun `no answer is an empty list, not a failure`() {
        assertEquals(0, CodexShapes.models(null)["models"]!!.jsonArray.size)
        assertEquals(0, CodexShapes.models(JsonPrimitive("x"))["models"]!!.jsonArray.size)
    }

    // --- Limits -----------------------------------------------------------------------

    private fun seconds(epoch: Long): String = Instant.ofEpochSecond(epoch).toString()

    /**
     * Each window goes to the ring its length belongs to rather than by its name: the five-hour window is
     * "primary" on one plan and the only window on another.
     */
    @Test
    fun `the windows go to the rings by their length`() {
        val limits = CodexShapes.usage(
            json(
                """{"primary":{"usedPercent":12.5,"windowDurationMins":10080,"resetsAt":1789900000},
                    "secondary":{"usedPercent":40,"windowDurationMins":300,"resetsAt":1789820000}}""",
            ),
        )["rate_limits"]!!.jsonObject

        val short = limits["five_hour"]!!.jsonObject
        assertEquals("40.0", short.text("utilization"))
        assertEquals(seconds(1_789_820_000), short.text("resets_at"))

        val long = limits["seven_day"]!!.jsonObject
        assertEquals("12.5", long.text("utilization"))
        assertEquals(seconds(1_789_900_000), long.text("resets_at"))
        assertNull(limits["extra_usage"])
    }

    @Test
    fun `one window of unknown length is the short ring`() {
        val limits = CodexShapes.usage(json("""{"primary":{"usedPercent":7}}"""))["rate_limits"]!!.jsonObject

        assertEquals("7.0", limits["five_hour"]!!.jsonObject.text("utilization"))
        assertNull(limits["seven_day"])
    }

    @Test
    fun `a week-long window alone is the long ring`() {
        val limits = CodexShapes.usage(json("""{"primary":{"usedPercent":3,"windowDurationMins":10080}}"""))["rate_limits"]!!.jsonObject

        assertNull(limits["five_hour"])
        assertEquals("3.0", limits["seven_day"]!!.jsonObject.text("utilization"))
    }

    /** A business seat's spend limit is money counted against a cap somebody set: the third ring. */
    @Test
    fun `a spend limit is drawn on the ring for usage past the plan`() {
        val byPercent = CodexShapes.usage(json("""{"individualLimit":{"remainingPercent":30,"resetsAt":1789900000}}"""))["rate_limits"]!!.jsonObject
        val extra = byPercent["extra_usage"]!!.jsonObject
        assertEquals("true", extra.text("is_enabled"))
        assertEquals("70", extra.text("utilization"))
        assertEquals(seconds(1_789_900_000), extra.text("resets_at"))

        val byMoney = CodexShapes.usage(json("""{"individualLimit":{"used":"25","limit":"100"}}"""))["rate_limits"]!!.jsonObject
        assertEquals("25", byMoney["extra_usage"]!!.jsonObject.text("utilization"))

        val unknown = CodexShapes.usage(json("""{"individualLimit":{"used":"5","limit":"0"}}"""))["rate_limits"]!!.jsonObject
        assertNull(unknown["extra_usage"]!!.jsonObject["utilization"])
    }

    @Test
    fun `no snapshot is no rings, and a window size is the session's`() {
        val nothing = CodexShapes.usage(null)
        assertEquals(JsonObject(emptyMap()), nothing["rate_limits"])
        assertNull(nothing["session"])

        val withWindow = CodexShapes.usage(null, contextWindow = 258_400)
        assertEquals(
            "258400",
            withWindow["session"]!!.jsonObject["model_usage"]!!.jsonObject["codex"]!!.jsonObject.text("contextWindow"),
        )
        assertNull(CodexShapes.usage(null, contextWindow = 0)["session"])
    }

    @Test
    fun `the window that stopped the work and when it opens again`() {
        val snapshot = json(
            """{"rateLimitReachedType":"primary",
                "primary":{"usedPercent":100,"resetsAt":1789820000},
                "secondary":{"usedPercent":100,"resetsAt":1789900000}}""",
        )

        assertEquals("primary", CodexShapes.reachedWindow(snapshot))
        assertEquals(1_789_900_000L, CodexShapes.resetOf(snapshot))

        val open = json("""{"primary":{"usedPercent":55,"resetsAt":1789820000}}""")
        assertNull(CodexShapes.reachedWindow(open))
        assertNull(CodexShapes.resetOf(open))
        assertNull(CodexShapes.resetOf(null))
    }

    // --- MCP servers --------------------------------------------------------------------

    private fun servers(live: String?, configured: String?, startup: Map<String, CodexShapes.Startup> = emptyMap()): Map<String, JsonObject> =
        CodexShapes.mcpStatus(
            live?.let { Json.parseToJsonElement(it) },
            configured?.let { Json.parseToJsonElement(it).jsonArray },
            startup,
        )["mcpServers"]!!.jsonArray.map { it.jsonObject }.associateBy { it.text("name")!! }

    @Test
    fun `every server is named once, with its status in the MCP screen's words`() {
        val list = servers(
            live = """{"data":[
                {"name":"context7","runtimeStatus":"connected"},
                {"name":"linear","runtimeStatus":"authenticationRequired"},
                {"name":"broken","runtimeStatus":"failed"},
                {"name":"from-plugin","runtimeStatus":"connected","pluginId":"tools@market"}
            ]}""",
            configured = """[
                {"name":"context7","enabled":true,"transport":{"type":"stdio","command":"npx","args":["-y","@upstash/context7-mcp"]}},
                {"name":"linear","enabled":true,"transport":{"type":"streamable_http","url":"https://mcp.linear.app/mcp"}},
                {"name":"broken","enabled":true,"transport":{"type":"stdio","command":"nope"}},
                {"name":"off","enabled":false,"transport":{"type":"stdio","command":"x"}},
                {"name":"slow","enabled":true,"transport":{"type":"stdio","command":"y"}}
            ]""",
            startup = mapOf("broken" to CodexShapes.Startup("failed", "spawn nope ENOENT", reauthenticate = false)),
        )

        assertEquals(setOf("context7", "linear", "broken", "off", "slow", "from-plugin"), list.keys)
        assertEquals("connected", list["context7"]!!.text("status"))
        assertEquals("needs-auth", list["linear"]!!.text("status"))
        assertEquals("failed", list["broken"]!!.text("status"))
        assertEquals("spawn nope ENOENT", list["broken"]!!.text("error"))
        assertEquals("disabled", list["off"]!!.text("status"))
        assertEquals("pending", list["slow"]!!.text("status"))

        assertEquals("user", list["context7"]!!.text("scope"))
        assertEquals("plugin", list["from-plugin"]!!.text("scope"))

        val config = list["context7"]!!["config"]!!.jsonObject
        assertEquals("stdio", config.text("type"))
        assertEquals("npx", config.text("command"))
        assertEquals(2, (config["args"] as JsonArray).size)
        assertEquals("https://mcp.linear.app/mcp", list["linear"]!!["config"]!!.jsonObject.text("url"))
    }

    @Test
    fun `a server nobody configured is Codex's own`() {
        val list = servers(live = """{"data":[{"name":"codex_apps","runtimeStatus":"connected"}]}""", configured = null)

        assertEquals("builtin", list["codex_apps"]!!.text("scope"))
        assertEquals(JsonObject(emptyMap()), list["codex_apps"]!!["config"])
    }

    /** Before the live list arrives, the start-up notifications are all there is. */
    @Test
    fun `the start-up notifications stand in for the live list`() {
        val list = servers(
            live = null,
            configured = """[{"name":"a","enabled":true},{"name":"b","enabled":true},{"name":"c","enabled":true,"auth_status":"notLoggedIn"}]""",
            startup = mapOf(
                "a" to CodexShapes.Startup("ready", null, reauthenticate = false),
                "b" to CodexShapes.Startup("starting", null, reauthenticate = false),
                "c" to CodexShapes.Startup("ready", null, reauthenticate = false),
                "d" to CodexShapes.Startup("failed", null, reauthenticate = true),
            ),
        )

        assertEquals("connected", list["a"]!!.text("status"))
        assertEquals("pending", list["b"]!!.text("status"))
        assertEquals("needs-auth", list["c"]!!.text("status"))
        assertEquals("needs-auth", list["d"]!!.text("status"))
        assertEquals("The server wants you to sign in again.", list["d"]!!.text("error"))
        assertNull(list["a"]!!["error"])
    }

    // --- The context meter --------------------------------------------------------------

    @Test
    fun `the window holds what went in and came out, less the model's own reasoning`() {
        val usage = json("""{"last":{"totalTokens":20000,"reasoningOutputTokens":500},"modelContextWindow":258400}""")

        assertEquals(19_500 to 258_400, CodexShapes.contextOf(usage))
    }

    @Test
    fun `a count that cannot be read is no reading`() {
        assertNull(CodexShapes.contextOf(null))
        assertNull(CodexShapes.contextOf(json("""{"modelContextWindow":258400}""")))
        assertNull(CodexShapes.contextOf(json("""{"last":{"totalTokens":1}}""")))
        assertNull(CodexShapes.contextOf(json("""{"last":{"totalTokens":1},"modelContextWindow":0}""")))
        assertNull(CodexShapes.contextOf(json("""{"last":{},"modelContextWindow":100}""")))
        // Reasoning is optional, and never takes the count below nothing.
        assertEquals(5 to 100, CodexShapes.contextOf(json("""{"last":{"totalTokens":5},"modelContextWindow":100}""")))
        assertEquals(0 to 100, CodexShapes.contextOf(json("""{"last":{"totalTokens":5,"reasoningOutputTokens":9},"modelContextWindow":100}""")))
    }
}
