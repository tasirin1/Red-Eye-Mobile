package com.redeye.parentalmonitor.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object TimeFmt {
    private val fullHolder = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat {
            return SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US)
        }
    }
    private val fileHolder = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat {
            return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        }
    }

    @Volatile
    private var cachedTz: TimeZone? = null
    @Volatile
    private var tzFetchedAt: Long = 0L

    private fun currentTz(): TimeZone {
        val now = android.os.SystemClock.elapsedRealtime()
        val tz = cachedTz
        if (tz != null && now - tzFetchedAt < 60_000L) return tz
        return try {
            val fresh = TimeZone.getDefault()
            cachedTz = fresh
            tzFetchedAt = now
            fresh
        } catch (_: Exception) {
            tz ?: TimeZone.getTimeZone("UTC")
        }
    }

    fun full(timestamp: Long): String {
        val f = fullHolder.get() ?: SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US)
        f.timeZone = currentTz()
        return f.format(Date(timestamp))
    }

    fun fileStamp(timestamp: Long): String {
        val f = fileHolder.get() ?: SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        f.timeZone = currentTz()
        return f.format(Date(timestamp))
    }
}
