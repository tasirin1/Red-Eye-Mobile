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
    private val shortHolder = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat {
            return SimpleDateFormat("HH:mm", Locale.US)
        }
    }
    private val dayHolder = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat {
            return SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
        }
    }

    @Volatile
    private var cachedTz: TimeZone? = null
    @Volatile
    private var tzFetchedAt: Long = 0L

    private fun currentTz(): TimeZone {
        val tz = cachedTz
        return try {
            val now = android.os.SystemClock.elapsedRealtime()
            if (tz != null && now - tzFetchedAt < 60_000L) return tz
            val fresh = TimeZone.getDefault()
            cachedTz = fresh
            tzFetchedAt = now
            fresh
        } catch (_: Exception) {
            tz ?: TimeZone.getTimeZone("UTC")
        }
    }

    fun full(timestamp: Long): String {
        val f = fullHolder.get()
        f.timeZone = currentTz()
        return f.format(Date(timestamp))
    }

    fun fileStamp(timestamp: Long): String {
        val f = fileHolder.get()
        f.timeZone = currentTz()
        return f.format(Date(timestamp))
    }

    fun short(timestamp: Long): String {
        return try {
            val f = shortHolder.get()
            f.timeZone = currentTz()
            f.format(Date(timestamp))
        } catch (_: Exception) {
            "?"
        }
    }

    fun dayKey(timestamp: Long): String {
        return try {
            val f = dayHolder.get()
            f.timeZone = currentTz()
            f.format(Date(timestamp))
        } catch (_: Exception) {
            ""
        }
    }
}
