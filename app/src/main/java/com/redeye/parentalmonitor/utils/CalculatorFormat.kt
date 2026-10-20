package com.redeye.parentalmonitor.utils

object CalculatorFormat {

    fun displayNumber(raw: String): String {
        if (raw.isEmpty()) return "0"
        if (raw == "Error" || raw.contains('E') || raw.contains('e')) {
            return raw.replace("-", "−")
        }
        val negative = raw.startsWith("-")
        val body = if (negative) raw.substring(1) else raw
        if (body.isEmpty()) return "−"
        val dotIndex = body.indexOf('.')
        val intPart = if (dotIndex >= 0) body.substring(0, dotIndex) else body
        val decPart = if (dotIndex >= 0) body.substring(dotIndex) else ""
        val grouped = if (intPart.isEmpty()) {
            "0"
        } else {
            intPart.reversed().chunked(3).joinToString(",").reversed()
        }
        return (if (negative) "−" else "") + grouped + decPart
    }

    fun history(expression: String): String {
        if (expression.isEmpty()) return ""
        return expression.split(' ').joinToString(" ") { token ->
            when (token) {
                "", "+", "−", "×", "÷", "=" -> token
                else -> displayNumber(token)
            }
        }
    }
}
