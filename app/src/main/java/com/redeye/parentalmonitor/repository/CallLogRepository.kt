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
        return queryCalls(
            selection = "${CallLog.Calls.DATE} > ? OR (${CallLog.Calls.DATE} = ? AND ${CallLog.Calls._ID} > ?)",
            args = arrayOf(afterTimestamp.toString(), afterTimestamp.toString(), afterId.toString()),
            sortOrder = "${CallLog.Calls.DATE} ASC, ${CallLog.Calls._ID} ASC",
            limit = 100
        )
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
        return queryCalls(
            selection = "${CallLog.Calls.DATE} >= ?",
            args = arrayOf(sinceMillis.toString()),
            sortOrder = "${CallLog.Calls.DATE} DESC, ${CallLog.Calls._ID} DESC",
            limit = limit
        )
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
        } catch (e: Exception) {
            android.util.Log.e("CallLogRepository", "Error querying calls", e)
        }
        return result
    }

    fun getCallsForNumber(digits: String, limit: Int = 50): List<CallData> {
        val safeLimit = limit.coerceIn(1, 100)
        val norm = digits.filter { it.isDigit() }
        if (norm.isEmpty()) return emptyList()
        val fuzzy = "%" + norm.toCharArray().joinToString("%") + "%"
        val rows = queryCalls(
            selection = "${CallLog.Calls.NUMBER} LIKE ? ESCAPE '\\'",
            args = arrayOf(fuzzy),
            sortOrder = "${CallLog.Calls.DATE} DESC, ${CallLog.Calls._ID} DESC",
            limit = safeLimit
        )
        val matched = filterCallsByNumber(rows, norm)
        if (matched.isNotEmpty() || norm.length < 7) return matched.take(safeLimit)
        val cutoff = System.currentTimeMillis() - 365L * 24 * 60 * 60_000L
        var upperDate = Long.MAX_VALUE
        var upperId = Long.MAX_VALUE
        repeat(10) {
            val page = queryCalls(
                selection = "${CallLog.Calls.DATE} >= ? AND (${CallLog.Calls.DATE} < ? OR (${CallLog.Calls.DATE} = ? AND ${CallLog.Calls._ID} < ?))",
                args = arrayOf(cutoff.toString(), upperDate.toString(), upperDate.toString(), upperId.toString()),
                sortOrder = "${CallLog.Calls.DATE} DESC, ${CallLog.Calls._ID} DESC",
                limit = 500
            )
            if (page.isEmpty()) return emptyList()
            val pageMatched = filterCallsByNumber(page, norm)
            if (pageMatched.isNotEmpty()) return pageMatched.take(safeLimit)
            if (page.size < 500) return emptyList()
            val oldest = page.minWithOrNull(compareBy({ it.date }, { it.id })) ?: return emptyList()
            if (oldest.date <= cutoff) return emptyList()
            if (oldest.date > upperDate || (oldest.date == upperDate && oldest.id >= upperId)) return emptyList()
            upperDate = oldest.date
            upperId = oldest.id
        }
        return emptyList()
    }

    private fun filterCallsByNumber(rows: List<CallData>, want: String): List<CallData> {
        return rows.filter { com.redeye.parentalmonitor.utils.PhoneNumbers.matches(it.number, want) }
    }

}
