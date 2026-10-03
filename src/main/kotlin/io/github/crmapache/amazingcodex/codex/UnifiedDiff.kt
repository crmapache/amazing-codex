package io.github.crmapache.amazingcodex.codex

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

    private val HUNK_HEADER = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")
}
