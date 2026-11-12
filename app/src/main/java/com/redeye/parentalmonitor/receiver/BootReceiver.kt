package com.redeye.parentalmonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.service.MonitoringService
import com.redeye.parentalmonitor.utils.MessageScheduler
import com.redeye.parentalmonitor.utils.RingVolume
import com.redeye.parentalmonitor.utils.ServiceStart

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        private const val ACTION_HTC_QUICKBOOT = "com.htc.intent.action.QUICKBOOT_POWERON"

        // USER_UNLOCKED/USER_PRESENT/PACKAGE_RESTARTED
        // dihapus (issue #1204): entri manifest-nya sudah
        // dibersihkan di #1188 karena tak pernah terkirim di
        // API 26+, dan cabang handler-nya tertinggal sebagai
        // kode mati yang tampak hidup.
        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_QUICKBOOT_POWERON,
            ACTION_HTC_QUICKBOOT
        )

        private val handleLock = Any()

        // Penghitung boot persisten (issue #1034): bertambah
        // sekali per boot di handleBoot untuk aksi keluarga
        // BOOT_COMPLETED, ditulis commit() agar selamat dari
        // kematian proses. Bukti reboot yang kebal pergeseran
        // jam untuk uji anti-mati (pasangan wall+elapsed punya
        // residu reboot-cepat yang tak terbedakan dari koreksi
        // jam — lihat BootClock).
        fun bootCount(context: android.content.Context): Long {
            return try {
                synchronized(handleLock) {
                    val meta = context.getSharedPreferences(PreferencesManager.BOOT_META_PREFS_NAME, android.content.Context.MODE_PRIVATE)
                    var count = meta.getLong("boot_count", 0L)
                    // Swasembuh reboot tak tercatat (issue #1070):
                    // bila BOOT_COMPLETED tak pernah sampai
                    // (aplikasi berstatus berhenti — force-stop
                    // atau belum pernah dibuka sesudah update),
                    // penghitung tak naik dan reboot tak terlihat
                    // konsumen. Saksi utama sejak #1084 adalah
                    // penghitung boot SISTEM (selisihnya = jumlah
                    // boot tak tercatat, tanpa jendela waktu);
                    // cap elapsed hitungan terakhir adalah saksi
                    // cadangan: elapsed SEKARANG yang lebih kecil
                    // darinya hanya mungkin sesudah reboot —
                    // naikkan sekali dan perbarui cap.
                    // Penulisan heal memakai apply(), bukan
                    // commit() (issue #1085): bootCount() dibaca
                    // dari benang utama Setup dan dari dalam
                    // gate A11y — tulis disk sinkron di sana
                    // berisiko jeda/ANR. Heal bersifat idempoten
                    // (bukti reboot tetap ada sampai cap
                    // terbarui), jadi kehilangan flush oleh
                    // kematian proses hanya berarti heal ulang
                    // pada bacaan berikutnya.
                    val stampElapsed = meta.getLong("boot_count_elapsed", 0L)
                    val nowElapsed = android.os.SystemClock.elapsedRealtime()
                    // Saksi sistem lebih dulu (issue #1084):
                    // penghitung boot platform yang lebih besar
                    // dari tersimpan membuktikan reboot tanpa
                    // jendela waktu sama sekali — heal elapsed
                    // di bawah buta bila pembacaan pertama
                    // sesudah reboot terjadi sesudah uptime
                    // melewati cap lama. Selisihnya adalah
                    // jumlah boot yang tak tercatat.
                    val sysBoot = try {
                        android.provider.Settings.Global.getInt(
                            context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1
                        ).toLong()
                    } catch (_: Exception) {
                        -1L
                    }
                    val storedSys = meta.getLong("boot_count_sys", -1L)
                    if (sysBoot >= 0L && storedSys >= 0L && sysBoot > storedSys) {
                        count += (sysBoot - storedSys)
                        try {
                            meta.edit().putLong("boot_count", count)
                                .putLong("boot_count_wall", System.currentTimeMillis())
                                .putLong("boot_count_elapsed", nowElapsed)
                                .putLong("boot_count_sys", sysBoot)
                                .putLong("boot_count_sys_est", 0L).apply()
                        } catch (_: Exception) {
                        }
                    } else if (stampElapsed > 0L && nowElapsed < stampElapsed) {
                        count += 1L
                        try {
                            val ed = meta.edit().putLong("boot_count", count)
                                .putLong("boot_count_wall", System.currentTimeMillis())
                                .putLong("boot_count_elapsed", nowElapsed)
                            if (sysBoot >= 0L && sysBoot >= storedSys) ed.putLong("boot_count_sys", sysBoot).putLong("boot_count_sys_est", 0L)
                            else if (sysBoot < 0L && storedSys >= 0L) ed.putLong("boot_count_sys", storedSys + 1L).putLong("boot_count_sys_est", 1L)
                            // Pasangan +1 saat saksi tak terbaca
                            // (issue #1122): boot yang baru dihitung
                            // ini juga menaikkan BOOT_COUNT tepat
                            // satu — tanpa memajukan saksi, heal
                            // #1084 menambahkan selisih yang sama
                            // lagi pada bacaan berikutnya (dobel).
                            ed.apply()
                        } catch (_: Exception) {
                        }
                    }
                    count
                }
            } catch (_: Exception) {
                0L
            }
        }

        // Cap dinding peristiwa hitung boot terakhir (issue
        // #1071): membedakan baseline 0 "belum pernah ada boot
        // terhitung" dari baseline 0 hasil gagal baca.
        fun bootCountWall(context: android.content.Context): Long {
            return try {
                context.getSharedPreferences(PreferencesManager.BOOT_META_PREFS_NAME, android.content.Context.MODE_PRIVATE)
                    .getLong("boot_count_wall", 0L)
            } catch (_: Exception) {
                0L
            }
        }

        private fun noteBootCounted(context: android.content.Context) {
            try {
                val meta = context.getSharedPreferences(PreferencesManager.BOOT_META_PREFS_NAME, android.content.Context.MODE_PRIVATE)
                // Dedup per boot (issue #1067): perangkat yang
                // mengirim DUA aksi keluarga boot untuk satu
                // boot fisik (BOOT_COMPLETED + QUICKBOOT)
                // menaikkan penghitung dua kali. Dalam satu
                // boot, selang dinding dan selang elapsed antara
                // dua siaran itu nyaris sama dan pendek; pada
                // boot sungguhan elapsed ter-reset sehingga
                // kedua selang berpisah jauh (selang dinding
                // memuat waktu mati + sisa uptime boot lama).
                val lastWall = meta.getLong("boot_count_wall", 0L)
                val lastElapsed = meta.getLong("boot_count_elapsed", 0L)
                // Saksi independen platform (issue #1074):
                // penghitung boot sistem menelan reboot cepat
                // yang heuristik waktu samakan dengan duplikat.
                val sysBoot = try {
                    android.provider.Settings.Global.getInt(
                        context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1
                    ).toLong()
                } catch (_: Exception) {
                    -1L
                }
                val storedSys = meta.getLong("boot_count_sys", -1L)
                // Bendera asal-usul saksi (issue #1140): nilai
                // TAKSIRAN +1 dari #1122 bukan bacaan platform —
                // kesamaannya dengan bacaan berikutnya TIDAK
                // membuktikan boot yang sama.
                val sysEst = meta.getLong("boot_count_sys_est", 0L)
                if (lastWall > 0L && lastElapsed > 0L) {
                    val nowWall = System.currentTimeMillis()
                    val nowElapsed = android.os.SystemClock.elapsedRealtime()
                    val elapsedDiff = nowElapsed - lastElapsed
                    val wallDiff = nowWall - lastWall
                    if (com.redeye.parentalmonitor.utils.BootClock
                            .isDuplicateBroadcast(elapsedDiff, wallDiff, sysBoot, storedSys) ||
                        (sysBoot >= 0L && storedSys >= 0L && sysBoot == storedSys && sysEst == 0L)) {
                        // Kesamaan penghitung sistem berarti
                        // boot yang sama, berapa pun telatnya
                        // siaran kedua tiba (issue #1083):
                        // jendela waktu dedup tak lagi membatasi.
                        // Hanya untuk saksi BACAAN NYATA — saksi
                        // taksiran #1122 dikecualikan (#1140).
                        // Duplikat: bila baseline sistem belum
                        // pernah tersimpan (instalasi lama),
                        // simpan sekarang agar siaran berikutnya
                        // punya saksi — tanpa menaikkan hitungan.
                        if (sysBoot >= 0L && storedSys < 0L) {
                            try {
                                meta.edit().putLong("boot_count_sys", sysBoot).putLong("boot_count_sys_est", 0L).commit()
                            } catch (_: Exception) {
                            }
                        }
                        return
                    }
                    val ed = meta.edit().putLong("boot_count", meta.getLong("boot_count", 0L) + 1L)
                        .putLong("boot_count_wall", nowWall)
                        .putLong("boot_count_elapsed", nowElapsed)
                    if (sysBoot >= 0L) ed.putLong("boot_count_sys", sysBoot).putLong("boot_count_sys_est", 0L)
                    else if (storedSys >= 0L) ed.putLong("boot_count_sys", storedSys + 1L).putLong("boot_count_sys_est", 1L)
                    // Pasangan +1 seperti jalur heal (issue #1122).
                    ed.commit()
                    return
                }
                val edFirst = meta.edit().putLong("boot_count", meta.getLong("boot_count", 0L) + 1L)
                    .putLong("boot_count_wall", System.currentTimeMillis())
                    .putLong("boot_count_elapsed", android.os.SystemClock.elapsedRealtime())
                if (sysBoot >= 0L) edFirst.putLong("boot_count_sys", sysBoot).putLong("boot_count_sys_est", 0L)
                edFirst.commit()
            } catch (_: Exception) {
            }
        }
        @Volatile
        private var lastHandleAt = 0L

        // Cap elapsed hanya sebidang di dalam boot yang sama
        // (issue #1665): elapsedRealtime ter-reset tiap boot,
        // jadi cap dari boot lama yang dibandingkan mentah
        // dengan elapsed boot baru menelan keputusan gerbang
        // (notifikasi bisa tak terkirim hanya karena boot
        // sebelumnya mencap nilai elapsed yang mirip). Cap
        // dianggap hanya bila boot_count pasangannya sama
        // dengan boot berjalan; identitas tak terbaca (0)
        // mempertahankan perilaku lama.
        private fun capSebidangBoot(
            meta: android.content.SharedPreferences,
            kunciElapsed: String,
            kunciBoot: String,
            bootKini: Long
        ): Long {
            if (bootKini <= 0L) return meta.getLong(kunciElapsed, 0L)
            return if (meta.getLong(kunciBoot, -1L) == bootKini) meta.getLong(kunciElapsed, 0L) else 0L
        }

        private fun markHandled(context: android.content.Context, at: Long, full: Boolean = true) {
            synchronized(handleLock) { lastHandleAt = at }
            try {
                val bootPasangan = bootCount(context)
                val editor = context.getSharedPreferences(PreferencesManager.BOOT_META_PREFS_NAME, android.content.Context.MODE_PRIVATE).edit()
                    .putLong("last_handle_elapsed", at)
                    .putLong("last_handle_boot", bootPasangan)
                if (full) editor.putLong("last_full_boot_elapsed", at).putLong("last_full_boot_boot", bootPasangan)
                editor.apply()
            } catch (_: Exception) {
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val updatedPart = intent.data?.schemeSpecificPart
            if (updatedPart != null && updatedPart != context.packageName) return
        }
        val appContext = context.applicationContext
        // Alarm Liveness TIDAK dipasang di sini lagi
        // (issue #2025): pemasangan pindah ke
        // handleBootLocked sesudah gerbang dedup — siaran
        // boot duplikat yang berhenti di dedup dulu
        // memasang ulang alarm yang baru dibatalkan
        // cabang mati pada handle pertama.
        // goAsync gagal TIDAK membuang peristiwa boot
        // (issue #1635): pola receiver saudara
        // (Liveness/TimeChange) adalah pendingResult=null
        // dan handler tetap jalan lewat runReceiver —
        // dulu return di sini membatalkan seluruh
        // handleBoot (layanan tak dicoba hidup, hitungan
        // boot hilang, volume dering tak pulih).
        val pendingResult = try {
            goAsync()
        } catch (_: Exception) {
            null
        }
        val bootAction = intent.action
        com.redeye.parentalmonitor.utils.Background.runReceiver(pendingResult) {
            handleBoot(appContext, bootAction)
        }
    }

    // Seluruh handleBoot di-serial per proses (issue #1032):
    // pool receiver kini sungguh sejajar, sedangkan rantai
    // dedup di bawah (gerbang lastHandleAt + cap boot_meta
    // shot_notice_elapsed/reboot_notice_elapsed) adalah
    // cek-lalu-tulis — dua handleBoot sejajar (BOOT_COMPLETED
    // + QUICKBOOT berbarengan) bisa sama-sama lolos
    // gerbang 1 jam dan mengirim pesan restart ganda.
    // markHandled di dalamnya men-synchronized handleLock
    // yang sama; monitor Java reentrant untuk benang yang
    // sama, jadi tak ada deadlock.
    private fun handleBoot(context: Context, intentAction: String? = null) {
        synchronized(handleLock) { handleBootLocked(context, intentAction) }
    }

    private fun handleBootLocked(context: Context, intentAction: String? = null) {
        // Paling atas, sebelum return dedup mana pun (issue
        // #1034): penghitung hanya untuk aksi boot sungguhan.
        if (intentAction == Intent.ACTION_BOOT_COMPLETED ||
            intentAction == ACTION_QUICKBOOT_POWERON ||
            intentAction == ACTION_HTC_QUICKBOOT) {
            noteBootCounted(context)
        }
        val nowBoot = android.os.SystemClock.elapsedRealtime()
        val bootKini = bootCount(context)
        val updated = intentAction == Intent.ACTION_MY_PACKAGE_REPLACED
        val freshBoot = nowBoot <= 120_000L
        synchronized(handleLock) { if (!updated && !freshBoot && lastHandleAt != 0L && nowBoot - lastHandleAt < 60_000L) return }
        try {
            val meta = context.getSharedPreferences(PreferencesManager.BOOT_META_PREFS_NAME, android.content.Context.MODE_PRIVATE)
            val lastElapsed = capSebidangBoot(meta, "last_handle_elapsed", "last_handle_boot", bootKini)
            if (!updated && !freshBoot && lastElapsed != 0L && nowBoot >= lastElapsed && nowBoot - lastElapsed < 60_000L) return
            // Additional guard: if we've handled a boot in the last 10 minutes AND service runs, skip (catches OEM duplicate >60s apart)
            val lastFullBoot = capSebidangBoot(meta, "last_full_boot_elapsed", "last_full_boot_boot", bootKini)
            if (!updated && MonitoringService.isRunning && lastFullBoot != 0L && nowBoot >= lastFullBoot && nowBoot - lastFullBoot < 10 * 60_000L) return
        } catch (_: Exception) {
        }
        // Alarm Liveness dipasang di sini, sesudah gerbang
        // dedup (issue #2025; sebelumnya tanpa syarat di
        // onReceive): handle yang lolos dedup memang akan
        // menilai keadaan mati/hidup di bawah — cabang mati
        // membatalkan alarm ini lagi di pemanggilan yang
        // sama, jadi hasil bersihnya tetap tanpa alarm.
        try {
            com.redeye.parentalmonitor.utils.Liveness.schedule(context)
        } catch (_: Exception) {
        }
        if (!updated && MonitoringService.isRunning) {
            markHandled(context, nowBoot)
            return
        }
        val preferencesManager = try {
            PreferencesManager.refreshInstance(context)
            PreferencesManager.getInstance(context)
        } catch (_: Exception) {
            markHandled(context, 0L, full = false)
            MessageScheduler.scheduleBootRestart(context)
            return
        }
        try {
            RingVolume.restoreStuck(context, preferencesManager)
        } catch (_: Exception) {
        }
        try {
            com.redeye.parentalmonitor.utils.NetworkUtils.sweepAuthBlock(preferencesManager)
        } catch (_: Exception) {
        }
        if (!preferencesManager.isStorageEncrypted) {
            markHandled(context, 0L, full = false)
            MessageScheduler.scheduleBootRestart(context)
            return
        }
        if (preferencesManager.userDisabledMonitoring || !preferencesManager.userConsentedMonitoring || !preferencesManager.isMonitoringEnabled) {
            markHandled(context, nowBoot)
            try {
                MessageScheduler.cancelWatchdog(context)
            } catch (_: Exception) {
            }
            // Alarm Liveness yang terpasang di atas
            // (sesudah gerbang dedup) ikut dibatalkan
            // (issue #1274): pola
            // #1245 di BootRestartWorker — perangkat yang
            // pemantauannya sengaja dimatikan tak boleh
            // dibangunkan alarm yang tak seharusnya ada.
            try {
                com.redeye.parentalmonitor.utils.Liveness.cancel(context)
            } catch (_: Exception) {
            }
            return
        }
        if (!preferencesManager.isConfigured()) {
            markHandled(context, 0L, full = false)
            MessageScheduler.scheduleBootRestart(context)
            return
        }
        // MY_PACKAGE_REPLACED ikut (issue #1414): update
        // aplikasi juga membunuh proses dan menghanguskan
        // izin MediaProjection, tetapi pengingatnya dulu
        // hanya terpasang untuk aksi boot.
        if (intentAction == Intent.ACTION_BOOT_COMPLETED || intentAction == ACTION_QUICKBOOT_POWERON || intentAction == ACTION_HTC_QUICKBOOT || intentAction == Intent.ACTION_MY_PACKAGE_REPLACED) {
            try {
                val a11yOn = com.redeye.parentalmonitor.utils.AccessibilityProbe.isEnabled(
                    context,
                    com.redeye.parentalmonitor.service.ShotAccessibilityService::class.java
                )
                if (!a11yOn) {
                    val meta = context.getSharedPreferences(PreferencesManager.BOOT_META_PREFS_NAME, android.content.Context.MODE_PRIVATE)
                    val lastShotNotice = capSebidangBoot(meta, "shot_notice_elapsed", "shot_notice_boot", bootKini)
                    if (lastShotNotice == 0L || nowBoot < lastShotNotice || nowBoot - lastShotNotice >= 3_600_000L) {
                        meta.edit().putLong("shot_notice_elapsed", nowBoot).putLong("shot_notice_boot", bootKini).apply()
                        val teksShot = if (intentAction == Intent.ACTION_MY_PACKAGE_REPLACED) {
                            "\uD83D\uDD01 Aplikasi baru diperbarui. Izin tangkap layar dari sistem hangus (aturan Android). Kirim /screenshot lalu tekan tombol \"\uD83D\uDDA5\uFE0F Beri izin sekali\" \u2014 atau aktifkan jalur Aksesibilitas di Setup agar tak perlu izin lagi walau restart."
                        } else {
                            "\uD83D\uDD01 HP baru restart. Izin tangkap layar dari sistem hangus (aturan Android). Kirim /screenshot lalu tekan tombol \"\uD83D\uDDA5\uFE0F Beri izin sekali\" \u2014 atau aktifkan jalur Aksesibilitas di Setup agar tak perlu izin lagi walau restart."
                        }
                        com.redeye.parentalmonitor.data.MessageQueue.getInstance(context).addMessage(teksShot)
                        MessageScheduler.scheduleMessageSend(context)
                    }
                }
            } catch (_: Exception) {
            }
        }
        if (!MonitoringService.tryClaimServiceStart()) {
            // Penghidup lain baru saja mengklaim start
            // (issue #1683): jangan kirim start kedua.
            markHandled(context, nowBoot)
            return
        }
        if (!tryStartService(context)) {
            markHandled(context, 0L, full = false)
            MessageScheduler.scheduleBootRestart(context)
            try {
                val meta = context.getSharedPreferences(PreferencesManager.BOOT_META_PREFS_NAME, android.content.Context.MODE_PRIVATE)
                val lastNotice = capSebidangBoot(meta, "reboot_notice_elapsed", "reboot_notice_boot", bootKini)
                if (lastNotice == 0L || nowBoot < lastNotice || nowBoot - lastNotice >= 3_600_000L) {
                    meta.edit().putLong("reboot_notice_elapsed", nowBoot).putLong("reboot_notice_boot", bootKini).apply()
                    com.redeye.parentalmonitor.data.MessageQueue.getInstance(context).addMessage("🔄 Monitoring coba jalan lagi. Bila sepi, buka aplikasi kalkulator.")
                    MessageScheduler.scheduleMessageSend(context)
                }
            } catch (_: Exception) {
            }
        } else {
            markHandled(context, nowBoot)
        }
    }

    private fun tryStartService(context: Context): Boolean {
        val serviceIntent = Intent(context, MonitoringService::class.java).apply {
            action = MonitoringService.ACTION_START_MONITORING
        }
        return try {
            ServiceStart.startForeground(context, serviceIntent)
            true
        } catch (e: Exception) {
            android.util.Log.w("BootReceiver", "Service start blocked after reboot, will retry via worker", e)
            false
        }
    }
}
