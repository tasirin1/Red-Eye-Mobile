package com.redeye.parentalmonitor.utils

object DigestSchedule {
    enum class Verdict { RESET, WAIT, SEND }

    fun periodicDecision(lastMs: Long, nowMs: Long): Verdict {
        if (lastMs > nowMs) return Verdict.RESET
        if (nowMs - lastMs < 6 * 60 * 60_000L) return Verdict.WAIT
        return Verdict.SEND
    }

    // Jangkar periode berikutnya tetap di kisi fase lama (issue #736):
    // maju kelipatan penuh 6 jam dari jangkar sebelumnya, bukan ke waktu
    // kirim aktual — overshoot satu siklus gelung tidak lagi menggeser
    // jam kirim sedikit demi sedikit.
    fun nextPeriodicAnchor(lastMs: Long, nowMs: Long): Long {
        if (lastMs <= 0L || nowMs <= lastMs) return nowMs
        val period = 6 * 60 * 60_000L
        return lastMs + (nowMs - lastMs) / period * period
    }

    fun localHour(nowMs: Long, offsetMs: Long): Int {
        return A11yUsageMath.localHour(nowMs, offsetMs.toInt())
    }

    // Kunci cap penyelesaian sesudah kirim digest malam
    // berhasil (issue #621; diekstrak agar keputusannya
    // terkunci langsung oleh uji, bukan hanya akibatnya
    // — issue #1691): jalur reguler mencap kunci HARI
    // INI; jalur susulan mencap kunci KEMARIN. Mencap
    // hari ini pada jalur susulan akan mematikan digest
    // reguler malam nanti; mencap selain kemarin bisa
    // mempersenjatai ulang susulan yang sama.
    fun nightDoneKeyAfterSend(wasCatchUp: Boolean, yesterdayKey: String, todayKey: String): String {
        return if (wasCatchUp) yesterdayKey else todayKey
    }

    fun nightDue(hour: Int, doneKey: String, todayKey: String): Boolean {
        return hour >= 21 && todayKey.isNotEmpty() && doneKey < todayKey
    }

    // Susulan hanya pada jendela pagi 05–11 (issue #738): tanpa syarat
    // jam, susulan tertembak pada siklus pertama kapan pun — termasuk
    // tengah hari — dan digest malam reguler tetap terkirim lagi malam
    // yang sama (dua ringkasan harian dalam sehari). Sesudah jendela
    // pagi, susulan dilewati; digest malam reguler tetap jatuh tempo
    // malam ini karena penanda selesai tidak berubah.
    fun nightCatchUpDue(doneKey: String, yesterdayKey: String, todayKey: String, hour: Int): Boolean {
        return hour in 5..11 && doneKey.isNotEmpty() && yesterdayKey.isNotEmpty() && doneKey < yesterdayKey
    }

    // Pindah zona waktu jauh ke barat (issue #852): kunci hari lokal
    // terbaca mundur sehingga perbandingan string di atas membuat
    // digest hilang dua malam. Pembeda dari jam dimundurkan manual
    // (#601) adalah EPOCH: pindah zona membiarkan epoch berjalan
    // normal, jam mundur menggerakkan epoch ke belakang. Jadi bila
    // cap terbaca "hari ini/di depan" tetapi umur epoch-nya sudah
    // >= 16 jam, cap itu tidak mungkin ditulis malam ini — malam
    // zona baru tetap jatuh tempo.
    // Perpindahan kecil 1–2 jam ke barat (issue #1079 — kasus
    // domestik WIB/WITA/WIT yang paling umum) tak pernah mencapai
    // 16 jam di jendela mana pun. Saksi pembedanya adalah OFFSET
    // zona saat cap ditulis vs sekarang: pindah ke barat
    // menurunkan offset, jam dimundurkan manual tidak mengubah
    // offset sama sekali. Bila offset turun sebesar delta, pukul
    // 21:00 zona baru terjadi tepat delta sesudah cap (dan
    // jendela susulan pagi 8–14 jam sesudahnya), jadi umur cap
    // sebesar itu — dengan toleransi jadwal 30 menit — sudah
    // membuktikan malam zona baru. Offset tak dikenal (cap
    // warisan sebelum #1079, Long.MIN_VALUE) -> aturan 16 jam.
    private fun zoneShiftProven(ageMs: Long, doneOffsetMs: Long, nowOffsetMs: Long, extraMs: Long): Boolean {
        if (ageMs >= 16 * 3_600_000L) return true
        if (doneOffsetMs == Long.MIN_VALUE) return false
        val delta = doneOffsetMs - nowOffsetMs
        return delta > 0L && ageMs >= delta + extraMs
    }

    fun nightZoneShiftDue(doneKey: String, todayKey: String, hour: Int, doneAtMs: Long, nowMs: Long, doneOffsetMs: Long, nowOffsetMs: Long): Boolean {
        return hour >= 21 && doneKey.isNotEmpty() && doneAtMs > 0L && doneKey >= todayKey &&
            zoneShiftProven(nowMs - doneAtMs, doneOffsetMs, nowOffsetMs, -30 * 60_000L)
    }

    fun nightZoneShiftCatchUpDue(doneKey: String, yesterdayKey: String, hour: Int, doneAtMs: Long, nowMs: Long, doneOffsetMs: Long, nowOffsetMs: Long): Boolean {
        return hour in 5..11 && doneKey.isNotEmpty() && yesterdayKey.isNotEmpty() && doneAtMs > 0L && doneKey >= yesterdayKey &&
            zoneShiftProven(nowMs - doneAtMs, doneOffsetMs, nowOffsetMs, 7 * 3_600_000L + 30 * 60_000L)
    }
}
