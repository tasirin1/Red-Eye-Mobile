package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkUtilsTest {

    @Test
    fun blockAgeJamGanda() {
        // Boot sama: usia dari jam monoton walau jam dinding dimundurkan.
        assertEquals(600_000L, NetworkUtils.blockAgeMs(1_000_000L, 500_000L, 1_000_000L + 600_000L - 3_600_000L, 1_100_000L))
        // Tanpa jangkar + jam mundur: -1 (perilaku warisan).
        assertEquals(-1L, NetworkUtils.blockAgeMs(1_000_000L, 0L, 500_000L, 500_000L))
        // Sesudah reboot (elapsed mengecil): kembali ke jam dinding.
        assertEquals(1_900_000L, NetworkUtils.blockAgeMs(1_000_000L, 500_000L, 2_900_000L, 100_000L))
        // Issue #1008: cap elapsed boot LAMA terlampaui lagi oleh
        // elapsed boot baru (penanda di elapsed 5 hari; kini 6 hari
        // dinding kemudian, elapsed boot baru 5 hari + 1 jam) —
        // usia harus dari jam dinding (6 hari), bukan selisih
        // elapsed palsu (1 jam).
        val hari = 86_400_000L
        assertEquals(6 * hari, NetworkUtils.blockAgeMs(10 * hari, 5 * hari, 16 * hari, 5 * hari + 3_600_000L))
    }

    @Test
    fun remainJamGanda() {
        // Jam dinding dimajukan 1 jam sesudah 60 detik nyata: sisa tetap
        // dihitung dari jam monoton (540 detik), bukan hangus.
        assertEquals(540_000L, NetworkUtils.remainMsDual(1_600_000L, 1_000_000L, 500_000L, 4_660_000L, 560_000L, 3_600_000L))
        // Warisan tanpa jangkar: sisa jam dinding polos, terkurung batas.
        assertEquals(300_000L, NetworkUtils.remainMsDual(1_600_000L, 0L, 0L, 1_300_000L, 0L, 3_600_000L))
        assertEquals(0L, NetworkUtils.remainMsDual(0L, 0L, 0L, 1_300_000L, 0L, 3_600_000L))
    }

    @Test
    fun retryAfterDariJson() {
        assertEquals(17L, NetworkUtils.parseRetryAfter("{\"parameters\":{\"retry_after\":17}}"))
    }

    @Test
    fun retryAfterDibatasiRentang() {
        assertEquals(1L, NetworkUtils.parseRetryAfter("{\"parameters\":{\"retry_after\":0}}"))
        assertEquals(3600L, NetworkUtils.parseRetryAfter("{\"parameters\":{\"retry_after\":99999}}"))
    }

    @Test
    fun retryAfterFallbackRegexDanBawaan() {
        assertEquals(42L, NetworkUtils.parseRetryAfter("Too Many Requests: retry after 42"))
        assertEquals(5L, NetworkUtils.parseRetryAfter(null))
        assertEquals(5L, NetworkUtils.parseRetryAfter(""))
        assertEquals(5L, NetworkUtils.parseRetryAfter("bukan json sama sekali"))
    }

    @Test
    fun migrasiChatIdDariJson() {
        assertEquals("-1001234567890", NetworkUtils.extractMigratedChatId("{\"parameters\":{\"migrate_to_chat_id\":-1001234567890}}"))
    }

    @Test
    fun migrasiChatIdFallbackRegexDanLegacy() {
        assertEquals("777", NetworkUtils.extractMigratedChatId("error \"migrate_to_chat_id\":777 di tengah"))
        assertEquals("-55", NetworkUtils.extractMigratedChatId("Bad Request: new chat id: -55"))
    }

    @Test
    fun migrasiChatIdNolDanKosongDitolak() {
        assertNull(NetworkUtils.extractMigratedChatId("{\"parameters\":{\"migrate_to_chat_id\":0}}"))
        assertNull(NetworkUtils.extractMigratedChatId(null))
        assertNull(NetworkUtils.extractMigratedChatId(""))
        assertNull(NetworkUtils.extractMigratedChatId("{\"ok\":false}"))
    }
}
