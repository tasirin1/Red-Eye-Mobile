package com.redeye.parentalmonitor.utils

// Batas efektif antrean pesan (issue #842), diekstrak dari
// MessageQueue agar terkunci uji (issue #977): kelonggaran
// +5 milik pesan prioritas yang ADA di antrean atau pesan
// yang sedang masuk — bukan bendera lain.
object QueueCap {
    const val MAX_SIZE = 100
    const val PRIORITY_BONUS = 5

    fun overCap(size: Int, anyPriorityQueued: Boolean, incomingPriority: Boolean): Boolean {
        val cap = MAX_SIZE + if (incomingPriority || anyPriorityQueued) PRIORITY_BONUS else 0
        return size > cap
    }
}
