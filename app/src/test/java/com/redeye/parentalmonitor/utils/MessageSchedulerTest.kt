package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
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

    @Test
    fun nilaiTerbitDelayNolTidakMenurunkanPenandaMasaDepan() {
        // Delay positif menerbitkan jadwalnya sendiri.
        assertEquals(130_000L, MessageScheduler.nilaiTerbitPenanda(30_000L, 0L, 100_000L))
        // Delay positif TIDAK menimpa penanda masa depan
        // yang lebih jauh (issue #2024; kasus ini dulu
        // mengunci perilaku menimpa dari #2019).
        assertEquals(500_000L, MessageScheduler.nilaiTerbitPenanda(30_000L, 500_000L, 100_000L))
        assertEquals(130_000L, MessageScheduler.nilaiTerbitPenanda(30_000L, 120_000L, 100_000L))
        // Inti issue #2019: delay-0 mempertahankan penanda
        // masa depan milik pekerjaan tertunda yang hidup.
        assertEquals(500_000L, MessageScheduler.nilaiTerbitPenanda(0L, 500_000L, 100_000L))
        // Penanda yang bukan masa depan tetap dinolkan.
        assertEquals(0L, MessageScheduler.nilaiTerbitPenanda(0L, 100_000L, 100_000L))
        assertEquals(0L, MessageScheduler.nilaiTerbitPenanda(0L, 50_000L, 100_000L))
        assertEquals(0L, MessageScheduler.nilaiTerbitPenanda(0L, 0L, 100_000L))
    }
}
