package com.redeye.parentalmonitor.utils

// Klasifikasi paket perbankan/e-wallet per SEGMEN nama
// paket (issue #933): pencocokan substring lama membuat
// paket tak terkait ikut prioritas ("bri" di "bright…",
// "dana" di "danais…"). Token pendek wajib sama persis
// dengan satu segmen; merek majemuk boleh sebagai awalan
// segmen. Diekstrak dari NotificationForwarderService agar
// terkunci uji (issue #977).
object PriorityPackages {
    fun isPriority(lowPkg: String): Boolean {
        val eksak = setOf("bank", "bri", "bca", "bni", "btn", "ovo", "dana", "mandiri", "pay")
        val awalan = listOf("brimo", "livin", "gopay", "shopeepay", "mbanking", "m-banking", "wallet", "paypal", "paytm", "spay")
        return lowPkg.split('.').any { seg ->
            seg in eksak || awalan.any { seg.startsWith(it) } ||
                // Merek bertoken menyatu (issue #970, regresi
                // cakupan #933): segmen yang diawali/diakhiri
                // "bank" atau diakhiri "banking" adalah merek
                // bank nyata yang tertulis menyatu — bankmega,
                // seabank, allobank, maybank, mobilebanking —
                // dan pencocokan persis/awalan di atas
                // melewatkannya semua. "cimbniaga" menyatu
                // tanpa pola bank (bni di tengah), disebut
                // eksplisit. Ini tetap jauh lebih sempit dari
                // substring bebas lama yang dibuang #933.
                seg.startsWith("bank") || seg.endsWith("bank") || seg.endsWith("banking") ||
                seg == "cimbniaga"
        }
    }
}
