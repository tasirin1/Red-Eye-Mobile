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
    fun wallClockAdvanceDoesNotUnlockWithinSameBoot() {
        // #687: kunci dipasang pada wall=now, elapsed=3_600_000 (boot sama).
        val wallUntil = now + RestorePinGate.LOCK_MS
        val elapsedAtLock = 3_600_000L
        // Jam dinding dimajukan 1 jam (melewati batas dinding), elapsed baru
        // berjalan 5 menit -> tetap terkunci oleh jam monoton.
        assertTrue(
            RestorePinGate.locked(wallUntil, now + 3_600_000L, elapsedAtLock, elapsedAtLock + 300_000L)
        )
        // Elapsed mencapai 15 menit -> terbuka walau jam dinding dimundurkan.
        assertFalse(
            RestorePinGate.locked(wallUntil, now - 3_600_000L, elapsedAtLock, elapsedAtLock + RestorePinGate.LOCK_MS)
        )
        // Sesudah reboot (elapsed mengulang ke 10 detik < elapsedAtLock):
        // keputusan memakai jam dinding.
        assertTrue(RestorePinGate.locked(wallUntil, now + 60_000L, elapsedAtLock, 10_000L))
        assertFalse(RestorePinGate.locked(wallUntil, now + RestorePinGate.LOCK_MS, elapsedAtLock, 10_000L))
    }

    @Test
    fun negativeCountTreatedAsZero() {
        val r = RestorePinGate.registerFailure(-5, now)
        assertEquals(1, r.first)
        assertEquals(0L, r.second)
    }
}
