package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityProbeTest {

    @Test
    fun daftarKosongBerartiMati() {
        assertFalse(AccessibilityProbe.enabledIn(null, "com.a/.Svc", "com.a/.Svc"))
        assertFalse(AccessibilityProbe.enabledIn("", "com.a/.Svc", "com.a/.Svc"))
        assertFalse(AccessibilityProbe.enabledIn("   ", "com.a/.Svc", "com.a/.Svc"))
    }

    @Test
    fun cocokBentukPanjangDanPendek() {
        assertTrue(AccessibilityProbe.enabledIn("com.a/.Svc", "com.a/.Svc", "com.a/.Svc"))
        assertTrue(AccessibilityProbe.enabledIn("com.a/com.a.Svc", "com.a/com.a.Svc", "com.a/.Svc"))
        assertTrue(AccessibilityProbe.enabledIn("com.b/.Other:com.a/com.a.Svc", "com.a/com.a.Svc", "com.a/.Svc"))
    }

    @Test
    fun abaikanSpasiDanBedaHurufBesarKecil() {
        assertTrue(AccessibilityProbe.enabledIn(" com.a/.Svc :com.b/.Other ", "com.a/.svc", "com.a/.Svc"))
    }

    @Test
    fun tidakCocokDenganPaketLain() {
        assertFalse(AccessibilityProbe.enabledIn("com.b/.Svc", "com.a/.Svc", "com.a/.Svc"))
        assertFalse(AccessibilityProbe.enabledIn("com.a/.SvcLain", "com.a/.Svc", "com.a/.Svc"))
    }
}
