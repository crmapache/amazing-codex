package io.github.crmapache.amazingcodex.codex

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The arithmetic of where Codex lives for a project inside WSL, as seen from Windows - apart from
 * `wsl.exe` and the disk, which a test on this machine has neither of. What breaks here breaks in
 * silence: the panel shows an empty history and says nothing (see CodexHome).
 */
class CodexHomeTest {

    private val root = "\\\\wsl.localhost\\Ubuntu"

    private fun home(configDirectory: String? = null, realLinuxPath: String? = null): CodexHome =
        CodexHome.inWsl(
            root = root,
            linuxPath = "/home/ivan/repo",
            realLinuxPath = realLinuxPath,
            home = "/home/ivan",
            configDirectory = configDirectory,
        )

    // Codex inside the distribution keeps its files in that user's home, and Windows reaches them
    // through the share the project was opened from.
    @Test
    fun `the config directory is the distribution user's home on the share`() {
        assertEquals("\\\\wsl.localhost\\Ubuntu\\home\\ivan\\.codex", home().configDirectory.path)
        assertTrue(home().remote)
    }

    @Test
    fun `the policy directory is the distribution's etc`() {
        assertEquals("\\\\wsl.localhost\\Ubuntu\\etc\\codex", home().managedSettingsDirectory.path)
    }

    // A moved home is Codex's own rule, and it is the distribution user's variable that moves it - not
    // one in the IDE's environment on Windows.
    @Test
    fun `CODEX_HOME inside the distribution moves the config directory`() {
        assertEquals("\\\\wsl.localhost\\Ubuntu\\srv\\codex", home(configDirectory = "/srv/codex").configDirectory.path)
        assertEquals("\\\\wsl.localhost\\Ubuntu\\home\\ivan\\cfg", home(configDirectory = "~/cfg").configDirectory.path)
        assertEquals("\\\\wsl.localhost\\Ubuntu\\home\\ivan\\cfg", home(configDirectory = "cfg").configDirectory.path)
        assertEquals("\\\\wsl.localhost\\Ubuntu\\home\\ivan", home(configDirectory = "~").configDirectory.path)
        assertEquals("\\\\wsl.localhost\\Ubuntu\\home\\ivan\\.codex", home(configDirectory = "  ").configDirectory.path)
    }

    // Codex names the project by the path it sees - the Linux one: that is the working directory its
    // conversation files record, and the flat name the search index keeps them under.
    @Test
    fun `the project is named by its Linux path`() {
        assertEquals(listOf("/home/ivan/repo"), home().projectPaths)
        assertEquals("-home-ivan-repo", CodexHistory.slugFor(home().projectPaths.single()))
    }

    @Test
    fun `the real path behind a link is a second name, and the same path is not`() {
        assertEquals(listOf("/home/ivan/repo", "/srv/repo"), home(realLinuxPath = "/srv/repo").projectPaths)
        assertEquals(listOf("/home/ivan/repo"), home(realLinuxPath = "/home/ivan/repo").projectPaths)
    }

    // A plugin's install path is printed by Codex in its own terms; the hint reads it from Windows.
    @Test
    fun `a path Codex printed is opened through the share`() {
        assertEquals(
            "\\\\wsl.localhost\\Ubuntu\\home\\ivan\\.codex\\plugins\\cache\\demo",
            home().hostPath("/home/ivan/.codex/plugins/cache/demo").path,
        )
    }

    @Test
    fun `the share's spelling is kept and slashes are turned`() {
        assertEquals("\\\\wsl$\\Ubuntu\\home\\ivan", CodexHome.windowsPathOf("\\\\wsl$\\Ubuntu", "/home/ivan"))
        assertEquals("\\\\wsl.localhost\\Ubuntu\\home\\ivan", CodexHome.windowsPathOf("\\\\wsl.localhost\\Ubuntu\\", "home/ivan"))
        assertEquals("\\\\wsl.localhost\\Ubuntu\\", CodexHome.windowsPathOf("\\\\wsl.localhost\\Ubuntu", "/"))
    }

    // A project on this machine is answered exactly as every reader answered it for itself before: the
    // machine's own directories, the path beside its canonical form. Nothing about WSL is touched.
    @Test
    fun `a project on this machine is the local answer`() {
        val directory = Files.createTempDirectory("acc-home").toFile()
        val home = CodexHome.local(directory.path)

        assertFalse(home.remote)
        assertEquals(HostOs.configDirectory(), home.configDirectory)
        assertEquals(HostOs.managedSettingsDirectory(), home.managedSettingsDirectory)
        assertEquals(directory.path, home.projectPaths.first())
        assertEquals(directory.canonicalPath, home.projectPaths.last())
        assertEquals(File("/x/y"), home.hostPath("/x/y"))
    }

    @Test
    fun `no project directory means nowhere to look, not this machine's home`() {
        assertEquals(emptyList(), CodexHome.local(null).projectPaths)
        assertEquals(emptyList(), CodexHome.of(null).projectPaths)
    }

    // The share is a Windows thing: on any other machine a path that merely starts with two slashes is a
    // local path, and asking the WSL classes about it would be asking the wrong machine.
    @Test
    fun `off Windows a share-looking path takes the local road`() {
        if (HostOs.isWindows) return

        val home = CodexHome.of("//wsl.localhost/Ubuntu/home/ivan/repo")

        assertFalse(home.remote)
        assertEquals(HostOs.configDirectory(), home.configDirectory)
    }

    // Everything Codex keeps is found under its home: the conversations by day, the custom prompts, the
    // skills and the config file.
    @Test
    fun `Codex's folders hang off its home`() {
        val home = home()
        val config = home.configDirectory

        assertEquals(File(config, "sessions"), home.sessionsDirectory)
        assertEquals(File(config, "prompts"), home.promptsDirectory)
        assertEquals(File(config, "skills"), home.skillsDirectory)
        assertEquals(File(config, "config.toml"), home.configFile)
    }

    @Test
    fun `off Windows this machine's policy directory is etc codex`() {
        if (HostOs.isWindows) return

        assertEquals(File("/etc/codex"), HostOs.managedSettingsDirectory())
        assertEquals("CODEX_HOME", HostOs.HOME_VARIABLE)
    }
}
