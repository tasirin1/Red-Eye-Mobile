package com.redeye.parentalmonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.redeye.parentalmonitor.utils.Background
import com.redeye.parentalmonitor.utils.Liveness

class TimeChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_TIME_CHANGED &&
            action != Intent.ACTION_TIMEZONE_CHANGED &&
            action != Intent.ACTION_DATE_CHANGED
        ) {
            return
        }
        val appContext = context.applicationContext
        val pendingResult = try {
            goAsync()
        } catch (_: Exception) {
            null
        }
        // Executor receiver khusus, BUKAN antrean critical
        // bersama (issue #968): pola lama menyelesaikan
        // broadcast di dalam antrean critical satu-thread —
        // finish() menunggu di belakang pekerjaan IO/restore
        // lain, umur broadcast bisa lewat dan proses dibunuh
        // sebelum jadwal liveness terpasang ulang. runReceiver
        // (pola LivenessReceiver) menyelesaikan pendingResult
        // di executornya sendiri termasuk jalur tertolak.
        Background.runReceiver(pendingResult) {
            try {
                Liveness.handleWakeUp(appContext, fromAlarm = false)
            } catch (e: Exception) {
                android.util.Log.e("TimeChangeReceiver", "Liveness wake-up failed", e)
            }
        }
    }
}
