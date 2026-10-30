package com.redeye.parentalmonitor.utils

object ContactsPaging {
    const val PAGE_SIZE = 30
    private const val MAX_PAGE = 999

    /** Ambang digit yang sama dengan mesin pencocokan ekor nomor di pencarian kontak. */
    const val MIN_NUMBER_DIGITS = 7

    enum class ArgKind {
        PAGE,
        NUMBER_SEARCH,
        PAGE_HINT,
        SEARCH
    }

    fun pageArg(arg: String): Int? {
        val input = arg.trim()
        if (input.isEmpty()) return 1
        if (input.length > MAX_PAGE.toString().length) return null
        if (input.length > 1 && input.startsWith("0")) return null
        if (input.any { !it.isDigit() }) return null
        val page = input.toIntOrNull() ?: return null
        return if (page in 1..MAX_PAGE) page else null
    }

    /**
     * Partisi argumen /contacts: nomor halaman (1-3 digit via [pageArg]),
     * nomor telepon digit-polos (>= [MIN_NUMBER_DIGITS] digit -> pencarian nomor),
     * digit-saja di bawah ambang itu (kemungkinan salah ketik nomor halaman),
     * dan sisanya pencarian nama/nomor bebas.
     */
    fun argKind(arg: String): ArgKind {
        if (pageArg(arg) != null) return ArgKind.PAGE
        val input = arg.trim()
        if (input.isNotEmpty() && input.all { it.isDigit() }) {
            return if (input.length >= MIN_NUMBER_DIGITS) ArgKind.NUMBER_SEARCH else ArgKind.PAGE_HINT
        }
        return ArgKind.SEARCH
    }
}
