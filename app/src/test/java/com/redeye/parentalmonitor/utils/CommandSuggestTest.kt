package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandSuggestTest {
    @Test
    fun editDistanceKnownValues() {
        assertEquals(0, CommandSuggest.editDistance("", ""))
        assertEquals(3, CommandSuggest.editDistance("abc", ""))
        assertEquals(4, CommandSuggest.editDistance("", "abcd"))
        assertEquals(3, CommandSuggest.editDistance("kitten", "sitting"))
        assertEquals(0, CommandSuggest.editDistance("/help", "/help"))
        assertEquals(2, CommandSuggest.editDistance("/hlep", "/help"))
        assertEquals(1, CommandSuggest.editDistance("/status", "/sttus"))
        assertEquals(2, CommandSuggest.editDistance("flaw", "lawn"))
    }

    @Test
    fun transposedEqualsDetectsSingleSwap() {
        assertTrue(CommandSuggest.transposedEquals("/help", "/hlep"))
        assertTrue(CommandSuggest.transposedEquals("abcd", "abdc"))
        assertTrue(CommandSuggest.transposedEquals("abcd", "bacd"))
        assertTrue(CommandSuggest.transposedEquals("abcd", "acbd"))
        assertTrue(CommandSuggest.transposedEquals("ab", "ba"))
        assertTrue(CommandSuggest.transposedEquals("aab", "aba"))
        assertFalse(CommandSuggest.transposedEquals("abcd", "abce"))
        assertFalse(CommandSuggest.transposedEquals("abcd", "adcb"))
        assertFalse(CommandSuggest.transposedEquals("abc", "abc"))
        assertFalse(CommandSuggest.transposedEquals("", ""))
    }

    @Test
    fun suggestCommandBehaviour() {
        assertNull(CommandSuggest.suggestCommand("/help"))
        assertNull(CommandSuggest.suggestCommand("/night"))
        assertNull(CommandSuggest.suggestCommand(""))
        assertNull(CommandSuggest.suggestCommand("/xyzqq"))
        assertNull(CommandSuggest.suggestCommand("/vers"))
        assertEquals("/help", CommandSuggest.suggestCommand("/hlep"))
        assertEquals("/help", CommandSuggest.suggestCommand("/hlp"))
        assertEquals("/status", CommandSuggest.suggestCommand("/sttus"))
        assertEquals("/start", CommandSuggest.suggestCommand("/stat"))
        assertEquals("/log", CommandSuggest.suggestCommand("/logg"))
        assertEquals("/log", CommandSuggest.suggestCommand("/lok"))
        assertEquals("/ping", CommandSuggest.suggestCommand("/pin"))
        assertEquals("/night", CommandSuggest.suggestCommand("/nigth"))
        assertEquals("/battery", CommandSuggest.suggestCommand("/batery"))
    }
}
