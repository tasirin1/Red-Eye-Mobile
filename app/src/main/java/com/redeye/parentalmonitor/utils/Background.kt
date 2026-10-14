package com.redeye.parentalmonitor.utils

object Background {
    private val critical: java.util.concurrent.ExecutorService = java.util.concurrent.ThreadPoolExecutor(
        0, 1, 60L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue(),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "RedEyeCrit").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()
    )

    fun runCritical(block: () -> Unit) {
        try {
            critical.execute {
                try {
                    block()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
            try {
                block()
            } catch (_: Exception) {
            }
        }
    }
}
