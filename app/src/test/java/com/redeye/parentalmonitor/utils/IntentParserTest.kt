package com.redeye.parentalmonitor.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
            "7 hari terakhir" to "/minggu",
            "laporan minggu ini" to "/minggu",
            "ringkasan minggu lalu" to "/minggu",
            "tadi habis dari mana saja" to "/jejak",
            "jejak anak hari ini" to "/jejak",
            "riwayat lokasi kemarin" to "/jejak",
            "riwayat lokasi anak hari ini" to "/jejak",
            "riwayat lokasi hp sekarang" to "/jejak",
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
            "anak 77 hari terakhir",
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
    fun parseRejectsCompoundNegations() {
        // #544: negasi yang ditempel kata penuh usah/perlu adalah satu token negasi.
        val cases = listOf(
            "nggakusah kirim foto sekarang",
            "tidakusah kirim foto sekarang",
            "enggakusah ambil foto",
            "gaperlu kirim foto sekarang",
            "tidakperlu kirim screenshot sekarang",
            "nggakperlu ambil foto"
        )
        for (text in cases) {
            assertNull("frasa: $text", IntentParser.parse(text))
        }
        assertNull(IntentParser.intentOrAlias("kunci tidakusah", "/lock tidakusah"))
        // Kata ekornya saja bukan negasi: "perlu" telanjang tetap perintah positif.
        assertEquals("/photo", IntentParser.parse("perlu kirim foto sekarang"))
    }

    @Test
    fun parseRejectsShortNegationsWithDigitReduplication() {
        // #620: reduplikasi digit gaya chat pada negasi basis pendek tetap negasi.
        assertNull(IntentParser.parse("gak2 kirim foto sekarang"))
        assertNull(IntentParser.parse("ga2 kirim foto sekarang"))
        assertNull(IntentParser.parse("jgn2 kirim foto sekarang"))
        assertNull(IntentParser.parse("tak2 kirim foto sekarang"))
        assertEquals("/photo", IntentParser.parse("kirim foto2 sekarang"))
    }

    @Test
    fun parseRejectsPrefixFalseFriends() {
        // #619: kata umum yang kebetulan berawalan kata kunci bukan intent.
        assertNull(IntentParser.parse("dayak berapa orang"))
        assertNull(IntentParser.parse("kabari anak sekarang"))
        assertEquals("/status", IntentParser.parse("kabar anak sekarang"))
        assertEquals("/battery", IntentParser.parse("daya baterai berapa persen"))
    }

    @Test
    fun parseToleratesAttachedDigitUnitTokens() {
        // #617: angka+satuan yang menempel dikenali seperti bentuk berspasi.
        assertEquals("/layar 6", IntentParser.intentOrAlias("cek layar 6jam dong", null))
        assertEquals("/layar 6", IntentParser.intentOrAlias("layar 6jam", "/layar 6jam"))
        assertEquals("/minggu", IntentParser.parse("7hari terakhir"))
        assertEquals("/photo", IntentParser.parse("kirim foto sekarang"))
    }

    @Test
    fun parseToleratesNyaClitic() {
        // #600: klitik -nya pada kata kunci pendek & kata akhir frasa tidak lagi
        // menggugurkan intent yang sah.
        assertEquals("/location", IntentParser.parse("hpnya dimana"))
        assertEquals("/screenshot", IntentParser.parse("kirim tangkapan layarnya sekarang"))
        assertEquals("/layar", IntentParser.parse("anak main hpnya berapa lama hari ini"))
        assertEquals("/lastsms", IntentParser.parse("smsnya terakhir apa"))
        assertEquals("/jejak", IntentParser.parse("riwayat lokasinya kemarin"))
        assertEquals("/status", IntentParser.parse("status hpnya sekarang"))
        assertNull(IntentParser.parse("hp rusak"))
    }

    @Test
    fun aliasFallbackRejectsFutureTimeWords() {
        // Kata masa depan pada alias mutating = niat menjadwalkan, bukan eksekusi sekarang.
        assertNull(IntentParser.intentOrAlias("kunci besok", "/lock besok"))
        assertNull(IntentParser.intentOrAlias("hilang besok", "/hilang besok"))
        assertNull(IntentParser.intentOrAlias("lanjut nanti", "/resume nanti"))
        assertNull(IntentParser.intentOrAlias("darurat lusa", "/darurat lusa"))
        assertTrue(IntentParser.isVetoedText("kunci besok", "/lock besok"))
        // Kontrol positif & cermin masa lalu tetap seperti sebelumnya.
        assertEquals("/lock", IntentParser.intentOrAlias("kunci", "/lock"))
        assertNull(IntentParser.intentOrAlias("kunci kemarin", "/lock kemarin"))
    }

    @Test
    fun parseRejectsFutureTimeWords() {
        // #624 (sisa #594): penanda masa depan juga memveto jalur parse() untuk
        // intent tangkapan — sebelumnya hanya jalur alias/slash yang terveto.
        assertNull(IntentParser.parse("kirim foto besok dong"))
        assertNull(IntentParser.parse("nanti saja kirim foto dong"))
        assertNull(IntentParser.parse("kirim foto lusa dong"))
        assertNull(IntentParser.parse("kirim screenshot nanti dong"))
        assertNull(IntentParser.parse("ambil foto besok"))
        // Kontrol: kalimat tangkap tanpa penanda waktu tetap ter-parse.
        assertEquals("/photo", IntentParser.parse("kirim foto sekarang"))
        assertEquals("/screenshot", IntentParser.parse("kirim screenshot sekarang"))
        // Intent baca-saja tidak ikut terveto oleh penanda masa depan.
        assertEquals("/location", IntentParser.parse("lokasi anak besok"))
    }

    @Test
    fun aliasFallbackRejectsQuestionTail() {
        // #625: kata tanya sebagai kata kedua alias = pertanyaan, bukan perintah;
        // handler mutating mengabaikan argumen sehingga sebelumnya tetap jalan.
        assertNull(IntentParser.intentOrAlias("kunci apa", "/lock apa"))
        assertNull(IntentParser.intentOrAlias("kunci kapan", "/lock kapan"))
        assertNull(IntentParser.intentOrAlias("kunci kenapa", "/lock kenapa"))
        assertNull(IntentParser.intentOrAlias("hilang apa", "/hilang apa"))
        assertNull(IntentParser.intentOrAlias("hilang kenapa", "/hilang kenapa"))
        assertNull(IntentParser.intentOrAlias("darurat gimana", "/darurat gimana"))
        // Kontrol: alias polos dan alias + argumen sah tetap jalan.
        assertEquals("/lock", IntentParser.intentOrAlias("kunci", "/lock"))
        assertEquals("/photo depan", IntentParser.intentOrAlias("foto depan dong", "/photo depan dong"))
    }

    @Test
    fun parseRejectsKemarenAndTempoHariReferences() {
        // #626: ejaan tak baku "kemaren" dan frasa "tempo hari" adalah rujukan
        // masa lalu — sebelumnya tak dikenali sehingga memicu tangkapan baru.
        assertNull(IntentParser.parse("kirim foto kemaren dong"))
        assertNull(IntentParser.parse("foto kemaren bagus dong"))
        assertNull(IntentParser.parse("kirim foto tempo hari dong"))
        assertNull(IntentParser.intentOrAlias("hilang kemaren", "/hilang kemaren"))
        assertNull(IntentParser.intentOrAlias("darurat kemaren", "/darurat kemaren"))
        // Kontrol: tangkap sekarang dan ejaan baku tetap seperti sebelumnya.
        assertEquals("/photo", IntentParser.parse("kirim foto sekarang"))
        assertNull(IntentParser.parse("kirim foto kemarin dong"))
    }

    @Test
    fun parseRejectsShortAndTransposedNegations() {
        // #627: singkatan konsonan & transposisi huruf negasi sebelumnya lolos.
        assertNull(IntentParser.parse("tdk kirim foto sekarang"))
        assertNull(IntentParser.parse("gk kirim foto sekarang"))
        assertNull(IntentParser.parse("dak kirim foto sekarang"))
        assertNull(IntentParser.parse("usah kirim foto dong"))
        assertNull(IntentParser.parse("jngan kirim foto sekarang"))
        assertNull(IntentParser.parse("tidka kirim foto sekarang"))
        assertNull(IntentParser.intentOrAlias("kunci tdk", "/lock tdk"))
        assertNull(IntentParser.intentOrAlias("kunci dak", "/lock dak"))
        // Kontrol: kata mirip yang bukan negasi tidak ikut tertelan.
        assertEquals("/photo", IntentParser.parse("kirim foto sekarang"))
    }

    @Test
    fun parseRejectsCancellationWords() {
        // #628: kata pembatalan sebelumnya tak memveto apa pun — pesan yang
        // maksudnya membatalkan justru mengeksekusi hal yang dibatalkan.
        assertNull(IntentParser.parse("batalkan kirim foto sekarang"))
        assertNull(IntentParser.parse("batal kirim screenshot sekarang"))
        assertNull(IntentParser.parse("batalin kirim foto sekarang"))
        assertNull(IntentParser.parse("foto batal dikirim sekarang dong"))
        assertNull(IntentParser.intentOrAlias("kunci batal", "/lock batal"))
        assertNull(IntentParser.intentOrAlias("lanjut batal", "/resume batal"))
        assertNull(IntentParser.intentOrAlias("pulih batal", "/sembuh batal"))
        // Pengecualian: "restore batal" adalah perintah batal-restore itu
        // sendiri (/pulihkan batal) dan harus tetap sampai ke handler-nya.
        assertEquals("/pulihkan batal", IntentParser.intentOrAlias("restore batal", "/pulihkan batal"))
        assertFalse(IntentParser.isVetoedText("restore batal", "/pulihkan batal"))
        // Kontrol positif tetap jalan.
        assertEquals("/photo", IntentParser.parse("kirim foto sekarang"))
    }

    @Test
    fun parseRejectsDeliveryStatusQuestions() {
        // #629: pertanyaan status kiriman bukan perintah tangkap baru — kata
        // status "terkirim/dikirim" kini dikenali sebagai rujukan tangkapan
        // yang sudah ada, seperti "diambil".
        assertNull(IntentParser.parse("apakah foto sudah terkirim sekarang"))
        assertNull(IntentParser.parse("apakah screenshot sudah terkirim sekarang"))
        assertNull(IntentParser.parse("foto sudah dikirim belum dong"))
        // Kontrol positif tetap jalan.
        assertEquals("/photo", IntentParser.parse("kirim foto sekarang"))
    }

    @Test
    fun mergeTextArgsDepanSetelahFrasaWaktuBukanLensa() {
        // #638: "depan" di dalam frasa waktu "minggu depan" bukan pilihan
        // lensa — sebelumnya kamera depan terpilih untuk pesan tanpa lensa.
        assertEquals("/photo", IntentParser.intentOrAlias("kirim foto minggu depan dong", null))
        assertEquals("/photo", IntentParser.intentOrAlias("kirim foto bulan depan dong", null))
        // #671: frasa "pekan depan" juga frasa waktu, bukan lensa.
        assertEquals("/photo", IntentParser.intentOrAlias("kirim foto pekan depan dong", null))
        // Lensa betulan tetap dikenali: tepat sesudah kata foto, sesudah kata
        // sisipan "dong", atau sesudah "kamera".
        assertEquals("/photo depan", IntentParser.intentOrAlias("kirim foto depan sekarang", null))
        assertEquals("/photo depan", IntentParser.intentOrAlias("foto dong depan", "/photo dong depan"))
        assertEquals("/photo depan", IntentParser.intentOrAlias("kirim foto kamera depan dong", null))
        assertEquals("/photo belakang", IntentParser.intentOrAlias("kirim foto kamera belakang dong", null))
    }

    @Test
    fun parseRejectsQuotedAndReportedSpeech() {
        // #640: kutipan dan laporan ucapan orang lain bukan perintah pemilik.
        assertNull(IntentParser.parse("\"kirim foto sekarang\" itu maksudnya apa"))
        assertNull(IntentParser.parse("katanya kirim foto sekarang"))
        assertNull(IntentParser.parse("dia bilang kirim foto sekarang"))
        assertNull(IntentParser.intentOrAlias("katanya kunci", "/lock"))
        // Kontrol: perintah langsung dan argumen berkutip di tengah tetap jalan.
        assertEquals("/photo", IntentParser.parse("kirim foto sekarang"))
        assertEquals("/photo depan", IntentParser.intentOrAlias("kirim foto \"depan\" dong", null))
    }

    @Test
    fun parseFotoLamaranTidakTervetoLama() {
        // #643 (efek samping #597): "lamaran" = "lama"+"ran" secara awalan
        // sehingga tertangkap penanda masa lalu "lama" dan perintah foto
        // surat lamaran gugur diam-diam. Kini kawan palsu awalan.
        assertEquals("/photo", IntentParser.parse("kirim foto lamaran sekarang"))
        assertEquals("/photo", IntentParser.parse("kirim foto lamaran dong"))
        // Kontrol: rujukan "lama" betulan tetap terveto.
        assertNull(IntentParser.parse("kirim foto lama dong"))
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
            "ndaklah ambil foto",
            "gakkk kirim foto sekarang",
            "takk usah kirim screenshot sekarang",
            "gaak usah kirim foto",
            "gaaa usah ambil foto sekarang",
            "jgnn kirim foto dong",
            "janganlahh kirim foto",
            "bukanlahh kirim screenshot sekarang",
            "jgnlahh kirim foto dong"
        )
        for (text in cases) {
            assertNull("frasa: $text", IntentParser.parse(text))
        }
    }

    @Test
    fun validWordBukaanIsNotNegationWhileElongatedBukanStillIs() {
        assertEquals("/photo", IntentParser.parse("foto bukaan dong"))
        assertNull(IntentParser.parse("bukann ambil foto sekarang"))
    }

    @Test
    fun elongatedBukaanIsNotNegation() {
        assertEquals("/photo", IntentParser.parse("foto bukaann dong"))
        assertEquals("/photo", IntentParser.parse("foto buukaan dong"))
        assertEquals("/photo", IntentParser.parse("foto bukaaann dong"))
        assertNull(IntentParser.parse("bukanlahh kirim screenshot sekarang"))
    }

    @Test
    fun parseRejectsCliticAfterElongatedNegationBase() {
        val phrases = listOf(
            "nggaklahh kirim foto sekarang",
            "enggaklahh ambil foto",
            "nggaklahh kirim screenshot"
        )
        for (phrase in phrases) {
            assertNull("frasa: $phrase", IntentParser.parse(phrase))
        }
    }

    @Test
    fun parseRejectsAmbilWithPastMarker() {
        // #656: pengecualian penanda masa lalu berlaku juga untuk ambil.
        assertNull(IntentParser.parse("ambil foto kemarin dong"))
        assertNull(IntentParser.parse("ambil foto tadi dong"))
        assertNull(IntentParser.parse("ambil screenshot kemarin dong"))
        assertNull(IntentParser.parse("ambilkan foto kemarin dong"))
        assertNull(IntentParser.parse("ambil foto barusan dong"))
        // Kontrol positif: tanpa penanda masa lalu tetap perintah.
        assertEquals("/photo", IntentParser.parse("ambil foto sekarang"))
        assertEquals("/photo", IntentParser.parse("ambilkan fotonya sekarang"))
    }

    @Test
    fun readOnlyCommandsAcceptPastArguments() {
        // #672: penanda masa lalu adalah argumen sah perintah baca-saja.
        assertEquals("/waspada kemarin", IntentParser.intentOrAlias("waspada kemarin", "/waspada kemarin"))
        assertEquals("/location kemarin", IntentParser.intentOrAlias("lokasi kemarin", "/location kemarin"))
        assertEquals("/hematdata kemarin", IntentParser.intentOrAlias("kuota kemarin", "/hematdata kemarin"))
        // Perintah tangkap tetap terveto rujukan seperti sebelumnya.
        assertNull(IntentParser.intentOrAlias("foto kemarin", "/photo kemarin"))
        // Veto keras (negasi) tetap berlaku untuk semua perintah.
        assertNull(IntentParser.intentOrAlias("lokasi jangan kemarin", "/location jangan kemarin"))
    }

    @Test
    fun parseRejectsTdakTypo() {
        // #670: typo "tdak" (huruf hilang+tertukar dari "tidak") adalah negasi,
        // sejajar entri typo terkunci #627 (tidka/jngan).
        assertNull(IntentParser.parse("tdak kirim foto sekarang"))
        assertNull(IntentParser.parse("tdak ambil screenshot"))
    }

    @Test
    fun parseRejectsOgahRefusal() {
        // #669: kata penolakan kasual "ogah" adalah negasi.
        assertNull(IntentParser.parse("ogah kirim foto sekarang"))
        assertNull(IntentParser.parse("ogah ambil screenshot"))
    }

    @Test
    fun parseRejectsPembatalanNoun() {
        // #668: nomina "pembatalan" adalah pembatalan.
        assertNull(IntentParser.parse("pembatalan kirim foto sekarang"))
        assertNull(IntentParser.parse("pembatalan ambil screenshot sekarang"))
    }

    @Test
    fun parseRejectsKataDiaReportPhrases() {
        // #667: penanda laporan frasa "kata dia"/"kata anak".
        assertNull(IntentParser.parse("kata dia kirim foto sekarang"))
        assertNull(IntentParser.parse("kata anak ambil foto depan"))
        assertNull(IntentParser.parse("kata dia kirim screenshot sekarang"))
    }

    @Test
    fun parseRejectsMidSentenceQuotedUtterance() {
        // #666: kutipan >=2 kata di tengah kalimat = ucapan yang dilaporkan.
        assertNull(IntentParser.parse("pesan masuk bunyinya \"kirim foto sekarang\""))
        assertNull(IntentParser.parse("bunyi notifnya “ambil foto depan”"))
        // Kontrol terkunci: kutipan satu kata adalah penekanan, tetap sah.
        assertEquals("/photo depan", IntentParser.intentOrAlias("kirim foto \"depan\" dong", null))
    }

    @Test
    fun parseRejectsMalamIniFuture() {
        // #665: frasa "malam ini" merujuk waktu mendatang seperti "nanti malam".
        assertNull(IntentParser.parse("kirim foto malam ini dong"))
        assertNull(IntentParser.parse("ambil screenshot malam ini"))
    }

    @Test
    fun parseRejectsEsokFuture() {
        // #664: sinonim "esok" sama seperti besok/nanti/lusa.
        assertNull(IntentParser.parse("kirim foto esok dong"))
        assertNull(IntentParser.parse("kirim foto esok hari dong"))
    }

    @Test
    fun parseDoesNotTreatUsahaAsNegation() {
        // #661: kata umum "usaha" bukan negasi "usah".
        assertEquals("/photo", IntentParser.parse("kirim foto usaha anak dong"))
        // Kontrol: "usah" betulan tetap negasi.
        assertNull(IntentParser.parse("usah kirim foto sekarang"))
        assertNull(IntentParser.parse("nggak usah ambil foto"))
    }

    @Test
    fun parseRejectsQuestionSentences() {
        // #658: kata tanya di kalimat >=3 token memveto jalur parse().
        assertNull(IntentParser.parse("kirim foto apa dong"))
        assertNull(IntentParser.parse("foto apa sekarang"))
        assertNull(IntentParser.parse("foto siapa dong"))
        assertNull(IntentParser.parse("kirim screenshot apa dong"))
        // Kontrol: kata tanya di kata pertama tak menghalangi perintah
        // tegas sesudahnya; perintah tanpa kata tanya tetap jalan.
        assertEquals("/photo", IntentParser.parse("kirim foto dong"))
    }

    @Test
    fun parseRejectsKahKanQuestions() {
        // #713: partikel tanya "kah"/tag konfirmasi "kan" adalah penanda
        // pertanyaan, bukan perintah — sisa #625/#658.
        assertNull(IntentParser.parse("kirim foto kah"))
        assertNull(IntentParser.parse("kirim foto kan"))
        assertNull(IntentParser.parse("kirim screenshot kah"))
        assertNull(IntentParser.intentOrAlias("darurat kah", "/darurat kah"))
        assertNull(IntentParser.intentOrAlias("darurat kan", "/darurat kan"))
        assertNull(IntentParser.intentOrAlias("kunci kah", "/lock kah"))
        assertNull(IntentParser.intentOrAlias("sembuh kah", "/sembuh kah"))
        assertNull(IntentParser.intentOrAlias("hilang kah", "/hilang kah"))
        // Kontrol: imbuhan -kan pada kata kerja bukan partikel tanya.
        assertEquals("/photo", IntentParser.parse("kirimkan foto sekarang"))
        assertEquals("/photo", IntentParser.parse("kirim foto sekarang"))
    }

    @Test
    fun parseRejectsAspectStatusStatements() {
        // #657: "belum/sudah + kata kerja" adalah pernyataan status kiriman.
        assertNull(IntentParser.parse("belum kirim foto dong"))
        assertNull(IntentParser.parse("belum ambil foto dong"))
        assertNull(IntentParser.parse("belum kirim screenshot dong"))
        assertNull(IntentParser.parse("sudah kirim foto dong"))
        assertNull(IntentParser.parse("sudah jepret foto dong"))
        assertNull(IntentParser.parse("belum jepret foto dong"))
        // Kontrol: tanpa penanda aspek, perintah yang sama tetap jalan.
        assertEquals("/photo", IntentParser.parse("kirim foto dong"))
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
            "potretan fotonya dong",
            "kirim jepretan itu dong",
            "kirim potretan itu dong",
            "tolong kirim jepretan tadi",
            "kirim foto tadi dong",
            "kirim foto terakhir dong",
            "kirim foto barusan dong",
            "screenshot yang diambil kemarin, kirim ulang dong",
            "kirim ulang screenshot kemarin",
            "kirim screenshotnya dong",
            "kirim foto lama dong",
            "kirim foto semalam dong",
            "kirim foto sebelumnya dong",
            "kirim foto yang lalu dong",
            "screenshot semalam kirim dong"
        )
        for (text in cases) {
            assertNull("frasa: $text", IntentParser.parse(text))
        }
        // Kontrol: "selama" bukan penanda "lama"; niat tangkap baru tetap jalan.
        assertEquals("/photo", IntentParser.parse("kirim foto selama liburan dong"))
    }

    @Test
    fun isVetoedTextCoversSlashAliases() {
        assertTrue(IntentParser.isVetoedText("foto kemarin", "/photo kemarin"))
        assertTrue(IntentParser.isVetoedText("kunci tidak", "/lock tidak"))
        assertTrue(IntentParser.isVetoedText("foto yang diambil kemarin dong", "/photo yang diambil kemarin dong"))
        assertTrue(IntentParser.isVetoedText("hilang kemarinn", "/hilang kemarinn"))
        assertFalse(IntentParser.isVetoedText("jejak kemarin", "/jejak kemarin"))
        assertFalse(IntentParser.isVetoedText("layar kemarin", "/layar kemarin"))
        assertFalse(IntentParser.isVetoedText("sms 0812 jangan lupa", "/sms 0812 jangan lupa"))
        assertFalse(IntentParser.isVetoedText("kuota bulan ini habis", "/hematdata bulan ini habis"))
        assertFalse(IntentParser.isVetoedText("foto bukaan dong", "/photo bukaan dong"))
        assertFalse(IntentParser.isVetoedText("waspada tadi malam", "/waspada tadi malam"))
    }

    @Test
    fun intentOrAliasUnparseableNumberShortQuestionStillHijacked() {
        // Nomor 16 digit ditolak SmsCommandParser; pertanyaan riwayat
        // pendek tetap dibajak, isi pesan ekor panjang tidak (issue #723).
        assertEquals(
            "/lastsms",
            IntentParser.intentOrAlias(
                "sms 0812345678901234 tadi isinya apa",
                "/sms 0812345678901234 tadi isinya apa"
            )
        )
        assertEquals(
            "/sms 0812345678901234 tolong kirimkan promo diskon besar besaran sekarang juga ya",
            IntentParser.intentOrAlias(
                "sms 0812345678901234 tolong kirimkan promo diskon besar besaran sekarang juga ya",
                "/sms 0812345678901234 tolong kirimkan promo diskon besar besaran sekarang juga ya"
            )
        )
    }

    @Test
    fun intentOrAliasSmsGateDoesNotHijackHistoryQuestions() {
        assertEquals("/lastsms", IntentParser.intentOrAlias("sms 0812, terakhir dari siapa", "/sms 0812, terakhir dari siapa"))
        assertEquals("/lastsms", IntentParser.intentOrAlias("sms 0,5 terakhir dari siapa", "/sms 0,5 terakhir dari siapa"))
        assertEquals("/sms 0812, halo apa kabar", IntentParser.intentOrAlias("sms 0812, halo apa kabar", "/sms 0812, halo apa kabar"))
        assertEquals("/sms 08123456789 terakhir dari siapa", IntentParser.intentOrAlias("sms 08123456789 terakhir dari siapa", "/sms 08123456789 terakhir dari siapa"))
        // #596: pertanyaan riwayat berkosa kata tanya lain (tadi/siapa) juga pertanyaan,
        // bukan formulir kirim, walau nomornya lengkap terpisah-pisah spasi.
        assertEquals("/lastsms", IntentParser.intentOrAlias("sms 0812 3456 7890 tadi isinya apa", "/sms 0812 3456 7890 tadi isinya apa"))
        // #714: nomor bertanda-pisah (titik/strip) dihitung dengan token
        // ter-tokenize juga, bukan token mentah — ekor tak lagi menggelembung.
        assertEquals("/lastsms", IntentParser.intentOrAlias("sms 0812.3456.7890 tadi isinya apa", "/sms 0812.3456.7890 tadi isinya apa"))
        assertEquals("/lastsms", IntentParser.intentOrAlias("sms 0812-3456-7890 tadi isinya apa", "/sms 0812-3456-7890 tadi isinya apa"))
        assertEquals("/lastsms", IntentParser.intentOrAlias("sms +62.812.3456.7890 tadi isinya apa", "/sms +62.812.3456.7890 tadi isinya apa"))
        assertEquals("/lastsms", IntentParser.intentOrAlias("sms 0812.3456.7890 tadi dari siapa", "/sms 0812.3456.7890 tadi dari siapa"))
        // Kontrol: formulir berekor panjang berisi "tadi" + kata tanya tetap formulir.
        assertEquals(
            "/sms 0812.3456.7890 tadi aku tanya apa kabar kamu sekeluarga",
            IntentParser.intentOrAlias("sms 0812.3456.7890 tadi aku tanya apa kabar kamu sekeluarga", "/sms 0812.3456.7890 tadi aku tanya apa kabar kamu sekeluarga")
        )
        assertEquals("/sms 081234567890 tanya dia tadi makan apa", IntentParser.intentOrAlias("sms 081234567890 tanya dia tadi makan apa", "/sms 081234567890 tanya dia tadi makan apa"))
        assertEquals("/lastsms", IntentParser.intentOrAlias("sms 0812, tadi dari siapa", "/sms 0812, tadi dari siapa"))
        assertEquals("/lastsms", IntentParser.intentOrAlias("sms 0812 barusan masuk dari siapa", "/sms 0812 barusan masuk dari siapa"))
        // Kontrol: isi kirim yang menyebut "tadi" tanpa kata tanya tetap formulir.
        assertEquals(
            "/sms 0812 3456 7890 tadi malam berangkat",
            IntentParser.intentOrAlias("sms 0812 3456 7890 tadi malam berangkat", "/sms 0812 3456 7890 tadi malam berangkat")
        )
        // #659: nomor 7-9 digit (utuh/berspasi) + pertanyaan riwayat juga
        // pertanyaan, bukan formulir — ambang >=7 tak lagi membutakan cek.
        assertEquals("/lastsms", IntentParser.intentOrAlias("sms 1234567 terakhir dari siapa", "/sms 1234567 terakhir dari siapa"))
        assertEquals("/lastsms", IntentParser.intentOrAlias("sms 123456789 tadi isinya apa", "/sms 123456789 tadi isinya apa"))
        assertEquals("/lastsms", IntentParser.intentOrAlias("sms 0812 3456 tadi isinya apa", "/sms 0812 3456 tadi isinya apa"))
        // Kontrol #595: nomor berspasi 8 digit + "terakhir" TANPA kata tanya
        // tetap formulir kirim.
        assertEquals("/sms 0812 3456 terakhir kalinya", IntentParser.intentOrAlias("sms 0812 3456 terakhir kalinya", "/sms 0812 3456 terakhir kalinya"))
    }

    @Test
    fun intentOrAliasSmsGateSparesCompleteNumberForms() {
        // #546: formulir yang ter-parse jadi nomor lengkap adalah kirim sungguhan,
        // walau isinya memuat "terakhir"; pertanyaan berelongasi tetap terbajak.
        assertEquals(
            "/sms 0812 3456 7890, ini terakhir kalinya",
            IntentParser.intentOrAlias("sms 0812 3456 7890, ini terakhir kalinya", "/sms 0812 3456 7890, ini terakhir kalinya")
        )
        assertEquals("/lastsms", IntentParser.intentOrAlias("sms 0812, terakhirr dari siapa", "/sms 0812, terakhirr dari siapa"))
        assertEquals("/lastsms", IntentParser.intentOrAlias("sms 0812, terbaruu dari siapa", "/sms 0812, terbaruu dari siapa"))
        // #595 (sisa #546): nomor spasi 8 digit yang ter-parse lengkap juga formulir kirim,
        // bukan pertanyaan riwayat, walau isinya memuat "terakhir".
        assertEquals(
            "/sms 0812 3456 terakhir kalinya",
            IntentParser.intentOrAlias("sms 0812 3456 terakhir kalinya", "/sms 0812 3456 terakhir kalinya")
        )
    }

    @Test
    fun intentOrAliasKeepsSmsFormWithCommaHead() {
        assertEquals("/sms 0812, halo apa kabar", IntentParser.intentOrAlias("sms 0812, halo apa kabar", "/sms 0812, halo apa kabar"))
        assertEquals("/sms 0812, halo", IntentParser.intentOrAlias("sms 0812, halo", "/sms 0812, halo"))
    }

    @Test
    fun intentOrAliasMergesArgsWrappedInQuotesBracketsOrStars() {
        assertEquals("/photo depan", IntentParser.intentOrAlias("kirim foto \"depan\" dong", null))
        assertEquals("/photo depan", IntentParser.intentOrAlias("foto (depan) dong", "/photo (depan) dong"))
        assertEquals("/photo depan", IntentParser.intentOrAlias("kirim foto *depan* dong", null))
        // Frasa grup-1 /layar ikut menjadi jangkar argumen jam (issue #726).
        assertEquals("/layar 6", IntentParser.intentOrAlias("screen time 6 jam hari ini", null))
        assertEquals("/layar 6", IntentParser.intentOrAlias("anak main hp 6 jam hari ini", null))
        assertEquals("/layar 6", IntentParser.intentOrAlias("pemakaian aplikasi 6 jam hari ini", null))
        assertEquals("/layar 6", IntentParser.intentOrAlias("screen_time 6 jam hari ini", null))
        assertEquals("/layar", IntentParser.intentOrAlias("screen time hari ini", null))
        assertEquals("/layar 6", IntentParser.intentOrAlias("cek layar (6) jam dong", null))
        assertEquals("/layar 6", IntentParser.intentOrAlias("cek layar \"6\" jam", null))
    }

    @Test
    fun intentOrAliasMergesArgsDespitePunctuation() {
        assertEquals("/photo depan", IntentParser.intentOrAlias("foto depan, dong", "/photo depan, dong"))
        assertEquals("/layar 6", IntentParser.intentOrAlias("layar 6, jam", "/layar 6, jam"))
    }

    @Test
    fun intentOrAliasRecognizesPhotoKeyword() {
        assertEquals("/photo depan", IntentParser.intentOrAlias("photo depan dong", "/photo depan dong"))
        assertEquals("/photo", IntentParser.intentOrAlias("photo sekarang dong", "/photo sekarang dong"))
    }

    @Test
    fun intentOrAliasMergesArgsFromParsedText() {
        assertEquals("/photo depan", IntentParser.intentOrAlias("kirim foto depan sekarang", null))
        assertEquals("/photo depan", IntentParser.intentOrAlias("tolong kirim foto depan sekarang", "/help kirim foto depan sekarang"))
        assertEquals("/layar 6", IntentParser.intentOrAlias("cek layar 6 jam", null))
        assertEquals("/photo depan", IntentParser.intentOrAlias("foto dong depan", "/photo dong depan"))
        assertEquals("/photo", IntentParser.intentOrAlias("tolong kirim foto sekarang", null))
        assertEquals("/layar", IntentParser.intentOrAlias("layar 25 jam", "/layar 25 jam"))
    }

    @Test
    fun intentOrAliasDoesNotMergeDistantArgs() {
        assertEquals("/photo", IntentParser.intentOrAlias("kirim foto sekarang, anak lagi di depan rumah dong", null))
        assertEquals("/photo", IntentParser.intentOrAlias("kirim foto sekarang, yang belakang itu adiknya dong", null))
        assertEquals("/layar", IntentParser.intentOrAlias("cek layar kemarin, anak main 6 jam dong", null))
        assertEquals("/photo depan", IntentParser.intentOrAlias("kirim foto depan sekarang", null))
        assertEquals("/layar 6", IntentParser.intentOrAlias("cek layar 6 jam", null))
    }

    @Test
    fun parseRejectsElongatedReferenceMarkers() {
        val cases = listOf(
            "kirim foto kemarinn dong",
            "kirim jepretann dong",
            "kirim potret yang tadii dong",
            "kirim foto barusann dong"
        )
        for (text in cases) {
            assertNull("frasa: $text", IntentParser.parse(text))
        }
        assertNull(IntentParser.intentOrAlias("hilang kemarinn", "/hilang kemarinn"))
        assertNull(IntentParser.intentOrAlias("hilang tadii", "/hilang tadii"))
        assertNull(IntentParser.intentOrAlias("hilang barusann", "/hilang barusann"))
        assertNull(IntentParser.intentOrAlias("kunci kemarinn", "/lock kemarinn"))
    }

    @Test
    fun parseRejectsElongationPlusSuffixReferences() {
        val cases = listOf(
            "kirim foto kemarinnlah dong",
            "kirim foto kemarinnkan dong",
            "kirim foto tadiilah dong",
            "kirim foto barusannkah dong",
            "kirim foto terakhirrkan dong",
            "kirim foto yang diambillkan dong",
            "kirim ulangg foto itu dong"
        )
        for (text in cases) {
            assertNull("frasa: $text", IntentParser.parse(text))
        }
        assertNull(IntentParser.intentOrAlias("hilang kemarinnlah", "/hilang kemarinnlah"))
        assertNull(IntentParser.intentOrAlias("kunci kemarinnlah", "/lock kemarinnlah"))
        assertTrue(IntentParser.isVetoedText("hilang kemarinnlah", "/hilang kemarinnlah"))
    }

    @Test
    fun parseRejectsBareResultNounsWithPastMarkers() {
        val phrases = listOf(
            "kirim potret yang tadi dong",
            "kirim jepret kemarin dong"
        )
        for (phrase in phrases) {
            assertNull("frasa: $phrase", IntentParser.parse(phrase))
        }
    }

    @Test
    fun parseElongatedCaptureVerbWithPastMarkerStaysReference() {
        // #543: kata kerja tangkap berelongasi/berimbuhan yang lolos markerMatches
        // tidak boleh lolos pengaman penanda masa lalu hanya karena tidak sama persis.
        val phrases = listOf(
            "potrett kemarin kirim dong",
            "jepretlah kemarin kirim dong",
            "potrett tadi kirim dong"
        )
        for (phrase in phrases) {
            assertNull("frasa: $phrase", IntentParser.parse(phrase))
        }
        // Tanpa penanda masa lalu, bentuk berelongasi tetap niat tegas.
        assertEquals("/photo", IntentParser.parse("potrett sekarang"))
        assertEquals("/photo", IntentParser.parse("jepretlah sekarang"))
    }

    @Test
    fun parseToleratesInternalElongation() {
        // #551: elongasi di tengah kata kunci/frasa tidak lagi menggagalkan parse.
        assertEquals("/location", IntentParser.parse("anak dimaana sekarang"))
        assertEquals("/location", IntentParser.parse("lokaasi hp saat ini"))
        assertEquals("/battery", IntentParser.parse("bateraai hp tinggal berapa"))
        assertEquals("/photo", IntentParser.parse("kirim footo sekarang"))
        assertEquals("/screenshot", IntentParser.parse("kirim tangkaap layar sekarang"))
    }

    @Test
    fun parseFirmCaptureIntentToleratesElongation() {
        assertEquals("/photo", IntentParser.parse("tolong jepretlahh fotonya sekarang"))
        assertEquals("/photo", IntentParser.parse("foto kemarin jelek, jepret foto baruu sekarang"))
        assertNull(IntentParser.parse("kirim foto barusan dong"))
    }

    @Test
    fun parseFirmCaptureIntentToleratesStackedSuffix() {
        // #616: imbuhan bertumpuk -kanlah pada kata kerja tangkap tetap niat tegas.
        assertEquals("/photo", IntentParser.parse("ambilkanlah fotonya sekarang"))
        assertEquals("/photo", IntentParser.parse("jepretkanlah fotonya sekarang"))
        assertEquals("/photo", IntentParser.parse("potretkanlah fotonya dong"))
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
    fun intentOrAliasFallbackRespectsParserVeto() {
        val cases: List<Triple<String, String?, String?>> = listOf(
            Triple("kunci tidak", "/lock tidak", null),
            Triple("hilang kemarin", "/hilang kemarin", null),
            Triple("hilang tadi", "/hilang tadi", null),
            Triple("sembuh sekarang", "/sembuh sekarang", "/sembuh sekarang"),
            Triple("darurat sekarang", "/darurat sekarang", "/darurat sekarang"),
            Triple("hilang hpku", "/hilang hpku", "/hilang hpku"),
            Triple("kunci sekarang", "/lock sekarang", "/lock sekarang"),
            Triple("foto depan", "/photo depan", "/photo depan")
        )
        for ((text, alias, expected) in cases) {
            assertEquals("frasa: $text", expected, IntentParser.intentOrAlias(text, alias))
        }
    }

    @Test
    fun intentOrAliasParserWinMergesRelevantAliasArgs() {
        val cases: List<Triple<String, String?, String?>> = listOf(
            Triple("foto depan dong", "/photo depan dong", "/photo depan"),
            Triple("foto belakang dong", "/photo belakang dong", "/photo belakang"),
            Triple("layar 6 jam", "/layar 6 jam", "/layar 6"),
            Triple("layar 25 jam", "/layar 25 jam", "/layar"),
            Triple("foto sekarang dong", "/photo sekarang dong", "/photo"),
            Triple("tolong kirim foto sekarang", "/help kirim foto sekarang", "/photo")
        )
        for ((text, alias, expected) in cases) {
            assertEquals("frasa: $text", expected, IntentParser.intentOrAlias(text, alias))
        }
    }

    @Test
    fun intentOrAliasSmsGateAlignsWithDownstreamParser() {
        val cases: List<Triple<String, String?, String?>> = listOf(
            Triple("sms 123 terakhir dari siapa", "/sms 123 terakhir dari siapa", "/lastsms"),
            Triple("sms 2024 terakhir dari siapa", "/sms 2024 terakhir dari siapa", "/lastsms"),
            Triple("sms 0812.3456.7890 halo", "/sms 0812.3456.7890 halo", "/sms 0812.3456.7890 halo"),
            Triple("sms 0812.3456.7890 halo apa kabar", "/sms 0812.3456.7890 halo apa kabar", "/sms 0812.3456.7890 halo apa kabar"),
            Triple("sms (0812) 3456-7890 halo", "/sms (0812) 3456-7890 halo", "/sms (0812) 3456-7890 halo"),
            Triple("sms 0812 halo", "/sms 0812 halo", "/sms 0812 halo"),
            Triple("sms 1234567 halo", "/sms 1234567 halo", "/sms 1234567 halo")
        )
        for ((text, alias, expected) in cases) {
            assertEquals("frasa: $text", expected, IntentParser.intentOrAlias(text, alias))
        }
    }

    @Test
    fun intentOrAliasKeepsPureAliasForms() {
        val cases: List<Triple<String, String?, String?>> = listOf(
            Triple("kunci", "/lock", "/lock"),
            Triple("kunci sekarang", "/lock sekarang", "/lock sekarang"),
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
