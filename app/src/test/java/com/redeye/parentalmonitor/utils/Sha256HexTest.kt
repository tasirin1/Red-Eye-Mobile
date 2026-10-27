package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class Sha256HexTest {
    @Test
    fun knownVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Sha256Hex.hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256Hex.hex("abc"))
        assertEquals("187a884a4242cc6048f4e14e488d997775b1c5be7076ac76c6ce99f7e3b55949", Sha256Hex.hex("123456:789:rahasia:42:true:519"))
    }
}
