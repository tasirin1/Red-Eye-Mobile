package com.redeye.parentalmonitor.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Issue #1672: NumberHistorySearch adalah logika murni
// by-design (kueri diinjeksi sebagai lambda) tetapi tak
// punya uji sama sekali. Uji ini memaku kontraknya:
// varian persis 0xx/+62xx, bendera terpotong pada tepat
// limit, jalur fuzzy hanya untuk nomor >=10 digit, dan
// pengumpulan kecocokan lintas halaman (issue #1000).
// Lapisan Android tak disentuh: satu-satunya sentuhan
// framework di kelas ini adalah Log.w pada jalur pindai
// tak tuntas TANPA kecocokan — jalur itu sengaja tidak
// dipicu di sini.
class NumberHistorySearchTest {

    private data class Baris(val nomor: String, val tanggal: Long, val id: Long)

    // Tiruan setia ketiga bentuk kueri produksi: persis
    // (kesetaraan mentah terhadap varian), fuzzy (LIKE =
    // digit pola sebagai subsekuens digit nomor tersimpan),
    // dan halaman (jendela tanggal/id menurun + batas
    // 365 hari). Urutan pembeda penting: pilihan halaman
    // juga memuat " OR ", jadi ">= ?" diperiksa lebih dulu.
    private class KueriPalsu(private val data: List<Baris>) {
        val panggilan = ArrayList<String>()
        var argsTerakhir: Array<String>? = null

        fun jalankan(selection: String?, args: Array<String>?, sortOrder: String, limit: Int): List<Baris> {
            val sel = selection ?: ""
            argsTerakhir = args
            return when {
                sel.contains("LIKE") -> {
                    panggilan.add("fuzzy")
                    val digitPola = (args?.getOrNull(0) ?: "").filter { it.isDigit() }
                    data.filter { subsekuens(it.nomor.filter { c -> c.isDigit() }, digitPola) }
                        .sortedWith(compareByDescending<Baris> { it.tanggal }.thenByDescending { it.id })
                        .take(limit)
                }
                sel.contains(">= ?") -> {
                    panggilan.add("halaman")
                    val a = args ?: emptyArray()
                    val cutoff = a[0].toLong()
                    val upperDate = a[1].toLong()
                    val upperId = a[3].toLong()
                    data.filter { it.tanggal >= cutoff && (it.tanggal < upperDate || (it.tanggal == upperDate && it.id < upperId)) }
                        .sortedWith(compareByDescending<Baris> { it.tanggal }.thenByDescending { it.id })
                        .take(limit)
                }
                sel.contains(" OR ") -> {
                    panggilan.add("persis")
                    val varian = (args ?: emptyArray()).dropLast(1).toSet()
                    data.filter { it.nomor in varian }
                        .sortedWith(compareByDescending<Baris> { it.tanggal }.thenByDescending { it.id })
                        .take(limit)
                }
                else -> {
                    panggilan.add("lain")
                    emptyList()
                }
            }
        }

        private fun subsekuens(teks: String, pola: String): Boolean {
            var i = 0
            for (c in teks) if (i < pola.length && c == pola[i]) i++
            return i == pola.length
        }
    }

    private fun pencarian(kueri: KueriPalsu): NumberHistorySearch<Baris> =
        NumberHistorySearch(
            numberColumn = "nomor",
            dateColumn = "tanggal",
            idColumn = "_id",
            logTag = "UjiNumberHistorySearch",
            query = kueri::jalankan,
            numberOf = { it.nomor },
            dateOf = { it.tanggal },
            idOf = { it.id }
        )

    private val kini = System.currentTimeMillis()

    @Test
    fun masukanTanpaDigitKosongTanpaKueri() {
        val kueri = KueriPalsu(emptyList())
        val hasil = pencarian(kueri).search("abc-def")
        assertTrue(hasil.items.isEmpty())
        assertFalse(hasil.truncated)
        assertTrue(kueri.panggilan.isEmpty())
    }

    @Test
    fun persisMenemukanVarianNolDanKodeNegara() {
        val data = listOf(
            Baris("+6281234567890", kini - 1_000, 3),
            Baris("081234567890", kini - 2_000, 2),
            Baris("089999999999", kini - 500, 9)
        )
        val kueri = KueriPalsu(data)
        val hasil = pencarian(kueri).search("0812 3456 7890")
        assertEquals(2, hasil.items.size)
        assertFalse(hasil.truncated)
        assertEquals(listOf("persis"), kueri.panggilan)
        // Kueri persis membawa keempat varian + cap kini.
        val varian = (kueri.argsTerakhir ?: emptyArray()).dropLast(1).toSet()
        assertEquals(setOf("081234567890", "+081234567890", "6281234567890", "+6281234567890"), varian)
    }

    @Test
    fun persisTerpotongPadaTepatLimit() {
        val data = (1..5).map { Baris("081234567890", kini - it * 1_000L, it.toLong()) }
        val kueri = KueriPalsu(data)
        val hasil = pencarian(kueri).search("081234567890", limit = 3)
        assertEquals(3, hasil.items.size)
        assertTrue(hasil.truncated)
    }

    @Test
    fun nomorPendekTidakTurunKeFuzzy() {
        val data = listOf(Baris("081234567", kini - 1_000, 1))
        val kueri = KueriPalsu(data)
        val hasil = pencarian(kueri).search("081234599")
        assertTrue(hasil.items.isEmpty())
        // Hanya kueri persis yang boleh berjalan untuk <10 digit.
        assertEquals(listOf("persis"), kueri.panggilan)
    }

    @Test
    fun fuzzyMenangkapAkhiranYangLolosPersis() {
        // Nomor tersimpan membawa awalan asing: kueri persis
        // (kesetaraan mentah) melewatkannya, LIKE subsekuens
        // mengembalikannya, dan saringan akhiran >=10 digit
        // (PhoneNumbers) menerimanya.
        val data = listOf(Baris("99081234567890", kini - 1_000, 1))
        val kueri = KueriPalsu(data)
        val hasil = pencarian(kueri).search("081234567890")
        assertEquals(1, hasil.items.size)
        assertFalse(hasil.truncated)
        assertEquals(listOf("persis", "fuzzy"), kueri.panggilan)
    }

    @Test
    fun pindaiHalamanMengumpulkanKecocokanLintasHalaman() {
        // 500 baris tak cocok mengisi halaman pertama; dua
        // baris cocok berformat bebas (persis melewatkannya
        // karena kesetaraan mentah, fuzzy melewatkannya
        // karena digit pola bukan subsekuens digitnya) hanya
        // tertangkap saringan pindai halaman di halaman kedua
        // (perilaku issue #1000).
        val takCocok = (1..500).map { Baris("087777777777", kini - it * 1_000L, 10_000L + it) }
        val cocok = listOf(
            Baris("+62 812-3456-7890", kini - 600_000, 5),
            Baris("62 812 3456 7890", kini - 700_000, 4)
        )
        val data = takCocok + cocok
        val kueri = KueriPalsu(data)
        val hasil = pencarian(kueri).search("081234567890")
        assertEquals(2, hasil.items.size)
        assertFalse(hasil.truncated)
        assertEquals(listOf("persis", "fuzzy", "halaman", "halaman"), kueri.panggilan)
    }

    @Test
    fun pindaiHalamanBerhentiPadaPlafonDuaHalaman() {
        // Plafon issue #1931: 2 halaman x 500 per repositori.
        // Satu baris cocok terperangkap saringan halaman di
        // halaman pertama, diikuti dua halaman penuh tak
        // cocok dan sisa 2 baris. Di bawah plafon lama
        // (4 halaman) pindai berlanjut ke halaman ketiga,
        // tuntas, dan truncated=false; di bawah plafon baru
        // pindai berhenti sesudah halaman kedua sehingga
        // hasilnya terpotong. Jalur Log.w tak terpanggil
        // karena kecocokan tidak kosong.
        val cocok = Baris("+62 812-3456-7890", kini - 1_000L, 9_999L)
        val takCocok = (1..1_001).map { Baris("087777777777", kini - (it + 1) * 1_000L, 9_000L - it) }
        val data = listOf(cocok) + takCocok
        val kueri = KueriPalsu(data)
        val hasil = pencarian(kueri).search("081234567890")
        assertEquals(1, hasil.items.size)
        assertTrue(hasil.truncated)
        assertEquals(listOf("persis", "fuzzy", "halaman", "halaman"), kueri.panggilan)
    }
}
