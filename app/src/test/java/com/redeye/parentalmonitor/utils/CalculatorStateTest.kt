package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalculatorStateTest {

    private class Harness {
        val state = CalculatorState()
        var secret = false
        fun press(token: String) {
            when (token) {
                "=" -> if (state.calculate()) secret = true
                "+" -> state.setOperator("+")
                "−" -> state.setOperator("−")
                "×" -> state.setOperator("×")
                "÷" -> state.setOperator("÷")
                "⌫" -> state.backspace()
                "±" -> state.toggleSign()
                "%" -> state.percent()
                "C" -> state.clear()
                else -> for (ch in token) state.appendNumber(ch.toString())
            }
        }
        fun run(vararg tokens: String) {
            for (t in tokens) press(t)
        }
    }

    @Test
    fun typedSecretCodeOpensSetup() {
        val h = Harness()
        h.run("1234", "=")
        assertTrue(h.secret)
        assertEquals("", h.state.currentNumber)
        assertEquals("", h.state.previousNumber)
        assertEquals("", h.state.operator)
    }

    @Test
    fun typedCodeViaBackspaceStillOpensSetup() {
        val h = Harness()
        h.run("12345", "⌫", "=")
        assertTrue(h.secret)
    }

    @Test
    fun percentResultEqualToCodeDoesNotOpenSetup() {
        val h = Harness()
        h.run("123400", "%", "=")
        assertFalse(h.secret)
        assertEquals("1234", h.state.currentNumber)
        assertTrue(h.state.entryReplaced)
    }

    @Test
    fun percentThenToggleSignTwiceDoesNotOpenSetup() {
        val h = Harness()
        h.run("123400", "%", "±", "±", "=")
        assertFalse(h.secret)
        assertEquals("1234", h.state.currentNumber)
        assertTrue(h.state.entryReplaced)
    }

    @Test
    fun percentTwiceDoesNotOpenSetup() {
        val h = Harness()
        h.run("12340000", "%", "%", "=")
        assertFalse(h.secret)
        assertEquals("1234", h.state.currentNumber)
        assertTrue(h.state.entryReplaced)
    }

    @Test
    fun percentOnCalculatedResultDoesNotOpenSetup() {
        val h = Harness()
        h.run("123000", "+", "400", "=", "%", "=")
        assertFalse(h.secret)
        assertEquals("1234", h.state.currentNumber)
        assertTrue(h.state.justCalculated)
        assertTrue(h.state.entryReplaced)
    }

    @Test
    fun calculatedResultEqualToCodeDoesNotOpenSetup() {
        val h = Harness()
        h.run("1000", "+", "234", "=", "=")
        assertFalse(h.secret)
        assertEquals("1234", h.state.currentNumber)
        assertEquals("1000 + 234", h.state.lastExpression)
        assertTrue(h.state.justCalculated)
    }

    @Test
    fun digitAfterResultStartsFreshEntry() {
        val h = Harness()
        h.run("1000", "+", "234", "=", "5")
        assertFalse(h.secret)
        assertEquals("5", h.state.currentNumber)
        assertFalse(h.state.justCalculated)
    }

    @Test
    fun digitAfterPercentResultStartsFreshEntry() {
        val h = Harness()
        h.run("123400", "%", "5", "=")
        assertFalse(h.secret)
        assertEquals("5", h.state.currentNumber)
        assertFalse(h.state.entryReplaced)
    }

    @Test
    fun backspaceOnPercentResultClearsWholeEntry() {
        val h = Harness()
        h.run("123400", "%", "⌫", "=")
        assertFalse(h.secret)
        assertEquals("", h.state.currentNumber)
        assertFalse(h.state.entryReplaced)
    }

    @Test
    fun plainArithmeticStillWorks() {
        val h = Harness()
        h.run("7", "×", "8", "=")
        assertEquals("56", h.state.currentNumber)
        assertEquals("7 × 8", h.state.lastExpression)
        assertTrue(h.state.justCalculated)
    }

    @Test
    fun decimalDivisionFormatsLikeBefore() {
        val h = Harness()
        h.run("1", "÷", "4", "=")
        assertEquals("0.25", h.state.currentNumber)
    }

    @Test
    fun percentWithPendingOperatorThenEquals() {
        val h = Harness()
        h.run("200", "+", "10", "%", "=")
        assertFalse(h.secret)
        assertEquals("220", h.state.currentNumber)
        assertEquals("200 + 20", h.state.lastExpression)
    }

    @Test
    fun percentWithMultiplyThenEquals() {
        val h = Harness()
        h.run("200", "×", "10", "%", "=")
        assertFalse(h.secret)
        assertEquals("20", h.state.currentNumber)
        assertEquals("200 × 0.1", h.state.lastExpression)
    }

    @Test
    fun percentWithDivideThenEquals() {
        val h = Harness()
        h.run("200", "÷", "10", "%", "=")
        assertFalse(h.secret)
        assertEquals("2000", h.state.currentNumber)
        assertEquals("200 ÷ 0.1", h.state.lastExpression)
    }

    @Test
    fun divisionByZeroShowsErrorAndNextDigitRecovers() {
        val h = Harness()
        h.run("5", "÷", "0", "=")
        assertEquals("Error", h.state.currentNumber)
        h.run("3")
        assertEquals("3", h.state.currentNumber)
    }

    @Test
    fun hugeResultFallsBackToScientificNotation() {
        val h = Harness()
        h.run("999999999999", "+", "1", "=")
        assertEquals("1.00000E+12", h.state.currentNumber)
    }

    @Test
    fun toggleSignStartsNegativeOperand() {
        val h = Harness()
        h.run("5", "+", "±", "3", "=")
        assertEquals("2", h.state.currentNumber)
        assertEquals("5 + -3", h.state.lastExpression)
    }

    @Test
    fun chainedOperatorsComputeIntermediateResult() {
        val h = Harness()
        h.run("2", "+", "3", "×", "4", "=")
        assertEquals("20", h.state.currentNumber)
        assertEquals("5 × 4", h.state.lastExpression)
    }
}
