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
        // = kedua kini mengulang operasi terakhir
        // (issue #2042) -- hasil hitungan yang sama
        // dengan kode rahasia tetap tidak membuka
        // Setup, dan pengulangannya pun tidak.
        assertEquals("1468", h.state.currentNumber)
        assertEquals("1234 + 234", h.state.lastExpression)
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
    fun backspacePadaErrorMembersihkanKeadaan() {
        // Cakupan #2067: cabang pemulihan Error di
        // kepala backspace membersihkan keadaan.
        val h = Harness()
        h.run("1", "÷", "0", "=")
        assertEquals("Error", h.state.currentNumber)
        h.run("⌫")
        assertEquals("", h.state.currentNumber)
        h.run("5")
        assertEquals("5", h.state.currentNumber)
    }

    @Test
    fun backspacePadaHasilTidakMengubah() {
        // Cakupan #2067: backspace atas hasil hitung
        // adalah no-op (gerbang justCalculated).
        val h = Harness()
        h.run("2", "+", "3", "=")
        assertEquals("5", h.state.currentNumber)
        h.run("⌫")
        assertEquals("5", h.state.currentNumber)
    }

    @Test
    fun backspaceEntriSatuKarakterMengosongkan() {
        // Cakupan #2067: entri 1 karakter menjadi
        // kosong, bukan memotong sebagian.
        val h = Harness()
        h.run("7", "⌫")
        assertEquals("", h.state.currentNumber)
    }

    @Test
    fun backspaceMenghapusAkhiranMinus() {
        // Cakupan #2067: memotong digit terakhir dari
        // entri negatif membuang juga tanda minus yang
        // tertinggal sendirian.
        val h = Harness()
        h.run("5", "±")
        assertEquals("-5", h.state.currentNumber)
        h.run("⌫")
        assertEquals("", h.state.currentNumber)
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
    fun equalsRepeatsLastOperation() {
        val h = Harness()
        h.run("2", "+", "3", "=")
        assertEquals("5", h.state.currentNumber)
        h.press("=")
        assertEquals("8", h.state.currentNumber)
        h.press("=")
        assertEquals("11", h.state.currentNumber)
        assertFalse(h.secret)
    }

    @Test
    fun equalsWithMissingSecondOperandUsesFirstOperand() {
        val h = Harness()
        h.run("5", "+", "=")
        assertEquals("10", h.state.currentNumber)
        h.press("=")
        assertEquals("15", h.state.currentNumber)
    }

    @Test
    fun repeatEqualsUsesNewestOperationAfterNewChain() {
        val h = Harness()
        h.run("2", "+", "3", "=", "+", "4", "=")
        assertEquals("9", h.state.currentNumber)
        h.press("=")
        assertEquals("13", h.state.currentNumber)
    }

    @Test
    fun digitAfterResultClearsRepeatMemory() {
        val h = Harness()
        h.run("2", "+", "3", "=", "7", "=")
        assertEquals("7", h.state.currentNumber)
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

    // Ambang format simetris (issue #1482): hasil bulat
    // negatif sebesar pasangan positifnya tampil utuh,
    // bukan notasi ilmiah terbulatkan.
    @Test
    fun hasilKecilMembulatKeNolTampilIlmiah() {
        // Cakupan #2068: cabang polos yang membulat
        // ke nol pada skala maksimum beralih ke
        // notasi ilmiah, bukan tampil "0".
        val h = Harness()
        h.run("1", "÷", "999999999999", "=")
        assertEquals("1.00000E-12", h.state.currentNumber)
    }

    @Test
    fun skalaTerpaksaNolMembulatkanSetengahKeAtas() {
        // Cakupan #2068: cabang skala ter-coerce 0
        // (bagian bulat sudah 12 digit): pembulatan
        // setengah ke atas pada skala nol.
        val h = Harness()
        h.run("999999999999", "−", "0.5", "=")
        assertEquals("999999999999", h.state.currentNumber)
    }

    @Test
    fun luapanDigitSesudahPembulatanTampilIlmiah() {
        // Cakupan #2068: cabang luapan digit PASCA
        // pembulatan (12 digit menjadi 13) beralih
        // ke notasi ilmiah.
        val h = Harness()
        h.run("999999999999", "+", "0.5", "=")
        assertEquals("1.00000E+12", h.state.currentNumber)
    }

    @Test
    fun clearMenghapusSemuaTermasukIngatanPengulangan() {
        // Cakupan #2062: C menolkan semua bidang
        // termasuk ingatan operasi terakhir #2042 --
        // = sesudahnya tidak boleh mengulang operasi
        // yang sudah dibersihkan pengguna.
        val h = Harness()
        h.run("2", "+", "3", "=")
        assertEquals("5", h.state.currentNumber)
        h.run("C")
        assertEquals("", h.state.currentNumber)
        assertEquals("", h.state.lastExpression)
        assertEquals("", h.state.lastOperator)
        assertEquals("", h.state.lastOperand)
        h.run("=")
        assertEquals("", h.state.currentNumber)
    }

    @Test
    fun errorLaluOperatorMembersihkanKeadaan() {
        // Cakupan #2062: cabang pemulihan Error di
        // kepala setOperator membersihkan keadaan,
        // lalu masukan biasa berjalan normal.
        val h = Harness()
        h.run("1", "÷", "0", "=")
        assertEquals("Error", h.state.currentNumber)
        h.run("+")
        assertEquals("", h.state.currentNumber)
        assertEquals("", h.state.previousNumber)
        assertEquals("", h.state.operator)
        h.run("5")
        assertEquals("5", h.state.currentNumber)
    }

    @Test
    fun errorLaluToggleDanPersenMembersihkanKeadaan() {
        // Cakupan #2062: cabang pemulihan Error di
        // toggleSign dan percent sama-sama
        // membersihkan keadaan lebih dulu.
        val h = Harness()
        h.run("1", "÷", "0", "=", "±")
        assertEquals("", h.state.currentNumber)
        val h2 = Harness()
        h2.run("1", "÷", "0", "=", "%")
        assertEquals("", h2.state.currentNumber)
    }

    @Test
    fun titikDesimalGandaDitolak() {
        // Cakupan #2061: titik kedua pada entri yang
        // sama diabaikan penjaga titik ganda.
        val h = Harness()
        h.run("1", ".", ".", "5")
        assertEquals("1.5", h.state.currentNumber)
    }

    @Test
    fun titikDiAwalMenjadiNolKoma() {
        // Cakupan #2061: titik pada entri kosong
        // menjadi awalan 0.
        val h = Harness()
        h.run(".", "5")
        assertEquals("0.5", h.state.currentNumber)
    }

    @Test
    fun penjumlahanDesimalDuaOperand() {
        // Cakupan #2061: desimal di kedua operand
        // dihitung dan diformat utuh.
        val h = Harness()
        h.run("1.5", "+", "2.25", "=")
        assertEquals("3.75", h.state.currentNumber)
        assertEquals("1.5 + 2.25", h.state.lastExpression)
    }

    @Test
    fun titikSesudahHasilMulaiEntriBaru() {
        // Cakupan #2061: titik sesudah hasil
        // membersihkan keadaan hitung dan memulai
        // entri desimal baru dari 0.
        val h = Harness()
        h.run("2", "+", "3", "=", ".", "5")
        assertEquals("0.5", h.state.currentNumber)
    }

    @Test
    fun persenOperandKeduaLaluUlangSamaDengan() {
        // Regresi #2059: persen pada operand kedua
        // dikonsumsi perhitungan sungguhan, jadi =
        // berikutnya wajib mengulang operasi terakhir
        // seperti jalur tanpa persen (200+20== -> 240).
        val h = Harness()
        h.run("200", "+", "10", "%", "=")
        assertEquals("220", h.state.currentNumber)
        h.run("=")
        assertEquals("240", h.state.currentNumber)
        assertEquals("220 + 20", h.state.lastExpression)
    }

    @Test
    fun persenOperandKeduaMinusLaluUlangSamaDengan() {
        // Pasangan simetris #2059 untuk pengurangan:
        // 200 - 10% = 180, = lagi -> 160.
        val h = Harness()
        h.run("200", "−", "10", "%", "=")
        assertEquals("180", h.state.currentNumber)
        h.run("=")
        assertEquals("160", h.state.currentNumber)
    }

    @Test
    fun negativeHugeIntegerShowsInFull() {
        val h = Harness()
        h.run("0", "−", "999999999999", "=")
        assertEquals("-999999999999", h.state.currentNumber)
        // Asersi ekspresi membuktikan pengurangan
        // sungguhan berjalan (issue #2053): token
        // minus ASCII sebelumnya jatuh ke masukan
        // angka sehingga string terbentuk tekstual
        // tanpa perhitungan apa pun.
        assertEquals("0 − 999999999999", h.state.lastExpression)
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

    @Test
    fun secretCodeAsSecondOperandDoesNotOpenSetup() {
        val h = Harness()
        h.run("5", "+", "1234", "=")
        assertFalse(h.secret)
        assertEquals("1239", h.state.currentNumber)
    }

    @Test
    fun secretGateBlockedByPreviousNumberAlone() {
        // Syarat previousNumber kosong di gerbang
        // rahasia (issue #2075): keadaan pasangan
        // terisi sebagian hanya lahir dari pemulihan
        // Bundle, bukan tombol -- diuji langsung ke
        // state. Mutan yang membuang syarat ini
        // membuka Setup dari keadaan ini.
        val h = Harness()
        h.state.currentNumber = "1234"
        h.state.previousNumber = "5"
        assertFalse(h.state.calculate())
        assertEquals("1234", h.state.currentNumber)
    }

    @Test
    fun secretGateBlockedByOperatorAlone() {
        // Pasangan cerminnya (issue #2075): operator
        // terisi saja sudah menggagalkan gerbang.
        val h = Harness()
        h.state.currentNumber = "1234"
        h.state.operator = "+"
        assertFalse(h.state.calculate())
        assertEquals("1234", h.state.currentNumber)
    }
    @Test
    fun digitTypedOverZeroReplacesEntry() {
        val h = Harness()
        h.run("0", "5")
        assertEquals("5", h.state.currentNumber)
    }

    @Test
    fun digitTypedOverMinusZeroReplacesEntry() {
        val h = Harness()
        h.run("5", "+", "±", "0", "5")
        assertEquals("-5", h.state.currentNumber)
    }

    @Test
    fun dotAfterLoneMinusStartsMinusZeroPoint() {
        val h = Harness()
        h.run("5", "+", "±", ".", "5")
        assertEquals("-0.5", h.state.currentNumber)
    }

    @Test
    fun digitCapStopsAtTwelveDigits() {
        val h = Harness()
        h.run("1234567890123")
        assertEquals("123456789012", h.state.currentNumber)
    }
    @Test
    fun operatorSwapUsesLatestOperator() {
        val h = Harness()
        h.run("5", "+", "×")
        assertEquals("×", h.state.operator)
        h.run("3", "=")
        assertEquals("15", h.state.currentNumber)
    }

    @Test
    fun operatorAfterChainedErrorKeepsStateClean() {
        val h = Harness()
        h.run("1", "÷", "0", "×")
        assertEquals("Error", h.state.currentNumber)
        assertEquals("", h.state.previousNumber)
        assertEquals("", h.state.operator)
        h.run("5")
        assertEquals("5", h.state.currentNumber)
    }
    @Test
    fun toggleSignOnLoneMinusClearsIt() {
        val h = Harness()
        h.run("5", "+", "±", "±")
        assertEquals("", h.state.currentNumber)
    }

    @Test
    fun toggleSignOnZeroKeepsZero() {
        val h = Harness()
        h.run("0", "±")
        assertEquals("0", h.state.currentNumber)
    }

    @Test
    fun toggleSignOnEmptyEntryIsNoOp() {
        val h = Harness()
        h.run("±")
        assertEquals("", h.state.currentNumber)
    }
    @Test
    fun repeatingDecimalUsesFullEightDigitScale() {
        val h = Harness()
        h.run("1", "÷", "3", "=")
        assertEquals("0.33333333", h.state.currentNumber)
    }

    @Test
    fun negativeRepeatingDecimalStaysPlainWithSignExtra() {
        val h = Harness()
        h.run("0", "−", "10000", "÷", "3", "=")
        assertEquals("-3333.3333333", h.state.currentNumber)
    }
    @Test
    fun digitCapCountsOnlyDigitsForNegativeEntry() {
        val h = Harness()
        h.run("5", "+", "±", "123456789012")
        assertEquals("-123456789012", h.state.currentNumber)
    }

    @Test
    fun digitCapCountsOnlyDigitsForDecimalEntry() {
        val h = Harness()
        h.run("0.123456789012")
        assertEquals("0.12345678901", h.state.currentNumber)
    }
    @Test
    fun operatorSwapFromLoneMinusEntryKeepsOperandSign() {
        val h = Harness()
        h.run("5", "+", "±", "×")
        assertEquals("×", h.state.operator)
        assertEquals("-", h.state.currentNumber)
        h.run("3", "=")
        assertEquals("-15", h.state.currentNumber)
    }
}
