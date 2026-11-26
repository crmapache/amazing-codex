package io.github.crmapache.amazingcodex.codex

import com.intellij.openapi.diagnostic.thisLogger
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * How many tokens went through Codex, read straight out of the conversations it writes
 * (`~/.codex/sessions/YYYY/MM/DD/rollout-*.jsonl`) - every project at once, the aquamarine figure beside the
 * rings and the statistics tab's tokens card (see stats/TranscriptTokens).
 *
 * Every model request leaves a `token_count` event with the usage of that one request (`last_token_usage`)
 * and the thread's running total (`total_token_usage`). Counted for every conversation, not only the panel's:
 * a turn run in Codex's own terminal was spent all the same, and so were a spawned agent's turns and a
 * scenario run's, which keep threads - files - of their own.
 *
 * An event that repeats the running total of the one before it in the same file is left out: Codex says the
 * usage again when only the rate limits changed (measured on 0.163: 72 of 5073 events), and that is no new
 * request. A fork or a rewound conversation needs nothing of the kind - its file points at the earlier one's
 * lines (`history_base`) instead of copying them (measured on 0.163), so every event is in one file only. The
 * events are still keyed by their time and running total, so that a file read twice in one reading cannot
 * count twice.
 *
 * Archived conversations (`archived_sessions`) are not read: Codex compresses them with zstd, which the IDE has no
 * reader for. What a reading saw of them before they were archived stays in the book.
 *
 * Every request is also put down to the folder Codex was working in when it was made (ACC 0.13.50): the thread's
 * `cwd` as its file says it - `session_meta` at the top, and each `turn_context` after it, since a turn can move the
 * thread elsewhere. Which project a folder belongs to is the statistics' business, not the reader's (see
 * stats/TranscriptTokens).
 */
internal object CodexTokenUsage {

    private const val MAX_DEPTH = 8

    private const val HOUR_MS = 60L * 60 * 1000

    /**
     * Threads for one reading. A first reading goes through every conversation there is, and the files are
     * independent of each other. Half the cores and no more than four, at the lowest priority: it runs while
     * somebody works in the editor.
     */
    private val THREADS = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)

    /**
     * What one reading found: the tokens by the hour, and the same tokens again by the folder Codex was working in
     * when it spent them, in Codex's own spelling of it (a Linux path inside WSL).
     */
    class Reading(
        /** Hour since the epoch -> tokens, every folder together. */
        val hours: Map<Long, Long>,
        /** Folder -> hour since the epoch -> tokens. A request with no folder known is in [hours] only. */
        val directories: Map<String, Map<Long, Long>>,
    )

    /**
     * The tokens of every conversation under [root] changed at or after [since] (epoch millis, zero for all of
     * them), by the hour each request was answered in - an hour counted from the epoch, so that the figure means
     * the same in every time zone (see stats/TokenHome) - and by the folder it was made in. Null if the folder could
     * not be walked at all; a single file that could not be read is left out and logged, the rest still count.
     */
    fun read(root: File, since: Long): Reading? {
        val files = runCatching {
            root.walkTopDown()
                .maxDepth(MAX_DEPTH)
                .filter { it.isFile && it.extension == "jsonl" && it.lastModified() >= since }
                // The biggest first: a big file started last would leave one thread reading it alone.
                .sortedByDescending { it.length() }
                .toList()
        }.onFailure { thisLogger().warn("Failed to scan Codex sessions for token usage", it) }.getOrNull() ?: return null

        // By a hash of the pair rather than the pair itself: a first reading meets tens of thousands of requests.
        val requests = ConcurrentHashMap<Long, Request>()
        val pool = Executors.newFixedThreadPool(THREADS) { task ->
            Thread(task, "Amazing Codex token reading").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
        val finished = runCatching {
            files.map { file -> pool.submit(Runnable { scanFile(file, requests) }) }.forEach { it.get() }
        }
        pool.shutdownNow()
        finished.onFailure {
            thisLogger().warn("The token reading was cut short", it)
            return null
        }

        val hours = HashMap<Long, Long>()
        val directories = HashMap<String, HashMap<Long, Long>>()
        for (request in requests.values) {
            val hour = Math.floorDiv(request.at, HOUR_MS)
            hours.merge(hour, request.tokens, Long::plus)
            if (request.directory.isNotEmpty()) directories.getOrPut(request.directory) { HashMap() }.merge(hour, request.tokens, Long::plus)
        }
        return Reading(hours, directories)
    }

    /**
     * "445.5M" - the figure by the composer. A fixed locale: in an Arabic or Devanagari one the digits themselves
     * would come out in another script.
     */
    fun millions(tokens: Long): String = String.format(Locale.ROOT, "%.1fM", tokens / 1_000_000.0)

    /** One model request: when it was answered, what it cost, and the folder Codex worked in then. */
    private class Request(val at: Long, val tokens: Long, val directory: String)

    private fun scanFile(file: File, requests: ConcurrentHashMap<Long, Request>) {
        runCatching {
            var lastTotal = -1L
            // One string per folder per file rather than one per request: nearly all of a file's requests share it.
            var directory = ""
            file.useLines { lines ->
                for (line in lines) {
                    if (line.contains(SESSION_META) || line.contains(TURN_CONTEXT)) {
                        directoryOf(line)?.let { if (it != directory) directory = it }
                        continue
                    }
                    if (!line.contains(TOKEN_COUNT)) continue
                    val event = eventOf(line) ?: continue
                    if (event.total == lastTotal) continue
                    lastTotal = event.total
                    if (event.tokens <= 0) continue

                    val key = (event.stamp.hashCode().toLong() shl 32) or (event.total.hashCode().toLong() and 0xffffffffL)
                    requests.putIfAbsent(key, Request(event.at, event.tokens, directory))
                }
            }
        }.onFailure { thisLogger().warn("Failed to scan $file for token usage", it) }
    }

    /**
     * The `cwd` of a `session_meta` or `turn_context` line - read off the line rather than parsed whole: a thread's
     * first line carries its instructions, tens of kilobytes, and the folder sits near its start.
     */
    internal fun directoryOf(line: String): String? {
        val raw = CWD.find(line)?.groupValues?.get(1) ?: return null
        return runCatching { Json.parseToJsonElement("\"$raw\"").jsonPrimitive.contentOrNull }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** A `token_count` event: its time as written and as epoch millis, the thread's running total, and this request's tokens. */
    internal class Event(val stamp: String, val at: Long, val total: Long, val tokens: Long)

    internal fun eventOf(line: String): Event? {
        val event = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return null
        val payload = event["payload"] as? JsonObject ?: return null
        if (payload["type"]?.jsonPrimitive?.contentOrNull != "token_count") return null
        val info = payload["info"] as? JsonObject ?: return null

        val stamp = event["timestamp"]?.jsonPrimitive?.contentOrNull ?: return null
        val at = runCatching { Instant.parse(stamp).toEpochMilli() }.getOrNull() ?: return null
        val total = (info["total_token_usage"] as? JsonObject)?.let(::tokensOf) ?: return null
        val tokens = (info["last_token_usage"] as? JsonObject)?.let(::tokensOf) ?: 0L
        return Event(stamp, at, total, tokens)
    }

    private fun tokensOf(usage: JsonObject): Long? = usage["total_tokens"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

    private const val TOKEN_COUNT = "\"token_count\""
    private const val SESSION_META = "\"type\":\"session_meta\""
    private const val TURN_CONTEXT = "\"type\":\"turn_context\""
    private val CWD = Regex("\"cwd\":\"((?:[^\"\\\\]|\\\\.)*)\"")
}
