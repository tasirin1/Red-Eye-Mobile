package com.redeye.parentalmonitor.utils

object CommandSuggest {
    private val KNOWN_COMMANDS = setOf("/photo", "/screenshot", "/status", "/perms", "/lastcalls", "/lastsms", "/photointerval", "/patrol", "/patrolinterval", "/pause", "/battery", "/stop", "/resume", "/location", "/camera", "/notif", "/log", "/syncinterval", "/restart", "/flush", "/clearqueue", "/lock", "/ring", "/ping", "/record", "/recordvideo", "/sms", "/smsconfirm", "/lastnotif", "/version", "/uptime", "/alive", "/cekmati", "/sembuh", "/layar", "/heartbeat", "/contacts", "/apps", "/storage", "/history", "/help", "/start", "/login", "/logout", "/night", "/ringkas", "/watch", "/home", "/school", "/digest", "/darurat", "/jampulang", "/minggu", "/watchnomor", "/jejak", "/waspada", "/hilang", "/jemput", "/kendara", "/hematdata", "/izinshot", "/pulihkan", "/audit", "/bersihkan", "/appalert", "/lindungi", "/pantauapp")

    fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        val m = a.length
        val n = b.length
        if (m == 0) return n
        if (n == 0) return m
        var prev = IntArray(n + 1) { it }
        var cur = IntArray(n + 1)
        for (i in 1..m) {
            cur[0] = i
            for (j in 1..n) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[n]
    }

    fun transposedEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var i = 0
        while (i < a.length && a[i] == b[i]) i++
        if (i + 1 >= a.length) return false
        if (a[i] != b[i + 1] || a[i + 1] != b[i]) return false
        for (j in i + 2 until a.length) if (a[j] != b[j]) return false
        return true
    }

    private val MUTATING_COMMANDS = setOf("/lock", "/stop", "/restart", "/logout", "/clearqueue", "/pause", "/record", "/recordvideo", "/sms", "/photo", "/screenshot", "/camera", "/ring", "/pulihkan", "/bersihkan", "/flush")

    fun suggestCommand(typed: String): String? {
        var best: String? = null
        var bestDist = Int.MAX_VALUE
        var transposed: String? = null
        for (known in KNOWN_COMMANDS) {
            val d = try { editDistance(typed, known) } catch (_: Exception) { Int.MAX_VALUE }
            if (d < bestDist) {
                bestDist = d
                best = known
            }
            if (transposed == null) {
                try { if (transposedEquals(typed, known)) transposed = known } catch (_: Exception) { }
            }
        }
        if (best != null && bestDist == 2) {
            val winner = transposed
            if (winner != null) {
                best = winner
                bestDist = 1
            }
        }
        val limit = if (typed.length <= 5) 1 else 2
        if (best == null || bestDist !in 1..limit) return null
        if (best in MUTATING_COMMANDS && !best.startsWith(typed)) {
            val others = KNOWN_COMMANDS.filter { it != best && it.startsWith(typed) }
            if (others.isNotEmpty()) return if (others.size == 1) others[0] else null
        }
        return best
    }
}
