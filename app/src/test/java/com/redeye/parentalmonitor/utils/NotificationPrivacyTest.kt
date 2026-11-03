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
    fun detectsOtpKeywordsAttachedToDigitsAndCompounds() {
        assertTrue(NotificationPrivacy.isOtp("OTP123456"))
        assertTrue(NotificationPrivacy.isOtp("PIN1234 adalah rahasia"))
        assertTrue(NotificationPrivacy.isOtp("KodeOTP: 123456"))
        assertTrue(NotificationPrivacy.isOtp("kodeotp 998877"))
        assertTrue(NotificationPrivacy.isOtp("KodeOTP123456"))
        assertFalse(NotificationPrivacy.isOtp("pindah rumah besok"))
        assertFalse(NotificationPrivacy.isOtp("tokenisasi aset digital"))
        assertFalse(NotificationPrivacy.isOtp("kodeks etik profesi"))
        assertFalse(NotificationPrivacy.isOtp("codename aplikasi rahasia"))
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
    fun masksCommaSlashColonSeparatedCodes() {
        // #912: pemisah koma/garis-miring/titik-dua dulu tidak
        // dikenal pola grup digit sehingga OTP tampil polos.
        assertEquals("Kode verifikasi Anda ••••", NotificationPrivacy.mask("Kode verifikasi Anda 123,456"))
        assertEquals("OTP ••••", NotificationPrivacy.mask("OTP 123/456"))
        assertEquals("kode ••••", NotificationPrivacy.mask("kode 123:456:789"))
        assertTrue(NotificationPrivacy.hasCode("kode 123,456"))
        assertTrue(NotificationPrivacy.hasCode("kode 123/456"))
    }

    @Test
    fun masksUnderscoreSeparatedCodes() {
        // #955: pemisah garis-bawah dulu tak dikenal pola grup
        // digit, jadi OTP "123_456" tampil polos.
        assertEquals("Kode verifikasi Anda ••••", NotificationPrivacy.mask("Kode verifikasi Anda 123_456"))
        assertTrue(NotificationPrivacy.hasCode("kode 123_456"))
    }

    @Test
    fun masksHyphenGroupedAlphanumericCodes() {
        // #660: kode bergrup-strip membelah token jadi potongan 2 karakter
        // yang terlalu pendek untuk pola digit maupun token menyambung.
        assertEquals("Kode Anda ••••", NotificationPrivacy.mask("Kode Anda AB-12-CD-34"))
        assertEquals("Kode akses ••••", NotificationPrivacy.mask("Kode akses A1-B2-C3-D4"))
        assertEquals("Kode Anda: ••••", NotificationPrivacy.mask("Kode Anda: AB-1234-CD"))
        // Kontrol: dua grup saja (kode promo, model perangkat) bukan pola ini.
        assertEquals("Kode promo HEMAT-50", NotificationPrivacy.mask("Kode promo HEMAT-50"))
        // #694: pemisah titik/garis-bawah/campuran dan spasi antar grup campuran.
        assertEquals("Kode Anda ••••", NotificationPrivacy.mask("Kode Anda AB.12.CD.34"))
        assertEquals("Kode Anda ••••", NotificationPrivacy.mask("Kode Anda AB_12_CD_34"))
        assertEquals("Kode Anda ••••", NotificationPrivacy.mask("Kode Anda AB-12.CD-34"))
        assertEquals("Kode Anda ••••", NotificationPrivacy.mask("Kode Anda AB12 CD34 EF56"))
        assertEquals("Alamat RT 04 RW 05", NotificationPrivacy.mask("Alamat RT 04 RW 05"))
    }

    @Test
    fun leavesPlainSentencesAndLongTokensUntouched() {
        assertEquals(
            "Kode verifikasi sudah dikirim ke aplikasi Anda",
            NotificationPrivacy.mask("Kode verifikasi sudah dikirim ke aplikasi Anda")
        )
        // #654: batas atas token kode dinaikkan 10 -> 16 agar kode OTP
        // alfanumerik yang lebih panjang ikut tertopeng; token yang lebih
        // panjang lagi (ID transaksi dsb.) tetap utuh seperti sebelumnya.
        assertEquals("kode ••••", NotificationPrivacy.mask("kode AB12CD34EF5"))
        assertEquals("kode ••••", NotificationPrivacy.mask("kode A1B2C3D4E5F6"))
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
