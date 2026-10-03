package io.github.crmapache.amazingcodex.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Two rules that keep one account's percentages off another account's row: how often the server may be
 * asked, and what a borrowed answer looks like when it arrives anyway.
 *
 * Both break silently - a wrong percentage looks exactly like a right one - so they are held here
 * rather than by care while reading the diff.
 */
class UsageProbesTest {

    private val hour = 60 * 60 * 1000L

    private fun week(percent: Int, resets: String) =
        CodexUsage.Snapshot(session = null, week = CodexUsage.Window(percent, resets), contextWindow = null)

    @Test
    fun `an account is asked once and then told how long is left`() {
        val probes = UsageProbes()
        val now = 1_700_000_000_000L

        assertEquals(0, probes.claim("work", 15_000, now))
        assertEquals(15_000 - 4_000, probes.claim("work", 15_000, now + 4_000))
        assertEquals(0, probes.claim("work", 15_000, now + 15_000))
    }

    // The pace is per account because the server counts per account: one account being asked must not
    // stop the row below it from getting a figure at all.
    @Test
    fun `the pace is one account's own`() {
        val probes = UsageProbes()
        val now = 1_700_000_000_000L

        assertEquals(0, probes.claim("work", 15_000, now))
        assertEquals(0, probes.claim("home", 15_000, now))
    }

    // Every caller names its own floor - a question into a running process is cheap, one that raises a
    // process is not - and they share one register, because the server does.
    @Test
    fun `the floor is the caller's, the register is shared`() {
        val probes = UsageProbes()
        val now = 1_700_000_000_000L

        assertEquals(0, probes.claim("work", 15_000, now))
        assertEquals(60_000 - 20_000, probes.claim("work", 60_000, now + 20_000))
    }

    // The answer to a postponed question reaches every open project, so one of them waiting is enough: a
    // place per project queued one question per project, each going the moment the previous one freed the
    // pace - the burst that makes the endpoint refuse.
    @Test
    fun `one question per account waits out the pace, however many ask`() {
        val probes = UsageProbes()

        assertTrue(probes.hold("work"))
        assertFalse(probes.hold("work"))
        assertTrue(probes.hold("home"))

        probes.release("work")
        assertTrue(probes.hold("work"))
    }

}
