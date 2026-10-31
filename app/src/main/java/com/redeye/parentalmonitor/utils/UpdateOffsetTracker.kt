package com.redeye.parentalmonitor.utils

class UpdateOffsetTracker {
    private val pending = java.util.TreeMap<Long, Long>()
    private val confirmed = java.util.TreeMap<Long, Long>()
    private val failed = java.util.TreeMap<Long, Long>()

    @Synchronized
    fun begin(updateId: Long, generation: Long) {
        pending[updateId] = generation
    }

    @Synchronized
    fun forget(updateId: Long, generation: Long) {
        if (pending[updateId] == generation) pending.remove(updateId)
        if (confirmed[updateId] == generation) confirmed.remove(updateId)
        if (failed[updateId] == generation) failed.remove(updateId)
    }

    @Synchronized
    fun finish(updateId: Long, generation: Long, delivered: Boolean): Long? {
        if (pending[updateId] != generation) return null
        pending.remove(updateId)
        if (!delivered) {
            confirmed.remove(updateId)
            failed[updateId] = generation
            return null
        }
        failed.remove(updateId)
        confirmed[updateId] = generation
        var advanceTo: Long? = null
        while (true) {
            val next = confirmed.keys.firstOrNull() ?: break
            val pendingMin = pending.keys.firstOrNull()
            if (pendingMin != null && pendingMin < next) break
            val failedMin = failed.keys.firstOrNull()
            if (failedMin != null && failedMin <= next) break
            confirmed.remove(next)
            advanceTo = next
        }
        return advanceTo
    }
}
