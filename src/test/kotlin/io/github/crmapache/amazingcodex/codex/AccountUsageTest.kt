package io.github.crmapache.amazingcodex.codex

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One account's usage, one picture for the whole IDE.
 *
 * What breaks here breaks quietly: a panel whose rings stopped moving looks exactly like a panel on an
 * idle account, and that is how one froze on 5% for forty minutes while the account went to 49%.
 */
class AccountUsageTest {

    private val announcements = ExtraUsageAnnouncements()

    private val published = mutableListOf<JsonObject>()

    private val usage = AccountUsage(
        publish = { published += Json.parseToJsonElement(it).jsonObject },
        announcements = { announcements },
    )

    /** The five-hour window running now - the reset is in the future, or the memory drops the share. */
    private val resets = Instant.now().plus(Duration.ofHours(3)).toString()

    private fun session(percent: Int, resets: String = this.resets, extra: CodexUsage.Extra? = null) =
        CodexUsage.Snapshot(
            session = CodexUsage.Window(percent, resets),
            week = null,
            contextWindow = null,
            extra = extra,
        )

    private fun limitEvent(extraUsage: Boolean, window: String = "five_hour") =
        CodexRateLimit.Verdict(extraUsage = extraUsage, stopped = false, window = window, resetsAt = null)

    private val JsonObject.sessionPercent: Int?
        get() = (this["session"] as? JsonObject)?.get("percent")?.jsonPrimitive?.int

    private val JsonObject.extra: JsonObject? get() = this["extra"] as? JsonObject

    @Test
    fun `an answer is published as the account's picture`() {
        usage.fold("work", session(41))

        val picture = published.single()
        assertEquals("usage", picture["type"]?.jsonPrimitive?.contentOrNull)
        assertEquals("work", picture["account"]?.jsonPrimitive?.contentOrNull)
        assertEquals(41, picture.sessionPercent)
    }

    /**
     * The failure itself. The project allowed to ask learned 56%; another one, answered out of a cache
     * the CLI keeps for up to an hour, heard 5% about the same window. With a memory per project each
     * showed its own - and the one stuck on the cache showed it for as long as it kept losing the right
     * to ask. With one memory the lagging answer is what it is: behind.
     */
    @Test
    fun `a lagging answer from another project does not take the rings back`() {
        usage.fold("", session(56))
        usage.fold("", session(5, resets = Instant.parse(resets).plusMillis(240).toString()))

        assertEquals(56, published.last().sessionPercent)
    }

    @Test
    fun `accounts keep their own pictures`() {
        usage.fold("work", session(56))
        usage.fold("home", session(3))

        assertEquals(3, published.last().sessionPercent)
        assertEquals("home", published.last()["account"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `a panel that opens is handed what the IDE already knows`() {
        usage.fold("work", session(41))

        val handed = usage.pictures().map { Json.parseToJsonElement(it).jsonObject }

        assertEquals(41, handed.single().sessionPercent)
        assertEquals("work", handed.single()["account"]?.jsonPrimitive?.contentOrNull)
    }

    // One project's stream says money is being spent; another project's answer to get_usage brings the
    // month's budget. Kept apart, the second one's picture carried "not spending" and put out the ring the
    // first one had lit.
    @Test
    fun `extra usage is one picture, whichever project brought which half`() {
        usage.fold("work", session(100, extra = CodexUsage.Extra(enabled = true, percent = 30)))
        usage.noteRateLimit("work", limitEvent(extraUsage = true))
        usage.fold("work", session(100, extra = CodexUsage.Extra(enabled = true, percent = 31)))

        val extra = published.last().extra
        assertEquals(true, extra?.get("active")?.jsonPrimitive?.booleanOrNull)
        assertEquals("five_hour", extra?.get("window")?.jsonPrimitive?.contentOrNull)
        assertEquals(31, extra?.get("percent")?.jsonPrimitive?.int)
    }

    @Test
    fun `the first limit event after a launch is a reading, a later switch on is a crossing`() {
        assertFalse(usage.noteRateLimit("work", limitEvent(extraUsage = true)))
        assertFalse(usage.noteRateLimit("work", limitEvent(extraUsage = false)))
        assertTrue(usage.noteRateLimit("work", limitEvent(extraUsage = true)))
    }

    // The CLI repeats the event on every turn while the state holds; a phone across the city is on the
    // other end of every message.
    @Test
    fun `a limit event that changes nothing is not said again`() {
        usage.noteRateLimit("work", limitEvent(extraUsage = true))
        val said = published.size

        assertFalse(usage.noteRateLimit("work", limitEvent(extraUsage = true)))
        assertEquals(said, published.size)
    }

    /**
     * Every open project notices one switch for itself, one after another. Each throwing the figures away
     * again blanked every panel's rings over the answer the first one had already brought.
     */
    @Test
    fun `one switch noticed by every project throws the figures away once`() {
        usage.fold("", session(56))

        assertTrue(usage.forget("", identity = "b@example.com"))
        assertEquals(true, published.last()["reset"]?.jsonPrimitive?.booleanOrNull)

        usage.fold("", session(3))
        val said = published.size

        assertFalse(usage.forget("", identity = "b@example.com"))
        assertEquals(said, published.size)
        assertEquals(3, Json.parseToJsonElement(usage.pictures().single()).jsonObject.sessionPercent)

        // Switching back is a switch.
        assertTrue(usage.forget("", identity = "a@example.com"))
    }

    // An account removed, signed out of or merged into another names nobody: its figures always go.
    @Test
    fun `a forget without an identity always throws the figures away`() {
        usage.fold("work", session(41))

        assertTrue(usage.forget("work"))
        assertTrue(usage.forget("work"))
        assertTrue(usage.pictures().isEmpty())
    }

    @Test
    fun `a forgotten account keeps no extra usage either`() {
        usage.noteRateLimit("work", limitEvent(extraUsage = true))
        usage.forget("work")
        usage.fold("work", session(4))

        assertNull(published.last().extra)
    }

    // --- Whether an account can take work (what a scenario run looks for room by) ----------------------

    @Test
    fun `an account nothing is known about is neither refused nor measured`() {
        assertEquals(AccountUsage.Standing(), usage.standing("work"))
    }

    /** The stop a tab's stream heard is the next run's warning: it must not move onto that account. */
    @Test
    fun `a genuine stop heard in a stream refuses the account until its reset`() {
        val now = System.currentTimeMillis()
        val reset = now + 40 * 60 * 1000L
        usage.noteRateLimit("work", CodexRateLimit.Verdict(extraUsage = false, stopped = true, window = "five_hour", resetsAt = reset))

        assertEquals(reset, usage.standing("work", now).refusedUntil)
        assertNull(usage.standing("work", reset + 1).refusedUntil)
    }

    /**
     * A model's own week refuses that model only. Marked as the whole account's, a tab's Opus week running out
     * held a scenario run on Sonnet off the same account - and its head off the person's words - for days.
     */
    @Test
    fun `a model's own week running out does not refuse the account`() {
        val reset = System.currentTimeMillis() + 3 * 24 * 60 * 60 * 1000L
        usage.noteRateLimit("work", CodexRateLimit.Verdict(extraUsage = false, stopped = true, window = "seven_day_opus", resetsAt = reset))

        assertNull(usage.standing("work").refusedUntil)
    }

    /** Extra usage carries on past the limit: the work is paid for, not stopped, and the account is usable. */
    @Test
    fun `a limit passed on extra usage refuses nothing`() {
        usage.noteRateLimit("work", CodexRateLimit.Verdict(extraUsage = true, stopped = false, window = "five_hour", resetsAt = null))

        assertNull(usage.standing("work").refusedUntil)
    }

    @Test
    fun `a window at a hundred percent refuses until it resets, and the share says how spent the rest are`() {
        usage.fold("work", session(100))
        usage.fold("home", session(37))

        val reset = Instant.parse(resets).toEpochMilli()
        assertEquals(reset, usage.standing("work").refusedUntil)
        assertEquals(100, usage.standing("work").fullest)
        assertNull(usage.standing("home").refusedUntil)
        assertEquals(37, usage.standing("home").fullest)
    }

    /** A refusal whose end nobody said is held until the guess, and a known later reset wins over the guess. */
    @Test
    fun `a refusal without a reset is held until the caller's guess`() {
        val now = System.currentTimeMillis()
        usage.noteRefused("work", now + 15 * 60 * 1000L)
        assertEquals(now + 15 * 60 * 1000L, usage.standing("work", now).refusedUntil)

        usage.noteRefused("work", now + 60 * 1000L)
        assertEquals(now + 15 * 60 * 1000L, usage.standing("work", now).refusedUntil)
    }

    // --- A one-off reset of the limit (the gift reset on the account page) ------------------------------

    /** One limit as Codex reports it: its share and when it resets, in milliseconds here and seconds there. */
    private data class Limit(val percent: Int, val resets: Long)

    private fun windowJson(limit: Limit, minutes: Int) =
        """{"usedPercent":${limit.percent},"windowDurationMins":$minutes,"resetsAt":${limit.resets / 1000}}"""

    /** A Codex limits snapshot: the plan's windows, a workspace's spending cap, and Codex's word on that cap. */
    private fun limitsJson(session: Limit?, week: Limit?, cap: Limit?, reached: Boolean?): String = listOfNotNull(
        session?.let { "\"primary\":" + windowJson(it, 300) },
        week?.let { "\"secondary\":" + windowJson(it, 10080) },
        cap?.let {
            """"individualLimit":{"limit":"100","used":"${it.percent}","remainingPercent":${100 - it.percent},"resetsAt":${it.resets / 1000}}"""
        },
        reached?.let { "\"spendControlReached\":$it" },
    ).joinToString(",", "{", "}")

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    /** A whole answer to `account/rateLimits/read`, through CodexShapes.usageAnswer as every route takes it. */
    private fun read(
        session: Limit? = null,
        week: Limit? = null,
        cap: Limit? = null,
        reached: Boolean? = null,
        ordinary: Boolean? = null,
    ): CodexUsage.Snapshot {
        val permission = ordinary?.let { ",\"ordinaryUsageAllowed\":$it" }.orEmpty()
        return CodexUsage.parse(CodexShapes.usageAnswer(json("""{"rateLimits":${limitsJson(session, week, cap, reached)}$permission}""")))
    }

    /** A rolling `account/rateLimits/updated`, as a conversation passes it on (CodexShapes.usage alone). */
    private fun rolling(session: Limit? = null, week: Limit? = null, cap: Limit? = null, reached: Boolean? = null) =
        CodexUsage.parse(CodexShapes.usage(json(limitsJson(session, week, cap, reached))))

    private fun weeklyStop(resets: Long) =
        CodexRateLimit.Verdict(extraUsage = false, stopped = true, window = "seven_day", resetsAt = resets)

    private fun hoursStop(resets: Long) =
        CodexRateLimit.Verdict(extraUsage = false, stopped = true, window = "five_hour", resetsAt = resets)

    /** A reset as Codex says it - in whole seconds. */
    private fun seconds(ms: Long) = ms / 1000 * 1000

    private val day = 24 * 60 * 60 * 1000L

    /**
     * The week ran out, a tab was refused, and the person reset the limit from the account page. The window's
     * reset time does not move, so without the backend's word the account read as refused for the days left
     * until it: a scenario run waited them out rather than go on it.
     */
    @Test
    fun `a whole answer putting a refused window back under a hundred lifts the refusal`() {
        val now = System.currentTimeMillis()
        val reset = now + 2 * day
        usage.noteRateLimit("work", weeklyStop(reset), now)
        usage.fold("work", read(week = Limit(100, reset)), now)

        val after = now + CodexUsage.SERVER_ANSWER_MAX_AGE_MS + 1
        usage.fold("work", read(week = Limit(0, reset)), after)

        assertNull(usage.standing("work", after).refusedUntil)
        assertEquals(0, usage.standing("work", after).fullest)
    }

    // Inside the time a read can be in flight its answer may have been fetched before the refusal itself.
    @Test
    fun `a whole answer that may predate the refusal lifts nothing`() {
        val now = System.currentTimeMillis()
        val reset = now + 2 * day
        usage.noteRateLimit("work", weeklyStop(reset), now)

        usage.fold("work", read(week = Limit(97, reset)), now + 20_000)

        assertEquals(reset, usage.standing("work", now + 20_000).refusedUntil)
    }

    // A rolling update is a figure the process last heard: the refusal may well be newer than it.
    @Test
    fun `a rolling update lifts nothing`() {
        val now = System.currentTimeMillis()
        val reset = now + 2 * day
        usage.noteRateLimit("work", weeklyStop(reset), now)

        val after = now + 10 * 60 * 1000L
        usage.fold("work", rolling(week = Limit(0, reset)), after)

        assertEquals(reset, usage.standing("work", after).refusedUntil)
    }

    // Room in the week says nothing about the five hours: each refusal is lifted by its own window.
    @Test
    fun `room in one window does not lift the other window's refusal`() {
        val now = System.currentTimeMillis()
        val weekly = now + 2 * day
        val hours = now + 2 * 60 * 60 * 1000L
        usage.noteRateLimit("work", weeklyStop(weekly), now)
        usage.noteRateLimit("work", hoursStop(hours), now)

        val after = now + 10 * 60 * 1000L
        usage.fold("work", read(week = Limit(5, weekly)), after)

        assertEquals(hours, usage.standing("work", after).refusedUntil)
    }

    // --- The backend's word on the plan's usage (`ordinaryUsageAllowed`) --------------------------------

    /**
     * "Clients must not infer recovery from percentages or reset times": the permission is the one signal Codex
     * documents. It takes back every refusal heard before the answer, whichever window it was about, and the shares
     * in whole percent beside it refuse nothing either.
     */
    @Test
    fun `the backend allowing the plan's usage takes every earlier refusal back`() {
        val now = System.currentTimeMillis()
        val weekly = now + 2 * day
        val hours = now + 2 * 60 * 60 * 1000L
        usage.noteRateLimit("work", weeklyStop(weekly), now)
        usage.noteRateLimit("work", hoursStop(hours), now)

        val after = now + CodexUsage.SERVER_ANSWER_MAX_AGE_MS + 1
        usage.fold("work", read(session = Limit(100, hours), week = Limit(100, weekly), ordinary = true), after)

        assertNull(usage.standing("work", after).refusedUntil)
        assertEquals(true, published.last()["ordinaryUsage"]?.jsonPrimitive?.booleanOrNull)
    }

    // The answer may have been fetched before the refusal: the permission it carries is about that moment.
    @Test
    fun `a permission that may predate the refusal takes nothing back`() {
        val now = System.currentTimeMillis()
        val reset = now + 2 * day
        usage.noteRateLimit("work", weeklyStop(reset), now)

        usage.fold("work", read(week = Limit(23, reset), ordinary = true), now + 20_000)

        assertEquals(reset, usage.standing("work", now + 20_000).refusedUntil)
    }

    // A "no" from the backend is not overruled by a share in whole percent: the refusal holds to the reset it named.
    @Test
    fun `the backend refusing the plan's usage holds a refusal whose window reads as open`() {
        val now = System.currentTimeMillis()
        val hours = now + 2 * 60 * 60 * 1000L
        usage.noteRateLimit("work", hoursStop(hours), now)

        val after = now + 10 * 60 * 1000L
        usage.fold("work", read(session = Limit(20, hours), ordinary = false), after)

        assertEquals(hours, usage.standing("work", after).refusedUntil)
        assertNull(usage.standing("work", hours + 1).refusedUntil)
        assertEquals(false, published.last()["ordinaryUsage"]?.jsonPrimitive?.booleanOrNull)
    }

    /**
     * The permission is about the moment of its answer. A rolling update that has put a window at the limit since
     * takes the "yes" back - and says so to the panels, which merge field by field and would otherwise keep it.
     */
    @Test
    fun `a window reaching the limit after the permission takes it back`() {
        val now = System.currentTimeMillis()
        val hours = now + 2 * 60 * 60 * 1000L
        usage.fold("work", read(session = Limit(96, hours), ordinary = true), now)
        assertNull(usage.standing("work", now).refusedUntil)

        usage.fold("work", rolling(session = Limit(100, hours)), now + 60_000)

        assertEquals(seconds(hours), usage.standing("work", now + 60_000).refusedUntil)
        val picture = published.last()
        assertTrue(picture.containsKey("ordinaryUsage"))
        assertNull(picture["ordinaryUsage"]?.jsonPrimitive?.booleanOrNull)
    }

    // --- A business seat: no window of the plan, only the workspace's spending cap -------------------------

    /**
     * The seat's only limit, spent: it refuses every model until its budget starts over. Before, the IDE read only the
     * five-hour and the weekly windows, and a run moved onto such a seat was refused and came back every quarter hour.
     */
    @Test
    fun `a spent spending cap refuses a seat with no window until its budget starts over`() {
        val now = System.currentTimeMillis()
        val month = now + 14 * day
        usage.fold("seat", read(cap = Limit(100, month)), now)

        assertEquals(seconds(month), usage.standing("seat", now).refusedUntil)
        assertEquals(100, usage.standing("seat", now).fullest)
        assertNull(usage.standing("seat", month + 1000).refusedUntil)
    }

    // Measured by its cap alone it is measured all the same: at 12% it carries a night of work.
    @Test
    fun `a seat with room in its cap is measured by it`() {
        val now = System.currentTimeMillis()
        usage.fold("seat", read(cap = Limit(12, now + 14 * day)), now)

        assertNull(usage.standing("seat", now).refusedUntil)
        assertEquals(12, usage.standing("seat", now).fullest)
    }

    // The share is `100 - remainingPercent` in whole percent; `spendControlReached` is taken over it both ways.
    @Test
    fun `Codex's word on the cap is taken over its share`() {
        val now = System.currentTimeMillis()
        val month = now + 14 * day
        usage.fold("a", read(cap = Limit(99, month), reached = true), now)
        usage.fold("b", read(cap = Limit(100, month), reached = false), now)

        assertEquals(seconds(month), usage.standing("a", now).refusedUntil)
        assertNull(usage.standing("b", now).refusedUntil)
        assertEquals(true, published.first()["extra"]?.jsonObject?.get("reached")?.jsonPrimitive?.booleanOrNull)
    }

    /**
     * "None is unavailable, not a sparse-update recovery": a rolling update sends the cap's figures without the
     * flag, and must not open a reached cap. A share that went down since - the owner raised the cap - does.
     */
    @Test
    fun `a rolling update silent about the cap keeps its word while it can still be true`() {
        val now = System.currentTimeMillis()
        val month = now + 14 * day
        usage.fold("seat", read(cap = Limit(99, month), reached = true), now)

        usage.fold("seat", rolling(cap = Limit(99, month)), now + 60_000)
        assertEquals(seconds(month), usage.standing("seat", now + 60_000).refusedUntil)

        usage.fold("seat", rolling(cap = Limit(40, month)), now + 120_000)
        assertNull(usage.standing("seat", now + 120_000).refusedUntil)
    }

    // Beside the plan's windows a cap only bounds the spending past them: it refuses nothing they still allow.
    @Test
    fun `a cap beside the plan's windows refuses nothing they allow`() {
        val now = System.currentTimeMillis()
        usage.fold("team", read(session = Limit(30, now + 3_600_000), week = Limit(40, now + 3 * day), cap = Limit(100, now + 14 * day)), now)

        assertNull(usage.standing("team", now).refusedUntil)
        assertEquals(40, usage.standing("team", now).fullest)
    }

    /**
     * The cap stopping a turn is filed against the seat until the cap starts over - the limit event names the cap
     * as its window (see CodexShapes.resetOf) - and lifted when a later whole answer shows room in it again.
     */
    @Test
    fun `a refusal by the spending cap is filed until its budget starts over, and lifted when the cap has room`() {
        val now = System.currentTimeMillis()
        val month = seconds(now + 14 * day)
        usage.noteRateLimit(
            "seat",
            CodexRateLimit.Verdict(extraUsage = false, stopped = true, window = "workspace_member_usage_limit_reached", resetsAt = month),
            now,
        )
        assertEquals(month, usage.standing("seat", now).refusedUntil)

        val after = now + CodexUsage.SERVER_ANSWER_MAX_AGE_MS + 1
        usage.fold("seat", read(cap = Limit(40, month)), after)

        assertNull(usage.standing("seat", after).refusedUntil)
    }

    // Out of credits is over when somebody adds credits, on no clock: nothing to file it until.
    @Test
    fun `a workspace out of credits files no refusal of its own`() {
        val now = System.currentTimeMillis()
        usage.noteRateLimit(
            "seat",
            CodexRateLimit.Verdict(extraUsage = false, stopped = true, window = "workspace_member_credits_depleted", resetsAt = null),
            now,
        )

        assertNull(usage.standing("seat", now).refusedUntil)
    }

    /** About somebody else's sign-in once it is forgotten - the refusal included. */
    @Test
    fun `a forgotten account is refused no longer`() {
        usage.noteRefused("work", System.currentTimeMillis() + 60 * 60 * 1000L)
        usage.forget("work")

        assertNull(usage.standing("work").refusedUntil)
    }
}
