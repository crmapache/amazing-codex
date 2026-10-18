package io.github.crmapache.amazingcodex.codex

import io.github.crmapache.amazingcodex.editor.ChangeRevert
import io.github.crmapache.amazingcodex.editor.LineDiff
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * Putting the files back the way they were before a message - the code half of a rewind (see Rewind).
 *
 * Claude Code keeps a copy of every file its edit tools touch and hands them back on request. Codex keeps
 * nothing of the kind: `thread/revert` changes the conversation and says in its own description that it does
 * not revert local file changes (0.160). What Codex does keep is every patch it applied, whole, in the thread:
 * each `fileChange` item names its files with the change made to each - the file's full text for an added or
 * a deleted one, a unified diff for an update, and the new path for a move. Undone newest first, those
 * patches bring the files back to where they stood before the first of them - the same thing Claude Code's
 * copies give, for the same files: what the agent's own edits touched. Shell commands are not undone, here as
 * there.
 *
 * Unlike a copy, a patch undone has to fit the file it is undone on. A file changed since by something else -
 * a command, the person, another tool - no longer holds what the patch put there, and undoing it blindly would
 * mangle it. So the whole restore is worked out in memory first ([plan]), and a file that does not fit stops
 * it before anything is written: the dialog names it, and the conversation part can still go on its own.
 *
 * The undoing is [ChangeRevert]'s - the engine the list of a turn's changed files puts a turn back with - turn by
 * turn and file by file, so the two agree about a file and about what is already undone. A turn the person put back
 * from its list, and then rewound past, reads as undone already rather than as "changed since": its created file
 * gone, its deleted file back, its patch taken out, each found as the list left it. Where this run of the IDE
 * settled a turn's file against the disk (a formatter after the patch, the agent's own `sed` - see TurnFiles), that
 * turn is undone by what it really did to the file rather than by its patches alone.
 */
internal object CodeRewind {

    /** One file of one applied patch, as Codex said it - and the [turn] that made it. */
    data class Change(val path: String, val kind: Kind, val movedTo: String?, val diff: String, val turn: String = "")

    enum class Kind { ADD, DELETE, UPDATE }

    /** What undoing the patches comes to. */
    sealed interface Plan {
        /** Each file's text as it will be put back - null to remove the file - and the counts of the dialog. */
        data class Ready(val files: Map<String, String?>, val insertions: Int, val deletions: Int) : Plan

        /** No patch since then. */
        data object None : Plan

        /** Files that were changed since by something else, so the patches no longer fit them. Nothing is written. */
        data class Conflict(val files: List<String>) : Plan
    }

    /** The applied patches in [turns] (whole turns, oldest first), in the order they were made. */
    fun changesOf(turns: List<JsonObject>): List<Change> = turns.flatMap { turn ->
        val turnId = AppServer.text(turn["id"])
        (turn["items"] as? JsonArray).orEmpty().mapNotNull { item -> item as? JsonObject }
            .filter { AppServer.text(it["type"]) == "fileChange" && AppServer.text(it["status"]) == "completed" }
            .flatMap { item ->
                (item["changes"] as? JsonArray).orEmpty().mapNotNull { element ->
                    val change = element as? JsonObject ?: return@mapNotNull null
                    val path = AppServer.text(change["path"]).ifEmpty { null } ?: return@mapNotNull null
                    val kind = change["kind"] as? JsonObject
                    Change(
                        path = path,
                        kind = when (AppServer.text(kind?.get("type"))) {
                            "add" -> Kind.ADD
                            "delete" -> Kind.DELETE
                            else -> Kind.UPDATE
                        },
                        movedTo = AppServer.text(kind?.get("move_path")).ifEmpty { null },
                        diff = AppServer.text(change["diff"]),
                        turn = turnId,
                    )
                }
            }
    }

    /**
     * What one turn did to one file, in [ChangeRevert]'s terms: the file where the turn left it ([path]) and its
     * edits in the order they were made. [broken] - a patch of it was no diff that could be read, and nothing can be
     * undone by it.
     */
    private class FileTurn(var path: String) {
        val edits = ArrayList<ChangeRevert.Edit>()
        var broken = false

        /** Where the file stood before the turn moved it (see ChangeRevert.originOf), unless that is where it is. */
        val origin: String?
            get() = ChangeRevert.originOf(edits)?.takeIf { it != path }
    }

    /** [changes] gathered by turn (oldest first) and, within a turn, by file - a moved file goes on as one file. */
    private fun byTurn(changes: List<Change>): List<Pair<String, List<FileTurn>>> =
        changes.groupBy { it.turn }.map { (turn, made) ->
            val files = LinkedHashMap<String, FileTurn>()
            for (change in made) {
                val to = change.movedTo?.takeIf { change.kind == Kind.UPDATE && it != change.path } ?: change.path
                val file = files.remove(change.path) ?: FileTurn(change.path)
                file.path = to
                when (change.kind) {
                    Kind.ADD -> file.edits += ChangeRevert.Edit.Create(change.diff)
                    Kind.DELETE -> file.edits += ChangeRevert.Edit.Delete(change.diff)
                    Kind.UPDATE -> {
                        if (to != change.path) file.edits += ChangeRevert.Edit.Move(change.path)
                        val hunks = UnifiedDiff.patch(change.diff)
                        if (hunks == null) file.broken = true else if (hunks.isNotEmpty()) file.edits += ChangeRevert.Edit.Patch(hunks)
                    }
                }
                files[to] = file
            }
            turn to files.values.toList()
        }

    /**
     * The files as they will be once [changes] are undone, worked out against what [read] says they hold now
     * (null for a file that is not there). Nothing is written.
     *
     * Turn by turn, newest first, and in a turn file by file, last touched first: each turn's edits of a file are
     * undone together, the way its list puts the file back, with the list's allowance - a file that already reads as
     * undone is left as it stands (see ChangeRevert.revert). [settled] gives a turn's file as this run of the IDE
     * settled it at the turn's end (see TurnFiles), when it did: that turn is undone by it instead.
     */
    fun plan(
        changes: List<Change>,
        settled: (turn: String, path: String) -> List<ChangeRevert.Edit>? = { _, _ -> null },
        read: (String) -> String?,
    ): Plan {
        if (changes.isEmpty()) return Plan.None

        // What each touched path holds as the undoing goes: read off the disk the first time it is needed (null for
        // no file), changed in memory after that.
        val state = LinkedHashMap<String, String?>()
        val now = HashMap<String, String?>()
        fun current(path: String): String? {
            if (path in state) return state[path]
            return read(path).also {
                state[path] = it
                now[path] = it
            }
        }

        val conflicts = LinkedHashSet<String>()
        for ((turn, files) in byTurn(changes).asReversed()) {
            for (file in files.asReversed()) {
                val edits = settled(turn, file.path) ?: file.edits.takeUnless { file.broken }
                if (edits == null) {
                    conflicts += file.path
                    continue
                }
                val origin = file.origin
                if (origin == null) {
                    when (val undone = ChangeRevert.revert(current(file.path), edits)) {
                        is ChangeRevert.Result.Text -> state[file.path] = undone.text
                        ChangeRevert.Result.Delete -> state[file.path] = null
                        ChangeRevert.Result.Changed -> conflicts += file.path
                    }
                    continue
                }

                // Moved: put back where it stood, with its text undone - unless it stands there undone already.
                val here = current(file.path)
                val there = current(origin)
                when {
                    here != null && there == null -> when (val undone = ChangeRevert.revert(here, edits)) {
                        is ChangeRevert.Result.Text -> {
                            state[origin] = undone.text
                            state[file.path] = null
                        }
                        else -> conflicts += file.path
                    }
                    here == null && there != null -> if (!ChangeRevert.isUndone(there, edits)) conflicts += origin
                    else -> conflicts += file.path
                }
            }
        }

        if (conflicts.isNotEmpty()) return Plan.Conflict(conflicts.toList())

        // Only what really changes: a file patched and patched back is no file to restore.
        val files = state.filter { (path, text) -> text != now[path] }
        if (files.isEmpty()) return Plan.None
        var insertions = 0
        var deletions = 0
        for ((path, text) in files) {
            val before = now[path].orEmpty()
            val after = text.orEmpty()
            val lines = LineDiff.hunks(before, after)?.flatMap { it.lines }
            if (lines != null) {
                insertions += lines.count { it.startsWith("+") }
                deletions += lines.count { it.startsWith("-") }
            } else {
                insertions += lineCount(after)
                deletions += lineCount(before)
            }
        }
        return Plan.Ready(files, insertions, deletions)
    }

    /**
     * Write [plan] to the disk. Returns the paths written, or the first one that would not be written with
     * the reason - the files before it are back already, which the caller says.
     */
    fun apply(plan: Plan.Ready): Result<List<String>> {
        val written = ArrayList<String>()
        for ((path, text) in plan.files) {
            val file = File(path)
            val outcome = runCatching {
                if (text == null) {
                    if (file.exists() && !file.delete()) error("could not delete $path")
                } else {
                    file.parentFile?.mkdirs()
                    file.writeText(text, Charsets.UTF_8)
                }
            }
            if (outcome.isFailure) {
                return Result.failure(IllegalStateException("${outcome.exceptionOrNull()?.message ?: path} (${written.size} files restored before it)"))
            }
            written += path
        }
        return Result.success(written)
    }

    private fun lineCount(text: String): Int = if (text.isEmpty()) 0 else text.trimEnd('\n').count { it == '\n' } + 1
}
