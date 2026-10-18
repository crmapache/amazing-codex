package io.github.crmapache.amazingcodex.codex

import io.github.crmapache.amazingcodex.editor.ChangeRevert
import io.github.crmapache.amazingcodex.editor.LineDiff
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A turn's files held against the disk at its end (see TurnFiles). The disk is a map here, changed between the items
 * the way Codex 0.163 changes it: still before the patch at `item/started`, after it at `item/completed`.
 */
class TurnFilesTest {

    private val disk = HashMap<String, String>()
    private val files = TurnFiles { disk[it] }

    private fun item(id: String, status: String, vararg changes: String): JsonObject =
        Json.parseToJsonElement("""{"type":"fileChange","id":"$id","status":"$status","changes":[${changes.joinToString(",")}]}""").jsonObject

    private fun update(path: String, diff: String, movedTo: String? = null): String =
        """{"path":"$path","kind":{"type":"update","move_path":${movedTo?.let { "\"$it\"" } ?: "null"}},"diff":${Json.encodeToString(String.serializer(), diff)}}"""

    private fun add(path: String, text: String): String =
        """{"path":"$path","kind":{"type":"add"},"diff":${Json.encodeToString(String.serializer(), text)}}"""

    private fun delete(path: String, text: String): String =
        """{"path":"$path","kind":{"type":"delete"},"diff":${Json.encodeToString(String.serializer(), text)}}"""

    /** One patch as Codex runs it: started over the disk as it is, applied, completed. */
    private fun patch(id: String, vararg changes: String, apply: () -> Unit) {
        files.started(item(id, "inProgress", *changes))
        apply()
        files.completed(item(id, "completed", *changes))
    }

    private fun settledFiles(): List<JsonObject> {
        val settlement = files.settle() ?: return emptyList()
        val line = Json.parseToJsonElement(settlement.line).jsonObject
        assertEquals(TurnFiles.SETTLED_TYPE, line["type"]!!.jsonPrimitive.content)
        return line["files"]!!.jsonArray.map { it.jsonObject }
    }

    private fun editsOf(file: JsonObject): List<ChangeRevert.Edit> =
        ChangeRevert.requestsOf(JsonArray(listOf(file))).single().edits

    @Test
    fun `a turn whose patches say what stands on the disk is not settled`() {
        disk["/p/a.txt"] = "one\ntwo\n"
        patch("f1", update("/p/a.txt", "@@ -1,2 +1,2 @@\n one\n-two\n+TWO\n")) { disk["/p/a.txt"] = "one\nTWO\n" }
        assertNull(files.settle())
    }

    /** The probe of 0.163: a patch to `gamma`, then the agent's own `sed` to `beta` - the disk says beta. */
    @Test
    fun `a sed after the patch is settled from the file the turn found to the disk`() {
        disk["/p/note.txt"] = "alpha\n"
        patch("f1", update("/p/note.txt", "@@ -1 +1 @@\n-alpha\n+gamma\n")) { disk["/p/note.txt"] = "gamma\n" }
        disk["/p/note.txt"] = "beta\n"

        val file = settledFiles().single()
        assertEquals("/p/note.txt", file["path"]!!.jsonPrimitive.content)
        assertEquals(1, file["added"]!!.jsonPrimitive.content.toInt())
        assertEquals(1, file["removed"]!!.jsonPrimitive.content.toInt())
        // Put back by what was settled, the file is as the turn found it.
        assertEquals(ChangeRevert.Result.Text("alpha\n"), ChangeRevert.revert("beta\n", editsOf(file)))
    }

    /** A formatter run as a hook after each edit: the file the turn created came out longer than the patch wrote it. */
    @Test
    fun `a created file a formatter rewrote is created empty and filled`() {
        patch("f1", add("/p/style.css", "a{color:red}\n")) { disk["/p/style.css"] = "a{color:red}\n" }
        disk["/p/style.css"] = "a {\n  color: red;\n}\n"

        val file = settledFiles().single()
        val edits = editsOf(file)
        assertEquals(ChangeRevert.Edit.Create(""), edits.first())
        assertEquals(3, file["added"]!!.jsonPrimitive.content.toInt())
        assertEquals(ChangeRevert.Result.Delete, ChangeRevert.revert("a {\n  color: red;\n}\n", edits))
        assertEquals(ChangeRevert.Result.Text("a {\n  color: red;\n}\n"), ChangeRevert.bringBack(null, edits))
    }

    @Test
    fun `a read that came after the patch is the base less the patch`() {
        disk["/p/a.txt"] = "x\n"
        // The disk already holds the patch when the start is heard.
        disk["/p/a.txt"] = "y\n"
        patch("f1", update("/p/a.txt", "@@ -1 +1 @@\n-x\n+y\n")) {}
        disk["/p/a.txt"] = "y;\n"

        val file = settledFiles().single()
        assertEquals(ChangeRevert.Result.Text("x\n"), ChangeRevert.revert("y;\n", editsOf(file)))
    }

    @Test
    fun `a file whose patches do not go onto what was read is not settled`() {
        disk["/p/a.txt"] = "something else\n"
        patch("f1", update("/p/a.txt", "@@ -1 +1 @@\n-x\n+y\n")) { disk["/p/a.txt"] = "z\n" }
        assertNull(files.settle())
    }

    @Test
    fun `a moved file is settled where it went, by where it came from`() {
        disk["/p/a.kt"] = "val a=1\n"
        patch("f1", update("/p/a.kt", "@@ -1 +1 @@\n-val a=1\n+val b=1\n", movedTo = "/p/b.kt")) {
            disk.remove("/p/a.kt")
            disk["/p/b.kt"] = "val b=1\n"
        }
        disk["/p/b.kt"] = "val b = 1\n"

        val file = settledFiles().single()
        assertEquals("/p/b.kt", file["path"]!!.jsonPrimitive.content)
        val edits = editsOf(file)
        assertEquals(ChangeRevert.Edit.Move("/p/a.kt"), edits.first())
        assertEquals(ChangeRevert.Result.Text("val a=1\n"), ChangeRevert.revert("val b = 1\n", edits))
    }

    @Test
    fun `a deleted file gone as the turn left it, and a patch that did not apply, are nothing to settle`() {
        disk["/p/old.txt"] = "x\n"
        patch("f1", delete("/p/old.txt", "x\n")) { disk.remove("/p/old.txt") }
        disk["/p/a.txt"] = "1\n"
        files.started(item("f2", "inProgress", update("/p/a.txt", "@@ -1 +1 @@\n-1\n+2\n")))
        files.completed(item("f2", "declined", update("/p/a.txt", "@@ -1 +1 @@\n-1\n+2\n")))
        assertNull(files.settle())
    }

    @Test
    fun `a patch whose start was never heard has no base`() {
        disk["/p/a.txt"] = "2;\n"
        files.completed(item("f1", "completed", update("/p/a.txt", "@@ -1 +1 @@\n-1\n+2\n")))
        assertNull(files.settle())
    }

    @Test
    fun `two patches of one file in a turn are settled as one change, and the next turn starts afresh`() {
        disk["/p/a.txt"] = "a\nb\n"
        patch("f1", update("/p/a.txt", "@@ -1,2 +1,2 @@\n-a\n+A\n b\n")) { disk["/p/a.txt"] = "A\nb\n" }
        patch("f2", update("/p/a.txt", "@@ -1,2 +1,2 @@\n A\n-b\n+B\n")) { disk["/p/a.txt"] = "A\nB\n" }
        disk["/p/a.txt"] = "A\nB\nformatted\n"

        val file = settledFiles().single()
        assertEquals(ChangeRevert.Result.Text("a\nb\n"), ChangeRevert.revert("A\nB\nformatted\n", editsOf(file)))
        assertNull(files.settle())
    }

    @Test
    fun `the phone is sent the counts without the edits`() {
        disk["/p/note.txt"] = "alpha\n"
        patch("f1", update("/p/note.txt", "@@ -1 +1 @@\n-alpha\n+gamma\n")) { disk["/p/note.txt"] = "gamma\n" }
        disk["/p/note.txt"] = "beta\n"
        val line = files.settle()!!.line
        val envelope = """{"type":"agent","sessionId":"main","event":$line}"""

        val light = TurnFiles.lightened(envelope)!!
        assertFalse(light.contains("\"edits\""))
        assertTrue(light.contains("\"added\":1"))
        assertNull(TurnFiles.lightened("""{"type":"agent","event":{"type":"result"}}"""))
    }

    /** The diff the settling sends goes there and back: what [LineDiff] writes, [ChangeRevert] undoes and redoes. */
    @Test
    fun `a worked-out diff goes there and back`() {
        val before = "a\nb\nc\nd\ne\nf\ng\nh\n"
        val after = "a\nB\nc\nd\ne\nf\nG\nh\nend"
        val hunks = LineDiff.hunks(before, after)!!
        val edits = listOf(ChangeRevert.Edit.Patch(hunks.map { ChangeRevert.Hunk(it.newStart, it.lines, it.oldStart) }))
        assertEquals(ChangeRevert.Result.Text(before), ChangeRevert.undo(after, edits))
        val redone = ChangeRevert.redo(before, edits)
        assertIs<ChangeRevert.Result.Text>(redone)
        assertEquals(after, redone.text)
    }
}
