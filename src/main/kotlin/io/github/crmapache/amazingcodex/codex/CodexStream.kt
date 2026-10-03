package io.github.crmapache.amazingcodex.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * A live Codex thread, retold as the panel's events while it happens.
 *
 * [CodexDialect] knows what one item looks like on screen; this knows WHEN to say it. A tool card goes up
 * the moment Codex starts the item and is closed when it completes, so a long command stands in the feed
 * with its own clock instead of appearing only once it is over. The answer is typed out delta by delta
 * and laid down whole when the message completes. The turn is closed with a `result` carrying the time
 * it took and the last step's usage, which is what the panel's meta row and context meter read.
 *
 * One instance per conversation, fed only that conversation's notifications (see CodexSession - it
 * filters by thread). Not thread-safe on its own: the session calls it from the one thread that reads
 * the server's output.
 */
internal class CodexStream(
    private val emit: (String) -> Unit,
    private val model: () -> String,
    private val threadId: () -> String,
) {

    /** A plan-mode turn proposed this plan; the session turns it into the plan card's question. */
    data class PlanProposal(val itemId: String, val text: String)

    private var turnId: String? = null
    private var turnStartedAt = 0L

    /** The last thing the agent said in this turn: the `result` of the turn carries it. */
    private var lastAgentText = ""

    /** The last step's usage, already in the panel's shape - see CodexDialect.usageOf. */
    private var lastUsage: JsonObject? = null

    /** Tool cards already drawn for this turn: an item that completes without having started gets both. */
    private val drawn = HashSet<String>()

    /** How many times in a row this turn has been retried - the retry card counts attempts. */
    private var retryAttempt = 0

    /** When the compaction this turn is running began, for its card's duration. */
    private var compactingSince = 0L
    private var tokensBeforeCompaction: Long? = null

    /** A counter for the task strip's snapshots: each plan update is a call of its own in the feed. */
    private var planUpdates = 0

    /** The plan steps as Codex last sent them - the strip is redrawn only when they change. */
    private var lastPlan: List<Pair<String, String>> = emptyList()

    val activeTurn: String? get() = turnId

    fun turnStarted(turn: JsonObject, now: Long = System.currentTimeMillis()) {
        turnId = AppServer.text(turn["id"]).ifEmpty { null }
        turnStartedAt = AppServer.longOf(turn["startedAt"])?.times(1000)?.takeIf { it > 0 } ?: now
        lastAgentText = ""
        // The usage is this turn's to report. Kept from the one before, a turn that ends without a token
        // count of its own - failed on the sign-in or on a limit before the model answered - would sign
        // its result with the previous turn's figures, and the statistics would count them twice.
        lastUsage = null
        drawn.clear()
        retryAttempt = 0
    }

    fun itemStarted(item: JsonObject) {
        closeRetry()
        when (AppServer.text(item["type"])) {
            "contextCompaction" -> {
                compactingSince = System.currentTimeMillis()
                emit(CodexDialect.compacting())
            }

            else -> if (CodexDialect.isTool(item)) drawCard(item)
        }
    }

    /**
     * An item has finished. Returns the plan a plan-mode turn arrived at, if that is what this was - the
     * session raises the card's question, because only it can hold a question open.
     */
    fun itemCompleted(item: JsonObject): PlanProposal? {
        closeRetry()
        val id = AppServer.text(item["id"])

        when (AppServer.text(item["type"])) {
            "agentMessage" -> {
                val text = AppServer.text(item["text"])
                if (text.isNotBlank()) {
                    lastAgentText = text
                    emit(CodexDialect.assistantText(id, text, model(), uuid = id, usage = lastUsage))
                }
            }

            "reasoning" -> {
                val thinking = CodexDialect.thinkingOf(item)
                if (thinking.isNotBlank()) emit(CodexDialect.assistantThinking(id, thinking, model(), uuid = id))
            }

            "plan" -> {
                val text = AppServer.text(item["text"]).trim()
                if (text.isNotEmpty()) {
                    CodexDialect.toolUses(CodexDialect.toolCalls(item), model(), uuid = id)?.let(emit)
                    return PlanProposal(id, text)
                }
            }

            "contextCompaction" -> finishCompaction()

            "enteredReviewMode" -> Unit

            "exitedReviewMode" -> {
                val review = AppServer.text(item["review"])
                if (review.isNotBlank()) {
                    lastAgentText = review
                    emit(CodexDialect.assistantText(id, review, model(), uuid = id))
                }
            }

            else -> if (CodexDialect.isTool(item)) {
                if (id !in drawn) drawCard(item)
                CodexDialect.toolResults(CodexDialect.toolResults(item), uuid = "$id-result")?.let(emit)
            }
        }

        return null
    }

    fun agentDelta(delta: String) {
        if (delta.isNotEmpty()) emit(CodexDialect.textDelta(delta))
    }

    fun reasoningDelta(delta: String) {
        if (delta.isNotEmpty()) emit(CodexDialect.thinkingDelta(delta))
    }

    /**
     * Codex's own step list for the turn - the task strip over the input field.
     *
     * It arrives whole every time, which is exactly the shape the older TodoWrite call has, so it goes out
     * as one: a call whose input is the list, answered at once (there is nothing for it to wait on).
     */
    fun planUpdated(plan: JsonArray) {
        val steps = plan.mapNotNull { element ->
            val step = element as? JsonObject ?: return@mapNotNull null
            AppServer.text(step["step"]) to AppServer.text(step["status"])
        }.filter { it.first.isNotBlank() }
        if (steps == lastPlan) return
        lastPlan = steps

        planUpdates += 1
        val id = "plan-${turnId ?: "turn"}-$planUpdates"
        val call = CodexDialect.ToolCall(
            id,
            TODO_TOOL,
            buildJsonObject {
                putJsonArray("todos") {
                    for ((step, status) in steps) {
                        addJsonObject {
                            put("content", step)
                            put("activeForm", step)
                            put(
                                "status",
                                when (status) {
                                    "completed" -> "completed"
                                    "inProgress" -> "in_progress"
                                    else -> "pending"
                                },
                            )
                        }
                    }
                }
            },
        )
        CodexDialect.toolUses(listOf(call), model(), uuid = id)?.let(emit)
        CodexDialect.toolResults(listOf(CodexDialect.ToolResult(id, "", false)), uuid = "$id-result")?.let(emit)
    }

    /** The token counts of the turn so far - see CodexSession, which also tells the context meter. */
    fun tokenUsage(last: JsonObject?) {
        CodexDialect.usageOf(last)?.let { lastUsage = it }
    }

    /**
     * Something went wrong. A request Codex will try again is the retry card with its countdown - the one
     * thing on screen while a turn stands still waiting for the API. One it will not retry ends the turn,
     * and the turn's own `completed` says so; nothing is drawn twice.
     */
    fun error(error: JsonObject, willRetry: Boolean) {
        if (!willRetry) return
        retryAttempt += 1
        emit(
            CodexDialect.apiRetry(
                attempt = retryAttempt,
                maxRetries = MAX_RETRIES_SHOWN,
                delayMs = 0,
                status = httpStatus(error),
                error = AppServer.text(error["message"]),
            ),
        )
    }

    fun rerouted(from: String, to: String, reason: String) {
        emit(CodexDialect.modelFallback(from, to, reason))
    }

    /**
     * The turn is over: every card it left open is closed by the panel on the `result`, and the result
     * carries how long the turn took and what it cost in tokens.
     *
     * A turn that failed on the sign-in is said the way the panel recognises it - an answer of the CLI's
     * own with `authentication_failed` beside it, which is what puts the sign-in door into the feed.
     */
    fun turnCompleted(turn: JsonObject, now: Long = System.currentTimeMillis()) {
        val status = AppServer.text(turn["status"])
        val error = turn["error"] as? JsonObject
        val message = error?.let { AppServer.text(it["message"]) }.orEmpty()
        val info = error?.get("codexErrorInfo")
        val failed = status == "failed"

        if (failed && CodexErrors.isAuth(info)) {
            emit(
                CodexDialect.assistantText(
                    id = "auth-${turnId ?: now}",
                    text = message.ifEmpty { "Codex could not sign in." },
                    model = SYNTHETIC,
                    uuid = "auth-${turnId ?: now}",
                    error = AUTH_FAILED,
                ),
            )
        }

        if (failed && CodexErrors.isUsageLimit(info)) {
            emit(CodexDialect.rateLimited(resetsAtSeconds = null, window = "codex"))
        }

        val duration = AppServer.longOf(turn["durationMs"]) ?: (now - turnStartedAt).coerceAtLeast(0)
        emit(
            CodexDialect.result(
                threadId = threadId(),
                durationMs = duration,
                isError = failed && !CodexErrors.isAuth(info),
                resultText = if (failed) message.ifEmpty { "The turn failed." } else lastAgentText,
                usage = lastUsage,
            ),
        )

        turnId = null
        lastUsage = null
        drawn.clear()
        retryAttempt = 0
        lastPlan = emptyList()
    }

    /** A compaction of the whole conversation (`/compact`) runs as a turn of its own - see CodexSession. */
    fun compactionFinished() = finishCompaction()

    private fun finishCompaction() {
        if (compactingSince == 0L) return
        val took = System.currentTimeMillis() - compactingSince
        compactingSince = 0L
        emit(CodexDialect.compactBoundary(tokensBeforeCompaction, null, took))
    }

    fun noteTokensBeforeCompaction(tokens: Long?) {
        tokensBeforeCompaction = tokens
    }

    private fun drawCard(item: JsonObject) {
        val calls = CodexDialect.toolCalls(item)
        val id = AppServer.text(item["id"])
        drawn += id
        CodexDialect.toolUses(calls, model(), uuid = id)?.let(emit)
    }

    /**
     * The first event after a run of retries is the whole news about how they ended - the panel closes its
     * retry card on it by itself (see closeRetryFor). All that is left here is to stop counting.
     */
    private fun closeRetry() {
        retryAttempt = 0
    }

    private fun httpStatus(error: JsonObject): Int? = CodexErrors.httpStatus(error["codexErrorInfo"])

    companion object {
        /** The panel's name for a whole-list task update - see feed/build.ts. */
        const val TODO_TOOL = "TodoWrite"

        /** How the panel recognises an answer that is not the model's but the client's own. */
        const val SYNTHETIC = "<synthetic>"

        /** The machine word the panel's sign-in door is keyed on - see AgentStream.isAuthFailure. */
        const val AUTH_FAILED = "authentication_failed"

        /** Codex does not say how many retries it will make; the card needs a figure, and this is its own. */
        private const val MAX_RETRIES_SHOWN = 5
    }
}
