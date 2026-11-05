package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class A11yUsageMathTest {

    private val hour = 3_600_000L
    // Zona diteruskan eksplisit (issue #1619): uji tak
    // lagi mengubah zona bawaan global proses, jadi
    // hasil tak bergantung urutan jalan/rerun parsial.
    private val utc = java.util.TimeZone.getTimeZone("UTC")
    private val newYork = java.util.TimeZone.getTimeZone("America/New_York")

    @Test
    fun jamLokalMengikutiOffset() {
        assertEquals(0, A11yUsageMath.localHour(0L, 0))
        assertEquals(7, A11yUsageMath.localHour(0L, 7 * hour.toInt()))
        assertEquals(23, A11yUsageMath.localHour(0L, -hour.toInt()))
    }

    @Test
    fun malamMelewatiTengahMalam() {
        assertEquals(8 * hour, A11yUsageMath.nightOverlapMs(22 * hour, 30 * hour) { 0 })
    }

    @Test
    fun malamAkhirHanyaMenghitungBagian22Sampai24() {
        // Nilai harapan dibuktikan porting 1:1 logika
        // jalannya ke Python (issue #1607).
        assertEquals(2 * hour, A11yUsageMath.nightLateOverlapMs(22 * hour, 30 * hour) { 0 })
        assertEquals(30 * 60_000L, A11yUsageMath.nightLateOverlapMs(21L * hour + 30 * 60_000L, 22L * hour + 30 * 60_000L) { 0 })
        assertEquals(0L, A11yUsageMath.nightLateOverlapMs(5L * hour + 30 * 60_000L, 6L * hour + 30 * 60_000L) { 0 })
        assertEquals(0L, A11yUsageMath.nightLateOverlapMs(20 * hour, 22 * hour) { 0 })
        assertEquals(1 * hour, A11yUsageMath.nightLateOverlapMs(23 * hour, 25 * hour) { 0 })
    }

    @Test
    fun malamHanyaMenghitungBagianMalam() {
        assertEquals(30 * 60_000L, A11yUsageMath.nightOverlapMs(21L * hour + 30 * 60_000L, 22L * hour + 30 * 60_000L) { 0 })
        assertEquals(30 * 60_000L, A11yUsageMath.nightOverlapMs(5L * hour + 30 * 60_000L, 6L * hour + 30 * 60_000L) { 0 })
    }

    @Test
    fun malamMengikutiPerubahanOffsetDiTengahRentang() {
        val offsetAt: (Long) -> Int = { ts -> if (ts < 5 * hour) hour.toInt() else 0 }
        assertEquals(2 * hour, A11yUsageMath.nightOverlapMs(4 * hour, 7 * hour, offsetAt))
    }

    @Test
    fun siangTidakDihitung() {
        assertEquals(0L, A11yUsageMath.nightOverlapMs(12 * hour, 13 * hour) { 0 })
    }

    @Test
    fun rentangKosongAtauTerbalikAman() {
        assertEquals(0L, A11yUsageMath.nightOverlapMs(10 * hour, 10 * hour) { 0 })
        assertEquals(0L, A11yUsageMath.nightOverlapMs(11 * hour, 10 * hour) { 0 })
    }

    @Test
    fun awalHariMengikutiTanggalYangSama() {
        assertEquals(1_793_232_000_000L, A11yUsageMath.dayStartWallMillis(1_793_281_530_123L, utc))
        assertEquals(1_793_232_000_000L, A11yUsageMath.dayStartWallMillis(1_793_318_399_999L, utc))
    }

    @Test
    fun awalHariTepatTengahMalamMengembalikanDirinya() {
        assertEquals(1_793_232_000_000L, A11yUsageMath.dayStartWallMillis(1_793_232_000_000L, utc))
        assertEquals(1_793_232_000_000L, A11yUsageMath.dayStartWallMillis(1_793_232_000_001L, utc))
    }

    @Test
    fun awalHariSebelumTengahMalamMundurKeHariSebelumnya() {
        assertEquals(1_793_145_600_000L, A11yUsageMath.dayStartWallMillis(1_793_231_999_999L, utc))
    }

    @Test
    fun batasBelahMemilihTengahMalamPertama() {
        val day0 = 1_793_232_000_000L
        val hour = 3_600_000L
        val day = 86_400_000L
        assertEquals(day0 + day, A11yUsageMath.splitBoundaryWallMillis(day0 + 23 * hour, day0 + day, utc))
        assertEquals(day0 + day, A11yUsageMath.splitBoundaryWallMillis(day0 + 23 * hour, day0 + 2 * day, utc))
        assertEquals(day0 + day, A11yUsageMath.splitBoundaryWallMillis(day0 + 1_800_000L, day0 + 2 * day, utc))
        assertEquals(day0 + day, A11yUsageMath.splitBoundaryWallMillis(day0, day0 + day, utc))
    }

    @Test
    fun batasBelahSemuaMengumpulkanSetiapTengahMalam() {
        // #717: sesi yang membentang >=2 tengah malam harus dibelah di SETIAP
        // tengah malam, bukan hanya yang pertama — hari di tengah tak hilang.
        val day0 = 1_793_232_000_000L
        val hour = 3_600_000L
        val day = 86_400_000L
        assertEquals(
            listOf(day0 + day, day0 + 2 * day),
            A11yUsageMath.splitBoundariesWallMillis(day0 + 23 * hour, day0 + 2 * day, utc)
        )
        assertEquals(
            listOf(day0 + day, day0 + 2 * day, day0 + 3 * day),
            A11yUsageMath.splitBoundariesWallMillis(day0 + 8 * hour, day0 + 3 * day, utc)
        )
        // Satu tengah malam: sama dengan splitBoundaryWallMillis tunggal.
        assertEquals(
            listOf(day0 + day),
            A11yUsageMath.splitBoundariesWallMillis(day0 + 23 * hour, day0 + day, utc)
        )
    }

    @Test
    fun awalHariNilaiEkstremAman() {
        assertEquals(0L, A11yUsageMath.dayStartWallMillis(0L, utc))
        assertEquals(-86_400_000L, A11yUsageMath.dayStartWallMillis(-1L, utc))
        assertEquals(-86_400_000L, A11yUsageMath.dayStartWallMillis(-86_400_000L, utc))
    }

    @Test
    fun awalKemarinSamaDenganAwalHariKurangSehariDiZonaTanpaDst() {
        assertEquals(1_793_145_600_000L, A11yUsageMath.previousDayStartWallMillis(1_793_281_530_123L, utc))
        assertEquals(1_793_145_600_000L, A11yUsageMath.previousDayStartWallMillis(1_793_232_000_000L, utc))
        assertEquals(1_793_059_200_000L, A11yUsageMath.previousDayStartWallMillis(1_793_231_999_999L, utc))
    }

    @Test
    fun awalKemarinMengikutiKalenderPadaTransisiDst() {
        assertEquals(1_772_946_000_000L, A11yUsageMath.previousDayStartWallMillis(1_773_084_600_000L, newYork))
        assertEquals(1_793_505_600_000L, A11yUsageMath.previousDayStartWallMillis(1_793_628_900_000L, newYork))
    }

    @Test
    fun awalKemarinPadaJendelaSesudahSpringForward() {
        assertEquals(1_772_946_000_000L, A11yUsageMath.previousDayStartWallMillis(1_773_028_800_000L, newYork))
        assertEquals(1_772_946_000_000L, A11yUsageMath.previousDayStartWallMillis(1_773_030_600_000L, newYork))
        assertEquals(1_772_946_000_000L, A11yUsageMath.previousDayStartWallMillis(1_773_032_399_999L, newYork))
    }
}
