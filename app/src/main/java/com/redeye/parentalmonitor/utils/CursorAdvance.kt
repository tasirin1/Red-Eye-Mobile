package com.redeye.parentalmonitor.utils

object CursorAdvance {
    // Batas atas jendela ambil (issue #1374): baris bertanggal
    // lebih jauh dari toleransi ini ke masa depan DITUNDA,
    // bukan diambil. Satu baris bertanggal masa depan (excursi
    // jam perangkat) yang terkirim memajukan tanggal kursor
    // melewati semua baris normal — predikat keyset lalu tak
    // pernah memilih baris normal lagi dan sinkron mati
    // senyap tanpa jalan pulih. Toleransi menampung selisih
    // wajar cap SMSC/jam penyedia vs jam perangkat.
    const val TOLERANSI_MASA_DEPAN_MS: Long = 15 * 60_000L

    fun batasAtas(now: Long): Long = now + TOLERANSI_MASA_DEPAN_MS

    /**
     * Kursor maju ke pasangan (id, tanggal) TERBESAR SECARA LEKSIKOGRAFIK
     * (tanggal dulu, lalu id) di antara baris terkirim — pasangan yang sama
     * dengan urutan ambil repository `DATE ASC, _ID ASC` dan predikat
     * keyset-nya. Maksimum per-bidang yang independen (id maks + tanggal
     * maks dari baris berbeda) DILARANG: kombinasinya melompati baris sudut
     * yang belum terkirim sehingga hilang permanen (issue #655).
     */
    fun penyelesaian(idCursor: Long, dateCursor: Long, rows: List<Pair<Long, Long>>): Pair<Long, Long> {
        var id = idCursor
        var date = dateCursor
        for (row in rows) {
            if (row.second > date || (row.second == date && row.first > id)) {
                id = row.first
                date = row.second
            }
        }
        return id to date
    }
}
