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
        return filterByNumber(getRecentSms(200), digits) { it.address }
    }

    private fun altVariant(digits: String): String? {
        if (digits.isEmpty()) return null
        if (digits.startsWith("628") && digits.length in 10..15) return "0" + digits.substring(2)
        if (digits.startsWith("08") && digits.length in 10..14) return "62" + digits.substring(1)
        return null
    }

    private fun numbersEqual(have: String, want: String): Boolean {
        return numbersEqualFast(have, want, altVariant(want))
    }

    private fun numbersEqualFast(have: String, want: String, wantAlt: String?): Boolean {
        if (have == want) return true
        if (wantAlt != null && have == wantAlt) return true
        val haveAlt = altVariant(have)
        if (haveAlt != null) {
            if (haveAlt == want) return true
            if (wantAlt != null && haveAlt == wantAlt) return true
        }
        if (want.length < 9) return false
        if (have.endsWith(want) || want.endsWith(have)) return true
        if (wantAlt != null && (have.endsWith(wantAlt) || wantAlt.endsWith(have))) return true
        if (haveAlt != null) {
            if (haveAlt.endsWith(want) || want.endsWith(haveAlt)) return true
            if (wantAlt != null && (haveAlt.endsWith(wantAlt) || wantAlt.endsWith(haveAlt))) return true
        }
        return false
    }

    private fun filterByNumber(rows: List<SmsData>, digits: String, pick: (SmsData) -> String): List<SmsData> {
        val want = digits.filter { it.isDigit() }
        if (want.isEmpty()) return emptyList()
        if (want.length < 7) return rows.filter { pick(it).filter { c -> c.isDigit() } == want }
        val wantAlt = altVariant(want)
        return rows.filter {
            val have = pick(it).filter { c -> c.isDigit() }
            have.isNotEmpty() && numbersEqualFast(have, want, wantAlt)
        }
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
            val cursor = if (limit != Int.MAX_VALUE && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val bundle = android.os.Bundle().apply {
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                    putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, limit)
                }
                try {
                    context.contentResolver.query(Telephony.Sms.CONTENT_URI, projection, bundle, null)
                } catch (_: Exception) {
                    try {
                        context.contentResolver.query(Telephony.Sms.CONTENT_URI, projection, selection, args, "$sortOrder LIMIT $limit")
                    } catch (_: Exception) {
                        try {
                            val limitedUri = Telephony.Sms.CONTENT_URI.buildUpon().appendQueryParameter("limit", limit.toString()).build()
                            context.contentResolver.query(limitedUri, projection, selection, args, sortOrder)
                        } catch (_: Exception) {
                            context.contentResolver.query(Telephony.Sms.CONTENT_URI, projection, selection, args, sortOrder)
                        }
                    }
                }
            } else if (limit == Int.MAX_VALUE) {
                context.contentResolver.query(Telephony.Sms.CONTENT_URI, projection, selection, args, sortOrder)
            } else {
                try {
                    context.contentResolver.query(Telephony.Sms.CONTENT_URI, projection, selection, args, "$sortOrder LIMIT $limit")
                } catch (_: Exception) {
                    try {
                        val limitedUri = Telephony.Sms.CONTENT_URI.buildUpon().appendQueryParameter("limit", limit.toString()).build()
                        context.contentResolver.query(limitedUri, projection, selection, args, sortOrder)
                    } catch (_: Exception) {
                        context.contentResolver.query(Telephony.Sms.CONTENT_URI, projection, selection, args, sortOrder)
                    }
                }
            }
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
