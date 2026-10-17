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
        try {
            Background.runCritical {
                try {
                    handle(appContext, action, pkg)
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
        if (!prefs.isConfigured()) return
        val text = if (action == Intent.ACTION_PACKAGE_ADDED) {
            if (isSystemApp(context, pkg)) return
            "📦 <b>Aplikasi baru dipasang</b>\n" + appLabel(context, pkg) + "\n<code>" + pkg + "</code>"
        } else {
            "🗑️ <b>Aplikasi dihapus</b>\n<code>" + pkg + "</code>"
        }
        try {
            MessageQueue.getInstance(context).addMessage(text, true)
            MessageScheduler.scheduleMessageSend(context)
        } catch (_: Exception) {
        }
    }

    private fun appLabel(context: Context, pkg: String): String {
        return try {
            val manager = context.packageManager
            val info = manager.getApplicationInfo(pkg, 0)
            val label = manager.getApplicationLabel(info).toString()
            android.text.Html.escapeHtml(label.ifEmpty { pkg })
        } catch (_: Exception) {
            pkg
        }
    }

    private fun isSystemApp(context: Context, pkg: String): Boolean {
        return try {
            val info = context.packageManager.getApplicationInfo(pkg, 0)
            (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        } catch (_: Exception) {
            false
        }
    }
}
