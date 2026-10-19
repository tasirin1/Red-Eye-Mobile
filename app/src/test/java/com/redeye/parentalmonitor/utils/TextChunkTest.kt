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
