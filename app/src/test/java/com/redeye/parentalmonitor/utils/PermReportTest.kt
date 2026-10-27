package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermReportTest {
    private val labels = arrayOf("Baca SMS", "Log panggilan", "Kontak", "Kirim SMS", "Kamera", "Mikrofon", "Lokasi", "Lokasi latar", "Akses notifikasi", "Baterai tanpa batas")

    @Test
    fun maskSemantics() {
        assertEquals(1023, PermReport.mask(BooleanArray(10) { true }, notifEnabled = false, stored = -1))
        assertEquals(128, PermReport.mask(BooleanArray(10), notifEnabled = true, stored = -1))
        assertEquals(0, PermReport.mask(BooleanArray(10), notifEnabled = true, stored = 0))
        assertEquals(384, PermReport.mask(BooleanArray(10), notifEnabled = false, stored = 384))
        val locOnly = BooleanArray(10).also { it[6] = true }
        assertEquals(64, PermReport.mask(locOnly, notifEnabled = true, stored = 0))
    }

    @Test
    fun missingLabelsOrder() {
        assertEquals(
            listOf("baca sms", "log panggilan", "kontak", "kirim sms", "kamera", "mikrofon", "akses notifikasi", "baterai tanpa batas", "lokasi"),
            PermReport.missingLabels(0, labels)
        )
        assertTrue(PermReport.missingLabels(1023, labels).isEmpty())
        assertEquals(listOf("lokasi"), PermReport.missingLabels(1023 and (1 shl 6).inv(), labels))
        assertEquals(listOf("lokasi latar"), PermReport.missingLabels(1023 and (1 shl 7).inv(), labels))
    }

    @Test
    fun inventoryTextShape() {
        val all = PermReport.inventoryText(BooleanArray(10) { true }, notifEnabled = true, labels = labels)
        assertTrue(all.startsWith("🛡️ <b>Izin</b>\n✅ Baca SMS\n"))
        assertTrue(all.endsWith("Cabut/pasang ulang? Buka Setup di perangkat.\n"))
        val off = PermReport.inventoryText(BooleanArray(10), notifEnabled = false, labels = labels)
        assertTrue(off.contains("❌ Kamera\n"))
        assertTrue(off.contains("Penerusan notif dimatikan (/notif off).\n"))
    }
}
