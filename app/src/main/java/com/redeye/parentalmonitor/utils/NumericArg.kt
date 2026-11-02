package com.redeye.parentalmonitor.utils

import java.util.Locale

// Pengurai argumen angka perintah: menerima angka polos, angka + satuan
// terpisah ("500 meter", "30 detik"), atau angka + satuan menempel
// ("500m", "30detik") — menyamai kelonggaran /layar untuk "6 jam"
// (issue #731). Angka dipakai apa adanya dalam satuan bawaan perintah
// yang memanggil; satuan lain dari daftar dikenal tetap diterima sebagai
// hiasan, bukan dikonversi. Di luar bentuk itu hasilnya null.
object NumericArg {
    private val UNITS = setOf("detik", "menit", "jam", "meter", "dtk", "mnt", "m", "s", "min")
    private val ATTACHED = Regex("^([0-9]+)([a-z]+)$")

    fun parse(raw: String): Int? {
        val t = raw.trim().lowercase(Locale.ROOT)
        if (t.isEmpty()) return null
        val parts = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size == 1) {
            val single = parts[0]
            if (single.all { it.isDigit() }) return single.toIntOrNull()
            val m = ATTACHED.matchEntire(single) ?: return null
            if (m.groupValues[2] !in UNITS) return null
            return m.groupValues[1].toIntOrNull()
        }
        if (parts.size == 2) {
            if (!parts[0].all { it.isDigit() }) return null
            val num = parts[0].toIntOrNull() ?: return null
            if (parts[1] !in UNITS) return null
            return num
        }
        return null
    }
}
