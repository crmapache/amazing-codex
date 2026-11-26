package io.github.crmapache.amazingcodex.editor

import io.github.crmapache.amazingcodex.codex.TurnFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Everything a turn changed in the project, found on the disk - rather than in the calls that report on it.
 *
 * The list under a turn was drawn from the edit tools' results, and a turn changes files by much more than those. Reported
 * by Max 2026-10-24 with a screenshot: the IDE's commit window said 156 files, the list under the turn said five. All of
 * it was that one turn, an hour long: the agent changed most files with Python and `sed` through Bash (the CLI's own
 * system prompt in bypass mode tells it to), and four background subagents edited sixteen more - their results reach the
 * stream without the structured half the list is counted from (measured on 2.1.293). Neither says a word about a diff.
 *
 * So the project is written down as the turn begins and again as it ends ([WorkTreeSnapshots]), and every file that
 * differs between the two is the turn's - with the diff from one to the other, which is what the row counts and what a
 * revert undoes. Its edits, its scripts, its subagents, a formatter after them, a generator it ran: all one picture.
 *
 * What changed on the disk while the turn ran is not always the turn's, and two kinds are left out:
 * - a file the IDE itself wrote (an editor saved, a refactoring, a revert of an earlier turn) - the person's work, which
 *   a "Revert all" must never take away. The IDE tells its own writes from outside ones (see [noteIdeWrite]);
 * - a file another tab's agent named in its edit calls while this turn ran - its edit, not this one's.
 *   A file the turn's own calls named stays whatever else touched it.
 * What neither can tell apart stays in: another tab's Bash in the same project at the same moment, a terminal beside
 * the IDE. Both are rare, and a revert of such a file refuses or is brought back by the same row.
 *
 * A turn is measured from the moment a person's message went in - or, for a turn the CLI started by itself (a
 * background agent reporting back), from the end of the turn before: the work in between is the agents', and it is
 * this turn that reports it.
 */
internal class TurnFootprint(
    /** The project's folder, when there is one. */
    private val root: () -> File?,
    private val snapshots: WorkTreeSnapshots,
    /** Said to the diagnostics log when the footprint gives up for good. */
    private val say: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {

    private class Baseline(val snapshot: WorkTreeSnapshots.Snapshot, val at: Long)

    /** By tab: the project as its turn began. */
    private val baselines = ConcurrentHashMap<String, Baseline>()

    /** By file: the tabs whose agents named it in an edit call, and when they last did. */
    private val namedBy = ConcurrentHashMap<String, ConcurrentHashMap<String, Long>>()

    /** By file: when the IDE itself last wrote it. */
    private val ideWrites = ConcurrentHashMap<String, Long>()

    /** The tabs whose turn [end] has just measured - see [turnOver]. */
    private val measured = ConcurrentHashMap.newKeySet<String>()

    /** Git missing or too slow for this project: every turn would wait on it for nothing. */
    @Volatile
    private var off = false

    private val begun = AtomicInteger()

    /**
     * A turn begins. [carryOn] keeps the start already held: a background agent of the last turn is still at work, and
     * what it changes since belongs to the turn it reports to.
     */
    fun begin(sessionId: String, carryOn: Boolean = false) {
        if (off || (carryOn && baselines.containsKey(sessionId))) return
        val root = root() ?: return
        // A store that has grown big starts over - only when no other tab's turn is measured from it.
        val fresh = begun.incrementAndGet() % OUTGROWN_CHECK_EVERY == 0 && baselines.keys.none { it != sessionId } && snapshots.outgrown(root)
        val at = now()
        val snapshot = take(root, fresh)
        if (snapshot == null) baselines.remove(sessionId) else baselines[sessionId] = Baseline(snapshot, at)
    }

    /**
     * The turn is over: every file it changed, as rows for the panel (see [settledEvent]) - and the project as it stands
     * now is where the next turn of this tab starts, should the CLI begin one by itself.
     */
    fun end(sessionId: String): List<JsonObject> {
        if (off) return emptyList()
        val root = root() ?: return emptyList()
        val at = now()
        measured += sessionId
        val snapshot = take(root, fresh = false) ?: run {
            baselines.remove(sessionId)
            return emptyList()
        }
        val baseline = baselines.put(sessionId, Baseline(snapshot, at)) ?: return emptyList()
        prune()

        val changes = snapshots.changes(baseline.snapshot, snapshot) ?: return emptyList()
        val theirs = changes.filter { turnOwns(it.file, sessionId, baseline.at) }.take(MAX_FILES)
        if (theirs.isEmpty()) return emptyList()
        val contents = snapshots.contents(snapshot, theirs.flatMap { listOfNotNull(it.before, it.after) }.distinct()) ?: return emptyList()

        var budget = MAX_LINES
        return theirs.mapNotNull { change ->
            row(change, contents, budget)?.also { (_, lines) -> budget -= lines }?.first
        }
    }

    /**
     * The turn is over, with or without a result. After one, [end] has measured it and its end is where the next turn
     * starts. Without one - stopped, cut back by a rewind - its list closed before anything was measured, and the next
     * turn starts afresh rather than inherit its files.
     */
    fun turnOver(sessionId: String) {
        if (!measured.remove(sessionId)) baselines.remove(sessionId)
    }

    /** The tab's process is gone, and nothing it began is measured any more. */
    fun drop(sessionId: String) {
        measured.remove(sessionId)
        baselines.remove(sessionId)
    }

    /** A line of the agent's stream: the files its edit calls name, its subagents' included. */
    fun noteLine(sessionId: String, line: String) {
        if (!line.contains(ASSISTANT) || !line.contains(TOOL_USE)) return
        if (!line.contains(FILE_PATH) && !line.contains(NOTEBOOK_PATH)) return
        val event = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return
        val blocks = (event["message"] as? JsonObject)?.get("content") as? JsonArray ?: return
        val at = now()
        for (block in blocks) {
            val call = block as? JsonObject ?: continue
            if (call.text("type") != "tool_use" || call.text("name") !in EDITING) continue
            val input = call["input"] as? JsonObject ?: continue
            val path = input.text("file_path").ifEmpty { input.text("notebook_path") }.ifEmpty { continue }
            namedBy.computeIfAbsent(keyOf(path)) { ConcurrentHashMap() }[sessionId] = at
        }
    }

    /** The IDE wrote [path] itself - not an outside process (see VFileEvent.isFromRefresh). */
    fun noteIdeWrite(path: String) {
        ideWrites[keyOf(path)] = now()
    }

    /** Whether the turn of [sessionId], begun at [since], is the one to answer for [file] (see the class's note). */
    private fun turnOwns(file: File, sessionId: String, since: Long): Boolean {
        val key = keyOf(file.path)
        val named = namedBy[key]
        if ((named?.get(sessionId) ?: -1) >= since) return true
        if ((ideWrites[key] ?: -1) >= since) return false
        return named?.none { (other, at) -> other != sessionId && at >= since } ?: true
    }

    private fun take(root: File, fresh: Boolean): WorkTreeSnapshots.Snapshot? {
        val started = now()
        val snapshot = snapshots.take(root, fresh)
        val took = now() - started
        if (took > SLOW_MS) {
            off = true
            say("the project's snapshot took ${took}ms - files changed outside the edit tools are not looked for")
        }
        return snapshot
    }

    /** Marks older than every turn still measured say nothing any more. */
    private fun prune() {
        val oldest = (baselines.values.minOfOrNull { it.at } ?: now()).coerceAtMost(now() - KEEP_MARKS_MS)
        ideWrites.values.removeIf { it < oldest }
        namedBy.values.forEach { tabs -> tabs.values.removeIf { it < oldest } }
        namedBy.values.removeIf { it.isEmpty() }
    }

    /**
     * One file's row, and how many diff lines it carries - or null when the two sides read the same (only line endings
     * changed). A file that is not text, too big, or past [budget] is listed without figures ("±") and cannot be put back.
     */
    private fun row(change: WorkTreeSnapshots.Change, contents: Map<String, WorkTreeSnapshots.Content>, budget: Int): Pair<JsonObject, Int>? {
        val created = change.before == null
        val removed = change.after == null
        val before = if (created) "" else textOf(contents[change.before])
        val after = if (removed) "" else textOf(contents[change.after])
        val hunks = if (before != null && after != null && budget > 0) LineDiff.hunks(before, after, maxLines = minOf(budget, MAX_FILE_LINES)) else null
        if (hunks != null && hunks.isEmpty() && !created && !removed) return null

        val lines = hunks?.flatMap { it.lines }.orEmpty()
        val row = buildJsonObject {
            put("path", change.file.path)
            if (created) put("created", true)
            if (removed) put("deleted", true)
            if (hunks == null) {
                put("added", 0)
                put("removed", 0)
                put("uncounted", true)
                return@buildJsonObject
            }
            put("added", lines.count { it.startsWith("+") })
            put("removed", lines.count { it.startsWith("-") })
            putJsonArray("edits") {
                // Made empty and then filled - its text then travels as short lines, which a journal does not cut (see
                // JournalTrim) - and taken away the same way: emptied, then removed.
                if (created) {
                    addJsonObject {
                        put("kind", "create")
                        put("content", "")
                    }
                }
                if (hunks.isNotEmpty()) {
                    addJsonObject {
                        put("kind", "patch")
                        put("hunks", hunksJson(hunks))
                    }
                }
                if (removed) addJsonObject { put("kind", "remove") }
            }
        }
        return row to lines.size
    }

    /** The text of a file, or null when it is not one this can diff: binary, too big, not UTF-8, an LFS pointer. */
    private fun textOf(content: WorkTreeSnapshots.Content?): String? {
        val bytes = (content as? WorkTreeSnapshots.Content.Bytes)?.bytes ?: return null
        val head = bytes.size.coerceAtMost(BINARY_PROBE)
        for (index in 0 until head) if (bytes[index] == 0.toByte()) return null
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            return null
        }
        // Git keeps a file under LFS as a pointer: the lines of the pointer are not the lines of the file.
        if (text.startsWith(LFS_POINTER)) return null
        return text.replace("\r\n", "\n")
    }

    private fun hunksJson(hunks: List<LineDiff.Hunk>): JsonArray = buildJsonArray {
        for (hunk in hunks) {
            add(
                buildJsonObject {
                    put("oldStart", hunk.oldStart)
                    put("newStart", hunk.newStart)
                    put("lines", JsonArray(hunk.lines.map(::JsonPrimitive)))
                },
            )
        }
    }

    companion object {
        /** A snapshot slower than this turns the footprint off for the project: every turn would wait on it. */
        private const val SLOW_MS = 5_000L

        /** More files than this in one turn is a migration; the first ones are listed. */
        private const val MAX_FILES = 2_000

        /** Diff lines carried for the whole turn, and for one file; past them a file is listed without figures. */
        private const val MAX_LINES = 300_000
        private const val MAX_FILE_LINES = 100_000

        private const val OUTGROWN_CHECK_EVERY = 20

        /** Marks outlive the turns they were made in by this much at least - a background agent can run long. */
        private const val KEEP_MARKS_MS = 24L * 60 * 60 * 1000

        private const val BINARY_PROBE = 8_000
        private const val LFS_POINTER = "version https://git-lfs.github.com/spec/"

        private val EDITING = setOf("Edit", "MultiEdit", "Write", "NotebookEdit")

        private const val ASSISTANT = "\"type\":\"assistant\""
        private const val TOOL_USE = "\"tool_use\""
        private const val FILE_PATH = "\"file_path\""
        private const val NOTEBOOK_PATH = "\"notebook_path\""

        /** One spelling of a path for comparing: the CLI writes `\` on Windows, the IDE `/`, and Windows does not mind case. */
        fun keyOf(path: String): String {
            val forward = path.replace('\\', '/')
            return if (File.separatorChar == '\\') forward.lowercase() else forward
        }

        /**
         * The event that tells the panel what the turn did to its files: what [TurnFiles] settled of the files its
         * edits named, with the footprint's word over it for every file the footprint found - it measured the whole turn,
         * not one road of it - and the files only the footprint found after them. [turn] names the event, so a list made
         * of found files alone is named the same in every feed built from the journal (see closeChanges in the panel).
         */
        fun settledEvent(settled: String?, found: List<JsonObject>, turn: String): String? {
            if (found.isEmpty()) return settled
            val foundKeys = found.mapTo(HashSet()) { keyOf(it.text("path")) }
            val earlier = settled
                ?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                ?.get("files")?.let { it as? JsonArray }
                ?.filter { keyOf((it as? JsonObject)?.text("path").orEmpty()) !in foundKeys }
                .orEmpty()
            return buildJsonObject {
                put("type", TurnFiles.SETTLED_TYPE)
                put("turn", turn)
                put("files", JsonArray(earlier + found))
            }.toString()
        }

        private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull ?: ""
    }
}
