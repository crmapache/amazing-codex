package io.github.crmapache.amazingcodex.stats

import com.intellij.openapi.util.Disposer
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TranscriptTokensTest {

    private val directory = Files.createTempDirectory("acx-tokens")

    private val ledger = StatsLedger(directory.resolve("statistics.json"))

    /** Codex's folder of conversations: day folders, as Codex files them. */
    private val root = directory.resolve("sessions").toFile().apply { mkdirs() }

    private val zone = ZoneId.of("UTC")

    private val now = Instant.parse("2026-10-16T12:00:00Z").toEpochMilli()

    private val hour = 60L * 60 * 1000

    @AfterTest
    fun tearDown() {
        Disposer.dispose(ledger)
        directory.toFile().deleteRecursively()
    }

    /** A `token_count` event: the thread's running [total] after a request that cost [tokens]. */
    private fun request(at: String, total: Long, tokens: Long = 100): String =
        """{"timestamp":"$at","type":"event_msg","payload":{"type":"token_count","info":{""" +
            """"total_token_usage":{"input_tokens":${total - 10},"cached_input_tokens":0,"output_tokens":10,"total_tokens":$total},""" +
            """"last_token_usage":{"input_tokens":${tokens - 10},"cached_input_tokens":0,"output_tokens":10,"total_tokens":$tokens},""" +
            """"model_context_window":258400},"rate_limits":null}}"""

    /** The first line of a thread's file: where Codex works, among its instructions. */
    private fun meta(cwd: String): String =
        """{"timestamp":"2026-10-16T08:00:00Z","type":"session_meta","payload":{"id":"t","cwd":"${cwd.replace("\\", "\\\\")}",""" +
            """"base_instructions":{"text":"You are Codex"}}}"""

    /** A turn's context: a turn may move the thread to another folder. */
    private fun context(cwd: String): String =
        """{"timestamp":"2026-10-16T08:00:01Z","type":"turn_context","payload":{"turn_id":"x","cwd":"${cwd.replace("\\", "\\\\")}"}}"""

    /** A conversation's file with these lines, last changed at [changed]. */
    private fun transcript(path: String, changed: Long, vararg lines: String): File {
        val file = File(root, path)
        file.parentFile.mkdirs()
        file.writeText(lines.joinToString("\n", postfix = "\n"))
        file.setLastModified(changed)
        return file
    }

    private fun hours(): Map<Long, Long> =
        ledger.read { HashMap(it.tokens[TranscriptTokens.keyOf(root)]?.hours ?: emptyMap()) }

    private fun hourOf(at: String): Long = Instant.parse(at).toEpochMilli() / hour

    @Test
    fun `the first reading goes through every conversation and counts each request once`() {
        transcript(
            "2026/09/06/rollout-a.jsonl",
            now - 40 * 24 * hour,
            request("2026-09-06T08:10:00Z", total = 100),
            """{"timestamp":"2026-09-06T08:10:01Z","type":"response_item","payload":{"type":"message","role":"user"}}""",
        )
        transcript(
            "2026/10/16/rollout-b.jsonl",
            now,
            request("2026-10-16T09:05:00Z", total = 400, tokens = 400),
            request("2026-10-16T09:50:00Z", total = 600, tokens = 200),
        )
        // A spawned agent keeps a thread - a file - of its own.
        transcript("2026/10/16/rollout-agent.jsonl", now, request("2026-10-16T11:30:00Z", total = 40, tokens = 40))

        val today = TranscriptTokens.refresh(root, ledger, zone, now)

        assertEquals(640L, today)
        assertEquals(100L, hours()[hourOf("2026-09-06T08:00:00Z")])
        assertEquals(600L, hours()[hourOf("2026-10-16T09:00:00Z")])
        assertEquals(40L, hours()[hourOf("2026-10-16T11:00:00Z")])
        assertEquals(now, ledger.read { it.tokens[TranscriptTokens.keyOf(root)]!!.readAt })
    }

    @Test
    fun `an event that repeats the running total is no new request`() {
        // Codex says the usage again when only the rate limits moved: the same running total, the last
        // request's tokens once more.
        transcript(
            "2026/10/16/rollout-a.jsonl",
            now,
            request("2026-10-16T09:58:00Z", total = 100, tokens = 100),
            request("2026-10-16T09:59:00Z", total = 100, tokens = 100),
            request("2026-10-16T10:01:00Z", total = 340, tokens = 240),
        )

        TranscriptTokens.refresh(root, ledger, zone, now)

        assertEquals(100L, hours()[hourOf("2026-10-16T09:00:00Z")])
        assertEquals(240L, hours()[hourOf("2026-10-16T10:00:00Z")])
    }

    @Test
    fun `a later reading opens only what changed since the last one`() {
        val old = transcript("2026/10/06/rollout-old.jsonl", now - 10 * 24 * hour, request("2026-10-06T10:00:00Z", total = 100))
        TranscriptTokens.refresh(root, ledger, zone, now)

        // Rewritten behind the reading's back, with its time left alone: a reading that opened it again
        // would count 900 for that hour.
        old.writeText(request("2026-10-06T10:00:00Z", total = 900, tokens = 900) + "\n")
        old.setLastModified(now - 10 * 24 * hour)
        transcript("2026/10/16/rollout-new.jsonl", now + hour, request("2026-10-16T12:30:00Z", total = 80, tokens = 80))

        val today = TranscriptTokens.refresh(root, ledger, zone, now + hour)

        assertEquals(80L, today)
        assertEquals(100L, hours()[hourOf("2026-10-06T10:00:00Z")])
        assertEquals(now + hour, ledger.read { it.tokens[TranscriptTokens.keyOf(root)]!!.readAt })
    }

    @Test
    fun `an old hour seen only in part by a later reading is not counted down`() {
        // Two conversations worked in the same hour ten days ago.
        transcript("2026/10/06/rollout-one.jsonl", now - 10 * 24 * hour, request("2026-10-06T10:00:00Z", total = 100))
        val other = transcript("2026/10/06/rollout-two.jsonl", now - 10 * 24 * hour, request("2026-10-06T10:20:00Z", total = 100))
        TranscriptTokens.refresh(root, ledger, zone, now)

        // The second goes on today; a reading of recent files opens it alone and sees half of that old hour.
        other.writeText(
            listOf(request("2026-10-06T10:20:00Z", total = 100), request("2026-10-16T12:10:00Z", total = 150, tokens = 50))
                .joinToString("\n", postfix = "\n"),
        )
        other.setLastModified(now + hour)
        TranscriptTokens.refresh(root, ledger, zone, now + hour)

        assertEquals(200L, hours()[hourOf("2026-10-06T10:00:00Z")])
        assertEquals(50L, hours()[hourOf("2026-10-16T12:00:00Z")])
    }

    @Test
    fun `without a folder the book answers for today and nothing is read`() {
        val missing = directory.resolve("nowhere").toFile()

        assertEquals(0L, TranscriptTokens.refresh(missing, ledger, zone, now))
        assertNull(ledger.read { it.tokens[TranscriptTokens.keyOf(missing)] })
    }

    private fun projects(): Map<String, TokenProject> =
        ledger.read { HashMap(it.tokens[TranscriptTokens.keyOf(root)]?.projects ?: emptyMap()) }

    @Test
    fun `the tokens go to the project the directory lies in`() {
        val work = directory.resolve("work").toFile()
        // Opened in the IDE: the book knows it under the key StatsCollector gives its path.
        val app = File(work, "app").apply { mkdirs() }
        ledger.update { it.project(StatsCollector.keyOf(app.path), "app") }
        // Never opened, but a repository - a worktree's .git is a file, and that counts the same.
        val tool = File(work, "tool").apply { mkdirs() }
        File(tool, ".git").writeText("gitdir: elsewhere")
        File(tool, "scripts").mkdirs()
        // Neither: a folder of its own.
        val scratch = File(work, "scratch").apply { mkdirs() }

        transcript(
            "2026/10/16/rollout-a.jsonl",
            now,
            meta(app.path),
            request("2026-10-16T09:05:00Z", total = 400, tokens = 400),
            // The next turn ran in a folder inside the project: still the project's.
            context("${app.path}/webview/src"),
            request("2026-10-16T09:10:00Z", total = 440, tokens = 40),
        )
        transcript(
            "2026/10/16/rollout-b.jsonl",
            now,
            meta("${tool.path}/scripts"),
            request("2026-10-16T10:05:00Z", total = 80, tokens = 80),
        )
        transcript("2026/10/16/rollout-c.jsonl", now, meta(scratch.path), request("2026-10-16T11:05:00Z", total = 8, tokens = 8))
        // A file with no folder said still counts in the day, just not for any project.
        transcript("2026/10/16/rollout-d.jsonl", now, request("2026-10-16T11:20:00Z", total = 4, tokens = 4))

        TranscriptTokens.refresh(root, ledger, zone, now)

        val projects = projects()
        val appTokens = projects[StatsCollector.keyOf(app.path)]
        assertEquals("app", appTokens?.name)
        assertEquals(mapOf(hourOf("2026-10-16T09:00:00Z") to 440L), appTokens?.hours?.toMap())
        assertEquals("tool", projects[StatsCollector.keyOf(tool.path)]?.name)
        assertEquals(80L, projects[StatsCollector.keyOf(tool.path)]?.hours?.values?.sum())
        assertEquals("scratch", projects[StatsCollector.keyOf(scratch.path)]?.name)
        assertEquals(3, projects.size)
        assertEquals(532L, hours().values.sum())
    }

    @Test
    fun `a book kept before the projects reads every thread again`() {
        val app = directory.resolve("app").toFile().apply { mkdirs() }
        transcript("2026/09/26/rollout-old.jsonl", now - 20 * 24 * hour, meta(app.path), request("2026-09-26T10:00:00Z", total = 100))
        transcript("2026/10/16/rollout-new.jsonl", now, meta(app.path), request("2026-10-16T10:00:00Z", total = 100))
        // Read through yesterday by a plugin that knew nothing of projects: the totals are there, the
        // projects are not - and the reading time says nothing about them.
        ledger.update { book ->
            book.tokens[TranscriptTokens.keyOf(root)] = TokenHome().apply {
                record(mapOf(hourOf("2026-09-26T10:00:00Z") to 100L), now - 24 * hour)
            }
        }

        TranscriptTokens.refresh(root, ledger, zone, now)

        val tokens = projects()[StatsCollector.keyOf(app.path)]
        assertEquals(
            mapOf(hourOf("2026-09-26T10:00:00Z") to 100L, hourOf("2026-10-16T10:00:00Z") to 100L),
            tokens?.hours?.toMap(),
        )
        assertEquals(now, ledger.read { it.tokens[TranscriptTokens.keyOf(root)]!!.projectsReadAt })

        // From then on only what changed: the old file rewritten behind the reading's back is not opened.
        File(root, "2026/09/26/rollout-old.jsonl").apply {
            writeText(meta(app.path) + "\n" + request("2026-09-26T10:00:00Z", total = 900, tokens = 900) + "\n")
            setLastModified(now - 20 * 24 * hour)
        }
        TranscriptTokens.refresh(root, ledger, zone, now + hour)
        assertEquals(100L, projects()[StatsCollector.keyOf(app.path)]?.hours?.get(hourOf("2026-09-26T10:00:00Z")))
    }
}
