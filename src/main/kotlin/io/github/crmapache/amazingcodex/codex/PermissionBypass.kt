package io.github.crmapache.amazingcodex.codex

/**
 * Whether "Full access" may be offered at all.
 *
 * Codex needs no flag to allow switching into it mid-conversation - the sandbox is a turn parameter - so
 * the only thing that can take it away is an administrator: `allowed_sandbox_modes` in the system-wide
 * `requirements.toml`. A mode the requirements forbid Codex would refuse on the turn it is used in, and a
 * chip offering it would be offering a refusal.
 */
internal object PermissionBypass {

    fun isAvailable(projectDirectory: String?): Boolean = allowedBySettings(projectDirectory)

    fun allowedBySettings(projectDirectory: String?): Boolean {
        val allowed = CodexSettings.allowed(projectDirectory, "allowed_sandbox_modes") ?: return true
        return PermissionModes.SANDBOX_FULL in allowed
    }
}
