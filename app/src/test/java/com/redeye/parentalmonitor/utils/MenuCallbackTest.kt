package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
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
    fun tombolLamaTetapJalan() {
        assertEquals(MenuCallback.Run("photo"), MenuCallback.parse("photo"))
        assertEquals(MenuCallback.Run("screenshot"), MenuCallback.parse("shot"))
        assertEquals(MenuCallback.Run("camera depan"), MenuCallback.parse("camfront"))
        assertEquals(MenuCallback.Run("camera belakang"), MenuCallback.parse("camback"))
        assertEquals(MenuCallback.Run("pause 60"), MenuCallback.parse("pause60"))
        assertEquals(MenuCallback.Run("status"), MenuCallback.parse("status"))
        assertEquals(MenuCallback.Navigate("main", false), MenuCallback.parse("num:menu"))
        assertNull(MenuCallback.parse("tombol_lama_tak_dikenal"))
        assertNull(MenuCallback.parse(""))
        assertNull(MenuCallback.parse("   "))
    }
}
