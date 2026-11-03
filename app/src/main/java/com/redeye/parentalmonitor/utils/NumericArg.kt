package com.redeye.parentalmonitor.utils

import java.util.Locale

// Pengurai argumen angka perintah: menerima angka polos, angka + satuan
// terpisah ("500 meter", "30 detik"), atau angka + satuan menempel
// ("500m", "30detik") — menyamai kelonggaran /layar untuk "6 jam"
// (issue #731). Sejak keputusan owner atas issue #776, satuan yang
// diberikan pengguna DIKONVERSI ke satuan bawaan perintah:
// "/photointerval 1 jam" berarti 60 menit dan "/heartbeat 30 menit"
// berarti 1 jam (dibulatkan ke satuan bawaan terdekat, setengah ke
// atas). Satuan dari keluarga lain (jarak untuk perintah durasi,
// atau sebaliknya) ditolak. Tanpa satuan, angka dipakai apa adanya
// dalam satuan bawaan.
object NumericArg {
    enum class Satuan { DETIK, MENIT, JAM, METER }

    private val UNIT_MAP = mapOf(
        "detik" to Satuan.DETIK, "dtk" to Satuan.DETIK, "s" to Satuan.DETIK,
        "menit" to Satuan.MENIT, "mnt" to Satuan.MENIT, "min" to Satuan.MENIT,
        "jam" to Satuan.JAM,
        "meter" to Satuan.METER, "m" to Satuan.METER
    )

    private val ATTACHED = Regex("^([0-9]+)([a-z]+)$")

    // Faktor ke satuan dasar keluarga: detik untuk waktu, meter jarak.
    private fun faktor(s: Satuan): Long = when (s) {
        Satuan.DETIK -> 1L
        Satuan.MENIT -> 60L
        Satuan.JAM -> 3600L
        Satuan.METER -> 1L
    }

    private fun keluarga(s: Satuan): Int = if (s == Satuan.METER) 1 else 0

    fun parse(raw: String, native: Satuan): Int? {
        val t = raw.trim().lowercase(Locale.ROOT)
        if (t.isEmpty()) return null
        val parts = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
        var num: Long? = null
        var unit: Satuan? = null
        when (parts.size) {
            1 -> {
                val single = parts[0]
                if (single.all { it.isDigit() }) {
                    num = single.toLongOrNull() ?: return null
                } else {
                    val m = ATTACHED.matchEntire(single) ?: return null
                    unit = UNIT_MAP[m.groupValues[2]] ?: return null
                    num = m.groupValues[1].toLongOrNull() ?: return null
                }
            }
            2 -> {
                if (!parts[0].all { it.isDigit() }) return null
                num = parts[0].toLongOrNull() ?: return null
                unit = UNIT_MAP[parts[1]] ?: return null
            }
            else -> return null
        }
        val n = num ?: return null
        val u = unit ?: native
        if (keluarga(u) != keluarga(native)) return null
        val dasar = n * faktor(u)
        val f = faktor(native)
        val hasil = (dasar + f / 2) / f
        if (hasil > Int.MAX_VALUE) return null
        // Masukan positif yang terkonversi menjadi 0 ditolak (issue
        // #878): di beberapa perintah 0 berarti mati/manual
        // (heartbeat, photointerval), jadi meneruskannya akan
        // mematikan fitur yang justru diminta pemilik dengan durasi
        // pendek. Angka 0 eksplisit tetap sah (mematikan dengan
        // sengaja memang arti 0 di perintah-perintah itu).
        if (hasil == 0L && n > 0L) return null
        return hasil.toInt()
    }
}
