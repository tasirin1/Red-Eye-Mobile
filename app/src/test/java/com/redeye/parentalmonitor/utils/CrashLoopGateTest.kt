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
        // Fixture dipilih agar SATU-SATUNYA alasan false adalah
        // cek rentang: ketiga entri sah (dalam 1..now), jumlah
        // cukup, dan crash terakhir masih segar (1 menit) --
        // mutan modeAman tanpa cek rentang mengembalikan true
        // di sini (issue #1940; fixture lama berentri 0L gugur
        // oleh saringan jumlah sebelum rentang dinilai).
        val jurnal = listOf(75 * menit, 76 * menit, 99 * menit)
        assertFalse(CrashLoopGate.modeAman(jurnal, 100 * menit))
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
        // Daftar PERSIS (issue #1976, memperketat #1958):
        // asersi sampel tepi (ukuran <= plafon, ujung 59/11
        // ada, 10 tak ada) terbukti lewat port lolos untuk
        // mutan pembuang satu entri tengah dan mutan
        // berkompensasi (buang tengah + tahan yang
        // terpangkas). 61 entri masuk; yang bertahan tepat
        // 11..59 menit (49 entri) + entri baru 200 menit.
        assertEquals((11..59).map { it * menit } + 200 * menit, hasil)
        val basi = CrashLoopGate.catat(listOf(1L), 200 * menit + CrashLoopGate.SIMPAN_MS + 1)
        assertEquals(listOf(200 * menit + CrashLoopGate.SIMPAN_MS + 1), basi)
    }

    @Test
    fun parseTahanSampah() {
        assertEquals(emptyList<Long>(), CrashLoopGate.parse(""))
        assertEquals(listOf(5L, 10L), CrashLoopGate.parse("10\nabc\n-3\n5\n\n"))
        assertEquals("5\n10", CrashLoopGate.serialkan(listOf(5L, 10L)))
    }

    @Test
    fun modeAmanPivotMemakaiCrashKetigaTerakhir() {
        val menit = 60_000L
        val jurnal = listOf(80 * menit, 95 * menit, 99 * menit)
        assertFalse(CrashLoopGate.modeAman(jurnal, 100 * menit))
    }
}
