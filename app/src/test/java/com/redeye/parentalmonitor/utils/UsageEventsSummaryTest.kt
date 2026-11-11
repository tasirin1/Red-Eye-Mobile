package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageEventsSummaryTest {

    @Test
    fun hitungBukaKunciDanPertama() {
        val events = listOf(
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_KEYGUARD_HIDDEN, 5_000L),
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_KEYGUARD_HIDDEN, 9_000L)
        )
        val s = UsageEventsSummary.build(events)
        assertEquals(2, s.unlocks)
        assertEquals(5_000L, s.firstUnlockAt)
    }

    @Test
    fun aplikasiPalingSeringDibuka() {
        val events = listOf(
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_RESUMED, 1L, "com.a"),
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_RESUMED, 2L, "com.b"),
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_RESUMED, 3L, "com.a"),
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_RESUMED, 4L, "com.a"),
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_RESUMED, 5L, "com.c")
        )
        val s = UsageEventsSummary.build(events, topCount = 2)
        assertEquals(listOf("com.a" to 2, "com.b" to 1), s.topOpens)
    }

    @Test
    fun paketKosongDiabaikan() {
        val events = listOf(
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_RESUMED, 1L, null),
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_RESUMED, 2L, ""),
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_RESUMED, 3L, "com.x")
        )
        val s = UsageEventsSummary.build(events)
        assertEquals(1, s.topOpens.size)
        assertEquals("com.x", s.topOpens.first().first)
    }

    @Test
    fun tanpaPeristiwaMenghasilkanKosong() {
        val s = UsageEventsSummary.build(emptyList())
        assertEquals(0, s.unlocks)
        assertEquals(0L, s.firstUnlockAt)
        assertTrue(s.topOpens.isEmpty())
    }

    @Test
    fun bukaKunciPertamaHariIniDipisahDariJendela24Jam() {
        val events = listOf(
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_KEYGUARD_HIDDEN, 1_000L),
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_RESUMED, 1_500L, "com.a"),
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_KEYGUARD_HIDDEN, 5_000L),
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_KEYGUARD_HIDDEN, 3_000L)
        )
        val s = UsageEventsSummary.build(events)
        assertEquals(3, s.unlocks)
        assertEquals(1_000L, s.firstUnlockAt)
        assertEquals(3_000L, UsageEventsSummary.firstUnlockToday(events, 2_000L))
        assertEquals(0L, UsageEventsSummary.firstUnlockToday(events, 0L))
        assertEquals(0L, UsageEventsSummary.firstUnlockToday(events, 9_000L))
        // #634: jumlah buka kunci hari ini dihitung terpisah dari jendela 24 jam.
        assertEquals(2, UsageEventsSummary.unlocksToday(events, 2_000L))
        assertEquals(0, UsageEventsSummary.unlocksToday(events, 0L))
        assertEquals(0, UsageEventsSummary.unlocksToday(events, 9_000L))
    }

    @Test
    fun capNonPositifTidakMeracuniPertama() {
        // Regresi #1662 (kasus yang dulu tak teruji, issue
        // #1690): event pertama bercap <= 0 meracuni
        // firstUnlockAt selamanya pada klausa lama — tak
        // ada cap valid yang lebih kecil dari negatif.
        // Kini cap non-positif tetap terhitung sebagai
        // buka kunci tetapi tak pernah menjadi "pertama".
        val negatifDulu = listOf(
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_KEYGUARD_HIDDEN, -500L),
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_KEYGUARD_HIDDEN, 5_000L),
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_KEYGUARD_HIDDEN, 9_000L)
        )
        val s1 = UsageEventsSummary.build(negatifDulu)
        assertEquals(3, s1.unlocks)
        assertEquals(5_000L, s1.firstUnlockAt)

        val semuaNonPositif = listOf(
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_KEYGUARD_HIDDEN, -100L),
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_KEYGUARD_HIDDEN, 0L)
        )
        val s2 = UsageEventsSummary.build(semuaNonPositif)
        assertEquals(2, s2.unlocks)
        assertEquals(0L, s2.firstUnlockAt)
    }

    @Test
    fun akumulatorMengalirSetaraDaftar() {
        // Issue #1923: akumulator yang diberi event satu-satu
        // wajib menghasilkan persis build()/firstUnlockToday()/
        // unlocksToday() atas daftar yang sama, termasuk kasus
        // tepi #1662 dan reset lastResumed oleh paket kosong.
        val events = listOf(
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_KEYGUARD_HIDDEN, -500L),
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_RESUMED, 900L, "com.a"),
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_KEYGUARD_HIDDEN, 1_000L),
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_RESUMED, 1_500L, "com.a"),
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_RESUMED, 1_600L, null),
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_RESUMED, 1_700L, "com.a"),
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_RESUMED, 1_800L, "com.b"),
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_KEYGUARD_HIDDEN, 5_000L),
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_KEYGUARD_HIDDEN, 3_000L)
        )
        val dariDaftar = UsageEventsSummary.build(events, topCount = 2)
        val ak = UsageEventsSummary.Akumulator(2_000L)
        for (e in events) ak.terima(e)
        val dariAliran = ak.ringkasan(topCount = 2)
        assertEquals(dariDaftar.unlocks, dariAliran.unlocks)
        assertEquals(dariDaftar.firstUnlockAt, dariAliran.firstUnlockAt)
        assertEquals(dariDaftar.topOpens, dariAliran.topOpens)
        assertEquals(UsageEventsSummary.firstUnlockToday(events, 2_000L), ak.firstUnlockToday())
        assertEquals(UsageEventsSummary.unlocksToday(events, 2_000L), ak.unlocksToday())
        assertEquals(3_000L, ak.firstUnlockToday())
        assertEquals(2, ak.unlocksToday())
        // Tanpa batas hari: penanda hari ini selalu 0.
        val akTanpaBatas = UsageEventsSummary.Akumulator()
        for (e in events) akTanpaBatas.terima(e)
        assertEquals(0L, akTanpaBatas.firstUnlockToday())
        assertEquals(0, akTanpaBatas.unlocksToday())
    }

    @Test
    fun pertamaDiambilTimestampTerkecil() {
        val events = listOf(
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_KEYGUARD_HIDDEN, 8_000L),
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_KEYGUARD_HIDDEN, 3_000L)
        )
        assertEquals(3_000L, UsageEventsSummary.build(events).firstUnlockAt)
    }
}
