package io.github.crmapache.amazingcodex.codex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** Decisions hang on Codex's machine codes for a failure (`codexErrorInfo`), never on its sentences. */
class CodexErrorsTest {

    private fun info(json: String): JsonElement = Json.parseToJsonElement(json)

    @Test
    fun `a code is a bare word or the one key of an object`() {
        assertEquals("unauthorized", CodexErrors.codeOf(JsonPrimitive("unauthorized")))
        assertEquals("responseStreamConnectionFailed", CodexErrors.codeOf(info("""{"responseStreamConnectionFailed":{"httpStatusCode":503}}""")))
        assertEquals("", CodexErrors.codeOf(null))
        assertEquals("", CodexErrors.codeOf(info("[1]")))
    }

    @Test
    fun `a failed sign-in and a spent limit are told apart from everything else`() {
        assertTrue(CodexErrors.isAuth(JsonPrimitive("unauthorized")))
        assertFalse(CodexErrors.isAuth(JsonPrimitive("usageLimitExceeded")))
        assertFalse(CodexErrors.isAuth(null))

        assertTrue(CodexErrors.isUsageLimit(JsonPrimitive("usageLimitExceeded")))
        assertTrue(CodexErrors.isUsageLimit(JsonPrimitive("sessionBudgetExceeded")))
        // A rate limit is a retry, not a spent plan.
        assertFalse(CodexErrors.isUsageLimit(JsonPrimitive("rateLimitExceeded")))
    }

    /** The retry card words a 429 and a 529 differently, and Codex names those two as codes. */
    @Test
    fun `the HTTP status is the object's own, or the one a code stands for`() {
        assertEquals(503, CodexErrors.httpStatus(info("""{"responseStreamConnectionFailed":{"httpStatusCode":503}}""")))
        assertEquals(429, CodexErrors.httpStatus(JsonPrimitive("rateLimitExceeded")))
        assertEquals(429, CodexErrors.httpStatus(JsonPrimitive("usageLimitExceeded")))
        assertEquals(529, CodexErrors.httpStatus(JsonPrimitive("serverOverloaded")))
        assertEquals(401, CodexErrors.httpStatus(JsonPrimitive("unauthorized")))
        assertEquals(500, CodexErrors.httpStatus(JsonPrimitive("internalServerError")))
        assertNull(CodexErrors.httpStatus(JsonPrimitive("contextWindowExceeded")))
        assertNull(CodexErrors.httpStatus(info("""{"responseStreamDisconnected":{"httpStatusCode":null}}""")))
        assertNull(CodexErrors.httpStatus(null))
    }
}
