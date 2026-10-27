package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternBaselineTest {

    private fun day(day: String, s: Int = 0, n: Int = -1, f: Int = -1, a: Int = -1, dep: Int = -1): DailyFacts.Day {
        return DailyFacts.Day(day, s, n, f, a, dep)
    }

    private val hist = listOf(
        day("20261020", s = 180, n = 10, f = 400, a = 1130, dep = 430),
        day("20261021", s = 200, n = 0, f = 410, a = 1140, dep = 425),
        day("20261022", s = 190, n = 20, f = 395, a = 1125, dep = 435),
        day("20261023", s = 210, n = 5, f = 405, a = 1135, dep = 428),
        day("20261024", s = 175, n = 15, f = 415, a = 1145, dep = 432)
    )

    private fun byType(findings: List<PatternBaseline.Finding>): Map<String, PatternBaseline.Finding> {
        return findings.associateBy { it.type }
    }

    @Test
    fun medianValues() {
        assertEquals(3, PatternBaseline.median(listOf(5, 1, 3)))
        assertEquals(2, PatternBaseline.median(listOf(1, 2, 3, 4)))
    }

    @Test
    fun notReadyBelowFiveDays() {
        assertTrue(PatternBaseline.assess(hist.take(4), day("20261025", s = 500)).isEmpty())
    }

    @Test
    fun normalDayShowsAllComparisonsNotStrong() {
        val got = PatternBaseline.assess(hist, day("20261025", s = 200, n = 10, f = 400, a = 1130, dep = 430))
        assertEquals(
            listOf(
                PatternBaseline.Finding("SCREEN", "Layar 3 h 20 m — biasanya ±3 h 10 m", false),
                PatternBaseline.Finding("NIGHT", "Malam 10 m — biasanya ±10 m", false),
                PatternBaseline.Finding("FIRST", "Buka pertama 06.40 — biasanya 06.45", false),
                PatternBaseline.Finding("ARRIVE", "Tiba di rumah 18.50 — biasanya 18.55", false),
                PatternBaseline.Finding("DEPART", "Berangkat 07.10 — biasanya 07.10", false)
            ),
            got
        )
    }

    @Test
    fun strongAnomalies() {
        val screen = byType(PatternBaseline.assess(hist, day("20261025", s = 400)))
        assertEquals(PatternBaseline.Finding("SCREEN", "Layar 6 h 40 m — biasanya ±3 h 10 m", true), screen["SCREEN"])
        val night = byType(PatternBaseline.assess(hist, day("20261025", s = 190, n = 75)))
        assertEquals(PatternBaseline.Finding("NIGHT", "Malam 1 h 15 m — biasanya ±10 m", true), night["NIGHT"])
        val first = byType(PatternBaseline.assess(hist, day("20261025", s = 190, f = 320)))
        assertEquals(PatternBaseline.Finding("FIRST", "Buka pertama 05.20 — biasanya 06.45", true), first["FIRST"])
        val arrive = byType(PatternBaseline.assess(hist, day("20261025", s = 190, a = 1210)))
        assertEquals(PatternBaseline.Finding("ARRIVE", "Tiba di rumah 20.10 — biasanya 18.55", true), arrive["ARRIVE"])
    }

    @Test
    fun sparseMetricsAreSkipped() {
        val sparse = listOf(
            day("20261020", s = 180), day("20261021", s = 200), day("20261022", s = 190),
            day("20261023", s = 210), day("20261024", s = 175)
        )
        val got = PatternBaseline.assess(sparse, day("20261025", s = 190, n = 90, f = 300, a = 1200, dep = 400))
        assertEquals(
            listOf(PatternBaseline.Finding("SCREEN", "Layar 3 h 10 m — biasanya ±3 h 10 m", false)),
            got
        )
    }
}
