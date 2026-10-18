package io.github.crmapache.amazingcodex.editor

import com.intellij.util.diff.Diff

/**
 * A diff of two texts in the shape the CLI reports its own (`structuredPatch`): hunks of lines marked `+`, `-`
 * and ` `, with three lines of context - so that [ChangeRevert] and the panel read a diff worked out here
 * exactly as they read one from the agent (see TurnFiles in the codex package for why there is one to work out:
 * a turn's file held against the disk at the turn's end).
 *
 * Correct first and short second. Lines are matched by their common start and end, and what lies between is
 * compared line by line when both sides kept their length (a `replace_all` - the case this exists for - is
 * exactly that) and by the platform's diff otherwise; a middle too big for that becomes one hunk. Any of these
 * is a patch that takes one text to the other, which is what a revert needs; only the first two are also as
 * short as the change.
 */
internal object LineDiff {

    data class Hunk(val oldStart: Int, val newStart: Int, val lines: List<String>)

    private const val CONTEXT = 3

    /** The platform's diff is given a middle no bigger than this many lines a side. */
    private const val MAX_DIFFED = 20_000

    /** Hunks for [before] → [after], or null when they would run past [maxLines] - too big to carry. */
    fun hunks(before: String, after: String, maxLines: Int = 100_000): List<Hunk>? {
        val old = Text.of(before)
        val new = Text.of(after)
        if (old == new) return emptyList()

        val blocks = withEnding(blocks(old.lines, new.lines), old, new)
        val hunks = mutableListOf<Hunk>()
        var total = 0

        var index = 0
        while (index < blocks.size) {
            // Blocks whose contexts touch go into one hunk, the way a unified diff joins them.
            var last = index
            while (last + 1 < blocks.size && blocks[last + 1].oldFrom - blocks[last].oldTo <= 2 * CONTEXT) last++

            val first = blocks[index]
            val end = blocks[last]
            val oldFrom = maxOf(0, first.oldFrom - CONTEXT)
            // The lines before a block are shared, so the new side's context starts as far back as the old one's.
            val newFrom = first.newFrom - (first.oldFrom - oldFrom)
            val oldTo = minOf(old.lines.size, end.oldTo + CONTEXT)

            val lines = mutableListOf<String>()
            var at = oldFrom
            var newAt = newFrom
            for (block in blocks.subList(index, last + 1)) {
                while (at < block.oldFrom) {
                    context(lines, old, new, at, newAt)
                    at++
                    newAt++
                }
                for (line in block.oldFrom until block.oldTo) {
                    lines += "-" + old.lines[line]
                    if (line == old.lines.lastIndex && !old.endsWithBreak) lines += NO_BREAK
                }
                for (line in block.newFrom until block.newTo) {
                    lines += "+" + new.lines[line]
                    if (line == new.lines.lastIndex && !new.endsWithBreak) lines += NO_BREAK
                }
                at = block.oldTo
                newAt = block.newTo
            }
            while (at < oldTo) {
                context(lines, old, new, at, newAt)
                at++
                newAt++
            }

            total += lines.size
            if (total > maxLines) return null
            hunks += Hunk(oldStart = oldFrom + 1, newStart = newFrom + 1, lines = lines)
            index = last + 1
        }
        return hunks
    }

    /**
     * A line both sides share - unless it ends one side without a line break and not the other, which a diff
     * can only say as the line taken out and put back.
     */
    private fun context(lines: MutableList<String>, old: Text, new: Text, at: Int, newAt: Int) {
        val text = old.lines[at]
        val oldBare = at == old.lines.lastIndex && !old.endsWithBreak
        val newBare = newAt == new.lines.lastIndex && !new.endsWithBreak
        if (oldBare == newBare) {
            lines += " $text"
            if (oldBare) lines += NO_BREAK
            return
        }
        lines += "-$text"
        if (oldBare) lines += NO_BREAK
        lines += "+$text"
        if (newBare) lines += NO_BREAK
    }

    private const val NO_BREAK = "\\ No newline at end of file"

    /** One stretch that differs: lines `[oldFrom, oldTo)` of the old text became `[newFrom, newTo)` of the new. */
    private data class Block(val oldFrom: Int, val oldTo: Int, val newFrom: Int, val newTo: Int)

    /**
     * [blocks] with the last line in one of them when only the line break after it changed: matched as the same
     * line, it would otherwise be left out, and a revert would leave the file ending the wrong way.
     */
    private fun withEnding(blocks: List<Block>, old: Text, new: Text): List<Block> {
        if (old.endsWithBreak == new.endsWithBreak || old.lines.isEmpty() || new.lines.isEmpty()) return blocks
        val last = blocks.lastOrNull()
        if (last != null && last.oldTo == old.lines.size && last.newTo == new.lines.size) return blocks
        return blocks + Block(old.lines.size - 1, old.lines.size, new.lines.size - 1, new.lines.size)
    }

    private fun blocks(old: List<String>, new: List<String>): List<Block> {
        var head = 0
        while (head < old.size && head < new.size && old[head] == new[head]) head++
        var tail = 0
        while (tail < old.size - head && tail < new.size - head && old[old.size - 1 - tail] == new[new.size - 1 - tail]) tail++

        val oldTo = old.size - tail
        val newTo = new.size - tail
        if (head == oldTo && head == newTo) return emptyList()

        // Same length both sides: line against line, which is what a replace across the file leaves.
        if (oldTo - head == newTo - head) {
            val blocks = mutableListOf<Block>()
            var at = head
            while (at < oldTo) {
                if (old[at] == new[at]) {
                    at++
                    continue
                }
                val from = at
                while (at < oldTo && old[at] != new[at]) at++
                blocks += Block(from, at, from, at)
            }
            return blocks
        }

        if (oldTo - head <= MAX_DIFFED && newTo - head <= MAX_DIFFED) {
            val changes = runCatching {
                Diff.buildChanges(old.subList(head, oldTo).toTypedArray(), new.subList(head, newTo).toTypedArray())
            }.getOrNull()
            if (changes != null) {
                val blocks = mutableListOf<Block>()
                var change: Diff.Change? = changes
                while (change != null) {
                    blocks += Block(
                        head + change.line0,
                        head + change.line0 + change.deleted,
                        head + change.line1,
                        head + change.line1 + change.inserted,
                    )
                    change = change.link
                }
                return blocks
            }
        }

        return listOf(Block(head, oldTo, head, newTo))
    }

    /** A text as its lines and whether a line break ends it - as [ChangeRevert] reads one. */
    private data class Text(val lines: List<String>, val endsWithBreak: Boolean) {
        companion object {
            fun of(text: String): Text {
                val plain = text.replace("\r\n", "\n")
                if (plain.isEmpty()) return Text(emptyList(), false)
                val ends = plain.endsWith("\n")
                return Text((if (ends) plain.dropLast(1) else plain).split("\n"), ends)
            }
        }
    }
}
