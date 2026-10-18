package io.github.crmapache.amazingcodex.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the agent is told about the files the person put back from the panel (see PutBack). */
class PutBackTest {

    @Test
    fun `the files are named from the project, grouped by what became of them`() {
        val notes = PutBack()
        notes.note("/p/src/a.kt", PutBack.Way.REVERTED)
        notes.note("/p/src/b.kt", PutBack.Way.RESTORED)
        notes.note("/elsewhere/c.kt", PutBack.Way.REVERTED)
        notes.note("/p/d.kt", PutBack.Way.REWOUND)

        val words = notes.take("/p")!!
        assertTrue(words.startsWith(PutBack.INTRODUCTION))
        val lines = words.lines()
        assertTrue(lines.indexOf("- src/a.kt") < lines.indexOf("- /elsewhere/c.kt"))
        assertTrue(lines.indexOf("- /elsewhere/c.kt") < lines.indexOf("- src/b.kt"))
        assertTrue(lines.indexOf("- src/b.kt") < lines.indexOf("- d.kt"))
    }

    @Test
    fun `a moved file says both places`() {
        val notes = PutBack()
        notes.note("/p/new/b.kt", PutBack.Way.REVERTED, origin = "/p/old/a.kt")
        assertTrue(notes.take("/p")!!.contains("- old/a.kt (moved back from new/b.kt)"))

        notes.note("/p/new/b.kt", PutBack.Way.RESTORED, origin = "/p/old/a.kt")
        assertTrue(notes.take("/p")!!.contains("- new/b.kt (moved again from old/a.kt)"))
    }

    @Test
    fun `what is told once is not told again, and a revert brought back before anyone heard is nothing`() {
        val notes = PutBack()
        notes.note("/p/a.kt", PutBack.Way.REVERTED)
        notes.note("/p/a.kt", PutBack.Way.RESTORED)
        assertNull(notes.take("/p"))

        notes.note("/p/a.kt", PutBack.Way.REVERTED)
        notes.take("/p")
        assertNull(notes.take("/p"))
        // Told as reverted, then brought back: that is news.
        notes.note("/p/a.kt", PutBack.Way.RESTORED)
        assertTrue(notes.take("/p")!!.contains("- a.kt"))
    }

    @Test
    fun `a conversation begun afresh is told nothing of the old one's files`() {
        val notes = PutBack()
        notes.note("/p/a.kt", PutBack.Way.REVERTED)
        notes.forget()
        assertNull(notes.take("/p"))
        assertEquals("a.kt", PutBack.relative("/p/a.kt", "/p/"))
    }

    @Test
    fun `a turn goes at once when no press is on its way`() {
        var went = 0
        assertTrue(PutBack().afterPresses { went++ })
        assertEquals(0, went)
    }

    @Test
    fun `a turn waits for the presses on their way and goes once when the last is answered`() {
        val putBack = PutBack()
        var went = 0
        putBack.pressed()
        putBack.pressed()

        assertEquals(false, putBack.afterPresses { went++ })
        putBack.answered()
        assertEquals(0, went, "one press is still on its way")
        putBack.answered()
        assertEquals(1, went)
        putBack.answered()
        assertEquals(1, went, "a turn goes once")
    }

    @Test
    fun `a press that never comes back holds the turn only for a while`() {
        val putBack = PutBack()
        val went = java.util.concurrent.CountDownLatch(1)
        var times = 0
        putBack.pressed()

        assertEquals(false, putBack.afterPresses(patienceMs = 50) { times++; went.countDown() })
        assertTrue(went.await(5, java.util.concurrent.TimeUnit.SECONDS))
        putBack.answered()
        assertEquals(1, times, "the late answer does not start it again")
    }
}
