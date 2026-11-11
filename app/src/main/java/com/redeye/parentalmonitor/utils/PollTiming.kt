package com.redeye.parentalmonitor.utils

object PollTiming {

    // Jeda antarputaran polling perintah Telegram mengikuti
    // lama diam berkesinambungan, dalam dua mode:
    // - Mode tanggap (bawaan): keputusan pemilik 2026-11-11
    //   membalik jadwal hemat pertama (issue #1573) demi
    //   respons — rampa 3 dtk naik 2 dtk per putaran diam
    //   (plafon 10 dtk), 20 dtk sesudah 5 menit diam, dan
    //   maksimal 30 dtk sesudah 30 menit diam; perintah
    //   pertama sesudah diam lama menunggu paling lama
    //   satu jeda ini.
    // - Mode hemat daya (hematDaya, keputusan pemilik yang
    //   sama, malam yang sama): aktif saat baterai rendah
    //   / penghemat baterai sistem menyala dan tidak sedang
    //   mengisi daya (vonis lowPowerActive pemanggil, ambang
    //   yang sama dengan perlambatan kamera & patroli) —
    //   jadwal longgar pertama berlaku lagi: rampa 10-30
    //   dtk, 60 dtk sesudah 5 menit diam, 120 dtk sesudah
    //   30 menit. Responsif saat daya aman, hemat saat
    //   genting, tanpa pemilik mengatur apa pun.
    // Aktivitas apa pun mengosongkan penghitung diam di
    // pemanggil, jadi jeda kembali rapat begitu ada
    // perintah. Murni & teruji JVM.
    fun idleGapMs(idlePolls: Int, idleForMs: Long, hematDaya: Boolean = false): Long {
        return if (hematDaya) {
            when {
                idleForMs >= 30 * 60_000L -> 120_000L
                idleForMs >= 5 * 60_000L -> 60_000L
                else -> (10_000L + idlePolls.coerceIn(0, 4) * 5_000L).coerceAtMost(30_000L)
            }
        } else {
            when {
                idleForMs >= 30 * 60_000L -> 30_000L
                idleForMs >= 5 * 60_000L -> 20_000L
                else -> (3_000L + idlePolls.coerceIn(0, 4) * 2_000L).coerceAtMost(10_000L)
            }
        }
    }
}
