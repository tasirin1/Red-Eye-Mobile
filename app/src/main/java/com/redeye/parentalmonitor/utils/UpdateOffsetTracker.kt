package com.redeye.parentalmonitor.utils

class UpdateOffsetTracker {
    private val pending = java.util.TreeSet<Long>()
    private val confirmed = java.util.TreeSet<Long>()
    private val failed = java.util.TreeSet<Long>()

    @Synchronized
    fun begin(updateId: Long) {
        pending.add(updateId)
    }

    @Synchronized
    fun finish(updateId: Long, delivered: Boolean): Long? {
        pending.remove(updateId)
        if (!delivered) {
            confirmed.remove(updateId)
            failed.add(updateId)
            return null
        }
        failed.remove(updateId)
        confirmed.add(updateId)
        var advanceTo: Long? = null
        while (true) {
            val next = confirmed.firstOrNull() ?: break
            val pendingMin = pending.firstOrNull()
            if (pendingMin != null && pendingMin < next) break
            val failedMin = failed.firstOrNull()
            if (failedMin != null && failedMin <= next) break
            confirmed.remove(next)
            advanceTo = next
        }
        return advanceTo
    }
}
