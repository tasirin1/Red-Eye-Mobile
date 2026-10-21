package com.redeye.parentalmonitor.utils

object CursorRepair {
    fun shouldReset(cursor: Long, newest: Long): Boolean {
        if (cursor <= 0L) return false
        return newest in 0L until cursor
    }
}
