package com.redeye.parentalmonitor.utils

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FgsTypesTest {
    @Test
    fun baseBySdk() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, FgsTypes.base(28))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, FgsTypes.base(33))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, FgsTypes.base(34))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, FgsTypes.base(35))
    }

    @Test
    fun computeCombinesPermissions() {
        val su = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        val cam = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val loc = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        val mp = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        assertEquals(su or cam or mic, FgsTypes.compute(34, camera = true, mic = true, location = false, projection = false))
        assertEquals(su or cam or mic or loc or mp, FgsTypes.compute(34, camera = true, mic = true, location = true, projection = true))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC or cam or mp, FgsTypes.compute(29, camera = true, mic = false, location = false, projection = true))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, FgsTypes.compute(28, camera = false, mic = false, location = false, projection = true))
    }

    @Test
    fun ladderDegradesInOrder() {
        val su = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        val cam = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val loc = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        val mp = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        val full = su or cam or mic or loc or mp
        assertEquals(
            listOf(full, su or cam or mic or loc, su or mp, su),
            FgsTypes.ladder(full, su)
        )
        val ds = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        assertEquals(listOf(ds or cam, ds), FgsTypes.ladder(ds or cam, ds))
        assertEquals(listOf(ds), FgsTypes.ladder(ds, ds))
    }

    @Test
    fun describeNames() {
        val su = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        val ds = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        val cam = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val loc = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        val mp = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        assertEquals("-", FgsTypes.describe(0))
        assertEquals("dataSync", FgsTypes.describe(ds))
        assertEquals("camera", FgsTypes.describe(cam))
        assertEquals("dataSync+camera+mic", FgsTypes.describe(ds or cam or mic))
        assertEquals("screen", FgsTypes.describe(mp))
        assertEquals("specialUse", FgsTypes.describe(su))
        assertEquals("location+camera+mic+screen+specialUse", FgsTypes.describe(su or cam or mic or loc or mp))
        assertEquals("lain", FgsTypes.describe(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK))
    }

    @Test
    fun hasCameraAndCooldown() {
        val cam = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        val loc = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        assertTrue(FgsTypes.hasCamera(cam or loc))
        assertFalse(FgsTypes.hasCamera(loc))
        assertFalse(FgsTypes.hasCamera(0))
        assertEquals(1_800_000L, FgsTypes.wakeCooldownMs(0))
        assertEquals(1_800_000L, FgsTypes.wakeCooldownMs(2))
        assertEquals(21_600_000L, FgsTypes.wakeCooldownMs(3))
        assertEquals(21_600_000L, FgsTypes.wakeCooldownMs(10))
    }
}
