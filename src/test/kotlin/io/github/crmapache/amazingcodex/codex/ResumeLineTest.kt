package io.github.crmapache.amazingcodex.codex

import junit.framework.TestCase

/**
 * The line `/status` offers for a terminal (see ResumeLine): plain on the default sign-in, the account's drawer in
 * front of it where one form carries it for that command alone, and an honest "on the default sign-in" where none
 * does - rather than a line that bills the other subscription without a word.
 */
class ResumeLineTest : TestCase() {

    private val id = "019a2b3c-4d5e-7f60-8a9b-0c1d2e3f4a5b"

    private val homes = "/Users/max/.amazing-codex/accounts/s-1f2e3d4c" to "/Users/max/.codex"

    fun testTheDefaultSignInIsThePlainLine() {
        assertEquals(ResumeLine.Line(command = null, account = null, onDefault = false), ResumeLine.of(id, null, null, posix = true, carried = emptyList()))
        assertEquals("codex resume $id", ResumeLine.plain(id))
    }

    fun testAnAddedAccountOnAPosixShellNamesItsDrawer() {
        val line = ResumeLine.of(id, "Work", homes, posix = true, carried = emptyList())

        assertEquals(
            "CODEX_HOME=/Users/max/.amazing-codex/accounts/s-1f2e3d4c CODEX_SQLITE_HOME=/Users/max/.codex codex resume $id",
            line.command,
        )
        assertEquals("Work", line.account)
        assertFalse(line.onDefault)
    }

    /** A key the shell carries would outrank the drawer, so the line clears it - only those, and in a fixed order. */
    fun testTheKeysTheShellCarriesAreClearedForTheCommand() {
        val line = ResumeLine.of(id, "Work", homes, posix = true, carried = listOf("CODEX_API_KEY", "OPENAI_API_KEY"))

        assertEquals(
            "CODEX_HOME=/Users/max/.amazing-codex/accounts/s-1f2e3d4c CODEX_SQLITE_HOME=/Users/max/.codex " +
                "OPENAI_API_KEY= CODEX_API_KEY= codex resume $id",
            line.command,
        )
    }

    fun testAPathTheShellWouldReadIsQuoted() {
        val line = ResumeLine.of(id, "Work", "/Users/Jo O'Neil/.amazing-codex/accounts/s-1" to "/Users/Jo O'Neil/.codex", posix = true, carried = emptyList())

        assertEquals(
            "CODEX_HOME='/Users/Jo O'\\''Neil/.amazing-codex/accounts/s-1' CODEX_SQLITE_HOME='/Users/Jo O'\\''Neil/.codex' codex resume $id",
            line.command,
        )
        assertEquals("'~/x'", ResumeLine.word("~/x"))
        assertEquals("''", ResumeLine.word(""))
    }

    /** No form there sets a variable for one command, and a guess would leave it set - or fail - in the other shell. */
    fun testWindowsGetsThePlainLineAndSaysWhoItRunsAs() {
        assertEquals(ResumeLine.Line(command = null, account = "Work", onDefault = true), ResumeLine.of(id, "Work", homes, posix = false, carried = emptyList()))
    }

    /** A phone is not handed the paths, and an account whose drawer would not resolve has none to hand. */
    fun testNoHomesToNameIsTheDefaultSignInSaid() {
        assertEquals(ResumeLine.Line(command = null, account = "Work", onDefault = true), ResumeLine.of(id, "Work", null, posix = true, carried = emptyList()))
    }

    fun testAnAccountWithoutANameIsStillAnAccount() {
        val line = ResumeLine.of(id, "", null, posix = true, carried = emptyList())

        assertNull(line.account)
        assertTrue(line.onDefault)
    }
}
