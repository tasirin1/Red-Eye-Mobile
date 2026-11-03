package com.redeye.parentalmonitor.utils

object QueueFresh {

    const val MAX_ELAPSED_AGE_MS = 7 * 24 * 60 * 60_000L
    const val BOOT_TOLERANCE_MS = 120_000L
    const val FUTURE_PROOF_MS = 5 * 60_000L

    // Inti murni kesegaran pesan antrean (issue #1028;
    // logika dari MessageQueue.isFresh): pesan segar bila
    // cap dindingnya belum melewati batas, atau — bila cap
    // monotonnya terbukti dari boot yang sama — usia
    // monotonnya belum melewati 7 hari.
    fun isFresh(timestamp: Long, elapsedAt: Long, msgBootAt: Long, wallCutoff: Long, nowElapsed: Long, bootAt: Long): Boolean {
        val sameBoot = msgBootAt > 0L && bootAt > 0L && kotlin.math.abs(msgBootAt - bootAt) <= BOOT_TOLERANCE_MS
        if (elapsedAt > 0L && sameBoot) {
            return if (nowElapsed < elapsedAt) {
                timestamp >= wallCutoff
            } else {
                nowElapsed - elapsedAt <= MAX_ELAPSED_AGE_MS || timestamp >= wallCutoff
            }
        }
        return timestamp >= wallCutoff
    }

    // Keputusan penyelamatan "jam melompat" (issue #967):
    // antrean mayoritas-basi hanya dipertahankan utuh bila
    // ada BUKTI lompatan jam — semua pesan tanpa penanda
    // boot (warisan) DAN ada pesan bertanggal masa depan.
    fun clockJumpRescue(total: Int, freshCount: Int, allBootUnknown: Boolean, anyFutureDated: Boolean): Boolean {
        return total > 5 && freshCount * 2 < total && allBootUnknown && anyFutureDated
    }
}
