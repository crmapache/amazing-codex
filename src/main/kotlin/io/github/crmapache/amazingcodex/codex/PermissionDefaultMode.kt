package io.github.crmapache.amazingcodex.codex

/**
 * The mode a new tab starts in when the person has not picked one in the panel: whatever their own Codex
 * configuration says (`approval_policy` and `sandbox_mode`), and Codex's own default - "Auto" - when it
 * says nothing.
 *
 * Read off the files rather than asked of a process: it is needed for the panel's very first frame, and
 * raising Codex for it would cost a process before anybody has typed a word.
 */
internal object PermissionDefaultMode {

    fun of(projectDirectory: String?): String {
        val approval = CodexSettings.effective(projectDirectory, "approval_policy")
        val sandbox = CodexSettings.effective(projectDirectory, "sandbox_mode")
        return of(approval, sandbox, bypassAllowed = PermissionBypass.allowedBySettings(projectDirectory))
    }

    fun of(approval: String, sandbox: String, bypassAllowed: Boolean): String {
        if (approval.isEmpty() && sandbox.isEmpty()) return PermissionModes.ACCEPT_EDITS
        val mode = PermissionModes.fromPolicy(approval, sandbox)
        return if (mode == PermissionModes.BYPASS && !bypassAllowed) PermissionModes.ACCEPT_EDITS else mode
    }
}
