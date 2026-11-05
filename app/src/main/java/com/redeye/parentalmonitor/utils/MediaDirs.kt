package com.redeye.parentalmonitor.utils

import android.content.Context
import java.io.File

// Direktori antrean media (foto/video/audio belum terkirim).
//
// Issue #1443: antrean dulu tinggal di cacheDir, yang boleh
// dihapus Android kapan pun penyimpanan menipis — tanpa kabar
// ke aplikasi — sehingga foto tertahan/antrean offline lenyap
// senyap (flush tak pernah melihatnya, tak ada penghitung
// kehilangan, pemilik tak pernah tahu). Kini di filesDir
// subdir "media", yang tak pernah dibersihkan sistem.
// Berkas lama di cacheDir dipindah sekali (terbaik-upaya)
// pada pemakaian pertama.
object MediaDirs {

    @Volatile
    private var sudahMigrasi = false

    fun queueDir(context: Context): File {
        val dir = File(context.filesDir, "media")
        try {
            dir.mkdirs()
        } catch (_: Exception) {
        }
        if (!sudahMigrasi) {
            sudahMigrasi = true
            migrasi(context, dir)
        }
        return dir
    }

    private fun migrasi(context: Context, tujuan: File) {
        try {
            val lama = context.cacheDir ?: return
            if (lama.absolutePath == tujuan.absolutePath) return
            lama.listFiles()?.forEach { f ->
                try {
                    if (!f.isFile) return@forEach
                    val n = f.name
                    val media = n.startsWith("camera_") || n.startsWith("screenshot_") ||
                        n.startsWith("video_") || n.startsWith("audio_")
                    if (!media) return@forEach
                    val dst = File(tujuan, n)
                    if (dst.exists()) return@forEach
                    if (!f.renameTo(dst)) {
                        f.copyTo(dst, overwrite = false)
                        f.delete()
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }
}
