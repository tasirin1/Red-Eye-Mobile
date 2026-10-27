package com.redeye.parentalmonitor.utils

import android.content.pm.ServiceInfo

object FgsTypes {
    private const val WAKE_COOLDOWN_MS = 30 * 60_000L
    private const val WAKE_LONG_COOLDOWN_MS = 6 * 60 * 60_000L
    private const val WAKE_MAX_FAILS = 3

    fun base(sdkInt: Int): Int {
        return if (sdkInt >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
    }

    fun compute(sdkInt: Int, camera: Boolean, mic: Boolean, location: Boolean, projection: Boolean): Int {
        var types = base(sdkInt)
        if (camera) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        }
        if (mic) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
        if (location) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        if (sdkInt >= android.os.Build.VERSION_CODES.Q && projection) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        return types
    }

    fun ladder(full: Int, base: Int): List<Int> {
        val ladder = ArrayList<Int>(4)
        ladder.add(full)
        if ((full and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) != 0) {
            ladder.add(full and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION.inv())
        }
        val camType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        val micType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val locType = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        val whileInUse = camType or micType or locType
        val withoutWhileInUse = full and whileInUse.inv()
        if (withoutWhileInUse != full && withoutWhileInUse != ladder[ladder.size - 1]) {
            ladder.add(withoutWhileInUse)
        }
        if (base != full && base != ladder[ladder.size - 1]) ladder.add(base)
        return ladder
    }

    fun hasCamera(types: Int): Boolean {
        return (types and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA) != 0
    }

    fun wakeCooldownMs(fails: Int): Long {
        return if (fails >= WAKE_MAX_FAILS) WAKE_LONG_COOLDOWN_MS else WAKE_COOLDOWN_MS
    }
}
