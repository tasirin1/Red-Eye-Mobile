package com.redeye.parentalmonitor.utils

object SmsCommandParser {
    data class Parsed(val number: String, val normalized: String, val text: String)

    private val NUMBER_REGEX = Regex("^\\+?[0-9]{7,15}$")

    fun parse(arg: String): Parsed? {
        val trimmed = arg.trim()
        if (trimmed.isEmpty()) return null
        var spaced: Parsed? = null
        var spacedComplete: Parsed? = null
        var firstTokenNumber: Parsed? = null
        for (i in trimmed.indices) {
            if (!trimmed[i].isWhitespace()) continue
            val candidate = trimmed.substring(0, i).trim()
            val rest = trimmed.substring(i).trim()
            if (candidate.isEmpty() || rest.isEmpty()) continue
            val normalized = normalize(candidate) ?: continue
            val parsed = Parsed(candidate, normalized, rest)
            spaced = parsed
            val lastGroup = candidate.takeLastWhile { !it.isWhitespace() }
            val lastDigits = lastGroup.count { it.isDigit() }
            val totalDigits = normalized.count { it.isDigit() }
            if (lastDigits >= 3 || (lastDigits == 2 && totalDigits <= 11)) spacedComplete = parsed
            val firstToken = candidate.takeWhile { !it.isWhitespace() }
            if (firstTokenNumber == null && firstToken.count { it.isDigit() } >= 7) {
                firstTokenNumber = parsed
            }
        }
        return firstTokenNumber ?: spacedComplete ?: spaced
    }

    private fun normalize(candidate: String): String? {
        var plusCount = 0
        val digits = StringBuilder(candidate.length + 1)
        if (candidate.startsWith("+")) digits.append('+')
        for (c in candidate) {
            when {
                c.isDigit() -> digits.append(c)
                c == '+' -> {
                    plusCount++
                    if (!candidate.startsWith("+") || plusCount > 1) return null
                }
                c == ' ' || c == '-' || c == '(' || c == ')' || c == '.' -> Unit
                else -> return null
            }
        }
        val normalized = digits.toString()
        if (plusCount > 1 || !NUMBER_REGEX.matches(normalized)) return null
        return normalized
    }
}
