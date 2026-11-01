package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RestorePinGateTest {
    private val now = 1_700_000_000_000L

    @Test
    fun thirdConsecutiveFailureLocksFor15Minutes() {
        var count = 0
        var lock = 0L
        repeat(2) {
            val r = RestorePinGate.registerFailure(count, now)
            count = r.first
            lock = r.second
        }
        assertEquals(2, count)
        assertEquals(0L, lock)
        val r3 = RestorePinGate.registerFailure(count, now)
        assertEquals(0, r3.first)
        assertEquals(now + RestorePinGate.LOCK_MS, r3.second)
        assertTrue(RestorePinGate.locked(r3.second, now + 1))
        assertFalse(RestorePinGate.locked(r3.second, now + RestorePinGate.LOCK_MS))
    }

    @Test
    fun negativeCountTreatedAsZero() {
        val r = RestorePinGate.registerFailure(-5, now)
        assertEquals(1, r.first)
        assertEquals(0L, r.second)
    }
}
