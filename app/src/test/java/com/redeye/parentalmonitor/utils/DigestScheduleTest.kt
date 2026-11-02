package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestScheduleTest {
    @Test
    fun periodicDecisionBoundaries() {
        val now = 1_700_000_000_000L
        val h6 = 21_600_000L
        assertEquals(DigestSchedule.Verdict.RESET, DigestSchedule.periodicDecision(now + 1, now))
        assertEquals(DigestSchedule.Verdict.RESET, DigestSchedule.periodicDecision(now + 10_000_000_000L, now))
        assertEquals(DigestSchedule.Verdict.WAIT, DigestSchedule.periodicDecision(now - h6 + 1, now))
        assertEquals(DigestSchedule.Verdict.WAIT, DigestSchedule.periodicDecision(0L, 10_000L))
        assertEquals(DigestSchedule.Verdict.SEND, DigestSchedule.periodicDecision(now - h6, now))
        assertEquals(DigestSchedule.Verdict.SEND, DigestSchedule.periodicDecision(0L, now))
    }

    @Test
    fun nextPeriodicAnchorStaysOnGrid() {
        val period = 6 * 60 * 60_000L
        assertEquals(1_000_000L + 2 * period, DigestSchedule.nextPeriodicAnchor(1_000_000L, 1_000_000L + 2 * period + 12_345L))
        assertEquals(1_000_000L + period, DigestSchedule.nextPeriodicAnchor(1_000_000L, 1_000_000L + period))
        assertEquals(5_000L, DigestSchedule.nextPeriodicAnchor(0L, 5_000L))
        assertEquals(5_000L, DigestSchedule.nextPeriodicAnchor(9_000L, 5_000L))
    }

    @Test
    fun localHourAcrossOffsets() {
        assertEquals(20, DigestSchedule.localHour(1_699_970_340_000L, 25_200_000L))
        assertEquals(21, DigestSchedule.localHour(1_699_970_400_000L, 25_200_000L))
        assertEquals(0, DigestSchedule.localHour(1_699_920_000_000L, 0L))
        assertEquals(23, DigestSchedule.localHour(1_700_006_340_000L, 0L))
        assertEquals(21, DigestSchedule.localHour(1_699_927_200_000L, -18_000_000L))
        assertEquals(19, DigestSchedule.localHour(1_699_957_800_000L, 32_400_000L))
    }

    @Test
    fun nightDueRules() {
        assertFalse(DigestSchedule.nightDue(20, "", "2026-10-27"))
        assertTrue(DigestSchedule.nightDue(21, "", "2026-10-27"))
        assertTrue(DigestSchedule.nightDue(22, "2026-10-26", "2026-10-27"))
        assertFalse(DigestSchedule.nightDue(21, "2026-10-27", "2026-10-27"))
        assertFalse(DigestSchedule.nightDue(23, "", ""))
        assertFalse(DigestSchedule.nightDue(21, "", ""))
    }

    @Test
    fun nightCatchUpDueRules() {
        assertTrue(DigestSchedule.nightCatchUpDue("2026-10-25", "2026-10-26", "2026-10-27", 7))
        assertFalse(DigestSchedule.nightCatchUpDue("2026-10-26", "2026-10-26", "2026-10-27", 7))
        assertFalse(DigestSchedule.nightCatchUpDue("2026-10-27", "2026-10-26", "2026-10-27", 7))
        assertFalse(DigestSchedule.nightCatchUpDue("", "2026-10-26", "2026-10-27", 7))
        assertFalse(DigestSchedule.nightCatchUpDue("2026-10-25", "", "2026-10-27", 7))
        // Jendela pagi saja (issue #738): tengah hari/malam tidak menyusul.
        assertTrue(DigestSchedule.nightCatchUpDue("2026-10-25", "2026-10-26", "2026-10-27", 5))
        assertTrue(DigestSchedule.nightCatchUpDue("2026-10-25", "2026-10-26", "2026-10-27", 11))
        assertFalse(DigestSchedule.nightCatchUpDue("2026-10-25", "2026-10-26", "2026-10-27", 15))
        assertFalse(DigestSchedule.nightCatchUpDue("2026-10-25", "2026-10-26", "2026-10-27", 22))
        assertFalse(DigestSchedule.nightCatchUpDue("2026-10-25", "2026-10-26", "2026-10-27", 3))
    }

    @Test
    fun catchUpStampedWithYesterdayKeepsTonightDue() {
        // #632: cabang catch-up di MonitoringService mencap kunci KEMARIN
        // (bukan hari ini) — urutan predikat ini mengunci akibatnya: catch-up
        // tidak menembak dua kali, dan digest reguler malam nanti tetap due.
        val doneAfterCatchUp = "2026-10-26"
        assertFalse(DigestSchedule.nightCatchUpDue(doneAfterCatchUp, "2026-10-26", "2026-10-27", 7))
        assertTrue(DigestSchedule.nightDue(21, doneAfterCatchUp, "2026-10-27"))
    }

    @Test
    fun clockRollbackDoesNotRearm() {
        // #601: jam dimundurkan sehari -> kunci selesai yang lebih baru dari "hari ini"
        // tidak boleh mempersenjatai ulang digest malam maupun tangkapannya.
        assertFalse(DigestSchedule.nightDue(21, "2026-10-28", "2026-10-27"))
        assertFalse(DigestSchedule.nightDue(23, "2026-10-28", "2026-10-27"))
        assertFalse(DigestSchedule.nightCatchUpDue("2026-10-27", "2026-10-25", "2026-10-26", 7))
    }
}
