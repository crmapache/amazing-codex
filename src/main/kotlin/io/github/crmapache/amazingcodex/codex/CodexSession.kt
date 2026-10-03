package io.github.crmapache.amazingcodex.codex

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.crmapache.amazingcodex.codex.accounts.CodexAccounts
import io.github.crmapache.amazingcodex.feedback.DiagnosticsLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** An image pasted into the input field from the clipboard: bytes, not a path on disk. */
internal data class ImageAttachment(val mediaType: String, val data: String)

/**
 * One conversation with Codex = one `codex app-server` process holding one thread.
 *
 * The process comes up lazily, on the first message: an open panel on its own starts nothing. A thread is
 * Codex's conversation - it lives on disk under `~/.codex/sessions` - and the process is only what holds
 * it open, so it can be put to sleep, moved to another account or restarted for an MCP change, and a new
 * one resumes the same thread with everything it remembers.
 *
 * Everything the rest of the plugin reads about the conversation leaves here in the panel's event
 * language (see CodexDialect): the hub, the journal, the phone, the statistics and the scenario engine
 * were built on it and keep reading it unchanged. What arrives is Codex's - turns, items, approvals, a
 * question for the person - and each is retold on its way through (see CodexStream).
 */
internal class CodexSession(
    private val workingDirectory: String?,
    /**
     * The conversation this one branches off. A branch gets the parent's whole history and an id of its
     * own, so continuing inside it leaves the parent untouched. Readable from outside: a fork nobody has
     * spoken in yet owes its whole conversation to this parent, and a tab replaced under it has to be
     * raised as a fork again (see CodexSessions.moveTo).
     */
    val forkFrom: String? = null,
    /** A past conversation being continued: it comes up with its own history. */
    resumeFrom: String? = null,
    /**
     * The model and the effort are the conversation's, chosen once and carried to every turn. `var`
     * rather than `val`, because they are chosen before the first message too, when there is no process
     * to tell.
     */
    model: String = "",
    effort: String = "",
    permissionMode: String = "",
    /**
     * Which account this conversation runs on. Empty is Codex's ordinary sign-in.
     *
     * A `val`, and that is load-bearing: Codex reads its credentials when the process comes up, so this
     * cannot change on a running conversation. A restart - which happens on its own, after a crash or an
     * MCP change - must raise the new process on the account this conversation was on, not on whichever
     * is current by then.
     */
    val accountId: String = "",
    private val onEvent: (String) -> Unit,
    private val onError: (String) -> Unit,
    /** Something the process said past the protocol - its stderr, a warning. */
    private val onDiagnostic: (String) -> Unit = {},
    private val onFinished: () -> Unit,
    /** The agent asks the person something - a permission, a question, a plan. The turn waits. */
    private val onToolPermission: (PermissionChannel.ToolPermission) -> Unit = {},
    /** A question Codex took back itself (the turn was interrupted, say) - its card has to go. */
    private val onPermissionWithdrawn: (String) -> Unit = {},
    /** A model picked the conversation's name - see [requestTitle]. */
    private val onTitle: (String) -> Unit = {},
    /** Whether this conversation still needs a name of its own - the tab keeps one across processes. */
    private val titleWanted: () -> Boolean = { true },
    /** The process died on its own, not because we stopped it. */
    private val onCrashed: (Int) -> Unit = {},
    /** The person's turn has ended - by its end, by a crash, or by never getting started. */
    private val onTurnEnded: () -> Unit = {},
    /** A turn started without a message of ours - an approved plan's implementation, a queued steer. */
    private val onTurnStarted: () -> Unit = {},
    /** What the agent is told about where it runs - see CodexLaunch.PANEL_BRIEFING. */
    private val briefing: String = CodexLaunch.PANEL_BRIEFING,
    /** Whether this conversation should be given a name at all; the scenario engine's sessions are not. */
    private val nameWanted: Boolean = true,
    /** How full the context window is now, straight from Codex's own count - see CodexShapes.contextOf. */
    private val onContext: (used: Int, max: Int) -> Unit = { _, _ -> },
    /** Codex told us the account's limits along the way - the rings move without asking. */
    private val onRateLimits: (JsonObject) -> Unit = {},
    /**
     * An MCP server of this process finished starting - came up, or failed. The MCP screen asks for the
     * list when it opens, and a server still starting then (Codex's own `codex_apps` takes seconds) would
     * stand at "connecting…" for as long as the screen stays open without this.
     */
    private val onMcpSettled: () -> Unit = {},
) : Disposable {

    @Volatile
    private var server: AppServer? = null

    /** When the process behind this conversation came up - the idle sweep and moves read it. */
    @Volatile
    var startedAt: Long = 0
        private set

    /** The person's turn is running: something was sent and the turn has not completed. */
    @Volatile
    private var busy = false

    /** The thread Codex is running now - known from turn/start's answer or turn/started, whichever is first. */
    @Volatile
    private var activeTurn: String? = null

    /** Stop was pressed before the turn had an id to interrupt - it is interrupted the moment it has one. */
    @Volatile
    private var interruptWanted = false

    var model: String = model
        private set

    var effort: String = effort
        private set

    var permissionMode: String? = permissionMode.ifEmpty { null }?.let(PermissionModes::normalize)
        private set

    /** The conversation's identifier - Codex's thread id. Without it there is no resuming it later. */
    @Volatile
    var conversationId: String? = resumeFrom
        private set

    /** The model the thread actually runs on, as Codex said when it opened it - the menu's empty "default". */
    @Volatile
    private var threadModel: String = ""

    /** The thread is open and turns can be started. Until then everything waits in [whenOpen]. */
    @Volatile
    private var open = false
    private val waitingForThread = ArrayList<() -> Unit>()

    /** Whether the last turn was sent in plan mode - leaving it has to be said, it is sticky. */
    private var planSent = false

    /** An explicit effort was sent at some point - choosing "auto" afterwards has to clear it. */
    private var effortSent = false

    /** Messages written into a turn that would not take them in (see [steer]): sent when it ends. */
    private val afterTurn = ArrayList<Pair<String, List<ImageAttachment>>>()

    private val stream = CodexStream(emit = ::emit, model = ::currentModel, threadId = { conversationId.orEmpty() })

    /** What each server request asked, by our id for it - see [answerPermission]. */
    private val awaitingPermission = ConcurrentHashMap<String, Pending>()

    /** Our id for a server request, by the server's own id - the server withdraws by its own. */
    private val requestIds = ConcurrentHashMap<String, String>()

    /** Tool calls of the items in flight, by item id: a patch approval names the item, the card needs the file. */
    private val itemCalls = ConcurrentHashMap<String, List<CodexDialect.ToolCall>>()

    /** What each MCP server's start-up said - the only place its error text lives (see CodexShapes.mcpStatus). */
    private val mcpStartup = ConcurrentHashMap<String, CodexShapes.Startup>()

    /** The last token counts Codex reported, for the context meter asked after the fact. */
    @Volatile
    private var lastTokenUsage: JsonObject? = null

    /** A counter that makes our request ids unique across the processes this conversation goes through. */
    private val processEpoch = AtomicLong(0)

    private var titleAsked = false

    private enum class Kind { COMMAND, FILE, ASK, PLAN, PERMISSIONS, ELICITATION }

    private data class Pending(
        val kind: Kind,
        val serverId: JsonElement?,
        val request: PermissionChannel.ToolPermission,
        /** For a question: the question's text → Codex's id for it. */
        val questionIds: Map<String, String> = emptyMap(),
        /** For a command: the rule Codex offers for "do not ask again", when it offers one. */
        val amendment: JsonElement? = null,
        /** For a permissions request: what was asked for, granted back whole on "allow". */
        val permissions: JsonElement? = null,
    )

    val isRunning: Boolean get() = server?.isAlive == true

    val isBusy: Boolean get() = isRunning && busy

    // --- Sending ---------------------------------------------------------------------------------

    fun sendPrompt(text: String, images: List<ImageAttachment> = emptyList()) {
        if (server == null && start() == null) {
            endTurn()
            return
        }
        busy = true
        whenOpen { deliver(text, images) }
    }

    /**
     * What a message is decides where it goes (see CodexCommands): the commands that are actions become
     * the requests that do them, everything else is a turn - or, while one is running, words steered into it.
     */
    private fun deliver(text: String, images: List<ImageAttachment>) {
        when (val command = CodexCommands.parse(text, CodexHome.of(workingDirectory).promptsDirectory, CodexSkills.of(workingDirectory))) {
            CodexCommands.Command.Compact -> compact()
            CodexCommands.Command.Clear -> clear()
            CodexCommands.Command.Init -> startTurn(CodexLaunch.INIT_PROMPT, emptyList(), emptyList())
            is CodexCommands.Command.Review -> review(command.target)
            is CodexCommands.Command.Say ->
                if (activeTurn != null) steer(command.text, images, command.skills) else startTurn(command.text, images, command.skills)
        }
    }

    private fun startTurn(text: String, images: List<ImageAttachment>, skills: List<CodexCommands.Skill>) {
        val srv = server ?: return endTurn()
        val thread = conversationId ?: return endTurn()
        val policy = PermissionModes.policyOf(permissionMode)

        val params = buildJsonObject {
            put("threadId", thread)
            put("input", inputOf(text, images, skills))
            put("approvalPolicy", policy.approval)
            put("sandboxPolicy", policy.sandboxPolicy())
            modelOnWire()?.let { put("model", it) }
            val wired = EffortLevels.wire(effort)
            when {
                wired != null -> {
                    put("effort", wired)
                    effortSent = true
                }
                effortSent -> put("effort", JsonNull)
            }
            put("summary", "auto")
            // Plan mode is a collaboration mode, and a sticky one: leaving it has to be said as much as
            // entering it, or the next turn would still be planning.
            if (policy.plan || planSent) {
                putJsonObject("collaborationMode") {
                    put("mode", if (policy.plan) "plan" else "default")
                    putJsonObject("settings") {
                        put("model", currentModel())
                        EffortLevels.wire(effort)?.let { put("reasoning_effort", it) } ?: put("reasoning_effort", JsonNull)
                        put("developer_instructions", JsonNull)
                    }
                }
                planSent = policy.plan
            }
        }

        srv.request(
            "turn/start",
            params,
            timeoutSeconds = TURN_START_TIMEOUT_SECONDS,
            onResult = { result ->
                val turn = (result as? JsonObject)?.get("turn") as? JsonObject
                AppServer.text(turn?.get("id")).takeIf { it.isNotEmpty() }?.let { id ->
                    if (activeTurn == null) activeTurn = id
                    if (interruptWanted) interruptNow(null)
                }
            },
            onError = { error ->
                DiagnosticsLog.note(DiagnosticsLog.AGENT, "codex refused to start a turn")
                onError("Codex did not start the turn: ${error.message}")
                endTurn()
            },
        )
        requestTitle(text)
    }

    /**
     * Words written into a running turn go INTO it, the way Codex's terminal steers: the agent reads them
     * at its next step. A turn that cannot be steered (a review, a compaction) keeps them until it ends.
     */
    private fun steer(text: String, images: List<ImageAttachment>, skills: List<CodexCommands.Skill>) {
        val srv = server ?: return
        val thread = conversationId ?: return
        val turn = activeTurn ?: return startTurn(text, images, skills)

        srv.request(
            "turn/steer",
            buildJsonObject {
                put("threadId", thread)
                put("input", inputOf(text, images, skills))
                put("expectedTurnId", turn)
            },
            onError = {
                synchronized(afterTurn) { afterTurn += text to images }
                // The turn may have ended between the send and the refusal: then nobody else will drain.
                if (activeTurn == null) drainAfterTurn()
            },
        )
    }

    private fun inputOf(text: String, images: List<ImageAttachment>, skills: List<CodexCommands.Skill>): JsonArray =
        buildJsonArray {
            addJsonObject {
                put("type", "text")
                put("text", text)
                putJsonArray("text_elements") {}
            }
            for (image in images) {
                addJsonObject {
                    put("type", "image")
                    put("url", "data:${image.mediaType};base64,${image.data}")
                }
            }
            for (skill in skills) {
                addJsonObject {
                    put("type", "skill")
                    put("name", skill.name)
                    put("path", skill.path)
                }
            }
        }

    private fun compact() {
        val srv = server ?: return endTurn()
        val thread = conversationId ?: return endTurn()
        srv.request(
            "thread/compact/start",
            buildJsonObject { put("threadId", thread) },
            onError = { error ->
                onError("Codex could not compact the conversation: ${error.message}")
                endTurn()
            },
        )
    }

    private fun review(target: CodexCommands.ReviewTarget) {
        val srv = server ?: return endTurn()
        val thread = conversationId ?: return endTurn()
        srv.request(
            "review/start",
            buildJsonObject {
                put("threadId", thread)
                putJsonObject("target") {
                    when (target) {
                        CodexCommands.ReviewTarget.Uncommitted -> put("type", "uncommittedChanges")
                        is CodexCommands.ReviewTarget.BaseBranch -> {
                            put("type", "baseBranch")
                            put("branch", target.branch)
                        }
                        is CodexCommands.ReviewTarget.Commit -> {
                            put("type", "commit")
                            put("sha", target.sha)
                            put("title", JsonNull)
                        }
                        is CodexCommands.ReviewTarget.Custom -> {
                            put("type", "custom")
                            put("instructions", target.instructions)
                        }
                    }
                }
                put("delivery", "inline")
            },
            onError = { error ->
                onError("Codex could not start the review: ${error.message}")
                endTurn()
            },
        )
    }

    /**
     * `/clear` - a new thread in the same process. The previous conversation stays on disk and in the
     * history; this tab simply starts over, and says so the way the panel expects (`conversation_reset`).
     */
    private fun clear() {
        val srv = server ?: return endTurn()
        srv.request(
            "thread/start",
            threadParams(),
            onResult = { result ->
                val thread = (result as? JsonObject)?.get("thread") as? JsonObject
                val id = AppServer.text(thread?.get("id"))
                if (id.isNotEmpty()) {
                    conversationId = id
                    lastTokenUsage = null
                    titleAsked = false
                    awaitingPermission.clear()
                    emit(CodexDialect.conversationReset(id))
                }
                endTurn()
            },
            onError = { error ->
                onError("Codex could not start a new conversation: ${error.message}")
                endTurn()
            },
        )
    }

    /**
     * Ask a small model for this conversation's name, and write it into the thread so the history shows it.
     *
     * Codex names nothing on its own through this protocol, and a tab called by its first sixty characters
     * reads like the stand-in it is. So the name is asked for once, in an ephemeral thread of the same
     * process - it costs one short turn of the fastest effort and leaves nothing on disk.
     */
    private fun requestTitle(text: String) {
        if (!nameWanted || titleAsked || !titleWanted()) return
        val description = SessionTitle.describe(text) ?: return
        titleAsked = true

        val srv = server ?: return
        val thread = conversationId ?: return
        CodexTitles.ask(srv, workingDirectory, currentModel(), description) { title ->
            if (title.isBlank() || conversationId != thread) return@ask
            onTitle(title)
            srv.request(
                "thread/name/set",
                buildJsonObject {
                    put("threadId", thread)
                    put("name", title)
                },
            )
        }
    }

    // --- Settings ---------------------------------------------------------------------------------

    data class ModelChange(val applied: Boolean, val model: String, val error: String = "")

    /**
     * The model for the next turn. Codex takes it with the turn itself (`turn/start` carries it and keeps
     * it), so there is nothing to send now and nothing that can refuse now: a model this account cannot
     * run fails the turn it is used in, and that error reaches the feed like any other.
     */
    fun setModel(model: String, onApplied: (ModelChange) -> Unit = {}) {
        this.model = model
        onApplied(ModelChange(applied = true, model = model))
    }

    fun setEffort(effort: String) {
        this.effort = EffortLevels.normalize(effort).ifEmpty { effort }
    }

    /** Only before the process is up: a conversation opened from the history carries on at its own model. */
    fun adoptModel(model: String) {
        if (server != null) return
        this.model = model
    }

    data class ModeChange(val applied: Boolean, val mode: String, val error: String = "")

    /**
     * The permission mode for the next turn. Like the model it travels with the turn (approval policy and
     * sandbox are turn parameters in Codex), so the change is immediate for everything that starts from
     * now on and cannot reach into a turn already running.
     */
    fun setPermissionMode(requested: String, onApplied: (ModeChange) -> Unit) {
        val mode = PermissionModes.normalize(requested)
        permissionMode = mode
        onApplied(ModeChange(applied = true, mode = mode))
    }

    // --- Questions about the session ---------------------------------------------------------------

    fun requestModels(onResult: (JsonObject) -> Unit, onFailure: (String) -> Unit = {}) {
        val srv = server ?: return onFailure("no live session")
        srv.request(
            "model/list",
            buildJsonObject { put("includeHidden", false) },
            onResult = { onResult(CodexShapes.models(it)) },
            onError = { onFailure(it.message) },
        )
    }

    /**
     * This conversation's MCP servers: the live list from the process, the configuration from `codex mcp
     * list`, and what each server's start-up said - see CodexShapes.mcpStatus.
     */
    fun requestMcpStatus(onResult: (JsonObject) -> Unit, onFailure: (String) -> Unit = {}) {
        val srv = server ?: return onFailure("no live session")
        srv.request(
            "mcpServerStatus/list",
            buildJsonObject {
                conversationId?.let { put("threadId", it) }
                put("detail", "toolsAndAuthOnly")
            },
            onResult = { live ->
                AppExecutorUtil.getAppExecutorService().submit {
                    val configured = CodexMcp.listConfigured(workingDirectory, accountId)
                    onResult(CodexShapes.mcpStatus(live, configured, HashMap(mcpStartup)))
                }
            },
            onError = { onFailure(it.message) },
        )
    }

    /**
     * Signing in to an MCP server. Codex answers with the address to open and waits for the browser on a
     * loopback port of its own - which is why the request goes to this conversation's live process: the
     * listener dies with it.
     */
    fun authenticateMcp(server: String, onResult: (JsonObject) -> Unit, onFailure: (String) -> Unit = {}) {
        val srv = this.server ?: return onFailure("no live session")
        srv.request(
            "mcpServer/oauth/login",
            buildJsonObject {
                put("name", server)
                conversationId?.let { put("threadId", it) }
            },
            timeoutSeconds = MCP_LOGIN_TIMEOUT_SECONDS,
            onResult = { result ->
                onResult(
                    buildJsonObject {
                        put("authUrl", AppServer.text((result as? JsonObject)?.get("authorizationUrl")))
                        put("callbackExpected", true)
                    },
                )
            },
            onError = { onFailure(it.message) },
        )
    }

    /** Codex reloads its MCP servers all at once; there is no reconnecting just one. */
    fun reconnectMcp(server: String, onResult: (JsonObject) -> Unit, onFailure: (String) -> Unit = {}) {
        val srv = this.server ?: return onFailure("no live session")
        mcpStartup.remove(server)
        srv.request(
            "config/mcpServer/reload",
            onResult = { onResult(JsonObject(emptyMap())) },
            onError = { onFailure(it.message) },
        )
    }

    /**
     * Stopping one background task without the turn. Codex has no such request - its background terminals
     * end with the turn that owns them or with an interrupt - so the answer is an honest no.
     */
    fun stopTask(taskId: String, onFailure: (String) -> Unit = {}) {
        onFailure("Codex cannot stop a single task; stop the turn instead.")
    }

    /** How full the context window is: Codex's own last count and the model's window. */
    fun requestContextUsage(onResult: (JsonObject) -> Unit, onFailure: (String) -> Unit = {}) {
        val (used, max) = CodexShapes.contextOf(lastTokenUsage) ?: return onFailure("no usage yet")
        onResult(
            buildJsonObject {
                put("totalTokens", used)
                put("maxTokens", max)
            },
        )
    }

    /** The account's limits, from the process this conversation already has up. */
    fun requestUsage(onUsage: (JsonObject) -> Unit, onFailure: (String) -> Unit = {}) {
        val srv = server ?: return onFailure("no live session")
        srv.request(
            "account/rateLimits/read",
            onResult = { result ->
                val snapshot = (result as? JsonObject)?.get("rateLimits") as? JsonObject
                onUsage(CodexShapes.usage(snapshot, CodexShapes.contextOf(lastTokenUsage)?.second))
            },
            onError = { onFailure(it.message) },
        )
    }

    // --- The turn ---------------------------------------------------------------------------------

    /**
     * Interrupting the turn: the conversation stays. Codex confirms the interrupt and then completes the
     * turn as interrupted, which is what clears the panel. A process that does not even confirm is worth
     * offering to kill - [onTimeout].
     */
    fun interrupt(onTimeout: () -> Unit = {}) {
        synchronized(afterTurn) { afterTurn.clear() }
        if (server == null) return

        val turn = activeTurn ?: stream.activeTurn
        if (turn == null) {
            if (busy) interruptWanted = true
            return
        }
        interruptNow(onTimeout)
    }

    private fun interruptNow(onTimeout: (() -> Unit)?) {
        interruptWanted = false
        val srv = server ?: return
        val thread = conversationId ?: return
        val turn = activeTurn ?: stream.activeTurn ?: return

        srv.request(
            "turn/interrupt",
            buildJsonObject {
                put("threadId", thread)
                put("turnId", turn)
            },
            timeoutSeconds = INTERRUPT_TIMEOUT_SECONDS,
            onError = { error ->
                if (error.code == AppServer.TIMED_OUT || error.code == AppServer.PROCESS_GONE) onTimeout?.invoke()
            },
        )
    }

    fun wake(): Boolean = server != null || start() != null

    fun restart(): Boolean {
        if (server == null) return false
        stop()
        return start() != null
    }

    fun stop() {
        val srv = server ?: return
        server = null
        busy = false
        open = false
        activeTurn = null
        synchronized(waitingForThread) { waitingForThread.clear() }
        synchronized(afterTurn) { afterTurn.clear() }
        awaitingPermission.clear()
        requestIds.clear()
        itemCalls.clear()
        srv.stop()
    }

    override fun dispose() = stop()

    private fun endTurn() {
        busy = false
        activeTurn = null
        onTurnEnded()
    }

    private fun drainAfterTurn() {
        val next = synchronized(afterTurn) {
            if (afterTurn.isEmpty()) null else afterTurn.removeAt(0)
        } ?: return
        busy = true
        onTurnStarted()
        whenOpen { deliver(next.first, next.second) }
    }

    // --- Permissions --------------------------------------------------------------------------------

    /**
     * The person's answer to what Codex asked. [extraInput] carries a question's answers (`answers`, keyed
     * by the question's text, the panel's shape); [remember] is "always allow".
     */
    fun answerPermission(
        requestId: String,
        allow: Boolean,
        message: String = "",
        extraInput: JsonObject? = null,
        remember: Boolean = false,
    ) {
        val pending = awaitingPermission.remove(requestId) ?: return
        val srv = server
        pending.serverId?.let { requestIds.remove(AppServer.idText(it)) }

        when (pending.kind) {
            Kind.COMMAND -> srv?.respond(
                pending.serverId!!,
                buildJsonObject {
                    when {
                        !allow -> put("decision", "decline")
                        remember && pending.amendment != null -> putJsonObject("decision") {
                            putJsonObject("acceptWithExecpolicyAmendment") {
                                put("execpolicy_amendment", pending.amendment)
                            }
                        }
                        remember -> put("decision", "acceptForSession")
                        else -> put("decision", "accept")
                    }
                },
            )

            Kind.FILE -> srv?.respond(
                pending.serverId!!,
                buildJsonObject {
                    put("decision", if (!allow) "decline" else if (remember) "acceptForSession" else "accept")
                },
            )

            Kind.ASK -> {
                val answers = extraInput?.get("answers") as? JsonObject
                srv?.respond(
                    pending.serverId!!,
                    buildJsonObject {
                        putJsonObject("answers") {
                            if (allow && answers != null) {
                                for ((question, value) in answers) {
                                    val id = pending.questionIds[question] ?: pending.questionIds.values.firstOrNull() ?: continue
                                    putJsonObject(id) {
                                        putJsonArray("answers") { add(JsonPrimitive(AppServer.text(value))) }
                                    }
                                }
                            }
                        }
                    },
                )
                closeCard(pending.request.toolUseId, if (allow) summaryOf(answers) else message.ifEmpty { "Closed without an answer." }, isError = !allow)
            }

            Kind.PLAN -> {
                closeCard(pending.request.toolUseId, if (allow) "Plan approved." else message, isError = !allow)
                if (allow) implementPlan() else followUp(message)
            }

            Kind.PERMISSIONS -> srv?.respond(
                pending.serverId!!,
                buildJsonObject {
                    put("permissions", if (allow) pending.permissions ?: JsonObject(emptyMap()) else JsonObject(emptyMap()))
                    put("scope", if (remember) "session" else "turn")
                },
            )

            Kind.ELICITATION -> srv?.respond(
                pending.serverId!!,
                buildJsonObject {
                    put("action", if (allow) "accept" else "decline")
                    put("content", JsonNull)
                    put("_meta", JsonNull)
                },
            )
        }
    }

    fun isAwaitingPermission(requestId: String): Boolean = awaitingPermission.containsKey(requestId)

    /**
     * The approved plan is carried out in a turn of its own - Codex's plan mode ends its turn with the
     * plan, and its terminal answers an approval with exactly this.
     *
     * Deferred a moment on purpose: the approval is followed at once by the mode change that frees the
     * conversation from planning (see SessionPermissions.decidePlan), and the implementing turn must go out
     * in the mode that follows, not in plan mode.
     */
    private fun implementPlan() {
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            {
                if (PermissionModes.normalize(permissionMode.orEmpty()) == PermissionModes.PLAN) {
                    permissionMode = PermissionModes.ACCEPT_EDITS
                }
                if (server == null) return@schedule
                busy = true
                onTurnStarted()
                whenOpen { startTurn(CodexLaunch.IMPLEMENT_PLAN, emptyList(), emptyList()) }
            },
            PLAN_FOLLOW_UP_DELAY_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    /** "Keep planning" with a remark: the remark is the next planning turn. */
    private fun followUp(message: String) {
        if (message.isBlank() || server == null) return
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            {
                busy = true
                onTurnStarted()
                whenOpen { startTurn(message, emptyList(), emptyList()) }
            },
            PLAN_FOLLOW_UP_DELAY_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun closeCard(toolUseId: String?, content: String, isError: Boolean) {
        val id = toolUseId ?: return
        CodexDialect.toolResults(listOf(CodexDialect.ToolResult(id, content, isError)), uuid = "$id-answer")?.let(::emit)
    }

    private fun summaryOf(answers: JsonObject?): String =
        answers?.entries?.joinToString("\n") { (question, value) -> "$question: ${AppServer.text(value)}" }.orEmpty()

    // --- The process --------------------------------------------------------------------------------

    private fun start(): AppServer? {
        val executable = CodexExecutable.find()
        if (executable == null) {
            DiagnosticsLog.note(DiagnosticsLog.AGENT, "the codex executable was not found")
            onError("Codex was not found on this machine. Install it (npm install -g @openai/codex) or set its path in the panel's settings.")
            return null
        }

        // Which account pays travels in the environment and nowhere else. A refusal stops the launch -
        // there is deliberately no fallback to the ordinary sign-in: a conversation silently billed to
        // somebody else is the one failure this must not have.
        val environment = CodexAccounts.getInstance().variablesFor(accountId, workingDirectory)
        if (environment == null) {
            DiagnosticsLog.note(DiagnosticsLog.ACCOUNTS, "a conversation was not started: its account would not resolve")
            onError("The chosen Codex account is unavailable. Pick another one on the Accounts screen.")
            return null
        }

        val epoch = processEpoch.incrementAndGet()
        lateinit var created: AppServer
        created = AppServer(
            label = "conversation",
            executable = executable,
            workingDirectory = workingDirectory,
            environment = CodexLaunch.environment(environment),
            arguments = CodexLaunch.serverArguments(),
            onNotification = { method, params -> if (server === created) notification(method, params) },
            onServerRequest = { id, method, params -> if (server === created) serverRequest(epoch, id, method, params) },
            onDiagnostic = { line ->
                DiagnosticsLog.note(DiagnosticsLog.STDERR, line)
                onDiagnostic(line)
            },
            onExit = { code, requested -> if (server === created || server == null) exited(created, code, requested) },
        )

        if (!created.start(CodexLaunch.CLIENT_NAME, CodexLaunch.CLIENT_TITLE, ProjectCatalog.pluginVersion ?: "dev")) {
            onError("Failed to start codex.")
            return null
        }

        server = created
        startedAt = System.currentTimeMillis()
        open = false
        openThread(created)
        return created
    }

    private fun exited(process: AppServer, code: Int, requested: Boolean) {
        if (server === process) server = null
        val wasBusy = busy
        busy = false
        open = false
        activeTurn = null
        synchronized(waitingForThread) { waitingForThread.clear() }
        // The questions it was asking died with it: said out loud, so a card answered afterwards goes as
        // an ordinary message rather than into a process that is gone.
        val orphaned = awaitingPermission.keys.toList()
        awaitingPermission.clear()
        requestIds.clear()
        orphaned.forEach(onPermissionWithdrawn)

        if (!requested) {
            DiagnosticsLog.note(DiagnosticsLog.AGENT, "codex exited on its own (code $code)")
            onCrashed(code)
            if (wasBusy) onTurnEnded()
        }
        onFinished()
    }

    /** The thread this process holds: a new one, the one being continued, or a fork of the parent. */
    private fun openThread(srv: AppServer) {
        val (method, params) = when {
            conversationId != null -> "thread/resume" to buildJsonObject {
                put("threadId", conversationId)
                threadSettings(this)
                put("excludeTurns", true)
            }
            forkFrom != null -> "thread/fork" to buildJsonObject {
                put("threadId", forkFrom)
                threadSettings(this)
                put("excludeTurns", true)
            }
            else -> "thread/start" to threadParams()
        }

        srv.request(
            method,
            params,
            timeoutSeconds = THREAD_OPEN_TIMEOUT_SECONDS,
            onResult = { result -> threadOpened(result as? JsonObject) },
            onError = { error ->
                DiagnosticsLog.note(DiagnosticsLog.AGENT, "codex could not open the conversation ($method)")
                onError("Codex could not open the conversation: ${error.message}")
                val waiting = synchronized(waitingForThread) { waitingForThread.toList().also { waitingForThread.clear() } }
                if (waiting.isNotEmpty() || busy) endTurn()
            },
        )
    }

    private fun threadParams(): JsonObject = buildJsonObject {
        threadSettings(this)
        put("serviceName", CodexLaunch.CLIENT_TITLE)
    }

    private fun threadSettings(builder: kotlinx.serialization.json.JsonObjectBuilder) = with(builder) {
        val policy = PermissionModes.policyOf(permissionMode)
        workingDirectory?.let { put("cwd", it) }
        modelOnWire()?.let { put("model", it) }
        put("approvalPolicy", policy.approval)
        put("sandbox", policy.sandboxMode)
        put("developerInstructions", briefing)
    }

    private fun threadOpened(result: JsonObject?) {
        val thread = result?.get("thread") as? JsonObject
        val id = AppServer.text(thread?.get("id"))
        if (id.isEmpty()) {
            onError("Codex opened the conversation without naming it.")
            endTurn()
            return
        }

        conversationId = id
        threadModel = AppServer.text(result?.get("model"))
        planSent = false
        effortSent = false

        emit(
            CodexDialect.systemInit(
                threadId = id,
                model = currentModel(),
                cwd = AppServer.text(result?.get("cwd")).ifEmpty { workingDirectory },
                permissionMode = permissionMode ?: PermissionModes.ACCEPT_EDITS,
                slashCommands = CodexCommands.BUILT_IN + CodexSkills.of(workingDirectory).keys,
            ),
        )

        val waiting = synchronized(waitingForThread) {
            open = true
            waitingForThread.toList().also { waitingForThread.clear() }
        }
        waiting.forEach { it() }

        // A conversation continued from the history knows how full its window is only after its next turn;
        // until then the meter would stand empty over a conversation that may be nearly full. Codex wrote
        // the last count into the thread's file, so it is read from there.
        if (lastTokenUsage == null) {
            AppExecutorUtil.getAppExecutorService().submit {
                val remembered = CodexHistory.lastTokenUsage(CodexHistory.transcriptFile(workingDirectory, id)) ?: return@submit
                if (lastTokenUsage != null || conversationId != id) return@submit
                lastTokenUsage = remembered
                CodexShapes.contextOf(remembered)?.let { (used, max) -> onContext(used, max) }
            }
        }
    }

    private fun whenOpen(action: () -> Unit) {
        val now = synchronized(waitingForThread) {
            if (open) true else {
                waitingForThread += action
                false
            }
        }
        if (now) action()
    }

    // --- What Codex says ----------------------------------------------------------------------------

    private fun notification(method: String, params: JsonObject) {
        val thread = AppServer.text(params["threadId"])
        // Another thread of this process - a title being asked for (see CodexTitles), a subagent Codex
        // spawned. Their items are not this conversation's feed.
        if (thread.isNotEmpty() && thread != conversationId) {
            CodexTitles.notification(method, params)
            return
        }

        when (method) {
            "turn/started" -> {
                val turn = params["turn"] as? JsonObject ?: return
                stream.turnStarted(turn)
                activeTurn = AppServer.text(turn["id"]).ifEmpty { activeTurn }
                if (!busy) {
                    busy = true
                    onTurnStarted()
                }
                if (interruptWanted) interruptNow(null)
            }

            "item/started" -> (params["item"] as? JsonObject)?.let { item ->
                rememberCalls(item)
                stream.itemStarted(item)
            }

            "item/completed" -> (params["item"] as? JsonObject)?.let { item ->
                val plan = stream.itemCompleted(item)
                itemCalls.remove(AppServer.text(item["id"]))
                plan?.let(::proposePlan)
            }

            "item/agentMessage/delta" -> stream.agentDelta(AppServer.text(params["delta"]))
            "item/reasoning/summaryTextDelta", "item/reasoning/textDelta" -> stream.reasoningDelta(AppServer.text(params["delta"]))
            "item/reasoning/summaryPartAdded" -> Unit
            "turn/plan/updated" -> (params["plan"] as? JsonArray)?.let(stream::planUpdated)

            "thread/tokenUsage/updated" -> (params["tokenUsage"] as? JsonObject)?.let { usage ->
                lastTokenUsage = usage
                stream.tokenUsage(usage["last"] as? JsonObject)
                CodexShapes.contextOf(usage)?.let { (used, max) -> onContext(used, max) }
            }

            "turn/completed" -> {
                val turn = params["turn"] as? JsonObject ?: return
                stream.turnCompleted(turn)
                endTurn()
                drainAfterTurn()
            }

            "error" -> {
                val error = params["error"] as? JsonObject ?: return
                stream.error(error, willRetry = params["willRetry"] == JsonPrimitive(true))
            }

            "thread/compacted" -> stream.compactionFinished()

            "model/rerouted" -> stream.rerouted(
                AppServer.text(params["fromModel"]),
                AppServer.text(params["toModel"]),
                AppServer.text(params["reason"]),
            )

            "thread/name/updated" -> AppServer.text(params["threadName"]).takeIf { it.isNotBlank() }?.let(onTitle)

            "account/rateLimits/updated" -> (params["rateLimits"] as? JsonObject)?.let { snapshot ->
                // A rolling update is sparse: an empty window means "not said this time", not "zero", and
                // passed on it would wipe the rings the last full answer drew.
                if (LIMIT_FIELDS.any { snapshot[it] is JsonObject }) onRateLimits(CodexShapes.usage(snapshot))
                CodexShapes.reachedWindow(snapshot)?.let { window ->
                    emit(CodexDialect.rateLimited(CodexShapes.resetOf(snapshot), window))
                }
            }

            "serverRequest/resolved" -> {
                val ours = requestIds.remove(AppServer.idText(params["requestId"] ?: return)) ?: return
                if (awaitingPermission.remove(ours) != null) onPermissionWithdrawn(ours)
            }

            "mcpServer/startupStatus/updated" -> {
                val name = AppServer.text(params["name"])
                if (name.isNotEmpty()) {
                    val state = AppServer.text(params["status"])
                    val before = mcpStartup.put(
                        name,
                        CodexShapes.Startup(
                            state = state,
                            error = AppServer.text(params["error"]).ifEmpty { null },
                            reauthenticate = AppServer.text(params["failureReason"]) == "reauthenticationRequired",
                        ),
                    )
                    if (state != "starting" && before?.state != state) onMcpSettled()
                }
            }

            "warning", "configWarning", "deprecationNotice", "guardianWarning" -> {
                val message = AppServer.text(params["message"]).ifEmpty { AppServer.text(params["summary"]) }
                if (message.isNotBlank()) onDiagnostic(message)
            }
        }
    }

    private fun rememberCalls(item: JsonObject) {
        if (AppServer.text(item["type"]) != "fileChange") return
        itemCalls[AppServer.text(item["id"])] = CodexDialect.toolCalls(item)
    }

    /**
     * Codex asks the person something. Each kind becomes the question the panel already knows how to put -
     * a permission card, the question card, a plan card - under our own id, and the answer goes back by
     * the server's (see [answerPermission]).
     */
    private fun serverRequest(epoch: Long, id: JsonElement, method: String, params: JsonObject) {
        val srv = server ?: return
        val thread = AppServer.text(params["threadId"])
        if (thread.isNotEmpty() && thread != conversationId) {
            // Not this conversation's question: an ephemeral title thread has nothing to ask about, and a
            // subagent's approvals would be asked on its own thread. Declined rather than left hanging.
            srv.respondError(id, AppServer.METHOD_NOT_FOUND, "Not handled for this thread.")
            return
        }

        val ours = "codex-$epoch-${AppServer.idText(id)}"
        val pending = when (method) {
            "item/commandExecution/requestApproval" -> commandApproval(ours, id, params)
            "item/fileChange/requestApproval" -> fileApproval(ours, id, params)
            "item/tool/requestUserInput" -> question(ours, id, params)
            "item/permissions/requestApproval" -> permissionsRequest(ours, id, params)
            "mcpServer/elicitation/request" -> elicitation(ours, id, params)
            else -> null
        }

        if (pending == null) {
            thisLogger().info("Unsupported request from codex: $method")
            srv.respondError(id, AppServer.METHOD_NOT_FOUND, "The panel does not handle '$method'.")
            return
        }

        awaitingPermission[ours] = pending
        requestIds[AppServer.idText(id)] = ours
        onToolPermission(pending.request)
    }

    private fun commandApproval(ours: String, id: JsonElement, params: JsonObject): Pending {
        val command = CodexDialect.unwrapShell(AppServer.text(params["command"]))
        val reason = AppServer.text(params["reason"])
        return Pending(
            kind = Kind.COMMAND,
            serverId = id,
            amendment = params["proposedExecpolicyAmendment"]?.takeIf { it !is JsonNull },
            request = PermissionChannel.ToolPermission(
                requestId = ours,
                toolName = "Bash",
                toolUseId = AppServer.text(params["itemId"]).ifEmpty { null },
                input = buildJsonObject {
                    put("command", command)
                    if (reason.isNotEmpty()) put("description", reason)
                },
                requiresUserInteraction = false,
                reason = reason,
                reasonType = if (reason.isNotEmpty()) "codex" else "",
            ),
        )
    }

    private fun fileApproval(ours: String, id: JsonElement, params: JsonObject): Pending {
        val itemId = AppServer.text(params["itemId"])
        val calls = itemCalls[itemId].orEmpty()
        val first = calls.firstOrNull()
        val reason = AppServer.text(params["reason"])
        val input = when {
            first == null -> buildJsonObject { put("description", reason.ifEmpty { "apply a patch" }) }
            calls.size == 1 -> first.input
            else -> buildJsonObject {
                first.input.forEach { (key, value) -> put(key, value) }
                put("files", calls.size)
            }
        }

        return Pending(
            kind = Kind.FILE,
            serverId = id,
            request = PermissionChannel.ToolPermission(
                requestId = ours,
                toolName = first?.name ?: "Edit",
                toolUseId = first?.id ?: itemId.ifEmpty { null },
                input = input,
                requiresUserInteraction = false,
                reason = reason,
                reasonType = if (reason.isNotEmpty()) "codex" else "",
            ),
        )
    }

    /**
     * Codex's `request_user_input`, drawn as the panel's question card: the card is a tool call in the
     * feed, so the call is put there first, and the question that follows is the card's to answer.
     */
    private fun question(ours: String, id: JsonElement, params: JsonObject): Pending {
        val itemId = AppServer.text(params["itemId"]).ifEmpty { "ask-$ours" }
        val asked = (params["questions"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val ids = LinkedHashMap<String, String>()

        val input = buildJsonObject {
            putJsonArray("questions") {
                for (question in asked) {
                    val text = AppServer.text(question["question"])
                    ids[text] = AppServer.text(question["id"])
                    addJsonObject {
                        put("question", text)
                        put("header", AppServer.text(question["header"]))
                        put("multiSelect", false)
                        putJsonArray("options") {
                            (question["options"] as? JsonArray)?.forEach { option ->
                                val o = option as? JsonObject ?: return@forEach
                                addJsonObject {
                                    put("label", AppServer.text(o["label"]))
                                    put("description", AppServer.text(o["description"]))
                                }
                            }
                        }
                    }
                }
            }
        }

        CodexDialect.toolUses(listOf(CodexDialect.ToolCall(itemId, CodexLaunch.ASK_TOOL, input)), currentModel(), uuid = itemId)
            ?.let(::emit)

        return Pending(
            kind = Kind.ASK,
            serverId = id,
            questionIds = ids,
            request = PermissionChannel.ToolPermission(
                requestId = ours,
                toolName = CodexLaunch.ASK_TOOL,
                toolUseId = itemId,
                input = input,
                requiresUserInteraction = true,
            ),
        )
    }

    private fun permissionsRequest(ours: String, id: JsonElement, params: JsonObject): Pending {
        val reason = AppServer.text(params["reason"])
        val wanted = params["permissions"] as? JsonObject
        val granted = buildJsonObject {
            (wanted?.get("network") as? JsonObject)?.let { put("network", it) }
            (wanted?.get("fileSystem") as? JsonObject)?.let { put("fileSystem", it) }
        }
        return Pending(
            kind = Kind.PERMISSIONS,
            serverId = id,
            permissions = granted,
            request = PermissionChannel.ToolPermission(
                requestId = ours,
                toolName = "Permissions",
                toolUseId = AppServer.text(params["itemId"]).ifEmpty { null },
                input = buildJsonObject {
                    put("description", reason.ifEmpty { "more access for this turn" })
                    wanted?.let { put("permissions", it) }
                },
                requiresUserInteraction = false,
                reason = reason,
                reasonType = if (reason.isNotEmpty()) "codex" else "",
            ),
        )
    }

    private fun elicitation(ours: String, id: JsonElement, params: JsonObject): Pending? {
        val server = AppServer.text(params["serverName"])
        val message = AppServer.text(params["message"])
        val url = AppServer.text(params["url"])
        // A form the panel cannot draw is declined rather than left standing: an MCP server waiting on a
        // form nobody can see holds the turn forever.
        if (AppServer.text(params["mode"]) != "url") return null

        return Pending(
            kind = Kind.ELICITATION,
            serverId = id,
            request = PermissionChannel.ToolPermission(
                requestId = ours,
                toolName = "mcp__${server}__open",
                toolUseId = null,
                input = buildJsonObject {
                    put("url", url)
                    put("description", message)
                },
                requiresUserInteraction = false,
                reason = message,
                reasonType = "codex",
            ),
        )
    }

    /** A plan-mode turn arrived at a plan: the plan card's question, answered by its buttons. */
    private fun proposePlan(plan: CodexStream.PlanProposal) {
        val requestId = "plan-${plan.itemId}"
        val request = PermissionChannel.ToolPermission(
            requestId = requestId,
            toolName = CodexDialect.PLAN_TOOL,
            toolUseId = plan.itemId,
            input = buildJsonObject { put("plan", plan.text) },
            requiresUserInteraction = true,
        )
        awaitingPermission[requestId] = Pending(Kind.PLAN, serverId = null, request = request)
        onToolPermission(request)
    }

    private fun emit(line: String) = onEvent(line)

    private fun modelOnWire(): String? = model.takeIf { it.isNotEmpty() && it != DEFAULT_MODEL }

    private fun currentModel(): String = modelOnWire() ?: threadModel

    private companion object {
        /** The menu's name for "whatever Codex runs by default" - never sent as a model. */
        const val DEFAULT_MODEL = "default"

        const val THREAD_OPEN_TIMEOUT_SECONDS = 90L
        const val TURN_START_TIMEOUT_SECONDS = 60L
        const val INTERRUPT_TIMEOUT_SECONDS = 20L
        const val MCP_LOGIN_TIMEOUT_SECONDS = 60L
        const val PLAN_FOLLOW_UP_DELAY_MS = 150L

        /** The parts of a limits update that carry figures - see the rolling update in [notification]. */
        val LIMIT_FIELDS = listOf("primary", "secondary", "individualLimit")
    }
}
