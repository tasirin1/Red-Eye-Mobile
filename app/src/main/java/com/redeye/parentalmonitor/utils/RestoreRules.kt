package com.redeye.parentalmonitor.utils

object RestoreRules {
    const val MAX_BACKUP_BYTES = 64 * 1024
    private val TOKEN_REGEX = Regex("^[0-9]+:[A-Za-z0-9_-]{35,}$")
    private val CHAT_REGEX = Regex("^-?[0-9]{4,}$")

    fun isValidToken(value: String): Boolean = TOKEN_REGEX.matches(value.trim())

    fun isValidChatId(value: String): Boolean = CHAT_REGEX.matches(value.trim())

    fun isBackupFileName(name: String): Boolean = name.trim().endsWith(".redeyebak", ignoreCase = true)

    fun ownerFallback(backupOwner: Long, newChat: String, oldOwner: Long): Long {
        if (backupOwner > 0L) return backupOwner
        val chatNumber = newChat.trim().toLongOrNull()
        return if (chatNumber != null && chatNumber > 0L) chatNumber else oldOwner
    }
}
