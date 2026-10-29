package com.redeye.parentalmonitor.utils

class CalculatorState {

    var currentNumber = ""
    var operator = ""
    var previousNumber = ""
    var lastExpression = ""
    var justCalculated = false
    var entryReplaced = false

    fun appendNumber(number: String) {
        if (entryReplaced) {
            currentNumber = ""
            entryReplaced = false
        }
        if (justCalculated) {
            currentNumber = ""
            previousNumber = ""
            operator = ""
            lastExpression = ""
            justCalculated = false
        }
        if (currentNumber == "Error") {
            currentNumber = ""
            previousNumber = ""
            operator = ""
            lastExpression = ""
        }
        if (number == ".") {
            if (currentNumber.contains(".")) return
            when {
                currentNumber.isEmpty() -> currentNumber = "0."
                currentNumber == "-" -> currentNumber = "-0."
                else -> currentNumber += "."
            }
        } else if (currentNumber == "0") {
            currentNumber = number
        } else if (currentNumber == "-0") {
            currentNumber = "-$number"
        } else {
            if (currentNumber.count { it.isDigit() } >= 12) return
            currentNumber += number
        }

    }

    fun setOperator(op: String) {
        justCalculated = false
        entryReplaced = false
        if (currentNumber == "Error") {
            clear()
            return
        }
        if (currentNumber.isEmpty() || currentNumber == "-") {
            if (previousNumber.isNotEmpty()) {
                operator = op
            }
            return
        }
        if (previousNumber.isNotEmpty()) {
            calculate()
            if (currentNumber == "Error" || currentNumber.isEmpty()) return
        }
        operator = op
        previousNumber = currentNumber
        currentNumber = ""
        justCalculated = false
    }

    fun calculate(): Boolean {
        if (!justCalculated && !entryReplaced && currentNumber == "1234" && previousNumber.isEmpty() && operator.isEmpty()) {
            currentNumber = ""
            previousNumber = ""
            operator = ""
            lastExpression = ""
            justCalculated = false
            return true
        }

        if (previousNumber.isEmpty() || currentNumber.isEmpty() || currentNumber == "-") return false

        val currentStr = currentNumber
        val num1 = previousNumber.toDoubleOrNull() ?: return false
        val num2 = currentStr.toDoubleOrNull() ?: return false

        val result = when (operator) {
            "+" -> num1 + num2
            "−" -> num1 - num2
            "×" -> num1 * num2
            "÷" -> if (num2 != 0.0) num1 / num2 else Double.NaN
            else -> num2
        }

        currentNumber = formatResult(result)

        lastExpression = "$previousNumber $operator $currentStr"
        justCalculated = true
        previousNumber = ""
        operator = ""
        return false
    }

    private fun formatResult(result: Double): String {
        if (result.isNaN() || result.isInfinite()) return "Error"
        val abs = kotlin.math.abs(result)
        if (result == kotlin.math.floor(result) && abs < if (result < 0) 1e11 else 1e12) {
            return result.toLong().toString()
        }
        val floored = kotlin.math.floor(abs)
        val intDigits = if (floored < 1.0) 1 else java.math.BigDecimal.valueOf(floored).toPlainString().substringBefore('.').trimStart('-').trimStart('0').length.coerceAtLeast(1)
        val signExtra = if (result < 0) 1 else 0
        if (intDigits + signExtra > 12) {
            return String.format(java.util.Locale.US, "%.5E", result)
        }
        val maxScale = (12 - intDigits - signExtra).coerceIn(0, 8)
        val plain = java.math.BigDecimal.valueOf(result)
            .setScale(maxScale, java.math.RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
        if (result != 0.0 && plain.trimStart('-') == "0") {
            return String.format(java.util.Locale.US, "%.5E", result)
        }
        if (plain.count { it.isDigit() } + (if (plain.startsWith("-")) 1 else 0) > 12) {
            return String.format(java.util.Locale.US, "%.5E", result)
        }
        return plain
    }

    fun backspace() {
        if (currentNumber == "Error") {
            clear()
            return
        }
        if (justCalculated) return
        if (entryReplaced) {
            currentNumber = ""
            entryReplaced = false
            return
        }
        currentNumber = when {
            currentNumber.length <= 1 -> ""
            else -> currentNumber.dropLast(1).removeSuffix("-")
        }
    }

    fun clear() {
        currentNumber = ""
        previousNumber = ""
        operator = ""
        lastExpression = ""
        justCalculated = false
        entryReplaced = false
    }

    fun toggleSign() {
        if (currentNumber == "Error") {
            clear()
            return
        }
        if (currentNumber.isEmpty()) {
            if (operator.isNotEmpty()) {
                currentNumber = "-"
            }
            return
        }
        currentNumber = when {
            currentNumber == "-" -> ""
            currentNumber.startsWith("-") -> currentNumber.removePrefix("-")
            currentNumber == "0" -> "0"
            else -> "-$currentNumber"
        }
    }

    fun percent() {
        if (currentNumber == "Error") {
            clear()
            return
        }
        if (currentNumber.isEmpty() || currentNumber == "-") return
        val value = currentNumber.toDoubleOrNull() ?: return
        val base = previousNumber.toDoubleOrNull()
        val result = if (operator.isNotEmpty() && base != null) {
            base * value / 100.0
        } else {
            value / 100.0
        }
        currentNumber = formatResult(result)
        lastExpression = ""
        entryReplaced = true
    }
}
