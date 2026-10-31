package com.redeye.parentalmonitor.utils

object DigestHighlights {

    data class Facts(
        val screenTodayMs: Long,
        val screenYesterdayMs: Long,
        val topAppToday: String,
        val topAppTodayMs: Long,
        val topAppYesterdayMs: Long,
        val nightMs: Long,
        val firstUnlockMinuteOfDay: Int
    )

    private data class Candidate(val score: Int, val order: Int, val text: String)

    fun build(f: Facts): List<String> {
        val out = ArrayList<Candidate>()
        val today = f.screenTodayMs
        val yesterday = f.screenYesterdayMs
        var pctDone = false
        if (yesterday >= 30 * 60_000L) {
            val delta = today - yesterday
            val pct = kotlin.math.abs(delta) * 100L / yesterday
            if (pct >= 30L && kotlin.math.abs(delta) >= 30 * 60_000L) {
                val naik = delta > 0
                val ikon = if (naik) "📈" else "📉"
                val arah = if (naik) "naik" else "turun"
                out.add(Candidate(55, 0, "$ikon Layar " + ReportFormat.durationText(today) + " — $arah $pct% dari kemarin"))
                pctDone = true
            }
        }
        if (f.nightMs >= 90 * 60_000L) {
            out.add(Candidate(60, 1, "🌙 Pemakaian malam " + ReportFormat.durationText(f.nightMs)))
        } else if (f.nightMs >= 30 * 60_000L) {
            out.add(Candidate(40, 1, "🌙 Pemakaian malam " + ReportFormat.durationText(f.nightMs)))
        }
        if (f.topAppToday.isNotEmpty() && f.topAppYesterdayMs >= 15 * 60_000L &&
            f.topAppTodayMs * 2L >= f.topAppYesterdayMs * 3L && f.topAppTodayMs - f.topAppYesterdayMs >= 30 * 60_000L
        ) {
            out.add(
                Candidate(
                    45, 2,
                    "🔥 " + Html.escape(f.topAppToday) + " " + ReportFormat.durationText(f.topAppTodayMs) +
                        " — kemarin hanya " + ReportFormat.durationText(f.topAppYesterdayMs)
                )
            )
        }
        val fu = f.firstUnlockMinuteOfDay
        if (fu in 0 until 330) {
            val hh = (fu / 60).toString().padStart(2, '0')
            val mm = (fu % 60).toString().padStart(2, '0')
            out.add(Candidate(35, 3, "⏰ HP pertama dibuka $hh.$mm"))
        }
        if (!pctDone && today >= 360 * 60_000L) {
            out.add(Candidate(30, 4, "📱 Layar " + ReportFormat.durationText(today) + " hari ini"))
        }
        return out.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.order }).take(3).map { it.text }
    }
}
