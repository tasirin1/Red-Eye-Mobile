package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class PollTimingTest {

    @Test fun rampaAwalMengikutiPutaranDiam() {
        assertEquals(10_000L, PollTiming.idleGapMs(0, 0L))
        assertEquals(15_000L, PollTiming.idleGapMs(1, 20_000L))
        assertEquals(30_000L, PollTiming.idleGapMs(4, 60_000L))
        assertEquals(30_000L, PollTiming.idleGapMs(9, 60_000L))
    }

    @Test fun tingkatDiamLimaMenit() {
        assertEquals(30_000L, PollTiming.idleGapMs(4, 299_999L))
        assertEquals(60_000L, PollTiming.idleGapMs(4, 300_000L))
        assertEquals(60_000L, PollTiming.idleGapMs(0, 900_000L))
    }

    @Test fun tingkatDiamTigaPuluhMenit() {
        assertEquals(60_000L, PollTiming.idleGapMs(4, 1_799_999L))
        assertEquals(120_000L, PollTiming.idleGapMs(4, 1_800_000L))
        assertEquals(120_000L, PollTiming.idleGapMs(0, 7_200_000L))
    }
}
