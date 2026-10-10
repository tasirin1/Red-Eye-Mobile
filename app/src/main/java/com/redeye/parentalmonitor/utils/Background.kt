package com.redeye.parentalmonitor.utils

object Background {
    private val pool: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newFixedThreadPool(2)

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
}
