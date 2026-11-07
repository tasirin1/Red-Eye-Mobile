package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatTujuanMediaTest {

    // Urutan cadangan adalah aturan keamanan
    // (issue #1548/#1720): tujuan eksplisit yang
    // ditangkap saat perintah berjalan menang atas
    // segalanya.
    @Test
    fun explicitDestinationWins() {
        assertEquals("111", ChatTujuanMedia.resolusi("111", "222", "333"))
    }

    @Test
    fun overrideBeatsConfig() {
        assertEquals("222", ChatTujuanMedia.resolusi("", "222", "333"))
    }

    @Test
    fun configIsLastResort() {
        assertEquals("333", ChatTujuanMedia.resolusi("", "", "333"))
    }

    @Test
    fun allEmptyStaysEmpty() {
        assertEquals("", ChatTujuanMedia.resolusi("", "", ""))
    }
}
