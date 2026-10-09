package io.github.crmapache.amazingcodex.codex

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A reverted thread's files laid out the way Codex 0.160 wrote them (see CodexRollout). */
class CodexRolloutTest {

    private val root: File = Files.createTempDirectory("rollouts").toFile()
    private val day = File(root, "2026/10/09").apply { mkdirs() }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private val thread = "01a11f01-1185-76e3-9d21-eb51086a9155"
    private val first = "01a11f01-4a93-7510-a728-8287e415965a"
    private val second = "01a11f02-2e99-7101-a76b-618774648f60"

    private fun meta(base: String? = null, offset: Long = 0): String =
        if (base == null) {
            """{"type":"session_meta","payload":{"id":"$thread"}}"""
        } else {
            """{"type":"session_meta","payload":{"id":"$thread","history_base":{"thread_id":"$base","end_ordinal_exclusive":0,"end_byte_offset":$offset}}}"""
        }

    private fun write(name: String, lines: List<String>): File =
        File(day, name).apply { writeText(lines.joinToString("") { "$it\n" }) }

    private fun offsetAfter(lines: List<String>, count: Int): Long =
        lines.take(count).sumOf { it.toByteArray(Charsets.UTF_8).size + 1L }

    @Test
    fun `a file's name says its thread and its segment`() {
        assertEquals(CodexRollout.Name(thread, null), CodexRollout.nameOf(File("rollout-2026-10-09T01-52-06-$thread.jsonl")))
        assertEquals(CodexRollout.Name(thread, first), CodexRollout.nameOf(File("rollout-2026-10-09T01-52-20-${thread}_$first.jsonl")))
        assertNull(CodexRollout.nameOf(File("notes.txt")))
    }

    @Test
    fun `the newest segment is where the thread goes on`() {
        val files = listOf(
            File("rollout-a-$thread.jsonl"),
            File("rollout-c-${thread}_$second.jsonl"),
            File("rollout-b-${thread}_$first.jsonl"),
        )
        assertEquals("rollout-c-${thread}_$second.jsonl", CodexRollout.current(files)?.name)
    }

    // Three turns, a revert before the third, one more turn, a revert of that one, and one more turn: the
    // history is the first two turns and the last one - nothing the reverts took out.
    @Test
    fun `a reverted thread reads as Codex would resume it`() {
        val rootLines = listOf(meta(), "turn-1", "turn-2", "turn-3")
        write("rollout-2026-10-09T01-52-06-$thread.jsonl", rootLines)

        val firstLines = listOf(meta(thread, offsetAfter(rootLines, 3)), "turn-4", "turn-5")
        write("rollout-2026-10-09T01-52-20-${thread}_$first.jsonl", firstLines)

        val last = write(
            "rollout-2026-10-09T01-53-19-${thread}_$second.jsonl",
            listOf(meta(first, offsetAfter(firstLines, 2)), "turn-6"),
        )

        val read = CodexRollout.useLines(last) { lines -> lines.toList() }

        assertEquals(listOf(meta(), "turn-1", "turn-2", "turn-4", "turn-6"), read)
    }

    @Test
    fun `a thread never reverted reads as its own file`() {
        val file = write("rollout-2026-10-09T01-52-06-$thread.jsonl", listOf(meta(), "only"))
        assertEquals(listOf(meta(), "only"), CodexRollout.useLines(file) { it.toList() })
    }

    // A file the pointer leads to is gone: what the newest file says is still the thread's latest words.
    @Test
    fun `a missing base leaves the newest file's own lines`() {
        val last = write("rollout-x-${thread}_$second.jsonl", listOf(meta(first, 100), "after"))
        assertEquals(listOf("after"), CodexRollout.useLines(last) { it.toList() })
    }

    @Test
    fun `the files of a thread are found across folders`() {
        write("rollout-2026-10-09T01-52-06-$thread.jsonl", listOf(meta()))
        File(root, "2026/10/10").mkdirs()
        File(root, "2026/10/10/rollout-2026-10-10T09-00-00-${thread}_$first.jsonl").writeText(meta(thread, 10) + "\n")
        File(day, "rollout-2026-10-09T02-00-00-01a11f01-0000-0000-0000-000000000000.jsonl").writeText(meta() + "\n")

        val found = CodexRollout.filesOf(root, thread, fresh = true).map { it.name }.sorted()
        assertEquals(2, found.size)
        assertEquals(first, CodexRollout.nameOf(CodexRollout.current(CodexRollout.filesOf(root, thread))!!)?.segment)
    }
}
