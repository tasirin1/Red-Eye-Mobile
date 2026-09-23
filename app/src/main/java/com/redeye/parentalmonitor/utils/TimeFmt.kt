package com.redeye.parentalmonitor.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeFmt {
    private val full = ThreadLocal.withInitial {
        SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
    }
    private val file = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
    }

    fun full(timestamp: Long): String = full.get()!!.format(Date(timestamp))
    fun fileStamp(timestamp: Long): String = file.get()!!.format(Date(timestamp))
}
