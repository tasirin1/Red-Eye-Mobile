package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class PollTimingTest {

    @Test fun rampaAwalMengikutiPutaranDiam() {
        assertEquals(3_000L, PollTiming.idleGapMs(0, 0L))
        assertEquals(5_000L, PollTiming.idleGapMs(1, 20_000L))
        assertEquals(10_000L, PollTiming.idleGapMs(4, 60_000L))
        assertEquals(10_000L, PollTiming.idleGapMs(9, 60_000L))
    }

    @Test fun tingkatDiamLimaMenit() {
        assertEquals(10_000L, PollTiming.idleGapMs(4, 299_999L))
        assertEquals(20_000L, PollTiming.idleGapMs(4, 300_000L))
        assertEquals(20_000L, PollTiming.idleGapMs(0, 900_000L))
    }

    @Test fun tingkatDiamTigaPuluhMenit() {
        assertEquals(20_000L, PollTiming.idleGapMs(4, 1_799_999L))
        assertEquals(30_000L, PollTiming.idleGapMs(4, 1_800_000L))
        assertEquals(30_000L, PollTiming.idleGapMs(0, 7_200_000L))
    }

    @Test fun timeoutLongPollNaikSesudahDiamSemenit() {
        assertEquals(30, PollTiming.longPollTimeoutSec(0L))
        assertEquals(30, PollTiming.longPollTimeoutSec(59_999L))
        assertEquals(50, PollTiming.longPollTimeoutSec(60_000L))
        assertEquals(50, PollTiming.longPollTimeoutSec(7_200_000L))
    }

    @Test fun modeDozeMeregangkanJedaKeduaMode() {
        assertEquals(180_000L, PollTiming.idleGapMs(0, 0L, doze = true))
        assertEquals(180_000L, PollTiming.idleGapMs(4, 1_800_000L, doze = true))
        assertEquals(180_000L, PollTiming.idleGapMs(4, 1_800_000L, hematDaya = true, doze = true))
        assertEquals(180_000L, PollTiming.idleGapMs(0, 0L, hematDaya = true, doze = true))
    }

    @Test fun modeHematMengikutiJadwalLonggar() {
        assertEquals(10_000L, PollTiming.idleGapMs(0, 0L, hematDaya = true))
        assertEquals(15_000L, PollTiming.idleGapMs(1, 20_000L, hematDaya = true))
        assertEquals(30_000L, PollTiming.idleGapMs(4, 299_999L, hematDaya = true))
        assertEquals(60_000L, PollTiming.idleGapMs(4, 300_000L, hematDaya = true))
        assertEquals(60_000L, PollTiming.idleGapMs(4, 1_799_999L, hematDaya = true))
        assertEquals(120_000L, PollTiming.idleGapMs(4, 1_800_000L, hematDaya = true))
        assertEquals(120_000L, PollTiming.idleGapMs(0, 7_200_000L, hematDaya = true))
    }
}
