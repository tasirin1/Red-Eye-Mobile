package com.redeye.parentalmonitor.utils

object DailyFacts {

    data class Day(
        val day: String,
        val screenMin: Int,
        val nightMin: Int,
        val firstUnlockMin: Int,
        val arriveMin: Int,
        val departMin: Int
    )

    fun parse(raw: String): List<Day> {
        if (raw.isBlank()) return emptyList()
        val out = ArrayList<Day>()
        for (part in raw.split(";")) {
            val f = part.split(",")
            if (f.size != 6) continue
            if (f[0].isEmpty()) continue
            val nums = ArrayList<Int>(5)
            var ok = true
            for (i in 1..5) {
                val v = f[i].toIntOrNull()
                if (v == null) {
                    ok = false
                    break
                }
                nums.add(v)
            }
            if (!ok) continue
            out.add(Day(f[0], nums[0], nums[1], nums[2], nums[3], nums[4]))
        }
        return out.sortedBy { it.day }
    }

    fun serialize(days: List<Day>): String {
        return days.joinToString(";") { d ->
            "${d.day},${d.screenMin},${d.nightMin},${d.firstUnlockMin},${d.arriveMin},${d.departMin}"
        }
    }

    fun upsert(days: List<Day>, newDay: Day, keep: Int = 14): List<Day> {
        val out = days.filter { it.day != newDay.day }.toMutableList()
        out.add(newDay)
        return out.sortedBy { it.day }.takeLast(keep.coerceAtLeast(1))
    }
}
