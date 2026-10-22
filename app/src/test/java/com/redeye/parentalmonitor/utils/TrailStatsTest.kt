package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailStatsTest {

    private val base = 1_700_000_000_000L

    @Test
    fun jarakMenjumlahSegmenBerurutan() {
        val pts = listOf(
            TrailStats.Point(base, -6.200000, 106.800000),
            TrailStats.Point(base + 60_000L, -6.201000, 106.800000),
            TrailStats.Point(base + 120_000L, -6.202000, 106.800000)
        )
        val meters = TrailStats.distanceMeters(pts)
        assertTrue("jarak ${meters}", meters in 215f..228f)
        assertTrue(TrailStats.kilometers(meters) in 0.21f..0.23f)
    }

    @Test
    fun celahPanjangTidakDihitung() {
        val pts = listOf(
            TrailStats.Point(base, -6.200000, 106.800000),
            TrailStats.Point(base + 60 * 60_000L, -6.300000, 106.800000)
        )
        assertEquals(0f, TrailStats.distanceMeters(pts, maxGapMs = 20 * 60_000L), 0.01f)
    }

    @Test
    fun diamTerlamaTerdeteksi() {
        val pts = ArrayList<TrailStats.Point>()
        for (i in 0..60) pts.add(TrailStats.Point(base + i * 60_000L, -6.200000, 106.800000))
        pts.add(TrailStats.Point(base + 61 * 60_000L, -6.250000, 106.800000))
        pts.add(TrailStats.Point(base + 62 * 60_000L, -6.250000, 106.800000))
        val stay = TrailStats.longestStay(pts)
        assertTrue(stay != null)
        assertTrue("diam ${stay!!.millis}", stay.millis >= 55 * 60_000L)
        assertTrue(stay.millis <= 62 * 60_000L)
    }

    @Test
    fun diamNullBilaTitikKurang() {
        assertNull(TrailStats.longestStay(listOf(TrailStats.Point(base, 0.0, 0.0))))
    }

    @Test
    fun durasiDiDalamRadius() {
        val pts = listOf(
            TrailStats.Point(base, -6.200000, 106.800000),
            TrailStats.Point(base + 10 * 60_000L, -6.200500, 106.800000),
            TrailStats.Point(base + 20 * 60_000L, -6.400000, 106.800000),
            TrailStats.Point(base + 30 * 60_000L, -6.400100, 106.800000)
        )
        val inside = TrailStats.millisInside(pts, -6.200000, 106.800000, 200f)
        assertTrue("durasi $inside", inside in 10 * 60_000L..10 * 60_000L + 5_000L)
    }

    @Test
    fun segmenLintasBatasDihitungSebagian() {
        val pts = listOf(
            TrailStats.Point(base, -6.200000, 106.800000),
            TrailStats.Point(base + 10 * 60_000L, -6.203600, 106.800000)
        )
        val inside = TrailStats.millisInside(pts, -6.200000, 106.800000, 200f)
        assertTrue("masuk $inside", inside in 4 * 60_000L..6 * 60_000L)
    }

    @Test
    fun batasCelahMenentukanHitunganDiDalam() {
        val pts = listOf(
            TrailStats.Point(base, -6.200000, 106.800000),
            TrailStats.Point(base + 30 * 60_000L, -6.200100, 106.800000)
        )
        assertEquals(0L, TrailStats.millisInside(pts, -6.200000, 106.800000, 200f, maxGapMs = 20 * 60_000L))
        assertEquals(30 * 60_000L, TrailStats.millisInside(pts, -6.200000, 106.800000, 200f, maxGapMs = 40 * 60_000L))
    }

    @Test
    fun radiusNolTidakMenghitung() {
        val pts = listOf(
            TrailStats.Point(base, -6.2, 106.8),
            TrailStats.Point(base + 60_000L, -6.2, 106.8)
        )
        assertEquals(0L, TrailStats.millisInside(pts, -6.2, 106.8, 0f))
    }
}
