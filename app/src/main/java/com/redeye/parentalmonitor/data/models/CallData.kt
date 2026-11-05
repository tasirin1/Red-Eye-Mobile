package com.redeye.parentalmonitor.data.models

data class CallData(
    val id: Long = 0L,
    val number: String,
    val name: String?,
    val date: Long,
    val duration: Int, // seconds
    val type: Int, // 1 = incoming, 2 = outgoing, 3 = missed, 4 = voicemail, 5 = rejected, 6 = blocked, 7 = answered elsewhere
    // Label SIM asal (issue #1496): "SIM 1"/"SIM 2" bila
    // atribusi tersedia (PHONE_ACCOUNT_ID terpetakan ke
    // langganan aktif); kosong bila tak diketahui.
    val simLabel: String = ""
) {
    fun getTypeString(): String {
        return when (type) {
            1 -> "Masuk"
            2 -> "Keluar"
            3 -> "Tak terjawab"
            4 -> "Voicemail"
            5 -> "Ditolak"
            6 -> "Diblokir"
            7 -> "Dijawab di lain"
            else -> "Tak tahu"
        }
    }

    fun getDurationString(): String {
        if (duration == 0) return "0s"
        // Sesudah satu jam, naik ke format jam ReportFormat yang
        // dipakai semua laporan lain (issue #1247): dulu panggilan
        // 2 jam tampil "120m 0s". Presisi detik di bawah satu jam
        // dipertahankan (durationText bekerja per menit utuh).
        if (duration >= 3600) {
            return com.redeye.parentalmonitor.utils.ReportFormat.durationText(duration * 1000L)
        }
        val minutes = duration / 60
        val seconds = duration % 60
        return if (minutes > 0) {
            "${minutes}m ${seconds}s"
        } else {
            "${seconds}s"
        }
    }
}

