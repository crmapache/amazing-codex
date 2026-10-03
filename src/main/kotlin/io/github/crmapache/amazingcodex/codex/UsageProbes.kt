package io.github.crmapache.amazingcodex.codex

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service

/**
 * How often the subscription may be asked about. One register for the whole IDE, because the question is
 * about an ACCOUNT while everything that asks it belongs to a project: the server counts requests per
 * account and starts refusing after a few in a row, and two open projects asking politely on their own
 * schedules add up to one impolite one.
 *
 * The plugin this one grew out of kept a second half here, a judge of whether an answer was really the
 * account's own - Claude Code could answer out of a usage cache the whole machine shared. Codex asks the
 * server for the limits of the credential the process holds, so there is nothing to borrow, and the judge
 * is gone: it threw away honest answers (see ProjectUsage.receiveUsage).
 *
 * Kept in memory: it is about not asking twice in a moment, and after a restart there is nothing left
 * to pace.
 */
@Service(Service.Level.APP)
internal class UsageProbes {

    private val askedAt = HashMap<String, Long>()

    /** The accounts with a question already waiting out the pace - see [hold]. */
    private val waiting = HashSet<String>()

    /**
     * Take the right to ask this account about its usage, or find out how long is left until it comes
     * free. Zero means "ask now, and it is written down"; anything else is milliseconds to wait.
     *
     * [minGapMs] is the caller's own floor rather than a constant here, because the two callers pay
     * different prices: a question into a process that is already up costs nothing, while a question
     * that raises a process of its own costs seconds of one (see ProjectUsage.refreshLimits). What they
     * share is the register: the server counts requests per ACCOUNT, and two open projects asking
     * politely on their own schedules add up to one impolite one.
     */
    @Synchronized
    fun claim(account: String, minGapMs: Long, now: Long = System.currentTimeMillis()): Long {
        val since = now - (askedAt[account] ?: 0L)
        if (since in 0 until minGapMs) return minGapMs - since

        askedAt[account] = now
        return 0
    }

    /**
     * Take the one place for a question about this account that waits out the pace instead of being
     * dropped, or find it taken (false).
     *
     * One place for the whole IDE rather than one per project, because the answer is the whole IDE's
     * (see AccountUsage): the accounts screen asks for every row at once, several projects open at once
     * each ask for their rings, and a question already on its way answers all of them. A place per
     * project queued one question per project behind one another - each of them the moment the previous
     * one freed the pace, a burst of exactly the kind that makes the endpoint refuse.
     *
     * Whoever takes it gives it back with [release] when the wait is over, before asking.
     */
    @Synchronized
    fun hold(account: String): Boolean = waiting.add(account)

    @Synchronized
    fun release(account: String) {
        waiting.remove(account)
    }

    companion object {

        fun getInstance(): UsageProbes = service()

        /**
         * The floor under a question that has to be asked now - a panel opening, a retry, the accounts
         * screen wanting a figure beside every row.
         *
         * Measured against the live endpoint rather than guessed: four questions about one account
         * inside a few seconds were enough for it to start refusing, and the refusal lasted well over a
         * minute. At one question per fifteen seconds nothing refused anything.
         */
        const val URGENT_GAP_MS = 15_000L

    }
}
