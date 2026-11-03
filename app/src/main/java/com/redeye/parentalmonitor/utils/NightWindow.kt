package com.redeye.parentalmonitor.utils

// Sisa waktu sampai akhir jendela malam (05:00) — bagian
// murni dari MonitoringService.millisUntilNightEnd (issue
// #937), diekstrak agar terkunci uji (issue #977).
// todayEndMs = jam dinding pukul 05:00 hari ini; bila sudah
// lewat, akhir malam berarti 05:00 besok. Hasil dijepit
// minimal 5 detik agar loop tak berputar rapat.
object NightWindow {
    fun millisUntilNightEnd(nowMs: Long, todayEndMs: Long): Long {
        var end = todayEndMs
        if (end <= nowMs) end += 24 * 3_600_000L
        return (end - nowMs).coerceAtLeast(5_000L)
    }
}
