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
}
