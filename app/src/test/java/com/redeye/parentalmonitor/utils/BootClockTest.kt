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
}
