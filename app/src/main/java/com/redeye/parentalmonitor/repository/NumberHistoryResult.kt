package com.redeye.parentalmonitor.repository

data class NumberHistoryResult<T>(val items: List<T>, val truncated: Boolean = false)
