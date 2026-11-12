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
    // Id terbesar di antara baris yang sungguh terkirim
    // (issue #2012): jangkar sapuan baris sisipan terlambat.
    // Berbeda dari penyelesaian, nilai ini memang maksimum
    // per-bidang murni -- ia TIDAK ikut membentuk pasangan
    // kursor keyset (yang tetap leksikografik, #655), hanya
    // menandai "semua baris ber-id <= ini sudah terkirim",
    // dan _ID penyedia memang monoton menurut urutan sisip.
    fun idMaks(idMaxCursor: Long, rows: List<Pair<Long, Long>>): Long {
        var m = idMaxCursor
        for (row in rows) if (row.first > m) m = row.first
        return m
    }

    // Baris sisipan terlambat (issue #2012): log panggilan
    // menyisipkan baris saat panggilan BERAKHIR dengan DATE
    // = waktu MULAI, jadi baris panggilan panjang tiba
    // dengan _ID baru tetapi tanggal di bawah kursor --
    // predikat keyset tak pernah memilihnya lagi. Baris
    // terlambat = id belum terkirim, tanggal di bawah
    // tanggal kursor, dan tidak melewati batas masa depan
    // (#1374). Tanggal == kursor adalah wilayah keyset.
    fun terlambat(id: Long, date: Long, dateCursor: Long, idMaxCursor: Long, batas: Long): Boolean {
        return id > idMaxCursor && date < dateCursor && date <= batas
    }

    // Nilai jangkar yang aman diterbitkan di tengah halaman
    // gabungan (issue #2021): halaman urut (tanggal, id),
    // jadi baris terlambat ber-id kecil bisa duduk di
    // potongan BELAKANG sementara baris ber-id besar sudah
    // terkirim di potongan awal — menerbitkan maksimum id
    // terkirim apa adanya saat halaman terputus melewati
    // baris terlambat yang belum terkirim dan ia hilang
    // permanen. Selama masih ada baris terlambat tersisa di
    // halaman, jangkar terbit dibatasi tepat di bawah id
    // tersisa terkecil; baris terlambat yang sudah terkirim
    // di atas batas itu terpilih ulang sekali pada siklus
    // berikut (duplikat terbatas sebesar awalan yang
    // terputus) — harga yang dipilih basis kode ini
    // ketimbang kehilangan (pola jangkar ulang #1374).
    fun jangkarTerbit(idMaxTerkirim: Long, idTerlambatTersisaTerkecil: Long?): Long {
        if (idTerlambatTersisaTerkecil == null) return idMaxTerkirim
        return minOf(idMaxTerkirim, idTerlambatTersisaTerkecil - 1L)
    }

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
