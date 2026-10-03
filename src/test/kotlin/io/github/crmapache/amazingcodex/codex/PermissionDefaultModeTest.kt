package io.github.crmapache.amazingcodex.codex

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The mode a new tab starts in when nobody picked one in the panel: what the person's Codex configuration
 * says (`approval_policy` and `sandbox_mode`), read as one of the panel's modes.
 */
class PermissionDefaultModeTest {

    @Test
    fun `with nothing said we start where Codex starts`() {
        assertEquals(PermissionModes.ACCEPT_EDITS, PermissionDefaultMode.of("", "", bypassAllowed = true))
    }

    @Test
    fun `the approval policy and the sandbox are read as one mode`() {
        assertEquals(PermissionModes.ASK, PermissionDefaultMode.of("untrusted", "workspace-write", bypassAllowed = true))
        assertEquals(PermissionModes.READ_ONLY, PermissionDefaultMode.of("on-request", "read-only", bypassAllowed = true))
        assertEquals(PermissionModes.ACCEPT_EDITS, PermissionDefaultMode.of("on-request", "workspace-write", bypassAllowed = true))
        assertEquals(PermissionModes.BYPASS, PermissionDefaultMode.of("never", "danger-full-access", bypassAllowed = true))
    }

    // Either half alone is still a choice somebody made: the other half is Codex's own default.
    @Test
    fun `one half said is read with the other left as Codex has it`() {
        assertEquals(PermissionModes.READ_ONLY, PermissionDefaultMode.of("", "read-only", bypassAllowed = true))
        assertEquals(PermissionModes.ASK, PermissionDefaultMode.of("untrusted", "", bypassAllowed = true))
        assertEquals(PermissionModes.ACCEPT_EDITS, PermissionDefaultMode.of("never", "", bypassAllowed = true))
    }

    // A mode the requirements forbid Codex would refuse on the first turn, and the panel would be left
    // showing a mode the conversation does not have.
    @Test
    fun `full access is not taken as the default when it is forbidden`() {
        assertEquals(PermissionModes.ACCEPT_EDITS, PermissionDefaultMode.of("never", "danger-full-access", bypassAllowed = false))
        // Only full access is taken away: the rest stands.
        assertEquals(PermissionModes.ASK, PermissionDefaultMode.of("untrusted", "workspace-write", bypassAllowed = false))
    }

    /**
     * The project's own `.codex/config.toml` outranks the person's. Read off the disk of this machine, so
     * the one layer above it - an administrator's config - has to be silent about the keys for the answer
     * to be the project's.
     */
    /**
     * Only once the project is trusted: Codex reads nothing of an untrusted project's own config, and a
     * panel that did would start a new tab in a mode no Codex process here comes up in (see CodexSettings).
     */
    @Test
    fun `an untrusted project's own config is not the default`() {
        val policy = File(HostOs.managedSettingsDirectory(), "config.toml")
        if (CodexSettings.value(policy, "approval_policy").isNotEmpty() || CodexSettings.value(policy, "sandbox_mode").isNotEmpty()) return

        val project = Files.createTempDirectory("acx-default-mode").toFile()
        File(project, ".codex").mkdirs()
        File(project, ".codex/config.toml").writeText(
            """
            # the project asks before anything
            approval_policy = "untrusted"
            sandbox_mode = "workspace-write"

            [profiles.fast]
            approval_policy = "never"
            """.trimIndent(),
        )

        try {
            // The machine's own config may be trusting nothing at that temporary path - and must not be
            // what decides: the default is the one the project would not have set.
            val withoutProject = PermissionDefaultMode.of(null)
            assertEquals(withoutProject, PermissionDefaultMode.of(project.absolutePath))
        } finally {
            project.deleteRecursively()
        }
    }
}
