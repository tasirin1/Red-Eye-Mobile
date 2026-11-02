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

    private class LimitedCursor(delegate: android.database.Cursor, private val maxRows: Int) : android.database.CursorWrapper(delegate) {
        override fun getCount(): Int = minOf(super.getCount(), maxRows)

        override fun getPosition(): Int = minOf(super.getPosition(), count)

        override fun moveToPosition(position: Int): Boolean {
            if (position < 0) return super.moveToPosition(position)
            if (position >= maxRows) {
                super.moveToPosition(super.getCount())
                return false
            }
            return super.moveToPosition(position)
        }

        override fun move(offset: Int): Boolean = moveToPosition(position + offset)

        override fun moveToNext(): Boolean = moveToPosition(position + 1)

        override fun moveToPrevious(): Boolean = moveToPosition(position - 1)

        override fun moveToFirst(): Boolean = moveToPosition(0)

        override fun moveToLast(): Boolean = moveToPosition(count - 1)

        override fun isAfterLast(): Boolean = position >= count

        override fun isLast(): Boolean = count > 0 && position == count - 1
    }
}
