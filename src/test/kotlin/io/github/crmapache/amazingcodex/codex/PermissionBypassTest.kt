package io.github.crmapache.amazingcodex.codex

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Whether "Full access" may be offered. Codex needs no launch flag for it - the sandbox is a turn
 * parameter - so only an administrator's `requirements.toml` can take it away, and that file lives on
 * the machine, out of a test's reach. What is held here is the half that does not depend on it.
 */
class PermissionBypassTest {

    private val requirements = File(HostOs.managedSettingsDirectory(), "requirements.toml")

    @Test
    fun `with no requirements nothing is forbidden`() {
        if (requirements.exists()) return

        assertTrue(PermissionBypass.allowedBySettings(null))
        assertTrue(PermissionBypass.isAvailable("/tmp/some-project"))
        assertEquals(null, CodexSettings.allowed(null, "allowed_sandbox_modes"))
    }

    @Test
    fun `availability is the requirements' answer and nothing else`() {
        assertEquals(PermissionBypass.allowedBySettings("/tmp/some-project"), PermissionBypass.isAvailable("/tmp/some-project"))
    }
}
