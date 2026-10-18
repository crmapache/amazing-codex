package io.github.crmapache.amazingcodex.codex

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Which model a conversation asks Codex for, and which model its answers are signed by (see CodexSession.modelOnWire
 * and signedModel). Codex keeps a model given to a turn "for this turn and subsequent turns", so the two are not the
 * pick: a pick reaches Codex with the next `turn/start`, "Default" after a pick has to be said with a model, and
 * what the thread runs on is what Codex says or was told.
 *
 * On a process that speaks Codex's protocol (see FakeCodex): what reaches Codex is read off what it was sent, and what
 * Codex says by itself - a turn starting and ending, an answer, a reroute - is told in its own words.
 */
class ModelOnWireSessionsTest : BasePlatformTestCase() {

    private lateinit var codex: FakeCodex

    private lateinit var sessions: CodexSessions

    private val lines = CopyOnWriteArrayList<String>()

    override fun setUp() {
        super.setUp()
        codex = FakeCodex()
        sessions = CodexSessions(
            workingDirectory = null,
            parentDisposable = testRootDisposable,
            onEvent = { _, line -> lines += line },
            onError = { _, _ -> },
            onFinished = {},
        )
        // Whatever the machine's settings would start a tab on: these conversations begin on "Default".
        sessions.setModel(TAB, ModelNames.DEFAULT, remember = false) {}
    }

    override fun tearDown() {
        try {
            runCatching { Disposer.dispose(sessions) }
            codex.close()
        } finally {
            super.tearDown()
        }
    }

    private fun conversation(): CodexSession = sessions.conversation(TAB)!!

    private fun thread(): String = sessions.conversationIdOf(TAB)!!

    /** A message into the tab, and its turn under way in Codex. Returns the turn's id. */
    private fun turnUnderWay(text: String): String {
        val uuid = UUID.randomUUID().toString()
        sessions.prompt(TAB, text, uuid = uuid)
        assertTrue("the message never reached Codex", FakeCodex.waitFor { sessions.conversation(TAB)?.turnOf(uuid) != null })
        val turn = conversation().turnOf(uuid)!!
        conversation().heardFromCodex(FakeCodex.turnStarted(thread(), turn))
        return turn
    }

    private fun finished(turn: String) = conversation().heardFromCodex(FakeCodex.turnCompleted(thread(), turn))

    /** What each `turn/start` of this conversation carried as its model - null where it carried none. */
    private fun modelsSent(): List<String?> {
        codex.settled(conversation())
        return codex.requests("turn/start", thread()).map { request ->
            ((request["params"] as? JsonObject)?.get("model") as? JsonPrimitive)?.contentOrNull
        }
    }

    /** The model the conversation's last answer was signed by. */
    private fun signature(): String? = lines
        .mapNotNull { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        .lastOrNull { it["type"] == JsonPrimitive("assistant") }
        ?.let { ((it["message"] as? JsonObject)?.get("model") as? JsonPrimitive)?.contentOrNull }

    private fun answer(turn: String, text: String): String? {
        conversation().heardFromCodex(FakeCodex.answered(thread(), turn, "msg-${UUID.randomUUID()}", text))
        return signature()
    }

    /**
     * A model given to a turn stays with the thread, so "Default" picked after it, sent as nothing, left the tab on the
     * pick - while the chip and the signatures named the model the thread had opened on. The way back is said with the
     * model Codex itself opened the thread on, once; after that nothing is sent again.
     */
    fun testReturningToDefaultAfterAPickSaysTheDefaultModel() {
        finished(turnUnderWay("Rename formatPrice to formatAmount"))

        sessions.setModel(TAB, PICKED, remember = false) {}
        finished(turnUnderWay("And formatDate"))

        sessions.setModel(TAB, ModelNames.DEFAULT, remember = false) {}
        val back = turnUnderWay("Now the tests")
        assertEquals(FakeCodex.DEFAULT_MODEL, answer(back, "Back on the default."))
        finished(back)

        finished(turnUnderWay("And the docs"))

        assertEquals(listOf(null, PICKED, FakeCodex.DEFAULT_MODEL, null), modelsSent())
    }

    /** A tab that never left "Default" never names a model: Codex's own config decides, as it always did. */
    fun testATabLeftOnDefaultNamesNoModel() {
        finished(turnUnderWay("Rename formatPrice to formatAmount"))
        finished(turnUnderWay("And formatDate"))

        assertEquals(listOf<String?>(null, null), modelsSent())
    }

    /**
     * A pick made while a turn runs reaches Codex with the next `turn/start`, and the turn already running goes on in the
     * model it was started on. Signed by the pick at once, its answers told the panel the pick had arrived when it had
     * not; signed by what runs, the pick arrives with the next turn's first answer.
     */
    fun testAPickMadeMidTurnSignsTheNextTurnAndNotThisOne() {
        val running = turnUnderWay("Rename formatPrice to formatAmount")
        assertEquals(FakeCodex.DEFAULT_MODEL, answer(running, "Looking."))

        sessions.setModel(TAB, PICKED, remember = false) {}
        assertEquals(FakeCodex.DEFAULT_MODEL, answer(running, "Still on the old request."))
        finished(running)

        val next = turnUnderWay("And formatDate")
        assertEquals(PICKED, answer(next, "On the pick now."))
        assertEquals(listOf(null, PICKED), modelsSent())
    }

    /** What Codex says the thread runs on is what the answers come from, whatever this side last sent. */
    fun testCodexsWordOnTheThreadsModelSignsTheAnswers() {
        val turn = turnUnderWay("Rename formatPrice to formatAmount")
        conversation().heardFromCodex(FakeCodex.modelChanged(thread(), "gpt-said"))

        assertEquals("gpt-said", answer(turn, "On what Codex says."))
    }

    /** A reroute signs the rest of its turn by the model it moved to, and the next turn is the thread's again. */
    fun testARerouteSignsTheRestOfItsTurnOnly() {
        val turn = turnUnderWay("Rename formatPrice to formatAmount")
        conversation().heardFromCodex(FakeCodex.rerouted(thread(), turn, FakeCodex.DEFAULT_MODEL, REROUTED))
        assertEquals(REROUTED, answer(turn, "Carrying on."))
        assertEquals(REROUTED, answer(turn, "Still here."))
        finished(turn)

        val next = turnUnderWay("And formatDate")
        assertEquals(FakeCodex.DEFAULT_MODEL, answer(next, "Back."))
    }

    /**
     * Codex's count is of the thread, so a conversation whose process went to sleep still knows how full its window
     * is - and has to say it: a window picked while it sleeps raises no process, and this is the only way the count
     * measured against the new window reaches the clients (see CodexSessions.requestContextUsage).
     */
    fun testASleepingConversationStillAnswersHowFullItsWindowIs() {
        val turn = turnUnderWay("Rename formatPrice to formatAmount")
        conversation().heardFromCodex(FakeCodex.counted(thread(), turn, total = 120_000, window = 258_400))
        finished(turn)
        conversation().stop()

        var answer: JsonObject? = null
        sessions.requestContextUsage(TAB, onResult = { answer = it })

        // A model the cache does not know was given no window of ours: Codex's own still holds.
        assertEquals(120_000, (answer?.get("totalTokens") as? JsonPrimitive)?.contentOrNull?.toInt())
        assertEquals(258_400, (answer?.get("maxTokens") as? JsonPrimitive)?.contentOrNull?.toInt())
    }

    private companion object {
        const val TAB = "main"

        /**
         * Models no cache on any machine knows: a pick between two of them moves no window, so it does not bring the
         * process up again (see CodexSessions.setModel) and every turn goes to the one process the test watches.
         */
        const val PICKED = "gpt-picked-by-hand"
        const val REROUTED = "gpt-rerouted-by-codex"
    }
}
