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

    fun getNewSms(afterId: Long, afterDate: Long = 0L): List<SmsData> {
        val selection: String
        val args: Array<String>
        if (afterDate > 0L) {
            selection = "(${Telephony.Sms._ID} > ? OR ${Telephony.Sms.DATE} > ?)"
            args = arrayOf(afterId.toString(), afterDate.toString())
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

    fun getSmsForNumber(digits: String, limit: Int = 50): List<SmsData> {
        val safeLimit = limit.coerceIn(1, 100)
        val norm = digits.filter { it.isDigit() }
        if (norm.isEmpty()) return emptyList()
        val wantAlt = com.redeye.parentalmonitor.utils.PhoneNumbers.altVariant(norm)
        val variants = mutableListOf(norm, "+$norm")
        if (wantAlt != null) {
            if (wantAlt !in variants) variants.add(wantAlt)
            val altPlus = "+$wantAlt"
            if (altPlus !in variants) variants.add(altPlus)
        }
        val exactRows = querySms(
            selection = variants.joinToString(" OR ") { "${Telephony.Sms.ADDRESS} = ?" },
            args = variants.toTypedArray(),
            sortOrder = "${Telephony.Sms.DATE} DESC, ${Telephony.Sms._ID} DESC",
            limit = safeLimit
        )
        val exactMatched = filterByNumber(exactRows, norm, wantAlt) { it.address }
        if (exactMatched.isNotEmpty()) return exactMatched.take(safeLimit)
        val fuzzy = buildFuzzy(norm)
        val rows = querySms(
            selection = "${Telephony.Sms.ADDRESS} LIKE ? ESCAPE '\\'",
            args = arrayOf(fuzzy),
            sortOrder = "${Telephony.Sms.DATE} DESC, ${Telephony.Sms._ID} DESC",
            limit = safeLimit
        )
        val matched = filterByNumber(rows, norm, wantAlt) { it.address }
        if (matched.isNotEmpty() || norm.length < 10) return matched.take(safeLimit)
        val cutoff = System.currentTimeMillis() - 365L * 24 * 60 * 60_000L
        var upperDate = Long.MAX_VALUE
        var upperId = Long.MAX_VALUE
        repeat(4) {
            val page = querySms(
                selection = "${Telephony.Sms.DATE} >= ? AND (${Telephony.Sms.DATE} < ? OR (${Telephony.Sms.DATE} = ? AND ${Telephony.Sms._ID} < ?))",
                args = arrayOf(cutoff.toString(), upperDate.toString(), upperDate.toString(), upperId.toString()),
                sortOrder = "${Telephony.Sms.DATE} DESC, ${Telephony.Sms._ID} DESC",
                limit = 500
            )
            if (page.isEmpty()) return emptyList()
            val pageMatched = filterByNumber(page, norm, wantAlt) { it.address }
            if (pageMatched.isNotEmpty()) return pageMatched.take(safeLimit)
            if (page.size < 500) return emptyList()
            val oldest = page.last()
            if (oldest.date <= cutoff) return emptyList()
            if (oldest.date > upperDate || (oldest.date == upperDate && oldest.id >= upperId)) return emptyList()
            upperDate = oldest.date
            upperId = oldest.id
        }
        android.util.Log.w("SmsRepository", "paged scan truncated")
        return emptyList()
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

    private fun filterByNumber(rows: List<SmsData>, want: String, wantAlt: String?, pick: (SmsData) -> String): List<SmsData> {
        return rows.filter { com.redeye.parentalmonitor.utils.PhoneNumbers.matchesPrecomputed(pick(it), want, wantAlt) }
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

    private fun buildFuzzy(norm: String): String {
        val out = StringBuilder(norm.length * 2 + 2)
        out.append('%')
        for (c in norm) {
            out.append(c).append('%')
        }
        return out.toString()
    }

    private fun querySms(selection: String?, args: Array<String>?, sortOrder: String, limit: Int = Int.MAX_VALUE, strict: Boolean = false): List<SmsData> {
        val result = mutableListOf<SmsData>()
        try {
            val cursor = com.redeye.parentalmonitor.utils.ContentQuery.query(context.contentResolver, Telephony.Sms.CONTENT_URI, projection, selection, args, sortOrder, limit)
            cursor?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
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
            body = cursor.getString(bodyIndex) ?: "(empty message)",
            date = cursor.getLong(dateIndex),
            type = cursor.getInt(typeIndex)
        )
    }
}
