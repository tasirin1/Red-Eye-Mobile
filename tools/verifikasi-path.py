#!/usr/bin/env python3
"""Verifikasi otomatis path yang baru dikerjakan.

Memastikan penghapusan kode tak merusak acuan dan aturan repo tetap
ditaati, tanpa toolchain Android (hanya baca kode + git diff + parse XML).

Pakai:
    python3 tools/verifikasi-path.py                 # kerja lokal + belum push
    python3 tools/verifikasi-path.py --semua         # seluruh repo
    python3 tools/verifikasi-path.py --rentang A...B # rentang commit (dipakai CI)
    python3 tools/verifikasi-path.py app/... SetupActivity.kt

Keluar 0 bila lolos (peringatan tak menggagalkan), 1 bila ada yang gagal.
"""
import argparse
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from functools import lru_cache

AKAR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(AKAR, "app", "src", "main")
UJI = os.path.join(AKAR, "app", "src", "test")
EKSTENSI_KODE = (".kt", ".java")
KATA_KUNCI = {
    "if", "for", "while", "switch", "catch", "return", "new", "throw",
    "else", "do", "try", "synchronized", "assert", "case",
    "fun", "val", "var", "class", "object", "interface", "when", "is",
    "in", "as", "data", "companion", "override", "suspend", "inline",
    "tailrec", "operator", "infix", "sealed", "enum", "annotation",
    "typealias", "constructor", "init", "where", "by", "get", "set",
    "field", "const", "lateinit", "open", "final", "abstract", "out",
    "import", "package",
}
# Kata alur kendali: tak pernah menjadi tipe atau judul deklarasi. Baris
# hapus yang slot "tipe"-nya hanya kata ini adalah fragmen pernyataan
# (mis. `else startService(intent)`), bukan deklarasi metode yang terhapus.
KATA_ALUR = {
    "if", "else", "while", "for", "when", "catch", "do", "try",
    "return", "throw", "switch", "case", "assert", "synchronized",
}
# Nama metode yang sengaja dihapus total dimuat dari tools/bekas-hapusan.txt
# (satu nama per baris, # = komentar): bila baris baru masih memanggilnya,
# itu regresi hapusan. Tambahkan nama ke berkas itu tiap menghapus metode.
def muat_bekas_hapusan():
    berkas = os.path.join(AKAR, "tools", "bekas-hapusan.txt")
    if not os.path.isfile(berkas):
        print("GAGAL: tools/bekas-hapusan.txt tidak ada - gerbang regresi hapusan mati.")
        sys.exit(1)
    nama = set()
    with open(berkas, encoding="utf-8") as f:
        for baris in f:
            baris = baris.strip()
            if baris and not baris.startswith("#"):
                nama.add(baris.split()[0])
    if not nama:
        print("GAGAL: tools/bekas-hapusan.txt kosong - gerbang regresi hapusan mati.")
        sys.exit(1)
    return nama


BEKAS_HAPUSAN = muat_bekas_hapusan()
# Callback siklus hidup: menghapusnya aman dari sisi acuan (turunkan jadi peringatan).
OVERRIDE_AMAN = {
    "onCreate", "onStart", "onResume", "onPause", "onStop", "onDestroy",
    "onReceive", "onHandleIntent", "onBind", "onUnbind", "onUpgrade",
    "onAlarm", "onBoot", "onClick", "onFocusChange",
}


def jalan(*args):
    try:
        hasil = subprocess.run(
            list(args), cwd=AKAR, capture_output=True, text=True, timeout=60)
    except Exception:
        return None
    return hasil.stdout if hasil.returncode == 0 else None


def pecah_nama(out):
    """Pecah keluaran daftar nama git berformat -z (issue #1138).

    Tanpa -z git mengutip nama non-ASCII gaya C sehingga nama
    terkuotasi tak pernah cocok dengan berkas nyata: berkas
    terlarang lolos aturan repo dan isi berkas tak terpindai.
    Semua daftar nama di gerbang ini wajib -z + pecah NUL.
    """
    return [x for x in (out or "").split("\x00") if x]


def jalan_biner(*args):
    # Varian biner (issue #801): isi blob riwayat harus didekode
    # mengikuti BOM lewat _dekode; jalan() teks memutus pola token
    # UTF-16 oleh byte NUL antar karakter.
    try:
        hasil = subprocess.run(
            list(args), cwd=AKAR, capture_output=True, timeout=120)
    except Exception:
        return None
    return hasil.stdout if hasil.returncode == 0 else None


def daftar_berkas_berubah(rentang):
    """Nama file berubah pada rentang diff (None bila revisi tak valid)."""
    if rentang:
        for pemisah in ("...", ".."):
            if pemisah in rentang:
                a, b = rentang.split(pemisah, 1)
                if set(a) == {"0"} or not a:
                    return None  # push pertama: tak ada pembanding
                out = jalan("git", "diff", "--name-only", "-z", a + pemisah + b)
                if out is not None:
                    return pecah_nama(out)
                return None
    return None


def nama_tak_lacak():
    out = jalan("git", "ls-files", "--others", "--exclude-standard", "-z")
    return pecah_nama(out)


def _utf16_tanpa_bom(data):
    # Cermin _utf16_tanpa_bom di tools/cek-token.py (issue #817).
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


def _dekode(data):
    # Cermin dekode_berkas di tools/cek-token.py (issue #785): berkas
    # UTF-16 membawa BOM dan harus didekode sesuai BOM, kalau tidak
    # byte NUL antar karakter memutus pola token dan isi lain. Tanpa
    # BOM: tebak UTF-16 dari pola NUL (issue #817).
    if data.startswith(b"\xff\xfe") or data.startswith(b"\xfe\xff"):
        return data.decode("utf-16", errors="replace")
    if data.startswith(b"\xef\xbb\xbf"):
        return data.decode("utf-8-sig", errors="replace")
    enc = _utf16_tanpa_bom(data)
    if enc:
        return data.decode(enc, errors="replace")
    return data.decode("utf-8", errors="replace")


@lru_cache(maxsize=None)
def baca(path):
    try:
        with open(os.path.join(AKAR, path), "rb") as f:
            return _dekode(f.read())
    except OSError:
        return None


def baca_teks(path):
    return baca(path) or ""


JALUR_STRINGS_WAJIB = (
    "app/src/main/res/values/strings.xml",
    "app/src/main/res/values-in/strings.xml",
)
_isi_strings_wajib = None


def isi_strings_wajib(gagal):
    global _isi_strings_wajib
    if _isi_strings_wajib is None:
        _isi_strings_wajib = {path: baca(path) for path in JALUR_STRINGS_WAJIB}
        for path, teks in _isi_strings_wajib.items():
            if teks is None:
                gagal.append("Berkas strings wajib tidak ada: " + path)
    return _isi_strings_wajib


def kupas_komentar_xml(isi):
    return re.sub(r"<!--.*?-->", "", isi, flags=re.S)


class _AnggaranHabis(Exception):
    """Anggaran telusur pemindai cadangan habis di tengah badan."""


def hitung_item_array(badan):
    """Jumlah <item> anak langsung sebuah string-array (issue #1110)."""
    try:
        root = ET.fromstring("<root>" + badan + "</root>")
        return len(root.findall("item"))
    except Exception:
        bersih_cdata = re.sub(r"<!\[CDATA\[.*?\]\]>", "", badan, flags=re.S)
        # Cadangan sadar kedalaman (issue #1137): hanya bukaan
        # pada kedalaman 0 (anak langsung) yang dihitung. Tag
        # dipindai manual sadar petik (issue #1149: > sah di
        # dalam nilai atribut berpetik; issue #1154: pola regex
        # sebelumnya backtracking kuadratik pada <item tak
        # tertutup + deret spasi — pemindai ini linear).
        anggaran = [4 * len(bersih_cdata) + 65536]
        def token_tag(teks):
            i = 0
            n = len(teks)
            while i < n:
                j = teks.find("<", i)
                if j < 0:
                    return
                if teks.startswith("</item", j):
                    k = j + 6
                    if k < n and (teks[k] == ">" or teks[k].isspace()):
                        e = teks.find(">", k)
                        if e < 0:
                            # Tag tutup tak berujung: lewati dan
                            # lanjutkan sesudahnya (issue #1161).
                            i = j + 1
                            continue
                        yield ("tutup", False)
                        i = e + 1
                    else:
                        i = j + 1
                    continue
                if teks.startswith("<item", j):
                    k = j + 5
                    if k < n and (teks[k] == ">" or teks[k] == "/" or teks[k].isspace()):
                        # Loncati isi tag sadar petik dengan find
                        # (mesin C): setara telusur per karakter
                        # tetapi tak kuadratik pada tag rusak
                        # beruntun (issue #1161).
                        petik = ""
                        m = k
                        while m < n:
                            if petik:
                                e = teks.find(petik, m)
                                if e < 0:
                                    m = n
                                    break
                                m = e + 1
                                petik = ""
                            else:
                                cand = [x for x in (teks.find(">", m), teks.find('"', m), teks.find("'", m)) if x >= 0]
                                if not cand:
                                    m = n
                                    break
                                e = min(cand)
                                if teks[e] == ">":
                                    m = e
                                    break
                                petik = teks[e]
                                m = e + 1
                        # Anggaran kerja telusur (issue #1171,
                        # menggantikan batas 512 #1161): item sah
                        # ber-atribut panjang tetap terhitung —
                        # telusurnya dibayar sekali — sedangkan
                        # tag rusak beruntun yang memindai ulang
                        # wilayah yang sama menghabiskan anggaran
                        # 4x panjang badan lalu hitungan berhenti
                        # (badan memang sudah gagal parse).
                        anggaran[0] -= m - k
                        if anggaran[0] < 0:
                            # Habis di tengah array (issue #1183):
                            # hitungan parsial yang dikembalikan
                            # senyap bisa kebetulan sama antar
                            # locale dan menutupi selisih paritas
                            # nyata — tandai tak-terhitung.
                            raise _AnggaranHabis()
                        if m >= n:
                            # Tag tak berujung / petik tak
                            # tertutup (issue #1161): pola lama
                            # melewatkan tag rusak ini dan tetap
                            # menghitung item sah berikutnya —
                            # jangan hentikan seluruh hitungan,
                            # lanjutkan dari sesudah '<' ini.
                            i = j + 1
                            continue
                        yield ("buka", teks[m - 1] == "/")
                        i = m + 1
                    else:
                        i = j + 1
                    continue
                i = j + 1
        jumlah = 0
        kedalaman = 0
        try:
            for jenis, selfclosing in token_tag(bersih_cdata):
                if jenis == "tutup":
                    kedalaman = max(0, kedalaman - 1)
                elif selfclosing:
                    if kedalaman == 0:
                        jumlah += 1
                else:
                    if kedalaman == 0:
                        jumlah += 1
                    kedalaman += 1
        except _AnggaranHabis:
            return None
        return jumlah


def jalan_repo(rel, ext=EKSTENSI_KODE):
    for akar, dirs, berkas in os.walk(os.path.join(AKAR, rel)):
        dirs[:] = [d for d in dirs if d not in ("build", ".gradle", "__pycache__")]
        for nama in berkas:
            if nama.endswith(ext):
                yield os.path.relpath(os.path.join(akar, nama), AKAR)


def himpun_definisi():
    """Kembalikan (definisi, acuan): nama sumber daya terdefinisi vs terpakai."""
    definisi = {}
    acuan = {}
    # Titik pada nama style (Theme.A.B) menjadi garis bawah di R (aturan aapt).
    def rapikan(jenis, nama):
        return nama.replace(".", "_") if jenis == "style" else nama

    pola_def = re.compile(r'<(style|string|color|dimen|plurals|bool)(?:\s[^>]*?)?\bname\s*=\s*["\x27]([^"\x27]+)["\x27]')
    pola_array = re.compile(r'<(string-array|integer-array|array)(?:\s[^>]*?)?\bname\s*=\s*["\x27]([^"\x27]+)["\x27]')

    def tambah(kunci, nama):
        definisi.setdefault(kunci, set()).add(rapikan(kunci, nama))

    akar_res = os.path.join(MAIN, "res")
    for path in jalan_repo(os.path.join("app", "src", "main", "res"), ext=(".xml",)):
        isi = kupas_komentar_xml(baca_teks(path))
        for jenis, nama in pola_def.findall(isi):
            tambah(jenis, nama)
        for jenis, nama in pola_array.findall(isi):
            tambah("array", nama)
        for nama in re.findall(r'@\+id/([A-Za-z0-9_]+)', isi):
            tambah("id", nama)
    # Definisi berbasis berkas: semua subdir res kecuali values* (layout,
    # drawable, xml, ...). Kualifikasi (-night/-land) digabung ke tipe dasar.
    for nama_dir in sorted(os.listdir(akar_res)):
        if nama_dir.startswith("values"):
            continue
        tipe = nama_dir.split("-")[0]
        wadah = os.path.join(akar_res, nama_dir)
        if not os.path.isdir(wadah):
            continue
        for akar, _, berkas in os.walk(wadah):
            for nama in berkas:
                tambah(tipe, os.path.splitext(nama)[0])

    # (?<!android\.) mengecualikan kerangka (android.R.*, @android:*) yang
    # bukan milik repo; style membolehkan titik (Theme.A.B).
    pola_pakai = [
        ("style", r"@style/([A-Za-z0-9_.]+)", r"(?<!android\.)R\.style\.([A-Za-z0-9_]+)"),
        ("string", r"@string/([A-Za-z0-9_]+)", r"(?<!android\.)R\.string\.([A-Za-z0-9_]+)"),
        ("color", r"@color/([A-Za-z0-9_]+)", r"(?<!android\.)R\.color\.([A-Za-z0-9_]+)"),
        ("dimen", r"@dimen/([A-Za-z0-9_]+)", r"(?<!android\.)R\.dimen\.([A-Za-z0-9_]+)"),
        ("array", r"@array/([A-Za-z0-9_]+)", r"(?<!android\.)R\.array\.([A-Za-z0-9_]+)"),
        ("drawable", r"@drawable/([A-Za-z0-9_]+)", r"(?<!android\.)R\.drawable\.([A-Za-z0-9_]+)"),
        ("layout", None, r"(?<!android\.)R\.layout\.([A-Za-z0-9_]+)"),
        ("id", r"@id/([A-Za-z0-9_]+)", r"(?<!android\.)R\.id\.([A-Za-z0-9_]+)"),
        ("plurals", r"@plurals/([A-Za-z0-9_]+)", r"(?<!android\.)R\.plurals\.([A-Za-z0-9_]+)"),
    ]
    pola_generik_xml = re.compile(r"@([a-z]+)/([A-Za-z0-9_.]+)")
    pola_generik_r = re.compile(r"(?<!android\.)R\.([a-z]+)\.([A-Za-z0-9_]+)")
    pola_android = re.compile(r"@android:[a-z]+/[A-Za-z0-9_.]+")
    pola_pustaka = re.compile(
        r"@style/(Widget[._]|ThemeOverlay[._]|TextAppearance[._]|Animation[._]"
        r"|Theme\.Material|Theme_Material|Theme\.AppCompat|Theme_AppCompat"
        r"|Theme\.Design|Theme_Design)[A-Za-z0-9_.]*")
    pemindai = [p for p in jalan_repo("app", ext=EKSTENSI_KODE + (".xml",))]
    pemindai.append("app/src/main/AndroidManifest.xml")
    for path in pemindai:
        isi = baca_teks(path)
        if not isi:
            continue
        # Sisi acuan mengupas komentar seperti sisi definisi
        # (issue #1218): acuan yang hanya hidup di komentar
        # XML/Kotlin/Java bukan acuan nyata dan tak boleh
        # menggagalkan gerbang sebagai yatim.
        if path.endswith(".xml"):
            isi = kupas_komentar_xml(isi)
        elif path.endswith(EKSTENSI_KODE):
            isi = _kupas_komentar_kode(isi, bersarang=not path.endswith(".java"))
        isi = pola_android.sub("", isi)
        isi = pola_pustaka.sub("", isi)
        for jenis, pola_xml, pola_r in pola_pakai:
            for pola in (pola_xml, pola_r):
                if pola:
                    for nama in re.findall(pola, isi):
                        acuan.setdefault(jenis, set()).add(rapikan(jenis, nama))
        for tipe, nama in pola_generik_xml.findall(isi):
            acuan.setdefault(tipe, set()).add(rapikan(tipe, nama))
        for tipe, nama in pola_generik_r.findall(isi):
            acuan.setdefault(tipe, set()).add(rapikan(tipe, nama))
    return definisi, acuan


def cek_xml(gagal):
    buruk = []
    for path in jalan_repo(os.path.join("app", "src", "main", "res"), ext=(".xml",)):
        try:
            ET.parse(os.path.join(AKAR, path))
        except ET.ParseError as e:
            buruk.append("%s (%s)" % (path, e))
        except OSError as e:
            # Symlink putus/berkas lenyap di tengah jalan: laporkan bernama,
            # jangan mati traceback menutupi cek lain (issue #682).
            buruk.append("%s (tak terbaca: %s)" % (path, e.__class__.__name__))
    for path in ("app/src/main/AndroidManifest.xml",):
        try:
            ET.parse(os.path.join(AKAR, path))
        except ET.ParseError as e:
            buruk.append("%s (%s)" % (path, e))
        except OSError:
            buruk.append("%s (tidak ditemukan)" % path)
    if buruk:
        gagal.append("XML rusak: " + ", ".join(buruk))


def cek_acuan_yatim(gagal):
    definisi, acuan = himpun_definisi()
    for jenis, dipakai in sorted(acuan.items()):
        ada = definisi.get(jenis, set())
        yatim = sorted(n for n in dipakai if n not in ada)
        if yatim:
            gagal.append("Acuan %s yatim (dipakai tapi tak terdefinisi): %s"
                         % (jenis, ", ".join(yatim[:10])))


def _kupas_komentar_kode(teks, bersarang=True):
    # Buang isi komentar // dan /* */ dari berkas kode
    # (issue #1218): komentar blok bersarang untuk Kotlin,
    # tidak untuk Java — petunjuk bahasa yang sama dengan
    # pelacak deklarasi (#1198). Sadar string/char sederhana
    # (komentar di dalam string tidak dibuang); isi komentar
    # diganti spasi dengan newline dipertahankan. String
    # mentah Kotlin (tiga petik) dikenali utuh (issue #1234,
    # meniru pemindai saudara di berkas ini): petik ganjil di
    # dalamnya tak lagi membalik pemindai ke mode komentar.
    out = []
    i, n = 0, len(teks)
    while i < n:
        c = teks[i]
        if teks.startswith('"""', i):
            j = teks.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(teks[i:j])
            i = j
            continue
        if c == '"' or c == "'":
            j = i + 1
            while j < n:
                if teks[j] == "\\":
                    j += 2
                    continue
                if teks[j] == c:
                    j += 1
                    break
                if teks[j] == "\n" and c == "'":
                    break
                j += 1
            out.append(teks[i:j])
            i = j
            continue
        if teks.startswith("//", i):
            j = teks.find("\n", i)
            if j < 0:
                j = n
            out.append(" " * (j - i))
            i = j
            continue
        if teks.startswith("/*", i):
            if not bersarang:
                k = teks.find("*/", i + 2)
                j = n if k < 0 else k + 2
            else:
                depth = 1
                j = i + 2
                while j < n and depth > 0:
                    if teks.startswith("/*", j):
                        depth += 1
                        j += 2
                    elif teks.startswith("*/", j):
                        depth -= 1
                        j += 2
                    else:
                        j += 1
            out.append("".join("\n" if ch == "\n" else " " for ch in teks[i:j]))
            i = j
            continue
        out.append(c)
        i += 1
    return "".join(out)


def namespace_aplikasi():
    tetap = baca_teks("app/build.gradle")
    cocok = re.search(r'namespace\s*=\s*"([^"]+)"', tetap)
    if cocok:
        return cocok.group(1)
    cocok = re.search(r"namespace\s*'([^']+)'", tetap)
    return cocok.group(1) if cocok else ""


def cek_manifest(gagal):
    """Komponen di manifest wajib ada kelasnya (tangkap hapus berkas kode)."""
    try:
        pohon = ET.parse(os.path.join(AKAR, "app/src/main/AndroidManifest.xml"))
    except ET.ParseError:
        return  # sudah dilaporkan cek_xml
    except OSError:
        gagal.append("AndroidManifest.xml tidak ditemukan / tidak bisa dibaca")
        return
    ns = namespace_aplikasi()
    for el in pohon.getroot().iter():
        nama = el.get("{http://schemas.android.com/apk/res/android}name", "")
        if not nama or el.tag not in (
                "activity", "service", "receiver", "provider"):
            continue
        rel = None
        if nama.startswith("."):
            rel = nama[1:].split(".")
        elif ns and (nama == ns or nama.startswith(ns + ".")):
            rel = nama[len(ns) + 1:].split(".") if nama != ns else []
        else:
            continue  # kerangka / pustaka
        if not rel:
            continue
        dasar = os.path.join(MAIN, "java", *ns.split("."), *rel)
        if not (os.path.isfile(dasar + ".kt")
                or os.path.isfile(dasar + ".java")):
            gagal.append("Komponen manifest tanpa kelas: %s"
                         % nama)
    # Broadcast yang TIDAK membawa URI/MIME wajib berada di filter tanpa
    # <data>; kalau dicampur, uji data gagal dan broadcast-nya tak pernah
    # sampai (nyata: MY_PACKAGE_REPLACED + <data scheme="package">).
    aksi_tanpa_data = {
        "android.intent.action.MY_PACKAGE_REPLACED",
        "android.intent.action.BOOT_COMPLETED",
        "android.intent.action.USER_PRESENT",
        "android.intent.action.USER_UNLOCKED",
        "android.intent.action.QUICKBOOT_POWERON",
        "com.htc.intent.action.QUICKBOOT_POWERON",
    }
    for saring in pohon.getroot().iter("intent-filter"):
        if saring.find("data") is None:
            continue
        for aksi in saring.findall("action"):
            nama_aksi = aksi.get(
                "{http://schemas.android.com/apk/res/android}name", "")
            if nama_aksi in aksi_tanpa_data:
                gagal.append(
                    "Filter %s tak boleh punya <data> (intent itu tanpa "
                    "URI/MIME, broadcast-nya jadi tak pernah sampai)"
                    % nama_aksi)


def cek_duplikat_definisi(gagal):
    """Nama ganda dalam SATU konfigurasi values = galat aapt2.
    Konfigurasi = folder values yang sama; duplikat LINTAS BERKAS
    di folder itu sama fatalnya dengan dalam satu berkas (issue
    #904: penghitung dulu direset per berkas). Override antar
    folder beda (values vs values-night/values-in) tetap sah."""
    pola = re.compile(r'<(style|string|color|dimen|plurals|bool|string-array|integer-array|array)(?:\s[^>]*?)?\bname\s*=\s*["\x27]([^"\x27]+)["\x27]')
    per_folder = {}
    for path in jalan_repo(os.path.join("app", "src", "main", "res"), ext=(".xml",)):
        if "/values" not in path.replace(os.sep, "/"):
            continue
        hitung = per_folder.setdefault(os.path.dirname(path), {})
        for jenis, nama in pola.findall(kupas_komentar_xml(baca_teks(path))):
            kunci = jenis + "/" + nama
            hitung[kunci] = hitung.get(kunci, 0) + 1
    for folder in sorted(per_folder):
        ganda = sorted(k for k, n in per_folder[folder].items() if n > 1)
        if ganda:
            gagal.append("Definisi ganda di %s: %s" % (folder, ", ".join(ganda[:5])))


def _versi_dari_teks(teks):
    """(versionCode, (mayor, minor, patch)) dari isi build.gradle."""
    kode = re.search(r"versionCode\s+(\d+)", teks or "")
    nama = re.search(r'versionName\s+"(\d+)\.(\d+)\.(\d+)"', teks or "")
    if not kode or not nama:
        return None
    return int(kode.group(1)), tuple(int(x) for x in nama.groups())


def cek_versi(gagal, peringatan):
    """Aturan versi AGENTS.md §8 ditegakkan (issue #906): patch
    tidak mencapai 1000, dan versionCode tidak boleh mundur/
    stagnan terhadap rilis bertag terakhir. Bila tag tak
    tersedia (clone tanpa tag), perbandingan DILEWATI tetapi
    kini selalu dengan PERINGATAN (issue #976) — dulu diam-diam
    sehingga separuh penegakan tidur tanpa terasa."""
    kini = _versi_dari_teks(baca_teks(os.path.join("app", "build.gradle")))
    if kini is None:
        gagal.append("versionCode/versionName tak terbaca di app/build.gradle")
        return
    kode_kini, nama_kini = kini
    if nama_kini[2] > 999:
        gagal.append("versionName patch %d mencapai 1000 — naikkan minor (aturan §8)" % nama_kini[2])
    daftar = jalan("git", "tag", "--list", "v*")
    if not daftar:
        peringatan.append("cek_versi: tak ada tag v* — perbandingan versi vs rilis "
                          "bertag terakhir DILEWATI (aturan patch<1000 tetap diperiksa)")
        return
    tag_versi = []
    for baris in daftar.splitlines():
        cocok = re.fullmatch(r"v(\d+)\.(\d+)\.(\d+)", baris.strip())
        if cocok:
            tag_versi.append((tuple(int(x) for x in cocok.groups()), baris.strip()))
    if not tag_versi:
        peringatan.append("cek_versi: tag v* ada tetapi tak satu pun berbentuk vX.Y.Z — "
                          "perbandingan versi vs rilis bertag terakhir DILEWATI")
        return
    nama_tag, label_tag = max(tag_versi)
    isi_tag = jalan("git", "show", label_tag + ":app/build.gradle")
    dulu = _versi_dari_teks(isi_tag)
    if dulu is None:
        peringatan.append("cek_versi: app/build.gradle di tag %s tak terbaca — "
                          "perbandingan versionCode DILEWATI" % label_tag)
        return
    kode_tag = dulu[0]
    if nama_kini < nama_tag:
        gagal.append("versionName %s mundur dari rilis bertag %s" % (".".join(map(str, nama_kini)), label_tag))
    elif nama_kini == nama_tag and kode_kini < kode_tag:
        gagal.append("versionCode %d di bawah versionCode rilis %s (%d)" % (kode_kini, label_tag, kode_tag))
    elif nama_kini > nama_tag and kode_kini <= kode_tag:
        gagal.append("versionName baru %s tetapi versionCode %d tidak naik dari rilis %s (%d)"
                     % (".".join(map(str, nama_kini)), kode_kini, label_tag, kode_tag))


def cek_builder_legacy(gagal):
    """Aturan AGENTS.md §3 ditegakkan (issue #909): builder.sh/
    builder.bat LEGACY wajib tetap berhenti sendiri (exit)
    sebelum menulis apa pun — keduanya tak boleh diaktifkan
    ulang menjadi alur build yang menulis token ke
    app/build.gradle."""
    for nama, penanda_exit in (("builder.sh", "exit 1"), ("builder.bat", "exit /b 1")):
        isi = baca_teks(nama)
        if not isi:
            gagal.append("%s hilang — berkas legacy wajib dipertahankan sebagai rintisan berhenti" % nama)
            continue
        if penanda_exit not in isi:
            gagal.append("%s tak lagi berhenti sendiri (%s hilang) — aturan §3: jangan aktifkan ulang" % (nama, penanda_exit))
        for terlarang in ("gradlew", "assembleRelease", "BOT_TOKEN="):
            if terlarang in isi:
                gagal.append("%s memuat pola alur build aktif (%s) — aturan §3: legacy harus tetap mati" % (nama, terlarang))


def _ambil_terhapus(out):
    # Token -z --name-status berurutan posisi: status, path
    # (dua path untuk R/C). Ambil path milik status D saja.
    token = pecah_nama(out)
    hasil = []
    i = 0
    while i < len(token):
        status = token[i]
        i += 1
        if not status or status[0] not in "AMDRCTU":
            continue
        ambil = 2 if status[0] in "RC" else 1
        for _ in range(ambil):
            if i >= len(token):
                break
            if status[0] == "D":
                hasil.append(token[i])
            i += 1
    return hasil


def _deklarasi_tingkat_atas(teks, kelas, bersarang=True):
    # Deklarasi class/object/interface/enum/record bernama `kelas`
    # pada kedalaman kurung 0 (tingkat atas berkas), sadar
    # komentar/string secara sederhana (kurung di dalam
    # komentar/string tak dihitung). Kelas BERSARANG tak
    # dihitung: dari berkas lain ia tak terlihat tanpa
    # kualifikasi (issue #1172).
    pola = re.compile(r"(?:class|object|interface|enum)\s+%s\b" % re.escape(kelas))
    # Record adalah konstruksi JAVA — berkas Kotlin tak
    # pernah mendeklarasikannya ("record" di .kt hanyalah
    # identifier, mis. fungsi infix). Karena itu pola ini
    # hanya dikonsultasikan untuk berkas .java (issue
    # #1214), dan record generik "record Nama<T>(...)"
    # ikut dikenali (issue #1215). Di berkas Java pola
    # infix tak mungkin muncul.
    # Potongan generik kurung sudut SEIMBANG (issue #1238,
    # menyempurnakan regex satu tingkat #1222): generik
    # bertingkat dua "Map<String, List<X>>" ikut dikenali.
    # berhenti di > pertama bila memakai [^>]* biasa.
    def cocok_record(pos):
        # "record <kelas>" + generik seimbang opsional + "(".
        if not teks.startswith("record", pos):
            return False
        j = pos + 6
        if j >= n or not teks[j].isspace():
            return False
        while j < n and teks[j].isspace():
            j += 1
        if not teks.startswith(kelas, j):
            return False
        j += len(kelas)
        if j < n and (teks[j].isalnum() or teks[j] == "_"):
            return False
        while j < n and teks[j].isspace():
            j += 1
        if j < n and teks[j] == "<":
            depth = 0
            while j < n:
                if teks[j] == "<":
                    depth += 1
                elif teks[j] == ">":
                    depth -= 1
                    if depth == 0:
                        j += 1
                        break
                j += 1
            else:
                return False
            while j < n and teks[j].isspace():
                j += 1
        return j < n and teks[j] == "("
    i = 0
    n = len(teks)
    dalam = 0
    while i < n:
        c = teks[i]
        if c == "/" and i + 1 < n and teks[i + 1] == "/":
            j = teks.find("\n", i)
            i = n if j < 0 else j + 1
            continue
        if c == "/" and i + 1 < n and teks[i + 1] == "*":
            # Komentar blok Kotlin bisa BERSARANG (issue
            # #1184): hitung kedalaman buka/tutup, jangan
            # berhenti di penutup pertama — sisa komentar luar
            # yang memuat teks deklarasi atau kurung pernah
            # terhitung sebagai kode sungguhan dua arah.
            if not bersarang:
                # Java: komentar blok TIDAK bersarang (issue
                # #1198) — penutup pertama mengakhiri, walau
                # isinya memuat "/*" literal (mis. glob).
                j = teks.find("*/", i + 2)
                i = n if j < 0 else j + 2
                continue
            kedalaman_komentar = 1
            j = i + 2
            while j < n and kedalaman_komentar > 0:
                if teks.startswith("/*", j):
                    kedalaman_komentar += 1
                    j += 2
                elif teks.startswith("*/", j):
                    kedalaman_komentar -= 1
                    j += 2
                else:
                    j += 1
            i = j
            continue
        if teks.startswith('"""', i):
            j = teks.find('"""', i + 3)
            i = n if j < 0 else j + 3
            continue
        if c == '"' or c == "'":
            j = i + 1
            while j < n:
                if teks[j] == "\\":
                    j += 2
                    continue
                if teks[j] == c or teks[j] == "\n":
                    break
                j += 1
            i = j + 1
            continue
        if c == "{":
            dalam += 1
            i += 1
            continue
        if c == "}":
            dalam = max(0, dalam - 1)
            i += 1
            continue
        if dalam == 0 and (c == "c" or c == "o" or c == "i" or c == "e" or c == "r"):
            m = pola.match(teks, i)
            if m:
                return True
            if c == "r" and not bersarang and cocok_record(i):
                return True
        i += 1
    return False


def _ambil_rename_berisiko(out, ref_lama=None):
    # Rename (R) yang mengganti nama berkas kode (issue #1164):
    # kelas bernama sama dengan nama lama hilang bersama
    # berkasnya — acuan yang tertinggal sama berbahayanya
    # dengan hapusan murni, tetapi status D tak pernah
    # mengumpulkannya. Path lama diperlakukan terhapus HANYA
    # bila berkas baru tak lagi mendeklarasikan nama lama
    # (rename murni yang kelasnya ikut berganti nama dengan
    # benar lolos; vonis akhir tetap di penyebutan nama).
    token = pecah_nama(out)
    hasil = []
    i = 0
    while i < len(token):
        status = token[i]
        i += 1
        if not status or status[0] not in "AMDRCTU":
            continue
        ambil = 2 if status[0] in "RC" else 1
        pasangan = []
        for _ in range(ambil):
            if i >= len(token):
                break
            pasangan.append(token[i])
            i += 1
        if status[0] != "R" or len(pasangan) != 2:
            continue
        lama, baru = pasangan
        if not (lama.startswith("app/src/main/java/")
                and lama.endswith(EKSTENSI_KODE)):
            continue
        kelas = os.path.splitext(os.path.basename(lama))[0]
        if os.path.splitext(os.path.basename(baru))[0] == kelas:
            continue
        # Hanya berkas yang memang mendeklarasikan tipe
        # bernama sama dengan nama berkasnya yang bisa
        # meninggalkan acuan menggantung (issue #1173):
        # berkas bernama bebas (Util.kt berisi class
        # Pembantu) tak punya "kelas nama berkas" untuk
        # dirujuk, dan memasukkannya membuat vonis bergantung
        # pada kata sama di komentar mana pun. Isi lama
        # dibaca dari ref sumber diff; tak terbaca = sikap
        # lama (tetap dimasukkan, arah aman).
        if ref_lama is not None:
            isi_lama = jalan("git", "show", "%s:%s" % (ref_lama, lama))
            if isi_lama is not None and not _deklarasi_tingkat_atas(isi_lama, kelas, bersarang=not lama.endswith(".java")):
                continue
        if not _deklarasi_tingkat_atas(baca_teks(baru), kelas, bersarang=not baru.endswith(".java")):
            hasil.append(lama)
    return hasil


def berkas_dihapus(rentang):
    """Daftar path yang dihapus pada diff (butuh status; kosong bila tak tahu)."""
    if rentang and ("..." in rentang or ".." in rentang):
        for pemisah in ("...", ".."):
            if pemisah in rentang:
                a, b = rentang.split(pemisah, 1)
                if set(a) == {"0"} or not a:
                    return []
                out = jalan("git", "diff", "--name-status", "-z", a + pemisah + b)
                if out is None:
                    return []
                return _ambil_terhapus(out) + _ambil_rename_berisiko(out, a)
    out = jalan("git", "diff", "--name-status", "-z")
    return _ambil_terhapus(out) + _ambil_rename_berisiko(out, "HEAD")


def disebut_kata(nama, lewat=()):
    pola = re.compile(r"\b%s\b" % re.escape(nama))
    temu = []
    for path in jalan_repo("app", ext=EKSTENSI_KODE + (".xml",)):
        if path in lewat:
            continue
        teks = baca_teks(path)
        if path.endswith(EKSTENSI_KODE):
            # Komentar dikupas dulu (issue #1308, keluarga
            # #1254/#1218): sebutan nama kelas di komentar
            # bukan rujukan — tanpa kupasan, menghapus berkas
            # kelas ter-GAGAL-kan oleh baris komentar berkas
            # lain. _kupas_komentar_kode mempertahankan
            # newline sehingga nomor baris tetap tepat.
            # Berkas XML tetap dibaca mentah (komentar XML
            # bukan komentar kode).
            teks = _kupas_komentar_kode(teks, bersarang=not path.endswith(".java"))
        for i, baris in enumerate(teks.splitlines(), 1):
            if pola.search(baris):
                temu.append("%s:%d" % (path, i))
                if len(temu) >= 6:
                    return temu
    return temu


def cek_berkas_kode_dihapus(gagal, hapus):
    for path in hapus:
        if not (path.startswith("app/src/main/java/")
                and path.endswith(EKSTENSI_KODE)):
            continue
        kelas = os.path.splitext(os.path.basename(path))[0]
        siapa = disebut_kata(kelas, lewat=[path])
        if siapa:
            gagal.append("Berkas %s dihapus tapi %s masih disebut: %s"
                         % (path, kelas, ", ".join(siapa)))


def _buka_kutip_git(token):
    """Buka kutipan gaya-C git pada satu token path: git
    menulis nama non-ASCII (quotepath bawaan) sebagai
    "b/app/\303\211.kt" — oktet UTF-8 ter-escape oktal.
    Token tanpa kutip dikembalikan apa adanya."""
    if len(token) >= 2 and token.startswith('"') and token.endswith('"'):
        mentah = token[1:-1]
        keluar = bytearray()
        i = 0
        while i < len(mentah):
            c = mentah[i]
            if c == "\\" and i + 1 < len(mentah):
                berikut = mentah[i + 1]
                if berikut in "01234567":
                    j = i + 1
                    okt = ""
                    while j < len(mentah) and len(okt) < 3 and mentah[j] in "01234567":
                        okt += mentah[j]
                        j += 1
                    keluar.append(int(okt, 8))
                    i = j
                    continue
                peta = {"n": 10, "t": 9, "r": 13, '"': 34, "\\": 92}
                keluar.append(peta.get(berikut, ord(berikut) if len(berikut) == 1 else 63))
                i += 2
                continue
            keluar.extend(c.encode("utf-8"))
            i += 1
        return keluar.decode("utf-8", "replace")
    return token


def _path_sisi_b(baris):
    """Path sisi-b dari kepala 'diff --git a/... b/...',
    sadar kutipan-C (issue #1309): pengurai lama (split +
    [2:]) menghasilkan path rusak untuk nama terkuotasi
    sehingga semua cek berbasis baris-diff (locale,
    bekas-hapusan, metode hilang, cakupan uji) melewatkan
    berkas itu seluruhnya."""
    sisa = baris[len("diff --git "):]
    if sisa.startswith('"'):
        j = 1
        while j < len(sisa):
            if sisa[j] == "\\":
                j += 2
                continue
            if sisa[j] == '"':
                break
            j += 1
        token_b = sisa[j + 1:].strip()
    else:
        token_b = sisa.split(" ", 1)[1] if " " in sisa else ""
    token_b = _buka_kutip_git(token_b)
    return token_b[2:] if token_b.startswith("b/") else token_b


def tambah_kode(diff):
    """Hasilkan (path, isi) tiap baris tambah dari hunk berkas kode."""
    path = ""
    for baris in diff.splitlines():
        if baris.startswith("diff --git "):
            path = _path_sisi_b(baris)
        elif baris.startswith("+") and not baris.startswith("+++"):
            if path.endswith(EKSTENSI_KODE):
                yield path, baris[1:]


def hapus_kode(diff):
    """Hasilkan (path, isi) tiap baris hapus dari hunk berkas kode."""
    path = ""
    for baris in diff.splitlines():
        if baris.startswith("diff --git "):
            path = _path_sisi_b(baris)
        elif baris.startswith("-") and not baris.startswith("---"):
            if path.endswith(EKSTENSI_KODE):
                yield path, baris[1:]


def _lewati_interpolasi(isi, i):
    """Indeks sesudah '}' penutup interpolasi ${...} yang mulai
    di i (posisi '$'). Literal string/char bersarang di dalam
    interpolasi dilompati utuh (issue #1289): '}' di dalam
    "${"}"}" adalah ISI string bersarang, bukan penutup
    interpolasi — penghitung kurung buta menutup interpolasi
    di situ dan menyisakan kurung hantu ke penghitung kode."""
    n = len(isi)
    j = i + 2
    kedalaman = 1
    while j < n and kedalaman > 0:
        c = isi[j]
        tiga = isi[j:j + 3]
        if tiga == chr(34) * 3:
            akhir = isi.find(tiga, j + 3)
            j = n if akhir < 0 else akhir + 3
            continue
        if c == chr(34) or c == chr(39):
            kutip = c
            j += 1
            while j < n:
                if isi[j] == chr(92):
                    j += 2
                    continue
                if isi[j] == kutip:
                    j += 1
                    break
                if kutip == chr(34) and isi[j] == chr(10):
                    break
                j += 1
            continue
        if isi[j:j + 2] == "//":
            while j < n and isi[j] != chr(10):
                j += 1
            continue
        if isi[j:j + 2] == "/*":
            dalam = 1
            j += 2
            while j < n and dalam > 0:
                if isi[j:j + 2] == "/*":
                    dalam += 1
                    j += 2
                elif isi[j:j + 2] == "*/":
                    dalam -= 1
                    j += 2
                else:
                    j += 1
            continue
        if c == "{":
            kedalaman += 1
        elif c == "}":
            kedalaman -= 1
            if kedalaman == 0:
                return j + 1
        j += 1
    return j


def _salin_interpolasi(isi, i, keluar):
    """Salin segmen ${...} mulai dari posisi $ ke keluaran sampai
    kurung kurawal seimbang; mengembalikan indeks sesudah '}'.
    Ujung segmen dicari _lewati_interpolasi agar literal
    bersarang tak menutup segmen di tengah (issue #1289)."""
    akhir = _lewati_interpolasi(isi, i)
    keluar.append(isi[i:akhir])
    return akhir


def kupas_kode(isi, simpan_interpolasi=False, bersarang=True):
    """Buang komentar + literal sekali-jalan (termasuk string mentah Kotlin).

    Dengan simpan_interpolasi=True, isi ${...}/$nama di dalam string
    DIPERTAHANKAN sebagai kode: impor yang hanya dipakai di template
    string itu HIDUP (issue #905) — tanpa ini cek_import_mati
    memvonisnya mati. Pemakai lain (cek kurung dsb.) tetap memakai
    perilaku lama. bersarang mengikuti petunjuk bahasa pemanggil
    (issue #1236): komentar blok Kotlin bersarang dilacak
    kedalamannya; Java berhenti di penutup pertama."""
    petik_ganda = chr(34)
    petik_satu = chr(39)
    garis_miring_balik = chr(92)
    keluar = []
    i, n = 0, len(isi)
    while i < n:
        c = isi[i]
        dua = isi[i:i + 2]
        tiga = isi[i:i + 3]
        if tiga == petik_ganda * 3:
            akhir = isi.find(tiga, i + 3)
            if simpan_interpolasi:
                batas = n if akhir < 0 else akhir
                j = i + 3
                while j < batas:
                    if isi[j] == "$" and isi[j + 1:j + 2] == "{":
                        j = _salin_interpolasi(isi, j, keluar)
                    elif isi[j] == "$" and (isi[j + 1:j + 2].isalnum() or isi[j + 1:j + 2] == "_"):
                        k = j + 1
                        while k < batas and (isi[k].isalnum() or isi[k] == "_"):
                            k += 1
                        keluar.append(isi[j:k])
                        j = k
                    else:
                        j += 1
            i = n if akhir < 0 else akhir + 3
        elif dua == '//':
            while i < n and isi[i] != chr(10):
                i += 1
        elif dua == '/*':
            i += 2
            if bersarang:
                # Komentar blok Kotlin BERSARANG (issue #1236):
                # penutup pertama hanya menutup sarang terdalam —
                # berhenti di situ menyisakan ekor komentar yang
                # ikut terhitung sebagai kode (kurung palsu dsb.).
                kedalaman = 1
                while i < n and kedalaman > 0:
                    if isi[i:i + 2] == '/*':
                        kedalaman += 1
                        i += 2
                    elif isi[i:i + 2] == '*/':
                        kedalaman -= 1
                        i += 2
                    else:
                        i += 1
            else:
                while i < n and isi[i:i + 2] != '*/':
                    i += 1
                i += 2
        elif c == petik_ganda or c == petik_satu:
            kutip = c
            i += 1
            while i < n:
                if isi[i] == garis_miring_balik:
                    i += 2
                    continue
                if isi[i] == kutip:
                    i += 1
                    break
                if kutip == petik_ganda and isi[i] == chr(10):
                    break
                if not simpan_interpolasi and kutip == petik_ganda and isi[i] == "$" and isi[i + 1:i + 2] == "{":
                    # Interpolasi dilompati UTUH (issue #1289):
                    # tanpa ini, petik pembuka string bersarang
                    # di dalam ${...} dianggap penutup string
                    # luar dan sisa interpolasi bocor sebagai
                    # kode (kurung hantu menutupi kurung hilang).
                    i = _lewati_interpolasi(isi, i)
                    continue
                if simpan_interpolasi and kutip == petik_ganda and isi[i] == "$":
                    if isi[i + 1:i + 2] == "{":
                        i = _salin_interpolasi(isi, i, keluar)
                        continue
                    j = i + 1
                    while j < n and (isi[j].isalnum() or isi[j] == "_"):
                        j += 1
                    if j > i + 1:
                        keluar.append(isi[i:j])
                        i = j
                        continue
                i += 1
        else:
            keluar.append(c)
            i += 1
    return "".join(keluar)


def cek_kurung_kode(gagal, berubah):
    for path in berubah:
        if not path.endswith(EKSTENSI_KODE):
            continue
        penuh = os.path.join(AKAR, path)
        if not os.path.isfile(penuh):
            continue  # berkas dihapus: ditangani cek lain
        isi = kupas_kode(baca_teks(path), bersarang=not path.endswith(".java"))
        for buka, tutup, nama in (("{", "}", "kurawal"), ("(", ")", "lengkung"), ("[", "]", "siku")):
            selisih = isi.count(buka) - isi.count(tutup)
            if selisih:
                gagal.append("Kurung %s tak seimbang di %s (%+d) — kompilasi pasti gagal"
                             % (nama, path, selisih))
                break


# Pola boros: muncul di baris tambah = peringatan + saran hemat.
POLA_BOROS = [
    ("String.format(", "Formatter+Locale mahal; pakai concat/manual"),
    ("new SimpleDateFormat(", "buat sekali per thread (ThreadLocal bersama)"),
    ("Calendar.getInstance(", "hitung manual tanpa Calendar berat"),
    (".split(", "kompilasi Pattern tiap panggil; pecah manual/indexOf"),
    ("new Thread(", "pakai coroutine/lifecycleScope hemat stack"),
    ("System.out.println", "pakai Log/Toast, bukan stdout"),
    (".printStackTrace()", "telan/abaikan eksplisit + catat log"),
]


def cek_pola_boros(peringatan, diff):
    tampil = set()
    for _, isi in tambah_kode(diff):
        for pola, saran in POLA_BOROS:
            if pola in isi and pola not in tampil:
                if pola == ".split(" and "matcher" in isi.lower():
                    continue  # POLA_X.matcher().split() sah
                tampil.add(pola)
                peringatan.append("Pola boros di baris baru %s — %s: %s"
                                  % (pola.strip(), saran, isi.strip()[:70]))
    for _, isi in tambah_kode(diff):
        if ".matches(" in isi and "matcher" not in isi.lower() \
                and ".matches(" not in tampil:
            tampil.add(".matches(")
            peringatan.append("Pola boros di baris baru .matches( — "
                              "kompilasi regex tiap panggil; pakai Regex/Pattern statis: %s"
                              % isi.strip()[:70])


def cek_izin_baru(peringatan, diff):
    for baris in diff.splitlines():
        if baris.startswith("+") and "uses-permission" in baris:
            cocok = re.search(r'android:name="([^"]+)"', baris)
            if cocok:
                peringatan.append("Izin baru %s — pastikan perlu + tercatat README"
                                  % cocok.group(1))


def cek_definisi_baru(peringatan, diff):
    pola = re.compile(r'\+\s*<(style|string|color|dimen|plurals|bool|string-array|integer-array|array)(?:\s[^>]*?)?\bname\s*=\s*["\x27]([^"\x27]+)["\x27]')
    _, acuan = himpun_definisi()
    sudah = set()
    for baris in diff.splitlines():
        cocok = pola.match(baris)
        if cocok and (cocok.group(1), cocok.group(2)) not in sudah:
            sudah.add((cocok.group(1), cocok.group(2)))
            jenis, nama = cocok.group(1), cocok.group(2)
            kunci = "array" if "array" in jenis else jenis
            if nama not in acuan.get(kunci, set()):
                peringatan.append("Definisi baru %s/%s tanpa acuan — hapus bila tak jadi dipakai"
                                  % (kunci, nama))


def cek_pengingat_changelog(peringatan, berubah):
    if not berubah or "CHANGELOG.md" in berubah:
        return
    if any(b.startswith(("app/src/main/java/", "app/src/main/res/",
                          "app/src/main/AndroidManifest.xml",
                          "app/build.gradle", ".github/workflows/",
                          "tools/", "scripts/")) for b in berubah):
        peringatan.append("Kode/workflow/tooling berubah tanpa CHANGELOG.md — catat per rilis (aturan repo)")


def cek_sinkron_terjemah(gagal):
    # Keberadaan pasangan strings.xml utama tetap wajib.
    isi_strings_wajib(gagal)
    # Paritas nama kini untuk TIGA jenis elemen yang bisa
    # diterjemahkan (string, string-array, plurals) di SEMUA
    # berkas values/ dan values-in/ (issue #1030): dulu hanya
    # <string> di dua strings.xml, jadi string-array/plurals
    # baru dan <string> di berkas values kedua lolos tanpa
    # terjemahan Indonesia dan UI jatuh ke teks Inggris diam-diam.
    dasar_res = os.path.join(AKAR, "app/src/main/res")

    def peta_nama(dirpath):
        peta = {"string": set(), "string-array": set(), "plurals": set()}
        bebas = {"string": set(), "string-array": set(), "plurals": set()}
        jumlah_item = {}
        if not os.path.isdir(dirpath):
            return peta, jumlah_item, bebas
        for nama_berkas in sorted(os.listdir(dirpath)):
            if not nama_berkas.endswith(".xml"):
                continue
            isi = baca(os.path.relpath(os.path.join(dirpath, nama_berkas), AKAR))
            if isi is None:
                continue
            bersih = kupas_komentar_xml(isi)
            for jenis in peta:
                for m in re.finditer(
                        r'<%s(?:\s([^>]*?))?\bname\s*=\s*["\x27]([^"\x27]+)["\x27]([^>]*)>' % jenis, bersih):
                    peta[jenis].add(m.group(2))
                    # translatable="false" memang tak perlu
                    # pasangan terjemahan (issue #1057): nama
                    # aplikasi/brand yang hanya ada di values/
                    # dulu menggagalkan gerbang sebagai "kurang".
                    # Pencocokan kini pola atribut toleran
                    # (issue #1113): spasi di sekitar = dan
                    # kapital nilai adalah XML sah yang aapt
                    # terima, bukan hanya substring persis.
                    # Petik tunggal juga diterima (issue #1133),
                    # simetris dengan pola nama di #1114.
                    if re.search(r'translatable\s*=\s*["\x27]false["\x27]', (m.group(1) or "") + m.group(3), re.I):
                        bebas[jenis].add(m.group(2))
            # Jumlah <item> per string-array (issue #1044): nama
            # yang sama antar locale tetapi itemnya lebih sedikit
            # membuat kode yang mengindeks menurut panjang bawaan
            # kekurangan entri — paritas nama saja tak menangkapnya.
            # Hitungan persis anak langsung via parser XML (issue
            # #1110): pola tekstual lama tak menghitung <item/>
            # self-closing dan ikut menghitung teks <item> di
            # dalam CDATA serta item bersarang.
            for nama_arr, badan in re.findall(
                    r'<string-array(?:\s[^>]*?)?\bname\s*=\s*["\x27]([^"\x27]+)["\x27][^>]*>(.*?)</string-array>',
                    bersih, re.S):
                _h = hitung_item_array(badan)
                # Bedakan "belum tercatat" dari "sudah None" (issue
                # #1237): .get(nama, 0) membuat kemunculan pertama
                # array menjumlah dari 0, bukan meracuni hitungan jadi
                # None; hanya _h None atau racun yang sudah ada yang
                # meracuni. Tanpa beda ini paritas jumlah item mati
                # sejak lahir dan beda item antar locale lolos total.
                if _h is None or jumlah_item.get(nama_arr, 0) is None:
                    jumlah_item[nama_arr] = None
                else:
                    jumlah_item[nama_arr] = jumlah_item.get(nama_arr, 0) + _h
        return peta, jumlah_item, bebas

    inggris, item_inggris, bebas_inggris = peta_nama(os.path.join(dasar_res, "values"))
    indonesia, item_indonesia, _bebas_id = peta_nama(os.path.join(dasar_res, "values-in"))
    for jenis in ("string", "string-array", "plurals"):
        kurang = sorted((inggris[jenis] - bebas_inggris[jenis]) - indonesia[jenis])
        lebih = sorted(indonesia[jenis] - (inggris[jenis] - bebas_inggris[jenis]) - bebas_inggris[jenis])
        if kurang:
            gagal.append("values-in kurang %s: %s" % (jenis, ", ".join(kurang[:10])))
        if lebih:
            gagal.append("values-in berlebih %s: %s" % (jenis, ", ".join(lebih[:10])))
    tak_terhitung = sorted(
        nama for nama in item_inggris.keys() & item_indonesia.keys()
        if item_inggris[nama] is None or item_indonesia[nama] is None)
    if tak_terhitung:
        print("PERINGATAN: paritas item tak bisa dinilai (anggaran "
              "pemindai cadangan habis) untuk: %s" % ", ".join(tak_terhitung[:10]))
    beda_item = sorted(
        nama for nama in item_inggris.keys() & item_indonesia.keys()
        if item_inggris[nama] is not None and item_indonesia[nama] is not None
        and item_inggris[nama] != item_indonesia[nama])
    if beda_item:
        gagal.append("jumlah item string-array beda antar locale: %s" % ", ".join(
            "%s (%d vs %d)" % (n, item_inggris[n], item_indonesia[n])
            for n in beda_item[:10]))


def adalah_env(rendah):
    # Berkas env terlarang (issue #975): bukan hanya nama persis
    # ".env" — varian ".env.local"/".env.production"/".env.bak"
    # adalah konvensi nama berkas rahasia yang sama lazimnya
    # dan dulu lolos kedua gerbang karena pencocokan akhiran
    # persis. Repo ini tak memakai berkas env sama sekali
    # (kredensial hanya via Setup), jadi semua ".env.*" dilarang.
    # Konvensi berakhiran juga dilarang (issue #1027):
    # "production.env"/"staging.env" sama-sama berkas env.
    nama = rendah.rsplit("/", 1)[-1]
    # .envrc (direnv) ikut dilarang (issue #1199): berkas itu
    # lazim memuat rahasia lingkungan walau namanya tak cocok
    # pola .env di atas.
    # Varian .envrc.* ikut dilarang (issue #1288): ".envrc.local"
    # adalah konvensi direnv yang sama lazimnya untuk rahasia
    # lokal dan dulu lolos karena pencocokan nama persis.
    return nama == ".env" or nama == ".envrc" or nama.startswith(".env.") or nama.startswith(".envrc.") or nama.endswith(".env")


def cek_aturan_repo(gagal, berubah):
    for path in berubah:
        # Karakter kontrol di nama berkas (issue #1202): newline
        # di tengah ekstensi ("jahat.j\nks") mengalahkan
        # pencocokan akhiran kedua gerbang — nama semacam itu
        # tak punya alasan sah, tolak apa pun ekstensinya.
        if any(ord(ch) < 32 or ord(ch) == 127 for ch in path):
            gagal.append("Nama berkas mengandung karakter kontrol: %r" % path)
            continue
        rendah = path.lower()
        sudah_dilarang = False
        # Penghapusan berkas terlarang adalah REMEDIASI, bukan
        # pelanggaran (issue #1290): path yang tak ada lagi di
        # pohon kerja sedang KELUAR dari repo — memvonisnya
        # GAGAL menutup satu-satunya jalan membersihkan repo
        # tanpa --no-verify. Mode --semua tak terpengaruh:
        # daftarnya berkas terlacak yang memang ada.
        masih_ada = os.path.isfile(os.path.join(AKAR, path))
        if masih_ada and (rendah.endswith((".apk", ".aab", ".ap_", ".dex", ".jks",
                             ".keystore", ".p12", ".pfx", ".pem", ".key")) or adalah_env(rendah)):
            gagal.append("Berkas terlarang masuk repo: " + path)
            sudah_dilarang = True
        elif masih_ada and (rendah == "local.properties" or rendah.endswith("/local.properties")):
            gagal.append("local.properties tak boleh masuk repo: " + path)
            sudah_dilarang = True
        # Batas ukuran berlaku di SEMUA folder (issue #1040):
        # dulu bersyarat awalan app/src/main/ sehingga blob
        # besar berekstensi jinak di tools/docs/akar lolos —
        # repo membengkak permanen untuk semua clone. Satu
        # pengecualian bernama: CHANGELOG.md memang sudah
        # melewati 1 MB secara sah (teks riwayat rilis yang
        # hanya bertambah) — mengecualikannya per nama, bukan
        # per folder/ekstensi, agar berkas lain tetap terjaga.
        # Pengecualian bukan tanpa batas (issue #1058): di atas
        # 5 MB berkas itu tak lagi masuk akal sebagai changelog
        # dan tetap menggagalkan — isi apa pun tak boleh bisa
        # diselundupkan lewat satu-satunya nama yang dikecualikan.
        batas = 5 * 1024 * 1024 if path == "CHANGELOG.md" else 1024 * 1024
        if not sudah_dilarang:
            try:
                # lstat seperti sapuan pohon (issue #1144):
                # getsize mengikuti symlink membuat vonis
                # bergantung mode — tautan kecil ke berkas
                # besar luar repo digagalkan palsu di jalur ini
                # sementara sapuan meloloskannya.
                ukuran = os.lstat(os.path.join(AKAR, path)).st_size
            except OSError:
                ukuran = 0
            if ukuran > batas:
                gagal.append("Berkas >%d MB masuk repo: %s" % (batas // (1024 * 1024), path))


def cek_ukuran_pohon(gagal, sudah_dicek):
    # Sapuan pohon penuh untuk plafon ukuran (issue #1118):
    # cek_aturan_repo hanya menilai berkas BERUBAH, jadi blob
    # besar yang sudah ter-commit lebih dulu lolos mode
    # rentang/bawaan selamanya — commit trivial sesudahnya
    # tak pernah menyentuhnya lagi. Semua berkas terlacak
    # dinilai ukurannya di sini dengan ambang dan pengecualian
    # bernama yang sama; berkas yang baru dinilai lewat jalur
    # berubah dilewati agar laporannya tak dobel.
    # Daftar terlacak memakai -z (issue #1131): tanpa itu git
    # mengutip nama non-ASCII gaya C sehingga getsize pada
    # nama terutip gagal dan berkas besarnya terlewat diam-diam.
    for path in (jalan("git", "ls-files", "-z") or "").split("\x00"):
        if not path or path in sudah_dicek:
            continue
        batas = 5 * 1024 * 1024 if path == "CHANGELOG.md" else 1024 * 1024
        try:
            # Symlink dinilai dari lstat, bukan tujuannya
            # (issue #1132): yang masuk repo hanya teks tautan
            # (belasan byte) — mengikuti tautan ke berkas besar
            # di luar repo menggagalkan secara palsu.
            ukuran = os.lstat(os.path.join(AKAR, path)).st_size
        except OSError:
            continue
        if ukuran > batas:
            gagal.append("Berkas >%d MB masuk repo: %s" % (batas // (1024 * 1024), path))


POLA_TOKEN_ASLI = re.compile(r"[0-9]{6,}:[A-Za-z0-9_-]{35,}")


def _muat_mengandung_token():
    """Pakai pemindai bersama tools/cek-token.py (issue #765: bentuk
    base64/terbelah ikut dikenali); jatuh ke pola literal bila berkas
    pendamping hilang agar gerbang dasar tetap hidup."""
    try:
        import importlib.util
        spec = importlib.util.spec_from_file_location(
            "cek_token", os.path.join(AKAR, "tools", "cek-token.py"))
        modul = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(modul)
        return modul.mengandung_token
    except Exception:
        return lambda teks: bool(teks) and bool(POLA_TOKEN_ASLI.search(teks))


mengandung_token = _muat_mengandung_token()


def cek_secret(gagal, diff):
    tambah = "\n".join(
        baris[1:] for baris in diff.splitlines()
        if baris.startswith("+") and not baris.startswith("+++"))
    if mengandung_token(tambah):
        gagal.append("Kemungkinan token bot asli ikut diff (termasuk "
                     "bentuk base64/terbelah) — kredensial hanya via "
                     "input manual SetupActivity")
    # Berkas biner yang berubah tak menyumbang baris "+" berisi isi —
    # token di dalamnya lolos pemindaian baris di atas (issue #695).
    # Pindai isi berkas berubah langsung (dekode toleran seperti baca()).
    for baris in diff.splitlines():
        if baris.startswith("diff --git "):
            path = _path_sisi_b(baris)
        elif baris.startswith("+++ "):
            token = _buka_kutip_git(baris[4:].strip())
            path = token[2:] if token.startswith("b/") else token
        else:
            continue
        if True:
            isi = baca(path)
            if isi is not None and mengandung_token(isi):
                gagal.append("Kemungkinan token bot asli di berkas berubah %s "
                             "(termasuk biner/base64/terbelah) — kredensial "
                             "hanya via input manual SetupActivity" % path)
                break


def cek_secret_pohon(gagal):
    """Pindai token asli di seluruh berkas terlacak (mode --semua/audit penuh)."""
    # Per berkas lewat mengandung_token (issue #765): git grep literal
    # saja melewatkan bentuk base64/terbelah; baca() mendekode toleran
    # sehingga berkas biner ikut terbaca seperti issue #695/#742.
    for path in pecah_nama(jalan("git", "ls-files", "-z")):
        if mengandung_token(baca(path)):
            gagal.append("Kemungkinan token bot asli terlacak di %s "
                         "(termasuk biner/base64/terbelah) — periksa dan "
                         "ganti/hapus berkas itu bila bukan token "
                         "sungguhan. Kredensial hanya via input manual "
                         "SetupActivity" % path)


def cek_secret_riwayat(gagal, rentang):
    """Pindai token di pohon TIAP commit dalam rentang (issue #750).

    Diff bersih rentang tidak memuat token yang ditambah lalu dihapus
    lagi di dalam rentang yang sama, padahal kedua commit ikut terkirim
    dan token itu hidup permanen di riwayat git. Pindai pohon tiap
    commit dalam rentang agar pola "sudah kuhapus kok" tetap tertangkap.
    """
    daftar_commit = jalan("git", "rev-list", rentang) or ""
    for sha in daftar_commit.splitlines():
        sha = sha.strip()
        if not sha:
            continue
        out = jalan("git", "grep", "-l", "-E",
                    r"[0-9]{6,}:[A-Za-z0-9_-]{35,}", sha) or ""
        for baris in out.splitlines():
            teks = baris.strip()
            if not teks:
                continue
            if teks.startswith("Binary file ") and teks.endswith(" matches"):
                path = teks[len("Binary file "):-len(" matches")]
                gagal.append("Kemungkinan token bot asli di berkas BINER %s "
                             "pada commit %s (riwayat rentang) — token tetap "
                             "hidup di riwayat walau sudah dihapus di pohon "
                             "akhir. Kredensial hanya via input manual "
                             "SetupActivity" % (path, sha[:8]))
            else:
                path = teks.split(":", 1)[1] if ":" in teks else teks
                gagal.append("Kemungkinan token bot asli di %s pada commit %s "
                             "(riwayat rentang) — token tetap hidup di "
                             "riwayat walau sudah dihapus di pohon akhir. "
                             "Kredensial hanya via input manual "
                             "SetupActivity" % (path, sha[:8]))
            return
        # Bentuk base64/terbelah tak terlihat grep literal (issue #765):
        # saring berkas kandidat (memuat deret digit panjang atau run
        # base64 panjang), lalu uji isinya per commit dengan pemindai
        # bersama.
        # Ambang run base64 40 (issue #1200): token yang
        # di-commit terbelah 40 char/baris tak pernah mencapai
        # 48 sehingga tak pernah jadi kandidat dan pemindai
        # bersama tak pernah melihat blob-nya. 40 masih selektif
        # (kandidat bukan vonis — vonis tetap mengandung_token);
        # pemecahan di bawah 40 char/baris tetap batas kanal ini.
        kandidat = jalan("git", "grep", "-a", "-l", "-E",
                         r"[0-9]{6,}|[A-Za-z0-9+/=_-]{40,}", sha) or ""
        for baris in kandidat.splitlines():
            teks = baris.strip()
            if not teks:
                continue
            path = teks.split(":", 1)[1] if ":" in teks else teks
            mentah = jalan_biner("git", "show", "%s:%s" % (sha, path))
            isi = _dekode(mentah) if mentah is not None else None
            if mengandung_token(isi):
                gagal.append("Kemungkinan token bot asli (base64/terbelah) "
                             "di %s pada commit %s (riwayat rentang) — "
                             "token tetap hidup di riwayat walau sudah "
                             "dihapus di pohon akhir. Kredensial hanya "
                             "via input manual SetupActivity"
                             % (path, sha[:8]))
                return
        # Berkas UTF-16 tak pernah jadi kandidat di atas (issue #801):
        # byte NUL antar karakter memutus regex saringan grep. Sapu
        # blob ber-BOM UTF-16 secara terpisah dan uji sesudah didekode.
        for bom in (b"\xff\xfe", b"\xfe\xff"):
            out = jalan_biner("git", "grep", "-a", "-l", "-F", "-e", bom, sha) or b""
            for baris in out.decode("utf-8", errors="replace").splitlines():
                teks = baris.strip()
                if not teks:
                    continue
                path = teks.split(":", 1)[1] if ":" in teks else teks
                mentah = jalan_biner("git", "show", "%s:%s" % (sha, path))
                if mentah is not None and mengandung_token(_dekode(mentah)):
                    gagal.append("Kemungkinan token bot asli (UTF-16) "
                                 "di %s pada commit %s (riwayat rentang) — "
                                 "token tetap hidup di riwayat walau sudah "
                                 "dihapus di pohon akhir. Kredensial hanya "
                                 "via input manual SetupActivity"
                                 % (path, sha[:8]))
                    return
        # UTF-16 TANPA BOM juga tak jadi kandidat (issue #817): tak
        # ada BOM untuk disapu dan NUL memutus saringan grep. Uji pola
        # NUL-nya langsung pada blob berkas yang berubah di commit ini.
        # --root + -m (issue #835): tanpa keduanya diff-tree kosong
        # untuk commit root dan commit merge, jadi berkas UTF-16
        # yang lahir di sana tak pernah terjangkau kanal ini.
        berubah = jalan("git", "diff-tree", "--no-commit-id",
                        "--name-only", "-z", "-r", "--root", "-m", sha) or ""
        for path in dict.fromkeys(pecah_nama(berubah)):
            path = path.strip()
            if not path:
                continue
            mentah = jalan_biner("git", "show", "%s:%s" % (sha, path))
            if mentah is not None and _utf16_tanpa_bom(mentah) and \
                    mengandung_token(_dekode(mentah)):
                gagal.append("Kemungkinan token bot asli (UTF-16 "
                             "tanpa BOM) di %s pada commit %s (riwayat "
                             "rentang) — token tetap hidup di riwayat "
                             "walau sudah dihapus di pohon akhir. "
                             "Kredensial hanya via input manual "
                             "SetupActivity" % (path, sha[:8]))
                return


def cek_secret_pesan(gagal, rentang):
    """Pindai token di PESAN commit dalam rentang (issue #769).

    Diff dan pohon tak memuat pesan commit, padahal token yang tertulis
    di pesan (tempelan contoh, pesan bawaan cherry-pick) terkirim dan
    hidup permanen di riwayat — media yang sama dengan #750.
    """
    daftar_commit = jalan("git", "rev-list", rentang) or ""
    for sha in daftar_commit.splitlines():
        sha = sha.strip()
        if not sha:
            continue
        pesan = jalan("git", "log", "-1", "--format=%B", sha)
        if mengandung_token(pesan):
            gagal.append("Kemungkinan token bot asli di PESAN commit %s — "
                         "token di pesan commit hidup permanen di riwayat; "
                         "tulis ulang pesan itu sebelum push. Kredensial "
                         "hanya via input manual SetupActivity" % sha[:8])
            return


def cek_secret_pesan_semua(gagal):
    """Pindai pesan SELURUH commit terjangkau HEAD (issue #800).

    Mode --semua adalah pindai forensik berkala: pesan commit yang
    sudah telanjur ter-push pun harus tertandai agar token yang
    bocor bisa dirotasi. Satu panggilan git log berformat rekaman
    agar riwayat panjang (ribuan commit) tidak dibayar per commit.
    """
    out = jalan("git", "log", "--format=%H%x00%B%x1e", "HEAD") or ""
    # Pesan commit bisa memuat byte \x1e itu sendiri (issue #814):
    # pecahan yang TIDAK diawali pola hash+\x00 adalah kelanjutan
    # pesan rekaman sebelumnya, bukan rekaman baru — gabungkan lagi
    # sebelum memindai, agar token sesudah \x1e di dalam pesan tidak
    # jatuh ke potongan yang tak pernah diperiksa.
    rekaman_list = []
    for pecahan in out.split("\x1e"):
        inti = pecahan.strip("\n")
        if not inti.strip():
            continue
        if "\x00" in inti and len(inti.split("\x00", 1)[0].strip()) == 40:
            rekaman_list.append(inti)
        elif rekaman_list:
            rekaman_list[-1] += "\x1e" + pecahan
        else:
            rekaman_list.append(inti)
    for rekaman in rekaman_list:
        sha, _, pesan = rekaman.partition("\x00")
        if mengandung_token(pesan):
            gagal.append("Kemungkinan token bot asli di PESAN commit %s — "
                         "token di pesan commit hidup permanen di riwayat; "
                         "rotasi token itu. Kredensial hanya via input "
                         "manual SetupActivity" % sha.strip()[:8])
            return


def nama_riwayat(rev):
    """Semua path yang muncul di diff per-commit dalam cakupan rev
    (issue #816): nama bertoken yang sudah diganti nama tetap hidup
    di riwayat dan ikut terpublikasi. Daftar per-commit (bukan diff
    ujung-ke-ujung dan bukan rev-list --objects) adalah satu-satunya
    sumber lengkap: diff ujung menyembunyikan rename di tengah
    rentang, dan rev-list menamai blob yang sama hanya sekali."""
    # -m (issue #835): tanpa itu git log tak menampilkan diff commit
    # merge sama sekali, jadi nama yang hanya hidup di resolusi
    # merge tak terlihat daftar ini.
    out = jalan("git", "log", "--format=", "--name-status", "-z", "-m", rev) or ""
    # Token -z berurutan posisi seperti berkas_dihapus:
    # status lalu satu path (dua untuk R/C) — kumpulkan semua
    # pathnya (nama lama rename ikut, sesuai maksud #816).
    hasil = []
    token = pecah_nama(out)
    i = 0
    while i < len(token):
        status = token[i]
        i += 1
        if not status or status[0] not in "AMDRCTU":
            continue
        ambil = 2 if status[0] in "RC" else 1
        for _ in range(ambil):
            if i >= len(token):
                break
            hasil.append(token[i])
            i += 1
    return hasil


def cek_secret_nama(gagal, daftar):
    """Pindai token pada NAMA berkas itu sendiri (issue #803).

    Semua jalur lain hanya membaca isi; berkas yang namanya berpola
    token (tangkapan/log yang dinamai dari teks berisi token) ikut
    ter-commit dan tampil di daftar repo tanpa pernah diperiksa.
    """
    for path in daftar:
        # Varian gabungan tanpa "/" (issue #815): token yang terbelah
        # antar komponen path (direktori "123456789:" + berkas ekor
        # token) tak terlihat sebagai satu token pada path mentah.
        if path and (mengandung_token(path) or
                     mengandung_token(path.replace("/", ""))):
            gagal.append("Kemungkinan token bot asli pada NAMA berkas %s — "
                         "ganti nama berkas itu; kredensial hanya via "
                         "input manual SetupActivity" % path)
            return


def cek_secret_tak_lacak(gagal):
    """Pindai token asli di berkas tak terlacak (belum masuk git grep/diff):
    berkas seperti itu lolos kedua jalur lain dan tinggal menunggu commit."""
    for path in nama_tak_lacak():
        if mengandung_token(baca(path)):
            gagal.append("Kemungkinan token bot asli di berkas tak terlacak %s "
                         "(termasuk base64/terbelah) — kredensial hanya "
                         "via input manual SetupActivity" % path)


def nama_terabaikan():
    out = jalan("git", "ls-files", "--others", "-i", "--exclude-standard", "-z")
    return pecah_nama(out)


def kupas_komentar_gradle(teks):
    """Buang komentar Gradle/Groovy sambil mempertahankan isi string."""
    keluar = []
    i, n = 0, len(teks)
    while i < n:
        tiga = teks[i:i + 3]
        dua = teks[i:i + 2]
        if tiga in ("'''", '"""'):
            # Isi string multi-baris dikosongkan (issue #804): string
            # triple-quote adalah DATA, bukan kode — baris include di
            # dalamnya (mis. catatan) bukan pernyataan modul. String
            # satu baris tetap dipertahankan utuh karena jalur include
            # sungguhan tinggal di sana (include ':app').
            akhir = teks.find(tiga, i + 3)
            if akhir < 0:
                keluar.append(tiga)
                keluar.append("".join("\n" if c == "\n" else " " for c in teks[i + 3:]))
                break
            keluar.append(tiga)
            keluar.append("".join("\n" if c == "\n" else " " for c in teks[i + 3:akhir]))
            keluar.append(tiga)
            i = akhir + 3
        elif dua == "//":
            while i < n and teks[i] != "\n":
                keluar.append(" ")
                i += 1
        elif dua == "/*":
            keluar.append("  ")
            i += 2
            while i < n and teks[i:i + 2] != "*/":
                keluar.append("\n" if teks[i] == "\n" else " ")
                i += 1
            if i < n:
                keluar.append("  ")
                i += 2
        elif teks[i] in ("'", '"'):
            kutip = teks[i]
            keluar.append(kutip)
            i += 1
            while i < n:
                c = teks[i]
                keluar.append(c)
                i += 1
                if c == "\\" and i < n:
                    keluar.append(teks[i])
                    i += 1
                elif c == kutip or c == "\n":
                    break
        else:
            keluar.append(teks[i])
            i += 1
    return "".join(keluar)


def modul_settings():
    """Daftar direktori modul dari settings.gradle/settings.gradle.kts
    (baris include ':app' dst) sebagai tuple komponen path."""
    teks = baca_teks("settings.gradle") or baca_teks("settings.gradle.kts")
    teks = kupas_komentar_gradle(teks or "")
    modul = set()
    for baris in teks.splitlines():
        # Hanya pernyataan include di awal baris yang sah (issue #786):
        # #764 membuang komentar tetapi mempertahankan string, sehingga
        # teks include ':x' di dalam string (mis. catatan di variabel)
        # ikut menyatakan modul dan mengecualikan direktori itu dari
        # gerbang. includeBuild juga bukan include modul.
        s = baris.lstrip()
        if not s.startswith("include"):
            continue
        rest = s[len("include"):]
        if rest and rest[0] not in " \t('\"":
            continue
        for m in re.finditer(r"""['"](:[A-Za-z0-9_:\-]+)['"]""", baris):
            modul.add(tuple(p for p in m.group(1).split(":") if p))
    return modul


_MODUL_CACHE = None


def _modul_repo():
    global _MODUL_CACHE
    if _MODUL_CACHE is None:
        _MODUL_CACHE = modul_settings()
    return _MODUL_CACHE


def dir_sampah_sungguhan(bagian):
    """True bila path memuat direktori sampah (.gradle/__pycache__)
    di lokasi SUNGGUHAN (issue #895): .gradle di akar repo atau di
    akar modul settings.gradle; __pycache__ di akar atau di bawah
    tools/ (satu-satunya tempat berkas .py terlacak tinggal, jadi
    satu-satunya tempat Python menaruh cache-nya). Sebelumnya nama
    itu dikecualikan di komponen path MANA PUN sehingga
    docs/__pycache__/sembunyi.txt lolos kedua gerbang — pola yang
    sama yang ditutup untuk "build" di #706/#719."""
    modul = _modul_repo()
    for i, b in enumerate(bagian):
        if b == ".gradle" and (i == 0 or tuple(bagian[:i]) in modul):
            return True
        if b == "__pycache__" and (i == 0 or bagian[0] == "tools"):
            return True
    return False


def cek_secret_terabaikan(gagal, peringatan):
    """Berkas ignored + tak terlacak tak terlihat ls-files biasa: berkas
    berisi token di sana tak terlihat kedua gerbang sampai seseorang
    menjalankan git add -f (issue #680). Abaikan direktori build."""
    modul = modul_settings()
    for path in nama_terabaikan():
        bagian = path.split("/")
        if dir_sampah_sungguhan(bagian):
            continue
        # Pengecualian direktori build hanya untuk direktori build SUNGGUHAN
        # (issue #706): komponen bernama "build" di mana pun sebelumnya ikut
        # dikecualikan sehingga catatan/build/bocor.txt lolos. Jangkar
        # build.gradle (#706) masih bisa dilompati folder contoh yang memuat
        # build.gradle kosong (issue #719) — kini direktori build harus di
        # akar repo atau milik modul yang tercantum di settings.gradle.
        if "build" in bagian:
            i = bagian.index("build")
            awalan = bagian[:i]
            if i == 0 or tuple(awalan) in modul:
                continue
        rendah = path.lower()
        if rendah.endswith((".apk", ".aab", ".ap_", ".dex", ".jks",
                            ".keystore", ".p12", ".pfx", ".pem", ".key")) or adalah_env(rendah) or \
                rendah == "local.properties" or rendah.endswith("/local.properties"):
            peringatan.append("Berkas sensitif terabaikan di pohon kerja: " + path)
        if mengandung_token(baca(path)):
            gagal.append("Kemungkinan token bot asli di berkas terabaikan %s "
                         "(termasuk base64/terbelah) — kredensial hanya "
                         "via input manual SetupActivity" % path)


def _rapikan_generik(teks):
    """Runtuhkan rentang generik <...> yang seimbang menjadi "<T>"
    (issue #1263): pola deklarasi gerbang hanya mengenal generik
    satu tingkat, jadi generik bertingkat dua (Map<String,
    List<Int>>) lolos dari panen metode hilang maupun pencocokan
    definisi. Rentang tak seimbang atau melewati baris dibiarkan
    apa adanya."""
    hasil = []
    i = 0
    while i < len(teks):
        if teks[i] == "<":
            dalam = 0
            j = i
            while j < len(teks) and teks[j] != "\n":
                if teks[j] == "<":
                    dalam += 1
                elif teks[j] == ">":
                    dalam -= 1
                    if dalam == 0:
                        break
                j += 1
            if j < len(teks) and teks[j] == ">" and dalam == 0:
                hasil.append("<T>")
                i = j + 1
                continue
        hasil.append(teks[i])
        i += 1
    return "".join(hasil)


def _berkas_terhapus(diff):
    """Path yang dihapus SEUTUHNYA di diff ini (kepala 'deleted
    file mode' / tujuan /dev/null)."""
    terhapus = set()
    path_kini = ""
    for baris in diff.splitlines():
        if baris.startswith("diff --git "):
            path_kini = _path_sisi_b(baris)
        elif baris.startswith("deleted file mode") and path_kini:
            terhapus.add(path_kini)
    return terhapus


def nama_metode_hilang(diff, lewati_berkas=()):
    """Nama metode pada baris dihapus yang definisinya hilang dari pohon kerja."""
    terhapus_utuh = _berkas_terhapus(diff)
    hilang = []
    for path, baris in hapus_kode(diff):
        baris = _rapikan_generik(baris)
        rapi = baris.rstrip()
        if re.match(r"\s*return\b", baris):
            continue
        if not re.search(r"\b(public|protected|private|static|fun|override|suspend)\b", baris) \
                and not rapi.endswith("{") \
                and (rapi.endswith(";")
                     or not re.match(r"\s*\w[\w<>\[\].,? ]*\s+\w+\s*\(", baris)):
            continue
        # Berkas yang terhapus UTUH di diff ini tetap dipanen
        # (issue #1285): saringan isfile lama meloloskan seluruh
        # metodenya, jadi pemanggil tertinggal dari penghapusan
        # satu berkas penuh tak pernah tertangkap. Berkas yang
        # hilang karena alasan lain (di luar pohon kerja) tetap
        # dilewati.
        if path in lewati_berkas:
            continue
        if path not in terhapus_utuh and not os.path.isfile(os.path.join(AKAR, path)):
            continue
        cocok = re.match(
            r"\s*(?:(?:public|protected|private)\s+)?(?:static\s+)?"
            r"(?:fun\s+)?([\w<>\[\].,? ]*\w)\s+(\w+)\s*\(", baris)
        nama = None
        if cocok and cocok.group(2) not in KATA_KUNCI \
                and cocok.group(1).strip() not in KATA_ALUR:
            nama = cocok.group(2)
        if nama is None:
            # Deklarasi extension/generik Kotlin (issue #1252):
            # "fun String.nama()" / "fun <T> nama()" — titik
            # penerima hanya sah sesudah kata kunci fun. Tanpa
            # syarat itu, baris hapus berisi panggilan anggota
            # ("lifecycleScope.launch(...) {") ikut terpanen
            # sebagai metode hilang dan gerbang gagal palsu
            # (terbukti pada build tag v1.6.614).
            cocok2 = re.match(
                r"\s*(?:(?:public|protected|private|internal|override|suspend|inline|operator|infix|tailrec)\s+)*"
                r"fun\s+(?:<[^>\n]+>\s*)?(?:[\w<>\[\],.? ]*?\.)?(\w+)\s*\(", baris)
            if cocok2 and cocok2.group(1) not in KATA_KUNCI:
                nama = cocok2.group(1)
        if nama is not None:
            hilang.append(nama)
    return hilang


def masih_didefinisikan(nama):
    # Kelas (termasuk data class) juga diakui sebagai definisi:
    # mengubah parameter konstruktor data class bukan menghapus
    # metode — tanpa ini nama_metode_hilang memanen nama kelas
    # dari baris deklarasi lama dan masih_didefinisikan (yang
    # dulu hanya mencari "fun") tak pernah menemukannya lagi.
    pola = re.compile(
        r"(?m)^[ \t]*(?:(?:public|private|protected|internal|override|suspend|inline|"
        r"operator|open|final|abstract|sealed|tailrec|infix|data)\s+)*"
        r"(?:fun\s+(?:<[^>\n]+>\s*)?(?:[\w.]+\.)?|class\s+)%s\s*(?:<[^>\n]+>\s*)?\(" % re.escape(nama))
    for path in jalan_repo(os.path.join("app", "src", "main", "java")):
        if pola.search(_rapikan_generik(baca_teks(path))):
            return True
    return False


def pemanggil(nama):
    pola = re.compile(r"(?:\b%s\s*\(|::%s\b)" % (re.escape(nama), re.escape(nama)))
    temu = []
    for path in jalan_repo("app", ext=EKSTENSI_KODE):
        # Komentar dikupas dulu (issue #1254, keluarga #1218):
        # sebutan di komentar bukan pemanggil — tanpa kupasan ia
        # memicu GAGAL palsu yang menamai baris komentar itu.
        # _kupas_komentar_kode mempertahankan newline sehingga
        # nomor baris laporan tetap tepat.
        bersih = _kupas_komentar_kode(baca_teks(path), bersarang=not path.endswith(".java"))
        for i, baris in enumerate(bersih.splitlines(), 1):
            if pola.search(baris):
                temu.append("%s:%d" % (path, i))
                if len(temu) >= 6:
                    return temu
    return temu


def cek_metode_hilang(gagal, peringatan, diff, hapus=()):
    # Berkas terhapus TIDAK dilewati di sini (issue #1285):
    # lewati_berkas lama membuat seluruh metode berkas yang
    # dihapus utuh tak terpanen, dan cek_berkas_kode_dihapus
    # hanya memeriksa nama KELASnya — pemanggil metode/fungsi
    # lepas-atas yang tertinggal lolos total. Metode yang
    # pindah rumah tetap aman: masih_didefinisikan menemukannya
    # di berkas barunya.
    for nama in sorted(nama_metode_hilang(diff)):
        if masih_didefinisikan(nama):
            continue  # overload/kembaran masih ada: aman
        siapa = pemanggil(nama)
        if not siapa:
            continue  # tak ada pemanggil sisa: hapus bersih
        pesan = "Metode %s() dihapus tapi masih dipanggil: %s" % (nama, ", ".join(siapa))
        if nama in OVERRIDE_AMAN:
            peringatan.append(pesan + " (callback siklus hidup?)")
        else:
            gagal.append(pesan)


def _kupasan_baris_beruntun(baris_list):
    """Kupas komentar/string dari baris tambah satu berkas
    secara beruntun: keadaan string mentah dan komentar blok
    (bersarang, gaya Kotlin) dibawakan antarbaris, sehingga
    baris yang merupakan ISI string mentah/komentar blok
    menghasilkan teks kosong, bukan kode. Di dalam satu baris:
    // mengakhiri baris, string/char biasa tak membentang
    baris, interpolasi ${...} di dalam string dilompati utuh
    memakai _lewati_interpolasi (selaras kupas_kode bawaan)."""
    hasil = []
    dalam_mentah = False
    kedalaman_blok = 0
    for baris in baris_list:
        keluar = []
        i, n = 0, len(baris)
        while i < n:
            if dalam_mentah:
                j = baris.find('"""', i)
                if j < 0:
                    i = n
                else:
                    dalam_mentah = False
                    i = j + 3
                continue
            if kedalaman_blok > 0:
                if baris[i:i + 2] == '/*':
                    kedalaman_blok += 1
                    i += 2
                elif baris[i:i + 2] == '*/':
                    kedalaman_blok -= 1
                    i += 2
                else:
                    i += 1
                continue
            tiga = baris[i:i + 3]
            dua = baris[i:i + 2]
            if tiga == '"""':
                dalam_mentah = True
                i += 3
            elif dua == '//':
                i = n
            elif dua == '/*':
                kedalaman_blok = 1
                i += 2
            elif baris[i] == '"' or baris[i] == "'":
                q = baris[i]
                i += 1
                while i < n:
                    if baris[i] == '\\':
                        i += 2
                        continue
                    if baris[i] == q:
                        i += 1
                        break
                    if q == '"' and baris[i] == '$' and baris[i + 1:i + 2] == '{':
                        i = _lewati_interpolasi(baris, i)
                        continue
                    i += 1
            else:
                keluar.append(baris[i])
                i += 1
        hasil.append("".join(keluar))
    return hasil


def cek_panggil_bekas_hapusan(gagal, diff):
    # Pola diperluas (issue #1284): entri bekas-hapusan tak
    # selalu fungsi — konstanta/properti terhapus dipakai wajar
    # tanpa "(": OTP_CODE_REGEX.containsMatchIn(...),
    # OTP_KEYWORDS.any{...}, diteruskan sebagai argumen, dsb.
    # Baris dikupas komentar/string dulu; sesudah itu setiap
    # penyebutan nama di posisi kode adalah pemakaian. Baris
    # DEKLARASI nama yang sama dikecualikan — sisi definisi
    # dijaga cek lain, dan val/var lokal baru bernama sama
    # bukan regresi hapusan.
    pola = {nama: re.compile(r"\b%s\b" % re.escape(nama)) for nama in BEKAS_HAPUSAN}
    pola_dekl = {nama: re.compile(r"\b(?:val|var|fun|class|object)\s+%s\b" % re.escape(nama)) for nama in BEKAS_HAPUSAN}
    sisa = set(BEKAS_HAPUSAN)
    # Pengupasan membawa KEADAAN antarbaris per berkas (issue
    # #1302): kupas_kode per baris buta terhadap string mentah
    # multi-baris dan komentar blok yang membentang antarbaris
    # tambah — baris isinya divonis sebagai kode (positif
    # palsu). (Menggabungkan baris lalu kupas_kode tak bisa:
    # kupas_kode tak mempertahankan baris-baru di dalam rentang
    # yang dilompati, jadi pemetaan per barisnya rusak.)
    per_berkas = {}
    for path, baris in tambah_kode(diff):
        per_berkas.setdefault(path, []).append(baris)
    for path in per_berkas:
        if not sisa:
            break
        baris_list = per_berkas[path]
        bersih_list = _kupasan_baris_beruntun(baris_list)
        for baris, bersih in zip(baris_list, bersih_list):
            if not sisa:
                break
            for nama in sorted(sisa):
                if pola[nama].search(bersih) and not pola_dekl[nama].search(bersih):
                    gagal.append(
                        "Baris baru memakai %s yang sudah dihapus: %s"
                        % (nama, baris.strip()[:80]))
                    sisa.discard(nama)


def hunk_fungsi(diff):
    """Hasilkan (path, nama_fungsi) dari kepala hunk diff."""
    path = ""
    for baris in diff.splitlines():
        if baris.startswith("diff --git "):
            path = _path_sisi_b(baris)
        elif baris.startswith("@@ ") and path.endswith(EKSTENSI_KODE):
            ekor = baris.split("@@", 2)[-1]
            cocok = re.search(r"(\w+)\s*\(", ekor)
            if cocok:
                yield path, cocok.group(1)


def _fungsi_pemilik(path, baris_mulai):
    """Nama fun yang badannya memuat baris (1-based) pada berkas
    kerja saat ini, atau None. Kepala hunk Git untuk metode
    anggota Kotlin hampir selalu menamai KELAS pemiliknya
    (issue #1286), jadi pemilik dicari dari isi berkas: pindai
    deklarasi fun sebelum baris itu pada teks terkupas, lacak
    kedalaman kurung, dan ambil deklarasi terdalam yang
    badannya belum tertutup di baris_mulai."""
    penuh = os.path.join(AKAR, path)
    if not os.path.isfile(penuh):
        return None
    bersih = _kupas_komentar_kode(baca_teks(penuh), bersarang=not path.endswith(".java"))
    pola = re.compile(r"^\s*(?:(?:public|private|protected|internal|override|suspend|inline|operator|infix|tailrec|open|final|abstract)\s+)*fun\s+(?:<[^>\n]+>\s*)?(?:[\w<>\[\],.? ]*\.)?(\w+)\s*\(")
    tumpukan = []  # (nama, kedalaman badan)
    kedalaman = 0
    for i, baris in enumerate(bersih.splitlines(), 1):
        if i > baris_mulai:
            break
        cocok = pola.match(baris)
        buka = baris.count("{")
        tutup = baris.count("}")
        if cocok and cocok.group(1) not in KATA_KUNCI:
            tumpukan.append((cocok.group(1), kedalaman + (1 if buka > 0 else 0)))
        kedalaman += buka - tutup
        while tumpukan and kedalaman < tumpukan[-1][1]:
            tumpukan.pop()
    return tumpukan[-1][0] if tumpukan else None


def hunk_posisi(diff):
    """Hasilkan (path, baris_baru) tiap hunk berkas kode: nomor
    baris sisi-baru dari baris TAMBAH pertama hunk itu (baris
    yang benar-benar berubah), atau baris awal hunk bila tak
    ada baris tambah. Baris konteks awal/akhir hunk tak dipakai
    — ia bisa berupa deklarasi kelas atau kurung tutup fungsi
    (issue #1286)."""
    path = ""
    kini = None  # nomor baris sisi-baru berjalan
    awal = 0
    sudah = False
    for baris in diff.splitlines():
        if baris.startswith("diff --git "):
            if path.endswith(EKSTENSI_KODE) and kini is not None and not sudah:
                yield path, awal
            path = _path_sisi_b(baris)
            kini = None
            sudah = False
        elif baris.startswith("@@ ") and path.endswith(EKSTENSI_KODE):
            if kini is not None and not sudah:
                yield path, awal
            cocok = re.search(r"\+(\d+)", baris)
            awal = int(cocok.group(1)) if cocok else 0
            kini = awal
            sudah = False
        elif kini is not None:
            if baris.startswith("+") and not baris.startswith("+++"):
                if not sudah:
                    yield path, kini
                    sudah = True
                kini += 1
            elif baris.startswith("-") and not baris.startswith("---"):
                pass
            elif baris.startswith("\\"):
                pass
            else:
                kini += 1
    if path.endswith(EKSTENSI_KODE) and kini is not None and not sudah:
        yield path, awal


def cek_uji_ubah(peringatan, diff):
    sudah = set()
    kandidat = [nama for _, nama in hunk_fungsi(diff)]
    # Pemilik badan hunk dari isi berkas (issue #1286): tanpa
    # ini peringatan uji praktis mati untuk semua metode
    # anggota — kepala hunk hanya menamai kelasnya.
    for path, mulai in hunk_posisi(diff):
        pemilik = _fungsi_pemilik(path, mulai)
        if pemilik:
            kandidat.append(pemilik)
    for nama in kandidat:
        if not nama or nama in sudah or nama in KATA_KUNCI:
            continue
        if nama[:1].isupper():
            continue  # kelas/konstruktor, bukan metode biasa
        sudah.add(nama)
        if not disebut_di_uji(nama):
            peringatan.append(
                "Metode %s() diubah tapi tanpa jejak di app/src/test — "
                "tambah/cek uji logika murninya agar salah-fix ketahuan" % nama)


def cek_hapusan_belum_tercatat(peringatan, diff):
    tercatat = muat_bekas_hapusan()
    for nama in sorted(set(nama_metode_hilang(diff))):
        if nama in tercatat or masih_didefinisikan(nama):
            continue
        peringatan.append(
            "Metode %s() dihapus tapi belum di tools/bekas-hapusan.txt — "
            "tambahkan agar push berikut tetap terjaga" % nama)


def cek_locale_baru(gagal, diff):
    for _, isi in tambah_kode(diff):
        if re.search(r"\.to(Lower|Upper)Case\(\)", isi):
            gagal.append("Baris baru toLower/UpperCase() tanpa Locale — bug locale "
                         "Turki + alokasi sia-sia; pakai varian Locale: %s"
                         % isi.strip()[:80])
        if re.search(r"\.(lowercase|uppercase)\(\)", isi):
            gagal.append("Baris baru lowercase()/uppercase() tanpa Locale — bug locale "
                         "Turki; pakai varian Locale.ROOT: %s"
                         % isi.strip()[:80])


def argumen_format(isi):
    # Paritas format yang sadar urutan (issue #1082): perbandingan
    # multiset terurut lama membuang urutan, padahal untuk
    # specifier TANPA indeks urutan adalah satu-satunya pengikat
    # ke argumen pemanggil — ID yang menukar %s/%d lolos gerbang
    # tetapi crash IllegalFormatConversionException di perangkat.
    # Bentuk kanonik: bila SEMUA specifier (selain %% literal)
    # berindeks eksplisit, urutan bebas sah -> bandingkan peta
    # indeks->tipe; bila ada yang tanpa indeks, bandingkan
    # urutan token persis seperti tertulis.
    # Token kini specifier UTUH (issue #1111): pola lama hanya
    # membaca huruf konversi sesudah indeks opsional, jadi
    # specifier berflag/lebar (%5s vs %5d) dan pakai-ulang
    # (%<s vs %<d) gugur dari daftar dan beda tipenya lolos.
    # Specifier yang memakai %< (pakai ulang argumen
    # sebelumnya) memaksa mode urut karena maknanya posisional.
    # Entitas XML dinormalkan lebih dulu: %< di berkas sumber
    # tertulis %&lt; sehingga tanpa ini tak terlihat pola.
    isi = isi.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", '"').replace("&apos;", "'").replace("&amp;", "&")
    mentah = []
    for idx, flags, width, prec, t_konv, t_akhir, konv in re.findall(r'%(?:(\d+)\$)?([-#+ 0,(<]*)(\d+|\*)?(?:\.(\d+|\*))?(?:([tT])([a-zA-Z])|([a-zA-Z%]))', isi):
        mentah.append((idx, flags, width, prec, (t_konv + t_akhir) if t_konv else konv))
    # Akhiran konversi tanggal ikut dibaca (issue #1136):
    # untuk %t/%T, huruf penentu makna adalah AKHIRAN
    # sesudahnya (%tY tahun, %tm bulan) — pola yang berhenti
    # di t menilai semua akhiran sama.
    # Lebar/presisi dinamis (*) ikut dibaca (issue #1129):
    # %*d dan %.*f mengonsumsi argumen int tambahan — pola
    # angka-saja membuat specifier-nya gugur seluruhnya
    # sehingga beda tipenya lolos seperti lubang #1111.
    # Konversi % TIDAK PERNAH argumen (issue #1112 + #1135):
    # %% polos maupun berlebar/berflag (%5%, %-%) sama-sama
    # persen LITERAL — membuang SEMUA token berkonversi %
    # dari daftar agar terjemahan yang menulis "persen"
    # sebagai kata atau %% polos tidak digagalkan palsu.
    # %n juga bukan argumen (issue #1130): newline khusus
    # platform yang tidak mengonsumsi argumen apa pun —
    # bentuk berindeks tak sah dan tetap dibandingkan.
    token = [(idx, flags, width, prec, conv) for idx, flags, width, prec, conv in mentah
             if conv != "%"
             and not (conv == "n" and not idx and not width and not prec)]
    nyata = token
    if nyata and all(t[0] for t in nyata) and not any("<" in t[1] for t in nyata):
        return ("indeks", tuple(sorted((int(t[0]), t[1], t[2], t[3], t[4]) for t in nyata)))
    return ("urut", tuple(token))


def cek_paritas_resource(gagal):
    """Argumen format string Indonesia wajib sama dengan Inggris (cegah crash)."""
    jalur = JALUR_STRINGS_WAJIB
    isi = isi_strings_wajib(gagal)
    if any(teks is None for teks in isi.values()):
        return
    pola = re.compile(r'<string(?:\s[^>]*?)?\bname\s*=\s*["\x27]([^"\x27]+)["\x27][^>]*>(.*?)</string>', re.S)
    inggris = dict(pola.findall(kupas_komentar_xml(isi[jalur[0]])))
    indonesia = dict(pola.findall(kupas_komentar_xml(isi[jalur[1]])))
    for nama, teks in sorted(indonesia.items()):
        if nama in inggris and argumen_format(teks) != argumen_format(inggris[nama]):
            gagal.append("Argumen format beda di values-in: " + nama)

    # Pasangan berkas values LAIN ikut diperiksa (issue #1307):
    # pola lama hanya membaca strings.xml, jadi string
    # ber-format di berkas values lain (mis. ekstra.xml) buta
    # paritas dan beda %d vs %s di sana lolos — crash
    # IllegalFormatConversionException menunggu di perangkat.
    dir_en = os.path.join(AKAR, "app/src/main/res/values")
    dir_in = os.path.join(AKAR, "app/src/main/res/values-in")
    try:
        berkas_in = set(os.listdir(dir_in))
    except OSError:
        berkas_in = set()
    try:
        berkas_en = set(os.listdir(dir_en))
    except OSError:
        berkas_en = set()
    for nama_berkas in sorted(berkas_en & berkas_in):
        if nama_berkas == "strings.xml" or not nama_berkas.endswith(".xml"):
            continue
        teks_en = baca_teks(os.path.join("app/src/main/res/values", nama_berkas))
        teks_in = baca_teks(os.path.join("app/src/main/res/values-in", nama_berkas))
        if not teks_en or not teks_in:
            continue
        peta_en = dict(pola.findall(kupas_komentar_xml(teks_en)))
        peta_in = dict(pola.findall(kupas_komentar_xml(teks_in)))
        for nama, teks in sorted(peta_in.items()):
            if nama in peta_en and argumen_format(teks) != argumen_format(peta_en[nama]):
                gagal.append("Argumen format beda di values-in/%s: %s" % (nama_berkas, nama))
    # Argumen format di dalam <item> plurals/string-array juga wajib
    # paritas (issue #1045): cek di atas hanya melihat <string>, jadi
    # plurals "%d files" vs "%s berkas" lolos — padahal kelas crash
    # formatnya sama persis. Plurals dibandingkan per quantity,
    # string-array per indeks item.
    dasar_res = os.path.join(AKAR, "app/src/main/res")
    pola_plurals = re.compile(r'<plurals(?:\s[^>]*?)?\bname\s*=\s*["\x27]([^"\x27]+)["\x27][^>]*>(.*?)</plurals>', re.S)
    pola_item_q = re.compile(r'<item\s+quantity=["\x27]([^"\x27]+)["\x27][^>]*>(.*?)</item>', re.S)
    pola_array = re.compile(r'<string-array(?:\s[^>]*?)?\bname\s*=\s*["\x27]([^"\x27]+)["\x27][^>]*>(.*?)</string-array>', re.S)
    pola_item = re.compile(r'<item(?:\s[^>]*)?>(.*?)</item>', re.S)

    def peta_item(dirpath):
        plurals, arrays = {}, {}
        if not os.path.isdir(dirpath):
            return plurals, arrays
        for nama_berkas in sorted(os.listdir(dirpath)):
            if not nama_berkas.endswith(".xml"):
                continue
            isi_b = baca(os.path.relpath(os.path.join(dirpath, nama_berkas), AKAR))
            if isi_b is None:
                continue
            bersih_b = kupas_komentar_xml(isi_b)
            for nama, badan in pola_plurals.findall(bersih_b):
                plurals.setdefault(nama, {}).update(dict(pola_item_q.findall(badan)))
            for nama, badan in pola_array.findall(bersih_b):
                arrays.setdefault(nama, []).extend(pola_item.findall(badan))
        return plurals, arrays

    pl_en, ar_en = peta_item(os.path.join(dasar_res, "values"))
    pl_id, ar_id = peta_item(os.path.join(dasar_res, "values-in"))
    for nama, per_q in sorted(pl_id.items()):
        if nama not in pl_en:
            continue
        for q, teks in sorted(per_q.items()):
            if q in pl_en[nama] and argumen_format(teks) != argumen_format(pl_en[nama][q]):
                gagal.append("Argumen format plurals beda di values-in: %s (%s)" % (nama, q))
    for nama, daftar in sorted(ar_id.items()):
        if nama not in ar_en:
            continue
        for i, teks in enumerate(daftar):
            if i < len(ar_en[nama]) and argumen_format(teks) != argumen_format(ar_en[nama][i]):
                gagal.append("Argumen format string-array beda di values-in: %s (item %d)" % (nama, i))


def cek_plurals(gagal):
    """Kuantitas plurals wajib sah CLDR: selalu ada 'other', tak melebihi
    acuan values/, dan khusus values-in hanya 'other' (CLDR bahasa 'in'
    hanya mengenal other — quantity lain = galat lint UnusedQuantity)."""
    dasar_res = os.path.join(AKAR, "app/src/main/res")
    if not os.path.isdir(dasar_res):
        return
    pola = re.compile(r'<plurals(?:\s[^>]*?)?\bname\s*=\s*["\x27]([^"\x27]+)["\x27][^>]*>(.*?)</plurals>', re.S)
    pola_item = re.compile(r'<item\s+quantity=["\x27]([^"\x27]+)["\x27]')

    def peta_plurals(dirpath):
        peta = {}
        for nama_berkas in sorted(os.listdir(dirpath)):
            if not nama_berkas.endswith(".xml"):
                continue
            isi = baca(os.path.relpath(os.path.join(dirpath, nama_berkas), AKAR))
            if isi is None:
                continue
            for nama, badan in pola.findall(kupas_komentar_xml(isi)):
                peta[nama] = set(pola_item.findall(badan))
        return peta

    acuan = peta_plurals(os.path.join(dasar_res, "values"))
    for nama, kuantitas in sorted(acuan.items()):
        if "other" not in kuantitas:
            gagal.append("Plurals %s di values tanpa quantity 'other'" % nama)
    for entri in sorted(os.listdir(dasar_res)):
        if not entri.startswith("values-"):
            continue
        dirpath = os.path.join(dasar_res, entri)
        if not os.path.isdir(dirpath):
            continue
        for nama, kuantitas in sorted(peta_plurals(dirpath).items()):
            if nama not in acuan:
                gagal.append("Plurals %s di %s tanpa acuan di values" % (nama, entri))
                continue
            if "other" not in kuantitas:
                gagal.append("Plurals %s di %s tanpa quantity 'other'" % (nama, entri))
            if entri == "values-in" and kuantitas != {"other"}:
                gagal.append("Plurals %s di values-in memakai quantity selain 'other' (CLDR 'in' hanya other): %s" % (nama, ", ".join(sorted(kuantitas - {"other"}))))
            elif kuantitas - acuan[nama]:
                gagal.append("Plurals %s di %s memakai quantity di luar acuan values: %s" % (nama, entri, ", ".join(sorted(kuantitas - acuan[nama]))))


def cek_ikon_launcher(gagal):
    """Ikon adaptive (mipmap-anydpi-v26) wajib punya cadangan non-v26 bila minSdk < 26."""
    m = re.search(r"minSdk\s+(\d+)", baca_teks("app/build.gradle"))
    if not m or int(m.group(1)) >= 26:
        return
    akar_res = os.path.join(AKAR, "app", "src", "main", "res")
    try:
        folder = sorted(os.listdir(akar_res))
    except OSError:
        return
    cadangan = [f for f in folder if f.startswith("mipmap") and "-v" not in f]
    if not cadangan:
        gagal.append("Tak ada folder mipmap tanpa kualifikasi versi padahal "
                     "minSdk < 26 (ikon launcher kosong di API 24-25)")
        return
    v26 = os.path.join(akar_res, "mipmap-anydpi-v26")
    if not os.path.isdir(v26):
        return
    for nama in sorted(os.listdir(v26)):
        if not nama.endswith(".xml"):
            continue
        if not any(os.path.exists(os.path.join(akar_res, f, nama))
                   for f in cadangan):
            gagal.append(
                "Ikon %s hanya ada di mipmap-anydpi-v26 padahal minSdk < 26 "
                "(tambahkan cadangan di %s)" % (nama, cadangan[0]))


def cek_import_mati(gagal):
    """Import Kotlin/Java yang namanya tak dipakai di kode (GAGAL)."""
    for path in jalan_repo("app", ext=EKSTENSI_KODE):
        bersih = kupas_kode(baca_teks(path), simpan_interpolasi=True, bersarang=not path.endswith(".java"))
        tanpa = re.sub(r"^\s*import\s+.*$", "", bersih, flags=re.M)
        for m in re.finditer(r"^\s*import\s+([\w.*]+)(\s+as\s+(\w+))?\s*;?\s*$",
                             bersih, flags=re.M):
            jalan_imp = m.group(1)
            if "*" in jalan_imp:
                continue  # wildcard tak bisa dipastikan
            nama = m.group(3) if m.group(3) else jalan_imp.split(".")[-1]
            if not re.search(r"\b%s\b" % re.escape(nama), tanpa):
                gagal.append("Import mati di %s: %s" % (path, jalan_imp))


def disebut_di_uji(nama):
    pola = re.compile(r"\b%s\s*\(" % re.escape(nama))
    for path in jalan_repo(os.path.join("app", "src", "test")):
        # Komentar dikupas dulu (issue #1287, keluarga
        # #1218/#1254): sebutan di komentar berkas uji —
        # "// ubahSaya109() akan diuji nanti" — bukan cakupan
        # uji, tetapi pola mentah memvonisnya sudah teruji dan
        # peringatan cakupan mati.
        bersih = _kupas_komentar_kode(baca_teks(path), bersarang=not path.endswith(".java"))
        if pola.search(bersih):
            return True
    return False


def cek_uji_baru(peringatan, diff):
    pola = re.compile(
        r"\s*(?:public\s+|private\s+|protected\s+)?(?:static\s+|fun\s+)?[\w<>\[\].,? ]+\s+(\w+)\s*\(")
    sudah = set()
    for _, baris in tambah_kode(diff):
        cocok = pola.match(baris)
        if cocok and cocok.group(1) not in KATA_KUNCI:
            nama = cocok.group(1)
            if nama not in sudah:
                sudah.add(nama)
                if not disebut_di_uji(nama):
                    peringatan.append(
                        "Metode baru %s() tanpa jejak di app/src/test "
                        "(aturan: uji logika murni baru)" % nama)


def cek_pola_berisiko(peringatan, diff, rentang=""):
    for _, isi in tambah_kode(diff):
        if "System.out.println" in isi:
            peringatan.append("System.out.println di diff (pakai Log/Toast): " + isi.strip()[:80])
        if ".printStackTrace()" in isi:
            peringatan.append("printStackTrace di diff (telan/abaikan eksplisit): " + isi.strip()[:80])
    cek = ""
    try:
        perintah = ["git", "diff", "--check"]
        if rentang:
            perintah.append(rentang)
        hasil = subprocess.run(perintah, cwd=AKAR, capture_output=True, text=True, timeout=60)
        cek = (hasil.stdout or "").strip()
        if not rentang:
            hasil_cached = subprocess.run(
                ["git", "diff", "--cached", "--check"], cwd=AKAR,
                capture_output=True, text=True, timeout=60)
            lain = (hasil_cached.stdout or "").strip()
            if lain:
                cek = (cek + "\n" + lain).strip()
    except Exception:
        cek = ""
    if cek:
        peringatan.append("Rapikan spasi diff --check: " + cek.splitlines()[0][:100])


def utama():
    parser = argparse.ArgumentParser(description="Verifikasi path yang baru dikerjakan.")
    parser.add_argument("jalur", nargs="*", help="berkas spesifik (opsional)")
    parser.add_argument("--semua", action="store_true", help="cek seluruh repo")
    parser.add_argument("--rentang", default="", help="rentang diff A...B (dipakai CI)")
    args = parser.parse_args()

    gagal = []
    peringatan = []
    diff = ""
    rentang_lokal = ""
    rentang_isi = ""
    if args.jalur:
        # Jalur yang salah ketik tak boleh hilang diam-diam menjadi
        # "LOLOS (0 berkas diperiksa)" (issue #681).
        for j in args.jalur:
            if not os.path.exists(os.path.join(AKAR, j)):
                gagal.append("Jalur tidak ditemukan: " + j)
        berubah = [j for j in args.jalur if os.path.exists(os.path.join(AKAR, j))]
        diff = jalan("git", "diff", "--", *berubah) or ""
    elif args.rentang:
        daftar = daftar_berkas_berubah(args.rentang)
        if daftar is None:
            # Ekstrak ujung dengan pemisah yang sama seperti awal
            # (issue #871): split("...") pada rentang dua titik
            # mengembalikan seluruh string sebagai "ujung", git show
            # gagal, diff kosong, dan commit bertoken LOLOS.
            ujung = re.split(r"\.\.+", args.rentang)[-1] or "HEAD"
            if not ujung or set(ujung) == {"0"}:
                ujung = "HEAD"
            awal = re.split(r"\.\.+", args.rentang, maxsplit=1)[0]
            if awal and set(awal) != {"0"} and jalan("git", "rev-parse", "--verify", "--quiet", awal + "^{commit}") is None:
                gagal.append("Rentang diff tak valid: %s (revisi %s tidak ditemukan)." % (args.rentang, awal))
                berubah = []
                diff = ""
            else:
                print("info: rentang tak valid (push pertama?) — cek diff %s." % ujung)
                berubah = [p for p in jalan_repo("app") if not p.endswith(".md")]
                diff = jalan("git", "show", "--format=", "--patch", ujung) or ""
        else:
            berubah = daftar
            rentang_lokal = args.rentang
            a, b = re.split(r"\.\.+", args.rentang, maxsplit=1)
            pemisah = "..." if "..." in args.rentang else ".."
            diff = jalan("git", "diff", a + pemisah + b) or ""
            cek_secret_riwayat(gagal, args.rentang)
            cek_secret_pesan(gagal, args.rentang)
    else:
        hulu = jalan("git", "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}")
        if hulu:
            rentang_lokal = hulu.strip() + "...HEAD"
            rentang_isi = hulu.strip() + "..HEAD"
            diff = jalan("git", "diff", rentang_lokal) or ""
            daftar = jalan("git", "diff", "--name-only", "-z", rentang_lokal) or ""
            berubah = pecah_nama(daftar)
        else:
            # Tanpa upstream (issue #869): fallback lama HEAD~1...HEAD
            # hanya melihat commit terakhir — isi dan pesan commit
            # akar (dan commit lama lain di repo tanpa hulu) tak
            # pernah terperiksa lokal. Kini seluruh riwayat lokal
            # diperiksa: diff dari commit akar + isi commit akar itu
            # sendiri, pesan dari semua commit.
            akar = (jalan("git", "rev-list", "--max-parents=0", "HEAD") or "").splitlines()
            akar_sha = akar[0].strip() if akar else ""
            rentang_lokal = "HEAD"
            rentang_isi = "HEAD"
            if akar_sha:
                diff = (jalan("git", "show", "--format=", "--patch", akar_sha) or "") + \
                    (jalan("git", "diff", akar_sha + "...HEAD") or "")
                berubah = pecah_nama(jalan("git", "show", "--format=", "--name-only", "-z", akar_sha))
                berubah += pecah_nama(jalan("git", "diff", "--name-only", "-z", akar_sha + "...HEAD"))
            else:
                diff = ""
                berubah = []
        # Isi pohon tiap commit lokal ikut dipindai (issue #894):
        # sebelumnya pemindaian per-commit ini hanya jalan di mode
        # --rentang (CI), sehingga token yang ditambah lalu dihapus
        # di commit tengah riwayat lokal LOLOS mode bawaan — diff
        # bersih tak memuatnya. Rentang isi memakai dua titik agar
        # commit sisi hulu yang bukan bagian cabang ini tak ikut.
        if rentang_isi:
            cek_secret_riwayat(gagal, rentang_isi)
        cek_secret_pesan(gagal, rentang_lokal)
        kotor = jalan("git", "status", "--porcelain", "-z") or ""
        # Format -z: tiap entri "XY <path>"; untuk rename urutan
        # dibalik git menjadi "R  <baru>" lalu entri berikutnya
        # path asal polos — keduanya ikut diperiksa.
        token_kotor = pecah_nama(kotor)
        i_kotor = 0
        while i_kotor < len(token_kotor):
            entri = token_kotor[i_kotor]
            i_kotor += 1
            if len(entri) < 4:
                continue
            nama = entri[3:]
            if nama and nama not in berubah:
                berubah.append(nama)
            if ("R" in entri[:2] or "C" in entri[:2]) and i_kotor < len(token_kotor):
                asal = token_kotor[i_kotor]
                i_kotor += 1
                if asal and asal not in berubah:
                    berubah.append(asal)
        # Diff kerja yang belum commit ikut diperiksa agar hapusan salah
        # tertangkap sebelum commit, bukan sesudah push.
        diff += (jalan("git", "diff") or "")
        diff += jalan("git", "diff", "--cached") or ""
    berubah += [b for b in nama_tak_lacak() if b not in berubah]
    if args.semua:
        berubah = [p for p in jalan_repo("app")]
        # Repo dangkal (issue #837): pindai pesan commit + nama
        # riwayat hanya melihat commit yang terunduh lokal. Tanpa
        # peringatan ini LOLOS terasa seperti seluruh riwayat sudah
        # diperiksa, padahal cakupannya menyempit diam-diam.
        if (jalan("git", "rev-parse", "--is-shallow-repository") or "").strip() == "true":
            peringatan.append(
                "Repo ini clone DANGKAL (shallow): pindai pesan commit "
                "dan nama riwayat --semua hanya mencakup commit lokal "
                "yang terunduh, bukan seluruh riwayat — gunakan clone "
                "penuh (fetch-depth 0) untuk vonis forensik.")
        cek_secret_pesan_semua(gagal)
        # Forensik penuh ikut memindai isi pohon tiap commit (issue
        # #894): pesan semua commit saja tak menangkap token yang
        # hidup di isi commit tengah lalu terhapus di ujung.
        cek_secret_riwayat(gagal, "HEAD")

    ada_uji = os.path.isdir(UJI)
    cek_xml(gagal)
    cek_acuan_yatim(gagal)
    cek_manifest(gagal)
    cek_duplikat_definisi(gagal)
    cek_builder_legacy(gagal)
    cek_versi(gagal, peringatan)
    cek_sinkron_terjemah(gagal)
    cek_import_mati(gagal)
    cek_paritas_resource(gagal)
    cek_plurals(gagal)
    cek_ikon_launcher(gagal)
    if args.semua:
        # Mode audit penuh: aturan berkas terlarang harus melihat SELURUH
        # berkas terlacak, bukan hanya kode di app/ (issue #679).
        terlacak = pecah_nama(jalan("git", "ls-files", "-z"))
        cek_aturan_repo(gagal, terlacak)
    else:
        cek_aturan_repo(gagal, berubah)
        cek_ukuran_pohon(gagal, set(berubah))
    if args.semua:
        cek_secret_pohon(gagal)
        nama_pindai = pecah_nama(jalan("git", "ls-files", "-z"))
        nama_pindai += [p for p in nama_riwayat("HEAD")
                        if p not in nama_pindai]
        # Berkas tak terlacak ikut dipindai namanya (issue #863):
        # mode default melihatnya lewat daftar berubah, mode --semua
        # sebelumnya tidak — vonis forensik LOLOS atas berkas bernama
        # token yang belum pernah ter-commit.
        nama_pindai += [p for p in nama_tak_lacak()
                        if p not in nama_pindai]
        # Nama berkas ignored ikut dipindai (issue #864).
        nama_pindai += [p for p in nama_terabaikan()
                        if p not in nama_pindai and
                        not dir_sampah_sungguhan(p.split("/"))]
        cek_secret_nama(gagal, nama_pindai)
    else:
        cek_secret(gagal, diff)
        nama_pindai = list(berubah)
        if rentang_lokal:
            nama_pindai += [p for p in nama_riwayat(rentang_lokal)
                            if p not in nama_pindai]
        # Nama berkas ignored ikut dipindai (issue #864): isinya
        # sudah dipindai cek_secret_terabaikan, namanya belum.
        nama_pindai += [p for p in nama_terabaikan()
                        if p not in nama_pindai and
                        not dir_sampah_sungguhan(p.split("/"))]
        cek_secret_nama(gagal, nama_pindai)
    cek_secret_tak_lacak(gagal)
    cek_secret_terabaikan(gagal, peringatan)
    cek_kurung_kode(gagal, berubah)
    hapus = berkas_dihapus(args.rentang)
    if not args.rentang:
        # -z + tokenisasi posisional seperti berkas_dihapus
        # (issue #1157): uraian per baris tanpa -z mematahkan
        # nama non-ASCII terkuotasi sehingga hapusan staged
        # berkas beraksen yang masih dirujuk lolos vonis.
        keluar_staged = jalan("git", "diff", "--cached", "--name-status", "-z") or ""
        hapus += _ambil_terhapus(keluar_staged)
        hapus += _ambil_rename_berisiko(keluar_staged, "HEAD")
    cek_berkas_kode_dihapus(gagal, hapus)
    if not args.semua:
        cek_pengingat_changelog(peringatan, berubah)
    if diff:
        cek_panggil_bekas_hapusan(gagal, diff)
        cek_locale_baru(gagal, diff)
        cek_metode_hilang(gagal, peringatan, diff, hapus)
        cek_hapusan_belum_tercatat(peringatan, diff)
        if ada_uji:
            cek_uji_baru(peringatan, diff)
            cek_uji_ubah(peringatan, diff)
        cek_pola_berisiko(peringatan, diff, args.rentang)
        cek_pola_boros(peringatan, diff)
        cek_izin_baru(peringatan, diff)
        cek_definisi_baru(peringatan, diff)

    for w in peringatan:
        print("PERINGATAN: " + w)
    if gagal:
        for g in gagal:
            print("GAGAL: " + g)
        print("Hasil: GAGAL (%d) — perbaiki sebelum push." % len(gagal))
        return 1
    print("Hasil: LOLOS (%d berkas diperiksa%s)." % (
        len(berubah), (", %d peringatan" % len(peringatan)) if peringatan else ""))
    return 0


if __name__ == "__main__":
    sys.exit(utama())
