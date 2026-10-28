package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NetworkUtilsTest {

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
