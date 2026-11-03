package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Issue #1028: inti kesegaran antrean (#967) kini logika
// murni teruji. Nilai harapan dibuktikan dengan porting
// 1:1 ke Python sebelum asersi ditulis.
class QueueFreshTest {

    private val cutoff = 1_699_000_000_000L
    private val nowElapsed = 900_000_000L // uptime ±10,4 hari
    private val bootAt = 1_700_000_000_000L - nowElapsed

    @Test
    fun bootSamaMarkMasaDepanIkutDinding() {
        assertTrue(QueueFresh.isFresh(cutoff + 1000L, nowElapsed + 5000L, bootAt, cutoff, nowElapsed, bootAt))
        assertFalse(QueueFresh.isFresh(cutoff - 1000L, nowElapsed + 5000L, bootAt, cutoff, nowElapsed, bootAt))
    }

    @Test
    fun bootSamaUsiaMonotonDiBawahTujuhHariSegar() {
        assertTrue(QueueFresh.isFresh(cutoff - 1000L, nowElapsed - 86_400_000L, bootAt, cutoff, nowElapsed, bootAt))
    }

    @Test
    fun bootSamaUsiaMonotonLewatTujuhHariIkutDinding() {
        assertFalse(QueueFresh.isFresh(cutoff - 1000L, nowElapsed - 8 * 86_400_000L, bootAt, cutoff, nowElapsed, bootAt))
        assertTrue(QueueFresh.isFresh(cutoff + 1000L, nowElapsed - 8 * 86_400_000L, bootAt, cutoff, nowElapsed, bootAt))
    }

    @Test
    fun bootBedaSelaluIkutDinding() {
        assertFalse(QueueFresh.isFresh(cutoff - 1000L, nowElapsed - 1000L, bootAt - 10_000_000L, cutoff, nowElapsed, bootAt))
        assertTrue(QueueFresh.isFresh(cutoff + 1000L, nowElapsed - 1000L, bootAt - 10_000_000L, cutoff, nowElapsed, bootAt))
    }

    @Test
    fun tanpaMarkIkutDinding() {
        assertTrue(QueueFresh.isFresh(cutoff + 1000L, 0L, bootAt, cutoff, nowElapsed, bootAt))
        assertFalse(QueueFresh.isFresh(cutoff - 1000L, 0L, bootAt, cutoff, nowElapsed, bootAt))
    }

    @Test
    fun penyelamatanLompatanJamButuhBuktiLengkap() {
        assertTrue(QueueFresh.clockJumpRescue(6, 2, allBootUnknown = true, anyFutureDated = true))
        assertFalse(QueueFresh.clockJumpRescue(6, 2, allBootUnknown = true, anyFutureDated = false))
        assertFalse(QueueFresh.clockJumpRescue(5, 0, allBootUnknown = true, anyFutureDated = true))
        assertFalse(QueueFresh.clockJumpRescue(6, 3, allBootUnknown = true, anyFutureDated = true))
        assertFalse(QueueFresh.clockJumpRescue(6, 2, allBootUnknown = false, anyFutureDated = true))
    }
}
