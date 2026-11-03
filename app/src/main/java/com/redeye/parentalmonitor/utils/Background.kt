package com.redeye.parentalmonitor.utils

object Background {
    private val critical: java.util.concurrent.ExecutorService = java.util.concurrent.ThreadPoolExecutor(
        0, 1, 60L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue(),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "RedEyeCrit").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()
    )

    // Pool luapan receiver khusus (issue #1089): satu benang
    // tersendiri agar pekerjaan receiver berat yang meluap
    // tidak mengantre di depan tugas kritis (laporan
    // kebangkitan Liveness, dll.) di pool critical — badai
    // siaran dulu membuat jalur kritis ikut tertahan di
    // belakang handleBoot luapan. Tetap satu benang agar
    // luapan terserial seperti rancangan #1069.
    private val receiverOverflow: java.util.concurrent.ExecutorService = java.util.concurrent.ThreadPoolExecutor(
        0, 1, 60L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue(),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "RedEyeRecvOvf").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()
    )

    // Maks 3 benang (issue #1016, diperbaiki #1032): dulu
    // SATU benang bersama untuk semua receiver — pekerjaan
    // berat BootReceiver (I/O keystore + retry) membuat
    // receiver lain (alarm liveness, ganti paket, jam
    // berubah) mengantre di belakangnya dan
    // pendingResult.finish() mereka ikut tertunda melewati
    // jendela goAsync. PERINGATAN #1032: corePoolSize TIDAK
    // BOLEH 0 dengan antrean tak terbatas — ThreadPoolExecutor
    // hanya membuat pekerja di atas core bila antrean PENUH,
    // jadi bentuk (0, 3, LinkedBlockingQueue()) tetap efektif
    // SATU benang dan patch #1016 dulu mati. Bentuk yang benar:
    // core = maks = 3 + antrean TERBATAS. Handler receiver
    // menjaga state bersamanya dengan kunci masing-masing;
    // BootReceiver men-serial handleBoot-nya sendiri lewat
    // handleLock karena rantai dedup cap boot_meta-nya
    // cek-lalu-tulis (issue #1032).
    private val receivers: java.util.concurrent.ExecutorService = java.util.concurrent.ThreadPoolExecutor(
        3, 3, 60L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue(64),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "RedEyeRecv").apply { isDaemon = true } },
        // Penolakan BUKAN CallerRuns (issue #1069): badai
        // siaran yang memenuhi 3 pekerja + antrean 64 membuat
        // CallerRuns menjalankan handleBoot/handle paket DI
        // BENANG PEMANGGIL (benang utama broadcast) — risiko
        // ANR dan jatah broadcast terlewati. Tugas luapan
        // diserahkan ke pool luapan khusus satu benang
        // (issue #1089): tetap jalan (tidak hilang),
        // terserial, tidak pernah di benang pemanggil, dan
        // tidak lagi mencuri jalur pool kritis.
        java.util.concurrent.RejectedExecutionHandler { r, _ ->
            try { receiverOverflow.execute(r) } catch (_: Exception) { }
        }
    )

    fun runCritical(block: () -> Unit) {
        critical.execute {
            try {
                block()
            } catch (e: Exception) {
                android.util.Log.e("Background", "runCritical block failed (${e.javaClass.simpleName}): ${com.redeye.parentalmonitor.utils.Redact.token(e.message)}", e)
            }
        }
    }

    fun runReceiver(pendingResult: android.content.BroadcastReceiver.PendingResult?, block: () -> Unit) {
        try {
            receivers.execute {
                try {
                    block()
                } catch (_: Exception) {
                } finally {
                    try {
                        pendingResult?.finish()
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
            try {
                pendingResult?.finish()
            } catch (_: Exception) {
            }
        }
    }
}
