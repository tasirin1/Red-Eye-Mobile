package com.redeye.parentalmonitor.utils

object Background {
    // Antrean kedua pool ini kini TERBATAS 64 (issue #1596,
    // menyelaraskan pola pool receivers di bawah): antrean
    // tak terbatas membuat CallerRunsPolicy tak pernah
    // terpicu dan satu tugas menggantung bisa menumpuk
    // tugas tanpa batas di memori. Pada batasnya tugas
    // kritis berjalan di benang pemanggil (tekanan balik,
    // bukan hilang) — sesuai makna CallerRuns.
    private val critical: java.util.concurrent.ExecutorService = java.util.concurrent.ThreadPoolExecutor(
        0, 1, 60L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue(64),
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
    // Penolakannya BUKAN CallerRuns (issue #1708): handler
    // penolakan pool receivers berjalan sinkron di benang
    // pemanggil (benang utama broadcast), jadi CallerRuns
    // di sini menjalankan pekerjaan receiver berat DI
    // BENANG UTAMA saat saturasi ganda — ANR yang justru
    // dicegah #1069. Jalan terakhirnya benang daemon lepas
    // satu kali pakai: tugas tetap jalan (pembungkusnya
    // tetap memanggil finish() pendingResult), tak pernah
    // di benang pemanggil.
    // Plafon benang jalan-terakhir luapan (issue #1726):
    // tanpa plafon, badai siaran saat antrean macet
    // membuat satu benang daemon baru per penolakan
    // tanpa batas. Di atas plafon tugas dijalankan di
    // tempat dengan penjaga (jalan terakhir #1725) —
    // jumlah benang tetap berpagar, tugas tetap jalan.
    private val benangLuapanAktif = java.util.concurrent.atomic.AtomicInteger(0)
    private const val MAX_BENANG_LUAPAN = 16

    private val receiverOverflow: java.util.concurrent.ExecutorService = java.util.concurrent.ThreadPoolExecutor(
        0, 1, 60L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue(64),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "RedEyeRecvOvf").apply { isDaemon = true } },
        java.util.concurrent.RejectedExecutionHandler { r, _ ->
            val terhitung = Runnable {
                try { r.run() } finally { benangLuapanAktif.decrementAndGet() }
            }
            if (benangLuapanAktif.incrementAndGet() > MAX_BENANG_LUAPAN) {
                benangLuapanAktif.decrementAndGet()
                try { r.run() } catch (_: Throwable) {
                }
            } else try {
                Thread(terhitung, "RedEyeRecvOvfX").apply { isDaemon = true }.start()
            } catch (_: Throwable) {
                benangLuapanAktif.decrementAndGet()
                // Kelas Error (mis. OutOfMemoryError saat
                // membuat benang) BUKAN Exception (issue
                // #1725): tangkapan Exception saja membuat
                // tugas hilang senyap — finish()
                // pendingResult tak pernah terpanggil —
                // dan Error-nya merambat ke benang
                // pemanggil broadcast. Jalan terakhir:
                // jalankan tugasnya di sini dengan
                // penjaga penuh; finish() tetap terpanggil
                // walau keadaannya sudah sekarat.
                try { r.run() } catch (_: Throwable) {
                }
            }
        }
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
            // Serah terima lewat benang daemon lepas
            // (issue #1745): execute() langsung dari
            // handler ini menjalankan handler penolakan
            // receiverOverflow DI BENANG INI — benang
            // utama broadcast, satu-satunya pemanggil
            // rantai ini — dan di atas plafon jalan-
            // terakhir (#1726) handler itu menjalankan
            // pekerjaan receiver berat di tempat, di
            // benang utama. Benang serah terima berumur
            // satu panggilan execute() membuat seluruh
            // rantai penolakan sesudahnya mendarat di
            // benang daemon, tak pernah di pemanggil.
            try {
                Thread({
                    try { receiverOverflow.execute(r) } catch (_: Exception) { }
                }, "RedEyeRecvHandoff").apply { isDaemon = true }.start()
            } catch (_: Throwable) {
                // Pembuatan benang gagal (kelas Error):
                // jalan lama adalah satu-satunya sisa.
                try { receiverOverflow.execute(r) } catch (_: Exception) { }
            }
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
