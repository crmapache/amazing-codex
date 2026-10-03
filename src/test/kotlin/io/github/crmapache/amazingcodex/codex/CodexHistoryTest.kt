package io.github.crmapache.amazingcodex.codex

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The parts of the history that do not need a Codex process: what a conversation file says about itself
 * (its thread, its working directory, its model, how full its window was), and a past turn retold in the
 * panel's language (CodexReplay). The list and the pages themselves are asked of `codex app-server`.
 */
class CodexHistoryTest {

    private val thread = "01a0b94e-0c89-7131-9bb1-aeb769fee104"

    private fun rollout(vararg lines: String): File {
        val folder = Files.createTempDirectory("acx-history").toFile()
        return File(folder, "rollout-2026-09-19T07-54-55-$thread.jsonl").apply {
            writeText(lines.joinToString("\n", postfix = "\n"))
            deleteOnExit()
        }
    }

    private val meta = """{"timestamp":"2026-09-19T10:54:55.285Z","type":"session_meta","payload":{"id":"$thread","cwd":"/work/proj","cli_version":"0.152.0"}}"""

    private fun context(model: String) =
        """{"timestamp":"2026-09-19T10:54:57.125Z","type":"turn_context","payload":{"turn_id":"t1","cwd":"/work/proj","model":"$model","approval_policy":"untrusted"}}"""

    private fun tokens(total: Int, window: Int = 258400) =
        """{"timestamp":"2026-09-19T10:55:02.219Z","type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"total_tokens":99999},"last_token_usage":{"input_tokens":19891,"cached_input_tokens":9984,"output_tokens":104,"reasoning_output_tokens":18,"total_tokens":$total},"model_context_window":$window}}}"""

    // --- The file ------------------------------------------------------------------------

    @Test
    fun `a conversation file is known by the thread at the end of its name`() {
        assertEquals(thread, CodexHistory.threadIdOf(File("/x/rollout-2026-09-19T07-54-55-$thread.jsonl")))
        assertNull(CodexHistory.threadIdOf(File("/x/rollout-2026-09-19T07-54-55-$thread.json")))
        assertNull(CodexHistory.threadIdOf(File("/x/notes.jsonl")))
    }

    @Test
    fun `a conversation file names the directory it was started in on its first line`() {
        assertEquals("/work/proj", CodexHistory.cwdOf(rollout(meta, context("gpt-5.6-sol"))))
        assertNull(CodexHistory.cwdOf(rollout("not json", meta)))
        assertNull(CodexHistory.cwdOf(File("/no/such/rollout.jsonl")))
    }

    /**
     * Codex writes the settings of every turn into the thread, and the last one is what the conversation
     * is on now: opened from the history, it carries on at its own model.
     */
    @Test
    fun `the model a conversation is on is its last turn's`() {
        val file = rollout(meta, context("gpt-5.1-codex-mini"), tokens(100), context("gpt-5.6-sol"))

        assertEquals("gpt-5.6-sol", CodexHistory.lastModelOf(file))
        assertEquals("", CodexHistory.lastModelOf(rollout(meta)))
        assertEquals("", CodexHistory.lastModelOf(null))
        assertEquals("", CodexHistory.lastModelOf(File("/no/such/rollout.jsonl")))
    }

    @Test
    fun `how full the window was is the last token count, in the live notification's shape`() {
        val usage = assertNotNull(CodexHistory.lastTokenUsage(rollout(meta, tokens(5000), tokens(19995))))

        val last = usage["last"]!!.jsonObject
        assertEquals("19995", last["totalTokens"]?.jsonPrimitive?.content)
        assertEquals("19891", last["inputTokens"]?.jsonPrimitive?.content)
        assertEquals("9984", last["cachedInputTokens"]?.jsonPrimitive?.content)
        assertEquals("104", last["outputTokens"]?.jsonPrimitive?.content)
        assertEquals("18", last["reasoningOutputTokens"]?.jsonPrimitive?.content)
        assertEquals("258400", usage["modelContextWindow"]?.jsonPrimitive?.content)

        // And the context meter reads exactly this shape: what stays in the window, of how much.
        assertEquals(19977 to 258400, CodexShapes.contextOf(usage))
    }

    @Test
    fun `a file without a finished count says nothing about the window`() {
        val opening = """{"timestamp":"2026-09-19T10:54:55.287Z","type":"event_msg","payload":{"type":"token_count","info":null}}"""

        assertNull(CodexHistory.lastTokenUsage(rollout(meta, opening)))
        assertNull(CodexHistory.lastTokenUsage(rollout(meta)))
        assertNull(CodexHistory.lastTokenUsage(null))
    }

    // The search index keeps one folder per project, named after the path with everything but letters
    // and digits turned into hyphens.
    @Test
    fun `a path becomes a flat name`() {
        assertEquals("-Users-max-Documents-Projects-amazing-codex", CodexHistory.slugFor("/Users/max/Documents/Projects/amazing-codex"))
        assertEquals("-home-ivan-dev-my-project", CodexHistory.slugFor("/home/ivan/dev/my_project"))
        assertEquals("-home-ivan-my-app-v2", CodexHistory.slugFor("/home/ivan/my app.v2"))
        assertEquals("C--Users-Ivan-dev-proj", CodexHistory.slugFor("C:\\Users\\Ivan\\dev\\proj"))
    }

    // --- A past turn in the panel's language -------------------------------------------

    private fun parse(line: String): JsonObject = Json.parseToJsonElement(line).jsonObject

    private fun turn(items: String, status: String = "completed", extra: String = ""): JsonObject = parse(
        """{"id":"turn-1","status":"$status","startedAt":1789815295,"durationMs":14000$extra,"items":[$items]}""",
    )

    private val asked = """{"type":"userMessage","id":"u1","content":[{"type":"text","text":"Run cat","text_elements":[]},{"type":"localImage","path":"/tmp/x.png"}]}"""
    private val thought = """{"type":"reasoning","id":"rs1","summary":["Looking at the file"],"content":[]}"""
    private val ran = """{"type":"commandExecution","id":"exec-1","command":"/bin/zsh -lc 'cat a.txt'","cwd":"/p","commandActions":[{"type":"read","command":"cat a.txt","name":"a.txt","path":"/p/a.txt"}],"status":"completed","aggregatedOutput":"hello\n","exitCode":0}"""
    private val patched = """{"type":"fileChange","id":"f1","changes":[{"path":"/p/x.ts","kind":{"type":"update","move_path":null},"diff":"@@ -1,2 +1,3 @@\n a\n+b\n c\n"}],"status":"completed"}"""
    private val answered = """{"type":"agentMessage","id":"m1","text":"Done."}"""

    @Test
    fun `a past turn is told in order and closed with its time`() {
        val lines = CodexReplay.lines(turn("$asked,$thought,$ran,$patched,$answered"), outputLimit = 12_000, closeTurn = true).map(::parse)

        assertEquals(
            listOf("user", "assistant", "assistant", "user", "assistant", "user", "assistant", "result"),
            lines.map { it["type"]?.jsonPrimitive?.content },
        )

        val prompt = lines[0]
        assertEquals("u1", prompt["uuid"]?.jsonPrimitive?.content)
        assertEquals("2026-09-19T10:54:55Z", prompt["timestamp"]?.jsonPrimitive?.content)
        assertEquals("Run cat [Image #1]", textOf(prompt))

        assertEquals("Looking at the file", blockOf(lines[1])["thinking"]?.jsonPrimitive?.content)

        val read = blockOf(lines[2])
        assertEquals("Read", read["name"]?.jsonPrimitive?.content)
        assertEquals("exec-1", read["id"]?.jsonPrimitive?.content)
        assertEquals("/p/a.txt", read["input"]!!.jsonObject["file_path"]?.jsonPrimitive?.content)

        val output = blockOf(lines[3])
        assertEquals("exec-1", output["tool_use_id"]?.jsonPrimitive?.content)
        assertEquals("hello\n", output["content"]?.jsonPrimitive?.content)
        assertEquals("exec-1-result", lines[3]["uuid"]?.jsonPrimitive?.content)

        val edit = blockOf(lines[4])
        assertEquals("Edit", edit["name"]?.jsonPrimitive?.content)
        assertEquals("a\nb\nc", edit["input"]!!.jsonObject["new_string"]?.jsonPrimitive?.content)

        assertEquals("Done.", textOf(lines[6]))
        assertEquals("m1", lines[6]["uuid"]?.jsonPrimitive?.content)

        val result = lines[7]
        assertEquals("false", result["is_error"]?.jsonPrimitive?.content)
        assertEquals("14000", result["duration_ms"]?.jsonPrimitive?.content)
        assertEquals("Done.", result["result"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a replayed answer carries no model of its own`() {
        val line = parse(CodexReplay.lines(turn(answered), 12_000, closeTurn = false).single())

        assertNull(line["message"]!!.jsonObject["model"])
    }

    // A turn cut in half by a page boundary is continued on the page below: it is not closed here.
    @Test
    fun `a turn left open has no result`() {
        val lines = CodexReplay.lines(turn("$asked,$answered"), 12_000, closeTurn = false)

        assertEquals(2, lines.size)
        assertTrue(lines.none { "\"type\":\"result\"" in it })
    }

    // Nothing to show is nothing to close: a result on its own would stand in the feed as a meta row
    // under no answer.
    @Test
    fun `a turn with nothing drawable leaves nothing behind`() {
        assertEquals(emptyList(), CodexReplay.lines(turn(""), 12_000, closeTurn = true))
        assertEquals(emptyList(), CodexReplay.lines(turn("""{"type":"agentMessage","id":"m1","text":"  "}"""), 12_000, closeTurn = true))
    }

    @Test
    fun `a failed turn is closed as an error with its message`() {
        val lines = CodexReplay.lines(
            turn(asked, status = "failed", extra = ""","error":{"message":"The stream broke.","codexErrorInfo":"internalServerError"}"""),
            12_000,
            closeTurn = true,
        ).map(::parse)

        val result = lines.last()
        assertEquals("true", result["is_error"]?.jsonPrimitive?.content)
        assertEquals("The stream broke.", result["result"]?.jsonPrimitive?.content)
    }

    /** A page is sized for whoever reads it: a phone's page has to fit a relay frame. */
    @Test
    fun `a long output is cut to the page's limit`() {
        val long = """{"type":"commandExecution","id":"exec-2","command":"ls","commandActions":[],"status":"completed","aggregatedOutput":"${"x".repeat(5000)}","exitCode":0}"""
        val output = parse(CodexReplay.lines(turn(long), outputLimit = 2_000, closeTurn = false)[1])

        val content = blockOf(output)["content"]!!.jsonPrimitive.content
        assertTrue(content.startsWith("x".repeat(2_000)))
        assertTrue(content.endsWith("(3000 more characters)"))
    }

    @Test
    fun `a plan, a compaction and a review are drawn as their own cards`() {
        val items = listOf(
            """{"type":"plan","id":"p1","text":"1. Read\n2. Fix"}""",
            """{"type":"contextCompaction","id":"c1"}""",
            """{"type":"exitedReviewMode","id":"r1","review":"No findings."}""",
            // Codex 0.152 says the findings again as a message; the replay shows them once.
            """{"type":"agentMessage","id":"m1","text":"No findings."}""",
        ).joinToString(",")

        val lines = CodexReplay.lines(turn(items), 12_000, closeTurn = true).map(::parse)

        assertEquals(CodexDialect.PLAN_TOOL, blockOf(lines[0])["name"]?.jsonPrimitive?.content)
        assertEquals("compact_boundary", lines[1]["subtype"]?.jsonPrimitive?.content)
        assertEquals("No findings.", textOf(lines[2]))
        // The review is the turn's last word.
        assertEquals("No findings.", lines[3]["result"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a picture sent alone is still the person's message`() {
        val picture = """{"type":"userMessage","id":"u2","content":[{"type":"image","url":"data:image/png;base64,AAAA"}]}"""
        val line = parse(CodexReplay.lines(turn(picture), 12_000, closeTurn = false).single())

        assertEquals("[Image #1]", textOf(line).trim())
    }

    private fun blockOf(line: JsonObject): JsonObject =
        (line["message"]!!.jsonObject["content"] as JsonArray).first().jsonObject

    private fun textOf(line: JsonObject): String = blockOf(line)["text"]!!.jsonPrimitive.content
}
