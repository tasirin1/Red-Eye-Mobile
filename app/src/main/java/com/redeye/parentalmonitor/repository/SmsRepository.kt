package com.redeye.parentalmonitor.repository

import android.content.Context
import android.database.Cursor
import android.provider.Telephony
import com.redeye.parentalmonitor.data.models.SmsData

class SmsRepository(private val context: Context) {

    private val projection = arrayOf(
        Telephony.Sms._ID,
        Telephony.Sms.ADDRESS,
        Telephony.Sms.BODY,
        Telephony.Sms.DATE,
        Telephony.Sms.TYPE
    )

    private val numberSearch = NumberHistorySearch(
        numberColumn = Telephony.Sms.ADDRESS,
        dateColumn = Telephony.Sms.DATE,
        idColumn = Telephony.Sms._ID,
        logTag = "SmsRepository",
        query = { selection, args, sortOrder, limit -> querySms(selection, args, sortOrder, limit, light = true) },
        numberOf = { it.address },
        dateOf = { it.date },
        idOf = { it.id }
    )

    private val lightProjection = arrayOf(
        Telephony.Sms._ID,
        Telephony.Sms.ADDRESS,
        Telephony.Sms.DATE,
        Telephony.Sms.TYPE
    )

    fun getNewSms(afterId: Long, afterDate: Long = 0L): List<SmsData> {
        val selection: String
        val args: Array<String>
        if (afterId > 0L && afterDate > 0L) {
            selection = "(${Telephony.Sms._ID} > ? OR ${Telephony.Sms.DATE} > ?)"
            args = arrayOf(afterId.toString(), afterDate.toString())
        } else if (afterDate > 0L) {
            selection = "${Telephony.Sms.DATE} > ?"
            args = arrayOf(afterDate.toString())
        } else {
            selection = "${Telephony.Sms._ID} > ?"
            args = arrayOf(afterId.toString())
        }
        return querySms(
            selection = selection,
            args = args,
            sortOrder = "${Telephony.Sms.DATE} ASC, ${Telephony.Sms._ID} ASC",
            limit = 100,
            strict = true
        )
    }

    fun smsDateForId(id: Long): Long {
        if (id <= 0L) return 0L
        val cursor = com.redeye.parentalmonitor.utils.ContentQuery.query(
            context.contentResolver,
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.DATE),
            "${Telephony.Sms._ID} = ?",
            arrayOf(id.toString()),
            "${Telephony.Sms._ID} ASC",
            1
        ) ?: return 0L
        return try {
            if (cursor.moveToFirst()) cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)) else 0L
        } catch (_: Exception) {
            0L
        } finally {
            try { cursor.close() } catch (_: Exception) { }
        }
    }

    fun newestSmsId(): Long {
        val cursor = com.redeye.parentalmonitor.utils.ContentQuery.query(
            context.contentResolver,
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms._ID),
            null,
            null,
            "${Telephony.Sms._ID} DESC",
            1
        ) ?: return -1L
        return try {
            if (cursor.moveToFirst()) cursor.getLong(cursor.getColumnIndexOrThrow(Telephony.Sms._ID)) else 0L
        } catch (e: Exception) {
            android.util.Log.e("SmsRepository", "Error reading newest SMS id", e)
            -1L
        } finally {
            try { cursor.close() } catch (_: Exception) { }
        }
    }

    fun getSmsForNumber(digits: String, limit: Int = 50): NumberHistoryResult<SmsData> {
        val result = numberSearch.search(digits, limit)
        if (result.items.isEmpty()) return result
        val bodies = HashMap<Long, String>()
        var hydrationComplete = false
        try {
            val ids = result.items.map { it.id }
            val cursor = com.redeye.parentalmonitor.utils.ContentQuery.query(
                context.contentResolver,
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY),
                "${Telephony.Sms._ID} IN (" + ids.joinToString(",") { "?" } + ")",
                ids.map { it.toString() }.toTypedArray(),
                "${Telephony.Sms._ID} ASC",
                ids.size
            )
            if (cursor == null) {
                android.util.Log.w("SmsRepository", "SMS body hydration query returned no cursor")
            }
            cursor?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val bodyIdx = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                while (c.moveToNext()) {
                    bodies[c.getLong(idIdx)] = c.getString(bodyIdx) ?: "(empty message)"
                }
            }
            if (cursor != null) hydrationComplete = true
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("SmsRepository", "SMS body hydration failed", e)
        }
        return NumberHistoryResult(result.items.map { item ->
            val body = bodies[item.id]
            when {
                body != null -> item.copy(body = body)
                hydrationComplete -> item.copy(body = "(pesan sudah terhapus)")
                else -> item.copy(body = "(isi pesan gagal dimuat)")
            }
        }, result.truncated)
    }

    fun getRecentSmsSince(sinceMillis: Long, limit: Int = 200): List<SmsData> {
        val safeLimit = limit.coerceIn(1, 200)
        return querySms(
            selection = "${Telephony.Sms.DATE} >= ?",
            args = arrayOf(sinceMillis.toString()),
            sortOrder = "${Telephony.Sms.DATE} DESC, ${Telephony.Sms._ID} DESC",
            limit = safeLimit
        )
    }


    fun getRecentSms(limit: Int = 20): List<SmsData> {
        val safeLimit = limit.coerceIn(1, 200)
        return querySms(
            selection = null,
            args = null,
            sortOrder = "${Telephony.Sms.DATE} DESC, ${Telephony.Sms._ID} DESC",
            limit = safeLimit
        )
    }

    private fun querySms(selection: String?, args: Array<String>?, sortOrder: String, limit: Int = Int.MAX_VALUE, strict: Boolean = false, light: Boolean = false): List<SmsData> {
        val result = mutableListOf<SmsData>()
        try {
            val cursor = com.redeye.parentalmonitor.utils.ContentQuery.query(context.contentResolver, Telephony.Sms.CONTENT_URI, if (light) lightProjection else projection, selection, args, sortOrder, limit, strict)
            cursor?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIndex = cursor.getColumnIndex(Telephony.Sms.BODY)
                val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                while (cursor.moveToNext() && result.size < limit) {
                    try {
                        result.add(readSms(cursor, idIndex, addressIndex, bodyIndex, dateIndex, typeIndex))
                    } catch (e: Exception) {
                        android.util.Log.e("SmsRepository", "Skipping corrupted SMS row", e)
                    }
                }
            }
        } catch (e: SecurityException) {
            android.util.Log.e("SmsRepository", "SMS permission denied", e)
            throw e
        } catch (e: Exception) {
            android.util.Log.e("SmsRepository", "Error querying SMS", e)
            if (strict) throw e
        }
        return result
    }

    private fun readSms(
        cursor: Cursor,
        idIndex: Int,
        addressIndex: Int,
        bodyIndex: Int,
        dateIndex: Int,
        typeIndex: Int
    ): SmsData {
        return SmsData(
            id = cursor.getLong(idIndex),
            address = cursor.getString(addressIndex) ?: "Unknown",
            body = if (bodyIndex >= 0) cursor.getString(bodyIndex) ?: "(empty message)" else "",
            date = cursor.getLong(dateIndex),
            type = cursor.getInt(typeIndex)
        )
    }
}
