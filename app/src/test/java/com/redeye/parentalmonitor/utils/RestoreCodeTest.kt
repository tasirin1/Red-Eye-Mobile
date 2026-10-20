package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestoreCodeTest {
    private val secret = "00".repeat(32)
    private val now = 1_700_000_000_000L

    @Test
    fun currentCodeIsSixDigitsAndStable() {
        val code = RestoreCode.current(secret, now)
        assertEquals(6, code.length)
        assertTrue(code.all { it in '0'..'9' })
        assertEquals(code, RestoreCode.current(secret, now))
    }

    @Test
    fun nextWindowStillProducesSixDigits() {
        val later = RestoreCode.current(secret, now + RestoreCode.WINDOW_MS)
        assertEquals(6, later.length)
        assertTrue(later.all { it in '0'..'9' })
    }

    @Test
    fun acceptsCurrentAndPreviousWindowOnly() {
        assertTrue(RestoreCode.isValid(secret, RestoreCode.current(secret, now), now))
        assertTrue(RestoreCode.isValid(secret, RestoreCode.current(secret, now - RestoreCode.WINDOW_MS), now))
        assertFalse(RestoreCode.isValid(secret, RestoreCode.current(secret, now + RestoreCode.WINDOW_MS), now))
        assertFalse(RestoreCode.isValid(secret, "12345", now))
        assertFalse(RestoreCode.isValid(secret, "abcdef", now))
    }

    @Test
    fun windowEndMovesToNextBoundary() {
        val aligned = now - (now % RestoreCode.WINDOW_MS)
        assertEquals(aligned + RestoreCode.WINDOW_MS, RestoreCode.windowEnd(aligned))
        assertEquals(aligned + RestoreCode.WINDOW_MS, RestoreCode.windowEnd(aligned + 1))
    }

    @Test
    fun invalidSecretReturnsEmptyCode() {
        assertEquals("", RestoreCode.current("zz", now))
        assertFalse(RestoreCode.isValid("zz", "123456", now))
    }
}
