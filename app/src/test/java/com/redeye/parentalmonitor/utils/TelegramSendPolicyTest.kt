package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class TelegramSendPolicyTest {

    private fun kind(isSuccessful: Boolean, ok: Boolean, code: Int, body: String?, migrated: Boolean = false) =
        TelegramSendPolicy.classify(isSuccessful, ok, code, body, migrated).kind

    @Test
    fun sentOnlyWhenSuccessfulAndOk() {
        assertEquals(TelegramSendPolicy.Kind.SENT, kind(true, true, 200, null))
        assertEquals(TelegramSendPolicy.Kind.REJECTED, kind(true, false, 200, null))
        assertEquals(TelegramSendPolicy.Kind.REJECTED, kind(false, true, 200, null))
    }

    @Test
    fun rateLimitedCarriesRetryAfter() {
        val withRetry = TelegramSendPolicy.classify(false, false, 429, "{\"ok\":false,\"parameters\":{\"retry_after\":42}}", false)
        assertEquals(TelegramSendPolicy.Kind.RATE_LIMITED, withRetry.kind)
        assertEquals(42L, withRetry.retryAfterSecs)
        val noRetry = TelegramSendPolicy.classify(false, false, 429, null, false)
        assertEquals(TelegramSendPolicy.Kind.RATE_LIMITED, noRetry.kind)
        assertEquals(5L, noRetry.retryAfterSecs)
        val clamped = TelegramSendPolicy.classify(false, false, 429, "{\"parameters\":{\"retry_after\":99999}}", false)
        assertEquals(3600L, clamped.retryAfterSecs)
    }

    @Test
    fun authFailedFor401And403() {
        assertEquals(TelegramSendPolicy.Kind.AUTH_FAILED, kind(false, false, 401, null))
        assertEquals(TelegramSendPolicy.Kind.AUTH_FAILED, kind(false, false, 403, null))
    }

    @Test
    fun badRequestSubclassificationOrder() {
        assertEquals(TelegramSendPolicy.Kind.MIGRATED, kind(false, false, 400, "chat not found", migrated = true))
        assertEquals(TelegramSendPolicy.Kind.CHAT_MISSING, kind(false, false, 400, "{\"description\":\"chat not found\"}"))
        assertEquals(TelegramSendPolicy.Kind.CHAT_MISSING, kind(false, false, 400, "chat not found; not enough rights"))
        assertEquals(TelegramSendPolicy.Kind.RIGHTS_LIMITED, kind(false, false, 400, "not enough rights to send messages"))
        assertEquals(TelegramSendPolicy.Kind.PARSE_ENTITIES, kind(false, false, 400, "Bad Request: can't parse entities"))
        assertEquals(TelegramSendPolicy.Kind.REJECTED, kind(false, false, 400, "some other error"))
        assertEquals(TelegramSendPolicy.Kind.REJECTED, kind(false, false, 400, null))
    }

    @Test
    fun transientAndRejected() {
        assertEquals(TelegramSendPolicy.Kind.TRANSIENT, kind(false, false, 408, null))
        assertEquals(TelegramSendPolicy.Kind.TRANSIENT, kind(false, false, 500, null))
        assertEquals(TelegramSendPolicy.Kind.TRANSIENT, kind(false, false, 502, null))
        assertEquals(TelegramSendPolicy.Kind.REJECTED, kind(false, false, 404, null))
    }
}
