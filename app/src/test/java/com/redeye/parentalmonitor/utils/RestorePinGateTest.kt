package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RestorePinGateTest {
    private val now = 1_700_000_000_000L

    @Test
    fun lockedWithCustomDurationUsesGivenLockMs() {
        // #1251: varian berdurasian bebas (kunci global /login 10
        // menit). Nilai dibuktikan replikasi Python lebih dulu;
        // kasus kedua MEMBEDAKAN durasi 10 vs 15 menit pada jalur
        // jam monoton (jam dinding melompat +1 jam).
        val w = 1_700_000_000_000L
        val e = 1_000_000L
        val lockMs = 600_000L
        val until = w + lockMs
        assertTrue(RestorePinGate.locked(until, w + 3_600_000L, e, e + 300_000L, lockMs))
        assertFalse(RestorePinGate.locked(until, w + 3_600_000L, e, e + 600_000L, lockMs))
        assertTrue(RestorePinGate.locked(until, w + 1, 0L, 0L, lockMs))
        assertFalse(RestorePinGate.locked(until, w + lockMs, 0L, 0L, lockMs))
    }

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
    fun rebootPastOldElapsedAnchorDoesNotRelock() {
        // #957: kunci dipasang pada uptime 3 jam; perangkat reboot
        // dan 3 hari kemudian uptime baru melewati jangkar lama —
        // syarat elapsed lama terpenuhi lagi dan gerbang terkunci
        // palsu selama 15 menit walau tenggat dinding lama lewat.
        val wallUntil = now + RestorePinGate.LOCK_MS
        val elapsedAtLock = 10_800_000L
        val wallThreeDaysLater = now + 3 * 24 * 3_600_000L
        assertFalse(
            RestorePinGate.locked(wallUntil, wallThreeDaysLater, elapsedAtLock, elapsedAtLock + 300_000L)
        )
    }

    @Test
    fun negativeCountTreatedAsZero() {
        val r = RestorePinGate.registerFailure(-5, now)
        assertEquals(1, r.first)
        assertEquals(0L, r.second)
    }

    @Test
    fun attemptKeyDistinguishesBlobAndPin() {
        val a = RestorePinGate.attemptKey("blobSama", "1234")
        assertTrue(a.isNotEmpty())
        assertEquals(a, RestorePinGate.attemptKey("blobSama", "1234"))
        assertNotEquals(a, RestorePinGate.attemptKey("blobSama", "9999"))
        assertNotEquals(a, RestorePinGate.attemptKey("blobLain", "1234"))
    }

    @Test
    fun identicalAttemptIsNotCountedTwice() {
        // #873: berkas rusak + PIN benar dicoba berulang kali tidak
        // boleh mengunci; hanya percobaan berbeda yang terhitung.
        val key = RestorePinGate.attemptKey("blob", "1234")
        val first = RestorePinGate.registerFailure(0, now, "", key)
        assertEquals(1, first.first)
        assertTrue(first.third)
        val again = RestorePinGate.registerFailure(first.first, now, key, key)
        assertEquals(1, again.first)
        assertEquals(0L, again.second)
        assertFalse(again.third)
        // PIN berbeda atas berkas yang sama tetap terhitung.
        val other = RestorePinGate.registerFailure(again.first, now, key, RestorePinGate.attemptKey("blob", "9999"))
        assertEquals(2, other.first)
        assertTrue(other.third)
        val third = RestorePinGate.registerFailure(other.first, now, RestorePinGate.attemptKey("blob", "9999"), RestorePinGate.attemptKey("blob", "5555"))
        assertTrue(third.second > 0L)
        assertTrue(third.third)
    }
}
