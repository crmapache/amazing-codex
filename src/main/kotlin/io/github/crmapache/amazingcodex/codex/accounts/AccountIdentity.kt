package io.github.crmapache.amazingcodex.codex.accounts

import io.github.crmapache.amazingcodex.codex.HostOs
import java.io.File
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Who a Codex credential belongs to, read off the credential itself.
 *
 * Codex keeps a ChatGPT sign-in in `auth.json` inside its home: the tokens, the workspace the sign-in is
 * for (`account_id`), and an id token whose claims name the address. Each account has its own home (see
 * AccountDrawer), so each account's identity is in its own file - there is no shared profile naming
 * whoever signed in last, which is what the Claude side of this code had to work around.
 *
 * A credential kept in the system keyring instead (Codex's `cli_auth_credentials_store`) leaves no file;
 * then this answers with nobody, and the callers fall back on what Codex itself says (see CodexAuth).
 */
internal object AccountIdentity {

    data class Who(val email: String, val orgUuid: String, val orgName: String) {

        val isNamed: Boolean get() = email.isNotEmpty()
    }

    /** The ordinary home's credential - the account a person signed in to with plain `codex login`. */
    fun configFile(): File = File(HostOs.configDirectory(), AUTH_FILE)

    fun current(): Who = read(configFile())

    /** The credential in an account's own home. */
    fun ofDrawer(storeDir: String): Who = read(File(storeDir, AUTH_FILE))

    fun read(file: File): Who {
        val blank = Who("", "", "")
        val text = runCatching { file.readText() }.getOrNull() ?: return blank
        val auth = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return blank

        val tokens = auth["tokens"] as? JsonObject
        if (tokens != null) {
            val claims = claimsOf((tokens["id_token"] as? JsonPrimitive)?.contentOrNull)
            val openai = claims?.get("https://api.openai.com/auth") as? JsonObject
            val email = (claims?.get("email") as? JsonPrimitive)?.contentOrNull.orEmpty()
            val workspace = (tokens["account_id"] as? JsonPrimitive)?.contentOrNull
                ?: (openai?.get("chatgpt_account_id") as? JsonPrimitive)?.contentOrNull
                ?: ""
            val plan = (openai?.get("chatgpt_plan_type") as? JsonPrimitive)?.contentOrNull.orEmpty()
            return Who(email = email, orgUuid = workspace, orgName = plan)
        }

        // An API key has no address; it is named by its last characters, which is how a person tells
        // their keys apart anyway.
        val key = (auth["OPENAI_API_KEY"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (key.length >= KEY_TAIL) return Who(email = "API key …${key.takeLast(KEY_TAIL)}", orgUuid = "key-${key.takeLast(KEY_TAIL)}", orgName = "")

        return blank
    }

    /** The claims of a JWT, unverified - they are only read to name the account, never trusted for access. */
    private fun claimsOf(token: String?): JsonObject? {
        val payload = token?.split('.')?.getOrNull(1) ?: return null
        return runCatching {
            val bytes = Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '='))
            Json.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        }.getOrNull()
    }

    private const val AUTH_FILE = "auth.json"
    private const val KEY_TAIL = 4
}
