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
        val cands = mutableListOf(want)
        if (wantAlt != null) cands.add(wantAlt)
        val haveCands = mutableListOf(have)
        if (haveAlt != null) haveCands.add(haveAlt)
        for (a in haveCands) {
            for (b in cands) {
                if (a.length < 7 || b.length < 7) continue
                val short = if (a.length <= b.length) a else b
                val long = if (a.length <= b.length) b else a
                if (long.endsWith(short)) return true
            }
        }
        return false
    }

    fun matches(raw: String, digits: String, pick: (String) -> String = { it }): Boolean {
        return matchesNormalized(pick(raw), digits.filter { it.isDigit() })
    }

    fun matchesNormalized(haveRaw: String, wantDigits: String): Boolean {
        val have = haveRaw.filter { it.isDigit() }
        if (have.isEmpty() || wantDigits.isEmpty()) return false
        if (wantDigits.length < 7) return have == wantDigits
        return numbersEqualFast(have, wantDigits, altVariant(wantDigits))
    }
}
