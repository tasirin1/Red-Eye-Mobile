package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateOffsetTrackerTest {

    @Test
    fun konfirmasiBerurutan() {
        val tracker = UpdateOffsetTracker()
        tracker.begin(10)
        assertEquals(10L, tracker.finish(10, true))
    }

    @Test
    fun tungguPembaruanLebihAwal() {
        val tracker = UpdateOffsetTracker()
        tracker.begin(1)
        tracker.begin(2)
        tracker.begin(3)
        assertNull(tracker.finish(3, true))
        assertEquals(1L, tracker.finish(1, true))
        assertEquals(3L, tracker.finish(2, true))
    }

    @Test
    fun idYangTidakDilacakTidakMenahan() {
        val tracker = UpdateOffsetTracker()
        tracker.begin(100)
        tracker.begin(105)
        assertEquals(100L, tracker.finish(100, true))
        assertEquals(105L, tracker.finish(105, true))
    }

    @Test
    fun gagalMenahanSeluruhKemajuan() {
        val tracker = UpdateOffsetTracker()
        tracker.begin(7)
        tracker.begin(8)
        assertNull(tracker.finish(7, false))
        assertNull(tracker.finish(8, true))
        tracker.begin(9)
        assertNull(tracker.finish(9, true))
    }

    @Test
    fun gagalLaluBerhasilMenggeserOffset() {
        val tracker = UpdateOffsetTracker()
        tracker.begin(7)
        tracker.begin(8)
        assertNull(tracker.finish(7, false))
        assertNull(tracker.finish(8, true))
        tracker.begin(7)
        assertEquals(8L, tracker.finish(7, true))
    }

    @Test
    fun gagalLaluPembaruanSamaBerhasil() {
        val tracker = UpdateOffsetTracker()
        tracker.begin(5)
        assertNull(tracker.finish(5, false))
        tracker.begin(5)
        assertEquals(5L, tracker.finish(5, true))
    }

    @Test
    fun pembaruanSetelahGagalMenungguYangGagal() {
        val tracker = UpdateOffsetTracker()
        tracker.begin(1)
        tracker.begin(2)
        tracker.begin(3)
        assertEquals(1L, tracker.finish(1, true))
        assertNull(tracker.finish(3, true))
        assertNull(tracker.finish(2, false))
        tracker.begin(2)
        assertEquals(3L, tracker.finish(2, true))
    }

    @Test
    fun selesaiLebihDuluLaluYangAwal() {
        val tracker = UpdateOffsetTracker()
        tracker.begin(20)
        tracker.begin(21)
        assertEquals(20L, tracker.finish(20, true))
        assertEquals(21L, tracker.finish(21, true))
    }
}
