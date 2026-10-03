package io.github.crmapache.amazingcodex.codex.accounts

import io.github.crmapache.amazingcodex.codex.CodexAuth
import io.github.crmapache.amazingcodex.codex.accounts.CodexAccounts.Capability
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Whether this machine can keep two sign-ins apart, as proven by three answers from Codex.
 *
 * Both ways of getting it wrong are silent. Refused for no reason, the accounts screen hides its Add
 * button and says "sign in first" to somebody working on two accounts. Allowed without proof, a second
 * sign-in overwrites the account in use.
 */
class IsolationProofTest {

    private val folder = "/Users/someone/.codex/sessions"

    private fun signedIn(method: String = "chatgpt", projects: String = folder) =
        CodexAuth.Status(installed = true, loggedIn = true, method = method, projectsDirectory = projects)

    private fun signedOut(projects: String = folder) =
        CodexAuth.Status(installed = true, loggedIn = false, projectsDirectory = projects)

    private val notToBeAsked: () -> CodexAuth.Status? = { error("the empty drawer was asked for nothing") }

    @Test
    fun `Codex's own sign-in and an empty drawer that answers signed out prove it`() {
        val plain = signedIn()

        assertEquals(Capability.SUPPORTED, IsolationProof.verdict(plain, plain) { signedOut() })
    }

    // A key stored by `codex login --with-api-key` lives in the home's auth.json exactly as a ChatGPT
    // sign-in does, so it is a drawer's to hold.
    @Test
    fun `a stored API key is a sign-in a drawer holds`() {
        val plain = signedIn(method = "apiKey")

        assertEquals(Capability.SUPPORTED, IsolationProof.verdict(plain, plain) { signedOut() })
    }

    // A status that does not say how it signed in is not refused for that alone.
    @Test
    fun `a sign-in that does not name its kind is judged by the drawers`() {
        val plain = signedIn(method = "")

        assertEquals(Capability.SUPPORTED, IsolationProof.verdict(plain, plain) { signedOut() })
    }

    /**
     * The case this was written for: logged out of Codex's own sign-in, working on accounts added here.
     * The drawer answering signed in while plain Codex does not is the stronger proof, not a weaker
     * one - it can only happen if the variable chose the drawer.
     */
    @Test
    fun `an added drawer stands in for Codex's own sign-in when that one is signed out`() {
        val verdict = IsolationProof.verdict(signedOut(), signedIn()) { signedOut() }

        assertEquals(Capability.SUPPORTED, verdict)
    }

    @Test
    fun `with no live sign-in anywhere there is nothing to prove against`() {
        assertEquals(Capability.NOT_SIGNED_IN, IsolationProof.verdict(signedOut(), null, notToBeAsked))
        assertEquals(Capability.NOT_SIGNED_IN, IsolationProof.verdict(signedOut(), signedOut(), notToBeAsked))
    }

    // Tokens handed to Codex from outside are not in any drawer's auth.json: a second account beside
    // them would be a row that cannot be switched to.
    @Test
    fun `a sign-in no drawer can hold is said so without asking further`() {
        val plain = signedIn(method = "chatgptAuthTokens")

        assertEquals(Capability.API_KEY, IsolationProof.verdict(plain, plain, notToBeAsked))
    }

    @Test
    fun `an empty drawer that answers signed in means the variable was ignored`() {
        val plain = signedIn()

        assertEquals(Capability.IGNORED, IsolationProof.verdict(plain, plain) { signedIn() })
    }

    @Test
    fun `an empty drawer that could not be named proves nothing`() {
        val plain = signedIn()

        assertEquals(Capability.IGNORED, IsolationProof.verdict(plain, plain) { null })
    }

    /** The half that matters: a variable that moved the folder would split history, skills and settings. */
    @Test
    fun `a drawer that moves the folder is refused, whichever drawer shows it`() {
        val elsewhere = "/Users/someone/drawers/probe/sessions"

        assertEquals(Capability.IGNORED, IsolationProof.verdict(signedIn(), signedIn()) { signedOut(elsewhere) })
        assertEquals(Capability.IGNORED, IsolationProof.verdict(signedOut(), signedIn(projects = elsewhere)) { signedOut() })
    }

    @Test
    fun `a build that does not report the folder cannot pass by two absences comparing equal`() {
        val plain = signedIn(projects = "")

        assertEquals(Capability.IGNORED, IsolationProof.verdict(plain, plain) { signedOut(projects = "") })
    }
}
