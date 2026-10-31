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
            if (confirmed[updateId] == generation) confirmed.remove(updateId)
            failed[updateId] = generation
            return null
        }
        if (failed[updateId] == generation) failed.remove(updateId)
        confirmed[updateId] = generation
        var advanceTo: Long? = null
        while (true) {
            val next = confirmed.keys.firstOrNull() ?: break
            val entryGeneration = confirmed.getValue(next)
            if (entryGeneration < generation) {
                confirmed.remove(next)
                continue
            }
            if (entryGeneration > generation) break
            val pendingMin = pending.entries.firstOrNull { it.value == generation }?.key
            if (pendingMin != null && pendingMin < next) break
            val failedMin = failed.entries.firstOrNull { it.value == generation }?.key
            if (failedMin != null && failedMin <= next) break
            confirmed.remove(next)
            advanceTo = next
        }
        return advanceTo
    }
}
