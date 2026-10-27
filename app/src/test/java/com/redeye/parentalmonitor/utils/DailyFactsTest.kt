package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class DailyFactsTest {

    private fun day(day: String, s: Int = 0, n: Int = -1, f: Int = -1, a: Int = -1, dep: Int = -1): DailyFacts.Day {
        return DailyFacts.Day(day, s, n, f, a, dep)
    }

    @Test
    fun parseEmptyAndBroken() {
        assertEquals(emptyList<DailyFacts.Day>(), DailyFacts.parse(""))
        assertEquals(
            listOf(day("20261026", 1, 2, 3, 4, 5)),
            DailyFacts.parse("20261027,10,x,1,2,3;jelek;20261026,1,2,3,4,5")
        )
    }

    @Test
    fun serializeRoundTrip() {
        assertEquals("20261026,1,2,3,4,5", DailyFacts.serialize(listOf(day("20261026", 1, 2, 3, 4, 5))))
        val days = listOf(day("20261026", 100, 5, 400, 1130, 430), day("20261027", 120, -1, 410, -1, 425))
        assertEquals(days, DailyFacts.parse(DailyFacts.serialize(days)))
    }

    @Test
    fun upsertReplacesAndKeepsNewest14() {
        val base = (10..23).map { day("202610" + it.toString().padStart(2, '0'), s = 100) }
        val replaced = DailyFacts.upsert(base, day("20261015", s = 999))
        assertEquals(999, replaced.first { it.day == "20261015" }.screenMin)
        assertEquals(14, replaced.size)
        val extended = DailyFacts.upsert(base, day("20261024", s = 1))
        assertEquals(14, extended.size)
        assertEquals("20261011", extended.first().day)
    }
}
