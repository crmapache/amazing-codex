package io.github.crmapache.amazingcodex.codex

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
 */
internal object CodeRewind {

    /** One file of one applied patch, as Codex said it. */
    data class Change(val path: String, val kind: Kind, val movedTo: String?, val diff: String)

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
    fun changesOf(turns: List<JsonObject>): List<Change> = turns
        .flatMap { (it["items"] as? JsonArray).orEmpty().mapNotNull { item -> item as? JsonObject } }
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
                )
            }
        }

    /**
     * The files as they will be once [changes] are undone, worked out against what [read] says they hold now
     * (null for a file that is not there). Nothing is written.
     */
    fun plan(changes: List<Change>, read: (String) -> String?): Plan {
        if (changes.isEmpty()) return Plan.None

        // What each touched path holds as the undoing goes, newest patch first: read off the disk the first
        // time it is needed, changed in memory after that.
        val state = LinkedHashMap<String, String?>()
        val now = HashMap<String, String?>()
        fun current(path: String): String? = state.getOrPut(path) { read(path).also { now[path] = it } }

        val conflicts = LinkedHashSet<String>()
        var insertions = 0
        var deletions = 0

        for (change in changes.asReversed()) {
            when (change.kind) {
                Kind.ADD -> {
                    val held = current(change.path)
                    if (held == null || !sameText(held, change.diff)) {
                        conflicts += change.path
                    } else {
                        state[change.path] = null
                        deletions += lineCount(held)
                    }
                }

                Kind.DELETE -> {
                    if (current(change.path) != null) {
                        conflicts += change.path
                    } else {
                        state[change.path] = change.diff
                        insertions += lineCount(change.diff)
                    }
                }

                Kind.UPDATE -> {
                    val at = change.movedTo ?: change.path
                    val held = current(at)
                    val undone = held?.let { undo(it, change.diff) }
                    when {
                        undone == null -> conflicts += at
                        change.movedTo != null && change.movedTo != change.path && current(change.path) != null -> conflicts += change.path
                        else -> {
                            if (change.movedTo != null && change.movedTo != change.path) state[at] = null
                            state[change.path] = undone.text
                            insertions += undone.removed
                            deletions += undone.added
                        }
                    }
                }
            }
        }

        if (conflicts.isNotEmpty()) return Plan.Conflict(conflicts.toList())

        // Only what really changes: a file patched and patched back is no file to restore.
        val files = state.filter { (path, text) -> text != now[path] }
        if (files.isEmpty()) return Plan.None
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

    /** A file's text once [diff] is undone on it, and the line counts of the undoing - null when it does not fit. */
    internal data class Undone(val text: String, val added: Int, val removed: Int)

    internal fun undo(text: String, diff: String): Undone? {
        val hunks = hunksOf(diff) ?: return null
        if (hunks.isEmpty()) return Undone(text, 0, 0)

        val endsWithBreak = text.endsWith("\n")
        val lines = text.split("\n").toMutableList().also { if (endsWithBreak) it.removeAt(it.lastIndex) }
        var added = 0
        var removed = 0
        var newHasBreak = endsWithBreak
        var oldHasBreak: Boolean? = null

        // From the bottom up, so the places of the hunks above stay where the diff says they are.
        for (hunk in hunks.asReversed()) {
            val start = locate(lines, hunk.newLines, hunk.newStart - 1) ?: return null
            repeat(hunk.newLines.size) { lines.removeAt(start) }
            lines.addAll(start, hunk.oldLines)
            added += hunk.newLines.size - hunk.context
            removed += hunk.oldLines.size - hunk.context
            if (hunk.oldEndsWithoutBreak) oldHasBreak = false
            if (hunk.newEndsWithoutBreak) newHasBreak = true
        }

        val joined = lines.joinToString("\n")
        val finalBreak = oldHasBreak ?: newHasBreak
        return Undone(if (finalBreak && lines.isNotEmpty()) "$joined\n" else joined, added, removed)
    }

    /**
     * Where [block] stands in [lines] - at [expected] when the file is as the patch left it, otherwise the
     * nearest place it is found whole. Null when it is nowhere.
     */
    private fun locate(lines: List<String>, block: List<String>, expected: Int): Int? {
        fun fits(at: Int): Boolean =
            at >= 0 && at + block.size <= lines.size && block.indices.all { lines[at + it] == block[it] }

        val anchor = expected.coerceIn(0, lines.size)
        if (fits(anchor)) return anchor
        for (distance in 1..lines.size) {
            if (fits(anchor - distance)) return anchor - distance
            if (fits(anchor + distance)) return anchor + distance
        }
        return null
    }

    internal data class Hunk(
        val newStart: Int,
        val oldLines: List<String>,
        val newLines: List<String>,
        val context: Int,
        val oldEndsWithoutBreak: Boolean,
        val newEndsWithoutBreak: Boolean,
    )

    /**
     * The hunks of a unified diff, read by the counts their headers give - so what Codex writes after them (a
     * move ends with "Moved to: <path>") is never taken for a line of the file. Null when the text is no diff
     * at all.
     */
    internal fun hunksOf(diff: String): List<Hunk>? {
        val rows = diff.split("\n")
        val hunks = ArrayList<Hunk>()
        var index = 0
        while (index < rows.size) {
            val header = HEADER.find(rows[index])
            if (header == null) {
                index++
                continue
            }
            val oldCount = header.groupValues[2].ifEmpty { "1" }.toInt()
            val newCount = header.groupValues[4].ifEmpty { "1" }.toInt()
            val newStart = header.groupValues[3].toInt().let { if (newCount == 0) it + 1 else it }
            index++

            val oldLines = ArrayList<String>()
            val newLines = ArrayList<String>()
            var context = 0
            var oldNoBreak = false
            var newNoBreak = false
            var last = ' '
            while (index < rows.size && (oldLines.size < oldCount || newLines.size < newCount || rows[index].startsWith("\\"))) {
                val row = rows[index]
                when {
                    row.startsWith("\\") -> when (last) {
                        '-' -> oldNoBreak = true
                        '+' -> newNoBreak = true
                        else -> {
                            oldNoBreak = true
                            newNoBreak = true
                        }
                    }
                    row.startsWith("-") -> {
                        oldLines += row.substring(1)
                        last = '-'
                    }
                    row.startsWith("+") -> {
                        newLines += row.substring(1)
                        last = '+'
                    }
                    else -> {
                        val line = if (row.startsWith(" ")) row.substring(1) else row
                        oldLines += line
                        newLines += line
                        context++
                        last = ' '
                    }
                }
                index++
            }
            if (oldLines.size != oldCount || newLines.size != newCount) return null
            hunks += Hunk(newStart, oldLines, newLines, context, oldNoBreak, newNoBreak)
        }
        return if (hunks.isEmpty() && diff.isNotBlank() && !diff.trimStart().startsWith("@@")) null else hunks
    }

    private val HEADER = Regex("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@")

    /** An added file's text against what Codex said it wrote - a missing last line break is no difference. */
    private fun sameText(held: String, written: String): Boolean = held == written || held.trimEnd('\n') == written.trimEnd('\n')

    private fun lineCount(text: String): Int = if (text.isEmpty()) 0 else text.trimEnd('\n').count { it == '\n' } + 1
}
