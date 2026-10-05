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
            sortOrder = "${CallLog.Calls.DATE} DESC",
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
        val norm = digits.filter { it.isDigit() }
        if (norm.isEmpty()) return emptyList()
        val escaped = norm.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val rows = queryCalls(
            selection = "${CallLog.Calls.NUMBER} LIKE ? ESCAPE '\\'",
            args = arrayOf("%$escaped%"),
            sortOrder = "${CallLog.Calls.DATE} DESC, ${CallLog.Calls._ID} DESC",
            limit = limit
        )
        val matched = filterCallsByNumber(rows, norm)
        if (matched.isNotEmpty() || norm.length < 7) return matched
        val cutoff = System.currentTimeMillis() - 90L * 24 * 60 * 60_000L
        return filterCallsByNumber(getAllCalls(200).filter { it.date >= cutoff }, norm)
    }

    private fun filterCallsByNumber(rows: List<CallData>, want: String): List<CallData> {
        return rows.filter { com.redeye.parentalmonitor.utils.PhoneNumbers.matches(it.number, want) }
    }

}
