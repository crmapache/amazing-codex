package io.github.crmapache.amazingcodex.codex

import com.intellij.openapi.util.io.FileUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.crmapache.amazingcodex.editor.ChangeRevert
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
 * A file put back from a turn's list is told to the agent before its next turn (see PutBack).
 *
 * Claude Code notices by itself that a file it read has changed outside; Codex does not, and the agent would go on by
 * the text it remembers. On a process that speaks Codex's protocol (see FakeCodex), so what is checked is what reached
 * Codex: the note, as a developer message put into the thread, and only then the person's next turn.
 */
class FilesPutBackTest : BasePlatformTestCase() {

    private class Recorder(override val id: String) : SessionClient {
        val received = CopyOnWriteArrayList<String>()

        override fun deliver(messages: List<String>) {
            received += messages
        }
    }

    private lateinit var codex: FakeCodex

    /** A tab of this test's own - the light project, and with it the hub, outlives a test. */
    private val TAB = "put-back-${UUID.randomUUID()}"

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

    private fun said(recorder: Recorder): List<String> =
        recorder.received.filter { it.contains("\"type\":\"promptEcho\"") && it.contains("\"sessionId\":\"$TAB\"") }

    private fun uuidOf(echo: String): String =
        ((Json.parseToJsonElement(echo) as JsonObject)["uuid"] as JsonPrimitive).contentOrNull!!

    /** The person's message into the tab and its turn, over. */
    private fun turn(hub: CodexSessionHub, recorder: Recorder, text: String) {
        hub.prompt(TAB, text, echo = echo(text))
        val uuid = uuidOf(said(recorder).last())
        assertTrue("the message never reached Codex", FakeCodex.waitFor { conversation(hub).turnOf(uuid) != null })
        val id = conversation(hub).turnOf(uuid)!!
        conversation(hub).heardFromCodex(FakeCodex.turnStarted(thread(hub), id))
        conversation(hub).heardFromCodex(FakeCodex.turnCompleted(thread(hub), id))
    }

    /** The methods that reached Codex on this tab's thread, in order. */
    private fun methods(hub: CodexSessionHub): List<String> {
        codex.settled(conversation(hub))
        return codex.requests().filter { text((it["params"] as? JsonObject)?.get("threadId")) == thread(hub) }.map { text(it["method"]) }
    }

    /**
     * A file of this test's own, where the IDE lets a test touch the disk: the light project outlives a test, and its
     * folder is the first test's, which the next one may not read.
     */
    private fun notesFile(): File = File(FileUtil.getTempDirectory(), "notes-${UUID.randomUUID()}.txt")

    private fun text(element: Any?): String = (element as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** The text of each developer message put into the thread. */
    private fun notes(hub: CodexSessionHub): List<String> = codex.requests("thread/inject_items", thread(hub)).map { request ->
        val items = (request["params"] as JsonObject)["items"] as JsonArray
        val content = (items.single() as JsonObject)["content"] as JsonArray
        text((content.single() as JsonObject)["text"])
    }

    fun testARevertedFileIsToldToTheAgentBeforeItsNextTurnAndOnlyOnce() {
        val recorder = Recorder("desk")
        val hub = hub(recorder)
        val file = notesFile().apply { writeText("one\nTWO\nthree\n") }

        turn(hub, recorder, "Shout the second line")
        hub.revertChanges(
            TAB,
            "changes-p1",
            listOf(ChangeRevert.FileRequest(file.path, listOf(ChangeRevert.Edit.Patch(listOf(ChangeRevert.Hunk(1, listOf(" one", "-two", "+TWO", " three"))))))),
        )
        val answered = FakeCodex.waitFor { recorder.received.any { it.contains("\"type\":\"changesReverted\"") } }
        assertTrue("the revert was never answered", answered)
        val outcome = recorder.received.last { it.contains("\"type\":\"changesReverted\"") }
        assertTrue(outcome, outcome.contains("\"outcome\":\"reverted\""))
        assertEquals("one\ntwo\nthree\n", file.readText())
        assertTrue("nothing is said before a turn goes", notes(hub).isEmpty())

        turn(hub, recorder, "Now the third")

        val note = notes(hub).single()
        assertTrue(note, note.startsWith(PutBack.INTRODUCTION))
        assertTrue(note, note.lines().any { it.startsWith("- ") && it.endsWith(file.name) })
        val order = methods(hub)
        assertTrue(order.toString(), order.indexOf("thread/inject_items") < order.lastIndexOf("turn/start"))

        turn(hub, recorder, "And the fourth")
        assertEquals("the note is said once", 1, notes(hub).size)
    }

    /**
     * A turn the person stopped after its patch, with the agent's own `sed` over the file in between (the probe of 0.163):
     * Codex closes a stopped turn with `turn/completed` too, and the turn's real change of the file reaches the panel
     * before the result that closes its list (see TurnFiles).
     */
    fun testAStoppedTurnIsSettledAgainstTheDiskBeforeItsResult() {
        val recorder = Recorder("desk")
        val hub = hub(recorder)
        val file = notesFile().apply { writeText("alpha\n") }
        hub.prompt(TAB, "Make it gamma", echo = echo("Make it gamma"))
        val uuid = uuidOf(said(recorder).last())
        assertTrue(FakeCodex.waitFor { conversation(hub).turnOf(uuid) != null })
        val turn = conversation(hub).turnOf(uuid)!!
        val session = conversation(hub)
        session.heardFromCodex(FakeCodex.turnStarted(thread(hub), turn))

        val patch = """{"type":"fileChange","id":"f1","changes":[{"path":"${file.path}","kind":{"type":"update","move_path":null},""" +
            """"diff":"@@ -1 +1 @@\n-alpha\n+gamma\n"}],"status":"%s"}"""
        session.heardFromCodex("""{"method":"item/started","params":{"threadId":"${thread(hub)}","turnId":"$turn","item":${patch.format("inProgress")}}}""")
        file.writeText("gamma\n")
        session.heardFromCodex("""{"method":"item/completed","params":{"threadId":"${thread(hub)}","turnId":"$turn","item":${patch.format("completed")}}}""")
        file.writeText("beta\n")
        session.heardFromCodex(FakeCodex.turnInterrupted(thread(hub), turn))

        assertTrue(FakeCodex.waitFor { recorder.received.any { it.contains("\"type\":\"result\"") && it.contains("\"sessionId\":\"$TAB\"") } })
        val lines = recorder.received.filter { it.contains("\"sessionId\":\"$TAB\"") }
        val settledAt = lines.indexOfFirst { it.contains("\"type\":\"files_settled\"") }
        val resultAt = lines.indexOfLast { it.contains("\"type\":\"result\"") }
        assertTrue(lines.joinToString("\n"), settledAt in 0 until resultAt)
        assertTrue(lines[settledAt], lines[settledAt].contains(file.name) && lines[settledAt].contains("\"added\":1"))
    }

    /** Put back and brought back again before the next turn: the file is as the agent left it, and nothing is said. */
    fun testAFileRevertedAndBroughtBackIsNoNews() {
        val recorder = Recorder("desk")
        val hub = hub(recorder)
        val file = notesFile().apply { writeText("a\n") }
        val edits = listOf(ChangeRevert.Edit.Patch(listOf(ChangeRevert.Hunk(1, listOf("-z", "+a")))))

        turn(hub, recorder, "Write a")
        hub.revertChanges(TAB, "changes-p2", listOf(ChangeRevert.FileRequest(file.path, edits)))
        assertTrue(FakeCodex.waitFor { file.readText() == "z\n" })
        hub.revertChanges(TAB, "changes-p2", listOf(ChangeRevert.FileRequest(file.path, edits)), restore = true)
        assertTrue(FakeCodex.waitFor { recorder.received.any { it.contains("\"restored\"") } })

        turn(hub, recorder, "Carry on")
        assertTrue(notes(hub).toString(), notes(hub).isEmpty())
    }

    /**
     * The revert pressed as the turn ended and the next words right after (caught in the sandbox): the IDE answers the
     * press seconds later, busy taking in the turn's files, and a turn started meanwhile went without the note. It waits
     * for the answer now.
     */
    fun testATurnWaitsForARevertStillOnItsWay() {
        val recorder = Recorder("desk")
        val hub = hub(recorder)
        val file = notesFile().apply { writeText("one\n") }

        turn(hub, recorder, "Write one")
        val startsBefore = methods(hub).count { it == "turn/start" }
        hub.conversations.putBackPressed(TAB)
        hub.prompt(TAB, "Now the next", echo = echo("Now the next"))

        Thread.sleep(300)
        assertEquals("the turn waits for the press", startsBefore, methods(hub).count { it == "turn/start" })

        hub.conversations.notePutBack(TAB, file.path, PutBack.Way.REVERTED)
        hub.conversations.putBackAnswered(TAB)

        assertTrue("the turn never went", FakeCodex.waitFor { methods(hub).count { it == "turn/start" } > startsBefore })
        val order = methods(hub)
        assertTrue(order.toString(), order.lastIndexOf("thread/inject_items") < order.lastIndexOf("turn/start"))
        assertTrue(notes(hub).single().lines().any { it.endsWith(file.name) })
    }
}
