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
        if (want.length < 10) return false
        if (have.endsWith(want) || want.endsWith(have)) return true
        if (wantAlt != null && (have.endsWith(wantAlt) || wantAlt.endsWith(have))) return true
        if (haveAlt != null) {
            if (haveAlt.endsWith(want) || want.endsWith(haveAlt)) return true
            if (wantAlt != null && (haveAlt.endsWith(wantAlt) || wantAlt.endsWith(haveAlt))) return true
        }
        return false
    }

    fun matches(raw: String, digits: String, pick: (String) -> String = { it }): Boolean {
        val have = pick(raw).filter { it.isDigit() }
        val want = digits.filter { it.isDigit() }
        if (have.isEmpty() || want.isEmpty()) return false
        if (want.length < 7) return have == want
        return numbersEqualFast(have, want, altVariant(want))
    }
}
