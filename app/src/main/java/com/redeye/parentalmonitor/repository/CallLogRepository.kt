package com.redeye.parentalmonitor.repository

import android.content.Context
import android.database.Cursor
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
            val cursor = if (limit != Int.MAX_VALUE && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val bundle = android.os.Bundle().apply {
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                    putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
                }
                try {
                    context.contentResolver.query(CallLog.Calls.CONTENT_URI, projection, bundle, null)
                } catch (_: Exception) {
                    try {
                        context.contentResolver.query(CallLog.Calls.CONTENT_URI, projection, selection, args, "$sortOrder LIMIT $limit")
                    } catch (_: Exception) {
                        context.contentResolver.query(CallLog.Calls.CONTENT_URI, projection, selection, args, sortOrder)
                    }
                }
            } else if (limit == Int.MAX_VALUE) {
                context.contentResolver.query(CallLog.Calls.CONTENT_URI, projection, selection, args, sortOrder)
            } else {
                try {
                    context.contentResolver.query(CallLog.Calls.CONTENT_URI, projection, selection, args, "$sortOrder LIMIT $limit")
                } catch (_: Exception) {
                    context.contentResolver.query(CallLog.Calls.CONTENT_URI, projection, selection, args, sortOrder)
                }
            }
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
        val escaped = digits.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val rows = queryCalls(
            selection = "${CallLog.Calls.NUMBER} LIKE ? ESCAPE '\\'",
            args = arrayOf("%$escaped%"),
            sortOrder = "${CallLog.Calls.DATE} DESC, ${CallLog.Calls._ID} DESC",
            limit = limit
        )
        val want = digits.filter { it.isDigit() }
        if (want.length < 7) return rows
        return rows.filter {
            val have = it.number.filter { c -> c.isDigit() }
            have.isNotEmpty() && (have == want || have.endsWith(want) || want.endsWith(have))
        }
    }

}
