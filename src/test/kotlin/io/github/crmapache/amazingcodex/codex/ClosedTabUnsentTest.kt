package io.github.crmapache.amazingcodex.codex

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * A tab closed while its conversation still had something to say keeps it for when it is opened again.
 *
 * Two kinds of message wait for a turn's end: the queue the hub keeps, and the words written into a turn that
 * would not take them in (a review, a compaction - see CodexSession.steer), which the conversation keeps itself.
 * The queue came back with the tab, held for the person's next turn; the other kind went with the process, unsaid
 * and unmentioned, though its card had been in the feed. On a process that speaks Codex's protocol (see FakeCodex).
 */
class ClosedTabUnsentTest : BasePlatformTestCase() {

    private class Recorder(override val id: String) : SessionClient {
        val received = CopyOnWriteArrayList<String>()

        override fun deliver(messages: List<String>) {
            received += messages
        }
    }

    private lateinit var codex: FakeCodex

    /** A tab of this test's own - the light project, and with it the hub, outlives a test. */
    private val TAB = "unsent-${UUID.randomUUID()}"

    override fun setUp() {
        super.setUp()
        project.basePath?.let { File(it).mkdirs() }
        codex = FakeCodex()
    }

    override fun tearDown() {
        try {
            runCatching { CodexSessionHub.getInstance(project).closeSession(TAB) }
            codex.close()
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

    /** The tab's queue as every client was last told it: its messages' words, and whether it waits for the person. */
    private fun queueShown(recorder: Recorder): Pair<List<String>, Boolean> {
        val last = recorder.received.map(::json).last { text(it["type"]) == "queue" && text(it["sessionId"]) == TAB }
        val items = (last["items"] as JsonArray).map { text((it as JsonObject)["text"]) }
        return items to (last["held"] == JsonPrimitive(true))
    }

    fun testWordsATurnWouldNotTakeComeBackWithTheClosedTabHeld() {
        val recorder = Recorder("desk")
        val hub = CodexSessionHub.getInstance(project).also {
            it.register(recorder)
            it.attach(recorder.id)
        }

        hub.prompt(TAB, "Review the branch", echo = echo("Review the branch"))
        val first = recorder.received.map(::json).last { text(it["type"]) == "promptEcho" && text(it["sessionId"]) == TAB }
        val conversation = hub.conversations.conversation(TAB)!!
        val uuid = text(first["uuid"])
        assertTrue(FakeCodex.waitFor { conversation.turnOf(uuid) != null })
        val thread = hub.conversations.conversationIdOf(TAB)!!
        conversation.heardFromCodex(FakeCodex.turnStarted(thread, conversation.turnOf(uuid)!!))

        // Sent into the running turn, which will not take it: it waits for the turn's end inside the conversation.
        hub.prompt(TAB, "and mind the tests", echo = echo("and mind the tests"))
        // Queued for after the turn: it waits in the hub.
        hub.queuePrompt(TAB, UUID.randomUUID().toString(), "and then the docs", echo = echo("and then the docs"))
        codex.settled(conversation)
        assertEquals(listOf("and mind the tests"), codex.steered(thread))
        assertEquals(listOf("and mind the tests"), conversation.unsent().map { it.text })

        hub.closeSession(TAB, byPerson = true)
        val back = hub.reopenTab(emptySet())

        assertEquals(TAB, (back as CodexSessionHub.Reopened.Chat).sessionId)
        // Both came back, held for the person's next turn, and the one sent into the turn ahead of the queue.
        assertEquals(listOf("and mind the tests", "and then the docs") to true, queueShown(recorder))

        // The person writes again, and after their turn the held messages go - one turn each, in their order.
        hub.prompt(TAB, "go on", echo = echo("go on"))
        assertEquals(listOf("and mind the tests", "and then the docs") to false, queueShown(recorder))
        val reopened = hub.conversations.conversation(TAB)!!
        val goOn = text(recorder.received.map(::json).last { text(it["type"]) == "promptEcho" && text(it["sessionId"]) == TAB }["uuid"])
        assertTrue(FakeCodex.waitFor { reopened.turnOf(goOn) != null })
        val newThread = hub.conversations.conversationIdOf(TAB)!!
        reopened.heardFromCodex(FakeCodex.turnStarted(newThread, reopened.turnOf(goOn)!!))
        reopened.heardFromCodex(FakeCodex.turnCompleted(newThread, reopened.turnOf(goOn)!!))

        codex.settled(reopened)
        assertEquals(listOf("go on", "and mind the tests"), codex.turnsStarted(newThread))
        // Under the name it was sent with the first time, so its card and a rewind to it name it the same.
        val again = recorder.received.map(::json).last { text(it["type"]) == "promptEcho" && text(it["sessionId"]) == TAB }
        val steered = recorder.received.map(::json).first {
            text(it["type"]) == "promptEcho" && text(it["sessionId"]) == TAB && it.toString().contains("and mind the tests")
        }
        assertEquals(text(steered["uuid"]), text(again["uuid"]))
    }
}
