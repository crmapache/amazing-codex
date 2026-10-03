package io.github.crmapache.amazingcodex.codex

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.crmapache.amazingcodex.codex.accounts.CodexAccounts
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonElement

/**
 * One shared `codex app-server` for the questions that are about Codex rather than about a conversation:
 * the list of past threads, a page of one of them, the skills of a project.
 *
 * Asking those of a conversation's process would raise a conversation - with every MCP server the machine
 * has configured - to answer "what did I talk about yesterday". Raising a process per question would pay
 * the start-up every time the history list is opened. So there is one, raised on the first question, kept
 * while questions keep coming, and put down after a quiet while: an idle IDE holds no Codex process at all.
 *
 * It runs on the account chosen now, like everything else. The threads it lists are the same whichever
 * account asks - they live in the shared sessions folder (see accounts/AccountStore).
 */
internal object CodexCatalog {

    private val lock = Any()

    @Volatile
    private var server: AppServer? = null

    @Volatile
    private var lastUsed = 0L

    private var sweep: ScheduledFuture<*>? = null

    /** What a question came back with: the answer, or Codex's own words for why there is none. */
    data class Answer(val result: JsonElement?, val error: String?)

    /**
     * Ask, and wait for the answer on this thread - which must not be the IDE's UI thread. Null when there
     * is no answer: Codex is not installed, the question failed, or it took longer than [timeoutSeconds].
     */
    fun call(method: String, params: JsonElement? = null, timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): JsonElement? =
        ask(method, params, timeoutSeconds).result

    /**
     * The same, keeping Codex's words when it refuses - for a question whose refusal the person has to
     * read, such as a setting that would not be written (see CodexConfigDesk).
     */
    fun ask(method: String, params: JsonElement? = null, timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): Answer {
        check(!ApplicationManager.getApplication().isDispatchThread) { "CodexCatalog.call on the UI thread" }
        val srv = ensure() ?: return Answer(null, "Codex is not available.")
        lastUsed = System.currentTimeMillis()

        val answer = CompletableFuture<Answer>()
        srv.request(
            method,
            params,
            timeoutSeconds,
            onResult = { answer.complete(Answer(it, null)) },
            onError = { error ->
                thisLogger().info("Codex catalog: $method failed: ${error.message}")
                answer.complete(Answer(null, error.message))
            },
        )

        return runCatching { answer.get(timeoutSeconds + 5, TimeUnit.SECONDS) }.getOrElse { Answer(null, "Codex did not answer in time.") }
    }

    /** Take the process down now - the plugin is being unloaded, or the account changed under it. */
    fun shutdown() {
        synchronized(lock) {
            server?.stop()
            server = null
            sweep?.cancel(false)
            sweep = null
        }
    }

    private fun ensure(): AppServer? = synchronized(lock) {
        server?.takeIf { it.isAlive }?.let { return it }

        val executable = CodexExecutable.find() ?: return null
        val environment = CodexAccounts.getInstance().variablesFor(CodexAccounts.getInstance().currentId, null) ?: return null

        lateinit var created: AppServer
        created = AppServer(
            label = "catalog",
            executable = executable,
            workingDirectory = System.getProperty("user.home"),
            environment = CodexLaunch.environment(environment),
            onNotification = { _, _ -> },
            onServerRequest = { id, method, _ -> created.respondError(id, AppServer.METHOD_NOT_FOUND, "Not handled: $method") },
            onDiagnostic = { thisLogger().debug("Codex catalog said: $it") },
            onExit = { _, _ -> synchronized(lock) { if (server === created) server = null } },
        )

        if (!created.start(CodexLaunch.CLIENT_NAME, CodexLaunch.CLIENT_TITLE, ProjectCatalog.pluginVersion ?: "dev")) return null
        server = created
        lastUsed = System.currentTimeMillis()

        if (sweep == null) {
            sweep = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
                ::putDownIfQuiet,
                SWEEP_SECONDS,
                SWEEP_SECONDS,
                TimeUnit.SECONDS,
            )
        }
        created
    }

    private fun putDownIfQuiet() {
        synchronized(lock) {
            val srv = server ?: return
            if (System.currentTimeMillis() - lastUsed < IDLE_MS) return
            srv.stop()
            server = null
        }
    }

    private const val DEFAULT_TIMEOUT_SECONDS = 30L
    private const val SWEEP_SECONDS = 30L

    /** Two minutes of no questions and the process goes: the history list is read in bursts, not all day. */
    private const val IDLE_MS = 2 * 60 * 1000L
}
