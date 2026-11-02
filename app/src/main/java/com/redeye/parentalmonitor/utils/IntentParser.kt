package com.redeye.parentalmonitor.utils

import java.util.Locale

object IntentParser {

    private class Group(val tokens: Set<String>, val phrases: Set<String>)

    private class Intent(val command: String, val groups: List<Group>)

    private val WHITESPACE = Regex("\\s+")

    private val NEGATIONS = setOf(
        "jangan", "tidak", "nggak", "enggak", "gak", "bukan", "tak", "ndak",
        "ga", "ngga", "engga", "gausah", "kagak", "jgn",
        "tdk", "gk", "dak", "usah", "jngan", "tidka"
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

    private val MARKER_SUFFIXES = setOf("lah", "kan", "kah", "kanlah")

    private fun markerMatches(token: String, marker: String): Boolean {
        if (tokenMatches(token, marker)) return true
        if (collapseRuns(token) == collapseRuns(marker)) return true
        val collapsedToken = collapseRuns(token)
        val collapsedMarker = collapseRuns(marker)
        return collapsedMarker.length >= 3 && collapsedToken.length > collapsedMarker.length &&
            collapsedToken.startsWith(collapsedMarker) &&
            collapsedToken.substring(collapsedMarker.length) in MARKER_SUFFIXES
    }

    private fun isPhotoReference(tokens: List<String>, padded: String): Boolean {
        if (padded.contains(" kirim ulang ")) return true
        if (padded.contains(" yang lalu ")) return true
        for (i in 0 until tokens.size - 1) {
            if (markerMatches(tokens[i], "kirim") && markerMatches(tokens[i + 1], "ulang")) return true
        }
        if (padded.contains(" tempo hari ")) return true
        for (token in tokens) {
            if (markerMatches(token, "kemarin") || markerMatches(token, "kemaren") || markerMatches(token, "fotonya")) return true
            if (markerMatches(token, "tadi") || markerMatches(token, "terakhir") || markerMatches(token, "barusan")) return true
            if (CAPTURE_RESULT_NOUNS.any { markerMatches(token, it) }) return true
            if (markerMatches(token, "diambil")) return true
            if (markerMatches(token, "terkirim") || markerMatches(token, "dikirim")) return true
            if (markerMatches(token, "lama") || markerMatches(token, "semalam") || markerMatches(token, "sebelumnya")) return true
        }
        return false
    }

    private fun isScreenshotReference(tokens: List<String>, padded: String): Boolean {
        if (isPhotoReference(tokens, padded)) return true
        return tokens.any { markerMatches(it, "screenshotnya") }
    }

    private val CAPTURE_VERBS = setOf("jepret", "potret", "ambil")

    private val CAPTURE_RESULT_NOUNS = setOf("jepretan", "ambilan", "potretan")

    private val PAST_MARKERS = setOf("tadi", "kemarin", "kemaren", "barusan", "terakhir")

    private fun firmPairMatches(token: String, marker: String): Boolean {
        val collapsedToken = collapseRuns(token)
        val collapsedMarker = collapseRuns(marker)
        if (collapsedToken == collapsedMarker) return true
        return collapsedMarker.length >= 3 && collapsedToken.length > collapsedMarker.length &&
            collapsedToken.startsWith(collapsedMarker) &&
            collapsedToken.substring(collapsedMarker.length) in MARKER_SUFFIXES
    }

    private fun hasFirmCaptureIntent(tokens: List<String>, padded: String): Boolean {
        if (padded.contains(" foto baru ")) return true
        for (i in 0 until tokens.size - 1) {
            if (firmPairMatches(tokens[i], "foto") && firmPairMatches(tokens[i + 1], "baru")) return true
        }
        val hasPastMarker = tokens.any { token -> PAST_MARKERS.any { markerMatches(token, it) } }
        for (token in tokens) {
            if (CAPTURE_RESULT_NOUNS.any { markerMatches(token, it) }) continue
            for (verb in CAPTURE_VERBS) {
                if (!markerMatches(token, verb)) continue
                // Penanda masa lalu menggugurkan SEMUA kata kerja tangkap
                // (termasuk ambil — sebelumnya hanya potret/jepret, #656).
                if (hasPastMarker) continue
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

    private fun collapseLetterRuns(token: String): String {
        val sb = StringBuilder()
        var prev = '\u0000'
        for (c in token) {
            if (c == prev && c.isLetter()) continue
            sb.append(c)
            prev = c
        }
        return sb.toString()
    }

    private val COLLAPSE_FALSE_FRIENDS = setOf("bukaan")

    private fun isCollapseFalseFriend(token: String): Boolean {
        val runChars = StringBuilder()
        val runCounts = ArrayList<Int>()
        for (c in token) {
            if (runChars.isNotEmpty() && runChars[runChars.length - 1] == c) {
                runCounts[runCounts.size - 1] = runCounts[runCounts.size - 1] + 1
            } else {
                runChars.append(c)
                runCounts.add(1)
            }
        }
        if (runChars.toString() != "bukan") return false
        return runCounts[3] >= 2
    }

    private val NEGATION_COMPOUND_TAILS = setOf("usah", "perlu")

    private fun isNegationCompound(token: String): Boolean {
        for (tail in NEGATION_COMPOUND_TAILS) {
            if (token.length <= tail.length || !token.endsWith(tail)) continue
            val head = token.dropLast(tail.length)
            if (head in NEGATIONS) return true
            if (NEGATIONS.any { base -> negationForm(head, base) }) return true
            val collapsedHead = collapseRuns(head)
            if (NEGATIONS.any { base -> collapseRuns(base) == collapsedHead }) return true
            if (NEGATIONS.any { base -> negationForm(collapsedHead, base) }) return true
        }
        return false
    }

    private fun isNegation(token: String): Boolean {
        // "usaha" (bisnis) bukan bentuk negasi "usah" — basis usah dari
        // #627 ditangkap aturan sufiks negationForm (issue #661).
        if (collapseRuns(token) == "usaha") return false
        if (token in COLLAPSE_FALSE_FRIENDS) return false
        if (isCollapseFalseFriend(token)) return false
        if (token.length > 1 && token.endsWith("2")) {
            val head = token.dropLast(1)
            if (head in NEGATIONS) return true
            val collapsedHead = collapseRuns(head)
            if (NEGATIONS.any { base -> collapseRuns(base) == collapsedHead }) return true
        }
        if (token in NEGATIONS) return true
        if (NEGATIONS.any { base -> negationForm(token, base) }) return true
        if (isNegationCompound(token)) return true
        val collapsed = collapseRuns(token)
        if (collapsed == token) return false
        if (isNegationCompound(collapsed)) return true
        if (NEGATIONS.any { base -> collapseRuns(base) == collapsed }) return true
        if (NEGATIONS.any { base ->
                val collapsedBase = collapseRuns(base)
                collapsedBase.length >= 3 && collapsed.startsWith(collapsedBase) &&
                    collapsed.substring(collapsedBase.length) in NEGATION_SUFFIXES
            }
        ) return true
        return NEGATIONS.any { base -> negationForm(collapsed, base) }
    }

    private val TOKEN_PREFIX_FALSE_FRIENDS = setOf("dayak", "kabari", "lamaran")

    private fun tokenMatches(token: String, keyword: String): Boolean {
        if (token == keyword) return true
        if (token in TOKEN_PREFIX_FALSE_FRIENDS) return false
        if (token == keyword + "nya") return true
        return keyword.length >= 4 && token.length - keyword.length in 1..3 && token.startsWith(keyword)
    }

    private fun groupHit(tokens: List<String>, padded: String, group: Group): Boolean {
        for (token in tokens) {
            for (keyword in group.tokens) {
                if (tokenMatches(token, keyword)) return true
                if (collapseLetterRuns(token) == collapseLetterRuns(keyword)) return true
            }
        }
        for (phrase in group.phrases) {
            if (padded.contains(" $phrase ")) return true
            if (padded.contains(" ${phrase}nya ")) return true
            if (collapseLetterRuns(padded).contains(" ${collapseLetterRuns(phrase)} ")) return true
            if (collapseLetterRuns(padded).contains(" ${collapseLetterRuns(phrase)}nya ")) return true
        }
        return false
    }

    private val DIGIT_UNIT = Regex("^([0-9]+)([a-z]+)$")

    private fun tokenize(text: String): Pair<List<String>, String>? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("/")) return null
        val lower = trimmed.lowercase(Locale.ROOT)
        val sb = StringBuilder()
        for (c in lower) sb.append(if (c.isLetterOrDigit()) c else ' ')
        val rawTokens = sb.toString().split(WHITESPACE).filter { it.isNotEmpty() }
        if (rawTokens.isEmpty()) return null
        val tokens = rawTokens.flatMap { token ->
            val m = DIGIT_UNIT.matchEntire(token)
            if (m != null) listOf(m.groupValues[1], m.groupValues[2]) else listOf(token)
        }
        return tokens to " " + tokens.joinToString(" ") + " "
    }

    private val FUTURE_MARKERS = setOf("besok", "nanti", "lusa")

    private val QUESTION_TAIL = setOf("apa", "apakah", "kapan", "kenapa", "mengapa", "gimana", "bagaimana", "siapa", "berapa", "mana", "kok")

    private fun isCancellation(token: String): Boolean {
        if (token == "batalin") return true
        return markerMatches(token, "batal")
    }

    private fun isReportToken(token: String): Boolean {
        return markerMatches(token, "katanya") || markerMatches(token, "bilang")
    }

    private val SEND_OR_CAPTURE_VERBS = setOf("kirim", "ambil", "jepret", "potret")

    // Pernyataan status aspek: "belum/sudah + kata kerja kirim/tangkap"
    // membicarakan pengiriman yang sudah/ belum terjadi, bukan perintah
    // baru (keluarga #629 bentuk aktif, issue #657). Mutlak seperti veto
    // masa depan: mengalahkan niat tangkap tegas.
    private fun isAspectStatus(tokens: List<String>): Boolean {
        val hasAspect = tokens.any { markerMatches(it, "belum") || markerMatches(it, "sudah") }
        if (!hasAspect) return false
        return tokens.any { token -> SEND_OR_CAPTURE_VERBS.any { markerMatches(token, it) } }
    }

    private fun isVetoed(tokens: List<String>, padded: String): Boolean {
        if (tokens.any { isNegation(it) }) return true
        if (tokens.any { isCancellation(it) }) return true
        if (tokens.any { isReportToken(it) }) return true
        if (tokens.any { token -> FUTURE_MARKERS.any { markerMatches(token, it) } }) return true
        if (isAspectStatus(tokens)) return true
        if (tokens.size == 2 && QUESTION_TAIL.any { markerMatches(tokens[1], it) }) return true
        if (hasFirmCaptureIntent(tokens, padded)) return false
        return isPhotoReference(tokens, padded) || isScreenshotReference(tokens, padded)
    }

    fun parse(text: String): String? {
        val (tokens, padded) = tokenize(text) ?: return null
        if (tokens.any { isNegation(it) }) return null
        if (tokens.any { isCancellation(it) }) return null
        if (tokens.any { isReportToken(it) }) return null
        val rawTrim = text.trim()
        if (rawTrim.startsWith("\"") || rawTrim.startsWith("'") || rawTrim.startsWith("“") || rawTrim.startsWith("‘")) return null
        val futureVetoed = tokens.any { token -> FUTURE_MARKERS.any { markerMatches(token, it) } }
        val aspectVetoed = isAspectStatus(tokens)
        // Kata tanya sesudah kata pertama menandakan pertanyaan, bukan
        // perintah tangkap — #625 baru menutup bentuk alias 2-token
        // (issue #658 untuk kalimat >=3 token di jalur parse()).
        val questionVetoed = tokens.size >= 2 && tokens.drop(1).any { token -> QUESTION_TAIL.any { markerMatches(token, it) } }
        val photoVetoed = futureVetoed || aspectVetoed || questionVetoed || (isPhotoReference(tokens, padded) && !hasFirmCaptureIntent(tokens, padded))
        val shotVetoed = futureVetoed || aspectVetoed || questionVetoed || (isScreenshotReference(tokens, padded) && !hasFirmCaptureIntent(tokens, padded))
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

    private val PHOTO_LENS_BLOCKED_PREV = setOf("minggu", "bulan", "tahun", "di")

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
        return token.trim('"', '\'', '(', ')', '[', ']', '{', '}', '*', '_', '`', ',', '.', '!', '?', ':', ';')
    }

    private val PHOTO_KEYWORDS = setOf("foto", "potret", "jepret", "photo")
    private val LAYAR_KEYWORDS = setOf("layar", "screentime")

    private fun mergeTextArgs(text: String, parsed: String?): String? {
        if (parsed == null) return null
        val tokens = text.trim().lowercase(Locale.ROOT).split(WHITESPACE).filter { it.isNotEmpty() }
        if (parsed == "/photo") {
            val start = tokens.indexOfFirst { token -> PHOTO_KEYWORDS.any { tokenMatches(token, it) } }
            if (start >= 0) {
                for (i in start + 1 until minOf(start + 3, tokens.size)) {
                    val word = stripEdgePunct(tokens[i])
                    if (word in PHOTO_LENS_WORDS) {
                        val prev = if (i > 0) stripEdgePunct(tokens[i - 1]) else ""
                        if (prev !in PHOTO_LENS_BLOCKED_PREV) return "$parsed $word"
                    }
                }
            }
            return parsed
        }
        if (parsed == "/layar") {
            val start = tokens.indexOfFirst { token -> LAYAR_KEYWORDS.any { tokenMatches(token, it) } }
            if (start >= 0) {
                for (i in start + 1 until minOf(start + 3, tokens.size)) {
                    val word = stripEdgePunct(tokens[i])
                    val attachedHours = if (word.length > 3 && word.endsWith("jam")) word.removeSuffix("jam").toIntOrNull() else null
                    if (attachedHours != null && attachedHours in 1..24) return "$parsed $attachedHours"
                    val hours = word.toIntOrNull()
                    if (hours != null && hours in 1..24 && i + 1 < tokens.size && stripEdgePunct(tokens[i + 1]) == "jam") return "$parsed $hours"
                }
            }
            return parsed
        }
        return parsed
    }

    fun isVetoedText(text: String, aliasInput: String): Boolean {
        if (isSmsSendForm(aliasInput)) return false
        val tk = tokenize(text) ?: return false
        if (aliasInput.substringBefore(" ") == "/pulihkan" && tk.first.size == 2 && isCancellation(tk.first[1])) return false
        if (!isVetoed(tk.first, tk.second)) return false
        val parsed = parse(text)
        if (parsed != null && aliasInput.substringBefore(" ") == parsed) return false
        return true
    }

    private fun smsFormHijacksParser(text: String, aliasInput: String, parsed: String?): Boolean {
        if (parsed != "/lastsms" && parsed != "/lastcalls") return false
        val arg = aliasInput.removePrefix("/sms ")
        val head = arg.split(WHITESPACE).firstOrNull().orEmpty()
        val smsParsed = SmsCommandParser.parse(arg)
        val tk = tokenize(text) ?: return false
        val hasTadi = tk.first.any { markerMatches(it, "tadi") }
        val hasQuestion = tk.first.any { markerMatches(it, "apa") || markerMatches(it, "siapa") || markerMatches(it, "isinya") }
        if (smsParsed != null && smsParsed.normalized.count { it.isDigit() } >= 10) {
            return hasTadi && hasQuestion
        }
        // Kepala/nomor 7-9 digit: umumnya formulir, tetapi pertanyaan
        // riwayat (tadi/terakhir + kata tanya) tetap dibajak ke /lastsms —
        // ambang >=7 sebelumnya membuat pemeriksaan itu tak terjangkau
        // (#659). Cabang >=10 digit di atas sengaja tetap lebih ketat
        // (nomor lengkap bukti formulir lebih kuat, terkunci #546).
        val hasHistory = hasTadi || tk.first.any { markerMatches(it, "terakhir") }
        if (head.count { it.isDigit() } >= 7) return hasHistory && hasQuestion
        if (smsParsed != null && smsParsed.number.contains(" ") && smsParsed.normalized.count { it.isDigit() } >= 7) {
            return hasHistory && hasQuestion
        }
        return tk.first.any { markerMatches(it, "terakhir") || markerMatches(it, "terbaru") || markerMatches(it, "tadi") || markerMatches(it, "siapa") }
    }

    fun intentOrAlias(text: String, aliasInput: String?): String? {
        val parsed = parse(text)
        if (aliasInput == null) return mergeTextArgs(text, parsed)
        if (isSmsSendForm(aliasInput) && !smsFormHijacksParser(text, aliasInput, parsed)) return aliasInput
        if (parsed != null) {
            val merged = mergeAliasArgs(parsed, aliasInput)
            if (merged != parsed) return merged
            return mergeTextArgs(text, parsed)
        }
        val words = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.size > 2) return null
        val tk = tokenize(text)
        if (aliasInput.substringBefore(" ") == "/pulihkan" && tk != null && tk.first.size == 2 && isCancellation(tk.first[1])) return aliasInput
        if (tk != null && isVetoed(tk.first, tk.second)) return null
        return aliasInput
    }
}
