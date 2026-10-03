package io.github.crmapache.amazingcodex.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The executable lookup breaks where hands cannot reach: someone else's Windows, an unusual install
 * location, the PATH of the IDE's shell. We check the path walking against a substituted environment -
 * otherwise Windows would be guesswork and a person would be sent to check blind.
 */
class CodexLookupTest {

    private fun unix(
        env: Map<String, String> = emptyMap(),
        configured: String = "",
        home: String = "/Users/max",
    ) = CodexLookup.candidates(windows = false, home = home, env = env, configured = configured, separator = ':')

    private fun windows(
        env: Map<String, String> = emptyMap(),
        configured: String = "",
        home: String = "C:\\Users\\max",
    ) = CodexLookup.candidates(windows = true, home = home, env = env, configured = configured, separator = ';')

    @Test
    fun `on Windows every name Codex is installed under is checked`() {
        // A native build is an exe, npm puts down a cmd wrapper, MSYS builds a file with no extension at all.
        assertEquals(listOf("codex.exe", "codex.cmd", "codex.bat", "codex"), CodexLookup.executableNames(true))
        assertEquals(listOf("codex"), CodexLookup.executableNames(false))
    }

    @Test
    fun `every PATH folder is checked with every name`() {
        val paths = windows(env = mapOf("Path" to "C:\\tools\\bin;C:\\other\\"))

        assertTrue("C:\\tools\\bin\\codex.exe" in paths)
        assertTrue("C:\\tools\\bin\\codex.cmd" in paths)
        // A trailing separator on a PATH entry does not double the slash.
        assertTrue("C:\\other\\codex.exe" in paths)
    }

    // On Windows the variable is called `Path`, and the environment map is not always case-insensitive:
    // asking for "PATH" alone would have found nothing.
    @Test
    fun `PATH is found under any spelling`() {
        assertEquals("A", CodexLookup.pathValue(mapOf("PATH" to "A")))
        assertEquals("B", CodexLookup.pathValue(mapOf("Path" to "B")))
        assertEquals("C", CodexLookup.pathValue(mapOf("path" to "C")))
    }

    @Test
    fun `the npm wrapper and the installers' folders from the environment get into the list`() {
        val paths = windows(
            env = mapOf(
                "APPDATA" to "C:\\Users\\max\\AppData\\Roaming",
                "LOCALAPPDATA" to "C:\\Users\\max\\AppData\\Local",
            ),
        )

        assertTrue("C:\\Users\\max\\AppData\\Roaming\\npm\\codex.cmd" in paths)
        assertTrue("C:\\Users\\max\\AppData\\Local\\Programs\\codex\\codex.exe" in paths)
        assertTrue("C:\\Users\\max\\AppData\\Local\\codex\\codex.exe" in paths)
    }

    @Test
    fun `an environment without them adds no blank folder`() {
        val paths = windows(env = mapOf("APPDATA" to "  "))

        assertTrue(paths.none { it.startsWith("\\") || it.startsWith("  ") })
        // The home-relative npm folder is still there for such a machine.
        assertTrue("C:\\Users\\max\\AppData\\Roaming\\npm\\codex.cmd" in paths)
    }

    @Test
    fun `the usual install roads are checked on both systems`() {
        val onUnix = unix()
        assertTrue("/opt/homebrew/bin/codex" in onUnix)
        assertTrue("/usr/local/bin/codex" in onUnix)
        assertTrue("/Users/max/.local/bin/codex" in onUnix)
        assertTrue("/Users/max/.cargo/bin/codex" in onUnix)
        assertTrue("/Users/max/.npm-global/bin/codex" in onUnix)

        val onWindows = windows()
        assertTrue("C:\\Users\\max\\.local\\bin\\codex.exe" in onWindows)
        assertTrue("C:\\Users\\max\\.cargo\\bin\\codex.exe" in onWindows)
        assertTrue("C:\\Users\\max\\scoop\\shims\\codex.exe" in onWindows)
    }

    // A person is just as likely to point at the file itself as at the folder holding it.
    @Test
    fun `a path given by hand comes first - as a file and as a folder`() {
        val paths = unix(configured = "/opt/codex")

        assertEquals("/opt/codex", paths.first())
        assertTrue("/opt/codex/codex" in paths)
    }

    @Test
    fun `a tilde in the given path expands to the home folder`() {
        assertTrue("/Users/max/bin/codex" in unix(configured = "~/bin/codex"))
        assertEquals("C:\\Users\\max\\bin", CodexLookup.expandHome("~\\bin", "C:\\Users\\max"))
        // Only a leading tilde followed by a separator is the home folder.
        assertEquals("~other/bin", CodexLookup.expandHome("~other/bin", "/Users/max"))
    }

    @Test
    fun `an empty setting adds nothing`() {
        assertFalse(unix(configured = "   ").any { it.isBlank() })
    }

    @Test
    fun `one and the same path is not checked twice`() {
        // PATH often holds the same folder as our list of usual locations.
        val paths = unix(env = mapOf("PATH" to "/Users/max/.local/bin"))
        assertEquals(1, paths.count { it == "/Users/max/.local/bin/codex" })
    }

    @Test
    fun `PATH comes before the usual locations`() {
        val paths = unix(env = mapOf("PATH" to "/somewhere/bin"))

        assertTrue(paths.indexOf("/somewhere/bin/codex") < paths.indexOf("/opt/homebrew/bin/codex"))
    }
}
