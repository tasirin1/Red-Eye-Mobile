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

    @Test
    fun mediaNameTimeParsesEmbeddedStamps() {
        val ts = 1_700_000_000_000L
        // Audio: milidetik epoch mentah di nama.
        assertEquals(ts, TimeFmt.mediaNameTimeMs("audio_1700000000000_9f8c7d6a.m4a"))
        // Kamera/video/screenshot: fileStamp (presisi detik).
        assertEquals(ts, TimeFmt.mediaNameTimeMs("camera_" + TimeFmt.fileStamp(ts) + "_9f8c7d6a.jpg"))
        assertEquals(ts, TimeFmt.mediaNameTimeMs("video_" + TimeFmt.fileStamp(ts) + "_9f8c7d6a.mp4"))
        assertEquals(ts, TimeFmt.mediaNameTimeMs("screenshot_" + TimeFmt.fileStamp(ts) + "_9f8c7d6a.jpg.tmp"))
    }

    @Test
    fun mediaNameTimeRejectsUnknownNames() {
        assertEquals(-1L, TimeFmt.mediaNameTimeMs("random.txt"))
        assertEquals(-1L, TimeFmt.mediaNameTimeMs("camera_rusak_x.jpg"))
        assertEquals(-1L, TimeFmt.mediaNameTimeMs("audio_bukanangka_x.m4a"))
        assertEquals(-1L, TimeFmt.mediaNameTimeMs("camera_20231340_999999_x.jpg"))
    }
}
