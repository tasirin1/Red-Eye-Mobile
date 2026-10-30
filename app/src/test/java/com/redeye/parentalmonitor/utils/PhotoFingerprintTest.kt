package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoFingerprintTest {
    @Test
    fun uniformGridHasNoFingerprint() {
        assertEquals(0L, PhotoFingerprint.fingerprintBits(IntArray(64) { 200 }))
        assertEquals(0L, PhotoFingerprint.fingerprintBits(IntArray(64) { 0 }))
        assertEquals(0L, PhotoFingerprint.fingerprintBits(IntArray(64) { 37 }))
    }

    @Test
    fun patternedGridBits() {
        val checker = IntArray(64) { i -> if (i % 2 == 0) 255 else 0 }
        assertEquals(0x5555555555555555L, PhotoFingerprint.fingerprintBits(checker))
        val gradient = IntArray(64) { i -> i * 4 }
        assertEquals(-4294967296L, PhotoFingerprint.fingerprintBits(gradient))
        val single = IntArray(64)
        single[0] = 255
        assertEquals(1L, PhotoFingerprint.fingerprintBits(single))
    }

    @Test
    fun countsDifferingBits() {
        assertEquals(0, PhotoFingerprint.hammingDistance(0L, 0L))
        assertEquals(1, PhotoFingerprint.hammingDistance(1L, 0L))
        assertEquals(3, PhotoFingerprint.hammingDistance(0b1011L, 0L))
        assertEquals(2, PhotoFingerprint.hammingDistance(Long.MIN_VALUE, 1L))
    }
}
