package com.redeye.parentalmonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.service.MonitoringService
import com.redeye.parentalmonitor.utils.ServiceStart

class InstantEventReceiver : BroadcastReceiver() {

    companion object {
        // Antrean TERBATAS (issue #925): executor lama memakai
        // antrean tak terbatas sehingga rentetan SMS/panggilan
        // bisa menumpuk pekerjaan tanpa batas. Bila penuh,
        // execute() melempar dan penangkap di onReceive
        // menyelesaikan goAsync sebagaimana mestinya.
        private val executor = java.util.concurrent.ThreadPoolExecutor(
            1,
            1,
            0L,
            java.util.concurrent.TimeUnit.MILLISECONDS,
            java.util.concurrent.ArrayBlockingQueue(32),
            { runnable ->
                Thread(runnable, "redeye-instant").apply { isDaemon = true }
            }
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        val target = when (intent.action) {
            "android.provider.Telephony.SMS_RECEIVED" -> MonitoringService.ACTION_SMS_RECEIVED
            TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                if (state != TelephonyManager.EXTRA_STATE_IDLE) return
                MonitoringService.ACTION_CALL_STATE
            }
            else -> return
        }
        val appContext = context.applicationContext
        val pending = try { goAsync() } catch (_: Exception) { null }
        try {
            executor.execute {
                try {
                    try { PreferencesManager.refreshInstance(appContext) } catch (_: Exception) { }
                    val active = try {
                        val prefs = PreferencesManager.getInstance(appContext)
                        prefs.isConfigured() && prefs.isMonitoringEnabled && !prefs.userDisabledMonitoring && prefs.userConsentedMonitoring && !prefs.monitoringPaused
                    } catch (_: Exception) {
                        false
                    }
                    if (active) {
                        try {
                            val service = Intent(appContext, MonitoringService::class.java).apply { action = target }
                            ServiceStart.startForeground(appContext, service)
                        } catch (_: Exception) {
                            try {
                                com.redeye.parentalmonitor.utils.MessageScheduler.scheduleBootRestart(appContext)
                            } catch (_: Exception) {
                            }
                        }
                    }
                    // Keadaan tak aktif SENGAJA (belum diatur,
                    // dimatikan owner, belum setuju, dijeda) TIDAK
                    // lagi menjadwalkan BootRestart (issue #925):
                    // dulu semua keadaan else menjadwalkannya —
                    // bangun berkala yang sia-sia untuk layanan
                    // yang memang diminta berhenti. Penjadwalan
                    // pemulihan hanya tinggal di cabang gagal-mulai
                    // di atas, tempat ia memang berguna.
                } finally {
                    try { pending?.finish() } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
            // Antrean executor penuh = peristiwa terbuang (issue
            // #969): cabang ini dulu hanya menyelesaikan
            // goAsync, tanpa jaring apa pun — berbeda dari
            // cabang gagal-mulai layanan di atas. Jadwalkan
            // BootRestart sebagai jaring pengaman (unique work
            // KEEP: tak menumpuk walau rentetan peristiwa
            // tertolak) agar layanan yang mati tetap dibangunkan
            // untuk memeriksa data terbaru; worker-nya sendiri
            // yang menilai apakah layanan memang harus jalan.
            try {
                com.redeye.parentalmonitor.utils.MessageScheduler.scheduleBootRestart(appContext)
            } catch (_: Exception) {
            }
            try { pending?.finish() } catch (_: Exception) {
            }
        }
    }
}
