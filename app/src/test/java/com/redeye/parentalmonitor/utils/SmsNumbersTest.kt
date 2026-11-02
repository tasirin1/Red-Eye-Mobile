package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsNumbersTest {
    @Test
    fun detectsEmergencyNumbers() {
        assertTrue(SmsNumbers.isEmergency("112"))
        assertTrue(SmsNumbers.isEmergency("911"))
        assertTrue(SmsNumbers.isEmergency("110"))
        assertTrue(SmsNumbers.isEmergency("011 911"))
        assertFalse(SmsNumbers.isEmergency("08123456789"))
        assertFalse(SmsNumbers.isEmergency(""))
    }

    @Test
    fun detectsPremiumNumbers() {
        assertTrue(SmsNumbers.isPremium("1900"))
        assertTrue(SmsNumbers.isPremium("+1 900 555 1234"))
        assertTrue(SmsNumbers.isPremium("+44 70 1234 5678"))
        assertTrue(SmsNumbers.isPremium("976"))
        assertTrue(SmsNumbers.isPremium("12345"))
        // #641 (dugaan): awalan 0809 adalah layanan premium Indonesia (bukan
        // awalan seluler reguler) — sebelumnya lolos semua aturan premium.
        assertTrue(SmsNumbers.isPremium("080912345678"))
        // #662: bentuk internasional +62809…/62809… juga premium.
        assertTrue(SmsNumbers.isPremium("+6280912345678"))
        assertTrue(SmsNumbers.isPremium("6280912345678"))
        // Kontrol: nomor seluler Indonesia biasa format internasional aman.
        assertFalse(SmsNumbers.isPremium("+6281234567890"))
    }

    @Test
    fun emergencyVariantsAreNotPremium() {
        assertFalse(SmsNumbers.isPremium("0112"))
        assertFalse(SmsNumbers.isPremium("00911"))
        assertFalse(SmsNumbers.isPremium("0118"))
        assertFalse(SmsNumbers.isPremium("00112"))
        assertTrue(SmsNumbers.isEmergency("0112"))
    }

    @Test
    fun keepsEmergencyAndNormalNumbersOutOfPremium() {
        assertFalse(SmsNumbers.isPremium("112"))
        assertFalse(SmsNumbers.isPremium("911"))
        assertFalse(SmsNumbers.isPremium("081234567890"))
        assertFalse(SmsNumbers.isPremium("+6281234567890"))
    }
}
