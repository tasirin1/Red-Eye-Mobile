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

    fun getNewCalls(afterTimestamp: Long, afterId: Long = 0L): List<CallData> {
        if (afterId > 0L && afterTimestamp > 0L) {
            return queryCalls(
                selection = "(${CallLog.Calls._ID} > ? OR ${CallLog.Calls.DATE} > ?)",
                args = arrayOf(afterId.toString(), afterTimestamp.toString()),
                sortOrder = "${CallLog.Calls.DATE} ASC, ${CallLog.Calls._ID} ASC",
                limit = 100,
                strict = true
            )
        }
        if (afterId > 0L) {
            return queryCalls(
                selection = "${CallLog.Calls._ID} > ?",
                args = arrayOf(afterId.toString()),
                sortOrder = "${CallLog.Calls._ID} ASC",
                limit = 100,
                strict = true
            )
        }
        return queryCalls(
            selection = "${CallLog.Calls.DATE} > ?",
            args = arrayOf(afterTimestamp.toString()),
            sortOrder = "${CallLog.Calls.DATE} ASC, ${CallLog.Calls._ID} ASC",
            limit = 100,
            strict = true
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
            if (strict) throw e
        }
        iterationError?.let { throw it }
        return result
    }

    fun getCallsForNumber(digits: String, limit: Int = 50): NumberHistoryResult<CallData> {
        return numberSearch.search(digits, limit)
    }
}
