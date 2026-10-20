package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class CalculatorFormatTest {

    @Test
    fun groupsThousandsAndKeepsDecimals() {
        assertEquals("12,500", CalculatorFormat.displayNumber("12500"))
        assertEquals("−1,234.50", CalculatorFormat.displayNumber("-1234.50"))
        assertEquals("0.5", CalculatorFormat.displayNumber("0.5"))
        assertEquals("0", CalculatorFormat.displayNumber(""))
    }

    @Test
    fun keepsScientificAndErrorText() {
        assertEquals("1.2E10", CalculatorFormat.displayNumber("1.2E10"))
        assertEquals("−1.2E10", CalculatorFormat.displayNumber("-1.2E10"))
        assertEquals("Error", CalculatorFormat.displayNumber("Error"))
        assertEquals("−", CalculatorFormat.displayNumber("-"))
    }

    @Test
    fun formatsHistoryTokens() {
        assertEquals("12,500 + 450 =", CalculatorFormat.history("12500 + 450 ="))
        assertEquals("−5 × 2 =", CalculatorFormat.history("-5 × 2 ="))
        assertEquals("", CalculatorFormat.history(""))
    }
}
