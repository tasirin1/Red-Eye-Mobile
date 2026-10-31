package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateOffsetTrackerTest {

    @Test
    fun konfirmasiBerurutan() {
        val tracker = UpdateOffsetTracker()
        tracker.begin(10, 1L)
        assertEquals(10L, tracker.finish(10, 1L, true))
    }

    @Test
    fun tungguPembaruanLebihAwal() {
        val tracker = UpdateOffsetTracker()
        tracker.begin(1, 1L)
        tracker.begin(2, 1L)
        tracker.begin(3, 1L)
        assertNull(tracker.finish(3, 1L, true))
        assertEquals(1L, tracker.finish(1, 1L, true))
        assertEquals(3L, tracker.finish(2, 1L, true))
    }

    @Test
    fun idYangTidakDilacakTidakMenahan() {
        val tracker = UpdateOffsetTracker()
        tracker.begin(100, 1L)
        tracker.begin(105, 1L)
        assertEquals(100L, tracker.finish(100, 1L, true))
        assertEquals(105L, tracker.finish(105, 1L, true))
    }

    @Test
    fun gagalMenahanSeluruhKemajuan() {
        val tracker = UpdateOffsetTracker()
        tracker.begin(7, 1L)
        tracker.begin(8, 1L)
        assertNull(tracker.finish(7, 1L, false))
        assertNull(tracker.finish(8, 1L, true))
        tracker.begin(9, 1L)
        assertNull(tracker.finish(9, 1L, true))
    }

    @Test
    fun gagalLaluBerhasilMenggeserOffset() {
        val tracker = UpdateOffsetTracker()
        tracker.begin(7, 1L)
        tracker.begin(8, 1L)
        assertNull(tracker.finish(7, 1L, false))
        assertNull(tracker.finish(8, 1L, true))
        tracker.begin(7, 1L)
        assertEquals(8L, tracker.finish(7, 1L, true))
    }

    @Test
    fun gagalLaluPembaruanSamaBerhasil() {
        val tracker = UpdateOffsetTracker()
        tracker.begin(5, 1L)
        assertNull(tracker.finish(5, 1L, false))
        tracker.begin(5, 1L)
        assertEquals(5L, tracker.finish(5, 1L, true))
    }

    @Test
    fun pembaruanSetelahGagalMenungguYangGagal() {
        val tracker = UpdateOffsetTracker()
        tracker.begin(1, 1L)
        tracker.begin(2, 1L)
        tracker.begin(3, 1L)
        assertEquals(1L, tracker.finish(1, 1L, true))
        assertNull(tracker.finish(3, 1L, true))
        assertNull(tracker.finish(2, 1L, false))
        tracker.begin(2, 1L)
        assertEquals(3L, tracker.finish(2, 1L, true))
    }

    @Test
    fun lupakanTertundaBasiMembebaskanKemajuan() {
        val tracker = UpdateOffsetTracker()
        tracker.begin(1, 1L)
        tracker.begin(2, 1L)
        tracker.begin(3, 1L)
        assertNull(tracker.finish(2, 1L, true))
        tracker.forget(1, 1L)
        assertEquals(3L, tracker.finish(3, 1L, true))
    }

    @Test
    fun lupakanGagalBasiMembebaskanKemajuan() {
        val tracker = UpdateOffsetTracker()
        tracker.begin(5, 1L)
        assertNull(tracker.finish(5, 1L, false))
        tracker.begin(6, 1L)
        assertNull(tracker.finish(6, 1L, true))
        tracker.forget(5, 1L)
        tracker.begin(7, 1L)
        assertEquals(7L, tracker.finish(7, 1L, true))
    }

    @Test
    fun selesaiLebihDuluLaluYangAwal() {
        val tracker = UpdateOffsetTracker()
        tracker.begin(20, 1L)
        tracker.begin(21, 1L)
        assertEquals(20L, tracker.finish(20, 1L, true))
        assertEquals(21L, tracker.finish(21, 1L, true))
    }

    @Test
    fun lupakanBasiTidakMenghapusTertundaBotBaru() {
        val tracker = UpdateOffsetTracker()
        tracker.begin(5, 1L)
        tracker.begin(5, 2L)
        tracker.begin(6, 2L)
        tracker.forget(5, 1L)
        assertNull(tracker.finish(6, 2L, true))
        assertNull(tracker.finish(5, 2L, false))
    }

    @Test
    fun selesaiBasiDiabaikan() {
        val tracker = UpdateOffsetTracker()
        tracker.begin(5, 2L)
        assertNull(tracker.finish(5, 1L, true))
        assertEquals(5L, tracker.finish(5, 2L, true))
    }

    @Test
    fun sisaTerkonfirmasiGenerasiLamaTidakMemajukanOffsetBotBaru() {
        val tracker = UpdateOffsetTracker()
        tracker.begin(10, 1L)
        tracker.begin(11, 1L)
        assertNull(tracker.finish(11, 1L, true))
        tracker.forget(10, 1L)
        tracker.begin(5, 2L)
        assertEquals(5L, tracker.finish(5, 2L, true))
        tracker.begin(12, 2L)
        assertEquals(12L, tracker.finish(12, 2L, true))
    }

    @Test
    fun lupakanBasiTidakMenghapusTerkonfirmasiBotBaru() {
        val tracker = UpdateOffsetTracker()
        tracker.begin(3, 2L)
        tracker.begin(5, 2L)
        assertNull(tracker.finish(5, 2L, true))
        tracker.forget(5, 1L)
        assertEquals(5L, tracker.finish(3, 2L, true))
    }
}
