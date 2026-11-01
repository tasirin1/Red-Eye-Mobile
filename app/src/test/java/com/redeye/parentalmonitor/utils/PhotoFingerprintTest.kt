package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun comparableRuleExcludesZeroAndOne() {
        assertFalse(PhotoFingerprint.isComparableFingerprint(0L))
        assertFalse(PhotoFingerprint.isComparableFingerprint(1L))
        assertTrue(PhotoFingerprint.isComparableFingerprint(2L))
        assertTrue(PhotoFingerprint.isComparableFingerprint(0b1010L))
        assertTrue(PhotoFingerprint.isComparableFingerprint(-1L))
    }

    @Test
    fun duplicateRuleSkipsZeroAndOneOnEitherSide() {
        val patterned = 0x5555555555555555L
        val skewed = 0x7FL
        assertFalse(PhotoFingerprint.isDuplicateFingerprint(0L, patterned))
        assertFalse(PhotoFingerprint.isDuplicateFingerprint(patterned, 0L))
        assertFalse(PhotoFingerprint.isDuplicateFingerprint(0L, 0L))
        assertFalse(PhotoFingerprint.isDuplicateFingerprint(1L, 1L))
        assertFalse(PhotoFingerprint.isDuplicateFingerprint(1L, patterned))
        assertFalse(PhotoFingerprint.isDuplicateFingerprint(patterned, 1L))
        assertEquals(6, PhotoFingerprint.hammingDistance(skewed, 1L))
        assertFalse(PhotoFingerprint.isDuplicateFingerprint(skewed, 1L))
        assertFalse(PhotoFingerprint.isDuplicateFingerprint(1L, skewed))
    }

    @Test
    fun duplicateRuleComparesPatternedHashes() {
        val patterned = 0x5555555555555555L
        val skewed = 0x7FL
        assertTrue(PhotoFingerprint.isDuplicateFingerprint(patterned, patterned))
        assertTrue(PhotoFingerprint.isDuplicateFingerprint(patterned, patterned xor 1L))
        assertTrue(PhotoFingerprint.isDuplicateFingerprint(skewed, skewed))
        assertFalse(PhotoFingerprint.isDuplicateFingerprint(patterned, patterned.inv()))
    }

    @Test
    fun cellAveragesFollowCellMajorityNotCenterPixel() {
        val a = IntArray(256) { i -> if (i % 16 < 8) 40 else 200 }
        val b = a.copyOf()
        for (cy in 0 until 4) {
            for (cx in 4 until 8) {
                val x0 = cx * 2
                val y0 = cy * 2
                b[y0 * 16 + x0] = 40
                b[y0 * 16 + x0 + 1] = 40
                b[(y0 + 1) * 16 + x0] = 40
                b[(y0 + 1) * 16 + x0 + 1] = 200
            }
        }
        val fa = PhotoFingerprint.fingerprintBits(PhotoFingerprint.cellAverages(a, 16, 16))
        val fb = PhotoFingerprint.fingerprintBits(PhotoFingerprint.cellAverages(b, 16, 16))
        assertTrue(PhotoFingerprint.hammingDistance(fa, fb) > 6)
        assertFalse(PhotoFingerprint.isDuplicateFingerprint(fa, fb))
        val noisy = a.copyOf()
        noisy[0] = 45
        val fn = PhotoFingerprint.fingerprintBits(PhotoFingerprint.cellAverages(noisy, 16, 16))
        assertTrue(PhotoFingerprint.isDuplicateFingerprint(fa, fn))
        val uniform = PhotoFingerprint.cellAverages(IntArray(256) { 150 }, 16, 16)
        assertTrue(uniform.all { it == 150 })
        assertEquals(0L, PhotoFingerprint.fingerprintBits(uniform))
    }

    @Test
    fun countsDifferingBits() {
        assertEquals(0, PhotoFingerprint.hammingDistance(0L, 0L))
        assertEquals(1, PhotoFingerprint.hammingDistance(1L, 0L))
        assertEquals(3, PhotoFingerprint.hammingDistance(0b1011L, 0L))
        assertEquals(2, PhotoFingerprint.hammingDistance(Long.MIN_VALUE, 1L))
    }
}
