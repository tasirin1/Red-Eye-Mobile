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
    fun matchesOnlyExactValidPins() {
        assertTrue(AccessPin.verify("123456", "123456"))
        assertTrue(AccessPin.verify(" 123456 ", "123456"))
        assertFalse(AccessPin.verify("123456", "123457"))
        assertFalse(AccessPin.verify("123456", "12345"))
        assertFalse(AccessPin.verify("12345", "12345"))
    }
}
