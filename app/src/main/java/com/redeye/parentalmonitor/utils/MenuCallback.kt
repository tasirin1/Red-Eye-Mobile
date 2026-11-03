package com.redeye.parentalmonitor.utils

import java.util.Locale

sealed class MenuCallback {
    data class Navigate(val view: String, val editInPlace: Boolean) : MenuCallback()
    data class Run(val command: String) : MenuCallback()
    data class AskInput(val command: String, val view: String) : MenuCallback()
    data class Toggle(val key: String, val view: String) : MenuCallback()

    companion object {
        val VIEWS = setOf("main", "media", "lokasi", "komu", "info", "pantau", "alat")

        // Kunci saklar on/off (permintaan owner: tombol toggle di
        // menu, tak perlu ketik "/notify off"). Kunci di luar daftar
        // ini ditolak parser agar data callback liar tak bisa
        // membalik setelan apa pun.
        val TOGGLE_KEYS = setOf("notify", "otp", "patrol", "night", "appalert", "appwatch", "protect", "datasaver")

        private val LEGACY = mapOf(
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
            "cekmati" to "cekmati",
            "alive" to "alive"
        )

        fun viewName(name: String): String = if (name in VIEWS) name else "main"

        fun parse(data: String): MenuCallback? {
            val raw = data.trim()
            if (raw.isEmpty()) return null
            if (raw == "menu:open") return Navigate("main", false)
            if (raw == "menu:angka") return Navigate("main", false)
            if (raw.startsWith("m:")) return Navigate(viewName(raw.removePrefix("m:").trim()), true)
            if (raw.startsWith("x:")) {
                val command = perintah(raw.removePrefix("x:"))
                return if (command.isEmpty()) null else Run(command)
            }
            if (raw.startsWith("tg:")) {
                val isi = raw.removePrefix("tg:")
                val pisah = isi.indexOf(':')
                if (pisah < 0) return null
                val key = isi.substring(pisah + 1).trim().lowercase(Locale.ROOT)
                if (key !in TOGGLE_KEYS) return null
                return Toggle(key, viewName(isi.substring(0, pisah).trim()))
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
