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
        CallLog.Calls.TYPE
    )

    fun getNewCalls(afterTimestamp: Long, afterId: Long = 0L): List<CallData> {
        if (afterId > 0L) {
            return queryCalls(
                selection = "${CallLog.Calls._ID} > ?",
                args = arrayOf(afterId.toString()),
                sortOrder = "${CallLog.Calls._ID} ASC",
                limit = 100
            )
        }
        return queryCalls(
            selection = "${CallLog.Calls.DATE} > ?",
            args = arrayOf(afterTimestamp.toString()),
            sortOrder = "${CallLog.Calls.DATE} ASC, ${CallLog.Calls._ID} ASC",
            limit = 100
        )
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
        return queryCalls(
            selection = null,
            args = null,
            sortOrder = "${CallLog.Calls.DATE} DESC, ${CallLog.Calls._ID} DESC",
            limit = limit.coerceIn(1, 200)
        )
    }

    fun getCallsSince(sinceMillis: Long, limit: Int = 200): List<CallData> {
        val safeLimit = limit.coerceIn(1, 200)
        return queryCalls(
            selection = "${CallLog.Calls.DATE} >= ?",
            args = arrayOf(sinceMillis.toString()),
            sortOrder = "${CallLog.Calls.DATE} DESC, ${CallLog.Calls._ID} DESC",
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

    private fun queryCalls(selection: String?, args: Array<String>?, sortOrder: String, limit: Int = Int.MAX_VALUE): List<CallData> {
        val result = mutableListOf<CallData>()
        try {
            val cursor = com.redeye.parentalmonitor.utils.ContentQuery.query(context.contentResolver, CallLog.Calls.CONTENT_URI, projection, selection, args, sortOrder, limit)
            cursor?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(CallLog.Calls._ID)
                val numberIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                val nameIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                val dateIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                val durationIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                val typeIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                while (cursor.moveToNext() && result.size < limit) {
                    try {
                        val number = cursor.getString(numberIndex) ?: "Unknown"
                        result.add(
                            CallData(
                                id = cursor.getLong(idIndex),
                                number = number,
                                name = cursor.getString(nameIndex),
                                date = cursor.getLong(dateIndex),
                                duration = cursor.getInt(durationIndex),
                                type = cursor.getInt(typeIndex)
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
        }
        return result
    }

    fun getCallsForNumber(digits: String, limit: Int = 50): List<CallData> {
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
        val exactRows = queryCalls(
            selection = variants.joinToString(" OR ") { "${CallLog.Calls.NUMBER} = ?" },
            args = variants.toTypedArray(),
            sortOrder = "${CallLog.Calls.DATE} DESC, ${CallLog.Calls._ID} DESC",
            limit = safeLimit
        )
        val exactMatched = filterCallsByNumber(exactRows, norm, wantAlt)
        if (exactMatched.isNotEmpty()) return exactMatched.take(safeLimit)
        val fuzzy = buildFuzzy(norm)
        val rows = queryCalls(
            selection = "${CallLog.Calls.NUMBER} LIKE ? ESCAPE '\\'",
            args = arrayOf(fuzzy),
            sortOrder = "${CallLog.Calls.DATE} DESC, ${CallLog.Calls._ID} DESC",
            limit = safeLimit
        )
        val matched = filterCallsByNumber(rows, norm, wantAlt)
        if (matched.isNotEmpty() || norm.length < 10) return matched.take(safeLimit)
        val cutoff = System.currentTimeMillis() - 365L * 24 * 60 * 60_000L
        var upperDate = Long.MAX_VALUE
        var upperId = Long.MAX_VALUE
        repeat(4) {
            val page = queryCalls(
                selection = "${CallLog.Calls.DATE} >= ? AND (${CallLog.Calls.DATE} < ? OR (${CallLog.Calls.DATE} = ? AND ${CallLog.Calls._ID} < ?))",
                args = arrayOf(cutoff.toString(), upperDate.toString(), upperDate.toString(), upperId.toString()),
                sortOrder = "${CallLog.Calls.DATE} DESC, ${CallLog.Calls._ID} DESC",
                limit = 500
            )
            if (page.isEmpty()) return emptyList()
            val pageMatched = filterCallsByNumber(page, norm, wantAlt)
            if (pageMatched.isNotEmpty()) return pageMatched.take(safeLimit)
            if (page.size < 500) return emptyList()
            val oldest = page.last()
            if (oldest.date <= cutoff) return emptyList()
            if (oldest.date > upperDate || (oldest.date == upperDate && oldest.id >= upperId)) return emptyList()
            upperDate = oldest.date
            upperId = oldest.id
        }
        android.util.Log.w("CallLogRepository", "paged scan truncated")
        return emptyList()
    }

    private fun filterCallsByNumber(rows: List<CallData>, want: String, wantAlt: String?): List<CallData> {
        return rows.filter { com.redeye.parentalmonitor.utils.PhoneNumbers.matchesPrecomputed(it.number, want, wantAlt) }
    }

}
