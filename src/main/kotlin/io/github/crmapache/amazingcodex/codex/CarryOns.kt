package io.github.crmapache.amazingcodex.codex

import io.github.crmapache.amazingcodex.scenario.LimitRelief
import java.util.concurrent.ConcurrentHashMap

/**
 * The tabs the panel owes a "carry on" to, and why - work that stopped for a reason that was nobody's, and
 * that nobody would come back to start again.
 *
 * Three roads lead here, and on each of them the agent is left standing with nothing to tell it to move:
 *
 * - an account switch interrupted its turn (see CodexSessions.moveTo), and Codex filed the stop as the
 *   person's own (`<turn_aborted>`, "the user interrupted the previous turn on purpose");
 * - the subscription's limit refused it, and the tab waits for the window to reset - or for the person to
 *   move it to an account with room (see CodexSessionHub.noteLimitRefusal);
 * - its process was replaced while background agents were working for it (an account switch, a sign-in
 *   renewed, an MCP server added), and the agents died with the process without a word - the agent goes on
 *   waiting for reports that are never coming. Shut for a tab here, and kept for the merges with the original:
 *   a tab's conversation is told nothing of the agents Codex spawns (CodexSession.onBackground is a scenario
 *   card's alone), so the count this road reads stays empty and it never owes anything.
 *
 * Kept beside the sessions rather than in the hub, because that is where every road out of it already runs:
 * a person's Stop, a kill, a rewind, the tab closed or given another conversation all go through
 * CodexSessions. The message itself and its timing are the hub's (see CodexSessionHub.carryOn), and so is
 * telling the screens about a wait (see [onChange]). The decisions that are only arithmetic over this state - when
 * a limit's wait ends, whether it follows the tab to another account, whether lost agents were still at work - are
 * the functions below, kept apart so that every road through them has a test.
 */
internal class CarryOns(
    /**
     * What is owed for a tab has changed. Only the tab is named: whoever listens reads what is owed NOW (see
     * [of]), because changes come from several threads and a value handed along with each one could arrive after
     * a newer one - a wait dropped a moment ago drawn back on every screen.
     */
    private val onChange: (sessionId: String) -> Unit = {},
) {

    /** Why a tab is owed its "carry on". [wire] is how the screens and the transcript name it. */
    enum class Reason(val wire: String) {
        ACCOUNT_SWITCH("accountSwitch"),
        LIMIT("limit"),
        RESTART("restart"),
    }

    data class Owed(
        val reason: Reason,
        /** Not before this moment, in milliseconds - a limit's reset; null for at once. */
        val notBefore: Long? = null,
        /** The account whose limit is being waited out - LIMIT only. */
        val account: String? = null,
        /** The CLI did not say when the limit resets, and [notBefore] is a guess at when to try. */
        val guess: Boolean = false,
        /** Background agents died on the way, and the words say so. */
        val helpersLost: Boolean = false,
        /** The tab has been moved to another account since the limit stopped it. */
        val moved: Boolean = false,
        /** How many of the panel's own "carry on"s in a row the limit has refused already. */
        val tries: Int = 0,
        /**
         * The person said no to the carry-on ("Don't carry on", Stop), and the panel says nothing when the wait
         * ends. The wait itself stays: what is queued was written to go once the limit lets it (see [cancel]).
         */
        val silent: Boolean = false,
    ) {
        fun isDue(now: Long): Boolean = notBefore == null || notBefore <= now

        /** When the wait ends, while that is still ahead - what wakes the tab. */
        fun dueAt(now: Long): Long? = notBefore?.takeIf { it > now }

        /** The same, as the screens are shown it: a wait the panel will say nothing at the end of is no plan. */
        fun shownAt(now: Long): Long? = if (silent) null else dueAt(now)
    }

    private val owed = ConcurrentHashMap<String, Owed>()

    /**
     * The last "carry on" the panel said in each tab, kept until the person speaks there or a turn goes through -
     * what tells a limit refusing the panel's own message again from a fresh refusal (see [Owed.tries]).
     */
    private val said = ConcurrentHashMap<String, Owed>()

    fun of(sessionId: String): Owed? = owed[sessionId]

    /** Owe [next], folded into whatever is owed already (see [merged]). */
    fun owe(sessionId: String, next: Owed) {
        owed.merge(sessionId, next, ::merged)
        onChange(sessionId)
    }

    /**
     * Put [next] in place of [read], if [read] is still what is owed - null lets it go. False when something
     * changed in between: somebody spoke and dropped it, or another road already timed it anew. Written over that
     * unconditionally, a wait dropped by the person's own message came back and carried on behind it.
     */
    fun replace(sessionId: String, read: Owed, next: Owed?): Boolean {
        val written = if (next == null) owed.remove(sessionId, read) else owed.replace(sessionId, read, next)
        if (written) onChange(sessionId)
        return written
    }

    /**
     * Nothing is owed any more: somebody spoke, cut it back or closed it. And what the panel last said there stops
     * mattering - the next refusal is refusing somebody else's words.
     */
    fun drop(sessionId: String) {
        said.remove(sessionId)
        if (owed.remove(sessionId) != null) onChange(sessionId)
    }

    /**
     * The person said no - "Don't carry on" under the wait, or Stop. The panel's words go; a limit's wait stays,
     * silent, because the queue stands behind it: what is in it was written to go once the limit lets it, and
     * with the wait gone it stood in the queue past the reset until somebody wrote again.
     */
    fun cancel(sessionId: String) {
        said.remove(sessionId)
        owed.computeIfPresent(sessionId) { _, now -> if (now.reason == Reason.LIMIT) now.copy(silent = true) else null }
        // Told whatever happened: the listener reads what stands now, and says nothing when nothing changed (see
        // CodexSessionHub.carryOnChanged).
        onChange(sessionId)
    }

    /** What is owed now, settled - or null when nothing is, or what is owed is not due yet. */
    fun take(sessionId: String, now: Long): Owed? {
        val due = owed[sessionId]?.takeIf { it.isDue(now) } ?: return null
        if (!owed.remove(sessionId, due)) return null

        if (!due.silent) said[sessionId] = due
        onChange(sessionId)
        return due
    }

    /**
     * A turn of this tab went through. Whatever the panel said before it worked, and a limit met later is a fresh
     * one rather than another refusal of the same carry-on.
     */
    fun answered(sessionId: String) {
        said.remove(sessionId)
    }

    /** The tabs whose "carry on" is due at [now] - for the hub's minute round, see CodexSessionHub.carryOnWhatIsDue. */
    fun due(now: Long): List<String> = owed.filterValues { it.isDue(now) }.keys.toList()

    /** The "carry on" the panel said last in this tab, while nobody spoke and nothing went through since - see [said]. */
    fun lastSaid(sessionId: String): Owed? = said[sessionId]

    companion object {
        /**
         * The longest limit a tab waits out to carry on by itself - see [limitWait]. Six hours holds the five-hour
         * window whatever moment it ran out at, and keeps out the weekly ones, which reset in days: by then the tab
         * is old work, and the plan would start it again out of nowhere.
         */
        const val LIMIT_WAIT_MS = 6L * 60 * 60 * 1000

        /** How many of the panel's own "carry on"s in a row a limit may refuse before the tab is left alone. */
        const val LIMIT_TRIES = 3

        /**
         * How long background agents may go unheard and still be taken for work in progress - see [helpersAtWork].
         * Half an hour, the same span after which a conversation is taken for one nobody is using (see IdleSleep).
         */
        const val HELPERS_QUIET_MS = 30L * 60 * 1000

        /**
         * Two reasons for one tab, as one.
         *
         * An account switch's words cover everything - the stop, and the background that did not survive it -
         * so it stands over the rest. A restart under a tab waiting out a limit does not hurry the wait: the
         * limit still refuses, and the words take the lost agents in. A new limit wait replaces an old one -
         * it knows the newer reset - and keeps whatever was lost before it, and the person's "no" too: a turn
         * the CLI started by itself and the limit refused is no reason to say what the person turned down.
         */
        fun merged(old: Owed, new: Owed): Owed = when {
            new.reason == Reason.ACCOUNT_SWITCH -> new
            old.reason == Reason.ACCOUNT_SWITCH -> old
            new.reason == Reason.RESTART -> old.copy(helpersLost = true)
            else -> new.copy(
                helpersLost = new.helpersLost || old.helpersLost,
                silent = new.silent || (old.silent && old.reason == Reason.LIMIT),
            )
        }

        /**
         * The wait a limit's refusal of [account] calls for at [now] - or null when there is to be none.
         *
         * [known] is when the refusal ends, if anything said; unknown, the panel tries a quarter of an hour on and
         * says it is guessing. The slack for the server's clock goes on top, as it does for a scenario run (see
         * LimitRelief). None for a wait over [LIMIT_WAIT_MS], and none after [LIMIT_TRIES] of the panel's own carry-
         * ons refused in a row - [previous] is the one it said last, while nothing went through since.
         */
        fun limitWait(now: Long, known: Long?, previous: Owed?, account: String): Owed? {
            if (known != null && known - now > LIMIT_WAIT_MS) return null

            val tries = previous?.takeIf { it.reason == Reason.LIMIT }?.let { it.tries + 1 } ?: 0
            if (tries >= LIMIT_TRIES) return null

            return Owed(
                Reason.LIMIT,
                notBefore = LimitRelief.retryAt(known, now) + LimitRelief.RESET_SLACK_MS,
                account = account,
                guess = known == null,
                tries = tries,
            )
        }

        /**
         * Whether a refusal of the limit's kind, named [window] the way the limit event names it, ends by itself on
         * a clock - the only kind a tab waits out (see CodexSessionHub.noteLimitRefusal).
         *
         * Codex refuses a turn for two reasons no clock ends: the workspace out of credits (see
         * CodexShapes.CREDITS_REASONS), over when somebody adds credits, and the session's own budget (see
         * CodexErrors.SESSION_BUDGET), over when somebody raises it. Neither says when, and a wait planned over
         * one went at the guess a quarter of an hour on, was refused, and told the agent "the limit has reset" -
         * three times over, about something that does not reset. The spending cap of a workspace seat is not
         * among them: it starts over with its budget, a month on, and [limitWait] declines that wait by its length.
         */
        fun endsOnAClock(window: String): Boolean = window !in UNTIMED

        /** The refusals that end when somebody acts rather than when a window resets - see [endsOnAClock]. */
        private val UNTIMED = CodexShapes.CREDITS_REASONS + CodexErrors.SESSION_BUDGET

        /**
         * A limit's wait, once the tab stands on another account [to] - which the caller has made sure is another
         * subscription, not the same one under another row. At once if [to] is not known to refuse, at the end of
         * its own refusal if that is close, and null - nothing to wait for - if that is days off.
         */
        fun limitAcross(owed: Owed, to: String, refusedUntil: Long?, now: Long): Owed? {
            val moved = owed.copy(account = to, moved = true, guess = false)
            return when {
                refusedUntil == null || refusedUntil <= now -> moved.copy(notBefore = null)
                refusedUntil - now <= LIMIT_WAIT_MS -> moved.copy(notBefore = refusedUntil + LimitRelief.RESET_SLACK_MS)
                else -> null
            }
        }

        /**
         * Whether background agents the snapshot still counts were at work when the process went - and so whether
         * their loss is worth a "carry on". The count can stick: an agent that ended without its report, a task of a
         * kind nobody knew. Counted as work, a tab with such a ghost since the morning was set going in the evening,
         * on an account switch, in a conversation nobody remembered. [lastHeard] is the last sign of the agents or
         * of the tab itself; nothing heard at all is no sign.
         */
        fun helpersAtWork(pending: Boolean, lastHeard: Long, now: Long): Boolean =
            pending && lastHeard > 0 && now - lastHeard < HELPERS_QUIET_MS
    }
}
