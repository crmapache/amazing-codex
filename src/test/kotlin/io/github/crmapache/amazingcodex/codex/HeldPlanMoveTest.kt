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
 * A plan waiting for a decision, through the real hub, while the conversation's process is replaced under it - and
 * decided afterwards, from a phone or by a message typed past it.
 *
 * Codex ends a plan-mode turn with the plan, and the decision is a turn the panel starts (see CodexSession.HeldPlan),
 * so the plan goes on with the conversation into its next process. The process here is replaced by the renewal road
 * (CodexSessions.relaunchOn - the same account's drawer replaced): it is the move that raises the next process on the
 * default sign-in, which a test can stand on, and it is the same move an account switch makes (see
 * CarryOnSessionsTest for that one). On a process that speaks Codex's protocol (see FakeCodex).
 */
class HeldPlanMoveTest : BasePlatformTestCase() {

    private class Recorder(override val id: String, override val isLocal: Boolean) : SessionClient {
        val received = CopyOnWriteArrayList<String>()

        override fun deliver(messages: List<String>) {
            received += messages
        }
    }

    private lateinit var codex: FakeCodex

    private val TAB = "held-plan-${UUID.randomUUID()}"

    override fun setUp() {
        super.setUp()
        project.basePath?.let { File(it).mkdirs() }
        codex = FakeCodex()
    }

    override fun tearDown() {
        try {
            runCatching { CodexSessionHub.getInstance(project).closeSession(TAB) }
            codex.close()
            CodexPreferences.mode = ""
        } finally {
            super.tearDown()
        }
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

    private fun json(line: String): JsonObject = Json.parseToJsonElement(line) as JsonObject

    private fun text(element: Any?): String = (element as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun conversation(hub: CodexSessionHub): CodexSession = hub.conversations.conversation(TAB)!!

    private fun thread(hub: CodexSessionHub): String = hub.conversations.conversationIdOf(TAB)!!

    /** The messages of [type] about this tab, as [recorder] was told them. */
    private fun told(recorder: Recorder, type: String): List<JsonObject> =
        recorder.received.map(::json).filter { text(it["type"]) == type && text(it["sessionId"]) == TAB }

    /**
     * A tab in plan mode whose turn has proposed a plan and ended: the card waits for a decision, and the thread has
     * its file (FakeCodex writes none - see CodexHistory.standIn). Returns the hub, with [desk] attached.
     */
    private fun planWaiting(desk: Recorder): CodexSessionHub {
        val hub = CodexSessionHub.getInstance(project).also {
            it.register(desk)
            it.attach(desk.id)
        }
        hub.conversations.setPermissionMode(TAB, PermissionModes.PLAN) {}
        hub.prompt(TAB, "Plan the move of the totals", echo = echo("Plan the move of the totals"))
        val uuid = text(told(desk, "promptEcho").last()["uuid"])
        assertTrue(FakeCodex.waitFor { conversation(hub).turnOf(uuid) != null })
        val turn = conversation(hub).turnOf(uuid)!!
        val conversation = conversation(hub)
        conversation.heardFromCodex(FakeCodex.turnStarted(thread(hub), turn))
        conversation.heardFromCodex(
            """{"method":"item/completed","params":{"threadId":"${thread(hub)}","turnId":"$turn",""" +
                """"item":{"type":"plan","id":"plan-1","text":"1. Read the totals\n2. Move them"}}}""",
        )
        conversation.heardFromCodex(FakeCodex.turnCompleted(thread(hub), turn))
        assertTrue(conversation.waitsOnPlan)
        CodexHistory.standIn(thread(hub), File.createTempFile("rollout-", ".jsonl").apply { deleteOnExit() })
        return hub
    }

    /** The process replaced under the waiting plan, the plan still standing: nothing ended, nothing withdrawn. */
    private fun replaced(hub: CodexSessionHub, desk: Recorder): CodexSession {
        val before = conversation(hub)
        val statuses = told(desk, "status").size

        hub.conversations.relaunchOn("")

        val after = conversation(hub)
        assertNotSame("moved at once - a plan is no turn to wait for", before, after)
        assertTrue(after.waitsOnPlan)
        assertTrue(hub.conversations.isAwaitingPermission(TAB, "plan-plan-1"))
        assertTrue("its card was never taken back", told(desk, "planResolved").isEmpty())
        assertEquals("and the tab was never said to be free", statuses, told(desk, "status").size)
        assertTrue(told(desk, "turnStopped").isEmpty())
        return after
    }

    /**
     * Approved from a phone after the move: the new process comes up over the same thread, and the turn the approval
     * asks for goes into it - out of plan mode, which the new process has never sent and so says (see
     * CodexSession.adopt).
     */
    fun testAPlanCarriedToTheNextProcessIsApprovedFromThePhone() {
        val desk = Recorder("desk", isLocal = true)
        val hub = planWaiting(desk)
        val oldThread = thread(hub)

        val after = replaced(hub, desk)

        val phone = Recorder("phone", isLocal = false)
        hub.register(phone)
        hub.commands.handle(
            phone.id,
            buildJsonObject {
                put("type", "planDecision")
                put("sessionId", TAB)
                put("id", "plan-1")
                put("decision", "approve")
            },
        )

        assertTrue(FakeCodex.waitFor { codex.turnsStarted(oldThread).size == 2 })
        codex.settled(after)
        assertEquals(listOf("Plan the move of the totals", CodexLaunch.IMPLEMENT_PLAN), codex.turnsStarted(oldThread))
        assertEquals(1, codex.requests("thread/resume", oldThread).size)
        val implementing = codex.requests("turn/start", oldThread).last().toString()
        assertTrue(implementing, implementing.contains("\"mode\":\"default\""))
        assertEquals("approve", text(told(desk, "planResolved").single()["decision"]))
        assertFalse(after.waitsOnPlan)
    }

    /**
     * A message typed past the plan after the move - from a phone, which has no plan card to remark on - sets the plan
     * aside and is the next turn, alone: the plan's end does not let a queued message in ahead of it.
     */
    fun testAMessagePastACarriedPlanIsTheNextTurnAlone() {
        val desk = Recorder("desk", isLocal = true)
        val hub = planWaiting(desk)
        val oldThread = thread(hub)
        hub.queuePrompt(TAB, UUID.randomUUID().toString(), "and then the docs", echo = echo("and then the docs"))

        val after = replaced(hub, desk)
        hub.prompt(TAB, "Do it with a migration instead", echo = echo("Do it with a migration instead"))

        assertTrue(FakeCodex.waitFor { codex.turnsStarted(oldThread).size == 2 })
        codex.settled(after)
        assertEquals(listOf("Plan the move of the totals", "Do it with a migration instead"), codex.turnsStarted(oldThread))
        assertFalse(after.waitsOnPlan)
    }
}
