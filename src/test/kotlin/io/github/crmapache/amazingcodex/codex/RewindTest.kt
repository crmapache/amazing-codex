package io.github.crmapache.amazingcodex.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RewindTest {

    private fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `undone patches come to the dialog's states`() {
        assertEquals(Rewind.Code.None, Rewind.codeOf(CodeRewind.Plan.None))
        assertEquals(
            Rewind.Code.Ready(listOf("/p/a.txt"), 2, 1),
            Rewind.codeOf(CodeRewind.Plan.Ready(mapOf("/p/a.txt" to "x\n"), insertions = 2, deletions = 1)),
        )
        assertEquals(Rewind.Code.Changed(listOf("/p/b.txt")), Rewind.codeOf(CodeRewind.Plan.Conflict(listOf("/p/b.txt"))))
    }

    // The detail of a code refusal is the preview's own state, so the panel words it as the dialog did.
    @Test
    fun `a code refusal carries the state, and the changed files by name`() {
        assertEquals("none", Rewind.detailOf(Rewind.Code.None))
        assertEquals("changed:a.txt, b.kt", Rewind.detailOf(Rewind.Code.Changed(listOf("/p/a.txt", "/p/src/b.kt"))))
        assertEquals("no answer", Rewind.detailOf(Rewind.Code.Unavailable("no answer")))
    }

    @Test
    fun `the preview of files changed since says them from the project's folder`() {
        val code = Rewind.relativeTo(Rewind.Code.Changed(listOf("/home/me/app/src/a.ts", "/tmp/x")), "/home/me/app", "/home/me")
        val sent = json(Rewind.previewJson("tab", "u-1", code))["code"]!!.jsonObject

        assertEquals("changed", sent["state"]!!.jsonPrimitive.content)
        assertEquals(listOf("src/a.ts", "/tmp/x"), sent["files"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(2, sent["count"]!!.jsonPrimitive.content.toInt())
    }

    // Codex answers a refusal with an error, so its code and words are what the reason is read from.
    @Test
    fun `Codex's refusals become the panel's reasons`() {
        assertEquals(Rewind.Refusal.ENDED, Rewind.Refusal.ofError(AppServer.PROCESS_GONE, "codex was stopped"))
        assertEquals(Rewind.Refusal.BUSY, Rewind.Refusal.ofError(AppServer.TIMED_OUT, "thread/revert timed out"))
        assertEquals(Rewind.Refusal.UNSUPPORTED, Rewind.Refusal.ofError(AppServer.METHOD_NOT_FOUND, "unknown method"))
        assertEquals(Rewind.Refusal.UNSUPPORTED, Rewind.Refusal.ofError(-32600, "unknown variant `thread/revert`"))
        assertEquals(Rewind.Refusal.BUSY, Rewind.Refusal.ofError(-32600, "cannot revert while a turn is in progress"))
        assertEquals(Rewind.Refusal.GONE, Rewind.Refusal.ofError(-32600, "turn not found in thread"))
        assertEquals(Rewind.Refusal.OTHER, Rewind.Refusal.ofError(-32600, "something else"))
    }

    // Nothing but a uuid passes as a message's name.
    @Test
    fun `only a uuid names a message`() {
        assertTrue(Rewind.isUuid("2fd136cf-b840-41f5-938f-5eaa3a7c2738"))
        assertFalse(Rewind.isUuid("2fd136cf"))
        assertFalse(Rewind.isUuid("2fd136cf-b840-41f5-938f-5eaa3a7c2738\"}"))
        assertFalse(Rewind.isUuid(null))
    }

    @Test
    fun `the preview says files from the project's folder, from home past it, and leaves the rest whole`() {
        val files = listOf("/home/me/app/src/a.ts", "/home/me/.codex/notes.md", "/tmp/scratch.txt", "/home/me/application.txt")
        val code = Rewind.relativeTo(Rewind.Code.Ready(files, 3, 1), "/home/me/app", "/home/me")
        val sent = json(Rewind.previewJson("tab", "u-1", code))["code"]!!.jsonObject

        assertEquals("ready", sent["state"]!!.jsonPrimitive.content)
        assertEquals(
            listOf("src/a.ts", "~/.codex/notes.md", "/tmp/scratch.txt", "~/application.txt"),
            sent["files"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(4, sent["count"]!!.jsonPrimitive.content.toInt())
    }

    // The IDE says the project's folder with "/" on Windows too, Codex says its files with "\\", and the home
    // directory comes from the JVM with "\\" and maybe another case of the drive letter.
    @Test
    fun `a Windows path is said from its folders whichever separator each side uses`() {
        val code = Rewind.relativeTo(
            Rewind.Code.Ready(listOf("C:\\work\\app\\src\\a.ts", "C:\\Users\\me\\.codex\\note.md", "C:\\work\\apple\\b.ts"), 1, 0),
            "C:/work/app",
            "c:\\Users\\me",
        ) as Rewind.Code.Ready

        assertEquals(listOf("src\\a.ts", "~\\.codex\\note.md", "C:\\work\\apple\\b.ts"), code.files)
    }

    @Test
    fun `the outcome says what went, and a refusal says why`() {
        val done = json(Rewind.outcomeJson("tab", "u-1", Rewind.Outcome.Done(true, "hello", Rewind.Files.RESTORED, listOf("/a"))))
        assertEquals(true, done["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("restored", done["files"]!!.jsonPrimitive.content)
        assertEquals("hello", done["prefill"]!!.jsonPrimitive.content)

        val refused = json(Rewind.outcomeJson("tab", "u-1", Rewind.Outcome.Refused(Rewind.Refusal.MOVED, "")))
        assertEquals(false, refused["ok"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("moved", refused["reason"]!!.jsonPrimitive.content)
    }

    // Said into the tab's feed as a code the panel words (see feed/rewind.ts, forkCodeOf), like FORK_WHOLE.
    @Test
    fun `code a fork could not take along is said as a code with its reason`() {
        assertEquals("FORK_CODE|busy|", Rewind.forkCodeError(Rewind.Outcome.Refused(Rewind.Refusal.BUSY, "")))
        assertEquals(
            "FORK_CODE|code|changed:a.txt",
            Rewind.forkCodeError(Rewind.Outcome.Refused(Rewind.Refusal.CODE, "changed:a.txt")),
        )
    }

    @Test
    fun `the cut is said with where the journal's cut began`() {
        val rewound = json(Rewind.rewoundJson("tab", "u-1", 42))
        assertEquals("rewound", rewound["type"]!!.jsonPrimitive.content)
        assertEquals(42, rewound["fromSeq"]!!.jsonPrimitive.content.toInt())
    }
}
