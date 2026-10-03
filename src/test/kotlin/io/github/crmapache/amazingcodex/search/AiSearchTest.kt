package io.github.crmapache.amazingcodex.search

import io.github.crmapache.amazingcodex.codex.CodexOneShot
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiSearchTest {

    /**
     * What comes back is the text of the model's last message. With an output schema it is the bare JSON,
     * but a model may still wrap it in a fence or a sentence, and the parsing reads past both.
     */
    @Test
    fun `the hits are read out of the model's text`() {
        val text = "Here you go:\n```json\n{\"hits\":[{\"conversationId\":\"bbbb\",\"uuid\":\"u-4\",\"reason\":\"about the balance\"}]}\n```"

        assertEquals(listOf(AiHit("bbbb", "u-4", "about the balance")), AiSearch.parse(text))
    }

    @Test
    fun `a bare JSON answer is read as it is`() {
        val hits = AiSearch.parse("""{"hits":[{"conversationId":" a ","uuid":" u ","reason":" why "}]}""")

        assertEquals(listOf(AiHit("a", "u", "why")), hits)
    }

    @Test
    fun `an empty list is an answer, prose is not`() {
        assertEquals(emptyList(), AiSearch.parse("""{"hits":[]}"""))
        assertNull(AiSearch.parse("I could not find anything."))
        assertNull(AiSearch.parse("{}"))
        assertNull(AiSearch.parse("""{"hits":"none"}"""))
        assertNull(AiSearch.parse("not json {"))
    }

    @Test
    fun `a hit without names is dropped`() {
        val hits = AiSearch.parse("""{"hits":[{"uuid":"u"},{"conversationId":"a","uuid":"u2"},"junk"]}""")

        assertEquals(listOf(AiHit("a", "u2", "")), hits)
    }

    @Test
    fun `a conversation named sessionId is still read`() {
        assertEquals(listOf(AiHit("a", "u", "")), AiSearch.parse("""{"hits":[{"sessionId":"a","uuid":"u"}]}"""))
    }

    /**
     * The steps come from the commands Codex runs, already read into a kind and a subject by
     * CodexOneShot; what this side adds is naming the conversation a read opened.
     */
    @Test
    fun `the model's steps are read out of what Codex ran`() {
        assertEquals(
            AiStep(AiStep.Kind.GREP, "Deepgram|баланс"),
            AiSearch.stepOf(CodexOneShot.Step("search", "Deepgram|баланс")),
        )
        assertEquals(AiStep(AiStep.Kind.READ, "abc-123"), AiSearch.stepOf(CodexOneShot.Step("read", "/x/corpus/abc-123.txt")))
        assertEquals(AiStep(AiStep.Kind.READ, "abc-123"), AiSearch.stepOf(CodexOneShot.Step("read", "abc-123.txt")))
        assertEquals(AiStep(AiStep.Kind.LIST, ""), AiSearch.stepOf(CodexOneShot.Step("read", "sessions.txt")))
        assertEquals(AiStep(AiStep.Kind.LIST, ""), AiSearch.stepOf(CodexOneShot.Step("list", "/x/corpus")))
        assertEquals(AiStep(AiStep.Kind.OTHER, ""), AiSearch.stepOf(CodexOneShot.Step("run", "wc -l *.txt")))

        // A search with nothing to search for says nothing about what the model is doing.
        assertNull(AiSearch.stepOf(CodexOneShot.Step("search", " ")))
    }

    @Test
    fun `a long pattern is cut to a subject`() {
        val step = AiSearch.stepOf(CodexOneShot.Step("search", "x".repeat(200)))

        assertEquals(60, step?.subject?.length)
    }

    @Test
    fun `the request travels last, between markers, with the date`() {
        val body = AiSearch.body("  where did we talk about \"balance\"?  ", LocalDate.of(2026, 9, 1))

        assertTrue(body.contains("Today is 2026-09-01."))
        assertTrue(body.endsWith("<<<REQUEST\nwhere did we talk about \"balance\"?\nREQUEST>>>\n"))
        assertTrue(body.indexOf("REQUEST>>>") > body.indexOf("sessions.txt"))
    }
}
