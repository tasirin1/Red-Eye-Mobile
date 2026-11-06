# Uji regresi gerbang Python (issue #1789).
#
# Puluhan perbaikan gerbang (#765..#1788) selama ini
# hanya dibuktikan baterai sandbox sekali pakai yang
# dibuang sesudahnya — regresi pada kanal yang sama
# tak akan tertangkap CI. Berkas ini mengunci kasus-
# kasus kanal utama sebagai uji ter-commit:
#   - mengandung_token (permukaan berkas/isi)
#   - mengandung_token_pesan (permukaan pesan)
#   - mengandung_fragmen_pesan (aturan #1736/#1749/#1750)
#   - paritas penilai fragmen cadangan verifikasi-path
#     (#1788) terhadap penilai kanonik
#   - penjaga vonis tag sekali-jalan verifikasi-path
#     (#1787)
#   - byteArrayOf (#802), dekode UTF-16 (#785/#817),
#     encode web (#1799), aksara format (#1800),
#     escape \xNN (#1801), base64 dua lapis (#1802)
#   - gabungan pesan lintas commit (#1786), metadata
#     author/committer (#1717/#1803), nama tag (#1804)
#     — ditambahkan di issue #1805
# Jalankan: python3 -m unittest discover -s tools/tests
# (juga berjalan sebagai langkah CI di workflow
# Verifikasi Path).
#
# Nilai token di bawah adalah KONTROL UJI SINTETIS
# (bukan kredensial): dirakit dari dua paruh variabel
# agar tak ada token utuh tertulis di berkas ini, dan
# wilayahnya ditandai penanda contoh terdokumentasi
# gerbang (CONTOH-TOKEN-MULAI/SELESAI).

import base64
import importlib.util
import os
import unittest

_AKAR = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def _muat(nama, relatif):
    spec = importlib.util.spec_from_file_location(nama, os.path.join(_AKAR, relatif))
    modul = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(modul)
    return modul


cek_token = _muat("cek_token", os.path.join("tools", "cek-token.py"))
verifikasi = _muat("verifikasi_path", os.path.join("tools", "verifikasi-path.py"))

# CONTOH-TOKEN-MULAI
_KP = "123456789:AAEaaBB-cc"
_EK = "_ddEEFFggHHiiJJkkLLmmNNooPPq"
# CONTOH-TOKEN-SELESAI
TOKEN = _KP + _EK
KEPALA, EKOR = TOKEN[:20], TOKEN[20:]


def _b32(teks):
    return base64.b32encode(teks.encode()).decode()


class UjiMengandungToken(unittest.TestCase):
    def test_mentah(self):
        self.assertTrue(cek_token.mengandung_token("nilai=" + TOKEN))

    def test_berjarak_spasi(self):
        berjarak = TOKEN[:12] + " " + TOKEN[12:24] + " " + TOKEN[24:]
        self.assertTrue(cek_token.mengandung_token(berjarak))

    def test_base64(self):
        self.assertTrue(cek_token.mengandung_token(base64.b64encode(TOKEN.encode()).decode()))

    def test_hex(self):
        self.assertTrue(cek_token.mengandung_token(TOKEN.encode().hex()))

    def test_escape_unicode(self):
        lolos = "".join("\\u%04x" % ord(c) for c in TOKEN)
        self.assertTrue(cek_token.mengandung_token(lolos))

    def test_base32_besar(self):
        self.assertTrue(cek_token.mengandung_token(_b32(TOKEN)))

    def test_base32_kecil(self):
        # Issue #1784: encoder lazim mengeluarkan huruf kecil.
        self.assertTrue(cek_token.mengandung_token(_b32(TOKEN).lower()))

    def test_base32_jinak(self):
        jinak = _b32("teks jinak biasa saja bukan rahasia sama sekali ya").lower()
        self.assertFalse(cek_token.mengandung_token(jinak))

    def test_hash_sha_dikecualikan(self):
        # Issue #1529: id numerik + hash hex murni 40
        # adalah bentuk kutipan log sah, bukan token.
        self.assertFalse(cek_token.mengandung_token("run 41960463603:" + "a" * 40))

    def test_prosa_bersih(self):
        self.assertFalse(cek_token.mengandung_token("perbaikan tombol simpan di layar utama"))


class UjiMengandungTokenPesan(unittest.TestCase):
    def test_mentah(self):
        self.assertTrue(cek_token.mengandung_token_pesan("tempel " + TOKEN + " di sini"))

    def test_satu_spasi_sesudah_titik_dua(self):
        # Issue #1764: kanal tanpa-spasi permukaan pesan.
        self.assertTrue(cek_token.mengandung_token_pesan(TOKEN[:10] + " " + TOKEN[10:]))

    def test_base64(self):
        self.assertTrue(cek_token.mengandung_token_pesan(base64.b64encode(TOKEN.encode()).decode()))

    def test_hex(self):
        self.assertTrue(cek_token.mengandung_token_pesan(TOKEN.encode().hex()))

    def test_base32_besar_dan_kecil(self):
        # Issue #1785: kanal base32 permukaan pesan.
        self.assertTrue(cek_token.mengandung_token_pesan(_b32(TOKEN)))
        self.assertTrue(cek_token.mengandung_token_pesan(_b32(TOKEN).lower()))

    def test_base32_jinak(self):
        jinak = _b32("teks jinak biasa saja bukan rahasia sama sekali ya").lower()
        self.assertFalse(cek_token.mengandung_token_pesan(jinak))

    def test_prosa_id_run(self):
        # Issue #1425: prosa yang mengutip id run + titik
        # dua tidak boleh tertuduh token.
        self.assertFalse(cek_token.mengandung_token_pesan(
            "run 41960463603: langkah build gagal total, periksa log"))


class UjiFragmenPesan(unittest.TestCase):
    def test_belah_dua_baris(self):
        self.assertTrue(cek_token.mengandung_fragmen_pesan(KEPALA + "\n" + EKOR))

    def test_celah_satu_baris(self):
        # Issue #1749: dua blok berseberangan tepat satu
        # baris penyela ikut dinilai tersambung.
        teks = KEPALA + "\nbaris penyela biasa saja\n" + EKOR
        self.assertTrue(cek_token.mengandung_fragmen_pesan(teks))

    def test_baris_pemisah(self):
        # Issue #1750: baris pemisah murni di dalam blok
        # dibuang sebelum penilaian ulang.
        self.assertTrue(cek_token.mengandung_fragmen_pesan(KEPALA + "\n====\n" + EKOR))
        self.assertTrue(cek_token.mengandung_fragmen_pesan(KEPALA + "\n////\n" + EKOR))

    def test_celah_dua_baris_di_luar_aturan(self):
        teks = KEPALA + "\npenyela satu\npenyela dua\n" + EKOR
        self.assertFalse(cek_token.mengandung_fragmen_pesan(teks))

    def test_prosa_bersih(self):
        self.assertFalse(cek_token.mengandung_fragmen_pesan(
            "fix: perbaiki tombol simpan\n\nisi pesan normal tanpa rahasia"))

    def test_blok_alfabet_bukan_token(self):
        self.assertFalse(cek_token.mengandung_fragmen_pesan(
            "AAAAAAAAAAAAAAAAAAAAAAAA\nBBBBBBBBBBBBBBBBBBBBBBBB"))


class UjiParitasCadangan(unittest.TestCase):
    # Issue #1788: penilai cadangan lokal verifikasi-path
    # (dipakai hanya bila pemuatan cek-token gagal) harus
    # memberi vonis yang sama dengan penilai kanonik.

    KASUS = [
        KEPALA + "\n" + EKOR,
        KEPALA + "\nbaris penyela biasa saja\n" + EKOR,
        KEPALA + "\n\n" + EKOR,
        KEPALA + "\n====\n" + EKOR,
        KEPALA + "\n////\n" + EKOR,
        KEPALA + "\npenyela satu\npenyela dua\n" + EKOR,
        "fix: perbaiki tombol simpan\n\nisi pesan normal tanpa rahasia",
        "AAAAAAAAAAAAAAAAAAAAAAAA\nBBBBBBBBBBBBBBBBBBBBBBBB",
    ]

    def test_paritas(self):
        verifikasi._penilai_fragmen = None
        try:
            for teks in self.KASUS:
                self.assertEqual(
                    cek_token.mengandung_fragmen_pesan(teks),
                    verifikasi._pesan_terbelah(teks),
                    "paritas pecah untuk kasus: %r" % teks[:40])
        finally:
            verifikasi._penilai_fragmen = cek_token.mengandung_fragmen_pesan


class UjiVonisTagSekali(unittest.TestCase):
    # Issue #1787: pada mode --semua dua jalur pemindai
    # memanggil penilai anotasi tag dalam satu proses —
    # tag kotor yang sama hanya boleh terhitung sekali.

    def test_tag_kotor_terhitung_sekali(self):
        jalan_asli = verifikasi.jalan

        def jalan_palsu(*args):
            gabung = " ".join(args)
            if "%(contents)" in gabung:
                return "anotasi rilis berisi " + TOKEN
            if "%(refname:short)" in gabung:
                return "vuji\ttag\t1234567890abcdef\tobjekuji"
            return ""

        verifikasi.jalan = jalan_palsu
        verifikasi._cek_tag_sudah_jalan = False
        try:
            gagal_pertama = []
            self.assertTrue(verifikasi._cek_pesan_tag(gagal_pertama))
            self.assertEqual(len(gagal_pertama), 1)
            gagal_kedua = []
            self.assertFalse(verifikasi._cek_pesan_tag(gagal_kedua))
            self.assertEqual(gagal_kedua, [])
        finally:
            verifikasi.jalan = jalan_asli
            verifikasi._cek_tag_sudah_jalan = False


class UjiKanalBerkasTambahan(unittest.TestCase):
    # Kanal permukaan berkas/pesan yang lahir sesudah
    # angkatan pertama suite ini; sebelum #1805 mereka
    # hanya dibuktikan baterai sekali pakai.

    def test_byte_array_of(self):
        literal = "byteArrayOf(" + ", ".join(
            "0x%02x" % b for b in TOKEN.encode()) + ")"
        self.assertTrue(cek_token.mengandung_token(literal))

    def test_byte_array_of_jinak(self):
        jinak = b"\x89PNG\r\n\x1a\n" + bytes(range(40))
        literal = "byteArrayOf(" + ", ".join(
            "0x%02x" % b for b in jinak) + ")"
        self.assertFalse(cek_token.mengandung_token(literal))

    def test_utf16_bom_dan_tanpa_bom(self):
        for enc in ("utf-16", "utf-16-le", "utf-16-be"):
            with self.subTest(enc=enc):
                self.assertTrue(cek_token.mengandung_token(
                    cek_token.dekode_berkas(("kunci=" + TOKEN).encode(enc))))

    def test_utf16_jinak(self):
        self.assertFalse(cek_token.mengandung_token(
            cek_token.dekode_berkas("teks biasa saja".encode("utf-16"))))

    def test_encode_persen_dan_entitas(self):
        self.assertTrue(cek_token.mengandung_token(TOKEN.replace(":", "%3A")))
        self.assertTrue(cek_token.mengandung_token_pesan(TOKEN.replace(":", "%3A")))
        self.assertTrue(cek_token.mengandung_token(TOKEN.replace(":", "&#58;")))
        self.assertFalse(cek_token.mengandung_token("persen %3A biasa saja"))

    def test_aksara_format_tak_kasatmata(self):
        self.assertTrue(cek_token.mengandung_token(TOKEN.replace(":", ":\u200b")))
        self.assertTrue(cek_token.mengandung_token_pesan(TOKEN[:20] + "\u200d" + TOKEN[20:]))
        self.assertTrue(cek_token.mengandung_token("\ufeff" + TOKEN))

    def test_escape_x(self):
        esc = "".join("\\x%02x" % ord(c) for c in TOKEN)
        self.assertTrue(cek_token.mengandung_token(esc))
        self.assertTrue(cek_token.mengandung_token_pesan(esc))
        self.assertFalse(cek_token.mengandung_token("path C:\\x41\\x42 pendek"))

    def test_base64_dua_lapis(self):
        lapis1 = base64.b64encode(TOKEN.encode()).decode()
        lapis2 = base64.b64encode(lapis1.encode()).decode()
        self.assertTrue(cek_token.mengandung_token(lapis2))
        self.assertTrue(cek_token.mengandung_token_pesan(lapis2))
        jinak2 = base64.b64encode(base64.b64encode(b"teks jinak biasa")).decode()
        self.assertFalse(cek_token.mengandung_token(jinak2))
        self.assertFalse(cek_token.mengandung_token_pesan(jinak2))

    def test_dekoder_dua_lapis_semua_pasangan(self):
        # Issue #1813: hex dan base32 kini dua lapis
        # seperti base64 #1802, termasuk silang antar
        # dekoder — total tepat dua lapis.
        hx = TOKEN.encode().hex()
        b32 = base64.b32encode(TOKEN.encode()).decode()
        b64 = base64.b64encode(TOKEN.encode()).decode()
        for nama, s in [
            ("hex(hex)", hx.encode().hex()),
            ("b32(b32)", base64.b32encode(b32.encode()).decode()),
            ("b64(hex)", base64.b64encode(hx.encode()).decode()),
            ("hex(b64)", b64.encode().hex()),
            ("b64(b32)", base64.b64encode(b32.encode()).decode()),
            ("b32(b64)", base64.b32encode(b64.encode()).decode()),
        ]:
            with self.subTest(pasangan=nama):
                self.assertTrue(cek_token.mengandung_token(s))

    def test_lapis_terbuka_di_dalam_dekoder(self):
        # Issue #1823: lapisan persen/entitas/escape di
        # sisi DALAM dekoder — penilai lapis bawah kini
        # menilai hasil dekode dalam bentuk terbukanya
        # juga, mencerminkan arah #1812.
        import urllib.parse
        persen = urllib.parse.quote(TOKEN, safe="")
        entitas = TOKEN.replace(":", "&#58;")
        escape = TOKEN.replace(":", "\\x3a")
        for nama, s in [
            ("b64(persen)", base64.b64encode(persen.encode()).decode()),
            ("b64(entitas)", base64.b64encode(entitas.encode()).decode()),
            ("b64(escape)", base64.b64encode(escape.encode()).decode()),
            ("hex(persen)", persen.encode().hex()),
            ("b32(persen)", base64.b32encode(persen.encode()).decode()),
        ]:
            with self.subTest(pasangan=nama):
                self.assertTrue(cek_token.mengandung_token(s))
                self.assertTrue(cek_token.mengandung_token_pesan(s))
                self.assertTrue(cek_token.mengandung_token_pesan(s))
        jinak = base64.b64encode(
            "teks jinak biasa saja".encode().hex().encode()).decode()
        self.assertFalse(cek_token.mengandung_token(jinak))
        self.assertFalse(cek_token.mengandung_token_pesan(jinak))

    def test_dua_lapis_dekoder_berakhir_terbuka(self):
        # Issue #1832: komposisi dua dekoder yang hasil
        # akhirnya ter-persen/entitas/escape. Vonis
        # varian terbuka #1823 dulu hanya diterapkan
        # _nilai_lapis_bawah (kedalaman 0); hasil dekode
        # kedalaman 1 hanya dinilai pola mentahnya.
        persen = "".join("%%%02X" % ord(c) for c in TOKEN)
        entitas = "".join("&#%d;" % ord(c) for c in TOKEN)
        escape = "".join("\\u%04x" % ord(c) for c in TOKEN)
        b64p = base64.b64encode(persen.encode()).decode()
        for nama, s in [
            ("b64(b64(persen))",
             base64.b64encode(b64p.encode()).decode()),
            ("hex(b64(persen))", b64p.encode().hex()),
            ("b64(b64(entitas))",
             base64.b64encode(
                 base64.b64encode(entitas.encode()).decode().encode()
             ).decode()),
            ("b32(b64(persen))",
             base64.b32encode(b64p.encode()).decode()),
            ("b64(hex(persen))",
             base64.b64encode(persen.encode().hex().encode()).decode()),
            ("b64(b64(escape))",
             base64.b64encode(
                 base64.b64encode(escape.encode()).decode().encode()
             ).decode()),
        ]:
            with self.subTest(pasangan=nama):
                self.assertTrue(cek_token.mengandung_token(s))
                self.assertTrue(cek_token.mengandung_token_pesan(s))
        jinak = base64.b64encode(base64.b64encode(
            "".join("%%%02X" % ord(c)
                    for c in "teks jinak biasa saja").encode()
        ).decode().encode()).decode()
        self.assertFalse(cek_token.mengandung_token(jinak))
        self.assertFalse(cek_token.mengandung_token_pesan(jinak))

    def test_bytearrayof_berlapis(self):
        # Issue #1837: byte rakitan byteArrayOf dinilai
        # seperti hasil dekode lain (pola/varian terbuka
        # + satu lapis bawah), bukan hanya pola mentah —
        # ejaan persen/base64/hex dari token sebelumnya
        # lolos sebagai literal byte.
        def eja(teks):
            return "byteArrayOf(" + ", ".join(
                "0x%02x" % b for b in teks.encode()) + ")"
        persen = "".join("%%%02X" % ord(c) for c in TOKEN)
        b64 = base64.b64encode(TOKEN.encode()).decode()
        for nama, s in [
            ("polos", TOKEN),
            ("persen", persen),
            ("b64", b64),
            ("hex", TOKEN.encode().hex()),
            ("b64(persen)", base64.b64encode(persen.encode()).decode()),
        ]:
            with self.subTest(ejaan=nama):
                self.assertTrue(cek_token.mengandung_token(eja(s)))
        jinak = eja("teks jinak biasa saja tanpa rahasia")
        self.assertFalse(cek_token.mengandung_token(jinak))

    def test_komposisi_silang_varian_dekoder(self):
        # Issue #1812: varian terbuka diteruskan ke
        # dekoder — encode di atas encode tertangkap.
        b64 = base64.b64encode(TOKEN.encode()).decode()
        persen = "".join("%%%02X" % ord(c) for c in b64)
        self.assertTrue(cek_token.mengandung_token(persen))
        self.assertTrue(cek_token.mengandung_token_pesan(persen))
        escx = "".join("\\x%02x" % ord(c) for c in b64)
        self.assertTrue(cek_token.mengandung_token(escx))
        self.assertTrue(cek_token.mengandung_token_pesan(escx))
        hx_persen = "".join("%%%02X" % ord(c) for c in TOKEN.encode().hex())
        self.assertTrue(cek_token.mengandung_token(hx_persen))
        jinak = "".join("%%%02X" % ord(c) for c in "teks jinak biasa saja")
        self.assertFalse(cek_token.mengandung_token(jinak))
        self.assertFalse(cek_token.mengandung_token_pesan(jinak))

    def test_escape_U_dan_dua_lapis(self):
        # Issue #1811: escape \U 8-digit dikenali, dan
        # escape dua lapis (kedua arah) terbuka oleh dua
        # lintasan _varian_terbuka (#1810).
        esc_u_besar = "".join("\\U%08x" % ord(c) for c in TOKEN)
        self.assertTrue(cek_token.mengandung_token(esc_u_besar))
        self.assertTrue(cek_token.mengandung_token_pesan(esc_u_besar))
        ganda = TOKEN.replace(":", "\\u005cu003a")
        self.assertTrue(cek_token.mengandung_token(ganda))
        self.assertTrue(cek_token.mengandung_token_pesan(ganda))
        jinak = 'teks = "\\U00000041\\U00000042 pendek"'
        self.assertFalse(cek_token.mengandung_token(jinak))

    def test_encode_web_dua_lapis(self):
        # Issue #1810: persen ganda dan entitas bertingkat
        # terbuka di lintasan kedua _varian_terbuka.
        ganda = TOKEN.replace(":", "%253A")
        self.assertTrue(cek_token.mengandung_token(ganda))
        self.assertTrue(cek_token.mengandung_token_pesan(ganda))
        entitas = TOKEN.replace(":", "&#38;#58;")
        self.assertTrue(cek_token.mengandung_token(entitas))
        self.assertTrue(cek_token.mengandung_token_pesan(entitas))
        jinak = "diskon 50%25 dan 25%3A bukan token"
        self.assertFalse(cek_token.mengandung_token(jinak))
        self.assertFalse(cek_token.mengandung_token_pesan(jinak))

    def test_belah_tanda_kurung(self):
        # Issue #1814: fragmen yang masing-masing dibungkus
        # kurung tersambung lagi oleh pemadatan — hanya
        # pasangan kurung antar fragmen yang dibuang.
        belah = 'val t = ("' + TOKEN[:20] + '") + ("' + TOKEN[20:] + '")'
        self.assertTrue(cek_token.mengandung_token(belah))
        jinak = 'fun hitung(x: Int) = (x + 1) * (2 + 3)'
        self.assertFalse(cek_token.mengandung_token(jinak))
        # Kontrol positif palsu nyata: contoh format angka
        # disusul kurung tunggal + prosa TIDAK tertuduh.
        contoh = ("berkelompok dengan pemisah koma, garis miring, "
                  "dan titik dua 123.456.123/456.123:456) yang "
                  "sebelumnya tampil polos di notifikasi")
        self.assertFalse(cek_token.mengandung_token(contoh))


class UjiPesanVerifikasiPath(unittest.TestCase):
    # Kanal pesan milik verifikasi-path (bukan cek-token):
    # gabungan lintas commit, metadata, dan nama tag.
    # git disimulasikan lewat monkeypatch jalan, pola
    # yang sama dengan UjiVonisTagSekali.

    def _pasang_jalan(self, palsu):
        asli = verifikasi.jalan
        verifikasi.jalan = palsu
        verifikasi._cek_tag_sudah_jalan = False
        self.addCleanup(setattr, verifikasi, "jalan", asli)
        self.addCleanup(setattr, verifikasi, "_cek_tag_sudah_jalan", False)

    def test_gabungan_lintas_commit(self):
        pesan = {"a" * 40: "perbaiki parser " + KEPALA,
                 "b" * 40: EKOR + " sesudahnya"}

        def palsu(*args):
            gabung = " ".join(args)
            if "rev-list" in gabung:
                return "\n".join(pesan)
            if "%an" in gabung:
                return "Penguji uji@uji.id Penguji uji@uji.id"
            if "%B" in gabung:
                return pesan[args[-1]]
            return ""

        self._pasang_jalan(palsu)
        gagal = []
        verifikasi.cek_secret_pesan(gagal, "rentang-uji")
        self.assertEqual(len(gagal), 1, gagal)
        self.assertIn("TERBELAH lintas pesan", gagal[0])

    def test_metadata_rentang(self):
        def palsu(*args):
            gabung = " ".join(args)
            if "rev-list" in gabung:
                return "c" * 40
            if "%an" in gabung:
                return TOKEN + " uji@uji.id Penguji uji@uji.id"
            if "%B" in gabung:
                return "pesan bersih"
            return ""

        self._pasang_jalan(palsu)
        gagal = []
        verifikasi.cek_secret_pesan(gagal, "rentang-uji")
        self.assertTrue(any("METADATA" in g for g in gagal), gagal)

    def test_metadata_semua(self):
        rekaman = (("d" * 40) + "\x00" + TOKEN
                   + "\x00uji@uji.id\x00Penguji\x00uji@uji.id"
                   + "\x00pesan bersih\x1e")

        def palsu(*args):
            if "--format=%H" in " ".join(args):
                return rekaman
            return ""

        self._pasang_jalan(palsu)
        gagal = []
        verifikasi.cek_secret_pesan_semua(gagal)
        self.assertTrue(any("METADATA" in g for g in gagal), gagal)

    def test_pesan_semua_badan(self):
        rekaman = (("e" * 40)
                   + "\x00Penguji\x00uji@uji.id\x00Penguji\x00uji@uji.id"
                   + "\x00memuat " + TOKEN + "\x1e")

        def palsu(*args):
            if "--format=%H" in " ".join(args):
                return rekaman
            return ""

        self._pasang_jalan(palsu)
        gagal = []
        verifikasi.cek_secret_pesan_semua(gagal)
        self.assertTrue(any("di PESAN commit" in g for g in gagal), gagal)

    def test_nama_tag_terencode(self):
        nama = "uji-" + base64.urlsafe_b64encode(
            TOKEN.encode()).decode().rstrip("=")

        def palsu(*args):
            gabung = " ".join(args)
            if "%(contents)" in gabung:
                return "anotasi bersih"
            if "%(refname:short)" in gabung:
                return nama + "\ttag\t1234567890abcdef\tobjekuji"
            return ""

        self._pasang_jalan(palsu)
        gagal = []
        self.assertTrue(verifikasi._cek_pesan_tag(gagal))
        self.assertTrue(any("di NAMA tag" in g for g in gagal), gagal)

    def test_nama_branch_terencode(self):
        # Issue #1815: nama branch dinilai dengan penilai
        # yang sama seperti nama tag #1804.
        nama = "uji-" + base64.urlsafe_b64encode(
            TOKEN.encode()).decode().rstrip("=")

        def palsu(*args):
            gabung = " ".join(args)
            if "refs/heads" in gabung:
                return "main\n" + nama
            return ""

        self._pasang_jalan(palsu)
        gagal = []
        self.assertTrue(verifikasi._cek_pesan_tag(gagal))
        self.assertTrue(any("di NAMA branch" in g for g in gagal), gagal)

    def test_nama_branch_remote_origin_terencode(self):
        # Issue #1824: refs/remotes/origin adalah cermin
        # branch milik sendiri di remote tujuan push —
        # namanya dinilai juga, tidak seperti remote lain.
        nama = "uji-" + base64.urlsafe_b64encode(
            TOKEN.encode()).decode().rstrip("=")

        def palsu(*args):
            gabung = " ".join(args)
            if "refs/remotes/origin" in gabung:
                return "origin/main\norigin/" + nama
            return ""

        self._pasang_jalan(palsu)
        gagal = []
        self.assertTrue(verifikasi._cek_pesan_tag(gagal))
        self.assertTrue(any("branch remote origin" in g for g in gagal),
                        gagal)

    def test_terbelah_lintas_perpukaan(self):
        # Issue #1825: separuh token ter-encode di nama
        # branch, separuh lain di pesan commit — sendiri-
        # sendiri bersih, tersambung tertuduh.
        enc = base64.urlsafe_b64encode(
            TOKEN.encode()).decode().rstrip("=")
        kepala, ekor = enc[:30], enc[30:]

        def palsu(*args):
            gabung = " ".join(args)
            if "refs/heads" in gabung:
                return kepala
            return ""

        self._pasang_jalan(palsu)
        gagal = []
        self.assertTrue(verifikasi._cek_pesan_tag(gagal, [ekor]))
        self.assertTrue(any("lintas permukaan" in g for g in gagal),
                        gagal)

    def test_terbelah_tak_seimbang(self):
        # Issue #1833 (a): belahan tak-seimbang —
        # fragmen 18 di nama branch + 42 di pesan.
        # Penyaring lama menuntut tepi >=20 di SETIAP
        # permukaan sehingga pembawa fragmen 18 gugur
        # sebelum dinilai; uji jumlah-larian menilai
        # pasangan berdasar jumlah kedua tepinya.
        enc = base64.urlsafe_b64encode(
            TOKEN.encode()).decode().rstrip("=")
        kepala, ekor = enc[:18], enc[18:]

        def palsu(*args):
            gabung = " ".join(args)
            if "refs/heads" in gabung:
                return "wip/" + kepala
            return ""

        self._pasang_jalan(palsu)
        gagal = []
        self.assertTrue(verifikasi._cek_pesan_tag(gagal, [ekor]))
        self.assertTrue(any("lintas permukaan" in g for g in gagal),
                        gagal)

    def test_terbelah_fragmen_tengah_pesan(self):
        # Issue #1833 (b): separuh lain dibungkus prosa
        # di TENGAH pesan — penggabungan permukaan utuh
        # tak pernah menyambungnya; pasangan
        # antar-larian yang menangkapnya.
        enc = base64.urlsafe_b64encode(
            TOKEN.encode()).decode().rstrip("=")
        kepala, ekor = enc[:30], enc[30:]

        def palsu(*args):
            gabung = " ".join(args)
            if "refs/heads" in gabung:
                return "uji-" + kepala
            return ""

        self._pasang_jalan(palsu)
        gagal = []
        pesan = "perbaikan biasa " + ekor + " selesai"
        self.assertTrue(verifikasi._cek_pesan_tag(gagal, [pesan]))
        self.assertTrue(any("lintas permukaan" in g for g in gagal),
                        gagal)

    def test_larian_jinak_tak_tertuduh(self):
        # Kontrol #1833: dua larian jinak panjang dari
        # permukaan berbeda tetap jinak sesudah
        # disambung oleh kedua mekanisme baru.
        def palsu(*args):
            gabung = " ".join(args)
            if "refs/heads" in gabung:
                return "uji-" + "x" * 30
            return ""

        self._pasang_jalan(palsu)
        gagal = []
        self.assertFalse(verifikasi._cek_pesan_tag(
            gagal, ["catatan " + "y" * 30 + " selesai"]))
        self.assertFalse(gagal)

    def test_tag_bersarang(self):
        # Issue #1826: objek tag dalam tanpa ref sendiri
        # — pesan tag dalamnya kini ditelusuri lewat
        # rantai object dan dinilai.
        luar_obj = "a" * 40
        dalam_obj = "b" * 40

        def palsu(*args):
            gabung = " ".join(args)
            if "cat-file" in gabung and "-t" in args:
                return "tag\n" if dalam_obj in gabung else ""
            if "cat-file" in gabung and luar_obj in gabung:
                return ("object " + dalam_obj + "\ntype tag\n"
                        "tag luar\n\nanotasi luar bersih")
            if "cat-file" in gabung and dalam_obj in gabung:
                return ("object " + "c" * 40 + "\ntype commit\n"
                        "tag dalam\n\ncatatan " + TOKEN)
            if "%(contents)" in gabung:
                return "anotasi luar bersih"
            if "%(refname:short)" in gabung:
                return "luar\ttag\t" + "c" * 40 + "\t" + luar_obj
            return ""

        self._pasang_jalan(palsu)
        gagal = []
        self.assertTrue(verifikasi._cek_pesan_tag(gagal))
        self.assertTrue(any("BERSARANG" in g for g in gagal), gagal)

    def test_tag_bersarang_ikut_gabungan(self):
        # Issue #1834: pesan tag DALAM separuh token +
        # pesan commit separuh lain — tag dalam kini
        # masuk korpus gabungan lintas permukaan.
        enc = base64.urlsafe_b64encode(
            TOKEN.encode()).decode().rstrip("=")
        kepala, ekor = enc[:30], enc[30:]
        luar_obj = "a" * 40
        dalam_obj = "b" * 40

        def palsu(*args):
            gabung = " ".join(args)
            if "cat-file" in gabung and "-t" in args:
                return "tag\n" if dalam_obj in gabung else ""
            if "cat-file" in gabung and luar_obj in gabung:
                return ("object " + dalam_obj + "\ntype tag\n"
                        "tag luar\n\nanotasi luar bersih")
            if "cat-file" in gabung and dalam_obj in gabung:
                return ("object " + "c" * 40 + "\ntype commit\n"
                        "tag dalam\n\n" + kepala)
            if "%(contents)" in gabung:
                return "anotasi luar bersih"
            if "%(refname:short)" in gabung:
                return "luar\ttag\t" + "c" * 40 + "\t" + luar_obj
            return ""

        self._pasang_jalan(palsu)
        gagal = []
        self.assertTrue(verifikasi._cek_pesan_tag(gagal, [ekor]))
        self.assertTrue(any("lintas permukaan" in g for g in gagal),
                        gagal)

    def test_nama_anggota_tar_terkompresi(self):
        # Issue #1827: nama anggota tar terkompresi
        # (gz/bz2/xz) dan tar polos kini terbaca lewat
        # dekode_berkas/_dekode dan dinilai.
        import io
        import tarfile

        def buat(mode):
            buf = io.BytesIO()
            with tarfile.open(fileobj=buf, mode=mode) as tf:
                for nama in ("biasa.txt", "kunci-" + TOKEN + ".txt"):
                    info = tarfile.TarInfo(nama)
                    data = b"isi"
                    info.size = len(data)
                    tf.addfile(info, io.BytesIO(data))
            return buf.getvalue()

        for mode in ("w:gz", "w:bz2", "w:xz", "w"):
            with self.subTest(mode=mode):
                mentah = buat(mode)
                self.assertTrue(cek_token.mengandung_token(
                    cek_token.dekode_berkas(mentah)))
                self.assertTrue(cek_token.mengandung_token(
                    verifikasi._dekode(mentah)))
        self.assertFalse(cek_token.mengandung_token(
            cek_token.dekode_berkas(b"halo dunia biasa")))

    def test_memoisasi_dekoder_stabil(self):
        # Issue #1830: cache penilai dekoder tak boleh
        # mengubah vonis — panggilan berulang atas
        # kandidat yang sama (panas) wajib sama dengan
        # panggilan pertama (dingin), untuk yang
        # tertuduh maupun yang bersih.
        import urllib.parse
        kotor = base64.b64encode(
            urllib.parse.quote(TOKEN, safe="").encode()).decode()
        for _ in range(3):
            self.assertTrue(cek_token.mengandung_token(kotor))
            self.assertTrue(cek_token.mengandung_token_pesan(kotor))
            self.assertFalse(
                cek_token.mengandung_token("teks jinak pengulangan"))
            self.assertFalse(
                cek_token.mengandung_token_pesan("teks jinak pengulangan"))

if __name__ == "__main__":
    unittest.main()
