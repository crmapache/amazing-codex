package io.github.crmapache.amazingcodex.codex

/**
 * What the editor showed when a message went, in the words Codex's own terminal uses for it - and back.
 *
 * Codex's terminal has the same feature (`/ide`, "include current selection, open files, and other context
 * from your IDE") and writes it as a block of Markdown headings ahead of the request: `# Context from my
 * IDE setup:`, then `## Active file:`, the selection, the open tabs, and `## My request for Codex:`. The
 * models behind Codex are trained on exactly that block, so it is what the agent is given here too - as a
 * text item of its own AFTER the person's words rather than wrapped around them: a thread's preview is
 * the start of its first message, and a history row that began "# Context from my IDE setup:" would name
 * every conversation started over an open file after the same five words.
 *
 * The other direction is the panel's: its feed knows this note in the words Claude Code uses for it (see
 * feed/editorContext.ts, shared with the plugin this one was forked from), so a past conversation's block
 * is retold in those words on its way to the screen - the rule every other Codex item follows (see
 * CodexDialect). Without it the line under a message ("14 lines from useSession.ts") would vanish the
 * moment the conversation was reopened, although the agent read it all the same.
 */
internal object IdeContextPrompt {

    const val HEADER = "# Context from my IDE setup:"

    /**
     * The block for one message. [startLine] and [endLine] are the selection's, null for a file merely
     * open; [text] is the selected text, null when there is more of it than a message should carry - then
     * the agent is told which lines to read rather than given them.
     */
    fun of(path: String, startLine: Int?, endLine: Int?, text: String?): String = buildString {
        append(HEADER).append("\n\n")
        append(ACTIVE_FILE).append(path).append('\n')
        if (startLine != null && endLine != null) {
            append('\n').append(RANGE).append(rangeOf(startLine, endLine)).append('\n')
            if (text != null) {
                append('\n').append(SELECTION).append('\n').append(text).append('\n')
            } else {
                append('\n').append(TOO_LONG).append('\n')
            }
        }
        append('\n').append(MAYBE)
    }

    fun isContext(text: String): Boolean = text.startsWith(HEADER)

    /**
     * The same note in the panel's words, or null when [text] is not one of ours. Path and lines only:
     * the panel draws a line under the message, and the selected text is the agent's business.
     */
    fun asPanelNote(text: String): String? {
        if (!isContext(text)) return null
        val path = ACTIVE_FILE_LINE.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val range = RANGE_LINE.find(text)

        val body = if (range == null) {
            "The user opened the file $path in the IDE. $PANEL_MAYBE"
        } else {
            val from = range.groupValues[1]
            val to = range.groupValues[2].ifEmpty { from }
            "The user selected the lines $from to $to from $path in the IDE - too long to include here, read the file for them. $PANEL_MAYBE"
        }
        return "<system-reminder>\n$body\n</system-reminder>"
    }

    private fun rangeOf(start: Int, end: Int): String = if (start == end) "line $start" else "lines $start-$end"

    private const val ACTIVE_FILE = "## Active file: "
    private const val RANGE = "## Active selection range: "
    private const val SELECTION = "## Active selection of the file:"
    private const val TOO_LONG = "(The selection is too long to include here - read those lines from the file.)"

    /** What keeps an unrelated file left open from steering the answer. */
    private const val MAYBE = "This may or may not be related to the current task."

    /** The panel's own ending of the same note - see feed/editorContext.ts. */
    private const val PANEL_MAYBE = "This may or may not be related to the current task."

    private val ACTIVE_FILE_LINE = Regex("^## Active file: (.+)$", RegexOption.MULTILINE)
    private val RANGE_LINE = Regex("^## Active selection range: lines? (\\d+)(?:-(\\d+))?$", RegexOption.MULTILINE)
}
