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
                Group(setOf("foto", "potret", "jepret", "photo"), emptySet()),
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

    private fun markerMatches(token: String, marker: String): Boolean {
        if (tokenMatches(token, marker)) return true
        return collapseRuns(token) == collapseRuns(marker)
    }

    private fun isPhotoReference(tokens: List<String>, padded: String): Boolean {
        if (padded.contains(" kirim ulang ")) return true
        for (token in tokens) {
            if (markerMatches(token, "kemarin") || markerMatches(token, "fotonya")) return true
            if (markerMatches(token, "tadi") || markerMatches(token, "terakhir") || markerMatches(token, "barusan")) return true
            if (CAPTURE_RESULT_NOUNS.any { markerMatches(token, it) }) return true
            if (markerMatches(token, "diambil")) return true
        }
        return false
    }

    private fun isScreenshotReference(tokens: List<String>, padded: String): Boolean {
        if (isPhotoReference(tokens, padded)) return true
        return tokens.any { markerMatches(it, "screenshotnya") }
    }

    private val CAPTURE_VERBS = setOf("jepret", "potret", "ambil")

    private val CAPTURE_RESULT_NOUNS = setOf("jepretan", "ambilan", "potretan")

    private val PAST_MARKERS = setOf("tadi", "kemarin", "barusan", "terakhir")

    private fun hasFirmCaptureIntent(tokens: List<String>, padded: String): Boolean {
        if (padded.contains(" foto baru ")) return true
        val hasPastMarker = tokens.any { token -> PAST_MARKERS.any { markerMatches(token, it) } }
        for (token in tokens) {
            if (CAPTURE_RESULT_NOUNS.any { markerMatches(token, it) }) continue
            for (verb in CAPTURE_VERBS) {
                if (!tokenMatches(token, verb)) continue
                if (hasPastMarker && token == verb && (verb == "potret" || verb == "jepret")) continue
                return true
            }
        }
        return false
    }

    private val NEGATION_SUFFIXES = setOf("lah", "kan", "kah")

    private fun negationForm(token: String, base: String): Boolean {
        return when {
            base.length >= 4 -> token.length - base.length in 1..3 && token.startsWith(base)
            base.length == 3 -> NEGATION_SUFFIXES.any { token == base + it }
            else -> false
        }
    }

    private fun collapseRuns(token: String): String {
        val sb = StringBuilder()
        var prev = '\u0000'
        for (c in token) {
            if (c != prev) {
                sb.append(c)
                prev = c
            }
        }
        return sb.toString()
    }

    private val COLLAPSE_FALSE_FRIENDS = setOf("bukaan")

    private fun isNegation(token: String): Boolean {
        if (token in COLLAPSE_FALSE_FRIENDS) return false
        if (token in NEGATIONS) return true
        if (NEGATIONS.any { base -> negationForm(token, base) }) return true
        val collapsed = collapseRuns(token)
        if (collapsed == token) return false
        if (NEGATIONS.any { base -> collapseRuns(base) == collapsed }) return true
        if (NEGATIONS.any { base ->
                val collapsedBase = collapseRuns(base)
                collapsedBase.length >= 3 && collapsed.startsWith(collapsedBase) &&
                    collapsed.substring(collapsedBase.length) in NEGATION_SUFFIXES
            }
        ) return true
        return NEGATIONS.any { base -> negationForm(collapsed, base) }
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

    private fun tokenize(text: String): Pair<List<String>, String>? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("/")) return null
        val lower = trimmed.lowercase(Locale.ROOT)
        val sb = StringBuilder()
        for (c in lower) sb.append(if (c.isLetterOrDigit()) c else ' ')
        val tokens = sb.toString().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return tokens to " " + tokens.joinToString(" ") + " "
    }

    private fun isVetoed(tokens: List<String>, padded: String): Boolean {
        if (tokens.any { isNegation(it) }) return true
        if (hasFirmCaptureIntent(tokens, padded)) return false
        return isPhotoReference(tokens, padded) || isScreenshotReference(tokens, padded)
    }

    fun parse(text: String): String? {
        val (tokens, padded) = tokenize(text) ?: return null
        if (tokens.any { isNegation(it) }) return null
        val photoVetoed = isPhotoReference(tokens, padded) && !hasFirmCaptureIntent(tokens, padded)
        val shotVetoed = isScreenshotReference(tokens, padded) && !hasFirmCaptureIntent(tokens, padded)
        var best: String? = null
        var bestScore = 0
        var tie = false
        for (intent in INTENTS) {
            var score = 0
            val suppressed = (intent.command == "/photo" && photoVetoed) || (intent.command == "/screenshot" && shotVetoed)
            if (!suppressed) {
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

    private fun isNumberAttemptHead(head: String): Boolean {
        if (head.isEmpty()) return false
        val digits = head.count { it.isDigit() }
        if (digits == 0) return false
        if (!head.all { it.isDigit() || it in "+().,-" }) return false
        return head[0] == '0' || head[0] == '+' || head[0] == '(' || digits >= 7
    }

    private fun isSmsSendForm(aliasInput: String): Boolean {
        if (!aliasInput.startsWith("/sms ")) return false
        val arg = aliasInput.substring(5)
        if (SmsCommandParser.parse(arg) != null) return true
        val head = arg.split(WHITESPACE).firstOrNull().orEmpty()
        return isNumberAttemptHead(head)
    }

    private val PHOTO_LENS_WORDS = setOf("depan", "belakang", "front", "back")

    private fun mergeAliasArgs(parsed: String, aliasInput: String): String {
        val parts = aliasInput.split(WHITESPACE, limit = 2)
        if (parts.size != 2 || parts[0] != parsed) return parsed
        val first = parts[1].split(WHITESPACE).firstOrNull()?.lowercase(Locale.ROOT)?.let { stripEdgePunct(it) }.orEmpty()
        if (parsed == "/photo" && first in PHOTO_LENS_WORDS) return "$parsed $first"
        if (parsed == "/layar") {
            val hours = first.toIntOrNull()
            if (hours != null && hours in 1..24) return "$parsed $hours"
        }
        return parsed
    }

    private fun stripEdgePunct(token: String): String {
        return token.trimEnd(',', '.', '!', '?', ':', ';', ')')
    }

    private val PHOTO_KEYWORDS = setOf("foto", "potret", "jepret", "photo")
    private val LAYAR_KEYWORDS = setOf("layar", "screentime")

    private fun mergeTextArgs(text: String, parsed: String?): String? {
        if (parsed == null) return null
        val tokens = text.trim().lowercase(Locale.ROOT).split(WHITESPACE).filter { it.isNotEmpty() }
        if (parsed == "/photo") {
            val start = tokens.indexOfFirst { token -> PHOTO_KEYWORDS.any { tokenMatches(token, it) } }
            if (start >= 0) {
                for (i in start + 1 until tokens.size) {
                    val word = stripEdgePunct(tokens[i])
                    if (word in PHOTO_LENS_WORDS) return "$parsed $word"
                }
            }
            return parsed
        }
        if (parsed == "/layar") {
            val start = tokens.indexOfFirst { token -> LAYAR_KEYWORDS.any { tokenMatches(token, it) } }
            if (start >= 0) {
                for (i in start + 1 until tokens.size - 1) {
                    val hours = stripEdgePunct(tokens[i]).toIntOrNull()
                    if (hours != null && hours in 1..24 && stripEdgePunct(tokens[i + 1]) == "jam") return "$parsed $hours"
                }
            }
            return parsed
        }
        return parsed
    }

    fun intentOrAlias(text: String, aliasInput: String?): String? {
        val parsed = parse(text)
        if (aliasInput == null) return mergeTextArgs(text, parsed)
        if (isSmsSendForm(aliasInput)) return aliasInput
        if (parsed != null) {
            val merged = mergeAliasArgs(parsed, aliasInput)
            if (merged != parsed) return merged
            return mergeTextArgs(text, parsed)
        }
        val words = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.size > 2) return null
        val tk = tokenize(text)
        if (tk != null && isVetoed(tk.first, tk.second)) return null
        return aliasInput
    }
}
