package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContactsPagingTest {

    @Test
    fun argumenKosongBerartiHalamanPertama() {
        assertEquals(1, ContactsPaging.pageArg("") ?: -1)
        assertEquals(1, ContactsPaging.pageArg("   ") ?: -1)
    }

    @Test
    fun angkaKecilBerartiHalaman() {
        assertEquals(1, ContactsPaging.pageArg("1") ?: -1)
        assertEquals(2, ContactsPaging.pageArg(" 2 ") ?: -1)
        assertEquals(30, ContactsPaging.pageArg("30") ?: -1)
        assertEquals(999, ContactsPaging.pageArg("999") ?: -1)
    }

    @Test
    fun namaDanNomorTeleponBukanHalaman() {
        assertNull(ContactsPaging.pageArg("budi"))
        assertNull(ContactsPaging.pageArg("0"))
        assertNull(ContactsPaging.pageArg("01"))
        assertNull(ContactsPaging.pageArg("0812"))
        assertNull(ContactsPaging.pageArg("1234"))
        assertNull(ContactsPaging.pageArg("1000"))
    }

    @Test
    fun partisiArgumenHalaman() {
        assertEquals(ContactsPaging.ArgKind.PAGE, ContactsPaging.argKind(""))
        assertEquals(ContactsPaging.ArgKind.PAGE, ContactsPaging.argKind("   "))
        assertEquals(ContactsPaging.ArgKind.PAGE, ContactsPaging.argKind("1"))
        assertEquals(ContactsPaging.ArgKind.PAGE, ContactsPaging.argKind(" 2 "))
        assertEquals(ContactsPaging.ArgKind.PAGE, ContactsPaging.argKind("999"))
    }

    @Test
    fun partisiArgumenDigitDiBawahAmbangNomor() {
        assertEquals(ContactsPaging.ArgKind.PAGE_HINT, ContactsPaging.argKind("0"))
        assertEquals(ContactsPaging.ArgKind.PAGE_HINT, ContactsPaging.argKind("01"))
        assertEquals(ContactsPaging.ArgKind.PAGE_HINT, ContactsPaging.argKind("007"))
        assertEquals(ContactsPaging.ArgKind.PAGE_HINT, ContactsPaging.argKind("0812"))
        assertEquals(ContactsPaging.ArgKind.PAGE_HINT, ContactsPaging.argKind("1234"))
        assertEquals(ContactsPaging.ArgKind.PAGE_HINT, ContactsPaging.argKind("1000"))
        assertEquals(ContactsPaging.ArgKind.PAGE_HINT, ContactsPaging.argKind("123456"))
    }

    @Test
    fun partisiArgumenNomorDigitPolos() {
        assertEquals(ContactsPaging.ArgKind.NUMBER_SEARCH, ContactsPaging.argKind("1234567"))
        assertEquals(ContactsPaging.ArgKind.NUMBER_SEARCH, ContactsPaging.argKind("08123456789"))
        assertEquals(ContactsPaging.ArgKind.NUMBER_SEARCH, ContactsPaging.argKind("628123456789"))
        assertEquals(ContactsPaging.ArgKind.NUMBER_SEARCH, ContactsPaging.argKind("08129876543"))
    }

    @Test
    fun partisiArgumenPencarianBebas() {
        assertEquals(ContactsPaging.ArgKind.SEARCH, ContactsPaging.argKind("budi"))
        assertEquals(ContactsPaging.ArgKind.SEARCH, ContactsPaging.argKind("Sari"))
        assertEquals(ContactsPaging.ArgKind.SEARCH, ContactsPaging.argKind("budi2"))
        assertEquals(ContactsPaging.ArgKind.SEARCH, ContactsPaging.argKind("+628123456789"))
        assertEquals(ContactsPaging.ArgKind.SEARCH, ContactsPaging.argKind("0812-3456"))
        assertEquals(ContactsPaging.ArgKind.SEARCH, ContactsPaging.argKind("0812 3456 789"))
        assertEquals(ContactsPaging.ArgKind.SEARCH, ContactsPaging.argKind("12a"))
    }
}
