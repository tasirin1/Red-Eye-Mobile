package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IntentParserTest {

    @Test
    fun parsePositivePhrases() {
        val cases = listOf(
            "anakku di mana sekarang" to "/location",
            "cek lokasi hp" to "/location",
            "posisi anak terkini" to "/location",
            "lokasi hp saat ini" to "/location",
            "keberadaan anak saat ini" to "/location",
            "baterai hp tinggal berapa persen" to "/battery",
            "kondisi baterai gimana" to "/battery",
            "daya baterai sekarang" to "/battery",
            "batre tinggal berapa" to "/battery",
            "status hp sekarang" to "/status",
            "kabar anak gimana" to "/status",
            "kondisi monitoring terkini" to "/status",
            "anak main hp berapa lama hari ini" to "/layar",
            "berapa lama anak pakai aplikasi hari ini" to "/layar",
            "total waktu layar kemarin" to "/layar",
            "screen time hari ini berapa" to "/layar",
            "laporan hari ini" to "/digest",
            "ringkasan hari ini dong" to "/digest",
            "rekap aktivitas hari ini" to "/digest",
            "rekap seminggu terakhir" to "/minggu",
            "laporan minggu ini" to "/minggu",
            "ringkasan minggu lalu" to "/minggu",
            "tadi habis dari mana saja" to "/jejak",
            "jejak anak hari ini" to "/jejak",
            "riwayat lokasi kemarin" to "/jejak",
            "ada yang mencurigakan semalam" to "/waspada",
            "ada kejadian aneh tadi malam" to "/waspada",
            "notif apa saja yang masuk" to "/lastnotif",
            "pemberitahuan terakhir apa" to "/lastnotif",
            "sms terakhir dari siapa" to "/lastsms",
            "sms masuk terbaru apa" to "/lastsms",
            "sms dari bank masuk" to "/lastsms",
            "siapa saja yang nelpon hari ini" to "/lastcalls",
            "panggilan terakhir siapa" to "/lastcalls",
            "panggilan masuk tadi" to "/lastcalls",
            "bot ini bisa apa saja" to "/help",
            "daftar perintah apa saja" to "/help",
            "tolong kirim foto sekarang" to "/photo",
            "gadis kecil itu kirim foto dong" to "/photo",
            "kirim foto dong" to "/photo",
            "foto sekarang dong" to "/photo",
            "tolong foto" to "/photo",
            "kirim tangkapan layar sekarang" to "/screenshot",
            "screenshot dong" to "/screenshot",
            "tangkap layar sekarang" to "/screenshot",
            "notifikasi baterai penuh" to "/battery"
        )
        for ((text, expected) in cases) {
            assertEquals("frasa: $text", expected, IntentParser.parse(text))
        }
    }

    @Test
    fun parseRejectsUnsafeOrAmbiguous() {
        val cases = listOf(
            "jangan kirim foto",
            "janganlah ambil foto",
            "tidaklah usah kirim screenshot",
            "bukankah kirim screenshot sekarang",
            "nggak usah kirim screenshot",
            "ada foto baru nggak",
            "baterai hp bocor",
            "layar hp mati",
            "telepon anak mati",
            "minggu depan ada acara apa",
            "rute ke sekolah jauh nggak",
            "cara screenshot di hp android",
            "pesan terakhir apa isinya",
            "lokasi baterai di gudang",
            "status baterai hp sekarang",
            "laporan hari ini sama laporan minggu ini",
            "anak main hp terus",
            "dia lagi tidur",
            "kirim pesan ke ibu",
            "tolong kunci hp",
            "matikan monitoring sekarang",
            "hapus foto kemarin",
            "halo",
            "terima kasih",
            "",
            "/location"
        )
        for (text in cases) {
            assertNull("frasa: $text", IntentParser.parse(text))
        }
    }

    @Test
    fun parseRejectsColloquialNegations() {
        val cases = listOf(
            "ga usah kirim foto",
            "gausah kirim foto",
            "ngga usah kirim foto",
            "ngga perlu kirim foto",
            "engga usah kirim screenshot",
            "ga usah ambil screenshot",
            "kagak usah ambil foto",
            "jgn kirim foto",
            "ga perlu ambil foto dia sekarang",
            "gaklah kirim foto sekarang",
            "takkan kirim foto sekarang",
            "jgnlah kirim foto dong",
            "ndaklah ambil foto"
        )
        for (text in cases) {
            assertNull("frasa: $text", IntentParser.parse(text))
        }
    }

    @Test
    fun parseRejectsReferencesToExistingPhoto() {
        val cases = listOf(
            "mana fotonya anak sekarang",
            "foto yang diambil kemarin dong",
            "fotonya kemarin bagus, kirim ulang dong",
            "kirim ulang foto yang tadi",
            "mana foto yang diambil tadi",
            "kirim fotonya sekarang dong",
            "jepretan fotonya dong",
            "ambilan fotonya dong",
            "potretan fotonya dong"
        )
        for (text in cases) {
            assertNull("frasa: $text", IntentParser.parse(text))
        }
    }

    @Test
    fun parsePhotoReferenceVetoYieldsToCaptureVerbs() {
        val cases = listOf(
            "jepret fotonya sekarang",
            "potret fotonya dong",
            "tolong ambilkan fotonya sekarang",
            "ambil fotonya sekarang",
            "foto kemarin jelek, ambil foto baru sekarang"
        )
        for (text in cases) {
            assertEquals("frasa: $text", "/photo", IntentParser.parse(text))
        }
    }

    @Test
    fun parseNeverReturnsMutatingCommands() {
        val allowed = setOf(
            "/location", "/battery", "/status", "/layar", "/digest", "/minggu", "/jejak",
            "/waspada", "/lastnotif", "/lastsms", "/lastcalls", "/help", "/photo", "/screenshot"
        )
        val probes = listOf(
            "tolong kunci hp sekarang", "matikan monitoring sekarang", "berhenti memantau sekarang",
            "hapus semua foto dan video", "kirim sms ke ibu sekarang", "tolong telepon ibu sekarang",
            "restart hp sekarang", "logout sekarang juga", "ganti jam pulang jadi 22 00"
        )
        for (text in probes) {
            val got = IntentParser.parse(text)
            assertNull("frasa: $text", got)
            assertEquals(true, got == null || got in allowed)
        }
    }

    @Test
    fun intentOrAliasLetsParserJudgeNaturalSentences() {
        val cases: List<Triple<String, String?, String?>> = listOf(
            Triple("tolong kirim foto sekarang", "/help kirim foto sekarang", "/photo"),
            Triple("tolong foto", "/help foto", "/photo"),
            Triple("sms terakhir dari siapa", "/sms terakhir dari siapa", "/lastsms"),
            Triple("laporan hari ini", "/minggu hari ini", "/digest"),
            Triple("minggu depan ada acara apa", "/minggu depan ada acara apa", null),
            Triple("lokasi baterai di gudang", "/location baterai di gudang", null),
            Triple("foto sekarang dong", "/photo sekarang dong", "/photo"),
            Triple("foto yang diambil kemarin dong", "/photo yang diambil kemarin dong", null),
            Triple("kunci perangkat dari jauh sekarang", "/lock perangkat dari jauh sekarang", null),
            Triple("lanjut pemantauan yang kemarin", "/resume pemantauan yang kemarin", null),
            Triple("tolong kunci hp", "/help kunci hp", null),
            Triple("kirim foto dong", null, "/photo"),
            Triple("halo", null, null)
        )
        for ((text, alias, expected) in cases) {
            assertEquals("frasa: $text", expected, IntentParser.intentOrAlias(text, alias))
        }
    }

    @Test
    fun intentOrAliasKeepsPureAliasForms() {
        val cases: List<Triple<String, String?, String?>> = listOf(
            Triple("kunci", "/lock", "/lock"),
            Triple("kunci sekarang", "/lock sekarang", "/lock"),
            Triple("tolong", "/help", "/help"),
            Triple("foto", "/photo", "/photo"),
            Triple("foto depan", "/photo depan", "/photo depan"),
            Triple("foto belakang", "/photo belakang", "/photo belakang"),
            Triple("sms 0812 halo", "/sms 0812 halo", "/sms 0812 halo"),
            Triple("sms 0812 3456 7890 apa kabar", "/sms 0812 3456 7890 apa kabar", "/sms 0812 3456 7890 apa kabar"),
            Triple("sms +62812 3456 7890 sampai jumpa", "/sms +62812 3456 7890 sampai jumpa", "/sms +62812 3456 7890 sampai jumpa")
        )
        for ((text, alias, expected) in cases) {
            assertEquals("frasa: $text", expected, IntentParser.intentOrAlias(text, alias))
        }
    }
}
