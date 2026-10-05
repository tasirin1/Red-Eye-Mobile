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

    fun getNewSms(afterId: Long): List<SmsData> {
        return querySms(
            selection = "${Telephony.Sms._ID} > ?",
            args = arrayOf(afterId.toString()),
            sortOrder = "${Telephony.Sms._ID} ASC",
            limit = 100
        )
    }

    fun getSmsForNumber(digits: String, limit: Int = 50): List<SmsData> {
        val norm = digits.filter { it.isDigit() }
        if (norm.isEmpty()) return emptyList()
        val escaped = norm.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val rows = querySms(
            selection = "${Telephony.Sms.ADDRESS} LIKE ? ESCAPE '\\'",
            args = arrayOf("%$escaped%"),
            sortOrder = "${Telephony.Sms.DATE} DESC",
            limit = limit
        )
        val matched = filterByNumber(rows, digits) { it.address }
        if (matched.isNotEmpty() || norm.length < 7) return matched
        val cutoff = System.currentTimeMillis() - 365L * 24 * 60 * 60_000L
        return filterByNumber(getRecentSmsSince(cutoff, 500), digits) { it.address }
    }

    fun getRecentSmsSince(sinceMillis: Long, limit: Int = 200): List<SmsData> {
        return querySms(
            selection = "${Telephony.Sms.DATE} >= ?",
            args = arrayOf(sinceMillis.toString()),
            sortOrder = "${Telephony.Sms.DATE} DESC",
            limit = limit
        )
    }

    private fun filterByNumber(rows: List<SmsData>, digits: String, pick: (SmsData) -> String): List<SmsData> {
        return rows.filter { com.redeye.parentalmonitor.utils.PhoneNumbers.matches(pick(it), digits) }
    }

    fun getRecentSms(limit: Int = 20): List<SmsData> {
        return querySms(
            selection = null,
            args = null,
            sortOrder = "${Telephony.Sms.DATE} DESC",
            limit = limit
        )
    }

    private fun querySms(selection: String?, args: Array<String>?, sortOrder: String, limit: Int = Int.MAX_VALUE): List<SmsData> {
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
        } catch (e: Exception) {
            android.util.Log.e("SmsRepository", "Error querying SMS", e)
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
