package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Issue #1692: matematika klem LimitedCursor diekstrak
// ke ContentQuery.Clamp agar teruji JVM (pembungkusnya
// terikat android.database.Cursor). Nilai harapan dari
// aturan produksi: hitung terklem ke maxRows, posisi
// terklem ke hitung terbatas, gerak negatif diteruskan,
// gerak mencapai maxRows parkir di akhir dan gagal.
class ContentQueryClampTest {

    @Test
    fun hitungTerklemKeMaxRows() {
        assertEquals(5, ContentQuery.Clamp.limitedCount(10, 5))
        assertEquals(3, ContentQuery.Clamp.limitedCount(3, 5))
        assertEquals(0, ContentQuery.Clamp.limitedCount(0, 5))
        assertEquals(0, ContentQuery.Clamp.limitedCount(10, 0))
    }

    @Test
    fun posisiTerklemKeHitungTerbatas() {
        assertEquals(2, ContentQuery.Clamp.clampedPosition(2, 10, 5))
        assertEquals(5, ContentQuery.Clamp.clampedPosition(9, 10, 5))
        assertEquals(3, ContentQuery.Clamp.clampedPosition(9, 3, 5))
    }

    @Test
    fun keputusanGerakDiBatas() {
        assertEquals(ContentQuery.Clamp.Move.PASSTHROUGH, ContentQuery.Clamp.moveDecision(-1, 5))
        assertEquals(ContentQuery.Clamp.Move.DIRECT, ContentQuery.Clamp.moveDecision(0, 5))
        assertEquals(ContentQuery.Clamp.Move.DIRECT, ContentQuery.Clamp.moveDecision(4, 5))
        assertEquals(ContentQuery.Clamp.Move.PARK_END, ContentQuery.Clamp.moveDecision(5, 5))
        assertEquals(ContentQuery.Clamp.Move.PARK_END, ContentQuery.Clamp.moveDecision(9, 5))
        assertEquals(ContentQuery.Clamp.Move.PARK_END, ContentQuery.Clamp.moveDecision(0, 0))
    }

    @Test
    fun sesudahTerakhirDanTerakhir() {
        // Kursor 10 baris dibatasi 5: posisi mentah 5..9
        // terbaca sebagai sesudah-terakhir lewat klem.
        assertFalse(ContentQuery.Clamp.isAfterLast(4, 10, 5))
        assertTrue(ContentQuery.Clamp.isAfterLast(5, 10, 5))
        assertTrue(ContentQuery.Clamp.isAfterLast(9, 10, 5))
        assertTrue(ContentQuery.Clamp.isLast(4, 10, 5))
        assertFalse(ContentQuery.Clamp.isLast(3, 10, 5))
        assertFalse(ContentQuery.Clamp.isLast(5, 10, 5))
        // Kursor kosong tak pernah "terakhir".
        assertFalse(ContentQuery.Clamp.isLast(0, 0, 5))
        assertTrue(ContentQuery.Clamp.isAfterLast(0, 0, 5))
        // Tanpa klem efektif (maxRows di atas total).
        assertTrue(ContentQuery.Clamp.isLast(2, 3, 100))
        assertFalse(ContentQuery.Clamp.isAfterLast(2, 3, 100))
    }
}
