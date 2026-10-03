package io.github.crmapache.amazingcodex.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.jsonPrimitive

class PermissionModesTest {

    @Test
    fun `the mode's old name is brought to the one the panel knows`() {
        // Older settings and the Claude-era panel called the asking mode "default".
        assertEquals("manual", PermissionModes.normalize("default"))
    }

    @Test
    fun `the known modes are left alone`() {
        for (mode in listOf("manual", "acceptEdits", "readOnly", "plan", "bypassPermissions")) {
            assertEquals(mode, PermissionModes.normalize(mode))
        }
    }

    // Claude's classifier modes have no Codex counterpart, and neither has a hand-edited typo: both land
    // on Codex's own default rather than on something stricter or looser nobody chose.
    @Test
    fun `a mode Codex does not have becomes its default`() {
        assertEquals("acceptEdits", PermissionModes.normalize("auto"))
        assertEquals("acceptEdits", PermissionModes.normalize("dontAsk"))
        assertEquals("acceptEdits", PermissionModes.normalize("yolo"))
    }

    @Test
    fun `an unchosen mode takes the fallback, Codex's default when none is given`() {
        // The panel works the default out itself (see PermissionDefaultMode) and passes it in: the
        // selector has to show the same mode the process came up with.
        assertEquals("acceptEdits", PermissionModes.resolve(""))
        assertEquals("readOnly", PermissionModes.resolve("", fallback = "readOnly"))
    }

    @Test
    fun `a chosen mode is not overridden by the fallback`() {
        assertEquals("plan", PermissionModes.resolve("plan", fallback = "readOnly"))
        assertEquals("bypassPermissions", PermissionModes.resolve("bypassPermissions"))
        assertEquals("manual", PermissionModes.resolve("default"))
    }

    @Test
    fun `each mode asks Codex for its approval policy and sandbox`() {
        assertEquals(PermissionModes.Policy("untrusted", "workspace-write", plan = false), PermissionModes.policyOf("manual"))
        assertEquals(PermissionModes.Policy("on-request", "workspace-write", plan = false), PermissionModes.policyOf("acceptEdits"))
        assertEquals(PermissionModes.Policy("on-request", "read-only", plan = false), PermissionModes.policyOf("readOnly"))
        assertEquals(PermissionModes.Policy("on-request", "read-only", plan = true), PermissionModes.policyOf("plan"))
        assertEquals(PermissionModes.Policy("never", "danger-full-access", plan = false), PermissionModes.policyOf("bypassPermissions"))
        // Nothing chosen is Codex's default.
        assertEquals(PermissionModes.policyOf("acceptEdits"), PermissionModes.policyOf(null))
        assertEquals(PermissionModes.policyOf("manual"), PermissionModes.policyOf("default"))
    }

    @Test
    fun `the sandbox is spelled as the object a turn takes`() {
        assertEquals("dangerFullAccess", PermissionModes.policyOf("bypassPermissions").sandboxPolicy()["type"]?.jsonPrimitive?.content)

        val readOnly = PermissionModes.policyOf("readOnly").sandboxPolicy()
        assertEquals("readOnly", readOnly["type"]?.jsonPrimitive?.content)
        assertEquals("false", readOnly["networkAccess"]?.jsonPrimitive?.content)

        val workspace = PermissionModes.policyOf("acceptEdits").sandboxPolicy()
        assertEquals("workspaceWrite", workspace["type"]?.jsonPrimitive?.content)
        assertTrue("writableRoots" in workspace)
        assertFalse("dangerFullAccess" == workspace["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a pair of Codex settings is read back as a mode`() {
        assertEquals("bypassPermissions", PermissionModes.fromPolicy("never", "danger-full-access"))
        assertEquals("manual", PermissionModes.fromPolicy("untrusted", "workspace-write"))
        assertEquals("manual", PermissionModes.fromPolicy("untrusted", "read-only"))
        assertEquals("readOnly", PermissionModes.fromPolicy("on-request", "read-only"))
        assertEquals("acceptEdits", PermissionModes.fromPolicy("on-request", "workspace-write"))
        // Full access that still asks is not bypass: it asks.
        assertEquals("acceptEdits", PermissionModes.fromPolicy("on-request", "danger-full-access"))
    }

    // Every mode the chip offers survives the round trip through Codex's own settings, except plan, which
    // is a collaboration mode rather than a policy.
    @Test
    fun `a mode read back from its own policy is itself`() {
        for (mode in listOf("manual", "acceptEdits", "readOnly", "bypassPermissions")) {
            val policy = PermissionModes.policyOf(mode)
            assertEquals(mode, PermissionModes.fromPolicy(policy.approval, policy.sandboxMode))
        }
    }
}
