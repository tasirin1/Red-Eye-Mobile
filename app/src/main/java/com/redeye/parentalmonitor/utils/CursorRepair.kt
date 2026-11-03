package com.redeye.parentalmonitor.utils

object CursorRepair {
    fun shouldReset(cursor: Long, newest: Long): Boolean {
        if (cursor <= 0L) return false
        if (newest < 0L) return false
        // Reset hanya untuk keruntuhan besar (issue #1159):
        // basis yang diganti/restore mengecilkan MAX jauh
        // (id mulai lagi dari kecil), sedangkan menghapus
        // baris teratas — perilaku biasa yang justru
        // dipantau — hanya menurunkan MAX sedikit. Aturan
        // lama mereset untuk newest berapa pun di bawah
        // kursor, jadi satu SMS teratas yang dihapus saja
        // memicu kirim ulang seluruh riwayat. Ambang:
        // newest <= (cursor-1)/2, setara newest*2 < cursor
        // tanpa luapan. Baris baru sesudah restore tetap
        // tertangkap lengan DATE predikat keyset, jadi
        // restore dangkal yang tak mereset tidak membutakan.
        return newest <= (cursor - 1L) / 2L
    }
}
