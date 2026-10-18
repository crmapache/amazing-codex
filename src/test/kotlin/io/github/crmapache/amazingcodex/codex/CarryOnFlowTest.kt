package io.github.crmapache.amazingcodex.codex

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The panel's own "carry on" through the real hub, with a conversation standing on a process that speaks Codex's
 * protocol (see FakeCodex).
 *
 * What Codex would say by itself - a turn starting, the account's limits, the turn refused or done - is told to the
 * conversation in Codex's own words (see CodexSession.heardFromCodex), so it goes through the same translation a
 * live turn does: the refusal arrives in the fork's form, a result that is no error after the placeholder answer.
 * What is checked is both ends: what every client is told, and what went into Codex - the turns started, and with
 * which words.
 */
class CarryOnFlowTest : BasePlatformTestCase() {

    private class Recorder(override val id: String) : SessionClient {
        val received = CopyOnWriteArrayList<String>()

        override fun deliver(messages: List<String>) {
            received += messages
        }
    }

    private lateinit var codex: FakeCodex

    /**
     * A tab of this test's own. The light project, and with it the hub and its journals, outlives a test: a tab
     * name shared between tests hands every new client the messages of the test before.
     */
    private val TAB = "carry-on-${UUID.randomUUID()}"

    override fun setUp() {
        super.setUp()
        // The hub outlives a test and launches in the project's folder, which a test before may have removed: a
        // process that cannot start ends its turn at once, and everything after it would be about that.
        project.basePath?.let { File(it).mkdirs() }
        codex = FakeCodex()
    }

    override fun tearDown() {
        try {
            val hub = CodexSessionHub.getInstance(project)
            // What the account's limits were told here is the machine's (see AccountUsage), and outlives the test.
            runCatching { AccountUsage.getInstance().forget(hub.conversations.accountOf(TAB)) }
            // Takes the stand-in process down with the tab.
            runCatching { hub.closeSession(TAB) }
            codex.close()
        } finally {
            super.tearDown()
        }
    }

    private fun hub(recorder: Recorder): CodexSessionHub = CodexSessionHub.getInstance(project).also {
        it.register(recorder)
        it.attach(recorder.id)
    }

    /** The pieces a card is drawn from, as the panel sends them with a message. */
    private fun echo(text: String) = buildJsonObject {
        putJsonArray("tokens") {
            addJsonObject {
                put("kind", "text")
                put("value", text)
            }
        }
        putJsonArray("quotes") {}
    }

    /** A message queued the way the panel queues one. */
    private fun queue(hub: CodexSessionHub, text: String) {
        hub.queuePrompt(TAB, UUID.randomUUID().toString(), text, echo = echo(text))
    }

    private fun conversation(hub: CodexSessionHub): CodexSession = hub.conversations.conversation(TAB)!!

    /** The thread Codex opened for the tab - what every turn of it is started on. */
    private fun thread(hub: CodexSessionHub): String = hub.conversations.conversationIdOf(TAB)!!

    /** Codex says this next, about this conversation. */
    private fun codexSays(hub: CodexSessionHub, line: String) = conversation(hub).heardFromCodex(line)

    /** The messages that went into the conversation, as every client saw them, in order. */
    private fun said(recorder: Recorder): List<String> =
        recorder.received.filter { it.contains("\"type\":\"promptEcho\"") && it.contains("\"sessionId\":\"$TAB\"") }

    private fun waits(recorder: Recorder): List<String> =
        recorder.received.filter { it.contains("\"type\":\"carryOnWait\"") && it.contains("\"sessionId\":\"$TAB\"") }

    /** The words of every turn Codex was asked to start in this tab, once all of them have reached it. */
    private fun turns(hub: CodexSessionHub): List<String> {
        codex.settled(conversation(hub))
        return codex.turnsStarted(thread(hub))
    }

    /**
     * The turn the last message said in the tab started, running: the process has answered it with its id, and
     * Codex says the turn is under way. Returns the turn's id.
     */
    private fun turnUnderWay(hub: CodexSessionHub, recorder: Recorder): String {
        val uuid = uuidOf(said(recorder).last())
        assertTrue("the message never reached Codex", FakeCodex.waitFor { conversation(hub).turnOf(uuid) != null })
        val turn = conversation(hub).turnOf(uuid)!!
        codexSays(hub, FakeCodex.turnStarted(thread(hub), turn))
        return turn
    }

    private fun uuidOf(echo: String): String =
        ((Json.parseToJsonElement(echo) as JsonObject)["uuid"] as JsonPrimitive).contentOrNull!!

    /** The person's message into the tab, its turn under way in Codex. */
    private fun personSays(hub: CodexSessionHub, recorder: Recorder, text: String): String {
        hub.prompt(TAB, text, echo = echo(text))
        return turnUnderWay(hub, recorder)
    }

    /** The turn under way refused by the plan's five-hour limit, which resets in [resetsInSeconds]. */
    private fun refusedByTheFiveHours(hub: CodexSessionHub, turn: String, resetsInSeconds: Long = 600) {
        codexSays(hub, FakeCodex.fiveHoursFull(System.currentTimeMillis() / 1000 + resetsInSeconds))
        codexSays(hub, FakeCodex.turnRefused(thread(hub), turn))
    }

    /**
     * A turn under way, refused by the five-hour limit: the conversation has its thread, and the process stands
     * idle on the refusal - the state most tests here start from.
     */
    private fun refusedTurn(hub: CodexSessionHub, recorder: Recorder) {
        refusedByTheFiveHours(hub, personSays(hub, recorder, "Move the totals onto the new money type"))
    }

    private fun isCarryOn(words: String, reason: CarryOns.Reason): Boolean = words.startsWith("<${CarryOnWords.TAG} reason=\"${reason.wire}\">")

    /**
     * The limit refused the turn: every screen is told when the panel carries on, the queue waits behind it, and
     * at the reset the panel's "carry on" goes into Codex first and alone - the queued message after the turn it
     * starts.
     */
    fun testALimitIsWaitedOutAndTheCarryOnGoesBeforeTheQueue() {
        val recorder = Recorder("desk")
        val hub = hub(recorder)

        refusedTurn(hub, recorder)

        val planned = hub.conversations.carryOnOf(TAB)
        assertEquals(CarryOns.Reason.LIMIT, planned?.reason)
        assertTrue(waits(recorder).joinToString(), waits(recorder).last().contains("\"until\":${planned!!.notBefore}"))

        queue(hub, "and then the invoices")
        // Held behind the limit: refused like the turn before it, it would only spend a request.
        assertEquals(1, turns(hub).size)

        hub.runQueued(TAB, now = planned.notBefore!!)

        val afterReset = turns(hub)
        assertEquals(afterReset.joinToString("\n"), 2, afterReset.size)
        assertTrue(afterReset.last(), isCarryOn(afterReset.last(), CarryOns.Reason.LIMIT))
        assertTrue(said(recorder).last(), said(recorder).last().contains("\"auto\":\"limit\""))
        // The plan is spent and the screens are told so.
        assertFalse(waits(recorder).last(), waits(recorder).last().contains("\"until\""))

        // The carry-on's own turn goes through, and only then does the queue speak - into a turn of its own.
        val carried = turnUnderWay(hub, recorder)
        assertEquals(2, turns(hub).size)
        codexSays(hub, FakeCodex.turnCompleted(thread(hub), carried))

        val atTheEnd = turns(hub)
        assertEquals(atTheEnd.joinToString("\n"), 3, atTheEnd.size)
        assertEquals("and then the invoices", atTheEnd.last())
        assertTrue(codex.steered(thread(hub)).isEmpty())
    }

    /**
     * "Don't carry on" takes the panel's words away and leaves the wait: what is queued still goes at the
     * reset, with nothing said before it. It used to stand in the queue past the reset for good.
     */
    fun testSayingNoStillSendsTheQueueAtTheReset() {
        val recorder = Recorder("desk")
        val hub = hub(recorder)

        refusedTurn(hub, recorder)
        val at = hub.conversations.carryOnOf(TAB)!!.notBefore!!
        queue(hub, "and then the invoices")

        hub.cancelCarryOn(TAB)
        // No plan on the screens any more.
        assertFalse(waits(recorder).last(), waits(recorder).last().contains("\"until\""))
        // And still nothing before the reset.
        hub.runQueued(TAB)
        assertEquals(1, turns(hub).size)

        hub.runQueued(TAB, now = at)

        val atTheReset = turns(hub)
        assertEquals(atTheReset.joinToString("\n"), 2, atTheReset.size)
        assertEquals("and then the invoices", atTheReset.last())
        assertTrue(atTheReset.none { it.startsWith("<${CarryOnWords.TAG}") })
        assertFalse(said(recorder).last(), said(recorder).last().contains("\"auto\""))
    }

    /** The person writing in the meantime is the next word: nothing of the panel's is said after it. */
    fun testThePersonsOwnMessageDropsTheWait() {
        val recorder = Recorder("desk")
        val hub = hub(recorder)

        refusedTurn(hub, recorder)
        val at = hub.conversations.carryOnOf(TAB)!!.notBefore!!

        val theirs = personSays(hub, recorder, "never mind, do the invoices")
        assertNull(hub.conversations.carryOnOf(TAB))
        assertFalse(waits(recorder).last(), waits(recorder).last().contains("\"until\""))

        codexSays(hub, FakeCodex.turnCompleted(thread(hub), theirs))
        hub.runQueued(TAB, now = at)

        assertEquals(listOf("Move the totals onto the new money type", "never mind, do the invoices"), turns(hub))
        assertTrue(said(recorder).none { it.contains("\"auto\"") })
    }

    /**
     * Two passes at the moment the wait ends - its timer and the minute round - with a message queued: one
     * message goes into Codex, the carry-on, and the queued one waits for the turn it starts. Both used to go, the
     * second into the turn the first had just begun.
     */
    fun testTwoPassesAtOnceSayOneThing() {
        val recorder = Recorder("desk")
        val hub = hub(recorder)

        refusedTurn(hub, recorder)
        val at = hub.conversations.carryOnOf(TAB)!!.notBefore!!
        queue(hub, "and then the invoices")

        val start = CyclicBarrier(2)
        List(2) { thread { start.await(); hub.runQueued(TAB, now = at) } }.forEach { it.join(10_000) }

        val after = turns(hub)
        assertEquals(after.joinToString("\n"), 2, after.size)
        assertTrue(after.last(), isCarryOn(after.last(), CarryOns.Reason.LIMIT))
        // Nor steered into the carry-on's turn: the queued message has not gone anywhere yet.
        assertTrue(codex.steered(thread(hub)).isEmpty())
        assertTrue(said(recorder).last(), said(recorder).last().contains("\"auto\":\"limit\""))
    }

    /** A turn that went through ends the count: the next limit is a fresh one, not another refused carry-on. */
    fun testATurnThatWentThroughStartsTheCountAgain() {
        val recorder = Recorder("desk")
        val hub = hub(recorder)

        refusedTurn(hub, recorder)
        hub.runQueued(TAB, now = hub.conversations.carryOnOf(TAB)!!.notBefore!!)
        assertEquals(CarryOns.Reason.LIMIT, hub.conversations.lastCarryOn(TAB)?.reason)

        codexSays(hub, FakeCodex.turnCompleted(thread(hub), turnUnderWay(hub, recorder)))

        assertNull(hub.conversations.lastCarryOn(TAB))
    }

    /**
     * Three of the panel's own carry-ons refused in a row, and the tab is left to the person.
     *
     * Codex's refused turn ends with a result that is no error (see CodexStream.turnCompleted). Read by that alone,
     * each refusal was a turn that went through, and what the panel said last was forgotten at every one - the count
     * held only because the placeholder answer, which counts, comes before the result. A refused turn is no turn
     * that went through: the panel's carry-on stays its last word until somebody speaks or a turn does go through.
     */
    fun testThreeRefusedCarryOnsInARowLeaveTheTabToThePerson() {
        val recorder = Recorder("desk")
        val hub = hub(recorder)

        refusedTurn(hub, recorder)
        repeat(CarryOns.LIMIT_TRIES) { refused ->
            val owed = hub.conversations.carryOnOf(TAB)
            assertEquals("a wait after refusal $refused", CarryOns.Reason.LIMIT, owed?.reason)
            assertEquals(refused, owed!!.tries)

            hub.runQueued(TAB, now = owed.notBefore!!)
            refusedByTheFiveHours(hub, turnUnderWay(hub, recorder))
            assertEquals("still the panel's last word after refusal ${refused + 1}", refused, hub.conversations.lastCarryOn(TAB)?.tries)
        }

        assertNull(hub.conversations.carryOnOf(TAB))
        // Three carry-ons after the person's own turn, and nothing more however long the tab waits.
        hub.runQueued(TAB, now = System.currentTimeMillis() + CarryOns.LIMIT_WAIT_MS)
        val all = turns(hub)
        assertEquals(all.joinToString("\n"), 1 + CarryOns.LIMIT_TRIES, all.size)
        assertTrue(all.drop(1).all { isCarryOn(it, CarryOns.Reason.LIMIT) })
    }

    /**
     * A refusal no clock ends is not waited out: the workspace out of credits, the session's own budget. Planned, it
     * went at a guess a quarter of an hour on and told the agent "the limit has reset" about something that does
     * not reset. Nothing holds the queue either - it goes at the turn's end, as it did before any of this.
     */
    fun testARefusalNoClockEndsIsNotWaitedOut() {
        val recorder = Recorder("desk")
        val hub = hub(recorder)

        // The workspace has run out of credits: Codex names that as the reason, with no window full.
        val credits = personSays(hub, recorder, "Move the totals onto the new money type")
        codexSays(
            hub,
            FakeCodex.rateLimits(
                """{"limitId":"codex","credits":{"hasCredits":false,"unlimited":false},"rateLimitReachedType":"workspace_member_credits_depleted"}""",
            ),
        )
        codexSays(hub, FakeCodex.turnRefused(thread(hub), credits))
        assertNull(hub.conversations.carryOnOf(TAB))

        // The session's own budget, right after the account had said its five hours were full: the budget is no
        // window of the account's, and the account's reset is not when it ends.
        val budget = personSays(hub, recorder, "try once more")
        queue(hub, "and then the invoices")
        codexSays(hub, FakeCodex.fiveHoursFull(System.currentTimeMillis() / 1000 + 600))
        codexSays(hub, FakeCodex.turnRefused(thread(hub), budget, code = CodexErrors.SESSION_BUDGET))
        assertNull(hub.conversations.carryOnOf(TAB))

        assertEquals(listOf("Move the totals onto the new money type", "try once more", "and then the invoices"), turns(hub))
        assertTrue(said(recorder).none { it.contains("\"auto\"") })
        hub.runQueued(TAB, now = System.currentTimeMillis() + 20 * 60 * 1000)
        assertEquals(3, turns(hub).size)
    }

    /**
     * Only a limit that resets within hours is waited out. A weekly window resets in days, and a workspace seat's
     * spending cap starts over with its budget, a month on - by then the tab is old work, and the plan would start
     * it again out of nowhere.
     */
    fun testALimitResettingInDaysOrAMonthIsNotWaitedOut() {
        val recorder = Recorder("desk")
        val hub = hub(recorder)
        val now = System.currentTimeMillis() / 1000

        val weekly = personSays(hub, recorder, "Move the totals onto the new money type")
        codexSays(
            hub,
            FakeCodex.rateLimits(
                """{"limitId":"codex","primary":{"usedPercent":30,"windowDurationMins":300,"resetsAt":${now + 3_600}},""" +
                    """"secondary":{"usedPercent":100,"windowDurationMins":10080,"resetsAt":${now + 3 * 86_400}},""" +
                    """"rateLimitReachedType":"rate_limit_reached"}""",
            ),
        )
        codexSays(hub, FakeCodex.turnRefused(thread(hub), weekly))
        assertNull(hub.conversations.carryOnOf(TAB))

        // A business seat: no window of the plan at all, only the cap the workspace set, reset with the month.
        val capped = personSays(hub, recorder, "try once more")
        codexSays(
            hub,
            FakeCodex.rateLimits(
                """{"limitId":"codex","individualLimit":{"limit":"100","used":"100","remainingPercent":0,"resetsAt":${now + 20 * 86_400}},""" +
                    """"spendControlReached":true,"rateLimitReachedType":"workspace_owner_usage_limit_reached"}""",
            ),
        )
        codexSays(hub, FakeCodex.turnRefused(thread(hub), capped))
        assertNull(hub.conversations.carryOnOf(TAB))
    }

    /**
     * A wait of hours outlasts the idle sweep: half an hour on, the tab's process is taken back (see IdleSleep), and
     * at the reset there is none. The carry-on raises one, which resumes the same thread - the work it carries on is
     * there, and only there.
     */
    fun testACarryOnWakesATabPutToSleepWhileItWaited() {
        val recorder = Recorder("desk")
        val hub = hub(recorder)

        refusedTurn(hub, recorder)
        val at = hub.conversations.carryOnOf(TAB)!!.notBefore!!
        val thread = thread(hub)
        assertTrue(hub.conversations.sleep(TAB) { true })
        assertFalse(hub.conversations.isRunning(TAB))
        val before = codex.requests().size

        hub.runQueued(TAB, now = at)

        val after = turns(hub)
        assertEquals(after.joinToString("\n"), 2, after.size)
        assertTrue(after.last(), isCarryOn(after.last(), CarryOns.Reason.LIMIT))
        val resumed = codex.requests().drop(before).filter { it["method"] == JsonPrimitive("thread/resume") }
        assertEquals(listOf(JsonPrimitive(thread)), resumed.map { (it["params"] as JsonObject)["threadId"] })
    }
}
