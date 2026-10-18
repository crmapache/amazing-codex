package io.github.crmapache.amazingcodex.codex

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * What a turn's end lets out, and in what order: one turn at a time, and what belongs to the turn that ended before
 * what was queued for after it.
 *
 * Two things wait for a turn's end - what the person wrote into the turn and the turn would not take in (see
 * CodexSession.unsent), and the hub's queue - and the end used to let both out at once: the hub's queue first, since
 * the end is announced before the conversation looked at its own, and then the conversation's, as a second
 * `turn/start` beside the first one Codex had not answered yet. A decided plan raced the queue the same way. On a
 * process that speaks Codex's protocol (see FakeCodex), so what is checked is what reached Codex.
 */
class TurnEndOrderTest : BasePlatformTestCase() {

    private class Recorder(override val id: String) : SessionClient {
        val received = CopyOnWriteArrayList<String>()

        override fun deliver(messages: List<String>) {
            received += messages
        }
    }

    private lateinit var codex: FakeCodex

    /** A tab of this test's own - the light project, and with it the hub, outlives a test. */
    private val TAB = "turn-end-${UUID.randomUUID()}"

    override fun setUp() {
        super.setUp()
        project.basePath?.let { File(it).mkdirs() }
        codex = FakeCodex()
    }

    override fun tearDown() {
        try {
            val hub = CodexSessionHub.getInstance(project)
            runCatching { AccountUsage.getInstance().forget(hub.conversations.accountOf(TAB)) }
            runCatching { hub.closeSession(TAB) }
            codex.close()
            CodexPreferences.mode = ""
        } finally {
            super.tearDown()
        }
    }

    private fun hub(recorder: Recorder): CodexSessionHub = CodexSessionHub.getInstance(project).also {
        it.register(recorder)
        it.attach(recorder.id)
    }

    private fun echo(text: String) = buildJsonObject {
        putJsonArray("tokens") {
            addJsonObject {
                put("kind", "text")
                put("value", text)
            }
        }
        putJsonArray("quotes") {}
    }

    private fun conversation(hub: CodexSessionHub): CodexSession = hub.conversations.conversation(TAB)!!

    private fun thread(hub: CodexSessionHub): String = hub.conversations.conversationIdOf(TAB)!!

    private fun codexSays(hub: CodexSessionHub, line: String) = conversation(hub).heardFromCodex(line)

    private fun said(recorder: Recorder): List<String> =
        recorder.received.filter { it.contains("\"type\":\"promptEcho\"") && it.contains("\"sessionId\":\"$TAB\"") }

    private fun uuidOf(echo: String): String =
        ((Json.parseToJsonElement(echo) as JsonObject)["uuid"] as JsonPrimitive).contentOrNull!!

    /** The words of every turn Codex was asked to start in this tab, once all of them have reached it. */
    private fun turns(hub: CodexSessionHub): List<String> {
        codex.settled(conversation(hub))
        return codex.turnsStarted(thread(hub))
    }

    /** The turn the message named [uuid] started, under way in Codex. Returns its id. */
    private fun underWay(hub: CodexSessionHub, uuid: String): String {
        assertTrue("the message never reached Codex", FakeCodex.waitFor { conversation(hub).turnOf(uuid) != null })
        val turn = conversation(hub).turnOf(uuid)!!
        codexSays(hub, FakeCodex.turnStarted(thread(hub), turn))
        return turn
    }

    /** The person's message into the tab, its turn under way. Returns the turn's id. */
    private fun personSays(hub: CodexSessionHub, recorder: Recorder, text: String): String {
        hub.prompt(TAB, text, echo = echo(text))
        return underWay(hub, uuidOf(said(recorder).last()))
    }

    /**
     * Words written into a running turn that would not take them in (FakeCodex refuses every steer, as Codex refuses
     * one into a review or a compaction), with a message queued after them: the end of the turn starts ONE turn, with
     * the words written into it, and the queue waits for that turn's end.
     */
    fun testWhatTheTurnWouldNotTakeGoesBeforeTheQueueAndAlone() {
        val recorder = Recorder("desk")
        val hub = hub(recorder)

        val first = personSays(hub, recorder, "Review the branch")
        hub.prompt(TAB, "and mind the tests", echo = echo("and mind the tests"))
        val steeredUuid = uuidOf(said(recorder).last())
        hub.queuePrompt(TAB, UUID.randomUUID().toString(), "and then the docs", echo = echo("and then the docs"))
        codex.settled(conversation(hub))
        assertEquals(listOf("and mind the tests"), codex.steered(thread(hub)))
        assertEquals(listOf("and mind the tests"), conversation(hub).unsent().map { it.text })

        codexSays(hub, FakeCodex.turnCompleted(thread(hub), first))

        assertEquals(listOf("Review the branch", "and mind the tests"), turns(hub))
        assertTrue(conversation(hub).unsent().isEmpty())
        // Under the name it was sent with: its card names it the same for a rewind or a fork.
        val second = underWay(hub, steeredUuid)

        codexSays(hub, FakeCodex.turnCompleted(thread(hub), second))

        assertEquals(listOf("Review the branch", "and mind the tests", "and then the docs"), turns(hub))
    }

    /**
     * A plan approved while a message waits in the queue: the turn the approval asks for goes first and alone, the
     * queued message after it. The approval used to end the planning turn - which let the queue out - and start its
     * own turn on a timer a moment later, beside the queue's.
     */
    fun testAnApprovedPlanIsCarriedOutBeforeTheQueue() {
        val recorder = Recorder("desk")
        val hub = hub(recorder)

        hub.conversations.setPermissionMode(TAB, PermissionModes.PLAN) {}
        val planning = personSays(hub, recorder, "Plan the move of the totals")
        codexSays(
            hub,
            """{"method":"item/completed","params":{"threadId":"${thread(hub)}","turnId":"$planning",""" +
                """"item":{"type":"plan","id":"plan-1","text":"1. Read the totals\n2. Move them"}}}""",
        )
        codexSays(hub, FakeCodex.turnCompleted(thread(hub), planning))
        assertTrue("the plan holds the turn's end back", conversation(hub).waitsOnPlan)

        hub.queuePrompt(TAB, UUID.randomUUID().toString(), "and then the docs", echo = echo("and then the docs"))
        assertEquals(1, turns(hub).size)

        hub.permissions.decidePlan(TAB, "plan-1", "approve")

        assertEquals(listOf("Plan the move of the totals", CodexLaunch.IMPLEMENT_PLAN), turns(hub))
        // Out of plan mode, said to Codex with the turn itself.
        val implementing = codex.requests("turn/start", thread(hub)).last()
        assertTrue(implementing.toString(), implementing.toString().contains("\"mode\":\"default\""))
        assertEquals(PermissionModes.ACCEPT_EDITS, hub.conversations.permissionMode(TAB))
    }

    /**
     * The limit refused a turn the person had written into: the words wait with the queue for the reset, and then go
     * in their place - after the panel's "carry on", which finishes the work they were written into, and before the
     * queued message. They used to go the moment the refusal ended the turn, into the same limit.
     */
    fun testALimitHoldsTheWordsAndTheyGoAfterTheCarryOnAndBeforeTheQueue() {
        val recorder = Recorder("desk")
        val hub = hub(recorder)

        val first = personSays(hub, recorder, "Move the totals onto the new money type")
        hub.prompt(TAB, "and mind the tests", echo = echo("and mind the tests"))
        val steeredUuid = uuidOf(said(recorder).last())
        hub.queuePrompt(TAB, UUID.randomUUID().toString(), "and then the invoices", echo = echo("and then the invoices"))
        codex.settled(conversation(hub))

        codexSays(hub, FakeCodex.fiveHoursFull(System.currentTimeMillis() / 1000 + 600))
        codexSays(hub, FakeCodex.turnRefused(thread(hub), first))

        val planned = hub.conversations.carryOnOf(TAB)!!
        assertEquals(CarryOns.Reason.LIMIT, planned.reason)
        assertEquals(1, turns(hub).size)

        hub.runQueued(TAB, now = planned.notBefore!!)
        val atTheReset = turns(hub)
        assertEquals(atTheReset.joinToString("\n"), 2, atTheReset.size)
        assertTrue(atTheReset.last(), atTheReset.last().startsWith("<${CarryOnWords.TAG}"))

        val carried = underWay(hub, uuidOf(said(recorder).last()))
        codexSays(hub, FakeCodex.turnCompleted(thread(hub), carried))
        assertEquals("and mind the tests", turns(hub).last())

        codexSays(hub, FakeCodex.turnCompleted(thread(hub), underWay(hub, steeredUuid)))
        assertEquals("and then the invoices", turns(hub).last())
        assertEquals(4, turns(hub).size)
    }
}
