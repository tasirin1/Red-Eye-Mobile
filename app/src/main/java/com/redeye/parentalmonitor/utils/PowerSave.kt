package com.redeye.parentalmonitor.utils

object PowerSave {

    fun cameraIntervalEff(rawMinutes: Int, lowPower: Boolean): Int {
        val minutes = rawMinutes.coerceIn(0, 60)
        // Penganda hemat kamera diperkuat x2 -> x4 (issue
        // #1574): putaran kamera adalah putaran termahal
        // (kamera + JPEG + radio), jadi saat baterai rendah /
        // Battery Saver aktif penghematannya harus terasa —
        // interval bawaan 1 mnt menjadi 4 mnt, bukan 2 mnt.
        return if (lowPower) (minutes * 4).coerceAtMost(60) else minutes
    }

    fun patrolIntervalEff(rawMinutes: Int, lowPower: Boolean): Int {
        val minutes = rawMinutes.coerceIn(5, 180)
        return if (lowPower) (minutes * 2).coerceAtMost(180) else minutes
    }

    fun cameraSlowed(rawMinutes: Int): Boolean {
        return cameraIntervalEff(rawMinutes, true) > rawMinutes.coerceIn(0, 60)
    }

    fun patrolSlowed(rawMinutes: Int): Boolean {
        return patrolIntervalEff(rawMinutes, true) > rawMinutes.coerceIn(5, 180)
    }

    // Gerbang gerak patroli (keputusan pemilik 2026-11-11):
    // putaran patroli boleh memakai ulang posisi cache
    // TANPA sesi lokasi baru hanya bila sensor gerak
    // signifikan terpasang, pernah ada pengambilan posisi,
    // tidak ada gerak tercatat sesudah pengambilan itu,
    // dan umur cache <= 90 menit. Sensor pemicunya berdaya
    // nyaris nol (perangkat keras), jadi vonis "perangkat
    // tidak berpindah" hampir gratis; di luar syarat ini
    // patroli mengambil posisi seperti biasa.
    fun patrolPakaiCache(sensorSiap: Boolean, gerakTerakhirElapsed: Long, ambilTerakhirElapsed: Long, umurCacheMs: Long): Boolean {
        if (!sensorSiap) return false
        if (ambilTerakhirElapsed <= 0L) return false
        if (gerakTerakhirElapsed > ambilTerakhirElapsed) return false
        return umurCacheMs in 0L..90 * 60_000L
    }

    // Gerbang gerak kamera interval (keputusan pemilik
    // 2026-11-11): interval jepretan otomatis boleh
    // diregangkan hanya bila sensor gerak signifikan
    // terpasang, pernah ada jepretan, tidak ada gerak
    // tercatat sesudah jepretan terakhir, dan layar
    // mati. Perangkat yang diangkat/bergerak memicu
    // sensor sehingga irama normal kembali sendiri
    // pada putaran berikutnya.
    fun kameraLonggarGerak(sensorSiap: Boolean, gerakTerakhirElapsed: Long, jepretTerakhirElapsed: Long, layarMati: Boolean): Boolean {
        if (!sensorSiap) return false
        if (jepretTerakhirElapsed <= 0L) return false
        if (gerakTerakhirElapsed > jepretTerakhirElapsed) return false
        return layarMati
    }

    // Interval kamera efektif sesudah gerbang gerak:
    // empat kali lipat saat longgar (masukan sudah
    // hasil cameraIntervalEff yang berplafon 60, jadi
    // hasil di sini berplafon 240 menit).
    fun cameraIntervalGerakEff(effMinutes: Int, longgar: Boolean): Int {
        if (!longgar || effMinutes <= 0) return effMinutes
        return effMinutes * 4
    }
}
