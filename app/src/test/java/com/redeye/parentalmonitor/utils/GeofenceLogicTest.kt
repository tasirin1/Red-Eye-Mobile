package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceLogicTest {
    @Test
    fun transitionAdoptionRequiresAccuracyWithinRadius() {
        assertEquals(GeofenceLogic.Transition(null, false), GeofenceLogic.transition(0, true, 10_000f, 200))
        // #748: akurasi <= 0 (tak diketahui, termasuk 0.0f dari fix tanpa
        // akurasi) tidak lagi mengadopsi state — ekspektasi lama yang
        // memakai -1f/0f sebagai "lolos" diperbarui sengaja di sini.
        assertEquals(GeofenceLogic.Transition(null, false), GeofenceLogic.transition(3, false, -1f, 200))
        assertEquals(GeofenceLogic.Transition(null, false), GeofenceLogic.transition(-1, true, 51f, 50))
        assertEquals(GeofenceLogic.Transition(1, false), GeofenceLogic.transition(0, true, 200f, 200))
        assertEquals(GeofenceLogic.Transition(null, false), GeofenceLogic.transition(0, false, 0f, 200))
    }

    @Test
    fun transitionNoChangeNoWrite() {
        assertEquals(GeofenceLogic.Transition(null, false), GeofenceLogic.transition(1, true, -1f, 200))
        assertEquals(GeofenceLogic.Transition(null, false), GeofenceLogic.transition(2, false, 10_000f, 200))
    }

    @Test
    fun transitionChangeRequiresAccuracyWithinRadius() {
        assertEquals(GeofenceLogic.Transition(null, false), GeofenceLogic.transition(1, false, -1f, 200))
        assertEquals(GeofenceLogic.Transition(null, false), GeofenceLogic.transition(2, true, 0f, 200))
        assertEquals(GeofenceLogic.Transition(null, false), GeofenceLogic.transition(1, false, 201f, 200))
        assertEquals(GeofenceLogic.Transition(null, false), GeofenceLogic.transition(2, true, 5_000f, 200))
    }

    @Test
    fun transitionChangeRequiresAccuracyWithinHalfRadius() {
        // #716: perubahan state (yang mengalert) menuntut akurasi lebih ketat
        // dari adopsi awal: fix berakurasi sebesar radius tak lagi membalik
        // state — posisi sebenarnya bisa di mana saja selebar radius.
        assertEquals(GeofenceLogic.Transition(null, false), GeofenceLogic.transition(2, true, 200f, 200))
        assertEquals(GeofenceLogic.Transition(null, false), GeofenceLogic.transition(1, false, 200f, 200))
        assertEquals(GeofenceLogic.Transition(null, false), GeofenceLogic.transition(1, false, 101f, 200))
        assertEquals(GeofenceLogic.Transition(2, true), GeofenceLogic.transition(1, false, 100f, 200))
        assertEquals(GeofenceLogic.Transition(1, true), GeofenceLogic.transition(2, true, 100f, 200))
        assertEquals(GeofenceLogic.Transition(2, true), GeofenceLogic.transition(1, false, 40f, 50))
        assertEquals(GeofenceLogic.Transition(1, true), GeofenceLogic.transition(2, true, 50f, 50))
        assertEquals(GeofenceLogic.Transition(null, false), GeofenceLogic.transition(1, false, 51f, 50))
        assertEquals(GeofenceLogic.Transition(2, true), GeofenceLogic.transition(1, false, 50f, 75))
        assertEquals(GeofenceLogic.Transition(null, false), GeofenceLogic.transition(1, false, 51f, 75))
        // Adopsi state awal tetap pada gerbang lama (akurasi <= radius).
        assertEquals(GeofenceLogic.Transition(1, false), GeofenceLogic.transition(0, true, 200f, 200))
    }

    @Test
    fun hysteresisKeepsInsideWithinExitMargin() {
        // #715: dari state di dalam, keluar hanya bila melewati radius + margin
        // (maks 30 m atau 15% radius) — jitter tepi tak membalik state.
        assertTrue(GeofenceLogic.insideWithHysteresis(1, 210f, 200))
        assertTrue(GeofenceLogic.insideWithHysteresis(1, 230f, 200))
        assertFalse(GeofenceLogic.insideWithHysteresis(1, 231f, 200))
        // Dari luar / belum dikenal, masuk tetap pada radius persis.
        assertFalse(GeofenceLogic.insideWithHysteresis(2, 210f, 200))
        assertTrue(GeofenceLogic.insideWithHysteresis(2, 195f, 200))
        assertTrue(GeofenceLogic.insideWithHysteresis(0, 200f, 200))
        // Radius kecil: margin dasar 30 m (ambang keluar 80 m pada radius 50).
        assertTrue(GeofenceLogic.insideWithHysteresis(1, 79f, 50))
        assertFalse(GeofenceLogic.insideWithHysteresis(1, 81f, 50))
    }

    @Test
    fun hysteresisStopsJitterFlapping() {
        // Simulasi porting terverifikasi: jarak bergantian 190/210 m pada
        // radius 200 m selama 24 fix tak menulis state dan tak mengalert.
        var state = 1
        repeat(24) { i ->
            val dist = if (i % 2 == 0) 190f else 210f
            val t = GeofenceLogic.transition(state, GeofenceLogic.insideWithHysteresis(state, dist, 200), 30f, 200)
            assertEquals(GeofenceLogic.Transition(null, false), t)
        }
        // Keluar sungguhan (260 m) tetap terdeteksi.
        assertEquals(
            GeofenceLogic.Transition(2, true),
            GeofenceLogic.transition(1, GeofenceLogic.insideWithHysteresis(1, 260f, 200), 30f, 200)
        )
    }

    @Test
    fun jemputHitBoundaries() {
        assertFalse(GeofenceLogic.jemputHit(0, 0f, -1f))
        // #748: akurasi tak diketahui (-1f/0f) tidak lagi memicu jemput.
        assertFalse(GeofenceLogic.jemputHit(200, 200f, -1f))
        assertFalse(GeofenceLogic.jemputHit(200, 201f, -1f))
        assertTrue(GeofenceLogic.jemputHit(200, 150f, 100f))
        assertFalse(GeofenceLogic.jemputHit(200, 150f, 200f))
        assertFalse(GeofenceLogic.jemputHit(200, 150f, 201f))
        assertFalse(GeofenceLogic.jemputHit(200, 150f, -1f))
        assertFalse(GeofenceLogic.jemputHit(200, 150f, 0f))
        // #1797: lantai akurasi #771 berlaku juga di sini — radius
        // kecil memakai batas maxOf(radius/2, min(50, radius)),
        // bukan setengah radius murni.
        assertTrue(GeofenceLogic.jemputHit(50, 40f, 30f))
        assertFalse(GeofenceLogic.jemputHit(50, 40f, 51f))
        assertTrue(GeofenceLogic.jemputHit(30, 20f, 30f))
        assertFalse(GeofenceLogic.jemputHit(30, 20f, 31f))
    }

    @Test
    fun judgedOutsideAccuracyGate() {
        assertFalse(GeofenceLogic.judgedOutside(150f, 100, 800f))
        assertTrue(GeofenceLogic.judgedOutside(150f, 100, 50f))
        assertFalse(GeofenceLogic.judgedOutside(150f, 100, -1f))
        // #759: akurasi selebar radius penuh tak lagi cukup menilai
        // "di luar" — gerbang diselaraskan jadi setengah radius.
        assertFalse(GeofenceLogic.judgedOutside(150f, 100, 100f))
        assertFalse(GeofenceLogic.judgedOutside(150f, 100, 101f))
        assertFalse(GeofenceLogic.judgedOutside(90f, 100, 10f))
        assertFalse(GeofenceLogic.judgedOutside(-1f, 100, 10f))
        // Lantai 50 m selaras transition (issue #771): radius kecil.
        assertTrue(GeofenceLogic.judgedOutside(80f, 50, 40f))
        assertTrue(GeofenceLogic.judgedOutside(80f, 50, 50f))
        assertFalse(GeofenceLogic.judgedOutside(80f, 50, 51f))
        assertTrue(GeofenceLogic.judgedOutside(100f, 75, 50f))
        assertFalse(GeofenceLogic.judgedOutside(100f, 75, 51f))
    }

    @Test
    fun statusTextFormatting() {
        assertEquals("-6.180058,107.384718 ±150m", GeofenceLogic.statusText(-6_180_058L, 107_384_718L, 150))
        assertEquals("mati", GeofenceLogic.statusText(Long.MIN_VALUE, 107_384_718L, 150))
        assertEquals("mati", GeofenceLogic.statusText(-6_180_058L, Long.MIN_VALUE, 150))
        assertEquals("mati", GeofenceLogic.statusText(-6_180_058L, 107_384_718L, 0))
    }

    @Test
    fun configuredSentinels() {
        assertFalse(GeofenceLogic.configured(Long.MIN_VALUE, 106_800_000L, 200))
        assertFalse(GeofenceLogic.configured(-6_200_000L, Long.MIN_VALUE, 200))
        assertFalse(GeofenceLogic.configured(-6_200_000L, 106_800_000L, 0))
        assertFalse(GeofenceLogic.configured(-6_200_000L, 106_800_000L, -5))
        assertTrue(GeofenceLogic.configured(-6_200_000L, 106_800_000L, 200))
    }

    @Test
    fun jemputHitJarakPersisRadiusTerpicu() {
        assertTrue(GeofenceLogic.jemputHit(50, 50f, 10f))
    }
}
