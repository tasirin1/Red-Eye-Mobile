package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Test

// Issue #1673: keputusan eviksi + akuntansi anggaran
// retry MessageQueue diekstrak ke QueueDecisions agar
// teruji. Nilai harapan dibuktikan dengan porting 1:1
// loop lama ke Python (20.000 kasus retry acak dan
// 5.000 kasus eviksi, semuanya identik).
class QueueDecisionsTest {

    @Test
    fun eviksiMemilihNonPrioritasPertama() {
        assertEquals(1, QueueDecisions.evictionIndex(listOf(true, false, true, false)))
        assertEquals(0, QueueDecisions.evictionIndex(listOf(false, true)))
        assertEquals(2, QueueDecisions.evictionIndex(listOf(true, true, false)))
    }

    @Test
    fun eviksiSemuaPrioritasMemilihTertua() {
        assertEquals(0, QueueDecisions.evictionIndex(listOf(true, true, true)))
        assertEquals(0, QueueDecisions.evictionIndex(listOf(true)))
    }

    @Test
    fun eviksiDaftarKosongMinusSatu() {
        assertEquals(-1, QueueDecisions.evictionIndex(emptyList()))
    }

    @Test
    fun retryGugurPadaTepatAnggaran() {
        // Anggaran 3: hitungan kini 2 -> baru 3 >= 3 gugur;
        // hitungan 1 -> baru 2 bertahan dan jadi maksimum;
        // hitungan 0 -> baru 1 bertahan.
        val (gugur, maks) = QueueDecisions.retryBudgetOutcome(listOf(2, 1, 0), budget = 3)
        assertEquals(listOf(true, false, false), gugur)
        assertEquals(2, maks)
    }

    @Test
    fun retrySemuaGugurMaksimumNol() {
        val (gugur, maks) = QueueDecisions.retryBudgetOutcome(listOf(4, 9), budget = 3)
        assertEquals(listOf(true, true), gugur)
        assertEquals(0, maks)
    }

    @Test
    fun retryMaksimumHanyaDariYangBertahan() {
        // Item dengan hitungan tertinggi justru gugur, jadi
        // maksimum diambil dari yang bertahan tertinggi.
        val (gugur, maks) = QueueDecisions.retryBudgetOutcome(listOf(0, 5, 3), budget = 5)
        assertEquals(listOf(false, true, false), gugur)
        assertEquals(4, maks)
    }

    @Test
    fun retryDaftarKosong() {
        val (gugur, maks) = QueueDecisions.retryBudgetOutcome(emptyList(), budget = 3)
        assertEquals(emptyList<Boolean>(), gugur)
        assertEquals(0, maks)
    }
}
