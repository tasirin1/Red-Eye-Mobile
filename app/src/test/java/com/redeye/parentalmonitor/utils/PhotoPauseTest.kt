package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Issue #1028: inti hitung jeda foto (#939/#941) kini
// logika murni teruji. Nilai harapan dibuktikan dengan
// porting 1:1 ke Python sebelum asersi ditulis.
class PhotoPauseTest {

    private val wall = 1_700_000_000_000L
    private val elapsed = 5_000_000L

    @Test
    fun tanpaJeda() {
        val hasil = PhotoPause.effectiveUntil(0L, 0L, wall, elapsed)
        assertEquals(0L, hasil.first)
        assertNull(hasil.second)
    }

    @Test
    fun formatWarisanDimigrasi() {
        // Tersimpan elapsed 5000, kini elapsed 3000: sisa
        // 2000 ditempel ke dinding kini + ditulis ulang.
        val hasil = PhotoPause.effectiveUntil(5000L, 0L, wall, 3000L)
        assertEquals(1_700_000_002_000L, hasil.first)
        assertEquals(1_700_000_002_000L, hasil.second)
    }

    @Test
    fun formatWarisanLewatJadiNol() {
        val hasil = PhotoPause.effectiveUntil(2000L, 0L, wall, 3000L)
        assertEquals(0L, hasil.first)
        assertEquals(0L, hasil.second)
    }

    @Test
    fun jamMundurDiaturSisaElapsed() {
        // Dinding memberi 60 mnt, elapsed 30 mnt: yang
        // lebih kecil (elapsed) yang berlaku (#939).
        val hasil = PhotoPause.effectiveUntil(wall + 3_600_000L, elapsed + 1_800_000L, wall, elapsed)
        assertEquals(1_700_001_800_000L, hasil.first)
        assertNull(hasil.second)
    }

    @Test
    fun rebootDiaturSisaDinding() {
        // Cap elapsed dari uptime 10 hari, kini uptime
        // 60 dtk: sisa elapsed membengkak, sisa dinding
        // (60 mnt) yang membatasi (#941).
        val hasil = PhotoPause.effectiveUntil(wall + 3_600_000L, 864_000_000L, wall, 60_000L)
        assertEquals(1_700_003_600_000L, hasil.first)
        assertNull(hasil.second)
    }

    @Test
    fun elapsedLewatSelesai() {
        val hasil = PhotoPause.effectiveUntil(wall + 3_600_000L, elapsed - 1000L, wall, elapsed)
        assertEquals(0L, hasil.first)
        assertNull(hasil.second)
    }

    @Test
    fun dindingLewatSelesai() {
        val hasil = PhotoPause.effectiveUntil(wall - 1000L, elapsed + 60_000L, wall, elapsed)
        assertEquals(0L, hasil.first)
        assertNull(hasil.second)
    }

    @Test
    fun tenggatKejauhanDijepitDanDitulis() {
        val hasil = PhotoPause.effectiveUntil(wall + 600_000_000L, 0L, wall, elapsed)
        assertEquals(1_700_028_800_000L, hasil.first)
        assertEquals(1_700_028_800_000L, hasil.second)
    }

    @Test
    fun normalApaAdanya() {
        val hasil = PhotoPause.effectiveUntil(wall + 600_000L, elapsed + 600_000L, wall, elapsed)
        assertEquals(1_700_000_600_000L, hasil.first)
        assertNull(hasil.second)
    }
}
