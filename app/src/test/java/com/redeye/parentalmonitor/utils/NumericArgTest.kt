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
        // Issue #878: masukan positif yang membulat ke 0 DITOLAK —
        // 0 berarti mati/manual di heartbeat & photointerval, jadi
        // durasi pendek tak boleh mematikan fitur dengan senyap.
        assertNull(NumericArg.parse("20 detik", NumericArg.Satuan.MENIT))
        assertNull(NumericArg.parse("1 menit", NumericArg.Satuan.JAM))
        assertNull(NumericArg.parse("15 menit", NumericArg.Satuan.JAM))
        // Angka 0 eksplisit tetap sah (mematikan dengan sengaja).
        assertEquals(0, NumericArg.parse("0", NumericArg.Satuan.JAM))
        assertEquals(0, NumericArg.parse("0 detik", NumericArg.Satuan.MENIT))
    }

    @Test
    fun angkaRaksasaTidakMeluap() {
        // Issue #880: perkalian Long yang meluap sebelumnya
        // membungkus jadi nilai kecil yang lolos rentang handler.
        assertNull(NumericArg.parse("5124095576030432 jam", NumericArg.Satuan.MENIT))
        assertNull(NumericArg.parse("999999999999999999 jam", NumericArg.Satuan.JAM))
        assertNull(NumericArg.parse("999999999999999999 detik", NumericArg.Satuan.DETIK))
        assertNull(NumericArg.parse("1000000 jam", NumericArg.Satuan.DETIK))
        // Kontrol: besar tapi sah tetap diterima.
        assertEquals(1800000000, NumericArg.parse("500000 jam", NumericArg.Satuan.DETIK))
        // Issue #899: penjumlahan pembulatan dasar + f/2 di tepi
        // Long.MAX membungkus negatif dan lolos sebagai Int
        // negatif (terbukti porting int64: -2562047788015215).
        assertNull(NumericArg.parse("9223372036854775807 detik", NumericArg.Satuan.JAM))
        assertNull(NumericArg.parse("9223372036854775807 detik", NumericArg.Satuan.MENIT))
    }

    @Test
    fun jamDesimalLayar() {
        // Issue #885: "/screentime 6,5 jam" sebelumnya ditolak jalur slash
        // padahal jalur alami menerimanya (dibulatkan, #774/#790).
        assertEquals(7, NumericArg.parseDecimalHours("6,5 jam"))
        assertEquals(7, NumericArg.parseDecimalHours("6,5"))
        assertEquals(7, NumericArg.parseDecimalHours("6.5 jam"))
        assertEquals(7, NumericArg.parseDecimalHours("6,5jam"))
        assertEquals(1, NumericArg.parseDecimalHours("0,4 jam"))
        assertEquals(3, NumericArg.parseDecimalHours("2,5"))
        assertEquals(24, NumericArg.parseDecimalHours("24,4"))
        assertNull(NumericArg.parseDecimalHours("25,5 jam"))
        assertNull(NumericArg.parseDecimalHours("24,5"))
        assertNull(NumericArg.parseDecimalHours("0"))
        assertNull(NumericArg.parseDecimalHours("6,5 menit"))
        assertNull(NumericArg.parseDecimalHours("abc"))
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
