package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessPinTest {
    @Test
    fun validatesLengthAndDigits() {
        assertTrue(AccessPin.isValid("123456"))
        assertTrue(AccessPin.isValid(" 123456789012 "))
        assertFalse(AccessPin.isValid("12345"))
        assertFalse(AccessPin.isValid("1234567890123"))
        assertFalse(AccessPin.isValid("12345a"))
        assertFalse(AccessPin.isValid(""))
    }

    @Test
    fun matchesOnlyExactPins() {
        assertTrue(AccessPin.verify("123456", "123456"))
        assertTrue(AccessPin.verify(" 123456 ", "123456"))
        assertFalse(AccessPin.verify("123456", "123457"))
        assertFalse(AccessPin.verify("123456", "12345"))
        assertFalse(AccessPin.verify("", ""))
        assertFalse(AccessPin.verify("123456", ""))
        // #645: PIN warisan di luar format masa kini tetap terverifikasi bila
        // persis sama — syarat format hanya untuk pembuatan PIN (isValid),
        // bukan untuk perbandingan; sebelumnya pemilik sah terkunci permanen
        // dan ikut mengunci gerbang restore bersama.
        assertTrue(AccessPin.verify("12345", "12345"))
        assertTrue(AccessPin.verify("1234567890123", "1234567890123"))
    }
}
