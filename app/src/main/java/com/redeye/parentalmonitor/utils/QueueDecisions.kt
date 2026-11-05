package com.redeye.parentalmonitor.utils

// Keputusan murni antrean pesan yang dulu terkubur di
// MessageQueue tanpa uji (issue #1673) — diekstrak mengikuti
// pola QueueCap/QueueFresh/MessageTrim agar bisa dikunci
// uji JVM. Kesetaraan dengan loop lama dibuktikan porting
// 1:1 (20.000 kasus retry acak + 5.000 kasus eviksi identik).
object QueueDecisions {

    // Indeks korban eviksi saat antrean penuh: pesan
    // non-prioritas PERTAMA; bila semua prioritas, yang
    // tertua (indeks 0). Prasyarat: daftar tidak kosong
    // (pemanggil hanya membuang saat antrean melewati
    // plafon); daftar kosong menghasilkan -1.
    fun evictionIndex(priorities: List<Boolean>): Int {
        if (priorities.isEmpty()) return -1
        val idx = priorities.indexOfFirst { !it }
        return if (idx >= 0) idx else 0
    }

    // Akuntansi anggaran retry (registerWithBudget): untuk
    // hitungan retry item yang ditargetkan (URUTAN antrean),
    // tentukan per item apakah ia gugur sesudah dinaikkan
    // satu (hitungan baru >= anggaran) dan retry maksimum
    // di antara item yang BERTAHAN. Mengembalikan pasangan
    // (daftar gugur sejajar masukan, retry maksimum).
    fun retryBudgetOutcome(currentCounts: List<Int>, budget: Int): Pair<List<Boolean>, Int> {
        val dropped = currentCounts.map { it + 1 >= budget }
        var maxRetry = 0
        for (i in currentCounts.indices) {
            if (!dropped[i]) {
                val n = currentCounts[i] + 1
                if (n > maxRetry) maxRetry = n
            }
        }
        return dropped to maxRetry
    }
}
