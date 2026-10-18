package io.github.crmapache.amazingcodex.codex

import io.github.crmapache.amazingcodex.editor.ChangeRevert
import io.github.crmapache.amazingcodex.editor.LineDiff
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The files a turn patched, held against the disk at the turn's end - so that the list of a turn's changed files (see
 * feed/changes.ts) says, and puts back, what the turn really did to each file rather than what its patches alone did.
 *
 * A file changes by more than the patches that report on it. A formatter the project runs as a hook after each edit,
 * the agent's own `sed` or script in the same turn, a code generator the turn ran: each leaves the file other than its
 * patches say, and the list's revert, holding the file against those patches, refused such a file as "changed since"
 * while nobody had touched it after the turn. Claude Code's answer, in the original, rests on the copies the CLI keeps
 * of every file it edits. Codex keeps none, and its own aggregate of the turn (`turn/diff/updated`) is no answer
 * either: measured on 0.163, it is the patches summed - after a `sed` it still showed the patch's line while the disk
 * held another.
 *
 * What Codex does give is the moment: at a patch's `item/started` the file on the disk is still as it was before the
 * patch (an added file is not there yet), at its `item/completed` it is as the patch left it (measured on 0.163). So:
 *
 * - The file is read as the turn's first patch of it starts - the base the turn found ([started]). An added file's base
 *   is no file; a deleted one's, the text the patch deleted.
 * - The base is trusted only when every patch of the file in the turn goes forward onto it ([baseOf]). A read that
 *   came a moment too late, after the patch, does not take the patch forward - but takes it back, and then the base is
 *   the read less the patch. Anything else, and the file is not settled at all: its row stays on its patches, which is
 *   honest, if strict.
 * - At the turn's end - a stopped turn ends too, Codex closes it with `turn/completed` all the same - the base with the
 *   turn's patches made again is what the patches say the file is ([settle]). Read off the disk, it nearly always is.
 *   Where it is not, the panel is told the turn's real change of the file, base to disk, as `files_settled` (the shape
 *   the original's panel reads, see settleChanges), right before the turn's result, while its list is still open.
 *
 * A file the turn deleted that is gone, as the turn left it, is settled by its patches; one gone where the turn left a
 * file, or there where the turn left none, is not settled - nothing here can say what happened to it.
 *
 * The settled changes are kept by turn for this run of the IDE ([Settled]), so a rewind past the turn undoes the same
 * change the list does (see CodeRewind.plan).
 *
 * Fed from the one thread that reads the process's output, the subagents' threads included (see
 * CodexSession.subagentNotification): their patches are the parent turn's too, as the panel lists them.
 */
internal class TurnFiles(
    /** A file's text, or null when it is not there - or too big or unreadable, which is the same here: unknown. */
    private val read: (String) -> String? = ::readFile,
) {

    /** What one file of the turn so far is: where it stands now, what it was at the turn's start, its edits since. */
    private class Track(var path: String, val base: Base) {
        val edits = ArrayList<ChangeRevert.Edit>()

        /** One of its patches was no diff to read: nothing about the file can be said by its edits. */
        var broken = false
    }

    /** What a file was before the turn's first change of it. */
    private sealed interface Base {
        /** Not there - the turn's first change created it. */
        data object Absent : Base

        /** This text - read off the disk as the first patch started, or the text a deletion took. */
        data class Read(val text: String) : Base

        /** Nobody knows: the file could not be read when its first patch started, or the start was never heard. */
        data object Unknown : Base
    }

    /** The turn's files by where each stands now, in the order the turn first changed them. */
    private val tracks = LinkedHashMap<String, Track>()

    /** The texts read as a patch started, by the patch's item and the file's path there - until the patch completes. */
    private val reads = HashMap<String, Map<String, String?>>()

    /**
     * A patch is about to be applied ([item] is its `item/started`): each file it changes that the turn has not changed
     * yet is read now, as the turn found it.
     */
    @Synchronized
    fun started(item: JsonObject) {
        if (AppServer.text(item["type"]) != "fileChange") return
        val id = AppServer.text(item["id"]).ifEmpty { return }
        val read = changesOf(item).filter { it.type == UPDATE && it.path !in tracks }.associate { it.path to read(it.path) }
        reads[id] = read
    }

    /** A patch is over ([item] is its `item/completed`): an applied one goes into its files' edits. */
    @Synchronized
    fun completed(item: JsonObject) {
        if (AppServer.text(item["type"]) != "fileChange") return
        val id = AppServer.text(item["id"])
        val readAtStart = reads.remove(id)
        if (AppServer.text(item["status"]) != "completed") return

        for (change in changesOf(item)) {
            val track = tracks.remove(change.path) ?: Track(
                change.path,
                when (change.type) {
                    ADD -> Base.Absent
                    DELETE -> Base.Read(change.diff)
                    else -> readAtStart?.get(change.path)?.let(Base::Read) ?: Base.Unknown
                },
            )
            when (change.type) {
                ADD -> track.edits += ChangeRevert.Edit.Create(change.diff)
                DELETE -> track.edits += ChangeRevert.Edit.Delete(change.diff)
                else -> {
                    if (change.movedTo != null) track.edits += ChangeRevert.Edit.Move(change.path)
                    val hunks = UnifiedDiff.patch(change.diff)
                    if (hunks == null) track.broken = true else if (hunks.isNotEmpty()) track.edits += ChangeRevert.Edit.Patch(hunks)
                }
            }
            track.path = change.movedTo ?: change.path
            tracks[track.path] = track
        }
    }

    /**
     * The turn is over: every file it patched held against the disk. Returns the `files_settled` line for the files
     * whose patches do not say what stands there - null when there are none, which is nearly always - and the settled
     * changes by file, for [Settled]. Forgets the turn either way: what is patched after this belongs to the next one
     * (a subagent still at work, see CodexSession).
     */
    @Synchronized
    fun settle(): Settlement? {
        val files = tracks.values.mapNotNull { track -> runCatching { settled(track) }.getOrNull() }
        forget()
        if (files.isEmpty()) return null
        val line = buildJsonObject {
            put("type", SETTLED_TYPE)
            putJsonArray("files") {
                for (file in files) {
                    addJsonObject {
                        put("path", file.path)
                        put("added", file.added)
                        put("removed", file.removed)
                        put("edits", editsJson(file.edits))
                    }
                }
            }
        }.toString()
        return Settlement(line, files.associate { it.path to it.edits })
    }

    /** What [settle] found: the line for the panel, and each settled file's whole change in the turn. */
    data class Settlement(val line: String, val files: Map<String, List<ChangeRevert.Edit>>)

    /** The turn is gone without an end to settle it by - its process went (see CodexSession.start). */
    @Synchronized
    fun forget() {
        tracks.clear()
        reads.clear()
    }

    private data class SettledFile(val path: String, val added: Int, val removed: Int, val edits: List<ChangeRevert.Edit>)

    /** One file as the turn really left it - or null when its patches say so already, or when nothing can be said. */
    private fun settled(track: Track): SettledFile? {
        if (track.broken) return null
        val base = baseOf(track) ?: return null
        val expected = ChangeRevert.redo(base.text, track.edits)
        val disk = read(track.path)?.replace("\r\n", "\n")
        val left = when (expected) {
            is ChangeRevert.Result.Text -> disk?.takeIf { it != expected.text } ?: return null
            // Gone as the turn left it, or a turn whose patches no longer go: nothing to say either way.
            else -> return null
        }

        val hunks = LineDiff.hunks(base.text.orEmpty(), left)?.takeIf { it.isNotEmpty() } ?: return null
        val created = track.edits.firstOrNull() is ChangeRevert.Edit.Create
        val moved = ChangeRevert.originOf(track.edits)?.takeIf { it != track.path }
        val edits = buildList {
            // A created file is made empty and then filled, rather than created with its text: the text then travels as
            // short lines of hunks, and a journal cuts long strings (see JournalTrim).
            if (created) add(ChangeRevert.Edit.Create(""))
            moved?.let { add(ChangeRevert.Edit.Move(it)) }
            add(ChangeRevert.Edit.Patch(hunks.map { ChangeRevert.Hunk(it.newStart, it.lines, it.oldStart) }))
        }
        val lines = hunks.flatMap { it.lines }
        return SettledFile(track.path, lines.count { it.startsWith("+") }, lines.count { it.startsWith("-") }, edits)
    }

    /** What a file was before the turn, known: its [text], or null for no file. */
    private data class Known(val text: String?)

    /**
     * What the file was before the turn, when the turn's patches all go forward onto it - and null when no such thing
     * is known (see the class's note on a read that came a moment too late).
     */
    private fun baseOf(track: Track): Known? = when (val base = track.base) {
        Base.Absent -> Known(null).takeIf { ChangeRevert.redo(null, track.edits) != ChangeRevert.Result.Changed }
        Base.Unknown -> null
        is Base.Read -> {
            val text = base.text.replace("\r\n", "\n")
            when {
                ChangeRevert.redo(text, track.edits) != ChangeRevert.Result.Changed -> Known(text)
                // Read after its first patch, not before: that patch goes back off it, and the rest forward from there.
                else -> {
                    val first = track.edits.takeWhile { it !is ChangeRevert.Edit.Patch } + listOfNotNull(track.edits.firstOrNull { it is ChangeRevert.Edit.Patch })
                    val undone = ChangeRevert.undo(text, first) as? ChangeRevert.Result.Text
                    undone?.text?.takeIf { ChangeRevert.redo(it, track.edits) != ChangeRevert.Result.Changed }?.let(::Known)
                }
            }
        }
    }

    /** One file of a patch item, as this class reads it. */
    private data class Change(val path: String, val type: String, val movedTo: String?, val diff: String)

    private fun changesOf(item: JsonObject): List<Change> =
        (item["changes"] as? JsonArray).orEmpty().mapNotNull { element ->
            val change = element as? JsonObject ?: return@mapNotNull null
            val path = AppServer.text(change["path"]).ifEmpty { return@mapNotNull null }
            val kind = change["kind"] as? JsonObject
            Change(
                path = path,
                type = AppServer.text(kind?.get("type")).ifEmpty { UPDATE },
                movedTo = AppServer.text(kind?.get("move_path")).ifEmpty { null }?.takeIf { it != path },
                diff = AppServer.text(change["diff"]),
            )
        }

    /**
     * What this run of the IDE settled, by turn and by file - what a rewind past a turn undoes in place of its patches
     * (see CodeRewind.plan). Kept per tab rather than per process (see CodexSessions), and only for the last
     * [KEPT_TURNS] turns: a rewind reaches back further rarely, and then by the patches, as always.
     */
    class Settled {
        private val turns = object : LinkedHashMap<String, Map<String, List<ChangeRevert.Edit>>>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, List<ChangeRevert.Edit>>>?): Boolean =
                size > KEPT_TURNS
        }

        @Synchronized
        fun put(turn: String, files: Map<String, List<ChangeRevert.Edit>>) {
            if (turn.isNotEmpty() && files.isNotEmpty()) turns[turn] = files
        }

        @Synchronized
        fun of(turn: String, path: String): List<ChangeRevert.Edit>? = turns[turn]?.get(path)
    }

    companion object {
        /** The panel's own event that carries what a turn really did to its files (see [settle]). */
        const val SETTLED_TYPE = "files_settled"

        private const val ADD = "add"
        private const val DELETE = "delete"
        private const val UPDATE = "update"

        /** A file bigger than this is not read: its turn is not settled, and its row stays on its patches. */
        private const val MAX_FILE_BYTES = 8L * 1024 * 1024

        /** How many turns of settled changes a tab keeps - see [Settled]. */
        private const val KEPT_TURNS = 50

        private fun readFile(path: String): String? = runCatching {
            File(path).takeIf { it.isFile && it.length() <= MAX_FILE_BYTES }?.readText(Charsets.UTF_8)
        }.getOrNull()

        /** Edits in the wire shape the panel sends back with a revert (see ChangeRevert.requestsOf). */
        internal fun editsJson(edits: List<ChangeRevert.Edit>): JsonArray = buildJsonArray {
            for (edit in edits) {
                addJsonObject {
                    when (edit) {
                        is ChangeRevert.Edit.Create -> {
                            put("kind", "create")
                            put("content", edit.content)
                        }
                        is ChangeRevert.Edit.Delete -> {
                            put("kind", "delete")
                            put("content", edit.content)
                        }
                        is ChangeRevert.Edit.Move -> {
                            put("kind", "move")
                            put("from", edit.from)
                        }
                        is ChangeRevert.Edit.Patch -> {
                            put("kind", "patch")
                            putJsonArray("hunks") {
                                for (hunk in edit.hunks) {
                                    addJsonObject {
                                        put("oldStart", hunk.oldStart)
                                        put("newStart", hunk.newStart)
                                        put("lines", JsonArray(hunk.lines.map(::JsonPrimitive)))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        /**
         * [message] with the settled edits taken out of every `files_settled` in it, the counts left - for a phone,
         * whose frame a diff of thousands of lines would not fit and which has no revert to use one for (see
         * RemoteAgent's fitted). Null when there was nothing to take.
         */
        fun lightened(message: String): String? {
            if (!message.contains("\"$SETTLED_TYPE\"")) return null
            return runCatching { lighten(Json.parseToJsonElement(message)).toString() }.getOrNull()
        }

        private fun lighten(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> when {
                (element["type"] as? JsonPrimitive)?.contentOrNull == SETTLED_TYPE && element["files"] is JsonArray ->
                    JsonObject(
                        element + (
                            "files" to JsonArray(
                                (element["files"] as JsonArray).map { file -> JsonObject(((file as? JsonObject) ?: JsonObject(emptyMap())) - "edits") },
                            )
                        ),
                    )
                else -> JsonObject(element.mapValues { (_, value) -> lighten(value) })
            }
            is JsonArray -> JsonArray(element.map(::lighten))
            else -> element
        }
    }
}
