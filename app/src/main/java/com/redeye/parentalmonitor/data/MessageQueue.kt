@file:Suppress("DEPRECATION")

package com.redeye.parentalmonitor.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.redeye.parentalmonitor.data.models.QueuedMessage

class MessageQueue(context: Context) {

    private val appContext = context.applicationContext

    private var volatileOnly = false
    private val volatileQueue = mutableListOf<QueuedMessage>()
    private val sharedPreferences: SharedPreferences? = try {
        val masterKey = PreferencesManager.getMasterKey(appContext)
        EncryptedSharedPreferences.create(
            appContext,
            "encrypted_queue",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        android.util.Log.w("MessageQueue", "Encrypted queue unavailable, using volatile memory", e)
        try { appContext.deleteSharedPreferences("message_queue") } catch (_: Exception) { }
        volatileOnly = true
        null
    }
    private val lock = Any()
    private var cached: MutableList<QueuedMessage>? = null
    private val listType = object : TypeToken<MutableList<QueuedMessage>>() {}.type

    companion object {
        private const val KEY_QUEUE = "queued_messages"
        private const val MAX_QUEUE_SIZE = 100
        const val MAX_RETRIES = 5
        private val gson = Gson()
    }

    fun addMessage(message: String) {
        synchronized(lock) {
            if (volatileOnly) {
                volatileQueue.add(QueuedMessage(message = message))
                while (volatileQueue.size > MAX_QUEUE_SIZE) volatileQueue.removeAt(0)
                return
            }
            val queue = readLocked()
            queue.add(QueuedMessage(message = message))
            while (queue.size > MAX_QUEUE_SIZE) {
                queue.removeAt(0)
                android.util.Log.w("MessageQueue", "Queue full, dropped oldest message")
            }
            persistLocked(queue)
        }
    }

    fun getQueue(): List<QueuedMessage> {
        synchronized(lock) {
            if (volatileOnly) return volatileQueue.toList()
            return readLocked().toList()
        }
    }

    fun removeMessage(messageId: String) {
        synchronized(lock) {
            if (volatileOnly) {
                volatileQueue.removeAll { it.id == messageId }
                return
            }
            val queue = readLocked().toMutableList()
            queue.removeAll { it.id == messageId }
            writeLocked(queue)
        }
    }

    fun removeMessages(messageIds: Collection<String>) {
        if (messageIds.isEmpty()) return
        synchronized(lock) {
            if (volatileOnly) {
                volatileQueue.removeAll { it.id in messageIds }
                return
            }
            val queue = readLocked()
            queue.removeAll { it.id in messageIds }
            persistLocked(queue)
        }
    }

    fun registerFailures(messageIds: Collection<String>): List<String> {
        if (messageIds.isEmpty()) return emptyList()
        synchronized(lock) {
            if (volatileOnly) {
                val drop = mutableListOf<String>()
                for (i in volatileQueue.indices) {
                    val queued = volatileQueue[i]
                    if (queued.id in messageIds) {
                        val updated = queued.copy(retryCount = queued.retryCount + 1)
                        volatileQueue[i] = updated
                        if (updated.retryCount >= MAX_RETRIES) drop.add(updated.id)
                    }
                }
                volatileQueue.removeAll { it.id in drop }
                return drop
            }
            val queue = readLocked()
            val drop = mutableListOf<String>()
            for (i in queue.indices) {
                val queued = queue[i]
                if (queued.id in messageIds) {
                    val updated = queued.copy(retryCount = queued.retryCount + 1)
                    queue[i] = updated
                    if (updated.retryCount >= MAX_RETRIES) drop.add(updated.id)
                }
            }
            queue.removeAll { it.id in drop }
            persistLocked(queue)
            return drop
        }
    }

    fun incrementRetry(messageId: String): Int {
        synchronized(lock) {
            if (volatileOnly) {
                val index = volatileQueue.indexOfFirst { it.id == messageId }
                if (index < 0) return -1
                val updated = volatileQueue[index].copy(retryCount = volatileQueue[index].retryCount + 1)
                volatileQueue[index] = updated
                return updated.retryCount
            }
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
            volatileQueue.clear()
            sharedPreferences?.edit()?.remove(KEY_QUEUE)?.apply()
        }
    }

    private fun readLocked(): MutableList<QueuedMessage> {
        cached?.let { return it }
        val json = sharedPreferences?.getString(KEY_QUEUE, null)
        val loaded: MutableList<QueuedMessage> = try {
            if (json == null) mutableListOf()
            else {
                gson.fromJson<MutableList<QueuedMessage>>(json, listType) ?: mutableListOf()
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
        sharedPreferences?.edit()?.putString(KEY_QUEUE, gson.toJson(queue))?.apply()
    }

    private fun persistLocked(queue: MutableList<QueuedMessage>) {
        cached = queue
        sharedPreferences?.edit()?.putString(KEY_QUEUE, gson.toJson(queue))?.apply()
    }

    fun hasMessages(): Boolean {
        synchronized(lock) {
            if (volatileOnly) return volatileQueue.isNotEmpty()
            return readLocked().isNotEmpty()
        }
    }
    fun getQueueSize(): Int {
        synchronized(lock) {
            if (volatileOnly) return volatileQueue.size
            return readLocked().size
        }
    }
}
