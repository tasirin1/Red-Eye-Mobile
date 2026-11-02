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
            // Cache 60 detik sebelumnya bisa menyajikan zona lama sesudah
            // zona perangkat berubah — di sekitar tengah malam dayKey()
            // bisa salah hari sesaat (issue #700). Bandingkan ID zona
            // setiap panggil: getDefault() murah, dan instans cache hanya
            // diganti bila zona benar-benar berubah.
            val fresh = TimeZone.getDefault()
            if (tz != null && tz.id == fresh.id && tz.rawOffset == fresh.rawOffset) {
                tzFetchedAt = android.os.SystemClock.elapsedRealtime()
                return tz
            }
            cachedTz = fresh
            tzFetchedAt = android.os.SystemClock.elapsedRealtime()
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

    // Waktu buat berkas media dari NAMANYA (issue #855): nama media
    // menyematkan cap pembuatannya — audio memakai milidetik epoch,
    // kamera/video/screenshot memakai fileStamp. lastModified tidak
    // bisa dipakai menilai umur karena rotasi flush (#824) menulis
    // ulangnya. -1 bila nama tak memuat cap yang bisa dibaca.
    fun mediaNameTimeMs(name: String): Long {
        val base = name.removeSuffix(".tmp").substringBeforeLast(".")
        val parts = base.split("_")
        if (name.startsWith("audio_")) {
            return parts.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L } ?: -1L
        }
        if (parts.size >= 3) {
            val stamp = parts[1] + "_" + parts[2]
            if (stamp.length == 15 && stamp[8] == '_') {
                try {
                    val f = fileHolder.get()
                    f.timeZone = currentTz()
                    f.isLenient = false
                    val parsed = f.parse(stamp)
                    if (parsed != null) return parsed.time
                } catch (_: Exception) {
                }
            }
        }
        return -1L
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
