package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashLoopGateTest {

    private val menit = 60_000L

    @Test
    fun kosongDanSedikitTidakAman() {
        assertFalse(CrashLoopGate.modeAman(emptyList(), 100 * menit))
        assertFalse(CrashLoopGate.modeAman(listOf(90 * menit), 100 * menit))
        assertFalse(CrashLoopGate.modeAman(listOf(90 * menit, 95 * menit), 100 * menit))
    }

    @Test
    fun tigaBeruntunDalamSepuluhMenitAman() {
        val jurnal = listOf(90 * menit, 95 * menit, 99 * menit)
        assertTrue(CrashLoopGate.modeAman(jurnal, 100 * menit))
    }

    @Test
    fun tigaTersebarTidakAman() {
        // Tiga crash berjauhan bukan loop: tak boleh memicu.
        val jurnal = listOf(0L, 20 * menit, 25 * menit)
        assertFalse(CrashLoopGate.modeAman(jurnal, 26 * menit))
    }

    @Test
    fun amanBerakhirTigaPuluhMenitSesudahCrashTerakhir() {
        val jurnal = listOf(90 * menit, 95 * menit, 99 * menit)
        assertTrue(CrashLoopGate.modeAman(jurnal, 99 * menit + 30 * menit))
        assertFalse(CrashLoopGate.modeAman(jurnal, 99 * menit + 30 * menit + 1))
    }

    @Test
    fun gugusDiTengahJurnalPanjangTetapTerdeteksi() {
        // Crash lama yang tersebar tak menutupi gugus baru.
        val jurnal = listOf(0L, 50 * menit, 200 * menit, 204 * menit, 208 * menit)
        assertTrue(CrashLoopGate.modeAman(jurnal, 210 * menit))
    }

    @Test
    fun catatMemangkasYangTuaDanMembatasi() {
        val lama = (0 until 60).map { it * menit }
        val hasil = CrashLoopGate.catat(lama, 200 * menit)
        assertTrue(hasil.size <= CrashLoopGate.MAKS_ENTRI)
        assertEquals(200 * menit, hasil.last())
        // Entri yang masih dalam jendela simpan bertahan.
        assertTrue(hasil.contains(200 * menit - 5 * menit) || hasil.size == CrashLoopGate.MAKS_ENTRI)
        val basi = CrashLoopGate.catat(listOf(1L), 200 * menit + CrashLoopGate.SIMPAN_MS + 1)
        assertEquals(listOf(200 * menit + CrashLoopGate.SIMPAN_MS + 1), basi)
    }

    @Test
    fun parseTahanSampah() {
        assertEquals(emptyList<Long>(), CrashLoopGate.parse(""))
        assertEquals(listOf(5L, 10L), CrashLoopGate.parse("10\nabc\n-3\n5\n\n"))
        assertEquals("5\n10", CrashLoopGate.serialkan(listOf(5L, 10L)))
    }
}
