package io.github.crmapache.amazingcodex.codex

import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.PlatformTestUtil
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * A stand-in for `codex app-server` to stand a conversation on in a test: it answers what it is asked the way Codex
 * does as far as a conversation needs - the handshake, a thread opened or resumed, a turn started, a steer refused (or
 * taken, see [takesSteers]) - and writes down every line it is sent. So a test checks what reached Codex, not only what
 * the screens were told.
 *
 * What Codex says by itself - a turn starting and ending, the account's limits - is told by the test, through
 * CodexSession.heardFromCodex, at the moment it chooses: a script cannot know when that is. It goes through the
 * same reader a live process's line does, so the conversation's own state (its turn, its plan, the limit it heard)
 * follows it exactly as it would follow Codex.
 *
 * Raised through the setting that names the executable, which is looked at before anything on the machine (see
 * CodexLookup.candidates): no real Codex comes up while this stands, and [close] puts the setting back. Each process
 * names its threads and turns by its own pid and the request's id, so two processes of one tab never collide.
 *
 * A thread comes up on a service tier the way Codex's does: the one the request asked for, otherwise the one its
 * config names ([configuredTier]), otherwise none (`null`). And on a model the same way: the one the open asked for,
 * otherwise [DEFAULT_MODEL] - what this stand-in's config would name.
 */
internal class FakeCodex : AutoCloseable {

    private val directory: File = Files.createTempDirectory("fake-codex").toFile()

    /** Every line the fake processes were sent, one JSON-RPC message a line, in the order they arrived. */
    private val log = File(directory, "requests.jsonl")

    private val script = File(directory, "codex")

    private val tierFile = File(directory, "service-tier")

    /**
     * What `service_tier` the stand-in's config.toml would say - the tier a thread opened without asking for one
     * comes up on. Read by every request, so it can change between two opens.
     */
    var configuredTier: String? = null
        set(value) {
            field = value
            if (value == null) tierFile.delete() else tierFile.writeText(value)
        }

    private val steersFile = File(directory, "takes-steers")

    /**
     * Whether a running turn takes words written into it (`turn/steer`) - by default it does not, the way Codex refuses
     * a steer into a review or a compaction. Read by every request, so it can change between two steers.
     */
    var takesSteers: Boolean = false
        set(value) {
            field = value
            if (value) steersFile.writeText("yes") else steersFile.delete()
        }

    private val executableBefore = CodexPreferences.executablePath

    init {
        script.writeText(
            SCRIPT.replace(LOG_MARK, log.absolutePath).replace(TIER_MARK, tierFile.absolutePath).replace(STEERS_MARK, steersFile.absolutePath),
        )
        script.setExecutable(true)
        CodexPreferences.executablePath = script.absolutePath
    }

    /** Every request sent to any fake process so far - the conversation's, a title's, the shared catalogue's. */
    fun requests(): List<JsonObject> {
        if (!log.isFile) return emptyList()
        return log.readLines().mapNotNull { line -> runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() }
    }

    /** The requests of one method on one thread, in order. */
    fun requests(method: String, thread: String): List<JsonObject> =
        requests().filter { text(it["method"]) == method && text(params(it)["threadId"]) == thread }

    /** The words each turn started on [thread] was given - the first text item of its input. */
    fun turnsStarted(thread: String): List<String> = requests("turn/start", thread).map(::words)

    /** The words each message steered into a running turn of [thread] carried. */
    fun steered(thread: String): List<String> = requests("turn/steer", thread).map(::words)

    /**
     * Wait until everything [session] has written to its process is written down and answered, and the answers
     * have been heard.
     *
     * A question goes after everything already written, the process answers in order, and its answers are read in
     * order: once this question's answer is in, so is every earlier one. What makes "nothing more was sent" a
     * thing a test can say, rather than a pause it hopes is long enough.
     */
    fun settled(session: CodexSession) {
        val answered = CountDownLatch(1)
        session.requestUsage(onUsage = { answered.countDown() }, onFailure = { answered.countDown() })
        check(waitFor { answered.count == 0L }) { "the fake Codex did not answer in time" }
    }

    override fun close() {
        // The shared catalogue process may have been raised over this one (a conversation's start reads the
        // settings through it - see CodexConfigDesk.warm): let go of it while it is still this, so nothing goes on
        // asking a stand-in after the test - or, the setting put back, raises the real one for a question.
        CodexCatalog.shutdown()
        CodexPreferences.executablePath = executableBefore
        directory.deleteRecursively()
    }

    companion object {
        /** The model a thread opened without one comes up on - Codex's "default" in this stand-in. */
        const val DEFAULT_MODEL = "gpt-fake"

        private const val LOG_MARK = "@LOG@"
        private const val TIER_MARK = "@TIER@"
        private const val STEERS_MARK = "@STEERS@"

        /**
         * Codex's app-server, as far as a conversation needs one. Our client writes `method` and then `id` first in
         * every request (see AppServer.request), which is what the patterns below rest on; a notification has no id
         * and is only written down. Anything not `app-server` - a probe of `--help`, `mcp list` - ends at once.
         */
        private val SCRIPT = """
            #!/bin/sh
            [ "${'$'}1" = "app-server" ] || exit 0
            log='$LOG_MARK'
            me=${'$'}${'$'}
            while IFS= read -r line; do
              printf '%s\n' "${'$'}line" >> "${'$'}log"
              id=${'$'}(printf '%s\n' "${'$'}line" | sed -n 's/^{"method":"[^"]*","id":\([0-9][0-9]*\).*/\1/p')
              [ -n "${'$'}id" ] || continue
              method=${'$'}(printf '%s\n' "${'$'}line" | sed -n 's/^{"method":"\([^"]*\)".*/\1/p')
              thread=${'$'}(printf '%s\n' "${'$'}line" | sed -n 's/.*"threadId":"\([^"]*\)".*/\1/p')
              asked=${'$'}(printf '%s\n' "${'$'}line" | sed -n 's/.*"serviceTier":"\([^"]*\)".*/\1/p')
              [ -n "${'$'}asked" ] || asked=${'$'}(cat '$TIER_MARK' 2>/dev/null)
              if [ -n "${'$'}asked" ]; then tier="\"${'$'}asked\""; else tier=null; fi
              model=${'$'}(printf '%s\n' "${'$'}line" | sed -n 's/.*"model":"\([^"]*\)".*/\1/p')
              [ -n "${'$'}model" ] || model='$DEFAULT_MODEL'
              case "${'$'}method" in
                initialize) printf '{"id":%s,"result":{"userAgent":"fake-codex"}}\n' "${'$'}id" ;;
                thread/start|thread/fork) printf '{"id":%s,"result":{"thread":{"id":"thread-%s-%s"},"model":"%s","serviceTier":%s}}\n' "${'$'}id" "${'$'}me" "${'$'}id" "${'$'}model" "${'$'}tier" ;;
                thread/resume) printf '{"id":%s,"result":{"thread":{"id":"%s"},"model":"%s","serviceTier":%s}}\n' "${'$'}id" "${'$'}thread" "${'$'}model" "${'$'}tier" ;;
                turn/start) printf '{"id":%s,"result":{"turn":{"id":"turn-%s-%s","status":"inProgress","items":[]}}}\n' "${'$'}id" "${'$'}me" "${'$'}id" ;;
                turn/steer)
                  if [ -f '$STEERS_MARK' ]; then printf '{"id":%s,"result":{}}\n' "${'$'}id"
                  else printf '{"id":%s,"error":{"code":-32600,"message":"This turn cannot be steered."}}\n' "${'$'}id"; fi ;;
                *) printf '{"id":%s,"result":{}}\n' "${'$'}id" ;;
              esac
            done
        """.trimIndent() + "\n"

        private fun params(request: JsonObject): JsonObject = request["params"] as? JsonObject ?: JsonObject(emptyMap())

        private fun words(request: JsonObject): String {
            val input = params(request)["input"] as? JsonArray
            return text((input?.firstOrNull() as? JsonObject)?.get("text"))
        }

        private fun text(element: Any?): String = (element as? JsonPrimitive)?.contentOrNull.orEmpty()

        /**
         * Whether [condition] came true within [timeoutMs], looked at every few milliseconds. On the test's own UI
         * thread the events queued for it are let through between looks: what is waited for may be one of them.
         */
        fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
            val until = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < until) {
                if (condition()) return true
                if (ApplicationManager.getApplication()?.isDispatchThread == true) {
                    PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
                }
                Thread.sleep(POLL_MS)
            }
            return condition()
        }

        private const val POLL_MS = 10L

        /**
         * What Codex would say next, as the lines it would write - for CodexSession.heardFromCodex. The shapes are
         * Codex 0.163's own (`codex app-server generate-json-schema`).
         */
        fun turnStarted(thread: String, turn: String): String =
            """{"method":"turn/started","params":{"threadId":"$thread","turn":{"id":"$turn","status":"inProgress","items":[]}}}"""

        fun turnCompleted(thread: String, turn: String): String =
            """{"method":"turn/completed","params":{"threadId":"$thread","turn":{"id":"$turn","status":"completed","items":[]}}}"""

        fun turnInterrupted(thread: String, turn: String): String =
            """{"method":"turn/completed","params":{"threadId":"$thread","turn":{"id":"$turn","status":"interrupted","items":[]}}}"""

        /** A turn Codex refused - [code] is its `codexErrorInfo`, `usageLimitExceeded` for the plan's limit. */
        fun turnRefused(thread: String, turn: String, code: String = "usageLimitExceeded"): String =
            """{"method":"turn/completed","params":{"threadId":"$thread","turn":{"id":"$turn","status":"failed","items":[],""" +
                """"error":{"message":"You've hit your usage limit.","codexErrorInfo":"$code"}}}}"""

        /** What Codex says when a turn changed the thread's service tier (`thread/settings/updated`, 0.163). */
        fun tierChanged(thread: String, tier: String?): String {
            val said = tier?.let { "\"$it\"" } ?: "null"
            return """{"method":"thread/settings/updated","params":{"threadId":"$thread","threadSettings":{"model":"gpt-fake","serviceTier":$said}}}"""
        }

        /** What Codex says when a turn moved the thread to [model] (`thread/settings/updated`, 0.163). */
        fun modelChanged(thread: String, model: String): String =
            """{"method":"thread/settings/updated","params":{"threadId":"$thread","threadSettings":{"model":"$model"}}}"""

        /** An answer of the agent's, complete (`item/completed` of an `agentMessage`). */
        fun answered(thread: String, turn: String, item: String, text: String): String =
            """{"method":"item/completed","params":{"threadId":"$thread","turnId":"$turn","item":{"type":"agentMessage","id":"$item","text":"$text"}}}"""

        /** Codex's count of the thread's window after a step (`thread/tokenUsage/updated`). */
        fun counted(thread: String, turn: String, total: Long, window: Long): String =
            """{"method":"thread/tokenUsage/updated","params":{"threadId":"$thread","turnId":"$turn","tokenUsage":""" +
                """{"last":{"inputTokens":$total,"cachedInputTokens":0,"outputTokens":0,"reasoningOutputTokens":0,"totalTokens":$total},""" +
                """"total":{"inputTokens":$total,"cachedInputTokens":0,"outputTokens":0,"reasoningOutputTokens":0,"totalTokens":$total},""" +
                """"modelContextWindow":$window}}}"""

        /** Codex moved [turn] to another model by itself (`model/rerouted`). */
        fun rerouted(thread: String, turn: String, from: String, to: String): String =
            """{"method":"model/rerouted","params":{"threadId":"$thread","turnId":"$turn","fromModel":"$from","toModel":"$to","reason":"highRiskCyberActivity"}}"""

        /** The account's limits as a rolling update tells them - [snapshot] is the `rateLimits` object. */
        fun rateLimits(snapshot: String): String = """{"method":"account/rateLimits/updated","params":{"rateLimits":$snapshot}}"""

        /** The plan's five-hour window full until [resetsAt], in seconds. */
        fun fiveHoursFull(resetsAt: Long): String = rateLimits(
            """{"limitId":"codex","primary":{"usedPercent":100,"windowDurationMins":300,"resetsAt":$resetsAt},""" +
                """"secondary":{"usedPercent":40,"windowDurationMins":10080,"resetsAt":${resetsAt + 3 * 86_400}},""" +
                """"rateLimitReachedType":"rate_limit_reached"}""",
        )
    }
}
