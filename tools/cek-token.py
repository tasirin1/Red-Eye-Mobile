#!/usr/bin/env python3
"""Pemindai token bot untuk gerbang repo (issue #765).

Mengenali bentuk-bentuk token:
  1. literal utuh satu baris (pola lama),
  2. terbelah antar baris / antar literal string / oleh pemisah kode
     lain (".", "${...}", pasangan tanda kurung antar fragmen) —
     dipadatkan dulu (issue #772, pasangan kurung #1814),
  3. ter-encode base64 (kandidat panjang didekode lalu dicocokkan),
     termasuk base64 yang terbelah lalu tersambung lagi sesudah
     pemadatan (issue #772),
  4. ter-encode hex (kandidat hex panjang didekode lalu dicocokkan,
     issue #784).

Dipakai tools/verifikasi-path.py (diimpor) dan scripts/cek-cepat.sh:
    cek-token.py --teks     : baca teks dari stdin; cetak DITEMUKAN bila ada
    cek-token.py --teks-pesan: sama, varian permukaan pesan (tanpa pemadatan)
    cek-token.py --berkas   : baca daftar path NUL-separated dari stdin;
                              cetak path pertama yang mengandung token
Kode keluar selalu 0; keputusan dibaca dari keluaran stdout.
"""
import base64
import hashlib
import html
import json
import os
import re
import sys
import urllib.parse

POLA = re.compile(r"[0-9]{6,}:[A-Za-z0-9_-]{35,}")
# Aksara format tak kasatmata (issue #1800): ZWSP/ZWNJ/ZWJ,
# penggabung kata, BOM, tanda hubung lunak, kontrol bidi,
# dan operator tak kasatmata. Tak pernah bermakna di teks
# sumber/pesan, tetapi satu sisipan di tengah token
# memutus pola DAN tak terlihat pada diff.
POLA_FORMAT = re.compile(r"[\u00ad\u200b-\u200f\u202a-\u202e\u2060-\u2064\ufeff]+")
_POLA_HEX = re.compile(r"[0-9a-fA-F]+\Z")
# Penanda contoh eksplisit (issue #1530): teks di antara
# dua penanda ini adalah CONTOH token terdokumentasi dan
# disamarkan sebelum pemindaian, di kedua permukaan.
# Tanpa penanda, contoh kanonis BotFather hanya selamat
# karena ekornya 34 aksara (satu di bawah ambang) —
# kebetulan aritmetika, bukan kebijakan. Penanda tampil
# mencolok saat peninjauan, jadi bukan jalur sembunyi.
_POLA_CONTOH = re.compile(r"CONTOH-TOKEN-MULAI.*?CONTOH-TOKEN-SELESAI", re.S)


def _samarkan_contoh(teks):
    return _POLA_CONTOH.sub("[contoh-token]", teks)


def _pola_mentah_cocok(teks):
    """Kecocokan POLA mentah pada teks, dengan satu
    pengecualian terbukti (issue #1529): ekor yang MURNI
    heksadesimal sepanjang tepat 40/64 adalah bentuk hash
    SHA-1/SHA-256 — pesan sah yang mengutip 'run
    41960463603:<sha40 rapat>' tertuduh token bot.
    Peluang token bot asli berekor heksadesimal murni
    40 aksara ≈ (16/64)^40 — dapat diabaikan; token yang
    sama tetap tertangkap lewat jalur dekode hex/base64
    bila disamarkan, dan ekor hex panjang lain (35, 48,
    ...) tetap tertuduh."""
    for m in POLA.finditer(teks):
        ekor = m.group(0).split(":", 1)[1]
        if len(ekor) in (40, 64) and _POLA_HEX.match(ekor):
            continue
        # Kelanjutan #1529 (issue #1543): pada teks
        # TERPADATKAN, kata sesudah hash tersambung
        # menjadi ekor campuran ('<sha40>gagal') sehingga
        # pengecualian di atas kalah di permukaan berkas.
        # Ekor yang 40/64 aksara pertamanya hex murni dan
        # dilanjutkan kata prosa adalah hash + kata
        # berikutnya, bukan token (ekor token asli 35
        # aksara campuran entropi tinggi).
        lolos_hash_kata = False
        for n in (40, 64):
            if len(ekor) > n and _POLA_HEX.match(ekor[:n]):
                sisa = ekor[n:]
                # Kata pertama sesudah hash (issue #1568):
                # syarat lama (seluruh sisa huruf kecil)
                # terlalu sempit — 'GagalTotal', 'Gagal
                # total', 'gagal_total', 'gagal 2 kali',
                # 'gagal2x' tetap tertuduh padahal permukaan
                # pesan bersih untuk teks yang sama. Nilai
                # deret huruf PEMBUKA sisa saja (huruf apa
                # pun) sepanjang wajar sebuah kata (<=30):
                # gerbang hex 40/64 di atas sudah menying-
                # kirkan token asli secara praktis, sisanya
                # adalah prosa yang terpadatkan.
                kata = 0
                for c in sisa:
                    if c.isalpha():
                        kata += 1
                    else:
                        break
                # Plafon panjang sisa (issue #1602): sisa
                # yang panjangnya mencapai ekor token
                # minimum (35) bisa memuat ekor token ASLI
                # utuh yang menempel rapat sesudah hash —
                # kata pembukanya saja lolos sebagai
                # 'prosa' padahal digit + entropi
                # sesudahnya tak pernah dinilai. Sisa
                # di bawah 35 tak mungkin memuat ekor
                # token utuh, jadi pengecualian prosa
                # hanya berlaku di bawah plafon itu.
                if 1 <= kata <= 30 and len(sisa) < 35:
                    lolos_hash_kata = True
                    break
        if lolos_hash_kata:
            continue
        return True
    return False
POLA_BYTE = re.compile(rb"[0-9]{6,}:[A-Za-z0-9_-]{35,}")
POLA_KANDIDAT_B64 = re.compile(r"[A-Za-z0-9+/=_-]{48,}")
POLA_KANDIDAT_HEX = re.compile(r"[0-9a-fA-F]{80,}")


# Memoisasi penilai dekoder (issue #1830): ketiga
# penilai murni adalah fungsi murni dari (kandidat,
# kedalaman), tetapi kandidat yang sama dinilai
# berulang kali — berkas besar menghasilkan ribuan
# kandidat dengan >80% duplikat (kandidat yang sama
# muncul di teks mentah, varian tanpa-spasi, varian
# terpadatkan, dan varian terbuka). Cache proses
# berbatas 100rb entri meruntuhkan kerja duplikat
# itu tanpa mengubah satu pun vonis.
_cache_cocok = {}


def _cocok_b64(kandidat, kedalaman=0):
    kunci = ("b", kandidat, kedalaman)
    if kunci in _cache_cocok:
        return _cache_cocok[kunci]
    hasil = _cocok_b64_murni(kandidat, kedalaman)
    if len(_cache_cocok) < 100000:
        _cache_cocok[kunci] = hasil
    return hasil


def _cocok_hex(kandidat, kedalaman=0):
    kunci = ("h", kandidat, kedalaman)
    if kunci in _cache_cocok:
        return _cache_cocok[kunci]
    hasil = _cocok_hex_murni(kandidat, kedalaman)
    if len(_cache_cocok) < 100000:
        _cache_cocok[kunci] = hasil
    return hasil


def _cocok_b32(kandidat, kedalaman=0):
    kunci = ("3", kandidat, kedalaman)
    if kunci in _cache_cocok:
        return _cache_cocok[kunci]
    hasil = _cocok_b32_murni(kandidat, kedalaman)
    if len(_cache_cocok) < 100000:
        _cache_cocok[kunci] = hasil
    return hasil


def _cocok_b64_murni(kandidat, kedalaman=0):
    teks = kandidat.replace("-", "+").replace("_", "/")
    for awal in range(0, min(4, len(teks))):
        inti = teks[awal:].rstrip("=")
        if len(inti) < 48:
            continue
        for potong in range(0, 4):
            s = inti[:len(inti) - potong] if potong else inti
            s += "=" * (-len(s) % 4)
            try:
                hasil = base64.b64decode(s, validate=True)
            except Exception:
                continue
            if _pola_atau_terbuka(hasil):
                return True
            if kedalaman < 1:
                # Base64 dua lapis (issue #1802): hasil
                # lapis pertama yang masih berupa base64
                # dinilai ulang satu tingkat — log/konfig
                # bersarang lazim meng-encode dua kali.
                # Kedalaman dibatasi tepat dua lapis.
                # Penilaian lapis bawah kini mencakup
                # ketiga encoding (issue #1813).
                if _nilai_lapis_bawah(hasil):
                    return True
    return False


def _cocok_hex_murni(kandidat, kedalaman=0):
    s = kandidat
    if len(s) % 2 == 1:
        s = s[:-1]
    try:
        hasil = bytes.fromhex(s)
    except Exception:
        return False
    if _pola_atau_terbuka(hasil):
        return True
    if kedalaman < 1:
        return _nilai_lapis_bawah(hasil)
    return False


def _cocok_b32_murni(kandidat, kedalaman=0):
    s32 = kandidat.upper()
    s32 += "=" * ((8 - len(s32) % 8) % 8)
    try:
        hasil = base64.b32decode(s32)
    except Exception:
        return False
    if _pola_atau_terbuka(hasil):
        return True
    if kedalaman < 1:
        return _nilai_lapis_bawah(hasil)
    return False


def _nilai_lapis_bawah(hasil):
    """Penilaian satu tingkat atas hasil dekode
    (issue #1813): teks ASCII hasil dekode dipindai
    kandidat base64/hex/base32 dan dinilai dekoder
    masing-masing pada kedalaman 1 (pola saja, tanpa
    rekursi lanjutan) — total tepat dua lapis untuk
    semua pasangan encoding, menyelaraskan hex dan
    base32 dengan base64 #1802. Hasil dekode juga
    dinilai dalam bentuk TERBUKANYA (issue #1823):
    lapisan persen/entitas/escape di sisi DALAM
    dekoder (b64(persen(token)) dan kawan-kawan)
    sebelumnya lolos karena penilai lapis bawah tak
    pernah membuka varian — cermin arah #1812.
    Pembukaan bukan lapis dekode tambahan, jadi
    batas dua lapis tak berubah. Varian terbuka
    hanya dihitung bila ada penanda lapisan terbuka
    di teksnya, agar jalur panas tak melambat."""
    lapis = hasil.decode("ascii", "ignore")
    sumber = [lapis]
    if "%" in lapis or "&" in lapis or "\\" in lapis:
        sumber += _varian_terbuka(lapis)
    for teks in sumber:
        if POLA_BYTE.search(teks.encode("ascii", "ignore")):
            return True
        for m in POLA_KANDIDAT_B64.finditer(teks):
            if _cocok_b64(m.group(0), 1):
                return True
        for m in POLA_KANDIDAT_HEX.finditer(teks):
            if _cocok_hex(m.group(0), 1):
                return True
        for m in re.finditer(r"[A-Za-z2-7]{40,}={0,6}", teks):
            if _cocok_b32(m.group(0), 1):
                return True
    return False


def _pola_atau_terbuka(hasil):
    """Vonis terminal atas satu hasil dekode (issue
    #1832): pola byte langsung, atau pola pada varian
    TERBUKA teksnya. Dipakai ketiga penilai dekoder
    agar hasil dekode lapis KEDUA yang masih ter-persen/
    entitas/escape ikut tertuduh: sebelumnya hanya
    _nilai_lapis_bawah yang membuka varian, dan ia tak
    dipanggil lagi dari kedalaman 1, jadi komposisi
    dua dekoder berakhir varian terbuka (mis.
    b64(b64(persen(token)))) lolos total. Pembukaan
    bukan lapis dekode tambahan; batas dua lapis tak
    berubah. Gerbang penanda sama seperti #1823 agar
    jalur panas tak melambat."""
    if POLA_BYTE.search(hasil):
        return True
    lapis = hasil.decode("ascii", "ignore")
    if "%" in lapis or "&" in lapis or "\\" in lapis:
        for teks in _varian_terbuka(lapis):
            if POLA_BYTE.search(
                    teks.encode("ascii", "ignore")):
                return True
    return False

def _padatkan(teks):
    # Sisipan interpolasi "${...}" dibuang utuh lebih dulu (menyamai
    # semantik gabung string saat jalan), lalu pemisah belah yang lazim
    # (spasi, kutip, +) dirapatkan seperti sebelumnya, dan terakhir titik
    # yang diapit dua karakter alfabet token dibuang — token terbelah
    # "." tersambung lagi (issue #772). Titik yang lebih luas TIDAK
    # dibuang: merapatkan semua tanda baca kode terbukti menyambung
    # kata-kata kode biasa (fixture uji "A".repeat(35) dsb.) menjadi
    # ekor pola palsu.
    # Aksara format dibuang lebih dulu (issue #1800),
    # sebelum pemisah lain dirapatkan.
    rapat = POLA_FORMAT.sub("", teks)
    rapat = re.sub(r"\$\{[^}]*\}", "", rapat)
    rapat = re.sub(r"[\s\"'\\+]+", "", rapat)
    rapat = re.sub(r"(?<=[A-Za-z0-9_-])\.(?=[A-Za-z0-9_-])", "", rapat)
    # Koma yang diapit karakter token juga pemisah belah (issue #802):
    # base64/hex yang dipecah elemen arrayOf("...", "...") tersambung
    # lagi. Sempit seperti titik di atas — koma berkawan spasi/kurung
    # (pemisah argumen biasa) tidak ikut terhapus karena sesudah
    # langkah sebelumnya spasi sudah hilang; yang tersisa berkoma
    # rapat memang bentuk pecahan literal.
    rapat = re.sub(r"(?<=[A-Za-z0-9+/=_-]),(?=[A-Za-z0-9+/=_-])", "", rapat)
    # Koma/titik yang menempel TEPAT di titik-dua (issue #1642):
    # titik-dua adalah satu-satunya karakter yang wajib utuh
    # agar pola token cocok, tetapi ia tidak termasuk kelas
    # karakter kedua aturan di atas — jadi belahan
    # listOf("123456789:", "AAE…") atau ("123456789", ":AAE…")
    # tak pernah tersambung lagi dan lolos di semua permukaan.
    # Dua aturan sempit ini hanya melepas pemisah yang salah
    # satu sisinya persis titik-dua token.
    rapat = re.sub(r"(?<=[A-Za-z0-9+/=_-]),(?=:)", "", rapat)
    rapat = re.sub(r"(?<=:),(?=[A-Za-z0-9+/=_-])", "", rapat)
    rapat = re.sub(r"(?<=[A-Za-z0-9_-])\.(?=:)", "", rapat)
    rapat = re.sub(r"(?<=:)\.(?=[A-Za-z0-9_-])", "", rapat)
    # Titik koma yang diapit karakter token juga pemisah
    # belah (issue #1754): pemisah pernyataan baku
    # Kotlin/Java — sempit seperti koma di atas, plus
    # varian menempel titik dua seperti #1642.
    rapat = re.sub(r"(?<=[A-Za-z0-9+/=_-]);(?=[A-Za-z0-9+/=_-])", "", rapat)
    rapat = re.sub(r"(?<=[A-Za-z0-9+/=_-]);(?=:)", "", rapat)
    rapat = re.sub(r"(?<=:);(?=[A-Za-z0-9+/=_-])", "", rapat)
    # Pasangan kurung di antara fragmen (issue #1814):
    # bentuk ("paruh1") + ("paruh2") menyisakan runtunan
    # )( di sambungan sesudah pemisah lain dibuang.
    # HANYA pasangan tutup-buka yang dibuang — kurung
    # tunggal di antara aksara token TIDAK: contoh format
    # angka di CHANGELOG ("456.123:456) yang sebelumnya
    # tampil polos...") membuktikan kurung tunggal yang
    # dibuang menyambung contoh angka + prosa menjadi
    # ekor pola palsu (positif palsu nyata di repo ini).
    rapat = re.sub(r"(?<=[A-Za-z0-9+/=_-])\)+\(+(?=[A-Za-z0-9+/=_-])", "", rapat)
    rapat = re.sub(r"(?<=[A-Za-z0-9+/=_-])\)+\(+(?=:)", "", rapat)
    rapat = re.sub(r"(?<=:)\)+\(+(?=[A-Za-z0-9+/=_-])", "", rapat)
    # Byte kontrol non-spasi (issue #1754): tak pernah
    # bermakna di teks sumber; di permukaan terpadatkan
    # ia hanya bisa menjadi pemisah belah.
    rapat = re.sub(r"[\x00-\x08\x0e-\x1f\x7f]+", "", rapat)
    return rapat


def _varian_terbuka(teks):
    """Varian teks sesudah pengkodean lazim dibuka,
    sampai DUA lintasan (issue #1810): teks yang
    ter-encode dua kali (persen ganda %253A, entitas
    bertingkat &#38;#58;) baru terbuka di lintasan
    kedua. Hanya varian yang BERBEDA dari teks asal
    yang dikembalikan; penilaian atas varian tetap
    menuntut pola token persis, jadi teks jinak yang
    kebetulan memuat kode tak ikut tertuduh."""
    lapis1 = _varian_terbuka_satu(teks)
    hasil = list(lapis1)
    for varian in lapis1:
        for varian2 in _varian_terbuka_satu(varian):
            if varian2 != teks and varian2 not in hasil:
                hasil.append(varian2)
    return hasil


def _varian_terbuka_satu(teks):
    """Satu lintasan pembuka encode persen/entitas/
    escape atas teks."""
    hasil = []
    # Escape string (issue #1801, memperluas #1765):
    # urutan \uXXXX dan \xNN di string Kotlin/Java/
    # JSON/Python dibuka ke aksara aslinya. Escape
    # \UXXXXXXXX Python ditambahkan di issue #1811;
    # escape dua lapis terbuka oleh dua lintasan
    # _varian_terbuka (#1810).
    if "\\u" in teks or "\\x" in teks or "\\U" in teks:
        dibuka = re.sub(r"\\U([0-9A-Fa-f]{8})",
                        lambda m: chr(int(m.group(1), 16)), teks)
        dibuka = re.sub(r"\\u([0-9A-Fa-f]{4})",
                        lambda m: chr(int(m.group(1), 16)), dibuka)
        dibuka = re.sub(r"\\x([0-9A-Fa-f]{2})",
                        lambda m: chr(int(m.group(1), 16)), dibuka)
        if dibuka != teks:
            hasil.append(dibuka)
    # Encode persen URL + entitas HTML (issue #1799):
    # titik-dua token tertulis %3A atau &#58; saat token
    # tertempel di dalam URL/log/berkas HTML.
    if "%" in teks:
        try:
            dibuka = urllib.parse.unquote(teks)
        except Exception:
            dibuka = teks
        if dibuka != teks:
            hasil.append(dibuka)
    if "&" in teks:
        dibuka = html.unescape(teks)
        if dibuka != teks:
            hasil.append(dibuka)
    return hasil


def _utf16_tanpa_bom(data):
    # Tebak UTF-16 tanpa BOM (issue #817): teks yang didominasi ASCII
    # tersimpan UTF-16LE punya NUL di semua posisi ganjil; UTF-16BE
    # sebaliknya. Ambang 90% dua arah agar berkas biner biasa tidak
    # ikut terdekode sebagai UTF-16.
    contoh = data[:512]
    if len(contoh) < 8:
        return None
    genap = contoh[0::2]
    ganjil = contoh[1::2]

    def dominan_nul(bs):
        return bs.count(0) >= max(1, int(len(bs) * 0.9))

    def dominan_ascii(bs):
        cocok = sum(1 for b in bs if b in (9, 10, 13) or 32 <= b < 127)
        return cocok >= int(len(bs) * 0.9)

    if dominan_nul(ganjil) and dominan_ascii(genap):
        return "utf-16-le"
    if dominan_nul(genap) and dominan_ascii(ganjil):
        return "utf-16-be"
    # Skor dekode untuk teks non-Latin (issue #836): UTF-16 Kiril/
    # CJK tak didominasi NUL maupun ASCII (byte tingginya 0x04,
    # 0x4E, ...), jadi dua syarat 90% di atas gugur begitu >10% isi
    # non-ASCII. Nilai hasil dekodenya langsung: teks sungguhan
    # terdekode hampir seluruhnya menjadi karakter tercetak.
    # Hanya dinilai bila contoh tak terbaca sebagai teks UTF-8
    # bersih (3 byte ekor dibuang agar potongan di tengah urutan
    # multi-byte tak mengotori penilaian), supaya berkas UTF-8
    # non-Latin tak salah terdekode. Sisa yang diakui: UTF-16 CJK
    # murni yang seluruh bytenya kebetulan ASCII tercetak.
    # Coba utuh lalu mundur 1-3 byte: potongan 512 byte bisa
    # membelah urutan multi-byte UTF-8 di ekornya. Prefiks parsial
    # TIDAK diterima — byte UTF-16 CJK terbaca sebagai UTF-8 sah
    # pada prefiks panjang, jadi kebersihan harus atas contoh utuh
    # (sesudah mundur ekor itu).
    teks8 = None
    for mundur in (0, 1, 2, 3):
        try:
            teks8 = contoh[: len(contoh) - mundur].decode("utf-8")
            break
        except Exception:
            teks8 = None
    utf8_bersih = teks8 is not None and all(
        c in "\t\n\r" or ord(c) >= 32 for c in teks8)
    if not utf8_bersih:
        # Penjaga teks-mentah (issue #1551): berkas yang
        # sesungguhnya teks biasa (UTF-8) kecuali satu-dua
        # byte kontrol/rusak gugur uji kebersihan di atas,
        # lalu dekode UTF-16 yang salah atas berkas PENDEK
        # menghasilkan CJK tercetak-koheren yang menang
        # skor -> isi jadi sampah dan pola token hancur
        # sebelum pemindaian (fail-open). Bila bacaan
        # mentah UTF-8 (errors=replace) sudah >=95%
        # tercetak dengan sedikit aksara pengganti, teks
        # itu memang teks biasa: jangan tebak UTF-16.
        # UTF-16 asli tanpa BOM terbaca mentah penuh NUL /
        # aksara pengganti sehingga tak kena penjaga ini.
        # Sisa yang diakui: berkas UTF-16 aksara yang
        # seluruh byte-nya kebetulan ASCII tercetak murni
        # (kelas sisa #836).
        mentah = contoh.decode("utf-8", errors="replace")
        if mentah:
            tercetak_m = sum(1 for c in mentah if c == " " or c.isprintable() or c in "\t\n\r")
            if tercetak_m >= int(len(mentah) * 0.95) and mentah.count("\ufffd") <= max(1, int(len(mentah) * 0.02)):
                return None
        genap512 = contoh[:510] if len(contoh) >= 510 else contoh[: len(contoh) // 2 * 2]
        # Nilai KEDUA endian lalu pilih yang terbaik (issue #860):
        # urutan coba lama (LE dulu) salah menebak UTF-16BE aksara
        # Thai/Lao/Khmer — dekode byte-tertukarnya tetap >= 95%
        # tercetak. Dekode yang benar unggul di rasio tercetak dan
        # koherensi aksara (karakter non-ASCII terkumpul di SATU
        # blok aksara; hasil tertukar menyebar antar blok).
        blok_aksara = ((0x00C0, 0x024F), (0x0370, 0x03FF), (0x0400, 0x04FF),
                       (0x0590, 0x05FF), (0x0600, 0x06FF), (0x0900, 0x097F),
                       (0x0E00, 0x0E7F), (0x0E80, 0x0EFF), (0x1780, 0x17FF),
                       (0x1100, 0x11FF), (0x3040, 0x309F), (0x30A0, 0x30FF),
                       (0x4E00, 0x9FFF), (0xAC00, 0xD7AF), (0xFF00, 0xFF60))

        def koherensi(teks):
            hitung = {}
            total = 0
            for c in teks:
                cp = ord(c)
                if cp <= 0x7F:
                    continue
                total += 1
                kunci = -1
                for i, (a, b) in enumerate(blok_aksara):
                    if a <= cp <= b:
                        kunci = i
                        break
                hitung[kunci] = hitung.get(kunci, 0) + 1
            if total == 0:
                return 0.0
            return max(hitung.values()) / total

        terbaik = None
        for enc in ("utf-16-le", "utf-16-be"):
            teks = genap512.decode(enc, errors="replace").rstrip("\ufffd")
            if len(teks) < 8 or "\ufffd" in teks:
                continue
            tercetak = sum(1 for c in teks if c == " " or c.isprintable() or c in "\t\n\r")
            if tercetak < int(len(teks) * 0.95):
                continue
            skor = (tercetak / len(teks), koherensi(teks))
            if terbaik is None or skor > terbaik[0]:
                terbaik = (skor, enc)
        if terbaik is not None:
            return terbaik[1]
    return None


def nama_anggota_arsip(data):
    """Nama anggota arsip TAR (issue #1827): nama anggota
    zip terbaca polos di direktori pusatnya sehingga
    pindaian byte mentah sudah menangkapnya, tetapi
    tar terkompresi (gz/bz2/xz) menyembunyikan nama
    anggotanya dari semua permukaan. Nama dibaca via
    tarfile pustaka standar (hanya header, tanpa
    ekstrak). Arsip 7z belum tercakup: pustaka standar
    tak punya pembacanya. Cerminan persis fungsi ini
    ada di verifikasi-path.py (_nama_anggota_arsip)."""
    if not data or len(data) > 64 * 1024 * 1024:
        return ""
    awal = data[:6]
    terkompresi = (awal.startswith(b"\x1f\x8b")
                   or awal.startswith(b"BZh")
                   or awal.startswith(b"\xfd7zXZ\x00"))
    polos = len(data) > 262 and data[257:262] == b"ustar"
    if not (terkompresi or polos):
        return ""
    try:
        import io
        import tarfile
        nama = []
        with tarfile.open(fileobj=io.BytesIO(data),
                          mode="r:*") as tf:
            for anggota in tf.getmembers():
                nama.append(anggota.name)
                if len(nama) >= 10000:
                    break
        return "\n".join(nama)
    except Exception:
        return ""


def dekode_berkas(data):
    # Berkas UTF-16 (lazim dari editor Windows / ekspor log) membawa
    # BOM; dekode sesuai BOM agar byte NUL antar karakter tidak
    # memutus pola (issue #785). Tanpa BOM: tebak UTF-16 dari pola
    # NUL (issue #817), sisanya UTF-8 seperti sebelumnya.
    if data.startswith(b"\xff\xfe") or data.startswith(b"\xfe\xff"):
        teks = data.decode("utf-16", errors="replace")
    elif data.startswith(b"\xef\xbb\xbf"):
        teks = data.decode("utf-8-sig", errors="replace")
    else:
        enc = _utf16_tanpa_bom(data)
        if enc:
            teks = data.decode(enc, errors="replace")
        else:
            teks = data.decode("utf-8", errors="replace")
    # Nama anggota arsip ikut dinilai (issue #1827).
    nama_arsip = nama_anggota_arsip(data)
    if nama_arsip:
        teks += "\n" + nama_arsip
    return teks


def mengandung_token_pesan(teks):
    """Varian permukaan PESAN commit (issue #1425): TANPA
    pemadatan. Pemadatan menyambung prosa biasa yang memuat
    deret angka + titik dua (id run CI, kutipan galat) menjadi
    ekor pola token palsu — kejadian nyata: pesan perbaikan
    yang mengutip galat kompiler terblokir sebagai 'token
    asli'. Pesan tetap tertangkap untuk token tertempel
    utuh dan bentuk ter-encode (base64/hex); penyelundupan
    terbelah di pesan tak punya preseden dan pesan mudah
    ditulis ulang, jadi permukaan ini diselaraskan lebih
    sempit daripada isi berkas. Kanal base32 ditambahkan
    di issue #1785: #1765 dulu hanya memasangnya di
    permukaan berkas, jadi token ter-base32 di pesan
    lolos dari kedua kanal pesan."""
    if not teks:
        return False
    teks = _samarkan_contoh(teks)
    # Aksara format tak kasatmata dibuang (issue #1800):
    # ini BUKAN pemadatan #1425 — aksara ini tak pernah
    # bermakna di pesan dan hanya bisa menjadi sisipan
    # pemutus pola yang tak terlihat peninjau.
    teks = POLA_FORMAT.sub("", teks)
    if _pola_mentah_cocok(teks):
        return True
    tanpa_spasi = re.sub(r"\s+", "", teks)
    # Varian tanpa spasi juga dinilai pola mentah
    # (issue #1764): satu spasi sesudah titik dua
    # atau di tengah ekor sebelumnya memutus token
    # dari permukaan ini, padahal teks yang sama di
    # permukaan berkas tertangkap lewat pemadatan.
    # Ini BUKAN pemadatan #1425 — hanya spasi yang
    # dibuang; prosa yang rapat oleh tanda baca
    # tetap tidak tersambung.
    if _pola_mentah_cocok(tanpa_spasi):
        return True
    # Bentuk ter-encode web (issue #1799) dan escape
    # string (\xNN #1801): dekode BUKAN pemadatan
    # #1425 — hanya aksara yang memang ter-encode
    # yang dibuka, prosa tetap tak tersambung.
    # Varian terbuka dihitung SEKALI (issue #1853):
    # komposisi silang di bawah dulu memanggilnya lagi
    # untuk teks yang sama.
    varian_semua = _varian_terbuka(teks)
    for varian in varian_semua:
        if _pola_mentah_cocok(varian) or \
                _pola_mentah_cocok(re.sub(r"\s+", "", varian)):
            return True
    # Komposisi silang (issue #1812): varian terbuka
    # juga diteruskan ke dekoder di bawah — token yang
    # di-base64 lalu hasil base64-nya di-persen/escape/
    # entitas-kan lolos bila varian hanya dinilai pola
    # mentah. Sumber dekoder kini teks + tanpa spasi +
    # semua varian terbuka.
    sumber_semua = [teks, tanpa_spasi] + varian_semua
    for sumber in sumber_semua:
        for m in POLA_KANDIDAT_B64.finditer(sumber):
            if _cocok_b64(m.group(0)):
                return True
    for sumber in sumber_semua:
        for m in POLA_KANDIDAT_HEX.finditer(sumber):
            if _cocok_hex(m.group(0)):
                return True
    # Bentuk base32 (issue #1785): kanal yang sama
    # dengan permukaan berkas (#1765 + huruf kecil
    # #1784) — hasil dekode tetap harus cocok pola
    # byte token persis.
    for sumber in sumber_semua:
        for m in re.finditer(r"[A-Za-z2-7]{40,}={0,6}", sumber):
            if _cocok_b32(m.group(0)):
                return True
    return False


def mengandung_fragmen_pesan(teks):
    """Fragmen token di pesan commit (issue #1719:
    paritas dengan penilai fragmen verifikasi-path).

    Permukaan pesan TANPA pemadatan (#1425) membuat
    token yang dipecah beberapa aksara per baris lolos
    dari mengandung_token_pesan — dan langkah pesan
    cek-cepat hanya memakai penilai itu, jadi pola
    yang ditangkap vonis rentang CI lolos diam-diam
    di lokal. Blok baris murni alfabet token tak
    mirip prosa: sambung bloknya lalu nilai dengan
    mengandung_token yang sama.
    """
    if not teks:
        return False

    # Aturan gabungan (issue #1736): SATU sapuan blok
    # baris murni alfabet token (tiap baris <=64
    # aksara); blok >=2 baris yang sambungannya >=42
    # aksara (panjang token minimum) dinilai
    # tersambung. Menggantikan dua pita lama — kecil
    # >=6 baris <=8 aksara (#1698) dan sedang >=3
    # baris 9..40 aksara (#1716) — yang menyisakan
    # pita buta bersama: belahan 2 baris, campuran
    # ukuran 8/9, dan campuran sedang+kecil; semuanya
    # kini membentuk satu blok yang sama. Prosa tak
    # membentuk blok seperti ini: baris prosa memuat
    # spasi atau tanda baca di luar alfabet token
    # sehingga bloknya putus, dan sambungan yang
    # dinilai tetap harus lolos mengandung_token.
    def nilai(blok):
        if len(blok) < 2 or sum(len(b) for b in blok) < 42:
            return False
        if mengandung_token("".join(blok)):
            return True
        # Baris pemisah murni (issue #1750): baris yang
        # isinya hanya '=', '/', atau ':' ikut tergabung
        # ke blok (aksaranya anggota alfabet blok) tetapi
        # tidak dibuang _padatkan dan bukan alfabet ekor
        # token, jadi gabungan polosnya tak pernah cocok
        # — blok dinilai ulang tanpa baris-baris itu.
        # Baris ':' yang ASLI milik token (belahan tepat
        # di titik dua awalan) tetap tertangkap gabungan
        # polos di atas, jadi membuangnya di varian ini
        # tidak membuka lubang.
        inti = [b for b in blok if not re.fullmatch(r"[=\/:]+", b)]
        if inti == blok:
            return False
        gabung = "".join(inti)
        return len(gabung) >= 42 and mengandung_token(gabung)

    # Satu baris penyela (issue #1749): dua blok
    # yang terpisah TEPAT satu baris non-anggota
    # (kosong, atau >64 aksara) juga dinilai
    # tersambung. Sebelumnya penyela mengosongkan
    # blok berjalan, jadi dua paruh token yang
    # masing-masing menjadi blok satu baris tak
    # pernah bertemu — padahal bentuk belahan per
    # baris persis kelas yang dijaga penilai ini.
    blok = []
    blok_lalu = []
    celah = 0

    def tutup(blok_ini):
        if nilai(blok_ini):
            return True
        return (celah == 1 and bool(blok_lalu)
                and nilai(blok_lalu + blok_ini))

    for baris in teks.splitlines():
        b = baris.strip()
        if b and len(b) <= 64 and re.fullmatch(r"[A-Za-z0-9_+\/=.\:-]+", b):
            blok.append(b)
        elif blok:
            if tutup(blok):
                return True
            blok_lalu = blok
            blok = []
            celah = 1
        else:
            celah += 1
    return bool(blok) and tutup(blok)
def _rakit_bytearray(teks):
    """Rakitan byte dari literal byteArrayOf(...) (issue #1842).

    Dua pola larian lama (hex murni, desimal murni) masing-masing
    menuntut >=40 elemen sejenis, jadi literal yang membelah token
    antara ejaan hex dan desimal tak pernah terakit utuh. Pengurai
    ini membaca daftar argumen literalnya langsung — elemen hex
    (0xNN) maupun desimal dibaca sebagai satu deretan berurutan.
    Menghasilkan bytes tiap literal yang elemennya >=40 dan semua
    nilainya muat sebagai byte.
    """
    for m in re.finditer(r"byteArrayOf\s*\(", teks):
        awal = m.end()
        kedalaman = 1
        i = awal
        while i < len(teks) and kedalaman:
            c = teks[i]
            if c == "(":
                kedalaman += 1
            elif c == ")":
                kedalaman -= 1
            i += 1
        if kedalaman:
            continue
        dalam = teks[awal:i - 1]
        # Segmentasi di dalam kurung (issue #1843): komentar
        # blok/baris yang menyela deretan dilewati, dan
        # sufiks .toByte() dinormalkan ke nilai byte-nya
        # (nilai negatif atau >127 dibungkus & 0xFF, sesuai
        # semantik konversi Kotlin).
        dalam = re.sub(r"/\*.*?\*/", " ", dalam, flags=re.S)
        dalam = re.sub(r"//[^\n]*", " ", dalam)
        elemen = re.findall(
            r"(0[xX][0-9A-Fa-f]+|-?[0-9]+)"
            r"(\s*\.\s*toByte\s*\(\s*\))?", dalam)
        if len(elemen) < 40:
            continue
        try:
            nilai = []
            for mentah, sufiks in elemen:
                v = (int(mentah, 16) if mentah[:2].lower() == "0x"
                     else int(mentah))
                if sufiks or v < 0:
                    v &= 0xFF
                nilai.append(v)
            hasil = bytes(nilai)
        except ValueError:
            continue
        yield hasil


def mengandung_token(teks):
    if not teks:
        return False
    teks = _samarkan_contoh(teks)
    if _pola_mentah_cocok(teks):
        return True
    rapat = _padatkan(teks)
    if rapat != teks and _pola_mentah_cocok(rapat):
        return True
    tanpa_spasi = re.sub(r"\s+", "", teks)
    # Komposisi silang (issue #1812): varian terbuka
    # (mentah + terpadatkan) ikut menjadi sumber
    # dekoder, sama seperti di permukaan pesan.
    sumber_semua = [teks, tanpa_spasi, rapat]
    # Varian terbuka + padatannya dihitung SEKALI
    # (issue #1853): putaran bentuk web di bawah dulu
    # menghitung ulang keduanya dari nol untuk teks
    # yang sama — sekitar seperempat waktu vonis
    # berkas besar adalah hitungan ulang identik.
    pasangan_varian = [(v, _padatkan(v)) for v in _varian_terbuka(teks)]
    for varian, padat in pasangan_varian:
        sumber_semua.append(varian)
        sumber_semua.append(padat)
    for sumber in sumber_semua:
        for m in POLA_KANDIDAT_B64.finditer(sumber):
            if _cocok_b64(m.group(0)):
                return True
    # Bentuk hex (issue #784): token ASCII 42+ karakter menjadi 84+
    # karakter hex; ambang 80 memberi ruang. Hasil dekode tetap harus
    # cocok pola token persis, jadi hash panjang biasa tidak tertuduh.
    # Sumber kini termasuk teks terpadatkan (issue #802): hex yang
    # terbelah gabung-string/koma tersambung lagi di sana.
    for sumber in sumber_semua:
        for m in POLA_KANDIDAT_HEX.finditer(sumber):
            if _cocok_hex(m.group(0)):
                return True
    # Bentuk byteArrayOf(0xNN, ...) (issue #802): urutan panjang
    # literal byte hex berkoma adalah bentuk tempelan hex yang lazim
    # di kode Kotlin/Java; kumpulkan pasangan hex-nya dan uji dekode.
    for m in re.finditer(r"(?:0[xX][0-9A-Fa-f]{2}[\s,]*)+", teks):
        pasangan = re.findall(r"0[xX]([0-9A-Fa-f]{2})", m.group(0))
        if len(pasangan) < 40:
            continue
        try:
            hasil = bytes.fromhex("".join(pasangan))
        except Exception:
            continue
        # Byte rakitan dinilai seperti hasil dekode lain
        # (issue #1837): pola/varian terbuka plus satu
        # lapis bawah — sebelumnya hanya pola mentah,
        # jadi ejaan persen/base64/hex dari token lolos
        # sebagai literal byte padahal isi yang sama
        # tertuduh sebagai teks.
        if _pola_atau_terbuka(hasil) or _nilai_lapis_bawah(hasil):
            return True
    # Bentuk byteArrayOf DESIMAL (issue #1840): deretan
    # angka 0-255 berkoma adalah ejaan lain dari kanal
    # yang sama — semua aksara token ASCII < 128, jadi
    # token dapat ditempel utuh sebagai desimal tanpa
    # pernah terakit oleh pola hex di atas. Vonisnya
    # sama seperti rakitan hex.
    for m in re.finditer(
            r"(?:\b(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])\b[\s,]*)+",
            teks):
        angka = re.findall(r"[0-9]+", m.group(0))
        if len(angka) < 40:
            continue
        hasil = bytes(int(a) for a in angka)
        if _pola_atau_terbuka(hasil) or _nilai_lapis_bawah(hasil):
            return True
    # Rakitan campuran lintas ejaan (issue #1842): daftar
    # argumen byteArrayOf dibaca utuh oleh pengurai, jadi
    # belahan hex+desimal dalam satu literal tetap terakit.
    for hasil in _rakit_bytearray(teks):
        if _pola_atau_terbuka(hasil) or _nilai_lapis_bawah(hasil):
            return True
    # Bentuk ter-encode web (issue #1799) dan escape
    # string (\uXXXX #1765, \xNN #1801): varian
    # terbukanya dinilai seperti teks biasa.
    for varian, padat in pasangan_varian:
        if _pola_mentah_cocok(varian) \
                or _pola_mentah_cocok(padat):
            return True
    # Bentuk base32 (issue #1765): padanan kanal
    # base64 untuk alfabet base32 — hasil dekode
    # tetap harus cocok pola byte token persis.
    # Huruf kecil ikut dinilai (issue #1784):
    # encoder lazim mengeluarkan base32 huruf
    # kecil; kandidat dinormalkan ke huruf besar
    # sebelum dekode.
    for sumber in sumber_semua:
        for m in re.finditer(r"[A-Za-z2-7]{40,}={0,6}", sumber):
            if _cocok_b32(m.group(0)):
                return True
    return False


# Cache vonis lintas lari (issue #1854), HANYA untuk
# mode --berkas: langkah 5d cek-cepat menilai ulang
# seluruh pohon dari nol pada setiap lari, padahal
# hampir semua berkas tak berubah antar-lari (6 berkas
# teratas = 89% waktu vonis terukur). Kunci entri =
# hash ISI yang dinilai; kop cache = hash SUMBER berkas
# hakim ini, jadi perubahan logika penilai mana pun
# mengosongkan cache secara otomatis. Cache tinggal di
# direktori cache pengguna (di luar pohon terlacak);
# jalur uji/fuzzer menilai fungsi mentahnya langsung
# dan tak pernah menyentuh cache ini. Matikan dengan
# CEK_TOKEN_TANPA_CACHE=1.
def _path_cache_vonis():
    dasar = os.environ.get("XDG_CACHE_HOME") or os.path.join(
        os.path.expanduser("~"), ".cache")
    return os.path.join(dasar, "redeye-gerbang", "vonis-cek-token.json")


def _muat_cache_vonis():
    if os.environ.get("CEK_TOKEN_TANPA_CACHE"):
        return None
    try:
        with open(__file__, "rb") as f:
            versi = hashlib.sha256(f.read()).hexdigest()[:16]
    except OSError:
        return None
    try:
        with open(_path_cache_vonis(), encoding="utf-8") as f:
            data = json.load(f)
        if data.get("versi") != versi or not isinstance(
                data.get("entri"), dict):
            return {"versi": versi, "entri": {}}
        return data
    except Exception:
        return {"versi": versi, "entri": {}}


def _simpan_cache_vonis(data):
    if data is None:
        return
    try:
        tujuan = _path_cache_vonis()
        os.makedirs(os.path.dirname(tujuan), exist_ok=True)
        sementara = tujuan + ".tmp"
        with open(sementara, "w", encoding="utf-8") as f:
            json.dump(data, f)
        os.replace(sementara, tujuan)
    except Exception:
        pass


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    if mode == "--teks":
        if mengandung_token(sys.stdin.read()):
            print("DITEMUKAN")
        return 0
    if mode == "--teks-pesan":
        data = sys.stdin.read()
        if mengandung_token_pesan(data) or mengandung_fragmen_pesan(data):
            print("DITEMUKAN")
        return 0
    if mode == "--padatkan":
        # Mode bukti gabungan (issue #1644): cetak stdin
        # SESUDAH pemadatan, tanpa penilaian. Dipakai
        # konfirmasi lintas berkas cek-cepat agar bahan
        # pengisi besar (mis. jutaan baris kosong) menyusut
        # sampai nyaris nol dan tak bisa mengusir paruh
        # token dari jendela gabungan; penilaian akhir
        # tetap mengandung_token atas hasil gabungannya.
        mentah = sys.stdin.buffer.read()
        teks = mentah.decode("utf-8", "surrogateescape")
        sys.stdout.buffer.write(
            _padatkan(teks).encode("utf-8", "surrogateescape"))
        return 0
    if mode == "--berkas":
        cache = _muat_cache_vonis()
        kotor = False
        for path in sys.stdin.buffer.read().split(b"\0"):
            if not path:
                continue
            nama = path.decode("utf-8", "replace")
            try:
                with open(nama, "rb") as f:
                    isi = dekode_berkas(f.read())
            except OSError:
                continue
            vonis = None
            kunci = None
            if cache is not None:
                kunci = hashlib.sha256(
                    isi.encode("utf-8", "replace")).hexdigest()
                vonis = cache["entri"].get(kunci)
            if vonis is None:
                vonis = mengandung_token(isi)
                if cache is not None and kunci is not None:
                    if len(cache["entri"]) > 10000:
                        cache["entri"] = {}
                    cache["entri"][kunci] = vonis
                    kotor = True
            if vonis:
                if kotor:
                    _simpan_cache_vonis(cache)
                print(nama)
                return 0
        if kotor:
            _simpan_cache_vonis(cache)
        return 0
    print("pemakaian: cek-token.py --teks | --teks-pesan | --berkas | --padatkan", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
