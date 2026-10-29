package com.redeye.parentalmonitor.utils

object PatternBaseline {

    data class Finding(val type: String, val text: String, val strong: Boolean)

    fun median(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    private fun dur(minutes: Int): String {
        return ReportFormat.durationText(minutes * 60_000L)
    }

    private fun hhmm(minute: Int): String {
        val hh = (minute / 60).toString().padStart(2, '0')
        val mm = (minute % 60).toString().padStart(2, '0')
        return "$hh.$mm"
    }

    fun assess(history: List<DailyFacts.Day>, today: DailyFacts.Day): List<Finding> {
        if (history.size < 5) return emptyList()
        val out = ArrayList<Finding>()
        val screenVals = history.filter { it.screenMin >= 0 }.map { it.screenMin }
        if (screenVals.size >= 3 && today.screenMin >= 0) {
            val screenMedian = median(screenVals)
            if (screenMedian >= 60) {
                val strong = today.screenMin * 2 >= screenMedian * 3 && today.screenMin - screenMedian >= 60
                out.add(Finding("SCREEN", "Layar " + dur(today.screenMin) + " — biasanya ±" + dur(screenMedian), strong))
            }
        }
        val nightVals = history.filter { it.nightMin >= 0 }.map { it.nightMin }
        if (nightVals.size >= 3 && today.nightMin >= 0) {
            val m = median(nightVals)
            val strong = today.nightMin >= 30 && today.nightMin >= m + 30
            out.add(Finding("NIGHT", "Malam " + dur(today.nightMin) + " — biasanya ±" + dur(m), strong))
        }
        val firstVals = history.filter { it.firstUnlockMin >= 0 }.map { it.firstUnlockMin }
        if (firstVals.size >= 3 && today.firstUnlockMin >= 0) {
            val m = median(firstVals)
            val strong = today.firstUnlockMin <= m - 60
            out.add(Finding("FIRST", "Buka pertama " + hhmm(today.firstUnlockMin) + " — biasanya " + hhmm(m), strong))
        }
        val arriveVals = history.filter { it.arriveMin >= 0 }.map { it.arriveMin }
        if (arriveVals.size >= 3 && today.arriveMin >= 0) {
            val m = median(arriveVals)
            val strong = today.arriveMin >= m + 60
            out.add(Finding("ARRIVE", "Tiba di rumah " + hhmm(today.arriveMin) + " — biasanya " + hhmm(m), strong))
        }
        val departVals = history.filter { it.departMin >= 0 }.map { it.departMin }
        if (departVals.size >= 3 && today.departMin >= 0) {
            val m = median(departVals)
            out.add(Finding("DEPART", "Berangkat " + hhmm(today.departMin) + " — biasanya " + hhmm(m), false))
        }
        return out
    }
}
