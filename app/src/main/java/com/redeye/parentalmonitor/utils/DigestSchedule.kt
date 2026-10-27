package com.redeye.parentalmonitor.utils

object DigestSchedule {
    enum class Verdict { RESET, WAIT, SEND }

    fun periodicDecision(lastMs: Long, nowMs: Long): Verdict {
        if (lastMs > nowMs) return Verdict.RESET
        if (nowMs - lastMs < 6 * 60 * 60_000L) return Verdict.WAIT
        return Verdict.SEND
    }

    fun localHour(nowMs: Long, offsetMs: Long): Int {
        return (((nowMs + offsetMs) / 3_600_000L) % 24L).toInt()
    }

    fun nightDue(hour: Int, doneKey: String, todayKey: String): Boolean {
        return hour >= 21 && todayKey.isNotEmpty() && doneKey != todayKey
    }
}
