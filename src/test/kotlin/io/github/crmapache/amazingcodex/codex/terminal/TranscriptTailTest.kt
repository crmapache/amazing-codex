package io.github.crmapache.amazingcodex.codex.terminal

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a terminal added to a Codex thread, played into the feed on the way back (see TranscriptTail): the turns after
 * the newest one holding an item the feed knows, walked from the newest down through `thread/turns/list`.
 */
class TranscriptTailTest {

    private fun turn(id: String, said: String, answered: String) = buildJsonObject {
        put("id", id)
        put("status", "completed")
        putJsonArray("items") {
            addJsonObject {
                put("type", "userMessage")
                put("id", "$id-u")
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", said)
                    }
                }
            }
            addJsonObject {
                put("type", "agentMessage")
                put("id", "$id-a")
                put("text", answered)
            }
        }
    }

    /** Codex answering newest first, two turns a request, with a cursor between the requests. */
    private fun codex(vararg oldestFirst: JsonObject, asked: MutableList<JsonObject> = mutableListOf()): (String, JsonObject) -> JsonElement? {
        val newest = oldestFirst.reversed()
        return { method, params ->
            assertEquals("thread/turns/list", method)
            asked += params
            val from = (params["cursor"] as? JsonPrimitive)?.content?.toInt() ?: 0
            val page = newest.drop(from).take(2)
            buildJsonObject {
                put("data", JsonArray(page))
                if (from + 2 < newest.size) put("nextCursor", "${from + 2}")
            }
        }
    }

    private val panelPart = arrayOf(turn("t1", "first", "answer"), turn("t2", "second", "done"))

    @Test
    fun `the turns after the one the feed holds are played, oldest first`() {
        val terminal = arrayOf(turn("t3", "from the terminal", "in the terminal"), turn("t4", "again", "and again"))

        val tail = TranscriptTail.after("thread", known = listOf("t2-a", "t2-u", "t1-a"), codex(*panelPart, *terminal))

        val lines = assertIs<TranscriptTail.Tail.Lines>(tail).lines
        val text = lines.joinToString("\n")
        assertTrue(text.indexOf("from the terminal") in 0 until text.indexOf("again"))
        assertTrue("second" !in text, "a turn the feed holds is not played again")
    }

    // The feed's newest line may be one Codex keeps no item for (a line of the panel's own) - an older known one anchors
    // just as well.
    @Test
    fun `the anchor is the newest turn holding any known item`() {
        val tail = TranscriptTail.after("thread", known = listOf("panel-only", "t2-a"), codex(*panelPart, turn("t3", "later", "ok")))

        val text = assertIs<TranscriptTail.Tail.Lines>(tail).lines.joinToString("\n")
        assertTrue("later" in text)
    }

    @Test
    fun `nothing done in the terminal is nothing to play`() {
        val tail = TranscriptTail.after("thread", known = listOf("t2-a"), codex(*panelPart))

        assertEquals(emptyList(), assertIs<TranscriptTail.Tail.Lines>(tail).lines)
    }

    // The feed holds nothing of this thread - or the terminal rewound past all of it.
    @Test
    fun `no anchor draws the conversation again`() {
        assertIs<TranscriptTail.Tail.Whole>(TranscriptTail.after("thread", known = listOf("elsewhere"), codex(*panelPart)))
        assertIs<TranscriptTail.Tail.Whole>(TranscriptTail.after("thread", known = emptyList(), codex(*panelPart)))
    }

    @Test
    fun `more than a page of turns draws the conversation again`() {
        val many = (3..(TranscriptTail.MAX_TURNS + 4)).map { turn("t$it", "message $it", "answer $it") }.toTypedArray()

        assertIs<TranscriptTail.Tail.Whole>(TranscriptTail.after("thread", known = listOf("t2-a"), codex(*panelPart, *many)))
    }

    @Test
    fun `codex not answering draws the conversation again`() {
        assertIs<TranscriptTail.Tail.Whole>(TranscriptTail.after("thread", known = listOf("t2-a")) { _, _ -> null })
    }

    @Test
    fun `the uuid is read out of the journal's envelope`() {
        assertEquals("item-1", TranscriptTail.uuidOf("""{"type":"agent","sessionId":"main","event":{"type":"assistant","uuid":"item-1"}}"""))
        assertNull(TranscriptTail.uuidOf("""{"type":"queue","sessionId":"main"}"""))
        assertNull(TranscriptTail.uuidOf("not json"))
    }

}
