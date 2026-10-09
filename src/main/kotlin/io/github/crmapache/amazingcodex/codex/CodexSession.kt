package io.github.crmapache.amazingcodex.codex

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.util.concurrency.AppExecutorUtil
import io.github.crmapache.amazingcodex.codex.accounts.CodexAccounts
import io.github.crmapache.amazingcodex.feedback.DiagnosticsLog
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
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
     * Where this conversation forks from - the conversation it branches off and the turn of it it ends on
     * (see ForkOrigin). A branch gets that much of the other thread and an id of its own, so continuing
     * inside it leaves the other one untouched.
     *
     * Worked out once, against the source's turns, by whoever asks first: the hub right after the fork is
     * made, while it plays what the fork inherited into the tab, or the first launch if a message beat it
     * there. Lazy rather than computed by the caller because the caller is the thread a panel talks on, and
     * the answer is a question to Codex.
     */
    private val origin: Lazy<ForkOrigin.Resolved?> = lazyOf(null),
    /**
     * The fork's thread has been born under this id - see ForkBook, which keeps what only this moment knows:
     * which conversation it came from, and where its own part begins.
     */
    private val onForked: (conversationId: String, origin: ForkOrigin) -> Unit = { _, _ -> },
    /** A past conversation being continued: it comes up with its own history. */
    resumeFrom: String? = null,
    /**
     * The model and the effort are the conversation's, chosen once and carried to every turn. `var`
     * rather than `val`, because they are chosen before the first message too, when there is no process
     * to tell.
     */
    model: String = "",
    effort: String = "",
    contextMode: String = ModelContexts.STANDARD,
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
    /**
     * The name the person gave the tab, if any - written into the thread whenever a process opens it and
     * it does not carry that name yet (see [nameAfterPerson]), so a name given before the first message,
     * or while the conversation slept, reaches Codex's record too.
     */
    private val ownTitle: () -> String? = { null },
    /**
     * The thread was renamed by somebody else - another Codex client, a terminal - to a name that is
     * neither ours nor the model's (see [notification]): a person's name, which the tab takes on.
     */
    private val onRenamed: (String) -> Unit = {},
    /** The process died on its own, not because we stopped it. */
    private val onCrashed: (Int) -> Unit = {},
    /** The person's turn has ended - by its end, by a crash, or by never getting started. */
    private val onTurnEnded: () -> Unit = {},
    /** A turn started without a message of ours - an approved plan's implementation, a queued steer. */
    private val onTurnStarted: () -> Unit = {},
    /** What the agent is told about where it runs - see CodexLaunch.PANEL_BRIEFING. */
    briefing: String = CodexLaunch.PANEL_BRIEFING,
    /**
     * A new role for a thread this process resumes, said before its next turn - see [threadOpened].
     *
     * Codex keeps the developer instructions a thread was started with: `thread/resume` takes the field
     * and does nothing with it (measured on 0.152 - a thread started "answer in French" and resumed
     * "answer in German" went on in French). A conversation whose role changes over the same thread - a
     * scenario's main thread raised to finish a card's work, and back - is told the change in its own
     * history instead, as a developer message, which is how Codex's own side conversations say theirs.
     */
    private val roleChange: String? = null,
    /** Whether this conversation should be given a name at all; the scenario engine's sessions are not. */
    private val nameWanted: Boolean = true,
    /** How full the context window is now, straight from Codex's own count - see CodexShapes.contextOf. */
    private val onContext: (used: Int, max: Int) -> Unit = { _, _ -> },
    /** Codex told us the account's limits along the way - the rings move without asking. */
    private val onRateLimits: (JsonObject) -> Unit = {},
    private val onAppAuthRequired: (String) -> Unit = {},
    /**
     * An MCP server of this process finished starting - came up, or failed. The MCP screen asks for the
     * list when it opens, and a server still starting then (Codex's own `codex_apps` takes seconds) would
     * stand at "connecting…" for as long as the screen stays open without this.
     */
    private val onMcpSettled: () -> Unit = {},
    /**
     * The agents this conversation spawned, told as the start and the end of each one's work - in the shape
     * Claude Code's background tasks have (`task_started`, `task_notification`), which is what a scenario
     * card's count of helpers reads (see ScenarioEngine.openCard, BackgroundWork). Null - a tab's
     * conversation - keeps none of this.
     *
     * A channel of its own rather than the stream: Codex runs a spawned agent in a thread of its own and
     * never wakes the thread that spawned it when the agent is done (measured on 0.160 - the turn that spawned
     * one ended at once, and the agent's own turn ended half a minute later with nothing said to its parent).
     * Told to a tab's feed, the panel would wait for the turn a report starts under Claude Code, which here
     * never comes.
     */
    private val onBackground: ((String) -> Unit)? = null,
) : Disposable {

    /** Every role gets the project's shared `.claude` contract when its process actually opens. */
    private val baseBriefing = briefing

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

    /**
     * The end of a plan-mode turn whose plan waits for a decision, not yet told to the panel. Codex ends the
     * turn with the plan; the panel's dialect has the plan as a question the turn stands on (ExitPlanMode),
     * and the card's buttons, the field's "answer the plan" and the phone's card all live only while the
     * turn runs. So the turn is told to have ended once the plan is decided - see [releasePlanTurn].
     */
    @Volatile
    private var heldPlanTurn: JsonObject? = null

    var model: String = model
        private set

    var effort: String = effort
        private set

    var contextMode: String = ModelContexts.normalize(contextMode)
        private set

    @Volatile
    private var executableStamp: CodexExecutable.Stamp? = null

    var permissionMode: String? = permissionMode.ifEmpty { null }?.let(PermissionModes::normalize)
        private set

    /** The conversation's identifier - Codex's thread id. Without it there is no resuming it later. */
    @Volatile
    var conversationId: String? = resumeFrom
        private set

    /**
     * Whether the thread has anything on disk to come back to: one continued from the history or forked
     * off another has, a new one only once a turn has started in it. Codex names a thread the moment it
     * opens it, but writes its file with the first turn - so a tab remembered by the id alone would come
     * back after a restart as a conversation that is not there (see CodexSessionHub.rememberTabs).
     */
    @Volatile
    var hasHistory: Boolean = resumeFrom != null
        private set

    /**
     * Where this conversation forks from (see [origin]) - null for one that is no fork.
     *
     * Readable from outside for one reason: a fork nobody has spoken in yet owes its whole conversation to its
     * source, so a tab replaced under it - an account chosen, say - has to be raised as the same fork again
     * (see CodexSessions.moveTo), and the tab remembers it across a restart (see TabMemory.Tab).
     */
    val forkOrigin: ForkOrigin?
        get() = origin.value?.origin

    /** [forkOrigin] when it has been worked out already, without working it out - for a thread that must not ask Codex. */
    val forkOriginIfKnown: ForkOrigin?
        get() = if (origin.isInitialized()) origin.value?.origin else null

    /** The message the fork was to stop at was not found, and it carries the whole conversation (see ForkOrigin.resolve). */
    val forkMissed: Boolean
        get() = origin.value?.missed == true

    /** A fork opened and not yet named by its process - see [threadOpened]. */
    @Volatile
    private var forkLaunched: ForkOrigin? = null

    /**
     * The turn each message began, by the name the panel gave the message (see [sendPrompt]) - what a rewind
     * or a fork names it by later. A message written into a running turn is filed under that turn, which it
     * belongs to without beginning it. Messages read from the history are named by Codex's own item ids and
     * found in the turns instead (see [turnOf]).
     */
    private val turnOfMessage = ConcurrentHashMap<String, String>()

    /**
     * The names of the messages this conversation was given, in the order it was given them - what tells a
     * message a client has seen from one it has not (see [movedPast]).
     */
    private val delivered = ArrayList<String>()

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

    /**
     * A message on its way: the person's words, the pictures, the editor note that goes with them, and the
     * name the panel gave it (see [turnOfMessage]).
     */
    private data class Outgoing(val text: String, val images: List<ImageAttachment>, val context: String?, val uuid: String? = null)

    /** Messages written into a turn that would not take them in (see [steer]): sent when it ends. */
    private val afterTurn = ArrayList<Outgoing>()

    /** The name the person gave, as this process last wrote it into the thread - see [nameAfterPerson]. */
    @Volatile
    private var namedAs: String? = null

    /** The name the model gave, as this process wrote it - its echo is not a rename (see [notification]). */
    @Volatile
    private var modelNamed: String? = null

    /** The last turn of this conversation that has ended - what a side question forks through (see [askAside]). */
    @Volatile
    private var lastEndedTurn: String? = null

    /** Side questions in flight, by the panel's id for them - see [askAside]. */
    private val asides = ConcurrentHashMap<String, Aside>()

    /** The same, by the id of the ephemeral thread each one is asked in. */
    private val asideThreads = ConcurrentHashMap<String, Aside>()

    private class Aside(
        val id: String,
        val question: String,
        val history: List<SideQuestion.Exchange>,
        val onProgress: (SideQuestion.Progress) -> Unit,
        val onEnd: (SideQuestion.Answer) -> Unit,
    ) {
        @Volatile var thread: String? = null
        @Volatile var turn: String? = null
        @Volatile var text: String = ""
        @Volatile var notice: String? = null
        @Volatile var attempts: Int = 0
        @Volatile var cancelled: Boolean = false
        @Volatile var timeout: java.util.concurrent.ScheduledFuture<*>? = null
    }

    private val stream = CodexStream(
        emit = ::emit,
        model = ::currentModel,
        threadId = { conversationId.orEmpty() },
        limitReached = { lastLimit?.takeIf { System.currentTimeMillis() - it.heardAt < LIMIT_MEMORY_MS }?.let { it.window to it.resetsAt } },
    )

    /** The account's limit as it last said a window was full - see CodexStream.limitReached. */
    private class Limit(val window: String, val resetsAt: Long?, val heardAt: Long)

    @Volatile
    private var lastLimit: Limit? = null

    /**
     * An agent this conversation spawned - see [onBackground]. At work from its spawn until its turn ends, and
     * again whenever it is sent back to work; [turn] is the turn to interrupt, once its thread has named it.
     */
    private class Helper(val callId: String, val description: String) {
        @Volatile var working = true
        @Volatile var turn: String? = null
        @Volatile var said: String = ""
    }

    /** The agents this conversation spawned, by their threads' ids - kept only for [onBackground]. */
    private val helpers = ConcurrentHashMap<String, Helper>()

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


    private var titleAsked = false

    /** The thread this process holds was opened by `thread/resume` rather than started or forked. */
    @Volatile
    private var resumed = false

    /** New developer instructions were said into a resumed thread - once per session across restarts. */
    @Volatile
    private var instructionsSaid = false

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

    /**
     * A message from the person. [context] is the editor note that goes with it (see IdeContextPrompt) - a
     * text item of its own after the words, never mixed into them. [uuid] is the name the panel gave the
     * message (see CodexSessionHub.deliverPrompt), the one a rewind or a fork names it by later.
     *
     * Returns whether the message went into a process: false when none would come up, and then onError has
     * already said why. A scenario run reads it to keep what it handed over waiting for the next process
     * rather than marking it given (see ScenarioEngine.askAgain).
     */
    fun sendPrompt(
        text: String,
        images: List<ImageAttachment> = emptyList(),
        context: String? = null,
        uuid: String? = null,
    ): Boolean {
        if (server == null && start() == null) {
            endTurn()
            return false
        }
        uuid?.let { synchronized(delivered) { delivered += it } }
        // A message that is not an answer to a waiting plan sets the plan aside, as typing past it does in
        // Codex's terminal: the plan is withdrawn and its turn ends before this one begins.
        if (heldPlanTurn != null) {
            withdrawPlans()
            releasePlanTurn(drain = false)
        }
        busy = true
        whenOpen { deliver(Outgoing(text, images, context, uuid)) }
        return true
    }

    /**
     * What a message is decides where it goes (see CodexCommands): the commands that are actions become
     * the requests that do them, everything else is a turn - or, while one is running, words steered into it.
     */
    private fun deliver(message: Outgoing) {
        val (text, images, context, uuid) = message
        when (val command = CodexCommands.parse(text, CodexHome.of(workingDirectory).promptsDirectory, CodexSkills.of(workingDirectory))) {
            CodexCommands.Command.Compact -> compact()
            CodexCommands.Command.Clear -> clear()
            CodexCommands.Command.Init -> startTurn(CodexLaunch.INIT_PROMPT, emptyList(), emptyList(), uuid = uuid)
            is CodexCommands.Command.Review -> review(command.target)
            is CodexCommands.Command.Rename -> {
                rename(command.title)
                onRenamed(command.title)
                endTurn()
            }
            is CodexCommands.Command.Say ->
                if (activeTurn != null) {
                    steer(command.text, images, command.skills, context, uuid)
                } else {
                    startTurn(command.text, images, command.skills, context, uuid)
                }
        }
    }

    private fun startTurn(
        text: String,
        images: List<ImageAttachment>,
        skills: List<CodexCommands.Skill>,
        context: String? = null,
        uuid: String? = null,
    ) {
        val srv = server ?: return endTurn()
        val thread = conversationId ?: return endTurn()
        val policy = PermissionModes.policyOf(permissionMode)

        val params = buildJsonObject {
            put("threadId", thread)
            put("input", inputOf(text, images, skills, context))
            put("approvalPolicy", policy.approval)
            put("sandboxPolicy", policy.sandboxPolicy(CodexConfigDesk.workspaceWrite(workingDirectory)))
            modelOnWire()?.let { put("model", it) }
            val wired = EffortLevels.wire(effort)
            when {
                wired != null -> {
                    put("effort", wired)
                    effortSent = true
                }
                effortSent -> put("effort", JsonNull)
            }
            // The panel draws the reasoning, so it asks for a summary of it - unless the person's own config
            // says what kind they want (`model_reasoning_summary`), which then decides by itself.
            if (!CodexConfigDesk.setsSummary(workingDirectory)) put("summary", "auto")
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
                    uuid?.let { turnOfMessage[it] = id }
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
    private fun steer(text: String, images: List<ImageAttachment>, skills: List<CodexCommands.Skill>, context: String?, uuid: String?) {
        val srv = server ?: return
        val thread = conversationId ?: return
        val turn = activeTurn ?: return startTurn(text, images, skills, context, uuid)

        srv.request(
            "turn/steer",
            buildJsonObject {
                put("threadId", thread)
                put("input", inputOf(text, images, skills, context))
                put("expectedTurnId", turn)
            },
            onResult = { uuid?.let { turnOfMessage[it] = turn } },
            onError = {
                // The note goes along with the words it was taken for, so a message sent after the turn
                // still says what the editor showed when it was written.
                synchronized(afterTurn) { afterTurn += Outgoing(text, images, context, uuid) }
                // The turn may have ended between the send and the refusal: then nobody else will drain.
                if (activeTurn == null) drainAfterTurn()
            },
        )
    }

    private fun inputOf(text: String, images: List<ImageAttachment>, skills: List<CodexCommands.Skill>, context: String? = null): JsonArray =
        buildJsonArray {
            addJsonObject {
                put("type", "text")
                put("text", text)
                putJsonArray("text_elements") {}
            }
            // After the words rather than before them: a thread's preview is the start of its first
            // message, and every history row would otherwise begin with the same heading (see IdeContextPrompt).
            context?.takeIf { it.isNotBlank() }?.let { note ->
                addJsonObject {
                    put("type", "text")
                    put("text", note)
                    putJsonArray("text_elements") {}
                }
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
        stream.compactionAsked()
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
                    hasHistory = false
                    lastTokenUsage = null
                    titleAsked = false
                    awaitingPermission.clear()
                    // A new conversation: nothing said into the old one is anything a rewind here can name.
                    turnOfMessage.clear()
                    synchronized(delivered) { delivered.clear() }
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
            // Asked again: the person may have named the tab in the seconds the model took, and a name
            // given by hand is never overwritten by the model's - not on the tab, and not in Codex's
            // record either, where the history and `codex resume` would read the model's instead.
            if (!titleWanted()) return@ask
            onTitle(title)
            modelNamed = title
            AppExecutorUtil.getAppExecutorService().execute { AutoTitles(workingDirectory).note(thread, title) }
            srv.request(
                "thread/name/set",
                buildJsonObject {
                    put("threadId", thread)
                    put("name", title)
                },
            )
        }
    }

    /**
     * The name the person gave the tab, into Codex's record of the thread - so the history, the search
     * and `codex resume` in a terminal show it too.
     *
     * Through this conversation's process while it has one. Without one, through the shared catalog
     * process (see CodexCatalog), off the caller's thread; and whichever process opens the thread next
     * writes it again if it is not there yet (see [nameAfterPerson]), which covers the one that could not.
     */
    fun rename(title: String) {
        val name = title.trim().takeIf { it.isNotEmpty() } ?: return
        val thread = conversationId ?: return
        val params = buildJsonObject {
            put("threadId", thread)
            put("name", name)
        }

        val srv = server
        if (srv != null && open) {
            namedAs = name
            srv.request("thread/name/set", params)
            return
        }

        AppExecutorUtil.getAppExecutorService().execute {
            runCatching { CodexCatalog.call("thread/name/set", params) }
        }
    }

    /** The person's name for the tab, written into the thread a process has just opened - see [ownTitle]. */
    /**
     * A fork carries its parent's name: Codex copies it into the new thread's record (measured on 0.152 -
     * the history and `codex resume` show the fork under it at once). The tab takes it on with the
     * parent's standing - the model's name stays the model's, anything else is a person's - so the title
     * question, which exists to replace stand-ins, does not write over a name somebody gave.
     */
    private fun adoptForkName(thread: String, name: String, parent: String) {
        if (name.isEmpty() || !nameWanted) return
        val book = AutoTitles(workingDirectory)
        if (AutoTitles.sourceOf(name, book.all()[parent]) == SessionSnapshot.TITLE_LLM) {
            modelNamed = name
            book.note(thread, name)
            onTitle(name)
        } else {
            namedAs = name
            onRenamed(name)
        }
    }

    private fun nameAfterPerson() {
        val title = ownTitle()?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (title == namedAs) return
        rename(title)
    }

    // --- Side questions ----------------------------------------------------------------------------

    /**
     * A question beside the conversation - the panel's `/btw`, Codex's own `/side` (see SideQuestion).
     *
     * Asked in an ephemeral fork of this thread, in this process: the fork knows everything the
     * conversation does, and the conversation knows nothing of the fork - the agent never sees the
     * question, nothing is written to disk, and a turn under way carries on. A conversation with no
     * thread on disk yet has nothing to fork; then the question goes to an ephemeral thread of its own,
     * as a new conversation would answer it.
     *
     * [onProgress] hears that the answer is under way and of the model being asked again; [onEnd] hears
     * the outcome exactly once - answered, empty, cancelled, or failed, including when this process goes
     * away first (see [abandonAsides]).
     */
    fun askAside(
        id: String,
        question: String,
        history: List<SideQuestion.Exchange>,
        onProgress: (SideQuestion.Progress) -> Unit,
        onEnd: (SideQuestion.Answer) -> Unit,
    ) {
        if (server == null && start() == null) {
            onEnd(SideQuestion.Answer.Failed(SideQuestion.Reason.ENDED, "The conversation could not be started."))
            return
        }

        val aside = Aside(id, question, history, onProgress, onEnd)
        asides[id] = aside
        aside.timeout = AppExecutorUtil.getAppScheduledExecutorService().schedule(
            {
                interruptAside(aside)
                finishAside(aside, SideQuestion.Answer.Failed(SideQuestion.Reason.TIMEOUT, "No answer in time."))
            },
            SideQuestion.TIMEOUT_SECONDS,
            TimeUnit.SECONDS,
        )
        whenOpen { forkForAside(aside, through = null) }
    }

    /** The person closed the question before its answer. Said at once; the fork is stopped behind it. */
    fun cancelAside(id: String) {
        val aside = asides[id] ?: return
        aside.cancelled = true
        interruptAside(aside)
        finishAside(aside, SideQuestion.Answer.Cancelled)
    }

    /**
     * Fork this thread for one question. [through] is the last turn that has ended, for a second try when
     * Codex would not fork across a turn still under way; without it the fork takes everything there is.
     */
    /**
     * Open the thread a side question is asked in. A fork of this conversation, through [through] - the
     * last turn that has ended - when Codex would not fork across a turn still under way; or, when [alone],
     * an ephemeral thread of its own, for a conversation with nothing on disk to fork yet.
     */
    private fun forkForAside(aside: Aside, through: String? = null, alone: Boolean = false) {
        if (aside.cancelled) return
        val srv = server ?: return finishAside(aside, SideQuestion.Answer.Failed(SideQuestion.Reason.ENDED, "The conversation is not running."))

        // Which MCP servers the settings in force configure - the only ones a fork can switch off by name
        // (see SideQuestion.configOverrides). Asked of this process, so a project's own servers count too.
        srv.request(
            "config/read",
            buildJsonObject { workingDirectory?.let { put("cwd", it) } },
            onResult = { result ->
                val servers = ((result as? JsonObject)?.get("config") as? JsonObject)?.get("mcp_servers") as? JsonObject
                openAside(srv, aside, through, alone, servers?.keys.orEmpty())
            },
            onError = { openAside(srv, aside, through, alone, emptySet()) },
        )
    }

    private fun openAside(srv: AppServer, aside: Aside, through: String?, alone: Boolean, configuredServers: Set<String>) {
        if (aside.cancelled) return
        val parent = conversationId.takeUnless { alone }

        val params = buildJsonObject {
            if (parent != null) {
                put("threadId", parent)
                put("excludeTurns", true)
                through?.let { put("lastTurnId", it) }
            }
            // Read-only and asking nobody, on the conversation's model, and without the tools the sandbox
            // does not hold (see SideQuestion.configOverrides).
            workingDirectory?.let { put("cwd", it) }
            modelOnWire()?.let { put("model", it) }
            put("ephemeral", true)
            put("approvalPolicy", "never")
            put("sandbox", PermissionModes.SANDBOX_READ_ONLY)
            put("developerInstructions", SideQuestion.INSTRUCTIONS)
            put("config", SideQuestion.configOverrides(configuredServers))
        }

        srv.request(
            if (parent != null) "thread/fork" else "thread/start",
            params,
            timeoutSeconds = THREAD_OPEN_TIMEOUT_SECONDS,
            onResult = { result ->
                val thread = AppServer.text(((result as? JsonObject)?.get("thread") as? JsonObject)?.get("id"))
                if (thread.isEmpty()) {
                    finishAside(aside, SideQuestion.Answer.Failed(SideQuestion.Reason.REFUSED, "Codex opened no thread for the question."))
                    return@request
                }
                aside.thread = thread
                asideThreads[thread] = aside
                if (aside.cancelled) finishAside(aside, SideQuestion.Answer.Cancelled) else askInFork(srv, aside, thread, injected = parent != null)
            },
            onError = { error ->
                when {
                    // Nothing on disk to fork: the conversation has not said a word yet.
                    parent != null && error.message.contains("rollout", ignoreCase = true) -> forkForAside(aside, alone = true)
                    // A turn under way that the fork would not cross: through the last one that ended.
                    parent != null && through == null && lastEndedTurn != null -> forkForAside(aside, through = lastEndedTurn)
                    else -> finishAside(aside, SideQuestion.Answer.Failed(SideQuestion.Reason.REFUSED, error.message))
                }
            },
        )
    }

    /**
     * The boundary and the earlier exchanges into the fork, then the question as its turn. When Codex would
     * not take the items - or there was no inherited history for a boundary to close - the same words go
     * ahead of the question instead (see SideQuestion.inlined), which costs nothing but their place.
     */
    private fun askInFork(srv: AppServer, aside: Aside, thread: String, injected: Boolean) {
        if (!injected && aside.history.isEmpty()) return startAsideTurn(srv, aside, thread, aside.question)

        srv.request(
            "thread/inject_items",
            buildJsonObject {
                put("threadId", thread)
                put("items", SideQuestion.items(aside.history))
            },
            onResult = { startAsideTurn(srv, aside, thread, aside.question) },
            onError = { startAsideTurn(srv, aside, thread, SideQuestion.inlined(aside.question, aside.history)) },
        )
    }

    private fun startAsideTurn(srv: AppServer, aside: Aside, thread: String, text: String) {
        if (aside.cancelled) return finishAside(aside, SideQuestion.Answer.Cancelled)

        srv.request(
            "turn/start",
            buildJsonObject {
                put("threadId", thread)
                put("input", inputOf(text, emptyList(), emptyList()))
                put("approvalPolicy", "never")
                put("sandboxPolicy", PermissionModes.policyOf(PermissionModes.READ_ONLY).sandboxPolicy())
                modelOnWire()?.let { put("model", it) }
                EffortLevels.wire(effort)?.let { put("effort", it) }
                // Its reasoning is shown nowhere - the card holds the answer alone.
                put("summary", "none")
            },
            timeoutSeconds = TURN_START_TIMEOUT_SECONDS,
            onResult = { result ->
                val turn = AppServer.text(((result as? JsonObject)?.get("turn") as? JsonObject)?.get("id"))
                if (turn.isNotEmpty() && aside.turn == null) aside.turn = turn
                if (aside.cancelled) interruptAside(aside)
            },
            onError = { error -> finishAside(aside, SideQuestion.Answer.Failed(SideQuestion.Reason.REFUSED, error.message)) },
        )
    }

    private fun interruptAside(aside: Aside) {
        val srv = server ?: return
        val thread = aside.thread ?: return
        val turn = aside.turn ?: return
        srv.request(
            "turn/interrupt",
            buildJsonObject {
                put("threadId", thread)
                put("turnId", turn)
            },
        )
    }

    /** The one outcome of a side question, and the fork let go of. */
    private fun finishAside(aside: Aside, answer: SideQuestion.Answer) {
        if (asides.remove(aside.id) == null) return
        aside.timeout?.cancel(false)
        aside.thread?.let { thread ->
            asideThreads.remove(thread)
            // An ephemeral thread stays loaded in the process until it is let go: a long day of questions
            // would otherwise keep every one of them in memory.
            server?.request("thread/unsubscribe", buildJsonObject { put("threadId", thread) })
        }
        aside.onEnd(answer)
    }

    /** The process is going: every question still waiting is answered as such, at once. */
    private fun abandonAsides() {
        for (aside in asides.values.toList()) {
            finishAside(aside, SideQuestion.Answer.Failed(SideQuestion.Reason.ENDED, "The conversation stopped before answering."))
        }
    }

    /** What the fork of a side question says, retold for the card that asked it. */
    private fun asideNotification(aside: Aside, method: String, params: JsonObject) {
        when (method) {
            "turn/started" -> {
                aside.turn = AppServer.text((params["turn"] as? JsonObject)?.get("id")).ifEmpty { aside.turn }
                aside.onProgress(SideQuestion.Progress(aside.id, SideQuestion.STARTED, null, null, null, null))
                if (aside.cancelled) interruptAside(aside)
            }

            "item/completed" -> {
                val item = params["item"] as? JsonObject ?: return
                if (AppServer.text(item["type"]) == "agentMessage") {
                    AppServer.text(item["text"]).takeIf { it.isNotBlank() }?.let { aside.text = it }
                }
            }

            "error" -> if (params["willRetry"] == JsonPrimitive(true)) {
                aside.attempts += 1
                val error = params["error"] as? JsonObject
                // Codex says that it will try again, not how often or when; the card's line is filled in
                // the way the conversation's own retry card is (see CodexStream.error).
                aside.onProgress(
                    SideQuestion.Progress(
                        requestId = aside.id,
                        status = SideQuestion.API_RETRY,
                        attempt = aside.attempts,
                        maxRetries = CodexStream.MAX_RETRIES_SHOWN,
                        delayMs = 0,
                        errorStatus = CodexErrors.httpStatus(error?.get("codexErrorInfo")),
                    ),
                )
            }

            "model/rerouted" -> aside.notice = CodexDialect.rerouteReason(AppServer.text(params["reason"])).ifEmpty { null }

            "turn/completed" -> {
                val turn = params["turn"] as? JsonObject ?: return
                val answer = when (AppServer.text(turn["status"])) {
                    "completed" ->
                        if (aside.text.isNotBlank()) SideQuestion.Answer.Answered(aside.text.trim(), aside.notice) else SideQuestion.Answer.Empty(null)
                    "interrupted" ->
                        if (aside.cancelled) SideQuestion.Answer.Cancelled
                        else SideQuestion.Answer.Failed(SideQuestion.Reason.ENDED, "The answer was interrupted.")
                    else -> SideQuestion.Answer.Failed(
                        SideQuestion.Reason.REFUSED,
                        AppServer.text((turn["error"] as? JsonObject)?.get("message")).ifEmpty { "Codex could not answer." },
                    )
                }
                finishAside(aside, answer)
            }
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

    fun setContextMode(mode: String) {
        contextMode = ModelContexts.normalize(mode)
    }

    fun contextWindow(model: String = this.model, mode: String = contextMode): Int? =
        ModelContexts.window(workingDirectory, model, mode)

    fun executableChanged(): Boolean = executableStamp?.let { it != CodexExecutable.currentStamp() } == true

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
        if (mode == PermissionModes.BYPASS) {
            awaitingPermission
                .filterValues { it.kind == Kind.COMMAND || it.kind == Kind.FILE || it.kind == Kind.PERMISSIONS }
                .keys
                .toList()
                .forEach { requestId ->
                    onPermissionWithdrawn(requestId)
                    answerPermission(requestId, allow = true)
                }
        }
        onApplied(ModeChange(applied = true, mode = mode))
    }

    // --- Questions about the session ---------------------------------------------------------------

    fun requestModels(onResult: (JsonObject) -> Unit, onFailure: (String) -> Unit = {}) {
        val srv = server ?: return onFailure("no live session")
        srv.request(
            "model/list",
            buildJsonObject { put("includeHidden", false) },
            onResult = {
                onResult(
                    CodexShapes.models(
                        it,
                        CodexSettings.effective(workingDirectory, "model"),
                        CodexSettings.effective(workingDirectory, "model_reasoning_effort"),
                        ModelContexts.read(workingDirectory),
                    ),
                )
            },
            onError = { onFailure(it.message) },
        )
    }

    fun readPluginApps(plugins: List<InstalledPlugin>, onResult: (List<PluginAppGroup>) -> Unit, onFailure: (String) -> Unit) {
        val srv = server ?: return onFailure("no live session")
        CodexApps.read(plugins, workingDirectory, { method, params, result, failure ->
            val scoped = buildJsonObject {
                params.forEach { (key, value) -> put(key, value) }
                if (method.startsWith("app/") && open) conversationId?.let { put("threadId", it) }
            }
            srv.request(method, scoped, onResult = result, onError = { failure(it.message) })
        }, onResult, onFailure)
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
                onUsage(CodexShapes.usageAnswer(result as? JsonObject, CodexShapes.contextOf(lastTokenUsage)?.second))
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
        // A card's helpers stop with it, as Claude Code's background agents stop with its turn: the card is
        // told afterwards that they did not survive (see HeadTalk.CARD_CARRY_ON), and that has to be true.
        if (onBackground != null) interruptHelpers()

        // Stop over a plan nobody has decided: Codex has nothing running, only the panel's turn stands on
        // the card. The plan is withdrawn and the turn ends as stopped.
        if (heldPlanTurn != null) {
            withdrawPlans()
            releasePlanTurn(drain = false)
            return
        }

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

    // --- Rewind ------------------------------------------------------------------------------------

    /**
     * What putting the code back to before [target] would touch - the rewind dialog's question, asked before
     * anything is changed (see Rewind.Code). Worked out from the patches the thread holds; nothing is written.
     */
    fun previewRewind(target: String, onCode: (Rewind.Code) -> Unit) {
        whenOpen {
            AppExecutorUtil.getAppExecutorService().execute {
                val code = runCatching {
                    val srv = server ?: return@runCatching Rewind.Code.Unavailable(NO_PROCESS)
                    val thread = conversationId ?: return@runCatching Rewind.Code.None
                    val turn = turnOf(srv, thread, target) ?: return@runCatching Rewind.Code.None
                    Rewind.codeOf(codePlan(srv, thread, turn))
                }.getOrElse { error -> Rewind.Code.Unavailable(error.message.orEmpty()) }
                onCode(code)
            }
        }
    }

    /**
     * Cut the conversation back to before [target] - the turn that message began and every later one leave
     * the agent's memory - and/or put the files its patches changed since then back the way they were.
     *
     * The order is chosen so that a refusal touches as little as it can:
     * - the code is checked first (see CodeRewind.plan), so a code part that cannot be done stops the whole
     *   thing before a word is dropped;
     * - a turn still running is stopped, and the code checked again over what it wrote meanwhile;
     * - the conversation is cut next (`thread/revert`) - the part with refusals no check can foresee;
     * - the files are put back last, so a refused cut leaves no code rolled back under a conversation that
     *   still talks about it.
     *
     * The stopped turn ends the way any stopped turn does, before the cut, and the hub holds the tab's queue
     * for as long as the rewind is out (see CodexSessionHub.rewind): what was queued belonged to the turn
     * that is thrown away.
     *
     * [lastSeen] is the newest message the asking client has on screen - a later one it has not seen stops the
     * rewind rather than being dropped unread (see [movedPast]).
     */
    fun rewind(
        target: String,
        lastSeen: String?,
        conversation: Boolean,
        files: Boolean,
        onOutcome: (Rewind.Outcome) -> Unit,
    ) {
        whenOpen {
            AppExecutorUtil.getAppExecutorService().execute {
                val outcome = runCatching { rewindNow(target, lastSeen, conversation, files) }.getOrElse { error ->
                    val failure = error as? RpcFailure
                    Rewind.Outcome.Refused(
                        failure?.let { Rewind.Refusal.ofError(it.error.code, it.error.message) } ?: Rewind.Refusal.OTHER,
                        error.message.orEmpty(),
                    )
                }
                onOutcome(outcome)
            }
        }
    }

    private fun rewindNow(target: String, lastSeen: String?, conversation: Boolean, files: Boolean): Rewind.Outcome {
        val srv = server ?: return Rewind.Outcome.Refused(Rewind.Refusal.NO_PROCESS, "")
        val thread = conversationId ?: return Rewind.Outcome.Refused(Rewind.Refusal.GONE, "")
        val refs = CodexHistory.turnRefs(thread) { method, params -> ask(srv, method, params).getOrThrow() }
            ?: return Rewind.Outcome.Refused(Rewind.Refusal.GONE, "")
        val turn = turnOf(target, refs) ?: return Rewind.Outcome.Refused(Rewind.Refusal.GONE, "")
        // A message written into a turn is no turn's beginning: the panel never offers it (see UserItem.steering),
        // and a phone or an older client asking anyway is told so rather than losing the turn it was written into.
        val began = refs.first { it.id == turn }
        val steered = when {
            target == turn -> false
            target in began.messages -> began.messages.first() != target
            else -> firstMessageOf(turn) != target
        }
        if (steered) return Rewind.Outcome.Refused(Rewind.Refusal.MID_CALL, "")

        if (!conversation) {
            // Code alone, under a conversation that carries on: not over a turn still writing files.
            if (busy) return Rewind.Outcome.Refused(Rewind.Refusal.BUSY, "")
            val plan = codePlan(srv, thread, turn)
            if (plan !is CodeRewind.Plan.Ready) return Rewind.Outcome.Refused(Rewind.Refusal.CODE, Rewind.detailOf(Rewind.codeOf(plan)))
            return CodeRewind.apply(plan).fold(
                onSuccess = { written -> Rewind.Outcome.Done(false, "", Rewind.Files.RESTORED, written) },
                onFailure = { error -> Rewind.Outcome.Refused(Rewind.Refusal.CODE, error.message.orEmpty()) },
            )
        }

        if (movedPast(target, lastSeen)) return Rewind.Outcome.Refused(Rewind.Refusal.MOVED, "")

        // Checked before anything is stopped: a code part that cannot be done changes nothing at all.
        if (files) {
            val plan = codePlan(srv, thread, turn)
            if (plan is CodeRewind.Plan.Conflict) return Rewind.Outcome.Refused(Rewind.Refusal.CODE, Rewind.detailOf(Rewind.codeOf(plan)))
        }

        if (!stopForRewind()) return Rewind.Outcome.Refused(Rewind.Refusal.BUSY, "")

        // Again after the stop: the turn may have patched a file in its last seconds.
        val plan = if (files) codePlan(srv, thread, turn) else CodeRewind.Plan.None
        if (plan is CodeRewind.Plan.Conflict) return Rewind.Outcome.Refused(Rewind.Refusal.CODE, Rewind.detailOf(Rewind.codeOf(plan)))
        val prefill = messageText(srv, thread, turn)

        ask(srv, "thread/revert", buildJsonObject {
            put("threadId", thread)
            put("beforeTurnId", turn)
        }, REVERT_TIMEOUT_SECONDS).getOrThrow()

        cutBack(thread, target, turn, refs)

        val (restored, written, detail) = when (plan) {
            is CodeRewind.Plan.Ready -> CodeRewind.apply(plan).fold(
                onSuccess = { Triple(Rewind.Files.RESTORED, it, "") },
                onFailure = { Triple(Rewind.Files.FAILED, emptyList(), it.message.orEmpty()) },
            )
            else -> Triple(Rewind.Files.SKIPPED, emptyList(), "")
        }
        return Rewind.Outcome.Done(conversation = true, prefill = prefill, files = restored, changed = written, filesDetail = detail)
    }

    /**
     * The turn a message this conversation was given began or was written into, by the panel's name for it -
     * what a fork made from that message is cut by (see CodexSessions.originOf). Null for a message read from
     * the history, which the fork finds among the turns by Codex's own name.
     */
    fun turnOf(uuid: String): String? = turnOfMessage[uuid]

    /** The first message the panel sent into [turn] - the one that began it, when it was this conversation's. */
    private fun firstMessageOf(turn: String): String? = synchronized(delivered) { delivered.firstOrNull { turnOfMessage[it] == turn } }

    /**
     * Whether the conversation has moved past what the asking client has seen: a message was given to it after
     * [lastSeen] - the newest one on that client's screen - or, without one, after the [target] itself.
     *
     * Kept here because Codex keeps nothing of the kind: `thread/revert` cuts whatever it is told to. A message
     * a phone sent while the dialog stood open at the desk is the case this is for - cut unread, it would be
     * gone without anybody having seen it.
     */
    private fun movedPast(target: String, lastSeen: String?): Boolean {
        val given = synchronized(delivered) { delivered.toList() }
        if (given.isEmpty()) return false
        val seen = lastSeen ?: target
        val at = given.indexOf(seen)
        // The client's newest message is none of the ones given here, while some were: it has not seen them.
        if (at < 0) return lastSeen != null || target !in given
        return at < given.lastIndex
    }

    /**
     * The turn the message [uuid] began - by the panel's name for it, or by Codex's own for one read off the
     * history. Null when this thread has no such message.
     */
    private fun turnOf(srv: AppServer, thread: String, uuid: String): String? {
        turnOfMessage[uuid]?.let { return it }
        val refs = CodexHistory.turnRefs(thread) { method, params -> ask(srv, method, params).getOrThrow() } ?: return null
        return turnOf(uuid, refs)
    }

    private fun turnOf(uuid: String, refs: List<CodexHistory.TurnRef>): String? =
        turnOfMessage[uuid]?.takeIf { turn -> refs.any { it.id == turn } }
            ?: refs.firstOrNull { it.id == uuid || uuid in it.messages }?.id

    /** Undoing the patches of [turn] and every later turn, worked out against the files as they are now. */
    private fun codePlan(srv: AppServer, thread: String, turn: String): CodeRewind.Plan {
        val turns = turnsFrom(srv, thread, turn)
        return CodeRewind.plan(CodeRewind.changesOf(turns)) { path ->
            java.io.File(path).takeIf { it.isFile }?.readText(Charsets.UTF_8)
        }
    }

    /** [turn] and every turn after it, whole, oldest first. */
    private fun turnsFrom(srv: AppServer, thread: String, turn: String): List<JsonObject> {
        val newestFirst = ArrayList<JsonObject>()
        var cursor: String? = null
        var guard = 0
        while (guard++ < MAX_TURN_PAGES) {
            val result = ask(srv, "thread/turns/list", buildJsonObject {
                put("threadId", thread)
                put("limit", TURNS_PER_PAGE)
                put("sortDirection", "desc")
                put("itemsView", "full")
                cursor?.let { put("cursor", it) }
            }).getOrThrow() as? JsonObject ?: break
            for (element in (result["data"] as? JsonArray).orEmpty()) {
                val one = element as? JsonObject ?: continue
                newestFirst += one
                if (AppServer.text(one["id"]) == turn) return newestFirst.asReversed()
            }
            cursor = AppServer.text(result["nextCursor"]).ifEmpty { null } ?: break
        }
        return newestFirst.asReversed()
    }

    /** The words of the message that began [turn] - what goes back into a phone's field (see Rewind.Outcome). */
    private fun messageText(srv: AppServer, thread: String, turn: String): String {
        val first = turnsFrom(srv, thread, turn).firstOrNull() ?: return ""
        val message = (first["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .firstOrNull { AppServer.text(it["type"]) == "userMessage" } ?: return ""
        return CodexDialect.userTextOf(message).first
    }

    /**
     * The turn running now stopped, and its end waited for - Codex will not revert a thread under a turn. A plan
     * waiting for a decision is set aside the way Stop sets it aside. False when the turn would not end in time.
     */
    private fun stopForRewind(): Boolean {
        if (heldPlanTurn != null) {
            withdrawPlans()
            releasePlanTurn(drain = false)
        }
        if (!busy) return true

        interrupt()
        val deadline = System.currentTimeMillis() + STOP_WAIT_MS
        while (busy && System.currentTimeMillis() < deadline) Thread.sleep(STOP_POLL_MS)
        return !busy
    }

    /**
     * What this side held about the dropped part, made to agree with Codex: the messages given into it are no
     * longer the conversation's, the turn it ends on is the one before the cut, and the thread's file is the
     * new one Codex has just started (see CodexRollout).
     */
    private fun cutBack(thread: String, target: String, turn: String, refs: List<CodexHistory.TurnRef>) {
        val dropped = refs.dropWhile { it.id != turn }.map { it.id }.toSet()
        turnOfMessage.entries.removeIf { it.value in dropped }
        synchronized(delivered) {
            val at = delivered.indexOf(target)
            if (at >= 0) delivered.subList(at, delivered.size).clear() else delivered.clear()
        }
        synchronized(afterTurn) { afterTurn.clear() }
        lastEndedTurn = refs.takeWhile { it.id != turn }.lastOrNull()?.id
        CodexHistory.forget(thread)

        // How full the window is now: the count Codex wrote after the last turn that stays.
        lastTokenUsage = CodexHistory.lastTokenUsage(CodexHistory.transcriptFile(workingDirectory, thread))
        CodexShapes.contextOf(lastTokenUsage)?.let { (used, max) -> onContext(used, max) }
    }

    /** A request answered on this thread - never the one reading [srv]'s answers, or it waits on itself. */
    private fun ask(srv: AppServer, method: String, params: JsonObject, timeoutSeconds: Long = ASK_TIMEOUT_SECONDS): Result<JsonElement> {
        val answer = CompletableFuture<Result<JsonElement>>()
        srv.request(
            method,
            params,
            timeoutSeconds = timeoutSeconds,
            onResult = { answer.complete(Result.success(it)) },
            onError = { answer.complete(Result.failure(RpcFailure(it))) },
        )
        return runCatching { answer.get(timeoutSeconds + 5, TimeUnit.SECONDS) }
            .getOrElse { Result.failure(RpcFailure(AppServer.RpcError(AppServer.TIMED_OUT, "$method timed out"))) }
    }

    /** Codex's refusal of a request, carried through a [Result] with its code - see Rewind.Refusal.ofError. */
    private class RpcFailure(val error: AppServer.RpcError) : Exception(error.message)

    fun wake(): Boolean = server != null || start() != null

    fun restart(): Boolean {
        if (server == null) return false
        stop()
        return start() != null
    }

    fun stop() {
        val srv = server ?: return
        abandonAsides()
        abandonHelpers()
        // A plan left undecided goes with the process; its turn is told to have ended, or the panel would
        // stand on a card nothing can answer any more.
        heldPlanTurn?.let(stream::turnCompleted)
        heldPlanTurn = null
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

    /** The held end of a plan-mode turn, told now - see [heldPlanTurn]. */
    private fun releasePlanTurn(drain: Boolean) {
        val turn = heldPlanTurn ?: return
        heldPlanTurn = null
        stream.turnCompleted(turn)
        endTurn()
        if (drain) drainAfterTurn()
    }

    private fun withdrawPlans() {
        val plans = awaitingPermission.filterValues { it.kind == Kind.PLAN }.keys
        plans.forEach { id -> if (awaitingPermission.remove(id) != null) onPermissionWithdrawn(id) }
    }

    private fun drainAfterTurn() {
        val next = synchronized(afterTurn) {
            if (afterTurn.isEmpty()) null else afterTurn.removeAt(0)
        } ?: return
        busy = true
        onTurnStarted()
        whenOpen { deliver(next) }
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
                // The planning turn ends here, as far as the panel knows; what was waiting for its end waits
                // on behind the turn that follows, if one does.
                val followed = allow || message.isNotBlank()
                releasePlanTurn(drain = !followed)
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

        val epoch = PROCESS_EPOCHS.incrementAndGet()
        lateinit var created: AppServer
        created = AppServer(
            label = "conversation",
            executable = executable,
            workingDirectory = workingDirectory,
            environment = CodexLaunch.environment(environment),
            arguments = CodexLaunch.serverArguments(contextWindow()),
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
        executableStamp = CodexExecutable.stamp(executable)
        startedAt = System.currentTimeMillis()
        open = false
        // The settings every turn of this conversation goes by, read while the thread opens (see
        // CodexConfigDesk.workspaceWrite) - the first turn should not have to go without them.
        CodexConfigDesk.warm(workingDirectory)
        openThreadAfterSkills(created)
        return created
    }

    /**
     * Register `.claude/skills` before the thread is opened, so the model sees the shared shelf on its
     * first turn. Older app-server versions do not have this method; their refusal is a soft fallback,
     * because explicit `/skill` invocation still works through CodexCommandHints.
     */
    private fun openThreadAfterSkills(srv: AppServer) {
        val root = ClaudeProjectConfig.skillsRoot(workingDirectory)
        if (root == null) {
            openThread(srv)
            return
        }

        srv.request(
            "skills/extraRoots/set",
            buildJsonObject { putJsonArray("extraRoots") { add(root) } },
            onResult = { if (server === srv) openThread(srv) },
            onError = { error ->
                DiagnosticsLog.note(
                    DiagnosticsLog.AGENT,
                    "project .claude skills could not be registered with app-server: ${error.message}",
                )
                if (server === srv) openThread(srv)
            },
        )
    }

    private fun exited(process: AppServer, code: Int, requested: Boolean) {
        if (server === process) abandonAsides()
        if (server === process) abandonHelpers()
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
        heldPlanTurn?.let(stream::turnCompleted)
        heldPlanTurn = null

        if (!requested) {
            DiagnosticsLog.note(DiagnosticsLog.AGENT, "codex exited on its own (code $code)")
            onCrashed(code)
            if (wasBusy) onTurnEnded()
        }
        onFinished()
    }

    /**
     * The thread this process holds: the one being continued, a fork of its source through the turn the fork
     * ends on, or a new one - which is also what a fork that carries nothing is.
     *
     * A fork whose origin nobody has worked out yet has it worked out first, off this thread: it is a
     * question to Codex (see CodexSessions.originOf), and the thread this runs on may be the one reading this
     * process's answers.
     */
    private fun openThread(srv: AppServer) {
        if (conversationId == null && !origin.isInitialized()) {
            AppExecutorUtil.getAppExecutorService().execute {
                runCatching { origin.value }.onFailure { thisLogger().warn("Could not work out where a fork begins", it) }
                if (server === srv) openThread(srv)
            }
            return
        }

        resumed = conversationId != null
        // A fork comes up off its source until its own thread exists - afterwards it is a conversation of its
        // own, continued by its own id like any other. Codex writes a fork's file the moment it forks, so
        // there is no fork-without-a-thread to come back to, as there was with Claude Code.
        val fork = if (conversationId == null) runCatching { forkOrigin }.getOrNull() else null
        forkLaunched = fork
        val (method, params) = when {
            conversationId != null -> "thread/resume" to buildJsonObject {
                put("threadId", conversationId)
                threadSettings(this)
                put("excludeTurns", true)
            }
            fork?.at != null -> "thread/fork" to buildJsonObject {
                put("threadId", fork.source)
                put("lastTurnId", fork.at)
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
        put("developerInstructions", ClaudeProjectConfig.appendTo(baseBriefing, workingDirectory))
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

        // A fork's thread is born: what it came from is put down by its id, for the seam and for the tab that
        // brings it back (see ForkBook). Once - the next launch resumes the thread by its id.
        val forked = forkLaunched
        forkLaunched = null
        if (forked != null) {
            if (forked.at != null) hasHistory = true
            onForked(id, forked)
        }

        emit(
            CodexDialect.systemInit(
                threadId = id,
                model = currentModel(),
                cwd = AppServer.text(result?.get("cwd")).ifEmpty { workingDirectory },
                permissionMode = permissionMode ?: PermissionModes.ACCEPT_EDITS,
                slashCommands = CodexCommands.BUILT_IN + CodexSkills.of(workingDirectory).keys,
            ),
        )

        // A new role, or the shared-config contract missing from an older thread, is said before anything
        // else: the turns waiting for the thread are the first ones the instructions apply to.
        val changedRole = roleChange?.takeIf { resumed && !instructionsSaid }
        val refresh = changedRole == null && resumed && !instructionsSaid &&
            ClaudeProjectConfig.needsInjection(workingDirectory, id)
        val instructions = when {
            changedRole != null -> ClaudeProjectConfig.appendTo(changedRole, workingDirectory)
            refresh -> ClaudeProjectConfig.appendTo(baseBriefing, workingDirectory)
            else -> null
        }
        if (instructions != null) {
            instructionsSaid = true
            val introduction = if (changedRole != null) ROLE_CHANGE else INSTRUCTIONS_REFRESH
            val srv = server
            if (srv == null) {
                releaseWaiting()
            } else {
                srv.request(
                    "thread/inject_items",
                    buildJsonObject {
                        put("threadId", id)
                        put("items", buildJsonArray {
                            addJsonObject {
                                put("type", "message")
                                put("role", "developer")
                                putJsonArray("content") {
                                    addJsonObject {
                                        put("type", "input_text")
                                        put("text", "$introduction\n\n$instructions")
                                    }
                                }
                            }
                        })
                    },
                    onResult = { releaseWaiting() },
                    onError = { error ->
                        DiagnosticsLog.note(DiagnosticsLog.AGENT, "codex would not take updated developer instructions")
                        thisLogger().info("Codex did not take updated developer instructions: ${error.message}")
                        releaseWaiting()
                    },
                )
            }
        } else {
            releaseWaiting()
        }

        namedAs = null
        if (forked?.at != null) adoptForkName(id, AppServer.text(thread?.get("name")).trim(), forked.source)
        nameAfterPerson()

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

    /** The thread is ready for turns: everything that waited for it goes now. */
    private fun releaseWaiting() {
        val waiting = synchronized(waitingForThread) {
            open = true
            waitingForThread.toList().also { waitingForThread.clear() }
        }
        waiting.forEach { it() }
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
            val aside = asideThreads[thread]
            val helper = helpers[thread]
            when {
                aside != null -> asideNotification(aside, method, params)
                helper != null -> helperNotification(thread, helper, method, params)
                else -> CodexTitles.notification(method, params)
            }
            return
        }

        when (method) {
            "turn/started" -> {
                val turn = params["turn"] as? JsonObject ?: return
                hasHistory = true
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
                CodexApps.authenticationFailure(item)?.let(onAppAuthRequired)
                noteSpawned(item)
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
                AppServer.text(turn["id"]).takeIf { it.isNotEmpty() }?.let { lastEndedTurn = it }
                if (AppServer.text(turn["status"]) == "completed" && awaitingPermission.values.any { it.kind == Kind.PLAN }) {
                    heldPlanTurn = turn
                    activeTurn = null
                    return
                }
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

            // The echo of a name this process wrote is nothing new. The model's name is the model's. Any other
            // name was given somewhere else - another Codex client, a terminal - and only a person names a
            // thread there (see AutoTitles), so the tab takes it on as one.
            "thread/name/updated" -> AppServer.text(params["threadName"]).trim().takeIf { it.isNotBlank() }?.let { name ->
                when (name) {
                    namedAs -> Unit
                    modelNamed -> onTitle(name)
                    else -> {
                        namedAs = name
                        onRenamed(name)
                    }
                }
            }

            "account/rateLimits/updated" -> (params["rateLimits"] as? JsonObject)?.let { snapshot ->
                // About another bucket than the plan's own: its windows are not the five-hour and weekly
                // ones, and its "reached" is not the plan's. The whole picture is asked again instead, which
                // draws that bucket on a ring of its own (see CodexShapes.usage).
                if (!CodexShapes.isMainBucket(snapshot)) {
                    requestUsage(onUsage = onRateLimits)
                    return
                }
                // A rolling update is sparse: an empty window means "not said this time", not "zero", and
                // passed on it would wipe the rings the last full answer drew.
                if (LIMIT_FIELDS.any { snapshot[it] is JsonObject }) onRateLimits(CodexShapes.usage(snapshot))
                CodexShapes.stoppedWindow(snapshot)?.let { window ->
                    val resets = CodexShapes.resetOf(snapshot)
                    lastLimit = Limit(window, resets, System.currentTimeMillis())
                    emit(CodexDialect.rateLimited(resets, window))
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

    /** An agent this conversation has just spawned starts its work - see [onBackground]. */
    private fun noteSpawned(item: JsonObject) {
        val tell = onBackground ?: return
        if (AppServer.text(item["type"]) != "collabAgentToolCall" || AppServer.text(item["tool"]) != "spawnAgent") return
        if (AppServer.text(item["status"]) != "completed") return

        val callId = AppServer.text(item["id"])
        val threads = ((item["receiverThreadIds"] as? JsonArray).orEmpty().map { AppServer.text(it) } +
            (item["agentsStates"] as? JsonObject)?.keys.orEmpty()).filter { it.isNotEmpty() }.distinct()
        for (thread in threads) {
            if (helpers.containsKey(thread)) continue
            val helper = Helper(callId, AppServer.text(item["prompt"]).take(HELPER_DESCRIPTION_CHARS))
            helpers[thread] = helper
            tell(CodexDialect.taskStarted(thread, callId, helper.description))
        }
    }

    /** What a spawned agent's own thread says: the start and the end of its work, and its last words. */
    private fun helperNotification(thread: String, helper: Helper, method: String, params: JsonObject) {
        val tell = onBackground ?: return
        when (method) {
            "turn/started" -> {
                helper.turn = AppServer.text((params["turn"] as? JsonObject)?.get("id")).ifEmpty { null }
                // Its first turn is told by the spawn itself; a later one is an agent sent back to work.
                if (!helper.working) {
                    helper.working = true
                    tell(CodexDialect.taskStarted(thread, helper.callId, helper.description))
                }
            }

            "item/completed" -> (params["item"] as? JsonObject)?.let { item ->
                if (AppServer.text(item["type"]) == "agentMessage") {
                    AppServer.text(item["text"]).takeIf { it.isNotBlank() }?.let { helper.said = it }
                }
            }

            "turn/completed" -> {
                helper.turn = null
                if (!helper.working) return
                helper.working = false
                val status = when (AppServer.text((params["turn"] as? JsonObject)?.get("status"))) {
                    "completed" -> "completed"
                    "interrupted" -> "stopped"
                    else -> "failed"
                }
                tell(CodexDialect.taskNotification(thread, helper.callId, status, helper.said.trim()))
            }
        }
    }

    /** The process is going, and the agents it spawned with it: each one still at work is told as stopped. */
    private fun abandonHelpers() {
        val tell = onBackground
        for ((thread, helper) in helpers.entries.toList()) {
            if (helper.working) tell?.invoke(CodexDialect.taskNotification(thread, helper.callId, "stopped", ""))
        }
        helpers.clear()
    }

    /** Stop every spawned agent still at work - a scenario's pause stops the card and its helpers together. */
    private fun interruptHelpers() {
        val srv = server ?: return
        for ((thread, helper) in helpers) {
            val turn = helper.turn ?: continue
            srv.request("turn/interrupt", buildJsonObject {
                put("threadId", thread)
                put("turnId", turn)
            })
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
        if (
            PermissionModes.normalize(permissionMode.orEmpty()) == PermissionModes.BYPASS &&
            (pending.kind == Kind.COMMAND || pending.kind == Kind.FILE || pending.kind == Kind.PERMISSIONS)
        ) {
            answerPermission(ours, allow = true)
            return
        }
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
        val (input, ids) = CodexDialect.askInput(asked)

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
        /**
         * What makes our ids for Codex's requests unique - `codex-<epoch>-<id>` (see [serverRequest]). One count
         * for every conversation in the IDE rather than one per conversation: JSON-RPC ids start again at zero
         * in every process, and so did a count of each conversation's own, so the first question of every new
         * conversation was `codex-1-0`. The project's register of waiting cards is keyed by that id alone (see
         * SessionPermissions), and with two conversations waiting at once, Allow pressed on one card - at the
         * desk or on a phone - answered the other one's command (caught taking the listing's pictures: three
         * fresh conversations, the first never moved, the third ran a command nobody had allowed).
         */
        val PROCESS_EPOCHS = AtomicLong(0)

        /** The menu's name for "whatever Codex runs by default" - never sent as a model. */
        const val DEFAULT_MODEL = "default"

        const val THREAD_OPEN_TIMEOUT_SECONDS = 90L
        const val TURN_START_TIMEOUT_SECONDS = 60L
        const val INTERRUPT_TIMEOUT_SECONDS = 20L
        const val MCP_LOGIN_TIMEOUT_SECONDS = 60L
        const val PLAN_FOLLOW_UP_DELAY_MS = 150L

        /** A rewind's requests: the revert rewrites nothing, but a long thread takes a moment to cut. */
        const val ASK_TIMEOUT_SECONDS = 60L
        const val REVERT_TIMEOUT_SECONDS = 90L
        const val TURNS_PER_PAGE = 20
        const val MAX_TURN_PAGES = 500

        /** How long a rewind waits for the turn it stopped to end - see [stopForRewind]. */
        const val STOP_WAIT_MS = 15_000L
        const val STOP_POLL_MS = 100L

        const val NO_PROCESS = "The conversation is not running."

        /** How long a full window heard of is what a refused turn is told with - see [lastLimit]. */
        const val LIMIT_MEMORY_MS = 10L * 60 * 1000

        /** How much of a spawned agent's task names it - see [onBackground]. */
        const val HELPER_DESCRIPTION_CHARS = 200

        /** What a role change is introduced by - see [roleChange]. */
        const val ROLE_CHANGE =
            "Your role in this conversation has changed. These are your instructions from now on, and they replace the earlier ones:"

        /** A bridge added after the thread was first created has to update that thread's durable instructions. */
        const val INSTRUCTIONS_REFRESH =
            "These are the current developer instructions for this conversation. They replace the earlier developer instructions:"

        /** The parts of a limits update that carry figures - see the rolling update in [notification]. */
        val LIMIT_FIELDS = listOf("primary", "secondary", "individualLimit")
    }
}
