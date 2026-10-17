package com.redeye.parentalmonitor.utils

object PhoneNumbers {
    fun altVariant(digits: String): String? {
        if (digits.isEmpty()) return null
        if (digits.startsWith("628") && digits.length in 10..15) return "0" + digits.substring(2)
        if (digits.startsWith("08") && digits.length in 10..14) return "62" + digits.substring(1)
        return null
    }

    fun numbersEqualFast(have: String, want: String, wantAlt: String?): Boolean {
        if (have == want) return true
        if (wantAlt != null && have == wantAlt) return true
        val haveAlt = altVariant(have)
        if (haveAlt != null) {
            if (haveAlt == want) return true
            if (wantAlt != null && haveAlt == wantAlt) return true
        }
        if (want.length >= 10 && suffixMatch(have, want)) return true
        if (wantAlt != null && wantAlt.length >= 10 && suffixMatch(have, wantAlt)) return true
        if (haveAlt != null) {
            if (want.length >= 10 && suffixMatch(haveAlt, want)) return true
            if (wantAlt != null && wantAlt.length >= 10 && suffixMatch(haveAlt, wantAlt)) return true
        }
        return false
    }

    private fun suffixMatch(a: String, b: String): Boolean {
        val short = if (a.length <= b.length) a else b
        val long = if (a.length <= b.length) b else a
        if (short.length < 10) return false
        return long.endsWith(short)
    }

    fun matchesPrecomputed(haveRaw: String, wantDigits: String, wantAlt: String?): Boolean {
        val have = haveRaw.filter { it.isDigit() }
        if (have.isEmpty() || wantDigits.isEmpty()) return false
        if (wantDigits.length < 10) return have == wantDigits
        return numbersEqualFast(have, wantDigits, wantAlt)
    }
}
