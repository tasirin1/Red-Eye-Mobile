package com.redeye.parentalmonitor.repository

import android.content.Context
import android.provider.CallLog
import com.redeye.parentalmonitor.data.models.CallData

class CallLogRepository(private val context: Context) {

    private val projection = arrayOf(
        CallLog.Calls._ID,
        CallLog.Calls.NUMBER,
        CallLog.Calls.CACHED_NAME,
        CallLog.Calls.DATE,
        CallLog.Calls.DURATION,
        CallLog.Calls.TYPE,
        CallLog.Calls.PHONE_ACCOUNT_ID,
        // Nama kolom presentasi sebagai literal (perbaikan
        // CI dari #1498): konstanta Calls.PRESENTATION tak
        // dikenal compileSdk di sini — literal adalah nama
        // kolom platform yang stabil ("presentation").
        "presentation"
    )

    // Peta id akun telepon -> label SIM (issue #1496),
    // dibangun malas & ditahan per instans repositori,
    // tetapi berumur terbatas (issue #1971): dulu peta
    // yang sudah terisi tak pernah dibatalkan, jadi ganti
    // SIM saat layanan hidup membuat label hilang/basi
    // sampai layanan restart. Sepuluh menit cukup pendek
    // untuk menangkap pergantian, cukup panjang agar
    // daftar langganan tak dibaca ulang per baris laporan.
    @Volatile
    private var petaSim: Map<String, String>? = null

    @Volatile
    private var petaSimAt: Long = 0L

    private fun labelSim(accountId: String?): String {
        if (accountId.isNullOrEmpty()) return ""
        val tersimpan = petaSim
        // Umur cache diukur jam monoton (issue #1991):
        // jam dinding yang mundur membuat selisih negatif
        // dan cache basi abadi -- celah #1971 dari arah
        // berlawanan. elapsedRealtime hanya maju.
        val peta = if (tersimpan != null && android.os.SystemClock.elapsedRealtime() - petaSimAt <= 600_000L) tersimpan else try {
            val sm = context.getSystemService(android.telephony.SubscriptionManager::class.java)
            val daftar = try { sm?.activeSubscriptionInfoList } catch (_: Exception) { null }
            val bangun = HashMap<String, String>()
            if (daftar != null) {
                for (info in daftar) {
                    try {
                        bangun[info.subscriptionId.toString()] = "SIM " + (info.simSlotIndex + 1)
                    } catch (_: Exception) {
                    }
                }
                // Hanya hasil dari daftar yang sungguh terbaca
                // DAN berisi yang di-cache (issue #1943, #1952):
                // daftar null/gagal -- atau kosong karena
                // telephony belum siap sesudah boot -- tidak
                // boleh mengunci peta kosong permanen --
                // panggilan berikutnya wajib mencoba lagi.
                if (bangun.isNotEmpty()) {
                    petaSim = bangun
                    petaSimAt = android.os.SystemClock.elapsedRealtime()
                }
            }
            bangun
        } catch (_: Exception) {
            emptyMap()
        }
        return peta[accountId] ?: ""
    }

    private val numberSearch = NumberHistorySearch(
        numberColumn = CallLog.Calls.NUMBER,
        dateColumn = CallLog.Calls.DATE,
        idColumn = CallLog.Calls._ID,
        logTag = "CallLogRepository",
        query = { selection, args, sortOrder, limit -> queryCalls(selection, args, sortOrder, limit) },
        numberOf = { it.number },
        dateOf = { it.date },
        idOf = { it.id }
    )

    fun getNewCalls(afterTimestamp: Long, afterId: Long = 0L, afterIdMax: Long = -1L): List<CallData> {
        // Batas masa depan (issue #1374): baris melewati batas
        // ditunda sampai waktunya tiba, tak memajukan kursor.
        val batas = com.redeye.parentalmonitor.utils.CursorAdvance.batasAtas(System.currentTimeMillis())
        if (afterId > 0L && afterTimestamp > 0L) {
            val utama = queryCalls(
                // Keyset atas urutan (DATE, _ID) — predikat OR lama melompati
                // baris sudut di bawah kedua maksimum kursor (issue #655).
                selection = "(${CallLog.Calls.DATE} > ? OR (${CallLog.Calls.DATE} = ? AND ${CallLog.Calls._ID} > ?)) AND ${CallLog.Calls.DATE} <= ?",
                args = arrayOf(afterTimestamp.toString(), afterTimestamp.toString(), afterId.toString(), batas.toString()),
                sortOrder = "${CallLog.Calls.DATE} ASC, ${CallLog.Calls._ID} ASC",
                limit = 100,
                strict = true
            )
            // Sapuan baris sisipan terlambat (issue #2012):
            // baris panggilan panjang disisipkan saat
            // panggilan berakhir dengan DATE = waktu mulai,
            // sehingga tanggalnya di bawah kursor dan keyset
            // tak pernah memilihnya. Calon diambil kasar
            // menurut _ID (monoton sisip) + tanggal di
            // bawah kursor (issue #2026: tanpa syarat
            // tanggal itu, LIMIT 100 bisa habis oleh baris
            // bukan-terlambat dan baris terlambat sungguhan
            // di luar jendela terlewati jangkar), lalu
            // disaring persis oleh predikat murni
            // CursorAdvance.
            // afterIdMax <= 0 menonaktifkan sapuan (pemanggil
            // selain siklus waktu-nyata tak berubah).
            if (afterIdMax <= 0L) return utama
            val calon = queryCalls(
                selection = "${CallLog.Calls._ID} > ? AND ${CallLog.Calls.DATE} < ? AND ${CallLog.Calls.DATE} <= ?",
                args = arrayOf(afterIdMax.toString(), afterTimestamp.toString(), batas.toString()),
                sortOrder = "${CallLog.Calls._ID} ASC",
                limit = 100,
                strict = true
            )
            val terlambat = calon.filter {
                com.redeye.parentalmonitor.utils.CursorAdvance.terlambat(it.id, it.date, afterTimestamp, afterIdMax, batas)
            }
            if (terlambat.isEmpty()) return utama
            return (terlambat + utama).sortedWith(compareBy({ it.date }, { it.id }))
        }
        if (afterId > 0L) {
            return queryCalls(
                selection = "${CallLog.Calls._ID} > ? AND ${CallLog.Calls.DATE} <= ?",
                args = arrayOf(afterId.toString(), batas.toString()),
                sortOrder = "${CallLog.Calls._ID} ASC",
                limit = 100,
                strict = true
            )
        }
        return queryCalls(
            selection = "${CallLog.Calls.DATE} > ? AND ${CallLog.Calls.DATE} <= ?",
            args = arrayOf(afterTimestamp.toString(), batas.toString()),
            sortOrder = "${CallLog.Calls.DATE} ASC, ${CallLog.Calls._ID} ASC",
            limit = 100,
            strict = true
        )
    }

    // Padanan smsPendahulu untuk panggilan (issue #1374).
    fun callPendahulu(beforeDate: Long, beforeId: Long, untilDate: Long): Pair<Long, Long>? {
        val cursor = com.redeye.parentalmonitor.utils.ContentQuery.query(
            context.contentResolver,
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls._ID, CallLog.Calls.DATE),
            "${CallLog.Calls.DATE} <= ? AND (${CallLog.Calls.DATE} < ? OR (${CallLog.Calls.DATE} = ? AND ${CallLog.Calls._ID} < ?))",
            arrayOf(untilDate.toString(), beforeDate.toString(), beforeDate.toString(), beforeId.toString()),
            "${CallLog.Calls.DATE} DESC, ${CallLog.Calls._ID} DESC",
            1
        ) ?: return null
        return try {
            if (cursor.moveToFirst()) {
                cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls._ID)) to
                    cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE))
            } else null
        } catch (_: Exception) {
            null
        } finally {
            try { cursor.close() } catch (_: Exception) { }
        }
    }

    fun callDateForId(id: Long): Long {
        if (id <= 0L) return 0L
        val cursor = com.redeye.parentalmonitor.utils.ContentQuery.query(
            context.contentResolver,
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.DATE),
            "${CallLog.Calls._ID} = ?",
            arrayOf(id.toString()),
            "${CallLog.Calls._ID} ASC",
            1
        ) ?: return 0L
        return try {
            if (cursor.moveToFirst()) cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)) else 0L
        } catch (_: Exception) {
            0L
        } finally {
            try { cursor.close() } catch (_: Exception) { }
        }
    }

    fun newestCallId(): Long {
        val cursor = com.redeye.parentalmonitor.utils.ContentQuery.query(
            context.contentResolver,
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls._ID),
            null,
            null,
            "${CallLog.Calls._ID} DESC",
            1
        ) ?: return -1L
        return try {
            if (cursor.moveToFirst()) cursor.getLong(cursor.getColumnIndexOrThrow(CallLog.Calls._ID)) else 0L
        } catch (e: Exception) {
            android.util.Log.e("CallLogRepository", "Error reading newest call id", e)
            -1L
        } finally {
            try { cursor.close() } catch (_: Exception) { }
        }
    }

    fun getAllCalls(limit: Int = 200): List<CallData> {
        val now = System.currentTimeMillis()
        return queryCalls(
            selection = "${CallLog.Calls.DATE} <= ?",
            args = arrayOf(now.toString()),
            sortOrder = "${CallLog.Calls.DATE} DESC, ${CallLog.Calls._ID} DESC",
            limit = limit.coerceIn(1, REPOSITORY_LIMIT_CAP)
        )
    }

    fun getCallsSince(sinceMillis: Long, limit: Int = 200): List<CallData> {
        val safeLimit = limit.coerceIn(1, REPOSITORY_LIMIT_CAP)
        val now = System.currentTimeMillis()
        return queryCalls(
            selection = "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.DATE} <= ?",
            args = arrayOf(sinceMillis.toString(), now.toString()),
            sortOrder = "${CallLog.Calls.DATE} DESC, ${CallLog.Calls._ID} DESC",
            limit = safeLimit
        )
    }

    fun countCallsSince(sinceMillis: Long, limit: Int = 200): Int {
        val safeLimit = limit.coerceIn(1, REPOSITORY_LIMIT_CAP)
        val now = System.currentTimeMillis()
        val cursor = com.redeye.parentalmonitor.utils.ContentQuery.query(
            context.contentResolver,
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls._ID),
            "${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.DATE} <= ?",
            arrayOf(sinceMillis.toString(), now.toString()),
            "${CallLog.Calls.DATE} DESC, ${CallLog.Calls._ID} DESC",
            safeLimit
        )
        return cursor?.use { it.count } ?: 0
    }

    private fun queryCalls(selection: String?, args: Array<String>?, sortOrder: String, limit: Int = Int.MAX_VALUE, strict: Boolean = false): List<CallData> {
        val result = mutableListOf<CallData>()
        var iterationError: Exception? = null
        try {
            val cursor = com.redeye.parentalmonitor.utils.ContentQuery.query(context.contentResolver, CallLog.Calls.CONTENT_URI, projection, selection, args, sortOrder, limit, strict)
            cursor?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
                val numberIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val dateIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durationIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val typeIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                // Kolom akun bersifat best-effort (issue
                // #1496): penyedia tanpa kolom ini tak boleh
                // menggugurkan seluruh kueri.
                val akunIndex = try { cursor.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID) } catch (_: Exception) { -1 }
                val presIndex = try { cursor.getColumnIndex("presentation") } catch (_: Exception) { -1 }
                while (true) {
                    val advanced = try {
                        cursor.moveToNext()
                    } catch (e: Exception) {
                        android.util.Log.e("CallLogRepository", "Cursor iteration failed mid-scan", e)
                        iterationError = e
                        break
                    }
                    if (!advanced) break
                    if (result.size >= limit) break
                    try {
                        // Nomor tersembunyi (issue #1498):
                        // presentasi 2 (RESTRICTED platform)
                        // atau penanda mentah
                        // OEM (-1/-2) dipetakan ke label jujur,
                        // bukan tampil apa adanya di laporan.
                        val mentah = cursor.getString(numberIndex)
                        val pres = if (presIndex >= 0) try { cursor.getInt(presIndex) } catch (_: Exception) { 1 } else 1
                        val number = when {
                            pres == 2 || mentah == "-1" || mentah == "-2" -> "Nomor tersembunyi"
                            mentah.isNullOrEmpty() -> "Unknown"
                            else -> mentah
                        }
                        result.add(
                            CallData(
                                id = cursor.getLong(idIndex),
                                number = number,
                                name = cursor.getString(nameIndex),
                                date = cursor.getLong(dateIndex),
                                duration = cursor.getInt(durationIndex),
                                type = cursor.getInt(typeIndex),
                                simLabel = if (akunIndex >= 0) labelSim(try { cursor.getString(akunIndex) } catch (_: Exception) { null }) else ""
                            )
                        )
                    } catch (e: Exception) {
                        android.util.Log.e("CallLogRepository", "Error reading call", e)
                    }
                }
            }
        } catch (e: SecurityException) {
            android.util.Log.e("CallLogRepository", "Call log permission denied", e)
            throw e
        } catch (e: Exception) {
            android.util.Log.e("CallLogRepository", "Error querying calls", e)
            if (strict) throw e
        }
        iterationError?.let { throw it }
        return result
    }

    fun getCallsForNumber(digits: String, limit: Int = 50): NumberHistoryResult<CallData> {
        return numberSearch.search(digits, limit)
    }
}
