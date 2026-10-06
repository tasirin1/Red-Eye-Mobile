package com.redeye.parentalmonitor.data.models

data class SmsData(
    val id: Long,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int // 1 = inbox, 2 = sent, 3 = draft, 4 = outbox, 5 = failed, 6 = queued
) {
    fun getTypeString(): String {
        return when (type) {
            1 -> "Received"
            2 -> "Sent"
            3 -> "Draft"
            4 -> "Outbox"
            5 -> "Failed"
            6 -> "Queued"
            else -> "Unknown"
        }
    }
}

