package com.redeye.parentalmonitor.utils

object PhotoPause {

    const val MAX_PAUSE_MS = 480 * 60_000L

    // Di bawah ambang ini nilai tersimpan dianggap format
    // warisan jam monoton (bukan milis jam dinding).
    private const val WALL_FORMAT_MIN = 1_000_000_000_000L

    // Inti murni keputusan jeda foto (issue #1028; logika
    // dari #939/#941 yang dulu menyatu dengan baca/tulis
    // prefs di MonitoringService): mengembalikan pasangan
    // (tenggat efektif jam dinding, nilai baru untuk
    // disimpan). Elemen kedua non-null berarti pemanggil
    // wajib menulisnya ke prefs — migrasi format warisan
    // atau penjepitan tenggat yang terlalu jauh.
    fun effectiveUntil(stored: Long, elapsedDeadline: Long, nowWall: Long, nowElapsed: Long): Pair<Long, Long?> {
        if (stored <= 0L) return 0L to null
        if (stored < WALL_FORMAT_MIN) {
            val remaining = stored - nowElapsed
            val migrated = if (remaining > 0L) nowWall + remaining else 0L
            return migrated to migrated
        }
        // Jangkar monoton (issue #939): sisa jeda dihitung
        // dari tenggat elapsedRealtime pendamping, jadi
        // kemunduran jam dinding tidak memperpanjang jeda.
        if (elapsedDeadline > 0L) {
            val sisaElapsed = elapsedDeadline - nowElapsed
            if (sisaElapsed <= 0L) return 0L to null
            // Jangkar elapsed tak sah lintas reboot (issue
            // #941): sisa dinding adalah batas kedua yang
            // selalu sah — ambil yang LEBIH KECIL, lalu jepit
            // 480 mnt (jeda sah maksimum).
            val sisaDinding = stored - nowWall
            if (sisaDinding <= 0L) return 0L to null
            val sisa = minOf(sisaElapsed, sisaDinding).coerceAtMost(MAX_PAUSE_MS)
            return (nowWall + sisa) to null
        }
        if (stored - nowWall > MAX_PAUSE_MS) {
            val clamped = nowWall + MAX_PAUSE_MS
            return clamped to clamped
        }
        return stored to null
    }
}
