package io.github.crmapache.amazingcodex.search

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What a line of a Codex conversation file gives the search: the finished messages of either side, in
 * the newer form (`item_completed` with ids) and the older one (`user_message` / `agent_message`), and
 * nothing of the model traffic written beside them.
 */
class TranscriptTextTest {

    private val at = "2026-08-14T09:12:00.000Z"
    private val millis = Instant.parse(at).toEpochMilli()

    private fun item(type: String, id: String, content: String) =
        """{"timestamp":"$at","type":"event_msg","payload":{"type":"item_completed","thread_id":"t","turn_id":"r","item":{"type":"$type","id":"$id","content":$content}}}"""

    private fun person(text: String, id: String = "u1") = item("UserMessage", id, """[{"type":"text","text":"$text","text_elements":[]}]""")

    private fun agent(content: String, id: String = "msg_1") = item("AgentMessage", id, content)

    @Test
    fun `a person's message is a message`() {
        val message = TranscriptText.messageOf("c1", person("почему баланс не показывается?"))

        assertNotNull(message)
        assertEquals(Speaker.YOU, message.speaker)
        assertEquals("почему баланс не показывается?", message.text)
        assertEquals("u1", message.uuid)
        assertEquals(millis, message.at)
        assertEquals("c1", message.conversation)
    }

    @Test
    fun `an answer keeps its text blocks and drops the rest`() {
        val line = agent("""[{"type":"Text","text":"Looked at the css."},{"type":"Image","url":"x"},{"type":"Text","text":"  "},{"type":"Text","text":"Done."}]""")

        val message = TranscriptText.messageOf("c1", line)

        assertNotNull(message)
        assertEquals(Speaker.AGENT, message.speaker)
        assertEquals("msg_1", message.uuid)
        assertEquals("Looked at the css.\nDone.", message.text)
    }

    // The answering side keeps the wire name the panel's protocol has always had.
    @Test
    fun `the two voices keep their wire names`() {
        assertEquals("you", Speaker.YOU.wire)
        assertEquals("claude", Speaker.AGENT.wire)
    }

    /**
     * Older Codex writes plain events with no id at all; their messages are named by their time, so the
     * two sides of one moment still differ.
     */
    @Test
    fun `the older events are read too, named by their time`() {
        val asked = TranscriptText.messageOf("c1", """{"timestamp":"$at","type":"event_msg","payload":{"type":"user_message","message":"old words","images":[]}}""")
        val answered = TranscriptText.messageOf("c1", """{"timestamp":"$at","type":"event_msg","payload":{"type":"agent_message","message":"old answer"}}""")

        assertEquals("u-$millis", asked?.uuid)
        assertEquals(Speaker.YOU, asked?.speaker)
        assertEquals("old words", asked?.text)
        assertEquals("a-$millis", answered?.uuid)
        assertEquals(Speaker.AGENT, answered?.speaker)
    }

    @Test
    fun `the machinery between the messages is not a message`() {
        // The same words as model traffic, which also carries every injected instruction.
        assertNull(TranscriptText.messageOf("c1", """{"timestamp":"$at","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"hello"}]}}"""))
        assertNull(TranscriptText.messageOf("c1", item("Reasoning", "rs_1", "[]")))
        assertNull(TranscriptText.messageOf("c1", """{"timestamp":"$at","type":"event_msg","payload":{"type":"item_completed","item":{"type":"FileChange","id":"f1","changes":{}}}}"""))
        assertNull(TranscriptText.messageOf("c1", """{"timestamp":"$at","type":"event_msg","payload":{"type":"token_count","info":null}}"""))
        assertNull(TranscriptText.messageOf("c1", """{"timestamp":"$at","type":"session_meta","payload":{"id":"t","cwd":"/p"}}"""))
        assertNull(TranscriptText.messageOf("c1", "not json"))
        assertNull(TranscriptText.messageOf("c1", """  {"type":"event_msg"}"""))
    }

    @Test
    fun `a message that cannot be jumped to or says nothing is left out`() {
        // No id: a hit on it could not be opened.
        assertNull(TranscriptText.messageOf("c1", """{"timestamp":"$at","type":"event_msg","payload":{"type":"item_completed","item":{"type":"UserMessage","content":[{"type":"text","text":"hi"}]}}}"""))
        assertNull(TranscriptText.messageOf("c1", person("   ")))
        assertNull(TranscriptText.messageOf("c1", agent("[]")))
    }

    @Test
    fun `a line without a time is still a message, at no time`() {
        val line = """{"type":"event_msg","payload":{"type":"item_completed","item":{"type":"UserMessage","id":"u1","content":[{"type":"text","text":"hello"}]}}}"""

        assertEquals(0L, TranscriptText.messageOf("c1", line)?.at)
    }

    @Test
    fun `what a client wrapped around the person's words goes, the words stay`() {
        val text = "<environment_context>\\n  <cwd>/p</cwd>\\n</environment_context>look at this"
        assertEquals("look at this", TranscriptText.messageOf("c1", person(text))?.text)
        assertEquals("look at this", TranscriptText.messageOf("c1", person("<system-reminder>be careful</system-reminder>look at this"))?.text)
        assertNull(TranscriptText.messageOf("c1", person("<user_instructions>only this</user_instructions>")))
    }

    @Test
    fun `runs of blank lines are folded to one`() {
        assertEquals("one\n\ntwo", TranscriptText.messageOf("c1", person("one\\n\\n  \\n\\ntwo"))?.text)
        assertEquals("one\n\ntwo", TranscriptText.messageOf("c1", agent("""[{"type":"Text","text":"one\n\n\n\ntwo"}]"""))?.text)
    }

    @Test
    fun `a very long message is cut`() {
        val long = "x".repeat(TranscriptText.MAX_TEXT_CHARS + 500)

        assertEquals(TranscriptText.MAX_TEXT_CHARS, TranscriptText.messageOf("c1", person(long))?.text?.length)
    }
}
