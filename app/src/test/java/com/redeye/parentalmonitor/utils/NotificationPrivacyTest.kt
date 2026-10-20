package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPrivacyTest {
    @Test
    fun detectsOtpKeywords() {
        assertTrue(NotificationPrivacy.isOtp("Kode OTP Anda 123456"))
        assertTrue(NotificationPrivacy.isOtp("Verification code: 998877"))
        assertTrue(NotificationPrivacy.isOtp("Token akses baru"))
        assertFalse(NotificationPrivacy.isOtp("Promo belanja hari ini"))
    }

    @Test
    fun masksFourToEightDigitGroups() {
        val out = NotificationPrivacy.mask("Kode 123456, pin 4321, rekening 1234567890")
        assertTrue(out.contains("••••••"))
        assertTrue(out.contains("••••"))
        assertTrue(out.contains("1234567890"))
        assertFalse(out.contains("123456"))
    }

    @Test
    fun leavesNonOtpTextUntouched() {
        val text = "Pesan biasa tanpa angka"
        assertEquals(text, NotificationPrivacy.mask(text))
    }
}
