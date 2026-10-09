package io.github.crmapache.amazingcodex.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject

/**
 * A fork not born yet reads its source's history up to the turn it ends on, page by page - walked here over
 * `thread/turns/list` answers of the test's own, the way Codex hands them out: newest first, ten at a time.
 */
class CodexHistoryForkPageTest {

    private val source = "11111111-1111-4111-8111-111111111111"

    private fun turnId(n: Int) = "00000000-0000-4000-8000-%012d".format(n)

    /** [count] turns, the newest last, each an answer of [size] characters. */
    private fun thread(count: Int, size: (Int) -> Int = { 20 }): (String, JsonObject) -> JsonElement? = { _, params ->
        val from = (params["cursor"] as? JsonPrimitive)?.content?.toInt() ?: count
        val upTo = maxOf(0, from - 10)
        buildJsonObject {
            putJsonArray("data") {
                for (n in from downTo upTo + 1) {
                    addJsonObject {
                        put("id", turnId(n))
                        put("status", "completed")
                        putJsonArray("items") {
                            addJsonObject {
                                put("type", "agentMessage")
                                put("id", "answer-$n")
                                put("text", "answer $n " + "x".repeat(size(n)))
                            }
                        }
                    }
                }
            }
            if (upTo > 0) put("nextCursor", upTo.toString())
        }
    }

    private fun page(ask: (String, JsonObject) -> JsonElement?, origin: ForkOrigin, before: String?) =
        CodexHistory.page(source, before, local = true, unborn = origin, ask = ask, asks = emptyMap(), book = { null }, model = { "" })

    private fun answers(page: CodexHistory.Page): List<Int> =
        page.lines.mapNotNull { Regex("\"answer (\\d+) ").find(it)?.groupValues?.get(1)?.toInt() }.distinct()

    @Test
    fun `a fork's opening is its source up to the fork's last turn, with the seam under it`() {
        val origin = ForkOrigin(source, "Source", cut = true, at = turnId(40))
        val opening = page(thread(45), origin, before = null)

        assertEquals((40 downTo 11).toList().reversed(), answers(opening))
        assertTrue(ForkOrigin.isSeam(opening.lines.last()))
        assertEquals(turnId(11), opening.cursor)
    }

    // The boundary sits below the fork's last turn: the page above it is the source's older turns, not an
    // empty page closing the history.
    @Test
    fun `the page above a fork's opening is the older part of its source`() {
        val ask = thread(45)
        val origin = ForkOrigin(source, "Source", cut = true, at = turnId(40))
        val opening = page(ask, origin, before = null)

        val earlier = page(ask, origin, before = opening.cursor)

        assertEquals((1..10).toList(), answers(earlier))
        assertNull(earlier.cursor)
        assertTrue(earlier.lines.none(ForkOrigin::isSeam))
    }

    // A last turn heavier than the whole page budget is still the page, and the seam stands under it.
    @Test
    fun `a heavy last turn is not crowded out by the seam`() {
        val origin = ForkOrigin(source, "Source", cut = true, at = turnId(5))
        val opening = page(thread(6) { n -> if (n == 5) CodexHistory.DESK_PAGE_CHARS + 10 else 20 }, origin, before = null)

        assertEquals(listOf(5), answers(opening))
        assertTrue(ForkOrigin.isSeam(opening.lines.last()))
        assertEquals(turnId(5), opening.cursor)
    }

    @Test
    fun `a fork that carries nothing opens on the seam alone`() {
        val opening = page(thread(3), ForkOrigin(source, "Source", cut = true, at = null), before = null)
        assertEquals(1, opening.lines.size)
        assertTrue(ForkOrigin.isSeam(opening.lines.single()))
        assertNull(opening.cursor)
    }
}
