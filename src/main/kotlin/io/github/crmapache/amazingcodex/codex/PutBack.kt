package io.github.crmapache.amazingcodex.codex

import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Files the person put back since the agent last heard about it - a row of a turn's list reverted or brought back
 * (see CodexSessionHub.revertChanges), the code alone rewound to an earlier message (see CodexSession.rewind) - and the
 * words the agent is told about them before its next turn (see CodexSession.startTurn).
 *
 * Claude Code needs none of this: it notices by itself that a file it read has changed outside, and tells the model at
 * its next step. Codex does not: nothing in its protocol says a file changed under a thread, and the agent goes on by
 * the text it remembers - its next patch does not apply, or lands on a file that no longer holds its lines. So the
 * panel says it, as a developer message put into the thread (`thread/inject_items`, the road a role change takes),
 * right before the turn that would otherwise not know.
 *
 * Kept per tab rather than per process (see CodexSessions): a move to another account replaces the process between the
 * revert and the next message, and the note has to reach whichever process says that message.
 */
internal class PutBack {

    enum class Way { REVERTED, RESTORED, REWOUND }

    /** What became of one file: the last word on it, and where it stood before the turn moved it, if it did. */
    private data class Entry(val way: Way, val origin: String?)

    /** The last word on each file by its path in the turn's list, in the order the files were first put back. */
    private val files = LinkedHashMap<String, Entry>()

    /** Presses on a turn's list sent to the IDE and not answered yet - see [pressed]. */
    private var underWay = 0

    /** Turns held for those presses - see [afterPresses]. */
    private val waiting = ArrayList<() -> Unit>()

    /**
     * A revert or a bring-back is on its way through the IDE (see CodexSessionHub.revertChanges). It is noted only
     * once the IDE has answered, and that can take seconds: the IDE puts a file back on its event thread, which at the
     * end of a turn is busy taking in what the agent wrote. A message sent meanwhile - the revert pressed as the turn
     * ended, the next words right after - would start its turn before the note, and the agent would go on believing
     * the file is as it left it (caught in the sandbox).
     */
    @Synchronized
    fun pressed() {
        underWay++
    }

    /** One press answered, its files noted already; the turns held for the presses go once none is left. */
    fun answered() {
        val ready = synchronized(this) {
            underWay = (underWay - 1).coerceAtLeast(0)
            if (underWay > 0) return
            waiting.toList().also { waiting.clear() }
        }
        ready.forEach { it() }
    }

    /**
     * True when no press is under way, and the turn goes now. Otherwise [then] runs once the IDE has answered every
     * press, or after [patienceMs] whatever happened - a revert stuck behind a dialog of the IDE's must not hold the
     * person's message for good, and its note then goes with the turn after - and the answer is false. Never blocks: a
     * message can arrive on any thread, the event thread included, and the revert it waits for needs that thread.
     */
    fun afterPresses(patienceMs: Long = PRESS_PATIENCE_MS, then: () -> Unit): Boolean {
        val once = AtomicBoolean(false)
        val run = { if (once.compareAndSet(false, true)) then() }
        synchronized(this) {
            if (underWay == 0) return true
            waiting += run
        }
        AppExecutorUtil.getAppScheduledExecutorService().schedule(
            {
                synchronized(this) { waiting.remove(run) }
                run()
            },
            patienceMs,
            TimeUnit.MILLISECONDS,
        )
        return false
    }

    /**
     * [path] was put back [way]. [origin] is where a moved file stood before the turn moved it to [path]. A file
     * reverted and brought back again before the agent heard of either is as the agent left it: nothing to say.
     */
    @Synchronized
    fun note(path: String, way: Way, origin: String? = null) {
        val before = files.remove(path)?.way
        if (way == Way.RESTORED && before == Way.REVERTED) return
        files[path] = Entry(way, origin?.takeIf { it != path })
    }

    /** Nothing more to say: the tab's conversation began afresh (`/clear`), and its new agent never saw these files. */
    @Synchronized
    fun forget() = files.clear()

    /**
     * The words for the agent about everything noted since the last time, the paths said from [workingDirectory] - or
     * null when there is nothing to say. Taken: the turn after this one hears nothing more about the same files.
     */
    @Synchronized
    fun take(workingDirectory: String): String? {
        if (files.isEmpty()) return null
        val noted = files.entries.map { (path, entry) -> entry.way to line(path, entry, workingDirectory) }
        files.clear()
        return words(noted)
    }

    companion object {
        /** How long a turn waits for presses still on their way - see [afterPresses]. */
        const val PRESS_PATIENCE_MS = 20_000L

        /** What opens the note: whose doing it is, and why it matters to the agent now. */
        const val INTRODUCTION =
            "Note from the IDE: since your last turn the person changed some files you worked on, from the panel - not you, " +
                "and not with a command. They are not as you left them, so read them again before you rely on their contents " +
                "or patch them."

        private const val REVERTED_HEAD =
            "Your changes to these files were undone - each is back to how it stood before the turn that changed it " +
                "(a file you created is gone, a file you deleted is back, a file you moved is back where it was):"

        private const val RESTORED_HEAD = "Your changes to these files were brought back - they are again as you left them:"

        private const val REWOUND_HEAD =
            "The code was rewound to an earlier message of this conversation - these files are back to how they stood then:"

        /** The note itself, the files grouped by what happened to them. */
        internal fun words(noted: List<Pair<Way, String>>): String = buildString {
            append(INTRODUCTION)
            for ((way, head) in listOf(Way.REVERTED to REVERTED_HEAD, Way.RESTORED to RESTORED_HEAD, Way.REWOUND to REWOUND_HEAD)) {
                val lines = noted.filter { it.first == way }.map { it.second }
                if (lines.isEmpty()) continue
                append("\n\n").append(head)
                lines.forEach { append("\n- ").append(it) }
            }
        }

        /** One file as the note names it - a moved one by where it stands now and where it was. */
        private fun line(path: String, entry: Entry, workingDirectory: String): String {
            val origin = entry.origin ?: return relative(path, workingDirectory)
            return when (entry.way) {
                Way.REVERTED -> "${relative(origin, workingDirectory)} (moved back from ${relative(path, workingDirectory)})"
                else -> "${relative(path, workingDirectory)} (moved again from ${relative(origin, workingDirectory)})"
            }
        }

        /** [path] from [workingDirectory] when it lies inside it - the way the agent names its files itself. */
        internal fun relative(path: String, workingDirectory: String): String {
            val root = workingDirectory.trimEnd('/', '\\')
            if (root.isEmpty()) return path
            return if (path.startsWith("$root/") || path.startsWith("$root\\")) path.substring(root.length + 1) else path
        }
    }
}
