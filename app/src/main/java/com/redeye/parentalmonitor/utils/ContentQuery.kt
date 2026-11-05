package com.redeye.parentalmonitor.utils

object ContentQuery {
    fun query(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        projection: Array<String>?,
        selection: String?,
        args: Array<String>?,
        sortOrder: String,
        limit: Int = Int.MAX_VALUE,
        strict: Boolean = false
    ): android.database.Cursor? {
        val safeLimit = if (limit < 0) Int.MAX_VALUE else limit
        val cursor = try {
            if (safeLimit != Int.MAX_VALUE && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val bundle = android.os.Bundle().apply {
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                    putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, safeLimit)
                }
                try {
                    resolver.query(uri, projection, bundle, null)
                } catch (e: SecurityException) {
                    throw e
                } catch (_: Exception) {
                    resolver.query(uri, projection, selection, args, "$sortOrder LIMIT $safeLimit")
                }
            } else if (safeLimit == Int.MAX_VALUE) {
                resolver.query(uri, projection, selection, args, sortOrder)
            } else {
                try {
                    resolver.query(uri, projection, selection, args, "$sortOrder LIMIT $safeLimit")
                } catch (e: SecurityException) {
                    throw e
                } catch (_: Exception) {
                    val raw = resolver.query(uri, projection, selection, args, sortOrder)
                    if (raw != null) LimitedCursor(raw, safeLimit) else null
                }
            }
        } catch (e: SecurityException) {
            android.util.Log.w("ContentQuery", "permission denied", e)
            throw e
        } catch (e: Exception) {
            android.util.Log.w("ContentQuery", "query failed: ${e.message}", e)
            if (strict) throw e
            null
        }
        if (cursor == null && strict) throw IllegalStateException("query returned null")
        return cursor
    }

    // Matematika klem LimitedCursor sebagai logika murni
    // (issue #1692): pembungkusnya terikat
    // android.database.Cursor sehingga tak teruji JVM;
    // keputusan hitung/posisi/geraknya kini di sini dan
    // dikunci uji. LimitedCursor hanya menerjemahkan
    // keputusan ke panggilan delegasi.
    internal object Clamp {
        enum class Move { PASSTHROUGH, PARK_END, DIRECT }

        fun limitedCount(total: Int, maxRows: Int): Int = minOf(total, maxRows)

        fun clampedPosition(rawPosition: Int, total: Int, maxRows: Int): Int =
            minOf(rawPosition, limitedCount(total, maxRows))

        fun moveDecision(position: Int, maxRows: Int): Move = when {
            position < 0 -> Move.PASSTHROUGH
            position >= maxRows -> Move.PARK_END
            else -> Move.DIRECT
        }

        fun isAfterLast(rawPosition: Int, total: Int, maxRows: Int): Boolean =
            clampedPosition(rawPosition, total, maxRows) >= limitedCount(total, maxRows)

        fun isLast(rawPosition: Int, total: Int, maxRows: Int): Boolean {
            val c = limitedCount(total, maxRows)
            return c > 0 && clampedPosition(rawPosition, total, maxRows) == c - 1
        }
    }

    private class LimitedCursor(delegate: android.database.Cursor, private val maxRows: Int) : android.database.CursorWrapper(delegate) {
        override fun getCount(): Int = Clamp.limitedCount(super.getCount(), maxRows)

        override fun getPosition(): Int = Clamp.clampedPosition(super.getPosition(), super.getCount(), maxRows)

        override fun moveToPosition(position: Int): Boolean {
            return when (Clamp.moveDecision(position, maxRows)) {
                Clamp.Move.PASSTHROUGH -> super.moveToPosition(position)
                Clamp.Move.PARK_END -> {
                    super.moveToPosition(super.getCount())
                    false
                }
                Clamp.Move.DIRECT -> super.moveToPosition(position)
            }
        }

        override fun move(offset: Int): Boolean = moveToPosition(position + offset)

        override fun moveToNext(): Boolean = moveToPosition(position + 1)

        override fun moveToPrevious(): Boolean = moveToPosition(position - 1)

        override fun moveToFirst(): Boolean = moveToPosition(0)

        override fun moveToLast(): Boolean = moveToPosition(count - 1)

        override fun isAfterLast(): Boolean = Clamp.isAfterLast(super.getPosition(), super.getCount(), maxRows)

        override fun isLast(): Boolean = Clamp.isLast(super.getPosition(), super.getCount(), maxRows)
    }
}
