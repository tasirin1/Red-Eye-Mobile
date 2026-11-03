package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PriorityPackagesTest {

    @Test
    fun exactSegmentTokensMatch() {
        assertTrue(PriorityPackages.isPriority("id.co.bank"))
        assertTrue(PriorityPackages.isPriority("com.bri"))
        assertTrue(PriorityPackages.isPriority("ovo"))
    }

    @Test
    fun brandPrefixesMatch() {
        assertTrue(PriorityPackages.isPriority("com.brimo"))
        assertTrue(PriorityPackages.isPriority("com.gopay"))
    }

    // Merek menyatu (issue #970).
    @Test
    fun fusedBankBrandsMatch() {
        assertTrue(PriorityPackages.isPriority("com.seabank"))
        assertTrue(PriorityPackages.isPriority("com.mobilebanking"))
        assertTrue(PriorityPackages.isPriority("id.cimbniaga"))
    }

    // Substring bebas tidak boleh lolos (issue #933).
    @Test
    fun substringLookalikesDoNotMatch() {
        assertFalse(PriorityPackages.isPriority("com.brightness"))
        assertFalse(PriorityPackages.isPriority("id.danais.app"))
        assertFalse(PriorityPackages.isPriority("com.bni46"))
        assertFalse(PriorityPackages.isPriority("com.example.chat"))
    }
}
