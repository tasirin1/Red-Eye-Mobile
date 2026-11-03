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

    // Lama masa tenggang Setup (ditulis SetupActivity saat
    // memberi izin; satu-satunya penulis cap tenggang).
    const val GRACE_MS = 120_000L

    fun inSetupGrace(
        reason: String?,
        nowWall: Long,
        settingsGraceUntil: Long,
        adminGraceUntil: Long,
        nowElapsed: Long,
        settingsGraceElapsedUntil: Long,
        adminGraceElapsedUntil: Long,
        currentBootCount: Long = 0L,
        settingsGraceBootCount: Long = 0L,
        adminGraceBootCount: Long = 0L
    ): Boolean {
        // Jangkar elapsed didahulukan (issue #1007): masa
        // tenggang yang hanya berjangkar jam dinding bisa
        // diperpanjang tanpa batas dengan memundurkan jam
        // perangkat. Cap elapsed ditulis Setup bersamaan;
        // cap warisan tanpa pasangan elapsed jatuh ke
        // perbandingan dinding seperti sebelumnya.
        return when (reason) {
            "aksesibilitas" -> graceDecision(nowWall, settingsGraceUntil, nowElapsed, settingsGraceElapsedUntil, settingsGraceBootCount, currentBootCount)
            "admin perangkat" -> graceDecision(nowWall, adminGraceUntil, nowElapsed, adminGraceElapsedUntil, adminGraceBootCount, currentBootCount)
            else -> false
        }
    }

    private fun graceDecision(nowWall: Long, wallUntil: Long, nowElapsed: Long, elapsedUntil: Long, capBootCount: Long = 0L, currentBootCount: Long = 0L): Boolean {
        // Cabang elapsed hanya sah di boot tempat cap ditulis
        // (issue #1035): cap berpenanda boot yang berbeda
        // adalah cap basi — putuskan dari dinding. Penanda 0
        // berarti "ditulis sebelum ada boot tercatat" (cap
        // warisan atau cap dari boot pertama sesudah
        // pasang/update): ia hanya setara boot yang sama bila
        // penghitung saat ini juga masih 0 — sesudah boot
        // pertama tercatat, cap 0 tak lagi boleh memakai
        // cabang elapsed (issue #1048).
        val bootSama = capBootCount == currentBootCount
        if (elapsedUntil > 0L && bootSama) {
            val remaining = elapsedUntil - nowElapsed
            // Di boot tempat cap ditulis, sisa elapsed TIDAK
            // PERNAH melebihi GRACE_MS. Sisa yang lebih besar
            // berarti cap basi dari boot sebelumnya (issue
            // #1009: elapsed persisten terlampaui lagi oleh
            // boot baru, tenggang "hidup lagi" berhari-hari)
            // — putuskan dari dinding, yang untuk cap basi
            // sudah kedaluwarsa.
            if (remaining <= GRACE_MS) return remaining > 0L
        }
        return nowWall < wallUntil
    }

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
