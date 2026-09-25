package com.redeye.parentalmonitor.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeFmt {
    fun full(timestamp: Long): String =
        SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(timestamp))

    fun fileStamp(timestamp: Long): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestamp))
}
