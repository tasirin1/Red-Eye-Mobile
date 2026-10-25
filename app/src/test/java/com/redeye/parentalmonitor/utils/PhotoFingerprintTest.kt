package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoFingerprintTest {
    @Test
    fun countsDifferingBits() {
        assertEquals(0, PhotoFingerprint.hammingDistance(0L, 0L))
        assertEquals(1, PhotoFingerprint.hammingDistance(1L, 0L))
        assertEquals(3, PhotoFingerprint.hammingDistance(0b1011L, 0L))
        assertEquals(2, PhotoFingerprint.hammingDistance(Long.MIN_VALUE, 1L))
    }
}
