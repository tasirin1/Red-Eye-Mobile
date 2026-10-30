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
    fun hasCodeDetectsPlainAndSeparatedDigitGroups() {
        assertTrue(NotificationPrivacy.hasCode("Kode 123456"))
        assertTrue(NotificationPrivacy.hasCode("PIN code is 123 456"))
        assertTrue(NotificationPrivacy.hasCode("kode 123-456"))
        assertFalse(NotificationPrivacy.hasCode("kode 123"))
        assertFalse(NotificationPrivacy.hasCode("pesan biasa tanpa angka"))
    }

    @Test
    fun masksDigitGroupsWithFixedLengthToken() {
        val out = NotificationPrivacy.mask("Kode 123456, pin 4321, rekening 1234567890")
        assertEquals("Kode ••••, pin ••••, rekening ••••", out)
    }

    @Test
    fun masksSeparatedCodesAndLongNumbers() {
        val out = NotificationPrivacy.mask("OTP 123 456, kode 123-456, nomor 123456789, panjang 1234567890123")
        assertEquals("OTP ••••, kode ••••, nomor ••••, panjang ••••", out)
        assertEquals("kartu ••••", NotificationPrivacy.mask("kartu 1234 5678 9012 3456"))
    }

    @Test
    fun masksAlphanumericCodesAndIrregularSeparators() {
        assertEquals("Kode verifikasi Anda: ••••", NotificationPrivacy.mask("Kode verifikasi Anda: X7K9P2"))
        assertEquals("Your verification code is ••••, valid 5 minutes", NotificationPrivacy.mask("Your verification code is A1B2C3, valid 5 minutes"))
        assertEquals("token ••••", NotificationPrivacy.mask("token 9F8E7D"))
        assertEquals("kode ••••", NotificationPrivacy.mask("kode AB12CD34EF"))
        assertEquals("Kode OTP ••••", NotificationPrivacy.mask("Kode OTP 123  456"))
        assertEquals("kode ••••", NotificationPrivacy.mask("kode 123 - 456"))
    }

    @Test
    fun leavesPlainSentencesAndLongTokensUntouched() {
        assertEquals(
            "Kode verifikasi sudah dikirim ke aplikasi Anda",
            NotificationPrivacy.mask("Kode verifikasi sudah dikirim ke aplikasi Anda")
        )
        assertEquals("kode AB12CD34EF5", NotificationPrivacy.mask("kode AB12CD34EF5"))
        assertEquals(
            "ID transaksi TRXAB12CD34EF56GH selesai",
            NotificationPrivacy.mask("ID transaksi TRXAB12CD34EF56GH selesai")
        )
    }

    @Test
    fun leavesNonOtpTextUntouched() {
        val text = "Pesan biasa tanpa angka"
        assertEquals(text, NotificationPrivacy.mask(text))
    }
}
