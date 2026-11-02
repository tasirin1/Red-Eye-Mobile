package com.redeye.parentalmonitor.utils

import java.util.Locale

object IntentParser {

    private class Group(val tokens: Set<String>, val phrases: Set<String>)

    private class Intent(val command: String, val groups: List<Group>)

    private val WHITESPACE = Regex("\\s+")

    private val NEGATIONS = setOf(
        "jangan", "tidak", "nggak", "enggak", "gak", "bukan", "tak", "ndak",
        "ga", "ngga", "engga", "gausah", "kagak", "jgn",
        "tdk", "gk", "dak", "usah", "jngan", "tidka", "ogah", "tdak"
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

    private val TOKEN_PREFIX_FALSE_FRIENDS = setOf("dayak", "kabari", "lamaran", "manajer", "harimau", "bilangan", "batalion")

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

    private val FUTURE_MARKERS = setOf("besok", "nanti", "lusa", "esok", "kelak")

    private fun hasFutureMarker(tokens: List<String>, padded: String): Boolean {
        if (tokens.any { token -> FUTURE_MARKERS.any { markerMatches(token, it) } }) return true
        // Frasa "malam ini" merujuk malam yang akan datang (issue #665).
        return padded.contains(" malam ini ")
    }

    private val QUESTION_TAIL = setOf("apa", "apakah", "kapan", "kenapa", "mengapa", "gimana", "bagaimana", "siapa", "berapa", "mana", "kok", "kah", "kan")

    private fun isCancellation(token: String): Boolean {
        if (token == "batalin") return true
        // Nomina "pembatalan" berawalan pem- sehingga tak diawali "batal"
        // (issue #668, sisa #628).
        if (collapseRuns(token) == "pembatalan") return true
        return markerMatches(token, "batal")
    }

    // Kutipan berisi >=2 kata di tengah kalimat adalah ucapan yang DILAPORKAN
    // (mis. bunyi pesan masuk), bukan perintah — sisa #640 yang baru menutup
    // kutipan di awal kalimat (issue #666). Kutipan satu kata ("depan")
    // adalah penekanan dan tetap sah (uji terkunci).
    private fun hasQuotedUtterance(text: String): Boolean {
        // Kutip tunggal & kutip tak tertutup kini ikut dilacak (issue #697):
        // ucapan yang dilaporkan tak selalu memakai kutip ganda tertutup.
        var open = '\u0000'
        var spanStart = -1
        fun closes(c: Char): Boolean = when (open) {
            '"' -> c == '"'
            '“' -> c == '”' || c == '"'
            '\'' -> c == '\'' || c == '’'
            '‘' -> c == '’' || c == '\''
            else -> false
        }
        for (i in text.indices) {
            val c = text[i]
            if (open == '\u0000') {
                if (c == '"' || c == '“' || c == '\'' || c == '‘') {
                    // Kutip tunggal hanya di awal kata (sesudah spasi/awal)
                    // agar apostrof di tengah kata tak membuka kutipan.
                    if ((c == '\'' || c == '‘') && i > 0 && !text[i - 1].isWhitespace()) continue
                    open = c
                    spanStart = i
                }
            } else if (closes(c)) {
                val span = text.substring(spanStart + 1, i).trim()
                if (span.split(WHITESPACE).count { it.isNotEmpty() } >= 2) return true
                open = '\u0000'
                spanStart = -1
            }
        }
        if (open != '\u0000') {
            val span = text.substring(spanStart + 1).trim()
            if (span.split(WHITESPACE).count { it.isNotEmpty() } >= 2) return true
        }
        return false
    }

    private fun isReportToken(token: String): Boolean {
        return markerMatches(token, "katanya") || markerMatches(token, "bilang")
    }

    private fun isReport(tokens: List<String>, padded: String): Boolean {
        if (tokens.any { isReportToken(it) }) return true
        // Penanda laporan berbentuk frasa "kata dia"/"kata anak" (issue #667).
        return padded.contains(" kata dia ") || padded.contains(" kata anak ")
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

    // Perintah baca-saja: argumen masa lalu sah ("waspada kemarin").
    // Perintah mutasi (hilang/kunci/dll.) tetap menerima veto rujukan.
    private val READ_ONLY_HEADS = setOf(
        "/location", "/battery", "/help", "/version", "/waspada", "/minggu",
        "/layar", "/hematdata", "/jejak", "/kendara", "/lastsms", "/lastcalls"
    )

    private val MUTATION_ARGS = setOf("on", "off", "nyala", "mati")

    // /hematdata & /kendara dwifungsi: tanpa argumen/status mereka laporan
    // baca-saja, tetapi dengan argumen on/off/nyala/mati mereka MUTASI —
    // bentuk mutasinya tidak boleh melewati veto rujukan (issue #696).
    private fun isReadOnlyTarget(aliasInput: String): Boolean {
        val head = aliasInput.substringBefore(" ")
        if (head !in READ_ONLY_HEADS) return false
        if (head == "/hematdata" || head == "/kendara") {
            val arg = aliasInput.substringAfter(" ", "").trim()
            val first = arg.split(WHITESPACE).firstOrNull().orEmpty()
            if (first in MUTATION_ARGS) return false
        }
        return true
    }

    private fun isVetoed(tokens: List<String>, padded: String, readOnlyTarget: Boolean = false): Boolean {
        if (tokens.any { isNegation(it) }) return true
        if (tokens.any { isCancellation(it) }) return true
        if (isReport(tokens, padded)) return true
        if (isAspectStatus(tokens)) return true
        // Veto tanya dinilai dari token inti (issue #744): kata penegas
        // ekor dibuang dulu seperti di pipa fallback, karena sebelumnya
        // "kunci apa dong" lolos (3 token mentah) dan dieksekusi sebagai
        // /lock.
        val core = coreTokens(tokens)
        if (core.size == 2 && QUESTION_TAIL.any { markerMatches(core[1], it) }) return true
        // Veto rujukan foto/screenshot tak relevan untuk perintah
        // baca-saja: penanda masa lalu di sana adalah argumen sah
        // ("waspada kemarin", "lokasi kemarin") — issue #672.
        if (readOnlyTarget) return false
        // Penanda masa depan juga tak memveto target baca-saja
        // (issue #755): parse() menoleransinya ("lokasi anak besok"
        // -> /location), jadi jalur alias diselaraskan — "lokasi
        // besok" sebelumnya gugur hanya karena jatuh ke fallback.
        if (hasFutureMarker(tokens, padded)) return true
        if (hasFirmCaptureIntent(tokens, padded)) return false
        return isPhotoReference(tokens, padded) || isScreenshotReference(tokens, padded)
    }

    fun parse(text: String): String? {
        val (tokens, padded) = tokenize(text) ?: return null
        if (tokens.any { isNegation(it) }) return null
        if (tokens.any { isCancellation(it) }) return null
        if (isReport(tokens, padded)) return null
        val rawTrim = text.trim()
        if (rawTrim.startsWith("\"") || rawTrim.startsWith("'") || rawTrim.startsWith("“") || rawTrim.startsWith("‘")) return null
        if (hasQuotedUtterance(rawTrim)) return null
        val futureVetoed = hasFutureMarker(tokens, padded)
        val aspectVetoed = isAspectStatus(tokens)
        // Kata tanya sesudah kata pertama menandakan pertanyaan, bukan
        // perintah tangkap — #625 baru menutup bentuk alias 2-token
        // (issue #658 untuk kalimat >=3 token di jalur parse()).
        val questionVetoed = tokens.size >= 2 && tokens.drop(1).any { token -> QUESTION_TAIL.any { markerMatches(token, it) } }
        val photoVetoed = futureVetoed || aspectVetoed || questionVetoed || (isPhotoReference(tokens, padded) && !hasFirmCaptureIntent(tokens, padded))
        val shotVetoed = futureVetoed || aspectVetoed || questionVetoed || (isScreenshotReference(tokens, padded) && !hasFirmCaptureIntent(tokens, padded))
        // Frasa "riwayat lokasi/perjalanan" adalah penanda riwayat yang
        // tegas (issue #689): tanpa ini, /location ikut menilai kata
        // "lokasi" sehingga seri 2-2 (-> null) atau menang salah
        // ("...hp sekarang" -> lokasi saat ini). Tekan /location dan beri
        // /jejak bonus agar frasa riwayat selalu menang sebagai /jejak.
        val historyLocation = padded.contains(" riwayat lokasi ") || padded.contains(" riwayat perjalanan ")
        var best: String? = null
        var bestScore = 0
        var tie = false
        val top = mutableListOf<String>()
        for (intent in INTENTS) {
            var score = 0
            val suppressed = (intent.command == "/photo" && photoVetoed) || (intent.command == "/screenshot" && shotVetoed) ||
                (historyLocation && intent.command == "/location")
            if (!suppressed) {
                for (group in intent.groups) {
                    if (groupHit(tokens, padded, group)) score++
                }
                if (historyLocation && intent.command == "/jejak") score++
            }
            if (score > bestScore) {
                best = intent.command
                bestScore = score
                tie = false
                top.clear()
                top.add(intent.command)
            } else if (score == bestScore && score > 0) {
                tie = true
                top.add(intent.command)
            }
        }
        // Seri laporan tunggal (issue #709): kata generik "laporan" memberi
        // /digest nilai penuh, tetapi kata benda khas intent lain (layar /
        // minggu) adalah penentu maksud sebenarnya. Permintaan ganda sejati
        // (kedua frasa cakupan hadir) tetap seri -> null.
        if (tie && bestScore >= 2 && "/digest" in top &&
            !(padded.contains(" hari ini ") && padded.contains(" minggu ini "))
        ) {
            for ((cmd, noun) in listOf("/layar" to "layar", "/minggu" to "minggu")) {
                if (cmd in top && tokens.any { markerMatches(it, noun) }) return cmd
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

    private val PHOTO_LENS_BLOCKED_PREV = setOf("minggu", "bulan", "tahun", "di", "pekan", "yang")

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
    private val LAYAR_PHRASES = listOf("screen time", "main hp", "main hape", "pemakaian aplikasi", "pakai aplikasi", "waktu layar")

    // Pencocokan kata kunci jalur gabung diselaraskan dengan groupHit
    // parse(): tokenMatches ATAU setara sesudah pelipatan elongasi huruf
    // (issue #727).
    private fun mergeKeywordMatch(token: String, keyword: String): Boolean {
        return tokenMatches(token, keyword) || collapseLetterRuns(stripEdgePunct(token)) == collapseLetterRuns(keyword)
    }

    private fun mergeTextArgs(text: String, parsed: String?): String? {
        if (parsed == null) return null
        val tokens = text.trim().lowercase(Locale.ROOT).split(WHITESPACE).filter { it.isNotEmpty() }
        if (parsed == "/photo") {
            val start = tokens.indexOfFirst { token -> PHOTO_KEYWORDS.any { mergeKeywordMatch(token, it) } }
            if (start >= 0) {
                var checked = 0
                for (i in start + 1 until minOf(start + 6, tokens.size)) {
                    if (PHOTO_KEYWORDS.any { mergeKeywordMatch(tokens[i], it) }) continue
                    // Kata penegas tidak menghabiskan anggaran pindai
                    // (issue #753): "foto sekarang dong depan" sebelumnya
                    // kehilangan lensa karena dua penegas dihitung jarak.
                    if (stripEdgePunct(tokens[i]) in TAIL_FILLERS) continue
                    // "yang" juga tak menghabiskan anggaran pindai
                    // (issue #770): kata penghubung relatif, bukan isi.
                    if (stripEdgePunct(tokens[i]) == "yang") continue
                    checked++
                    if (checked > 2) break
                    val word = stripEdgePunct(tokens[i])
                    if (word in PHOTO_LENS_WORDS) {
                        val prev = if (i > 0) stripEdgePunct(tokens[i - 1]) else ""
                        if (prev !in PHOTO_LENS_BLOCKED_PREV) return "$parsed $word"
                        // "yang" hanya memblokir bila frasanya berlanjut
                        // jadi keterangan ("yang belakang itu adiknya");
                        // bila kata lensa adalah isi terakhir ("foto yang
                        // belakang dong") itu pilihan lensa (issue #770).
                        if (prev == "yang" && (i + 1 until tokens.size).none { stripEdgePunct(tokens[it]) !in TAIL_FILLERS }) return "$parsed $word"
                    }
                }
            }
            return parsed
        }
        if (parsed == "/layar") {
            var start = tokens.indexOfFirst { token -> LAYAR_KEYWORDS.any { mergeKeywordMatch(token, it) || mergeKeywordMatch(token.replace("_", ""), it) } }
            var anchorLen = 1
            if (start < 0) {
                // Jangkar frasa grup-1 (issue #726): "screen time", "main hp",
                // dst dikenali parse() lewat frasa tetapi tak punya token
                // tunggal — tanpa ini argumen jam sesudah frasa hilang.
                for (phrase in LAYAR_PHRASES) {
                    val pw = phrase.split(' ')
                    val idx = (0..tokens.size - pw.size).firstOrNull { s -> pw.indices.all { stripEdgePunct(tokens[s + it]) == pw[it] } } ?: continue
                    start = idx
                    anchorLen = pw.size
                    break
                }
            }
            if (start >= 0) {
                // Jendela 2 token terhitung; penegas dilewati tanpa
                // dihitung dan rentang pindai diperluas secukupnya
                // (issue #753): "cek layar dong dong 6 jam" sebelumnya
                // kehilangan argumen jamnya.
                var counted = 0
                for (i in start + anchorLen until minOf(start + anchorLen + 4, tokens.size)) {
                    if (stripEdgePunct(tokens[i]) in TAIL_FILLERS) continue
                    counted++
                    if (counted > 2) break
                    val word = stripEdgePunct(tokens[i]).replace("-", "")
                    val attachedHours = if (word.length > 3 && word.endsWith("jam")) word.removeSuffix("jam").toIntOrNull() else null
                    if (attachedHours != null && attachedHours in 1..24) return "$parsed $attachedHours"
                    val hours = word.toIntOrNull()
                    if (hours != null && hours in 1..24 && i + 1 < tokens.size && stripEdgePunct(tokens[i + 1]) == "jam") return "$parsed $hours"
                    // Jam desimal (issue #774): "6,5 jam" sebelumnya jatuh
                    // diam-diam ke laporan bawaan 24 jam; kini dibulatkan
                    // ke jam terdekat (floor(x + 0,5) untuk nilai positif).
                    val numText = if (word.length > 3 && word.endsWith("jam")) word.removeSuffix("jam") else word
                    val decHours = numText.replace(',', '.').toDoubleOrNull()?.plus(0.5)?.toInt()
                    val nextJam = i + 1 < tokens.size && stripEdgePunct(tokens[i + 1]) == "jam"
                    if (decHours != null && decHours in 1..24 && (numText != word || nextJam)) return "$parsed $decHours"
                }
            }
            return parsed
        }
        return parsed
    }

    // Token inti: kata penegas ekor dibuang (issue #744). Token yang
    // cocok penanda tanya (mis. "kok") tidak ikut dibuang agar veto
    // lamanya tidak berubah.
    private fun coreTokens(tokens: List<String>): List<String> {
        var core = tokens
        while (core.isNotEmpty() && stripEdgePunct(core.last()) in TAIL_FILLERS &&
            QUESTION_TAIL.none { markerMatches(core.last(), it) }
        ) {
            core = core.dropLast(1)
        }
        return core
    }

    fun isVetoedText(text: String, aliasInput: String): Boolean {
        if (isSmsSendForm(aliasInput)) return false
        val tk = tokenize(text) ?: return false
        val core = coreTokens(tk.first)
        if (aliasInput.substringBefore(" ") == "/pulihkan" && core.size == 2 && isCancellation(core[1])) return false
        if (!isVetoed(tk.first, tk.second, isReadOnlyTarget(aliasInput))) return false
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
        // Ekor sesudah nomor: pertanyaan riwayat sejati pendek (<=4 token);
        // ekor panjang adalah ISI PESAN formulir yang kebetulan memuat
        // "tadi" + kata tanya (issue #690).
        val tailTokens = if (smsParsed != null) {
            tk.first.size - 1 - (tokenize(smsParsed.number)?.first?.size ?: smsParsed.number.split(WHITESPACE).size)
        } else {
            // Nomor tak ter-parse (mis. 16 digit di luar rentang parser):
            // ekor tetap dihitung dari token kepala mentah agar cabang
            // kepala >=7 digit di bawah tetap terjangkau (issue #723) —
            // sebelumnya ekor dianggap tak hingga dan cabang itu mati.
            tk.first.size - 1 - (tokenize(head)?.first?.size ?: 1)
        }
        val shortTail = tailTokens <= 4
        if (smsParsed != null && smsParsed.normalized.count { it.isDigit() } >= 10) {
            return hasTadi && hasQuestion && shortTail
        }
        // Kepala/nomor 7-9 digit: umumnya formulir, tetapi pertanyaan
        // riwayat (tadi/terakhir + kata tanya) tetap dibajak ke /lastsms —
        // ambang >=7 sebelumnya membuat pemeriksaan itu tak terjangkau
        // (#659). Cabang >=10 digit di atas sengaja tetap lebih ketat
        // (nomor lengkap bukti formulir lebih kuat, terkunci #546).
        val hasHistory = hasTadi || tk.first.any { markerMatches(it, "terakhir") }
        if (head.count { it.isDigit() } >= 7) return hasHistory && hasQuestion && shortTail
        if (smsParsed != null && smsParsed.number.contains(" ") && smsParsed.normalized.count { it.isDigit() } >= 7) {
            return hasHistory && hasQuestion && shortTail
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
        // Kata dihitung dari bentuk ternormalkan (issue #729): huruf kecil
        // (penegas "DONG" tetap dikenal) dan token tanpa huruf/angka sama
        // sekali (emoji penutup) tidak dihitung sebagai kata — sebelumnya
        // keduanya menggugurkan perintah yang sah menjadi null.
        var words = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
            .map { it.lowercase(Locale.ROOT) }
            .filter { w -> w.any { it.isLetterOrDigit() } }
        // Kata penegas sopan di ekor (issue #708): "kuota on dong" dan
        // "kunci hp sekarang" sebelumnya gugur oleh batas <=2 kata. Buang
        // kata penegas ekor lebih dulu, lalu batas lama berlaku. Pemeriksaan
        // veto di bawah tetap memakai teks utuh.
        while (words.size > 2 && stripEdgePunct(words.last()) in TAIL_FILLERS) {
            words = words.dropLast(1)
        }
        // Angka + satuan dihitung satu argumen (issue #731): "jemput 500
        // meter" sebelumnya 3 kata dan gugur total, padahal /layar menerima
        // bentuk yang sama.
        if (words.size == 3 && words[1].all { it.isDigit() } && stripEdgePunct(words[2]) in ARG_UNITS) {
            words = listOf(words[0], words[1])
        }
        if (words.size > 2) return null
        val tk = tokenize(text)
        // Pengecualian batal-restore dinilai dari token inti juga
        // (issue #752): "restore batal dong" sebelumnya gugur karena
        // token mentahnya 3. Yang dikembalikan bentuk bersihnya agar
        // argumen handler persis "batal".
        if (aliasInput.substringBefore(" ") == "/pulihkan" && tk != null) {
            val core = coreTokens(tk.first)
            if (core.size == 2 && isCancellation(core[1])) return cleanAliasTail(aliasInput)
        }
        if (tk != null && isVetoed(tk.first, tk.second, isReadOnlyTarget(aliasInput))) return null
        return cleanAliasTail(aliasInput)
    }

    private val TAIL_FILLERS = setOf("dong", "sih", "deh", "ya", "yuk", "sekarang", "nih", "kok")
    // Selaras dengan NumericArg.UNITS (issue #754): sebelumnya "s"/"min"
    // diterima handler lewat jalur slash tetapi ditolak jalur bahasa
    // alami karena daftar ini tertinggal dua satuan.
    private val ARG_UNITS = setOf("meter", "m", "detik", "menit", "jam", "dtk", "mnt", "s", "min")

    // Kata penegas ekor juga dibuang dari argumen alias yang dikembalikan
    // (issue #728): handler hilir memeriksa argumen secara persis, jadi
    // "/jampulang 21:30 dong" dan "/hematdata on dong" sebelumnya ditolak
    // "Cara pakai". Tanda baca tepi pada token argumen terakhir juga
    // dibersihkan. Argumen tunggal yang hanya berisi penegas dibiarkan
    // (perilaku terkunci perintah tanpa argumen, mis. "cekmati dong").
    private fun cleanAliasTail(aliasInput: String): String {
        val parts = aliasInput.split(WHITESPACE).filter { it.isNotEmpty() }
        if (parts.size < 2) return aliasInput
        var args = parts.drop(1)
        while (args.size > 1 && (stripEdgePunct(args.last().lowercase(Locale.ROOT)) in TAIL_FILLERS || args.last().none { it.isLetterOrDigit() })) {
            args = args.dropLast(1)
        }
        // Satuan sesudah angka dibuang dari argumen yang dikembalikan
        // (issue #731): "/jemput 500 meter" menjadi "/jemput 500".
        if (args.size == 2 && args[0].all { it.isDigit() } && stripEdgePunct(args[1].lowercase(Locale.ROOT)) in ARG_UNITS) {
            args = listOf(args[0])
        }
        val lastClean = stripEdgePunct(args.last())
        return (listOf(parts[0]) + args.dropLast(1) + lastClean).joinToString(" ")
    }
}
