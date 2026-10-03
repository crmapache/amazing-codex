package io.github.crmapache.amazingcodex.codex

import com.intellij.util.concurrency.AppExecutorUtil
import io.github.crmapache.amazingcodex.codex.accounts.CodexAccounts
import io.github.crmapache.amazingcodex.feedback.DiagnosticsLog
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A Codex process raised for one job and put down right after it.
 *
 * Two kinds of job come here. A question about an account - its limits, its models - asked in that
 * account's own environment, which is what lets the accounts screen show real figures beside every row
 * without switching to any of them. And a model's one-off answer - rewriting a draft, reading the
 * conversations for a search, writing a scenario - run as an ephemeral thread that leaves nothing on disk
 * and nothing in anybody's history.
 *
 * Neither goes into a conversation's process: a question to the model would land in that conversation's
 * transcript as somebody else's turn, and an account question has to be asked of the account that is NOT
 * the one the conversation is on.
 */
internal object CodexOneShot {

    /** A handle on a running job: [cancel] takes the process down and nobody hears from the job again. */
    class Handle internal constructor() {
        @Volatile internal var server: AppServer? = null
        internal val finished = AtomicBoolean(false)
        @Volatile var cancelled = false
            private set

        fun cancel() {
            cancelled = true
            finished.set(true)
            server?.stop()
        }
    }

    /**
     * Raise a process on [accountId] and hand it to [job]; the job calls `done` when it has what it came
     * for. [onError] runs at most once, and never after `done` or a cancel.
     */
    fun run(
        workingDirectory: String?,
        accountId: String = "",
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        label: String = "one-off",
        onNotification: (AppServer, String, JsonObject) -> Unit = { _, _, _ -> },
        onError: (String) -> Unit,
        job: (server: AppServer, done: () -> Unit, fail: (String) -> Unit) -> Unit,
    ): Handle {
        val handle = Handle()

        val fail = { message: String ->
            if (handle.finished.compareAndSet(false, true)) {
                handle.server?.stop()
                onError(message)
            }
        }
        val done = {
            if (handle.finished.compareAndSet(false, true)) handle.server?.stop()
        }

        val executable = CodexExecutable.find()
        if (executable == null) {
            fail("Codex executable not found.")
            return handle
        }

        val environment = CodexAccounts.getInstance().variablesFor(accountId, workingDirectory)
        if (environment == null) {
            fail("That account is unavailable.")
            return handle
        }

        lateinit var server: AppServer
        server = AppServer(
            label = label,
            executable = executable,
            workingDirectory = workingDirectory ?: System.getProperty("user.home"),
            environment = CodexLaunch.environment(environment),
            onNotification = { method, params -> if (!handle.finished.get()) onNotification(server, method, params) },
            onServerRequest = { id, method, _ ->
                // A one-off job asks nobody anything: it runs read-only with no approvals. Whatever comes is
                // refused at once so the job cannot stand waiting on a person who is not there.
                server.respondError(id, AppServer.METHOD_NOT_FOUND, "Not handled in a one-off run: $method")
            },
            onDiagnostic = { DiagnosticsLog.note(DiagnosticsLog.STDERR, it) },
            onExit = { code, requested ->
                if (!requested) fail("Codex exited before answering (code $code).")
            },
        )
        handle.server = server

        if (!server.start(CodexLaunch.CLIENT_NAME, CodexLaunch.CLIENT_TITLE, ProjectCatalog.pluginVersion ?: "dev")) {
            fail("Codex would not start.")
            return handle
        }

        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            { fail("Codex took too long to answer.") },
            timeoutMs,
            TimeUnit.MILLISECONDS,
        )

        job(server, done, fail)
        return handle
    }

    /**
     * One control question in [accountId]'s environment - the old Claude control names, so the callers
     * (the usage rings, the model menu) did not have to learn new ones: `get_usage` and `list_models`.
     */
    fun control(
        workingDirectory: String?,
        subtype: String,
        accountId: String = "",
        onResult: (JsonObject) -> Unit,
        onError: (String) -> Unit,
    ) {
        run(workingDirectory, accountId, CONTROL_TIMEOUT_MS, label = "ping", onError = onError) { server, done, fail ->
            when (subtype) {
                "get_usage" -> server.request(
                    "account/rateLimits/read",
                    onResult = { result ->
                        done()
                        onResult(CodexShapes.usageAnswer(result as? JsonObject))
                    },
                    onError = { fail(it.message) },
                )

                "list_models" -> server.request(
                    "model/list",
                    buildJsonObject { put("includeHidden", false) },
                    onResult = { result ->
                        done()
                        onResult(CodexShapes.models(result, CodexSettings.effective(workingDirectory, "model"), CodexSettings.effective(workingDirectory, "model_reasoning_effort")))
                    },
                    onError = { fail(it.message) },
                )

                "get_account" -> server.request(
                    "account/read",
                    buildJsonObject { put("refreshToken", false) },
                    onResult = { result ->
                        done()
                        onResult(result as? JsonObject ?: JsonObject(emptyMap()))
                    },
                    onError = { fail(it.message) },
                )

                else -> fail("Unknown question: $subtype")
            }
        }
    }

    /** What a one-off model run is asked with - see [ask]. */
    data class Ask(
        val prompt: String,
        /** Replaces Codex's own agent instructions - a rewrite or a title needs none of them. */
        val instructions: String,
        val model: String = "",
        val effort: String = "low",
        /** Where the model may look; it can read there and write nowhere. */
        val workingDirectory: String? = null,
        /** A JSON schema the final answer must follow, when the answer is data rather than prose. */
        val outputSchema: JsonObject? = null,
        val accountId: String = "",
        val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        /** Whether Codex's project documents (AGENTS.md) are read into the run. Off for a pure rewrite. */
        val projectDocs: Boolean = false,
    )

    /** What an ask has heard so far - read from the reader thread, written from the request callbacks. */
    private class AskState {
        @Volatile var thread = ""
        @Volatile var answer = ""
        @Volatile var finish: ((JsonObject) -> Unit)? = null
    }

    /** One step the model took while answering - what a search screen shows so it does not look frozen. */
    data class Step(val kind: String, val detail: String)

    /**
     * A model's one-off answer: an ephemeral thread, one turn, read-only, no approvals asked of anybody.
     * [onResult] gets the final message's text (the JSON, when there was a schema).
     */
    fun ask(
        ask: Ask,
        onStep: (Step) -> Unit = {},
        onError: (String) -> Unit,
        onResult: (String) -> Unit,
    ): Handle {
        val state = AskState()

        return run(
            ask.workingDirectory,
            ask.accountId,
            ask.timeoutMs,
            label = "ask",
            onError = onError,
            onNotification = { _, method, params ->
                if (state.thread.isEmpty() || AppServer.text(params["threadId"]) != state.thread) return@run
                when (method) {
                    "item/started" -> (params["item"] as? JsonObject)?.let { item -> stepOf(item)?.let(onStep) }
                    "item/completed" -> (params["item"] as? JsonObject)?.let { item ->
                        if (AppServer.text(item["type"]) == "agentMessage") state.answer = AppServer.text(item["text"])
                    }
                    "turn/completed" -> (params["turn"] as? JsonObject)?.let { turn -> state.finish?.invoke(turn) }
                }
            },
        ) { server, done, fail ->
            state.finish = { turn ->
                if (AppServer.text(turn["status"]) == "failed") {
                    fail(AppServer.text((turn["error"] as? JsonObject)?.get("message")).ifEmpty { "The run failed." })
                } else {
                    done()
                    onResult(state.answer)
                }
            }

            server.request(
                "thread/start",
                buildJsonObject {
                    ask.workingDirectory?.let { put("cwd", it) }
                    if (ask.model.isNotEmpty()) put("model", ask.model)
                    put("ephemeral", true)
                    put("approvalPolicy", "never")
                    put("sandbox", PermissionModes.SANDBOX_READ_ONLY)
                    put("baseInstructions", ask.instructions)
                    if (!ask.projectDocs) putJsonObject("config") { put("project_doc_max_bytes", 0) }
                },
                onResult = { result ->
                    val thread = ((result as? JsonObject)?.get("thread") as? JsonObject)?.let { AppServer.text(it["id"]) }.orEmpty()
                    if (thread.isEmpty()) return@request fail("Codex did not open a thread.")
                    state.thread = thread

                    server.request(
                        "turn/start",
                        buildJsonObject {
                            put("threadId", thread)
                            putJsonArray("input") {
                                addJsonObject {
                                    put("type", "text")
                                    put("text", ask.prompt)
                                    putJsonArray("text_elements") {}
                                }
                            }
                            EffortLevels.wire(ask.effort)?.let { put("effort", it) }
                            put("summary", "none")
                            ask.outputSchema?.let { put("outputSchema", it) }
                        },
                        onError = { fail(it.message) },
                    )
                },
                onError = { fail(it.message) },
            )
        }
    }

    private fun stepOf(item: JsonObject): Step? = when (AppServer.text(item["type"])) {
        "commandExecution" -> {
            val actions = (item["commandActions"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            val only = actions.singleOrNull()
            when (AppServer.text(only?.get("type"))) {
                "read" -> Step("read", AppServer.text(only?.get("name")).ifEmpty { AppServer.text(only?.get("path")) })
                "search" -> Step("search", AppServer.text(only?.get("query")))
                "listFiles" -> Step("list", AppServer.text(only?.get("path")))
                else -> Step("run", CodexDialect.unwrapShell(AppServer.text(item["command"])))
            }
        }
        else -> null
    }

    /** The text of a JSON answer, from the first brace to the last - models do add a sentence around it. */
    fun jsonOf(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else null
    }

    @Suppress("unused")
    private fun primitive(value: JsonElement?): String? = (value as? JsonPrimitive)?.content

    private const val DEFAULT_TIMEOUT_MS = 5 * 60 * 1000L
    private const val CONTROL_TIMEOUT_MS = 30_000L
}
