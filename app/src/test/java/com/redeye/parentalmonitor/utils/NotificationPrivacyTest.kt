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
        assertTrue(NotificationPrivacy.isOtp("Your code is 123456"))
        assertTrue(NotificationPrivacy.isOtp("Please verify 998877"))
        assertFalse(NotificationPrivacy.isOtp("Promo belanja hari ini"))
        assertFalse(NotificationPrivacy.isOtp("Shopping promo 12345"))
    }

    @Test
    fun masksFourToTwelveDigitGroups() {
        val out = NotificationPrivacy.mask("Kode 123456, pin 4321, rekening 1234567890")
        assertEquals("Kode ••••••, pin ••••, rekening ••••••••••", out)
    }

    @Test
    fun masksSeparatedCodesAndKeepsLongNumbers() {
        val out = NotificationPrivacy.mask("OTP 123 456, kode 123-456, nomor 123456789, panjang 1234567890123")
        assertEquals("OTP ••• •••, kode •••-•••, nomor •••••••••, panjang 1234567890123", out)
    }

    @Test
    fun leavesNonOtpTextUntouched() {
        val text = "Pesan biasa tanpa angka"
        assertEquals(text, NotificationPrivacy.mask(text))
    }
}
