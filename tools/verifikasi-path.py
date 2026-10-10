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
# Nama metode yang sengaja dihapus total dimuat dari tools/bekas-hapusan.txt
# (satu nama per baris, # = komentar): bila baris baru masih memanggilnya,
# itu regresi hapusan. Tambahkan nama ke berkas itu tiap menghapus metode.
def muat_bekas_hapusan():
    nama = set()
    try:
        with open(os.path.join(AKAR, "tools", "bekas-hapusan.txt"),
                  encoding="utf-8") as f:
            for baris in f:
                baris = baris.strip()
                if baris and not baris.startswith("#"):
                    nama.add(baris.split()[0])
    except OSError:
        pass
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


def daftar_berkas_berubah(rentang):
    """Nama file berubah pada rentang diff (None bila revisi tak valid)."""
    if rentang:
        for pemisah in ("...", ".."):
            if pemisah in rentang:
                a, b = rentang.split(pemisah, 1)
                if set(a) == {"0"} or not a:
                    return None  # push pertama: tak ada pembanding
                out = jalan("git", "diff", "--name-only", a + pemisah + b)
                if out is not None:
                    return [x for x in out.splitlines() if x]
                return None
    return None


def nama_tak_lacak():
    out = jalan("git", "ls-files", "--others", "--exclude-standard")
    return out.splitlines() if out else []


def baca(path):
    try:
        with open(os.path.join(AKAR, path), encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return ""


def jalan_repo(rel, ext=EKSTENSI_KODE):
    for akar, _, berkas in os.walk(os.path.join(AKAR, rel)):
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

    pola_def = re.compile(r'<(style|string|color|dimen|plurals)\s+name="([^"]+)"')
    pola_array = re.compile(r'<(string-array|integer-array|array)\s+name="([^"]+)"')

    def tambah(kunci, nama):
        definisi.setdefault(kunci, set()).add(rapikan(kunci, nama))

    akar_res = os.path.join(MAIN, "res")
    for path in jalan_repo(os.path.join("app", "src", "main", "res"), ext=(".xml",)):
        isi = baca(path)
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
        isi = baca(path)
        if not isi:
            continue
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


def cek_xml(gagal, basis_desc):
    buruk = []
    for path in jalan_repo(os.path.join("app", "src", "main", "res"), ext=(".xml",)):
        try:
            ET.parse(os.path.join(AKAR, path))
        except ET.ParseError as e:
            buruk.append("%s (%s)" % (path, e))
    for path in ("app/src/main/AndroidManifest.xml",):
        try:
            ET.parse(os.path.join(AKAR, path))
        except ET.ParseError as e:
            buruk.append("%s (%s)" % (path, e))
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


def namespace_aplikasi():
    tetap = baca("app/build.gradle")
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


def cek_duplikat_definisi(gagal):
    """Nama ganda dalam SATU berkas values = galat aapt (override beda
    berkas seperti values-night tetap sah)."""
    pola = re.compile(r'<(style|string|color|dimen|plurals|string-array|integer-array|array)\s+name="([^"]+)"')
    for path in jalan_repo(os.path.join("app", "src", "main", "res"), ext=(".xml",)):
        if "/values" not in path.replace(os.sep, "/"):
            continue
        hitung = {}
        for jenis, nama in pola.findall(baca(path)):
            kunci = jenis + "/" + nama
            hitung[kunci] = hitung.get(kunci, 0) + 1
        ganda = sorted(k for k, n in hitung.items() if n > 1)
        if ganda:
            gagal.append("Definisi ganda di %s: %s" % (path, ", ".join(ganda[:5])))


def berkas_dihapus(rentang, diff_biasa):
    """Daftar path yang dihapus pada diff (butuh status; kosong bila tak tahu)."""
    if rentang and ("..." in rentang or ".." in rentang):
        for pemisah in ("...", ".."):
            if pemisah in rentang:
                a, b = rentang.split(pemisah, 1)
                if set(a) == {"0"} or not a:
                    return []
                out = jalan("git", "diff", "--name-status", a + pemisah + b)
                if out is None:
                    return []
                return [x.split(None, 1)[1] for x in out.splitlines()
                        if x.startswith("D")]
    out = jalan("git", "diff", "--name-status")
    return [x.split(None, 1)[1] for x in (out or "").splitlines() if x.startswith("D")]


def disebut_kata(nama, lewat=()):
    pola = re.compile(r"\b%s\b" % re.escape(nama))
    temu = []
    for path in jalan_repo("app", ext=EKSTENSI_KODE + (".xml",)):
        if path in lewat:
            continue
        for i, baris in enumerate(baca(path).splitlines(), 1):
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


def tambah_kode(diff):
    """Hasilkan (path, isi) tiap baris tambah dari hunk berkas kode."""
    path = ""
    for baris in diff.splitlines():
        if baris.startswith("diff --git "):
            bagian = baris.split()
            path = bagian[-1][2:] if len(bagian) >= 4 else ""
        elif baris.startswith("+") and not baris.startswith("+++"):
            if path.endswith(EKSTENSI_KODE):
                yield path, baris[1:]


def hapus_kode(diff):
    """Hasilkan (path, isi) tiap baris hapus dari hunk berkas kode."""
    path = ""
    for baris in diff.splitlines():
        if baris.startswith("diff --git "):
            bagian = baris.split()
            path = bagian[-1][2:] if len(bagian) >= 4 else ""
        elif baris.startswith("-") and not baris.startswith("---"):
            if path.endswith(EKSTENSI_KODE):
                yield path, baris[1:]


def kupas_kode(isi):
    """Buang komentar + literal sekali-jalan (termasuk string mentah Kotlin)."""
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
            i = n if akhir < 0 else akhir + 3
        elif dua == '//':
            while i < n and isi[i] != chr(10):
                i += 1
        elif dua == '/*':
            i += 2
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
        isi = kupas_kode(baca(path))
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
    pola = re.compile(r'\+\s*<(style|string|color|dimen|plurals|string-array|integer-array|array)\s+name="([^"]+)"')
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
    if not os.path.isfile(os.path.join(AKAR, "app/src/main/res/values-in/strings.xml")):
        return
    def nama_string(path):
        return set(re.findall(r'<string\s+name="([^"]+)"', baca(path)))

    inggris = nama_string("app/src/main/res/values/strings.xml")
    indonesia = nama_string("app/src/main/res/values-in/strings.xml")
    if inggris and indonesia:
        kurang = sorted(inggris - indonesia)
        lebih = sorted(indonesia - inggris)
        if kurang:
            gagal.append("values-in kurang string: " + ", ".join(kurang[:10]))
        if lebih:
            gagal.append("values-in berlebih string: " + ", ".join(lebih[:10]))


def cek_aturan_repo(gagal, berubah):
    for path in berubah:
        rendah = path.lower()
        if rendah.endswith((".apk", ".aab", ".ap_", ".dex", ".jks",
                             ".keystore", ".p12", ".pfx", ".env")):
            gagal.append("Berkas terlarang masuk repo: " + path)
        elif rendah == "local.properties" or rendah.endswith("/local.properties"):
            gagal.append("local.properties tak boleh masuk repo: " + path)
        elif path.startswith("app/src/main/"):
            try:
                ukuran = os.path.getsize(os.path.join(AKAR, path))
            except OSError:
                ukuran = 0
            if ukuran > 1024 * 1024:
                gagal.append("Berkas >1 MB di app/src: " + path)


POLA_TOKEN_ASLI = re.compile(r"[0-9]{6,}:[A-Za-z0-9_-]{35,}")


def cek_secret(gagal, diff):
    for baris in diff.splitlines():
        if not baris.startswith("+") or baris.startswith("+++"):
            continue
        isi = baris[1:]
        if "[" in isi or "]" in isi:
            continue  # definisi regex seperti TOKEN_REGEX memakai kurung siku
        if POLA_TOKEN_ASLI.search(isi):
            gagal.append("Kemungkinan token bot asli ikut diff — kredensial "
                         "hanya via input manual SetupActivity: %s"
                         % isi.strip()[:60])
            break


def nama_metode_hilang(diff, lewati_berkas=()):
    """Nama metode pada baris dihapus yang definisinya hilang dari pohon kerja."""
    hilang = []
    for path, baris in hapus_kode(diff):
        rapi = baris.rstrip()
        if not re.search(r"\b(public|protected|private|static|fun|override|suspend)\b", baris) \
                and not rapi.endswith("{") \
                and (rapi.endswith(";")
                     or not re.match(r"\s*\w[\w<>\[\].,? ]*\s+\w+\s*\(", baris)):
            continue
        if path in lewati_berkas or not os.path.isfile(os.path.join(AKAR, path)):
            continue
        cocok = re.match(
            r"\s*(?:(?:public|protected|private)\s+)?(?:static\s+)?"
            r"(?:fun\s+)?[\w<>\[\].,? ]+\s+(\w+)\s*\(", baris)
        if cocok and cocok.group(1) not in KATA_KUNCI:
            hilang.append(cocok.group(1))
    return hilang


def masih_didefinisikan(nama):
    pola = re.compile(r"\b%s\s*\(" % re.escape(nama))
    for path in jalan_repo(os.path.join("app", "src", "main", "java")):
        if pola.search(baca(path)):
            return True
    return False


def pemanggil(nama):
    pola = re.compile(r"\b%s\s*\(" % re.escape(nama))
    temu = []
    for path in jalan_repo("app", ext=EKSTENSI_KODE):
        for i, baris in enumerate(baca(path).splitlines(), 1):
            if pola.search(baris):
                temu.append("%s:%d" % (path, i))
                if len(temu) >= 6:
                    return temu
    return temu


def cek_metode_hilang(gagal, peringatan, diff, hapus=()):
    lewati_berkas = set(hapus)
    for nama in sorted(nama_metode_hilang(diff, lewati_berkas)):
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


def cek_panggil_bekas_hapusan(gagal, diff):
    for nama in sorted(BEKAS_HAPUSAN):
        pola = re.compile(r"\b%s\s*\(" % re.escape(nama))
        for _, baris in tambah_kode(diff):
            if pola.search(baris):
                gagal.append(
                    "Baris baru memanggil %s() yang sudah dihapus: %s"
                    % (nama, baris.strip()[:80]))
                break


def hunk_fungsi(diff):
    """Hasilkan (path, nama_fungsi) dari kepala hunk diff."""
    path = ""
    for baris in diff.splitlines():
        if baris.startswith("diff --git "):
            bagian = baris.split()
            path = bagian[-1][2:] if len(bagian) >= 4 else ""
        elif baris.startswith("@@ ") and path.endswith(EKSTENSI_KODE):
            ekor = baris.split("@@", 2)[-1]
            cocok = re.search(r"(\w+)\s*\(", ekor)
            if cocok:
                yield path, cocok.group(1)


def cek_uji_ubah(peringatan, diff):
    sudah = set()
    for _, nama in hunk_fungsi(diff):
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


def nama_warna(path):
    return set(re.findall(r'<color\s+name="([^"]+)"', baca(path)))


def cek_paritas_resource(gagal):
    """Paritas siang/malam otomatis (GAGAL bila miring)."""
    try:
        siang = nama_warna("app/src/main/res/values/colors.xml")
        malam = nama_warna("app/src/main/res/values-night/colors.xml")
        if siang and malam:
            if siang - malam:
                gagal.append("colors.xml siang berlebih vs night: "
                             + ", ".join(sorted(siang - malam)[:10]))
            if malam - siang:
                gagal.append("values-night/colors.xml berlebih vs siang: "
                             + ", ".join(sorted(malam - siang)[:10]))
    except OSError as e:
        gagal.append("Paritas resource tak terbaca: %s" % e)


def cek_import_mati(gagal):
    """Import Kotlin/Java yang namanya tak dipakai di kode (GAGAL)."""
    for path in jalan_repo("app", ext=EKSTENSI_KODE):
        bersih = kupas_kode(baca(path))
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
        if pola.search(baca(path)):
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


def cek_pola_berisiko(peringatan, diff, berubah):
    for _, isi in tambah_kode(diff):
        if "System.out.println" in isi:
            peringatan.append("System.out.println di diff (pakai Log/Toast): " + isi.strip()[:80])
        if ".printStackTrace()" in isi:
            peringatan.append("printStackTrace di diff (telan/abaikan eksplisit): " + isi.strip()[:80])
    cek = jalan("git", "diff", "--check")
    if cek:
        peringatan.append("Rapikan spasi diff --check: " + cek.strip().splitlines()[0][:100])


def utama():
    parser = argparse.ArgumentParser(description="Verifikasi path yang baru dikerjakan.")
    parser.add_argument("jalur", nargs="*", help="berkas spesifik (opsional)")
    parser.add_argument("--semua", action="store_true", help="cek seluruh repo")
    parser.add_argument("--rentang", default="", help="rentang diff A...B (dipakai CI)")
    args = parser.parse_args()

    gagal = []
    peringatan = []
    diff = ""
    if args.jalur:
        berubah = [j for j in args.jalur if os.path.exists(os.path.join(AKAR, j))]
        diff = jalan("git", "diff", "--", *berubah) or ""
    elif args.rentang:
        daftar = daftar_berkas_berubah(args.rentang)
        if daftar is None:
            print("info: rentang tak valid (push pertama?) — cek keadaan penuh.")
            berubah = [p for p in jalan_repo("app") if not p.endswith(".md")]
        else:
            berubah = daftar
            a, b = re.split(r"\.\.+", args.rentang, maxsplit=1)
            diff = jalan("git", "diff", a + "..." + b) or ""
    else:
        hulu = jalan("git", "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}")
        if hulu:
            diff = jalan("git", "diff", hulu.strip() + "...HEAD") or ""
            daftar = jalan("git", "diff", "--name-only", hulu.strip() + "...HEAD") or ""
            berubah = daftar.splitlines()
        else:
            diff = jalan("git", "diff", "HEAD~1...HEAD") or ""
            berubah = (jalan("git", "diff", "--name-only", "HEAD~1...HEAD") or "").splitlines()
        kotor = jalan("git", "status", "--porcelain") or ""
        for baris in kotor.splitlines():
            nama = baris[3:].strip().strip('"')
            if nama and nama not in berubah:
                berubah.append(nama)
        # Diff kerja yang belum commit ikut diperiksa agar hapusan salah
        # tertangkap sebelum commit, bukan sesudah push.
        diff += (jalan("git", "diff") or "")
        diff += jalan("git", "diff", "--cached") or ""
    berubah += [b for b in nama_tak_lacak() if b not in berubah]
    if args.semua:
        berubah = [p for p in jalan_repo("app")]

    ada_uji = os.path.isdir(UJI)
    cek_xml(gagal, "")
    cek_acuan_yatim(gagal)
    cek_manifest(gagal)
    cek_duplikat_definisi(gagal)
    cek_sinkron_terjemah(gagal)
    cek_import_mati(gagal)
    cek_paritas_resource(gagal)
    cek_aturan_repo(gagal, berubah)
    cek_secret(gagal, diff)
    cek_kurung_kode(gagal, berubah)
    hapus = berkas_dihapus(args.rentang, diff)
    if not args.rentang:
        keluar = jalan("git", "diff", "--cached", "--name-status") or ""
        hapus += [x.split(None, 1)[1] for x in keluar.splitlines() if x.startswith("D")]
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
        cek_pola_berisiko(peringatan, diff, berubah)
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
