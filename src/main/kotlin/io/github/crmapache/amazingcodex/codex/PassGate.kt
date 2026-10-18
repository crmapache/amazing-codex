package io.github.crmapache.amazingcodex.codex

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * One pass at a time over one tab's "what is said next" - see CodexSessionHub.runQueued.
 *
 * That question is asked from several threads: the end of a turn, a process swapped quietly, a limit's timer,
 * the hub's minute round. Answering it is two steps - is the tab free, then send, which marks it busy - and two
 * passes side by side both found it free: one said the panel's "carry on", the other the message waiting in the
 * queue, and the second went into the turn the first had just started.
 *
 * A request made while a pass is running does not wait and is not dropped: it returns at once, and the running
 * thread makes one more pass for it. That pass sees the tab busy and does nothing - or sees what changed in the
 * meantime, a message queued at that very moment, and sends it. Nothing blocks, so nothing can deadlock against
 * whatever the pass itself does (raising a process, saving an editor's buffers).
 */
internal class PassGate {

    /** Requests not yet answered by a finished pass; above zero, somebody is passing. */
    private val pending = AtomicInteger(0)

    /** The latest moment asked about since the last pass began - a limit's timer asks for its own (see [request]). */
    private val wanted = AtomicLong(0)

    /**
     * Run [pass] now, or have the thread already running it run its own pass once more - so every caller of
     * one gate hands it the same work (see CodexSessionHub.runQueued: one gate per tab, one pass per gate).
     *
     * [now] is the moment the pass is to judge by, at least - a wait's timer passes the moment it was wound for,
     * since a timer can come a hair early and find "not yet". The pass gets the later of it and the clock.
     *
     * A pass that throws does not shut the gate: the rest are still run, and the first failure is rethrown to
     * the thread that ran them, with any later ones attached to it rather than lost.
     */
    fun request(now: Long, pass: (now: Long) -> Unit) {
        wanted.accumulateAndGet(now) { held, asked -> maxOf(held, asked) }
        if (pending.getAndIncrement() > 0) return

        var owned = 1
        var failure: Throwable? = null

        while (true) {
            val at = maxOf(System.currentTimeMillis(), wanted.getAndSet(0))
            runCatching { pass(at) }.onFailure { failed -> failure?.addSuppressed(failed) ?: run { failure = failed } }

            // What came in during the pass is owned by the next one; nothing came - the gate is open again.
            val left = pending.addAndGet(-owned)
            if (left == 0) break
            owned = left
        }

        failure?.let { throw it }
    }
}
