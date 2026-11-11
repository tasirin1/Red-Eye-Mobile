package com.redeye.parentalmonitor.utils

object UsageEventsSummary {

    const val TYPE_RESUMED = 1
    const val TYPE_KEYGUARD_HIDDEN = 18

    data class Event(val type: Int, val at: Long, val pkg: String? = null)

    data class Summary(val unlocks: Int, val firstUnlockAt: Long, val topOpens: List<Pair<String, Int>>)

    // Akumulator mengalir (issue #1923): agregasi yang
    // sama persis dengan build()/firstUnlockToday()/
    // unlocksToday(), tetapi event diberi satu-satu tanpa
    // ditampung semua lebih dulu — untuk pindaian
    // UsageEvents jendela panjang (24 jam) di layanan,
    // yang tadinya mematerialisasi puluhan ribu event ke
    // satu daftar. Ketiga fungsi daftar di bawah DIBANGUN
    // di atas kelas ini agar hanya ada satu sumber
    // kebenaran untuk aturan agregasinya.
    class Akumulator(private val dayStartMs: Long = 0L) {
        private var unlocks = 0
        private var first = 0L
        private var lastResumed = ""
        private val opens = HashMap<String, Int>()
        private var firstToday = 0L
        private var unlocksTodayCount = 0

        fun terima(e: Event) {
            when (e.type) {
                TYPE_KEYGUARD_HIDDEN -> {
                    unlocks++
                    // Hanya cap waktu positif yang boleh
                    // menjadi "pertama" (issue #1662):
                    // klausa lama membiarkan event pertama
                    // bercap <= 0 meracuni first selamanya
                    // (tak ada cap valid yang bisa lebih
                    // kecil dari negatif), selaras saringan
                    // firstUnlockToday di bawah.
                    if (e.at > 0L && (first == 0L || e.at < first)) first = e.at
                    if (dayStartMs > 0L && e.at >= dayStartMs) {
                        unlocksTodayCount++
                        if (firstToday == 0L || e.at < firstToday) firstToday = e.at
                    }
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

        fun ringkasan(topCount: Int = 3): Summary {
            val top = opens.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(topCount.coerceAtLeast(1))
                .map { it.key to it.value }
            return Summary(unlocks, first, top)
        }

        fun firstUnlockToday(): Long = if (dayStartMs <= 0L) 0L else firstToday

        fun unlocksToday(): Int = if (dayStartMs <= 0L) 0 else unlocksTodayCount
    }

    fun build(events: List<Event>, topCount: Int = 3): Summary {
        val ak = Akumulator()
        for (e in events) ak.terima(e)
        return ak.ringkasan(topCount)
    }

    fun firstUnlockToday(events: List<Event>, dayStartMs: Long): Long {
        val ak = Akumulator(dayStartMs)
        for (e in events) ak.terima(e)
        return ak.firstUnlockToday()
    }

    fun unlocksToday(events: List<Event>, dayStartMs: Long): Int {
        val ak = Akumulator(dayStartMs)
        for (e in events) ak.terima(e)
        return ak.unlocksToday()
    }
}
