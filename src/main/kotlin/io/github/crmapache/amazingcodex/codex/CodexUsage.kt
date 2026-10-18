package io.github.crmapache.amazingcodex.codex

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.abs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parsing the CLI's answer to `get_usage`: the subscription usage windows and the context window's
 * size. It does not matter whether a live conversation or a one-off ping answered - the shape is the
 * same.
 *
 * Apart from the panel, because all the difficulty here is in that shape: a freshly raised process has
 * no limits at all yet, the context window's size lies in a per-model breakdown, and an empty spot
 * arrives as an honest null. The panel is left with folding ready figures into a message upwards.
 */
internal object CodexUsage {

    /** One usage window: the share and when it resets (an empty string means the CLI did not say). */
    data class Window(val percent: Int, val resets: String) {

        /** The reset time parsed, or null: the CLI sends `Z`, and `+00:00`, and nothing at all. */
        val resetsAt: Instant? by lazy {
            if (resets.isBlank()) null else runCatching { OffsetDateTime.parse(resets).toInstant() }.getOrNull()
        }

        /** Whether this share is about the present window: the reset is still ahead, so it is. */
        fun isCurrent(now: Instant): Boolean = resetsAt?.isAfter(now) == true

        /**
         * Whether two shares are about one and the same window - to within minutes, rather than by
         * string. The reset time is fixed, but the routes give it differently: a live conversation
         * rounds to seconds ("20:30:00.000Z"), the server's summary carries microseconds of the moment
         * it answered ("20:30:00.464237+00:00"), and two answers minutes apart differ in them. Compared
         * as strings these would count as different windows, while real windows differ by five hours or
         * a week.
         *
         * Two readers: the memory below, which must not reset itself at every step, and UsageProbes,
         * which recognises one account's window turning up under another account's name.
         */
        fun sameWindowAs(other: Window): Boolean = other.resetsAt?.let(::resetsAround) == true

        /**
         * Whether this window resets at [moment], to within the same minutes as [sameWindowAs]. A limit
         * event names its window's reset in milliseconds rather than as a window, and AccountUsage tells by
         * this which window a refusal it holds was about.
         */
        fun resetsAround(moment: Instant): Boolean {
            val here = resetsAt ?: return false

            return abs(here.toEpochMilli() - moment.toEpochMilli()) <= SAME_WINDOW_TOLERANCE_MS
        }
    }

    /**
     * Spending past the plan, or - on a Codex business seat - the workspace's spending cap for this
     * person, which there is the only limit there is. [resets] is when that budget starts over, ISO-8601,
     * empty when unknown.
     *
     * Only the share is kept - how much of the budget has gone. Whether money is being spent at this very
     * moment the answer does not say: that arrives with the limit events of the stream (see
     * CodexRateLimit), and the two are put together by AccountUsage.
     *
     * [reached] is Codex's own word on the cap (`spendControlReached`), null when it did not say. It is
     * taken over the share both ways: the share is `100 - remainingPercent` in whole percent, so a cap with
     * a few cents left reads as 100%, and a cap the server calls reached may still read as 99%.
     */
    data class Extra(val enabled: Boolean, val percent: Int?, val resets: String = "", val reached: Boolean? = null) {

        /** When the budget starts over, parsed - null when unknown. */
        val resetsAt: Instant? by lazy {
            if (resets.isBlank()) null else runCatching { OffsetDateTime.parse(resets).toInstant() }.getOrNull()
        }

        /** Whether the cap stops the work: Codex's own word when it gave one, the share at a hundred otherwise. */
        val full: Boolean get() = reached ?: ((percent ?: 0) >= 100)

        /** The budget as a window with a share and a reset, for the readers that think in windows. */
        fun asWindow(): Window = Window(percent ?: 0, resets)
    }

    /**
     * A weekly window of one model rather than of the whole subscription - on a plan that has one, the
     * server keeps a separate week for a named model beside the shared one (today Fable). The label is the
     * server's own ("Fable"), exactly what the account page and `/usage` in a terminal write it as, so it
     * is a name to show rather than a word to translate.
     */
    data class ModelWindow(val label: String, val window: Window)

    /**
     * [models] has two different empties, and they are not the same answer: null means the reply said
     * nothing about per-model windows (an answer out of a cache, or a process that is handing over its
     * last response's headers), an empty list means the server was asked and named none. The first must
     * not wipe what is known, the second must.
     *
     * [fromServer] says whether this is a whole answer to `account/rateLimits/read` - the backend asked
     * about now - rather than a rolling `account/rateLimits/updated`. Codex sends the rolling one out of the
     * headers of the turn's model responses, and it is sparse by its own schema ("clients should merge
     * available values into the most recent read"): a figure the process last heard, of an age it does not
     * state, which a process that worked before a one-off reset of the limit repeats from before it. Only
     * the whole answer may take a share down within a window (see [Tracker]). Codex marks neither, so the
     * translation does: CodexShapes.usageAnswer writes the mark, CodexShapes.usage alone never does - read
     * the other way, an answer is a rolling one until it proves otherwise, and a shape that lost the mark
     * would leave the rings only going up within a window rather than taking them down on old figures.
     *
     * [ordinaryUsage] is the whole answer's `ordinaryUsageAllowed`: the backend's permission for the plan's
     * own usage, checked against the account, and the one recovery signal Codex documents - "null means
     * unavailable; clients must not infer recovery from percentages or reset times". Null on a rolling
     * update, which never carries it, and on a Codex that does not send it yet.
     */
    data class Snapshot(
        val session: Window?,
        val week: Window?,
        val contextWindow: Int?,
        val extra: Extra? = null,
        val models: List<ModelWindow>? = null,
        val fromServer: Boolean = false,
        val ordinaryUsage: Boolean? = null,
    ) {
        /**
         * Whether the limits themselves have arrived. Without them the panel asks again: usually it
         * means the process came up but has not yet learned the subscription windows from the server -
         * in a couple of seconds the answer will be complete.
         */
        val hasLimits: Boolean get() = session != null || week != null || extra != null

        /**
         * Whether the answer is frozen. A window whose reset time has already passed means exactly one
         * thing: the process that answered has not gone to the server since the previous window and is
         * repeating a share out of it. Asking it again is useless - for fresh figures the panel goes to
         * the server (see refreshLimits).
         */
        fun isStale(now: Instant = Instant.now()): Boolean =
            listOfNotNull(session, week).any { it.resetsAt != null && !it.isCurrent(now) }
    }

    fun parse(usage: JsonObject): Snapshot {
        val limits = usage.child("rate_limits")

        return Snapshot(
            session = limits?.let { window(it, "five_hour") },
            week = limits?.let { window(it, "seven_day") },
            contextWindow = contextWindow(usage),
            extra = limits?.let { extra(it) },
            models = limits?.let { modelWindows(it) },
            fromServer = limits?.get(FULL_ANSWER)?.jsonPrimitive?.booleanOrNull == true,
            ordinaryUsage = limits?.get(ORDINARY_USAGE)?.jsonPrimitive?.booleanOrNull,
        )
    }

    /**
     * The mark of a whole answer to `account/rateLimits/read` inside `rate_limits` - see [Snapshot.fromServer].
     * Written by CodexShapes.usageAnswer and nowhere else.
     */
    const val FULL_ANSWER = "full_answer"

    /** The whole answer's `ordinaryUsageAllowed`, when it gave one - see [Snapshot.ordinaryUsage]. */
    const val ORDINARY_USAGE = "ordinary_usage_allowed"

    /** Codex's `spendControlReached` beside the spending cap in `extra_usage` - see [Extra.reached]. */
    const val SPEND_REACHED = "reached"

    /**
     * The windows' memory: it brings a stream of dissimilar snapshots down to what is actually
     * happening to the limit.
     *
     * The panel learns one and the same share by two routes, and they disagree. A live conversation
     * hands over the figure from the server's last answer to a model request: that is the freshest one,
     * but it freezes while there are no turns - a process that worked yesterday answers with
     * yesterday's share all day. A one-off ping asks the server for a summary: that one is about now,
     * but it lags by minutes (over five minutes of watching it went 3, 12, 17, 20 per cent in a row).
     * Showing this as it comes means flickering the percentage back and forth, and after a window reset
     * holding the previous window's share on the ring: that is exactly how 99% ended up there on an
     * almost empty window.
     *
     * So a window is recognised by its reset time, and a share is not taken at its word:
     * - the reset has already passed - the snapshot is from a window that no longer exists, and its
     *   share says nothing about the present one; the present one is empty until real data arrives;
     * - the same window (the reset times match) - a larger share is taken from anyone, a smaller one only
     *   from the server itself, and only once it is sure to be newer than the larger one (see [within]);
     * - a window newer than the known one - we start counting afresh, from it.
     *
     * A smaller share used to be nobody's: within a window usage only grows, and a smaller figure was one
     * of the routes lagging. It does not only grow. A one-off reset of the limit (the gift reset the account
     * page offers) empties the window without moving its reset time, and on 16 October the weekly ring held
     * 99% for a day over a window the server had long put at 23%: every answer since was "the same window,
     * a smaller share", in every tab and every tab opened after, until the IDE was restarted.
     *
     * One instance per account for the whole IDE (see AccountUsage): this is the memory of what has
     * already been seen, and every open project's answers are folded into the same one.
     */
    class Tracker {

        /**
         * A window as it is held, and when the server last vouched for its share - null while the share
         * rests on seeded answers alone (see [Snapshot.fromServer]).
         */
        private data class Held(val window: Window, val toldAt: Instant? = null)

        private var session: Held? = null
        private var week: Held? = null

        /** The per-model weekly windows, by the server's label - each folded by the same rule as the week. */
        private var models: Map<String, Held> = emptyMap()

        /**
         * The same snapshot, but with its windows checked against everything seen before.
         *
         * Under a lock: answers arrive now from a conversation, now from a ping - each on its own
         * thread - and without it two simultaneous snapshots would overwrite each other's memory.
         */
        @Synchronized
        fun merge(snapshot: Snapshot, now: Instant = Instant.now()): Snapshot {
            val told = now.takeIf { snapshot.fromServer }
            // The backend's own word that the plan's usage is allowed again lowers a window held at the limit
            // at once (see [within]); only the plan's two windows are what that permission is about.
            val allowed = snapshot.fromServer && snapshot.ordinaryUsage == true
            session = fold(session, snapshot.session, told, now, allowed)
            week = fold(week, snapshot.week, told, now, allowed)
            models = foldModels(snapshot.models, told, now)

            return snapshot.copy(
                session = session?.window,
                week = week?.window,
                models = models.map { ModelWindow(it.key, it.value.window) },
            )
        }

        /**
         * The per-model windows, each through [fold]. An answer that said nothing about them (null) keeps
         * every known one, still passed through [fold] so a window whose reset has gone by drops to zero
         * the same way the shared week does; an answer that named none clears them - the plan no longer
         * has such a week, and a ring for it would be a ring about nothing.
         */
        private fun foldModels(incoming: List<ModelWindow>?, told: Instant?, now: Instant): Map<String, Held> {
            if (incoming != null && incoming.isEmpty()) return emptyMap()

            val named = incoming?.associate { it.label to it.window }.orEmpty()
            val labels = if (incoming == null) models.keys else named.keys

            return labels
                .mapNotNull { label -> fold(models[label], named[label], told, now)?.let { label to it } }
                .toMap()
        }

        /**
         * Everything seen is thrown away: the shares belonged to an account that is no longer signed in.
         *
         * Without this the memory works exactly against us after a switch. A weekly window the new
         * account has not opened yet arrives with no reset time at all, and the rule that saves a frozen
         * answer from wiping a known window - "a share without a window does not override" - keeps the
         * previous account's percentage on the ring for as long as its old reset time is still ahead.
         */
        @Synchronized
        fun forget() {
            session = null
            week = null
            models = emptyMap()
        }

        /**
         * [told] is the moment of this answer if it is a whole one, null if it is a rolling update. [allowed]
         * is that whole answer saying the plan's usage is allowed (see [within]).
         */
        private fun fold(known: Held?, incoming: Window?, told: Instant?, now: Instant, allowed: Boolean = false): Held? {
            // The known window, but only while it is the present one: at the moment of the reset its
            // share stops meaning anything, even if there is no new data.
            val current = known?.takeIf { it.window.isCurrent(now) }

            val incomingAt = incoming?.resetsAt
            if (incomingAt == null) {
                // A zero without a reset time is an honest "the window has not opened yet": that is how
                // a process that has made no requests answers. A share without a window, on the other
                // hand, has nothing to attach to, and must not override what is known.
                return current
                    ?: incoming?.takeIf { it.percent == 0 }?.let { Held(it) }
                    // We knew a window and it has ended: in the new one usage starts from zero.
                    ?: Held(RESET).takeIf { known != null }
            }

            // A snapshot from a window that has already reset: its share is about the past, and about
            // the present window it says exactly one thing - it has started afresh. That is precisely
            // how a frozen answer from a process that worked before the reset arrives.
            if (!incomingAt.isAfter(now)) return current ?: Held(RESET)

            val currentAt = current?.window?.resetsAt ?: return Held(incoming, told)

            return when {
                // One and the same window (see Window.sameWindowAs).
                current.window.sameWindowAs(incoming) -> within(current, incoming, told, now, allowed)
                // A window newer than the known one - the reset happened, we count afresh from it.
                incomingAt.isAfter(currentAt) -> Held(incoming, told)
                // Otherwise the snapshot is a whole window behind: we keep what we already know.
                else -> current
            }
        }

        /**
         * Two shares of one window. A larger one is taken from anyone: usage grows, and the route that saw
         * it first is simply ahead. A smaller one is taken only from a whole answer, and only when it is sure
         * to be about a later moment than the share it replaces:
         * - a rolling update is a figure the process last heard, and a process that worked before a reset of
         *   the limit repeats the share from before it - taken down by that, the ring would go back to 99%
         *   from 23%;
         * - a whole answer may be up to [SERVER_ANSWER_MAX_AGE_MS] old by the time it arrives, the time a read
         *   can be in flight. Two whole answers that reached us further apart than that are in order; closer
         *   together, the smaller one may be the older, and taking it would flicker the ring a percent down
         *   and back up at the end of a turn.
         *
         * So a smaller share from a whole answer waits that long after a whole answer last vouched for the
         * held one. A share that rests on rolling updates alone was vouched for by nobody, and gives way at
         * once.
         *
         * One more way in, [allowed]: the whole answer says the plan's usage is allowed again
         * (`ordinaryUsageAllowed`) while the held share is at the limit. That is the backend telling recovery
         * in so many words, and it is the moment a wait costs most - a ring at 100% over an account the
         * server has just reopened, and the account counted as refusing (see AccountUsage.standing). An
         * answer in flight from before the limit was hit could say the same, but only inside the seconds
         * around that moment, and the next answer puts it back. Below the limit the permission says nothing
         * about which of two answers is the later, and the wait stands - otherwise it would bring the flicker
         * back on every ordinary turn, where the permission is always there.
         */
        private fun within(current: Held, incoming: Window, told: Instant?, now: Instant, allowed: Boolean): Held {
            if (incoming.percent >= current.window.percent) return Held(incoming, told ?: current.toldAt)

            val vouched = current.toldAt
            val newer = told != null &&
                (vouched == null || Duration.between(vouched, now).toMillis() > SERVER_ANSWER_MAX_AGE_MS)
            val reopened = told != null && allowed && current.window.percent >= 100

            // The reset time from the newer answer either way: the same window, written by whoever was last.
            return if (newer || reopened) {
                Held(incoming, told)
            } else {
                Held(incoming.copy(percent = current.window.percent), vouched)
            }
        }
    }

    /**
     * How old a whole answer to `account/rateLimits/read` can be by the time it reaches us - two whole answers
     * that reached us further apart than this are in order, the later one about a later moment.
     *
     * The Claude side waited 75 seconds, for a cache of its CLI: that answered out of a snapshot of the
     * server while it was under a minute old. Codex keeps no such copy. Every read goes to the backend - the
     * protocol states no age on the answer, and the binary's errors for the read are those of a fetch
     * ("failed to fetch codex rate limits", "no snapshots returned"), while the stale-limits wording lives in
     * its terminal's own status screen, which keeps the figures client-side. So the age is how long the read
     * was in flight, and that has a bound of ours: every read, from a live conversation and from a one-off
     * process alike, is an AppServer request, dropped unanswered after [AppServer.DEFAULT_TIMEOUT_SECONDS]
     * (the one-off process is killed at the same thirty seconds in all). An answer that arrived was fetched
     * inside that time; five seconds on top go on reading the pipe and the JSON.
     *
     * Shorter than the Claude side's, and that matters twice: a lower share waits less after the last whole
     * answer (see [within]), and a refusal heard in a stream can be taken back by the first whole answer
     * fetched after it rather than a minute and a quarter on (see AccountUsage.Book.liftRefusals).
     */
    const val SERVER_ANSWER_MAX_AGE_MS = AppServer.DEFAULT_TIMEOUT_SECONDS * 1000 + 5_000L

    /**
     * The window has reset and there is no fresh data yet: usage in the new window is zero, and when it
     * ends will be known with the first request to the model.
     */
    private val RESET = Window(percent = 0, resets = "")

    private const val SAME_WINDOW_TOLERANCE_MS = 2 * 60 * 1000L

    private fun window(limits: JsonObject, name: String): Window? = limits.child(name)?.let(::windowOf)

    /** One window's share and reset, whichever list it came from - the keys are the same everywhere. */
    private fun windowOf(window: JsonObject): Window? {
        val percent = window["utilization"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return null

        return Window(
            percent = percent.toInt(),
            resets = window["resets_at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        )
    }

    /**
     * The per-model weekly windows: `model_scoped`, the list the CLI builds out of the server's own limits
     * (the `weekly_scoped` rows whose model is on its allowlist) - the same rows `/usage` draws as
     * "Current week (Fable)". Absent is null, not an empty list (see [Snapshot.models]).
     *
     * The label goes to the screen, so it is trimmed and capped: it is the server's word, and a row that
     * cannot name its model has nothing to be recognised by, so it is dropped rather than drawn nameless.
     */
    private fun modelWindows(limits: JsonObject): List<ModelWindow>? {
        val rows = limits.items("model_scoped") ?: return null

        return rows.mapNotNull { row ->
            val scoped = row as? JsonObject ?: return@mapNotNull null
            val label = scoped["display_name"]?.jsonPrimitive?.contentOrNull?.trim()?.take(MODEL_LABEL_LIMIT)
            if (label.isNullOrEmpty()) return@mapNotNull null
            val window = windowOf(scoped) ?: return@mapNotNull null
            ModelWindow(label, window)
        }.distinctBy { it.label }
    }

    /** How long a model's label may be on the screen - a name, not a sentence. */
    private const val MODEL_LABEL_LIMIT = 40

    /**
     * The extra usage settings out of the same answer: whether it is allowed at all and how much of its
     * monthly budget has gone. An absent block means the account knows nothing of extra usage - not the
     * same as "allowed but untouched", so null rather than a zero.
     */
    private fun extra(limits: JsonObject): Extra? {
        val extra = limits.child("extra_usage") ?: return null

        return Extra(
            enabled = extra["is_enabled"]?.jsonPrimitive?.booleanOrNull == true,
            percent = extra["utilization"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt(),
            resets = extra["resets_at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            reached = extra[SPEND_REACHED]?.jsonPrimitive?.booleanOrNull,
        )
    }

    /**
     * The context window's size depends on the model: with the large ones it is a million rather than
     * two hundred thousand. We take it from the answer, or the share on the meter would be three times
     * too low.
     */
    private fun contextWindow(usage: JsonObject): Int? {
        val models = usage.child("session")?.child("model_usage") ?: return null

        return models.keys
            .mapNotNull { models.child(it)?.get("contextWindow")?.jsonPrimitive?.contentOrNull?.toIntOrNull() }
            // 0 is cut off along with null: on the webview side there is nowhere to put it - `?? current`
            // does not fire on 0 (it is not nullish), it sticks in the panel's state forever, and the
            // context meter divides by zero.
            .filter { it > 0 }
            .maxOrNull()
    }
}
