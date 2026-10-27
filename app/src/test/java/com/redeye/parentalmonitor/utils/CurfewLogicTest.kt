package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurfewLogicTest {
    @Test
    fun validTimeFormat() {
        assertTrue(CurfewLogic.isValidTime("21:30"))
        assertTrue(CurfewLogic.isValidTime("05:00"))
        assertTrue(CurfewLogic.isValidTime("00:00"))
        assertTrue(CurfewLogic.isValidTime("23:59"))
        assertFalse(CurfewLogic.isValidTime("24:00"))
        assertFalse(CurfewLogic.isValidTime("9:30"))
        assertFalse(CurfewLogic.isValidTime("21:60"))
        assertFalse(CurfewLogic.isValidTime("2130"))
        assertFalse(CurfewLogic.isValidTime(""))
    }

    @Test
    fun eveningCurfewWindow() {
        assertFalse(CurfewLogic.inWindow("21:00", 20, 59))
        assertTrue(CurfewLogic.inWindow("21:00", 21, 0))
        assertTrue(CurfewLogic.inWindow("21:00", 4, 59))
        assertFalse(CurfewLogic.inWindow("21:00", 5, 0))
        assertTrue(CurfewLogic.inWindow("12:00", 12, 0))
    }

    @Test
    fun earlyCurfewWindow() {
        assertFalse(CurfewLogic.inWindow("03:30", 3, 29))
        assertTrue(CurfewLogic.inWindow("03:30", 3, 30))
        assertTrue(CurfewLogic.inWindow("03:30", 4, 59))
        assertFalse(CurfewLogic.inWindow("03:30", 5, 0))
        assertTrue(CurfewLogic.inWindow("00:00", 0, 0))
    }

    @Test
    fun morningCurfewNeverFires() {
        assertFalse(CurfewLogic.inWindow("07:00", 7, 30))
        assertFalse(CurfewLogic.inWindow("07:00", 4, 0))
        assertFalse(CurfewLogic.inWindow("11:59", 11, 59))
    }

    @Test
    fun invalidCurfewStrings() {
        assertFalse(CurfewLogic.inWindow("", 21, 0))
        assertFalse(CurfewLogic.inWindow("9:00", 21, 0))
        assertFalse(CurfewLogic.inWindow("24:00", 21, 0))
        assertFalse(CurfewLogic.inWindow("21:60", 21, 0))
    }

    @Test
    fun schoolHoursRule() {
        assertTrue(CurfewLogic.schoolHours(java.util.Calendar.MONDAY, 8))
        assertTrue(CurfewLogic.schoolHours(java.util.Calendar.FRIDAY, 15))
        assertFalse(CurfewLogic.schoolHours(java.util.Calendar.MONDAY, 7))
        assertFalse(CurfewLogic.schoolHours(java.util.Calendar.MONDAY, 16))
        assertFalse(CurfewLogic.schoolHours(java.util.Calendar.SATURDAY, 10))
        assertFalse(CurfewLogic.schoolHours(java.util.Calendar.SUNDAY, 10))
    }
}
