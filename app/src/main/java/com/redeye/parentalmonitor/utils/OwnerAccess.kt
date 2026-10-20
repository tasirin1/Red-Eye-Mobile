package com.redeye.parentalmonitor.utils

object OwnerAccess {
    fun isOwner(senderId: String, chatId: String, ownerId: Long, accessPinActive: Boolean): Boolean {
        if (senderId.isEmpty()) return false
        if (!accessPinActive) {
            if (senderId == chatId) return true
            return ownerId != 0L && senderId == ownerId.toString()
        }
        return ownerId != 0L && senderId == ownerId.toString()
    }
}
