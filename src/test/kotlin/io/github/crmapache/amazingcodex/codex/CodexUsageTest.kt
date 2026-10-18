package io.github.crmapache.amazingcodex.codex

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class CodexUsageTest {

    private fun parse(line: String) = CodexUsage.parse(Json.parseToJsonElement(line).jsonObject)

    @Test
    fun `the usage windows and the context size are parsed whole`() {
        val snapshot = parse(
            """
            {"session":{"model_usage":{"claude-opus-4":{"contextWindow":200000}}},
             "rate_limits":{
               "five_hour":{"utilization":7,"resets_at":"2026-08-16T01:39:59+00:00"},
               "seven_day":{"utilization":26,"resets_at":"2026-08-20T10:59:59+00:00"}}}
            """.trimIndent(),
        )

        assertEquals(7, snapshot.session?.percent)
        assertEquals("2026-08-16T01:39:59+00:00", snapshot.session?.resets)
        assertEquals(26, snapshot.week?.percent)
        assertEquals(200000, snapshot.contextWindow)
        assertTrue(snapshot.hasLimits)
    }

    // This answer is what used to bring the panel down: there are no limits yet, but the field is in
    // the answer - with an honest null inside. The parsing has to survive it silently.
    @Test
    fun `an answer without limits does not break the parsing, it simply stays empty`() {
        val snapshot = parse("""{"rate_limits":null,"rate_limits_available":false,"session":null}""")

        assertNull(snapshot.session)
        assertNull(snapshot.week)
        assertNull(snapshot.contextWindow)
        // This is exactly what makes the panel ask again in a couple of seconds.
        assertFalse(snapshot.hasLimits)
    }

    @Test
    fun `the extra usage settings are read off the same answer`() {
        val snapshot = parse(
            """
            {"rate_limits":{
               "five_hour":{"utilization":100,"resets_at":"2026-08-16T01:39:59+00:00"},
               "extra_usage":{"is_enabled":true,"monthly_limit":50,"used_credits":11.5,"utilization":23}}}
            """.trimIndent(),
        )

        assertEquals(true, snapshot.extra?.enabled)
        assertEquals(23, snapshot.extra?.percent)
        assertEquals("", snapshot.extra?.resets)
    }

    /** A Codex business seat: no windows, only the workspace's spending cap - with the day it starts over. */
    @Test
    fun `a spending cap keeps the day its budget starts over`() {
        val answer = CodexShapes.usage(
            kotlinx.serialization.json.Json.parseToJsonElement(
                """{"primary":null,"secondary":null,"individualLimit":{"limit":"1000","used":"853.4","remainingPercent":15,"resetsAt":1790812800}}""",
            ) as kotlinx.serialization.json.JsonObject,
        )

        val snapshot = parse(answer.toString())

        assertNull(snapshot.session)
        assertNull(snapshot.week)
        assertEquals(85, snapshot.extra?.percent)
        assertEquals("2026-10-01T00:00:00Z", snapshot.extra?.resets)
        assertTrue(snapshot.hasLimits)
    }

    // An account that knows nothing of extra usage is not the same as one with it allowed and untouched:
    // the ring must not be painted for money nobody is spending.
    @Test
    fun `no extra usage block means nothing is known about it`() {
        assertNull(parse("""{"rate_limits":{"five_hour":{"utilization":7}}}""").extra)
        assertNull(parse("""{"rate_limits":{"five_hour":{"utilization":7},"extra_usage":null}}""").extra)
    }

    // The subscription windows have a truth of their own: the weekly one may arrive before the five-hour one.
    @Test
    fun `an empty window does not get in the neighbour's way`() {
        val snapshot = parse("""{"rate_limits":{"five_hour":null,"seven_day":{"utilization":26}}}""")

        assertNull(snapshot.session)
        assertEquals(26, snapshot.week?.percent)
        // The CLI said nothing about the reset - an empty string rather than an invented time.
        assertEquals("", snapshot.week?.resets)
        assertTrue(snapshot.hasLimits)
    }

    // We take the largest window size: a conversation on a "1M" model would otherwise look overflowing
    // from the first second.
    @Test
    fun `the largest window is taken from the per-model breakdown, and a zero does not count`() {
        val snapshot = parse(
            """
            {"session":{"model_usage":{
              "claude-haiku":{"contextWindow":0},
              "claude-sonnet":{"contextWindow":200000},
              "claude-opus-1m":{"contextWindow":1000000}}}}
            """.trimIndent(),
        )

        assertEquals(1000000, snapshot.contextWindow)
    }

    // The shape recorded off CLI 2.1.273: `model_scoped` beside the shared windows, with the server's own
    // label - what the account page draws as its own weekly bar.
    @Test
    fun `a model's own weekly window is read with its label`() {
        val snapshot = parse(
            """
            {"rate_limits":{
               "seven_day":{"utilization":81,"resets_at":"2026-09-21T00:00:00+00:00"},
               "model_scoped":[{"display_name":"Fable","utilization":58,"resets_at":"2026-09-21T00:00:00.657078+00:00"}]}}
            """.trimIndent(),
        )

        assertEquals(listOf("Fable"), snapshot.models?.map { it.label })
        assertEquals(58, snapshot.models?.single()?.window?.percent)
        assertEquals("2026-09-21T00:00:00.657078+00:00", snapshot.models?.single()?.window?.resets)
    }

    // Two empties, two answers: an absent list is "nothing said" and must not wipe what is known, an
    // empty one is "the plan has none".
    @Test
    fun `an absent model list is not the same as an empty one`() {
        assertNull(parse("""{"rate_limits":{"seven_day":{"utilization":81}}}""").models)
        assertNull(parse("""{"rate_limits":{"model_scoped":null}}""").models)
        assertEquals(emptyList(), parse("""{"rate_limits":{"model_scoped":[]}}""").models)
    }

    // A row that cannot name its model has nothing to be recognised by on the screen.
    @Test
    fun `a model row without a name or a share is dropped`() {
        val snapshot = parse(
            """
            {"rate_limits":{"model_scoped":[
               {"display_name":"  ","utilization":10},
               {"utilization":20},
               {"display_name":"Fable"},
               {"display_name":"Fable 5","utilization":30}]}}
            """.trimIndent(),
        )

        assertEquals(listOf("Fable 5"), snapshot.models?.map { it.label })
    }

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    /**
     * Codex marks neither of its two shapes: a rolling `account/rateLimits/updated` carries the same snapshot as the
     * whole answer to `account/rateLimits/read`. The translation marks the whole answer (CodexShapes.usageAnswer),
     * and only it may take a share down within a window - a rolling update is a figure the process last heard.
     */
    @Test
    fun `a whole answer to the read is told from a rolling update`() {
        val limits = """{"primary":{"usedPercent":23,"windowDurationMins":10080,"resetsAt":1792368000}}"""

        val whole = parse(CodexShapes.usageAnswer(json("""{"rateLimits":$limits,"ordinaryUsageAllowed":true}""")).toString())
        val rolling = parse(CodexShapes.usage(json(limits)).toString())

        assertTrue(whole.fromServer)
        assertEquals(true, whole.ordinaryUsage)
        assertFalse(rolling.fromServer)
        assertNull(rolling.ordinaryUsage)
        assertEquals(23, rolling.week?.percent)
    }

    // "Null means unavailable": a permission the backend did not give is not a "no".
    @Test
    fun `only a permission the backend gave is read`() {
        val limits = """{"primary":{"usedPercent":100,"windowDurationMins":300,"resetsAt":1792368000}}"""

        val refused = parse(CodexShapes.usageAnswer(json("""{"rateLimits":$limits,"ordinaryUsageAllowed":false}""")).toString())
        val unsaid = parse(CodexShapes.usageAnswer(json("""{"rateLimits":$limits,"ordinaryUsageAllowed":null}""")).toString())

        assertEquals(false, refused.ordinaryUsage)
        assertTrue(unsaid.fromServer)
        assertNull(unsaid.ordinaryUsage)
    }

    // The plugin this one grew out of told the two apart by a `limits` list its CLI passed through; Codex has none.
    @Test
    fun `a limits list proves nothing about the answer`() {
        assertFalse(parse("""{"rate_limits":{"seven_day":{"utilization":23},"limits":[]}}""").fromServer)
        assertFalse(parse("""{"rate_limits":null}""").fromServer)
    }

    /** Codex's own word on a business seat's cap, beside its share - `spendControlReached`. */
    @Test
    fun `the spending cap carries Codex's word on it`() {
        val cap = """"individualLimit":{"limit":"100","used":"99.6","remainingPercent":0,"resetsAt":1790812800}"""

        val reached = parse(CodexShapes.usage(json("""{$cap,"spendControlReached":true}""")).toString()).extra
        val open = parse(CodexShapes.usage(json("""{$cap,"spendControlReached":false}""")).toString()).extra
        val unsaid = parse(CodexShapes.usage(json("""{$cap,"spendControlReached":null}""")).toString()).extra

        assertEquals(true, reached?.reached)
        assertTrue(reached!!.full)
        // Taken over the share both ways: the share is in whole percent.
        assertEquals(100, open?.percent)
        assertFalse(open!!.full)
        assertNull(unsaid?.reached)
        assertTrue(unsaid!!.full)
    }

    @Test
    fun `with no per-model breakdown we do not invent a window size`() {
        assertNull(parse("""{"session":{"model_usage":{}}}""").contextWindow)
        assertNull(parse("""{"session":{}}""").contextWindow)
        assertNull(parse("""{}""").contextWindow)
    }
}

class CodexUsageTrackerTest {

    private val now: Instant = Instant.parse("2026-08-20T12:00:00Z")

    private fun window(percent: Int, resets: String) = CodexUsage.Window(percent, resets)

    private fun snapshot(session: CodexUsage.Window? = null, week: CodexUsage.Window? = null) =
        CodexUsage.Snapshot(session = session, week = week, contextWindow = null)

    /** A window as Codex reports it: its share, its length, and its reset in seconds. */
    private fun codexWindow(window: CodexUsage.Window, minutes: Int) =
        """{"usedPercent":${window.percent},"windowDurationMins":$minutes,"resetsAt":${Instant.parse(window.resets).epochSecond}}"""

    private fun codexLimits(session: CodexUsage.Window?, week: CodexUsage.Window?) = listOfNotNull(
        session?.let { "\"primary\":" + codexWindow(it, 300) },
        week?.let { "\"secondary\":" + codexWindow(it, 10080) },
    ).joinToString(",", "{", "}")

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    /**
     * A whole answer to `account/rateLimits/read`, through the translation both routes use for it - a live
     * conversation and a one-off process alike (CodexShapes.usageAnswer).
     */
    private fun read(session: CodexUsage.Window? = null, week: CodexUsage.Window? = null, ordinary: Boolean? = null) =
        CodexUsage.parse(
            CodexShapes.usageAnswer(
                json("""{"rateLimits":${codexLimits(session, week)}${ordinary?.let { ",\"ordinaryUsageAllowed\":$it" }.orEmpty()}}"""),
            ),
        )

    /** A rolling `account/rateLimits/updated`, as a conversation passes it on (CodexShapes.usage alone). */
    private fun rolling(session: CodexUsage.Window? = null, week: CodexUsage.Window? = null) =
        CodexUsage.parse(CodexShapes.usage(json(codexLimits(session, week))))

    /** Past the time a read can be in flight: a whole answer this much later is about a later moment. */
    private val later: Instant = now.plusMillis(CodexUsage.SERVER_ANSWER_MAX_AGE_MS + 1)

    // The wait rests on our own request timeout: an answer that arrived was fetched inside it.
    @Test
    fun `the wait for a lower share covers the time a read can be in flight`() {
        assertTrue(CodexUsage.SERVER_ANSWER_MAX_AGE_MS > AppServer.DEFAULT_TIMEOUT_SECONDS * 1000)
    }

    // The very bug this exists for: for months the panel showed the share of a window that was already
    // gone. A process that worked before the reset goes on answering with its frozen figure - 99% on an
    // almost empty new window.
    @Test
    fun `a share from an already reset window is not shown`() {
        val tracker = CodexUsage.Tracker()

        val merged = tracker.merge(snapshot(session = window(99, "2026-08-20T11:30:00Z")), now)

        assertEquals(0, merged.session?.percent)
        assertEquals("", merged.session?.resets)
    }

    // A rolling update is a figure the process last heard. A smaller share from it is that age, not a roll-back,
    // and the ring must not follow it.
    @Test
    fun `a rolling update does not take a share down within its window`() {
        val tracker = CodexUsage.Tracker()
        val future = "2026-08-20T14:00:00Z"

        tracker.merge(read(session = window(20, future)), now)
        val merged = tracker.merge(rolling(session = window(12, future)), later)

        assertEquals(20, merged.session?.percent)
    }

    /**
     * The bug of 16 October, on the Claude side. A one-off reset of the limit (the gift reset on the account page)
     * empties the week without moving its reset time, so every answer after it is "the same window, a smaller
     * share" - and the ring held 99% for a day while the server said 23%.
     */
    @Test
    fun `a whole answer taking a share down within its window is believed`() {
        val tracker = CodexUsage.Tracker()

        tracker.merge(read(week = window(99, "2026-10-19T00:00:00Z")), now)
        val merged = tracker.merge(read(week = window(23, "2026-10-18T23:59:59Z")), later)

        assertEquals(23, merged.week?.percent)
    }

    /**
     * Two reads in flight at once - a round and a refetch for another bucket - can arrive in the wrong order. Taken
     * as it comes, the older one would drop the ring a percent at the end of a turn and put it back at the next round.
     */
    @Test
    fun `a smaller share inside the time a read is in flight waits`() {
        val tracker = CodexUsage.Tracker()
        val future = "2026-08-20T14:00:00Z"

        tracker.merge(read(session = window(25, future)), now)
        val close = tracker.merge(read(session = window(24, future)), now.plusSeconds(20))
        assertEquals(25, close.session?.percent)

        // Still smaller once that time is behind it: that is the backend's word about now.
        val apart = tracker.merge(read(session = window(24, future)), later)
        assertEquals(24, apart.session?.percent)
    }

    /**
     * A process that worked before the reset repeats the share from before it in a rolling update - and it may lift
     * the ring for a moment, as any larger share does. It must not hold it there: the next whole answer, once it is
     * surely the newer, takes it down again.
     */
    @Test
    fun `a stale rolling share does not stand in the way of a whole answer`() {
        val tracker = CodexUsage.Tracker()
        val week = "2026-10-19T00:00:00Z"

        tracker.merge(read(week = window(23, week)), now)
        assertEquals(99, tracker.merge(rolling(week = window(99, week)), now.plusSeconds(20)).week?.percent)

        assertEquals(23, tracker.merge(read(week = window(23, week)), later).week?.percent)
    }

    // A share only rolling updates have told is vouched for by nobody, and a whole answer replaces it at once.
    @Test
    fun `a share nothing but rolling updates told gives way to a whole answer at once`() {
        val tracker = CodexUsage.Tracker()
        val week = "2026-10-19T00:00:00Z"

        tracker.merge(rolling(week = window(99, week)), now)
        val merged = tracker.merge(read(week = window(23, week)), now.plusSeconds(5))

        assertEquals(23, merged.week?.percent)
    }

    /**
     * `ordinaryUsageAllowed` - the backend saying the plan's usage is allowed again - is recovery in so many words. Over a
     * window held at the limit it is taken at once: waiting out the read's flight there meant a ring at 100% over an
     * account the server had just reopened.
     */
    @Test
    fun `the backend reopening the plan takes a window at the limit down at once`() {
        val tracker = CodexUsage.Tracker()
        val week = "2026-10-19T00:00:00Z"

        tracker.merge(read(week = window(100, week)), now)
        val merged = tracker.merge(read(week = window(23, week), ordinary = true), now.plusSeconds(5))

        assertEquals(23, merged.week?.percent)
    }

    // Below the limit the permission says nothing about which answer is the later - it is there on every turn.
    @Test
    fun `the permission does not hurry a share below the limit`() {
        val tracker = CodexUsage.Tracker()
        val future = "2026-08-20T14:00:00Z"

        tracker.merge(read(session = window(25, future), ordinary = true), now)
        val merged = tracker.merge(read(session = window(24, future), ordinary = true), now.plusSeconds(10))

        assertEquals(25, merged.session?.percent)
    }

    // One and the same window arrives in different formats: a live conversation rounds to seconds, the
    // server's summary carries microseconds. As strings those are different values, while the window is
    // one.
    @Test
    fun `a window is recognised by its reset time rather than by its string`() {
        val tracker = CodexUsage.Tracker()

        tracker.merge(snapshot(session = window(20, "2026-08-20T14:00:00.000Z")), now)
        val merged = tracker.merge(snapshot(session = window(12, "2026-08-20T14:00:00.464237+00:00")), now)

        assertEquals(20, merged.session?.percent)
    }

    // A new window is a new count: the memory must not drag the previous share into it.
    @Test
    fun `with a window change the count starts afresh`() {
        val tracker = CodexUsage.Tracker()

        tracker.merge(snapshot(session = window(90, "2026-08-20T14:00:00Z")), now)
        val merged = tracker.merge(snapshot(session = window(4, "2026-08-20T19:00:00Z")), now)

        assertEquals(4, merged.session?.percent)
        assertEquals("2026-08-20T19:00:00Z", merged.session?.resets)
    }

    // A lagging route may bring a snapshot of the previous window after the new one has arrived: we
    // keep the new one rather than roll back to the good old one.
    @Test
    fun `a snapshot of the previous window does not override the present one`() {
        val tracker = CodexUsage.Tracker()

        tracker.merge(snapshot(session = window(4, "2026-08-20T19:00:00Z")), now)
        val merged = tracker.merge(snapshot(session = window(90, "2026-08-20T13:00:00Z")), now)

        assertEquals(4, merged.session?.percent)
        assertEquals("2026-08-20T19:00:00Z", merged.session?.resets)
    }

    // That is how a process that has made no requests yet answers: the window is not open, and a zero
    // in it is the truth rather than "no data".
    @Test
    fun `a zero without a reset time is an honest empty window`() {
        val tracker = CodexUsage.Tracker()

        val merged = tracker.merge(snapshot(session = window(0, "")), now)

        assertEquals(0, merged.session?.percent)
    }

    // A share without a window, on the other hand, has nothing to attach to - we do not override a known window with it.
    @Test
    fun `a share without a reset time does not override a known window`() {
        val tracker = CodexUsage.Tracker()
        val future = "2026-08-20T14:00:00Z"

        tracker.merge(snapshot(session = window(20, future)), now)
        val merged = tracker.merge(snapshot(session = window(77, "")), now)

        assertEquals(20, merged.session?.percent)
        assertEquals(future, merged.session?.resets)
    }

    // An answer with no limits at all (that happens with a freshly raised process) must neither invent
    // windows nor forget the known ones.
    @Test
    fun `an empty answer invents no windows and forgets no known ones`() {
        val tracker = CodexUsage.Tracker()

        assertNull(tracker.merge(snapshot(), now).session)

        tracker.merge(snapshot(session = window(20, "2026-08-20T14:00:00Z")), now)
        assertEquals(20, tracker.merge(snapshot(), now).session?.percent)
    }

    // The windows are independent: the weekly one lives by its week, the five-hour one by its hours.
    @Test
    fun `the windows are counted separately`() {
        val tracker = CodexUsage.Tracker()

        val merged = tracker.merge(
            snapshot(
                session = window(99, "2026-08-20T11:00:00Z"),
                week = window(52, "2026-08-25T03:00:00Z"),
            ),
            now,
        )

        assertEquals(0, merged.session?.percent)
        assertEquals(52, merged.week?.percent)
    }

    /**
     * The account has been switched, and this is the shape the panel used to get it wrong in: the new
     * account's weekly window has not opened yet, so its answer names no weekly window at all - and the
     * rule that saves a known window from a silent answer kept the previous account's percentage beside
     * the new account's five-hour one.
     */
    @Test
    fun `after a switch of account nothing of the previous one is left on the rings`() {
        val tracker = CodexUsage.Tracker()

        tracker.merge(
            snapshot(
                session = window(10, "2026-08-20T14:00:00Z"),
                week = window(18, "2026-08-25T03:00:00Z"),
            ),
            now,
        )

        tracker.forget()

        // The new account: a five-hour window of its own, and not a word about the week.
        val merged = tracker.merge(snapshot(session = window(3, "2026-08-20T16:00:00Z")), now)

        assertEquals(3, merged.session?.percent)
        assertNull(merged.week)
    }

    // Time passes without new data too: the window has ended, and the share is no longer about now.
    @Test
    fun `a known window stops being shown once its reset time has passed`() {
        val tracker = CodexUsage.Tracker()
        val resets = "2026-08-20T14:00:00Z"

        tracker.merge(snapshot(session = window(88, resets)), now)
        val later = tracker.merge(snapshot(session = window(88, resets)), Instant.parse("2026-08-20T14:30:00Z"))

        assertEquals(0, later.session?.percent)
        assertEquals("", later.session?.resets)
    }

    private fun fable(percent: Int, resets: String) = listOf(CodexUsage.ModelWindow("Fable", window(percent, resets)))

    /** A whole answer with another bucket beside the plan's - a ring of its own (see CodexShapes.usage). */
    private fun readBucket(percent: Int, resets: String) = CodexUsage.parse(
        CodexShapes.usageAnswer(
            json(
                """{"rateLimits":{"limitId":"codex"},"rateLimitsByLimitId":{"premium":{"limitId":"premium",
                   "secondary":{"usedPercent":$percent,"windowDurationMins":10080,"resetsAt":${Instant.parse(resets).epochSecond}}}}}""",
            ),
        ),
    )

    // Another bucket's week is a week like any other: two reads disagree about it the same way, and the backend
    // resets it the same way. A rolling update never names it (it is about one bucket), so it keeps what is known.
    @Test
    fun `another bucket's window goes down within itself only on a later whole answer`() {
        val tracker = CodexUsage.Tracker()
        val future = "2026-08-24T00:00:00Z"

        tracker.merge(readBucket(58, future), now)
        assertEquals(58, tracker.merge(readBucket(41, future), now.plusSeconds(20)).models?.single()?.window?.percent)
        assertEquals(58, tracker.merge(rolling(session = window(3, "2026-08-20T16:00:00Z")), later).models?.single()?.window?.percent)

        val apart = tracker.merge(readBucket(3, future), later)
        assertEquals("Premium", apart.models?.single()?.label)
        assertEquals(3, apart.models?.single()?.window?.percent)
    }

    // A live conversation hands over the last response's headers and says nothing about per-model
    // windows: that must not take the ring away.
    @Test
    fun `an answer silent about models keeps the known ones`() {
        val tracker = CodexUsage.Tracker()

        tracker.merge(snapshot().copy(models = fable(58, "2026-08-24T00:00:00Z")), now)
        val merged = tracker.merge(snapshot(session = window(3, "2026-08-20T16:00:00Z")), now)

        assertEquals(58, merged.models?.single()?.window?.percent)
    }

    // "The server named none" is news: the plan has no such week any more.
    @Test
    fun `an empty model list clears the known ones`() {
        val tracker = CodexUsage.Tracker()

        tracker.merge(snapshot().copy(models = fable(58, "2026-08-24T00:00:00Z")), now)
        val merged = tracker.merge(snapshot().copy(models = emptyList()), now)

        assertEquals(emptyList(), merged.models)
    }

    // The same rule as the shared week: a window whose reset has gone by says nothing about now.
    @Test
    fun `a model's window drops to zero once its reset has passed`() {
        val tracker = CodexUsage.Tracker()

        tracker.merge(snapshot().copy(models = fable(90, "2026-08-20T14:00:00Z")), now)
        val later = tracker.merge(snapshot(), Instant.parse("2026-08-20T14:30:00Z"))

        assertEquals(0, later.models?.single()?.window?.percent)
    }

    // The memory belongs to the account: a switch forgets the model's week along with the rest.
    @Test
    fun `forgetting takes the model windows too`() {
        val tracker = CodexUsage.Tracker()

        tracker.merge(snapshot().copy(models = fable(58, "2026-08-24T00:00:00Z")), now)
        tracker.forget()

        assertEquals(emptyList(), tracker.merge(snapshot(), now).models)
    }
}
