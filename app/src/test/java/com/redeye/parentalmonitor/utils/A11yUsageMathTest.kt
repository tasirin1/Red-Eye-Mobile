package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class A11yUsageMathTest {

    private val hour = 3_600_000L
    private var zonaAsal: java.util.TimeZone? = null

    @org.junit.Before
    fun pakaiZonaUtc() {
        zonaAsal = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
    }

    @org.junit.After
    fun kembalikanZona() {
        java.util.TimeZone.setDefault(zonaAsal)
    }

    @Test
    fun jamLokalMengikutiOffset() {
        assertEquals(0, A11yUsageMath.localHour(0L, 0))
        assertEquals(7, A11yUsageMath.localHour(0L, 7 * hour.toInt()))
        assertEquals(23, A11yUsageMath.localHour(0L, -hour.toInt()))
    }

    @Test
    fun malamMelewatiTengahMalam() {
        assertEquals(8 * hour, A11yUsageMath.nightOverlapMs(22 * hour, 30 * hour, 0))
    }

    @Test
    fun malamHanyaMenghitungBagianMalam() {
        assertEquals(30 * 60_000L, A11yUsageMath.nightOverlapMs(21L * hour + 30 * 60_000L, 22L * hour + 30 * 60_000L, 0))
        assertEquals(30 * 60_000L, A11yUsageMath.nightOverlapMs(5L * hour + 30 * 60_000L, 6L * hour + 30 * 60_000L, 0))
    }

    @Test
    fun siangTidakDihitung() {
        assertEquals(0L, A11yUsageMath.nightOverlapMs(12 * hour, 13 * hour, 0))
    }

    @Test
    fun rentangKosongAtauTerbalikAman() {
        assertEquals(0L, A11yUsageMath.nightOverlapMs(10 * hour, 10 * hour, 0))
        assertEquals(0L, A11yUsageMath.nightOverlapMs(11 * hour, 10 * hour, 0))
    }

    @Test
    fun awalHariMengikutiTanggalYangSama() {
        assertEquals(1_793_232_000_000L, A11yUsageMath.dayStartWallMillis(1_793_281_530_123L))
        assertEquals(1_793_232_000_000L, A11yUsageMath.dayStartWallMillis(1_793_318_399_999L))
    }

    @Test
    fun awalHariTepatTengahMalamMengembalikanDirinya() {
        assertEquals(1_793_232_000_000L, A11yUsageMath.dayStartWallMillis(1_793_232_000_000L))
        assertEquals(1_793_232_000_000L, A11yUsageMath.dayStartWallMillis(1_793_232_000_001L))
    }

    @Test
    fun awalHariSebelumTengahMalamMundurKeHariSebelumnya() {
        assertEquals(1_793_145_600_000L, A11yUsageMath.dayStartWallMillis(1_793_231_999_999L))
    }

    @Test
    fun batasBelahMemilihTengahMalamPertama() {
        val day0 = 1_793_232_000_000L
        val hour = 3_600_000L
        val day = 86_400_000L
        assertEquals(day0 + day, A11yUsageMath.splitBoundaryWallMillis(day0 + 23 * hour, day0 + day))
        assertEquals(day0 + day, A11yUsageMath.splitBoundaryWallMillis(day0 + 23 * hour, day0 + 2 * day))
        assertEquals(day0 + day, A11yUsageMath.splitBoundaryWallMillis(day0 + 1_800_000L, day0 + 2 * day))
        assertEquals(day0 + day, A11yUsageMath.splitBoundaryWallMillis(day0, day0 + day))
    }

    @Test
    fun awalHariNilaiEkstremAman() {
        assertEquals(0L, A11yUsageMath.dayStartWallMillis(0L))
        assertEquals(-86_400_000L, A11yUsageMath.dayStartWallMillis(-1L))
        assertEquals(-86_400_000L, A11yUsageMath.dayStartWallMillis(-86_400_000L))
    }

    @Test
    fun awalKemarinSamaDenganAwalHariKurangSehariDiZonaTanpaDst() {
        assertEquals(1_793_145_600_000L, A11yUsageMath.previousDayStartWallMillis(1_793_281_530_123L))
        assertEquals(1_793_145_600_000L, A11yUsageMath.previousDayStartWallMillis(1_793_232_000_000L))
        assertEquals(1_793_059_200_000L, A11yUsageMath.previousDayStartWallMillis(1_793_231_999_999L))
    }

    @Test
    fun awalKemarinMengikutiKalenderPadaTransisiDst() {
        val original = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
            assertEquals(1_772_946_000_000L, A11yUsageMath.previousDayStartWallMillis(1_773_084_600_000L))
            assertEquals(1_793_505_600_000L, A11yUsageMath.previousDayStartWallMillis(1_793_628_900_000L))
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }

    @Test
    fun awalKemarinPadaJendelaSesudahSpringForward() {
        val original = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("America/New_York"))
            assertEquals(1_772_946_000_000L, A11yUsageMath.previousDayStartWallMillis(1_773_028_800_000L))
            assertEquals(1_772_946_000_000L, A11yUsageMath.previousDayStartWallMillis(1_773_030_600_000L))
            assertEquals(1_772_946_000_000L, A11yUsageMath.previousDayStartWallMillis(1_773_032_399_999L))
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }
}
