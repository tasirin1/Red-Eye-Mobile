package com.redeye.parentalmonitor.utils

object RestoreRules {
    const val MAX_BACKUP_BYTES = 64 * 1024
    private val TOKEN_REGEX = Regex("^[0-9]+:[A-Za-z0-9_-]{35,}$")
    private val CHAT_REGEX = Regex("^-?[0-9]{4,}$")
    private val USERNAME_REGEX = Regex("^@[A-Za-z0-9_]{5,32}$")

    fun isValidToken(value: String): Boolean = TOKEN_REGEX.matches(value.trim())

    fun isValidChatId(value: String): Boolean {
        val chat = value.trim()
        return CHAT_REGEX.matches(chat) || USERNAME_REGEX.matches(chat)
    }

    fun isBackupFileName(name: String): Boolean = name.trim().endsWith(".redeyebak", ignoreCase = true)

    fun ownerFallback(backupOwner: Long, newChat: String, oldOwner: Long): Long {
        if (backupOwner > 0L) return backupOwner
        val chatNumber = newChat.trim().toLongOrNull()
        return if (chatNumber != null && chatNumber > 0L) chatNumber else oldOwner
    }

    enum class OwnerOutcome { FROM_BACKUP, FROM_CHAT, KEPT }

    fun ownerOutcome(backupOwner: Long, targetOwner: Long, oldOwner: Long): OwnerOutcome {
        if (backupOwner > 0L) return OwnerOutcome.FROM_BACKUP
        return if (targetOwner == oldOwner) OwnerOutcome.KEPT else OwnerOutcome.FROM_CHAT
    }
}
