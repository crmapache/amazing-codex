package io.github.crmapache.amazingcodex.codex

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * A thread's files on disk, read the way Codex reads them.
 *
 * A thread is one file until it is reverted (`thread/revert` - the panel's rewind, or a terminal's Esc Esc).
 * Codex does not rewrite the file then. It starts a new one beside it, a segment named after the thread and
 * a segment id of its own - `rollout-<time>-<thread>_<segment>.jsonl` - whose first line points back into the
 * file it was cut from: `session_meta.history_base = {thread_id, end_byte_offset}`, where `thread_id` names
 * that file (the thread's id for its first file, a segment id for a segment) and the offset is where the kept
 * part of it ends. Everything said after the revert goes into the new segment. Measured on 0.160: three
 * reverts gave three segments, two of them cut from the first file and one from a segment; every offset fell
 * on a line boundary; `thread/list` listed each file as a thread of its own under the same id.
 *
 * So the thread's history is its newest segment's lines after the kept part of the file it was cut from - and
 * so on back to the first file. Anything that reads a thread off the disk goes through here: read file by
 * file, the history brought back exactly the turns the person rewound to be rid of, and a segment's name read
 * as a thread id of its own.
 */
internal object CodexRollout {

    /** A file's place in its thread: the thread's id, and the segment's for a file a revert started. */
    data class Name(val thread: String, val segment: String?) {
        /** What another segment's `history_base.thread_id` calls this file. */
        val key: String get() = segment ?: thread
    }

    /** Who a rollout file belongs to, from its name - null for a file that is no rollout. */
    fun nameOf(file: File): Name? {
        val match = NAME.find(file.name) ?: return null
        return Name(thread = match.groupValues[1], segment = match.groupValues[2].ifEmpty { null })
    }

    /**
     * The file a thread goes on in now - out of [files], all of one thread: its newest segment, or its only
     * file. Segment ids are time-ordered (UUIDv7), so the newest sorts last.
     */
    fun current(files: Collection<File>): File? =
        files.maxWithOrNull(compareBy<File>({ nameOf(it)?.segment != null }, { nameOf(it)?.segment.orEmpty() }, { it.name }))

    /** Whether [file] is a thread's first file - the one a revert never starts. */
    fun isRoot(file: File): Boolean = nameOf(file)?.segment == null

    /**
     * The files of [thread] under [root], each of its segments included - remembered by path, and looked for
     * again when one of them is gone or a new segment may have appeared ([fresh]).
     */
    fun filesOf(root: File, thread: String, fresh: Boolean = false): List<File> {
        if (!fresh) {
            known[thread]?.takeIf { files -> files.all(File::isFile) }?.let { return it }
        }
        val found = runCatching {
            root.walkTopDown().maxDepth(4)
                .filter { it.isFile && it.name.startsWith("rollout-") && it.name.endsWith(".jsonl") && thread in it.name }
                .filter { nameOf(it)?.thread == thread }
                .toList()
        }.getOrDefault(emptyList())
        if (found.isNotEmpty()) known[thread] = found
        return found
    }

    private val known = ConcurrentHashMap<String, List<File>>()

    /** A segment's own first line says where it was cut from - null for a thread's first file. */
    internal data class Base(val key: String, val endByte: Long)

    internal fun baseOf(file: File): Base? {
        val first = runCatching { file.bufferedReader().use { it.readLine() } }.getOrNull() ?: return null
        if (!first.contains(HISTORY_BASE)) return null
        val meta = runCatching { (Json.parseToJsonElement(first).jsonObject["payload"] as? JsonObject)?.get("history_base") as? JsonObject }
            .getOrNull() ?: return null
        val key = (meta["thread_id"] as? JsonPrimitive)?.contentOrNull ?: return null
        val end = (meta["end_byte_offset"] as? JsonPrimitive)?.longOrNull ?: return null
        return Base(key, end)
    }

    /** One file's share of the history: its lines up to [endByte] (all when null), past its first line when [skipFirst]. */
    internal data class Part(val file: File, val endByte: Long?, val skipFirst: Boolean)

    /**
     * The files [file]'s history is read from, oldest first. [byKey] finds a file of the same thread by what a
     * segment calls it (see [Name.key]). A base that cannot be found ends the climb there: what is left is still
     * the thread's latest words, and an empty history would be the worse answer.
     */
    internal fun partsOf(file: File, byKey: (String) -> File?): List<Part> {
        val parts = ArrayList<Part>()
        var at = file
        var limit: Long? = null
        val seen = HashSet<String>()

        while (seen.add(at.path) && seen.size <= MAX_SEGMENTS) {
            val base = baseOf(at)
            parts += Part(at, limit, skipFirst = base != null)
            val from = base?.let { byKey(it.key) } ?: break
            limit = base.endByte
            at = from
        }
        return parts.reversed()
    }

    /**
     * [block] over the history of the thread [file] belongs to, as Codex would resume it - the file's own
     * lines when it was never reverted, which is nearly always.
     */
    fun <T> useLines(file: File, block: (Sequence<String>) -> T): T {
        val name = nameOf(file)
        val root = file.parentFile?.parentFile?.parentFile?.parentFile
        if (name == null || root == null || (name.segment == null && !file.readsAsSegment())) return file.useLines(block = block)

        val siblings = filesOf(root, name.thread).associateBy { nameOf(it)?.key }
        val parts = partsOf(file) { key -> siblings[key] ?: filesOf(root, name.thread, fresh = true).firstOrNull { nameOf(it)?.key == key } }
        return useParts(parts, block)
    }

    /** A first file never starts with a base - read as one anyway when it somehow does, rather than trusted by name. */
    private fun File.readsAsSegment(): Boolean = baseOf(this) != null

    internal fun <T> useParts(parts: List<Part>, block: (Sequence<String>) -> T): T {
        val streams = ArrayList<InputStream>()
        try {
            val lines = parts.asSequence().flatMap { part ->
                val stream = BufferedInputStream(part.file.inputStream()).also(streams::add)
                linesOf(stream, part.endByte).drop(if (part.skipFirst) 1 else 0)
            }
            return block(lines)
        } finally {
            streams.forEach { runCatching { it.close() } }
        }
    }

    /**
     * The lines of [input] that begin before [endByte] - all of them when it is null - without their breaks.
     * By chunks rather than by bytes, for the reason SearchIndex reads that way: a byte at a time is a call per
     * byte, and a working day's thread is tens of megabytes.
     */
    private fun linesOf(input: InputStream, endByte: Long?): Sequence<String> = sequence {
        val line = ByteArrayOutputStream()
        val chunk = ByteArray(CHUNK)
        var position = 0L
        while (endByte == null || position < endByte) {
            val wanted = if (endByte == null) CHUNK else minOf(CHUNK.toLong(), endByte - position).toInt()
            val got = input.read(chunk, 0, wanted)
            if (got < 0) break
            var start = 0
            for (index in 0 until got) {
                if (chunk[index].toInt() != NEWLINE) continue
                line.write(chunk, start, index - start)
                yield(line.toString(StandardCharsets.UTF_8))
                line.reset()
                start = index + 1
            }
            if (start < got) line.write(chunk, start, got - start)
            position += got
        }
        if (line.size() > 0) yield(line.toString(StandardCharsets.UTF_8))
    }

    private const val CHUNK = 64 * 1024

    /** rollout-<time>-<thread>[_<segment>].jsonl */
    private val NAME = Regex(
        "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(?:_([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}))?\\.jsonl$",
    )

    private const val HISTORY_BASE = "\"history_base\""
    private const val NEWLINE = '\n'.code

    /** How many files back a thread is followed at most - a cycle in the pointers ends the climb, not the IDE. */
    private const val MAX_SEGMENTS = 256
}
