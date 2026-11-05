package com.redeye.parentalmonitor.utils

object PollTiming {

    // Jeda antarputaran polling perintah Telegram mengikuti
    // lama diam berkesinambungan (issue #1573): putaran awal
    // tetap rapat (rampa 10 dtk naik 5 dtk per putaran diam,
    // plafon 30 dtk) agar perintah yang baru masuk terasa
    // segera; sesudah 5 menit diam total jeda naik ke 60 dtk,
    // sesudah 30 menit ke 120 dtk — modem bisa tidur nyenyak
    // di antara siklus long-poll 30 dtk. Aktivitas apa pun
    // mengosongkan penghitung diam di pemanggil, jadi jeda
    // kembali rapat begitu ada perintah. Murni & teruji JVM.
    fun idleGapMs(idlePolls: Int, idleForMs: Long): Long {
        return when {
            idleForMs >= 30 * 60_000L -> 120_000L
            idleForMs >= 5 * 60_000L -> 60_000L
            else -> (10_000L + idlePolls.coerceIn(0, 4) * 5_000L).coerceAtMost(30_000L)
        }
    }
}
