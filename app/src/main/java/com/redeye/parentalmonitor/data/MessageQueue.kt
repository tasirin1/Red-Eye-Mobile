package com.redeye.parentalmonitor.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.redeye.parentalmonitor.data.models.QueuedMessage

class MessageQueue private constructor(context: Context) {

    private val appContext = context.applicationContext

    @Volatile
    private var volatileOnly = false
    private val volatileQueue = mutableListOf<QueuedMessage>()
    @Volatile
    private var sharedPreferences: SharedPreferences? = null
    private val overflowDrops = java.util.concurrent.atomic.AtomicLong(0L)
    private val priorityDrops = java.util.concurrent.atomic.AtomicLong(0L)
    private val expiredDrops = java.util.concurrent.atomic.AtomicLong(0L)

    init {
        // Always try encrypted prefs first; fall back to volatile only if unavailable
        try {
            sharedPreferences = PreferencesManager.openEncryptedPrefs(appContext, QUEUE_PREFS_NAME)
            volatileOnly = false
            try {
                loadDropCountsLocked()
            } catch (_: Exception) {
            }
        } catch (e: Exception) {
            android.util.Log.w("MessageQueue", "Encrypted queue unavailable, using volatile memory", e)
            volatileOnly = true
            sharedPreferences = null
        }
        // Schedule persistent restore attempt if we started in volatile mode
        if (volatileOnly) {
            com.redeye.parentalmonitor.utils.Background.runCritical {
                try {
                    tryRestorePersistent()
                } catch (_: Exception) {
                }
            }
        }
    }
    private var lastPersistAt = 0L
    private var persistDirty = false
    private val lock = Any()
    private val generationCounter = java.util.concurrent.atomic.AtomicLong(0L)
    private var cached: MutableList<QueuedMessage>? = null
    private val arrayType = Array<QueuedMessage>::class.java

    companion object {
        private const val QUEUE_PREFS_NAME = "encrypted_queue"
        private const val KEY_QUEUE = "queued_messages"
        private const val KEY_QUEUE_BAK = "queued_messages_bak"
        private const val KEY_OVERFLOW_DROPS = "overflow_drops"
        private const val KEY_EXPIRED_DROPS = "expired_drops"
        private const val KEY_PRIORITY_DROPS = "priority_drops"
        private const val MAX_QUEUE_SIZE = 100
        const val MAX_TRANSIENT_RETRIES = 20
        private val gson = Gson()


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
                    val freshPending = filterFresh(pending, cutoff)
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

    private fun ensureRestored() {
        if (!volatileOnly) return
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            com.redeye.parentalmonitor.utils.Background.runCritical {
                try { tryRestorePersistent() } catch (_: Exception) { }
            }
            return
        }
        try {
            tryRestorePersistent()
        } catch (_: Exception) {
        }
    }

    fun addMessage(message: String, priority: Boolean = false, retryCount: Int = 0) {
        val cap = MAX_QUEUE_SIZE + if (priority) 5 else 0
        synchronized(lock) {
            ensureRestored()
            if (volatileOnly) {
                pruneVolatileLocked()
                volatileQueue.add(QueuedMessage(message = message, elapsedAt = android.os.SystemClock.elapsedRealtime(), retryCount = retryCount.coerceAtLeast(0), priority = priority))
                while (volatileQueue.size > cap) {
                    evictOldestLocked(volatileQueue)
                }
                return
            }
            val queue = readLocked().toMutableList()
            queue.add(QueuedMessage(message = message, elapsedAt = android.os.SystemClock.elapsedRealtime(), retryCount = retryCount.coerceAtLeast(0), priority = priority))
            while (queue.size > cap) {
                evictOldestLocked(queue)
            }
            persistLocked(queue, force = true)
        }
    }

    fun addMessages(messages: Collection<String>, priority: Boolean = false) {
        if (messages.isEmpty()) return
        val cap = MAX_QUEUE_SIZE + if (priority) 5 else 0
        synchronized(lock) {
            ensureRestored()
            if (volatileOnly) {
                pruneVolatileLocked()
                for (message in messages) volatileQueue.add(QueuedMessage(message = message, elapsedAt = android.os.SystemClock.elapsedRealtime(), priority = priority))
                while (volatileQueue.size > cap) {
                    evictOldestLocked(volatileQueue)
                }
                return
            }
            val queue = readLocked().toMutableList()
            for (message in messages) queue.add(QueuedMessage(message = message, elapsedAt = android.os.SystemClock.elapsedRealtime(), priority = priority))
            while (queue.size > cap) {
                evictOldestLocked(queue)
            }
            persistLocked(queue, force = true)
        }
    }

    private fun loadDropCountsLocked() {
        try {
            overflowDrops.set(sharedPreferences?.getLong(KEY_OVERFLOW_DROPS, 0L) ?: 0L)
            expiredDrops.set(sharedPreferences?.getLong(KEY_EXPIRED_DROPS, 0L) ?: 0L)
            priorityDrops.set(sharedPreferences?.getLong(KEY_PRIORITY_DROPS, 0L) ?: 0L)
        } catch (_: Exception) {
        }
    }

    private fun persistDropCountsLocked() {
        try {
            val editor = sharedPreferences?.edit()?.putLong(KEY_OVERFLOW_DROPS, overflowDrops.get())?.putLong(KEY_EXPIRED_DROPS, expiredDrops.get())?.putLong(KEY_PRIORITY_DROPS, priorityDrops.get())
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) editor?.apply() else editor?.commit()
        } catch (_: Exception) {
        }
    }

    private fun noteOverflowLocked(by: Long = 1L) {
        try {
            overflowDrops.addAndGet(by)
        } catch (_: Exception) {
        }
        if (!volatileOnly) persistDropCountsLocked()
    }

    private fun noteExpiredLocked(by: Long = 1L) {
        try {
            expiredDrops.addAndGet(by)
        } catch (_: Exception) {
        }
        if (!volatileOnly) persistDropCountsLocked()
    }

    private fun evictOldestLocked(queue: MutableList<QueuedMessage>) {
        val idx = queue.indexOfFirst { !it.priority }
        val evicted = if (idx >= 0) queue.removeAt(idx) else queue.removeAt(0)
        try {
            if (evicted.priority) priorityDrops.incrementAndGet()
        } catch (_: Exception) {
        }
        noteOverflowLocked()
        android.util.Log.w("MessageQueue", "Queue full, dropped oldest message")
    }

    private fun filterFresh(items: List<QueuedMessage>, wallCutoff: Long): List<QueuedMessage> {
        if (items.size <= 5) return items.filter { isFresh(it, wallCutoff) }
        val fresh = items.filter { isFresh(it, wallCutoff) }
        if (fresh.size * 2 < items.size) {
            android.util.Log.w("MessageQueue", "Clock jump suspected, keeping ${items.size} messages")
            return items
        }
        return fresh
    }

    private fun isFresh(q: QueuedMessage, wallCutoff: Long): Boolean {
        val mark = q.elapsedAt
        if (mark > 0L) {
            return try {
                val now = android.os.SystemClock.elapsedRealtime()
                if (now < mark) {
                    q.timestamp >= wallCutoff
                } else {
                    now - mark <= 7 * 24 * 60 * 60_000L || q.timestamp >= wallCutoff
                }
            } catch (_: Exception) {
                q.timestamp >= wallCutoff
            }
        }
        return q.timestamp >= wallCutoff
    }

    private fun pruneVolatileLocked() {
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60_000L
        val before = volatileQueue.size
        val fresh = filterFresh(volatileQueue, cutoff)
        val dropped = before - fresh.size
        if (dropped > 0) {
            volatileQueue.clear()
            volatileQueue.addAll(fresh)
            noteExpiredLocked(dropped.toLong())
            android.util.Log.w("MessageQueue", "Dropped $dropped expired volatile message(s)")
        }
    }

    fun getQueue(): List<QueuedMessage> {
        synchronized(lock) {
            ensureRestored()
            if (volatileOnly) {
                pruneVolatileLocked()
                return volatileQueue.toList()
            }
            return readLocked().toList()
        }
    }

    fun removeMessage(messageId: String) {
        synchronized(lock) {
            ensureRestored()
            if (volatileOnly) {
                volatileQueue.removeAll { it.id == messageId }
                return
            }
            val queue = readLocked().toMutableList()
            queue.removeAll { it.id == messageId }
            persistLocked(queue, force = true)
        }
    }

    fun removeMessages(messageIds: Collection<String>) {
        if (messageIds.isEmpty()) return
        synchronized(lock) {
            ensureRestored()
            if (volatileOnly) {
                volatileQueue.removeAll { it.id in messageIds }
                return
            }
            val queue = readLocked().toMutableList()
            queue.removeAll { it.id in messageIds }
            persistLocked(queue, force = true)
        }
    }

    fun registerTransientFailures(messageIds: Collection<String>): Pair<List<String>, Int> {
        return registerWithBudget(messageIds, MAX_TRANSIENT_RETRIES)
    }

    private fun registerWithBudget(messageIds: Collection<String>, budget: Int): Pair<List<String>, Int> {
        if (messageIds.isEmpty()) return Pair(emptyList(), 0)
        synchronized(lock) {
            var maxRetry = 0
            if (volatileOnly) {
                pruneVolatileLocked()
                val drop = mutableListOf<String>()
                for (i in volatileQueue.indices) {
                    val queued = volatileQueue[i]
                    if (queued.id in messageIds) {
                        val updated = queued.copy(retryCount = queued.retryCount + 1)
                        volatileQueue[i] = updated
                        if (updated.retryCount >= budget) drop.add(updated.id)
                        else if (updated.retryCount > maxRetry) maxRetry = updated.retryCount
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
                    if (updated.retryCount >= budget) drop.add(updated.id)
                    else if (updated.retryCount > maxRetry) maxRetry = updated.retryCount
                }
            }
            queue.removeAll { it.id in drop }
            persistLocked(queue, force = true)
            return Pair(drop, maxRetry)
        }
    }

    fun queueGeneration(): Long {
        return generationCounter.get()
    }

    fun clearQueue() {
        synchronized(lock) {
            generationCounter.incrementAndGet()
            cached = mutableListOf()
            persistDirty = false
            volatileQueue.clear()
            if (volatileOnly) {
                try {
                    PreferencesManager.openEncryptedPrefs(appContext, QUEUE_PREFS_NAME).edit()?.remove(KEY_QUEUE)?.remove(KEY_QUEUE_BAK)?.remove(KEY_OVERFLOW_DROPS)?.remove(KEY_EXPIRED_DROPS)?.remove(KEY_PRIORITY_DROPS)?.commit()
                } catch (_: Exception) {
                }
            }
            overflowDrops.set(0L)
            expiredDrops.set(0L)
            priorityDrops.set(0L)
            sharedPreferences?.edit()?.remove(KEY_QUEUE)?.remove(KEY_QUEUE_BAK)?.remove(KEY_OVERFLOW_DROPS)?.remove(KEY_EXPIRED_DROPS)?.remove(KEY_PRIORITY_DROPS)?.commit()
        }
    }

    fun flushSync() {
        try {
            val onMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
            if (volatileOnly && !onMain) {
                try {
                    tryRestorePersistent()
                } catch (_: Exception) {
                }
            }
            val prefs = sharedPreferences ?: return
            synchronized(lock) {
                val pending = cached
                if (pending != null && persistDirty) {
                    try {
                        val editor = prefs.edit()?.putString(KEY_QUEUE, gson.toJson(pending))
                        if (onMain) editor?.apply() else editor?.commit()
                    } catch (_: Exception) {
                    }
                    persistDirty = false
                    lastPersistAt = android.os.SystemClock.elapsedRealtime()
                }
            }
            try {
                val editor = prefs.edit()?.putLong(KEY_OVERFLOW_DROPS, overflowDrops.get())?.putLong(KEY_EXPIRED_DROPS, expiredDrops.get())?.putLong(KEY_PRIORITY_DROPS, priorityDrops.get())
                if (onMain) editor?.apply() else editor?.commit()
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
    }

    private fun readLocked(): MutableList<QueuedMessage> {
        cached?.let {
            val cutoffCached = System.currentTimeMillis() - 7 * 24 * 60 * 60_000L
            val freshCached = filterFresh(it, cutoffCached).toMutableList()
            if (freshCached.size != it.size) {
                noteExpiredLocked((it.size - freshCached.size).toLong())
                android.util.Log.w("MessageQueue", "Dropped ${it.size - freshCached.size} expired message(s)")
                persistLocked(freshCached)
                return freshCached
            }
            return it
        }
        val json = sharedPreferences?.getString(KEY_QUEUE, null)
        var parsedOk = false
        val loaded: MutableList<QueuedMessage> = try {
            if (json == null) mutableListOf()
            else {
                val parsed = gson.fromJson(json, arrayType)?.toMutableList() ?: mutableListOf()
                parsedOk = true
                parsed
            }
        } catch (e: Exception) {
            val bakJson = try { sharedPreferences?.getString(KEY_QUEUE_BAK, null) } catch (_: Exception) { null }
            val recovered: MutableList<QueuedMessage> = try {
                if (bakJson == null) mutableListOf()
                else gson.fromJson(bakJson, arrayType)?.toMutableList() ?: mutableListOf()
            } catch (_: Exception) {
                mutableListOf()
            }
            if (bakJson != null) {
                android.util.Log.w("MessageQueue", "Queue storage corrupt, recovered ${recovered.size} message(s) from backup")
            } else {
                android.util.Log.w("MessageQueue", "Queue storage corrupt, clearing poison blob: ${e.message}")
            }
            try {
                persistLocked(recovered, force = true)
            } catch (_: Exception) {
            }
            recovered
        }
        if (parsedOk && json != null) {
            try {
                val prefs = sharedPreferences
                if (prefs != null && prefs.getString(KEY_QUEUE_BAK, null) != json) {
                    val editor = prefs.edit()?.putString(KEY_QUEUE_BAK, json)
                    if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) editor?.commit()
                    else editor?.apply()
                }
            } catch (_: Exception) {
            }
        }
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60_000L
        val fresh = filterFresh(loaded, cutoff).toMutableList()
        if (fresh.size != loaded.size) {
            noteExpiredLocked((loaded.size - fresh.size).toLong())
            android.util.Log.w("MessageQueue", "Dropped ${loaded.size - fresh.size} expired message(s)")
            persistLocked(fresh)
            return fresh
        }
        cached = loaded
        return loaded
    }

    private fun persistLocked(queue: MutableList<QueuedMessage>, force: Boolean = false) {
        cached = queue
        val prefs = sharedPreferences ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastPersistAt < 2000L) {
            persistDirty = true
            return
        }
        lastPersistAt = now
        persistDirty = false
        try {
            val editor = prefs.edit()?.putString(KEY_QUEUE, gson.toJson(queue))
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) editor?.commit()
            else editor?.apply()
        } catch (_: Exception) {
        }
    }

    fun hasMessages(): Boolean {
        synchronized(lock) {
            ensureRestored()
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

    fun takePriorityDrops(): Long {
        synchronized(lock) {
            val n = priorityDrops.getAndSet(0L)
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
            ensureRestored()
            if (volatileOnly) {
                pruneVolatileLocked()
                return volatileQueue.size
            }
            return readLocked().size
        }
    }
}
