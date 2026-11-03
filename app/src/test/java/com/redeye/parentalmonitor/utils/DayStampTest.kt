package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Nilai harapan dibuktikan porting Python 1:1 lebih dulu
// (kasus yang sama persis, 2026-11-03).
class DayStampTest {
    private val h = 3_600_000L
    private val now = 1_700_000_000_000L
    private val today = "2026-11-03"

    @Test fun kosongTidakMemblokir() {
        assertFalse(DayStamp.blocks("", 0L, today, now))
    }

    @Test fun kemarinTidakMemblokir() {
        assertFalse(DayStamp.blocks("2026-11-02", now - 20 * h, today, now))
    }

    @Test fun hariIniWarisanTanpaEpochMemblokir() {
        assertTrue(DayStamp.blocks(today, 0L, today, now))
    }

    @Test fun hariIniSegarMemblokir() {
        assertTrue(DayStamp.blocks(today, now - 1 * h, today, now))
    }

    @Test fun hariIniPas24JamBasi() {
        assertFalse(DayStamp.blocks(today, now - 24 * h, today, now))
    }

    @Test fun hariIniDiBawah24JamMasihMemblokir() {
        assertTrue(DayStamp.blocks(today, now - (24 * h - 60_000L), today, now))
    }

    @Test fun tanggalDepanSegarMemblokir() {
        assertTrue(DayStamp.blocks("2026-11-04", now - 1 * h, today, now))
    }

    @Test fun tanggalDepanBasiTidakMemblokir() {
        assertFalse(DayStamp.blocks("2026-11-04", now - 25 * h, today, now))
    }

    @Test fun tanggalDepanWarisanMemblokir() {
        assertTrue(DayStamp.blocks("2026-11-04", 0L, today, now))
    }

    @Test fun epochMasaDepanTetapMemblokir() {
        assertTrue(DayStamp.blocks(today, now + 2 * h, today, now))
    }

    @Test fun kunciHariIniKosongTidakMemblokir() {
        assertFalse(DayStamp.blocks(today, now - 1 * h, "", now))
    }
}
