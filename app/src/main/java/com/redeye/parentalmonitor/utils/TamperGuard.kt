package com.redeye.parentalmonitor.utils

import java.util.Locale

object TamperGuard {

    private val SETTINGS_PREFIXES = listOf(
        "com.android.settings",
        "com.google.android.settings",
        "com.samsung.android.settings",
        "com.miui.securitycenter",
        "com.coloros.safecenter",
        "com.oplus.safecenter",
        "com.oneplus.security",
        "com.vivo.settings",
        "com.bbk.settings",
        "com.huawei.settings",
        "com.hihonor.settings",
        "com.meizu.settings",
        "com.transsion.settings"
    )

    private val INSTALLER_HINTS = listOf(
        "packageinstaller",
        "permissioncontroller"
    )

    fun mayBeSensitive(packageName: String): Boolean {
        val pkg = packageName.lowercase(Locale.ROOT)
        return isSettingsLike(pkg) || isInstallerLike(pkg)
    }

    fun reason(packageName: String, className: String, texts: List<String>, selfPackage: String, selfLabel: String): String? {
        val pkg = packageName.lowercase(Locale.ROOT)
        val cls = className.lowercase(Locale.ROOT)
        val joined = texts.joinToString(" ").lowercase(Locale.ROOT)
        val self = selfLabel.lowercase(Locale.ROOT).trim()
        val selfMention = joined.contains(selfPackage.lowercase(Locale.ROOT)) ||
            (self.isNotEmpty() && joined.contains(self))

        if (isSettingsLike(pkg)) {
            if (cls.contains("accessibility") ||
                (selfMention && (joined.contains("aksesibilitas") || joined.contains("accessibility")))) {
                return "aksesibilitas"
            }
            if (selfMention && (cls.contains("deviceadmin") || joined.contains("admin perangkat") || joined.contains("device admin"))) {
                return "admin perangkat"
            }
            if (selfMention && (cls.contains("appdetails") || cls.contains("installedappdetails") ||
                    joined.contains("hapus") || joined.contains("uninstall") || joined.contains("copot"))) {
                return "hapus aplikasi"
            }
            if (cls.contains("externalsources") || cls.contains("installunknown") || cls.contains("unknownsources") ||
                joined.contains("sumber tidak dikenal") || joined.contains("sumber tak dikenal") ||
                joined.contains("unknown sources") || joined.contains("install unknown apps")) {
                return "sumber tak dikenal"
            }
            if (cls.contains("development") || joined.contains("opsi pengembang") || joined.contains("developer options")) {
                return "opsi pengembang"
            }
        }

        if (isInstallerLike(pkg) && selfMention &&
            (joined.contains("hapus") || joined.contains("uninstall") || joined.contains("copot"))) {
            return "hapus aplikasi"
        }
        return null
    }

    private fun isSettingsLike(pkg: String): Boolean {
        return SETTINGS_PREFIXES.any { pkg.startsWith(it) }
    }

    private fun isInstallerLike(pkg: String): Boolean {
        return INSTALLER_HINTS.any { pkg.contains(it) }
    }
}
