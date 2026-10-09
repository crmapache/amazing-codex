package io.github.crmapache.amazingcodex.codex

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

/**
 * A live Codex turn retold as the panel's events, in the order they happen (see CodexStream): a card goes
 * up when an item starts and is closed when it completes, and the turn ends with a `result`.
 */
class CodexStreamTest {

    private val lines = mutableListOf<String>()

    private val stream = CodexStream(emit = { lines += it }, model = { "gpt-5.6-sol" }, threadId = { "thread-1" })

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun emitted(): List<JsonObject> = lines.map(::json)

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content

    private fun JsonObject.content(): List<JsonObject> = this["message"]!!.jsonObject["content"]!!.jsonArray.map { it.jsonObject }

    private val running = json(
        """{"type":"commandExecution","id":"exec-1","command":"/bin/zsh -lc 'cat a.txt'","commandActions":[{"type":"read","command":"cat a.txt","name":"a.txt","path":"/p/a.txt"}],"status":"inProgress","aggregatedOutput":null,"exitCode":null}""",
    )
    private val ran = json(
        """{"type":"commandExecution","id":"exec-1","command":"/bin/zsh -lc 'cat a.txt'","commandActions":[{"type":"read","command":"cat a.txt","name":"a.txt","path":"/p/a.txt"}],"status":"completed","aggregatedOutput":"hello\n","exitCode":0}""",
    )
    private val patched = json(
        """{"type":"fileChange","id":"f1","changes":[{"path":"/p/x.ts","kind":{"type":"update","move_path":null},"diff":"@@ -1,2 +1,3 @@\n a\n+b\n c\n"}],"status":"completed"}""",
    )

    @Test
    fun `a tool card goes up when the item starts and is closed when it completes`() {
        stream.turnStarted(json("""{"id":"turn-1"}"""))
        stream.itemStarted(running)

        val use = emitted().single()
        assertEquals("assistant", use.text("type"))
        val call = use.content().single()
        assertEquals("tool_use", call.text("type"))
        assertEquals("exec-1", call.text("id"))
        assertEquals("Read", call.text("name"))
        assertEquals("gpt-5.6-sol", use["message"]!!.jsonObject.text("model"))

        lines.clear()
        stream.itemCompleted(ran)

        // Closed, not drawn a second time.
        val result = emitted().single()
        assertEquals("user", result.text("type"))
        assertEquals("exec-1-result", result.text("uuid"))
        val block = result.content().single()
        assertEquals("tool_result", block.text("type"))
        assertEquals("exec-1", block.text("tool_use_id"))
        assertEquals("hello\n", block.text("content"))
        assertNull(block["is_error"])
    }

    // An item Codex only reports once it is over still gets its card, and then its end.
    @Test
    fun `an item that completes without having started gets both halves`() {
        stream.turnStarted(json("""{"id":"turn-1"}"""))
        stream.itemCompleted(patched)

        val (use, result) = emitted()
        assertEquals("Edit", use.content().single().text("name"))
        assertEquals("f1", result.content().single().text("tool_use_id"))
    }

    @Test
    fun `the answer is typed out and then laid down whole with the step's usage`() {
        stream.turnStarted(json("""{"id":"turn-1"}"""))
        stream.agentDelta("Hel")
        stream.agentDelta("")
        stream.tokenUsage(json("""{"inputTokens":1000,"cachedInputTokens":800,"outputTokens":20,"totalTokens":1020}"""))
        stream.itemCompleted(json("""{"type":"agentMessage","id":"msg_1","text":"Hello."}"""))

        val (delta, answer) = emitted()
        assertEquals("stream_event", delta.text("type"))
        val inner = delta["event"]!!.jsonObject["delta"]!!.jsonObject
        assertEquals("text_delta", inner.text("type"))
        assertEquals("Hel", inner.text("text"))

        assertEquals("msg_1", answer.text("uuid"))
        assertEquals("Hello.", answer.content().single().text("text"))
        assertEquals("200", answer["message"]!!.jsonObject["usage"]!!.jsonObject.text("input_tokens"))
    }

    @Test
    fun `a remark cut off by Stop stays in the feed as far as it got`() {
        stream.turnStarted(json("""{"id":"turn-1"}"""))
        stream.itemStarted(json("""{"type":"agentMessage","id":"msg_1","text":""}"""))
        stream.agentDelta("The protocol began ")
        stream.agentDelta("in 2008")
        lines.clear()

        // Codex completes nothing: the turn just ends, interrupted.
        stream.turnCompleted(json("""{"id":"turn-1","status":"interrupted","durationMs":4000}"""))

        val (answer, result) = emitted()
        assertEquals("msg_1", answer.text("uuid"))
        assertEquals("The protocol began in 2008", answer.content().single().text("text"))
        assertEquals("result", result.text("type"))
    }

    @Test
    fun `a remark that completed is not laid down a second time when the turn ends`() {
        stream.turnStarted(json("""{"id":"turn-1"}"""))
        stream.itemStarted(json("""{"type":"agentMessage","id":"msg_1","text":""}"""))
        stream.agentDelta("Done.")
        stream.itemCompleted(json("""{"type":"agentMessage","id":"msg_1","text":"Done."}"""))
        lines.clear()

        stream.turnCompleted(json("""{"id":"turn-1","status":"completed"}"""))

        assertEquals(listOf("result"), emitted().map { it.text("type") })
    }

    @Test
    fun `a thought is typed out and laid down as thinking`() {
        stream.reasoningDelta("Hm")
        stream.itemCompleted(json("""{"type":"reasoning","id":"rs_1","summary":["Reading the file."],"content":[]}"""))
        // A thought with nothing in it draws nothing.
        stream.itemCompleted(json("""{"type":"reasoning","id":"rs_2","summary":[],"content":[]}"""))

        val (delta, thought) = emitted()
        assertEquals("thinking_delta", delta["event"]!!.jsonObject["delta"]!!.jsonObject.text("type"))
        assertEquals("Reading the file.", thought.content().single().text("thinking"))
    }

    @Test
    fun `the turn ends with its time, its last word and its usage`() {
        stream.turnStarted(json("""{"id":"turn-1"}"""))
        assertEquals("turn-1", stream.activeTurn)
        stream.tokenUsage(json("""{"inputTokens":10,"cachedInputTokens":0,"outputTokens":2}"""))
        stream.itemCompleted(json("""{"type":"agentMessage","id":"msg_1","text":"All done."}"""))
        lines.clear()

        stream.turnCompleted(json("""{"id":"turn-1","status":"completed","durationMs":12000}"""))

        val result = emitted().single()
        assertEquals("result", result.text("type"))
        assertEquals("success", result.text("subtype"))
        assertEquals("false", result.text("is_error"))
        assertEquals("12000", result.text("duration_ms"))
        assertEquals("All done.", result.text("result"))
        assertEquals("thread-1", result.text("session_id"))
        assertEquals("10", result["usage"]!!.jsonObject.text("input_tokens"))
        assertNull(stream.activeTurn)
    }

    /** A turn with no token count of its own must not sign its result with the previous turn's figures. */
    @Test
    fun `a turn's usage is its own and never the turn before's`() {
        stream.turnStarted(json("""{"id":"turn-1"}"""))
        stream.tokenUsage(json("""{"inputTokens":10,"cachedInputTokens":0,"outputTokens":2}"""))
        stream.turnCompleted(json("""{"id":"turn-1","status":"completed"}"""))
        lines.clear()

        stream.turnStarted(json("""{"id":"turn-2"}"""))
        stream.turnCompleted(json("""{"id":"turn-2","status":"failed","error":{"message":"Limit."}}"""))

        assertNull(emitted().single { it.text("type") == "result" }["usage"])
    }

    @Test
    fun `a turn Codex did not time is timed from its start`() {
        stream.turnStarted(json("""{"id":"turn-1"}"""), now = 1_000)
        stream.turnCompleted(json("""{"id":"turn-1","status":"completed"}"""), now = 4_500)

        assertEquals("3500", emitted().single().text("duration_ms"))
    }

    @Test
    fun `a turn's own start time is in seconds`() {
        stream.turnStarted(json("""{"id":"turn-1","startedAt":100}"""), now = 999_999)
        stream.turnCompleted(json("""{"id":"turn-1","status":"completed"}"""), now = 102_000)

        assertEquals("2000", emitted().single().text("duration_ms"))
    }

    @Test
    fun `a failed turn is an error with Codex's own words`() {
        stream.turnStarted(json("""{"id":"turn-1"}"""))
        stream.turnCompleted(json("""{"id":"turn-1","status":"failed","durationMs":5,"error":{"message":"The stream broke.","codexErrorInfo":{"responseStreamDisconnected":{"httpStatusCode":502}}}}"""))

        val result = emitted().single()
        assertEquals("true", result.text("is_error"))
        assertEquals("error_during_execution", result.text("subtype"))
        assertEquals("The stream broke.", result.text("result"))

        lines.clear()
        stream.turnCompleted(json("""{"id":"turn-2","status":"failed","durationMs":5}"""))
        assertEquals("The turn failed.", emitted().single().text("result"))
    }

    /**
     * A turn that failed on the sign-in is said the way the panel recognises it - an answer of the
     * client's own with `authentication_failed` beside it, which is what puts the sign-in door into the
     * feed (see AgentStream.isAuthFailure).
     */
    @Test
    fun `a turn refused for the sign-in is the answer that opens the sign-in door`() {
        stream.turnStarted(json("""{"id":"turn-1"}"""))
        stream.turnCompleted(json("""{"id":"turn-1","status":"failed","durationMs":5,"error":{"message":"Your access token expired.","codexErrorInfo":"unauthorized"}}"""))

        val (answer, result) = lines
        assertTrue(AgentStream.isAuthFailure(answer))
        val parsed = json(answer)
        assertEquals("authentication_failed", parsed.text("error"))
        assertEquals(CodexStream.SYNTHETIC, parsed["message"]!!.jsonObject.text("model"))
        assertEquals("Your access token expired.", parsed.content().single().text("text"))

        // Said once: the result that closes the turn is not a second red error.
        assertEquals("false", json(result).text("is_error"))
    }

    @Test
    fun `a turn stopped by the usage limit says so as the limit card`() {
        stream.turnStarted(json("""{"id":"turn-1"}"""))
        stream.turnCompleted(json("""{"id":"turn-1","status":"failed","error":{"message":"You've hit your usage limit.","codexErrorInfo":"usageLimitExceeded"}}"""))

        val (limit, refusal, result) = emitted()
        assertEquals("rate_limit_event", limit.text("type"))
        assertEquals("rejected", limit["rate_limit_info"]!!.jsonObject.text("status"))
        // Said as Claude Code's own placeholder answer, which is what a scenario run moves on (see
        // AgentStream.isLimitRefusal) - and so the result is not a second error under it.
        assertTrue(AgentStream.isLimitRefusal(refusal.toString()))
        assertEquals("false", result.text("is_error"))
    }

    /** The refusal names no window; the account's own limit said which one was full a moment before. */
    @Test
    fun `a turn stopped by the usage limit carries the window the account said was full`() {
        val told = CodexStream(emit = { lines += it }, model = { "gpt-5.5" }, threadId = { "thread-1" }, limitReached = { "five_hour" to 1_800_000_000L })
        told.turnStarted(json("""{"id":"turn-1"}"""))
        told.turnCompleted(json("""{"id":"turn-1","status":"failed","error":{"message":"limit","codexErrorInfo":"usageLimitExceeded"}}"""))

        val info = emitted().first()["rate_limit_info"]!!.jsonObject
        assertEquals("five_hour", info.text("rateLimitType"))
        assertEquals("1800000000", info.text("resetsAt"))
    }

    /** Codex's own step list for the turn is the task strip, sent the way the older whole-list call was. */
    @Test
    fun `a plan update is the task list, and the same list twice is one update`() {
        stream.turnStarted(json("""{"id":"turn-1"}"""))
        val plan = Json.parseToJsonElement(
            """[{"step":"Read the file","status":"completed"},{"step":"Fix it","status":"inProgress"},{"step":"Test","status":"pending"},{"step":" ","status":"pending"}]""",
        ).jsonArray

        stream.planUpdated(plan)
        stream.planUpdated(plan)

        val (use, answered) = emitted()
        val call = use.content().single()
        assertEquals(CodexStream.TODO_TOOL, call.text("name"))
        assertEquals("plan-turn-1-1", call.text("id"))
        val todos = (call["input"]!!.jsonObject["todos"] as JsonArray).map { it.jsonObject }
        assertEquals(listOf("Read the file", "Fix it", "Test"), todos.map { it.text("content") })
        assertEquals(listOf("completed", "in_progress", "pending"), todos.map { it.text("status") })
        assertEquals("plan-turn-1-1", answered.content().single().text("tool_use_id"))

        val moved = Json.parseToJsonElement("""[{"step":"Read the file","status":"completed"},{"step":"Fix it","status":"completed"}]""").jsonArray
        lines.clear()
        stream.planUpdated(moved)
        assertEquals("plan-turn-1-2", emitted().first().content().single().text("id"))
    }

    @Test
    fun `a proposed plan is drawn as the plan card and handed to the session`() {
        val proposal = stream.itemCompleted(json("""{"type":"plan","id":"p1","text":"  1. Read\n2. Fix  "}"""))

        assertEquals(CodexStream.PlanProposal("p1", "1. Read\n2. Fix"), proposal)
        assertEquals(CodexDialect.PLAN_TOOL, emitted().single().content().single().text("name"))

        lines.clear()
        assertNull(stream.itemCompleted(json("""{"type":"plan","id":"p2","text":"  "}""")))
        assertTrue(lines.isEmpty())
    }

    /** Retries are counted until the next event, which is the whole news about how they ended. */
    @Test
    fun `a request Codex will retry is the retry card, counted until something else happens`() {
        val error = json("""{"message":"Reconnecting... 1/5","codexErrorInfo":{"responseStreamConnectionFailed":{"httpStatusCode":503}}}""")

        stream.error(error, willRetry = true)
        stream.error(error, willRetry = true)
        stream.error(error, willRetry = false)
        stream.itemStarted(running)
        stream.error(json("""{"message":"slow down","codexErrorInfo":"serverOverloaded"}"""), willRetry = true)

        val retries = emitted().filter { it.text("subtype") == "api_retry" }
        assertEquals(listOf("1", "2", "1"), retries.map { it.text("attempt") })
        assertEquals(listOf("503", "503", "529"), retries.map { it.text("error_status") })
        assertEquals("Reconnecting... 1/5", retries.first().text("error"))
    }

    @Test
    fun `a compaction is announced when it starts and closed when it ends`() {
        stream.noteTokensBeforeCompaction(200_000)
        stream.itemStarted(json("""{"type":"contextCompaction","id":"c1"}"""))
        stream.itemCompleted(json("""{"type":"contextCompaction","id":"c1"}"""))
        // A second end with nothing running says nothing.
        stream.compactionFinished()

        val (started, ended) = emitted()
        assertEquals("compacting", started.text("status"))
        assertEquals("compact_boundary", ended.text("subtype"))
        assertEquals("200000", ended["compact_metadata"]!!.jsonObject.text("pre_tokens"))
        assertEquals("auto", ended["compact_metadata"]!!.jsonObject.text("trigger"))
        assertEquals(2, lines.size)
    }

    @Test
    fun `a compaction the person asked for is said to be manual, and only that one`() {
        stream.compactionAsked()
        stream.itemStarted(json("""{"type":"contextCompaction","id":"c1"}"""))
        stream.itemCompleted(json("""{"type":"contextCompaction","id":"c1"}"""))
        stream.itemStarted(json("""{"type":"contextCompaction","id":"c2"}"""))
        stream.itemCompleted(json("""{"type":"contextCompaction","id":"c2"}"""))

        val triggers = emitted().filter { it.text("subtype") == "compact_boundary" }
            .map { it["compact_metadata"]!!.jsonObject.text("trigger") }
        assertEquals(listOf("manual", "auto"), triggers)
    }

    @Test
    fun `a review's findings are the turn's answer`() {
        stream.turnStarted(json("""{"id":"turn-1"}"""))
        stream.itemCompleted(json("""{"type":"enteredReviewMode","id":"r0","review":"the working tree"}"""))
        stream.itemCompleted(json("""{"type":"exitedReviewMode","id":"r1","review":"One finding: a.kt:3."}"""))
        stream.turnCompleted(json("""{"id":"turn-1","status":"completed","durationMs":1}"""))

        val (answer, result) = emitted()
        assertEquals("One finding: a.kt:3.", answer.content().single().text("text"))
        assertEquals("One finding: a.kt:3.", result.text("result"))
    }

    @Test
    fun `the same findings said again as a message are not a second answer`() {
        stream.turnStarted(json("""{"id":"turn-1"}"""))
        stream.itemCompleted(json("""{"type":"exitedReviewMode","id":"r1","review":"No new bugs in this patch."}"""))
        stream.itemStarted(json("""{"type":"agentMessage","id":"m1","text":""}"""))
        stream.itemCompleted(json("""{"type":"agentMessage","id":"m1","text":"No new bugs in this patch.\n"}"""))
        // Anything else said afterwards is said.
        stream.itemCompleted(json("""{"type":"agentMessage","id":"m2","text":"Anything else?"}"""))

        val answers = emitted().filter { it.text("type") == "assistant" }.map { it.content().single().text("text") }
        assertEquals(listOf("No new bugs in this patch.", "Anything else?"), answers)
    }

    @Test
    fun `a model Codex swapped in is said as a fallback`() {
        stream.rerouted("gpt-5.6-sol", "gpt-5.6-codex", "highRiskCyberActivity")

        val line = emitted().single()
        assertEquals("model_refusal_fallback", line.text("subtype"))
        assertEquals("gpt-5.6-sol", line.text("originalModel"))
        assertEquals("gpt-5.6-codex", line.text("fallbackModel"))
        assertTrue(line.text("content").orEmpty().startsWith("Codex's safeguards flagged this request"))
    }

    @Test
    fun `an empty answer draws nothing`() {
        stream.itemCompleted(json("""{"type":"agentMessage","id":"msg_1","text":"   "}"""))
        stream.itemStarted(json("""{"type":"agentMessage","id":"msg_2","text":""}"""))

        assertFalse(lines.any())
    }
}
