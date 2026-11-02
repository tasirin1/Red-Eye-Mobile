package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NumericArgTest {

    @Test
    fun angkaPolosDanBersatuan() {
        assertEquals(500, NumericArg.parse("500"))
        assertEquals(500, NumericArg.parse("500 meter"))
        assertEquals(500, NumericArg.parse("500 m"))
        assertEquals(500, NumericArg.parse("500meter"))
        assertEquals(30, NumericArg.parse("30 detik"))
        assertEquals(30, NumericArg.parse("30detik"))
        assertEquals(5, NumericArg.parse(" 5 menit "))
        assertEquals(6, NumericArg.parse("6 JAM"))
    }

    @Test
    fun bentukLainDitolak() {
        assertNull(NumericArg.parse(""))
        assertNull(NumericArg.parse("abc"))
        assertNull(NumericArg.parse("500 abc"))
        assertNull(NumericArg.parse("500 meter ekstra"))
        assertNull(NumericArg.parse("5.5"))
        assertNull(NumericArg.parse("-5"))
        assertNull(NumericArg.parse("meter"))
    }
}
