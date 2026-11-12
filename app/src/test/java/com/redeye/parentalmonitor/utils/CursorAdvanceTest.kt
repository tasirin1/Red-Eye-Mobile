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

    // Model SETIA disiplin sapuan produksi (issue #2012): siklus
    // waktu-nyata mengambil keyset + calon _ID di atas jangkar,
    // disaring CursorAdvance.terlambat, digabung urut (tanggal,
    // id); jangkar maju per potongan terkirim via idMaks.
    private fun konsumsiSapuan(
        barisAwal: List<Pair<Long, Long>>,
        sisipan: List<Pair<Long, Long>>,
        idCursor0: Long,
        dateCursor0: Long,
        idMax0: Long
    ): List<Pair<Long, Long>> {
        val rows = barisAwal.toMutableList()
        val terkonsumsi = mutableListOf<Pair<Long, Long>>()
        var idCursor = idCursor0
        var dateCursor = dateCursor0
        var idMax = idMax0
        var disisipkan = false
        var putaran = 0
        while (putaran < 10) {
            putaran++
            if (!disisipkan && putaran == 2) {
                rows.addAll(sisipan)
                disisipkan = true
            }
            val utama = rows.filter { it.second > dateCursor || (it.second == dateCursor && it.first > idCursor) }
                .sortedWith(compareBy({ it.second }, { it.first })).take(100)
            val calon = rows.filter { it.first > idMax }
                .sortedBy { it.first }.take(100)
            val telat = calon.filter { CursorAdvance.terlambat(it.first, it.second, dateCursor, idMax, Long.MAX_VALUE) }
            val page = if (telat.isEmpty()) utama else (telat + utama).sortedWith(compareBy({ it.second }, { it.first }))
            if (page.isEmpty()) {
                if (disisipkan) break else continue
            }
            val idMulai = idCursor
            val dateMulai = dateCursor
            for (part in page.chunked(10)) {
                terkonsumsi.addAll(part)
                val adv = CursorAdvance.penyelesaian(idCursor, dateCursor, part)
                idCursor = adv.first
                dateCursor = adv.second
                idMax = CursorAdvance.idMaks(idMax, part)
            }
            val tuntas = CursorAdvance.penyelesaian(idMulai, dateMulai, page)
            idCursor = tuntas.first
            dateCursor = tuntas.second
            idMax = CursorAdvance.idMaks(idMax, page)
        }
        return terkonsumsi
    }

    @Test
    fun idMaksMengambilIdTerbesarTerkirim() {
        assertEquals(11L, CursorAdvance.idMaks(10L, listOf(11L to 1000L, 5L to 3000L)))
        assertEquals(10L, CursorAdvance.idMaks(10L, emptyList()))
    }

    @Test
    fun terlambatHanyaUntukSisipanTerlambat() {
        // Baris panggilan panjang: id baru, tanggal di bawah kursor.
        assertTrue(CursorAdvance.terlambat(11L, 1000L, 2000L, 10L, Long.MAX_VALUE))
        // Tanggal == kursor adalah wilayah keyset, bukan sapuan.
        assertTrue(!CursorAdvance.terlambat(11L, 2000L, 2000L, 10L, Long.MAX_VALUE))
        // Tanggal sesudah kursor juga wilayah keyset.
        assertTrue(!CursorAdvance.terlambat(11L, 3000L, 2000L, 10L, Long.MAX_VALUE))
        // Id yang sudah terkirim tak disapu ulang.
        assertTrue(!CursorAdvance.terlambat(9L, 1000L, 2000L, 10L, Long.MAX_VALUE))
        // Melewati batas masa depan (#1374) ditunda, bukan disapu.
        assertTrue(!CursorAdvance.terlambat(11L, 1000L, 2000L, 10L, 500L))
    }

    // Model konsumsi sapuan sisi SMS (issue #2020): cermin
    // konsumsiSapuan dengan disiplin rangkap-tiga SMS —
    // pasangan (id, tanggal) + jangkar id maksimum; halaman
    // gabungan keyset + sapuan terpilih predikat
    // getNewSms (INBOX disaring di lapisan repository,
    // di luar model ini).
    private fun ambilSms(
        baris: List<Pair<Long, Long>>,
        idKursor: Long,
        tanggalKursor: Long,
        idMaxKursor: Long,
        batas: Long
    ): List<Pair<Long, Long>> {
        if (idKursor <= 0L || tanggalKursor <= 0L) return emptyList()
        val keyset = baris.filter { (id, tanggal) ->
            tanggal <= batas && (tanggal > tanggalKursor || (tanggal == tanggalKursor && id > idKursor))
        }
        if (idMaxKursor <= 0L) return keyset
        val calon = baris.filter { (id, tanggal) -> id > idMaxKursor && tanggal <= batas }.sortedBy { it.first }.take(100)
        val sapuan = calon.filter { (id, tanggal) ->
            CursorAdvance.terlambat(id, tanggal, tanggalKursor, idMaxKursor, batas)
        }
        return (keyset + sapuan).sortedWith(compareBy({ it.second }, { it.first }))
    }

    private fun konsumsiSapuanSms(
        baris: List<Pair<Long, Long>>,
        idKursor0: Long,
        tanggalKursor0: Long,
        idMax0: Long,
        batas: Long = CursorAdvance.batasAtas(1_700_000_000_000L)
    ): List<Pair<Long, Long>> {
        var idKursor = idKursor0
        var tanggalKursor = tanggalKursor0
        var idMax = idMax0
        val terkirim = mutableListOf<Pair<Long, Long>>()
        while (true) {
            val halaman = ambilSms(baris, idKursor, tanggalKursor, idMax, batas)
            if (halaman.isEmpty()) break
            for (potongan in halaman.chunked(10)) {
                val maju = CursorAdvance.penyelesaian(idKursor, tanggalKursor, potongan)
                idKursor = maju.first
                tanggalKursor = maju.second
                idMax = CursorAdvance.idMaks(idMax, potongan)
                terkirim += potongan
            }
        }
        return terkirim
    }

    @Test
    fun sapuanSmsMengirimSmsTertahanSmscTepatSatuKali() {
        // Skenario Audit-175 A4: SMS B bercap 1000 tersinkron
        // (jangkar 50); SMS A bercap SMSC 900 disisipkan
        // belakangan ber-id 61 — tanpa sapuan hilang senyap.
        val baris = listOf(50L to 1000L, 61L to 900L)
        val terkirim = konsumsiSapuanSms(baris, 50L, 1000L, 50L)
        assertEquals(listOf(61L to 900L), terkirim)
    }

    @Test
    fun tanpaJangkarSmsBarisTertahanHilang() {
        // Kontrol negatif: jangkar 0 mematikan sapuan —
        // baris yang sama tak terpilih keyset.
        val baris = listOf(50L to 1000L, 61L to 900L)
        assertTrue(konsumsiSapuanSms(baris, 50L, 1000L, 0L).isEmpty())
    }

    // Model konsumsi sapuan dengan halaman terputus +
    // topi jangkar terbit (issue #2021): dua variabel
    // jangkar seperti produksi — maksimum benar di memori
    // siklus berjalan, nilai terbit (tertopi) yang
    // terpersist dan menjadi awal siklus berikut.
    private fun konsumsiSapuanTerputus(
        baris: List<Pair<Long, Long>>,
        idCursor0: Long,
        dateCursor0: Long,
        idMax0: Long,
        putusSesudahPotongan: Int,
        pakaiTopi: Boolean
    ): List<Pair<Long, Long>> {
        var idCursor = idCursor0
        var dateCursor = dateCursor0
        var terpersist = idMax0
        val terkirim = mutableListOf<Pair<Long, Long>>()
        var putaran = 0
        while (putaran < 10) {
            putaran++
            val utama = baris.filter { it.second > dateCursor || (it.second == dateCursor && it.first > idCursor) }
                .sortedWith(compareBy({ it.second }, { it.first })).take(100)
            val calon = baris.filter { it.first > terpersist }
                .sortedBy { it.first }.take(100)
            val telat = calon.filter { CursorAdvance.terlambat(it.first, it.second, dateCursor, terpersist, Long.MAX_VALUE) }
            val page = if (telat.isEmpty()) utama else (telat + utama).sortedWith(compareBy({ it.second }, { it.first }))
            if (page.isEmpty()) break
            val terlambatHalaman = page.filter { it.second < dateCursor }.map { it.first }.toSet()
            val terlambatTerkirim = mutableSetOf<Long>()
            var idMaxBenar = terpersist
            val potongan = page.chunked(10)
            for ((indeks, part) in potongan.withIndex()) {
                if (putaran == 1 && indeks >= putusSesudahPotongan) break
                terkirim.addAll(part)
                val adv = CursorAdvance.penyelesaian(idCursor, dateCursor, part)
                idCursor = adv.first
                dateCursor = adv.second
                idMaxBenar = CursorAdvance.idMaks(idMaxBenar, part)
                terlambatTerkirim.addAll(part.filter { it.second < dateCursor0 }.map { it.first })
                terpersist = if (pakaiTopi) {
                    CursorAdvance.jangkarTerbit(idMaxBenar, (terlambatHalaman - terlambatTerkirim).minOrNull())
                } else {
                    idMaxBenar
                }
            }
        }
        return terkirim
    }

    @Test
    fun jangkarTerbitMenghormatiSisaTerlambat() {
        assertEquals(120L, CursorAdvance.jangkarTerbit(120L, null))
        assertEquals(94L, CursorAdvance.jangkarTerbit(120L, 95L))
        assertEquals(90L, CursorAdvance.jangkarTerbit(90L, 95L))
        assertEquals(120L, CursorAdvance.jangkarTerbit(120L, 121L))
    }

    @Test
    fun halamanTerputusDenganTopiBarisTerlambatTetapTerkirim() {
        // Sepuluh baris terlambat (tanggal 900-909, id besar)
        // di potongan pertama; baris terlambat ber-id 95
        // bertanggal 915 duduk di potongan kedua. Halaman
        // terputus sesudah potongan pertama.
        val baris = listOf(
            101L to 900L, 103L to 901L, 105L to 902L, 107L to 903L, 109L to 904L,
            111L to 905L, 113L to 906L, 115L to 907L, 117L to 908L, 120L to 909L,
            95L to 915L
        )
        val terkirim = konsumsiSapuanTerputus(baris, 50L, 1000L, 50L, 1, pakaiTopi = true)
        // Baris sudut terkirim tepat satu kali; duplikat
        // terbatas pada awalan terlambat yang terputus.
        assertEquals(1, terkirim.count { it == (95L to 915L) })
        assertEquals(21, terkirim.size)
    }

    @Test
    fun halamanTerputusTanpaTopiBarisTerlambatHilang() {
        // Kontrol negatif terhadap disiplin lama: jangkar
        // maksimum apa adanya melewati baris 95 selamanya.
        val baris = listOf(
            101L to 900L, 103L to 901L, 105L to 902L, 107L to 903L, 109L to 904L,
            111L to 905L, 113L to 906L, 115L to 907L, 117L to 908L, 120L to 909L,
            95L to 915L
        )
        val terkirim = konsumsiSapuanTerputus(baris, 50L, 1000L, 50L, 1, pakaiTopi = false)
        assertTrue(terkirim.none { it == (95L to 915L) })
    }

    @Test
    fun sisipanTerlambatTerkirimTepatSekali() {
        // Panggilan tunggu (id 10, tanggal 2000) sudah terkirim dan
        // kursor di (10, 2000) dengan jangkar 10; panggilan panjang
        // yang mulai pada 1000 baru menyisipkan barisnya (id 11)
        // sesudah berakhir -- tanpa sapuan ia hilang permanen.
        val terkonsumsi = konsumsiSapuan(
            barisAwal = listOf(10L to 2000L),
            sisipan = listOf(11L to 1000L),
            idCursor0 = 10L,
            dateCursor0 = 2000L,
            idMax0 = 10L
        )
        assertEquals(listOf(11L to 1000L), terkonsumsi)
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
