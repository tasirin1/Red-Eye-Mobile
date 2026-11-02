package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootClockTest {

    @Test
    fun bootWallAtMengurangkanElapsedDariWall() {
        assertEquals(1_699_999_700_000L, BootClock.bootWallAt(1_700_000_000_000L, 300_000L))
        assertEquals(0L, BootClock.bootWallAt(300_000L, 300_000L))
    }

    @Test
    fun bootYangSamaTerdeteksi() {
        assertTrue(BootClock.sameBoot(1_700_000_000_000L, 300_000L, 1_700_000_900_000L, 1_200_000L))
    }

    @Test
    fun geserJamDindingKecilDalamBootSamaTetapSatuBoot() {
        assertTrue(BootClock.sameBoot(1_700_000_000_000L, 300_000L, 1_700_000_330_000L, 600_000L))
    }

    @Test
    fun tepiToleransi() {
        assertTrue(BootClock.sameBoot(1_000_000_000L, 100_000L, 1_000_220_000L, 200_000L))
        assertFalse(BootClock.sameBoot(1_000_000_000L, 100_000L, 1_000_220_001L, 200_000L))
    }

    @Test
    fun bootLamaSingkatLaluMulaiTerlambatDiBootBaruBukanSatuBoot() {
        assertFalse(BootClock.sameBoot(1_700_000_000_000L, 300_000L, 1_700_003_600_000L, 1_500_000L))
    }

    @Test
    fun rebootSesudahUptimePanjangBukanSatuBoot() {
        assertFalse(BootClock.sameBoot(1_700_000_000_000L, 172_800_000L, 1_700_100_000_000L, 60_000L))
    }

    @Test
    fun penandaKosongTidakPernahSatuBoot() {
        assertFalse(BootClock.sameBoot(0L, 300_000L, 1_700_000_900_000L, 1_200_000L))
        assertFalse(BootClock.sameBoot(1_700_000_000_000L, 0L, 1_700_000_900_000L, 1_200_000L))
        assertFalse(BootClock.sameBoot(0L, 0L, 1_700_000_900_000L, 1_200_000L))
    }

    @Test
    fun koreksiJamBesarDenganElapsedKonsistenDianggapBootSama() {
        // Sesi mulai elapsed 2 jam; restore 30 menit kemudian dengan
        // jam dinding dikoreksi +10 menit (issue #831): sameBoot polos
        // menolak, jalur toleran menerima.
        val startWall = 1_700_000_000_000L
        val startElapsed = 7_200_000L
        assertFalse(BootClock.sameBoot(startWall, startElapsed, startWall + 2_400_000L, startElapsed + 1_800_000L))
        assertTrue(BootClock.sameBootOrClockShift(startWall, startElapsed, startWall + 2_400_000L, startElapsed + 1_800_000L))
    }

    @Test
    fun koreksiJamMundurDenganElapsedKonsistenDianggapBootSama() {
        val startWall = 1_700_000_000_000L
        val startElapsed = 7_200_000L
        assertTrue(BootClock.sameBootOrClockShift(startWall, startElapsed, startWall + 1_800_000L - 3_600_000L, startElapsed + 1_800_000L))
    }

    @Test
    fun rebootTetapDitolehJalurToleran() {
        // Elapsed tereset (negatif) dan rentang > 24 jam sama-sama
        // bukan koreksi jam.
        assertFalse(BootClock.sameBootOrClockShift(1_700_000_000_000L, 72_000_000L, 1_700_003_600_000L, 300_000L))
        assertFalse(BootClock.sameBootOrClockShift(1_700_000_000_000L, 3_600_000L, 1_700_093_600_000L, 3_600_000L + 90_000_000L))
        assertFalse(BootClock.sameBootOrClockShift(0L, 0L, 1_700_000_900_000L, 1_200_000L))
    }

    @Test
    fun rebootDenganJedaNyataBesarDitolakWalauElapsedKonsisten() {
        // #858: elapsed di boot baru kebetulan >= penanda, tetapi jeda
        // waktu nyata antar penanda dan restore 3-4 jam — geseran jam
        // tersirat melewati batas koreksi yang wajar.
        val w = 1_700_000_000_000L
        val e = 3_600_000L
        assertFalse(BootClock.sameBootOrClockShift(w, e, w + 4 * 3_600_000L, e + 1_800_000L))
        assertFalse(BootClock.sameBootOrClockShift(w, e, w + 3 * 3_600_000L, e))
        // Koreksi NTP besar 1,5 jam tetap diterima.
        assertTrue(BootClock.sameBootOrClockShift(w, e, w + 1_800_000L + 5_400_000L, e + 1_800_000L))
    }
}
