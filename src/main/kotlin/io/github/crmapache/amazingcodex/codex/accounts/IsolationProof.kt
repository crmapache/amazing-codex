package io.github.crmapache.amazingcodex.codex.accounts

import io.github.crmapache.amazingcodex.codex.CodexAuth
import io.github.crmapache.amazingcodex.codex.accounts.CodexAccounts.Capability

/**
 * What the isolation probe's answers prove, apart from the processes that give them (see
 * CodexAccounts.capability).
 *
 * The answers cost processes; the verdict is arithmetic, and it breaks silently in both directions. Too
 * strict, and a machine that has plainly proven the mechanism is refused the Add button - which is how
 * this was found in the Claude plugin this grew out of: "sign in first" beside two working accounts. Too lenient, and a second
 * sign-in lands in the drawer the account in use lives in and overwrites it. So it lives here, where
 * `IsolationProofTest` holds it without an IDE.
 */
internal object IsolationProof {

    /**
     * [plain] is the CLI asked with no drawer at all. [reference] is a sign-in known to be live - the
     * CLI's own when [plain] is signed in, otherwise a drawer added here that answers signed in - or null
     * when there is neither. [isolated] asks a drawer known to be empty; it is a lambda because it starts
     * a process the first two answers can make pointless, and it answers null when that drawer could not
     * even be named.
     */
    fun verdict(
        plain: CodexAuth.Status,
        reference: CodexAuth.Status?,
        isolated: () -> CodexAuth.Status?,
    ): Capability {
        // Nothing live anywhere: no answer can tell a drawer that was chosen from one that was ignored,
        // because both would be empty.
        if (reference == null || !reference.loggedIn) return Capability.NOT_SIGNED_IN

        // Both ways Codex signs in - ChatGPT and an API key stored by `codex login --with-api-key` - live
        // in the home's `auth.json`, so either one is a drawer's to hold. A sign-in of any other kind
        // (tokens handed to Codex from outside) is not, and a second account beside it would be a row that
        // cannot be switched to. A key in the IDE's environment is blanked for every drawer (see
        // AccountStore.OUTRANKING_VARIABLES); should one still outrank the drawer, the isolated run below catches it
        // by answering signed in where it should have answered nothing.
        if (reference.method.isNotEmpty() && reference.method !in DRAWER_METHODS) return Capability.API_KEY

        val empty = isolated() ?: return Capability.IGNORED

        val moved = !empty.loggedIn
        // Every answer given under a drawer must name the sessions folder the plain Codex names - the
        // drawer links to it, and the canonical path is what proves the history stayed shared. Present,
        // too: two absences compare equal.
        val folderStayed = plain.projectsDirectory.isNotEmpty() &&
            listOf(reference, empty).all { it.projectsDirectory == plain.projectsDirectory }

        return if (moved && folderStayed) Capability.SUPPORTED else Capability.IGNORED
    }

    /** The `account.type` values of `account/read` whose sign-in lives in the drawer's `auth.json`. */
    private val DRAWER_METHODS = setOf("chatgpt", "apiKey")
}
