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

    // Issue #1009: cap elapsed persisten dari boot sebelumnya
    // tak boleh menghidupkan lagi tenggang sesudah reboot —
    // sisa elapsed di boot tempat cap ditulis tak pernah
    // melebihi GRACE_MS, jadi sisa raksasa = cap basi dan
    // keputusan jatuh ke dinding (sudah kedaluwarsa).
    @Test
    fun tenggangElapsedCapBasiLintasBootDitolak() {
        // Ditulis pada uptime 10 hari; reboot; uptime baru 1 jam,
        // dinding +1 jam dari penulisan -> bukan tenggang.
        assertFalse(TamperGuard.inSetupGrace("aksesibilitas", 1_700_003_600_000L, 1_700_000_120_000L, 0L, 3_600_000L, 864_120_000L, 0L))
        // Boot sama, sisa elapsed 119 detik -> masih tenggang.
        assertTrue(TamperGuard.inSetupGrace("aksesibilitas", 1_700_000_001_000L, 1_700_000_120_000L, 0L, 864_001_000L, 864_120_000L, 0L))
        // Boot sama, jam dimundurkan 1 jam di tengah tenggang,
        // sisa elapsed 60 detik -> tetap tenggang.
        assertTrue(TamperGuard.inSetupGrace("aksesibilitas", 1_699_996_460_000L, 1_700_000_120_000L, 0L, 864_060_000L, 864_120_000L, 0L))
        // Cap basi yang elapsed-nya sudah terlampaui uptime baru -> habis.
        assertFalse(TamperGuard.inSetupGrace("aksesibilitas", 1_700_003_600_000L, 1_700_000_120_000L, 0L, 900_000_000L, 864_120_000L, 0L))
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
