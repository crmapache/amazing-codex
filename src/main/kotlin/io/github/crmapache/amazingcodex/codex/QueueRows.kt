package io.github.crmapache.amazingcodex.codex

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A queued message's pieces, cut down for the row that draws it (see QueueLine in the webview's Queue.tsx).
 *
 * The row draws the message as the chips it was typed in - a file by its name, a picture as a chip that shows
 * the picture under the pointer - rather than as the text the agent will read, where they are a long path and
 * `[Image #3]`. The pieces are the message's echo (see SessionQueue.Entry.echo), and the list goes out to
 * every client on every change of the queue, a phone across a 256 KB relay frame among them. So:
 *
 * - no picture's bytes - the row asks for them by the message when the pointer is on one, out of
 *   [SessionQueue.Entry.images];
 * - the words cut to what two lines of a row can show, and a chip's own text (a quote, a folded paste) to
 *   what its caption is made of;
 * - the quotes above the field the message was written under (see [quotes]) cut the same way.
 *
 * Only strings are cut and only bytes dropped; which piece is a chip, and what a chip means, is the page's
 * to know. Nothing is reordered, and no piece is left out: a picture is named by its place among the
 * message's pictures, and that place has to stay what it was - and the words between two chips, cut to their
 * first letters, are what keeps the chips from reading as one.
 */
internal object QueueRows {

    /** All the words of a row - two lines of a row are well under this at any width of the panel. */
    internal const val ROW_CHARS = 400

    /** A chip's own text: its caption is made of the first words. */
    internal const val CHIP_TEXT_CHARS = 200

    fun tokens(echo: JsonObject?): JsonArray? {
        val tokens = echo?.get("tokens") as? JsonArray ?: return null
        var left = ROW_CHARS

        return JsonArray(
            tokens.mapNotNull { token ->
                val piece = token as? JsonObject ?: return@mapNotNull token
                val chip = piece["chip"] as? JsonObject
                if (chip != null) return@mapNotNull JsonObject(piece + ("chip" to rowChip(chip)))

                val value = (piece["value"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: return@mapNotNull piece

                val kept = when {
                    value.length <= left -> value
                    // Where the budget ends, and said to be: two lines of a wide panel hold more than this, and a
                    // row cut with no mark reads as the whole message.
                    left > 0 -> value.take(left).trimEnd() + CUT
                    else -> glimpse(value)
                }
                left = (left - value.length).coerceAtLeast(0)
                JsonObject(piece + ("value" to JsonPrimitive(kept)))
            },
        )
    }

    /** What a row says where its words were cut. */
    private const val CUT = "…"

    /** The first letters a piece of words past the budget keeps, so the mark has something to stand on. */
    private const val GLIMPSE_CHARS = 2

    /**
     * A piece of words past the budget, down to its first letters and the mark - not to nothing. Dropped whole,
     * it left the chips on either side of it against each other with no words between, a picture and a file
     * reading as one thing; the space it began or ended with stays for the same reason.
     */
    private fun glimpse(value: String): String {
        val words = value.trim()
        val before = if (value.first().isWhitespace()) " " else ""
        if (words.isEmpty()) return before
        val after = if (value.last().isWhitespace()) " " else ""
        return before + words.take(GLIMPSE_CHARS).trimEnd() + CUT + after
    }

    /**
     * The message's quotes, each cut to what a row shows of one - or null when it has none. A quote is what the
     * words after it are about ("fix this"), and the one thing that tells two such messages in the queue apart;
     * the text the row used to draw began with them.
     */
    fun quotes(echo: JsonObject?): JsonArray? {
        val quotes = (echo?.get("quotes") as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { quote -> quote.isString }?.let(::cut) }
            .orEmpty()
        return quotes.takeIf { it.isNotEmpty() }?.let(::JsonArray)
    }

    private fun rowChip(chip: JsonObject): JsonObject = JsonObject(
        chip.filterKeys { it != "data" }.mapValues { (key, value) -> if (key == "text") cut(value) else value },
    )

    private fun cut(value: JsonElement): JsonElement {
        val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return value
        return if (text.length <= CHIP_TEXT_CHARS) value else JsonPrimitive(text.take(CHIP_TEXT_CHARS))
    }
}
