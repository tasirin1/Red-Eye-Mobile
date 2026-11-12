package com.redeye.parentalmonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

// Pesan terakhir saat perangkat dimatikan (keputusan
// pemilik 2026-11-12, paket jejak terakhir): pencuri
// biasanya langsung mematikan HP curian, jadi momen
// ACTION_SHUTDOWN dipakai mengantrekan pesan prioritas
// berisi lokasi terakhir yang tersimpan. Pengiriman
// adalah upaya terbaik yang balapan dengan proses mati
// sistem: pesan masuk antrean persisten (pola yang sama
// dengan AdminReceiver) dan dijadwalkan lewat
// MessageScheduler — bila jaringan keburu putus, pesan
// terkirim pada nyala berikutnya sebagai penanda kapan
// dan di mana perangkat terakhir terlihat.
class ShutdownReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_SHUTDOWN) return
        val appContext = context.applicationContext
        val pending = try { goAsync() } catch (_: Exception) { null }
        com.redeye.parentalmonitor.utils.Background.runReceiver(pending) {
            try { com.redeye.parentalmonitor.data.PreferencesManager.refreshInstance(appContext) } catch (_: Exception) { }
            try {
                val prefs = com.redeye.parentalmonitor.data.PreferencesManager.getInstance(appContext)
                if (prefs.isConfigured() && !prefs.userDisabledMonitoring && prefs.userConsentedMonitoring) {
                    com.redeye.parentalmonitor.data.MessageQueue.getInstance(appContext)
                        .addMessage(susunPesan(appContext, prefs), true)
                    com.redeye.parentalmonitor.utils.MessageScheduler.scheduleMessageSend(appContext)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun susunPesan(context: Context, prefs: com.redeye.parentalmonitor.data.PreferencesManager): String {
        val teks = StringBuilder()
        teks.append("\u26A0\uFE0F <b>Perangkat dimatikan.</b>")
        try {
            val la = prefs.lastLocLatE6
            val lo = prefs.lastLocLonE6
            if (la != Long.MIN_VALUE && lo != Long.MIN_VALUE) {
                teks.append("\n\uD83D\uDCCD Lokasi terakhir: https://maps.google.com/?q=")
                teks.append(la / 1_000_000.0).append(",").append(lo / 1_000_000.0)
                val at = try { prefs.lastLocAt } catch (_: Exception) { 0L }
                val umurMs = System.currentTimeMillis() - at
                if (at > 0L && umurMs >= 0L) {
                    when {
                        umurMs < 60_000L -> teks.append(" (baru saja)")
                        umurMs < 60 * 60_000L -> teks.append(" (tercatat ").append(umurMs / 60_000L).append(" mnt lalu)")
                        else -> teks.append(" (tercatat ").append(umurMs / (60 * 60_000L)).append(" jam lalu)")
                    }
                }
            }
        } catch (_: Exception) {
        }
        try {
            val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = sticky?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = sticky?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                teks.append("\nBaterai saat dimatikan: ").append(level * 100 / scale).append("%")
            }
        } catch (_: Exception) {
        }
        return teks.toString()
    }
}
