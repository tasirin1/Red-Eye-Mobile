package com.redeye.parentalmonitor.utils

object GeofenceLogic {
    data class Transition(val writeState: Int?, val alert: Boolean)

    fun configured(latE6: Long, lonE6: Long, radiusM: Int): Boolean {
        return latE6 != Long.MIN_VALUE && lonE6 != Long.MIN_VALUE && radiusM > 0
    }

    // Akurasi <= 0 berarti "tidak diketahui" (issue #748): di Android
    // fix tanpa akurasi menghasilkan getAccuracy() == 0.0f, jadi nilai
    // itu — dan sentinel negatif lama — tidak boleh lolos gerbang mana
    // pun; konvensi yang sama dipakai ReportFormat/bestFix/lastKnownFix.
    // Sebelumnya 0.0f terbaca sebagai akurasi sempurna.
    fun jemputHit(jemputRadiusM: Int, distM: Float, accuracyM: Float): Boolean {
        // Sekali tembak (issue #740): picu yang langsung mematikan fitur
        // menuntut akurasi paling tidak setengah radius — pola gerbang
        // perubahan state transition (issue #716) — agar satu fix yang
        // sangat tidak pasti tidak memicu kabar jemput palsu.
        // Lantai akurasi yang sama seperti transition/judgedOutside
        // (issue #771, diselaraskan di #1797): pada radius < 100 m
        // gerbang setengah radius murni lebih sempit dari galat GPS
        // kota biasa, sehingga kabar jemput praktis tak pernah
        // terpicu walau anak sudah di dalam radius.
        val limit = maxOf(jemputRadiusM / 2, minOf(50, jemputRadiusM))
        return jemputRadiusM > 0 && distM <= jemputRadiusM && accuracyM > 0f && accuracyM <= limit.toFloat()
    }

    fun judgedOutside(distM: Float, radiusM: Int, accuracyM: Float): Boolean {
        // Gerbang akurasi diselaraskan dengan transition (#716) dan
        // jemputHit (#740) — issue #759: fungsi ini tertinggal di
        // "akurasi <= radius penuh", sehingga alert jam malam/sekolah
        // dan skor /alertness bisa digerakkan fix yang ketidakpastiannya
        // selebar radius. Kini sama ketatnya: paling tidak setengah
        // radius.
        // Lantai akurasi perubahan state yang sama seperti transition
        // (issue #771): pada radius 50–99 m gerbang setengah radius lebih
        // sempit dari galat GPS kota biasa, sehingga state bisa berubah
        // lewat transition tetapi alert di sini tak pernah menyala dari
        // fix yang sama. Kini keduanya memakai batas yang sama.
        val limit = maxOf(radiusM / 2, minOf(50, radiusM))
        return distM >= 0f && distM > radiusM && accuracyM > 0f && accuracyM <= limit.toFloat()
    }

    fun statusText(latE6: Long, lonE6: Long, radiusM: Int): String {
        if (latE6 == Long.MIN_VALUE || lonE6 == Long.MIN_VALUE || radiusM <= 0) return "mati"
        return "${latE6 / 1_000_000.0},${lonE6 / 1_000_000.0} ±${radiusM}m"
    }

    fun insideWithHysteresis(state: Int, distM: Float, radiusM: Int): Boolean {
        val exitMargin = maxOf(30f, radiusM * 0.15f)
        return if (state == 1) distM <= radiusM + exitMargin else distM <= radiusM
    }

    fun transition(state: Int, inside: Boolean, accuracyM: Float, radiusM: Int): Transition {
        val now = if (inside) 1 else 2
        val adopting = state !in 1..2
        val changeLimit = maxOf(radiusM / 2, minOf(50, radiusM))
        val accurate = accuracyM > 0f && accuracyM <= (if (adopting) radiusM else changeLimit).toFloat()
        return when {
            adopting -> if (accurate) Transition(now, false) else Transition(null, false)
            state != now && accurate -> Transition(now, true)
            else -> Transition(null, false)
        }
    }
}
