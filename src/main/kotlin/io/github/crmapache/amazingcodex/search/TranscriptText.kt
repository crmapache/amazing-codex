package io.github.crmapache.amazingcodex.search

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Who said it - the two voices a search can find, named as the wire names them. */
internal enum class Speaker(val wire: String) {
    YOU("you"),
    /** The agent. Its wire name stays the one the panel's protocol has always used for the answering side. */
    AGENT("claude"),
}

/**
 * One message as the search knows it: enough to find it, to show it and to jump to it, and nothing
 * else. `uuid` is the transcript's own name for the line - the feed opens a conversation at it (see
 * feed/search.ts), and a page of older messages is asked for by it (see CodexHistory.page).
 */
internal data class IndexedMessage(
    val conversation: String,
    val uuid: String,
    /** Epoch milliseconds; zero when the line carried no time. */
    val at: Long,
    val speaker: Speaker,
    val text: String,
)

/**
 * What a transcript line contributes to the search: the person's words and the model's, and nothing
 * of the machinery between them.
 *
 * A transcript is mostly machinery. On this machine six hundred megabytes of it hold four megabytes of
 * words - the rest is tool results, file contents, command output, hooks talking to themselves - and
 * a search over all of it finds every file the agent ever read before it finds the sentence somebody
 * remembers writing. So the rule is stated the positive way, as the history's own count is (see
 * CodexHistory.scan): a message is what was said, by either side, on the screen.
 *
 * The cheap checks go first and by substring, exactly as the history does them: these lines number in
 * the tens of thousands per project and some of them run to a hundred kilobytes, and parsing each one
 * to find out it is a tool result is most of the cost of building the index at all.
 */
internal object TranscriptText {

    /**
     * One line of a Codex conversation file as a message worth searching, or nothing.
     *
     * Codex writes what was said twice over - once as the raw model traffic (`response_item`, where the
     * person's "message" also carries the environment, the permissions and every injected instruction),
     * and once as the events a client shows (`event_msg`). Only the second is read: it is what the person
     * and the agent actually said, and nothing else. Newer Codex writes those as finished items with ids
     * (`item_completed`), older Codex as plain `user_message` / `agent_message` events; a file holds one
     * form or the other, so both are read.
     *
     * The id is the item's own where there is one - the same id the history replay gives the message (see
     * CodexReplay), which is what lets a hit be jumped to. The older form has no id at all, and its hits
     * are found by their time instead.
     */
    fun messageOf(conversation: String, line: String): IndexedMessage? {
        if (!line.startsWith("{") || !line.contains(EVENT)) return null
        val item = line.contains(ITEM_COMPLETED) && (line.contains(USER_ITEM) || line.contains(AGENT_ITEM))
        val legacy = line.contains(USER_EVENT) || line.contains(AGENT_EVENT)
        if (!item && !legacy) return null

        val event = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return null
        val payload = event["payload"] as? JsonObject ?: return null
        val at = event.string("timestamp")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L

        val (speaker, uuid, raw) = when (payload.string("type")) {
            "item_completed" -> {
                val message = payload["item"] as? JsonObject ?: return null
                val speaker = when (message.string("type")) {
                    "UserMessage" -> Speaker.YOU
                    "AgentMessage" -> Speaker.AGENT
                    else -> return null
                }
                Triple(speaker, message.string("id") ?: return null, textOf(message["content"]) ?: return null)
            }
            "user_message" -> Triple(Speaker.YOU, "u-$at", payload.string("message") ?: return null)
            "agent_message" -> Triple(Speaker.AGENT, "a-$at", payload.string("message") ?: return null)
            else -> return null
        }

        val text = (if (speaker == Speaker.YOU) personWords(raw) else answerWords(raw))?.trim()?.take(MAX_TEXT_CHARS) ?: return null
        if (text.isEmpty()) return null

        return IndexedMessage(conversation, uuid, at, speaker, text)
    }

    private fun textOf(content: Any?): String? = when (content) {
        is JsonPrimitive -> content.contentOrNull.takeIf { content.isString }
        is JsonArray ->
            content
                .mapNotNull { block ->
                    (block as? JsonObject)
                        ?.takeIf { it.string("type").equals("text", ignoreCase = true) }
                        ?.string("text")
                }
                .filter { it.isNotBlank() }
                .joinToString("\n")
                .takeIf { it.isNotEmpty() }
        else -> null
    }

    /**
     * What the person wrote, without what the client wrapped around it: IDE context blocks, attachments'
     * markup, a reminder. A message that is nothing but such wrapping is not a message.
     */
    internal fun personWords(raw: String): String? {
        val words = raw
            .replace(CONTEXT_BLOCK, "")
            .replace(IDE_CONTEXT, "")
            .replace(BLANK_LINES, "\n\n")
            .trim()

        return words.takeIf { it.isNotEmpty() }
    }

    private fun answerWords(raw: String): String? = raw.trim().replace(BLANK_LINES, "\n\n").takeIf { it.isNotEmpty() }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.jsonPrimitive?.contentOrNull

    private const val EVENT = "\"event_msg\""
    private const val ITEM_COMPLETED = "\"item_completed\""
    private const val USER_ITEM = "\"UserMessage\""
    private const val AGENT_ITEM = "\"AgentMessage\""
    private const val USER_EVENT = "\"user_message\""
    private const val AGENT_EVENT = "\"agent_message\""

    internal const val MAX_TEXT_CHARS = 20_000

    /** The blocks clients put around a message for the model's eyes: `<environment_context>` and its kin. */
    /**
     * The editor note that goes with a message from the panel (see IdeContextPrompt) - the IDE's words, not
     * the person's, and always the last part of a message, so everything from its heading on.
     */
    private val IDE_CONTEXT = Regex("# Context from my IDE setup:[\\s\\S]*$")

    private val CONTEXT_BLOCK = Regex("<(environment_context|user_instructions|system-reminder|ide_context)>[\\s\\S]*?</\\1>")
    private val BLANK_LINES = Regex("\\n[ \\t]*\\n(?:[ \\t]*\\n)+")
}
