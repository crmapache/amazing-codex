package io.github.crmapache.amazingcodex.codex.terminal

import com.intellij.openapi.diagnostic.thisLogger
import io.github.crmapache.amazingcodex.codex.AppServer
import io.github.crmapache.amazingcodex.codex.CodexCatalog
import io.github.crmapache.amazingcodex.codex.CodexReplay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * What a terminal added to a conversation, in the shape the feed draws - the turns after the last one the feed already
 * holds (see CodexSessionHub.terminalReleased).
 *
 * **Anchored on ids, not on a position.** Every item of a Codex turn has an id, and the same id is the uuid of the
 * line the panel drew it with - live (see CodexStream) and replayed (see CodexReplay) alike; the pictures of a message
 * from the history rest on that already (see ThreadPictures). So the turns are walked from the newest down, and the
 * first one holding an item the feed knows is where the feed stands: what is newer than it is the terminal's.
 *
 * **Anything this cannot answer honestly is [Tail.Whole]**: draw the conversation again from its end, the way the
 * history opens it. No anchor among the newest turns - the feed holds nothing of this thread, or the terminal rewound
 * it past everything the feed holds (Codex's `thread/revert` takes the turns away, and with them the anchors). And more
 * than a page's worth - the feed would take it in one batch, and a conversation that went on for hours in the terminal
 * is better opened from its end, with the rest a page away.
 */
internal object TranscriptTail {

    sealed interface Tail {
        /** The lines to play into the feed after what it holds - possibly none. */
        data class Lines(val lines: List<String>) : Tail

        /** Draw the conversation again from its end. */
        data object Whole : Tail
    }

    /** How many of the feed's own uuids are looked for - its last ones, newest first. */
    const val ANCHORS = 200

    /** At most this many turns go into the feed after the anchor - the turns of a page the desk opens with. */
    internal const val MAX_TURNS = 30

    /** And at most this many characters of them. */
    internal const val MAX_CHARS = 512_000

    /** A turn's command output, cut the way a desk page cuts it. */
    private const val OUTPUT_CHARS = 12_000

    private const val TURNS_PER_REQUEST = 10

    /** The uuid an agent's line in the feed's journal carries - its item id - read out of the line's envelope. */
    fun uuidOf(envelope: String): String? = runCatching {
        val outer = Json.parseToJsonElement(envelope) as? JsonObject ?: return null
        val event = outer["event"] as? JsonObject ?: return null
        (event["uuid"] as? JsonPrimitive)?.contentOrNull
    }.getOrNull()

    /** The turns of [threadId] after the newest one holding an item of [known], through the shared catalog process. */
    fun after(threadId: String, known: Collection<String>): Tail = runCatching {
        after(threadId, known) { method, params -> CodexCatalog.call(method, params, timeoutSeconds = 30) }
    }.onFailure { thisLogger().warn("The terminal's part of a conversation could not be read", it) }
        .getOrDefault(Tail.Whole)

    /** The same, with Codex handed in, so that a test can walk it over turns of its own. */
    fun after(threadId: String, known: Collection<String>, ask: (String, JsonObject) -> JsonElement?): Tail {
        if (known.isEmpty()) return Tail.Whole
        val anchors = known.toHashSet()

        // Newest first, as Codex hands them out; the feed's turn is found before the walk outgrows a page.
        val newer = ArrayList<JsonObject>()
        var cursor: String? = null
        while (newer.size <= MAX_TURNS) {
            val result = ask(
                "thread/turns/list",
                buildJsonObject {
                    put("threadId", threadId)
                    put("limit", TURNS_PER_REQUEST)
                    put("sortDirection", "desc")
                    put("itemsView", "full")
                    cursor?.let { put("cursor", it) }
                },
            ) as? JsonObject ?: return Tail.Whole

            for (element in (result["data"] as? JsonArray).orEmpty()) {
                val turn = element as? JsonObject ?: continue
                if (holdsAny(turn, anchors)) return linesOf(newer)
                newer += turn
                if (newer.size > MAX_TURNS) return Tail.Whole
            }
            cursor = AppServer.text(result["nextCursor"]).ifEmpty { null } ?: return Tail.Whole
        }
        return Tail.Whole
    }

    private fun holdsAny(turn: JsonObject, anchors: Set<String>): Boolean =
        AppServer.text(turn["id"]) in anchors ||
            (turn["items"] as? JsonArray).orEmpty().any { AppServer.text((it as? JsonObject)?.get("id")) in anchors }

    /** The turns, oldest first, as the feed draws them - or [Tail.Whole] when they would not fit one batch. */
    private fun linesOf(newestFirst: List<JsonObject>): Tail {
        val lines = newestFirst.asReversed().flatMap { turn -> CodexReplay.lines(turn, OUTPUT_CHARS, closeTurn = true) }
        if (lines.sumOf { it.length } > MAX_CHARS) return Tail.Whole
        return Tail.Lines(lines)
    }
}
