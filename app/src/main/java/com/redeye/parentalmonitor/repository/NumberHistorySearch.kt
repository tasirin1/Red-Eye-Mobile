package com.redeye.parentalmonitor.repository

class NumberHistorySearch<T>(
    private val numberColumn: String,
    private val dateColumn: String,
    private val idColumn: String,
    private val logTag: String,
    private val query: (selection: String?, args: Array<String>?, sortOrder: String, limit: Int) -> List<T>,
    private val numberOf: (T) -> String,
    private val dateOf: (T) -> Long,
    private val idOf: (T) -> Long
) {

    fun search(digits: String, limit: Int = 50): NumberHistoryResult<T> {
        val safeLimit = limit.coerceIn(1, 100)
        val norm = digits.filter { it.isDigit() }
        if (norm.isEmpty()) return NumberHistoryResult(emptyList())
        val wantAlt = com.redeye.parentalmonitor.utils.PhoneNumbers.altVariant(norm)
        val variants = mutableListOf(norm, "+$norm")
        if (wantAlt != null) {
            if (wantAlt !in variants) variants.add(wantAlt)
            val altPlus = "+$wantAlt"
            if (altPlus !in variants) variants.add(altPlus)
        }
        val exactRows = query(
            selection = variants.joinToString(" OR ") { "${numberColumn} = ?" },
            args = variants.toTypedArray(),
            sortOrder = "${dateColumn} DESC, ${idColumn} DESC",
            limit = safeLimit
        )
        val exactMatched = filterByNumber(exactRows, norm, wantAlt)
        if (exactMatched.isNotEmpty()) return NumberHistoryResult(exactMatched.take(safeLimit))
        if (norm.length < 10) return NumberHistoryResult(emptyList())
        val fuzzy = com.redeye.parentalmonitor.utils.PhoneNumbers.fuzzyLikePattern(norm)
        val rows = query(
            selection = "${numberColumn} LIKE ? ESCAPE '\\'",
            args = arrayOf(fuzzy),
            sortOrder = "${dateColumn} DESC, ${idColumn} DESC",
            limit = safeLimit
        )
        val matched = filterByNumber(rows, norm, wantAlt)
        if (matched.isNotEmpty()) return NumberHistoryResult(matched.take(safeLimit))
        val cutoff = System.currentTimeMillis() - 365L * 24 * 60 * 60_000L
        var upperDate = Long.MAX_VALUE
        var upperId = Long.MAX_VALUE
        for (pageIndex in 0 until 4) {
            val page = query(
                selection = "${dateColumn} >= ? AND (${dateColumn} < ? OR (${dateColumn} = ? AND ${idColumn} < ?))",
                args = arrayOf(cutoff.toString(), upperDate.toString(), upperDate.toString(), upperId.toString()),
                sortOrder = "${dateColumn} DESC, ${idColumn} DESC",
                limit = 500
            )
            if (page.isEmpty()) return NumberHistoryResult(emptyList())
            val pageMatched = filterByNumber(page, norm, wantAlt)
            if (pageMatched.isNotEmpty()) return NumberHistoryResult(pageMatched.take(safeLimit))
            if (page.size < 500) return NumberHistoryResult(emptyList())
            val oldest = page.last()
            if (dateOf(oldest) <= cutoff) return NumberHistoryResult(emptyList())
            if (dateOf(oldest) > upperDate || (dateOf(oldest) == upperDate && idOf(oldest) >= upperId)) return NumberHistoryResult(emptyList())
            upperDate = dateOf(oldest)
            upperId = idOf(oldest)
        }
        android.util.Log.w(logTag, "paged scan truncated")
        return NumberHistoryResult(emptyList(), truncated = true)
    }

    private fun filterByNumber(rows: List<T>, want: String, wantAlt: String?): List<T> {
        return rows.filter { com.redeye.parentalmonitor.utils.PhoneNumbers.matchesPrecomputed(numberOf(it), want, wantAlt) }
    }
}
