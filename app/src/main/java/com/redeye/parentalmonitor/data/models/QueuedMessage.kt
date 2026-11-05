package com.redeye.parentalmonitor.data.models

import java.util.UUID

data class QueuedMessage(
    val id: String = UUID.randomUUID().toString(),
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val elapsedAt: Long = 0L,
    val retryCount: Int = 0,
    val priority: Boolean = false,
    val bootAt: Long = 0L,
    // Potongan (per ±4000 aksara) yang sudah terkirim
    // untuk pesan panjang (issue #1682): run worker
    // sesudah kematian proses melanjutkan dari potongan
    // ini, bukan mengulang dari potongan pertama.
    val chunksSent: Int = 0
)
