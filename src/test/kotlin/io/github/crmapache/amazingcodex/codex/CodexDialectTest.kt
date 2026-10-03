package io.github.crmapache.amazingcodex.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Codex's items retold as the cards the panel draws (see CodexDialect). The shapes below are Codex's own
 * `item/started` / `item/completed` items, as the app-server sends them.
 */
class CodexDialectTest {

    private fun item(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    private fun JsonObject.text(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content

    private fun command(command: String, actions: String, extra: String = "") = item(
        """{"type":"commandExecution","id":"exec-1","command":"$command","cwd":"/p","commandActions":$actions,"status":"completed"$extra}""",
    )

    // --- Commands ---------------------------------------------------------------------

    /**
     * Codex runs `cat`, `sed -n` and `rg` for exactly the jobs Claude had separate tools for, and a burst
     * of them drawn as twenty BASH cards hides the one command that actually changed something.
     */
    @Test
    fun `a plain read is a READ card`() {
        val call = CodexDialect.toolCalls(
            command("/bin/zsh -lc 'cat a.txt'", """[{"type":"read","command":"cat a.txt","name":"a.txt","path":"/p/a.txt"}]"""),
        ).single()

        assertEquals("exec-1", call.id)
        assertEquals("Read", call.name)
        assertEquals("/p/a.txt", call.input.text("file_path"))
        assertEquals("cat a.txt", call.input.text("command"))
    }

    @Test
    fun `a plain search is a GREP card and a listing a GLOB card`() {
        val grep = CodexDialect.toolCalls(
            command("/bin/zsh -lc 'rg -n balance src'", """[{"type":"search","command":"rg -n balance src","query":"balance","path":"src"}]"""),
        ).single()
        assertEquals("Grep", grep.name)
        assertEquals("balance", grep.input.text("pattern"))
        assertEquals("src", grep.input.text("path"))

        // A search Codex could not name a query for shows the command itself as the pattern.
        val bare = CodexDialect.toolCalls(command("rg --files", """[{"type":"search","command":"rg --files"}]""")).single()
        assertEquals("rg --files", bare.input.text("pattern"))
        assertNull(bare.input["path"])

        val glob = CodexDialect.toolCalls(
            command("/bin/bash -lc 'ls -la src'", """[{"type":"listFiles","command":"ls -la src","path":"src"}]"""),
        ).single()
        assertEquals("Glob", glob.name)
        assertEquals("ls -la src", glob.input.text("pattern"))
        assertEquals("src", glob.input.text("path"))
    }

    /**
     * Codex leaves the helpers out of what it names: `ls | wc -l` comes as a listing. Drawn as one, its
     * card counted the number it printed as "1 match". A pipe that only picks lines keeps the card.
     */
    @Test
    fun `a command whose output was reshaped is drawn as the command it is`() {
        val counted = CodexDialect.toolCalls(
            command("/bin/zsh -lc 'ls | wc -l'", """[{"type":"listFiles","command":"ls","path":null}]"""),
        ).single()
        assertEquals("Bash", counted.name)
        assertEquals("ls | wc -l", counted.input.text("command"))

        val picked = CodexDialect.toolCalls(
            command("/bin/zsh -lc 'rg -n balance src | head -20'", """[{"type":"search","command":"rg -n balance src","query":"balance","path":"src"}]"""),
        ).single()
        assertEquals("Grep", picked.name)

        assertTrue(CodexDialect.outputPassesThrough("rg 'a|b' src"))
        assertTrue(CodexDialect.outputPassesThrough("ls src || true"))
        assertTrue(CodexDialect.outputPassesThrough("ls | sort | head -5"))
        assertFalse(CodexDialect.outputPassesThrough("cat a.txt | wc -l"))
        assertFalse(CodexDialect.outputPassesThrough("rg x | awk '{print \$1}'"))
    }

    @Test
    fun `anything else Codex runs is a BASH card with the script unwrapped`() {
        val several = CodexDialect.toolCalls(
            command(
                "/bin/zsh -lc 'cat a.txt && rg x'",
                """[{"type":"read","command":"cat a.txt","name":"a.txt","path":"/p/a.txt"},{"type":"search","command":"rg x","query":"x"}]""",
            ),
        ).single()
        assertEquals("Bash", several.name)
        assertEquals("cat a.txt && rg x", several.input.text("command"))
        assertEquals("/p", several.input.text("cwd"))

        val unknown = CodexDialect.toolCalls(command("/bin/zsh -lc 'npm test'", """[{"type":"unknown","command":"npm test"}]""")).single()
        assertEquals("Bash", unknown.name)

        // A read with no path to open is not a READ card: the card would have nothing to show.
        val pathless = CodexDialect.toolCalls(command("cat", """[{"type":"read","command":"cat","name":"a"}]""")).single()
        assertEquals("Bash", pathless.name)
    }

    @Test
    fun `a command's result is its output, and a failure says so`() {
        val done = CodexDialect.toolResults(command("ls", "[]", ""","aggregatedOutput":"a\nb\n","exitCode":0""")).single()
        assertEquals(CodexDialect.ToolResult("exec-1", "a\nb\n", isError = false), done)

        val quiet = CodexDialect.toolResults(command("false", "[]", ""","aggregatedOutput":"","exitCode":2""")).single()
        assertEquals(CodexDialect.ToolResult("exec-1", "Exit code 2", isError = true), quiet)

        val loud = CodexDialect.toolResults(command("make", "[]", ""","aggregatedOutput":"boom","exitCode":1""")).single()
        assertEquals(CodexDialect.ToolResult("exec-1", "boom", isError = true), loud)
    }

    @Test
    fun `a command the person refused is declined, not failed quietly`() {
        val refused = item("""{"type":"commandExecution","id":"exec-2","command":"rm -rf x","commandActions":[],"status":"declined","aggregatedOutput":null,"exitCode":null}""")

        assertEquals(CodexDialect.ToolResult("exec-2", CodexDialect.DECLINED, isError = true), CodexDialect.toolResults(refused).single())
    }

    @Test
    fun `a failed command without an exit code is still an error`() {
        val failed = item("""{"type":"commandExecution","id":"exec-3","command":"x","commandActions":[],"status":"failed"}""")

        assertEquals(CodexDialect.ToolResult("exec-3", "", isError = true), CodexDialect.toolResults(failed).single())
    }

    // --- The shell wrapper --------------------------------------------------------------

    @Test
    fun `the shell Codex wrapped a script in is taken off`() {
        assertEquals("cat a.txt", CodexDialect.unwrapShell("/bin/zsh -lc 'cat a.txt'"))
        assertEquals("cat a.txt", CodexDialect.unwrapShell("bash -c 'cat a.txt'"))
        assertEquals("echo \"hi\"", CodexDialect.unwrapShell("/usr/bin/bash -lc \"echo \\\"hi\\\"\""))
        // A script with a quote of its own is glued together out of several quoted runs.
        assertEquals("echo it's", CodexDialect.unwrapShell("/bin/sh -lc 'echo it'\"'\"'s'"))
    }

    @Test
    fun `a command that is not a wrapped script is left as it is`() {
        assertEquals("git status", CodexDialect.unwrapShell("git status"))
        // Not one word after the wrapper: nothing to unquote with confidence.
        assertEquals("/bin/zsh -lc echo hi", CodexDialect.unwrapShell("/bin/zsh -lc echo hi"))
        assertEquals("/bin/zsh -lc 'unclosed", CodexDialect.unwrapShell("/bin/zsh -lc 'unclosed"))
    }

    @Test
    fun `one shell word is unquoted by the shell's own rules`() {
        assertEquals("a b", CodexDialect.unquoteWord("'a b'"))
        assertEquals("a\$b \\n", CodexDialect.unquoteWord("\"a\\\$b \\n\""))
        assertEquals("ab", CodexDialect.unquoteWord("a\\b"))
        assertEquals("plain", CodexDialect.unquoteWord("plain  "))
        assertNull(CodexDialect.unquoteWord("two words"))
        assertNull(CodexDialect.unquoteWord("\"unclosed"))
    }

    // --- Patches ------------------------------------------------------------------------

    @Test
    fun `a changed file is an EDIT card carrying the diff`() {
        val call = CodexDialect.toolCalls(
            item("""{"type":"fileChange","id":"f1","changes":[{"path":"/p/x.ts","kind":{"type":"update","move_path":null},"diff":"@@ -1,2 +1,3 @@\n a\n+b\n c\n"}],"status":"completed"}"""),
        ).single()

        assertEquals("f1", call.id)
        assertEquals("Edit", call.name)
        assertEquals("/p/x.ts", call.input.text("file_path"))
        assertEquals("a\nc", call.input.text("old_string"))
        assertEquals("a\nb\nc", call.input.text("new_string"))
        assertEquals("@@ -1,2 +1,3 @@\n a\n+b\n c\n", call.input.text("unified_diff"))
        assertNull(call.input["moved_from"])
    }

    @Test
    fun `a new file is a WRITE card, a deleted one an EDIT that empties it`() {
        val calls = CodexDialect.toolCalls(
            item(
                """{"type":"fileChange","id":"f2","changes":[
                    {"path":"/p/new.ts","kind":{"type":"add"},"diff":"one\ntwo\n"},
                    {"path":"/p/old.ts","kind":{"type":"delete"},"diff":"gone\n"}
                ],"status":"completed"}""",
            ),
        )

        assertEquals(listOf("f2:0", "f2:1"), calls.map { it.id })
        assertEquals("Write", calls[0].name)
        assertEquals("one\ntwo\n", calls[0].input.text("content"))
        assertEquals("Edit", calls[1].name)
        assertEquals("gone\n", calls[1].input.text("old_string"))
        assertEquals("", calls[1].input.text("new_string"))
        assertEquals("true", calls[1].input.text("deleted"))
    }

    @Test
    fun `a moved file is edited where it went, and says where it came from`() {
        val call = CodexDialect.toolCalls(
            item("""{"type":"fileChange","id":"f3","changes":[{"path":"/p/a.ts","kind":{"type":"update","move_path":"/p/b.ts"},"diff":"@@ -1 +1 @@\n-x\n+y\n"}],"status":"completed"}"""),
        ).single()

        assertEquals("/p/b.ts", call.input.text("file_path"))
        assertEquals("/p/a.ts", call.input.text("moved_from"))
    }

    @Test
    fun `a patch ends the same way for every file in it`() {
        fun patch(status: String) = item(
            """{"type":"fileChange","id":"f4","changes":[{"path":"a","kind":{"type":"add"},"diff":""},{"path":"b","kind":{"type":"add"},"diff":""}],"status":"$status"}""",
        )

        assertEquals(
            listOf(CodexDialect.ToolResult("f4:0", "", false), CodexDialect.ToolResult("f4:1", "", false)),
            CodexDialect.toolResults(patch("completed")),
        )
        assertTrue(CodexDialect.toolResults(patch("declined")).all { it.content == CodexDialect.DECLINED && it.isError })
        assertTrue(CodexDialect.toolResults(patch("failed")).all { it.content == "The patch did not apply." && it.isError })
    }

    @Test
    fun `one file keeps the item's own id, several are numbered`() {
        assertEquals(listOf("f1"), CodexDialect.fileCallIds("f1", 1))
        assertEquals(listOf("f1"), CodexDialect.fileCallIds("f1", 0))
        assertEquals(listOf("f1:0", "f1:1", "f1:2"), CodexDialect.fileCallIds("f1", 3))
    }

    /**
     * The old and new text are rebuilt beside the diff for the readers that count by them. Between two
     * hunks the lines Codex did not show are not there, and the rebuilt text says so rather than
     * pretending the hunks touch.
     */
    @Test
    fun `the text is rebuilt out of the diff, hunk by hunk`() {
        val input = CodexDialect.editInput(
            "/p/x.ts",
            "--- a/x.ts\n+++ b/x.ts\n@@ -1,2 +1,2 @@\n a\n-b\n+B\n@@ -9,2 +9,2 @@\n y\n-z\n+Z\n\\ No newline at end of file",
        )

        assertEquals("a\nb\n…\ny\nz", input.text("old_string"))
        assertEquals("a\nB\n…\ny\nZ", input.text("new_string"))
    }

    // --- Other tools ------------------------------------------------------------------

    @Test
    fun `an MCP call keeps the name every MCP reader knows`() {
        val call = CodexDialect.toolCalls(
            item("""{"type":"mcpToolCall","id":"m1","server":"context7","tool":"query-docs","arguments":{"q":"flows"},"status":"inProgress"}"""),
        ).single()

        assertEquals("mcp__context7__query-docs", call.name)
        assertEquals("flows", call.input.text("q"))

        val scalar = CodexDialect.toolCalls(item("""{"type":"mcpToolCall","id":"m2","server":"s","tool":"t","arguments":"raw"}""")).single()
        assertEquals("raw", scalar.input.text("value"))
        val none = CodexDialect.toolCalls(item("""{"type":"mcpToolCall","id":"m3","server":"s","tool":"t","arguments":null}""")).single()
        assertEquals(JsonObject(emptyMap()), none.input)
    }

    @Test
    fun `an MCP result is its text, and its error when there is one`() {
        val ok = item(
            """{"type":"mcpToolCall","id":"m1","server":"s","tool":"t","status":"completed",
               "result":{"content":[{"type":"text","text":"one"},{"type":"image","data":"x"},{"type":"resource_link","uri":"file:///a"}]}}""",
        )
        assertEquals(CodexDialect.ToolResult("m1", "one\n[image]\nfile:///a", false), CodexDialect.toolResults(ok).single())

        val structured = item("""{"type":"mcpToolCall","id":"m2","server":"s","tool":"t","status":"completed","result":{"structuredContent":{"n":1}}}""")
        assertEquals("""{"n":1}""", CodexDialect.toolResults(structured).single().content)

        val broken = item("""{"type":"mcpToolCall","id":"m3","server":"s","tool":"t","status":"failed","error":{"message":"server went away"}}""")
        assertEquals(CodexDialect.ToolResult("m3", "server went away", true), CodexDialect.toolResults(broken).single())
    }

    @Test
    fun `a web search, a picture and a plan have cards of their own`() {
        val search = CodexDialect.toolCalls(item("""{"type":"webSearch","id":"w1","query":"kotlin serialization"}""")).single()
        assertEquals("WebSearch", search.name)
        assertEquals("kotlin serialization", search.input.text("query"))

        val picture = CodexDialect.toolCalls(item("""{"type":"imageView","id":"i1","path":"/p/shot.png"}""")).single()
        assertEquals("Read", picture.name)
        assertEquals("/p/shot.png", picture.input.text("file_path"))

        val plan = CodexDialect.toolCalls(item("""{"type":"plan","id":"p1","text":"1. Read\n2. Fix"}""")).single()
        assertEquals(CodexDialect.PLAN_TOOL, plan.name)
        assertEquals("1. Read\n2. Fix", plan.input.text("plan"))
    }

    @Test
    fun `an agent Codex starts is a subagent card, and its result lists the agents`() {
        val spawn = item(
            """{"type":"collabAgentToolCall","id":"c1","tool":"spawnAgent","prompt":"Review the diff","model":"gpt-5.6-sol",
               "status":"completed","agentsStates":{"019aaaaa-bbbb":{"status":"completed","message":"No findings"}}}""",
        )

        val call = CodexDialect.toolCalls(spawn).single()
        assertEquals(CodexDialect.AGENT_TOOL, call.name)
        assertEquals("Start an agent · gpt-5.6-sol", call.input.text("description"))
        assertEquals("Review the diff", call.input.text("prompt"))
        assertEquals("spawnAgent", call.input.text("subagent_type"))

        assertEquals(CodexDialect.ToolResult("c1", "019aaaaa - completed - No findings", false), CodexDialect.toolResults(spawn).single())
    }

    @Test
    fun `what is not a tool draws no card`() {
        for (json in listOf(
            """{"type":"agentMessage","id":"a1","text":"hi"}""",
            """{"type":"reasoning","id":"r1","summary":[]}""",
            """{"type":"userMessage","id":"u1","content":[]}""",
            """{"type":"commandExecution","command":"ls","commandActions":[]}""",
        )) {
            assertEquals(emptyList(), CodexDialect.toolCalls(item(json)), json)
            assertEquals(emptyList(), CodexDialect.toolResults(item(json)), json)
        }
        assertFalse(CodexDialect.isTool(item("""{"type":"agentMessage","id":"a1"}""")))
        assertTrue(CodexDialect.isTool(item("""{"type":"fileChange","id":"f1"}""")))
    }

    // --- Messages ---------------------------------------------------------------------

    @Test
    fun `a thought is its summary and its raw text, blanks dropped`() {
        val thinking = CodexDialect.thinkingOf(item("""{"type":"reasoning","id":"r1","summary":["First.","  "],"content":["Second."]}"""))

        assertEquals("First.\n\nSecond.", thinking)
        assertEquals("", CodexDialect.thinkingOf(item("""{"type":"reasoning","id":"r2"}""")))
    }

    @Test
    fun `the person's message is its words, its pictures counted and its skills named`() {
        val message = item(
            """{"type":"userMessage","id":"u1","content":[
                {"type":"text","text":"Fix it","text_elements":[]},
                {"type":"image","url":"data:x"},
                {"type":"localImage","path":"/tmp/a.png"},
                {"type":"skill","name":"save","path":"/s/SKILL.md"}
            ]}""",
        )

        assertEquals("Fix it\n\$save" to 2, CodexDialect.userTextOf(message))
        assertEquals("" to 0, CodexDialect.userTextOf(item("""{"type":"userMessage","id":"u2"}""")))
    }

    // --- Lines ------------------------------------------------------------------------

    @Test
    fun `no calls and no results are no line at all`() {
        assertNull(CodexDialect.toolUses(emptyList(), "gpt-5.6-sol", "u"))
        assertNull(CodexDialect.toolResults(emptyList(), "u"))
    }

    @Test
    fun `calls travel as one assistant line and results as one user line`() {
        val calls = listOf(
            CodexDialect.ToolCall("f1:0", "Write", JsonObject(emptyMap())),
            CodexDialect.ToolCall("f1:1", "Edit", JsonObject(emptyMap())),
        )
        val use = item(CodexDialect.toolUses(calls, "gpt-5.6-sol", "f1")!!)
        assertEquals("assistant", use.text("type"))
        val message = use["message"]!!.jsonObject
        assertEquals("f1:0", message.text("id"))
        assertEquals("gpt-5.6-sol", message.text("model"))
        assertEquals(listOf("f1:0", "f1:1"), (message["content"] as JsonArray).map { it.jsonObject.text("id") })

        val results = item(CodexDialect.toolResults(listOf(CodexDialect.ToolResult("f1:0", "", true)), "f1-result")!!)
        val block = (results["message"]!!.jsonObject["content"] as JsonArray).single().jsonObject
        assertEquals("tool_result", block.text("type"))
        assertEquals("true", block.text("is_error"))
    }

    @Test
    fun `the end of a turn carries its time, its words and its usage`() {
        val usage = CodexDialect.usageOf(item("""{"inputTokens":1000,"cachedInputTokens":800,"outputTokens":50,"totalTokens":1050}"""))!!
        assertEquals("200", usage.text("input_tokens"))
        assertEquals("800", usage.text("cache_read_input_tokens"))
        assertEquals("50", usage.text("output_tokens"))

        val result = item(CodexDialect.result("t1", durationMs = 1200, isError = false, resultText = "Done.", usage = usage))
        assertEquals("success", result.text("subtype"))
        assertEquals("1200", result.text("duration_ms"))
        assertEquals("t1", result.text("session_id"))
        assertEquals("200", result["usage"]!!.jsonObject.text("input_tokens"))
        assertEquals(1, (result["usage"]!!.jsonObject["iterations"] as JsonArray).size)

        val failed = item(CodexDialect.result("t1", 5, isError = true, resultText = "boom", usage = null))
        assertEquals("error_during_execution", failed.text("subtype"))
        assertNull(failed["usage"])
        assertNull(CodexDialect.usageOf(null))
    }

    @Test
    fun `a retry without a status says so rather than leaving the field out`() {
        val retry = item(CodexDialect.apiRetry(attempt = 2, maxRetries = 5, delayMs = 0, status = null, error = "stream broke"))

        assertEquals("api_retry", retry.text("subtype"))
        assertEquals(JsonNull, retry["error_status"])
        assertEquals("503", item(CodexDialect.apiRetry(1, 5, 0, 503, "x")).text("error_status"))
    }

    @Test
    fun `the start of a conversation names its thread, model and commands`() {
        val init = item(CodexDialect.systemInit("thread-1", "gpt-5.6-sol", "/p", PermissionModes.PLAN, listOf("compact", "prompts:review")))

        assertEquals("init", init.text("subtype"))
        assertEquals("thread-1", init.text("session_id"))
        assertEquals("gpt-5.6-sol", init.text("model"))
        assertEquals("plan", init.text("permissionMode"))
        assertEquals(listOf("compact", "prompts:review"), (init["slash_commands"] as JsonArray).map { it.jsonPrimitive.content })
        // Read by the same reader the command hint uses.
        assertEquals(listOf("compact", "prompts:review"), CodexCommandNames.of(CodexDialect.systemInit("t", "", null, null, listOf("compact", "prompts:review"))))
    }

    @Test
    fun `a limit that stopped the work and a model that was swapped are said the panel's way`() {
        val limited = item(CodexDialect.rateLimited(resetsAtSeconds = 1_789_900_000, window = "codex"))
        val info = limited["rate_limit_info"]!!.jsonObject
        assertEquals("rejected", info.text("status"))
        assertEquals("1789900000", info.text("resetsAt"))

        val fallback = item(CodexDialect.modelFallback("", "gpt-5.6-codex", "highRiskCyberActivity"))
        assertNull(fallback["originalModel"])
        assertEquals("gpt-5.6-codex", fallback.text("fallbackModel"))
        assertEquals(CodexDialect.rerouteReason("highRiskCyberActivity"), fallback.text("content"))
    }

    /** Codex says why it rerouted a request by a code; the card says it in words. */
    @Test
    fun `a reroute's reason is said in words`() {
        assertEquals("", CodexDialect.rerouteReason(""))
        assertEquals(
            "Codex's safeguards flagged this request as possible high-risk cyber activity and moved it to another model.",
            CodexDialect.rerouteReason("highRiskCyberActivity"),
        )
        assertEquals("Codex moved this request to another model: model capacity exceeded.", CodexDialect.rerouteReason("modelCapacityExceeded"))
        assertEquals("Codex moved this request to another model: gpt5 unavailable.", CodexDialect.rerouteReason("gpt5Unavailable"))
        assertEquals("Codex moved this request to another model: capacity.", CodexDialect.rerouteReason("capacity"))
    }

    @Test
    fun `a long output is clipped with the rest counted`() {
        assertEquals("short", CodexDialect.clip("short"))
        assertEquals("abc\n… (2 more characters)", CodexDialect.clip("abcde", limit = 3))
    }
}
