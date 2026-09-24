package com.redeye.parentalmonitor.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.redeye.parentalmonitor.data.models.QueuedMessage

class MessageQueue(context: Context) {

    private val sharedPreferences = try {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "encrypted_queue",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        android.util.Log.w("MessageQueue", "Encrypted queue unavailable, using plaintext fallback", e)
        context.applicationContext.getSharedPreferences("message_queue", Context.MODE_PRIVATE)
    }
    private val gson = Gson()

    companion object {
        private const val KEY_QUEUE = "queued_messages"
        private const val MAX_QUEUE_SIZE = 100
    }

    fun addMessage(message: String) {
        val queue = getQueue().toMutableList()
        queue.add(QueuedMessage(message = message))
        
        // Keep only latest messages if queue is too large
        if (queue.size > MAX_QUEUE_SIZE) {
            queue.removeAt(0)
        }
        
        saveQueue(queue)
    }

    fun getQueue(): List<QueuedMessage> {
        val json = sharedPreferences.getString(KEY_QUEUE, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<QueuedMessage>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun removeMessage(messageId: Long) {
        val queue = getQueue().toMutableList()
        queue.removeAll { it.id == messageId }
        saveQueue(queue)
    }

    fun updateRetryCount(messageId: Long) {
        val queue = getQueue().toMutableList()
        val index = queue.indexOfFirst { it.id == messageId }
        if (index >= 0) {
            val message = queue[index]
            queue[index] = message.copy(retryCount = message.retryCount + 1)
            saveQueue(queue)
        }
    }

    fun clearQueue() {
        sharedPreferences.edit().remove(KEY_QUEUE).apply()
    }

    private fun saveQueue(queue: List<QueuedMessage>) {
        val json = gson.toJson(queue)
        sharedPreferences.edit().putString(KEY_QUEUE, json).apply()
    }

    fun hasMessages(): Boolean {
        return getQueue().isNotEmpty()
    }

    fun getQueueSize(): Int {
        return getQueue().size
    }
}

