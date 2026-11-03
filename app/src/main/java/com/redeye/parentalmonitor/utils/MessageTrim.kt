package com.redeye.parentalmonitor.utils

// Pemotongan pesan antrean ke batas ukuran (issue #926),
// diekstrak dari MessageQueue agar terkunci uji (issue #977).
// Potongan lewat TextChunk.safeCut: substring polos lama bisa
// membelah pasangan surrogate tepat di batas, menyisakan
// karakter rusak di ujung potongan.
object MessageTrim {
    const val MAX_CHARS = 32_000
    const val SUFFIX = " …[dipotong: pesan terlalu panjang]"

    fun trim(message: String): String {
        if (message.length <= MAX_CHARS) return message
        val cut = TextChunk.safeCut(message, MAX_CHARS - SUFFIX.length)
        return message.substring(0, cut) + SUFFIX
    }
}
