package com.redeye.parentalmonitor.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.redeye.parentalmonitor.R

class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        android.util.Log.i("AdminReceiver", "Device Admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        android.util.Log.w("AdminReceiver", "Device Admin disabled!")
        Toast.makeText(context, context.getString(R.string.admin_disabled), Toast.LENGTH_LONG).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        android.util.Log.w("AdminReceiver", "Attempting to disable Device Admin!")
        return context.getString(R.string.admin_disable_warning)
    }
}

