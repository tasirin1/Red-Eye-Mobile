package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlTest {

    @Test
    fun escapeHanyaUntukAmpersandDanKurung() {
        assertEquals("a&lt;b&gt;&amp;c\"d", Html.escape("a<b>&c\"d"))
    }

    @Test
    fun unescapeMengembalikanKarakter() {
        assertEquals("a<b>&c", Html.unescape("a&lt;b&gt;&amp;c"))
        assertEquals("'ok'", Html.unescape("&#39;ok&#39;"))
        assertEquals("&amp;", Html.unescape("&amp;amp;"))
    }

    @Test
    fun unescapeMembuangTag() {
        assertEquals("bold & x", Html.unescape("<b>bold</b> &amp; <i>x</i>"))
    }

    @Test
    fun ampersandMenggantungDanEntitasAsingDibiarkan() {
        assertEquals("100 & 200", Html.unescape("100 & 200"))
        assertEquals("&unknown;", Html.unescape("&unknown;"))
    }

    @Test
    fun teksKosongTetapKosong() {
        assertEquals("", Html.unescape(""))
    }
}
