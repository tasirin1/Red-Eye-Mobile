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


def _nama_anggota_arsip(data):
    # Cerminan persis nama_anggota_arsip di
    # tools/cek-token.py (issue #1827).
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


def _dekode(data):
    # Cermin dekode_berkas di tools/cek-token.py (issue #785): berkas
    # UTF-16 membawa BOM dan harus didekode sesuai BOM, kalau tidak
    # byte NUL antar karakter memutus pola token dan isi lain. Tanpa
    # BOM: tebak UTF-16 dari pola NUL (issue #817).
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
    nama_arsip = _nama_anggota_arsip(data)
    if nama_arsip:
        teks += "\n" + nama_arsip
    return teks


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


def _tanpa_komentar_xml(isi, pengganti):
    """Teks dengan komentar XML diganti `pengganti(teks_komentar)`,
    SADAR CDATA (issue #1345): '<!--' di dalam <![CDATA[ ... ]]>
    adalah ISI, bukan pembuka komentar — regex telanjang
    membuka 'komentar hantu' di sana lalu menelan isi sungguhan
    sampai '-->' berikutnya, yang bisa berada jauh sesudah
    CDATA berakhir. Pemindai satu lintasan ini memberi tiap
    kawasan ke pembuka yang lebih dulu muncul (komentar atau
    CDATA) sampai penutup kawasan itu sendiri; isi CDATA
    disalin utuh, komentar tak tertutup dibiarkan apa adanya
    (sama seperti regex yang gagal mencocokkan)."""
    out = []
    i = 0
    n = len(isi)
    while i < n:
        j_komen = isi.find("<!--", i)
        j_cdata = isi.find("<![CDATA[", i)
        if j_cdata >= 0 and (j_komen < 0 or j_cdata < j_komen):
            akhir = isi.find("]]>", j_cdata + 9)
            if akhir < 0:
                out.append(isi[i:])
                i = n
            else:
                out.append(isi[i:akhir + 3])
                i = akhir + 3
        elif j_komen >= 0:
            akhir = isi.find("-->", j_komen + 4)
            if akhir < 0:
                out.append(isi[i:])
                i = n
            else:
                out.append(isi[i:j_komen])
                out.append(pengganti(isi[j_komen:akhir + 3]))
                i = akhir + 3
        else:
            out.append(isi[i:])
            i = n
    return "".join(out)


def kupas_komentar_xml(isi):
    return _tanpa_komentar_xml(isi, lambda _teks: "")


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


# Berkas yang gagal dibaca saat menghimpun definisi/
# acuan (issue #1504) — diisi _isi di dalam
# himpun_definisi, dikosongkan di awalnya, dibaca
# cek_acuan_yatim.
BACA_GAGAL = []


def himpun_definisi():
    """Kembalikan (definisi, acuan): nama sumber daya terdefinisi vs terpakai."""
    definisi = {}
    acuan = {}
    BACA_GAGAL.clear()
    # Mode rentang menilai pohon ujung REF_POHON (issue
    # #1490): dulu himpunan ini selalu membaca berkas
    # KERJA — pemeriksaan rentang dari pohon kotor bisa
    # salah vonis dua arah. _jalan_pohon/_baca_pohon jatuh
    # ke berkas kerja bila REF_POHON tak dipasang, dan
    # _baca_pohon jatuh ke baca() yang sama dengan
    # baca_teks, jadi mode bawaan tak berubah.
    _jalan = _jalan_pohon

    def _isi(path):
        isi = _baca_pohon(path)
        if isi is None:
            # Kegagalan baca dari pohon tak boleh ditelan
            # diam-diam (issue #1504): himpunan sebagian
            # melahirkan vonis yatim palsu yang tak bisa
            # dibedakan dari vonis sah. Catat; pemanggil
            # (cek_acuan_yatim) mengangkatnya tersendiri:
            # GAGAL di mode rentang, PERINGATAN di mode
            # bawaan (issue #1518 — rumusan awal 'mode
            # bawaan tak terpengaruh' keliru: pengangkatannya
            # memang berlaku di semua mode).
            BACA_GAGAL.append(path)
            return ""
        return isi
    # Titik pada nama style (Theme.A.B) menjadi garis bawah di R (aturan aapt).
    def rapikan(jenis, nama):
        # Styleable ikut dirapikan (issue #1421): nama
        # declare-styleable bertitik (Uji.Titik) dirujuk kode
        # sebagai R.styleable.Uji_Titik; tanpa perapian di
        # kedua sisi, definisinya yatim palsu.
        return nama.replace(".", "_") if jenis in ("style", "styleable") else nama

    pola_def = re.compile(r'<(style|string|color|dimen|plurals|bool|attr|integer|fraction|id)(?:\s[^>]*?)?\bname\s*=\s*["\x27]([^"\x27]+)["\x27]')
    pola_array = re.compile(r'<(string-array|integer-array|array)(?:\s[^>]*?)?\bname\s*=\s*["\x27]([^"\x27]+)["\x27]')
    pola_item_nt = re.compile(r'<item\s[^>]*?\bname\s*=\s*["\x27]([^"\x27]+)["\x27][^>]*?\btype\s*=\s*["\x27]([^"\x27]+)["\x27]')
    pola_item_tn = re.compile(r'<item\s[^>]*?\btype\s*=\s*["\x27]([^"\x27]+)["\x27][^>]*?\bname\s*=\s*["\x27]([^"\x27]+)["\x27]')

    def tambah(kunci, nama):
        definisi.setdefault(kunci, set()).add(rapikan(kunci, nama))

    akar_res = os.path.join(MAIN, "res")
    for path in _jalan(os.path.join("app", "src", "main", "res"), ext=(".xml",)):
        isi = kupas_komentar_xml(_isi(path))
        for jenis, nama in pola_def.findall(isi):
            tambah(jenis, nama)
        for jenis, nama in pola_array.findall(isi):
            tambah("array", nama)
        # Definisi lewat elemen generik <item name type>
        # (issue #1366): AAPT mengizinkannya untuk sumber
        # daya values; kedua urutan atribut dikenali. Item
        # isi <style>/<plurals> tak punya pasangan name+type
        # sehingga tak ikut tertangkap.
        # Nama <declare-styleable> + turunannya (issue
        # #1383): AAPT menerbitkan R.styleable.<Nama> dan
        # R.styleable.<Nama>_<attr> untuk tiap attr bersarang;
        # keduanya didaftarkan agar acuan sah tak yatim.
        # Tag pembuka pola blok menuntut karakter terakhir
        # bukan '/' (issue #1408): tanpa itu pola menambatkan
        # diri pada declare-styleable self-closing dan menelan
        # blok berikutnya — namanya hilang, turunannya menempel
        # ke nama self-closing.
        # Attr kerangka (android:textColor) diterbitkan dengan
        # titik dua menjadi garis bawah (issue #1390), dan
        # bentuk self-closing tanpa blok isi ikut terhimpun.
        for nama_sb in re.findall(
                r'<declare-styleable\s[^>]*?\bname\s*=\s*["\x27]([^"\x27]+)["\x27][^>]*/>',
                isi):
            tambah("styleable", nama_sb)
        for nama_sb, blok in re.findall(
                r'<declare-styleable\s[^>]*?\bname\s*=\s*["\x27]([^"\x27]+)["\x27](?:[^>]*[^/>])?>(.*?)</declare-styleable>',
                isi, re.S):
            tambah("styleable", nama_sb)
            for nama_attr in re.findall(
                    r'<attr\s[^>]*?\bname\s*=\s*["\x27]([^"\x27]+)["\x27]', blok):
                tambah("styleable", nama_sb + "_" + nama_attr.replace(":", "_"))
        for nama, jenis in pola_item_nt.findall(isi):
            tambah(jenis, nama)
        for jenis, nama in pola_item_tn.findall(isi):
            tambah(jenis, nama)
        for nama in re.findall(r'@\+id/([A-Za-z0-9_]+)', isi):
            tambah("id", nama)
    # Definisi berbasis berkas: semua subdir res kecuali values* (layout,
    # drawable, xml, ...). Kualifikasi (-night/-land) digabung ke tipe dasar.
    # Sadar pohon juga (issue #1503, sisa #1490): enumerasi
    # lewat _jalan seperti sisi values & acuan — dulu
    # os.listdir/os.walk pohon KERJA, jadi rentang dari
    # pohon kotor salah vonis dua arah di keluarga ini.
    for path in _jalan(os.path.join("app", "src", "main", "res"), ext=("",)):
        bagian = path.replace(os.sep, "/").split("/")
        if len(bagian) < 6:
            continue
        nama_dir = bagian[4]
        if nama_dir.startswith("values"):
            continue
        tipe = nama_dir.split("-")[0]
        tambah(tipe, os.path.splitext(bagian[-1])[0])

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
        r"@style/(Widget[._]|ThemeOverlay[._]|TextAppearance[._]|Animation[._]|ShapeAppearance[._]|ShapeAppearanceOverlay[._]"
        r"|Theme\.Material|Theme_Material|Theme\.AppCompat|Theme_AppCompat"
        r"|Theme\.Design|Theme_Design)[A-Za-z0-9_.]*")
    pemindai = [p for p in _jalan("app", ext=EKSTENSI_KODE + (".xml",))]
    pemindai.append("app/src/main/AndroidManifest.xml")
    for path in pemindai:
        isi = _isi(path)
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


# Awalan nama sumber daya publik pustaka Android yang lazim
# (issue #1384): definisinya memang hidup di pustaka
# (Material 3, AppCompat, Design), bukan di repo — acuan ke
# sana bukan yatim. pola_pustaka hanya menyisihkan sebagian
# @style/, jadi saringan ini bekerja di tingkat nama untuk
# semua jenis.
AWALAN_PUSTAKA = ("m3_", "mtrl_", "abc_", "design_", "androidx_")

# Varian garis-bawah dari keluarga pola_pustaka untuk acuan
# R.style.* DARI KODE (issue #1420): sisi XML disaring pola
# bertitik, sisi kode dulu hanya AWALAN_PUSTAKA — asimetri
# yang memvonis yatim style pustaka yang dirujuk kode.
AWALAN_STYLE_PUSTAKA = (
    "Widget_", "ThemeOverlay_", "TextAppearance_", "Animation_",
    "ShapeAppearance_", "ShapeAppearanceOverlay_", "Platform_",
    "Theme_Material", "Theme_AppCompat", "Theme_Design",
    # Awalan Theme_ umum (issue #1488): Theme_* dianggap
    # milik pustaka KECUALI tema aplikasi sendiri yang
    # dikecualikan GAYA_APLIKASI_SENDIRI (issue #1493 —
    # premis awal "tema aplikasi selalu Tema_" keliru:
    # tema utama aplikasi adalah Theme.ParentalMonitor).
    "Theme_",
)

# Segmen ruang-nama pustaka yang dikenal persis (issue
# #1449): penanda substring bebas membebaskan nama seperti
# Widget_DesignKu / Widget_TombolDesign, dan tiga awalan
# Theme_* memuat penandanya sendiri sehingga uji penanda
# di sana vakum. Kini penanda wajib tampil sebagai SEGMEN
# garis-bawah utuh dari himpunan dikenal (MaterialComponents
# & Material3 adalah segmen baku Material; sisanya persis).
SEGMEN_PUSTAKA_STYLE = frozenset({
    "Material", "Material3", "MaterialComponents",
    "MaterialExpressive", "AppCompat", "Design", "Mdc",
})


# Tema milik aplikasi sendiri (issue #1493): awalan
# selimut Theme_ dari #1488 ikut melemahkan vonis tema
# aplikasi (Theme.ParentalMonitor — tema utama, dirujuk
# manifest). Nama ini dikecualikan dari kelas pustaka
# di SEMUA uji awalan, jadi yatimnya kembali GAGAL.
GAYA_APLIKASI_SENDIRI = ("Theme_ParentalMonitor",)


def _berawalan_pustaka(nama):
    return (nama.startswith(AWALAN_STYLE_PUSTAKA)
            and not nama.startswith(GAYA_APLIKASI_SENDIRI))


def _bebas_yatim_style_kode(nama):
    if nama.startswith("Platform_"):
        return True
    if not _berawalan_pustaka(nama):
        return False
    return any(seg in SEGMEN_PUSTAKA_STYLE for seg in nama.split("_"))



def _sufiks_sisa(jumlah, batas=10):
    """Penanda sisa daftar laporan yang dipotong (issue
    #1489): daftar yang dibuang diam-diam melanggar asas
    lapor-utuh gerbang ini."""
    if jumlah > batas:
        return " (+%d lagi)" % (jumlah - batas)
    return ""

def cek_acuan_yatim(gagal, peringatan=None):
    definisi, acuan = himpun_definisi()
    if BACA_GAGAL:
        # Himpunan tak utuh (issue #1504): vonis dari data
        # sebagian tak bisa dipercaya — laporkan dengan
        # menyebut berkasnya, jangan biarkan tampil
        # sebagai yatim biasa. Angka utama = berkas UNIK
        # (issue #1517; dulu kemunculan mentah, jadi satu
        # berkas yang terbaca dua gelung tercetak '(2)').
        # Per mode (issue #1518): mode RENTANG menilai
        # pohon — himpunan tak utuh menggagalkan vonis,
        # jadi GAGAL. Mode BAWAAN menilai pohon kerja:
        # berkas tak terbaca sudah dilaporkan bernama oleh
        # cek berkas masing-masing (mis. cek_xml), jadi di
        # sini cukup PERINGATAN agar keadaan pohon nyata
        # (symlink putus) tak memblokir dua kali.
        unik = sorted(set(BACA_GAGAL))
        teks = ("Berkas gagal dibaca saat menghimpun acuan (%d): %s%s"
                % (len(unik), ", ".join(unik[:10]), _sufiks_sisa(len(unik))))
        if REF_POHON:
            gagal.append(teks + " — vonis yatim dari himpunan tak utuh tak bisa dipercaya")
        elif peringatan is not None:
            peringatan.append(teks)
    for jenis, dipakai in sorted(acuan.items()):
        ada = definisi.get(jenis, set())
        yatim = sorted(n for n in dipakai
                       if n not in ada and not n.startswith(AWALAN_PUSTAKA)
                       # Style berawalan pustaka tak divonis
                       # yatim di sini (issue #1468): yang
                       # segmennya dikenal dibebaskan
                       # _bebas_yatim_style_kode; yang tak
                       # dikenal turun jadi peringatan di bawah —
                       # acuan kode R.style.* toh dijamin
                       # kompiler menunjuk style yang ada.
                       and not (jenis == "style" and _berawalan_pustaka(n))
                       # Keluarga Base.* pustaka (issue #1402):
                       # Base.Theme.AppCompat dsb. adalah bentuk
                       # baku pewarisan tema AppCompat/Material;
                       # nama terrapikannya Base_Theme_... tak
                       # dijangkau pola_pustaka maupun awalan di
                       # atas (khusus jenis style).
                       and not (jenis == "style" and n.startswith("Base_"))
                       and not (jenis == "style" and _bebas_yatim_style_kode(n)))
        if yatim:
            gagal.append("Acuan %s yatim (dipakai tapi tak terdefinisi): %s%s"
                         % (jenis, ", ".join(yatim[:10]), _sufiks_sisa(len(yatim))))
        # Style berawalan pustaka ber-segmen TAK dikenal
        # (issue #1468): dulu ikut divonis GAGAL "yatim" —
        # bump dependensi ber-segmen baru + acuan kode sah
        # akan memblokir push dengan pesan yang menyesatkan
        # (mengarahkan ke definisi XML yang memang tak ada).
        # Kini PERINGATAN yang menamai obatnya.
        if jenis == "style" and peringatan is not None:
            tak_dikenal = sorted(
                n for n in dipakai
                if n not in definisi.get(jenis, set())
                and _berawalan_pustaka(n)
                and not n.startswith("Base_")
                and not _bebas_yatim_style_kode(n))
            for n in tak_dikenal[:10]:
                peringatan.append(
                    "Acuan style %s berawalan pustaka tetapi segmen "
                    "ruang-namanya belum dikenal — bila ini style "
                    "pustaka sah dari dependensi baru, tambahkan "
                    "segmennya ke SEGMEN_PUSTAKA_STYLE (bukan vonis "
                    "yatim)." % n)
            if len(tak_dikenal) > 10:
                peringatan.append(
                    "Dan %d acuan style ber-segmen tak dikenal "
                    "lainnya%s — daftar lengkap dinilai dari "
                    "SEGMEN_PUSTAKA_STYLE." % (len(tak_dikenal) - 10, ""))


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
                if c == '"' and teks[j] == "$" \
                        and teks[j + 1:j + 2] == "{":
                    # Interpolasi templat dilewati utuh
                    # (issue #1907): penanda komentar di
                    # dalam string bersarang templat adalah
                    # ISI string, bukan komentar — tanpa
                    # lompatan ini petik string bersarang
                    # menutup string luar lebih awal dan /*
                    # isinya membuka komentar hantu yang
                    # menelan kode asli dari keluaran kupas.
                    j = _lewati_interpolasi(teks, j)
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
    # activity-alias divalidasi juga (issue #1311): elemen ini
    # tak punya kelas sendiri — yang wajib ada adalah kelas
    # targetActivity-nya. Tanpa cek ini, alias menunjuk target
    # yang terhapus/keliru lolos gerbang (manifest hari ini
    # belum memuat alias; ceknya menyusul sebelum dibutuhkan).
    for el in pohon.getroot().iter("activity-alias"):
        alias = el.get("{http://schemas.android.com/apk/res/android}name", "")
        target = el.get("{http://schemas.android.com/apk/res/android}targetActivity", "")
        if not target:
            gagal.append("activity-alias tanpa targetActivity: %s" % (alias or "?"))
            continue
        rel_t = None
        if target.startswith("."):
            rel_t = target[1:].split(".")
        elif ns and (target == ns or target.startswith(ns + ".")):
            rel_t = target[len(ns) + 1:].split(".") if target != ns else []
        else:
            continue  # target kerangka / pustaka
        if not rel_t:
            continue
        dasar_t = os.path.join(MAIN, "java", *ns.split("."), *rel_t)
        if not (os.path.isfile(dasar_t + ".kt")
                or os.path.isfile(dasar_t + ".java")):
            gagal.append("activity-alias %s menunjuk targetActivity tanpa kelas: %s"
                         % (alias or "?", target))
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


def _ambil_rename_berisiko(out, ref_lama=None, ref_baru=None):
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
        if not _deklarasi_tingkat_atas(_baca_ref(ref_baru, baru), kelas, bersarang=not baru.endswith(".java")):
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
                return _ambil_terhapus(out) + _ambil_rename_berisiko(out, a, b)
    out = jalan("git", "diff", "--name-status", "-z")
    return _ambil_terhapus(out) + _ambil_rename_berisiko(out, "HEAD")


def disebut_kata(nama, lewat=()):
    pola = re.compile(r"\b%s\b" % re.escape(nama))
    temu = []
    for path in _jalan_pohon("app", ext=EKSTENSI_KODE + (".xml",)):
        if path in lewat:
            continue
        teks = _baca_pohon(path) or ""
        if path.endswith(EKSTENSI_KODE):
            # Komentar dikupas dulu (issue #1308, keluarga
            # #1254/#1218): sebutan nama kelas di komentar
            # bukan rujukan — tanpa kupasan, menghapus berkas
            # kelas ter-GAGAL-kan oleh baris komentar berkas
            # lain. _kupas_komentar_kode mempertahankan
            # newline sehingga nomor baris tetap tepat.
            teks = _kupas_komentar_kode(teks, bersarang=not path.endswith(".java"))
        elif path.endswith(".xml"):
            # Komentar XML ikut dikupas (issue #1329):
            # sebutan di dalam <!-- --> juga bukan rujukan.
            # Isi komentar diganti spasi dengan newline
            # dipertahankan agar nomor baris laporan tetap
            # tepat (kupas_komentar_xml bawaan membuang
            # rentang utuh termasuk newline-nya).
            teks = _tanpa_komentar_xml(
                teks,
                lambda t: "".join("\n" if c == "\n" else " " for c in t))
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


def _ada_di_pohon(path):
    """Uji keberadaan berkas untuk belahan rename (#1347):
    pada mode rentang keberadaan dinilai di pohon REF_POHON
    (issue #1362) — menguji ke berkas kerja membuat belahan
    salah begitu checkout tak lagi sama dengan pohon yang
    digambarkan diff (mis. memeriksa rentang lama dari
    checkout yang lebih baru). Mode lain: berkas kerja."""
    if REF_POHON:
        return jalan("git", "cat-file", "-e",
                     "%s:%s" % (REF_POHON, path)) is not None
    return os.path.isfile(os.path.join(AKAR, path))


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
        # Sisi-b dicari dari kemunculan TERAKHIR pemisahnya
        # (issue #1327): git tidak mengkuotasi path hanya
        # karena mengandung spasi, jadi membelah pada spasi
        # pertama merusak path ber-spasi (di sisi-a maupun
        # sisi-b) dan berkasnya terlewat senyap oleh semua
        # cek berbasis kepala diff. Sisi-b terkuotasi
        # didahulukan lewat pola spasi+kutip.
        if ' "b/' in sisa:
            token_b = '"b/' + sisa.split(' "b/', 1)[1]
        elif " b/" in sisa:
            # Belahan yang kedua sisinya SAMA didahulukan
            # (issue #1344): direktori yang sendirinya memuat
            # " b/" (mis. "uji b/") membuat belah pada
            # kemunculan TERAKHIR memotong path jadi nama
            # berkas polos, dan berkasnya terlewat senyap oleh
            # semua cek berkepala diff (termasuk penyemaian
            # #1326). Untuk diff non-rename sisi-a == sisi-b,
            # jadi belahan setara pertama itulah pemisahnya;
            # rename (kedua sisi memang beda) jatuh ke
            # heuristik kemunculan terakhir seperti sebelumnya.
            token_b = None
            mulai = 0
            while True:
                idx = sisa.find(" b/", mulai)
                if idx < 0:
                    break
                b_tok = sisa[idx + 1:]
                if b_tok.startswith("b/") and sisa[:idx].startswith("a/") and b_tok[2:] == sisa[:idx][2:]:
                    token_b = b_tok
                    break
                mulai = idx + 1
            if token_b is None:
                # Rename (issue #1347): kedua sisi memang beda,
                # jadi belahan setara tak pernah ada — dan
                # kemunculan terakhir memotong rename yang path
                # BARU-nya memuat " b/". Calon belahan diuji
                # terhadap keberadaan berkas di pohon kerja:
                # hasil rename ada di pohon baru, sedangkan
                # belahan dalam-direktori menghasilkan path
                # yang tak ada. Bila tak ada calon yang ada
                # (forensik rentang lama), heuristik lama.
                token_b = None
                mulai = 0
                while True:
                    idx = sisa.find(" b/", mulai)
                    if idx < 0:
                        break
                    b_tok = sisa[idx + 1:]
                    if _ada_di_pohon(b_tok[2:]):
                        token_b = b_tok
                        break
                    mulai = idx + 1
                if token_b is None:
                    token_b = "b/" + sisa.rsplit(" b/", 1)[1]
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
                if kutip == chr(34) and isi[j] == "$" \
                        and isi[j + 1:j + 2] == "{":
                    # Interpolasi bersarang di dalam string
                    # bersarang (issue #1906): lewati utuh
                    # secara rekursif — petik pembuka string
                    # yang lebih dalam bukan penutup string
                    # ini, dan membacanya begitu mengacaukan
                    # hitungan kurung pada templat 3 lapis.
                    j = _lewati_interpolasi(isi, j)
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
        # Isi dari pohon yang dinilai (issue #1385): pada mode
        # rentang _baca_pohon mengambil isi dari REF_POHON —
        # membaca berkas kerja menilai checkout saat ini,
        # bukan isi rentang (LOLOS/GAGAL palsu begitu keduanya
        # menyimpang). Sisa pembaca keluarga #1348/#1362.
        isi_mentah = _baca_pohon(path)
        if isi_mentah is None:
            continue  # berkas dihapus / tak ada di pohon: cek lain
        isi = kupas_kode(isi_mentah, bersarang=not path.endswith(".java"))
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
    # Nilai per ELEMEN pada gabungan baris tambah (issue
    # #1910): elemen multi-baris (nama atribut di baris
    # berikutnya — gaya bawaan formatter Android Studio),
    # petik tunggal, dan spasi sekitar = adalah bentuk
    # XML sah yang sebelumnya lolos tanpa pengingat
    # karena pencocokan menuntut tag + nama petik ganda
    # pada satu baris yang sama.
    # Hanya hunk AndroidManifest.xml yang dinilai: izin
    # hanya hidup di sana, dan membatasi domain juga
    # mencegah literal contoh izin di berkas lain
    # (termasuk uji gerbang ini sendiri) tertuduh.
    potongan = []
    di_manifest = False
    for baris in diff.splitlines():
        if baris.startswith("+++"):
            di_manifest = baris.rstrip().endswith("AndroidManifest.xml")
            continue
        if baris.startswith("diff --git"):
            di_manifest = False
            continue
        if di_manifest and baris.startswith("+"):
            potongan.append(baris[1:])
    gabungan = "\n".join(potongan)
    for cocok in re.finditer(
            r"<uses-permission(?:-sdk-23)?\b[^>]*?"
            r"android:name\s*=\s*[\"']([^\"']+)[\"']",
            gabungan):
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
            gagal.append("values-in kurang %s: %s%s" % (jenis, ", ".join(kurang[:10]), _sufiks_sisa(len(kurang))))
        if lebih:
            gagal.append("values-in berlebih %s: %s%s" % (jenis, ", ".join(lebih[:10]), _sufiks_sisa(len(lebih))))
    tak_terhitung = sorted(
        nama for nama in item_inggris.keys() & item_indonesia.keys()
        if item_inggris[nama] is None or item_indonesia[nama] is None)
    if tak_terhitung:
        print("PERINGATAN: paritas item tak bisa dinilai (anggaran "
              "pemindai cadangan habis) untuk: %s%s" % (", ".join(tak_terhitung[:10]), _sufiks_sisa(len(tak_terhitung))))
    beda_item = sorted(
        nama for nama in item_inggris.keys() & item_indonesia.keys()
        if item_inggris[nama] is not None and item_indonesia[nama] is not None
        and item_inggris[nama] != item_indonesia[nama])
    if beda_item:
        gagal.append("jumlah item string-array beda antar locale: %s%s" % (", ".join(
            "%s (%d vs %d)" % (n, item_inggris[n], item_indonesia[n])
            for n in beda_item[:10]), _sufiks_sisa(len(beda_item))))


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
        # Spasi/titik di ujung nama dibuang sebelum
        # pencocokan akhiran (issue #1640): "kunci.p12 "
        # adalah nama sah di git tetapi lolos semua
        # endswith ekstensi terlarang.
        rendah = path.lower().rstrip(" .")
        sudah_dilarang = False
        # Penghapusan berkas terlarang adalah REMEDIASI, bukan
        # pelanggaran (issue #1290): path yang tak ada lagi di
        # pohon kerja sedang KELUAR dari repo — memvonisnya
        # GAGAL menutup satu-satunya jalan membersihkan repo
        # tanpa --no-verify. Mode --semua tak terpengaruh:
        # daftarnya berkas terlacak yang memang ada.
        # Keberadaan dinilai di pohon yang digambarkan diff
        # (_ada_di_pohon, issue #1362): mode lain tetap pohon
        # kerja sehingga remediasi lokal #1290 tak berubah.
        masih_ada = _ada_di_pohon(path)
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

def _muat_mengandung_token_pesan():
    try:
        import importlib.util
        spec = importlib.util.spec_from_file_location(
            "cek_token", os.path.join(AKAR, "tools", "cek-token.py"))
        modul = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(modul)
        return modul.mengandung_token_pesan
    except Exception:
        return lambda teks: bool(teks) and bool(POLA_TOKEN_ASLI.search(teks))


mengandung_token_pesan = _muat_mengandung_token_pesan()


def _muat_padatkan():
    try:
        import importlib.util
        spec = importlib.util.spec_from_file_location(
            "cek_token", os.path.join(AKAR, "tools", "cek-token.py"))
        modul = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(modul)
        return modul._padatkan
    except Exception:
        return lambda teks: re.sub(r"[\s\"'\\]+", "", teks or "")


_padatkan = _muat_padatkan()


def _muat_penilai_fragmen():
    # Penilai fragmen pesan kanonik (issue #1742):
    # satu sumber di tools/cek-token.py agar gerbang
    # lokal dan vonis rentang tak bisa berbeda lagi
    # seperti yang melahirkan #1719.
    try:
        import importlib.util
        spec = importlib.util.spec_from_file_location(
            "cek_token", os.path.join(AKAR, "tools", "cek-token.py"))
        modul = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(modul)
        return modul.mengandung_fragmen_pesan
    except Exception:
        return None


_penilai_fragmen = _muat_penilai_fragmen()


def _konfirmasi_diff_berdiri(diff):
    """Konfirmasi konteks untuk tuduhan fragmen diff (issue #1626).

    Fragmen baris tambah tak memuat konteks penanda contoh
    (#1530) pada baris tak berubah di sekitarnya, jadi nilai
    kontrol yang dirotasi di dalam wilayah bertanda tertuduh
    di sini walau berkas penuhnya bersih — cek-cepat lokal
    sudah membebaskannya lewat konfirmasi isi staged penuh
    (#1620), dan mode rentang CI menghukumnya: dua gerbang,
    dua vonis untuk perubahan yang sama. Nilai ulang isi
    berkas-berkas berubah (pohon kerja = ujung rentang di
    CI) satu per satu dan gabungannya (pola #1625): tuduhan
    hanya berdiri bila ada isi yang tertuduh. Tak ada isi
    yang bisa dinilai sama sekali = tuduhan berdiri
    (gagal-tertutup)."""
    paths = []
    for baris in diff.splitlines():
        if baris.startswith("+++ "):
            token = _buka_kutip_git(baris[4:].strip())
            path = token[2:] if token.startswith("b/") else token
            if path and path != "/dev/null" and path not in paths:
                paths.append(path)
    dinilai = 0
    gabungan = []
    for path in paths:
        isi = baca(path)
        if isi is None:
            continue
        dinilai += 1
        if mengandung_token(isi):
            return True
        gabungan.append(isi)
    if dinilai == 0:
        return True
    return mengandung_token("\n".join(gabungan))


def cek_secret(gagal, diff):
    tambah = "\n".join(
        baris[1:] for baris in diff.splitlines()
        if baris.startswith("+") and not baris.startswith("+++"))
    if mengandung_token(tambah) and _konfirmasi_diff_berdiri(diff):
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


def _kandidat_rapat(rapat):
    """Kandidasi sapuan rapat atas isi yang newline-nya dibuang.

    Blob besar dinilai per jendela tumpang-tindih (issue
    #1675): pola run yang dicari panjangnya terbatas, jadi
    jendela 64 KiB dengan tumpang-tindih 64 aksara pasti
    memuat utuh setiap run yang menyeberangi batas jendela.
    """
    pola = r"[0-9]{6,}|[A-Za-z0-9+/=_-]{40,}"
    if len(rapat) <= 65536:
        return re.search(pola, rapat) is not None
    langkah = 65536 - 64
    for i in range(0, len(rapat), langkah):
        if re.search(pola, rapat[i:i + 65536]):
            return True
    return False


def _vonis_isi_besar(isi):
    """Vonis pemindai bersama atas isi blob sapuan rapat.

    Isi <=2 MiB dinilai utuh seperti sebelumnya; lebih
    besar dari itu dinilai per jendela 2 MiB bertumpang-
    tindih 8 KiB (issue #1675) agar blob raksasa tidak
    menahan sapuan — bentang token terfragmentasi yang
    muat di satu jendela tetap tertangkap utuh.
    """
    if len(isi) <= 2097152:
        return mengandung_token(isi)
    langkah = 2097152 - 8192
    for i in range(0, len(isi), langkah):
        if mengandung_token(isi[i:i + 2097152]):
            return True
    return False


def cek_secret_riwayat(gagal, rentang):
    """Pindai token di pohon TIAP commit dalam rentang (issue #750).

    Diff bersih rentang tidak memuat token yang ditambah lalu dihapus
    lagi di dalam rentang yang sama, padahal kedua commit ikut terkirim
    dan token itu hidup permanen di riwayat git. Pindai pohon tiap
    commit dalam rentang agar pola "sudah kuhapus kok" tetap tertangkap.
    """
    daftar_commit = jalan("git", "rev-list", rentang) or ""
    tip_rentang = daftar_commit.splitlines()[0].strip() if daftar_commit.strip() else ""
    for sha in daftar_commit.splitlines():
        sha = sha.strip()
        if not sha:
            continue
        # Format -z (issue #1627): tanpa itu git mengutip
        # nama non-ASCII gaya C dan path hasil parse tak
        # bisa dibuka git show — berkas bernama seperti
        # itu terlewat diam-diam dari kanal ini, melanggar
        # aturan #1138 bahwa semua daftar nama wajib -z.
        out = jalan("git", "grep", "-l", "-z", "-E",
                    r"[0-9]{6,}:[A-Za-z0-9_-]{35,}", sha) or ""
        for teks in pecah_nama(out):
            if teks.startswith("Binary file ") and teks.endswith(" matches"):
                path = teks[len("Binary file "):-len(" matches")]
            else:
                path = teks.split(":", 1)[1] if ":" in teks else teks
            # Konfirmasi pemindai bersama (issue #1603):
            # grep mentah sendirian menuduh contoh bertanda
            # (#1530) dan kutipan hash (#1529/#1543/#1568/
            # #1602) yang oleh permukaan lain dinyatakan
            # bersih — satu commit sah seperti itu membuat
            # gerbang riwayat merah permanen. Vonis kanal
            # ini wajib lolos penilaian yang sama seperti
            # kanal kandidat di bawah: ambil blob, dekode,
            # nilai mengandung_token. Tak terkonfirmasi =
            # bukan vonis; lanjut ke berkas/commit berikut.
            mentah = jalan_biner("git", "show", "%s:%s" % (sha, path))
            isi = _dekode(mentah) if mentah is not None else None
            if not mengandung_token(isi):
                continue
            if mentah is not None and b"\x00" in mentah:
                gagal.append("Kemungkinan token bot asli di berkas BINER %s "
                             "pada commit %s (riwayat rentang) — token tetap "
                             "hidup di riwayat walau sudah dihapus di pohon "
                             "akhir. Kredensial hanya via input manual "
                             "SetupActivity" % (path, sha[:8]))
            else:
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
        kandidat = jalan("git", "grep", "-a", "-l", "-z", "-E",
                         r"[0-9]{6,}|[A-Za-z0-9+/=_-]{40,}", sha) or ""
        for teks in pecah_nama(kandidat):
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
        # Fragmentasi halus di bawah KEDUA ambang per baris
        # (issue #1643): grep menilai per baris, jadi token
        # yang dipecah <=5 aksara/baris TERMASUK digit id-nya
        # menggugurkan alternatif digit >=6 maupun run >=40
        # sekaligus dan blob tak pernah jadi kandidat. Sapu
        # berkas yang berubah di commit ini: kandidasinya
        # diuji pada isi yang newline-nya dibuang — run yang
        # menyeberangi baris terlihat di sana. Kandidat bukan
        # vonis: vonis tetap mengandung_token atas isi ASLI
        # (pemadatan pemindai menyambung belahannya sendiri).
        # Blob besar TIDAK lagi dilewati (issue #1675):
        # kandidasi + vonisnya per jendela (lihat
        # _kandidat_rapat/_vonis_isi_besar).
        berubah_kandidat = jalan("git", "diff-tree", "--no-commit-id",
                                 "--name-only", "-z", "-r", "--root",
                                 "-m", sha) or ""
        for path in dict.fromkeys(pecah_nama(berubah_kandidat)):
            path = path.strip()
            if not path:
                continue
            mentah = jalan_biner("git", "show", "%s:%s" % (sha, path))
            if mentah is None:
                continue
            isi = _dekode(mentah)
            if isi is None:
                continue
            rapat = isi.replace("\r", "").replace("\n", "")
            if not _kandidat_rapat(rapat):
                continue
            if _vonis_isi_besar(isi):
                gagal.append("Kemungkinan token bot asli (base64/terbelah) "
                             "di %s pada commit %s (riwayat rentang) — "
                             "token tetap hidup di riwayat walau sudah "
                             "dihapus di pohon akhir. Kredensial hanya "
                             "via input manual SetupActivity"
                             % (path, sha[:8]))
                return
        # Nama berkas berpola token (issue #1697): saluran
        # isi tak pernah menilai NAMA — berkas bernama
        # token utuh (':' diganti '_', bentuk yang sah
        # untuk nama berkas) lolos walau isinya polos.
        # Nilai nama tiap berkas yang berubah pada commit
        # ini: bentuk tanpa '/', plus rekonstruksi
        # '_'->':' pada batas digitnya; vonis tetap
        # mengandung_token.
        nama_gab_list = []
        for path in dict.fromkeys(pecah_nama(berubah_kandidat)):
            nama = path.strip()
            if not nama:
                continue
            polos_nama = nama.replace("/", "")
            nama_gab_list.append(polos_nama)
            # Pra-saring murah dulu (pola cek-cepat): nama
            # tanpa deret digit panjang tak mungkin berpola
            # token — memanggil pemindai untuk tiap path
            # (mis. path sumber panjang yang sesudah '/'
            # dibuang menjadi run alfabet raksasa) membuat
            # sapuan rentang jauh lebih lambat.
            calon_nama = []
            # Varian tanpa ekstensi terakhir (issue
            # #1706): rekonstruksi di bawah berjangkar $
            # sehingga nama token yang diberi ekstensi
            # (mis. .txt/.bak) lolos tak dinilai; nilai
            # juga bentuk sesudah ekstensi terakhir
            # dibuang.
            varian_nama = [polos_nama]
            # Kupas ekstensi BERTINGKAT (issue #1734):
            # satu tingkat saja (#1706) menyisakan nama
            # bersufiks ganda (.txt.bak) tak terjangkau
            # rekonstruksi — kupas sampai 3 tingkat.
            _sisa_nama = polos_nama
            for _ in range(3):
                if "." not in _sisa_nama:
                    break
                _sisa_nama = _sisa_nama.rsplit(".", 1)[0]
                if not _sisa_nama:
                    break
                varian_nama.append(_sisa_nama)
            for varian in varian_nama:
                if re.search(r"[0-9]{6,}:[A-Za-z0-9_-]{35,}", varian):
                    calon_nama.append(varian)
                cocok_nama = re.match(r"^(.*?)([0-9]{6,})_([A-Za-z0-9_-]{35,})$", varian)
                if cocok_nama:
                    calon_nama.append("%s%s:%s" % (cocok_nama.group(1), cocok_nama.group(2), cocok_nama.group(3)))
                # Pemisah '.' sama lazimnya di nama berkas
                # (issue #1714): rekonstruksi '_' saja
                # membuat bentuk titik lolos.
                cocok_titik = re.match(r"^(.*?)([0-9]{6,})\.([A-Za-z0-9_-]{35,})$", varian)
                if cocok_titik:
                    calon_nama.append("%s%s:%s" % (cocok_titik.group(1), cocok_titik.group(2), cocok_titik.group(3)))
            if any(mengandung_token(c) for c in calon_nama):
                gagal.append("Kemungkinan token bot asli pada NAMA berkas %s "
                             "pada commit %s (riwayat rentang) — token tetap "
                             "hidup di riwayat walau sudah dihapus di pohon "
                             "akhir. Kredensial hanya via input manual "
                             "SetupActivity" % (nama, sha[:8]))
                return
        # Nama terbelah antar DUA path dalam satu commit
        # (issue #1715): kanal nama menilai per path, jadi
        # dua berkas yang namanya bila disambung membentuk
        # token lolos. Nilai gabungan nama-nama yang
        # berubah pada commit ini dengan kandidat yang
        # sama seperti nama tunggal.
        if len(nama_gab_list) >= 2:
            # Urutan sambung yang dinilai (issue #1735):
            # urutan sortir saja membuat belahan yang
            # urutan sambungnya BUKAN urutan sortir —
            # termasuk belahan lintas direktori — lolos.
            # Nilai juga urutan diff dan semua pasangan
            # dua nama dalam kedua urutannya (belahan
            # token adalah dua paruh; kandidat tetap
            # disaring regex yang sama). Pasangan dibatasi
            # <=60 nama berubah agar biaya berpagar.
            daftar_gabungan = []
            _terlihat_gab = set()
            def _tambah_gabungan(teks_gab):
                if teks_gab and teks_gab not in _terlihat_gab:
                    _terlihat_gab.add(teks_gab)
                    daftar_gabungan.append(teks_gab)
            _tambah_gabungan("".join(sorted(nama_gab_list)))
            _tambah_gabungan("".join(nama_gab_list))
            if len(nama_gab_list) <= 60:
                for _i_gab in range(len(nama_gab_list)):
                    for _j_gab in range(len(nama_gab_list)):
                        if _i_gab != _j_gab:
                            _tambah_gabungan(nama_gab_list[_i_gab] + nama_gab_list[_j_gab])
            # Varian inti per nama (issue #1761):
            # ekstensi dikupas dari TIAP nama sebelum
            # digabung — kupas atas string gabungan
            # hanya melepas ekstensi nama terakhir,
            # jadi ekstensi nama KEPALA tertinggal di
            # tengah dan memutus run ekor rekonstruksi.
            def _kupas_inti(nama_inti):
                for _ in range(3):
                    if "." not in nama_inti:
                        break
                    nama_inti = nama_inti.rsplit(".", 1)[0]
                    if not nama_inti:
                        break
                return nama_inti
            nama_inti_list = [_kupas_inti(n) for n in nama_gab_list]
            _tambah_gabungan("".join(sorted(nama_inti_list)))
            _tambah_gabungan("".join(nama_inti_list))
            if len(nama_inti_list) <= 60:
                for _i_gab in range(len(nama_inti_list)):
                    for _j_gab in range(len(nama_inti_list)):
                        if _i_gab != _j_gab:
                            _tambah_gabungan(nama_inti_list[_i_gab] + nama_inti_list[_j_gab])
            else:
                # Plafon terikat (issue #1767): cakupan
                # yang menyempit harus TERDENGAR —
                # sebelumnya enumerasi pasangan dilewati
                # diam-diam dan tak ada yang tahu vonis
                # commit ini lebih sempit.
                print("PERINGATAN: enumerasi pasangan "
                      "nama dilewati untuk commit %s "
                      "(%d nama berubah > plafon 60) — "
                      "cakupan gabungan nama menyempit "
                      "pada commit ini."
                      % (sha[:8], len(nama_gab_list)))
            calon_gab = []
            for gabungan_nama in daftar_gabungan:
                varian_gab = [gabungan_nama]
                # Kupas bertingkat juga di sini (issue #1734).
                _sisa_gab = gabungan_nama
                for _ in range(3):
                    if "." not in _sisa_gab:
                        break
                    _sisa_gab = _sisa_gab.rsplit(".", 1)[0]
                    if not _sisa_gab:
                        break
                    varian_gab.append(_sisa_gab)
                for varian in varian_gab:
                    if re.search(r"[0-9]{6,}:[A-Za-z0-9_-]{35,}", varian):
                        calon_gab.append(varian)
                    cocok_g = re.match(r"^(.*?)([0-9]{6,})_([A-Za-z0-9_-]{35,})$", varian)
                    if cocok_g:
                        calon_gab.append("%s%s:%s" % (cocok_g.group(1), cocok_g.group(2), cocok_g.group(3)))
                    cocok_gt = re.match(r"^(.*?)([0-9]{6,})\.([A-Za-z0-9_-]{35,})$", varian)
                    if cocok_gt:
                        calon_gab.append("%s%s:%s" % (cocok_gt.group(1), cocok_gt.group(2), cocok_gt.group(3)))
            if any(mengandung_token(c) for c in calon_gab):
                gagal.append("Kemungkinan token bot asli pada GABUNGAN "
                             "nama berkas satu commit %s (riwayat "
                             "rentang) — nama terbelah antar path tetap "
                             "hidup di riwayat. Kredensial hanya via "
                             "input manual SetupActivity" % sha[:8])
                return
        # Belahan lintas berkas (issue #1676): semua saluran
        # di atas menilai per blob, jadi token yang separuh
        # di berkas A dan separuh di berkas B pada commit
        # yang sama tak tertuduh di mana pun (permukaan
        # staged sudah menilai gabungan sejak #1625/#1644,
        # permukaan riwayat belum). Nilai gabungan isi
        # terdekode berkas-berkas yang berubah pada commit
        # ini: kandidat dulu (regex run atas gabungan
        # rapat), vonis tetap mengandung_token.
        gabungan_isi = []
        for path in dict.fromkeys(pecah_nama(berubah_kandidat)):
            path = path.strip()
            if not path:
                continue
            mentah_g = jalan_biner("git", "show", "%s:%s" % (sha, path))
            if mentah_g is None:
                continue
            isi_g = _dekode(mentah_g)
            if isi_g is not None:
                gabungan_isi.append(isi_g)
        if len(gabungan_isi) >= 2:
            rapat_g = "".join(gabungan_isi).replace("\r", "").replace("\n", "")
            if _kandidat_rapat(rapat_g) and _vonis_isi_besar("\n".join(gabungan_isi)):
                gagal.append("Kemungkinan token bot asli (base64/terbelah lintas "
                             "berkas) pada commit %s (riwayat rentang) — token "
                             "tetap hidup di riwayat walau sudah dihapus di pohon "
                             "akhir. Kredensial hanya via input manual "
                             "SetupActivity" % sha[:8])
                return
        # Berkas UTF-16 tak pernah jadi kandidat di atas (issue #801):
        # byte NUL antar karakter memutus regex saringan grep. Sapu
        # blob ber-BOM UTF-16 secara terpisah dan uji sesudah didekode.
        for bom in (b"\xff\xfe", b"\xfe\xff"):
            out = jalan_biner("git", "grep", "-a", "-l", "-z", "-F", "-e", bom, sha) or b""
            for mentah_nama in out.split(b"\x00"):
                if not mentah_nama:
                    continue
                teks = mentah_nama.decode("utf-8", errors="surrogateescape")
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
    _cek_belahan_lintas_commit(gagal, tip_rentang)


def _cek_belahan_lintas_commit(gagal, tip_rentang):
    """Kanal belahan lintas commit di pohon tip
    rentang (issue #1702), dipisah dari loop
    per-commit (issue #1733): blok ini hanya
    bergantung pada pohon akhir, jadi cukup
    dinilai SEKALI sesudah loop — bukan sekali
    per commit seperti sebelumnya."""
    # Belahan LINTAS commit (issue #1702): gabungan
    # #1676 menilai berkas yang berubah pada commit
    # yang sama, jadi token yang separuh masuk pada
    # commit A di berkas X dan separuhnya pada commit
    # B di berkas Y tak pernah dinilai bersama walau
    # kedua paruh hidup berdampingan di pohon akhir.
    # Nilai JENDELA BATAS pohon akhir rentang: untuk
    # tiap berkas ambil 64 aksara terpadatkan pertama
    # dan terakhirnya; pasangan (ekor berkas P, isi
    # berkas Q) dinilai bila ekor P memuat kepala pola
    # (deret digit + titik dua + ekor kurang dari
    # utuh, atau deret digit saja untuk belahan di
    # dalam digit) dan Q berkas kecil berisi satu run
    # alfabet token murni — di luar syarat itu token
    # tak mungkin terbentuk tepat di batas pasangan.
    # Vonisnya tetap mengandung_token atas sambungan
    # jendela itu.
    if tip_rentang:
        daftar_path = jalan("git", "ls-tree", "-r", "-z",
                            "--name-only", tip_rentang) or ""
        kepala_pohon = {}
        ekor_pohon = {}
        murni_pohon = {}
        for path in dict.fromkeys(pecah_nama(daftar_path)):
            path = path.strip()
            if not path:
                continue
            mentah = jalan_biner("git", "show",
                                 "%s:%s" % (tip_rentang, path))
            if mentah is None:
                continue
            isi = _dekode(mentah)
            if isi is None:
                continue
            if len(isi) > 131072:
                padat_awal = _padatkan(isi[:65536])
                padat_akhir = _padatkan(isi[-65536:])
            else:
                padat_awal = padat_akhir = _padatkan(isi)
            kepala_pohon[path] = padat_awal[:64]
            ekor_pohon[path] = padat_akhir[-64:]
            # Berkas pasangan harus berkas KECIL yang
            # seluruh isinya satu run alfabet token
            # murni (bentuk paruh tertanam): awal berkas
            # kode biasa juga run panjang, tetapi isinya
            # tak pernah murni — mensyaratkan kemurnian
            # mencegah berkas wajar dituduh jadi paruh.
            # Tepi ternoda ditoleransi (issue #1739):
            # satu-dua aksara non-alfabet yang menempel
            # di tepi berkas (mis. '!') sebelumnya
            # meruntuhkan fullmatch kemurnian walau isi
            # paruhnya utuh — kupas tepinya (maks 2 per
            # sisi) sebelum menilai; inti tetap wajib
            # murni penuh dan vonis tetap penyelesaian
            # token atas sambungan pasangan.
            murni_kupas = padat_awal
            for _ in range(2):
                if murni_kupas and not re.match(r"[A-Za-z0-9_-]", murni_kupas[0]):
                    murni_kupas = murni_kupas[1:]
                if murni_kupas and not re.match(r"[A-Za-z0-9_-]", murni_kupas[-1]):
                    murni_kupas = murni_kupas[:-1]
            if len(isi) <= 131072 and 20 <= len(murni_kupas) <= 96 \
                    and re.fullmatch(r"[A-Za-z0-9_-]+", murni_kupas):
                murni_pohon[path] = murni_kupas
            if path not in murni_pohon:
                # Pasangan satu baris murni (issue #1740
                # untuk berkas besar; issue #1763: berkas
                # KECIL juga): syarat kemurnian seluruh
                # isi di atas membuat satu baris sampah
                # saja (mis. komentar) membatalkan paruh
                # yang hidup sebagai SATU baris murni di
                # berkas kecil. Pindai barisnya:
                # baris terpampat yang murni 20..96
                # aksara (tepi ternoda dikupas seperti
                # di atas) jadi kandidat pasangan berkas
                # ini; vonis tetap penyelesaian token
                # atas sambungan dengan kepala tertangkap.
                for baris_besar in isi.splitlines():
                    padat_baris = _padatkan(baris_besar)
                    for _ in range(2):
                        if padat_baris and not re.match(r"[A-Za-z0-9_-]", padat_baris[0]):
                            padat_baris = padat_baris[1:]
                        if padat_baris and not re.match(r"[A-Za-z0-9_-]", padat_baris[-1]):
                            padat_baris = padat_baris[:-1]
                    if 20 <= len(padat_baris) <= 96 \
                            and re.fullmatch(r"[A-Za-z0-9_-]+", padat_baris):
                        murni_pohon[path] = padat_baris
                        break
        # Tepi kepala ditoleransi juga (issue #1762):
        # #1739 hanya mengupas tepi sisi PASANGAN —
        # satu aksara non-alfabet yang tak dibuang
        # _padatkan di AKHIR berkas kepala (mis. ';')
        # membuat regex kandidat di bawah yang
        # berjangkar $ tak pernah cocok, dan aksara
        # yang sama ikut memutus sambungan pasangan.
        # Kupas maks 2 aksara tepi belakang ekor tiap
        # berkas sebelum kanal ini menilai.
        for _path_ekor in ekor_pohon:
            _kupas_ekor = ekor_pohon[_path_ekor]
            for _ in range(2):
                if _kupas_ekor and not re.match(r"[A-Za-z0-9_-]", _kupas_ekor[-1]):
                    _kupas_ekor = _kupas_ekor[:-1]
                else:
                    break
            ekor_pohon[_path_ekor] = _kupas_ekor
        daftar_ekor = [p for p in sorted(ekor_pohon)
                       if re.search(r"[0-9]{5,}:[A-Za-z0-9_-]{0,34}$",
                                    ekor_pohon[p])
                       or re.search(r"[0-9]{6,}$", ekor_pohon[p])]
        if len(daftar_ekor) > 50:
            # Plafon terikat (issue #1767): pemotongan
            # kandidat harus terdengar, tidak diam.
            print("PERINGATAN: kandidat kepala belahan "
                  "lintas-commit dipotong ke 50 pertama "
                  "(%d kandidat) — cakupan pasangan "
                  "menyempit pada pohon ini."
                  % len(daftar_ekor))
        for p in daftar_ekor[:50]:
            for q in sorted(murni_pohon):
                if p == q:
                    continue
                if mengandung_token(ekor_pohon[p] + murni_pohon[q]):
                    gagal.append("Kemungkinan token bot asli "
                                 "(base64/terbelah LINTAS commit) "
                                 "antara ujung %s dan awal %s pada "
                                 "pohon akhir rentang (commit %s) — "
                                 "kedua paruh hidup berdampingan di "
                                 "pohon walau masuk lewat commit "
                                 "berbeda. Kredensial hanya via "
                                 "input manual SetupActivity"
                                 % (p, q, tip_rentang[:8]))
                    return
        # Nama terbelah LINTAS commit (issue #1766):
        # kanal nama riwayat menilai per commit dan
        # kanal lintas-commit di atas menilai ISI —
        # dua paruh token sebagai NAMA berkas yang
        # masuk lewat commit berbeda tak pernah
        # disambung penilai mana pun. Nilai pasangan
        # nama di pohon akhir: kandidat kepala = inti
        # nama (kupas ekstensi, pola #1761) yang
        # memuat deret digit; kandidat ekor = inti
        # nama murni alfabet token; sambungannya
        # dinilai rekonstruksi yang sama seperti
        # gabungan nama satu commit.
        inti_tip = []
        for path in dict.fromkeys(pecah_nama(daftar_path)):
            polos_tip = path.strip().rsplit("/", 1)[-1]
            if not polos_tip:
                continue
            inti = polos_tip
            for _ in range(3):
                if "." not in inti:
                    break
                inti = inti.rsplit(".", 1)[0]
                if not inti:
                    break
            if inti:
                inti_tip.append(inti)
        kepala_nama = [n for n in inti_tip if re.search(r"[0-9]{6,}", n)]
        ekor_nama = [n for n in inti_tip
                     if 10 <= len(n) <= 47
                     and re.fullmatch(r"[A-Za-z0-9_-]+", n)]
        for kn in kepala_nama:
            for en in ekor_nama:
                if kn == en:
                    continue
                gab_nama = kn + en
                calon_nama_tip = [gab_nama]
                cocok_g = re.match(r"^(.*?)([0-9]{6,})_([A-Za-z0-9_-]{35,})$", gab_nama)
                if cocok_g:
                    calon_nama_tip.append("%s%s:%s" % (cocok_g.group(1), cocok_g.group(2), cocok_g.group(3)))
                cocok_gt = re.match(r"^(.*?)([0-9]{6,})\.([A-Za-z0-9_-]{35,})$", gab_nama)
                if cocok_gt:
                    calon_nama_tip.append("%s%s:%s" % (cocok_gt.group(1), cocok_gt.group(2), cocok_gt.group(3)))
                if any(mengandung_token(c) for c in calon_nama_tip):
                    gagal.append("Kemungkinan token bot asli "
                                 "pada GABUNGAN NAMA berkas "
                                 "LINTAS commit di pohon akhir "
                                 "rentang (commit %s) — dua "
                                 "paruh nama hidup berdampingan "
                                 "di pohon walau masuk lewat "
                                 "commit berbeda. Kredensial "
                                 "hanya via input manual "
                                 "SetupActivity" % tip_rentang[:8])
                    return


def _pesan_terbelah(teks):
    """Fragmen token di pesan commit (issue #1698).

    Varian pesan memang TANPA pemadatan (#1425 —
    pemadatan atas prosa yang mengutip id run/galat
    pernah menuduh pesan sah), jadi token yang dipecah
    beberapa aksara per baris lolos. Blok baris murni
    alfabet token adalah bentuk mekanis yang tak
    mirip prosa: sambung bloknya lalu nilai dengan
    pemindai bersama.
    """
    # Sumber tunggal (issue #1742): delegasikan ke
    # penilai kanonik cek-token bila termuat; badan
    # di bawah adalah cadangan lokal untuk keadaan
    # pemuatan gagal. Cadangan ini CERMINAN PERSIS
    # aturan kanonik — sapuan gabungan #1736, baris
    # pemisah murni #1750, dan gabungan dua blok
    # berseberangan satu baris penyela #1749 (issue
    # #1788: cadangan lama hanya memuat #1736, jadi
    # dua aturan itu hilang diam-diam tepat pada
    # keadaan darurat yang menjadi alasan cadangan
    # ini ada). Tes paritas di tools/tests menjaga
    # cerminan ini tak menyimpang lagi.
    if _penilai_fragmen is not None:
        return _penilai_fragmen(teks)
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
        # Baris pemisah murni (issue #1750): baris
        # berisi hanya '=', '/', atau ':' ikut ke
        # blok tetapi bukan alfabet ekor token —
        # nilai ulang gabungan tanpa baris-baris itu.
        inti = [b for b in blok if not re.fullmatch(r"[=\/:]+", b)]
        if inti == blok:
            return False
        gabung = "".join(inti)
        return len(gabung) >= 42 and mengandung_token(gabung)

    # Satu baris penyela (issue #1749): dua blok
    # yang terpisah TEPAT satu baris non-anggota
    # juga dinilai tersambung.
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


# Vonis tag hanya boleh berjalan sekali per proses
# (issue #1787): _cek_pesan_tag dipanggil dari
# cek_secret_pesan DAN cek_secret_pesan_semua, dan
# pada mode --semua keduanya berjalan dalam satu lari
# — tag kotor yang sama dulu terhitung dua kegagalan
# identik. Vonisnya fungsi murni keadaan repo, jadi
# panggilan sesudah yang pertama tak menilai ulang.
_cek_tag_sudah_jalan = False


_AMBANG_SAMBUNG = 35


def _lari_tepi(teks):
    """Pasangan (larian awal, larian akhir) aksara
    alfabet token pada teks (issue #1833). Tepi
    dibersihkan dulu dari pemisah yang memang
    direnggut _padatkan pada sambungan (spasi, kutip,
    +, titik tepi) agar lariannya mencerminkan teks
    tersambung yang dinilai penilai pesan. Dipakai
    uji jumlah-larian gabungan lintas permukaan:
    dua teks hanya bisa membentuk kandidat yang
    menyebrang bila larian akhir yang satu ditambah
    larian awal yang lain mencapai _AMBANG_SAMBUNG.
    Ambang 35 di bawah kandidat terpendek yang bisa
    cocok (base32 40 aksara; pola polos 42; base64
    48), jadi tak ada pasangan tersambung yang
    terbuang oleh penyaring — pasangan yang lolos
    tetap dinilai persis oleh penilai pesan."""
    t = (teks or "").strip(" \t\r\n\"'`+.")
    alfabet = frozenset(
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        "abcdefghijklmnopqrstuvwxyz0123456789_-:")

    def lari(sx):
        n = 0
        for ch in sx:
            if ch not in alfabet:
                break
            n += 1
        return n
    return lari(t), lari(t[::-1])


def _cek_pesan_tag(gagal, pesan_commit=None):
    """Pesan ANOTASI TAG (issue #1713): tag beranotasi
    membawa pesannya sendiri yang ikut terbit bersama
    rilis, tetapi tak ada kanal yang membacanya — kanal
    pesan hanya membaca %B commit. SEMUA tag beranotasi
    dinilai (issue #1741): saringan target-di-dalam-
    rentang sebelumnya membuat tag yang menunjuk commit
    lama tak pernah dinilai rentang mana pun, padahal
    pesan tag itu permanen dan publik. Mengembalikan
    True bila satu kegagalan ditambahkan."""
    global _cek_tag_sudah_jalan
    if _cek_tag_sudah_jalan:
        return False
    _cek_tag_sudah_jalan = True
    permukaan = []
    daftar_tag = jalan("git", "for-each-ref", "refs/tags",
                       "--format=%(refname:short)%09%(objecttype)%09%(*objectname)%09%(objectname)") or ""
    for baris in daftar_tag.splitlines():
        bagian = baris.split("\t")
        if len(bagian) != 4:
            continue
        nama_tag = bagian[0]
        permukaan.append(nama_tag)
        # NAMA tag ikut dinilai (issue #1804): nama adalah
        # satu-satunya bagian tag yang selalu terbit —
        # token ter-encode (base64url sah sebagai nama
        # ref) dengan anotasi bersih dulu lolos semua
        # gerbang. Berlaku untuk SEMUA tag, ringan maupun
        # beranotasi; titik-dua dilarang git di nama ref,
        # jadi yang bisa tertangkap memang bentuk encode.
        if mengandung_token_pesan(nama_tag):
            gagal.append("Kemungkinan token bot asli di NAMA "
                         "tag %s — nama tag ikut terbit dan "
                         "hidup permanen; buat ulang tag itu "
                         "dengan nama bersih. Kredensial hanya "
                         "via input manual SetupActivity" % nama_tag)
            return True
        if bagian[1] != "tag":
            continue
        pesan_tag = jalan("git", "for-each-ref", "refs/tags/" + nama_tag,
                          "--format=%(contents)") or ""
        permukaan.append(pesan_tag)
        if mengandung_token_pesan(pesan_tag) or _pesan_terbelah(pesan_tag):
            gagal.append("Kemungkinan token bot asli di PESAN anotasi "
                         "tag %s — pesan anotasi tag ikut terbit dan "
                         "hidup permanen; buat ulang tag itu tanpa "
                         "token. Kredensial hanya via input manual "
                         "SetupActivity" % nama_tag)
            return True
        # Rantai tag BERSARANG (issue #1826): objek tag
        # bisa menunjuk objek tag lain yang tak punya
        # ref sendiri — nama dan pesan tag dalam tak
        # terjangkau %(contents) ref mana pun. Telusuri
        # rantai object-nya (dibatasi 8, anti-putaran)
        # dan nilai nama + pesan tiap tag dalam.
        objek = bagian[3]
        dilihat = set()
        for _ in range(8):
            if not objek or objek in dilihat:
                break
            dilihat.add(objek)
            mentah = jalan("git", "cat-file", "-p", objek) or ""
            target = None
            for baris_h in mentah.splitlines():
                if baris_h.startswith("object "):
                    target = baris_h[7:].strip()
                elif baris_h == "":
                    break
            if not target:
                break
            if (jalan("git", "cat-file", "-t", target) or "").strip() != "tag":
                break
            isi_dalam = jalan("git", "cat-file", "-p", target) or ""
            nama_dalam = ""
            for baris_h in isi_dalam.splitlines():
                if baris_h.startswith("tag "):
                    nama_dalam = baris_h[4:].strip()
                elif baris_h == "":
                    break
            pesan_dalam = (isi_dalam.split("\n\n", 1)[1]
                           if "\n\n" in isi_dalam else "")
            # Nama + pesan tag dalam ikut korpus
            # gabungan lintas permukaan (issue #1834):
            # sebelumnya keduanya hanya dinilai
            # sendiri-sendiri di blok ini, jadi belahan
            # yang separuhnya di tag dalam dan separuh
            # lain di pesan commit atau ref lain tak
            # pernah tersambung oleh blok gabungan.
            if nama_dalam:
                permukaan.append(nama_dalam)
            if pesan_dalam:
                permukaan.append(pesan_dalam)
            if (mengandung_token_pesan(nama_dalam)
                    or mengandung_token_pesan(pesan_dalam)
                    or _pesan_terbelah(pesan_dalam)):
                gagal.append("Kemungkinan token bot asli di "
                             "tag BERSARANG dalam %s — objek "
                             "tag tanpa ref sendiri tetap "
                             "terbit bersama rantainya; buat "
                             "ulang rantai tag itu tanpa "
                             "token. Kredensial hanya via "
                             "input manual SetupActivity"
                             % nama_tag)
                return True
            objek = target
    # NAMA branch ikut dinilai (issue #1815): asimetri
    # langsung dengan nama tag #1804 — string yang sama
    # dituduh sebagai nama tag tetapi lolos total sebagai
    # nama branch, padahal nama branch ikut terbit ke
    # remote dan tampil publik selama branch hidup.
    # Hanya refs/heads (branch lokal yang akan di-push);
    # refs/remotes UMUM mencerminkan branch pihak lain
    # yang tak bisa diganti nama dari sini. Pengecualian
    # (issue #1824): refs/remotes/origin adalah cermin
    # branch MILIK SENDIRI di remote tujuan push repo
    # ini — alasan pengecualian tak berlaku untuknya,
    # jadi namanya dinilai juga. Seperti nama tag,
    # titik-dua dilarang git di nama ref — yang bisa
    # tertangkap memang bentuk ter-encode.
    daftar_cabang = jalan("git", "for-each-ref", "refs/heads",
                          "--format=%(refname:short)") or ""
    for nama_cabang in daftar_cabang.splitlines():
        nama_cabang = nama_cabang.strip()
        if nama_cabang:
            permukaan.append(nama_cabang)
        if nama_cabang and mengandung_token_pesan(nama_cabang):
            gagal.append("Kemungkinan token bot asli di NAMA "
                         "branch %s — nama branch ikut terbit dan "
                         "tampil publik selama branch itu hidup; "
                         "ganti nama branch itu sebelum push. "
                         "Kredensial hanya via input manual "
                         "SetupActivity" % nama_cabang)
            return True
    daftar_cabang_remote = jalan("git", "for-each-ref",
                                 "refs/remotes/origin",
                                 "--format=%(refname:short)") or ""
    for nama_cabang in daftar_cabang_remote.splitlines():
        nama_cabang = nama_cabang.strip()
        if nama_cabang:
            permukaan.append(nama_cabang)
        if nama_cabang and mengandung_token_pesan(nama_cabang):
            gagal.append("Kemungkinan token bot asli di NAMA "
                         "branch remote origin %s — nama itu "
                         "cermin branch milik sendiri yang sudah "
                         "terbit di remote; ganti nama branch "
                         "asalnya lalu push ulang. Kredensial "
                         "hanya via input manual SetupActivity"
                         % nama_cabang)
            return True
    # Gabungan LINTAS PERMUKAAN (issue #1825,
    # diperluas #1833): token ter-encode yang
    # separuhnya di nama branch/tag atau anotasi tag
    # dan separuh lainnya di pesan commit tak
    # terlihat penilai per permukaan mana pun — yang
    # dinilai sendiri-sendiri memang bersih.
    # Pasangan dinilai tersambung per arah bila uji
    # jumlah-larian tepinya lolos. Penyaring lama
    # (tepi >=20 di SETIAP permukaan) melewatkan
    # belahan TAK-SEIMBANG: fragmen 18 + sisa 42
    # membentuk kandidat utuh, tetapi permukaan
    # pembawa fragmen pendek gugur sebelum dinilai.
    daftar_pesan = list(pesan_commit or [])
    tepi_p = [_lari_tepi(t) for t in permukaan]
    tepi_m = [_lari_tepi(t) for t in daftar_pesan]

    def tersambung(tepi_a, tepi_b):
        return tepi_a[1] + tepi_b[0] >= _AMBANG_SAMBUNG

    def tuduh_terbelah():
        gagal.append("Kemungkinan token bot asli "
                     "TERBELAH lintas permukaan — "
                     "separuh di nama/anotasi ref dan "
                     "separuh lain di pesan commit atau "
                     "ref lain, tersambung bila digabung; "
                     "tulis ulang kedua potong itu "
                     "sebelum push. Kredensial hanya via "
                     "input manual SetupActivity")

    for i, a in enumerate(permukaan):
        for j, b in enumerate(daftar_pesan):
            if (tersambung(tepi_p[i], tepi_m[j])
                    and mengandung_token_pesan(a + b)):
                tuduh_terbelah()
                return True
            if (tersambung(tepi_m[j], tepi_p[i])
                    and mengandung_token_pesan(b + a)):
                tuduh_terbelah()
                return True
    # Permukaan x permukaan: pasangan yang kedua
    # permukaan tepinya <=17 tak mungkin mencapai
    # ambang (17+17 < 35), jadi hanya permukaan
    # berpotensi (satu tepi >=18) yang dipasangkan.
    berpotensi = [i for i, tp in enumerate(tepi_p)
                  if tp[0] >= 18 or tp[1] >= 18]
    letak = {v: k for k, v in enumerate(berpotensi)}
    for i in berpotensi:
        a = permukaan[i]
        for j, b in enumerate(permukaan):
            if j == i:
                continue
            if j in letak and letak[j] < letak[i]:
                continue
            if (tersambung(tepi_p[i], tepi_p[j])
                    and mengandung_token_pesan(a + b)):
                tuduh_terbelah()
                return True
            if (tersambung(tepi_p[j], tepi_p[i])
                    and mengandung_token_pesan(b + a)):
                tuduh_terbelah()
                return True
    # Fragmen di TENGAH teks (issue #1833): separuh
    # token yang dibungkus prosa tak pernah tersambung
    # oleh penggabungan permukaan utuh, jadi larian
    # alfabet token dikumpulkan dari semua permukaan
    # + pesan dan dipasangkan antar-larian. Lantai
    # naik bertingkat bila jumlah pasangan meledak,
    # agar biaya tetap terikat. Lantai awal 8 sejak
    # issue #1838: fragmen 11 aksara di tengah pesan
    # sebelumnya lolos dari kedua mekanisme (permukaan
    # menuntut ketetanggaan tepi, larian membuang run
    # di bawah 12); terukur di repo nyata biayanya
    # tetap seorde karena tangga adaptif yang sama
    # tetap menjadi pengikatnya.
    mentah = list(permukaan) + daftar_pesan
    larian = []
    for lantai in (8, 12, 16, 20, 24, 28):
        larian = []
        sudah = set()
        for t in mentah:
            for m in re.finditer(
                    r"[A-Za-z0-9_\-:]+", t or ""):
                r = m.group(0)
                if len(r) >= lantai and r not in sudah:
                    sudah.add(r)
                    larian.append(r)
        jumlah = 0
        for x, r1 in enumerate(larian):
            for r2 in larian[x + 1:]:
                if len(r1) + len(r2) >= 40:
                    jumlah += 1
        if jumlah <= 20000 or lantai == 28:
            break
    for x, r1 in enumerate(larian):
        for r2 in larian[x + 1:]:
            if len(r1) + len(r2) < 40:
                continue
            if (mengandung_token_pesan(r1 + r2)
                    or mengandung_token_pesan(r2 + r1)):
                gagal.append(
                    "Kemungkinan token bot asli "
                    "TERBELAH lintas permukaan — "
                    "fragmen di satu permukaan/pesan "
                    "dan fragmen lain di permukaan/"
                    "pesan lain tersambung bila "
                    "digabung; tulis ulang kedua "
                    "fragmen itu sebelum push. "
                    "Kredensial hanya via input "
                    "manual SetupActivity")
                return True
    # Belahan TIGA permukaan (issue #1844): belahan
    # seperti 22/20/22 lolos dari mekanisme pasangan
    # karena tiap pasangan fragmen yang BERURUTAN
    # jumlahnya di bawah kandidat minimum hakim (48),
    # padahal gabungan ketiganya token utuh. Tripel
    # larian yang sumbernya tidak semuanya sama
    # dinilai dalam keenam urutannya.
    # Disiplin anggaran (issue #1864): himpunan tripel
    # TIDAK lagi dipotong menurut urutan enumerasi —
    # vonis dulu bergantung posisi fragmen di banjir
    # larian jinak. Kini daftar berlabel diurutkan
    # kanonik (teks larian, lalu indeks sumber), tangga
    # lantai memilih lantai dari hitungan batas-atas
    # kandidat, dan penilaian MENYELURUH atas semua
    # tripel berbeda di lantai terpilih. Katup keras
    # 300.000 tripel ternilai membatasi banjir patologis;
    # himpunan yang ternilai saat katup bekerja adalah
    # awalan kanonik — fungsi dari multiset larian,
    # bukan dari posisi fragmen di permukaan.
    berlabel = []
    for lantai_t in (8, 10, 12, 16, 20):
        per_larian = {}
        for idx, t in enumerate(mentah):
            for m in re.finditer(
                    r"[A-Za-z0-9_\-:]+", t or ""):
                r = m.group(0)
                if len(r) < lantai_t:
                    continue
                asal = per_larian.setdefault(r, [])
                if idx not in asal and len(asal) < 3:
                    asal.append(idx)
        berlabel = sorted(
            (r, i) for r, asal in per_larian.items()
            for i in asal)
        n = len(berlabel)
        jumlah = 0
        for a in range(n):
            for b in range(a + 1, n):
                for c in range(b + 1, n):
                    if (berlabel[a][1] == berlabel[b][1]
                            == berlabel[c][1]):
                        continue
                    if (len(berlabel[a][0]) + len(berlabel[b][0])
                            + len(berlabel[c][0]) >= 48):
                        jumlah += 1
        if jumlah <= 20000 or lantai_t == 20:
            break
    sudah = set()
    ternilai = 0
    n = len(berlabel)
    for a in range(n):
        for b in range(a + 1, n):
            for c in range(b + 1, n):
                if (berlabel[a][1] == berlabel[b][1]
                        == berlabel[c][1]):
                    continue
                kunci = tuple(sorted(
                    (berlabel[a][0], berlabel[b][0],
                     berlabel[c][0])))
                if kunci in sudah:
                    continue
                sudah.add(kunci)
                r1, r2, r3 = kunci
                if len(r1) + len(r2) + len(r3) < 48:
                    continue
                ternilai += 1
                if ternilai > 300000:
                    return False
                for gabung in ((r1, r2, r3), (r1, r3, r2),
                               (r2, r1, r3), (r2, r3, r1),
                               (r3, r1, r2), (r3, r2, r1)):
                    if mengandung_token_pesan("".join(gabung)):
                        tuduh_terbelah()
                        return True
    return False


def cek_secret_pesan(gagal, rentang):
    """Pindai token di PESAN commit dalam rentang (issue #769).

    Diff dan pohon tak memuat pesan commit, padahal token yang tertulis
    di pesan (tempelan contoh, pesan bawaan cherry-pick) terkirim dan
    hidup permanen di riwayat — media yang sama dengan #750.
    """
    daftar_commit = jalan("git", "rev-list", rentang) or ""
    daftar_pesan = []
    for sha in daftar_commit.splitlines():
        sha = sha.strip()
        if not sha:
            continue
        pesan = jalan("git", "log", "-1", "--format=%B", sha)
        daftar_pesan.append(pesan or "")
        if mengandung_token_pesan(pesan) or _pesan_terbelah(pesan):
            gagal.append("Kemungkinan token bot asli di PESAN commit %s — "
                         "token di pesan commit hidup permanen di riwayat; "
                         "tulis ulang pesan itu sebelum push. Kredensial "
                         "hanya via input manual SetupActivity" % sha[:8])
            return
        # Metadata author/committer (issue #1717):
        # nama dan email tersimpan permanen di riwayat
        # dan tampil publik, tetapi kanal ini hanya
        # membaca %B. Nilai keempatnya dengan penilai
        # pesan yang sama.
        meta = jalan("git", "log", "-1", "--format=%an %ae %cn %ce", sha) or ""
        if mengandung_token_pesan(meta) or _pesan_terbelah(meta):
            gagal.append("Kemungkinan token bot asli di METADATA "
                         "author commit %s — metadata tersimpan "
                         "permanen di riwayat; tulis ulang commit "
                         "itu. Kredensial hanya via input manual "
                         "SetupActivity" % sha[:8])
            return

    # Gabungan pesan LINTAS commit (issue #1786): token
    # yang dipecah di batas dua pesan — kepala di ujung
    # pesan satu commit, ekor di awal pesan commit
    # berikutnya — tak terlihat penilai per commit di
    # atas. Gabungan kedua arah urutan dinilai dengan
    # penilai pesan yang sama; kanal tanpa-spasi dan
    # penilai fragmen menyambung belahan tepat di
    # jahitannya. Langkah 5e cek-cepat sudah menggabung
    # pesan belum-ter-push seperti ini — vonis rentang
    # CI kini setara.
    if len(daftar_pesan) > 1:
        for gabungan in ("\n".join(daftar_pesan),
                         "\n".join(reversed(daftar_pesan))):
            if mengandung_token_pesan(gabungan) or _pesan_terbelah(gabungan):
                gagal.append("Kemungkinan token bot asli "
                             "TERBELAH lintas pesan commit "
                             "dalam rentang — kepala dan ekor "
                             "token hidup di dua pesan commit "
                             "berurutan dan tersambung bila "
                             "pesan digabung; tulis ulang "
                             "kedua pesan itu sebelum push. "
                             "Kredensial hanya via input "
                             "manual SetupActivity")
                return

    # Pesan ANOTASI TAG: dinilai pembantu bersama
    # _cek_pesan_tag (issue #1713, #1741; dipakai
    # juga mode --semua sejak issue #1768).
    if _cek_pesan_tag(gagal, daftar_pesan):
        return


def cek_secret_pesan_semua(gagal):
    """Pindai pesan SELURUH commit terjangkau HEAD (issue #800).

    Mode --semua adalah pindai forensik berkala: pesan commit yang
    sudah telanjur ter-push pun harus tertandai agar token yang
    bocor bisa dirotasi. Satu panggilan git log berformat rekaman
    agar riwayat panjang (ribuan commit) tidak dibayar per commit.
    Metadata author/committer ikut dibaca dan dinilai (issue
    #1803): mode rentang sudah menilainya sejak #1717, mode ini
    tertinggal hanya membaca %B.
    """
    out = jalan("git", "log", "--format=%H%x00%an%x00%ae%x00%cn%x00%ce%x00%B%x1e", "HEAD") or ""
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
        # Rekaman berformat sha NUL author NUL email-author NUL
        # committer NUL email-committer NUL pesan (issue #1803);
        # pesan adalah sisa sesudah pemisah kelima, utuh walau
        # memuat NUL sendiri.
        bagian = rekaman.split("\x00", 5)
        sha = bagian[0]
        meta = " ".join(bagian[1:5]) if len(bagian) > 5 else ""
        pesan = bagian[5] if len(bagian) > 5 else ""
        if mengandung_token_pesan(pesan) or _pesan_terbelah(pesan):
            gagal.append("Kemungkinan token bot asli di PESAN commit %s — "
                         "token di pesan commit hidup permanen di riwayat; "
                         "rotasi token itu. Kredensial hanya via input "
                         "manual SetupActivity" % sha.strip()[:8])
            return
        if meta and (mengandung_token_pesan(meta) or _pesan_terbelah(meta)):
            gagal.append("Kemungkinan token bot asli di METADATA "
                         "author commit %s — metadata tersimpan "
                         "permanen di riwayat; rotasi token itu. "
                         "Kredensial hanya via input manual "
                         "SetupActivity" % sha.strip()[:8])
            return
    # Gabungan pesan LINTAS commit (issue #1786):
    # belahan di batas dua pesan dinilai seperti mode
    # rentang — gabungan kedua arah urutan dari semua
    # rekaman yang sudah terkumpul di atas.
    if len(rekaman_list) > 1:
        semua_pesan = [(r.split("\x00", 5)[5] if len(r.split("\x00", 5)) > 5 else "")
                       for r in rekaman_list]
        for gabungan in ("\n".join(semua_pesan),
                         "\n".join(reversed(semua_pesan))):
            if mengandung_token_pesan(gabungan) or _pesan_terbelah(gabungan):
                gagal.append("Kemungkinan token bot asli "
                             "TERBELAH lintas pesan commit "
                             "dalam riwayat — kepala dan ekor "
                             "token hidup di dua pesan commit "
                             "berurutan; rotasi token itu. "
                             "Kredensial hanya via input "
                             "manual SetupActivity")
                return
    # Pesan anotasi tag ikut dinilai (issue #1768):
    # mode forensik penuh sebelumnya hanya membaca
    # %B — perbaikan #1741 untuk mode rentang tidak
    # pernah mencapai mode ini.
    _cek_pesan_tag(gagal)


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
        # Spasi/titik di ujung nama dibuang sebelum
        # pencocokan akhiran (issue #1640): "kunci.p12 "
        # adalah nama sah di git tetapi lolos semua
        # endswith ekstensi terlarang.
        rendah = path.lower().rstrip(" .")
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
        if path not in terhapus_utuh and not _ada_di_pohon(path):
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
    # Pohon tes ikut dicari (issue #1905): nama_metode_hilang
    # memanen juga dari berkas tes dan pemanggil() memindai
    # seluruh pohon app/, jadi pencarian definisi yang hanya
    # di main memvonis perubahan tanda tangan helper tes yang
    # sah sebagai penghapusan — definisi barunya sendiri ikut
    # tertuduh sebagai pemanggil. Simetris di kedua pohon;
    # panggilan menggantung sungguhan tetap tertangkap karena
    # definisinya tidak ada di pohon mana pun.
    pola = re.compile(
        r"(?m)^[ \t]*(?:(?:public|private|protected|internal|override|suspend|inline|"
        r"operator|open|final|abstract|sealed|tailrec|infix|data)\s+)*"
        r"(?:fun\s+(?:<[^>\n]+>\s*)?(?:[\w.]+\.)?|class\s+)%s\s*(?:<[^>\n]+>\s*)?\(" % re.escape(nama))
    for akar in (os.path.join("app", "src", "main", "java"),
                 os.path.join("app", "src", "test", "java")):
        for path in _jalan_pohon(akar):
            if pola.search(_rapikan_generik(_baca_pohon(path) or "")):
                return True
    return False


def pemanggil(nama):
    pola = re.compile(r"(?:\b%s\s*\(|::%s\b)" % (re.escape(nama), re.escape(nama)))
    temu = []
    for path in _jalan_pohon("app", ext=EKSTENSI_KODE):
        # Komentar dikupas dulu (issue #1254, keluarga #1218):
        # sebutan di komentar bukan pemanggil — tanpa kupasan ia
        # memicu GAGAL palsu yang menamai baris komentar itu.
        # _kupas_komentar_kode mempertahankan newline sehingga
        # nomor baris laporan tetap tepat.
        bersih = _kupas_komentar_kode(_baca_pohon(path) or "", bersarang=not path.endswith(".java"))
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


def _kupasan_baris_beruntun(baris_list, dalam_mentah=False, kedalaman_blok=0):
    """Kupas komentar/string dari baris tambah satu berkas
    secara beruntun: keadaan string mentah dan komentar blok
    (bersarang, gaya Kotlin) dibawakan antarbaris, sehingga
    baris yang merupakan ISI string mentah/komentar blok
    menghasilkan teks kosong, bukan kode. Di dalam satu baris:
    // mengakhiri baris, string/char biasa tak membentang
    baris, interpolasi ${...} di dalam string dilompati utuh
    memakai _lewati_interpolasi (selaras kupas_kode bawaan).
    Keadaan awal bisa disemai pemanggil dan keadaan akhir
    dikembalikan bersama hasil (issue #1326): hunk yang mulai
    di tengah string mentah/komentar blok menyemai keadaannya
    dari isi berkas sebelum hunk, bukan mulai dari nol."""
    hasil = []
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
    return hasil, (dalam_mentah, kedalaman_blok)


def _segmen_hunk(diff):
    """Urai diff menjadi segmen hunk berkas kode:
    (path, nomor_baris_baru_mulai, [(tambah, isi), ...]) —
    baris konteks ikut tercatat (tambah=False) karena mereka
    baris asli gambar-sesudah yang memajukan keadaan
    pengupasan; baris hapus bukan bagian gambar-sesudah."""
    segmen = []
    path = ""
    nomor = 0
    isi = None
    for baris in diff.splitlines():
        if baris.startswith("diff --git "):
            if isi is not None:
                segmen.append((path, nomor, isi))
            path = _path_sisi_b(baris)
            isi = None
        elif baris.startswith("@@ "):
            if isi is not None:
                segmen.append((path, nomor, isi))
            cocok = re.search(r"\+(\d+)", baris)
            nomor = int(cocok.group(1)) if cocok else 0
            isi = [] if path.endswith(EKSTENSI_KODE) else None
        elif isi is not None:
            if baris.startswith("+") and not baris.startswith("+++"):
                isi.append((True, baris[1:]))
            elif baris.startswith(" "):
                isi.append((False, baris[1:]))
    if isi is not None:
        segmen.append((path, nomor, isi))
    return segmen


# Ref pohon SISI-B dari diff yang sedang diperiksa (issue
# #1343): None berarti diff menggambarkan pohon kerja (mode
# jalur), selain itu benih keadaan pengupasan WAJIB dibaca
# dari blob ref itu. Diff rentang menggambarkan pohon ujung
# rentang — menyemai dari berkas kerja membuat vonis berbalik
# dua arah bila checkout != ujung rentang (CI aman hanya
# karena checkout == github.sha).
REF_BENIH = None
_cache_benih = {}

# Pohon rujukan mode rentang (issue #1348): cek-cek yang menilai
# "masih ada/tidak" (definisi, pemanggil, sebutan, pemilik
# fungsi) harus membaca pohon UJUNG rentang yang digambarkan
# diff — bukan berkas kerja yang bisa sudah bergerak. Hanya
# dipasang pada mode rentang; mode lain None = berkas kerja.
REF_POHON = None


def _baca_pohon(path):
    """Isi berkas dari pohon REF_POHON, atau berkas kerja bila
    REF_POHON tak dipasang. None bila tak terbaca."""
    if REF_POHON:
        return jalan("git", "show", "%s:%s" % (REF_POHON, path))
    return baca(path)


def _baca_ref(ref, path):
    """Isi berkas dari ref tertentu (":index" = blob indeks);
    ref None = berkas kerja. Tak terbaca -> "" (sikap aman
    baca_teks)."""
    if ref is None:
        return baca_teks(path)
    if ref == ":index":
        isi = jalan("git", "show", ":%s" % path)
    else:
        isi = jalan("git", "show", "%s:%s" % (ref, path))
    return isi if isi is not None else ""


def _jalan_pohon(rel, ext=EKSTENSI_KODE):
    """Daftar path berkas di bawah rel pada pohon REF_POHON,
    atau jalan_repo berkas kerja bila tak dipasang."""
    if REF_POHON:
        out = jalan("git", "ls-tree", "-r", "--name-only", "-z",
                    REF_POHON, "--", rel)
        if out is None:
            return []
        return [p for p in pecah_nama(out) if p.endswith(ext)]
    return list(jalan_repo(rel, ext=ext))


def _keadaan_sebelum_baris(path, jumlah_baris):
    """Keadaan pengupasan (dalam string mentah/komentar blok)
    tepat sebelum baris ke-(jumlah_baris+1) berkas SUMBER diff
    (pohon kerja, atau blob REF_BENIH pada mode rentang),
    dihitung dengan menjalankan mesin kupas atas baris-baris
    sebelumnya. Berkas tak terbaca -> keadaan nol
    (perilaku lama)."""
    if jumlah_baris <= 0:
        return (False, 0)
    if path not in _cache_benih:
        if REF_BENIH == ":index":
            # Segmen staged mode bawaan (issue #1346): sisi baru
            # diff --cached adalah blob indeks, bukan pohon mana pun.
            isi = jalan("git", "show", ":%s" % path)
            _cache_benih[path] = isi.splitlines() if isi is not None else None
        elif REF_BENIH:
            isi = jalan("git", "show", "%s:%s" % (REF_BENIH, path))
            _cache_benih[path] = isi.splitlines() if isi is not None else None
        else:
            try:
                with open(os.path.join(AKAR, path), encoding="utf-8", errors="replace") as f:
                    _cache_benih[path] = f.read().splitlines()
            except Exception:
                _cache_benih[path] = None
    semua = _cache_benih[path]
    if semua is None:
        return (False, 0)
    _hasil, keadaan = _kupasan_baris_beruntun(semua[:jumlah_baris])
    return keadaan


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
    # Per segmen hunk (issue #1326): keadaan pengupasan
    # disemai dari isi berkas SEBELUM hunk (pembuka string
    # mentah/komentar blok bisa jauh di atas hunk dan tak
    # ikut sebagai baris tambah/konteks), lalu baris konteks
    # ikut memajukan keadaan di dalam hunk; hanya baris
    # tambah yang divonis.
    for path, nomor_mulai, segmen in _segmen_hunk(diff):
        if not sisa:
            break
        mentah0, blok0 = _keadaan_sebelum_baris(path, nomor_mulai - 1)
        bersih_list, _akhir = _kupasan_baris_beruntun(
            [isi for _t, isi in segmen], mentah0, blok0)
        for (tambah, baris), bersih in zip(segmen, bersih_list):
            if not sisa:
                break
            if not tambah:
                continue
            for nama in sorted(sisa):
                if pola[nama].search(bersih) and not pola_dekl[nama].search(bersih):
                    gagal.append(
                        "Baris baru memakai %s yang sudah dihapus: %s"
                        % (nama, baris.strip()[:80]))
                    sisa.discard(nama)


def _cek_bekas_sebenih(gagal, diff, ref):
    """cek_panggil_bekas_hapusan untuk SATU segmen diff dengan
    benih pohonnya sendiri (issue #1346): mode bawaan mencampur
    diff commit (pohon HEAD/akar), diff staged (indeks), dan diff
    pohon kerja dalam satu string — satu REF_BENIH global tak bisa
    mewakili ketiganya, jadi tiap segmen diperiksa dengan benih
    yang menggambarkan sisi-baru segmen itu, cache benih dikosongkan
    antar segmen."""
    global REF_BENIH
    simpan = REF_BENIH
    REF_BENIH = ref
    _cache_benih.clear()
    try:
        cek_panggil_bekas_hapusan(gagal, diff)
    finally:
        REF_BENIH = simpan
        _cache_benih.clear()


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
    isi = _baca_pohon(path)
    if isi is None:
        return None
    # Kurung dihitung atas teks yang string DAN komentarnya
    # dikupas beruntun (issue #1350): pengupasan komentar saja
    # menyisakan isi literal string, dan kurung di dalamnya
    # ("}" dalam string) menggeser kedalaman sehingga pemilik
    # baris salah atau hilang. Mesin beruntun membawa keadaan
    # string mentah/komentar blok antarbaris.
    bersih_list, _akhir = _kupasan_baris_beruntun(isi.splitlines())
    bersih = "\n".join(bersih_list)
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
    # Perbandingan per DIREKTORI, bukan per berkas (issue
    # #1307 diperluas #1328): terjemahan sebuah string boleh
    # tinggal di berkas values-in yang BERBEDA dari berkas
    # values Inggrisnya — perbandingan per pasangan berkas
    # se-nama tak pernah mempertemukan pasangan semacam itu,
    # jadi beda %d vs %s-nya lolos. Kini semua berkas .xml di
    # tiap direktori digabung jadi satu peta per locale
    # (meniru peta_item plurals/array di bawah).
    def peta_string_dir(dirpath):
        peta = {}
        if not os.path.isdir(dirpath):
            return peta
        for nama_berkas in sorted(os.listdir(dirpath)):
            if not nama_berkas.endswith(".xml"):
                continue
            isi_b = baca(os.path.relpath(os.path.join(dirpath, nama_berkas), AKAR))
            if isi_b is None:
                continue
            for nama, teks in pola.findall(kupas_komentar_xml(isi_b)):
                peta.setdefault(nama, (teks, nama_berkas))
        return peta

    dasar_res = os.path.join(AKAR, "app/src/main/res")
    peta_en = peta_string_dir(os.path.join(dasar_res, "values"))
    peta_in = peta_string_dir(os.path.join(dasar_res, "values-in"))
    for nama, (teks, berkas_in) in sorted(peta_in.items()):
        if nama in peta_en and argumen_format(teks) != argumen_format(peta_en[nama][0]):
            gagal.append("Argumen format beda di values-in/%s: %s" % (berkas_in, nama))
    # Argumen format di dalam <item> plurals/string-array juga wajib
    # paritas (issue #1045): cek di atas hanya melihat <string>, jadi
    # plurals "%d files" vs "%s berkas" lolos — padahal kelas crash
    # formatnya sama persis. Plurals dibandingkan per quantity,
    # string-array per indeks item.
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
    # Daftar & isi dari pohon yang dinilai (issue #1400):
    # pembaca sebutan terakhir yang masih menilai berkas
    # kerja pada mode rentang — peringatan cakupan bisa
    # palsu dua arah begitu checkout menyimpang dari pohon
    # rentang (doktrin _baca_pohon/_jalan_pohon).
    for path in _jalan_pohon(os.path.join("app", "src", "test")):
        # Komentar dikupas dulu (issue #1287, keluarga
        # #1218/#1254): sebutan di komentar berkas uji —
        # "// ubahSaya109() akan diuji nanti" — bukan cakupan
        # uji, tetapi pola mentah memvonisnya sudah teruji dan
        # peringatan cakupan mati.
        mentah = _baca_pohon(path)
        if mentah is None:
            continue
        bersih = _kupas_komentar_kode(mentah, bersarang=not path.endswith(".java"))
        if pola.search(bersih):
            return True
    return False


def cek_uji_baru(peringatan, diff):
    # Pengenal deklarasi (issue #1378, menyempurnakan #1364):
    # pola lama "slot tipe + nama" menelan indentasi dan kata
    # kepala baris, sehingga panggilan polos berindentasi,
    # kelanjutan konstruktor, dan kepala else/yield/new ikut
    # tercap metode baru. Kini deklarasi hanya diakui dalam
    # dua bentuk: (1) Kotlin — ada kata "fun" sebelum nama
    # (termasuk fungsi ekstensi ber-penerima, generik
    # bersarang, dan anotasi berargumen — issue #1398);
    # (2) Java — <pengubah*> <Tipe> <nama>( dengan Tipe
    # berawalan huruf besar atau primitif/void (nama-penuh
    # bertitik seperti java.util.List<String> juga sah —
    # cukup satu huruf besar di token tipenya, issue #1389).
    # Nama
    # berawalan huruf besar (kelas/konstruktor) dilewati,
    # seperti di cek_uji_ubah.
    # Batas slot anotasi (issue #1467, dikoreksi #1474
    # lewat harness banding tag-vs-HEAD audit-121):
    # argumen anotasi diterima sampai kurung bersarang
    # TIGA tingkat dengan string dasar di semua tingkat;
    # yang lolos = sarang EMPAT tingkat, char literal
    # berkurung (mis. @A(@B(')'))), dan string ber-escape
    # yang tak terurai penguraiannya. Ini batas
    # terdokumentasi, bukan keheningan tak disengaja.
    pola_fun = re.compile(
        r"^\s*(?:@\w+(?:\((?:[^()\"]|\"[^\"]*\"|\((?:[^()\"]|\"[^\"]*\"|\((?:[^()\"]|\"[^\"]*\")*\))*\))*\))?\s+)*(?:\w+\s+)*?fun\s+(?:<.*>\s*)?(?:[\w]+(?:\.[\w]+)*(?:<.+>)?\.)?(\w+)\s*\(")
    pola_java = re.compile(
        r"^\s*(?:(?:public|private|protected|static|final|abstract|synchronized)\s+|@\w+(?:\((?:[^()\"]|\"[^\"]*\"|\((?:[^()\"]|\"[^\"]*\"|\((?:[^()\"]|\"[^\"]*\")*\))*\))*\))?\s+)*"
        r"(?:void|int|long|boolean|double|float|byte|short|char|(?:[\w.]*[A-Z][\w<>\[\],.?]*|[\w]+\.[\w.<>\[\],?]*))(?:\[\])*\s+(\w+)\s*\(")
    sudah = set()
    for _, baris in tambah_kode(diff):
        cocok = pola_fun.match(baris) or pola_java.match(baris)
        if not cocok:
            continue
        nama = cocok.group(1)
        if nama in KATA_KUNCI or nama in sudah or nama[:1].isupper():
            continue
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

    global REF_BENIH, REF_POHON, _isi_strings_wajib, _MODUL_CACHE, _cek_tag_sudah_jalan
    # Reset keadaan modul di awal lari (issue #1528):
    # REF_POHON/REF_BENIH hanya dipasang cabang --rentang
    # dan tak pernah dikembalikan, baca() ter-lru_cache,
    # dan memo _isi_strings_wajib abadi — satu proses yang
    # menjalankan gerbang dua kali (impor harness) menilai
    # lari kedua dengan pohon & isi basi dari lari pertama
    # dan vonis bisa hilang senyap.
    REF_BENIH = None
    REF_POHON = None
    _isi_strings_wajib = None
    baca.cache_clear()
    # Cache baris benih (issue #1545): kuncinya hanya
    # path, isinya blob REF_BENIH — mengganti ref di
    # antara dua lari tanpa membersihkannya membuat
    # _keadaan_sebelum_baris menilai berkas benih lama.
    _cache_benih.clear()
    # Cache daftar modul (issue #1546): settings.gradle
    # yang berubah di antara dua lari tak terbaca ulang
    # tanpa penolan ini.
    _MODUL_CACHE = None
    # Bendera sekali-jalan cek tag (issue #1855):
    # bendera ini tertinggal dari daftar reset di atas —
    # proses yang menjalankan utama() dua kali (suite,
    # fuzzer, pemakaian pustaka) melewatkan seluruh
    # penilaian pesan/branch/tag pada lari kedua SENYAP.
    # Suite dan fuzzer sampai menyetel bendera privat
    # ini dengan tangan di beberapa tempat sebagai
    # tambalan; kini prolog sendiri yang menolkannya.
    _cek_tag_sudah_jalan = False
    # Ikatan pemindai token dimuat ulang (issue #1569):
    # binding dibekukan saat impor — impor yang terjadi
    # saat cek-token.py hilang mengikat fallback literal
    # permanen walau berkasnya kembali di proses yang
    # sama (jangkauan harness-impor).
    # Daftarnya kini lengkap (issue #1865): _padatkan
    # dan _penilai_fragmen berasal dari modul yang sama
    # dan tertinggal beku — penilai fragmen lokal
    # pra-#1742 hidup lagi seumur proses sesudah impor
    # yang gagal memuat cek-token.py.
    global mengandung_token, mengandung_token_pesan, _padatkan, _penilai_fragmen
    mengandung_token = _muat_mengandung_token()
    mengandung_token_pesan = _muat_mengandung_token_pesan()
    _padatkan = _muat_padatkan()
    _penilai_fragmen = _muat_penilai_fragmen()
    gagal = []
    peringatan = []
    diff = ""
    segmen_bekas = None
    rentang_lokal = ""
    rentang_isi = ""
    if args.jalur:
        # Jalur yang salah ketik tak boleh hilang diam-diam menjadi
        # "LOLOS (0 berkas diperiksa)" (issue #681).
        for j in args.jalur:
            if not os.path.exists(os.path.join(AKAR, j)):
                gagal.append("Jalur tidak ditemukan: " + j)
        berubah = [j for j in args.jalur if os.path.exists(os.path.join(AKAR, j))]
        diff_kerja = jalan("git", "diff", "--", *berubah) or ""
        # Perubahan staged ikut diperiksa (issue #1349): tanpa
        # --cached, berkas yang sudah di-git-add menghasilkan
        # diff kosong dan seluruh cek berbasis diff terlewati
        # senyap padahal keluaran mengaku berkas diperiksa.
        diff_indeks = jalan("git", "diff", "--cached", "--", *berubah) or ""
        diff = diff_kerja + diff_indeks
        # Segmen benih cek bekas-hapusan (issue #1361, sisa
        # #1346/#1349): hunk staged menggambarkan pohon indeks,
        # jadi disemai dari indeks — satu lari berbenih berkas
        # kerja membalik vonis bila indeks dan berkas kerja
        # menyimpang, persis kelas bug mode bawaan dulu.
        segmen_bekas = [(diff_kerja, None), (diff_indeks, ":index")]
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
            elif jalan("git", "rev-parse", "--verify", "--quiet", ujung + "^{commit}") is None:
                # Ujung tak valid divalidasi seperti awal
                # (issue #1363): dulu ujung langsung dipasang
                # sebagai benih/pohon, git show gagal, diff
                # kosong, dan semua cek berbasis diff terlewati
                # senyap dengan vonis LOLOS — salah ketik ref
                # ujung lolos sebagai "bersih".
                gagal.append("Rentang diff tak valid: %s (revisi %s tidak ditemukan)." % (args.rentang, ujung))
                berubah = []
                diff = ""
            else:
                print("info: rentang tak valid (push pertama?) — cek diff %s." % ujung)
                REF_BENIH = ujung
                REF_POHON = ujung
                berubah = [p for p in jalan_repo("app") if not p.endswith(".md")]
                diff = jalan("git", "show", "--format=", "--patch", ujung) or ""
        else:
            berubah = daftar
            rentang_lokal = args.rentang
            a, b = re.split(r"\.\.+", args.rentang, maxsplit=1)
            REF_BENIH = b or "HEAD"
            REF_POHON = REF_BENIH
            pemisah = "..." if "..." in args.rentang else ".."
            diff = jalan("git", "diff", a + pemisah + b) or ""
            cek_secret_riwayat(gagal, args.rentang)
            cek_secret_pesan(gagal, args.rentang)
    else:
        hulu = jalan("git", "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}")
        if hulu:
            rentang_lokal = hulu.strip() + "...HEAD"
            rentang_isi = hulu.strip() + "..HEAD"
            REF_BENIH = "HEAD"
            diff = jalan("git", "diff", rentang_lokal) or ""
            segmen_bekas = [(diff, "HEAD")]
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
                diff_akar = jalan("git", "show", "--format=", "--patch", akar_sha) or ""
                diff_sisa = jalan("git", "diff", akar_sha + "...HEAD") or ""
                diff = diff_akar + diff_sisa
                segmen_bekas = [(diff_akar, akar_sha), (diff_sisa, "HEAD")]
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
        diff_kerja = jalan("git", "diff") or ""
        diff_indeks = jalan("git", "diff", "--cached") or ""
        diff += diff_kerja
        diff += diff_indeks
        if segmen_bekas is not None:
            # Segmen benih cek bekas-hapusan (issue #1346): pohon
            # kerja untuk diff tak-staged, indeks untuk diff staged.
            segmen_bekas += [(diff_kerja, None), (diff_indeks, ":index")]
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

    # Keberadaan direktori uji pada mode berkas kerja
    # dinilai dari berkas kerja (issue #1422: checkout tanpa
    # dir-uji menelan seluruh cek uji, keluarga #1400).
    # Pada mode RENTANG saklar tidak mematikan cek (arah
    # terbalik #1439): himpunan uji dibaca dari pohon, dan
    # pohon ujung TANPA dir-uji berarti cakupan nol —
    # keadaan yang justru paling layak diperingatkan.
    ada_uji = True if REF_POHON else os.path.isdir(UJI)
    cek_xml(gagal)
    cek_acuan_yatim(gagal, peringatan)
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
        hapus += _ambil_rename_berisiko(keluar_staged, "HEAD", ":index")
    cek_berkas_kode_dihapus(gagal, hapus)
    if not args.semua:
        cek_pengingat_changelog(peringatan, berubah)
    if diff:
        if segmen_bekas is not None:
            for d_seg, ref_seg in segmen_bekas:
                if d_seg:
                    _cek_bekas_sebenih(gagal, d_seg, ref_seg)
        else:
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
