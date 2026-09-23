@file:Suppress("DEPRECATION")

package com.redeye.parentalmonitor.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.redeye.parentalmonitor.data.models.QueuedMessage

class MessageQueue(context: Context) {

    private val sharedPreferences: SharedPreferences = try {
        val masterKey = PreferencesManager.getMasterKey(context)
        EncryptedSharedPreferences.create(
            context,
            "encrypted_queue",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        android.util.Log.w("MessageQueue", "Encrypted queue unavailable, using plaintext fallback", e)
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
                android.util.Log.w("MessageQueue", "Queue full, dropped oldest message")
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
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60_000L
        val fresh = loaded.filter { it.timestamp >= cutoff }.toMutableList()
        if (fresh.size != loaded.size) {
            android.util.Log.w("MessageQueue", "Dropped ${loaded.size - fresh.size} expired message(s)")
            writeLocked(fresh)
            return fresh
        }
        cached = loaded
        return loaded
    }

    private fun writeLocked(queue: List<QueuedMessage>) {
        cached = queue.toMutableList()
        sharedPreferences.edit().putString(KEY_QUEUE, gson.toJson(queue)).apply()
    }

    fun hasMessages(): Boolean {
        synchronized(lock) {
            return readLocked().isNotEmpty()
        }
    }
    fun getQueueSize(): Int {
        synchronized(lock) {
            return readLocked().size
        }
    }
}
