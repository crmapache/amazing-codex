package io.github.crmapache.amazingcodex.codex

import io.github.crmapache.amazingcodex.webview.ImagePreviews
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * A picture the person sent, read back out of the conversation's thread - for the preview over its chip in a message
 * whose bytes the feed no longer holds, and for the file a click on that chip opens (see CodexSessionHub.previewImage).
 * A conversation opened from the history, or brought back after a restart, keeps `[Image #2]` and nothing else; a
 * phone's message reaches the desk without its bytes, and so does one that left the queue.
 *
 * Codex keeps the bytes with the message. Each message of the person's is written into the thread's file twice: as the
 * model's input (`response_item`, the pictures as `input_image`) and as the item a client shows (`event_msg`
 * `item_completed` with a `UserMessage`, the pictures as `image` with a data URL, or `local_image` with the path they
 * were attached from) - every thread of Codex 0.160 to 0.163 looked at holds them so, and none holds the older
 * `user_message` events. The item is what is read: it carries the id `thread/turns/list` gives the message, which is the
 * name the history's feed knows the message by (see CodexReplay) - the search jumps by the same id (see TranscriptText).
 * Its pictures stand in the order they were attached - the order the chips stood in, which is how the page names one
 * (see pastedOrdinals in imagePreview.ts).
 *
 * The file is read the way Codex reads the thread (see CodexRollout): a revert's segment holds only what was said after
 * the cut, and a fork made on 0.163 nothing of what it inherited. A line is found by its raw text and only then parsed:
 * a working day's thread is tens of megabytes, and parsing every line of it to find one would cost seconds.
 */
internal object ThreadPictures {

    /** Which of the person's messages a picture came with, as the thread can find it. */
    sealed interface Message {
        /** By Codex's own id for it - a message the feed read off the history (see CodexReplay). */
        data class Item(val id: String) : Message

        /**
         * As the [ordinal]-th of the person's messages in [turn], 0 for the one that began it - a message the panel sent
         * in this run under a name of its own, which Codex never hears (see CodexSession.placeOf).
         */
        data class InTurn(val turn: String, val ordinal: Int) : Message
    }

    /** What a thread's file said about a message's picture. */
    sealed interface Read {
        /** The message is there; [picture] is null when it has no such picture, or the picture is gone. */
        class Found(val picture: ImagePreviews.Picture?) : Read

        /** No line holds the message: it is not written yet, or it is not this thread's. */
        data object Missing : Read

        /**
         * No line holds it, and the file is - at least in part - of the form an older Codex wrote, with the person's
         * messages as `user_message` events that name no message at all. Codex itself is asked then (see [ofTurns]).
         */
        data object Unnamed : Read
    }

    /** The [index]-th picture of [message], out of the thread [file] belongs to (see [Read]). */
    fun ofFile(file: File, message: Message, index: Int): Read {
        if (index < 0) return Read.Missing
        val needle = when (message) {
            is Message.Item -> "\"id\":\"${message.id}\""
            is Message.InTurn -> "\"turn_id\":\"${message.turn}\""
        }

        return runCatching {
            CodexRollout.useLines(file) { lines ->
                var older = false
                var passed = 0
                // The model's copy of the person's latest message, while it has pictures: a picture attached by its
                // path is there as bytes, and the path is often a temporary file long gone.
                var modelCopy: String? = null

                for (line in lines) {
                    if (!line.startsWith("{")) continue
                    val head = line.take(HEAD)
                    if (PERSON_SAID.containsMatchIn(head)) {
                        modelCopy = line.takeIf { it.contains(INPUT_IMAGE) }
                        continue
                    }
                    if (!older && head.contains(OLDER_FORM)) older = true
                    if (!head.contains(ITEM_COMPLETED) || !line.contains(USER_ITEM) || !line.contains(needle)) continue

                    val item = itemOf(line, message) ?: continue
                    if (message is Message.InTurn && passed++ < message.ordinal) continue
                    return@useLines Read.Found(pictureIn(item, index, modelCopy))
                }

                if (older) Read.Unnamed else Read.Missing
            }
        }.getOrDefault(Read.Missing)
    }

    /**
     * The person's message in one line of a thread's file - an `item_completed` of a `UserMessage` that is [message]
     * (the raw text that found the line may stand anywhere in it, a remark that quotes an id among them).
     */
    internal fun itemOf(line: String, message: Message): JsonObject? = runCatching {
        val payload = Json.parseToJsonElement(line).jsonObject["payload"] as? JsonObject ?: return null
        if (AppServer.text(payload["type"]) != "item_completed") return null
        val item = payload["item"] as? JsonObject ?: return null
        if (AppServer.text(item["type"]) != "UserMessage") return null

        val holds = when (message) {
            is Message.Item -> AppServer.text(item["id"]) == message.id
            is Message.InTurn -> AppServer.text(payload["turn_id"]) == message.turn
        }
        item.takeIf { holds }
    }.getOrNull()

    /**
     * The [index]-th picture of a person's message [item] - as the thread's file writes it (`image` with `image_url`,
     * `local_image`) or as `thread/turns/list` gives it (`image` with `url`, `localImage`). A picture Codex was handed
     * as a web address is none of ours to fetch.
     *
     * A picture attached by its path whose file is gone is read out of the model's copy of the message ([modelCopy],
     * the line written just before the item), which holds every picture as bytes in the same order - when it holds as
     * many as the item has: a copy of another message's would be another picture.
     */
    internal fun pictureIn(item: JsonObject, index: Int, modelCopy: String? = null): ImagePreviews.Picture? {
        val pictures = (item["content"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.filter { AppServer.text(it["type"]) in PICTURES }
        val picture = pictures.getOrNull(index) ?: return null

        if (AppServer.text(picture["type"]) == "image") {
            return ImagePreviews.ofDataUrl(AppServer.text(picture["image_url"]).ifEmpty { AppServer.text(picture["url"]) })
        }
        return localPicture(AppServer.text(picture["path"])) ?: modelCopy?.let { copiedPicture(it, index, pictures.size) }
    }

    /**
     * A picture attached by its path, read off the disk while it is still there. Only a path Codex was handed as one,
     * on this machine: a network share is a host nobody chose to reach (see OpenInEditor.written).
     */
    private fun localPicture(path: String): ImagePreviews.Picture? {
        if (path.isBlank() || path.startsWith("//") || path.startsWith("\\\\")) return null
        val file = File(path)
        return if (file.isAbsolute) ImagePreviews.ofFile(file) else null
    }

    /** The [index]-th of the [count] pictures in the model's copy of a message - see [pictureIn]. */
    private fun copiedPicture(line: String, index: Int, count: Int): ImagePreviews.Picture? = runCatching {
        val payload = Json.parseToJsonElement(line).jsonObject["payload"] as? JsonObject ?: return null
        if (AppServer.text(payload["role"]) != "user") return null
        val pictures = (payload["content"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { AppServer.text(it["type"]) == "input_image" }
        if (pictures.size != count) return null
        ImagePreviews.ofDataUrl(AppServer.text(pictures.getOrNull(index)?.get("image_url")))
    }.getOrNull()

    /**
     * The [index]-th picture of [message], asked of Codex itself - for a thread whose file names none of its messages
     * (see [Read.Unnamed]). [ask] is the shared catalog process (see CodexCatalog); the turns are walked from the
     * newest, a page at a time, until the message is in one.
     */
    fun ofTurns(thread: String, message: Message, index: Int, ask: (String, JsonObject) -> JsonElement?): ImagePreviews.Picture? {
        if (index < 0) return null
        var cursor: String? = null

        repeat(MAX_PAGES) {
            val result = runCatching {
                ask(
                    "thread/turns/list",
                    buildJsonObject {
                        put("threadId", thread)
                        put("limit", TURNS_PER_PAGE)
                        put("sortDirection", "desc")
                        put("itemsView", "full")
                        cursor?.let { put("cursor", it) }
                    },
                ) as? JsonObject
            }.getOrNull() ?: return null

            for (turn in (result["data"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }) {
                val said = (turn["items"] as? JsonArray).orEmpty()
                    .mapNotNull { it as? JsonObject }
                    .filter { AppServer.text(it["type"]) == "userMessage" }
                val item = when (message) {
                    is Message.Item -> said.firstOrNull { AppServer.text(it["id"]) == message.id }
                    is Message.InTurn ->
                        if (AppServer.text(turn["id"]) == message.turn) said.getOrNull(message.ordinal) ?: return null else null
                } ?: continue
                return pictureIn(item, index)
            }

            cursor = AppServer.text(result["nextCursor"]).ifEmpty { null } ?: return null
        }
        return null
    }

    private val PICTURES = setOf("image", "local_image", "localImage")

    /** The head of the model's copy of a person's message: `response_item` of a `message`, `role` `user`. */
    private val PERSON_SAID = Regex(""""type":"response_item","payload":\{"type":"message"(?:,"id":"[^"]*")?,"role":"user"""")
    private const val HEAD = 400

    /** Where an event says what it is - the head of the line, so words that quote one are not taken for it. */
    private const val ITEM_COMPLETED = "\"payload\":{\"type\":\"item_completed\""
    private const val USER_ITEM = "\"UserMessage\""
    private const val INPUT_IMAGE = "\"input_image\""
    private const val OLDER_FORM = "\"payload\":{\"type\":\"user_message\""

    private const val TURNS_PER_PAGE = 10
    private const val MAX_PAGES = 50
}
