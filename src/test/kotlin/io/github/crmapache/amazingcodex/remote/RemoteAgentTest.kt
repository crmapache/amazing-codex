package io.github.crmapache.amazingcodex.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The address rule, kept apart from everything that needs a running IDE.
 *
 * It reads like strictness for its own sake and is not. A browser gives a page `crypto.subtle` only in
 * a secure context, so a relay served over plain HTTP does not make phase 3's encryption weaker - it
 * makes it impossible. Better to refuse the address than to ship something that looks encrypted.
 */
class RemoteAgentTest {

    @Test
    fun `a secure relay address is accepted`() {
        assertTrue(RemoteAgent.isSecure("wss://relay.example.com"))
        assertTrue(RemoteAgent.isSecure(RemoteAgent.DEFAULT_RELAY))
    }

    @Test
    fun `a plain address is refused`() {
        assertFalse(RemoteAgent.isSecure("ws://relay.example.com"))
        assertFalse(RemoteAgent.isSecure("http://relay.example.com"))
        assertFalse(RemoteAgent.isSecure("https://relay.example.com"))
        assertFalse(RemoteAgent.isSecure(""))
    }

    /** Loopback is the exception, because a browser counts it as secure and it is where this is built. */
    @Test
    fun `loopback is allowed for development`() {
        assertTrue(RemoteAgent.isSecure("ws://localhost:8080"))
        assertTrue(RemoteAgent.isSecure("ws://127.0.0.1:8080"))
    }

    /**
     * A hostname that merely starts with "localhost" is somebody else's machine. This is the shape of
     * mistake that reads as harmless in review and hands a third party the traffic.
     */
    @Test
    fun `a hostname that only looks like loopback is still refused`() {
        assertFalse(RemoteAgent.isSecure("ws://localhost.example.com"))
        assertFalse(RemoteAgent.isSecure("ws://127.0.0.1.example.com"))
    }

    /** Nothing chosen anywhere means this fork's own relay - the one that serves this fork's phone client. */
    @Test
    fun `with nothing chosen the fork's relay is dialled`() {
        assertEquals("wss://relay-codex.mzpizote.com", RemoteAgent.chooseRelay("", ""))
        assertEquals(RemoteAgent.DEFAULT_RELAY, RemoteAgent.chooseRelay("  ", "  "))
    }

    @Test
    fun `a sandbox property wins over the saved address, and the saved one over the default`() {
        assertEquals("ws://localhost:4450", RemoteAgent.chooseRelay("ws://localhost:4450", "wss://mine.example.com"))
        assertEquals("wss://mine.example.com", RemoteAgent.chooseRelay("", "wss://mine.example.com"))
    }

    /**
     * The Claude project's relay was this fork's default, and the panel saves its field on Enter, so a
     * saved copy of it is the old default rather than a choice. Kept, it would hand the phone the other
     * plugin's client.
     */
    @Test
    fun `the old default saved by the panel falls back to the fork's relay`() {
        assertEquals(RemoteAgent.DEFAULT_RELAY, RemoteAgent.chooseRelay("", "wss://relay.mzpizote.com"))
        assertEquals(RemoteAgent.DEFAULT_RELAY, RemoteAgent.chooseRelay("", "WSS://Relay.MZPizote.com/"))
        // Its development twin is somebody's deliberate pick, not a default anybody inherited.
        assertEquals("wss://relay-dev.mzpizote.com", RemoteAgent.chooseRelay("", "wss://relay-dev.mzpizote.com"))
    }
}
