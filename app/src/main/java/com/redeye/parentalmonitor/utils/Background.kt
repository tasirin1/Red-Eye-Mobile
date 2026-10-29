package com.redeye.parentalmonitor.utils

object Background {
    private val critical: java.util.concurrent.ExecutorService = java.util.concurrent.ThreadPoolExecutor(
        0, 1, 60L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue(),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "RedEyeCrit").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()
    )

    private val receivers: java.util.concurrent.ExecutorService = java.util.concurrent.ThreadPoolExecutor(
        0, 1, 60L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue(),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "RedEyeRecv").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()
    )

    fun runCritical(block: () -> Unit) {
        critical.execute {
            try {
                block()
            } catch (e: Exception) {
                android.util.Log.e("Background", "runCritical block failed (${e.javaClass.simpleName}): ${e.message}", e)
            }
        }
    }

    fun runReceiver(pendingResult: android.content.BroadcastReceiver.PendingResult?, block: () -> Unit) {
        try {
            receivers.execute {
                try {
                    block()
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
}
