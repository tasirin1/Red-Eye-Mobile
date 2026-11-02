package com.redeye.parentalmonitor.utils

object RestorePinGate {
    const val MAX_FAILURES = 3
    const val LOCK_MS = 15 * 60_000L

    fun locked(lockoutUntil: Long, now: Long): Boolean = now < lockoutUntil

    // Kunci ganda jam dinding + jam monoton (issue #687). elapsedRealtime
    // monoton selama satu boot dan hanya mengulang saat reboot, jadi:
    // selama elapsedNow >= elapsedAtLock (boot yang sama) keputusan memakai
    // jam monoton — memajukan jam dinding tidak membuka kunci lebih awal.
    // Sesudah reboot (elapsedNow < elapsedAtLock) keputusan memakai jam
    // dinding seperti semula.
    fun locked(lockoutUntilWall: Long, wallNow: Long, elapsedAtLock: Long, elapsedNow: Long): Boolean {
        if (elapsedAtLock > 0L && elapsedNow >= elapsedAtLock) {
            return elapsedNow - elapsedAtLock < LOCK_MS
        }
        return wallNow < lockoutUntilWall
    }

    fun registerFailure(failCount: Int, now: Long): Pair<Int, Long> {
        val next = failCount.coerceAtLeast(0) + 1
        return if (next >= MAX_FAILURES) 0 to (now + LOCK_MS) else next to 0L
    }
}
