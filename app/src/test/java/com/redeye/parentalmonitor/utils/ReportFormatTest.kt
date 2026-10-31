package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportFormatTest {
    @Test
    fun formatsDuration() {
        assertEquals("0 m", ReportFormat.durationText(0))
        assertEquals("0 m", ReportFormat.durationText(-5_000))
        assertEquals("59 m", ReportFormat.durationText(59 * 60_000L))
        assertEquals("1 h 5 m", ReportFormat.durationText(65 * 60_000L))
        assertEquals("2 d 3 h", ReportFormat.durationText(51 * 60 * 60_000L))
    }

    @Test
    fun formatsBytes() {
        assertEquals("512 B", ReportFormat.formatBytes(512))
        assertEquals("1.0 KB", ReportFormat.formatBytes(1024))
        assertEquals("1.5 MB", ReportFormat.formatBytes(1024L * 1024 + 512 * 1024))
        assertEquals("2.00 GB", ReportFormat.formatBytes(2L * 1024 * 1024 * 1024))
        assertEquals("1.0 MB", ReportFormat.formatBytes(1_048_575))
        assertEquals("1.00 GB", ReportFormat.formatBytes(1_073_741_823))
        assertEquals("999.9 KB", ReportFormat.formatBytes(1_023_897))
        assertEquals("1023.9 KB", ReportFormat.formatBytes(1_048_473))
        assertEquals("1023.99 GB", ReportFormat.formatBytes(1_099_500_000_000))
        assertEquals("1.00 TB", ReportFormat.formatBytes(1_099_511_627_775))
        assertEquals("1.00 TB", ReportFormat.formatBytes(1_099_511_627_776))
        assertEquals("1023.99 TB", ReportFormat.formatBytes(1_125_888_911_726_346))
        assertEquals("1.00 PB", ReportFormat.formatBytes(1_125_899_906_842_624))
        assertEquals("2.00 PB", ReportFormat.formatBytes(2_251_799_813_685_248))
    }

    @Test
    fun formatsAccuracy() {
        assertEquals("Akurasi: tak diketahui", ReportFormat.accuracyText(0f))
        assertEquals("Akurasi: tak diketahui", ReportFormat.accuracyText(-1f))
        assertEquals("Akurasi: ±12 m", ReportFormat.accuracyText(12.6f))
        assertEquals("Akurasi: ±1000 m", ReportFormat.accuracyText(1000f))
        assertEquals("Akurasi: ±5 m", ReportFormat.accuracyText(5f))
    }

    @Test
    fun detectsCameraPolicyErrors() {
        assertTrue(ReportFormat.isCameraPolicyError("CAMERA_DISABLED by admin"))
        assertTrue(ReportFormat.isCameraPolicyError("Camera disabled by policy"))
        assertFalse(ReportFormat.isCameraPolicyError("camera busy"))
        assertFalse(ReportFormat.isCameraPolicyError(null))
    }
}
