package com.redeye.parentalmonitor.utils

object RestorePinGate {
    const val MAX_FAILURES = 3
    const val LOCK_MS = 15 * 60_000L

    fun locked(lockoutUntil: Long, now: Long): Boolean = now < lockoutUntil

    // Kunci ganda jam dinding + jam monoton (issue #687). elapsedRealtime
    // monoton selama satu boot dan hanya mengulang saat reboot, jadi:
    // selama boot yang sama keputusan memakai jam monoton — memajukan
    // jam dinding tidak membuka kunci lebih awal. Sesudah reboot
    // keputusan memakai jam dinding seperti semula.
    // Boot yang sama WAJIB dibuktikan lewat pasangan jam (issue
    // #957): syarat lama elapsedNow >= elapsedAtLock terpenuhi
    // lagi sesudah reboot begitu uptime baru melewati jangkar
    // lama, dan jendela 15 menit dihitung ulang dari sana —
    // kunci palsu padahal tenggat dinding sudah lama lewat.
    // BootClock.sameBootOrClockShift membuktikan boot yang sama
    // sambil tetap menoleransi koreksi jam di tengah kunci.
    fun locked(lockoutUntilWall: Long, wallNow: Long, elapsedAtLock: Long, elapsedNow: Long): Boolean {
        if (elapsedAtLock > 0L && elapsedNow >= elapsedAtLock) {
            val wallAtLock = lockoutUntilWall - LOCK_MS
            if (BootClock.sameBootOrClockShift(wallAtLock, elapsedAtLock, wallNow, elapsedNow)) {
                return elapsedNow - elapsedAtLock < LOCK_MS
            }
        }
        return wallNow < lockoutUntilWall
    }

    fun registerFailure(failCount: Int, now: Long): Pair<Int, Long> {
        val next = failCount.coerceAtLeast(0) + 1
        return if (next >= MAX_FAILURES) 0 to (now + LOCK_MS) else next to 0L
    }

    // Kunci percobaan = pasangan (berkas, PIN) (issue #873): kegagalan
    // dekripsi tak bisa membedakan PIN salah dari berkas RUSAK —
    // keduanya gagal di tag AES-GCM yang sama. Menghitung percobaan
    // yang PERSIS sama berulang kali menghukum pemilik yang mencoba
    // lagi berkas rusak yang sama dengan PIN yang benar (3x -> kunci
    // 15 menit). Percobaan identik tak membawa tebakan baru, jadi
    // hanya percobaan BERBEDA yang dihitung; tebakan PIN berbeda atas
    // berkas yang sama tetap terhitung satu per satu.
    fun attemptKey(blob: String, pin: String): String {
        return try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val digest = md.digest((blob + "\u0000" + pin).toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }
    }

    // Hasil: (jumlah gagal baru, kunci sampai, terhitung?). Bila tak
    // terhitung, jumlah dan kunci tak berubah.
    fun registerFailure(failCount: Int, now: Long, prevKey: String, newKey: String): Triple<Int, Long, Boolean> {
        if (prevKey.isNotEmpty() && newKey.isNotEmpty() && prevKey == newKey) {
            return Triple(failCount.coerceAtLeast(0), 0L, false)
        }
        val (count, lock) = registerFailure(failCount, now)
        return Triple(count, lock, true)
    }
}
