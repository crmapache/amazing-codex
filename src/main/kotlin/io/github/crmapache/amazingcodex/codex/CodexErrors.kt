package io.github.crmapache.amazingcodex.codex

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Reading Codex's machine codes for a failure (`codexErrorInfo`) - never its sentences.
 *
 * The code is either a bare word (`unauthorized`, `usageLimitExceeded`) or an object holding the HTTP
 * status (`{"responseStreamConnectionFailed": {"httpStatusCode": 503}}`). The sentences beside it change
 * between versions and come in whatever language; the code is what decisions hang on.
 */
internal object CodexErrors {

    fun codeOf(info: JsonElement?): String = when (info) {
        is JsonPrimitive -> info.contentOrNull.orEmpty()
        is JsonObject -> info.keys.firstOrNull().orEmpty()
        else -> ""
    }

    fun isAuth(info: JsonElement?): Boolean = codeOf(info) == "unauthorized"

    fun isUsageLimit(info: JsonElement?): Boolean =
        codeOf(info) in setOf("usageLimitExceeded", "sessionBudgetExceeded")

    /**
     * The HTTP status behind a failure, for the retry card: the panel words a 429 and a 529 differently
     * from any other error, and Codex names those two as codes rather than statuses.
     */
    fun httpStatus(info: JsonElement?): Int? {
        (info as? JsonObject)?.values?.firstOrNull()?.let { inner ->
            AppServer.intOf((inner as? JsonObject)?.get("httpStatusCode"))?.let { return it }
        }

        return when (codeOf(info)) {
            "rateLimitExceeded", "usageLimitExceeded" -> 429
            "serverOverloaded" -> 529
            "unauthorized" -> 401
            "internalServerError" -> 500
            else -> null
        }
    }
}
