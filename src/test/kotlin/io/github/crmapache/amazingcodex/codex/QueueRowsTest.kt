package io.github.crmapache.amazingcodex.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A queued message's pieces, cut down for its row: no bytes, short words, and every chip in its place. */
class QueueRowsTest {

    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `a picture's bytes stay with the queue and the chip keeps everything else`() {
        val echo = json(
            """{"tokens":[{"kind":"text","value":"look "},""" +
                """{"kind":"chip","chip":{"kind":"img","value":"Image #1","data":"data:image/png;base64,AAAA","path":"/p/a.png"}}]}""",
        )

        assertEquals(
            json(
                """{"t":[{"kind":"text","value":"look "},""" +
                    """{"kind":"chip","chip":{"kind":"img","value":"Image #1","path":"/p/a.png"}}]}""",
            )["t"],
            QueueRows.tokens(echo),
        )
    }

    /* Two lines of a row show a few hundred characters; the rest of a long message is not sent to every client. */
    @Test
    fun `the words are cut to a row and every chip after them stays`() {
        val long = "w".repeat(QueueRows.ROW_CHARS + 50)
        val echo = json(
            """{"tokens":[{"kind":"text","value":"$long"},{"kind":"chip","chip":{"kind":"img","value":"Image #1"}},""" +
                """{"kind":"text","value":"after"},{"kind":"chip","chip":{"kind":"file","value":"src/a.ts"}}]}""",
        )

        val row = QueueRows.tokens(echo)!!.map { it.jsonObject }

        // Cut where the budget ends, and said to be: a row that looks whole on a wide panel is not.
        assertEquals("w".repeat(QueueRows.ROW_CHARS) + "…", row[0]["value"]!!.jsonPrimitive.content)
        // No chip goes - a picture is named by its place among them - and none lands against the next with no
        // words between: past the budget a piece of words keeps its first letters and the mark.
        assertEquals(listOf("text", "chip", "text", "chip"), row.map { it["kind"]!!.jsonPrimitive.content })
        assertEquals("af…", row[2]["value"]!!.jsonPrimitive.content)
        assertEquals("src/a.ts", (row[3]["chip"] as JsonObject)["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `words past the budget keep the spaces that stood between the chips`() {
        val long = "w".repeat(QueueRows.ROW_CHARS + 50)
        val echo = json(
            """{"tokens":[{"kind":"text","value":"$long"},{"kind":"chip","chip":{"kind":"img","value":"Image #1"}},""" +
                """{"kind":"text","value":" and compare with "},{"kind":"chip","chip":{"kind":"file","value":"src/a.ts"}},""" +
                """{"kind":"text","value":"  "}]}""",
        )

        val row = QueueRows.tokens(echo)!!.map { it.jsonObject }

        assertEquals(" an… ", row[2]["value"]!!.jsonPrimitive.content)
        assertEquals(" ", row[4]["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a folded paste keeps only what its caption is made of`() {
        val paste = "line\n".repeat(200)
        val echo = json("""{"tokens":[{"kind":"chip","chip":{"kind":"paste","value":"Pasted","text":${JsonPrimitive(paste)}}}]}""")

        val chip = QueueRows.tokens(echo)!!.jsonArray[0].jsonObject["chip"]!!.jsonObject

        assertEquals(paste.take(QueueRows.CHIP_TEXT_CHARS), chip["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a message without pieces has no row of pieces`() {
        assertNull(QueueRows.tokens(null))
        assertNull(QueueRows.tokens(json("""{"quotes":[]}""")))
    }

    /* A quote is what "fix this" after it is about - and what tells two such messages in the queue apart. */
    @Test
    fun `the quotes reach the row, each cut to what a row shows`() {
        val long = "q".repeat(QueueRows.CHIP_TEXT_CHARS + 50)
        val echo = json("""{"tokens":[{"kind":"text","value":"fix this"}],"quotes":["the first paragraph","$long",7]}""")

        val quotes = QueueRows.quotes(echo)!!.map { it.jsonPrimitive.content }

        assertEquals(listOf("the first paragraph", long.take(QueueRows.CHIP_TEXT_CHARS)), quotes)
        assertNull(QueueRows.quotes(json("""{"tokens":[]}""")))
        assertNull(QueueRows.quotes(json("""{"quotes":[]}""")))
    }
}
