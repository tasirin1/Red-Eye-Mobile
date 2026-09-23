package com.redeye.parentalmonitor.repository

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import com.redeye.parentalmonitor.data.models.SmsData

class SmsRepository(private val context: Context) {

    private val projection = arrayOf(
        Telephony.Sms._ID,
        Telephony.Sms.ADDRESS,
        Telephony.Sms.BODY,
        Telephony.Sms.DATE,
        Telephony.Sms.TYPE,
        Telephony.Sms.READ
    )

    fun getNewSms(afterId: Long): List<SmsData> {
        return querySms(
            selection = "${Telephony.Sms._ID} > ?",
            args = arrayOf(afterId.toString()),
            sortOrder = "${Telephony.Sms.DATE} DESC"
        )
    }

    fun getRecentSms(limit: Int = 20): List<SmsData> {
        return querySms(
            selection = null,
            args = null,
            sortOrder = "${Telephony.Sms.DATE} DESC LIMIT $limit"
        )
    }

    private fun querySms(selection: String?, args: Array<String>?, sortOrder: String): List<SmsData> {
        val result = mutableListOf<SmsData>()
        try {
            context.contentResolver.query(
                Uri.parse("content://sms"),
                projection,
                selection,
                args,
                sortOrder
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                val readIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)
                while (cursor.moveToNext()) {
                    try {
                        result.add(readSms(cursor, idIndex, addressIndex, bodyIndex, dateIndex, typeIndex, readIndex))
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
        typeIndex: Int,
        readIndex: Int
    ): SmsData {
        return SmsData(
            id = cursor.getLong(idIndex),
            address = cursor.getString(addressIndex) ?: "Unknown",
            body = cursor.getString(bodyIndex) ?: "(empty message)",
            date = cursor.getLong(dateIndex),
            type = cursor.getInt(typeIndex),
            read = cursor.getInt(readIndex) == 1
        )
    }
}
