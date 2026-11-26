package io.github.crmapache.amazingcodex.codex.terminal

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalDeskTest {

    // npm's codex.cmd is a batch file, and a batch file is not a program: it is run through cmd.exe.
    @Test
    fun `a batch file on Windows is run through cmd`() {
        val batch = File("C:\\Users\\me\\AppData\\Roaming\\npm\\codex.cmd")

        assertEquals(listOf("cmd.exe", "/c", batch.absolutePath), TerminalDesk.launcher(batch, windows = true))
    }

    @Test
    fun `a program is run as it is`() {
        val native = File("/Users/me/.local/bin/codex")

        assertEquals(listOf(native.absolutePath), TerminalDesk.launcher(native, windows = false))
        assertEquals(listOf(native.absolutePath), TerminalDesk.launcher(native, windows = true))
    }
}
