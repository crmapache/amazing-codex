package io.github.crmapache.amazingcodex.codex.accounts

import io.github.crmapache.amazingcodex.codex.HostOs
import java.io.File
import java.security.MessageDigest
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
 * whoever signed in last, which is what the Claude side of this code had to work around with one-off
 * config directories and answers dated by when a profile was fetched. Here the file IS the credential:
 * whoever it names is who a turn in that drawer runs as, at the moment it is read.
 *
 * A credential kept in the system keyring instead (Codex's `cli_auth_credentials_store`) leaves no file;
 * then this answers with nobody, and every caller treats nobody as "would not say" - which never deletes
 * or re-files anything (see CodexAccounts.Holds).
 */
internal object AccountIdentity {

    data class Who(
        val email: String,
        val orgUuid: String,
        val orgName: String,
        /** How the credential signs in - [CHATGPT] or [API_KEY] - or empty when there is none to read. */
        val method: String = "",
    ) {

        val isNamed: Boolean get() = email.isNotEmpty()

        /** Which account this is, in the terms records are compared by (see AccountStore.keyOf). */
        val key: String get() = AccountStore.keyOf(email, orgUuid)
    }

    /**
     * An answer together with the moment it was learned.
     *
     * The moment is the read itself: the credential is on this disk and says who it is the instant it is
     * read, so an answer is never older than the question that asked for it (see AccountTwin, which needs
     * to know that before it deletes anything).
     */
    data class Probed(val who: Who, val at: Long)

    /** The ordinary home's credential - the account a person signed in to with plain `codex login`. */
    fun configFile(): File = File(HostOs.configDirectory(), AUTH_FILE)

    fun current(): Who = read(configFile())

    /** The credential in an account's own home. */
    fun ofDrawer(storeDir: String): Who = read(File(storeDir, AUTH_FILE))

    /** The answer in this file dated now, or null when there is no file to answer with. */
    fun probe(file: File, now: Long = System.currentTimeMillis()): Probed? =
        if (file.isFile) Probed(read(file), now) else null

    /** [probe] for an account's own home, or for the ordinary one when [storeDir] is null. */
    fun probeDrawer(storeDir: String?): Probed? = probe(storeDir?.let { File(it, AUTH_FILE) } ?: configFile())

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
            return Who(email = email, orgUuid = workspace, orgName = plan, method = CHATGPT)
        }

        // An API key has no address. On screen it is named by its last characters, which is how a person
        // tells their keys apart anyway - but it is FILED by a fingerprint of the whole key: two keys can
        // share their last four characters, and a filing that cannot tell them apart would take a sign-in
        // with the second for a repeated sign-in with the first and replace its drawer.
        val key = (auth["OPENAI_API_KEY"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        if (key.length >= KEY_TAIL) {
            return Who(email = "API key …${key.takeLast(KEY_TAIL)}", orgUuid = "key-${fingerprint(key)}", orgName = "", method = API_KEY)
        }

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

    /** Enough of a key's hash to tell keys apart in a record, and nothing that leads back to the key. */
    private fun fingerprint(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .take(FINGERPRINT_BYTES).joinToString("") { "%02x".format(it) }

    /** Codex's own words for the two ways in - the values its `forced_login_method` takes. */
    const val CHATGPT = "chatgpt"
    const val API_KEY = "api"

    private const val AUTH_FILE = "auth.json"
    private const val KEY_TAIL = 4
    private const val FINGERPRINT_BYTES = 8
}
