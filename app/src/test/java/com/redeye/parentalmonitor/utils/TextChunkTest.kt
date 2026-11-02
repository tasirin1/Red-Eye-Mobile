package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class TextChunkTest {

    @Test
    fun teksPendekTidakDipotong() {
        assertEquals(0, TextChunk.safeCut("", 10))
        assertEquals(3, TextChunk.safeCut("abc", 10))
        assertEquals(0, TextChunk.safeCut("abc", 0))
    }

    @Test
    fun batasBiasaDipakaiApaAdanya() {
        assertEquals(3, TextChunk.safeCut("abcdef", 3))
        assertEquals(6, TextChunk.safeCut("halo&x dunia", 6))
    }

    @Test
    fun pasanganSurrogateTakDibelah() {
        val teks = "ab\uD83D\uDE00cd"
        assertEquals(2, TextChunk.safeCut(teks, 3))
        assertEquals(4, TextChunk.safeCut(teks, 4))
    }

    @Test
    fun benderaIndikatorRegionalTakDibelah() {
        // #647: pasangan indikator regional (bendera) adalah dua code point —
        // membelahnya menghasilkan dua huruf regional yatim di dua potongan.
        val teks = "ab\uD83C\uDDE6\uD83C\uDDE7cd"
        assertEquals(2, TextChunk.safeCut(teks, 4))
        assertEquals(6, TextChunk.safeCut(teks, 6))
        assertEquals(2, TextChunk.safeCut(teks, 3))
    }

    @Test
    fun urutanZwjTakDibelah() {
        // #647: potongan tidak boleh jatuh di tengah urutan ZWJ (emoji keluarga).
        val teks = "a\uD83D\uDC68\u200D\uD83D\uDC69b"
        assertEquals(1, TextChunk.safeCut(teks, 3))
        assertEquals(1, TextChunk.safeCut(teks, 4))
    }

    @Test
    fun entitasHtmlTakDibelah() {
        assertEquals(4, TextChunk.safeCut("halo&nbsp;dunia", 6))
    }

    @Test
    fun tagHtmlTakDibelah() {
        assertEquals(4, TextChunk.safeCut("halo<b>dunia", 6))
        assertEquals(7, TextChunk.safeCut("halo<b>dunia", 7))
    }

    @Test
    fun selaluAdaKemajuanBilaTeksTidakKosong() {
        assertEquals(1, TextChunk.safeCut("&nbsp;abc", 1))
    }

    @Test
    fun jatuhKeBatasAmanBilaTakAdaTitikAman() {
        val teks = "<" + "a".repeat(70) + ">tail"
        assertEquals(6, TextChunk.safeCut(teks, 70))
    }
}
