package io.github.crmapache.amazingcodex.codex

import com.intellij.openapi.diagnostic.thisLogger
import java.io.File
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * This project's past conversations.
 *
 * Codex keeps them itself - a thread per conversation, written to `~/.codex/sessions` - and the panel
 * starts no database of its own, or its history and the terminal's would drift apart. The list and the
 * pages are asked of Codex (`thread/list`, `thread/turns/list`) through the shared catalog process, and
 * a page is retold in the panel's language by the same translator the live stream uses (see
 * CodexDialect), so a conversation looks the same opened from the history as it did while it was written.
 *
 * Pages are turns, newest first, and the cursor the panel hands back to ask for more is the id of the
 * oldest turn it holds: opaque to the panel, and stable across a restart of either side, which Codex's own
 * cursors are not promised to be.
 */
internal object CodexHistory {

    data class Entry(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val messages: Int,
        /** The name was given (by a model or by a person) rather than guessed from the first line. */
        val named: Boolean = false,
    )

    /** A page of a conversation in the panel's language; [cursor] is null once the beginning is on it. */
    data class Page(val lines: List<String>, val cursor: String?, val model: String = "")

    fun list(workingDirectory: String?, limit: Int = 40): List<Entry> {
        val paths = CodexHome.of(workingDirectory).projectPaths
        if (paths.isEmpty()) return emptyList()

        val result = CodexCatalog.call(
            "thread/list",
            buildJsonObject {
                put("limit", limit)
                put("sortKey", "updated_at")
                putJsonArray("cwd") { paths.forEach { add(JsonPrimitive(it)) } }
            },
        ) as? JsonObject ?: return emptyList()

        return (result["data"] as? JsonArray).orEmpty().mapNotNull { element ->
            val thread = element as? JsonObject ?: return@mapNotNull null
            val id = AppServer.text(thread["id"]).ifEmpty { return@mapNotNull null }
            val name = AppServer.text(thread["name"]).trim()
            val preview = firstLine(AppServer.text(thread["preview"]))
            AppServer.text(thread["path"]).takeIf { it.isNotEmpty() }?.let { files[id] = File(it) }

            Entry(
                id = id,
                title = name.ifEmpty { preview }.ifEmpty { "Untitled" },
                updatedAt = (AppServer.longOf(thread["updatedAt"]) ?: 0) * 1000,
                messages = 0,
                named = name.isNotEmpty(),
            )
        }
    }

    /**
     * The conversation's file on disk - if Codex has written one. A thread that has not said a word yet
     * has none, and that is what tells a tab worth resuming from an empty one (see CodexSessions.moveTo).
     */
    fun transcriptFile(workingDirectory: String?, id: String): File? {
        files[id]?.takeIf { it.isFile }?.let { return it }
        val root = CodexHome.of(workingDirectory).sessionsDirectory
        val found = runCatching {
            root.walkTopDown().maxDepth(4).firstOrNull { it.isFile && it.name.endsWith("-$id.jsonl") }
        }.getOrNull()
        found?.let { files[id] = it }
        return found
    }

    private val files = ConcurrentHashMap<String, File>()

    /** The end of a conversation, as a tab opens it; what comes before is asked for page by page. */
    fun opening(workingDirectory: String?, id: String): Page =
        page(workingDirectory, id, before = null, local = true)

    /**
     * A page older than [before], sized for whoever asked. [local] is the IDE's own panel; a phone's page
     * has to fit into a relay frame capped at 256 KB, and a frame over the cap is dropped whole.
     */
    fun earlier(workingDirectory: String?, id: String, before: String?, local: Boolean): Page =
        page(workingDirectory, id, before, local)

    private fun page(workingDirectory: String?, id: String, before: String?, local: Boolean): Page {
        val maxChars = if (local) DESK_PAGE_CHARS else PHONE_PAGE_CHARS
        val maxTurns = if (local) DESK_PAGE_TURNS else PHONE_PAGE_TURNS
        val outputLimit = if (local) DESK_OUTPUT_CHARS else PHONE_OUTPUT_CHARS

        val collected = ArrayList<List<String>>()
        var chars = 0
        var passed = before == null
        var oldestTurn: String? = null
        var oldestTurnRequest: String? = null
        // Whether every turn down to the very first one is on this page.
        var exhausted = false

        // Where the request holding [before] began, when this reader asked for the page above it: the walk
        // starts there instead of at the newest turn. Still searched for [before] - a cursor is a place,
        // not a promise about what sits in it.
        var cursor: String? = before?.let { cursors[id to it] }

        var guard = 0
        walk@ while (guard++ < MAX_PAGES_WALKED) {
            val requestCursor = cursor
            val result = CodexCatalog.call(
                "thread/turns/list",
                buildJsonObject {
                    put("threadId", id)
                    put("limit", TURNS_PER_REQUEST)
                    put("sortDirection", "desc")
                    put("itemsView", "full")
                    requestCursor?.let { put("cursor", it) }
                },
                timeoutSeconds = PAGE_TIMEOUT_SECONDS,
            ) as? JsonObject ?: break

            val turns = (result["data"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            val next = AppServer.text(result["nextCursor"]).ifEmpty { null }

            for (turn in turns) {
                val turnId = AppServer.text(turn["id"])

                if (!passed) {
                    if (turnId == before) {
                        passed = true
                        continue
                    }
                    // An item's id rather than a turn's: the panel anchors on the oldest event it holds
                    // when it was never given a cursor. What precedes that item in its own turn is the
                    // first thing older than it.
                    val items = (turn["items"] as? JsonArray).orEmpty()
                    val at = items.indexOfFirst { AppServer.text((it as? JsonObject)?.get("id")) == before }
                    if (at < 0) continue
                    passed = true
                    val partial = buildJsonObject {
                        turn.forEach { (key, value) -> if (key != "items") put(key, value) }
                        put("items", JsonArray(items.take(at)))
                    }
                    val lines = CodexReplay.lines(partial, outputLimit, closeTurn = false)
                    if (lines.isNotEmpty()) {
                        collected += lines
                        chars += lines.sumOf { it.length }
                    }
                    oldestTurn = turnId
                    oldestTurnRequest = requestCursor
                    continue
                }

                val lines = CodexReplay.lines(turn, outputLimit, closeTurn = true)
                val full = collected.size >= maxTurns || chars + lines.sumOf { it.length } > maxChars
                if (collected.isNotEmpty() && full) break@walk

                collected += lines
                chars += lines.sumOf { it.length }
                oldestTurn = turnId
                oldestTurnRequest = requestCursor
            }

            if (next == null) {
                exhausted = passed
                break
            }
            cursor = next
        }

        // The page above is found from where the oldest turn's request began (see [cursors]).
        val nextCursor = if (exhausted || oldestTurn == null) null else oldestTurn
        if (nextCursor != null) {
            if (oldestTurnRequest != null) cursors[id to nextCursor] = oldestTurnRequest else cursors.remove(id to nextCursor)
        }

        val lines = collected.reversed().flatten()
        val model = if (before == null) lastModelOf(transcriptFile(workingDirectory, id)) else ""
        return Page(lines, nextCursor, model)
    }

    /**
     * Where the request that held a turn began, by (thread, that turn's id) - the id the panel will hand back
     * as its cursor. Only a speed-up: without it the next page walks down from the newest turn, which is
     * still correct.
     */
    private val cursors = ConcurrentHashMap<Pair<String, String>, String>()

    /**
     * The model a past conversation ran on, read off its file: Codex writes the settings of every turn
     * (`turn_context`) into the thread, and the last one is what the conversation is on now. A conversation
     * opened from the history carries on at its own model rather than at whatever a new tab starts on.
     */
    internal fun lastModelOf(file: File?): String {
        if (file == null || !file.isFile) return ""
        var model = ""
        runCatching {
            file.useLines { lines ->
                for (line in lines) {
                    if (!line.contains(TURN_CONTEXT)) continue
                    val payload = runCatching { Json.parseToJsonElement(line).jsonObject["payload"] as? JsonObject }.getOrNull()
                    (payload?.get("model") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { model = it }
                }
            }
        }
        return model
    }

    /**
     * The last token count a conversation's file holds, in the shape the live notification has
     * (`{"last": {...}, "modelContextWindow": n}`) - how full the window was when the conversation stopped.
     */
    internal fun lastTokenUsage(file: File?): JsonObject? {
        if (file == null || !file.isFile) return null
        var last: String? = null
        runCatching { file.useLines { lines -> lines.forEach { if (it.contains(TOKEN_COUNT)) last = it } } }
        val line = last ?: return null

        val info = runCatching {
            ((Json.parseToJsonElement(line).jsonObject["payload"] as? JsonObject)?.get("info") as? JsonObject)
        }.getOrNull() ?: return null
        val usage = info["last_token_usage"] as? JsonObject ?: return null
        val window = info["model_context_window"] ?: return null

        return buildJsonObject {
            put("last", buildJsonObject {
                put("totalTokens", usage["total_tokens"] ?: JsonPrimitive(0))
                put("inputTokens", usage["input_tokens"] ?: JsonPrimitive(0))
                put("cachedInputTokens", usage["cached_input_tokens"] ?: JsonPrimitive(0))
                put("outputTokens", usage["output_tokens"] ?: JsonPrimitive(0))
                put("reasoningOutputTokens", usage["reasoning_output_tokens"] ?: JsonPrimitive(0))
            })
            put("modelContextWindow", window)
        }
    }

    private const val TOKEN_COUNT = "\"token_count\""

    /** The first line of a thread's preview, cut to fit a row of the history list. */
    private fun firstLine(text: String): String {
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        return if (line.length <= TITLE_CHARS) line else line.take(TITLE_CHARS - 1).trimEnd() + "…"
    }

    /** A path as a flat name: the search index keeps one folder per project under it. */
    internal fun slugFor(path: String): String = path.map { if (it.isLetterOrDigit()) it else '-' }.joinToString("")

    /**
     * Every conversation Codex has written for this project, found by the working directory each file
     * names on its first line - Codex files by day, not by project. For the search index (see SearchIndex).
     */
    fun rolloutsOf(workingDirectory: String?): List<File> {
        val home = CodexHome.of(workingDirectory)
        val paths = home.projectPaths.toSet()
        if (paths.isEmpty()) return emptyList()

        return runCatching {
            home.sessionsDirectory.walkTopDown().maxDepth(4)
                .filter { it.isFile && it.extension == "jsonl" && it.name.startsWith("rollout-") }
                .filter { file -> cwdOf(file)?.let { it in paths } == true }
                .toList()
        }.getOrDefault(emptyList())
    }

    /** The working directory a conversation file was started in - its first line says. */
    internal fun cwdOf(file: File): String? {
        cwds[file.path]?.let { return it }
        val first = runCatching { file.bufferedReader().use { it.readLine() } }.getOrNull() ?: return null
        val cwd = runCatching {
            ((Json.parseToJsonElement(first).jsonObject["payload"] as? JsonObject)?.get("cwd") as? JsonPrimitive)?.contentOrNull
        }.getOrNull() ?: return null
        cwds[file.path] = cwd
        return cwd
    }

    private val cwds = ConcurrentHashMap<String, String>()

    /**
     * The names Codex has given its threads, by id - `session_index.jsonl`, appended to on every rename, so
     * the last line for an id is its current name.
     */
    fun threadNames(workingDirectory: String?): Map<String, String> {
        val file = File(CodexHome.of(workingDirectory).configDirectory, "session_index.jsonl")
        if (!file.isFile) return emptyMap()
        val names = HashMap<String, String>()
        runCatching {
            file.useLines { lines ->
                for (line in lines) {
                    val entry = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                    val id = (entry["id"] as? JsonPrimitive)?.contentOrNull ?: continue
                    val name = (entry["thread_name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    if (name.isNotEmpty()) names[id] = name
                }
            }
        }
        return names
    }

    /** The thread id a conversation file belongs to - the end of its name. */
    fun threadIdOf(file: File): String? = THREAD_ID.find(file.name)?.groupValues?.get(1)

    private val THREAD_ID = Regex("([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.jsonl$")

    private const val TURN_CONTEXT = "\"turn_context\""
    private const val TITLE_CHARS = 80

    private const val TURNS_PER_REQUEST = 10
    private const val MAX_PAGES_WALKED = 200
    private const val PAGE_TIMEOUT_SECONDS = 60L

    /** A tab's page: enough for an ordinary conversation to be on screen whole. */
    internal const val DESK_PAGE_TURNS = 30
    internal const val DESK_PAGE_CHARS = 512 * 1024
    internal const val DESK_OUTPUT_CHARS = 12_000

    /** A phone's page has to survive a relay frame of 256 KB with room for the envelope. */
    internal const val PHONE_PAGE_TURNS = 10
    internal const val PHONE_PAGE_CHARS = 150 * 1024
    internal const val PHONE_OUTPUT_CHARS = 2_000
}

/**
 * One past turn in the panel's language - the replay half of CodexDialect.
 *
 * The person's message, then every item in the order it happened, then the turn's end with the time it
 * took. The tool cards are drawn and closed at once: in a past conversation every call has long finished.
 */
internal object CodexReplay {

    fun lines(turn: JsonObject, outputLimit: Int, closeTurn: Boolean): List<String> {
        val items = (turn["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val startedAt = AppServer.longOf(turn["startedAt"])?.let { Instant.ofEpochSecond(it).toString() }
        val lines = ArrayList<String>()
        var lastText = ""

        for (item in items) {
            val id = AppServer.text(item["id"])
            when (AppServer.text(item["type"])) {
                "userMessage" -> {
                    val (text, images) = CodexDialect.userTextOf(item)
                    if (text.isNotBlank() || images > 0) lines += CodexDialect.userPrompt(text, images, uuid = id, timestamp = startedAt)
                }

                "agentMessage" -> {
                    val text = AppServer.text(item["text"])
                    if (text.isNotBlank()) {
                        lastText = text
                        lines += CodexDialect.assistantText(id, text, model = "", uuid = id)
                    }
                }

                "reasoning" -> {
                    val thinking = CodexDialect.thinkingOf(item)
                    if (thinking.isNotBlank()) lines += CodexDialect.assistantThinking(id, thinking, model = "", uuid = id)
                }

                "plan" -> {
                    val text = AppServer.text(item["text"])
                    if (text.isNotBlank()) {
                        CodexDialect.toolUses(CodexDialect.toolCalls(item), model = "", uuid = id)?.let(lines::add)
                    }
                }

                "contextCompaction" -> lines += CodexDialect.compactBoundary(null, null, null)

                "exitedReviewMode" -> {
                    val review = AppServer.text(item["review"])
                    if (review.isNotBlank()) {
                        lastText = review
                        lines += CodexDialect.assistantText(id, review, model = "", uuid = id)
                    }
                }

                else -> if (CodexDialect.isTool(item)) {
                    CodexDialect.toolUses(CodexDialect.toolCalls(item), model = "", uuid = id)?.let(lines::add)
                    val results = CodexDialect.toolResults(item).map { it.copy(content = CodexDialect.clip(it.content, outputLimit)) }
                    CodexDialect.toolResults(results, uuid = "$id-result")?.let(lines::add)
                }
            }
        }

        if (closeTurn && lines.isNotEmpty()) {
            val status = AppServer.text(turn["status"])
            val error = turn["error"] as? JsonObject
            lines += CodexDialect.result(
                threadId = "",
                durationMs = AppServer.longOf(turn["durationMs"]) ?: 0,
                isError = status == "failed",
                resultText = if (status == "failed") AppServer.text(error?.get("message")) else lastText,
                usage = null,
            )
        }

        return lines
    }

    @Suppress("unused")
    private fun text(element: JsonElement?): String = AppServer.text(element)
}
