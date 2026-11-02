package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    @Test
    fun upsertKeepsNearestFutureDaysFirst() {
        // #701: hari masa depan yang dipertahankan adalah yang terdekat.
        val stored = (1..20).map { day("202612%02d".format(it), s = it) }
        val out = DailyFacts.upsert(stored, day("20261102", s = 99), keep = 14)
        assertTrue(out.any { it.day == "20261201" })
        assertFalse(out.any { it.day == "20261220" })
    }

    @Test
    fun upsertKeepsFutureDaysInStorage() {
        // #550: upsert tidak lagi membuang hari sesudah hari yang ditulis — hari
        // tersimpan tak bisa dibedakan antara sisa loncatan jam vs hari sah saat
        // jam dimundurkan, dan pengecualian hari masa depan dari hitungan pola
        // adalah tugas saringan pembaca (day < hari ini, #536), bukan upsert.
        val stored = listOf(day("20261026", s = 100), day("20261031", s = 999))
        val out = DailyFacts.upsert(stored, day("20261027", s = 50))
        assertEquals(listOf("20261026", "20261027", "20261031"), out.map { it.day })
    }

    @Test
    fun upsertFutureDaysDoNotEvictRecentWindow() {
        val real = (17..30).map { day("202610" + it.toString().padStart(2, '0'), s = 100) }
        val stored = real + listOf(day("20261105", s = 1), day("20261106", s = 1), day("20261107", s = 1))
        val out = DailyFacts.upsert(stored, day("20261031", s = 7))
        assertEquals(14, out.count { it.day <= "20261031" })
        assertEquals("20261018", out.first { it.day <= "20261031" }.day)
        assertEquals(listOf("20261105", "20261106", "20261107"), out.filter { it.day > "20261031" }.map { it.day })
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
