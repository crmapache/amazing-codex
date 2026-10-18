package io.github.crmapache.amazingcodex.codex

import com.intellij.openapi.util.io.FileUtil
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
 * What an agent the conversation spawned patches is the turn's changed files too (see CodexSession.subagentNotification):
 * listed under the agent's card while the turn is open, and in the next turn's list when it comes after the turn's end -
 * Codex never wakes the parent for its agent, so that next turn is the person's.
 *
 * On a process that speaks Codex's protocol (see FakeCodex); what the agents' threads say is told the way Codex 0.163
 * tells it, through the same reader a live process's line goes through.
 */
class SubagentChangesTest : BasePlatformTestCase() {

    private class Recorder(override val id: String) : SessionClient {
        val received = CopyOnWriteArrayList<String>()

        override fun deliver(messages: List<String>) {
            received += messages
        }
    }

    private lateinit var codex: FakeCodex

    /** A tab of this test's own - the light project, and with it the hub, outlives a test. */
    private val TAB = "subagent-${UUID.randomUUID()}"

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

    private fun codexSays(hub: CodexSessionHub, line: String) = conversation(hub).heardFromCodex(line)

    private fun said(recorder: Recorder): List<String> =
        recorder.received.filter { it.contains("\"type\":\"promptEcho\"") && it.contains("\"sessionId\":\"$TAB\"") }

    private fun uuidOf(echo: String): String =
        ((Json.parseToJsonElement(echo) as JsonObject)["uuid"] as JsonPrimitive).contentOrNull!!

    /** The person's message into the tab, its turn under way. Returns the turn's id. */
    private fun personSays(hub: CodexSessionHub, recorder: Recorder, text: String): String {
        hub.prompt(TAB, text, echo = echo(text))
        val uuid = uuidOf(said(recorder).last())
        assertTrue("the message never reached Codex", FakeCodex.waitFor { conversation(hub).turnOf(uuid) != null })
        val turn = conversation(hub).turnOf(uuid)!!
        codexSays(hub, FakeCodex.turnStarted(thread(hub), turn))
        return turn
    }

    /** The lines this tab's clients were told, in order. */
    private fun lines(recorder: Recorder): List<String> = recorder.received.filter { it.contains("\"sessionId\":\"$TAB\"") }

    /** A patch an agent's thread [agent] made of [file] - started over the disk as it is, applied, completed. */
    private fun agentPatches(hub: CodexSessionHub, agent: String, id: String, file: File, before: String, after: String) {
        val item = """{"type":"fileChange","id":"$id","changes":[{"path":"${file.path}","kind":{"type":"update","move_path":null},""" +
            """"diff":"@@ -1 +1 @@\n-${before.trimEnd()}\n+${after.trimEnd()}\n"}],"status":"%s"}"""
        codexSays(hub, """{"method":"item/started","params":{"threadId":"$agent","turnId":"agent-turn","item":${item.format("inProgress")}}}""")
        file.writeText(after)
        codexSays(hub, """{"method":"item/completed","params":{"threadId":"$agent","turnId":"agent-turn","item":${item.format("completed")}}}""")
    }

    private fun spawned(hub: CodexSessionHub, turn: String, call: String, agent: String) {
        codexSays(
            hub,
            """{"method":"thread/started","params":{"thread":{"id":"$agent","parentThreadId":"${thread(hub)}","preview":"","ephemeral":false}}}""",
        )
        codexSays(
            hub,
            """{"method":"item/completed","params":{"threadId":"${thread(hub)}","turnId":"$turn","item":{"type":"collabAgentToolCall",""" +
                """"id":"$call","tool":"spawnAgent","status":"completed","senderThreadId":"${thread(hub)}","receiverThreadIds":["$agent"],""" +
                """"prompt":"Rename the totals","agentsStates":{}}}}""",
        )
    }

    private fun resultOf(lines: List<String>, file: File): Int =
        lines.indexOfFirst { it.contains("\"parent_tool_use_id\":") && it.contains("\"filePath\":\"${file.path}\"") }

    fun testAnAgentsPatchIsListedUnderItsCardWhileTheTurnIsOpenAndAfterItInTheNextOne() {
        val recorder = Recorder("desk")
        val hub = hub(recorder)
        val early = File(FileUtil.getTempDirectory(), "early-${UUID.randomUUID()}.txt").apply { writeText("a\n") }
        val late = File(FileUtil.getTempDirectory(), "late-${UUID.randomUUID()}.txt").apply { writeText("x\n") }
        val deep = File(FileUtil.getTempDirectory(), "deep-${UUID.randomUUID()}.txt").apply { writeText("p\n") }

        val first = personSays(hub, recorder, "Have an agent rename the totals")
        spawned(hub, first, "spawn-1", "agent-1")
        agentPatches(hub, "agent-1", "af1", early, "a\n", "b\n")
        // An agent the agent spawned works under the same card.
        codexSays(
            hub,
            """{"method":"thread/started","params":{"thread":{"id":"agent-2","parentThreadId":"agent-1","preview":"","ephemeral":false}}}""",
        )
        agentPatches(hub, "agent-2", "af2", deep, "p\n", "q\n")

        val during = lines(recorder)
        val earlyAt = resultOf(during, early)
        assertTrue(during.joinToString("\n"), earlyAt >= 0)
        assertTrue(during[earlyAt], during[earlyAt].contains("\"parent_tool_use_id\":\"spawn-1\""))
        assertTrue(during[earlyAt], during[earlyAt].contains("\"unified_diff\""))
        assertTrue(resultOf(during, deep) >= 0)
        assertTrue(lines(recorder)[resultOf(during, deep)].contains("\"parent_tool_use_id\":\"spawn-1\""))

        codexSays(hub, FakeCodex.turnCompleted(thread(hub), first))
        assertTrue(FakeCodex.waitFor { lines(recorder).any { it.contains("\"type\":\"result\"") } })

        // The agent goes on after its parent's turn: its patch waits for the next turn's list.
        agentPatches(hub, "agent-1", "af3", late, "x\n", "y\n")
        assertEquals("not said into a closed list", -1, resultOf(lines(recorder), late))

        personSays(hub, recorder, "Thanks, carry on")
        val after = lines(recorder)
        val lateAt = resultOf(after, late)
        assertTrue(after.joinToString("\n"), lateAt >= 0)
        assertTrue("after the next message, in its turn", lateAt > after.indexOfLast { it.contains("\"type\":\"promptEcho\"") })
    }
}
