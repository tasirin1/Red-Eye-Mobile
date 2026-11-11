package com.redeye.parentalmonitor.utils

object PollTiming {

    // Jeda antarputaran polling perintah Telegram mengikuti
    // lama diam berkesinambungan. Jadwal pertama (issue
    // #1573) hemat baterai: rampa 10-30 dtk, 60 dtk sesudah
    // 5 menit diam, 120 dtk sesudah 30 menit. Keputusan
    // pemilik 2026-11-11 membaliknya demi respons: rampa
    // 3 dtk naik 2 dtk per putaran diam (plafon 10 dtk),
    // 20 dtk sesudah 5 menit diam, dan maksimal 30 dtk
    // sesudah 30 menit diam — perintah pertama sesudah
    // diam lama menunggu paling lama satu jeda ini, bukan
    // 120 dtk. Harga yang disadari pemilik: modem lebih
    // sering bangun di antara siklus long-poll 30 dtk.
    // Aktivitas apa pun mengosongkan penghitung diam di
    // pemanggil, jadi jeda kembali rapat begitu ada
    // perintah. Murni & teruji JVM.
    fun idleGapMs(idlePolls: Int, idleForMs: Long): Long {
        return when {
            idleForMs >= 30 * 60_000L -> 30_000L
            idleForMs >= 5 * 60_000L -> 20_000L
            else -> (3_000L + idlePolls.coerceIn(0, 4) * 2_000L).coerceAtMost(10_000L)
        }
    }
}
