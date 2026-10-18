package io.github.crmapache.amazingcodex.codex

import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Say the next thing" into a tab is asked from several threads at once - the end of a turn, a quiet swap, a
 * wait's timer, the minute round - and deciding what goes and marking the tab busy are two steps. Run side by
 * side, two passes each found the tab free, and the second message went into the turn the first had just
 * started. The gate runs one pass at a time per tab and never loses a request made meanwhile.
 */
class PassGateTest {

    /** Two threads ask at once while one message is waiting: exactly one of them sends it. */
    @Test
    fun `two requests at once send one message`() {
        val gate = PassGate()
        val waiting = AtomicInteger(1)
        val busy = AtomicInteger(0)
        val sent = AtomicInteger(0)
        val start = CyclicBarrier(2)

        val pass = { _: Long ->
            // The two steps the hub takes: is the tab free, and then send - which marks it busy.
            if (busy.get() == 0 && waiting.get() > 0) {
                Thread.sleep(20)
                waiting.decrementAndGet()
                busy.set(1)
                sent.incrementAndGet()
            }
        }

        val threads = List(2) { thread { start.await(); gate.request(0, pass) } }
        threads.forEach { it.join(5_000) }

        assertEquals(1, sent.get())
    }

    /**
     * A request made while a pass is running is not dropped: the running thread makes one more pass for it.
     * Without that, a message queued at the wrong moment would wait for a status change that may never come.
     */
    @Test
    fun `a request made during a pass gets a pass of its own`() {
        val gate = PassGate()
        val passes = AtomicInteger(0)
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)

        val first = thread {
            gate.request(0) {
                if (passes.incrementAndGet() == 1) {
                    inside.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
        }

        assertTrue(inside.await(5, TimeUnit.SECONDS))
        // Returns at once: somebody is already passing.
        gate.request(0) { passes.incrementAndGet() }
        release.countDown()
        first.join(5_000)

        assertEquals(2, passes.get())
    }

    /** The latest moment anybody asked for is the one the pass looks at - a wait's timer asks for its own. */
    @Test
    fun `a pass looks at the latest moment asked for`() {
        val gate = PassGate()
        var seen = 0L

        gate.request(System.currentTimeMillis() + 60_000) { seen = it }

        assertTrue(seen >= System.currentTimeMillis() + 50_000)
    }

    /** A pass that fails does not lock the tab out of every pass after it. */
    @Test
    fun `a failed pass leaves the gate open`() {
        val gate = PassGate()

        runCatching { gate.request(0) { error("boom") } }
        var ran = false
        gate.request(0) { ran = true }

        assertTrue(ran)
    }

    /** Two passes failing in one run: the first is thrown, the second is attached to it rather than lost. */
    @Test
    fun `a second failure is kept with the first`() {
        val gate = PassGate()
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        var thrown: Throwable? = null

        val first = thread {
            thrown = runCatching {
                gate.request(0) {
                    if (inside.count > 0) {
                        inside.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        error("first")
                    }
                    error("second")
                }
            }.exceptionOrNull()
        }

        assertTrue(inside.await(5, TimeUnit.SECONDS))
        gate.request(0) {}
        release.countDown()
        first.join(5_000)

        assertEquals("first", thrown?.message)
        assertEquals("second", thrown?.suppressed?.singleOrNull()?.message)
    }
}
