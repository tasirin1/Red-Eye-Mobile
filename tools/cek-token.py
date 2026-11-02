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
    return re.sub(r"(?<=[A-Za-z0-9_-])\.(?=[A-Za-z0-9_-])", "", rapat)


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
    for sumber in (teks, tanpa_spasi):
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
                with open(nama, encoding="utf-8", errors="replace") as f:
                    isi = f.read()
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
