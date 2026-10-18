package io.github.crmapache.amazingcodex.editor

import io.github.crmapache.amazingcodex.editor.ChangeRevert.Edit
import io.github.crmapache.amazingcodex.editor.ChangeRevert.Hunk
import io.github.crmapache.amazingcodex.editor.ChangeRevert.Result
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Undoing a turn's changes to a file by its own diffs.
 *
 * The two things worth holding still: whatever else happened to the file since stays as it is, and a file
 * whose changed lines were touched again is left alone rather than guessed at - both ways, undoing a turn and
 * bringing it back.
 */
class ChangeRevertTest {

    private fun patch(newStart: Int, vararg lines: String) = Edit.Patch(listOf(Hunk(newStart, lines.toList())))

    @Test
    fun `an edit is taken back`() {
        val undone = ChangeRevert.undo("one\nTWO\nthree\n", listOf(patch(1, " one", "-two", "+TWO", " three")))
        assertEquals(Result.Text("one\ntwo\nthree\n"), undone)
    }

    @Test
    fun `lines added above the edit since do not stop it, and stay`() {
        val undone = ChangeRevert.undo("new\nnewer\none\nTWO\nthree\n", listOf(patch(1, " one", "-two", "+TWO", " three")))
        assertEquals(Result.Text("new\nnewer\none\ntwo\nthree\n"), undone)
    }

    @Test
    fun `an edit whose lines were changed again is not guessed at`() {
        val undone = ChangeRevert.undo("one\nTwo!\nthree\n", listOf(patch(1, " one", "-two", "+TWO", " three")))
        assertEquals(Result.Changed, undone)
    }

    @Test
    fun `several hunks and several edits are taken back newest first`() {
        val edits = listOf(
            Edit.Patch(
                listOf(
                    Hunk(1, listOf("-a", "+A", " b")),
                    Hunk(4, listOf(" d", "-e", "+E")),
                ),
            ),
            patch(2, " b", "+inserted", " c"),
        )
        assertEquals(Result.Text("a\nb\nc\nd\ne\n"), ChangeRevert.undo("A\nb\ninserted\nc\nd\nE\n", edits))
    }

    @Test
    fun `a file the turn created is deleted while it is still what the turn left`() {
        val created = listOf(Edit.Create("FROM node:22\nCOPY . .\n"))
        assertEquals(Result.Delete, ChangeRevert.undo("FROM node:22\nCOPY . .\n", created))
        assertEquals(Result.Changed, ChangeRevert.undo("FROM node:22\nCOPY . .\nRUN make\n", created))
    }

    @Test
    fun `a file created and then edited in the same turn is deleted too`() {
        val edits = listOf(Edit.Create("package home\n"), patch(1, " package home", "+", "+import x"))
        assertEquals(Result.Delete, ChangeRevert.undo("package home\n\nimport x\n", edits))
    }

    @Test
    fun `a write over a file gets the old text back, and how it ended`() {
        val written = patch(1, "-one", "-two", "-three", "+z", "\\ No newline at end of file")
        assertEquals(Result.Text("one\ntwo\nthree\n"), ChangeRevert.undo("z", listOf(written)))
    }

    @Test
    fun `a file that ended without a line break gets that back`() {
        val edit = patch(1, " a", "-b", "\\ No newline at end of file", "+b", "+c")
        assertEquals(Result.Text("a\nb"), ChangeRevert.undo("a\nb\nc\n", listOf(edit)))
    }

    @Test
    fun `an edit taken back is brought back`() {
        val edit = patch(1, " one", "-two", "+TWO", " three")
        assertEquals(Result.Text("one\nTWO\nthree\n"), ChangeRevert.redo("one\ntwo\nthree\n", listOf(edit)))
    }

    @Test
    fun `bringing back finds the lines where they moved to, and refuses where they changed`() {
        val edit = Edit.Patch(listOf(Hunk(newStart = 1, lines = listOf(" one", "-two", "+TWO", " three"), oldStart = 1)))
        assertEquals(Result.Text("new\none\nTWO\nthree\n"), ChangeRevert.redo("new\none\ntwo\nthree\n", listOf(edit)))
        assertEquals(Result.Changed, ChangeRevert.redo("one\nzwei\nthree\n", listOf(edit)))
    }

    @Test
    fun `a deleted file the turn created is written anew, with what the turn did to it after`() {
        val edits = listOf(Edit.Create("package home\n"), patch(1, " package home", "+", "+import x"))
        assertEquals(Result.Text("package home\n\nimport x\n"), ChangeRevert.redo(null, edits))
    }

    @Test
    fun `a file standing where the created one was is somebody else's`() {
        val edits = listOf(Edit.Create("FROM node:22\n"))
        assertEquals(Result.Text("FROM node:22\n"), ChangeRevert.redo("FROM node:22\n", edits))
        assertEquals(Result.Changed, ChangeRevert.redo("something else\n", edits))
    }

    @Test
    fun `a revert and its bringing back come to the file the turn left`() {
        val edits = listOf(
            Edit.Patch(listOf(Hunk(1, listOf("-a", "+A", " b")), Hunk(4, listOf(" d", "-e", "+E"), oldStart = 4))),
            patch(2, " b", "+inserted", " c"),
        )
        val left = "A\nb\ninserted\nc\nd\nE\n"
        val undone = ChangeRevert.undo(left, edits) as Result.Text
        assertEquals(Result.Text(left), ChangeRevert.redo(undone.text, edits))
    }

    @Test
    fun `a write over a file without a final line break comes back without one`() {
        val written = patch(1, "-one", "-two", "-three", "+z", "\\ No newline at end of file")
        assertEquals(Result.Text("z"), ChangeRevert.redo("one\ntwo\nthree\n", listOf(written)))
    }

    /** An IDE restarted under a reverted file forgets the mark, and the row offers the revert again. */
    @Test
    fun `a file already reverted is answered as reverted, with nothing to write`() {
        val edit = listOf(patch(1, " one", "-two", "+TWO", " three"))
        assertEquals(Result.Text("one\ntwo\nthree\n"), ChangeRevert.revert("one\ntwo\nthree\n", edit))
        assertEquals(Result.Changed, ChangeRevert.revert("one\nzwei\nthree\n", edit))
        // A created file that is still there is not reverted, whatever is in it.
        assertEquals(Result.Changed, ChangeRevert.revert("something\n", listOf(Edit.Create("FROM node:22\n"))))
    }

    /** Caught live: the agent's appended line rewritten by hand read as "reverted" - its context was all still there. */
    @Test
    fun `a line appended and then rewritten by hand is changed, not reverted`() {
        val file = "a\nb\nc\nd\ne\n"
        val appended = listOf(patch(3, " c", " d", " e", "+Final check line."))
        assertEquals(Result.Changed, ChangeRevert.revert(file + "Final check line, edited by hand.\n", appended))
        // Truly reverted, it is still recognised as such.
        assertEquals(Result.Text(file), ChangeRevert.revert(file, appended))
        // And a real undo is not held to the edge: a line somebody added under the agent's goes on standing.
        assertEquals(Result.Text(file + "mine\n"), ChangeRevert.undo(file + "Final check line.\nmine\n", appended))
    }

    @Test
    fun `changes already back are answered as brought back`() {
        val edit = listOf(patch(1, " one", "-two", "+TWO", " three"))
        assertEquals(Result.Text("one\nTWO\nthree\n"), ChangeRevert.bringBack("one\nTWO\nthree\n", edit))
        assertEquals(Result.Changed, ChangeRevert.bringBack("one\nzwei\nthree\n", edit))
    }

    @Test
    fun `the request is read, and anything malformed is left out`() {
        val files = Json.parseToJsonElement(
            """
            [
              {"path": "/p/a.kt", "edits": [{"kind": "patch", "hunks": [{"oldStart": 2, "newStart": 3, "lines": [" x", "+y"]}]}]},
              {"path": "/p/b.kt", "edits": [{"kind": "create", "content": "hi"}]},
              {"path": "/p/c.kt", "edits": [{"kind": "patch", "hunks": [{"lines": ["+y"]}]}]},
              {"path": "", "edits": [{"kind": "create", "content": "hi"}]},
              {"path": "/p/d.kt", "edits": []}
            ]
            """,
        )
        val requests = ChangeRevert.requestsOf(files)
        assertEquals(listOf("/p/a.kt", "/p/b.kt"), requests.map { it.path })
        assertEquals(Edit.Patch(listOf(Hunk(3, listOf(" x", "+y"), oldStart = 2))), requests[0].edits.single())
        assertEquals(Edit.Create("hi"), requests[1].edits.single())
    }

    // --- A Codex patch can delete a file and move one -------------------------------------

    @Test
    fun `a deleted file is written back with the text it held, and deleted again only while it holds it`() {
        val deleted = listOf(Edit.Delete("gone\nfor good\n"))
        assertEquals(Result.Text("gone\nfor good\n"), ChangeRevert.revert(null, deleted))
        assertEquals(Result.Delete, ChangeRevert.bringBack("gone\nfor good\n", deleted))
        // Somebody wrote into it after the revert: bringing the deletion back would throw that away.
        assertEquals(Result.Changed, ChangeRevert.bringBack("gone\nfor good\nand more\n", deleted))
    }

    @Test
    fun `a deleted file already back reads as reverted, and one already gone as brought back`() {
        val deleted = listOf(Edit.Delete("x\n"))
        assertEquals(Result.Text("x\n"), ChangeRevert.revert("x\n", deleted))
        assertEquals(Result.Changed, ChangeRevert.revert("y\n", deleted))
        assertEquals(Result.Delete, ChangeRevert.bringBack(null, deleted))
    }

    @Test
    fun `a file patched and then deleted in one turn comes back as it was before the patch`() {
        val edits = listOf(patch(1, "-a", "+b"), Edit.Delete("b\n"))
        assertEquals(Result.Text("a\n"), ChangeRevert.revert(null, edits))
        assertEquals(Result.Delete, ChangeRevert.bringBack("a\n", edits))
    }

    @Test
    fun `a created file somebody has since removed is reverted already, and brought back by writing it`() {
        val created = listOf(Edit.Create("hi\n"), patch(1, "-hi", "+hello"))
        assertEquals(Result.Delete, ChangeRevert.revert(null, created))
        assertEquals(Result.Text("hello\n"), ChangeRevert.bringBack(null, created))
    }

    @Test
    fun `a move is nothing to the text, and the patch after it is`() {
        val edits = listOf(Edit.Move("/p/a.kt"), patch(1, "-x", "+y"))
        assertEquals(Result.Text("x\n"), ChangeRevert.revert("y\n", edits))
        assertEquals(Result.Text("y\n"), ChangeRevert.bringBack("x\n", edits))
        assertTrue(ChangeRevert.isUndone("x\n", edits))
        assertTrue(ChangeRevert.isRedone("y\n", edits))
    }

    @Test
    fun `a delete and a move are read off the request`() {
        val requests = ChangeRevert.requestsOf(
            Json.parseToJsonElement(
                """
                [
                  {"path": "/p/gone.kt", "edits": [{"kind": "delete", "content": "x"}]},
                  {"path": "/p/b.kt", "edits": [{"kind": "move", "from": "/p/a.kt"}]},
                  {"path": "/p/c.kt", "edits": [{"kind": "move", "from": ""}]}
                ]
                """,
            ),
        )
        assertEquals(listOf(Edit.Delete("x")), requests[0].edits)
        assertEquals(listOf(Edit.Move("/p/a.kt")), requests[1].edits)
        assertEquals(2, requests.size)
    }

    @Test
    fun `an outcome of bringing back says so`() {
        val json = ChangeRevert.outcomeJson("main", "changes-1", listOf("/p/a.kt" to ChangeRevert.Outcome.RESTORED), restore = true)
        assertTrue(json.contains("\"restore\":true"))
        assertTrue(json.contains("{\"path\":\"/p/a.kt\",\"outcome\":\"restored\"}"))
    }

    @Test
    fun `the outcome names every file`() {
        val json = ChangeRevert.outcomeJson(
            "main",
            "changes-1",
            listOf("/p/a.kt" to ChangeRevert.Outcome.REVERTED, "/p/b.kt" to ChangeRevert.Outcome.CHANGED),
        )
        assertTrue(json.contains("\"type\":\"changesReverted\""))
        assertTrue(json.contains("{\"path\":\"/p/a.kt\",\"outcome\":\"reverted\"}"))
        assertTrue(json.contains("{\"path\":\"/p/b.kt\",\"outcome\":\"changed\"}"))
    }
}
