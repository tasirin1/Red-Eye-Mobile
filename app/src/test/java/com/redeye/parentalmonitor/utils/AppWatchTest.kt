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
    fun batasKataMenolakPotonganKataUmum() {
        assertNull(AppWatch.hit(listOf("mati"), "matikan"))
        assertNull(AppWatch.hit(listOf("mati"), "mode otomatis aktif"))
        assertNull(AppWatch.hit(listOf("mati"), "belajar matematika"))
        assertNull(AppWatch.hit(listOf("judi"), "Judika tampil di TV"))
        assertNull(AppWatch.hit(listOf("judi"), "main judol yuk"))
        assertNull(AppWatch.hit(listOf("mati"), "suara yang mematikan"))
        assertNull(AppWatch.hit(listOf("mati"), "hp dimatikan tadi"))
    }

    @Test
    fun batasKataMenoleransiImbuhanDanPemanjangan() {
        assertEquals("judi", AppWatch.hit(listOf("judi"), "dia berjudi semalam"))
        assertEquals("judi", AppWatch.hit(listOf("judi"), "judinya ketahuan"))
        assertEquals("judi", AppWatch.hit(listOf("judi"), "judiii seru katanya"))
        assertEquals("judi", AppWatch.hit(listOf("judi"), "perjudian online marak"))
        assertEquals("mati", AppWatch.hit(listOf("mati"), "kematian itu pasti"))
        assertEquals("mati", AppWatch.hit(listOf("mati"), "dia bilang mau mati saja"))
        assertEquals("judol", AppWatch.hit(listOf("judol"), "main judol yuk"))
        assertEquals("judol", AppWatch.hit(listOf("judol"), "judolnya lagi viral"))
        assertEquals("pinjol", AppWatch.hit(listOf("pinjol"), "pinjolnya sudah lunas"))
        assertEquals("bully", AppWatch.hit(listOf("bully"), "dia dibully di sekolah"))
        assertEquals("bully", AppWatch.hit(listOf("bully"), "bullying itu salah"))
    }

    @Test
    fun kasusTepiImbuhanGandaKataUlangDanBatangTersembunyi() {
        assertNull(AppWatch.hit(listOf("judi"), "dia memperjudikan nasibnya"))
        assertNull(AppWatch.hit(listOf("pergi"), "kepergiannya kemarin"))
        assertNull(AppWatch.hit(listOf("main"), "memainkan bola"))
        assertNull(AppWatch.hit(listOf("aman"), "amanah yang dijaga"))
        assertEquals("bully", AppWatch.hit(listOf("bully"), "bully-bully itu berulang"))
        assertEquals("aman", AppWatch.hit(listOf("aman"), "keamanan data terjaga"))
        assertEquals("tidur", AppWatch.hit(listOf("tidur"), "ketiduran di kelas"))
    }

    @Test
    fun potonganKonteksDiSekitarKataCocok() {
        assertEquals("dia berjudi semalam", AppWatch.contextSnippet("judi", "dia berjudi semalam"))
        assertEquals("Kemarin dia BERJUDI di warung", AppWatch.contextSnippet("judi", "Kemarin dia BERJUDI di warung"))
        assertEquals("judinya ketahuan", AppWatch.contextSnippet("judi", "judinya ketahuan"))
        assertEquals("baris satu berjudi baris tiga", AppWatch.contextSnippet("judi", "baris satu\nberjudi\nbaris tiga"))
        assertNull(AppWatch.contextSnippet("mati", "belajar matematika di sekolah"))
        assertNull(AppWatch.contextSnippet("judi", "main judol yuk"))
        val panjang = AppWatch.contextSnippet("judi", "x".repeat(100) + " berjudi " + "y".repeat(100))
        assertEquals(89, panjang?.length)
        assertTrue(panjang!!.startsWith("\u2026") && panjang.endsWith("\u2026") && panjang.contains("berjudi"))
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

    @Test
    fun paketBankNyataDikenali() {
        assertTrue(AppWatch.isSensitivePackage("ovo.id"))
        assertTrue(AppWatch.isSensitivePackage("id.co.bri.brimo"))
        assertTrue(AppWatch.isSensitivePackage("com.bca.mybca"))
        assertTrue(AppWatch.isSensitivePackage("id.dana"))
        assertTrue(AppWatch.isSensitivePackage("com.gojek.gopay"))
        assertTrue(AppWatch.isSensitivePackage("com.shopee.id"))
        assertTrue(AppWatch.isSensitivePackage("id.co.linkaja"))
        assertTrue(AppWatch.isSensitivePackage("id.flip"))
        assertTrue(AppWatch.isSensitivePackage("com.jago.digitalBanking"))
        assertTrue(AppWatch.isSensitivePackage("com.btpn.dc"))
        assertTrue(AppWatch.isSensitivePackage("id.co.seabank"))
        assertTrue(AppWatch.isSensitivePackage("src.com.bni"))
        assertTrue(AppWatch.isSensitivePackage("id.bni.wondr"))
        assertTrue(AppWatch.isSensitivePackage("id.bmri.livin"))
        assertTrue(AppWatch.isSensitivePackage("id.co.cimbniaga.mobile.android"))
        assertTrue(AppWatch.isSensitivePackage("com.dbank.mobile"))
        assertTrue(AppWatch.isSensitivePackage("com.permatabank"))
        assertTrue(AppWatch.isSensitivePackage("com.bsm.activity2"))
        assertTrue(AppWatch.isSensitivePackage("net.myinfosys.PermataMobileX"))
        assertTrue(AppWatch.isSensitivePackage("id.com.uiux.mobile"))
        assertTrue(AppWatch.isSensitivePackage("com.ocbcnisp.onemobileapp"))
        assertTrue(AppWatch.isSensitivePackage("com.alloapp.yump"))
        assertTrue(AppWatch.isSensitivePackage("com.panin.mypanin"))
        assertTrue(AppWatch.isSensitivePackage("com.panin.mobilepanin"))
        assertTrue(AppWatch.isSensitivePackage("com.muamalatdin"))
        assertTrue(AppWatch.isSensitivePackage("id.hsbc.hsbcindonesia"))
        assertFalse(AppWatch.isSensitivePackage("com.grabtaxi.passenger"))
        assertFalse(AppWatch.isSensitivePackage("com.mobile.legends"))
        assertFalse(AppWatch.isSensitivePackage("com.netflix.mediaclient"))
        assertFalse(AppWatch.isSensitivePackage("com.google.android.youtube"))
        assertFalse(AppWatch.isSensitivePackage("com.tokopedia.tkpd"))
        assertFalse(AppWatch.isSensitivePackage("flipboard.app"))
    }
}
