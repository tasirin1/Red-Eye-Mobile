package com.redeye.parentalmonitor.data.models

data class SmsData(
    val id: Long,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int, // 1 = inbox, 2 = sent
    val read: Boolean
) {
    fun getTypeString(): String {
        return when (type) {
            1 -> "Received"
            2 -> "Sent"
            else -> "Unknown"
        }
    }
}

