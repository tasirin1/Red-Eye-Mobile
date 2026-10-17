package com.redeye.parentalmonitor.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService
import com.redeye.parentalmonitor.ui.SetupActivity

class SetupTileService : TileService() {

    override fun onClick() {
        super.onClick()
        try {
            val intent = Intent(this, SetupActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val tap = PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(tap)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (_: Exception) {
        }
    }
}
