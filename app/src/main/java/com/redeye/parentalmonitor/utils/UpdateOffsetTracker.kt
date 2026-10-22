package com.redeye.parentalmonitor.utils

class UpdateOffsetTracker {
    private val pending = java.util.TreeSet<Long>()
    private val confirmed = java.util.TreeSet<Long>()
    private var blocked = false

    @Synchronized
    fun begin(updateId: Long) {
        if (blocked) return
        pending.add(updateId)
    }

    @Synchronized
    fun finish(updateId: Long, delivered: Boolean): Long? {
        pending.remove(updateId)
        if (blocked) return null
        if (!delivered) {
            blocked = true
            pending.clear()
            confirmed.clear()
            return null
        }
        confirmed.add(updateId)
        var advanceTo: Long? = null
        while (true) {
            val next = confirmed.firstOrNull() ?: break
            val pendingMin = pending.firstOrNull()
            if (pendingMin != null && pendingMin < next) break
            confirmed.remove(next)
            advanceTo = next
        }
        return advanceTo
    }
}
