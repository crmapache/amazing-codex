package io.github.crmapache.amazingcodex.net

import java.io.IOException
import java.net.ConnectException
import java.nio.channels.UnresolvedAddressException
import javax.net.ssl.SSLHandshakeException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The line a failed request leaves in the IDE's log (see IdeHttp.describe). */
class IdeHttpTest {

    /**
     * The shape of the failure this is really about: an address that would not resolve, wrapped twice by
     * the JDK's client and mute all the way down. Written from the messages alone the log said "null".
     */
    @Test
    fun `a failure with nothing to say is still named in the log`() {
        val mute = ConnectException()
        mute.initCause(UnresolvedAddressException())

        val line = IdeHttp.describe(mute)

        assertContains(line, "ConnectException")
        assertContains(line, "UnresolvedAddressException")
        assertFalse(line.contains("null"))
    }

    @Test
    fun `whatever a cause did find to say is carried up to the line`() {
        val outer = IOException()
        outer.initCause(SSLHandshakeException("certificate not trusted"))

        val line = IdeHttp.describe(outer)

        assertContains(line, "SSLHandshakeException")
        assertContains(line, "certificate not trusted")
    }

    /** A chain can be a ring, and a log line is not worth hanging a request's thread over. */
    @Test
    fun `a cause that points back at itself does not go round forever`() {
        assertEquals("Looping: round we go", IdeHttp.describe(Looping()))
    }

    private class Looping : RuntimeException("round we go") {
        override val cause: Throwable get() = this
    }
}
