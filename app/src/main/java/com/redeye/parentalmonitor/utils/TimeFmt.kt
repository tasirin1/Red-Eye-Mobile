package com.redeye.parentalmonitor.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object TimeFmt {
    private val fullHolder = ThreadLocal.withInitial {
        SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US)
    }
    private val fileHolder = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }

    fun full(timestamp: Long): String {
        val f = fullHolder.get() ?: SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US)
        f.timeZone = TimeZone.getDefault()
        return f.format(Date(timestamp))
    }

    fun fileStamp(timestamp: Long): String {
        val f = fileHolder.get() ?: SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        f.timeZone = TimeZone.getDefault()
        return f.format(Date(timestamp))
    }
}
