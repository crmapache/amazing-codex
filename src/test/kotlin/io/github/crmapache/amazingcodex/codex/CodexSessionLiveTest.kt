package io.github.crmapache.amazingcodex.codex

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The engine against a real Codex - a conversation raised, spoken to, asked for permission, resumed and
 * read back from the history, the way the panel does all of it.
 *
 * Off unless `/tmp/acx-live-codex` exists: it spends real turns on the signed-in account, and an ordinary
 * test run has no business doing that. Turned on by hand when the engine changes:
 *
 *     touch /tmp/acx-live-codex && ./gradlew test --tests '*CodexSessionLiveTest*'
 */
class CodexSessionLiveTest : BasePlatformTestCase() {

    private val enabled get() = File("/tmp/acx-live-codex").exists()

    /** The cheapest model the account lists, when the flag file names one; the account's default otherwise. */
    private val model get() = File("/tmp/acx-live-codex").readText().trim()

    private class Recorder {
        val lines = CopyOnWriteArrayList<String>()
        val errors = CopyOnWriteArrayList<String>()
        val permissions = CopyOnWriteArrayList<PermissionChannel.ToolPermission>()
        @Volatile var turnEnded = CountDownLatch(1)
        @Volatile var asked = CountDownLatch(1)

        fun types(): List<String> = lines.map { line ->
            val event = Json.parseToJsonElement(line).jsonObject
            listOfNotNull(AppServer.text(event["type"]), AppServer.text(event["subtype"]).ifEmpty { null }).joinToString(":")
        }

        fun toolNames(): List<String> = lines.flatMap { line ->
            val event = Json.parseToJsonElement(line).jsonObject
            if (AppServer.text(event["type"]) != "assistant") return@flatMap emptyList()
            val content = (event["message"] as? JsonObject)?.get("content") as? kotlinx.serialization.json.JsonArray ?: return@flatMap emptyList()
            content.mapNotNull { block ->
                val b = block as? JsonObject ?: return@mapNotNull null
                if (AppServer.text(b["type"]) == "tool_use") AppServer.text(b["name"]) else null
            }
        }
    }

    private fun session(directory: File, recorder: Recorder, mode: String, resumeFrom: String? = null) = CodexSession(
        workingDirectory = directory.absolutePath,
        resumeFrom = resumeFrom,
        model = model,
        effort = "low",
        permissionMode = mode,
        nameWanted = false,
        onEvent = { recorder.lines += it },
        onError = { recorder.errors += it },
        onFinished = {},
        onToolPermission = { request ->
            recorder.permissions += request
            recorder.asked.countDown()
        },
        onTurnEnded = { recorder.turnEnded.countDown() },
    ).also { com.intellij.openapi.util.Disposer.register(testRootDisposable, it) }

    /**
     * Codex marks the folder of a thread's first turn trusted in the person's own `config.toml`. The test's
     * folder is a temporary one, and a line naming it would be left in that config after every run.
     */
    private fun forgetTrust(directory: File) {
        val config = File(HostOs.configDirectory(), "config.toml")
        if (!config.isFile) return
        val header = Regex.escape("[projects.\"${directory.canonicalPath}\"]")
        val block = Regex("$header\ntrust_level = \"trusted\"\n\n?")
        val text = config.readText()
        if (block.containsMatchIn(text)) config.writeText(block.replace(text, ""))
    }

    fun testAConversationRunsAsksAndComesBack() {
        if (!enabled) return
        val directory = Files.createTempDirectory("acx-live").toFile()
        try {
            converse(directory)
        } finally {
            forgetTrust(directory)
            archiveThread()
            directory.deleteRecursively()
        }
    }

    /** The thread the test spoke in - archived afterwards, so a run leaves nothing in the person's history. */
    @Volatile
    private var thread: String? = null

    private fun archiveThread() {
        val id = thread ?: return
        val pool = com.intellij.openapi.application.ApplicationManager.getApplication()
        pool.executeOnPooledThread {
            CodexCatalog.call("thread/archive", kotlinx.serialization.json.buildJsonObject { put("threadId", kotlinx.serialization.json.JsonPrimitive(id)) })
            CodexCatalog.shutdown()
        }.get()
    }

    private fun converse(directory: File) {
        File(directory, "a.txt").writeText("hello from the file\n")
        ProcessBuilder("git", "init", "-q").directory(directory).start().waitFor()

        // 1. A plain answer.
        val recorder = Recorder()
        val session = session(directory, recorder, PermissionModes.ACCEPT_EDITS)
        session.sendPrompt("Reply with exactly the word: pong")
        assertTrue("the first turn did not end: ${recorder.errors}", recorder.turnEnded.await(180, TimeUnit.SECONDS))
        assertEquals(emptyList<String>(), recorder.errors)
        val types = recorder.types()
        assertEquals("system:init", types.first())
        assertTrue("no answer in $types", "assistant" in types)
        assertEquals("result:success", types.last())
        assertTrue(recorder.lines.any { it.contains("pong", ignoreCase = true) })
        val threadId = session.conversationId
        assertNotNull(threadId)
        thread = threadId

        // 2. Tools: a read and a new file, without questions in the default mode.
        recorder.turnEnded = CountDownLatch(1)
        recorder.lines.clear()
        session.sendPrompt("Run `cat a.txt`, then create b.txt containing the word bye. Reply with one short sentence.")
        assertTrue(recorder.turnEnded.await(240, TimeUnit.SECONDS))
        val tools = recorder.toolNames()
        assertTrue("no read or shell card in $tools", tools.any { it == "Read" || it == "Bash" })
        assertTrue("no file card in $tools", tools.any { it == "Write" || it == "Edit" })
        assertTrue(File(directory, "b.txt").isFile)
        assertTrue(recorder.types().any { it == "user" })

        // 3. A question: in "ask" mode a command stops the turn until the person answers.
        session.setPermissionMode(PermissionModes.ASK) {}
        recorder.turnEnded = CountDownLatch(1)
        recorder.asked = CountDownLatch(1)
        session.sendPrompt("Run `ls` in the project and tell me how many files there are.")
        assertTrue("nothing was asked", recorder.asked.await(240, TimeUnit.SECONDS))
        val request = recorder.permissions.last()
        assertEquals("Bash", request.toolName)
        assertTrue(session.isAwaitingPermission(request.requestId))
        session.answerPermission(request.requestId, allow = true)
        assertTrue(recorder.turnEnded.await(240, TimeUnit.SECONDS))
        assertFalse(session.isAwaitingPermission(request.requestId))

        // 4. A new process resumes the same thread, and it remembers.
        session.stop()
        val again = Recorder()
        val resumed = session(directory, again, PermissionModes.ACCEPT_EDITS, resumeFrom = threadId)
        resumed.sendPrompt("What single word did you reply with at the very start of this conversation? Answer with that word only.")
        assertTrue(again.turnEnded.await(180, TimeUnit.SECONDS))
        assertEquals(threadId, resumed.conversationId)
        assertTrue(again.lines.any { it.contains("pong", ignoreCase = true) })
        resumed.stop()

        // 5. The history reads it back in the panel's language, the person's words included.
        val pool = com.intellij.openapi.application.ApplicationManager.getApplication()
        val page = pool.executeOnPooledThread<CodexHistory.Page> { CodexHistory.opening(directory.absolutePath, threadId!!) }.get()
        assertTrue(page.lines.isNotEmpty())
        assertTrue(page.lines.any { it.contains("Reply with exactly the word") })
        assertTrue(page.lines.any { it.contains("\"type\":\"result\"") })
        val listed = pool.executeOnPooledThread<List<CodexHistory.Entry>> { CodexHistory.list(directory.absolutePath) }.get()
        assertTrue(listed.any { it.id == threadId })

        CodexCatalog.shutdown()
    }
}
