package io.github.crmapache.amazingcodex.codex

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Where a fork comes from: the conversation it branched off, and the line of that conversation's transcript
 * it ends on.
 *
 * Fixed the moment the fork is made, not when its process first comes up - and that is the whole point. A
 * fork's process is raised by its first message, which may come minutes after the button was pressed, and a
 * fork of the whole conversation used to carry whatever the parent held by THEN: a parent still working put
 * its later turns into the fork, past everything the person saw when they pressed. Now that the fork's feed
 * shows what it inherited (see CodexHistory.forkOpening), that would be a feed lying about what the agent
 * remembers. So the end is pinned at the press and handed to Codex as the fork's `lastTurnId` for the whole
 * conversation too (see CodexSession.openThread).
 *
 * Kept beside the fork as long as it lives: by the tab before its first message (see TabMemory.Tab), and by
 * the book after it (see ForkBook) - its transcript holds the inherited lines but never says where its own
 * begin, and the seam in the feed has to stand there after a restart, in a conversation opened from the
 * history and on a phone.
 */
internal data class ForkOrigin(
    /** The conversation forked. */
    val source: String,
    /** Its name when the fork was made - what the seam in the fork's feed calls it. */
    val title: String,
    /** Forked from a chosen message rather than whole - the seam says which (see feed/build.ts). */
    val cut: Boolean,
    /**
     * The last turn of [source] the fork carries - what `thread/fork` is given as `lastTurnId`, and where
     * the seam stands (a fork keeps the turn ids it inherited, measured on 0.160). Null when the fork carries
     * nothing at all: one made from the very first message, or of a conversation nothing was said in yet.
     */
    val at: String?,
) {
    fun json(): JsonObject = buildJsonObject {
        put("source", source)
        put("title", title)
        put("cut", cut)
        at?.let { put("at", it) }
    }

    /**
     * The seam as a line of the conversation's history: where a fork's own part begins, read and paged like
     * any other line (see CodexHistory.page) and drawn by the feed as the fork's mark.
     *
     * A line rather than a message of its own because a line is what every road to the feed already
     * carries in order: the replay into a tab, a page of history and the tail handed to a phone. A mark sent
     * beside them would have to be put in its place three times over. Its type is the first key, which is
     * what tells it apart cheaply ([isSeam]); it has no uuid, so no page can begin on it or be asked for by it.
     */
    fun seamLine(): String = buildJsonObject {
        put("type", SEAM_TYPE)
        put("source", source)
        put("title", title)
        put("cut", cut)
    }.toString()

    /** A fork worked out against its source - and whether the message it was to stop at was not found. */
    data class Resolved(val origin: ForkOrigin?, val missed: Boolean = false)

    companion object {
        const val SEAM_TYPE = "fork_seam"

        /**
         * A fork asked to stop at a message came up with the whole conversation instead - see [resolve]. A code rather than a sentence: the panel words it in the person's language
         * (see errorWords in webview/src/components/items/Rows.tsx).
         */
        const val WHOLE = "FORK_WHOLE"

        private const val SEAM_PREFIX = "{\"type\":\"$SEAM_TYPE\""

        fun isSeam(line: String): Boolean = line.startsWith(SEAM_PREFIX)

        fun decode(json: JsonObject?): ForkOrigin? {
            if (json == null) return null
            val source = json.text("source")?.takeIf(Rewind::isUuid) ?: return null
            val at = json.text("at")
            if (at != null && !Rewind.isUuid(at)) return null

            return ForkOrigin(
                source = source,
                title = json.text("title").orEmpty(),
                cut = (json["cut"] as? JsonPrimitive)?.booleanOrNull ?: false,
                at = at,
            )
        }

        /**
         * A fork of [source] worked out against its turns (see CodexHistory.turnRefs): of the whole
         * conversation when [before] is null - through its last finished turn, the one Codex can fork through
         * - otherwise up to and not including the turn [before] began.
         *
         * [before] is a message's name in the feed: Codex's id for a message read from the history, or the
         * panel's own for one sent in this run of the IDE, which only the conversation that sent it can
         * place ([turnOf]). A message written into a running turn is no turn's beginning, and the panel never
         * asks to fork before one (see forkPointAfter in feed/branch.ts).
         *
         * A message the turns do not hold may still be the conversation's own: one inherited from the
         * conversation it was itself forked from. It is looked for there, and the fork is then a fork of that
         * conversation - which is exactly what the person forked. Found nowhere, the fork carries the whole
         * conversation and says so ([Resolved.missed]): a fork that quietly holds the turns somebody forked
         * to get away from is the one outcome worse than an explained one.
         */
        fun resolve(
            turns: (String) -> List<CodexHistory.TurnRef>?,
            origins: (String) -> ForkOrigin?,
            source: String,
            title: String,
            before: String?,
            turnOf: (String) -> String? = { null },
        ): Resolved = resolve(turns, origins, source, title, before, turnOf, depth = 0)

        private fun resolve(
            turns: (String) -> List<CodexHistory.TurnRef>?,
            origins: (String) -> ForkOrigin?,
            source: String,
            title: String,
            before: String?,
            turnOf: (String) -> String?,
            depth: Int,
        ): Resolved {
            // Nothing on disk is nothing to carry - and nothing to fork either: Codex refuses a thread it has
            // no history for.
            val list = turns(source)?.takeIf { it.isNotEmpty() }
                ?: return Resolved(ForkOrigin(source, title, cut = before != null, at = null))

            if (before == null) return Resolved(ForkOrigin(source, title, cut = false, at = lastFinished(list)))

            val named = turnOf(before)
            val at = list.indexOfFirst { turn -> turn.id == named || turn.id == before || before in turn.messages }
            return when {
                at == 0 -> Resolved(ForkOrigin(source, title, cut = true, at = null))
                at > 0 -> Resolved(ForkOrigin(source, title, cut = true, at = list[at - 1].id))
                else -> {
                    val older = origins(source)
                        ?.takeIf { depth < MAX_DEPTH }
                        ?.let { resolve(turns, origins, it.source, it.title, before, turnOf, depth + 1) }
                        ?.takeIf { !it.missed }

                    older ?: Resolved(ForkOrigin(source, title, cut = false, at = lastFinished(list)), missed = true)
                }
            }
        }

        /**
         * The newest turn a fork can be cut through: Codex will not fork through one still running, and a turn
         * still running at the press is not what the person saw finished either.
         */
        private fun lastFinished(turns: List<CodexHistory.TurnRef>): String? = turns.lastOrNull { it.finished }?.id

        /** How far back through forks of forks a message is looked for - see [resolve]. */
        private const val MAX_DEPTH = 16

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}
