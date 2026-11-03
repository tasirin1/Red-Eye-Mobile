package com.redeye.parentalmonitor.utils

// Penilai cap "sudah dikerjakan untuk hari X" (issue #1029).
//
// Empat cap harian (jam malam, sekolah, begadang, anomali)
// dulu hanya menyimpan string tanggal yyyy-MM-dd dan
// dibandingkan sebagai string. Bila jam perangkat pernah
// melompat ke masa depan saat alert terkirim, cap
// bertanggal masa depan tersimpan; sesudah jam dikoreksi,
// alert dinilai "sudah terkirim" sampai tanggal asli
// melewati tanggal palsu — alert mati senyap berhari-hari.
// Ini kelas yang sama dengan digest malam #852, yang
// membedakan cap sah dari cap basi lewat EPOCH penulisan:
// cap yang mengaku "hari ini atau di depan" tetapi epoch
// penulisannya sudah berumur >= STALE_MS tidak mungkin
// ditulis pada hari epoch yang sedang berjalan, jadi ia
// cap basi sisa excursi jam dan tidak memblokir lagi.
// Cap warisan tanpa epoch (doneAtMs == 0) tetap memblokir
// seperti perilaku lama (konservatif), dan cap yang epoch
// -nya di masa depan (jam dimundurkan sesudah tulis) juga
// tetap memblokir.
object DayStamp {
    const val STALE_MS = 24 * 3_600_000L

    // Batas basi longgar untuk dedup anomali (issue #1055):
    // satu tanggal lokal bisa berumur LEBIH dari 24 jam epoch
    // bila offset zona berkurang di tengah hari yang sama
    // (terbang ke barat lintas zona; ekstrem garis tanggal
    // membuat tanggal yang sama berulang, rentang maksimum
    // ±50 jam). Dengan batas 24 jam, cap anomali pagi hari
    // dinilai basi pada tanggal lokal yang sama malamnya dan
    // anomali yang sama terkirim ulang. Situs lain terikat
    // jendela jam sehingga batas ketat 24 jam tetap benar
    // untuk mereka.
    const val STALE_MS_ZONA = 50 * 3_600_000L

    fun blocks(doneKey: String, doneAtMs: Long, todayKey: String, nowMs: Long, staleMs: Long = STALE_MS): Boolean {
        if (doneKey.isEmpty() || todayKey.isEmpty()) return false
        if (doneKey < todayKey) return false
        if (doneAtMs > 0L && nowMs - doneAtMs >= staleMs) return false
        return true
    }
}
