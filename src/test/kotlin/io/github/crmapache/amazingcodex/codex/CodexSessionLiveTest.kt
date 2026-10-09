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

    /**
     * A rewind against a real Codex: the conversation cut back by `thread/revert` and the agent's patches
     * undone, a rewind refused over a message the asker has not seen, and a fork cut through a chosen turn -
     * each the way the panel asks for it.
     */
    fun testARewindCutsTheConversationPutsTheCodeBackAndAForkEndsWhereItWasCut() {
        if (!enabled) return
        val directory = Files.createTempDirectory("acx-live-rewind").toFile()
        try {
            rewindAndFork(directory)
        } finally {
            forgetTrust(directory)
            archiveThread()
            directory.deleteRecursively()
        }
    }

    private fun rewindAndFork(directory: File) {
        ProcessBuilder("git", "init", "-q").directory(directory).start().waitFor()
        val notes = File(directory, "notes.txt")
        val first = java.util.UUID.randomUUID().toString()
        val second = java.util.UUID.randomUUID().toString()

        val recorder = Recorder()
        val session = session(directory, recorder, PermissionModes.ACCEPT_EDITS)
        session.sendPrompt("Using apply_patch (not the shell), create notes.txt containing exactly the line: one. Reply OK.", uuid = first)
        assertTrue("the first turn did not end: ${recorder.errors}", recorder.turnEnded.await(240, TimeUnit.SECONDS))
        assertEquals("one", notes.readText().trim())
        thread = session.conversationId

        recorder.turnEnded = CountDownLatch(1)
        session.sendPrompt(
            "Using apply_patch, change the line in notes.txt from one to two. Also remember the code word BANANA. Reply OK.",
            uuid = second,
        )
        assertTrue(recorder.turnEnded.await(240, TimeUnit.SECONDS))
        assertEquals("two", notes.readText().trim())

        // What the dialog is shown before anything is touched.
        val preview = java.util.concurrent.LinkedBlockingQueue<Rewind.Code>()
        session.previewRewind(second) { preview += it }
        val code = preview.poll(120, TimeUnit.SECONDS)
        assertTrue("the preview was $code", code is Rewind.Code.Ready && code.files.any { it.endsWith("notes.txt") })

        // A message the asker has not seen stops the rewind: it names the first message as its newest.
        val refused = java.util.concurrent.LinkedBlockingQueue<Rewind.Outcome>()
        session.rewind(second, lastSeen = first, conversation = true, files = false) { refused += it }
        assertEquals(Rewind.Refusal.MOVED, (refused.poll(120, TimeUnit.SECONDS) as? Rewind.Outcome.Refused)?.refusal)

        // The rewind itself: the second message and what came of it leave the conversation and the disk.
        val outcomes = java.util.concurrent.LinkedBlockingQueue<Rewind.Outcome>()
        session.rewind(second, lastSeen = second, conversation = true, files = true) { outcomes += it }
        val done = outcomes.poll(180, TimeUnit.SECONDS)
        assertTrue("the rewind came to $done", done is Rewind.Outcome.Done)
        done as Rewind.Outcome.Done
        assertEquals(Rewind.Files.RESTORED, done.files)
        assertTrue(done.prefill.contains("from one to two"))
        assertEquals("one", notes.readText().trim())

        recorder.turnEnded = CountDownLatch(1)
        recorder.lines.clear()
        session.sendPrompt("Did I give you a code word in this conversation? If so, say it; otherwise reply NONE.")
        assertTrue(recorder.turnEnded.await(240, TimeUnit.SECONDS))
        assertFalse("the agent still remembers the cut part", recorder.lines.any { it.contains("BANANA") && it.contains("\"assistant\"") })

        // The history reads the thread as Codex resumes it - from the file the revert started, not the old one.
        val pool = com.intellij.openapi.application.ApplicationManager.getApplication()
        val threadId = session.conversationId!!
        val page = pool.executeOnPooledThread<CodexHistory.Page> { CodexHistory.opening(directory.absolutePath, threadId) }.get()
        assertTrue(page.lines.any { it.contains("create notes.txt") })
        assertFalse(page.lines.any { it.contains("from one to two") })
        val listed = pool.executeOnPooledThread<List<CodexHistory.Entry>> { CodexHistory.list(directory.absolutePath) }.get()
        assertEquals(1, listed.count { it.id == threadId })

        // A fork through the first turn carries that turn and nothing after it.
        val firstTurn = session.turnOf(first)
        assertNotNull(firstTurn)
        session.stop()
        val forked = Recorder()
        var born: String? = null
        val fork = CodexSession(
            workingDirectory = directory.absolutePath,
            origin = lazyOf(ForkOrigin.Resolved(ForkOrigin(threadId, "Rewind test", cut = true, at = firstTurn))),
            onForked = { id, _ -> born = id },
            model = model,
            effort = "low",
            permissionMode = PermissionModes.ACCEPT_EDITS,
            nameWanted = false,
            onEvent = { forked.lines += it },
            onError = { forked.errors += it },
            onFinished = {},
            onTurnEnded = { forked.turnEnded.countDown() },
        ).also { com.intellij.openapi.util.Disposer.register(testRootDisposable, it) }
        fork.sendPrompt("What file did you create in this conversation? Answer with the file name only.")
        assertTrue("the fork's turn did not end: ${forked.errors}", forked.turnEnded.await(240, TimeUnit.SECONDS))
        assertNotNull(born)
        assertTrue(born != threadId)
        assertTrue(forked.lines.any { it.contains("notes.txt") })
        val forkTurns = pool.executeOnPooledThread<List<CodexHistory.TurnRef>?> { CodexHistory.turnRefs(born!!) }.get().orEmpty()
        assertEquals(listOf(firstTurn), forkTurns.dropLast(1).map { it.id })
        fork.stop()
        pool.executeOnPooledThread {
            CodexCatalog.call("thread/archive", kotlinx.serialization.json.buildJsonObject { put("threadId", kotlinx.serialization.json.JsonPrimitive(born!!)) })
        }.get()
        CodexCatalog.shutdown()
    }
}
