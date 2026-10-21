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
    fun deteksiAwalLayarSensitif() {
        assertTrue(TamperGuard.mayBeSensitive("com.android.settings", "com.android.settings.Settings"))
        assertTrue(TamperGuard.mayBeSensitive("com.android.packageinstaller", "com.android.packageinstaller.UninstallerActivity"))
        assertFalse(TamperGuard.mayBeSensitive("com.whatsapp", "com.whatsapp.Main"))
    }
}
