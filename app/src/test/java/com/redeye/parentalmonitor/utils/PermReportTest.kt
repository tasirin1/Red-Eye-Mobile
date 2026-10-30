package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermReportTest {
    private val labels = arrayOf("Baca SMS", "Log panggilan", "Kontak", "Kirim SMS", "Kamera", "Mikrofon", "Lokasi", "Lokasi latar", "Akses notifikasi", "Aksesibilitas", "Baterai tanpa batas")

    @Test
    fun maskSemantics() {
        assertEquals(1073743871, PermReport.mask(BooleanArray(11) { true }, notifEnabled = false, stored = -1))
        assertEquals(1073741952, PermReport.mask(BooleanArray(11), notifEnabled = true, stored = -1))
        assertEquals(1073741824, PermReport.mask(BooleanArray(11), notifEnabled = true, stored = 0))
        assertEquals(1073742208, PermReport.mask(BooleanArray(11), notifEnabled = false, stored = 384))
        val locOnly = BooleanArray(11).also { it[6] = true }
        assertEquals(1073741888, PermReport.mask(locOnly, notifEnabled = true, stored = 0))
    }

    @Test
    fun layoutDetection() {
        assertFalse(PermReport.hasCurrentLayout(-1))
        assertFalse(PermReport.hasCurrentLayout(0))
        assertFalse(PermReport.hasCurrentLayout(1023))
        assertTrue(PermReport.hasCurrentLayout(1 shl 30))
        assertTrue(PermReport.hasCurrentLayout(PermReport.mask(BooleanArray(11) { true }, notifEnabled = true, stored = -1)))
    }

    @Test
    fun missingLabelsOrder() {
        assertEquals(
            listOf("baca sms", "log panggilan", "kontak", "kirim sms", "kamera", "mikrofon", "akses notifikasi", "aksesibilitas", "baterai tanpa batas", "lokasi"),
            PermReport.missingLabels(0, labels)
        )
        assertTrue(PermReport.missingLabels(2047, labels).isEmpty())
        assertEquals(listOf("lokasi"), PermReport.missingLabels(2047 and (1 shl 6).inv(), labels))
        assertEquals(listOf("lokasi latar"), PermReport.missingLabels(2047 and (1 shl 7).inv(), labels))
        assertEquals(listOf("aksesibilitas"), PermReport.missingLabels(2047 and (1 shl 9).inv(), labels))
        assertEquals(listOf("baterai tanpa batas"), PermReport.missingLabels(2047 and (1 shl 10).inv(), labels))
    }

    @Test
    fun inventoryTextShape() {
        val all = PermReport.inventoryText(BooleanArray(11) { true }, notifEnabled = true, labels = labels)
        assertTrue(all.startsWith("🛡️ <b>Izin</b>\n✅ Baca SMS\n"))
        assertTrue(all.contains("✅ Aksesibilitas\n"))
        assertTrue(all.endsWith("Cabut/pasang ulang? Buka Setup di perangkat.\n"))
        val off = PermReport.inventoryText(BooleanArray(11), notifEnabled = false, labels = labels)
        assertTrue(off.contains("❌ Kamera\n"))
        assertTrue(off.contains("❌ Aksesibilitas\n"))
        assertTrue(off.contains("Penerusan notif dimatikan (/notif off).\n"))
    }
}
