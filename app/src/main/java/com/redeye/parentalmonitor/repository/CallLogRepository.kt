package com.redeye.parentalmonitor.repository

import android.content.Context
import android.database.Cursor
import android.provider.CallLog
import android.provider.ContactsContract
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
    private val contactCache = object : LinkedHashMap<String, String?>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>): Boolean {
            return size > 500
        }
    }

    fun getNewCalls(afterTimestamp: Long, afterId: Long = 0L): List<CallData> {
        return queryCalls(
            selection = "${CallLog.Calls.DATE} > ? OR (${CallLog.Calls.DATE} = ? AND ${CallLog.Calls._ID} > ?)",
            args = arrayOf(afterTimestamp.toString(), afterTimestamp.toString(), afterId.toString()),
            sortOrder = "${CallLog.Calls.DATE} DESC, ${CallLog.Calls._ID} DESC",
            limit = 500
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
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                args,
                sortOrder
            )?.use { cursor ->
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
                                name = cursor.getString(nameIndex) ?: lookupContact(number),
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

    private fun lookupContact(phoneNumber: String): String? {
        synchronized(contactCache) {
            if (contactCache.containsKey(phoneNumber)) return contactCache[phoneNumber]
        }
        var name: String? = null
        try {
            val uri = ContactsContract.PhoneLookup.CONTENT_FILTER_URI.buildUpon()
                .appendPath(phoneNumber)
                .build()
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CallLogRepository", "Error looking up contact", e)
        }
        synchronized(contactCache) {
            contactCache[phoneNumber] = name
        }
        return name
    }
}
