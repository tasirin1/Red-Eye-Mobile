package com.redeye.parentalmonitor.utils

object PermReport {
    private const val LAYOUT_BIT = 1 shl 30

    fun hasCurrentLayout(stored: Int): Boolean = stored != -1 && (stored and LAYOUT_BIT) != 0

    fun mask(raw: BooleanArray, notifEnabled: Boolean, stored: Int): Int {
        var mask = LAYOUT_BIT
        for (i in raw.indices) {
            var g = raw[i]
            if (i == 7 && !raw[6]) g = if (stored == -1) true else (stored and (1 shl 7)) != 0
            if (i == 8 && !notifEnabled) g = if (stored == -1) true else (stored and (1 shl 8)) != 0
            if (g) mask = mask or (1 shl i)
        }
        return mask
    }

    fun missingLabels(mask: Int, labels: Array<String>): ArrayList<String> {
        val out = ArrayList<String>()
        for (i in arrayOf(0, 1, 2, 3, 4, 5, 8, 9, 10)) if ((mask and (1 shl i)) == 0) out.add(labels[i].lowercase(java.util.Locale.ROOT))
        if ((mask and (1 shl 6)) == 0) out.add(labels[6].lowercase(java.util.Locale.ROOT))
        else if ((mask and (1 shl 7)) == 0) out.add(labels[7].lowercase(java.util.Locale.ROOT))
        return out
    }

    fun inventoryText(raw: BooleanArray, notifEnabled: Boolean, labels: Array<String>): String {
        return buildString {
            appendLine("🛡️ <b>Izin</b>")
            for (i in raw.indices) {
                appendLine("${if (raw[i]) "✅" else "❌"} ${labels[i]}")
            }
            if (!notifEnabled) appendLine("Penerusan notif dimatikan (/notif off).")
            appendLine("Cabut/pasang ulang? Buka Setup di perangkat.")
        }
    }
}
