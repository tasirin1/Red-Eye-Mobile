#!/usr/bin/env python3
"""Pemindai token bot untuk gerbang repo (issue #765).

Mengenali tiga bentuk token:
  1. literal utuh satu baris (pola lama),
  2. terbelah antar baris / antar literal string (dipadatkan dulu),
  3. ter-encode base64 (kandidat panjang didekode lalu dicocokkan).

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


def mengandung_token(teks):
    if not teks:
        return False
    if POLA.search(teks):
        return True
    rapat = re.sub(r"[\s\"'\\+]+", "", teks)
    if rapat != teks and POLA.search(rapat):
        return True
    tanpa_spasi = re.sub(r"\s+", "", teks)
    for sumber in (teks, tanpa_spasi):
        for m in POLA_KANDIDAT_B64.finditer(sumber):
            if _cocok_b64(m.group(0)):
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
