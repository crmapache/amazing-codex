package io.github.crmapache.amazingcodex.codex.accounts

import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Who a Codex credential belongs to, read off `auth.json` in the account's own home: the address out of
 * the id token's claims, the workspace the sign-in is for, and for an API key its last characters.
 */
class AccountIdentityTest {

    private val home = Files.createTempDirectory("acx-identity").toFile().apply { deleteOnExit() }

    private fun jwt(claims: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return listOf("""{"alg":"RS256"}""", claims, "signature")
            .joinToString(".") { encoder.encodeToString(it.toByteArray()) }
    }

    private fun auth(text: String): File = File(home, "auth.json").apply { writeText(text) }

    @Test
    fun `a ChatGPT sign-in is named by its address and its workspace`() {
        val token = jwt("""{"email":"someone@example.com","https://api.openai.com/auth":{"chatgpt_account_id":"ws-from-claims","chatgpt_plan_type":"pro"}}""")
        val who = AccountIdentity.read(auth("""{"OPENAI_API_KEY":null,"tokens":{"id_token":"$token","access_token":"a","refresh_token":"r","account_id":"ws-1"},"last_refresh":"2026-09-19T10:00:00Z"}"""))

        assertEquals(AccountIdentity.Who(email = "someone@example.com", orgUuid = "ws-1", orgName = "pro", method = AccountIdentity.CHATGPT), who)
        assertTrue(who.isNamed)
    }

    // The workspace the token itself names is the answer when the file does not say it beside the token.
    @Test
    fun `the workspace falls back to the one the token names`() {
        val token = jwt("""{"email":"someone@example.com","https://api.openai.com/auth":{"chatgpt_account_id":"ws-from-claims"}}""")
        val who = AccountIdentity.read(auth("""{"tokens":{"id_token":"$token"}}"""))

        assertEquals("ws-from-claims", who.orgUuid)
        assertEquals("", who.orgName)
    }

    // A key has no address; it is named by its last characters, which is how a person tells keys apart.
    @Test
    fun `an API key is named by its tail`() {
        val who = AccountIdentity.read(auth("""{"OPENAI_API_KEY":"sk-proj-abcdefWXYZ"}"""))

        assertEquals("API key …WXYZ", who.email)
        // Filed by a fingerprint of the whole key, so two keys ending alike are two accounts.
        assertTrue(who.orgUuid.startsWith("key-") && who.orgUuid.length == "key-".length + 16, who.orgUuid)
        val twin = AccountIdentity.read(auth("""{"OPENAI_API_KEY":"sk-proj-zzzzzzWXYZ"}"""))
        assertEquals("API key …WXYZ", twin.email)
        assertTrue(twin.orgUuid != who.orgUuid)
        assertTrue(who.isNamed)
    }

    @Test
    fun `nothing readable names nobody`() {
        val nobody = AccountIdentity.Who("", "", "")

        assertEquals(nobody, AccountIdentity.read(File(home, "missing.json")))
        assertEquals(nobody, AccountIdentity.read(auth("not json")))
        assertEquals(nobody, AccountIdentity.read(auth("""{"OPENAI_API_KEY":"abc"}""")))
        assertEquals(nobody, AccountIdentity.read(auth("""{"OPENAI_API_KEY":null}""")))
        assertFalse(nobody.isNamed)
    }

    // A token whose claims cannot be read is still a sign-in: the workspace beside it is not lost.
    @Test
    fun `a token that cannot be read still leaves the workspace`() {
        val who = AccountIdentity.read(auth("""{"tokens":{"id_token":"not-a-jwt","account_id":"ws-2"}}"""))

        assertEquals(AccountIdentity.Who("", "ws-2", "", method = AccountIdentity.CHATGPT), who)
        assertFalse(who.isNamed)
    }

    @Test
    fun `an account's own home is read for its own credential`() {
        val drawer = Files.createTempDirectory("acx-drawer").toFile().apply { deleteOnExit() }
        File(drawer, "auth.json").writeText("""{"OPENAI_API_KEY":"sk-1234"}""")

        assertEquals("API key …1234", AccountIdentity.ofDrawer(drawer.absolutePath).email)
    }
}
