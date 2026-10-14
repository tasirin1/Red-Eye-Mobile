package com.redeye.parentalmonitor.utils

object Background {
    private val pool: java.util.concurrent.ExecutorService = java.util.concurrent.ThreadPoolExecutor(
        0, 2, 60L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue(64),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "RedEyeBg").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()
    )

    private val critical: java.util.concurrent.ExecutorService = java.util.concurrent.ThreadPoolExecutor(
        0, 1, 60L, java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue(),
        java.util.concurrent.ThreadFactory { r -> Thread(r, "RedEyeCrit").apply { isDaemon = true } },
        java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()
    )

    fun run(block: () -> Unit) {
        try {
            pool.execute {
                try {
                    block()
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

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
