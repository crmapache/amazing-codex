package io.github.crmapache.amazingcodex.codex

import io.github.crmapache.amazingcodex.editor.ChangeRevert
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** The patches below are the shapes Codex 0.160 gave in live turns (see CodeRewind). */
class CodeRewindTest {

    private fun update(path: String, diff: String, movedTo: String? = null, turn: String = "") =
        CodeRewind.Change(path, CodeRewind.Kind.UPDATE, movedTo, diff, turn)

    /** [changes] undone over files that hold [files] - the plan's text for [path]. */
    private fun undone(path: String, files: Map<String, String>, vararg changes: CodeRewind.Change): String? {
        val plan = CodeRewind.plan(changes.toList()) { files[it] }
        assertIs<CodeRewind.Plan.Ready>(plan)
        return plan.files[path]
    }

    @Test
    fun `an update is undone line for line`() {
        val plan = CodeRewind.plan(listOf(update("/p/a.txt", "@@ -1 +1,2 @@\n one\n+two\n"))) { "one\ntwo\n" }
        assertIs<CodeRewind.Plan.Ready>(plan)
        assertEquals("one\n", plan.files["/p/a.txt"])
        assertEquals(0, plan.insertions)
        assertEquals(1, plan.deletions)
    }

    // A move's diff ends with Codex's own line about it, which is no line of the file.
    @Test
    fun `a move is undone without the line Codex writes after it`() {
        val diff = "@@ -1,3 +1,3 @@\n alpha\n-beta\n+BETA\n gamma\n\n\nMoved to: /p/moved.txt"
        val files = mapOf("/p/moved.txt" to "alpha\nBETA\ngamma\n")
        val plan = CodeRewind.plan(listOf(update("/p/mv.txt", diff, movedTo = "/p/moved.txt"))) { files[it] }

        assertIs<CodeRewind.Plan.Ready>(plan)
        assertEquals("alpha\nbeta\ngamma\n", plan.files["/p/mv.txt"])
        assertTrue(plan.files.containsKey("/p/moved.txt"))
        assertNull(plan.files["/p/moved.txt"])
    }

    @Test
    fun `an added file goes, a deleted one comes back`() {
        val changes = listOf(
            CodeRewind.Change("/p/del.txt", CodeRewind.Kind.DELETE, null, "to be deleted\nline2\n"),
            CodeRewind.Change("/p/new.txt", CodeRewind.Kind.ADD, null, "first\nsecond\n"),
        )
        val files = mapOf("/p/new.txt" to "first\nsecond\n")
        val plan = CodeRewind.plan(changes) { files[it] }

        assertIs<CodeRewind.Plan.Ready>(plan)
        assertEquals("to be deleted\nline2\n", plan.files["/p/del.txt"])
        assertTrue(plan.files.containsKey("/p/new.txt"))
        assertNull(plan.files["/p/new.txt"])
        assertEquals(2, plan.insertions)
        assertEquals(2, plan.deletions)
    }

    // Two patches of one file are undone newest first, each on the text the other left.
    @Test
    fun `patches of one file are undone in turn`() {
        val changes = listOf(
            CodeRewind.Change("/p/a.txt", CodeRewind.Kind.ADD, null, "one\n"),
            update("/p/a.txt", "@@ -1 +1,2 @@\n one\n+two\n"),
            update("/p/a.txt", "@@ -1,2 +1,2 @@\n one\n-two\n+TWO\n"),
        )
        val plan = CodeRewind.plan(changes) { if (it == "/p/a.txt") "one\nTWO\n" else null }

        assertIs<CodeRewind.Plan.Ready>(plan)
        assertNull(plan.files["/p/a.txt"])
    }

    // Something else wrote over what the patch left: undoing it would mangle the file, so nothing is done.
    @Test
    fun `a file changed since stops the restore`() {
        val plan = CodeRewind.plan(listOf(update("/p/a.txt", "@@ -1 +1,2 @@\n one\n+two\n"))) { "something else entirely\n" }
        assertEquals(CodeRewind.Plan.Conflict(listOf("/p/a.txt")), plan)

        val readded = CodeRewind.plan(listOf(CodeRewind.Change("/p/d.txt", CodeRewind.Kind.DELETE, null, "x\n"))) { "back again\n" }
        assertEquals(CodeRewind.Plan.Conflict(listOf("/p/d.txt")), readded)
    }

    // A hunk is looked for near its place when lines above it moved since, as long as it is there whole.
    @Test
    fun `a hunk found a little further down still fits`() {
        val text = undone("/p/a.txt", mapOf("/p/a.txt" to "new head\none\ntwo\nlast\n"), update("/p/a.txt", "@@ -1,2 +1,3 @@\n one\n+two\n last\n"))
        assertEquals("new head\none\nlast\n", text)
    }

    @Test
    fun `a missing last line break is kept the way the diff says`() {
        val diff = "@@ -1,2 +1,2 @@\n one\n-two\n\\ No newline at end of file\n+TWO\n"
        assertEquals("one\ntwo", undone("/p/a.txt", mapOf("/p/a.txt" to "one\nTWO\n"), update("/p/a.txt", diff)))
    }

    @Test
    fun `a deletion of lines is put back where they were`() {
        assertEquals(
            "a\nb\nc\nd\n",
            undone("/p/a.txt", mapOf("/p/a.txt" to "a\nd\n"), update("/p/a.txt", "@@ -1,4 +1,2 @@\n a\n-b\n-c\n d\n")),
        )
    }

    // --- Rewound past what the list of a turn's files put back already (see ChangeRevert) ---------------------------

    /**
     * The person put a turn back from its list, then rewound to a message before it. The list and the rewind are one
     * engine, so what the list undid reads as undone: the created file gone, the deleted file back, the patch taken
     * out - and the turn before it is undone over that.
     */
    @Test
    fun `a turn put back from its list and then rewound past is undone already`() {
        val changes = listOf(
            update("/p/a.txt", "@@ -1,2 +1,2 @@\n one\n-two\n+TWO\n", turn = "t1"),
            update("/p/a.txt", "@@ -1,2 +1,3 @@\n one\n TWO\n+three\n", turn = "t2"),
            CodeRewind.Change("/p/new.txt", CodeRewind.Kind.ADD, null, "made\n", turn = "t2"),
            CodeRewind.Change("/p/gone.txt", CodeRewind.Kind.DELETE, null, "kept\n", turn = "t2"),
        )
        // The list of t2 put everything back: t2's line out of a.txt, new.txt gone, gone.txt written again.
        val files = mapOf("/p/a.txt" to "one\nTWO\n", "/p/gone.txt" to "kept\n")
        val plan = CodeRewind.plan(changes) { files[it] }

        assertIs<CodeRewind.Plan.Ready>(plan)
        assertEquals(mapOf<String, String?>("/p/a.txt" to "one\ntwo\n"), plan.files)
    }

    @Test
    fun `a moved file put back by its list is where it stood already`() {
        val changes = listOf(update("/p/a.txt", "@@ -1 +1 @@\n-x\n+y\n", movedTo = "/p/b.txt", turn = "t1"))
        assertEquals(CodeRewind.Plan.None, CodeRewind.plan(changes) { if (it == "/p/a.txt") "x\n" else null })
    }

    // The allowance is for the turn's own lines found undone, not for anything at all: a file somebody wrote over
    // after the list put it back still stops the restore before a single file is written.
    @Test
    fun `a file changed by something else after the list still stops the restore`() {
        val changes = listOf(
            update("/p/a.txt", "@@ -1,2 +1,2 @@\n one\n-two\n+TWO\n", turn = "t1"),
            CodeRewind.Change("/p/new.txt", CodeRewind.Kind.ADD, null, "made\n", turn = "t2"),
        )
        val files = mapOf("/p/a.txt" to "one\nsomething else\n", "/p/new.txt" to "made\n")
        assertEquals(CodeRewind.Plan.Conflict(listOf("/p/a.txt")), CodeRewind.plan(changes) { files[it] })
    }

    /**
     * A turn whose file a formatter rewrote after the patch: its patch alone no longer fits, and what the IDE settled at
     * the turn's end (see TurnFiles) is what the turn did - the rewind undoes that.
     */
    @Test
    fun `a turn settled against the disk is undone by what it really did`() {
        val changes = listOf(update("/p/a.txt", "@@ -1 +1 @@\n-a=1\n+a = 2\n", turn = "t1"))
        val formatted = "a = 2;\n"
        val settled = listOf(ChangeRevert.Edit.Patch(listOf(ChangeRevert.Hunk(1, listOf("-a=1", "+a = 2;")))))

        assertEquals(CodeRewind.Plan.Conflict(listOf("/p/a.txt")), CodeRewind.plan(changes) { formatted })
        val plan = CodeRewind.plan(changes, settled = { turn, path -> settled.takeIf { turn == "t1" && path == "/p/a.txt" } }) { formatted }
        assertIs<CodeRewind.Plan.Ready>(plan)
        assertEquals("a=1\n", plan.files["/p/a.txt"])
    }

    @Test
    fun `no patch, nothing to restore - and a file patched back is none either`() {
        assertEquals(CodeRewind.Plan.None, CodeRewind.plan(emptyList()) { null })
        val there = listOf(
            update("/p/a.txt", "@@ -1 +1 @@\n-one\n+two\n"),
            update("/p/a.txt", "@@ -1 +1 @@\n-two\n+one\n"),
        )
        assertEquals(CodeRewind.Plan.None, CodeRewind.plan(there) { "one\n" })
    }

    @Test
    fun `only applied patches count`() {
        val turn = Json.parseToJsonElement(
            """{"id":"t","items":[
                {"type":"fileChange","id":"a","status":"completed","changes":[{"path":"/p/a","kind":{"type":"add"},"diff":"x\n"}]},
                {"type":"fileChange","id":"b","status":"declined","changes":[{"path":"/p/b","kind":{"type":"add"},"diff":"y\n"}]},
                {"type":"fileChange","id":"c","status":"completed","changes":[{"path":"/p/c","kind":{"type":"update","move_path":"/p/d"},"diff":"@@ -1 +1 @@\n-1\n+2\n"}]}
            ]}""",
        ).jsonObject
        val changes = CodeRewind.changesOf(listOf(turn))

        assertEquals(listOf("/p/a", "/p/c"), changes.map { it.path })
        assertEquals(listOf("t", "t"), changes.map { it.turn })
        assertEquals("/p/d", changes.last().movedTo)
        assertEquals(CodeRewind.Kind.UPDATE, changes.last().kind)
    }

    @Test
    fun `a plan is written to the disk`() {
        val folder = Files.createTempDirectory("code-rewind").toFile()
        val gone = folder.resolve("gone.txt").apply { writeText("x") }
        val back = folder.resolve("sub/back.txt")
        val plan = CodeRewind.Plan.Ready(mapOf(gone.path to null, back.path to "restored\n"), 1, 1)

        val written = CodeRewind.apply(plan).getOrThrow()

        assertEquals(listOf(gone.path, back.path), written)
        assertFalse(gone.exists())
        assertEquals("restored\n", back.readText())
        folder.deleteRecursively()
    }

    @Suppress("unused")
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
}
