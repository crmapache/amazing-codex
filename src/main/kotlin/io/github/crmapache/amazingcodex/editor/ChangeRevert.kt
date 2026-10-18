package io.github.crmapache.amazingcodex.editor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Putting back what one turn changed in a file, and bringing it back again - the buttons on the list of
 * changed files at a turn's end (see ChangesRow in the panel, and [FileReverts] for the part that touches
 * the disk).
 *
 * The turn's own diffs are undone, rather than an old copy of the file written back. Every edit the CLI
 * makes comes back with the hunks it applied (`structuredPatch`), and those hunks are applied here the other
 * way round, against the file as it stands now. So whatever else happened to the file since - a later turn,
 * a hand in the editor - stays where it is, as long as it did not touch the lines the turn wrote. When it did,
 * the turn's lines are not there to take out, and the answer is [Result.Changed] rather than a guess: the
 * file is left as it is. A copy written back would have wiped every later change without a word.
 *
 * Bringing the changes back after that is the same hunks applied forwards ([redo]), by the same rule: the
 * lines the turn replaced have to be there as they were, or nothing is written.
 *
 * A file the turn created is deleted - but only if what is in it is still exactly what the turn left there -
 * and brought back by writing it anew. A file the turn deleted (a Codex patch can) is written back with the text
 * the patch deleted, and deleted again on the way forward only if that text is what stands there. A move is
 * nothing to the text: it says where the file stood before (see [Edit.Move]), and [FileReverts] carries the file
 * between the two places.
 *
 * The same engine undoes a rewind's patches (see CodeRewind in the plugin's codex package), so a file put back
 * here and then rewound past is the same file to both.
 */
internal object ChangeRevert {

    /** One change the turn made to a file. A file's changes come in the order the turn made them. */
    sealed interface Edit {
        /** A Write brought the file into being with this text. */
        data class Create(val content: String) : Edit

        /** The hunks the CLI applied, as it reported them. */
        data class Patch(val hunks: List<Hunk>) : Edit

        /** A patch deleted the file, which held this text then. */
        data class Delete(val content: String) : Edit

        /**
         * A patch moved the file here from [from]. Its text is not touched by the move - the hunks of the same patch
         * come as a [Patch] after it - so to the text this is nothing; [FileReverts] puts the file back at [from].
         */
        data class Move(val from: String) : Edit
    }

    /**
     * One hunk: the 1-based lines its old and new sides start at, and its lines marked `+`, `-` or ` `. The
     * starts only say where to look first - the file may have moved since.
     */
    data class Hunk(val newStart: Int, val lines: List<String>, val oldStart: Int = newStart)

    /** What undoing or redoing the edits comes to. */
    sealed interface Result {
        data class Text(val text: String) : Result

        /**
         * The file is not to be there: undone, the turn created it and nothing else has touched it since; redone, the
         * turn deleted it. Also what a file already absent where it should be comes to.
         */
        data object Delete : Result

        /** The lines that had to be there are not there as the turn left them: something changed them since. */
        data object Changed : Result
    }

    /** What happened to one file, as the panel is told. */
    enum class Outcome(val wire: String) {
        REVERTED("reverted"),

        /** The changes were brought back after a revert. */
        RESTORED("restored"),
        CHANGED("changed"),

        /** The file the turn edited is not there any more. */
        MISSING("missing"),

        /** It could not be read or written - read-only, a binary, the disk said no. */
        FAILED("failed"),
    }

    data class FileRequest(val path: String, val edits: List<Edit>)

    /**
     * [current] with [edits] undone, newest first - [current] null for a file that is not there, which is where
     * undoing a deletion starts.
     *
     * Line endings are the editor's: the text arrives as a document holds it, with `\n` alone, and the
     * document writes the file's own separators back on save. The CLI's lines are read the same way.
     */
    fun undo(current: String?, edits: List<Edit>): Result = undo(current, edits, anchored = false)

    private fun undo(current: String?, edits: List<Edit>, anchored: Boolean): Result {
        // Null while the file is not there: before its creation, after its deletion.
        var text: Lines? = current?.let(Lines::of)
        for (edit in edits.asReversed()) {
            text = when (edit) {
                is Edit.Create -> if (text != null && text.lines == Lines.of(edit.content).lines) null else return Result.Changed
                is Edit.Patch -> apply(text ?: return Result.Changed, edit, forward = false, anchored) ?: return Result.Changed
                // Something stands where the turn left nothing: not the turn's to write over.
                is Edit.Delete -> if (text == null) Lines.of(edit.content) else return Result.Changed
                is Edit.Move -> text
            }
        }
        return text?.let { Result.Text(it.toString()) } ?: Result.Delete
    }

    /**
     * [edits] made again, oldest first - over [current], or over nothing when the file is not there (then the
     * first edit has to be the one that created it).
     *
     * A file that is back already is fine when it is exactly what the turn left; anything else standing at that
     * path is somebody else's file, and it is not written over. A deletion made again needs the very text the
     * turn deleted.
     */
    fun redo(current: String?, edits: List<Edit>): Result = redo(current, edits, anchored = false)

    private fun redo(current: String?, edits: List<Edit>, anchored: Boolean): Result {
        val first = edits.firstOrNull() ?: return Result.Changed
        if (first is Edit.Create && current != null) {
            val made = redo(null, edits, anchored) as? Result.Text ?: return Result.Changed
            return if (Lines.of(current).lines == Lines.of(made.text).lines) Result.Text(current) else Result.Changed
        }

        var text: Lines? = current?.let(Lines::of)
        for (edit in edits) {
            text = when (edit) {
                // A creation over a file that is there would mean the file was replaced whole - not a change to redo line by line.
                is Edit.Create -> if (text == null) Lines.of(edit.content) else return Result.Changed
                is Edit.Patch -> apply(text ?: return Result.Changed, edit, forward = true, anchored) ?: return Result.Changed
                is Edit.Delete -> if (text != null && text.lines == Lines.of(edit.content).lines) null else return Result.Changed
                is Edit.Move -> text
            }
        }
        return text?.let { Result.Text(it.toString()) } ?: Result.Delete
    }

    /**
     * What a press of the revert button comes to: [undo] - or, when the turn's lines are not there to take out
     * because they are gone already and the lines it replaced stand in their place, the file as it is, with
     * nothing to write.
     *
     * The second half is not a nicety. The list's marks live in the IDE's journal, and an IDE restarted under a
     * reverted file forgets them: the row offered the revert again, and the answer was "changed since" about a
     * file that was exactly where the person had put it. A rewind past a turn put back from the list leans on it
     * too (see CodeRewind).
     *
     * Judged strictly ("anchored", see [apply]): a hunk that reaches the edge of the file must still reach it.
     * Loosely, a line the agent appended and somebody then rewrote by hand read as reverted - the context before
     * it was all the hunk asked for, and that context was still there, only no longer last.
     */
    fun revert(current: String?, edits: List<Edit>): Result {
        val undone = undo(current, edits)
        if (undone != Result.Changed) return undone
        return if (isUndone(current, edits)) asItStands(current) else Result.Changed
    }

    /** [revert]'s other way: [redo], or the file as it is when the turn's changes stand in it already. */
    fun bringBack(current: String?, edits: List<Edit>): Result {
        val redone = redo(current, edits)
        if (redone != Result.Changed) return redone
        return if (isRedone(current, edits)) asItStands(current) else Result.Changed
    }

    /**
     * Whether [current] (null: no file) is already what undoing [edits] would make of the file - judged strictly
     * (see [revert]). A created file that is still there is not reverted however it reads - its revert is its
     * absence; one that is gone is.
     */
    fun isUndone(current: String?, edits: List<Edit>): Boolean =
        if (edits.firstOrNull() is Edit.Create) current == null else redo(current, edits, anchored = true) != Result.Changed

    /**
     * Whether [current] (null: no file) already holds what [edits] made of the file - judged strictly. A file the turn
     * deleted is back as the turn left it when it is gone.
     */
    fun isRedone(current: String?, edits: List<Edit>): Boolean = when {
        edits.lastOrNull() is Edit.Delete -> current == null
        current == null -> false
        else -> undo(current, edits, anchored = true) != Result.Changed
    }

    /**
     * Where the file stood before the turn moved it - the first move's source - or null when the turn did not move it.
     * A file the turn created stood nowhere before, wherever the turn moved it afterwards: its revert is its absence
     * where it ended up.
     */
    fun originOf(edits: List<Edit>): String? =
        edits.takeUnless { it.firstOrNull() is Edit.Create }?.firstNotNullOfOrNull { (it as? Edit.Move)?.from }

    /** Nothing to write: the file stays as it is - or stays away. */
    private fun asItStands(current: String?): Result = current?.let { Result.Text(it) } ?: Result.Delete

    /** A text as its lines, and whether a line break ends it - the one thing a list of lines cannot say. */
    private data class Lines(val lines: List<String>, val endsWithBreak: Boolean) {
        override fun toString(): String = lines.joinToString("\n") + if (endsWithBreak && lines.isNotEmpty()) "\n" else ""

        companion object {
            fun of(text: String): Lines {
                val plain = text.replace("\r\n", "\n")
                if (plain.isEmpty()) return Lines(emptyList(), false)
                val ends = plain.endsWith("\n")
                return Lines((if (ends) plain.dropLast(1) else plain).split("\n"), ends)
            }
        }
    }

    /**
     * One edit applied backwards (taken back) or forwards (made again): its hunks from the last to the first,
     * so a hunk's place is not moved by the ones done before it. A hunk is looked for where it said it was
     * and then outwards from there - the lines above it may have grown or shrunk since - and the nearest match
     * wins.
     *
     * [anchored] also holds a hunk to the edges it touched: one with less than a full context before its change
     * began at the top of the file, one with less than a full context after it ended at the bottom (the CLI's
     * patches and [LineDiff]'s carry three lines). Only for judging whether a file already stands where a press
     * would put it - a real undo may well find the agent's line with somebody's line added under it since.
     */
    private fun apply(text: Lines, patch: Edit.Patch, forward: Boolean, anchored: Boolean = false): Lines? {
        var lines = text.lines
        var endsWithBreak = text.endsWithBreak

        for (hunk in patch.hunks.asReversed()) {
            val side = Sides.of(hunk.lines)
            val (found, put, putEndsBare) = if (forward) {
                Triple(side.before, side.after, side.afterEndsBare)
            } else {
                Triple(side.after, side.before, side.beforeEndsBare)
            }
            val start = if (forward) hunk.oldStart else hunk.newStart
            val at = when {
                anchored && side.atTop && side.atBottom -> 0.takeIf { found == lines }
                anchored && side.atTop -> 0.takeIf { found.size <= lines.size && matches(lines, found, 0) }
                anchored && side.atBottom -> (lines.size - found.size).takeIf { it >= 0 && matches(lines, found, it) }
                found.isEmpty() -> start.coerceIn(0, lines.size)
                else -> find(lines, found, start - 1)
            }
            if (at == null) return null

            val reachesEnd = at + found.size == lines.size
            lines = lines.subList(0, at) + put + lines.subList(at + found.size, lines.size)
            // Only a hunk that reaches the end of the file says anything about how the file ends.
            if (reachesEnd) endsWithBreak = !putEndsBare
        }

        return Lines(lines, endsWithBreak)
    }

    /**
     * A hunk's two sides: the lines before the edit and the lines after it, context in both - and whether
     * each side's last line had no line break after it.
     */
    private class Sides(
        val before: List<String>,
        val after: List<String>,
        val beforeEndsBare: Boolean,
        val afterEndsBare: Boolean,
        /** Less than a full context before the change: the hunk starts at the top of the file. */
        val atTop: Boolean,
        /** Less than a full context after the change: the hunk ends at the bottom of the file. */
        val atBottom: Boolean,
    ) {
        companion object {
            fun of(lines: List<String>): Sides {
                val before = mutableListOf<String>()
                val after = mutableListOf<String>()
                var beforeEndsBare = false
                var afterEndsBare = false
                var last = ' '
                var lead = 0
                var trail = 0
                var changed = false

                for (raw in lines) {
                    // "\ No newline at end of file" - a note about the line above it rather than a line.
                    if (raw.startsWith("\\")) {
                        if (last == '-' || last == ' ') beforeEndsBare = true
                        if (last == '+' || last == ' ') afterEndsBare = true
                        continue
                    }
                    val mark = raw.firstOrNull() ?: ' '
                    val body = (if (raw.isEmpty()) "" else raw.substring(1)).removeSuffix("\r")
                    when (mark) {
                        '-' -> before += body
                        '+' -> after += body
                        else -> {
                            before += body
                            after += body
                        }
                    }
                    if (mark == '-' || mark == '+') {
                        changed = true
                        trail = 0
                    } else if (changed) {
                        trail++
                    } else {
                        lead++
                    }
                    last = mark
                }

                return Sides(before, after, beforeEndsBare, afterEndsBare, atTop = lead < CONTEXT, atBottom = trail < CONTEXT)
            }
        }
    }

    /** The context a hunk carries on each side of its change, the CLI's and [LineDiff]'s alike. */
    private const val CONTEXT = 3

    /** Where [block] stands in [lines], nearest to [expected] - or null when it stands nowhere. */
    private fun find(lines: List<String>, block: List<String>, expected: Int): Int? {
        val last = lines.size - block.size
        if (last < 0) return null

        val start = expected.coerceIn(0, last)
        for (distance in 0..maxOf(start, last - start)) {
            for (at in listOf(start - distance, start + distance).distinct()) {
                if (at in 0..last && matches(lines, block, at)) return at
            }
        }
        return null
    }

    private fun matches(lines: List<String>, block: List<String>, at: Int): Boolean =
        block.indices.all { index -> lines[at + index] == block[index] }

    // --- The wire ------------------------------------------------------------------

    /** The most a single request may name - a turn of a thousand files is a migration, not a turn to undo. */
    private const val MAX_FILES = 500
    private const val MAX_EDITS = 500

    /** The panel's request, read; anything malformed is left out rather than half-read. */
    fun requestsOf(element: JsonElement?): List<FileRequest> {
        val files = element as? JsonArray ?: return emptyList()
        return files.take(MAX_FILES).mapNotNull { entry ->
            val file = entry as? JsonObject ?: return@mapNotNull null
            val path = file.text("path")
            val edits = (file["edits"] as? JsonArray)?.take(MAX_EDITS)?.map { editOf(it) ?: return@mapNotNull null }
            if (path.isBlank() || edits.isNullOrEmpty()) null else FileRequest(path, edits)
        }
    }

    private fun editOf(element: JsonElement): Edit? {
        val edit = element as? JsonObject ?: return null
        return when (edit.text("kind")) {
            "create" -> Edit.Create(edit.text("content"))
            "delete" -> Edit.Delete(edit.text("content"))
            "move" -> edit.text("from").takeIf { it.isNotBlank() }?.let(Edit::Move)
            "patch" -> {
                val hunks = (edit["hunks"] as? JsonArray)?.map { hunk ->
                    val fields = hunk as? JsonObject ?: return null
                    val start = (fields["newStart"] as? JsonPrimitive)?.intOrNull ?: return null
                    val lines = (fields["lines"] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull ?: return null }
                    // A panel older than the way back sends no old start; the new one is the next best place to look.
                    val oldStart = (fields["oldStart"] as? JsonPrimitive)?.intOrNull ?: start
                    Hunk(start, lines ?: return null, oldStart)
                }
                if (hunks.isNullOrEmpty()) null else Edit.Patch(hunks)
            }
            else -> null
        }
    }

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""

    /**
     * What happened, to every client watching the conversation - and into its journal, so a reload keeps it.
     * [restore] says which way it went: a refusal to bring the changes back leaves the file reverted.
     */
    fun outcomeJson(sessionId: String, id: String, outcomes: List<Pair<String, Outcome>>, restore: Boolean = false): String = buildJsonObject {
        put("type", "changesReverted")
        put("sessionId", sessionId)
        put("id", id)
        if (restore) put("restore", true)
        putJsonArray("files") {
            for ((path, outcome) in outcomes) {
                addJsonObject {
                    put("path", path)
                    put("outcome", outcome.wire)
                }
            }
        }
    }.toString()
}
