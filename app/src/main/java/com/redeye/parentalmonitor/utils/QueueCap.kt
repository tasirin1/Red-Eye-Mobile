package com.redeye.parentalmonitor.utils

// Batas efektif antrean pesan (issue #842), diekstrak dari
// MessageQueue agar terkunci uji (issue #977): kelonggaran
// +5 milik pesan prioritas yang ADA di antrean atau pesan
// yang sedang masuk — bukan bendera lain.
object QueueCap {
    const val MAX_SIZE = 100
    const val PRIORITY_BONUS = 5

    // Batas AGREGAT karakter (issue #1212): batas jumlah x
    // batas per pesan saja mengizinkan +-3,36 juta karakter
    // dalam satu JSON terenkripsi yang disimpan dua kali
    // (utama + .bak) dan ditulis ulang pada setiap mutasi.
    const val MAX_TOTAL_CHARS = 1_000_000

    fun overCap(size: Int, anyPriorityQueued: Boolean, incomingPriority: Boolean): Boolean {
        val cap = MAX_SIZE + if (incomingPriority || anyPriorityQueued) PRIORITY_BONUS else 0
        return size > cap
    }

    fun overTotalChars(totalChars: Int): Boolean {
        return totalChars > MAX_TOTAL_CHARS
    }
}
