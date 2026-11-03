package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTrimTest {

    @Test
    fun shortMessageUnchanged() {
        assertEquals("halo", MessageTrim.trim("halo"))
    }

    @Test
    fun exactlyAtLimitUnchanged() {
        val msg = "a".repeat(MessageTrim.MAX_CHARS)
        assertEquals(msg, MessageTrim.trim(msg))
    }

    @Test
    fun overLimitCutToLimitWithSuffix() {
        val hasil = MessageTrim.trim("a".repeat(MessageTrim.MAX_CHARS + 1))
        assertEquals(MessageTrim.MAX_CHARS, hasil.length)
        assertTrue(hasil.endsWith(MessageTrim.SUFFIX))
    }

    // Pasangan surrogate tepat di batas potong (31965) tidak
    // boleh terbelah: emoji utuh dibuang, hasil 31999 aksara.
    // Nilai harapan dari porting Python 1:1 (issue #977).
    @Test
    fun surrogatePairAtCutBoundaryNotSplit() {
        val msg = "a".repeat(31964) + "\uD83D\uDE00" + "b".repeat(100)
        val hasil = MessageTrim.trim(msg)
        assertEquals(31999, hasil.length)
        assertTrue(hasil.endsWith(MessageTrim.SUFFIX))
        val sebelumSuffix = hasil[hasil.length - MessageTrim.SUFFIX.length - 1]
        assertFalse(Character.isHighSurrogate(sebelumSuffix))
        assertFalse(Character.isLowSurrogate(sebelumSuffix))
    }
}
