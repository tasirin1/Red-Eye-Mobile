package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NumericArgTest {

    @Test
    fun angkaPolosDanBersatuan() {
        assertEquals(500, NumericArg.parse("500", NumericArg.Satuan.METER))
        assertEquals(500, NumericArg.parse("500 meter", NumericArg.Satuan.METER))
        assertEquals(500, NumericArg.parse("500 m", NumericArg.Satuan.METER))
        assertEquals(500, NumericArg.parse("500meter", NumericArg.Satuan.METER))
        assertEquals(30, NumericArg.parse("30 detik", NumericArg.Satuan.DETIK))
        assertEquals(30, NumericArg.parse("30detik", NumericArg.Satuan.DETIK))
        assertEquals(5, NumericArg.parse(" 5 menit ", NumericArg.Satuan.MENIT))
        assertEquals(6, NumericArg.parse("6 JAM", NumericArg.Satuan.JAM))
    }

    @Test
    fun satuanSilangDikonversi() {
        // Keputusan owner atas issue #776: satuan pengguna dikonversi
        // ke satuan bawaan perintah, bukan lagi hiasan.
        assertEquals(60, NumericArg.parse("1 jam", NumericArg.Satuan.MENIT))
        assertEquals(1, NumericArg.parse("30 menit", NumericArg.Satuan.JAM))
        assertEquals(2, NumericArg.parse("90 menit", NumericArg.Satuan.JAM))
        assertEquals(2, NumericArg.parse("120 detik", NumericArg.Satuan.MENIT))
        assertEquals(1800, NumericArg.parse("30 menit", NumericArg.Satuan.DETIK))
        assertEquals(7200, NumericArg.parse("2 jam", NumericArg.Satuan.DETIK))
        assertEquals(3600, NumericArg.parse("1 jam", NumericArg.Satuan.DETIK))
        // Pembulatan setengah ke atas ke satuan bawaan terdekat.
        assertEquals(2, NumericArg.parse("90 detik", NumericArg.Satuan.MENIT))
        assertEquals(1, NumericArg.parse("30 detik", NumericArg.Satuan.MENIT))
        assertEquals(0, NumericArg.parse("20 detik", NumericArg.Satuan.MENIT))
        assertEquals(0, NumericArg.parse("1 menit", NumericArg.Satuan.JAM))
    }

    @Test
    fun satuanKeluargaLainDitolak() {
        assertNull(NumericArg.parse("5 meter", NumericArg.Satuan.DETIK))
        assertNull(NumericArg.parse("5 detik", NumericArg.Satuan.METER))
        assertNull(NumericArg.parse("500 jam", NumericArg.Satuan.METER))
    }

    @Test
    fun bentukLainDitolak() {
        assertNull(NumericArg.parse("", NumericArg.Satuan.MENIT))
        assertNull(NumericArg.parse("abc", NumericArg.Satuan.MENIT))
        assertNull(NumericArg.parse("500 abc", NumericArg.Satuan.MENIT))
        assertNull(NumericArg.parse("500 meter ekstra", NumericArg.Satuan.METER))
        assertNull(NumericArg.parse("5.5", NumericArg.Satuan.MENIT))
        assertNull(NumericArg.parse("-5", NumericArg.Satuan.MENIT))
        assertNull(NumericArg.parse("meter", NumericArg.Satuan.METER))
        assertNull(NumericArg.parse("999999999999 jam", NumericArg.Satuan.DETIK))
    }
}
