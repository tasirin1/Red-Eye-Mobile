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
    fun tokenTerencodeDanTerbelahBarisDitopeng() {
        // #707: fallback mengenal %3A dan token yang terbelah baris.
        val tok = "123456789:" + "A".repeat(40)
        assertEquals("url *** ok", Redact.token("url " + tok.replace(":", "%3A") + " ok"))
        val split = tok.substring(0, 20) + "\n" + tok.substring(20)
        assertEquals("***", Redact.token(split))
        assertEquals("baris satu\nbaris dua", Redact.token("baris satu\nbaris dua"))
    }

    @Test
    fun sisa707TokenKeduaEncodeKecilDanBelahDiTitikDua() {
        // #724: token terbelah kedua tetap ditopeng walau token utuh
        // pertama sudah kena penggantian langsung.
        val tok = "123456789:" + "A".repeat(40)
        val split = tok.substring(0, 20) + "\n" + tok.substring(20)
        val both = Redact.token("url $tok ok\n$split")
        assertEquals(false, both.contains("A".repeat(10)))
        assertEquals(2, both.split("***").size - 1)
        // %3a huruf kecil.
        assertEquals("url *** ok", Redact.token("url " + tok.replace(":", "%3a") + " ok"))
        // Terbelah tepat di pemisah ':' (kedua sisi).
        assertEquals("***", Redact.token("123456789:\n" + "A".repeat(40)))
        assertEquals("***", Redact.token("123456789\n:" + "A".repeat(40)))
        // Teks jinak berakhiran ':' tidak berubah.
        assertEquals("Error:\nlihat bawah", Redact.token("Error:\nlihat bawah"))
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
