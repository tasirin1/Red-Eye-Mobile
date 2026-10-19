package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class RedactTest {

    @Test
    fun nilaiKosongJadiTeksKosong() {
        assertEquals("", Redact.token(null))
        assertEquals("", Redact.token(""))
    }

    @Test
    fun rahasiaPanjangDigantiMentah() {
        assertEquals("chat *** ok", Redact.token("chat 1234567890 ok", "1234567890"))
        assertEquals("halo", Redact.token("halo", "9999999999"))
    }

    @Test
    fun rahasiaAngkaPendekDibatasiDigit() {
        assertEquals("telp *** ok", Redact.token("telp 5551234 ok", "5551234"))
        assertEquals("x65551234y", Redact.token("x65551234y", "5551234"))
    }

    @Test
    fun rahasiaBukanAngkaDigantiMentah() {
        assertEquals("***", Redact.token("a:b", "a:b"))
    }

    @Test
    fun tokenTelegramTertangkapPolaCadangan() {
        val token = "12345:" + "A".repeat(35)
        assertEquals("x *** y", Redact.token("x $token y"))
    }
}
