package com.redeye.parentalmonitor.utils

object ContentQuery {
    fun query(
        resolver: android.content.ContentResolver,
        uri: android.net.Uri,
        projection: Array<String>?,
        selection: String?,
        args: Array<String>?,
        sortOrder: String,
        limit: Int = Int.MAX_VALUE
    ): android.database.Cursor? {
        val safeLimit = if (limit < 0) Int.MAX_VALUE else limit
        return try {
            if (safeLimit != Int.MAX_VALUE && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val bundle = android.os.Bundle().apply {
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sortOrder)
                    putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, safeLimit)
                }
                try {
                    resolver.query(uri, projection, bundle, null)
                } catch (_: Exception) {
                    resolver.query(uri, projection, selection, args, "$sortOrder LIMIT $safeLimit")
                }
            } else if (safeLimit == Int.MAX_VALUE) {
                resolver.query(uri, projection, selection, args, sortOrder)
            } else {
                try {
                    resolver.query(uri, projection, selection, args, "$sortOrder LIMIT $safeLimit")
                } catch (_: Exception) {
                    resolver.query(uri, projection, selection, args, sortOrder)
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}
