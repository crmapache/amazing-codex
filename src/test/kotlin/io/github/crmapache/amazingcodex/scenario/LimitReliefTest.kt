package io.github.crmapache.amazingcodex.scenario

import io.github.crmapache.amazingcodex.codex.AccountUsage
import io.github.crmapache.amazingcodex.codex.CodexRateLimit
import io.github.crmapache.amazingcodex.codex.CodexShapes
import io.github.crmapache.amazingcodex.codex.CodexUsage
import io.github.crmapache.amazingcodex.codex.ExtraUsageAnnouncements
import io.github.crmapache.amazingcodex.scenario.LimitRelief.Candidate
import io.github.crmapache.amazingcodex.scenario.LimitRelief.Way
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Where a run goes on when its account's limit refuses it.
 *
 * Both ways of getting this wrong cost a night. A run that waits beside an account with room is the
 * evening of 8 October again: the five-hour window of one account ran out, two more stood idle, and the run
 * stopped until somebody came back to it. A run that moves onto an account that cannot take it is refused
 * again at once - or, on an account without its model, dies on its first message.
 */
class LimitReliefTest {

    private val now = 1_800_000_000_000L
    private val hour = 60 * 60 * 1000L

    private fun refused(until: Long, fullest: Int? = null) = AccountUsage.Standing(refusedUntil = until, fullest = fullest)

    private fun measured(percent: Int) = AccountUsage.Standing(fullest = percent)

    @Test
    fun `a refused run goes to the least spent account it is known to run on`() {
        val way = LimitRelief.choose(
            here = "gmail",
            chosen = "gmail",
            candidates = listOf(
                Candidate("gmail", refused(now + hour)),
                Candidate("proton", measured(58), runs = true),
                Candidate("work", measured(12), runs = true),
            ),
            now = now,
        )

        assertEquals(Way.Go("work"), way)
    }

    /** A run that borrowed an account goes home the moment home has room, however spent home is. */
    @Test
    fun `the account the person works on comes before any other with room`() {
        val way = LimitRelief.choose(
            here = "work",
            chosen = "proton",
            candidates = listOf(
                Candidate("work", refused(now + hour)),
                Candidate("proton", measured(80), runs = true),
                Candidate("gmail", measured(3), runs = true),
            ),
            now = now,
        )

        assertEquals(Way.Go("proton"), way)
    }

    /** After a wait, carrying on where it stood is no move at all. */
    @Test
    fun `the account the run is on is kept once its refusal is over`() {
        val way = LimitRelief.choose(
            here = "gmail",
            chosen = "proton",
            candidates = listOf(
                Candidate("gmail", refused(now - 1000)),
                Candidate("proton", measured(10), runs = true),
            ),
            now = now,
        )

        assertEquals(Way.Go("gmail"), way)
    }

    /**
     * The CLI's own sign-in and an added row holding the same account are one subscription: its figures may
     * have been filed under either, and moving onto the twin is being refused again a second later.
     */
    @Test
    fun `the same subscription under another row is refused with it`() {
        val way = LimitRelief.choose(
            here = "gmail",
            chosen = "gmail",
            candidates = listOf(
                Candidate("gmail", refused(now + hour)),
                Candidate("", measured(4), twinOfHere = true, runs = true),
                Candidate("work", measured(70), runs = true),
            ),
            now = now,
        )

        assertEquals(Way.Go("work"), way)
    }

    @Test
    fun `an account that cannot run the run's models is never a way on`() {
        val way = LimitRelief.choose(
            here = "gmail",
            chosen = "gmail",
            candidates = listOf(
                Candidate("gmail", refused(now + hour)),
                Candidate("pro", measured(1), runs = false),
            ),
            now = now,
        )

        assertEquals(Way.Wait(now + hour), way)
    }

    /** Known to run the models beats not known; an unmeasured account is still tried rather than waited out. */
    @Test
    fun `an account nothing is known about is tried after the known ones`() {
        val candidates = listOf(
            Candidate("gmail", refused(now + hour)),
            Candidate("fresh"),
            Candidate("work", measured(90), runs = true),
        )
        assertEquals(Way.Go("work"), LimitRelief.choose("gmail", "gmail", candidates, now))

        val onlyUnknown = listOf(Candidate("gmail", refused(now + hour)), Candidate("fresh"))
        assertEquals(Way.Go("fresh"), LimitRelief.choose("gmail", "gmail", onlyUnknown, now))
    }

    @Test
    fun `with no room anywhere the run waits for the first refusal to end`() {
        val way = LimitRelief.choose(
            here = "gmail",
            chosen = "gmail",
            candidates = listOf(
                Candidate("gmail", refused(now + 3 * hour)),
                Candidate("proton", refused(now + hour), runs = true),
            ),
            now = now,
        )

        assertEquals(Way.Wait(now + hour), way)
    }

    /** Nothing it could run on says when it frees up: no reason to ask every minute, nor to give up. */
    @Test
    fun `a wait with nothing to go by looks again after a quarter of an hour`() {
        val way = LimitRelief.choose(
            here = "gmail",
            chosen = "gmail",
            candidates = listOf(Candidate("pro", refused(now + hour), runs = false)),
            now = now,
        )

        assertEquals(Way.Wait(now + LimitRelief.UNKNOWN_RESET_MS), way)
    }

    @Test
    fun `a new choice of account is always followed`() {
        assertEquals(true, LimitRelief.follows(to = "work", lastFollowed = "gmail", borrowed = false, refused = true))
    }

    /**
     * Select pressed on the row already chosen, or another account forgotten: the same choice broadcast again.
     * A run that borrowed an account is not dragged back onto the one still refusing it.
     */
    @Test
    fun `the same choice said again moves a borrowed run home only when home has room`() {
        assertEquals(false, LimitRelief.follows(to = "gmail", lastFollowed = "gmail", borrowed = true, refused = true))
        assertEquals(true, LimitRelief.follows(to = "gmail", lastFollowed = "gmail", borrowed = true, refused = false))
        assertEquals(false, LimitRelief.follows(to = "gmail", lastFollowed = "gmail", borrowed = false, refused = false))
    }

    /** A reset a few seconds overdue on our clock is not a reason to ask every second. */
    @Test
    fun `a wait is never shorter than a minute`() {
        val way = LimitRelief.choose(
            here = "gmail",
            chosen = "gmail",
            candidates = listOf(Candidate("gmail", refused(now + 5_000))),
            now = now,
        )

        assertEquals(Way.Wait(now + LimitRelief.MIN_WAIT_MS), way)
    }

    // --- Business seats: no window of the plan, only the workspace's spending cap -------------------------

    private val day = 24 * hour

    /** The IDE's memory of the accounts, fed the way Codex speaks - see AccountUsage. */
    private val usage = AccountUsage(publish = {}, announcements = { ExtraUsageAnnouncements() })

    /** A whole answer to the read about a seat whose only limit is its cap, at [percent] until [resets]. */
    private fun seat(account: String, percent: Int, resets: Long) = usage.fold(
        account,
        CodexUsage.parse(
            CodexShapes.usageAnswer(
                Json.parseToJsonElement(
                    """{"rateLimits":{"individualLimit":{"limit":"100","used":"$percent",
                       "remainingPercent":${100 - percent},"resetsAt":${resets / 1000}}}}""",
                ).jsonObject,
            ),
        ),
        now,
    )

    private fun candidate(id: String) = Candidate(id, usage.standing(id, now), runs = true)

    /**
     * The loop this was written against. The cap's reset was unknown, so a refused seat read as free a quarter of an
     * hour after its refusal: the run went from one spent seat to the other and back every fifteen minutes, refused
     * each time, until the end of the month. Now a spent cap refuses its seat until the budget starts over, and with
     * nothing else free the run waits for the first cap to start over - not a quarter of an hour.
     */
    @Test
    fun `a run does not go round business seats whose caps are spent`() {
        val sooner = now + 9 * day
        val later = now + 14 * day
        seat("seat-a", 100, later)
        seat("seat-b", 100, sooner)

        val way = LimitRelief.choose("seat-a", "seat-a", listOf(candidate("seat-a"), candidate("seat-b")), now)

        assertEquals(Way.Wait(sooner / 1000 * 1000), way)
    }

    /** A seat with room in its cap is measured by it, and a run goes to the least spent one. */
    @Test
    fun `a run goes on on the business seat with the most room in its cap`() {
        seat("seat-a", 100, now + 14 * day)
        seat("seat-b", 64, now + 14 * day)
        seat("seat-c", 12, now + 14 * day)

        val way = LimitRelief.choose(
            "seat-a",
            "seat-a",
            listOf(candidate("seat-a"), candidate("seat-b"), candidate("seat-c")),
            now,
        )

        assertEquals(Way.Go("seat-c"), way)
    }

    /**
     * The refusal itself names the cap and its start-over (see CodexShapes.resetOf): a seat refused this way is not
     * taken for free again before then, even when no figure of it has arrived yet.
     */
    @Test
    fun `a seat refused by its cap stays refused until the cap starts over`() {
        val month = (now + 14 * day) / 1000 * 1000
        usage.noteRateLimit(
            "seat-a",
            CodexRateLimit.Verdict(extraUsage = false, stopped = true, window = "workspace_member_usage_limit_reached", resetsAt = month),
            now,
        )

        val way = LimitRelief.choose("seat-a", "seat-a", listOf(candidate("seat-a")), now)

        assertEquals(Way.Wait(month), way)
    }
}
