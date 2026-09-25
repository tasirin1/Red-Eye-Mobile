package com.redeye.parentalmonitor.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object TimeFmt {
    fun full(timestamp: Long): String {
        val f = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.US)
        f.timeZone = TimeZone.getDefault()
        return f.format(Date(timestamp))
    }

    fun fileStamp(timestamp: Long): String {
        val f = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        f.timeZone = TimeZone.getDefault()
        return f.format(Date(timestamp))
    }
}
