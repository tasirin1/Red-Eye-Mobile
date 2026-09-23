package com.redeye.parentalmonitor.data.models

import java.util.UUID

data class QueuedMessage(
    val id: String = UUID.randomUUID().toString(),
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val retryCount: Int = 0
)
