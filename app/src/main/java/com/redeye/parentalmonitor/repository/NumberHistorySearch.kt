package com.redeye.parentalmonitor.repository

// Plafon limit kueri repositori ringkasan: 201, BUKAN 200.
// Pemanggil ringkasan mengambil tepat 201 baris sebagai sonde —
// baris ke-201 adalah bukti data terpotong di 200 (issue #1661).
// Menekan plafon ini kembali ke 200 mematikan deteksi itu (issue #1664).
internal const val REPOSITORY_LIMIT_CAP = 201

class NumberHistorySearch<T>(
    private val numberColumn: String,
    private val dateColumn: String,
    private val idColumn: String,
    private val logTag: String,
    private val query: (selection: String?, args: Array<String>?, sortOrder: String, limit: Int) -> List<T>,
    private val numberOf: (T) -> String,
    private val dateOf: (T) -> Long,
    private val idOf: (T) -> Long
) {

    fun search(digits: String, limit: Int = 50): NumberHistoryResult<T> {
        val safeLimit = limit.coerceIn(1, 100)
        val norm = digits.filter { it.isDigit() }
        if (norm.isEmpty()) return NumberHistoryResult(emptyList())
        val now = System.currentTimeMillis()
        val wantAlt = com.redeye.parentalmonitor.utils.PhoneNumbers.altVariant(norm)
        val variants = mutableListOf(norm, "+$norm")
        if (wantAlt != null) {
            if (wantAlt !in variants) variants.add(wantAlt)
            val altPlus = "+$wantAlt"
            if (altPlus !in variants) variants.add(altPlus)
        }
        // Sonde limit+1 untuk bendera terpotong yang
        // jujur (issue #2045, doktrin yang sama dengan
        // #1661/#1664 di ringkasan): total tepat sebesar
        // limit adalah hasil UTUH, bukan terpotong --
        // hanya baris sonde yang membuktikan sebaliknya.
        val exactRows = query("(" + variants.joinToString(" OR ") { "${numberColumn} = ?" } + ") AND ${dateColumn} <= ?", (variants + now.toString()).toTypedArray(), "${dateColumn} DESC, ${idColumn} DESC", safeLimit + 1)
        val exactMatched = filterByNumber(exactRows, norm, wantAlt)
        if (exactMatched.isNotEmpty()) return NumberHistoryResult(exactMatched.take(safeLimit), truncated = exactRows.size > safeLimit)
        if (norm.length < 10) return NumberHistoryResult(emptyList())
        val fuzzy = com.redeye.parentalmonitor.utils.PhoneNumbers.fuzzyLikePattern(norm)
        val rows = query("${numberColumn} LIKE ? ESCAPE '\\' AND ${dateColumn} <= ?", arrayOf(fuzzy, now.toString()), "${dateColumn} DESC, ${idColumn} DESC", safeLimit + 1)
        val matched = filterByNumber(rows, norm, wantAlt)
        if (matched.isNotEmpty()) return NumberHistoryResult(matched.take(safeLimit), truncated = rows.size > safeLimit)
        val cutoff = now - 365L * 24 * 60 * 60_000L
        var upperDate = now
        var upperId = Long.MAX_VALUE
        // Kumpulkan kecocokan dari SEMUA halaman (issue #1000):
        // dulu pencarian berhenti di halaman pertama yang berisi
        // kecocokan, jadi riwayat nomor yang tersebar di beberapa
        // halaman tampil sebagian saja tanpa tanda.
        val acc = ArrayList<T>()
        var scanComplete = false
        // Plafon pindai 2 halaman x 500 = 1.000 baris per
        // repositori (issue #1931): dulu 4 halaman (2.000)
        // dan /history membayar kasus terburuk dua kali
        // lipat itu dari dua repositori. Angka yang sama
        // diumumkan di teks balasan /history.
        for (pageIndex in 0 until 2) {
            val page = query("${dateColumn} >= ? AND (${dateColumn} < ? OR (${dateColumn} = ? AND ${idColumn} < ?))", arrayOf(cutoff.toString(), upperDate.toString(), upperDate.toString(), upperId.toString()), "${dateColumn} DESC, ${idColumn} DESC", 500)
            if (page.isEmpty()) {
                scanComplete = true
                break
            }
            acc.addAll(filterByNumber(page, norm, wantAlt))
            // Bendera terpotong jalur halaman (issue
            // #2051): kecocokan yang MELEBIHI limit pasti
            // terpotong; yang TEPAT limit hanya terpotong
            // bila pindai belum tuntas (halaman penuh =
            // mungkin masih ada halaman berisi kecocokan
            // lain). Halaman parsial menuntaskan pindai,
            // jadi total tepat-limit di sana adalah hasil
            // utuh -- pemeriksaannya sesudah cek halaman.
            if (acc.size > safeLimit) return NumberHistoryResult(acc.take(safeLimit), truncated = true)
            if (page.size < 500) {
                scanComplete = true
                break
            }
            if (acc.size >= safeLimit) return NumberHistoryResult(acc.take(safeLimit), truncated = true)
            val oldest = page.last()
            if (dateOf(oldest) <= cutoff) {
                scanComplete = true
                break
            }
            if (dateOf(oldest) > upperDate || (dateOf(oldest) == upperDate && idOf(oldest) >= upperId)) break
            upperDate = dateOf(oldest)
            upperId = idOf(oldest)
        }
        if (acc.isNotEmpty()) return NumberHistoryResult(acc.take(safeLimit), truncated = !scanComplete)
        if (!scanComplete) {
            android.util.Log.w(logTag, "paged scan truncated")
            return NumberHistoryResult(emptyList(), truncated = true)
        }
        return NumberHistoryResult(emptyList())
    }

    private fun filterByNumber(rows: List<T>, want: String, wantAlt: String?): List<T> {
        return rows.filter { com.redeye.parentalmonitor.utils.PhoneNumbers.matchesPrecomputed(numberOf(it), want, wantAlt) }
    }
}
