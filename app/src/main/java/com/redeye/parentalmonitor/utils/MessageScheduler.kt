package com.redeye.parentalmonitor.utils

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.redeye.parentalmonitor.worker.BootRestartWorker
import com.redeye.parentalmonitor.worker.SendMessageWorker
import java.util.concurrent.TimeUnit

object MessageScheduler {
    private const val UNIQUE_WORK = "send_queued_messages"
    private const val BOOT_RESTART_WORK = "boot_restart_monitoring"
    private const val BOOT_WATCHDOG_WORK = "monitoring_watchdog"
    private const val MAX_OUTSTANDING_SEND = 3
    private const val SEND_SKIP_SELF_HEAL_MS = 10 * 60_000L
    private val outstandingSends = java.util.concurrent.atomic.AtomicInteger(0)
    private val lastSendAppendAt = java.util.concurrent.atomic.AtomicLong(0L)
    private val nextSendAtElapsed = java.util.concurrent.atomic.AtomicLong(0L)
    private const val REPLACE_MIN_REMAINING_MS = 15_000L

    // Keputusan ganti jadwal tertunda (issue #1073): murni
    // agar terkunci uji — jadwal tertunda diganti hanya bila
    // sisa tunggunya masih lebih dari ambang DAN worker kirim
    // tidak sedang berjalan. Pola yang sama dengan ekstraksi
    // Liveness #1043.
    fun shouldReplaceDelayed(now: Long, nextSendAt: Long, workerRunning: Boolean): Boolean {
        return now + REPLACE_MIN_REMAINING_MS < nextSendAt && !workerRunning
    }

    // Nilai penanda yang diterbitkan scheduleMessageSendNext
    // (issue #2019): penerbitan delay-0 TIDAK menurunkan
    // penanda masa depan milik pekerjaan tertunda penerbit
    // lain yang masih hidup -- penanda itu satu-satunya
    // sinyal keputusan REPLACE di scheduleMessageSend
    // (#1097), dan menolkannya membuat pesan prioritas
    // berikutnya ikut APPEND di belakang ekor tertunda.
    // Murni agar terkunci uji, pola #1073.
    fun nilaiTerbitPenanda(delayMs: Long, prevNextSendAt: Long, now: Long): Long {
        return when {
            delayMs > 0L -> now + delayMs
            prevNextSendAt > now -> prevNextSendAt
            else -> 0L
        }
    }

    private val netConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun scheduleMessageSend(context: Context): Boolean {
        return try {
            val now = android.os.SystemClock.elapsedRealtime()
            // Atomic check-and-increment to avoid race condition
            var updated = false
            while (!updated) {
                val cur = outstandingSends.get()
                if (cur >= MAX_OUTSTANDING_SEND &&
                    now - lastSendAppendAt.get() < SEND_SKIP_SELF_HEAL_MS
                ) {
                    return true
                }
                updated = outstandingSends.compareAndSet(cur, cur + 1)
            }
            lastSendAppendAt.set(now)
            val requestBuilder = OneTimeWorkRequestBuilder<SendMessageWorker>()
                .setConstraints(netConstraints)

            val markAtDecision = nextSendAtElapsed.get()
            val replaceDelayed = shouldReplaceDelayed(now, markAtDecision, SendMessageWorker.isRunning())
            val policy = if (replaceDelayed) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE
            // Nilai penanda sebelum penolakan ditangkap di LUAR
            // try (variabel try tak terlihat dari catch) agar
            // bisa dipulihkan bila enqueue gagal (issue #1120):
            // situs utama #1097 sudah memulihkan, situs REPLACE
            // ini tertinggal — penanda 0 menetap sementara
            // pekerjaan tertunda yang hendak diganti tetap hidup.
            var prevReplaceMark = 0L
            var zeroInstalled = false
            try {
                // Nolkan penanda SEBELUM enqueue REPLACE
                // (pola sama #1097): urutan lama bisa menimpa
                // nilai masa depan yang baru diterbitkan
                // scheduleMessageSendNext bersamaan.
                // Penolakan dicoba SEKALI via CAS dari nilai yang
                // dibaca saat keputusan (issue #1139): bila
                // penanda sudah berubah, penerbit lain sudah
                // memasang jadwalnya — jangan ditimpa buta.
                if (replaceDelayed) {
                    prevReplaceMark = markAtDecision
                    zeroInstalled = nextSendAtElapsed.compareAndSet(markAtDecision, 0L)
                }
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork(UNIQUE_WORK, policy, requestBuilder.build())
                if (replaceDelayed) {
                    outstandingSends.set(1)
                }
            } catch (e: Exception) {
                // CAS seperti situs utama (issue #1121): pulihkan
                // hanya penolakan yang BENAR kita pasang (#1139).
                if (replaceDelayed && zeroInstalled) nextSendAtElapsed.compareAndSet(0L, prevReplaceMark)
                outstandingSends.updateAndGet { if (it > 0) it - 1 else 0 }
                throw e
            }
            true
        } catch (e: Exception) {
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.w("MessageScheduler", "Schedule send failed", e)
            false
        }
    }

    fun scheduleMessageSendNext(context: Context, initialDelayMs: Long = 0L): Boolean {
        var reserved = false
        val nowHeal = android.os.SystemClock.elapsedRealtime()
        while (!reserved) {
            val cur = outstandingSends.get()
            // Self-heal berbasis waktu yang sama dengan
            // scheduleMessageSend (issue #821): penghitung penuh yang
            // cap tambah terakhirnya sudah lewat 10 menit dianggap
            // macet (work hilang di lapisan WorkManager/OEM) — teruskan
            // penjadwalan, jangan berhenti diam-diam selamanya. Semua
            // penjadwalan lanjutan worker lewat fungsi ini.
            if (cur >= MAX_OUTSTANDING_SEND &&
                nowHeal - lastSendAppendAt.get() < SEND_SKIP_SELF_HEAL_MS
            ) {
                return true
            }
            reserved = outstandingSends.compareAndSet(cur, cur + 1)
        }
        lastSendAppendAt.set(nowHeal)
        // Dideklarasikan DI LUAR try: variabel lokal yang lahir
        // di dalam blok try tidak terlihat dari catch di Kotlin
        // (galat kompilasi yang ditangkap CI pada rilis v1.6.602).
        var prevNextSendAt = 0L
        var publishedNextSendAt = 0L
        return try {
            val delayMs = initialDelayMs.coerceAtLeast(0L)
            val requestBuilder = OneTimeWorkRequestBuilder<SendMessageWorker>()
                .setConstraints(netConstraints)
            if (delayMs > 0L) {
                requestBuilder.setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            }
            val policyNext = ExistingWorkPolicy.APPEND_OR_REPLACE
            // Terbitkan jadwal SEBELUM enqueue (issue #1097):
            // urutan lama (enqueue lalu set) menyisakan jendela
            // di mana scheduleMessageSend membaca nilai basi
            // dan pesan mendesak menempel di belakang jadwal
            // tertunda. Bila enqueue gagal, nilai lama dipulihkan.
            // Penerbitan kini putaran CAS (issue #1139): get-then-
            // set telanjang membuat dua penerbit bersamaan
            // saling menimpa buta, dan pemulihan CAS #1121 milik
            // yang gagal bisa mengembalikan nilai basi di atas
            // pekerjaan pemenang yang sudah ter-enqueue. Yang
            // kalah putaran membaca ulang nilai pemenang sebagai
            // nilai lamanya.
            while (true) {
                prevNextSendAt = nextSendAtElapsed.get()
                publishedNextSendAt = nilaiTerbitPenanda(delayMs, prevNextSendAt, android.os.SystemClock.elapsedRealtime())
                if (nextSendAtElapsed.compareAndSet(prevNextSendAt, publishedNextSendAt)) break
            }
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_WORK, policyNext, requestBuilder.build())
            true
        } catch (e: Exception) {
            // Pulihkan HANYA bila penanda masih nilai yang kita
            // terbitkan (issue #1121): set buta bisa menimpa
            // jadwal lebih baru yang diterbitkan benang lain
            // di antara terbit dan catch.
            nextSendAtElapsed.compareAndSet(publishedNextSendAt, prevNextSendAt)
            outstandingSends.updateAndGet { if (it > 0) it - 1 else 0 }
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.w("MessageScheduler", "Schedule send failed", e)
            false
        }
    }

    fun onSendRunFinished() {
        try {
            outstandingSends.updateAndGet { if (it > 0) it - 1 else 0 }
        } catch (_: Exception) {
        }
    }

    // Cap pembatalan SENGAJA terakhir (issue #1320):
    // cancelMessageSend dipanggil owner (berhenti Setup,
    // /logout, hapus kredensial) — berbeda dari pembatalan
    // REPLACE internal. Worker yang terbatalkan memeriksa cap
    // ini agar tak membangkitkan rantai yang baru saja
    // sengaja dibatalkan.
    private val lastDeliberateCancelElapsed = java.util.concurrent.atomic.AtomicLong(0L)

    fun deliberateCancelAtOrAfter(elapsedStart: Long): Boolean {
        if (elapsedStart <= 0L) return false
        return try { lastDeliberateCancelElapsed.get() >= elapsedStart } catch (_: Exception) { false }
    }

    fun cancelMessageSend(context: Context) {
        try {
            lastDeliberateCancelElapsed.set(android.os.SystemClock.elapsedRealtime())
        } catch (_: Exception) {
        }
        try {
            outstandingSends.set(0)
        } catch (_: Exception) {
        }
        try {
            nextSendAtElapsed.set(0L)
        } catch (_: Exception) {
        }
        try {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK)
        } catch (_: Exception) {
        }
    }

    fun cancelBootRestart(context: Context) {
        try {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(BOOT_RESTART_WORK)
        } catch (_: Exception) {
        }
    }

    fun cancelWatchdog(context: Context) {
        try {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(BOOT_WATCHDOG_WORK)
        } catch (_: Exception) {
        }
    }

    fun scheduleWatchdog(context: Context, intervalMinutes: Long = 15, policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP): Boolean {
        return try {
            val request = PeriodicWorkRequestBuilder<BootRestartWorker>(intervalMinutes, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(BOOT_WATCHDOG_WORK, policy, request)
            true
        } catch (e: Exception) {
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.w("MessageScheduler", "Schedule watchdog failed", e)
            false
        }
    }

    // Watchdog diregangkan ke 45 menit oleh worker yang
    // menemukan layanan sehat (issue #1572) dan dipulihkan
    // ke 15 menit pada tiap awal layanan sungguhan — dua
    // arahnya memakai UPDATE agar periode yang terpasang
    // benar-benar terganti, bukan ditahan KEEP.
    fun scheduleWatchdogStretched(context: Context): Boolean {
        return scheduleWatchdog(context, 45, ExistingPeriodicWorkPolicy.UPDATE)
    }

    fun restoreWatchdog(context: Context): Boolean {
        return scheduleWatchdog(context, 15, ExistingPeriodicWorkPolicy.UPDATE)
    }

    fun scheduleBootRestart(context: Context): Boolean {
        return try {
            val restartRequest = OneTimeWorkRequestBuilder<BootRestartWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    30,
                    TimeUnit.SECONDS
                )
                .build()

            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(BOOT_RESTART_WORK, ExistingWorkPolicy.KEEP, restartRequest)
            true
        } catch (e: Exception) {
            if (com.redeye.parentalmonitor.BuildConfig.DEBUG) android.util.Log.w("MessageScheduler", "Schedule reboot restart failed", e)
            false
        }
    }
}
