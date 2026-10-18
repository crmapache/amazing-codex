package io.github.crmapache.amazingcodex.stats

import com.intellij.openapi.application.ApplicationManager
import io.github.crmapache.amazingcodex.codex.CodexHome
import io.github.crmapache.amazingcodex.codex.CodexTokenUsage
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/**
 * The tokens Codex spent, by the hour, read out of the conversations it writes into the book - what the
 * statistics tab draws a day at a time, and what the "tok" figure by the composer says about today.
 *
 * Codex's files rather than the turns the panel watches, because the turns undercount. A turn's usage is its
 * own thread's: the agents it spawned run threads of their own, and a scenario run's head and cards never pass
 * through a tab at all - while each of them writes its file like any conversation (see CodexTokenUsage).
 *
 * Kept in the book rather than read afresh each time: a first reading goes through every conversation on the
 * machine, and a conversation archived or deleted later takes its file with it (Codex compresses an archived
 * one beyond what the IDE can read) - the book keeps what the files said. After the first, a reading opens only
 * the files changed since the one before (see [SLACK_MS]), which is what the counter by the composer always
 * read anyway.
 */
internal object TranscriptTokens {

    /**
     * How far behind the last reading the next one starts. An answer is written down a moment after the
     * time it carries - a long one by minutes - so a reading that started exactly where the last one
     * ended would miss the hour on the seam. Two days is the window the counter used to read on its own,
     * and costs nothing over it: the files of the last day or two are being written to anyway.
     */
    const val SLACK_MS = 2L * 24 * 60 * 60 * 1000

    /** One reading at a time, for the whole IDE: every open project asks, and they share one book. */
    private val lock = Any()

    /** Readings going on or waiting their turn. */
    private val pending = AtomicInteger()

    /**
     * Whether the transcripts are being read right now. The statistics tab says so on the tokens card and
     * asks again every couple of seconds until it is over: a first reading takes a while, and an empty
     * chart under "no tokens spent" would be telling a person that a day of work cost nothing.
     */
    fun isReading(): Boolean = pending.get() > 0

    /**
     * A first reading of a folder never read before, started in the background - for the statistics tab
     * opened before the counter's round got to it. The round waits for a sign-in (see
     * ProjectUsage.refreshTodayTokens); the history of tokens does not need one.
     *
     * Counted as going on before this returns, so the payload built right after it already says so.
     */
    fun readIfNever(workingDirectory: String?) {
        val root = CodexHome.of(workingDirectory).sessionsDirectory
        if (!root.isDirectory || isReading()) return

        val ledger = StatsLedger.getInstance()
        if (ledger.read { (it.tokens[keyOf(root)]?.readAt ?: 0L) > 0L }) return

        pending.incrementAndGet()
        runCatching {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    readInto(root, ledger, ZoneId.systemDefault(), System.currentTimeMillis())
                } finally {
                    pending.decrementAndGet()
                }
            }
        }.onFailure { pending.decrementAndGet() }
    }

    /**
     * Read what has changed in the transcripts into the book; answer today's tokens across every folder
     * of them. [workingDirectory] says whose conversations: Codex's on this machine, or inside the WSL
     * distribution the project lives in (see CodexHome).
     */
    fun refresh(workingDirectory: String?): Long =
        refresh(CodexHome.of(workingDirectory).sessionsDirectory, StatsLedger.getInstance())

    fun refresh(
        root: File,
        ledger: StatsLedger,
        zone: ZoneId = ZoneId.systemDefault(),
        now: Long = System.currentTimeMillis(),
    ): Long {
        pending.incrementAndGet()
        try {
            return readInto(root, ledger, zone, now)
        } finally {
            pending.decrementAndGet()
        }
    }

    private fun readInto(root: File, ledger: StatsLedger, zone: ZoneId, now: Long): Long {
        val today = LocalDate.ofInstant(Instant.ofEpochMilli(now), zone).toString()
        val todayOf = { snapshot: StatsSnapshot -> snapshot.tokensByDay(zone)[today]?.sum() ?: 0L }
        if (!root.isDirectory) return ledger.read(todayOf)

        val home = keyOf(root)
        synchronized(lock) {
            val readAt = ledger.read { it.tokens[home]?.readAt ?: 0L }
            val since = if (readAt == 0L) 0L else readAt - SLACK_MS
            val read = CodexTokenUsage.read(root, since) ?: return ledger.read(todayOf)

            // Today from the book rather than from the reading: the book holds the larger of every hour,
            // the readings of a second IDE included, and the counter must not read lower than the chart.
            return ledger.update { snapshot ->
                snapshot.tokens.getOrPut(home) { TokenHome() }.record(read, now)
                todayOf(snapshot)
            }
        }
    }

    /** A folder's name in the book: a hash, so the book names no path. */
    fun keyOf(root: File): String = "h-" + StatsCollector.hashOf(root.absolutePath)
}
