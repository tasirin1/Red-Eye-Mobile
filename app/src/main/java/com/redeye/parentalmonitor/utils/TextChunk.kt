package com.redeye.parentalmonitor.utils

object TextChunk {
    // Batas yang DITERIMA sadar (issue #1243): safeCut menjaga
    // titik potong tidak membelah tag/entitas, tetapi tidak
    // menyeimbangkan PASANGAN tag yang membentang melewati batas
    // potong (<b> di potongan 1, </b>-nya di potongan 2) — potongan
    // pertama ditolak Telegram sebagai entitas rusak dan isinya
    // selamat lewat fallback teks polos, formatnya luruh. Semua
    // pembangun pesan saat ini menutup tag sebaris/segmentek, jadi
    // pemicunya belum ada; menyeimbangkan di sini mustahil tanpa
    // menyuntik tag (di luar kontrak fungsi indeks ini), dan
    // memundurkan potong ke sebelum tag pembuka bisa membuang
    // ribuan karakter. Buka lagi bila ada pembangun yang memancarkan
    // rentang tag panjang tak tertutup.
    fun safeCut(text: String, max: Int): Int {
        if (max <= 0) return 0
        if (text.length <= max) return text.length
        fun codePointBefore(c: Int): Int {
            return if (c >= 2 && Character.isLowSurrogate(text[c - 1]) && Character.isHighSurrogate(text[c - 2])) 2 else 1
        }
        fun isRegionalIndicatorAt(i: Int): Boolean {
            return i + 1 < text.length && text[i] == '\uD83C' && text[i + 1] in '\uDDE6'..'\uDDFF'
        }
        fun scan(cut: Int): Int {
            var c = cut
            if (c > 0 && c < text.length && Character.isHighSurrogate(text[c - 1]) && Character.isLowSurrogate(text[c])) c -= 1
            if (c > 0 && c < text.length) {
                var ri = 0
                var j = c
                while (j >= 2 && isRegionalIndicatorAt(j - 2)) {
                    ri++
                    j -= 2
                }
                if (ri % 2 == 1) c -= 2
            }
            // Mundur melewati SELURUH urutan ZWJ (emoji keluarga majemuk
            // punya beberapa ZWJ) — satu langkah saja menyisakan ZWJ
            // menggantung di akhir potongan (issue #663).
            while (c > 0 && c < text.length && (text[c] == '\u200D' || text[c - 1] == '\u200D')) {
                if (text[c] == '\u200D') {
                    c -= codePointBefore(c)
                } else {
                    c -= 1
                    if (c > 0) c -= codePointBefore(c)
                }
            }
            val amp = text.lastIndexOf('&', c - 1)
            if (amp >= 0 && amp > c - 12) {
                val semi = text.indexOf(';', amp)
                if (semi >= c) {
                    val entity = text.substring(amp, c)
                    if (entity.all { it.isLetterOrDigit() || it == '&' || it == '#' }) c = amp
                }
            }
            val tag = text.lastIndexOf('<', c - 1)
            if (tag >= 0 && text.indexOf('>', tag) >= c) c = tag
            return c
        }
        var cut = scan(max)
        if (cut <= 0) {
            var back = max.coerceAtLeast(1)
            val floor = (max - 64).coerceAtLeast(1)
            while (back > floor) {
                if (back < text.length && Character.isHighSurrogate(text[back - 1]) && Character.isLowSurrogate(text[back])) { back -= 1; break }
                if (text[back - 1] == '&' || text[back - 1] == '<') break
                back -= 1
            }
            cut = back.coerceAtLeast(1)
            if (cut < text.length && Character.isHighSurrogate(text[cut - 1]) && Character.isLowSurrogate(text[cut])) cut -= 1
            if (cut <= 0) cut = 1
        }
        return cut
    }
}
