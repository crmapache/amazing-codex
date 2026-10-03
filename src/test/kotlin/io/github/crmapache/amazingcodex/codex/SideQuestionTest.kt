package io.github.crmapache.amazingcodex.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A question beside the conversation, asked the way Codex's own `/side` asks it: the boundary and the
 * instructions are Codex's words, the earlier exchanges follow the boundary, and the fork goes without
 * the tools nobody could approve from a card.
 */
class SideQuestionTest {

    @Test
    fun `the boundary comes first and the earlier exchanges follow it as a conversation`() {
        val items = SideQuestion.items(
            listOf(
                SideQuestion.Exchange("What is retried?", "The socket, three times."),
                SideQuestion.Exchange("And the delay?", "Two seconds."),
            ),
        )

        assertEquals(5, items.size)
        val roles = items.map { (it as JsonObject)["role"]!!.jsonPrimitive.content }
        assertEquals(listOf("developer", "user", "assistant", "user", "assistant"), roles)

        val boundary = textOf(items[0])
        assertTrue(boundary.startsWith("Side conversation boundary."), boundary)
        assertEquals("output_text", ((items[2] as JsonObject)["content"] as JsonArray)[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("Two seconds.", textOf(items[4]))
    }

    @Test
    fun `with nothing asked before only the boundary goes in`() {
        val items = SideQuestion.items(emptyList())

        assertEquals(1, items.size)
        assertTrue(textOf(items[0]).startsWith("Side conversation boundary."))
    }

    // For a Codex that would not take the items: the same words, ahead of the question.
    @Test
    fun `inlined, the question is the last thing said`() {
        val text = SideQuestion.inlined("Why two?", listOf(SideQuestion.Exchange("What is retried?", "The socket.")))

        assertTrue(text.startsWith("Side conversation boundary."))
        assertTrue(text.contains("Earlier side question: What is retried?\nYour answer then: The socket."))
        assertTrue(text.endsWith("Why two?"))
    }

    @Test
    fun `the fork goes without sub-agents, without the configured MCP servers by name and without Codex's own`() {
        val config = SideQuestion.configOverrides(listOf("notion", "playwright-1"))

        assertEquals(JsonPrimitive(false), config["features.multi_agent"])
        assertEquals(JsonPrimitive(false), config["mcp_servers.notion.enabled"])
        assertEquals(JsonPrimitive(false), config["mcp_servers.playwright-1.enabled"])
        // Codex's apps and a plugin's servers have no table to switch off; their features go instead.
        assertEquals(JsonPrimitive(false), config["features.apps"])
        assertEquals(JsonPrimitive(false), config["features.plugins"])
        assertFalse(config.keys.any { it.startsWith("mcp_servers.codex_apps") })
    }

    // A name that would break the dotted key is left alone rather than written into a path it escapes.
    @Test
    fun `a server name that cannot be a key is not switched off by a broken key`() {
        val config = SideQuestion.configOverrides(listOf("odd.name", "", "quoted\"name"))

        assertEquals(setOf("features.multi_agent", "features.apps", "features.plugins"), config.keys)
    }

    @Test
    fun `the instructions are Codex's own, with the card's line on top`() {
        assertTrue(SideQuestion.INSTRUCTIONS.startsWith("You are in a side conversation, not the main thread."))
        assertTrue(SideQuestion.INSTRUCTIONS.contains("Sub-agents are off-limits in this side conversation."))
        assertTrue(SideQuestion.INSTRUCTIONS.contains("small card beside the main conversation"))
    }

    @Test
    fun `the history a client sends is cut to the newest ten and to safe lengths`() {
        val sent = Json.parseToJsonElement(
            buildString {
                append("[")
                append((1..12).joinToString(",") { """{"question":"q$it","response":"r$it"}""" })
                append(""",{"question":"","response":"empty question"}""")
                append(""",{"question":"${"x".repeat(25_000)}","response":"long"}""")
                append("]")
            },
        )

        val history = SideQuestion.historyOf(sent)

        assertEquals(SideQuestion.HISTORY_KEPT, history.size)
        assertEquals(SideQuestion.TEXT_LIMIT, history.last().question.length)
        assertFalse(history.any { it.question.isEmpty() })
    }

    @Test
    fun `answers are told in the panel's words`() {
        val answered = Json.parseToJsonElement(SideQuestion.answerJson("s", "q1", SideQuestion.Answer.Answered("Two seconds.", "moved"))).jsonObject
        assertEquals("answered", answered["outcome"]!!.jsonPrimitive.content)
        assertEquals("moved", answered["notice"]!!.jsonPrimitive.content)

        val failed = Json.parseToJsonElement(
            SideQuestion.answerJson("s", "q1", SideQuestion.Answer.Failed(SideQuestion.Reason.TIMEOUT, "No answer in time.")),
        ).jsonObject
        assertEquals("failed", failed["outcome"]!!.jsonPrimitive.content)
        assertEquals("timeout", failed["reason"]!!.jsonPrimitive.content)

        val progress = Json.parseToJsonElement(
            SideQuestion.progressJson("s", "q1", SideQuestion.Progress("q1", SideQuestion.API_RETRY, 2, null, null, 503)),
        ).jsonObject
        assertEquals("api_retry", progress["status"]!!.jsonPrimitive.content)
        assertEquals(2, progress["attempt"]!!.jsonPrimitive.content.toInt())
        assertEquals(503, progress["errorStatus"]!!.jsonPrimitive.content.toInt())
    }

    private fun textOf(item: Any): String =
        (((item as JsonObject)["content"] as JsonArray)[0] as JsonObject)["text"]!!.jsonPrimitive.content
}
