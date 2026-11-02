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
        val limit = keep.coerceAtLeast(1)
        val out = days.filter { it.day != newDay.day }.toMutableList()
        out.add(newDay)
        val sorted = out.sortedBy { it.day }
        val past = sorted.filter { it.day <= newDay.day }.takeLast(limit)
        // Hari masa depan (artefak jam perangkat berubah): pertahankan yang
        // TERDEKAT lebih dulu — takeLast sebelumnya justru membuang yang
        // terdekat dan menyimpan yang terjauh (issue #701).
        val future = sorted.filter { it.day > newDay.day }.take(limit)
        return past + future
    }
}
