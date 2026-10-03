package io.github.crmapache.amazingcodex.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The panel's event language, spoken about Codex.
 *
 * Everything past the process boundary - the feed, the task strip, the statistics, the phone, the
 * notifications, the scenario engine - reads one vocabulary of events: `system`, `assistant` with text,
 * thinking and tool calls, `user` with tool results, `stream_event` deltas, `result` at the end of a turn.
 * That vocabulary was born as Claude Code's stream-json, and a hundred thousand lines stand on it. Codex
 * speaks a different protocol - threads, turns and typed items over JSON-RPC - and the choice was where
 * to translate: in every one of those readers, or once, here, at the border.
 *
 * Here. A reader that learns a second dialect is a reader that can disagree with its neighbour about the
 * same event; one translation is one place to be right. The cost is that this file has to say, for every
 * kind of Codex item, which card the panel should draw for it - and that is exactly the decision that
 * belongs in one place.
 *
 * Pure functions only: an item in, lines out. The live stream (see CodexStream) and the history pages
 * (see CodexHistory) both come through here, which is what makes a conversation look the same whether
 * it is being written now or was written last week.
 */
internal object CodexDialect {

    /** A tool call as the panel draws it: the card's name, its id, and the arguments it shows. */
    data class ToolCall(val id: String, val name: String, val input: JsonObject)

    /** The end of a tool call: what the card's body shows, and whether it failed. */
    data class ToolResult(val id: String, val content: String, val isError: Boolean)

    // --- Lines ---------------------------------------------------------------------------------

    fun systemInit(
        threadId: String,
        model: String,
        cwd: String?,
        permissionMode: String?,
        slashCommands: List<String>,
    ): String = buildJsonObject {
        put("type", "system")
        put("subtype", "init")
        put("session_id", threadId)
        if (model.isNotEmpty()) put("model", model)
        cwd?.takeIf { it.isNotEmpty() }?.let { put("cwd", it) }
        permissionMode?.let { put("permissionMode", it) }
        putJsonArray("slash_commands") { slashCommands.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("tools") {}
    }.toString()

    fun conversationReset(newThreadId: String): String = buildJsonObject {
        put("type", "conversation_reset")
        put("new_conversation_id", newThreadId)
    }.toString()

    fun textDelta(text: String): String = delta("text_delta", "text", text)

    fun thinkingDelta(text: String): String = delta("thinking_delta", "thinking", text)

    private fun delta(kind: String, field: String, text: String): String = buildJsonObject {
        put("type", "stream_event")
        putJsonObject("event") {
            put("type", "content_block_delta")
            put("index", 0)
            putJsonObject("delta") {
                put("type", kind)
                put(field, text)
            }
        }
    }.toString()

    fun assistantText(
        id: String,
        text: String,
        model: String,
        uuid: String,
        usage: JsonObject? = null,
        error: String? = null,
    ): String = assistant(id, model, uuid, usage, error) {
        addJsonObject {
            put("type", "text")
            put("text", text)
        }
    }

    fun assistantThinking(id: String, thinking: String, model: String, uuid: String): String =
        assistant(id, model, uuid) {
            addJsonObject {
                put("type", "thinking")
                put("thinking", thinking)
            }
        }

    fun toolUses(calls: List<ToolCall>, model: String, uuid: String): String? {
        if (calls.isEmpty()) return null

        return assistant(calls.first().id, model, uuid) {
            for (call in calls) {
                addJsonObject {
                    put("type", "tool_use")
                    put("id", call.id)
                    put("name", call.name)
                    put("input", call.input)
                }
            }
        }
    }

    fun toolResults(results: List<ToolResult>, uuid: String): String? {
        if (results.isEmpty()) return null

        return buildJsonObject {
            put("type", "user")
            put("uuid", uuid)
            putJsonObject("message") {
                put("role", "user")
                putJsonArray("content") {
                    for (result in results) {
                        addJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", result.id)
                            put("content", result.content)
                            if (result.isError) put("is_error", true)
                        }
                    }
                }
            }
        }.toString()
    }

    /**
     * What the person said, as a past conversation remembers it. Only the replay draws this - in a live
     * turn the message is already on screen from the moment it was sent (see ClaudeSessionHub.prompt).
     */
    fun userPrompt(text: String, images: Int, uuid: String, timestamp: String?): String = buildJsonObject {
        put("type", "user")
        put("uuid", uuid)
        timestamp?.let { put("timestamp", it) }
        putJsonObject("message") {
            put("role", "user")
            putJsonArray("content") {
                addJsonObject {
                    put("type", "text")
                    put("text", text + (1..images).joinToString("") { " [Image #$it]" })
                }
            }
        }
    }.toString()

    fun result(
        threadId: String,
        durationMs: Long,
        isError: Boolean,
        resultText: String,
        usage: JsonObject?,
    ): String = buildJsonObject {
        put("type", "result")
        put("subtype", if (isError) "error_during_execution" else "success")
        put("is_error", isError)
        put("duration_ms", durationMs)
        put("num_turns", 1)
        put("result", resultText)
        put("session_id", threadId)
        usage?.let {
            putJsonObject("usage") {
                it.forEach { (key, value) -> put(key, value) }
                putJsonArray("iterations") { add(it) }
            }
        }
    }.toString()

    fun compacting(): String = buildJsonObject {
        put("type", "system")
        put("subtype", "status")
        put("status", "compacting")
    }.toString()

    fun compactBoundary(preTokens: Long?, postTokens: Long?, durationMs: Long?): String = buildJsonObject {
        put("type", "system")
        put("subtype", "compact_boundary")
        putJsonObject("compact_metadata") {
            put("trigger", "auto")
            preTokens?.let { put("pre_tokens", it) }
            postTokens?.let { put("post_tokens", it) }
            durationMs?.let { put("duration_ms", it) }
        }
    }.toString()

    fun apiRetry(attempt: Int, maxRetries: Int, delayMs: Long, status: Int?, error: String): String = buildJsonObject {
        put("type", "system")
        put("subtype", "api_retry")
        put("attempt", attempt)
        put("max_retries", maxRetries)
        put("retry_delay_ms", delayMs)
        if (status != null) put("error_status", status) else put("error_status", JsonNull)
        put("error", error)
    }.toString()

    /** A limit that stopped the work - the panel's card with the reset time (see rateLimitState). */
    fun rateLimited(resetsAtSeconds: Long?, window: String): String = buildJsonObject {
        put("type", "rate_limit_event")
        putJsonObject("rate_limit_info") {
            put("status", "rejected")
            resetsAtSeconds?.let { put("resetsAt", it) }
            put("rateLimitType", window)
        }
    }.toString()

    /**
     * Codex moved the turn to another model by itself (`model/rerouted`). The panel draws the swap with its
     * reason under it, and Codex gives the reason as a code - `highRiskCyberActivity` on 0.152 - which on
     * screen would read as a leak of the protocol rather than an explanation. So the code becomes a
     * sentence here; a code this version does not know is spelled out word by word rather than dropped.
     */
    fun modelFallback(from: String, to: String, reason: String): String = buildJsonObject {
        put("type", "system")
        put("subtype", "model_refusal_fallback")
        if (from.isNotEmpty()) put("originalModel", from)
        put("fallbackModel", to)
        put("content", rerouteReason(reason))
    }.toString()

    fun rerouteReason(reason: String): String = when (reason) {
        "" -> ""
        "highRiskCyberActivity" ->
            "Codex's safeguards flagged this request as possible high-risk cyber activity and moved it to another model."
        else -> {
            val words = reason.replace(Regex("(?<=[a-z0-9])(?=[A-Z])"), " ").lowercase()
            "Codex moved this request to another model: $words."
        }
    }

    private fun assistant(
        id: String,
        model: String,
        uuid: String,
        usage: JsonObject? = null,
        error: String? = null,
        content: kotlinx.serialization.json.JsonArrayBuilder.() -> Unit,
    ): String = buildJsonObject {
        put("type", "assistant")
        put("uuid", uuid)
        error?.let { put("error", it) }
        putJsonObject("message") {
            put("id", id)
            put("role", "assistant")
            if (model.isNotEmpty()) put("model", model)
            putJsonArray("content", content)
            usage?.let { put("usage", it) }
        }
    }.toString()

    // --- Items ---------------------------------------------------------------------------------

    /**
     * The cards a Codex item is drawn as, or nothing when it is not a tool at all (a message, a thought).
     *
     * The mapping is the point of the whole file, so it is spelled out case by case:
     *
     * - A shell command becomes a READ, GREP or GLOB card when Codex itself parsed it as one plain read,
     *   search or listing (its `commandActions`), and a BASH card otherwise. Codex runs `cat`, `sed -n`,
     *   `rg` for exactly the jobs Claude has separate tools for, and a burst of them drawn as twenty BASH
     *   cards hides the one command that actually changed something.
     * - A patch becomes one card per file: WRITE for a new file, EDIT for a changed or deleted one, with
     *   the unified diff Codex produced (see [editInput]).
     * - An MCP call keeps the `mcp__server__tool` name every MCP reader in the panel already knows.
     * - The plan of a plan-mode turn becomes the plan card, under the name the panel gives that card.
     */
    fun toolCalls(item: JsonObject): List<ToolCall> {
        val id = AppServer.text(item["id"])
        if (id.isEmpty()) return emptyList()

        return when (AppServer.text(item["type"])) {
            "commandExecution" -> listOf(commandCall(id, item))
            "fileChange" -> fileCalls(id, item)
            "mcpToolCall" -> listOf(
                ToolCall(
                    id,
                    "mcp__${AppServer.text(item["server"])}__${AppServer.text(item["tool"])}",
                    argumentsOf(item["arguments"]),
                ),
            )
            "dynamicToolCall" -> listOf(ToolCall(id, AppServer.text(item["tool"]).ifEmpty { "tool" }, argumentsOf(item["arguments"])))
            "webSearch" -> listOf(ToolCall(id, "WebSearch", buildJsonObject { put("query", AppServer.text(item["query"])) }))
            "imageView" -> listOf(ToolCall(id, "Read", buildJsonObject { put("file_path", AppServer.text(item["path"])) }))
            "imageGeneration" -> listOf(
                ToolCall(id, "ImageGeneration", buildJsonObject { put("description", AppServer.text(item["revisedPrompt"]).ifEmpty { "image" }) }),
            )
            "collabAgentToolCall" -> listOf(agentCall(id, item))
            "plan" -> listOf(ToolCall(id, PLAN_TOOL, buildJsonObject { put("plan", AppServer.text(item["text"])) }))
            else -> emptyList()
        }
    }

    /**
     * How the calls of a finished item ended. Only for items that are tools - see [toolCalls]; the plan
     * card is answered by the person, not here.
     */
    fun toolResults(item: JsonObject): List<ToolResult> {
        val id = AppServer.text(item["id"])
        if (id.isEmpty()) return emptyList()
        val status = AppServer.text(item["status"])

        return when (AppServer.text(item["type"])) {
            "commandExecution" -> {
                val exit = AppServer.intOf(item["exitCode"])
                val output = clip(AppServer.text(item["aggregatedOutput"]))
                val declined = status == "declined"
                val failed = status == "failed" || (exit != null && exit != 0)
                val content = when {
                    declined -> DECLINED
                    output.isNotEmpty() -> output
                    failed && exit != null -> "Exit code $exit"
                    else -> ""
                }
                listOf(ToolResult(id, content, declined || failed))
            }

            "fileChange" -> {
                val changes = item["changes"] as? JsonArray ?: JsonArray(emptyList())
                val ids = fileCallIds(id, changes.size)
                val (content, isError) = when (status) {
                    "declined" -> DECLINED to true
                    "failed" -> "The patch did not apply." to true
                    "completed" -> "" to false
                    else -> "" to false
                }
                ids.map { ToolResult(it, content, isError) }
            }

            "mcpToolCall" -> {
                val error = (item["error"] as? JsonObject)?.let { AppServer.text(it["message"]) }
                val result = item["result"] as? JsonObject
                val text = error ?: mcpText(result)
                listOf(ToolResult(id, clip(text), error != null || status == "failed"))
            }

            "dynamicToolCall" -> {
                val items = item["contentItems"] as? JsonArray
                val text = items?.mapNotNull { (it as? JsonObject)?.let { block -> AppServer.text(block["text"]) } }
                    ?.joinToString("\n").orEmpty()
                listOf(ToolResult(id, clip(text), status == "failed" || item["success"] == JsonPrimitive(false)))
            }

            "webSearch" -> listOf(ToolResult(id, "", false))
            "imageView" -> listOf(ToolResult(id, "", false))
            "imageGeneration" -> listOf(ToolResult(id, AppServer.text(item["savedPath"]), status == "failed"))
            "collabAgentToolCall" -> listOf(agentResult(id, item))
            else -> emptyList()
        }
    }

    /** Whether an item is drawn as a tool card at all - see [toolCalls]. */
    fun isTool(item: JsonObject): Boolean = AppServer.text(item["type"]) in TOOL_ITEMS

    /** The text of a reasoning item: the summary Codex shows, and the raw thinking when it sends any. */
    fun thinkingOf(item: JsonObject): String {
        val summary = (item["summary"] as? JsonArray)?.map { AppServer.text(it) }.orEmpty()
        val content = (item["content"] as? JsonArray)?.map { AppServer.text(it) }.orEmpty()
        return (summary + content).map(String::trim).filter(String::isNotEmpty).joinToString("\n\n")
    }

    /** The person's words out of a userMessage item, and how many pictures came with them. */
    fun userTextOf(item: JsonObject): Pair<String, Int> {
        val content = item["content"] as? JsonArray ?: return "" to 0
        val texts = mutableListOf<String>()
        var images = 0

        for (element in content) {
            val part = element as? JsonObject ?: continue
            when (AppServer.text(part["type"])) {
                "text" -> texts += AppServer.text(part["text"])
                "image", "localImage" -> images += 1
                "skill", "mention" -> AppServer.text(part["name"]).takeIf { it.isNotEmpty() }?.let { texts += "\$$it" }
            }
        }

        return texts.joinToString("\n").trim() to images
    }

    // --- Commands ------------------------------------------------------------------------------

    private fun commandCall(id: String, item: JsonObject): ToolCall {
        val script = unwrapShell(AppServer.text(item["command"]))
        val actions = (item["commandActions"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        // Codex names what a command does and leaves out the helpers around it: `ls | wc -l` comes as a
        // listing, `cat a.txt | wc -l` as a read. The card of a listing counts its lines as matches, so a
        // command whose output was reshaped on the way - counted, cut, rewritten - is drawn as the command
        // it is, with its own output, rather than as "1 match" over the number 4.
        val only = actions.singleOrNull()?.takeIf { outputPassesThrough(script) }

        if (only != null) {
            when (AppServer.text(only["type"])) {
                "read" -> {
                    val path = AppServer.text(only["path"])
                    if (path.isNotEmpty()) {
                        return ToolCall(id, "Read", buildJsonObject {
                            put("file_path", path)
                            put("command", script)
                        })
                    }
                }

                "search" -> return ToolCall(id, "Grep", buildJsonObject {
                    put("pattern", AppServer.text(only["query"]).ifEmpty { script })
                    AppServer.text(only["path"]).takeIf { it.isNotEmpty() }?.let { put("path", it) }
                    put("command", script)
                })

                "listFiles" -> return ToolCall(id, "Glob", buildJsonObject {
                    put("pattern", script)
                    AppServer.text(only["path"]).takeIf { it.isNotEmpty() }?.let { put("path", it) }
                    put("command", script)
                })
            }
        }

        return ToolCall(id, "Bash", buildJsonObject {
            put("command", script)
            AppServer.text(item["cwd"]).takeIf { it.isNotEmpty() }?.let { put("cwd", it) }
        })
    }

    /**
     * The script a command runs, without the shell Codex wrapped it in.
     *
     * Codex runs everything as `/bin/zsh -lc '<script>'` (or bash, or sh), and the wrapper is the same on
     * every card - the part a person reads is the quoted script inside it. Unquoted by the shell's own
     * rules, because Codex quotes by them: `'…'` literally, `"…"` with backslash escapes, and the two glued
     * together wherever the script itself holds a quote.
     */
    /**
     * Whether what the command prints is still what its first program printed: no pipe at all, or pipes
     * only into programs that pick lines out of it without changing them (see PASS_THROUGH). Pipes are
     * found outside quotes, so `rg 'a|b'` is one command and `a || b` is not a pipe.
     */
    internal fun outputPassesThrough(script: String): Boolean {
        val stages = mutableListOf(StringBuilder())
        var quote: Char? = null
        var i = 0
        while (i < script.length) {
            val c = script[i]
            when {
                quote != null -> { if (c == quote) quote = null; stages.last().append(c) }
                c == '\'' || c == '"' -> { quote = c; stages.last().append(c) }
                c == '\\' && i + 1 < script.length -> { stages.last().append(c).append(script[i + 1]); i++ }
                c == '|' && script.getOrNull(i + 1) == '|' -> { stages.last().append("||"); i++ }
                c == '|' -> stages += StringBuilder()
                else -> stages.last().append(c)
            }
            i++
        }
        return stages.drop(1).all { stage ->
            val program = stage.trim().split(Regex("\\s+")).firstOrNull().orEmpty().substringAfterLast('/')
            program in PASS_THROUGH
        }
    }

    /** Programs that leave the lines they pass on as they were - they only choose which, or in what order. */
    private val PASS_THROUGH = setOf("head", "tail", "sort", "uniq", "grep", "egrep", "fgrep", "rg", "cat", "less", "more")

    fun unwrapShell(command: String): String {
        val match = SHELL_WRAPPER.find(command) ?: return command
        val rest = command.substring(match.range.last + 1).trim()
        return unquoteWord(rest) ?: command
    }

    private val SHELL_WRAPPER = Regex("^\\s*(?:\\S*/)?(?:zsh|bash|sh|dash)\\s+-l?c\\s")

    /** One shell word, quotes resolved - or null when [text] is not exactly one word. */
    internal fun unquoteWord(text: String): String? {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\'' -> {
                    val end = text.indexOf('\'', i + 1)
                    if (end < 0) return null
                    out.append(text, i + 1, end)
                    i = end + 1
                }

                c == '"' -> {
                    i += 1
                    var closed = false
                    while (i < text.length) {
                        val d = text[i]
                        if (d == '\\' && i + 1 < text.length && text[i + 1] in "\"\\$`\n") {
                            out.append(text[i + 1])
                            i += 2
                            continue
                        }
                        if (d == '"') {
                            closed = true
                            i += 1
                            break
                        }
                        out.append(d)
                        i += 1
                    }
                    if (!closed) return null
                }

                c == '\\' && i + 1 < text.length -> {
                    out.append(text[i + 1])
                    i += 2
                }

                c.isWhitespace() -> return if (text.substring(i).isBlank()) out.toString() else null
                else -> {
                    out.append(c)
                    i += 1
                }
            }
        }
        return out.toString()
    }

    // --- Patches -------------------------------------------------------------------------------

    private fun fileCalls(id: String, item: JsonObject): List<ToolCall> {
        val changes = (item["changes"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val ids = fileCallIds(id, changes.size)

        return changes.mapIndexed { index, change ->
            val path = AppServer.text(change["path"])
            val kind = change["kind"] as? JsonObject
            val type = kind?.let { AppServer.text(it["type"]) }.orEmpty()
            val movedTo = kind?.let { AppServer.text(it["move_path"]) }.orEmpty()
            val diff = AppServer.text(change["diff"])

            when (type) {
                "add" -> ToolCall(ids[index], "Write", buildJsonObject {
                    put("file_path", path)
                    put("content", diff)
                })

                "delete" -> ToolCall(ids[index], "Edit", buildJsonObject {
                    put("file_path", path)
                    put("old_string", diff)
                    put("new_string", "")
                    put("deleted", true)
                })

                else -> ToolCall(ids[index], "Edit", editInput(movedTo.ifEmpty { path }, diff, movedFrom = path.takeIf { movedTo.isNotEmpty() }))
            }
        }
    }

    /**
     * An EDIT card's arguments out of a unified diff.
     *
     * The diff travels whole (`unified_diff`), and the panel draws it hunk by hunk with the line numbers
     * Codex wrote into it - a truer picture than Claude's old and new strings ever gave. The old and new
     * text are rebuilt beside it all the same, because other readers count a change by them: the
     * statistics add up lines written, and a phone too old to know `unified_diff` still draws something.
     */
    fun editInput(path: String, diff: String, movedFrom: String? = null): JsonObject {
        val before = StringBuilder()
        val after = StringBuilder()

        for (line in UnifiedDiff.lines(diff)) {
            when (line) {
                is UnifiedDiff.Line.Hunk -> {
                    if (before.isNotEmpty() || after.isNotEmpty()) {
                        before.append(HUNK_GAP)
                        after.append(HUNK_GAP)
                    }
                }
                is UnifiedDiff.Line.Added -> after.append(line.text).append('\n')
                is UnifiedDiff.Line.Removed -> before.append(line.text).append('\n')
                is UnifiedDiff.Line.Context -> {
                    before.append(line.text).append('\n')
                    after.append(line.text).append('\n')
                }
            }
        }

        return buildJsonObject {
            put("file_path", path)
            put("old_string", before.toString().removeSuffix("\n"))
            put("new_string", after.toString().removeSuffix("\n"))
            put("unified_diff", diff)
            movedFrom?.let { put("moved_from", it) }
        }
    }

    /** Between two hunks of one file in the rebuilt text: the lines Codex did not show are not there. */
    private const val HUNK_GAP = "…\n"

    /** One id per file of a patch: the item's own when there is one file, numbered past that. */
    fun fileCallIds(id: String, files: Int): List<String> =
        if (files <= 1) listOf(id) else (0 until files).map { "$id:$it" }

    // --- Agents --------------------------------------------------------------------------------

    private fun agentCall(id: String, item: JsonObject): ToolCall {
        val tool = AppServer.text(item["tool"])
        val prompt = AppServer.text(item["prompt"])
        val model = AppServer.text(item["model"])
        return ToolCall(id, AGENT_TOOL, buildJsonObject {
            put("description", "${agentVerb(tool)}${if (model.isNotEmpty()) " · $model" else ""}")
            put("prompt", prompt)
            put("subagent_type", tool.ifEmpty { "agent" })
        })
    }

    private fun agentResult(id: String, item: JsonObject): ToolResult {
        val states = item["agentsStates"] as? JsonObject
        val lines = states?.entries?.map { (threadId, state) ->
            val status = (state as? JsonObject)?.let { AppServer.text(it["status"]) }.orEmpty()
            val message = (state as? JsonObject)?.let { AppServer.text(it["message"]) }.orEmpty()
            listOf(threadId.take(8), status, message).filter { it.isNotEmpty() }.joinToString(" - ")
        }.orEmpty()
        return ToolResult(id, lines.joinToString("\n"), AppServer.text(item["status"]) == "failed")
    }

    private fun agentVerb(tool: String): String = when (tool) {
        "spawnAgent" -> "Start an agent"
        "sendInput" -> "Message an agent"
        "resumeAgent" -> "Resume an agent"
        "wait" -> "Wait for agents"
        "closeAgent" -> "Close an agent"
        else -> tool.ifEmpty { "Agent" }
    }

    // --- Helpers -------------------------------------------------------------------------------

    private fun argumentsOf(arguments: JsonElement?): JsonObject = when (arguments) {
        is JsonObject -> arguments
        null, is JsonNull -> JsonObject(emptyMap())
        else -> buildJsonObject { put("value", arguments) }
    }

    private fun mcpText(result: JsonObject?): String {
        if (result == null) return ""
        val content = result["content"] as? JsonArray ?: return result["structuredContent"]?.toString().orEmpty()
        return content.mapNotNull { element ->
            val block = element as? JsonObject ?: return@mapNotNull null
            when (AppServer.text(block["type"])) {
                "text" -> AppServer.text(block["text"])
                "image" -> "[image]"
                "resource", "resource_link" -> AppServer.text((block["resource"] as? JsonObject)?.get("uri") ?: block["uri"])
                else -> null
            }
        }.joinToString("\n")
    }

    /**
     * A tool's output as it goes into the feed: a command that printed a megabyte is a card, not a
     * megabyte - the card shows its first lines anyway, and the whole of it travels to every client and
     * into the journal the phone catches up from.
     */
    fun clip(text: String, limit: Int = OUTPUT_LIMIT): String =
        if (text.length <= limit) text else text.take(limit) + "\n… (${text.length - limit} more characters)"

    /** What a card shows when the person said no to it - the same words for a command and a patch. */
    const val DECLINED = "Declined."

    const val PLAN_TOOL = "ExitPlanMode"

    const val AGENT_TOOL = "Task"

    private const val OUTPUT_LIMIT = 30_000

    private val TOOL_ITEMS = setOf(
        "commandExecution",
        "fileChange",
        "mcpToolCall",
        "dynamicToolCall",
        "webSearch",
        "imageView",
        "imageGeneration",
        "collabAgentToolCall",
    )

    /** The usage of one step, in the shape the panel's context meter and the statistics read. */
    fun usageOf(breakdown: JsonObject?): JsonObject? {
        if (breakdown == null) return null
        val input = AppServer.longOf(breakdown["inputTokens"]) ?: 0
        val cached = AppServer.longOf(breakdown["cachedInputTokens"]) ?: 0
        val output = AppServer.longOf(breakdown["outputTokens"]) ?: 0
        return buildJsonObject {
            put("input_tokens", (input - cached).coerceAtLeast(0))
            put("cache_read_input_tokens", cached)
            put("cache_creation_input_tokens", 0)
            put("output_tokens", output)
        }
    }

    internal fun JsonObjectBuilder.putAll(values: JsonObject) = values.forEach { (key, value) -> put(key, value) }

    fun string(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull
}
