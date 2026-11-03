package com.redeye.parentalmonitor.utils

object BootClock {

    const val SAME_BOOT_TOLERANCE_MS = 120_000L

    fun bootWallAt(wallMs: Long, elapsedMs: Long): Long {
        return wallMs - elapsedMs
    }

    fun sameBoot(prevWall: Long, prevElapsed: Long, nowWall: Long, nowElapsed: Long): Boolean {
        if (prevWall <= 0L || prevElapsed <= 0L) return false
        val prevBoot = bootWallAt(prevWall, prevElapsed)
        val nowBoot = bootWallAt(nowWall, nowElapsed)
        return kotlin.math.abs(prevBoot - nowBoot) <= SAME_BOOT_TOLERANCE_MS
    }

    // Cadangan koreksi jam (issue #831): taksiran jam-boot bergeser
    // persis sebesar koreksi jam dinding (NTP/manual), jadi geseran
    // > toleransi dalam boot yang sama sebelumnya membuang penanda
    // sesi yang sah. Bila rentang elapsed-nya sendiri konsisten
    // (tak negatif — elapsed tereset saat reboot — dan tak lebih
    // dari sehari), perlakukan sebagai boot yang sama terkoreksi.
    // Geseran jam tersiratnya juga dibatasi (issue #858): tanpa
    // batas itu, sesi lintas reboot diterima bila restore terjadi
    // pada elapsed yang kebetulan >= penanda — jeda nyata antar
    // boot umumnya jauh lebih besar dari koreksi jam yang wajar.
    // Sisa yang tak bisa dibedakan: reboot cepat (< batas geseran)
    // memang tak terbedakan dari koreksi jam oleh wall+elapsed saja.
    fun sameBootOrClockShift(prevWall: Long, prevElapsed: Long, nowWall: Long, nowElapsed: Long): Boolean {
        if (sameBoot(prevWall, prevElapsed, nowWall, nowElapsed)) return true
        if (prevWall <= 0L || prevElapsed <= 0L) return false
        val span = nowElapsed - prevElapsed
        if (span < 0L || span > MAX_ELAPSED_SPAN_MS) return false
        val impliedShift = (nowWall - prevWall) - span
        return kotlin.math.abs(impliedShift) <= MAX_CLOCK_SHIFT_MS
    }

    const val MAX_ELAPSED_SPAN_MS = 24 * 60 * 60_000L
    const val MAX_CLOCK_SHIFT_MS = 2 * 60 * 60_000L

    // Keputusan dedup siaran boot (issue #1067, diperkuat
    // #1074): duplikat = selang elapsed pendek DAN selisih
    // dinding-elapsed kecil (kedua jam berjalan bersama dalam
    // satu boot). Kelemahan heuristik murni: reboot SUNGGUHAN
    // yang sangat cepat (uptime boot lama + waktu mati <= 120
    // detik) tak terbedakan dari duplikat. Penghitung boot
    // SISTEM (Settings.Global.BOOT_COUNT, dikelola platform)
    // adalah saksi independen: bila nilainya naik dari yang
    // tersimpan, ini boot baru — apa pun kata heuristik waktu.
    // Nilai -1 = tak tersedia/tak tersimpan -> heuristik saja.
    fun isDuplicateBroadcast(elapsedDiffMs: Long, wallDiffMs: Long, sysBoot: Long, storedSys: Long): Boolean {
        val timing = elapsedDiffMs >= 0L && elapsedDiffMs <= 30 * 60_000L &&
            kotlin.math.abs(wallDiffMs - elapsedDiffMs) <= SAME_BOOT_TOLERANCE_MS
        val sysSaysNewBoot = sysBoot >= 0L && storedSys >= 0L && sysBoot > storedSys
        return timing && !sysSaysNewBoot
    }
}
