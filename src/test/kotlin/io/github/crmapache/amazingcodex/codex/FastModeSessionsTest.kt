package io.github.crmapache.amazingcodex.codex

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.crmapache.amazingcodex.codex.accounts.CodexAccounts
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Fast mode is an ask and an answer (see CodexSession.fastMode and fastReport): the ask is the panel's, and it has to
 * reach Codex with every open of the thread and every turn - Codex keeps a tier for the turns after it, but only in
 * the process it was given to. The answer is Codex's, and it is what the stream says, so that a fast tier set in
 * Codex's own config shows as plainly as one asked for in the panel.
 *
 * On a process that speaks Codex's protocol (see FakeCodex): what reaches Codex is read off what it was sent, and
 * what Codex says by itself - a turn starting and ending, a tier changed - is told in its own words.
 */
class FastModeSessionsTest : BasePlatformTestCase() {

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
            CodexAccounts.getInstance().noteFastModels(sessions.accountOf(TAB), null)
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

    /**
     * What [method] carried as `serviceTier` each time this conversation sent it - null where it carried none. The
     * tab's name is asked in ephemeral threads of their own (see CodexTitles), and those are not the conversation.
     */
    private fun tiersSent(method: String): List<String?> =
        codex.requests().filter { it["method"] == JsonPrimitive(method) }.mapNotNull { request ->
            val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
            val ours = when (method) {
                "thread/start" -> params["ephemeral"] != JsonPrimitive(true)
                else -> params["threadId"] == JsonPrimitive(thread())
            }
            if (!ours) return@mapNotNull null
            listOf((params["serviceTier"] as? JsonPrimitive)?.contentOrNull)
        }.flatten()

    private fun said(): List<JsonObject> = lines.mapNotNull { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    /** What the stream said at the thread's open (`system/init`) and at the last turn's end (`result`). */
    private fun opened(): JsonObject? = said().lastOrNull { it["type"] == JsonPrimitive("system") && it["subtype"] == JsonPrimitive("init") }

    private fun ended(): JsonObject? = said().lastOrNull { it["type"] == JsonPrimitive("result") }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    /**
     * The ask rides every request that can carry it: the thread's open, each turn, and the open of the same thread
     * by the next process - which is the one that would otherwise come up on the config's tier again.
     */
    fun testTheAskRidesEveryTurnAndEveryOpenOfTheThread() {
        sessions.setFastMode(TAB, true)
        finished(turnUnderWay("Rename formatPrice to formatAmount"))
        finished(turnUnderWay("And formatDate"))
        codex.settled(conversation())

        assertEquals(listOf<String?>(ServiceTiers.FAST), tiersSent("thread/start"))
        assertEquals(listOf<String?>(ServiceTiers.FAST, ServiceTiers.FAST), tiersSent("turn/start"))

        assertTrue(sessions.restart(TAB))
        finished(turnUnderWay("Now the tests"))
        codex.settled(conversation())
        assertEquals(listOf<String?>(ServiceTiers.FAST), tiersSent("thread/resume"))
        assertEquals(ServiceTiers.FAST, tiersSent("turn/start").last())

        // Off is an ask too, and it rides the same way: the thread keeps a tier until told otherwise.
        sessions.setFastMode(TAB, false)
        turnUnderWay("And the docs")
        codex.settled(conversation())
        assertEquals(ServiceTiers.STANDARD, tiersSent("turn/start").last())
    }

    /** A tab that asks nothing sends nothing: Codex's own config decides, and the panel never overrides it. */
    fun testATabThatAsksNothingLeavesTheTierToCodex() {
        finished(turnUnderWay("Rename formatPrice to formatAmount"))
        codex.settled(conversation())

        assertEquals(listOf<String?>(null), tiersSent("thread/start"))
        assertEquals(listOf<String?>(null), tiersSent("turn/start"))
    }

    /**
     * What the stream says of fast mode is what Codex says the thread runs on, whoever put it there: a tier from
     * Codex's config lights it as much as the panel's ask, from the thread's open, and a tier changed by a turn
     * changes the turn's result.
     */
    fun testTheStreamSaysTheTierCodexRunsOn() {
        codex.configuredTier = ServiceTiers.FAST
        val turn = turnUnderWay("Rename formatPrice to formatAmount")
        assertTrue(FakeCodex.waitFor { opened() != null })
        assertEquals("on", opened()!!.text("fast_mode_state"))
        assertNull(opened()!!.text("fast_mode_disabled_reason"))

        conversation().heardFromCodex(FakeCodex.tierChanged(thread(), ServiceTiers.STANDARD))
        finished(turn)
        assertEquals("off", ended()!!.text("fast_mode_state"))
        // Nobody here asked: there is nothing to have refused.
        assertNull(ended()!!.text("fast_mode_disabled_reason"))

        val next = turnUnderWay("And formatDate")
        conversation().heardFromCodex(FakeCodex.tierChanged(thread(), ServiceTiers.FAST))
        finished(next)
        assertEquals("on", ended()!!.text("fast_mode_state"))
    }

    /**
     * An ask newer than the last request that carried a tier is not refused by the turn that was already running:
     * that turn ran on what it was given. Only an ask Codex has heard and not served is "it did not turn on".
     */
    fun testAnAskIsJudgedOnlyOnceCodexHasHeardIt() {
        val running = turnUnderWay("Rename formatPrice to formatAmount")
        sessions.setFastMode(TAB, true)
        finished(running)
        assertEquals("off", ended()!!.text("fast_mode_state"))
        assertEquals(CODEX_PENDING, ended()!!.text("fast_mode_disabled_reason"))

        val heard = turnUnderWay("And formatDate")
        codex.settled(conversation())
        assertEquals(ServiceTiers.FAST, tiersSent("turn/start").last())
        conversation().heardFromCodex(FakeCodex.tierChanged(thread(), ServiceTiers.STANDARD))
        finished(heard)
        assertEquals("off", ended()!!.text("fast_mode_state"))
        assertEquals(NOT_SERVED, ended()!!.text("fast_mode_disabled_reason"))
    }

    /**
     * A model without the tier has it dropped from every request, whatever the thread says it runs on - so the mark
     * does not light, and an ask of the panel's is told why. The catalogue says which model that is.
     */
    fun testAModelWithoutTheTierIsNotFastWhateverTheThreadSays() {
        CodexAccounts.getInstance().noteFastModels(sessions.accountOf(TAB), listOf(Triple(FAKE_MODEL, FAKE_MODEL, false)))
        // Named, so that the turn runs on the model the catalogue was told about whatever the machine's setting says -
        // and named before the ask, since a pick of a model without the tier turns an ask off (see CodexSessions.setModel).
        sessions.setModel(TAB, FAKE_MODEL, remember = false) {}
        sessions.setFastMode(TAB, true)
        val turn = turnUnderWay("Rename formatPrice to formatAmount")
        assertTrue(FakeCodex.waitFor { opened() != null })
        assertEquals("off", opened()!!.text("fast_mode_state"))
        assertEquals(MODEL_WITHOUT_TIER, opened()!!.text("fast_mode_disabled_reason"))

        conversation().heardFromCodex(FakeCodex.tierChanged(thread(), ServiceTiers.FAST))
        finished(turn)
        assertEquals("off", ended()!!.text("fast_mode_state"))
        assertEquals(MODEL_WITHOUT_TIER, ended()!!.text("fast_mode_disabled_reason"))
    }

    private companion object {
        const val TAB = "main"

        /** The model the stand-in opens every thread on. */
        const val FAKE_MODEL = "gpt-fake"

        /** The panel's words for these (see fastReasonText and PENDING in feed/fastMode.ts). */
        const val CODEX_PENDING = "pending"
        const val NOT_SERVED = "not_served"
        const val MODEL_WITHOUT_TIER = "model_not_allowed"
    }
}
