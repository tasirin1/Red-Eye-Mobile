package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceLogicTest {
    @Test
    fun transitionAdoptsStateWithoutAccuracyGate() {
        assertEquals(GeofenceLogic.Transition(1, false), GeofenceLogic.transition(0, true, 10_000f, 200))
        assertEquals(GeofenceLogic.Transition(2, false), GeofenceLogic.transition(3, false, -1f, 200))
        assertEquals(GeofenceLogic.Transition(1, false), GeofenceLogic.transition(-1, true, 51f, 50))
    }

    @Test
    fun transitionNoChangeNoWrite() {
        assertEquals(GeofenceLogic.Transition(null, false), GeofenceLogic.transition(1, true, -1f, 200))
        assertEquals(GeofenceLogic.Transition(null, false), GeofenceLogic.transition(2, false, 10_000f, 200))
    }

    @Test
    fun transitionChangeRequiresAccuracyWithinRadius() {
        assertEquals(GeofenceLogic.Transition(2, true), GeofenceLogic.transition(1, false, -1f, 200))
        assertEquals(GeofenceLogic.Transition(1, true), GeofenceLogic.transition(2, true, 200f, 200))
        assertEquals(GeofenceLogic.Transition(1, true), GeofenceLogic.transition(2, true, 0f, 200))
        assertEquals(GeofenceLogic.Transition(null, false), GeofenceLogic.transition(1, false, 201f, 200))
        assertEquals(GeofenceLogic.Transition(null, false), GeofenceLogic.transition(2, true, 5_000f, 200))
    }

    @Test
    fun jemputHitBoundaries() {
        assertFalse(GeofenceLogic.jemputHit(0, 0f, -1f))
        assertTrue(GeofenceLogic.jemputHit(200, 200f, -1f))
        assertFalse(GeofenceLogic.jemputHit(200, 201f, -1f))
        assertTrue(GeofenceLogic.jemputHit(200, 150f, 200f))
        assertFalse(GeofenceLogic.jemputHit(200, 150f, 201f))
        assertTrue(GeofenceLogic.jemputHit(200, 150f, -1f))
    }

    @Test
    fun statusTextFormatting() {
        assertEquals("-6.180058,107.384718 ±150m", GeofenceLogic.statusText(-6_180_058L, 107_384_718L, 150))
        assertEquals("mati", GeofenceLogic.statusText(Long.MIN_VALUE, 107_384_718L, 150))
        assertEquals("mati", GeofenceLogic.statusText(-6_180_058L, Long.MIN_VALUE, 150))
        assertEquals("mati", GeofenceLogic.statusText(-6_180_058L, 107_384_718L, 0))
    }

    @Test
    fun configuredSentinels() {
        assertFalse(GeofenceLogic.configured(Long.MIN_VALUE, 106_800_000L, 200))
        assertFalse(GeofenceLogic.configured(-6_200_000L, Long.MIN_VALUE, 200))
        assertFalse(GeofenceLogic.configured(-6_200_000L, 106_800_000L, 0))
        assertFalse(GeofenceLogic.configured(-6_200_000L, 106_800_000L, -5))
        assertTrue(GeofenceLogic.configured(-6_200_000L, 106_800_000L, 200))
    }
}
