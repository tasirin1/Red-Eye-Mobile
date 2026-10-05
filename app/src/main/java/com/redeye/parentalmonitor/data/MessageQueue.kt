@file:Suppress("DEPRECATION")

package com.redeye.parentalmonitor.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.redeye.parentalmonitor.data.models.QueuedMessage

class MessageQueue private constructor(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    private var volatileOnly = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
    private val volatileQueue = mutableListOf<QueuedMessage>()
    @Volatile
    private var sharedPreferences: SharedPreferences? = null

    init {
        if (volatileOnly) {
            try {
                Thread {
                    try {
                        tryRestorePersistent()
                    } catch (_: Exception) {
                    }
                }.start()
            } catch (_: Exception) {
            }
        } else {
            try {
                sharedPreferences = PreferencesManager.openEncryptedPrefs(appContext, QUEUE_PREFS_NAME)
                volatileOnly = false
            } catch (e: Exception) {
                android.util.Log.w("MessageQueue", "Encrypted queue unavailable, using volatile memory", e)
                volatileOnly = true
                sharedPreferences = null
            }
        }
    }
    private val lock = Any()
    private var cached: MutableList<QueuedMessage>? = null
    private val arrayType = Array<QueuedMessage>::class.java

    companion object {
        private const val QUEUE_PREFS_NAME = "encrypted_queue"
        private const val KEY_QUEUE = "queued_messages"
        private const val KEY_OVERFLOW_DROPS = "overflow_drops"
        private const val KEY_EXPIRED_DROPS = "expired_drops"
        private const val MAX_QUEUE_SIZE = 100
        const val MAX_RETRIES = 5
        const val MAX_TRANSIENT_RETRIES = 20
        private val gson = Gson()
        private val overflowDrops = java.util.concurrent.atomic.AtomicLong(0L)
        private val expiredDrops = java.util.concurrent.atomic.AtomicLong(0L)


        @Volatile
        private var instance: MessageQueue? = null

        fun getInstance(context: Context): MessageQueue {
            return instance ?: synchronized(this) {
                instance ?: MessageQueue(context.applicationContext).also { instance = it }
            }
        }
    }

    fun tryRestorePersistent(): Boolean {
        synchronized(lock) {
            if (!volatileOnly) return true
            return try {
                val restored = PreferencesManager.openEncryptedPrefs(appContext, QUEUE_PREFS_NAME)
                val pending = volatileQueue.toList()
                volatileQueue.clear()
                sharedPreferences = restored
                volatileOnly = false
                cached = null
                loadDropCountsLocked()
                if (pending.isNotEmpty()) {
                    val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60_000L
                    val freshPending = pending.filter { it.timestamp >= cutoff }
                    val expired = pending.size - freshPending.size
                    if (expired > 0) {
                        noteExpiredLocked(expired.toLong())
                        android.util.Log.w("MessageQueue", "Dropped $expired expired restore message(s)")
                    }
                    val queue = readLocked().toMutableList()
                    queue.addAll(freshPending)
                    while (queue.size > MAX_QUEUE_SIZE) {
                        queue.removeAt(0)
                        noteOverflowLocked()
                    }
                    persistLocked(queue)
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun addMessage(message: String, priority: Boolean = false) {
        val cap = MAX_QUEUE_SIZE + if (priority) 5 else 0
        synchronized(lock) {
            if (volatileOnly) {
                pruneVolatileLocked()
                volatileQueue.add(QueuedMessage(message = message))
                while (volatileQueue.size > cap) {
                    volatileQueue.removeAt(0)
                    noteOverflowLocked()
                }
                return
            }
            val queue = readLocked().toMutableList()
            queue.add(QueuedMessage(message = message))
            while (queue.size > cap) {
                queue.removeAt(0)
                noteOverflowLocked()
                android.util.Log.w("MessageQueue", "Queue full, dropped oldest message")
            }
            persistLocked(queue)
        }
    }

    fun addMessages(messages: Collection<String>) {
        if (messages.isEmpty()) return
        synchronized(lock) {
            if (volatileOnly) {
                pruneVolatileLocked()
                for (message in messages) volatileQueue.add(QueuedMessage(message = message))
                while (volatileQueue.size > MAX_QUEUE_SIZE) {
                    volatileQueue.removeAt(0)
                    noteOverflowLocked()
                }
                return
            }
            val queue = readLocked().toMutableList()
            for (message in messages) queue.add(QueuedMessage(message = message))
            while (queue.size > MAX_QUEUE_SIZE) {
                queue.removeAt(0)
                noteOverflowLocked()
                android.util.Log.w("MessageQueue", "Queue full, dropped oldest message")
            }
            persistLocked(queue)
        }
    }

    private fun loadDropCountsLocked() {
        try {
            overflowDrops.set(sharedPreferences?.getLong(KEY_OVERFLOW_DROPS, 0L) ?: 0L)
            expiredDrops.set(sharedPreferences?.getLong(KEY_EXPIRED_DROPS, 0L) ?: 0L)
        } catch (_: Exception) {
        }
    }

    private fun persistDropCountsLocked() {
        try {
            sharedPreferences?.edit()?.putLong(KEY_OVERFLOW_DROPS, overflowDrops.get())?.putLong(KEY_EXPIRED_DROPS, expiredDrops.get())?.commit()
        } catch (_: Exception) {
        }
    }

    private fun noteOverflowLocked(by: Long = 1L) {
        noteOverflowLocked(by)
        if (!volatileOnly) persistDropCountsLocked()
    }

    private fun noteExpiredLocked(by: Long = 1L) {
        noteExpiredLocked(by)
        if (!volatileOnly) persistDropCountsLocked()
    }

    private fun pruneVolatileLocked() {
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60_000L
        val before = volatileQueue.size
        volatileQueue.removeAll { it.timestamp < cutoff }
        val dropped = before - volatileQueue.size
        if (dropped > 0) {
            noteExpiredLocked(dropped.toLong())
            android.util.Log.w("MessageQueue", "Dropped $dropped expired volatile message(s)")
        }
    }

    fun getQueue(): List<QueuedMessage> {
        synchronized(lock) {
            if (volatileOnly) {
                pruneVolatileLocked()
                return volatileQueue.toList()
            }
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
            persistLocked(queue)
        }
    }

    fun removeMessages(messageIds: Collection<String>) {
        if (messageIds.isEmpty()) return
        synchronized(lock) {
            if (volatileOnly) {
                volatileQueue.removeAll { it.id in messageIds }
                return
            }
            val queue = readLocked().toMutableList()
            queue.removeAll { it.id in messageIds }
            persistLocked(queue)
        }
    }

    fun registerFailures(messageIds: Collection<String>): List<String> {
        return registerWithBudget(messageIds, MAX_RETRIES).first
    }

    fun registerTransientFailures(messageIds: Collection<String>): Pair<List<String>, Int> {
        return registerWithBudget(messageIds, MAX_TRANSIENT_RETRIES)
    }

    private fun registerWithBudget(messageIds: Collection<String>, budget: Int): Pair<List<String>, Int> {
        if (messageIds.isEmpty()) return Pair(emptyList(), 0)
        synchronized(lock) {
            var maxRetry = 0
            if (volatileOnly) {
                val drop = mutableListOf<String>()
                for (i in volatileQueue.indices) {
                    val queued = volatileQueue[i]
                    if (queued.id in messageIds) {
                        val updated = queued.copy(retryCount = queued.retryCount + 1)
                        volatileQueue[i] = updated
                        if (updated.retryCount > maxRetry) maxRetry = updated.retryCount
                        if (updated.retryCount >= budget) drop.add(updated.id)
                    }
                }
                volatileQueue.removeAll { it.id in drop }
                return Pair(drop, maxRetry)
            }
            val queue = readLocked().toMutableList()
            val drop = mutableListOf<String>()
            for (i in queue.indices) {
                val queued = queue[i]
                if (queued.id in messageIds) {
                    val updated = queued.copy(retryCount = queued.retryCount + 1)
                    queue[i] = updated
                    if (updated.retryCount > maxRetry) maxRetry = updated.retryCount
                    if (updated.retryCount >= budget) drop.add(updated.id)
                }
            }
            queue.removeAll { it.id in drop }
            persistLocked(queue)
            return Pair(drop, maxRetry)
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
            persistLocked(queue)
            return updated.retryCount
        }
    }

    fun clearQueue() {
        synchronized(lock) {
            cached = mutableListOf()
            volatileQueue.clear()
            sharedPreferences?.edit()?.remove(KEY_QUEUE)?.commit()
        }
    }

    private fun readLocked(): MutableList<QueuedMessage> {
        cached?.let {
            val cutoffCached = System.currentTimeMillis() - 7 * 24 * 60 * 60_000L
            val freshCached = it.filter { q -> q.timestamp >= cutoffCached }.toMutableList()
            if (freshCached.size != it.size) {
                noteExpiredLocked((it.size - freshCached.size).toLong())
                android.util.Log.w("MessageQueue", "Dropped ${it.size - freshCached.size} expired message(s)")
                persistLocked(freshCached)
                return freshCached
            }
            return it
        }
        val json = sharedPreferences?.getString(KEY_QUEUE, null)
        val loaded: MutableList<QueuedMessage> = try {
            if (json == null) mutableListOf()
            else {
                gson.fromJson(json, arrayType)?.toMutableList() ?: mutableListOf()
            }
        } catch (e: Exception) {
            android.util.Log.w("MessageQueue", "Queue storage corrupt, keeping a drop notice")
            val notice = mutableListOf(QueuedMessage(message = "\u26A0\uFE0F Queued messages were discarded (corrupt storage)."))
            try {
                sharedPreferences?.edit()?.putString(KEY_QUEUE, gson.toJson(notice))?.commit()
            } catch (_: Exception) {
            }
            cached = notice
            notice
        }
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60_000L
        val fresh = loaded.filter { it.timestamp >= cutoff }.toMutableList()
        if (fresh.size != loaded.size) {
            noteExpiredLocked((loaded.size - fresh.size).toLong())
            android.util.Log.w("MessageQueue", "Dropped ${loaded.size - fresh.size} expired message(s)")
            persistLocked(fresh)
            return fresh
        }
        cached = loaded
        return loaded
    }

    private fun persistLocked(queue: MutableList<QueuedMessage>) {
        cached = queue
        sharedPreferences?.edit()?.putString(KEY_QUEUE, gson.toJson(queue))?.commit()
    }

    fun hasMessages(): Boolean {
        synchronized(lock) {
            if (volatileOnly) {
                pruneVolatileLocked()
                return volatileQueue.isNotEmpty()
            }
            return readLocked().isNotEmpty()
        }
    }
    fun takeOverflowDrops(): Long {
        synchronized(lock) {
            val n = overflowDrops.getAndSet(0L)
            if (!volatileOnly) persistDropCountsLocked()
            return n
        }
    }

    fun takeExpiredDrops(): Long {
        synchronized(lock) {
            val n = expiredDrops.getAndSet(0L)
            if (!volatileOnly) persistDropCountsLocked()
            return n
        }
    }

    fun getQueueSize(): Int {
        synchronized(lock) {
            if (volatileOnly) {
                pruneVolatileLocked()
                return volatileQueue.size
            }
            return readLocked().size
        }
    }
}
