package com.redeye.parentalmonitor.utils

import java.util.Locale

object IntentParser {

    private class Group(val tokens: Set<String>, val phrases: Set<String>)

    private class Intent(val command: String, val groups: List<Group>)

    private val WHITESPACE = Regex("\\s+")

    private val NEGATIONS = setOf(
        "jangan", "tidak", "nggak", "enggak", "gak", "bukan", "tak", "ndak",
        "ga", "ngga", "engga", "gausah", "kagak", "jgn"
    )

    private val INTENTS = listOf(
        Intent(
            "/location",
            listOf(
                Group(setOf("dimana", "lokasi", "posisi", "keberadaan", "koordinat"), setOf("di mana")),
                Group(setOf("anak", "dia", "hp", "hape", "ponsel", "sekarang", "terkini"), setOf("saat ini"))
            )
        ),
        Intent(
            "/battery",
            listOf(
                Group(setOf("baterai", "batre", "battery", "daya"), emptySet()),
                Group(setOf("berapa", "sisa", "tinggal", "persen", "kondisi", "keadaan", "sekarang", "penuh", "habis", "low", "gimana", "bagaimana"), emptySet())
            )
        ),
        Intent(
            "/status",
            listOf(
                Group(setOf("status", "kondisi", "keadaan", "kabar"), emptySet()),
                Group(setOf("hp", "hape", "anak", "monitoring", "sekarang", "terkini"), emptySet())
            )
        ),
        Intent(
            "/layar",
            listOf(
                Group(setOf("layar", "screentime"), setOf("screen time", "main hp", "main hape", "pemakaian aplikasi", "pakai aplikasi", "waktu layar")),
                Group(setOf("berapa", "lama", "hari", "kemarin", "total", "durasi", "jam", "seharian"), setOf("hari ini", "berapa lama"))
            )
        ),
        Intent(
            "/digest",
            listOf(
                Group(setOf("digest", "laporan", "ringkasan", "rekap"), setOf("laporan hari ini", "ringkasan hari ini", "rekap hari ini")),
                Group(setOf("hari", "aktivitas", "kegiatan"), setOf("hari ini"))
            )
        ),
        Intent(
            "/minggu",
            listOf(
                Group(setOf("minggu", "mingguan", "seminggu"), setOf("laporan minggu", "rekap minggu", "ringkasan minggu", "7 hari")),
                Group(setOf("laporan", "rekap", "ringkasan", "ini", "lalu", "terakhir", "anak"), setOf("minggu ini", "minggu lalu"))
            )
        ),
        Intent(
            "/jejak",
            listOf(
                Group(setOf("jejak", "rute"), setOf("riwayat lokasi", "riwayat perjalanan", "dari mana saja", "ke mana saja", "habis dari mana")),
                Group(setOf("tadi", "seharian", "kemarin", "anak", "dia", "lengkap"), setOf("hari ini"))
            )
        ),
        Intent(
            "/waspada",
            listOf(
                Group(setOf("waspada", "kewaspadaan", "mencurigakan", "aneh", "janggal", "ganjil"), emptySet()),
                Group(setOf("semalam", "malam", "tadi", "anak", "dia", "kejadian"), setOf("tadi malam", "ada yang", "malam ini"))
            )
        ),
        Intent(
            "/lastnotif",
            listOf(
                Group(setOf("notifikasi", "notif", "pemberitahuan"), emptySet()),
                Group(setOf("terakhir", "terbaru", "masuk", "apa", "saja", "tadi"), setOf("apa saja"))
            )
        ),
        Intent(
            "/lastsms",
            listOf(
                Group(setOf("sms"), setOf("pesan sms", "sms masuk")),
                Group(setOf("terakhir", "terbaru", "apa", "siapa", "tadi", "anak", "masuk"), emptySet())
            )
        ),
        Intent(
            "/lastcalls",
            listOf(
                Group(setOf("panggilan", "telepon", "telpon", "telp", "nelpon", "menelepon"), emptySet()),
                Group(setOf("terakhir", "terbaru", "siapa", "tadi", "masuk", "hari"), setOf("hari ini"))
            )
        ),
        Intent(
            "/help",
            listOf(
                Group(setOf("bantuan", "pertolongan"), setOf("bisa apa", "perintah apa saja", "cara pakai", "cara menggunakan", "daftar perintah")),
                Group(setOf("bot", "kamu", "aplikasi", "ini", "dong", "sih", "apa"), emptySet())
            )
        ),
        Intent(
            "/photo",
            listOf(
                Group(setOf("foto", "potret", "jepret"), emptySet()),
                Group(setOf("kirim", "ambil", "minta", "tolong", "sekarang", "dong", "coba", "cepetan"), emptySet())
            )
        ),
        Intent(
            "/screenshot",
            listOf(
                Group(setOf("screenshot"), setOf("tangkap layar", "tangkapan layar")),
                Group(setOf("kirim", "ambil", "minta", "tolong", "sekarang", "dong", "coba"), emptySet())
            )
        )
    )

    private fun isPhotoReference(tokens: List<String>, padded: String): Boolean {
        if (padded.contains(" kirim ulang ")) return true
        for (token in tokens) {
            if (token == "kemarin" || token == "fotonya") return true
            if (tokenMatches(token, "diambil")) return true
        }
        return false
    }

    private val CAPTURE_VERBS = setOf("jepret", "potret", "ambil")

    private val CAPTURE_RESULT_NOUNS = setOf("jepretan", "ambilan")

    private fun hasFirmCaptureIntent(tokens: List<String>, padded: String): Boolean {
        if (padded.contains(" foto baru ")) return true
        for (token in tokens) {
            if (token in CAPTURE_RESULT_NOUNS) continue
            for (verb in CAPTURE_VERBS) {
                if (tokenMatches(token, verb)) return true
            }
        }
        return false
    }

    private val NEGATION_SUFFIXES = setOf("lah", "kan", "kah")

    private fun isNegation(token: String): Boolean {
        if (token in NEGATIONS) return true
        return NEGATIONS.any { base ->
            when {
                base.length >= 4 -> token.length - base.length in 1..3 && token.startsWith(base)
                base.length == 3 -> NEGATION_SUFFIXES.any { token == base + it }
                else -> false
            }
        }
    }

    private fun tokenMatches(token: String, keyword: String): Boolean {
        if (token == keyword) return true
        return keyword.length >= 4 && token.length - keyword.length in 1..3 && token.startsWith(keyword)
    }

    private fun groupHit(tokens: List<String>, padded: String, group: Group): Boolean {
        for (token in tokens) {
            for (keyword in group.tokens) {
                if (tokenMatches(token, keyword)) return true
            }
        }
        for (phrase in group.phrases) {
            if (padded.contains(" $phrase ")) return true
        }
        return false
    }

    fun parse(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("/")) return null
        val lower = trimmed.lowercase(Locale.ROOT)
        val sb = StringBuilder()
        for (c in lower) sb.append(if (c.isLetterOrDigit()) c else ' ')
        val tokens = sb.toString().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        if (tokens.any { isNegation(it) }) return null
        val padded = " " + tokens.joinToString(" ") + " "
        val photoVetoed = isPhotoReference(tokens, padded) && !hasFirmCaptureIntent(tokens, padded)
        var best: String? = null
        var bestScore = 0
        var tie = false
        for (intent in INTENTS) {
            var score = 0
            if (intent.command != "/photo" || !photoVetoed) {
                for (group in intent.groups) {
                    if (groupHit(tokens, padded, group)) score++
                }
            }
            if (score > bestScore) {
                best = intent.command
                bestScore = score
                tie = false
            } else if (score == bestScore && score > 0) {
                tie = true
            }
        }
        return if (bestScore < 2 || tie) null else best
    }
}
