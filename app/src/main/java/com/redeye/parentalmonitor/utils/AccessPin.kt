package com.redeye.parentalmonitor.utils

import java.security.MessageDigest

object AccessPin {
    const val MIN_LENGTH = 6
    const val MAX_LENGTH = 12

    fun isValid(value: String): Boolean {
        val pin = value.trim()
        return pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }
    }

    fun verify(expected: String, provided: String): Boolean {
        val stored = expected.trim()
        val input = provided.trim()
        if (!isValid(stored) || !isValid(input)) return false
        return MessageDigest.isEqual(stored.toByteArray(Charsets.UTF_8), input.toByteArray(Charsets.UTF_8))
    }
}
