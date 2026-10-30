package com.redeye.parentalmonitor.utils

import java.io.File

object PhotoFingerprint {
    fun analyze(photoFile: File): Pair<Int, Long> {
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(photoFile.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return 255 to 0L
            var sample = 1
            while (bounds.outWidth / sample > 16 || bounds.outHeight / sample > 16) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = android.graphics.BitmapFactory.decodeFile(photoFile.absolutePath, opts) ?: return 255 to 0L
            try {
                var sum = 0L
                var n = 0
                val stepX = (bmp.width / 16).coerceAtLeast(1)
                val stepY = (bmp.height / 16).coerceAtLeast(1)
                var y = 0
                while (y < bmp.height) {
                    var x = 0
                    while (x < bmp.width) {
                        val p = bmp.getPixel(x, y)
                        sum += (((p shr 16) and 0xff) * 299 + ((p shr 8) and 0xff) * 587 + (p and 0xff) * 114) / 1000
                        n++
                        x += stepX
                    }
                    y += stepY
                }
                val luma = if (n == 0) 255 else (sum / n).toInt().coerceIn(0, 255)

                val cell = IntArray(64)
                for (cy in 0 until 8) {
                    for (cx in 0 until 8) {
                        val x = (((cx + 0.5) * bmp.width) / 8).toInt().coerceIn(0, bmp.width - 1)
                        val y2 = (((cy + 0.5) * bmp.height) / 8).toInt().coerceIn(0, bmp.height - 1)
                        val p = bmp.getPixel(x, y2)
                        val l = (((p shr 16) and 0xff) * 299 + ((p shr 8) and 0xff) * 587 + (p and 0xff) * 114) / 1000
                        cell[cy * 8 + cx] = l
                    }
                }
                luma to fingerprintBits(cell)
            } finally {
                try { bmp.recycle() } catch (_: Exception) { }
            }
        } catch (_: Exception) {
            255 to 0L
        }
    }

    fun fingerprintBits(cell: IntArray): Long {
        var sum = 0L
        for (v in cell) sum += v
        val avg = (sum / 64).toInt()
        var bits = 0L
        for (i in 0 until 64) {
            if (cell[i] > avg) bits = bits or (1L shl i)
        }
        return bits
    }

    fun hammingDistance(a: Long, b: Long): Int {
        var x = a xor b
        var n = 0
        while (x != 0L) {
            n++
            x = x and (x - 1)
        }
        return n
    }
}
