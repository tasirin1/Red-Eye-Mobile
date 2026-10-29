package com.redeye.parentalmonitor.utils

object HeldPhotos {

    data class Entry(val name: String?, val hash: Long, val raw: String? = null)

    fun parse(raw: String): List<Entry> {
        if (raw.isEmpty()) return emptyList()
        val out = ArrayList<Entry>()
        var start = 0
        while (start <= raw.length) {
            val sep = raw.indexOf(',', start)
            val token = (if (sep >= 0) raw.substring(start, sep) else raw.substring(start)).trim()
            if (token.isNotEmpty()) {
                val eq = token.indexOf('=')
                val namedHash = if (eq > 0) token.substring(eq + 1).toLongOrNull() else null
                if (eq > 0 && namedHash != null) {
                    out.add(Entry(token.substring(0, eq), namedHash))
                } else {
                    val legacy = token.toLongOrNull()
                    if (legacy != null) out.add(Entry(null, legacy)) else out.add(Entry(null, 0L, token))
                }
            }
            if (sep < 0) break
            start = sep + 1
        }
        return out
    }

    fun serialize(entries: List<Entry>): String {
        return entries.joinToString(",") { entry ->
            when {
                entry.raw != null -> entry.raw
                entry.name != null -> entry.name + "=" + entry.hash
                else -> entry.hash.toString()
            }
        }
    }

    fun hashesOf(entries: List<Entry>): LinkedHashSet<Long> {
        val out = LinkedHashSet<Long>()
        for (entry in entries) {
            if (entry.raw == null && entry.hash != 0L) out.add(entry.hash)
        }
        return out
    }

    fun heal(entries: List<Entry>, existingNames: Set<String>, decoded: Map<String, Long>?): List<Entry> {
        val kept = ArrayList<Entry>()
        for (entry in entries) {
            when {
                entry.raw != null -> kept.add(entry)
                entry.name != null -> if (entry.name in existingNames) kept.add(entry)
                decoded == null -> kept.add(entry)
                else -> {
                    val converted = decoded.entries.firstOrNull { it.value == entry.hash }?.key
                    if (converted != null) kept.add(Entry(converted, entry.hash))
                }
            }
        }
        val seenNames = HashSet<String>()
        val seenLegacy = HashSet<Long>()
        val out = ArrayList<Entry>()
        for (entry in kept) {
            when {
                entry.name != null -> if (seenNames.add(entry.name)) out.add(entry)
                entry.raw == null -> if (seenLegacy.add(entry.hash)) out.add(entry)
                else -> out.add(entry)
            }
        }
        return out
    }

    fun withHeld(entries: List<Entry>, name: String, hash: Long, max: Int): List<Entry> {
        if (hash == 0L) return entries
        val out = ArrayList<Entry>()
        for (entry in entries) {
            val sameHash = entry.raw == null && entry.hash == hash
            val sameName = entry.raw == null && entry.name != null && entry.name == name
            if (!sameHash && !sameName) out.add(entry)
        }
        out.add(Entry(name, hash))
        while (out.size > max) {
            val idx = out.indexOfFirst { it.raw == null }
            out.removeAt(if (idx >= 0) idx else 0)
        }
        return out
    }

    fun withoutNames(entries: List<Entry>, names: Set<String>): List<Entry> {
        return entries.filter { it.name == null || it.name !in names }
    }

    fun withoutHashes(entries: List<Entry>, hashes: Set<Long>): List<Entry> {
        return entries.filter { it.raw != null || it.hash !in hashes }
    }
}
