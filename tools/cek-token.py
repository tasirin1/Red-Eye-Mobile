#!/usr/bin/env python3
"""Pemindai token bot untuk gerbang repo (issue #765).

Mengenali bentuk-bentuk token:
  1. literal utuh satu baris (pola lama),
  2. terbelah antar baris / antar literal string / oleh pemisah kode
     lain (".", "${...}", tanda kurung) — dipadatkan dulu (issue #772),
  3. ter-encode base64 (kandidat panjang didekode lalu dicocokkan),
     termasuk base64 yang terbelah lalu tersambung lagi sesudah
     pemadatan (issue #772),
  4. ter-encode hex (kandidat hex panjang didekode lalu dicocokkan,
     issue #784).

Dipakai tools/verifikasi-path.py (diimpor) dan scripts/cek-cepat.sh:
    cek-token.py --teks     : baca teks dari stdin; cetak DITEMUKAN bila ada
    cek-token.py --berkas   : baca daftar path NUL-separated dari stdin;
                              cetak path pertama yang mengandung token
Kode keluar selalu 0; keputusan dibaca dari keluaran stdout.
"""
import base64
import re
import sys

POLA = re.compile(r"[0-9]{6,}:[A-Za-z0-9_-]{35,}")
POLA_BYTE = re.compile(rb"[0-9]{6,}:[A-Za-z0-9_-]{35,}")
POLA_KANDIDAT_B64 = re.compile(r"[A-Za-z0-9+/=_-]{48,}")
POLA_KANDIDAT_HEX = re.compile(r"[0-9a-fA-F]{80,}")


def _cocok_b64(kandidat):
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
            if POLA_BYTE.search(hasil):
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
    rapat = re.sub(r"\$\{[^}]*\}", "", teks)
    rapat = re.sub(r"[\s\"'\\+]+", "", rapat)
    rapat = re.sub(r"(?<=[A-Za-z0-9_-])\.(?=[A-Za-z0-9_-])", "", rapat)
    # Koma yang diapit karakter token juga pemisah belah (issue #802):
    # base64/hex yang dipecah elemen arrayOf("...", "...") tersambung
    # lagi. Sempit seperti titik di atas — koma berkawan spasi/kurung
    # (pemisah argumen biasa) tidak ikut terhapus karena sesudah
    # langkah sebelumnya spasi sudah hilang; yang tersisa berkoma
    # rapat memang bentuk pecahan literal.
    return re.sub(r"(?<=[A-Za-z0-9+/=_-]),(?=[A-Za-z0-9+/=_-])", "", rapat)


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
    return None


def dekode_berkas(data):
    # Berkas UTF-16 (lazim dari editor Windows / ekspor log) membawa
    # BOM; dekode sesuai BOM agar byte NUL antar karakter tidak
    # memutus pola (issue #785). Tanpa BOM: tebak UTF-16 dari pola
    # NUL (issue #817), sisanya UTF-8 seperti sebelumnya.
    if data.startswith(b"\xff\xfe") or data.startswith(b"\xfe\xff"):
        return data.decode("utf-16", errors="replace")
    if data.startswith(b"\xef\xbb\xbf"):
        return data.decode("utf-8-sig", errors="replace")
    enc = _utf16_tanpa_bom(data)
    if enc:
        return data.decode(enc, errors="replace")
    return data.decode("utf-8", errors="replace")


def mengandung_token(teks):
    if not teks:
        return False
    if POLA.search(teks):
        return True
    rapat = _padatkan(teks)
    if rapat != teks and POLA.search(rapat):
        return True
    tanpa_spasi = re.sub(r"\s+", "", teks)
    for sumber in (teks, tanpa_spasi, rapat):
        for m in POLA_KANDIDAT_B64.finditer(sumber):
            if _cocok_b64(m.group(0)):
                return True
    # Bentuk hex (issue #784): token ASCII 42+ karakter menjadi 84+
    # karakter hex; ambang 80 memberi ruang. Hasil dekode tetap harus
    # cocok pola token persis, jadi hash panjang biasa tidak tertuduh.
    # Sumber kini termasuk teks terpadatkan (issue #802): hex yang
    # terbelah gabung-string/koma tersambung lagi di sana.
    for sumber in (teks, tanpa_spasi, rapat):
        for m in POLA_KANDIDAT_HEX.finditer(sumber):
            s = m.group(0)
            if len(s) % 2 == 1:
                s = s[:-1]
            try:
                hasil = bytes.fromhex(s)
            except Exception:
                continue
            if POLA_BYTE.search(hasil):
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
        if POLA_BYTE.search(hasil):
            return True
    return False


def main():
    mode = sys.argv[1] if len(sys.argv) > 1 else ""
    if mode == "--teks":
        if mengandung_token(sys.stdin.read()):
            print("DITEMUKAN")
        return 0
    if mode == "--berkas":
        for path in sys.stdin.buffer.read().split(b"\0"):
            if not path:
                continue
            nama = path.decode("utf-8", "replace")
            try:
                with open(nama, "rb") as f:
                    isi = dekode_berkas(f.read())
            except OSError:
                continue
            if mengandung_token(isi):
                print(nama)
                return 0
        return 0
    print("pemakaian: cek-token.py --teks | --berkas", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main())
