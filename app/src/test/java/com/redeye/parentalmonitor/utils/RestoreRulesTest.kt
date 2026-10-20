package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreRulesTest {
    @Test
    fun validatesTokenShape() {
        assertTrue(RestoreRules.isValidToken("123456789:" + "A".repeat(35)))
        assertFalse(RestoreRules.isValidToken(""))
        assertFalse(RestoreRules.isValidToken("bukan-token"))
        assertFalse(RestoreRules.isValidToken("123:pendek"))
    }

    @Test
    fun validatesChatIdShape() {
        assertTrue(RestoreRules.isValidChatId("123456789"))
        assertTrue(RestoreRules.isValidChatId("-1001234567890"))
        assertFalse(RestoreRules.isValidChatId("123"))
        assertFalse(RestoreRules.isValidChatId("@channel"))
    }

    @Test
    fun acceptsBackupFileNameCaseInsensitive() {
        assertTrue(RestoreRules.isBackupFileName("redeye.redeyebak"))
        assertTrue(RestoreRules.isBackupFileName("REDEYE.REDEYEBAK"))
        assertFalse(RestoreRules.isBackupFileName("redeye.zip"))
        assertFalse(RestoreRules.isBackupFileName(""))
    }

    @Test
    fun ownerFallbackPrefersBackupThenPrivateChatThenOldOwner() {
        assertEquals(42L, RestoreRules.ownerFallback(42L, "123456789", 7L))
        assertEquals(123456789L, RestoreRules.ownerFallback(0L, "123456789", 7L))
        assertEquals(7L, RestoreRules.ownerFallback(0L, "-1001234567890", 7L))
        assertEquals(0L, RestoreRules.ownerFallback(0L, "-1001234567890", 0L))
    }
}
