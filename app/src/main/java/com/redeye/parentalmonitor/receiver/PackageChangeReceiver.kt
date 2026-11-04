package com.redeye.parentalmonitor.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import com.redeye.parentalmonitor.data.MessageQueue
import com.redeye.parentalmonitor.data.PreferencesManager
import com.redeye.parentalmonitor.utils.Background
import com.redeye.parentalmonitor.utils.MessageScheduler

class PackageChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_PACKAGE_ADDED && action != Intent.ACTION_PACKAGE_REMOVED) return
        val pkg = intent.data?.schemeSpecificPart ?: return
        if (pkg.isEmpty() || pkg == context.packageName) return
        com.redeye.parentalmonitor.service.MonitoringService.invalidateAppLabel(pkg)
        com.redeye.parentalmonitor.service.A11yMonitor.invalidatePackageCache(pkg)
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
        val appContext = context.applicationContext
        val pendingResult = try {
            goAsync()
        } catch (_: Exception) {
            null
        }
        Background.runReceiver(pendingResult) {
            // Uninstall sungguhan melupakan paket dari himpunan
            // dikenal (issue #854) — tanpa syarat monitoring
            // aktif, agar pemasangan ulang kapan pun kembali
            // teralert. Dipindah ke jalur async (issue #999):
            // membuka prefs terenkripsi sinkron di benang
            // broadcast bisa lambat (I/O keystore) dan makan
            // jatah waktu onReceive.
            if (action == Intent.ACTION_PACKAGE_REMOVED) {
                try {
                    PreferencesManager.getInstance(appContext).forgetA11yApp(pkg)
                } catch (_: Exception) {
                }
            }
            handle(appContext, action, pkg)
        }
    }

    private fun handle(context: Context, action: String, pkg: String) {
        try {
            PreferencesManager.refreshInstance(context)
        } catch (_: Exception) {
        }
        val prefs = try {
            PreferencesManager.getInstance(context)
        } catch (_: Exception) {
            return
        }
        val active = try {
            prefs.isConfigured() && prefs.isMonitoringEnabled && !prefs.monitoringPaused && !prefs.userDisabledMonitoring && prefs.userConsentedMonitoring && prefs.a11yAppAlert
        } catch (_: Exception) {
            false
        }
        if (!active) return
        val text = if (action == Intent.ACTION_PACKAGE_ADDED) {
            val info = try { context.packageManager.getApplicationInfo(pkg, 0) } catch (_: Exception) { null } ?: return
            if ((info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0) return
            val label = try {
                context.packageManager.getApplicationLabel(info).toString().ifEmpty { pkg }
            } catch (_: Exception) {
                pkg
            }
            "📦 <b>Aplikasi baru dipasang</b>\n" + android.text.Html.escapeHtml(label) + "\n<code>" + pkg + "</code>"
        } else {
            val lastLabel = try {
                com.redeye.parentalmonitor.service.A11yMonitor.lastRecordedLabel(context, pkg)
            } catch (_: Exception) {
                ""
            }
            if (lastLabel.isNotEmpty() && lastLabel != pkg) {
                "🗑️ <b>Aplikasi dihapus</b>\n" + android.text.Html.escapeHtml(lastLabel) + "\n<code>" + pkg + "</code>"
            } else {
                "🗑️ <b>Aplikasi dihapus</b>\n<code>" + pkg + "</code>"
            }
        }
        // Dedupe antar-instance (issue #1413): hanya klaim
        // pertama yang mengantre; salinan siaran yang sama
        // dari instance manifest/dinamis lain dilewati.
        val diklaim = try {
            prefs.claimPackageEvent(action, pkg, System.currentTimeMillis())
        } catch (_: Exception) {
            true
        }
        if (!diklaim) return
        try {
            MessageQueue.getInstance(context).addMessage(text, true)
            MessageScheduler.scheduleMessageSend(context)
        } catch (_: Exception) {
        }
    }
}
