package io.github.crmapache.amazingcodex.codex.terminal

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import io.github.crmapache.amazingcodex.codex.CodexExecutable
import io.github.crmapache.amazingcodex.codex.CodexHistory
import io.github.crmapache.amazingcodex.codex.CodexHome
import io.github.crmapache.amazingcodex.codex.CodexLaunch
import io.github.crmapache.amazingcodex.codex.CodexSessionHub
import io.github.crmapache.amazingcodex.codex.HostOs
import io.github.crmapache.amazingcodex.codex.ResumeLine
import io.github.crmapache.amazingcodex.codex.SessionSnapshot
import io.github.crmapache.amazingcodex.codex.accounts.CodexAccounts
import io.github.crmapache.amazingcodex.feedback.DiagnosticsLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A conversation carried on in Codex's own interface, in a terminal of the IDE, and given back to its tab afterwards
 * (from ACC 0.13.64).
 *
 * Asked for in a review of the original (2026-11-24) by somebody coming from JetBrains Air, where an agent runs either
 * as a chat or in its own terminal interface. The panel is the chat; this is the other half, for the same
 * conversation: the person moves it into `codex resume` for whatever Codex does only on its own screens, and comes
 * back to the chat with everything that was done there.
 *
 * **One writer at a time, and that is the whole design.** Codex keeps one writer per thread: `thread/resume` of a
 * thread another live process holds is refused ("thread ... already has an active writer", see CLAUDE.md). So the
 * tab gives its process up before the terminal raises one (closing its input lets the thread go at once - see
 * AppServer.stop), and from then on the tab is HELD: nothing raises a process for it - not a message, not the queue,
 * not a phone, not a scenario, not the MCP screen, not a restored tab being looked at. The one place every process of
 * a tab comes up through is CodexSession.start, and the question is asked there (see [holds]), so a road added
 * tomorrow is held as well. What is written into a held tab waits in its queue and goes once the conversation is back.
 *
 * **The hold ends with the terminal's process, not with a button.** Codex ending - `/exit`, the tab closed, the IDE's
 * own "close session" - is what gives the conversation back; Return to chat in the panel simply closes that tab. A
 * button that released the tab while the terminal still ran would be the two writers again.
 *
 * **Somebody else's terminal is not waited for**, unlike the original's. Claude Code's CLI writes every live process
 * into a register the original reads; Codex keeps none (its daemon's `loaded-threads.json` is a list to reload after
 * its own restart, not a register of writers). A thread held elsewhere is found out the only way there is - Codex
 * refusing to open it - and the feed says where it is (see CodexSession.openRefusal).
 *
 * **What was done in the terminal is played into the feed on the way back** (see
 * CodexSessionHub.terminalReleased): the turns after the last one the feed already holds, the way a conversation
 * opened from the history is played.
 */
internal class TerminalDesk(
    private val project: Project,
    private val hub: CodexSessionHub,
) {

    /**
     * What may stop a conversation from going into the terminal - each a reason the panel words for itself.
     *
     * The panel asks most of these itself before it offers the move (see the tab's menu), so arriving here they
     * are a race: a turn begun by a background task a moment before the press, a phone's message.
     */
    enum class Refusal(val code: String) {
        /** Nothing to carry on: the tab has not been written into yet, or its transcript is gone. */
        NO_CONVERSATION("noConversation"),

        /** A turn is running, or a card waits for the person: killing the process now loses them. */
        BUSY("busy"),

        /**
         * A background subagent or command is alive: it is a child of the process, and goes with it - silently,
         * with its card still turning (see IdleSleep for the same rule and why).
         */
        BACKGROUND("background"),

        /** There is no `codex` to run. */
        NOT_FOUND("notFound"),

        /** The tab's account would not resolve - carrying on would run it as somebody else (see CodexAccounts). */
        ACCOUNT("account"),

        /** This IDE has no terminal, or it would not start. */
        NO_TERMINAL("noTerminal"),
    }

    /** A tab held by our terminal. [tab] is null only for a moment while it is coming up. */
    private class Hold(val conversationId: String) {
        @Volatile
        var tab: TerminalTab? = null
    }

    private val holds = ConcurrentHashMap<String, Hold>()

    /** What the panel draws for [sessionId]: [HERE], or null when the tab is its own. */
    fun stateOf(sessionId: String): String? = holds[sessionId]?.let { HERE }

    /** The held tabs and their states, for a client joining (see CodexSessionHub.attach). */
    fun states(): Map<String, String> = holds.keys.mapNotNull { id -> stateOf(id)?.let { id to it } }.toMap()

    /** Whether the tab's conversation is held by our terminal, so that nothing may be said into it now. */
    fun holding(sessionId: String): Boolean = holds.containsKey(sessionId)

    /**
     * Whether [conversationId] may not be raised in [sessionId] now - asked by CodexSession.start before every launch.
     *
     * A hold over another conversation than the one being raised is a tab that has since taken a different one: it is
     * let go, and the new one answers for itself.
     */
    fun holds(sessionId: String, conversationId: String): Boolean {
        val hold = holds[sessionId] ?: return false
        if (hold.conversationId == conversationId) return true
        holds.remove(sessionId, hold)
        return false
    }

    /**
     * Carry [sessionId]'s conversation on in a terminal. Off the interface thread: the checks read the disk,
     * and taking a process down is not instant.
     */
    fun handOver(sessionId: String) {
        holds[sessionId]?.let { hold ->
            hold.tab?.let { tab -> ApplicationManager.getApplication().invokeLater({ tab.show() }, project.disposed) }
            return
        }

        val conversations = hub.conversations
        val conversationId = conversations.conversationIdOf(sessionId)
            ?.takeIf { CodexHistory.transcriptFile(project.basePath, it) != null }
            ?: return refuse(sessionId, Refusal.NO_CONVERSATION)

        val snapshot = hub.snapshotOf(sessionId)
        if (snapshot.status == SessionSnapshot.STATUS_RUNNING || snapshot.awaitsYou) return refuse(sessionId, Refusal.BUSY)
        if (snapshot.pendingAgents.isNotEmpty() || snapshot.pendingCommands.isNotEmpty()) {
            return refuse(sessionId, Refusal.BACKGROUND)
        }

        val executable = CodexExecutable.find() ?: return refuse(sessionId, Refusal.NOT_FOUND)

        // The tab's own account, handed to the process as a map exactly as the panel's own are - never typed into
        // a shell (see AccountTerminal). A refusal stops the move: carried on, the conversation would run as
        // whoever the ordinary sign-in is.
        val account = CodexAccounts.getInstance().variablesFor(conversations.accountOf(sessionId), project.basePath)
            ?: return refuse(sessionId, Refusal.ACCOUNT)

        val hold = Hold(conversationId)
        if (holds.putIfAbsent(sessionId, hold) != null) return

        // The panel's process goes before the terminal's comes up. Through the idle sweep's own door, asked once
        // more at the last moment: a turn a background task began since the checks above is not killed on its
        // first words (see CodexSessions.sleep).
        if (conversations.isRunning(sessionId) && !conversations.sleep(sessionId) { idleNow(sessionId) }) {
            holds.remove(sessionId, hold)
            return refuse(sessionId, Refusal.BUSY)
        }

        hub.terminalChanged(sessionId)

        val remote = CodexHome.of(project.basePath).remote
        val arguments = CodexLaunch.terminalArguments(
            conversationId = conversationId,
            model = conversations.model(sessionId).orEmpty(),
            effort = conversations.effort(sessionId).orEmpty(),
            permissionMode = conversations.permissionMode(sessionId),
            fast = conversations.fastMode(sessionId),
            contextWindow = conversations.contextWindow(sessionId),
        )
        val command = if (remote) null else launcher(executable) + arguments
        val typed = if (remote) (listOf("codex") + arguments).joinToString(" ", transform = ResumeLine::word) else null
        // The tab's environment for a process of this machine. A distribution's shell is not one: the IDE's own
        // environment is a Windows one, and the account there is always the ordinary sign-in (a drawer cannot be
        // carried into WSL - see CodexAccounts).
        val variables = if (remote) emptyMap() else CodexLaunch.environment(account)

        val title = hub.tabs.tabs().firstOrNull { it.id == sessionId }?.title?.takeIf { it.isNotBlank() }
        val name = title?.let { "Codex: $it" } ?: "Codex"

        ApplicationManager.getApplication().invokeLater({
            val opened = TerminalTab.open(
                project = project,
                parentDisposable = hub,
                title = name,
                command = command,
                typed = typed,
                variables = variables,
                workingDirectory = project.basePath,
                onExit = { release(sessionId, hold) },
            )

            if (opened == null) {
                holds.remove(sessionId, hold)
                hub.terminalChanged(sessionId)
                refuse(sessionId, Refusal.NO_TERMINAL)
                return@invokeLater
            }

            hold.tab = opened
            // The kind of launch, never the conversation or the path: this buffer travels in bug reports.
            DiagnosticsLog.note(DiagnosticsLog.AGENT, "a conversation went into the terminal (${if (remote) "typed" else "process"})")
        }, project.disposed)
    }

    /** Bring the terminal holding [sessionId] to the front - ours only: somebody else's is not ours to raise. */
    fun show(sessionId: String) {
        val tab = holds[sessionId]?.tab ?: return
        ApplicationManager.getApplication().invokeLater({ tab.show() }, project.disposed)
    }

    /** Take the conversation back from our terminal: its tab is closed, and the process ending gives it back (see [release]). */
    fun takeBack(sessionId: String) {
        val tab = holds[sessionId]?.tab ?: return
        ApplicationManager.getApplication().invokeLater({ tab.close() }, project.disposed)
    }

    /**
     * Drop the hold without giving anything back - the tab is gone, or holds another conversation now. The
     * terminal itself is left running: the person may well be working in it.
     */
    fun forget(sessionId: String) {
        holds.remove(sessionId)
    }

    /** The terminal's process has ended - the conversation is the tab's again. */
    private fun release(sessionId: String, hold: Hold) {
        if (!holds.remove(sessionId, hold)) return
        thisLogger().info("A conversation came back from the terminal")
        hub.terminalReleased(sessionId, hold.conversationId)
    }

    /** The idle sweep's rule, asked at the moment the process is taken (see [handOver]). */
    private fun idleNow(sessionId: String): Boolean {
        val snapshot = hub.snapshotOf(sessionId)
        return snapshot.status != SessionSnapshot.STATUS_RUNNING &&
            !snapshot.awaitsYou &&
            snapshot.pendingAgents.isEmpty() &&
            snapshot.pendingCommands.isEmpty()
    }

    private fun refuse(sessionId: String, refusal: Refusal) {
        DiagnosticsLog.note(DiagnosticsLog.AGENT, "a conversation did not go into the terminal: ${refusal.code}")
        hub.terminalRefused(sessionId, refusal.code)
    }

    companion object {
        /** In a terminal this panel opened. */
        const val HERE = "here"

        /**
         * Codex as the terminal's process. On Windows npm installs it as a batch file, and a batch file is not a
         * program: it is run through `cmd.exe /c`, as the platform does for the panel's own processes. Nothing in
         * the arguments can trouble cmd - see CodexLaunch.terminalArguments.
         */
        internal fun launcher(executable: File, windows: Boolean = HostOs.isWindows): List<String> {
            val path = executable.absolutePath
            val batch = path.endsWith(".cmd", ignoreCase = true) || path.endsWith(".bat", ignoreCase = true)
            return if (windows && batch) listOf("cmd.exe", "/c", path) else listOf(path)
        }
    }
}
