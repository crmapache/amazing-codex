package io.github.crmapache.amazingcodex.codex

import io.github.crmapache.amazingcodex.editor.ChangeRevert

/**
 * The lines of a unified diff that belong to its hunks - the one reading of Codex's patches that the
 * edit card's rebuilt text (see CodexDialect.editInput) and the statistics (see EditLines.ofUnified) share.
 *
 * Inside a hunk a line is read by its first character, whatever follows it: a removed line whose text is
 * "-- a note" arrives as "--- a note", an added "++i;" as "+++i;". Telling those from a file's
 * "---"/"+++" header by their look is what dropped them from the count. So each hunk's header says how
 * many lines it holds, and whatever stands outside every hunk - a file header, a "\ No newline" note, the
 * diff's own trailing newline - is told apart by where it stands rather than by what it looks like.
 * Codex's diffs carry no file headers at all; a diff from elsewhere that does is read just as well.
 */
internal object UnifiedDiff {

    sealed interface Line {
        /** A hunk begins; [newStart] is the number of its first line in the new file. */
        data class Hunk(val newStart: Int) : Line
        data class Added(val text: String) : Line
        data class Removed(val text: String) : Line
        data class Context(val text: String) : Line
    }

    fun lines(diff: String): List<Line> {
        val out = mutableListOf<Line>()
        var oldLeft = 0
        var newLeft = 0

        for (raw in diff.lineSequence()) {
            val header = HUNK_HEADER.find(raw)
            if (header != null) {
                oldLeft = header.groupValues[2].ifEmpty { "1" }.toInt()
                newLeft = header.groupValues[4].ifEmpty { "1" }.toInt()
                out += Line.Hunk(header.groupValues[3].toInt())
                continue
            }
            if (oldLeft <= 0 && newLeft <= 0) continue

            when {
                raw.startsWith("+") -> { out += Line.Added(raw.substring(1)); newLeft-- }
                raw.startsWith("-") -> { out += Line.Removed(raw.substring(1)); oldLeft-- }
                raw.startsWith(" ") -> { out += Line.Context(raw.substring(1)); oldLeft--; newLeft-- }
                // "\ No newline at end of file" and the like: a note about the file, not a line of it.
            }
        }
        return out
    }

    /**
     * A unified diff as the hunks [ChangeRevert] undoes and redoes - the same reading as [lines], with the `\ No newline
     * at end of file` notes kept where they stood (a revert gives a file back the last line it ended with by them) and
     * a start of an empty side said as the line the side begins at, the way the CLI's own hunks say it. The panel
     * reads a card's diff into the same hunks (`unifiedPatch` in feed/tools.ts), so the list's revert and a rewind of
     * the same patch agree line for line.
     *
     * No hunks for a diff with none (a file moved and left as it was). Null when a hunk is cut short - fewer lines than
     * its header promised: half a patch undone would be a file nobody wrote.
     */
    fun patch(diff: String): List<ChangeRevert.Hunk>? {
        val hunks = mutableListOf<ChangeRevert.Hunk>()
        var lines: MutableList<String>? = null
        var oldStart = 0
        var newStart = 0
        var oldLeft = 0
        var newLeft = 0
        // The last row read was a line of a hunk - what a "\ No newline" note can belong to.
        var afterLine = false

        fun close(): Boolean {
            val body = lines ?: return true
            if (oldLeft > 0 || newLeft > 0) return false
            hunks += ChangeRevert.Hunk(newStart, body.toList(), oldStart)
            return true
        }

        for (raw in diff.lineSequence()) {
            val header = HUNK_HEADER.find(raw)
            if (header != null) {
                if (!close()) return null
                oldLeft = header.groupValues[2].ifEmpty { "1" }.toInt()
                newLeft = header.groupValues[4].ifEmpty { "1" }.toInt()
                oldStart = header.groupValues[1].toInt().let { if (oldLeft == 0) it + 1 else it }
                newStart = header.groupValues[3].toInt().let { if (newLeft == 0) it + 1 else it }
                lines = mutableListOf()
                afterLine = false
                continue
            }
            val body = lines ?: continue
            if (raw.startsWith("\\") && afterLine) {
                body += raw
                afterLine = false
                continue
            }
            afterLine = false
            if (oldLeft <= 0 && newLeft <= 0) continue
            when {
                raw.startsWith("+") -> newLeft--
                raw.startsWith("-") -> oldLeft--
                raw.startsWith(" ") -> { oldLeft--; newLeft-- }
                else -> continue
            }
            body += raw
            afterLine = true
        }
        return if (close()) hunks else null
    }

    private val HUNK_HEADER = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")
}
