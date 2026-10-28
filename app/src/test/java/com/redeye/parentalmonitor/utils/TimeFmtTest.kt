package com.redeye.parentalmonitor.utils

import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import java.util.TimeZone

class TimeFmtTest {

    companion object {
        private var previous: TimeZone? = null

        @BeforeClass
        @JvmStatic
        fun setUtc() {
            previous = TimeZone.getDefault()
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        }

        @AfterClass
        @JvmStatic
        fun restore() {
            previous?.let { TimeZone.setDefault(it) }
        }
    }

    @Test
    fun formatWaktuUtc() {
        val ts = 1_700_000_000_000L
        assertEquals("14.11.2023 22:13:20", TimeFmt.full(ts))
        assertEquals("22:13", TimeFmt.short(ts))
        assertEquals("2023-11-14", TimeFmt.dayKey(ts))
        assertEquals("20231114_221320", TimeFmt.fileStamp(ts))
    }
}
