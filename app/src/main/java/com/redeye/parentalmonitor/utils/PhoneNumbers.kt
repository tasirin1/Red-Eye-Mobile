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
        val tailLen = 10
        if (have.length < tailLen || want.length < tailLen) return false
        if (have.length > tailLen && want.length > tailLen && have.dropLast(tailLen) != want.dropLast(tailLen)) return false
        val haveTail = have.takeLast(tailLen)
        val wantTail = want.takeLast(tailLen)
        if (haveTail == wantTail) return true
        if (wantAlt != null && wantAlt.length >= tailLen) {
            val wantAltTail = wantAlt.takeLast(tailLen)
            if (haveTail == wantAltTail) return true
        }
        if (haveAlt != null && haveAlt.length >= tailLen) {
            val haveAltTail = haveAlt.takeLast(tailLen)
            if (haveAltTail == wantTail) return true
            if (wantAlt != null && wantAlt.length >= tailLen && haveAltTail == wantAlt.takeLast(tailLen)) return true
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
