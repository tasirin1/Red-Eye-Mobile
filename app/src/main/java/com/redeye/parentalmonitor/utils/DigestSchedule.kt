package com.redeye.parentalmonitor.utils

object DigestSchedule {
    enum class Verdict { RESET, WAIT, SEND }

    fun periodicDecision(lastMs: Long, nowMs: Long): Verdict {
        if (lastMs > nowMs) return Verdict.RESET
        if (nowMs - lastMs < 6 * 60 * 60_000L) return Verdict.WAIT
        return Verdict.SEND
    }

    // Jangkar periode berikutnya tetap di kisi fase lama (issue #736):
    // maju kelipatan penuh 6 jam dari jangkar sebelumnya, bukan ke waktu
    // kirim aktual — overshoot satu siklus gelung tidak lagi menggeser
    // jam kirim sedikit demi sedikit.
    fun nextPeriodicAnchor(lastMs: Long, nowMs: Long): Long {
        if (lastMs <= 0L || nowMs <= lastMs) return nowMs
        val period = 6 * 60 * 60_000L
        return lastMs + (nowMs - lastMs) / period * period
    }

    fun localHour(nowMs: Long, offsetMs: Long): Int {
        return A11yUsageMath.localHour(nowMs, offsetMs.toInt())
    }

    fun nightDue(hour: Int, doneKey: String, todayKey: String): Boolean {
        return hour >= 21 && todayKey.isNotEmpty() && doneKey < todayKey
    }

    // Susulan hanya pada jendela pagi 05–11 (issue #738): tanpa syarat
    // jam, susulan tertembak pada siklus pertama kapan pun — termasuk
    // tengah hari — dan digest malam reguler tetap terkirim lagi malam
    // yang sama (dua ringkasan harian dalam sehari). Sesudah jendela
    // pagi, susulan dilewati; digest malam reguler tetap jatuh tempo
    // malam ini karena penanda selesai tidak berubah.
    fun nightCatchUpDue(doneKey: String, yesterdayKey: String, todayKey: String, hour: Int): Boolean {
        return hour in 5..11 && doneKey.isNotEmpty() && yesterdayKey.isNotEmpty() && doneKey < yesterdayKey
    }
}
