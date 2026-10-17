package com.redeye.parentalmonitor.utils

import android.content.Intent
import android.media.projection.MediaProjection

object ScreenCapture {
    @Volatile
    private var resultCode = 0

    @Volatile
    private var resultData: Intent? = null

    @Volatile
    private var projection: MediaProjection? = null

    @Synchronized
    fun grant(code: Int, data: Intent?) {
        if (code == android.app.Activity.RESULT_OK && data != null) {
            resultCode = code
            resultData = data
        } else {
            revoke()
        }
    }

    @Synchronized
    fun request(): Pair<Int, Intent>? {
        val data = resultData ?: return null
        return resultCode to data
    }

    fun current(): MediaProjection? = projection

    @Synchronized
    fun adopt(value: MediaProjection?) {
        projection = value
    }

    @Synchronized
    fun revoke() {
        val dead = projection
        projection = null
        resultCode = 0
        resultData = null
        try { dead?.stop() } catch (_: Exception) { }
    }

    fun granted(): Boolean = resultData != null || projection != null
}
