package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Nilai harapan dibuktikan porting Python 1:1 lebih dulu
// (kasus yang sama persis, 2026-11-03). Issue #1043:
// logika watchdog ini keluarga yang sama dengan temuan
// waktu audit-94/95 tetapi tak punya satu pun uji.
class LivenessTest {

    @Test fun intervalWaspadaMenangAtasLayar() {
        assertEquals(180_000L, Liveness.intervalFor(urgent = true, screenOn = true))
        assertEquals(180_000L, Liveness.intervalFor(urgent = true, screenOn = false))
    }

    @Test fun intervalLayarNyalaDanMati() {
        assertEquals(900_000L, Liveness.intervalFor(urgent = false, screenOn = true))
        assertEquals(600_000L, Liveness.intervalFor(urgent = false, screenOn = false))
    }

    @Test fun laporanJatuhTempo() {
        val cd = 6 * 60 * 60_000L
        assertTrue(Liveness.reportDue(0L, 1000L))
        assertTrue(Liveness.reportDue(1000L, 500L))
        assertTrue(Liveness.reportDue(1000L, 1000L + cd))
        assertFalse(Liveness.reportDue(1000L, 1000L + cd - 1L))
        assertFalse(Liveness.reportDue(1000L, 1000L))
    }

    @Test fun teksCelahMenit() {
        assertEquals("1 menit", Liveness.gapText(0L))
        assertEquals("1 menit", Liveness.gapText(59_999L))
        assertEquals("1 menit", Liveness.gapText(60_000L))
        assertEquals("1 menit", Liveness.gapText(119_999L))
        assertEquals("2 menit", Liveness.gapText(120_000L))
        assertEquals("59 menit", Liveness.gapText(3_599_999L))
        assertEquals("1 menit", Liveness.gapText(-5_000L))
    }

    @Test fun teksCelahJam() {
        assertEquals("1 jam 0 menit", Liveness.gapText(3_600_000L))
        assertEquals("1 jam 31 menit", Liveness.gapText(5_460_000L))
        assertEquals("2 jam 0 menit", Liveness.gapText(7_200_000L))
    }
}
