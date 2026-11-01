package com.redeye.parentalmonitor.utils

object RestorePinGate {
    const val MAX_FAILURES = 3
    const val LOCK_MS = 15 * 60_000L

    fun locked(lockoutUntil: Long, now: Long): Boolean = now < lockoutUntil

    fun registerFailure(failCount: Int, now: Long): Pair<Int, Long> {
        val next = failCount.coerceAtLeast(0) + 1
        return if (next >= MAX_FAILURES) 0 to (now + LOCK_MS) else next to 0L
    }
}
