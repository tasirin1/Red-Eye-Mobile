package com.redeye.parentalmonitor.utils

object ContactsPaging {
    const val PAGE_SIZE = 30
    private const val MAX_PAGE = 999

    fun pageArg(arg: String): Int? {
        val input = arg.trim()
        if (input.isEmpty()) return 1
        if (input.length > MAX_PAGE.toString().length) return null
        if (input.length > 1 && input.startsWith("0")) return null
        if (input.any { !it.isDigit() }) return null
        val page = input.toIntOrNull() ?: return null
        return if (page in 1..MAX_PAGE) page else null
    }
}
