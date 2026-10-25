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
    }

    @Test
    fun keepsEmergencyAndNormalNumbersOutOfPremium() {
        assertFalse(SmsNumbers.isPremium("112"))
        assertFalse(SmsNumbers.isPremium("911"))
        assertFalse(SmsNumbers.isPremium("081234567890"))
        assertFalse(SmsNumbers.isPremium("+6281234567890"))
    }
}
