package com.redeye.parentalmonitor.utils

object PowerSave {

    fun cameraIntervalEff(rawMinutes: Int, lowPower: Boolean): Int {
        val minutes = rawMinutes.coerceIn(0, 60)
        // Penganda hemat kamera diperkuat x2 -> x4 (issue
        // #1574): putaran kamera adalah putaran termahal
        // (kamera + JPEG + radio), jadi saat baterai rendah /
        // Battery Saver aktif penghematannya harus terasa —
        // interval bawaan 1 mnt menjadi 4 mnt, bukan 2 mnt.
        return if (lowPower) (minutes * 4).coerceAtMost(60) else minutes
    }

    fun patrolIntervalEff(rawMinutes: Int, lowPower: Boolean): Int {
        val minutes = rawMinutes.coerceIn(5, 180)
        return if (lowPower) (minutes * 2).coerceAtMost(180) else minutes
    }

    fun cameraSlowed(rawMinutes: Int): Boolean {
        return cameraIntervalEff(rawMinutes, true) > rawMinutes.coerceIn(0, 60)
    }

    fun patrolSlowed(rawMinutes: Int): Boolean {
        return patrolIntervalEff(rawMinutes, true) > rawMinutes.coerceIn(5, 180)
    }
}
