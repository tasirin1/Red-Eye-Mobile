package com.redeye.parentalmonitor.utils

object CursorAdvance {
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
