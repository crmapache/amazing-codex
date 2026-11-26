package io.github.crmapache.amazingcodex.voice

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a dictation asks Deepgram for.
 *
 * The phone asks the same with its own code (`listenUrl` in webview/src/mobile/dictation.ts), and its test
 * expects these same addresses: the two drift silently, and a test on each side naming the same string is
 * what makes a change to one side visibly a change to both.
 */
class DeepgramStreamTest {

    @Test
    fun `a dictation asks for what the desk and the phone both ask for`() {
        assertEquals(
            "wss://api.deepgram.com/v1/listen?model=nova-3&language=en&encoding=linear16&sample_rate=16000" +
                "&channels=1&smart_format=true&interim_results=true",
            DeepgramStream.listenUrl("en", 16_000, trainingOptOut = false),
        )
    }

    /** Deepgram fails the handshake on any value but `true`, so the parameter is either right or absent. */
    @Test
    fun `opting out of training adds the one parameter Deepgram reads it by`() {
        assertEquals(
            "wss://api.deepgram.com/v1/listen?model=nova-3&language=en&encoding=linear16&sample_rate=16000" +
                "&channels=1&smart_format=true&interim_results=true&mip_opt_out=true",
            DeepgramStream.listenUrl("en", 16_000, trainingOptOut = true),
        )
    }
}
