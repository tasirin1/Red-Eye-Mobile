package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Nilai harapan dibuktikan porting Python 1:1 lebih dulu
// (kasus yang sama persis, 2026-11-03).
class MessageSchedulerTest {

    @Test
    fun sisaPanjangWorkerMatiDiganti() {
        assertTrue(MessageScheduler.shouldReplaceDelayed(100_000L, 200_000L, false))
        assertTrue(MessageScheduler.shouldReplaceDelayed(0L, 20_000L, false))
    }

    @Test
    fun ambangBatasKetat() {
        // Sisa pas 15 detik -> TIDAK diganti (syarat < ketat);
        // sisa 15.001 -> diganti.
        assertFalse(MessageScheduler.shouldReplaceDelayed(100_000L, 115_000L, false))
        assertTrue(MessageScheduler.shouldReplaceDelayed(100_000L, 115_001L, false))
    }

    @Test
    fun workerBerjalanTidakDiganti() {
        assertFalse(MessageScheduler.shouldReplaceDelayed(100_000L, 200_000L, true))
    }

    @Test
    fun belumAdaJadwalTidakDiganti() {
        assertFalse(MessageScheduler.shouldReplaceDelayed(100_000L, 0L, false))
    }
}
