package com.redeye.parentalmonitor.utils

// Chat tujuan menghormati override perintah
// sensitif (issue #1548): /photo, /screenshot,
// /record* yang diketik dari chat privat tak
// boleh menerbitkan medianya ke grup. Tujuan
// eksplisit chatTujuan (issue #1720) didahulukan:
// ia ditangkap pemanggil SAAT perintah berjalan,
// karena jepret/rekam asinkron mengirim sesudah
// jendela override ditutup — membaca jendela di
// sini saja membuat muatan media selalu bocor ke
// chat terkonfigurasi. Di luar jendela perintah
// override kosong = konfigurasi.
// Diekstrak dari ketiga fungsi kirim media di
// MonitoringService agar urutan cadangannya hidup
// di satu tempat dan terkunci uji (issue #1846).
object ChatTujuanMedia {
    fun resolusi(chatTujuan: String, replyOverrideChatId: String, chatIdMentah: String): String =
        chatTujuan.ifEmpty { replyOverrideChatId }.ifEmpty { chatIdMentah }
}
