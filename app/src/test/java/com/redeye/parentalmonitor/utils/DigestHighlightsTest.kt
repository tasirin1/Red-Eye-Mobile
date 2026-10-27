package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class DigestHighlightsTest {

    private val min = 60_000L

    private fun facts(
        today: Long = 0L,
        yesterday: Long = 0L,
        topApp: String = "",
        topToday: Long = 0L,
        topYesterday: Long = 0L,
        night: Long = -1L,
        firstUnlock: Int = -1
    ): DigestHighlights.Facts {
        return DigestHighlights.Facts(today, yesterday, topApp, topToday, topYesterday, night, firstUnlock)
    }

    @Test
    fun emptyFactsGiveNoHighlights() {
        assertEquals(emptyList<String>(), DigestHighlights.build(facts()))
    }

    @Test
    fun screenChangeHighlights() {
        assertEquals(
            listOf("📈 Layar 6 h 0 m — naik 50% dari kemarin"),
            DigestHighlights.build(facts(today = 360 * min, yesterday = 240 * min))
        )
        assertEquals(
            listOf("📉 Layar 3 h 0 m — turun 40% dari kemarin"),
            DigestHighlights.build(facts(today = 180 * min, yesterday = 300 * min))
        )
        assertEquals(
            emptyList<String>(),
            DigestHighlights.build(facts(today = 290 * min, yesterday = 240 * min))
        )
        assertEquals(
            emptyList<String>(),
            DigestHighlights.build(facts(today = 50 * min, yesterday = 30 * min))
        )
        assertEquals(
            listOf("📱 Layar 6 h 40 m dalam 24 jam terakhir"),
            DigestHighlights.build(facts(today = 400 * min, yesterday = 20 * min))
        )
    }

    @Test
    fun nightHighlights() {
        assertEquals(
            listOf("🌙 Pemakaian malam 2 h 0 m"),
            DigestHighlights.build(facts(night = 120 * min))
        )
        assertEquals(
            listOf("🌙 Pemakaian malam 45 m"),
            DigestHighlights.build(facts(night = 45 * min))
        )
        assertEquals(emptyList<String>(), DigestHighlights.build(facts(night = 20 * min)))
    }

    @Test
    fun topAppSurgeHighlights() {
        assertEquals(
            listOf("🔥 Game X 3 h 0 m — kemarin hanya 1 h 0 m"),
            DigestHighlights.build(facts(topApp = "Game X", topToday = 180 * min, topYesterday = 60 * min))
        )
        assertEquals(
            emptyList<String>(),
            DigestHighlights.build(facts(topApp = "Game X", topToday = 80 * min, topYesterday = 60 * min))
        )
    }

    @Test
    fun earlyUnlockHighlights() {
        assertEquals(
            listOf("⏰ HP pertama dibuka 04.50"),
            DigestHighlights.build(facts(firstUnlock = 290))
        )
        assertEquals(emptyList<String>(), DigestHighlights.build(facts(firstUnlock = 390)))
    }

    @Test
    fun combinedHighlightsAreScoreOrderedAndCappedAtThree() {
        val got = DigestHighlights.build(
            facts(
                today = 360 * min,
                yesterday = 240 * min,
                topApp = "Game X",
                topToday = 180 * min,
                topYesterday = 60 * min,
                night = 120 * min,
                firstUnlock = 290
            )
        )
        assertEquals(
            listOf(
                "🌙 Pemakaian malam 2 h 0 m",
                "📈 Layar 6 h 0 m — naik 50% dari kemarin",
                "🔥 Game X 3 h 0 m — kemarin hanya 1 h 0 m"
            ),
            got
        )
    }
}
