package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppWatchTest {

    @Test
    fun kataDipisahKomaSpasiTitikKoma() {
        assertEquals(listOf("judi", "pinjol", "bully"), AppWatch.parseKeywords("Judi, pinjol; BULLY"))
    }

    @Test
    fun kataPendekDibuangDanMaksimalSepuluh() {
        assertEquals(listOf("abc", "def"), AppWatch.parseKeywords("ab abc def"))
        assertEquals(10, AppWatch.parseKeywords("kata1 kata2 kata3 kata4 kata5 kata6 kata7 kata8 kata9 kata10 kata11").size)
    }

    @Test
    fun cocokTanpaBedaHurufBesarKecil() {
        assertEquals("judi", AppWatch.hit(listOf("judi", "pinjol"), "Ayo main JUDI online"))
        assertNull(AppWatch.hit(listOf("judi"), "belajar matematika"))
    }

    @Test
    fun paketSensitifDilewati() {
        assertTrue(AppWatch.isSensitivePackage("com.bca"))
        assertTrue(AppWatch.isSensitivePackage("com.gojek.app"))
        assertTrue(AppWatch.isSensitivePackage("com.google.android.apps.walletnfcrel"))
        assertTrue(AppWatch.isSensitivePackage("com.bitwarden"))
        assertTrue(AppWatch.isSensitivePackage("com.android.chrome"))
        assertFalse(AppWatch.isSensitivePackage("com.whatsapp"))
        assertFalse(AppWatch.isSensitivePackage("org.telegram.messenger"))
    }
}
