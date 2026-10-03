package io.github.crmapache.amazingcodex.codex

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The parts of the translator that carry what the plugin this one was forked from learned after the fork:
 * extra limit buckets, the config's default model, the endings of a turn and its blocks of words, the
 * questions a thread's file holds, and whose a thread's name is.
 */
class CodexCatchUpTest {

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    // --- Limits ---------------------------------------------------------------------------------------

    @Test
    fun `an extra bucket with a window is a ring of its own, named by the server or by its id`() {
        val answer = json(
            """
            {"rateLimits": {"limitId": "codex", "primary": {"usedPercent": 12, "windowDurationMins": 300, "resetsAt": 1790000000}},
             "rateLimitsByLimitId": {
                "codex": {"limitId": "codex", "primary": {"usedPercent": 12, "windowDurationMins": 300, "resetsAt": 1790000000}},
                "premium": {"limitId": "premium", "limitName": null, "secondary": {"usedPercent": 40, "windowDurationMins": 10080, "resetsAt": 1790500000}},
                "nothing": {"limitId": "nothing", "limitName": null}
             }}
            """,
        )

        val limits = CodexShapes.usageAnswer(answer)["rate_limits"]!!.jsonObject
        val scoped = limits["model_scoped"]!!.jsonArray

        assertEquals(1, scoped.size)
        assertEquals("Premium", scoped[0].jsonObject["display_name"]!!.jsonPrimitive.content)
        assertEquals("40.0", scoped[0].jsonObject["utilization"]!!.jsonPrimitive.content)
        assertTrue(limits.containsKey("five_hour"))
    }

    @Test
    fun `a rolling update about another bucket is not the plan's`() {
        assertTrue(CodexShapes.isMainBucket(json("""{"limitId": "codex"}""")))
        assertTrue(CodexShapes.isMainBucket(json("""{"primary": {"usedPercent": 3}}""")))
        assertFalse(CodexShapes.isMainBucket(json("""{"limitId": "premium"}""")))
        assertEquals("Codex mini", CodexShapes.bucketLabel("codex_mini", ""))
        assertEquals("Named", CodexShapes.bucketLabel("x", " Named "))
    }

    @Test
    fun `without the bucket view nothing is said about extra rings`() {
        val limits = CodexShapes.usage(json("""{"primary": {"usedPercent": 1, "windowDurationMins": 300}}"""))["rate_limits"]!!.jsonObject
        assertFalse(limits.containsKey("model_scoped"))
    }

    // --- Models -----------------------------------------------------------------------------------------

    private val catalogue = json(
        """
        {"data": [
            {"model": "gpt-5.5", "displayName": "GPT-5.5", "isDefault": true, "defaultReasoningEffort": "xhigh",
             "supportedReasoningEfforts": [{"reasoningEffort": "low"}, {"reasoningEffort": "high"}, {"reasoningEffort": "xhigh"}]},
            {"model": "gpt-5.6-sol", "displayName": "GPT-5.6 Sol", "isDefault": false, "defaultReasoningEffort": "medium",
             "supportedReasoningEfforts": [{"reasoningEffort": "low"}, {"reasoningEffort": "max"}]}
        ]}
        """,
    )

    private fun defaults(models: JsonObject): Map<String, Pair<Boolean, String>> =
        models["models"]!!.jsonArray.associate {
            val o = it.jsonObject
            o["value"]!!.jsonPrimitive.content to (o["isDefault"]!!.jsonPrimitive.content.toBoolean() to o["defaultEffort"]!!.jsonPrimitive.content)
        }

    @Test
    fun `Default names the model the config sets, and auto the effort it sets where the model takes it`() {
        val shown = defaults(CodexShapes.models(catalogue, configuredModel = "gpt-5.6-sol", configuredEffort = "max"))

        assertEquals(false to "xhigh", shown["gpt-5.5"])
        assertEquals(true to "max", shown["gpt-5.6-sol"])
    }

    @Test
    fun `a config naming a model off the list leaves Codex's own default`() {
        val shown = defaults(CodexShapes.models(catalogue, configuredModel = "my-gateway-model"))

        assertEquals(true to "xhigh", shown["gpt-5.5"])
        assertEquals(false to "medium", shown["gpt-5.6-sol"])
    }

    // --- The turn ---------------------------------------------------------------------------------------

    private val lines = mutableListOf<String>()
    private val stream = CodexStream(emit = { lines += it }, model = { "gpt-5.6-sol" }, threadId = { "thread-1" })

    private fun events(): List<JsonObject> = lines.map(::json)

    private fun innerType(event: JsonObject): String? = (event["event"] as? JsonObject)?.get("type")?.jsonPrimitive?.content

    @Test
    fun `every remark of the agent starts a block of its own, said with its first words`() {
        stream.turnStarted(json("""{"id": "t1"}"""))
        stream.itemStarted(json("""{"type": "agentMessage", "id": "m1", "text": ""}"""))
        assertTrue(lines.isEmpty())
        stream.agentDelta("Reading the migration.")
        stream.itemCompleted(json("""{"type": "agentMessage", "id": "m1", "text": "Reading the migration.", "phase": "commentary"}"""))
        stream.itemStarted(json("""{"type": "agentMessage", "id": "m2", "text": ""}"""))
        stream.itemCompleted(json("""{"type": "agentMessage", "id": "m2", "text": "Writing the tests.", "phase": "commentary"}"""))

        val starts = events().count { innerType(it) == "content_block_start" }
        assertEquals(2, starts)
        assertEquals("content_block_start", innerType(events().first()))
    }

    @Test
    fun `a final answer and a Stop hook sending the agent back each end the model's say`() {
        stream.turnStarted(json("""{"id": "t1"}"""))
        stream.itemStarted(json("""{"type": "agentMessage", "id": "m1", "text": ""}"""))
        stream.itemCompleted(json("""{"type": "agentMessage", "id": "m1", "text": "Done: all green.", "phase": "final_answer"}"""))
        stream.itemStarted(json("""{"type": "hookPrompt", "id": "h1", "fragments": [{"hookRunId": "r1", "text": "Run the formatter."}]}"""))
        stream.itemCompleted(json("""{"type": "agentMessage", "id": "m2", "text": "Formatted.", "phase": "commentary"}"""))

        val ends = events().filter { innerType(it) == "message_delta" }
        assertEquals(2, ends.size)
        assertEquals(
            "end_turn",
            ends.first()["event"]!!.jsonObject["delta"]!!.jsonObject["stop_reason"]!!.jsonPrimitive.content,
        )
    }

    // --- History ----------------------------------------------------------------------------------------

    /** A thread's file as Codex 0.152 wrote one with a question in it - the shape measured live. */
    private fun rollout(answered: Boolean): java.io.File {
        val file = Files.createTempFile("rollout-acx", ".jsonl").toFile().apply { deleteOnExit() }
        val call = """{"type":"response_item","payload":{"type":"function_call","name":"request_user_input","call_id":"call_1",""" +
            """"arguments":"{\"questions\":[{\"header\":\"Colour\",\"id\":\"colour\",\"question\":\"Red or blue?\",""" +
            """\"options\":[{\"label\":\"Red\",\"description\":\"Choose red.\"},{\"label\":\"Blue\",\"description\":\"Choose blue.\"}]}]}"}}"""
        val output = """{"type":"response_item","payload":{"type":"function_call_output","call_id":"call_1","output":"{\"answers\":{\"colour\":{\"answers\":[\"Red\"]}}}"}}"""
        file.writeText(
            listOfNotNull(
                """{"type":"turn_context","payload":{"turn_id":"turn-1","cwd":"/p"}}""",
                call,
                output.takeIf { answered },
            ).joinToString("\n") + "\n",
        )
        return file
    }

    @Test
    fun `a question and its answer are read off the thread's file by turn`() {
        val asks = CodexHistory.asksOf(rollout(answered = true))

        val ask = asks["turn-1"]!!.single()
        assertEquals("call_1", ask.callId)
        assertEquals(mapOf("Red or blue?" to "Red"), ask.answers)
        assertEquals("Red or blue?", (ask.input["questions"] as JsonArray)[0].jsonObject["question"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a question comes back before the turn's answer, with the person's choice beside it`() {
        val asks = CodexHistory.asksOf(rollout(answered = true))["turn-1"].orEmpty()
        val turn = json(
            """{"id": "turn-1", "status": "completed", "items": [
                {"type": "userMessage", "id": "u1", "content": [{"type": "text", "text": "Pick a colour"}]},
                {"type": "agentMessage", "id": "a1", "text": "Red", "phase": "final_answer"}]}""",
        )

        val replayed = CodexReplay.lines(turn, outputLimit = 1000, closeTurn = true, asks = asks).map(::json)
        val kinds = replayed.map { it["type"]!!.jsonPrimitive.content }

        assertEquals(listOf("user", "assistant", "user", "assistant", "result"), kinds)
        assertEquals("Red", replayed[2]["toolUseResult"]!!.jsonObject["answers"]!!.jsonObject["Red or blue?"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a question comes back where it was asked, after what the agent had said, with the time it was answered`() {
        val file = Files.createTempFile("rollout-acx", ".jsonl").toFile().apply { deleteOnExit() }
        file.writeText(
            listOf(
                """{"type":"turn_context","payload":{"turn_id":"turn-1","cwd":"/p"}}""",
                """{"timestamp":"t0","type":"response_item","payload":{"type":"message","id":"msg_u","role":"user","content":[]}}""",
                """{"timestamp":"t1","type":"response_item","payload":{"type":"message","id":"msg_a","role":"assistant","content":[]}}""",
                """{"timestamp":"t2","type":"response_item","payload":{"type":"function_call","name":"request_user_input","call_id":"call_1",""" +
                    """"arguments":"{\"questions\":[{\"id\":\"n\",\"question\":\"How many?\",\"options\":[{\"label\":\"5\",\"description\":\"Five.\"}]}]}"}}""",
                """{"timestamp":"2026-10-02T23:39:10Z","type":"response_item","payload":{"type":"function_call_output","call_id":"call_1","output":"{\"answers\":{\"n\":{\"answers\":[\"5\"]}}}"}}""",
            ).joinToString("\n") + "\n",
        )
        val asks = CodexHistory.asksOf(file)["turn-1"].orEmpty()
        assertEquals("msg_a", asks.single().after)

        val turn = json(
            """{"id": "turn-1", "status": "completed", "items": [
                {"type": "userMessage", "id": "u1", "content": [{"type": "text", "text": "Plan it"}]},
                {"type": "agentMessage", "id": "msg_a", "text": "First, a question.", "phase": "commentary"},
                {"type": "plan", "id": "p1", "text": "1. Five tries"}]}""",
        )
        val replayed = CodexReplay.lines(turn, outputLimit = 1000, closeTurn = true, asks = asks).map(::json)
        val said = replayed.map { line ->
            val block = (line["message"] as? JsonObject)?.get("content")?.jsonArray?.firstOrNull()?.jsonObject
            block?.get("text")?.jsonPrimitive?.content ?: block?.get("name")?.jsonPrimitive?.content ?: line["type"]!!.jsonPrimitive.content
        }

        // The person's message, the agent's remark, the question, its answer, the plan, the turn's end.
        assertEquals(6, said.size)
        assertEquals("First, a question.", said[1])
        assertEquals(CodexLaunch.ASK_TOOL, said[2])
        assertEquals("2026-10-02T23:39:10Z", replayed[3]["timestamp"]!!.jsonPrimitive.content)
        assertEquals(CodexDialect.PLAN_TOOL, said[4])
    }

    @Test
    fun `a turn that stopped on a question nobody answered is left open for it`() {
        val asks = CodexHistory.asksOf(rollout(answered = false))["turn-1"].orEmpty()
        assertNull(asks.single().answers)

        val turn = json(
            """{"id": "turn-1", "status": "interrupted", "items": [
                {"type": "userMessage", "id": "u1", "content": [{"type": "text", "text": "Pick a colour"}]}]}""",
        )
        val replayed = CodexReplay.lines(turn, outputLimit = 1000, closeTurn = true, asks = asks).map(::json)

        assertEquals("assistant", replayed.last()["type"]!!.jsonPrimitive.content)
        assertFalse(replayed.any { it["type"]!!.jsonPrimitive.content == "result" })
    }

    @Test
    fun `the editor note is not the person's words, and comes back as the panel's note`() {
        val item = json(
            """{"type": "userMessage", "id": "u1", "content": [
                {"type": "text", "text": "Why is this slow?"},
                {"type": "text", "text": "# Context from my IDE setup:\n\n## Active file: src/a.kt\n\nThis may or may not be related to the current task."}]}""",
        )

        assertEquals("Why is this slow?" to 0, CodexDialect.userTextOf(item))
        val prompt = json(CodexDialect.userPrompt("Why is this slow?", 0, "u1", null, CodexDialect.userContextOf(item)))
        val blocks = prompt["message"]!!.jsonObject["content"]!!.jsonArray
        assertEquals(2, blocks.size)
        assertTrue(blocks[1].jsonObject["text"]!!.jsonPrimitive.content.startsWith("<system-reminder>\nThe user opened the file src/a.kt"))
    }

    // --- Names ------------------------------------------------------------------------------------------

    @Test
    fun `a thread's name is the model's only when the model gave exactly that name`() {
        assertEquals(SessionSnapshot.TITLE_HEURISTIC, AutoTitles.sourceOf("", "Fix the search"))
        assertEquals(SessionSnapshot.TITLE_LLM, AutoTitles.sourceOf("Fix the search", "Fix the search"))
        assertEquals(SessionSnapshot.TITLE_USER, AutoTitles.sourceOf("Search, fixed", "Fix the search"))
        assertEquals(SessionSnapshot.TITLE_USER, AutoTitles.sourceOf("Search, fixed", null))
    }

    @Test
    fun `utf-8 weight counts what a relay frame counts`() {
        assertEquals(5, CodexHistory.utf8Bytes("hello"))
        assertEquals(12, CodexHistory.utf8Bytes("привет"))
        assertEquals(4, CodexHistory.utf8Bytes("😀"))
        assertEquals(JsonPrimitive("x"), JsonPrimitive("x"))
    }
}
