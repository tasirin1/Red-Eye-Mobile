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
        assertEquals(listOf("com.a" to 3, "com.b" to 1), s.topOpens)
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
    fun pertamaDiambilTimestampTerkecil() {
        val events = listOf(
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_KEYGUARD_HIDDEN, 8_000L),
            UsageEventsSummary.Event(UsageEventsSummary.TYPE_KEYGUARD_HIDDEN, 3_000L)
        )
        assertEquals(3_000L, UsageEventsSummary.build(events).firstUnlockAt)
    }
}
