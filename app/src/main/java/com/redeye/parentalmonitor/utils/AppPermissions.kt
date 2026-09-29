package com.redeye.parentalmonitor.utils

import android.Manifest
import android.os.Build

object AppPermissions {
    private val permsTiramisuPlus = arrayOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.CAMERA,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
    private val permsLegacy = arrayOf(
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.CAMERA,
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION
    )
    val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permsTiramisuPlus else permsLegacy
}
