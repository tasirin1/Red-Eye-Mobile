package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuCallbackTest {

    @Test
    fun navigasiMenuBaru() {
        assertEquals(MenuCallback.Navigate("main", false), MenuCallback.parse("menu:open"))
        assertEquals(MenuCallback.Navigate("main", false), MenuCallback.parse("menu:angka"))
        assertEquals(MenuCallback.Navigate("pantau", true), MenuCallback.parse("m:pantau"))
        assertEquals(MenuCallback.Navigate("main", true), MenuCallback.parse("m:main"))
    }

    @Test
    fun navigasiViewTakDikenalJatuhKeMenuUtama() {
        assertEquals(MenuCallback.Navigate("main", true), MenuCallback.parse("m:entah"))
        assertEquals(MenuCallback.Navigate("main", true), MenuCallback.parse("m:angka"))
        assertEquals("main", MenuCallback.viewName("entah"))
        assertEquals("alat", MenuCallback.viewName("alat"))
        assertTrue(!MenuCallback.VIEWS.contains("angka"))
    }

    @Test
    fun perintahLangsung() {
        assertEquals(MenuCallback.Run("status"), MenuCallback.parse("x:status"))
        assertEquals(MenuCallback.Run("patrol on"), MenuCallback.parse("x:patrol on"))
        assertEquals(MenuCallback.Run("patrol ON"), MenuCallback.parse("x:/PATROL ON"))
        assertEquals(MenuCallback.Run("camera Depan"), MenuCallback.parse("x:CAMERA Depan"))
        assertEquals(MenuCallback.Run("sms 0812 Halo"), MenuCallback.parse("x:sms 0812 Halo"))
        assertNull(MenuCallback.parse("x:"))
        assertNull(MenuCallback.parse("x:   "))
    }

    @Test
    fun segarkanLaporanDiTempat() {
        assertEquals(MenuCallback.Refresh("status"), MenuCallback.parse("segar:status"))
        assertEquals(MenuCallback.Refresh("battery"), MenuCallback.parse("segar:battery"))
        assertEquals(MenuCallback.Refresh("storage"), MenuCallback.parse("segar:/storage"))
        assertEquals(MenuCallback.Refresh("location"), MenuCallback.parse("segar:location"))
        assertEquals(MenuCallback.Refresh("lastcalls"), MenuCallback.parse("segar:lastcalls"))
        assertEquals(MenuCallback.Refresh("lastsms"), MenuCallback.parse("segar:lastsms"))
        assertEquals(MenuCallback.Refresh("lastnotify"), MenuCallback.parse("segar:lastnotify"))
        assertEquals(MenuCallback.Refresh("track"), MenuCallback.parse("segar:track"))
        assertNull(MenuCallback.parse("segar:photo"))
        assertNull(MenuCallback.parse("segar:apps"))
        assertNull(MenuCallback.parse("segar:stop"))
        assertNull(MenuCallback.parse("segar:"))
        assertNull(MenuCallback.parse("segar:status extra"))
    }

    @Test
    fun mintaAngkaAtauTeks() {
        assertEquals(MenuCallback.AskInput("record", ""), MenuCallback.parse("num:record"))
        assertEquals(MenuCallback.AskInput("record", "media"), MenuCallback.parse("num:media:record"))
        assertEquals(MenuCallback.AskInput("sms", "komu"), MenuCallback.parse("i:komu:sms"))
        assertEquals(MenuCallback.AskInput("home", "lokasi"), MenuCallback.parse("i:lokasi:home"))
        assertEquals(MenuCallback.AskInput("sms", ""), MenuCallback.parse("i:sms"))
        assertEquals(MenuCallback.AskInput("photointerval", ""), MenuCallback.parse("num:entah:photointerval"))
        assertNull(MenuCallback.parse("num:"))
        assertNull(MenuCallback.parse("i:komu:"))
    }

    @Test
    fun saklarToggle() {
        assertEquals(MenuCallback.Toggle("notify", "komu"), MenuCallback.parse("tg:komu:notify"))
        // #902: data saklar membawa keadaan label (1/0); format
        // lama tanpa potongan itu tetap terbaca dengan harapan
        // null, nilai liar dianggap tanpa harapan.
        assertEquals(MenuCallback.Toggle("notify", "komu", true), MenuCallback.parse("tg:komu:notify:1"))
        assertEquals(MenuCallback.Toggle("notify", "komu", false), MenuCallback.parse("tg:komu:notify:0"))
        assertEquals(MenuCallback.Toggle("notify", "komu", null), MenuCallback.parse("tg:komu:notify:x"))
        assertEquals(MenuCallback.Toggle("patrol", "pantau"), MenuCallback.parse("tg:pantau:patrol"))
        assertEquals(MenuCallback.Toggle("datasaver", "pantau"), MenuCallback.parse("tg:pantau:datasaver"))
        assertEquals(MenuCallback.Toggle("otp", "komu"), MenuCallback.parse("tg:komu:OTP"))
        // View tak dikenal jatuh ke menu utama (seperti Navigate).
        assertEquals(MenuCallback.Toggle("night", "main"), MenuCallback.parse("tg:entah:night"))
        // Kunci di luar daftar saklar ditolak: data liar tak boleh
        // membalik setelan.
        assertNull(MenuCallback.parse("tg:pantau:lock"))
        assertNull(MenuCallback.parse("tg:komu:"))
        assertNull(MenuCallback.parse("tg:notif"))
        assertNull(MenuCallback.parse("tg:"))
    }

    @Test
    fun tombolLamaTetapJalan() {
        assertEquals(MenuCallback.Run("photo"), MenuCallback.parse("photo"))
        assertEquals(MenuCallback.Run("screenshot"), MenuCallback.parse("shot"))
        assertEquals(MenuCallback.Run("camera front"), MenuCallback.parse("camfront"))
        assertEquals(MenuCallback.Run("camera back"), MenuCallback.parse("camback"))
        assertEquals(MenuCallback.Run("pause 60"), MenuCallback.parse("pause60"))
        assertEquals(MenuCallback.Run("status"), MenuCallback.parse("status"))
        assertEquals(MenuCallback.Navigate("main", false), MenuCallback.parse("num:menu"))
        assertNull(MenuCallback.parse("tombol_lama_tak_dikenal"))
        assertNull(MenuCallback.parse(""))
        assertNull(MenuCallback.parse("   "))
    }

    @Test
    fun legacySemuaMenunjukPerintahHidup() {
        // #903 (jalan lolosnya #898): tiap nilai LEGACY wajib
        // terurai ke Run yang sama DAN kepalanya perintah kanonis
        // yang dikenal — rename yang lupa memperbarui peta ini
        // membuat uji ini merah, bukan tombol yang mati diam-diam.
        for ((mentah, tujuan) in MenuCallback.LEGACY) {
            assertEquals("LEGACY[$mentah]", MenuCallback.Run(tujuan), MenuCallback.parse(mentah))
            assertTrue(
                "LEGACY[$mentah] -> /${tujuan.substringBefore(' ')} tak dikenal",
                CommandSuggest.isKnown("/" + tujuan.substringBefore(" "))
            )
        }
    }

    @Test
    fun saklarSemuaKunciPunyaPerintahHidup() {
        // #903: tiap kunci saklar wajib punya perintah on & off
        // yang kepalanya dikenal.
        for (kunci in MenuCallback.TOGGLE_KEYS) {
            for (nyala in listOf(true, false)) {
                val perintah = MenuCallback.toggleCommand(kunci, nyala)
                assertNotNull("toggleCommand($kunci, $nyala)", perintah)
                assertTrue(
                    "toggleCommand($kunci) -> $perintah tak dikenal",
                    CommandSuggest.isKnown(perintah!!.substringBefore(" "))
                )
            }
        }
        assertNull(MenuCallback.toggleCommand("takada", true))
    }

    @Test
    fun jepretUlangFotoDanLayar() {
        assertEquals(MenuCallback.JepretUlang("b"), MenuCallback.parse("refoto:b"))
        assertEquals(MenuCallback.JepretUlang("f"), MenuCallback.parse("refoto:f"))
        assertEquals(MenuCallback.JepretUlang("f"), MenuCallback.parse("refoto:F"))
        assertEquals(MenuCallback.JepretUlang("s"), MenuCallback.parse("reshot"))
    }

    @Test
    fun jepretUlangModeLiarDitolak() {
        assertNull(MenuCallback.parse("refoto:"))
        assertNull(MenuCallback.parse("refoto:x"))
        assertNull(MenuCallback.parse("refoto:s"))
        assertNull(MenuCallback.parse("reshotx"))
    }
}
