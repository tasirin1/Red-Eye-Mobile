package com.redeye.parentalmonitor.data.models

data class CallData(
    val number: String,
    val name: String?,
    val date: Long,
    val duration: Int, // seconds
    val type: Int // 1 = incoming, 2 = outgoing, 3 = missed
) {
    fun getTypeString(): String {
        return when (type) {
            1 -> "Incoming"
            2 -> "Outgoing"
            3 -> "Missed"
            else -> "Unknown"
        }
    }
    
    fun getDurationString(): String {
        if (duration == 0) return "0s"
        val minutes = duration / 60
        val seconds = duration % 60
        return if (minutes > 0) {
            "${minutes}m ${seconds}s"
        } else {
            "${seconds}s"
        }
    }
}

