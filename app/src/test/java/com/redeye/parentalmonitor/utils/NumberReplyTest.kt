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
    fun perintahTidakDianggapAngka() {
        assertNull(NumberReply.parse("/photointerval 15"))
        assertNull(NumberReply.parse("help me"))
    }
}
