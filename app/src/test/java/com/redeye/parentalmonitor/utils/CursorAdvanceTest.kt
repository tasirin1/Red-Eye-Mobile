package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorAdvanceTest {

    private fun ambil(rows: List<Pair<Long, Long>>, idCursor: Long, dateCursor: Long): List<Pair<Long, Long>> {
        return rows.filter { it.first > idCursor || it.second > dateCursor }
            .sortedWith(compareBy({ it.second }, { it.first }))
            .take(100)
    }

    private fun konsumsiSemua(rows: List<Pair<Long, Long>>, idAwal: Long = 0L, dateAwal: Long = 0L): List<Pair<Long, Long>> {
        val terkonsumsi = mutableListOf<Pair<Long, Long>>()
        var idCursor = idAwal
        var dateCursor = dateAwal
        var putaran = 0
        while (putaran < 50) {
            putaran++
            val page = ambil(rows, idCursor, dateCursor)
            if (page.isEmpty()) break
            val idMulai = idCursor
            val dateMulai = dateCursor
            for (part in page.chunked(10)) {
                terkonsumsi.addAll(part)
                val last = part.last()
                idCursor = last.first
                dateCursor = last.second
            }
            if (page.size < 100) {
                val tuntas = CursorAdvance.penyelesaian(idMulai, dateMulai, page)
                idCursor = tuntas.first
                dateCursor = tuntas.second
            }
        }
        return terkonsumsi
    }

    @Test
    fun penyelesaianMengambilMaksimumPerBidang() {
        val hasil = CursorAdvance.penyelesaian(5L, 900L, listOf(999L to 100L, 3L to 2000L, 50L to 50L))
        assertEquals(999L to 2000L, hasil)
    }

    @Test
    fun penyelesaianBarisKosongMempertahankanKursor() {
        assertEquals(7L to 8L, CursorAdvance.penyelesaian(7L, 8L, emptyList()))
    }

    @Test
    fun barisSudutTidakTerlewat() {
        val rows = mutableListOf(9999L to 1000L)
        for (i in 1L..99L) rows.add(i to 2000L)
        rows.add(500L to 2000L)
        val terkonsumsi = konsumsiSemua(rows)
        assertTrue(terkonsumsi.contains(500L to 2000L))
        assertEquals(rows.toSet(), terkonsumsi.toSet())
    }

    @Test
    fun banjirRestoreSemuaTerkonsumsi() {
        val rows = mutableListOf<Pair<Long, Long>>()
        for (i in 0L..199L) rows.add(8000L + i to 1000L + i)
        for (i in 1L..50L) rows.add(i to 5000L + i)
        val terkonsumsi = konsumsiSemua(rows)
        assertEquals(rows.toSet(), terkonsumsi.toSet())
    }

    @Test
    fun kursorDiagonalTetapMaju() {
        val rows = listOf(9999L to 1000L, 500L to 500L, 500L to 3000L, 200L to 4000L)
        val terkonsumsi = konsumsiSemua(rows, 9999L, 1000L)
        assertEquals(setOf(500L to 3000L, 200L to 4000L), terkonsumsi.toSet())
    }
}
