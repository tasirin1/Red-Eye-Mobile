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
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
        val appContext = context.applicationContext
        val pendingResult = try {
            goAsync()
        } catch (_: Exception) {
            null
        }
        Background.runReceiver(pendingResult) {
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
            prefs.isConfigured() && prefs.isMonitoringEnabled && !prefs.monitoringPaused && !prefs.userDisabledMonitoring && prefs.userConsentedMonitoring
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
            "🗑️ <b>Aplikasi dihapus</b>\n<code>" + pkg + "</code>"
        }
        try {
            MessageQueue.getInstance(context).addMessage(text, true)
            MessageScheduler.scheduleMessageSend(context)
        } catch (_: Exception) {
        }
    }
}
