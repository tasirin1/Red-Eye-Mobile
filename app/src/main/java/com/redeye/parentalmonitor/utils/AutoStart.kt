package com.redeye.parentalmonitor.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

object AutoStart {
    private val CANDIDATES: Map<String, List<String>> = mapOf(
        "xiaomi" to listOf("com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity"),
        "redmi" to listOf("com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity"),
        "poco" to listOf("com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity"),
        "oppo" to listOf(
            "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe/com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.oppo.safe/com.oppo.safe.PerformanceAppListActivity"
        ),
        "realme" to listOf(
            "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity"
        ),
        "oneplus" to listOf("com.oneplus.security/com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        "vivo" to listOf(
            "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
        ),
        "iqoo" to listOf(
            "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
        ),
        "huawei" to listOf(
            "com.huawei.systemmanager/com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager/com.huawei.systemmanager.optimize.process.ProtectActivity"
        ),
        "honor" to listOf(
            "com.hihonor.systemmanager/com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager/com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
        ),
        "samsung" to listOf(
            "com.samsung.android.lool/com.samsung.android.sm.ui.battery.BatteryActivity",
            "com.samsung.android.sm/com.samsung.android.sm.ui.battery.BatteryActivity"
        ),
        "asus" to listOf(
            "com.asus.mobilemanager/com.asus.mobilemanager.autostart.AutoStartActivity",
            "com.asus.mobilemanager/com.asus.mobilemanager.powersaver.PowerSaverSettings"
        ),
        "meizu" to listOf("com.meizu.safe/com.meizu.safe.permission.SmartBGActivity"),
        "letv" to listOf("com.letv.android.letvsafe/com.letv.android.letvsafe.AutobootManageActivity"),
        "nokia" to listOf("com.evenwell.powersaving.g3/com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity")
    )

    fun manufacturerLabel(): String {
        return try {
            val raw = if (Build.MANUFACTURER.isNotEmpty()) Build.MANUFACTURER else Build.BRAND
            if (raw.isEmpty()) "Android" else raw.substring(0, 1).uppercase() + raw.substring(1)
        } catch (_: Exception) {
            "Android"
        }
    }

    fun open(context: Context): Boolean {
        for (flat in candidates()) {
            val component = ComponentName.unflattenFromString(flat) ?: continue
            try {
                context.startActivity(
                    Intent().setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return true
            } catch (_: Exception) {
            }
        }
        return openAppInfo(context)
    }

    fun openAppInfo(context: Context): Boolean {
        return try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    fun canDrawOverlays(context: Context): Boolean {
        return try {
            Settings.canDrawOverlays(context)
        } catch (_: Exception) {
            false
        }
    }

    fun openOverlaySettings(context: Context): Boolean {
        return try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    fun candidates(): List<String> {
        val brands = LinkedHashSet<String>()
        try {
            brands.add(Build.MANUFACTURER.lowercase(java.util.Locale.ROOT))
        } catch (_: Exception) {
        }
        try {
            brands.add(Build.BRAND.lowercase(java.util.Locale.ROOT))
        } catch (_: Exception) {
        }
        val flats = LinkedHashSet<String>()
        for (brand in brands) {
            CANDIDATES[brand]?.let { flats.addAll(it) }
        }
        return flats.toList()
    }
}
