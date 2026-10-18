package io.github.crmapache.amazingcodex.codex

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.crmapache.amazingcodex.codex.accounts.AccountsState
import io.github.crmapache.amazingcodex.codex.accounts.CodexAccounts
import java.io.File
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Where the conversations decide what the panel will owe a tab: an account switch marks the turn it interrupts,
 * the move keeps the mark, a quiet swap is said for whoever might be owed something by it - and the carry-on, often
 * the first words of a new process, names nothing.
 *
 * On a process that speaks Codex's protocol (see FakeCodex), with what Codex would say by itself told in its own
 * words, so the session's turn and plan are its own state rather than a story told to the panel.
 */
class CarryOnSessionsTest : BasePlatformTestCase() {

    private lateinit var codex: FakeCodex

    private var quietSwaps = 0

    /** How many times a move said it was stopping a turn (see CodexSessions.onMoveStopping). */
    private var stoppings = 0

    /** What each dropped process was told to keep (see CodexSessions.onProcessDropping). */
    private val dropped = mutableListOf<Set<String>>()

    private lateinit var sessions: CodexSessions

    override fun setUp() {
        super.setUp()
        codex = FakeCodex()
        sessions = CodexSessions(
            workingDirectory = null,
            parentDisposable = testRootDisposable,
            onEvent = { _, _ -> },
            onError = { _, _ -> },
            onFinished = {},
            // What the hub does at a turn's end, first of all: a move that waited for the turn lands now.
            onTurnEnded = { sessionId -> sessions.applyPendingAccount(sessionId) },
            onReplacedQuietly = { quietSwaps += 1 },
            onMoveStopping = { stoppings += 1 },
            onProcessDropping = { _, kept -> dropped += kept },
        )
    }

    override fun tearDown() {
        try {
            // The stand-in processes go before the setting that named them is put back.
            runCatching { Disposer.dispose(sessions) }
            codex.close()
            runCatching {
                AccountsState.getInstance().accounts().forEach { AccountsState.getInstance().forget(it.id) }
                CodexAccounts.getInstance().currentId = ""
            }
            CodexPreferences.mode = ""
        } finally {
            super.tearDown()
        }
    }

    /** The account register is machine-wide and outlives a test - see tearDown. */
    private fun register(id: String) {
        AccountsState.getInstance().remember(AccountsState.Account().apply { this.id = id })
        CodexAccounts.getInstance().currentId = id
    }

    private fun conversation(tab: String = TAB): CodexSession = sessions.conversation(tab)!!

    private fun thread(tab: String = TAB): String = sessions.conversationIdOf(tab)!!

    /** A message into [tab], and its turn under way in Codex. Returns the turn's id. */
    private fun turnUnderWay(text: String, tab: String = TAB): String {
        val uuid = UUID.randomUUID().toString()
        sessions.prompt(tab, text, uuid = uuid)
        assertTrue("the message never reached Codex", FakeCodex.waitFor { sessions.conversation(tab)?.turnOf(uuid) != null })
        val turn = conversation(tab).turnOf(uuid)!!
        conversation(tab).heardFromCodex(FakeCodex.turnStarted(thread(tab), turn))
        return turn
    }

    private fun sent(method: String): List<JsonObject> = codex.requests().filter { it["method"] == JsonPrimitive(method) }

    /** Codex's plan item for [turn], as a plan-mode turn proposes it (`item/completed` of a `plan`). */
    private fun planProposed(thread: String, turn: String): String =
        """{"method":"item/completed","params":{"threadId":"$thread","turnId":"$turn",""" +
            """"item":{"type":"plan","id":"plan-1","text":"1. Read the totals\n2. Move them"}}}"""

    /**
     * The switch interrupts the running turn and marks it at that moment: the interrupt is the fact, and a switch
     * changed back within the eight seconds voids the move and not the stop. The move lands when the turn ends, and
     * the mark goes across with the tab - a close that is that very move keeps it.
     */
    fun testAnAccountSwitchMarksTheTurnItInterruptsAndTheMoveKeepsIt() {
        val turn = turnUnderWay("Move the totals onto the new money type")
        val before = conversation()

        register("work")
        sessions.switchAllTo()

        assertEquals(CarryOns.Reason.ACCOUNT_SWITCH, sessions.carryOnOf(TAB)?.reason)
        codex.settled(before)
        assertEquals(1, sent("turn/interrupt").size)
        // Still the old conversation: the move waits for the turn to end.
        assertSame(before, conversation())

        before.heardFromCodex(FakeCodex.turnInterrupted(thread(), turn))

        assertNotSame(before, sessions.conversation(TAB))
        assertEquals("work", sessions.accountOf(TAB))
        assertEquals(CarryOns.Reason.ACCOUNT_SWITCH, sessions.carryOnOf(TAB)?.reason)
        // Spoken for by the end of the turn it cut short - said twice, the queue would drain twice.
        assertEquals(0, quietSwaps)
    }

    /**
     * Words the person wrote into the turn the switch interrupts, which the turn would not take in, are not the turn's
     * to lose (see CodexSession.unsent): the interrupt keeps them, and the move hands them to the conversation on the
     * new account, where they are said after the "carry on". Nothing of them goes into the old process. They used to
     * go with the interrupt, unsaid, while their card stood in the feed.
     */
    fun testTheWordsATurnWouldNotTakeMoveWithTheConversation() {
        val turn = turnUnderWay("Review the branch")
        val before = conversation()
        val oldThread = thread()
        sessions.prompt(TAB, "and mind the tests", uuid = UUID.randomUUID().toString())
        codex.settled(before)
        assertEquals(listOf("and mind the tests"), codex.steered(oldThread))
        assertEquals(listOf("and mind the tests"), before.unsent().map { it.text })

        register("work")
        sessions.switchAllTo()
        codex.settled(before)
        assertEquals(1, sent("turn/interrupt").size)
        assertEquals("kept through the interrupt", listOf("and mind the tests"), before.unsent().map { it.text })

        before.heardFromCodex(FakeCodex.turnInterrupted(oldThread, turn))

        assertNotSame(before, sessions.conversation(TAB))
        assertEquals(listOf("and mind the tests"), sessions.unsentOf(TAB).map { it.text })
        assertTrue(before.unsent().isEmpty())
        codex.settled(before)
        assertEquals(listOf("Review the branch"), codex.turnsStarted(oldThread))
    }

    /*
     * The thread goes on in the new process, and so do the names the panel gave its messages: a rewind, a fork and a
     * picture's preview still name a message sent before the move by that name (see CodexSession.placeOf). They used to
     * stay behind with the process, and the message was nowhere to be found.
     */
    fun testTheNamesOfTheMessagesMoveWithTheConversation() {
        val uuid = UUID.randomUUID().toString()
        sessions.prompt(TAB, "Move the totals", uuid = uuid)
        assertTrue("the message never reached Codex", FakeCodex.waitFor { sessions.conversation(TAB)?.turnOf(uuid) != null })
        val before = conversation()
        val turn = before.turnOf(uuid)!!
        before.heardFromCodex(FakeCodex.turnStarted(thread(), turn))
        before.heardFromCodex(FakeCodex.turnCompleted(thread(), turn))
        // The thread has its file, as a thread with a turn in it does (see CodexSessions.moveTo).
        CodexHistory.standIn(thread(), File.createTempFile("rollout-", ".jsonl").apply { deleteOnExit() })

        register("work")
        sessions.switchAllTo()

        assertNotSame(before, conversation())
        assertEquals(turn, conversation().turnOf(uuid))
        assertEquals(turn to 0, sessions.placeOf(TAB, uuid))
    }

    /** The person's own Stop is another matter: what they wrote into the turn goes with the turn they stopped. */
    fun testTheirOwnStopTakesTheWordsAway() {
        turnUnderWay("Review the branch")
        sessions.prompt(TAB, "and mind the tests", uuid = UUID.randomUUID().toString())
        codex.settled(conversation())
        assertEquals(1, sessions.unsentOf(TAB).size)

        sessions.interrupt(TAB)

        assertTrue(sessions.unsentOf(TAB).isEmpty())
    }

    /**
     * A plan waiting for a decision moves with the conversation. Its turn ended in Codex with the plan, which is in the
     * thread, and the decision is a turn the panel starts itself - so there is nothing to interrupt and nothing to wait
     * for: the tab moves at once, nobody is told a turn was stopped, the dropped process keeps the plan's card, and the
     * conversation on the new account holds the plan as its own, live. No carry-on either: nothing was aborted.
     */
    fun testAPlanWaitingForADecisionMovesWithTheConversation() {
        sessions.setPermissionMode(TAB, PermissionModes.PLAN) {}
        val turn = turnUnderWay("Plan the move of the totals")
        val oldThread = thread()
        conversation().heardFromCodex(planProposed(oldThread, turn))
        conversation().heardFromCodex(FakeCodex.turnCompleted(oldThread, turn))
        val before = conversation()
        assertTrue(before.waitsOnPlan)
        // The thread has its file, as a thread with a turn in it does (see CodexSessions.moveTo).
        CodexHistory.standIn(oldThread, File.createTempFile("rollout-", ".jsonl").apply { deleteOnExit() })

        register("work")
        sessions.switchAllTo()

        val after = conversation()
        assertNotSame(before, after)
        assertEquals("work", sessions.accountOf(TAB))
        assertEquals(oldThread, sessions.conversationIdOf(TAB))
        assertTrue(after.waitsOnPlan)
        assertTrue(sessions.isAwaitingPermission(TAB, PLAN_REQUEST))
        assertFalse(before.waitsOnPlan)
        assertNull(sessions.carryOnOf(TAB))
        assertEquals(0, stoppings)
        assertEquals(listOf(setOf(PLAN_REQUEST)), dropped)
        codex.settled(before)
        assertTrue(sent("turn/interrupt").isEmpty())
    }

    /**
     * A plan whose thread cannot be continued - no file to resume it from - is set aside instead, the way Stop sets it
     * aside: a conversation coming up without the thread would be asked to carry out a plan it has never seen. Its turn
     * ended in Codex with the plan, nothing is running, and nothing is aborted. "The interruption above was an
     * automatic switch" would be said about an interruption that is not there.
     */
    fun testAPlanWaitingForADecisionIsSetAsideWithoutACarryOn() {
        sessions.setPermissionMode(TAB, PermissionModes.PLAN) {}
        val turn = turnUnderWay("Plan the move of the totals")
        conversation().heardFromCodex(planProposed(thread(), turn))
        conversation().heardFromCodex(FakeCodex.turnCompleted(thread(), turn))
        assertTrue("the plan holds the turn's end back", conversation().isBusy)
        assertFalse("and Codex runs nothing", conversation().runsATurn)
        val before = conversation()

        register("work")
        sessions.switchAllTo()

        assertEquals("work", sessions.accountOf(TAB))
        assertNull(sessions.carryOnOf(TAB))
        codex.settled(before)
        assertTrue(sent("turn/interrupt").isEmpty())
    }

    /**
     * A process replaced while its tab stands idle is said, on both roads - a quiet move and a restart (an MCP server
     * added): nobody else speaks for the tab there, and it may be exactly what a "carry on" was waiting for (see
     * CodexSessionHub.afterQuietSwap). It owes nothing by itself.
     */
    fun testAProcessReplacedWhileTheTabIsIdleIsSaid() {
        val turn = turnUnderWay("Move the totals onto the new money type")
        conversation().heardFromCodex(FakeCodex.turnCompleted(thread(), turn))

        assertTrue(sessions.restart(TAB))
        assertEquals(1, quietSwaps)

        register("work")
        sessions.switchAllTo()
        assertEquals(2, quietSwaps)
        assertNull(sessions.carryOnOf(TAB))
    }

    /**
     * The carry-on is often the first message of a new process - a tab moved to another account comes up on a new
     * one, and the first thing said there is the panel's "carry on" - and a tab still waiting for its name is not
     * named after it. The name is asked with the first message of a conversation, in an ephemeral thread of its own
     * (see CodexTitles); the person's own first words are asked about as before.
     */
    fun testTheCarryOnAsFirstWordsAsksForNoName() {
        turnUnderWay(CarryOnWords.forAgent(CarryOns.Reason.ACCOUNT_SWITCH, CarryOnWords.afterAccountSwitch("en")))
        codex.settled(conversation())
        assertTrue(sent("thread/start").none { ephemeral(it) })

        turnUnderWay("Move the totals onto the new money type", tab = OTHER)
        codex.settled(conversation(OTHER))
        assertEquals(1, sent("thread/start").count { ephemeral(it) })
    }

    private fun ephemeral(request: JsonObject): Boolean = (request["params"] as? JsonObject)?.get("ephemeral") == JsonPrimitive(true)

    private companion object {
        const val TAB = "main"
        const val OTHER = "other"

        /** The request a plan's card waits under - `plan-` and the plan item's id (see CodexSession.proposePlan). */
        const val PLAN_REQUEST = "plan-plan-1"
    }
}
