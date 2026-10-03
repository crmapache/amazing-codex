package io.github.crmapache.amazingcodex.codex

import kotlinx.serialization.json.JsonObject

/**
 * A question about an account asked without a conversation - its limits, its model list - in that
 * account's own environment (see CodexOneShot.control).
 *
 * The callers still name what they want by the control names they grew up with (`get_usage`,
 * `list_models`), and the answer comes back in the shapes they read (see CodexShapes).
 */
internal object CodexControlPing {

    fun request(
        workingDirectory: String?,
        subtype: String,
        /** Whose account to ask about. Empty is Codex's ordinary sign-in. */
        accountId: String = "",
        /**
         * Kept for the callers: on the Claude side a usage question needed a config directory of its own,
         * or it answered out of a cache shared by every account. Codex reads the limits from the server
         * for the credential it holds, so there is nothing to borrow and nothing to isolate.
         */
        @Suppress("UNUSED_PARAMETER") isolated: Boolean = false,
        onResult: (JsonObject) -> Unit,
        onError: (String) -> Unit,
    ) = CodexOneShot.control(workingDirectory, subtype, accountId, onResult, onError)
}
