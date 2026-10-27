package com.redeye.parentalmonitor.utils

object CursorAdvance {
    fun penyelesaian(idCursor: Long, dateCursor: Long, rows: List<Pair<Long, Long>>): Pair<Long, Long> {
        var id = idCursor
        var date = dateCursor
        for (row in rows) {
            if (row.first > id) id = row.first
            if (row.second > date) date = row.second
        }
        return id to date
    }
}
