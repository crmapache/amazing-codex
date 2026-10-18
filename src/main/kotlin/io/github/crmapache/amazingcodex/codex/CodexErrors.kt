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
        codeOf(info) in setOf("usageLimitExceeded", SESSION_BUDGET)

    /**
     * Codex's code for a session's own budget running out - a refusal like the limit's, but of this conversation
     * rather than of the account, and with no reset: it is over when somebody gives the session more (see
     * CodexStream.turnCompleted, CarryOns.endsOnAClock).
     */
    const val SESSION_BUDGET = "sessionBudgetExceeded"

    fun isSessionBudget(info: JsonElement?): Boolean = codeOf(info) == SESSION_BUDGET

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
            // A request the API would not take as sent - a parameter it refuses among them.
            "badRequest" -> 400
            "internalServerError" -> 500
            else -> null
        }
    }
}
