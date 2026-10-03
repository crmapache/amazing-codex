package io.github.crmapache.amazingcodex.codex

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The permission modes the MODE chip offers, and what each one asks of Codex.
 *
 * Codex has no single "mode": what a turn may do is two settings together - who is asked (the approval
 * policy) and what a command can touch at all (the sandbox) - plus, for planning, a collaboration mode.
 * The panel keeps its own short list of named modes, because a chip with two dropdowns behind it is a chip
 * nobody reads, and each name here is one of the presets Codex's own `/approvals` offers:
 *
 * - [ASK] - ask before anything that is not known to be safe, edits included (`untrusted`).
 * - [ACCEPT_EDITS] - Codex's "Auto": edit the project and run commands in the sandbox freely, ask only to
 *   step outside it (`on-request` + workspace-write). Codex's own default for a trusted folder.
 * - [READ_ONLY] - look but do not touch: read-only sandbox, ask to step outside it.
 * - [PLAN] - plan mode: the agent explores read-only and ends with a plan the person approves.
 * - [BYPASS] - "Full access": no sandbox and no questions (`never` + danger-full-access).
 *
 * The ids are the panel's old ones wherever the meaning carried over - `acceptEdits` is still what a plan
 * approval switches to (see SessionPermissions.decidePlan), the phone and the stored preferences name
 * them, and renaming them would only have moved the same decision into more places.
 */
internal object PermissionModes {

    const val ASK = "manual"
    const val ACCEPT_EDITS = "acceptEdits"
    const val READ_ONLY = "readOnly"
    const val PLAN = "plan"
    const val BYPASS = "bypassPermissions"

    /** What older settings and the Claude-era panel called ASK. */
    private const val LEGACY_ASK = "default"

    val KNOWN = setOf(ASK, ACCEPT_EDITS, READ_ONLY, PLAN, BYPASS)

    /** A name from before, or from a hand-edited setting, as one of [KNOWN]; an unknown one is Codex's default. */
    fun normalize(mode: String): String = when (mode) {
        LEGACY_ASK -> ASK
        // Claude's classifier modes have no Codex counterpart; the nearest honest one is the default.
        "auto", "dontAsk" -> ACCEPT_EDITS
        in KNOWN -> mode
        else -> ACCEPT_EDITS
    }

    fun resolve(stored: String, fallback: String = ACCEPT_EDITS): String =
        if (stored.isEmpty()) fallback else normalize(stored)

    /** What a mode asks of Codex - see the list above. */
    data class Policy(
        /** `approvalPolicy` in the protocol. */
        val approval: String,
        /** `sandbox` of thread/start and thread/resume: the mode's short name. */
        val sandboxMode: String,
        /** Whether the turn runs in Codex's plan collaboration mode. */
        val plan: Boolean,
    ) {
        /** `sandboxPolicy` of turn/start: the same sandbox, spelled as the object that field takes. */
        fun sandboxPolicy(): JsonObject = when (sandboxMode) {
            SANDBOX_FULL -> buildJsonObject { put("type", "dangerFullAccess") }
            SANDBOX_READ_ONLY -> buildJsonObject {
                put("type", "readOnly")
                put("networkAccess", false)
            }
            else -> buildJsonObject {
                put("type", "workspaceWrite")
                putJsonArray("writableRoots") {}
                put("networkAccess", false)
                put("excludeTmpdirEnvVar", false)
                put("excludeSlashTmp", false)
            }
        }
    }

    fun policyOf(mode: String?): Policy = when (normalize(mode.orEmpty())) {
        ASK -> Policy(approval = "untrusted", sandboxMode = SANDBOX_WORKSPACE, plan = false)
        READ_ONLY -> Policy(approval = "on-request", sandboxMode = SANDBOX_READ_ONLY, plan = false)
        PLAN -> Policy(approval = "on-request", sandboxMode = SANDBOX_READ_ONLY, plan = true)
        BYPASS -> Policy(approval = "never", sandboxMode = SANDBOX_FULL, plan = false)
        else -> Policy(approval = "on-request", sandboxMode = SANDBOX_WORKSPACE, plan = false)
    }

    /**
     * Which of the panel's modes a pair of Codex settings is - the mode a person's own config.toml puts a
     * new tab in (see PermissionDefaultMode).
     */
    fun fromPolicy(approval: String, sandbox: String): String = when {
        approval == "never" && sandbox == SANDBOX_FULL -> BYPASS
        approval == "untrusted" -> ASK
        sandbox == SANDBOX_READ_ONLY -> READ_ONLY
        else -> ACCEPT_EDITS
    }

    const val SANDBOX_READ_ONLY = "read-only"
    const val SANDBOX_WORKSPACE = "workspace-write"
    const val SANDBOX_FULL = "danger-full-access"
}
