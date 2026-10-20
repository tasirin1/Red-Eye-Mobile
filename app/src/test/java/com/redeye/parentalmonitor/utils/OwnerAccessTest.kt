package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerAccessTest {
    @Test
    fun requiresOwnerIdWhenPinIsActive() {
        assertTrue(OwnerAccess.isOwner("10", "10", 10L, true))
        assertFalse(OwnerAccess.isOwner("20", "20", 10L, true))
        assertFalse(OwnerAccess.isOwner("20", "10", 10L, true))
    }

    @Test
    fun blocksChatFallbackWhilePinIsActive() {
        assertFalse(OwnerAccess.isOwner("10", "10", 0L, true))
        assertFalse(OwnerAccess.isOwner("20", "10", 0L, true))
    }

    @Test
    fun allowsChatFallbackWithoutPin() {
        assertTrue(OwnerAccess.isOwner("10", "10", 0L, false))
        assertTrue(OwnerAccess.isOwner("20", "20", 10L, false))
        assertFalse(OwnerAccess.isOwner("20", "10", 0L, false))
        assertFalse(OwnerAccess.isOwner("", "", 0L, false))
    }
}
