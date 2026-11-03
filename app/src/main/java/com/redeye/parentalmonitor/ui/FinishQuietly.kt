package com.redeye.parentalmonitor.ui

import android.app.Activity

// Tutup activity trampolin tanpa transisi (issue #1042):
// semantik ini dulu hidup sebagai dua salinan finishQuietly
// yang nyaris identik di FgsWakeActivity dan
// ShotConsentActivity — perubahan perilaku di satu tempat
// mudah lupa diterapkan di salinan lain, padahal keduanya
// jalur sensitif (bangun FGS kamera & consent screenshot).
fun Activity.finishQuietly() {
    if (isFinishing || isDestroyed) return
    try {
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    } catch (_: Exception) {
    }
    try {
        finish()
    } catch (_: Exception) {
    }
}
