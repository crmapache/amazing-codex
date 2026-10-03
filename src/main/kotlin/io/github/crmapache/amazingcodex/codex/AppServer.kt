package io.github.crmapache.amazingcodex.codex

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Key
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.crmapache.amazingcodex.feedback.DiagnosticsLog
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * One `codex app-server` process and the JSON-RPC conversation with it.
 *
 * This is the whole of the plugin's contact with Codex: the same protocol the official editor extensions
 * speak, one JSON object per line over stdin and stdout. Three kinds of message cross it - requests we
 * send and wait on, notifications the server sends about what is happening, and requests the SERVER sends
 * us (approvals, questions for the person) that stand until we answer them.
 *
 * Kept apart from the conversation on purpose. A conversation (see CodexSession) decides what to ask and
 * what the answers mean; this decides nothing and knows only the wire: which answer belongs to which
 * question, that nothing may be sent before the handshake is answered, and that a question nobody answers
 * must not hang the caller forever.
 *
 * The server holds no state we could lose by restarting it that is not also on disk: threads live in
 * `~/.codex/sessions`, and a new process resumes one by its id. That is what lets a conversation be put to
 * sleep, moved to another account or restarted for an MCP change without losing a word of it.
 */
internal class AppServer(
    /** What the logs call this process - a tab, a one-off run, a scenario card. */
    private val label: String,
    private val executable: File,
    private val workingDirectory: String?,
    private val environment: Map<String, String>,
    /** Extra arguments after `app-server` - `-c key=value` overrides, never anything with a line break. */
    private val arguments: List<String> = emptyList(),
    private val onNotification: (method: String, params: JsonObject) -> Unit,
    /** The server asks US something; the answer goes back through [respond] with the same [id]. */
    private val onServerRequest: (id: JsonElement, method: String, params: JsonObject) -> Unit,
    /** Anything the process says past the protocol: stderr, and stdout lines that are not JSON. */
    private val onDiagnostic: (String) -> Unit,
    /** The process is gone. [requested] tells our own stop from a crash. */
    private val onExit: (exitCode: Int, requested: Boolean) -> Unit,
) {

    /** An error answer from the server, or one we make up ourselves (a timeout, a dead process). */
    data class RpcError(val code: Int, val message: String, val data: JsonElement? = null) {
        override fun toString(): String = message
    }

    private class Waiting(
        val method: String,
        val onResult: (JsonElement) -> Unit,
        val onError: (RpcError) -> Unit,
    )

    @Volatile
    private var handler: OSProcessHandler? = null

    @Volatile
    private var stopRequested = false

    /**
     * The handshake has been answered. Until then every request is held back: the server rejects anything
     * that arrives before `initialize`, and it is simpler to never send it than to recover from the refusal.
     */
    @Volatile
    private var initialized = false

    /** Requests written before the handshake finished, in the order they were asked. */
    private val held = ArrayList<() -> Unit>()

    private val waiting = ConcurrentHashMap<Long, Waiting>()

    private val nextId = AtomicLong(1)

    private val writeLock = Any()

    private val lines = StreamLines(onLine = ::consume)

    /** What the server said about itself in the handshake: its home directory, its platform. */
    @Volatile
    var serverInfo: JsonObject = JsonObject(emptyMap())
        private set

    val isAlive: Boolean get() = handler?.isProcessTerminated == false

    /**
     * Raise the process and say hello. False when the process would not start at all - the caller has
     * already been told why through [onDiagnostic].
     *
     * The handshake itself is asynchronous: requests made from now on queue behind it (see [held]), and a
     * handshake that fails takes every one of them down with the same error rather than leaving them waiting.
     */
    fun start(clientName: String, clientTitle: String, clientVersion: String): Boolean {
        if (handler != null) return true
        stopRequested = false
        initialized = false

        val commandLine = GeneralCommandLine(executable.absolutePath)
            .withParameters(listOf("app-server") + arguments)
            .withWorkingDirectory(workingDirectory?.let { Path.of(it) })
            .withEnvironment(environment)
            .withCharset(Charsets.UTF_8)

        val process = runCatching { OSProcessHandler(commandLine) }
            .onFailure {
                thisLogger().warn("Failed to start codex app-server for $label", it)
                DiagnosticsLog.note(DiagnosticsLog.AGENT, "codex app-server would not start: ${it.message}")
                onDiagnostic("Failed to start codex: ${it.message}")
            }
            .getOrNull() ?: return false

        process.addProcessListener(
            object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    when (outputType) {
                        ProcessOutputTypes.STDOUT -> lines.append(event.text)
                        ProcessOutputTypes.STDERR -> diagnostic(event.text)
                    }
                }

                override fun processTerminated(event: ProcessEvent) {
                    val requested = stopRequested
                    if (handler === process) handler = null
                    failEverything(RpcError(PROCESS_GONE, "codex exited (code ${event.exitCode})"))
                    onExit(event.exitCode, requested)
                }
            },
        )

        process.startNotify()
        handler = process

        request(
            "initialize",
            buildJsonObject {
                putJsonObject("clientInfo") {
                    put("name", clientName)
                    put("title", clientTitle)
                    put("version", clientVersion)
                }
                // Plan mode (collaborationMode) and the question tool are experimental on the protocol's
                // side; without this capability the server quietly ignores the first and never sends the
                // second.
                putJsonObject("capabilities") { put("experimentalApi", true) }
            },
            beforeHandshake = true,
            timeoutSeconds = HANDSHAKE_TIMEOUT_SECONDS,
            onResult = { result ->
                serverInfo = result as? JsonObject ?: JsonObject(emptyMap())
                write(buildJsonObject { put("method", "initialized") }.toString())
                val queued = synchronized(held) {
                    initialized = true
                    held.toList().also { held.clear() }
                }
                queued.forEach { it() }
            },
            onError = { error ->
                DiagnosticsLog.note(DiagnosticsLog.AGENT, "codex app-server refused the handshake: ${error.message}")
                onDiagnostic("Codex refused to start a session: ${error.message}")
                val queued = synchronized(held) { held.toList().also { held.clear() } }
                // The held requests carry their own error handlers inside; running them now with the
                // server marked broken fails each one at once rather than leaving it waiting.
                initialized = true
                queued.forEach { it() }
                stop()
            },
        )

        return true
    }

    /**
     * Ask the server something. Exactly one of the two callbacks runs, on a background thread: the answer,
     * the server's refusal, or our own timeout when neither comes - a question left unanswered forever is a
     * piece of the panel frozen forever.
     */
    fun request(
        method: String,
        params: JsonElement? = null,
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
        onResult: (JsonElement) -> Unit = {},
        onError: (RpcError) -> Unit = {},
    ) = request(method, params, beforeHandshake = false, timeoutSeconds, onResult, onError)

    private fun request(
        method: String,
        params: JsonElement?,
        beforeHandshake: Boolean,
        timeoutSeconds: Long,
        onResult: (JsonElement) -> Unit,
        onError: (RpcError) -> Unit,
    ) {
        val send = send@{
            if (handler == null) {
                onError(RpcError(PROCESS_GONE, "codex is not running"))
                return@send
            }

            val id = nextId.getAndIncrement()
            waiting[id] = Waiting(method, onResult, onError)

            if (timeoutSeconds > 0) {
                AppExecutorUtil.getAppScheduledExecutorService().schedule(
                    { waiting.remove(id)?.onError?.invoke(RpcError(TIMED_OUT, "$method timed out")) },
                    timeoutSeconds,
                    TimeUnit.SECONDS,
                )
            }

            val message = buildJsonObject {
                put("method", method)
                put("id", id)
                if (params != null && params !is JsonNull) put("params", params)
            }.toString()

            if (!write(message)) {
                waiting.remove(id)?.onError?.invoke(RpcError(PROCESS_GONE, "could not write to codex"))
            }
        }

        if (beforeHandshake) {
            send()
            return
        }

        val now = synchronized(held) {
            if (initialized) true else {
                held.add(send)
                false
            }
        }
        if (now) send()
    }

    /** The answer to a request the server sent us - see [onServerRequest]. */
    fun respond(id: JsonElement, result: JsonElement) {
        write(
            buildJsonObject {
                put("id", id)
                put("result", result)
            }.toString(),
        )
    }

    /** Refusing a server request outright - one we do not understand, or cannot serve. */
    fun respondError(id: JsonElement, code: Int, message: String) {
        write(
            buildJsonObject {
                put("id", id)
                putJsonObject("error") {
                    put("code", code)
                    put("message", message)
                }
            }.toString(),
        )
    }

    /**
     * Take the process down.
     *
     * Politely first: the app-server treats the end of its input as the end of the session and closes
     * its MCP servers and background terminals on the way out. Only a process that has not gone within a
     * couple of seconds is killed - a killed one leaves its children behind.
     */
    fun stop() {
        val process = handler ?: return
        stopRequested = true
        handler = null
        lines.reset()
        failEverything(RpcError(PROCESS_GONE, "codex was stopped"))

        runCatching { process.processInput.close() }
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            { if (!process.isProcessTerminated) process.destroyProcess() },
            GRACEFUL_EXIT_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    private fun failEverything(error: RpcError) {
        val pending = waiting.values.toList()
        waiting.clear()
        pending.forEach { runCatching { it.onError(error) } }
        synchronized(held) { held.clear() }
    }

    private fun write(payload: String): Boolean {
        val process = handler ?: return false
        return synchronized(writeLock) {
            runCatching {
                process.processInput.write((payload + "\n").toByteArray(Charsets.UTF_8))
                process.processInput.flush()
            }.isSuccess
        }
    }

    private fun diagnostic(text: String) {
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val plain = line.replace(ANSI, "")
            thisLogger().info("codex [$label] said: $plain")
            onDiagnostic(plain)
        }
    }

    /**
     * One line from the server. A slip in a handler must not take the reader down with it: this thread
     * carries every other line of the conversation, and an exception here would lose them.
     */
    private fun consume(line: String) {
        if (!line.startsWith("{")) {
            diagnostic(line)
            return
        }

        val message = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
        if (message == null) {
            diagnostic(line)
            return
        }

        val method = (message["method"] as? JsonPrimitive)?.contentOrNull
        val id = message["id"]

        runCatching {
            when {
                // A server request: a method AND an id. Answered through respond().
                method != null && id != null && id !is JsonNull ->
                    onServerRequest(id, method, message["params"] as? JsonObject ?: JsonObject(emptyMap()))

                method != null ->
                    onNotification(method, message["params"] as? JsonObject ?: JsonObject(emptyMap()))

                id != null -> answer(id, message)
            }
        }.onFailure { thisLogger().warn("codex [$label]: a handler failed on ${method ?: "an answer"}", it) }
    }

    private fun answer(id: JsonElement, message: JsonObject) {
        val key = (id as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: return
        val request = waiting.remove(key) ?: return
        val error = message["error"] as? JsonObject

        if (error != null) {
            request.onError(
                RpcError(
                    code = (error["code"] as? JsonPrimitive)?.intOrNull ?: 0,
                    message = (error["message"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        .ifEmpty { "${request.method} failed" },
                    data = error["data"],
                ),
            )
            return
        }

        request.onResult(message["result"] ?: JsonNull)
    }

    companion object {
        const val PROCESS_GONE = -32099
        const val TIMED_OUT = -32098

        /** JSON-RPC's own code for "I do not know that method" - what an unknown server request gets. */
        const val METHOD_NOT_FOUND = -32601

        private const val DEFAULT_TIMEOUT_SECONDS = 30L

        /**
         * The handshake of a cold start: a first run after an update unpacks and checks itself, and MCP
         * servers are not waited on here, so a minute is generous rather than tight.
         */
        private const val HANDSHAKE_TIMEOUT_SECONDS = 60L

        private const val GRACEFUL_EXIT_SECONDS = 3L

        /** Escape, parameters, the command letter: a terminal's colouring, which the logs have no use for. */
        private val ANSI = Regex("\\u001B\\[[0-9;?]*[ -/]*[@-~]")

        /** A field of the server's answer read as text, whatever JSON shape it came in. */
        fun text(element: JsonElement?): String = when (element) {
            null, is JsonNull -> ""
            is JsonPrimitive -> element.contentOrNull.orEmpty()
            else -> element.toString()
        }

        /** The server's id for a request, as a stable string of ours. */
        fun idText(id: JsonElement): String = (id as? JsonPrimitive)?.contentOrNull ?: id.toString()

        fun intOf(element: JsonElement?): Int? = (element as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() }

        fun longOf(element: JsonElement?): Long? =
            (element as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.toLong()

        fun stringOf(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull

        fun primitive(element: JsonElement?): String? = element?.jsonPrimitive?.contentOrNull
    }
}
