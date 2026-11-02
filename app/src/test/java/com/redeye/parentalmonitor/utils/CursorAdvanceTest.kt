package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorAdvanceTest {

    // Model SETIA disiplin produksi (issue #655): seleksi keyset repository
    // (DATE > kursorTanggal) OR (DATE = kursorTanggal AND _ID > kursorId),
    // urut DATE ASC, _ID ASC, halaman 100; kursor maju per potongan 10 baris
    // terkirim via penyelesaian + persist segera; penyelesaian akhir halaman
    // dari kursor awal halaman. Versi lama harness ini memodelkan disiplin
    // BERBEDA dari produksi (part.last() + penyelesaian hanya halaman
    // parsial) sehingga regresi #635 lolos.
    private fun ambil(rows: List<Pair<Long, Long>>, idCursor: Long, dateCursor: Long): List<Pair<Long, Long>> {
        val sel = when {
            idCursor > 0L && dateCursor > 0L ->
                rows.filter { it.second > dateCursor || (it.second == dateCursor && it.first > idCursor) }
            dateCursor > 0L -> rows.filter { it.second > dateCursor }
            else -> rows.filter { it.first > idCursor }
        }
        return sel.sortedWith(compareBy({ it.second }, { it.first })).take(100)
    }

    private fun konsumsiSemua(rows: List<Pair<Long, Long>>, idAwal: Long = 0L, dateAwal: Long = 0L): List<Pair<Long, Long>> {
        return konsumsi(rows, idAwal, dateAwal, -1)
    }

    private fun konsumsiTerputus(rows: List<Pair<Long, Long>>, putusSesudahPotongan: Int): List<Pair<Long, Long>> {
        return konsumsi(rows, 0L, 0L, putusSesudahPotongan)
    }

    private fun konsumsi(rows: List<Pair<Long, Long>>, idAwal: Long, dateAwal: Long, putusSesudahPotongan: Int): List<Pair<Long, Long>> {
        val terkonsumsi = mutableListOf<Pair<Long, Long>>()
        var idCursor = idAwal
        var dateCursor = dateAwal
        var potongan = 0
        var putus = putusSesudahPotongan
        var putaran = 0
        while (putaran < 60) {
            putaran++
            val page = ambil(rows, idCursor, dateCursor)
            if (page.isEmpty()) break
            val idMulai = idCursor
            val dateMulai = dateCursor
            var habis = true
            for (part in page.chunked(10)) {
                potongan++
                if (putus >= 0 && potongan > putus) {
                    habis = false
                    break
                }
                terkonsumsi.addAll(part)
                val adv = CursorAdvance.penyelesaian(idCursor, dateCursor, part)
                idCursor = adv.first
                dateCursor = adv.second
            }
            if (habis) {
                val tuntas = CursorAdvance.penyelesaian(idMulai, dateMulai, page)
                idCursor = tuntas.first
                dateCursor = tuntas.second
            } else {
                putus = -1 // siklus berikutnya berjalan normal
            }
        }
        return terkonsumsi
    }

    @Test
    fun penyelesaianMengambilMaksimumLeksikografik() {
        // (999,100) ber-id terbesar tetapi bertanggal kecil; pasangan
        // leksikografik terbesar adalah (3,2000) — BUKAN gabungan (999,2000)
        // yang melompati baris sudut (issue #655).
        val hasil = CursorAdvance.penyelesaian(5L, 900L, listOf(999L to 100L, 3L to 2000L, 50L to 50L))
        assertEquals(3L to 2000L, hasil)
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
        assertEquals(rows.size, terkonsumsi.size)
    }

    @Test
    fun barisSudutTidakTerlewatSaatPutusAntarPotongan() {
        val rows = mutableListOf(9999L to 1000L)
        for (i in 1L..99L) rows.add(i to 2000L)
        rows.add(500L to 2000L)
        val terkonsumsi = konsumsiTerputus(rows, 1)
        assertEquals(rows.toSet(), terkonsumsi.toSet())
        assertEquals(rows.size, terkonsumsi.size)
    }

    @Test
    fun banjirRestoreSemuaTerkonsumsi() {
        val rows = mutableListOf<Pair<Long, Long>>()
        for (i in 0L..199L) rows.add(8000L + i to 1000L + i)
        for (i in 1L..50L) rows.add(i to 5000L + i)
        val terkonsumsi = konsumsiSemua(rows)
        assertEquals(rows.toSet(), terkonsumsi.toSet())
        assertEquals(rows.size, terkonsumsi.size)
    }

    @Test
    fun kursorDiagonalTetapMaju() {
        val rows = listOf(9999L to 1000L, 500L to 500L, 500L to 3000L, 200L to 4000L)
        val terkonsumsi = konsumsiSemua(rows, 9999L, 1000L)
        assertEquals(setOf(500L to 3000L, 200L to 4000L), terkonsumsi.toSet())
    }
}
