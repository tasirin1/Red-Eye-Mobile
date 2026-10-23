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

    @Volatile
    private var dropsDirty = false
    private var lastPersistAt = 0L
    @Volatile
    private var persistDirty = false
    private val lock = Any()
    private val persistExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "redeye-queue-persist").apply { isDaemon = true }
    }
    private val persistQueued = java.util.concurrent.atomic.AtomicBoolean(false)
    private val persistMutex = Any()
    @Volatile
    private var persistVersion = 0L
    private var pendingSnapshot: Pair<Long, List<QueuedMessage>>? = null
    private val generationCounter = java.util.concurrent.atomic.AtomicLong(0L)
    private var cached: MutableList<QueuedMessage>? = null
    private val arrayType = Array<QueuedMessage>::class.java

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
    companion object {
        private const val QUEUE_PREFS_NAME = "encrypted_queue"
        private const val KEY_QUEUE = "queued_messages"
        private const val KEY_QUEUE_BAK = "queued_messages_bak"
        private const val KEY_OVERFLOW_DROPS = "overflow_drops"
        private const val KEY_EXPIRED_DROPS = "expired_drops"
        private const val KEY_PRIORITY_DROPS = "priority_drops"
        private const val MAX_QUEUE_SIZE = 100
        private const val MAX_TRANSIENT_RETRIES = 20
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
            val pending = volatileQueue.toList()
            return try {
                val restored = PreferencesManager.openEncryptedPrefs(appContext, QUEUE_PREFS_NAME)
                sharedPreferences = restored
                volatileOnly = false
                cached = null
                persistVersion++
                pendingSnapshot = null
                persistDirty = false
                loadDropCountsLocked(add = true)
                persistDropCountsLocked()
                if (pending.isNotEmpty()) {
                    val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60_000L
                    val freshPending = filterFresh(pending, cutoff)
                    val expired = pending.size - freshPending.size
                    if (expired > 0) {
                        noteExpiredLocked(expired.toLong())
                        android.util.Log.w("MessageQueue", "Dropped $expired expired restore message(s)")
                    }
                    val queue = readLocked().toMutableList()
                    if (volatileOnly) throw IllegalStateException("queue read failed during restore")
                    queue.addAll(freshPending)
                    var evicted = 0
                    while (queue.size > MAX_QUEUE_SIZE) {
                        evictOldestLocked(queue, persist = false)
                        evicted++
                    }
                    if (evicted > 0) persistDropCountsLocked()
                    persistLocked(queue)
                }
                volatileQueue.clear()
                true
            } catch (_: Exception) {
                volatileQueue.clear()
                volatileQueue.addAll(pending)
                sharedPreferences = null
                volatileOnly = true
                cached = null
                persistVersion++
                pendingSnapshot = null
                persistDirty = false
                false
            }
        }
    }

    fun reopenAfterStorageReset() {
        synchronized(lock) {
            cached = null
            volatileQueue.clear()
            dropsDirty = false
            persistVersion++
            pendingSnapshot = null
            persistDirty = false
            try {
                sharedPreferences = PreferencesManager.openEncryptedPrefs(appContext, QUEUE_PREFS_NAME)
                volatileOnly = false
                loadDropCountsLocked(add = false)
            } catch (e: Exception) {
                sharedPreferences = null
                volatileOnly = true
                android.util.Log.w("MessageQueue", "Queue reopen failed, using volatile: ${e.message}")
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

    fun addMessage(message: String, priority: Boolean = false, retryCount: Int = 0): Boolean {
        val cap = MAX_QUEUE_SIZE + if (priority) 5 else 0
        synchronized(lock) {
            ensureRestored()
            if (volatileOnly) {
                pruneVolatileLocked()
                val item = QueuedMessage(message = message, elapsedAt = android.os.SystemClock.elapsedRealtime(), retryCount = retryCount.coerceAtLeast(0), priority = priority, bootAt = bootWallAt())
                volatileQueue.add(item)
                while (volatileQueue.size > cap) {
                    evictOldestLocked(volatileQueue)
                }
                return volatileQueue.any { it === item }
            }
            val queue = readLocked().toMutableList()
            queue.add(QueuedMessage(message = message, elapsedAt = android.os.SystemClock.elapsedRealtime(), retryCount = retryCount.coerceAtLeast(0), priority = priority, bootAt = bootWallAt()))
            while (queue.size > cap) {
                evictOldestLocked(queue)
            }
            return persistLocked(queue, force = true)
        }
    }

    fun addMessages(messages: Collection<String>, priority: Boolean = false): Boolean {
        if (messages.isEmpty()) return true
        val cap = MAX_QUEUE_SIZE + if (priority) 5 else 0
        synchronized(lock) {
            ensureRestored()
            if (volatileOnly) {
                pruneVolatileLocked()
                val added = messages.map { QueuedMessage(message = it, elapsedAt = android.os.SystemClock.elapsedRealtime(), priority = priority, bootAt = bootWallAt()) }
                volatileQueue.addAll(added)
                var evictedVolatile = 0
                while (volatileQueue.size > cap) {
                    evictOldestLocked(volatileQueue, persist = false)
                    evictedVolatile++
                }
                if (evictedVolatile > 0) persistDropCountsLocked()
                return added.any { item -> volatileQueue.any { it === item } }
            }
            val queue = readLocked().toMutableList()
            for (message in messages) queue.add(QueuedMessage(message = message, elapsedAt = android.os.SystemClock.elapsedRealtime(), priority = priority, bootAt = bootWallAt()))
            var evictedBatch = 0
            while (queue.size > cap) {
                evictOldestLocked(queue, persist = false)
                evictedBatch++
            }
            if (evictedBatch > 0) persistDropCountsLocked()
            return persistLocked(queue, force = true)
        }
    }

    private fun loadDropCountsLocked(add: Boolean = false) {
        try {
            val overflow = sharedPreferences?.getLong(KEY_OVERFLOW_DROPS, 0L) ?: 0L
            val expired = sharedPreferences?.getLong(KEY_EXPIRED_DROPS, 0L) ?: 0L
            val priority = sharedPreferences?.getLong(KEY_PRIORITY_DROPS, 0L) ?: 0L
            if (add) {
                overflowDrops.addAndGet(overflow)
                expiredDrops.addAndGet(expired)
                priorityDrops.addAndGet(priority)
                dropsDirty = true
            } else {
                overflowDrops.set(overflow)
                expiredDrops.set(expired)
                priorityDrops.set(priority)
                dropsDirty = false
            }
        } catch (_: Exception) {
        }
    }

    private fun persistDropCountsLocked() {
        if (volatileOnly) return
        try {
            val editor = sharedPreferences?.edit()?.putLong(KEY_OVERFLOW_DROPS, overflowDrops.get())?.putLong(KEY_EXPIRED_DROPS, expiredDrops.get())?.putLong(KEY_PRIORITY_DROPS, priorityDrops.get())
            val saved = if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                editor?.apply()
                editor != null
            } else {
                editor?.commit() == true
            }
            dropsDirty = !saved
        } catch (_: Exception) {
            dropsDirty = true
        }
    }

    private fun noteOverflowLocked(by: Long = 1L, persist: Boolean = true) {
        noteDropLocked(overflowDrops, by, persist)
    }

    private fun noteExpiredLocked(by: Long = 1L, persist: Boolean = true) {
        noteDropLocked(expiredDrops, by, persist)
    }

    private fun noteDropLocked(counter: java.util.concurrent.atomic.AtomicLong, by: Long, persist: Boolean) {
        try {
            counter.addAndGet(by)
        } catch (_: Exception) {
        }
        dropsDirty = true
        if (persist) persistDropCountsLocked()
    }

    private fun evictOldestLocked(queue: MutableList<QueuedMessage>, persist: Boolean = true) {
        val idx = queue.indexOfFirst { !it.priority }
        val evicted = if (idx >= 0) queue.removeAt(idx) else queue.removeAt(0)
        try {
            if (evicted.priority) priorityDrops.incrementAndGet()
        } catch (_: Exception) {
        }
        noteOverflowLocked(persist = persist)
        android.util.Log.w("MessageQueue", "Queue full, dropped oldest message")
    }

    private fun bootWallAt(): Long = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()

    private fun filterFresh(items: List<QueuedMessage>, wallCutoff: Long): List<QueuedMessage> {
        val now = try {
            android.os.SystemClock.elapsedRealtime()
        } catch (_: Exception) {
            0L
        }
        val bootAt = try {
            bootWallAt()
        } catch (_: Exception) {
            0L
        }
        if (items.size <= 5) return items.filter { isFresh(it, wallCutoff, now, bootAt) }
        if (items.all { isFresh(it, wallCutoff, now, bootAt) }) return items
        val fresh = items.filter { isFresh(it, wallCutoff, now, bootAt) }
        if (fresh.size * 2 < items.size && items.all { it.bootAt <= 0L }) {
            android.util.Log.w("MessageQueue", "Clock jump suspected, keeping ${items.size} messages")
            return items
        }
        return fresh
    }

    private fun isFresh(q: QueuedMessage, wallCutoff: Long, now: Long, bootAt: Long): Boolean {
        val mark = q.elapsedAt
        val sameBoot = q.bootAt > 0L && bootAt > 0L && kotlin.math.abs(q.bootAt - bootAt) <= 120_000L
        if (mark > 0L && sameBoot) {
            return if (now < mark) {
                q.timestamp >= wallCutoff
            } else {
                now - mark <= 7 * 24 * 60 * 60_000L || q.timestamp >= wallCutoff
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
            overflowDrops.set(0L)
            expiredDrops.set(0L)
            priorityDrops.set(0L)
            dropsDirty = false
            val wipe = if (volatileOnly) {
                try {
                    PreferencesManager.openEncryptedPrefs(appContext, QUEUE_PREFS_NAME)
                } catch (_: Exception) {
                    null
                }
            } else {
                sharedPreferences
            }
            val cleared = synchronized(persistMutex) {
                persistVersion++
                pendingSnapshot = null
                try {
                    wipe?.edit()?.remove(KEY_QUEUE)?.remove(KEY_QUEUE_BAK)?.remove(KEY_OVERFLOW_DROPS)?.remove(KEY_EXPIRED_DROPS)?.remove(KEY_PRIORITY_DROPS)?.commit() == true
                } catch (_: Exception) {
                    false
                }
            }
            if (!cleared) persistDirty = true
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
            if (sharedPreferences == null) return
            synchronized(lock) {
                val pending = cached
                if (pending != null) persistLocked(pending, force = true)
                if (dropsDirty) persistDropCountsLocked()
            }
            if (onMain) return
            try {
                persistExecutor.submit(java.lang.Runnable { }).get(5L, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
    }

    private fun readLocked(): MutableList<QueuedMessage> {
        cached?.let {
            val cutoffCached = System.currentTimeMillis() - 7 * 24 * 60 * 60_000L
            val freshCached = filterFresh(it, cutoffCached)
            if (freshCached.size != it.size) {
                noteExpiredLocked((it.size - freshCached.size).toLong())
                android.util.Log.w("MessageQueue", "Dropped ${it.size - freshCached.size} expired message(s)")
                val kept = freshCached.toMutableList()
                persistLocked(kept)
                return kept
            }
            return it
        }
        val json = try {
            sharedPreferences?.getString(KEY_QUEUE, null)
        } catch (e: Exception) {
            android.util.Log.w("MessageQueue", "Queue read failed, falling back to volatile: ${e.message}")
            volatileOnly = true
            cached = null
            try {
                com.redeye.parentalmonitor.utils.MessageScheduler.scheduleMessageSendNext(appContext, 60_000L)
            } catch (_: Exception) {
            }
            return mutableListOf()
        }
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

    private fun persistLocked(queue: MutableList<QueuedMessage>, force: Boolean = false): Boolean {
        cached = queue
        val prefs = sharedPreferences ?: return false
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastPersistAt < 2000L) {
            persistDirty = true
            return false
        }
        lastPersistAt = now
        persistDirty = false
        val version = ++persistVersion
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            pendingSnapshot = version to ArrayList(queue)
            persistDirty = true
            schedulePersistLocked()
            return true
        }
        return writeSnapshot(prefs, ArrayList(queue), version)
    }

    private fun schedulePersistLocked() {
        if (!persistQueued.compareAndSet(false, true)) return
        try {
            persistExecutor.execute { drainPersist() }
        } catch (_: Exception) {
            persistQueued.set(false)
        }
    }

    private fun writeSnapshot(prefs: SharedPreferences, snapshot: List<QueuedMessage>, version: Long): Boolean {
        synchronized(persistMutex) {
            if (persistVersion != version) return true
            val saved = try {
                prefs.edit()?.putString(KEY_QUEUE, gson.toJson(snapshot))?.commit() == true
            } catch (_: Exception) {
                false
            }
            if (!saved && persistVersion == version) persistDirty = true
            return saved
        }
    }

    private fun drainPersist() {
        while (true) {
            val prefs: SharedPreferences
            val snapshot: List<QueuedMessage>
            val version: Long
            synchronized(lock) {
                val pending = pendingSnapshot
                val currentPrefs = sharedPreferences
                if (pending == null || currentPrefs == null || !persistDirty) {
                    pendingSnapshot = null
                    persistDirty = false
                    persistQueued.set(false)
                    return
                }
                pendingSnapshot = null
                persistDirty = false
                prefs = currentPrefs
                version = pending.first
                snapshot = pending.second
                if (dropsDirty) persistDropCountsLocked()
            }
            if (!writeSnapshot(prefs, snapshot, version)) {
                synchronized(lock) {
                    if (pendingSnapshot == null) {
                        pendingSnapshot = version to snapshot
                        persistDirty = true
                    }
                    persistQueued.set(false)
                }
                return
            }
            synchronized(lock) {
                if (pendingSnapshot == null && !persistDirty) {
                    persistQueued.set(false)
                    return
                }
                if (pendingSnapshot == null && persistDirty) {
                    pendingSnapshot = cached?.let { persistVersion to ArrayList(it) }
                    persistDirty = pendingSnapshot != null
                }
            }
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

    private fun takeDropsLocked(counter: java.util.concurrent.atomic.AtomicLong): Long {
        val n = counter.getAndSet(0L)
        if (n <= 0L) return 0L
        dropsDirty = true
        persistDropCountsLocked()
        return n
    }

    fun takeOverflowDrops(): Long {
        synchronized(lock) {
            return takeDropsLocked(overflowDrops)
        }
    }

    fun takePriorityDrops(): Long {
        synchronized(lock) {
            return takeDropsLocked(priorityDrops)
        }
    }

    fun takeExpiredDrops(): Long {
        synchronized(lock) {
            return takeDropsLocked(expiredDrops)
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
