package com.redeye.parentalmonitor.utils

object TelegramSendPolicy {

    enum class Kind {
        SENT, RATE_LIMITED, AUTH_FAILED, MIGRATED, CHAT_MISSING, RIGHTS_LIMITED, PARSE_ENTITIES, TRANSIENT, REJECTED
    }

    class Outcome(val kind: Kind, val code: Int, val retryAfterSecs: Long = 0L)

    fun classify(isSuccessful: Boolean, ok: Boolean, code: Int, errorBody: String?, migrated: Boolean): Outcome {
        if (isSuccessful && ok) return Outcome(Kind.SENT, code)
        return when {
            code == 429 -> Outcome(Kind.RATE_LIMITED, code, NetworkUtils.parseRetryAfter(errorBody))
            code == 401 || code == 403 -> Outcome(Kind.AUTH_FAILED, code)
            code == 400 -> when {
                migrated -> Outcome(Kind.MIGRATED, code)
                NetworkUtils.isChatMissing(errorBody) -> Outcome(Kind.CHAT_MISSING, code)
                NetworkUtils.isRightsLimited(errorBody) -> Outcome(Kind.RIGHTS_LIMITED, code)
                errorBody?.contains("can't parse entities", ignoreCase = true) == true -> Outcome(Kind.PARSE_ENTITIES, code)
                else -> Outcome(Kind.REJECTED, code)
            }
            code == 408 || code >= 500 -> Outcome(Kind.TRANSIENT, code)
            else -> Outcome(Kind.REJECTED, code)
        }
    }
}
