package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class NightWindowTest {

    // Nilai harapan dari porting Python 1:1 (issue #977):
    // (now, 05:00 hari ini) -> sisa ms.
    @Test
    fun beforeNightEndSameDay() {
        assertEquals(3_600_000L, NightWindow.millisUntilNightEnd(0L, 3_600_000L))
    }

    @Test
    fun exactlyAtNightEndRollsToTomorrow() {
        assertEquals(86_400_000L, NightWindow.millisUntilNightEnd(0L, 0L))
        assertEquals(86_400_000L, NightWindow.millisUntilNightEnd(5_000L, 5_000L))
    }

    @Test
    fun afterNightEndRollsToTomorrow() {
        assertEquals(82_800_000L, NightWindow.millisUntilNightEnd(0L, -3_600_000L))
    }

    @Test
    fun tinyRemainderClampedToFiveSeconds() {
        assertEquals(5_000L, NightWindow.millisUntilNightEnd(0L, 1L))
    }
}
