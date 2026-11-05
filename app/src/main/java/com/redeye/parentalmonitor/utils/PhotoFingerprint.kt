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
                val grid = IntArray(bmp.width * bmp.height)
                var sum = 0L
                for (y in 0 until bmp.height) {
                    for (x in 0 until bmp.width) {
                        val p = bmp.getPixel(x, y)
                        val l = (((p shr 16) and 0xff) * 299 + ((p shr 8) and 0xff) * 587 + (p and 0xff) * 114) / 1000
                        grid[y * bmp.width + x] = l
                        sum += l
                    }
                }
                val luma = if (grid.isEmpty()) 255 else (sum / grid.size).toInt().coerceIn(0, 255)
                luma to fingerprintBits(cellAverages(grid, bmp.width, bmp.height))
            } finally {
                try { bmp.recycle() } catch (_: Exception) { }
            }
        } catch (_: Exception) {
            255 to 0L
        }
    }

    fun cellAverages(lumas: IntArray, width: Int, height: Int): IntArray {
        val out = IntArray(64)
        if (width <= 0 || height <= 0 || lumas.size < width * height) return out
        for (cy in 0 until 8) {
            for (cx in 0 until 8) {
                val x0 = cx * width / 8
                val x1 = ((cx + 1) * width / 8).coerceAtLeast(x0 + 1)
                val y0 = cy * height / 8
                val y1 = ((cy + 1) * height / 8).coerceAtLeast(y0 + 1)
                var sum = 0L
                var n = 0
                for (y in y0 until y1) {
                    for (x in x0 until x1) {
                        sum += lumas[y * width + x]
                        n++
                    }
                }
                out[cy * 8 + cx] = if (n == 0) 0 else (sum / n).toInt()
            }
        }
        return out
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

    // Rentang grid 8x8 (issue #1540): proksi 'nyaris
    // tanpa detail' — foto buram berat / lensa kotor
    // meratakan kontras antarsel. Ambang disengaja
    // sangat rendah agar foto polos yang sah (dinding,
    // langit) tidak tertuduh; dipakai untuk CATATAN
    // laporan, bukan penolakan.
    fun spreadOfCells(cell: IntArray): Int {
        if (cell.isEmpty()) return 0
        var lo = cell[0]
        var hi = cell[0]
        for (v in cell) {
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        return hi - lo
    }

    const val FEATURELESS_SPREAD = 12

    fun gridSpread(photoFile: File): Int {
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(photoFile.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return -1
            var sample = 1
            while (bounds.outWidth / sample > 16 || bounds.outHeight / sample > 16) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = android.graphics.BitmapFactory.decodeFile(photoFile.absolutePath, opts) ?: return -1
            try {
                val grid = IntArray(bmp.width * bmp.height)
                for (y in 0 until bmp.height) {
                    for (x in 0 until bmp.width) {
                        val px = bmp.getPixel(x, y)
                        grid[y * bmp.width + x] = (((px shr 16) and 0xff) * 299 + ((px shr 8) and 0xff) * 587 + (px and 0xff) * 114) / 1000
                    }
                }
                spreadOfCells(cellAverages(grid, bmp.width, bmp.height))
            } finally {
                try { bmp.recycle() } catch (_: Exception) { }
            }
        } catch (_: Exception) {
            -1
        }
    }

    fun isComparableFingerprint(hash: Long): Boolean = hash != 0L && hash != 1L

    fun isDuplicateFingerprint(now: Long, other: Long): Boolean {
        if (!isComparableFingerprint(now) || !isComparableFingerprint(other)) return false
        return hammingDistance(now, other) <= 6
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
