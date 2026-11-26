package io.github.crmapache.amazingcodex.codex

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The guess at the next message (from ACC 0.13.50): after a tab's turn the IDE asks Codex for one
 * (`thread/prediction/request`), and Codex's answer (`thread/prediction/updated`) goes to the panel as the stream line
 * the field draws as gray text (see CodexSession.askPrediction). Codex 0.163 answers the request "not implemented yet",
 * and then that Codex is not asked again.
 *
 * On a process that speaks Codex's protocol (see FakeCodex): what reaches Codex is read off what it was sent.
 */
class PromptSuggestionSessionsTest : BasePlatformTestCase() {

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

    /** A message into the tab, its turn under way in Codex and finished. Returns the turn's id. */
    private fun turnDone(text: String): String {
        val uuid = UUID.randomUUID().toString()
        sessions.prompt(TAB, text, uuid = uuid)
        assertTrue("the message never reached Codex", FakeCodex.waitFor { sessions.conversation(TAB)?.turnOf(uuid) != null })
        val turn = conversation().turnOf(uuid)!!
        conversation().heardFromCodex(FakeCodex.turnStarted(thread(), turn))
        conversation().heardFromCodex(FakeCodex.turnCompleted(thread(), turn))
        codex.settled(conversation())
        return turn
    }

    private fun asked(): List<String> = codex.requests("thread/prediction/request", thread()).map { request ->
        ((request["params"] as JsonObject)["sourceTurnId"] as JsonPrimitive).content
    }

    private fun suggestions(): List<String> = lines.filter { it.contains("\"prompt_suggestion\"") }

    fun testAFinishedTurnAsksCodexAndItsGuessGoesToTheFieldAsTheStreamLine() {
        val turn = turnDone("Add a test for the parser")

        assertEquals(listOf(turn), asked())

        conversation().heardFromCodex(FakeCodex.predicted(thread(), turn, "now run the tests"))
        assertEquals(listOf("""{"type":"prompt_suggestion","suggestion":"now run the tests"}"""), suggestions())
    }

    // A guess about an older turn is stale: the person has moved on since.
    fun testAGuessAboutAnotherTurnIsDropped() {
        val first = turnDone("First")
        turnDone("Second")

        conversation().heardFromCodex(FakeCodex.predicted(thread(), first, "stale"))
        assertTrue(suggestions().isEmpty())
    }

    fun testACodexThatHasNoPredictionsIsNotAskedAgain() {
        codex.answersPredictions = false

        turnDone("First")
        turnDone("Second")

        assertEquals("asked once, then remembered as not implemented", 1, asked().size)
    }

    fun testAStoppedTurnAsksNothing() {
        val uuid = UUID.randomUUID().toString()
        sessions.prompt(TAB, "Something long", uuid = uuid)
        assertTrue(FakeCodex.waitFor { sessions.conversation(TAB)?.turnOf(uuid) != null })
        val turn = conversation().turnOf(uuid)!!
        conversation().heardFromCodex(FakeCodex.turnStarted(thread(), turn))
        conversation().heardFromCodex(FakeCodex.turnInterrupted(thread(), turn))
        codex.settled(conversation())

        assertTrue(asked().isEmpty())
    }

    private companion object {
        const val TAB = "prediction-tab"
    }
}
