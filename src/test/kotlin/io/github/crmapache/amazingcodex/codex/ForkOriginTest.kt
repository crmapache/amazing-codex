package io.github.crmapache.amazingcodex.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Where a fork ends, worked out against a thread's turns (see ForkOrigin.resolve). */
class ForkOriginTest {

    private val source = "11111111-1111-4111-8111-111111111111"
    private val older = "22222222-2222-4222-8222-222222222222"

    private fun turn(n: Int, vararg messages: String, finished: Boolean = true) =
        CodexHistory.TurnRef("00000000-0000-4000-8000-00000000000$n", messages.toList(), finished)

    private val turns = listOf(
        turn(1, "aaaaaaaa-0000-4000-8000-000000000001"),
        turn(2, "aaaaaaaa-0000-4000-8000-000000000002", "aaaaaaaa-0000-4000-8000-000000000022"),
        turn(3, "aaaaaaaa-0000-4000-8000-000000000003", finished = false),
    )

    private fun resolve(before: String?, turnOf: (String) -> String? = { null }, origins: (String) -> ForkOrigin? = { null }) =
        ForkOrigin.resolve({ if (it == source) turns else null }, origins, source, "Title", before, turnOf)

    // A turn still running at the press is not forked through: Codex refuses it, and it was not seen finished.
    @Test
    fun `a whole fork ends on the last finished turn`() {
        val resolved = resolve(null)
        assertEquals("00000000-0000-4000-8000-000000000002", resolved.origin?.at)
        assertFalse(resolved.origin!!.cut)
    }

    @Test
    fun `a fork before a message ends on the turn before the one it began`() {
        val resolved = resolve("aaaaaaaa-0000-4000-8000-000000000003")
        assertEquals("00000000-0000-4000-8000-000000000002", resolved.origin?.at)
        assertTrue(resolved.origin!!.cut)
    }

    @Test
    fun `a fork before the first message carries nothing`() {
        val resolved = resolve("aaaaaaaa-0000-4000-8000-000000000001")
        assertNull(resolved.origin?.at)
        assertTrue(resolved.origin!!.cut)
    }

    // A message this IDE sent is named by the panel's uuid, which only the conversation that sent it can place.
    @Test
    fun `a message named by the panel is placed by its conversation`() {
        val resolved = resolve("bbbbbbbb-0000-4000-8000-000000000002", turnOf = { "00000000-0000-4000-8000-000000000002" })
        assertEquals("00000000-0000-4000-8000-000000000001", resolved.origin?.at)
    }

    @Test
    fun `a message found nowhere forks the whole and says so`() {
        val resolved = resolve("cccccccc-0000-4000-8000-000000000000")
        assertTrue(resolved.missed)
        assertEquals("00000000-0000-4000-8000-000000000002", resolved.origin?.at)
    }

    // The source is itself a fork, and the message is one it inherited: the fork is a fork of that one.
    @Test
    fun `a message of the source's own source is looked for there`() {
        val olderTurns = listOf(turn(7, "dddddddd-0000-4000-8000-000000000001"), turn(8, "dddddddd-0000-4000-8000-000000000002"))
        val resolved = ForkOrigin.resolve(
            { when (it) { source -> turns; older -> olderTurns; else -> null } },
            { if (it == source) ForkOrigin(older, "Older", cut = false, at = null) else null },
            source,
            "Title",
            "dddddddd-0000-4000-8000-000000000002",
        )
        assertEquals(older, resolved.origin?.source)
        assertEquals("00000000-0000-4000-8000-000000000007", resolved.origin?.at)
    }

    @Test
    fun `a source with nothing on disk carries nothing`() {
        val resolved = ForkOrigin.resolve({ null }, { null }, source, "Title", null)
        assertNull(resolved.origin?.at)
        assertFalse(resolved.missed)
    }

    @Test
    fun `an origin travels whole through its json`() {
        val origin = ForkOrigin(source, "Title", cut = true, at = "00000000-0000-4000-8000-000000000002")
        assertEquals(origin, ForkOrigin.decode(origin.json()))
        assertTrue(ForkOrigin.isSeam(origin.seamLine()))
    }
}
