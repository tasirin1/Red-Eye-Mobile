package com.redeye.parentalmonitor.utils

object BootClock {

    const val SAME_BOOT_TOLERANCE_MS = 120_000L

    fun bootWallAt(wallMs: Long, elapsedMs: Long): Long {
        return wallMs - elapsedMs
    }

    fun sameBoot(prevWall: Long, prevElapsed: Long, nowWall: Long, nowElapsed: Long): Boolean {
        if (prevWall <= 0L || prevElapsed <= 0L) return false
        val prevBoot = bootWallAt(prevWall, prevElapsed)
        val nowBoot = bootWallAt(nowWall, nowElapsed)
        return kotlin.math.abs(prevBoot - nowBoot) <= SAME_BOOT_TOLERANCE_MS
    }
}
