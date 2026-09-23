package com.redeye.parentalmonitor.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.redeye.parentalmonitor.data.models.QueuedMessage

class MessageQueue(context: Context) {

    private val sharedPreferences: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "encrypted_queue",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences("message_queue", Context.MODE_PRIVATE)
    }
    private val gson = Gson()
    private val lock = Any()
    private var cached: MutableList<QueuedMessage>? = null

    companion object {
        private const val KEY_QUEUE = "queued_messages"
        private const val MAX_QUEUE_SIZE = 100
        const val MAX_RETRIES = 5
    }

    fun addMessage(message: String) {
        synchronized(lock) {
            val queue = readLocked().toMutableList()
            queue.add(QueuedMessage(message = message))
            while (queue.size > MAX_QUEUE_SIZE) {
                queue.removeAt(0)
            }
            writeLocked(queue)
        }
    }

    fun getQueue(): List<QueuedMessage> {
        synchronized(lock) {
            return readLocked().toList()
        }
    }

    fun removeMessage(messageId: String) {
        synchronized(lock) {
            val queue = readLocked().toMutableList()
            queue.removeAll { it.id == messageId }
            writeLocked(queue)
        }
    }

    fun incrementRetry(messageId: String): Int {
        synchronized(lock) {
            val queue = readLocked().toMutableList()
            val index = queue.indexOfFirst { it.id == messageId }
            if (index < 0) return -1
            val updated = queue[index].copy(retryCount = queue[index].retryCount + 1)
            queue[index] = updated
            writeLocked(queue)
            return updated.retryCount
        }
    }

    fun clearQueue() {
        synchronized(lock) {
            cached = mutableListOf()
            sharedPreferences.edit().remove(KEY_QUEUE).apply()
        }
    }

    private fun readLocked(): MutableList<QueuedMessage> {
        cached?.let { return it }
        val json = sharedPreferences.getString(KEY_QUEUE, null)
        val loaded: MutableList<QueuedMessage> = try {
            if (json == null) mutableListOf()
            else {
                val type = object : TypeToken<MutableList<QueuedMessage>>() {}.type
                gson.fromJson<MutableList<QueuedMessage>>(json, type) ?: mutableListOf()
            }
        } catch (e: Exception) {
            mutableListOf()
        }
        cached = loaded
        return loaded
    }

    private fun writeLocked(queue: List<QueuedMessage>) {
        cached = queue.toMutableList()
        sharedPreferences.edit().putString(KEY_QUEUE, gson.toJson(queue)).apply()
    }

    fun hasMessages(): Boolean = getQueue().isNotEmpty()
    fun getQueueSize(): Int = getQueue().size
}
