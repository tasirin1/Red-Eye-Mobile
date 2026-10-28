package com.redeye.parentalmonitor.utils

object UsageEventsSummary {

    const val TYPE_RESUMED = 1
    const val TYPE_KEYGUARD_HIDDEN = 18

    data class Event(val type: Int, val at: Long, val pkg: String? = null)

    data class Summary(val unlocks: Int, val firstUnlockAt: Long, val topOpens: List<Pair<String, Int>>)

    fun build(events: List<Event>, topCount: Int = 3): Summary {
        var unlocks = 0
        var first = 0L
        var lastResumed = ""
        val opens = HashMap<String, Int>()
        for (e in events) {
            when (e.type) {
                TYPE_KEYGUARD_HIDDEN -> {
                    unlocks++
                    if (first == 0L || (e.at > 0L && e.at < first)) first = e.at
                }
                TYPE_RESUMED -> {
                    val pkg = e.pkg
                    if (pkg.isNullOrEmpty()) {
                        lastResumed = ""
                    } else if (pkg != lastResumed) {
                        opens[pkg] = (opens[pkg] ?: 0) + 1
                        lastResumed = pkg
                    }
                }
                else -> lastResumed = ""
            }
        }
        val top = opens.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(topCount.coerceAtLeast(1))
            .map { it.key to it.value }
        return Summary(unlocks, first, top)
    }

    fun firstUnlockToday(events: List<Event>, dayStartMs: Long): Long {
        if (dayStartMs <= 0L) return 0L
        var first = 0L
        for (e in events) {
            if (e.type != TYPE_KEYGUARD_HIDDEN) continue
            if (e.at >= dayStartMs && (first == 0L || e.at < first)) first = e.at
        }
        return first
    }
}
