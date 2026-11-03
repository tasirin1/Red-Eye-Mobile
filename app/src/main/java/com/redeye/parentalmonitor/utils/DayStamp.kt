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

    fun blocks(doneKey: String, doneAtMs: Long, todayKey: String, nowMs: Long): Boolean {
        if (doneKey.isEmpty() || todayKey.isEmpty()) return false
        if (doneKey < todayKey) return false
        if (doneAtMs > 0L && nowMs - doneAtMs >= STALE_MS) return false
        return true
    }
}
