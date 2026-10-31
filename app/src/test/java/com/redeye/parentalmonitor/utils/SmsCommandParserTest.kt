package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmsCommandParserTest {

    @Test
    fun pesanDiawaliAngkaTidakMenggabungNomor() {
        val parsed = SmsCommandParser.parse("08123456789 2 orang")
        assertEquals("08123456789", parsed?.number)
        assertEquals("08123456789", parsed?.normalized)
        assertEquals("2 orang", parsed?.text)
    }

    @Test
    fun nomorDiakhiriKomaTetapUtuh() {
        val parsed = SmsCommandParser.parse("0812.3456.7890, halo")
        assertEquals("0812.3456.7890,", parsed?.number)
        assertEquals("081234567890", parsed?.normalized)
        assertEquals("halo", parsed?.text)
    }

    @Test
    fun nomorBerspasiTetapUtuh() {
        val parsed = SmsCommandParser.parse("0812 3456 7890 Halo")
        assertEquals("0812 3456 7890", parsed?.number)
        assertEquals("081234567890", parsed?.normalized)
        assertEquals("Halo", parsed?.text)
    }

    @Test
    fun nomorInternasionalBerspasi() {
        val parsed = SmsCommandParser.parse("+62 812 3456 7890 Halo")
        assertEquals("+62 812 3456 7890", parsed?.number)
        assertEquals("+6281234567890", parsed?.normalized)
        assertEquals("Halo", parsed?.text)
    }

    @Test
    fun tandaPisahDiterima() {
        val parsed = SmsCommandParser.parse("(0812) 3456-7890 Halo")
        assertEquals("(0812) 3456-7890", parsed?.number)
        assertEquals("081234567890", parsed?.normalized)
        assertEquals("Halo", parsed?.text)
    }

    @Test
    fun pesanAngkaPanjangTidakMenggabungNomor() {
        val parsed = SmsCommandParser.parse("08123456789 123456")
        assertEquals("08123456789", parsed?.number)
        assertEquals("123456", parsed?.text)
    }

    @Test
    fun pesanAngkaSetelahNomorBerspasi() {
        val parsed = SmsCommandParser.parse("0812 3456 7890 2 orang")
        assertEquals("0812 3456 7890", parsed?.number)
        assertEquals("081234567890", parsed?.normalized)
        assertEquals("2 orang", parsed?.text)
    }

    @Test
    fun kepalaPesanDuaDigitTidakTerserapNomorBerspasi() {
        val parsed = SmsCommandParser.parse("0812 3456 7890 22 orang")
        assertEquals("0812 3456 7890", parsed?.number)
        assertEquals("081234567890", parsed?.normalized)
        assertEquals("22 orang", parsed?.text)
    }

    @Test
    fun kepalaPesanDuaDigitTidakTerserapNomorInternasional() {
        val parsed = SmsCommandParser.parse("+62 812 3456 7890 22 orang")
        assertEquals("+62 812 3456 7890", parsed?.number)
        assertEquals("+6281234567890", parsed?.normalized)
        assertEquals("22 orang", parsed?.text)
    }

    @Test
    fun nomorPendekBerakhiranDuaDigitTetapUtuh() {
        val parsed = SmsCommandParser.parse("0812 3456 78 halo")
        assertEquals("0812 3456 78", parsed?.number)
        assertEquals("0812345678", parsed?.normalized)
        assertEquals("halo", parsed?.text)
    }

    @Test
    fun nomorBerspasiLengkapMengalahkanTokenPertama() {
        val a = SmsCommandParser.parse("0812345 6789 halo")
        assertEquals("0812345 6789", a?.number)
        assertEquals("08123456789", a?.normalized)
        assertEquals("halo", a?.text)
        val b = SmsCommandParser.parse("08123456 7890 sampai jumpa")
        assertEquals("08123456 7890", b?.number)
        assertEquals("081234567890", b?.normalized)
        assertEquals("sampai jumpa", b?.text)
        val c = SmsCommandParser.parse("+628123456 7890 halo")
        assertEquals("+628123456 7890", c?.number)
        assertEquals("+6281234567890", c?.normalized)
        assertEquals("halo", c?.text)
    }

    @Test
    fun nomorDuaBelasDigitGrupAkhirDuaTidakTerpotong() {
        val a = SmsCommandParser.parse("+62 8123 4567 89 halo")
        assertEquals("+62 8123 4567 89", a?.number)
        assertEquals("+628123456789", a?.normalized)
        assertEquals("halo", a?.text)
    }

    @Test
    fun kepalaPesanDuaDigitSesudahNomorUtuhTetapIsi() {
        val a = SmsCommandParser.parse("08123456789 22 orang")
        assertEquals("08123456789", a?.number)
        assertEquals("22 orang", a?.text)
        val b = SmsCommandParser.parse("+628123456789 22 orang")
        assertEquals("+628123456789", b?.number)
        assertEquals("22 orang", b?.text)
    }

    @Test
    fun tanpaSpasiDitolak() {
        assertNull(SmsCommandParser.parse("08123456789"))
    }

    @Test
    fun hurufPadaNomorDitolak() {
        assertNull(SmsCommandParser.parse("0812abc 3456 Halo"))
    }

    @Test
    fun plusGandaDitolak() {
        assertNull(SmsCommandParser.parse("++62812 3456 Halo"))
    }

    @Test
    fun nomorTerlaluPendekDitolak() {
        assertNull(SmsCommandParser.parse("0812 Halo"))
    }
}
