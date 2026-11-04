package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueCapTest {

    @Test
    fun atPlainCapNotOver() {
        assertFalse(QueueCap.overCap(100, anyPriorityQueued = false, incomingPriority = false))
    }

    @Test
    fun abovePlainCapOver() {
        assertTrue(QueueCap.overCap(101, anyPriorityQueued = false, incomingPriority = false))
    }

    @Test
    fun queuedPriorityGrantsBonus() {
        assertFalse(QueueCap.overCap(105, anyPriorityQueued = true, incomingPriority = false))
        assertTrue(QueueCap.overCap(106, anyPriorityQueued = true, incomingPriority = false))
    }

    @Test
    fun totalCharsCap() {
        assertFalse(QueueCap.overTotalChars(QueueCap.MAX_TOTAL_CHARS))
        assertTrue(QueueCap.overTotalChars(QueueCap.MAX_TOTAL_CHARS + 1))
    }

    @Test
    fun incomingPriorityGrantsBonus() {
        assertFalse(QueueCap.overCap(105, anyPriorityQueued = false, incomingPriority = true))
        assertTrue(QueueCap.overCap(106, anyPriorityQueued = false, incomingPriority = true))
    }
}
