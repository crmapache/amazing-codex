package io.github.crmapache.amazingcodex.codex

import com.intellij.openapi.diagnostic.thisLogger
import io.github.crmapache.amazingcodex.scenario.ScenarioConversations
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
import kotlinx.serialization.json.putJsonObject

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
        /**
         * Whose name [title] is, in the tab's terms (see SessionSnapshot): a person's is shown whole and
         * is never renamed by a model again, a model's is a stand-in - see AutoTitles.
         */
        val titleSource: String = SessionSnapshot.TITLE_HEURISTIC,
    )

    /** A page of a conversation in the panel's language; [cursor] is null once the beginning is on it. */
    data class Page(val lines: List<String>, val cursor: String?, val model: String = "")

    /**
     * The newest [limit] conversations of this project, without the ones a scenario run raised.
     *
     * Those are left out on purpose (see ScenarioConversations): a night of runs is a dozen conversations
     * nobody held, and the list answers "what was I doing". Left out BEFORE the limit rather than after
     * it: Codex hands the list out in pages, and filtering one page would leave a morning after a long
     * night with a list of two rows - so the pages are read on until [limit] rows a person held are in
     * hand or the threads run out, with a ceiling on the pages so a project of nothing but runs costs a
     * bounded wait.
     */
    fun list(workingDirectory: String?, limit: Int = 40): List<Entry> {
        val paths = CodexHome.of(workingDirectory).projectPaths
        if (paths.isEmpty()) return emptyList()

        val hidden = ScenarioConversations(workingDirectory).all()
        val givenByModel = AutoTitles(workingDirectory).all()
        val entries = ArrayList<Entry>()
        var cursor: String? = null
        var pages = 0

        while (entries.size < limit && pages++ < MAX_LIST_PAGES) {
            val result = CodexCatalog.call(
                "thread/list",
                buildJsonObject {
                    put("limit", limit)
                    put("sortKey", "updated_at")
                    putJsonArray("cwd") { paths.forEach { add(JsonPrimitive(it)) } }
                    cursor?.let { put("cursor", it) }
                },
            ) as? JsonObject ?: break

            for (element in (result["data"] as? JsonArray).orEmpty()) {
                val thread = element as? JsonObject ?: continue
                val id = AppServer.text(thread["id"]).ifEmpty { null } ?: continue
                if (id in hidden) continue
                val name = AppServer.text(thread["name"]).trim()
                // The preview is the start of the first message as Codex joined its parts - the editor note
                // that went with it included, glued on without a break (measured on 0.152), so it is cut off
                // at its heading (see IdeContextPrompt).
                val preview = firstLine(AppServer.text(thread["preview"]).substringBefore(IdeContextPrompt.HEADER))
                AppServer.text(thread["path"]).takeIf { it.isNotEmpty() }?.let { files[id] = File(it) }

                entries += Entry(
                    id = id,
                    title = name.ifEmpty { preview }.ifEmpty { "Untitled" },
                    updatedAt = (AppServer.longOf(thread["updatedAt"]) ?: 0) * 1000,
                    messages = 0,
                    named = name.isNotEmpty(),
                    titleSource = AutoTitles.sourceOf(name, givenByModel[id]),
                )
                if (entries.size >= limit) break
            }

            cursor = AppServer.text(result["nextCursor"]).ifEmpty { null } ?: break
        }

        return entries
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
        // A desk page is measured in characters, a phone's in bytes: the phone's has to fit a relay frame,
        // and a frame is bytes - Russian, Chinese and the like weigh two or three bytes a character, so a
        // page measured in characters went past the frame on exactly those conversations and was dropped
        // whole, which on the phone is "load earlier" doing nothing at all.
        val budget = if (local) DESK_PAGE_CHARS else PHONE_PAGE_BYTES
        val weigh: (String) -> Int = if (local) String::length else ::utf8Bytes
        val maxTurns = if (local) DESK_PAGE_TURNS else PHONE_PAGE_TURNS
        val outputLimit = if (local) DESK_OUTPUT_CHARS else PHONE_OUTPUT_CHARS

        val collected = ArrayList<List<String>>()
        var chars = 0
        var passed = before == null
        var oldestTurn: String? = null
        var oldestTurnRequest: String? = null
        // Whether every turn down to the very first one is on this page.
        var exhausted = false

        // The questions with options the conversation asked, by turn - they live in the thread's file alone
        // (see [asksOf]), read once for the page.
        val asks = asksOf(transcriptFile(workingDirectory, id))

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
                    val lines = CodexReplay.lines(partial, outputLimit, closeTurn = false, asks = asks[turnId].orEmpty())
                    if (lines.isNotEmpty()) {
                        collected += lines
                        chars += lines.sumOf(weigh)
                    }
                    oldestTurn = turnId
                    oldestTurnRequest = requestCursor
                    continue
                }

                val lines = CodexReplay.lines(turn, outputLimit, closeTurn = true, asks = asks[turnId].orEmpty())
                val full = collected.size >= maxTurns || chars + lines.sumOf(weigh) > budget
                if (collected.isNotEmpty() && full) break@walk

                collected += lines
                chars += lines.sumOf(weigh)
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

    /**
     * A question with options the agent asked (`request_user_input`) and how it was answered - null
     * [answers] when it never was. [input] is the panel's question card's (see CodexDialect.askInput).
     * [after] is the agent's item it was asked right after - a remark or a thought, by the id
     * `thread/turns/list` gives it too - or null when the agent had said nothing yet in the turn;
     * [answeredAt] is when the person answered, as the file stamped it.
     */
    data class Ask(
        val callId: String,
        val input: JsonObject,
        val answers: Map<String, String>?,
        val after: String? = null,
        val answeredAt: String? = null,
    )

    /**
     * The questions a conversation's file holds, by the id of the turn they were asked in.
     *
     * Read off the file because nothing else has them: `thread/turns/list` leaves the question and its
     * answer out of a turn's items altogether (measured on 0.152) - they are a call of the model's and the
     * person's reply to it, written into the thread as `function_call` and `function_call_output`. Without
     * them a conversation reopened from the history had a hole exactly where a decision was taken, and
     * one that ended on a question lost the question it was waiting on.
     *
     * The turn is the one whose `turn_context` came last before the call; the answer is matched by the
     * call's id.
     */
    internal fun asksOf(file: File?): Map<String, List<Ask>> {
        if (file == null || !file.isFile) return emptyMap()

        val calls = LinkedHashMap<String, Asked>()
        val replies = HashMap<String, Pair<JsonObject, String?>>()
        var turn = ""
        // The agent's last remark or thought in the turn so far - where the next question stands.
        var lastSaid: String? = null

        runCatching {
            file.useLines { lines ->
                for (line in lines) {
                    // The agent's own items are many and some heavy (a thought carries its encrypted body), so
                    // only their head is read, never the whole line.
                    val said = SAID.find(line.take(SAID_HEAD))
                    if (said != null) {
                        if (said.groupValues[1] == "reasoning" || said.groupValues[3] == "assistant") lastSaid = said.groupValues[2]
                        continue
                    }

                    val context = line.contains(TURN_CONTEXT)
                    if (!context && !line.contains(ASK_CALL) && !line.contains(FUNCTION_OUTPUT)) continue
                    val record = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                    val payload = record["payload"] as? JsonObject ?: continue

                    if (context) {
                        AppServer.text(payload["turn_id"]).takeIf { it.isNotEmpty() }?.let {
                            if (it != turn) lastSaid = null
                            turn = it
                        }
                        continue
                    }

                    val callId = AppServer.text(payload["call_id"]).ifEmpty { null } ?: continue
                    when (AppServer.text(payload["type"])) {
                        "function_call" -> if (AppServer.text(payload["name"]) == ASK_NAME) {
                            val arguments = runCatching { Json.parseToJsonElement(AppServer.text(payload["arguments"])).jsonObject }.getOrNull()
                            val questions = (arguments?.get("questions") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                            if (questions.isNotEmpty()) calls[callId] = Asked(turn, questions, lastSaid)
                        }

                        "function_call_output" -> runCatching {
                            Json.parseToJsonElement(AppServer.text(payload["output"])).jsonObject
                        }.getOrNull()?.let { replies[callId] = it to AppServer.text(record["timestamp"]).ifEmpty { null } }
                    }
                }
            }
        }

        return calls.entries.groupBy({ it.value.turn }) { (callId, asked) ->
            val (input, ids) = CodexDialect.askInput(asked.questions)
            val reply = replies[callId]?.first?.get("answers") as? JsonObject
            val answers = reply?.let { byId ->
                ids.mapNotNull { (question, id) ->
                    val picked = ((byId[id] as? JsonObject)?.get("answers") as? JsonArray).orEmpty()
                        .map { AppServer.text(it) }.filter { it.isNotBlank() }
                    if (picked.isEmpty()) null else question to picked.joinToString(", ")
                }.toMap()
            }
            Ask(callId, input, answers, after = asked.after, answeredAt = replies[callId]?.second)
        }
    }

    private class Asked(val turn: String, val questions: List<JsonObject>, val after: String?)

    /** The head of an agent's item in a thread's file: its kind, its id, and for a message who said it. */
    private val SAID = Regex(""""type":"response_item","payload":\{"type":"(message|reasoning)","id":"([^"]+)"(?:,"role":"([a-z]+)")?""")
    private const val SAID_HEAD = 400

    private const val ASK_NAME = "request_user_input"
    private const val ASK_CALL = "\"request_user_input\""
    private const val FUNCTION_OUTPUT = "\"function_call_output\""

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

    /**
     * A phone's page has to survive a relay frame of 256 KB with room for the envelope and the sealing -
     * in BYTES, which is what the frame counts (see [page]).
     */
    internal const val PHONE_PAGE_TURNS = 10
    internal const val PHONE_PAGE_BYTES = 128 * 1024
    internal const val PHONE_OUTPUT_CHARS = 2_000

    /** How many pages of the thread list are read at most for one listing - see [list]. */
    private const val MAX_LIST_PAGES = 10

    /** A line's weight on the wire: what a relay frame is measured in. */
    internal fun utf8Bytes(line: String): Int {
        var bytes = 0
        var index = 0
        while (index < line.length) {
            val char = line[index]
            bytes += when {
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                Character.isHighSurrogate(char) && index + 1 < line.length && Character.isLowSurrogate(line[index + 1]) -> {
                    index++
                    4
                }
                else -> 3
            }
            index++
        }
        return bytes
    }
}

/**
 * One past turn in the panel's language - the replay half of CodexDialect.
 *
 * The person's message, then every item in the order it happened, then the turn's end with the time it
 * took. The tool cards are drawn and closed at once: in a past conversation every call has long finished.
 */
internal object CodexReplay {

    fun lines(turn: JsonObject, outputLimit: Int, closeTurn: Boolean, asks: List<CodexHistory.Ask> = emptyList()): List<String> {
        val items = (turn["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        // Where the turn's questions go: before its final answer - the question is what the answer came out
        // of - or at its end when there is none (see [CodexHistory.asksOf] on why they are not items).
        val askAt = items.indexOfLast { AppServer.text(it["type"]) == "agentMessage" && AppServer.text(it["phase"]) == "final_answer" }
            .takeIf { it >= 0 } ?: items.size
        // Each question where it was asked: right after the agent's item it followed, or right after the
        // person's message when the agent had said nothing yet in the turn. An item the turn's list does not
        // have leaves it to the guess above.
        val slots = asks.groupBy { ask ->
            val after = ask.after?.let { id -> items.indexOfFirst { AppServer.text(it["id"]) == id } }
            when {
                after != null && after >= 0 -> after + 1
                ask.after == null ->
                    items.indexOfFirst { AppServer.text(it["type"]) == "userMessage" }.let { if (it >= 0) it + 1 else 0 }
                else -> askAt
            }
        }
        val startedAt = AppServer.longOf(turn["startedAt"])?.let { Instant.ofEpochSecond(it).toString() }
        val lines = ArrayList<String>()
        var lastText = ""
        // The review's findings come back once more as an ordinary message (see CodexStream.reviewSaid).
        var reviewSaid: String? = null

        fun askAt(index: Int) {
            for (question in slots[index].orEmpty()) lines += askLines(question)
        }

        for ((index, item) in items.withIndex()) {
            askAt(index)
            val id = AppServer.text(item["id"])
            when (AppServer.text(item["type"])) {
                "userMessage" -> {
                    val (text, images) = CodexDialect.userTextOf(item)
                    if (text.isNotBlank() || images > 0) {
                        lines += CodexDialect.userPrompt(text, images, uuid = id, timestamp = startedAt, ideContext = CodexDialect.userContextOf(item))
                    }
                }

                "agentMessage" -> {
                    val text = AppServer.text(item["text"])
                    val repeatsReview = reviewSaid != null && text.trim() == reviewSaid
                    if (repeatsReview) reviewSaid = null
                    if (text.isNotBlank() && !repeatsReview) {
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
                        reviewSaid = review.trim()
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

        askAt(items.size)

        // A turn that stopped on a question nobody answered is left open: the question is the last thing
        // that happened, and the panel brings it back as a card to answer (see feed/build.ts, revivedAsk).
        val waiting = asks.any { it.answers == null }
        if (closeTurn && lines.isNotEmpty() && !waiting) {
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

    /** One question as the panel reads it: the card's call, and the answer under it when there was one. */
    private fun askLines(ask: CodexHistory.Ask): List<String> {
        val lines = ArrayList<String>()
        CodexDialect.toolUses(listOf(CodexDialect.ToolCall(ask.callId, CodexLaunch.ASK_TOOL, ask.input)), model = "", uuid = ask.callId)
            ?.let(lines::add)

        val answers = ask.answers ?: return lines
        val summary = answers.entries.joinToString("\n") { (question, answer) -> "$question: $answer" }
        CodexDialect.toolResults(
            listOf(CodexDialect.ToolResult(ask.callId, summary, isError = answers.isEmpty())),
            uuid = "${ask.callId}-answer",
            timestamp = ask.answeredAt,
            toolUseResult = buildJsonObject {
                putJsonObject("answers") { answers.forEach { (question, answer) -> put(question, answer) } }
            },
        )?.let(lines::add)
        return lines
    }

    @Suppress("unused")
    private fun text(element: JsonElement?): String = AppServer.text(element)
}
