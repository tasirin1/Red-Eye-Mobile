package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerSaveTest {

    @Test
    fun cameraEffectiveIntervals() {
        assertEquals(60, PowerSave.cameraIntervalEff(60, true))
        assertEquals(60, PowerSave.cameraIntervalEff(30, true))
        assertEquals(60, PowerSave.cameraIntervalEff(45, true))
        assertEquals(0, PowerSave.cameraIntervalEff(0, true))
        assertEquals(0, PowerSave.cameraIntervalEff(-1, true))
        assertEquals(20, PowerSave.cameraIntervalEff(20, false))
        assertEquals(60, PowerSave.cameraIntervalEff(90, false))
    }

    @Test
    fun patrolEffectiveIntervals() {
        assertEquals(180, PowerSave.patrolIntervalEff(180, true))
        assertEquals(180, PowerSave.patrolIntervalEff(120, true))
        assertEquals(120, PowerSave.patrolIntervalEff(60, true))
        assertEquals(10, PowerSave.patrolIntervalEff(5, true))
        assertEquals(5, PowerSave.patrolIntervalEff(3, false))
        assertEquals(180, PowerSave.patrolIntervalEff(200, true))
    }

    @Test
    fun slowedFlagsAtCaps() {
        assertFalse(PowerSave.cameraSlowed(60))
        assertTrue(PowerSave.cameraSlowed(59))
        assertFalse(PowerSave.cameraSlowed(0))
        assertFalse(PowerSave.patrolSlowed(180))
        assertTrue(PowerSave.patrolSlowed(179))
        assertTrue(PowerSave.patrolSlowed(120))
    }
}
