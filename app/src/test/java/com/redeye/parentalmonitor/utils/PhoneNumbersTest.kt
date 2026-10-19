package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNumbersTest {

    @Test
    fun varianAlternatif62Dan08() {
        assertEquals("08123456789", PhoneNumbers.altVariant("628123456789"))
        assertEquals("628123456789", PhoneNumbers.altVariant("08123456789"))
    }

    @Test
    fun varianAlternatifNullBilaTakCocok() {
        assertNull(PhoneNumbers.altVariant(""))
        assertNull(PhoneNumbers.altVariant("62812"))
        assertNull(PhoneNumbers.altVariant("1234567890123"))
    }

    @Test
    fun nomorSamaWalauAwalanBeda() {
        assertTrue(PhoneNumbers.numbersEqualFast("08123456789", "628123456789", null))
        assertTrue(PhoneNumbers.numbersEqualFast("628123456789", "08123456789", null))
        assertFalse(PhoneNumbers.numbersEqualFast("628111111111", "08123456789", null))
    }

    @Test
    fun cocokLagiLewatSufiks() {
        assertTrue(PhoneNumbers.numbersEqualFast("1234567890123", "4567890123", null))
    }

    @Test
    fun nomorPendekHarusCocokPersis() {
        assertTrue(PhoneNumbers.matchesPrecomputed("0812 walk-in", "0812", null))
        assertFalse(PhoneNumbers.matchesPrecomputed("0812 walk-in", "0813", null))
        assertFalse(PhoneNumbers.matchesPrecomputed("", "628", null))
    }

    @Test
    fun nomorPenuhDibersihkanDariPemisah() {
        assertTrue(PhoneNumbers.matchesPrecomputed("+62 812-3456-789", "628123456789", null))
    }
}
