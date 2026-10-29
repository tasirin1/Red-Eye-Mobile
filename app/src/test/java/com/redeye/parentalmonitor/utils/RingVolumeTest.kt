package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RingVolumeTest {

    private val now = 1_700_000_000_000L
    private val maxAgeMs = 12 * 60 * 60_000L

    @Test
    fun tanpaVolumeTersimpanTidakDipulihkan() {
        assertFalse(RingVolume.isRestorable(-1, now - 1_000L, now, 3, 10))
    }

    @Test
    fun savedAtTidakPositifTidakDipulihkan() {
        assertFalse(RingVolume.isRestorable(5, 0L, now, 3, 10))
        assertFalse(RingVolume.isRestorable(5, -100L, now, 3, 10))
        assertFalse(RingVolume.isRestorable(0, 0L, now, 3, 10))
    }

    @Test
    fun umurDiDalamJendelaDipulihkan() {
        assertTrue(RingVolume.isRestorable(5, now, now, 3, 10))
        assertTrue(RingVolume.isRestorable(5, now - 3_600_000L, now, 3, 10))
        assertTrue(RingVolume.isRestorable(0, now - 1_000L, now, 3, 10))
    }

    @Test
    fun umurTepatBatasDuaBelasJamMasihDipulihkan() {
        assertTrue(RingVolume.isRestorable(5, now - maxAgeMs, now, 3, 10))
    }

    @Test
    fun umurLewatBatasTergantungKlausulVolumeKini() {
        assertFalse(RingVolume.isRestorable(5, now - maxAgeMs - 1L, now, 9, 10))
        assertTrue(RingVolume.isRestorable(5, now - maxAgeMs - 1L, now, 10, 10))
        assertTrue(RingVolume.isRestorable(5, now - maxAgeMs - 1L, now, 12, 10))
    }

    @Test
    fun jamMundurSavedAtMasaDepanMenghasilkanUmurNegatif() {
        assertFalse(RingVolume.isRestorable(5, now + 3_600_000L, now, 9, 10))
        assertTrue(RingVolume.isRestorable(5, now + 3_600_000L, now, 10, 10))
        assertTrue(RingVolume.isRestorable(5, now + 10 * maxAgeMs, now, 10, 10))
    }

    @Test
    fun klausulVolumeKiniMenuntutMaxVolumePositif() {
        assertFalse(RingVolume.isRestorable(5, now - maxAgeMs - 1L, now, 0, 0))
        assertFalse(RingVolume.isRestorable(5, now - maxAgeMs - 1L, now, 99, -1))
    }
}
