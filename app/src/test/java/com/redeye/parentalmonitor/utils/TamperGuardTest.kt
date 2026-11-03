package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TamperGuardTest {

    private val selfPackage = "com.redeye.parentalmonitor"
    private val selfLabel = "Red Eye"

    @Test
    fun layarAksesibilitasDiblokir() {
        val reason = TamperGuard.reason(
            "com.android.settings",
            "com.android.settings.accessibility.AccessibilitySettings",
            listOf("Aksesibilitas"),
            selfPackage,
            selfLabel
        )
        assertEquals("aksesibilitas", reason)
    }

    @Test
    fun detailAplikasiSendiriDiblokir() {
        val reason = TamperGuard.reason(
            "com.android.settings",
            "com.android.settings.applications.InstalledAppDetails",
            listOf("Red Eye", "Hapus"),
            selfPackage,
            selfLabel
        )
        assertEquals("hapus aplikasi", reason)
    }

    @Test
    fun detailAplikasiLainTidakDiblokir() {
        val reason = TamperGuard.reason(
            "com.android.settings",
            "com.android.settings.applications.InstalledAppDetails",
            listOf("WhatsApp", "Hapus"),
            selfPackage,
            selfLabel
        )
        assertNull(reason)
    }

    @Test
    fun dialogHapusDariPaketInstallerDiblokir() {
        val reason = TamperGuard.reason(
            "com.android.packageinstaller",
            "com.android.packageinstaller.uninstaller.UninstallUninstalledApp",
            listOf("Red Eye", "Hapus"),
            selfPackage,
            selfLabel
        )
        assertEquals("hapus aplikasi", reason)
    }

    @Test
    fun adminPerangkatSendiriDiblokir() {
        val reason = TamperGuard.reason(
            "com.android.settings",
            "com.android.settings.DeviceAdminAdd",
            listOf("Red Eye", "Nonaktifkan admin"),
            selfPackage,
            selfLabel
        )
        assertEquals("admin perangkat", reason)
    }

    @Test
    fun sumberTakDikenalDanOpsiPengembangDiblokir() {
        assertEquals(
            "sumber tak dikenal",
            TamperGuard.reason(
                "com.android.settings",
                "com.android.settings.applications.ManageExternalSourcesActivity",
                listOf("Sumber tidak dikenal"),
                selfPackage,
                selfLabel
            )
        )
        assertEquals(
            "opsi pengembang",
            TamperGuard.reason(
                "com.android.settings",
                "com.android.settings.development.DevelopmentSettingsDashboardActivity",
                listOf("Opsi pengembang"),
                selfPackage,
                selfLabel
            )
        )
    }

    @Test
    fun aplikasiBiasaTidakDiblokir() {
        val reason = TamperGuard.reason(
            "com.whatsapp",
            "com.whatsapp.Main",
            listOf("Hapus", "Aksesibilitas"),
            selfPackage,
            selfLabel
        )
        assertNull(reason)
    }

    @Test
    fun tenggangAksesibilitasDariJendelaAksesibilitas() {
        assertTrue(TamperGuard.inSetupGrace("aksesibilitas", 1000L, 1120L, 0L, 0L, 0L, 0L))
        assertFalse(TamperGuard.inSetupGrace("aksesibilitas", 1120L, 1120L, 0L, 0L, 0L, 0L))
        assertFalse(TamperGuard.inSetupGrace("aksesibilitas", 1000L, 0L, 0L, 0L, 0L, 0L))
    }

    @Test
    fun tenggangAdminDariJendelaAdmin() {
        assertTrue(TamperGuard.inSetupGrace("admin perangkat", 1000L, 0L, 1120L, 0L, 0L, 0L))
        assertFalse(TamperGuard.inSetupGrace("admin perangkat", 1120L, 0L, 1120L, 0L, 0L, 0L))
        assertFalse(TamperGuard.inSetupGrace("admin perangkat", 2000L, 0L, 1120L, 0L, 0L, 0L))
    }

    @Test
    fun tenggangTidakMenularAntarAlasan() {
        assertFalse(TamperGuard.inSetupGrace("admin perangkat", 1000L, 5000L, 0L, 0L, 0L, 0L))
        assertFalse(TamperGuard.inSetupGrace("aksesibilitas", 2000L, 1120L, 5000L, 0L, 0L, 0L))
    }

    // Issue #1007: bila cap elapsed ada, keputusan memakai
    // jam monoton — memundurkan jam dinding tak memperpanjang
    // tenggang, memajukannya tak memotong tenggang.
    @Test
    fun tenggangBerjangkarElapsedTahanGeseranJam() {
        // Dinding masih di dalam jendela, elapsed sudah lewat -> habis.
        assertFalse(TamperGuard.inSetupGrace("aksesibilitas", 1000L, 999_999L, 0L, 5000L, 2000L, 0L))
        // Dinding sudah jauh lewat (jam dimajukan), elapsed masih di dalam -> tetap tenggang.
        assertTrue(TamperGuard.inSetupGrace("aksesibilitas", 999_999L, 1120L, 0L, 1000L, 2000L, 0L))
        // Admin memakai cap elapsed admin, bukan cap settings.
        assertTrue(TamperGuard.inSetupGrace("admin perangkat", 1000L, 0L, 0L, 1000L, 0L, 2000L))
        assertFalse(TamperGuard.inSetupGrace("admin perangkat", 1000L, 0L, 999_999L, 5000L, 0L, 2000L))
    }

    @Test
    fun alasanLainDanTanpaAlasanTidakPernahBebas() {
        assertFalse(TamperGuard.inSetupGrace("hapus aplikasi", 1000L, 5000L, 5000L, 0L, 0L, 0L))
        assertFalse(TamperGuard.inSetupGrace("sumber tak dikenal", 1000L, 5000L, 5000L, 0L, 0L, 0L))
        assertFalse(TamperGuard.inSetupGrace(null, 1000L, 5000L, 5000L, 0L, 0L, 0L))
    }

    @Test
    fun deteksiAwalLayarSensitif() {
        assertTrue(TamperGuard.mayBeSensitive("com.android.settings"))
        assertTrue(TamperGuard.mayBeSensitive("com.android.packageinstaller"))
        assertFalse(TamperGuard.mayBeSensitive("com.whatsapp"))
    }
}
