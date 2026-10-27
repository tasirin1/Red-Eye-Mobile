package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class SparklineTest {
    @Test
    fun renderEmptyAndFlat() {
        assertEquals("", Sparkline.render(intArrayOf()))
        assertEquals("▁▁▁", Sparkline.render(intArrayOf(0, 0, 0)))
        assertEquals("████", Sparkline.render(intArrayOf(3, 3, 3, 3)))
    }

    @Test
    fun renderScalesToPeak() {
        assertEquals("█", Sparkline.render(intArrayOf(5)))
        assertEquals("▂▄▆█", Sparkline.render(intArrayOf(1, 2, 3, 4)))
        assertEquals("█▁▄", Sparkline.render(intArrayOf(10, 0, 5)))
        assertEquals("▁█", Sparkline.render(intArrayOf(-2, 4)))
        assertEquals(
            "▁▁▁▁▁▂▃▅█▅▂▁▁▃▄▇▄▂▁▁▁▁▁▁",
            Sparkline.render(intArrayOf(0, 0, 1, 0, 0, 2, 5, 9, 14, 8, 3, 0, 0, 4, 7, 12, 6, 2, 1, 0, 0, 0, 0, 0))
        )
    }
}
