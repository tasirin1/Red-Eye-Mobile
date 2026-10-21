package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorRepairTest {

    @Test
    fun kursorNolTidakDireset() {
        assertFalse(CursorRepair.shouldReset(0L, 0L))
        assertFalse(CursorRepair.shouldReset(0L, 50L))
    }

    @Test
    fun providerKosongDireset() {
        assertTrue(CursorRepair.shouldReset(500L, 0L))
    }

    @Test
    fun providerBergantiDireset() {
        assertTrue(CursorRepair.shouldReset(500L, 3L))
    }

    @Test
    fun providerNormalTidakDireset() {
        assertFalse(CursorRepair.shouldReset(500L, 500L))
        assertFalse(CursorRepair.shouldReset(500L, 501L))
    }

    @Test
    fun bacaGagalTidakDireset() {
        assertFalse(CursorRepair.shouldReset(500L, -1L))
    }
}
