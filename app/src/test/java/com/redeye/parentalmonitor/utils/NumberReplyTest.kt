package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NumberReplyTest {

    @Test
    fun angkaBulatDiterima() {
        assertEquals(15, NumberReply.parse("15"))
        assertEquals(0, NumberReply.parse("0"))
        assertEquals(5000, NumberReply.parse("5000"))
        assertEquals(7, NumberReply.parse("  7  "))
    }

    @Test
    fun bukanAngkaDitolak() {
        assertNull(NumberReply.parse(""))
        assertNull(NumberReply.parse("   "))
        assertNull(NumberReply.parse("help"))
        assertNull(NumberReply.parse("15a"))
        assertNull(NumberReply.parse("1.5"))
        assertNull(NumberReply.parse("-5"))
        assertNull(NumberReply.parse("+5"))
        assertNull(NumberReply.parse("1 5"))
        assertNull(NumberReply.parse("1234567"))
    }

    @Test
    fun jangkauanAngka() {
        assertEquals(false, NumberReply.inRange(4, "5-180"))
        assertEquals(true, NumberReply.inRange(5, "5-180"))
        assertEquals(true, NumberReply.inRange(180, "5-180"))
        assertEquals(false, NumberReply.inRange(181, "5-180"))
        assertEquals(true, NumberReply.inRange(0, "0-60"))
        assertEquals(false, NumberReply.inRange(5001, "50-5000"))
        assertEquals(true, NumberReply.inRange(10, ""))
        assertEquals(true, NumberReply.inRange(10, "abc"))
        assertEquals(true, NumberReply.inRange(10, "5"))
    }

    @Test
    fun perintahTidakDianggapAngka() {
        assertNull(NumberReply.parse("/photointerval 15"))
        assertNull(NumberReply.parse("help me"))
    }
}
