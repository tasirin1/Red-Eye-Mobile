package com.redeye.parentalmonitor.utils

// Gerbang mode aman crash-loop (keputusan pemilik
// 2026-11-12, stabilitas): logika murni di balik jurnal
// cap waktu crash yang dicatat CrashReporter. Mode aman
// aktif bila 3 crash terakhir membentuk gugus rapat
// (rentang <= JENDELA_PICU_MS) DAN crash terakhir belum
// lebih tua dari JENDELA_AMAN_MS — jadi mode aman
// bertahan 30 menit sesudah crash terakhir, lalu layanan
// berhak mencoba normal lagi sendiri. Crash yang
// tersebar jarang BUKAN loop dan tak pernah memicu.
object CrashLoopGate {

    const val AMBANG = 3
    const val JENDELA_PICU_MS = 10 * 60_000L
    const val JENDELA_AMAN_MS = 30 * 60_000L
    const val SIMPAN_MS = 24 * 60 * 60_000L
    const val MAKS_ENTRI = 50

    fun parse(teks: String): List<Long> {
        if (teks.isBlank()) return emptyList()
        val hasil = ArrayList<Long>()
        for (baris in teks.split('\n')) {
            val nilai = baris.trim().toLongOrNull() ?: continue
            if (nilai > 0L) hasil.add(nilai)
        }
        hasil.sort()
        return hasil
    }

    fun serialkan(journal: List<Long>): String {
        return journal.joinToString("\n")
    }

    // Catat satu crash: gabung, pangkas entri di luar
    // jendela simpan (selisih absolut, tahan jam mundur),
    // urutkan, batasi jumlah entri.
    fun catat(journal: List<Long>, nowMs: Long): List<Long> {
        val gabung = ArrayList<Long>(journal.size + 1)
        for (cap in journal) {
            if (cap > 0L && kotlin.math.abs(nowMs - cap) <= SIMPAN_MS) gabung.add(cap)
        }
        gabung.add(nowMs)
        gabung.sort()
        return if (gabung.size > MAKS_ENTRI) gabung.takeLast(MAKS_ENTRI) else gabung
    }

    fun modeAman(journal: List<Long>, nowMs: Long): Boolean {
        val urut = journal.filter { it in 1..nowMs }.sorted()
        if (urut.size < AMBANG) return false
        val terakhir = urut[urut.size - 1]
        if (nowMs - terakhir > JENDELA_AMAN_MS) return false
        val ketigaTerakhir = urut[urut.size - AMBANG]
        return terakhir - ketigaTerakhir <= JENDELA_PICU_MS
    }
}
