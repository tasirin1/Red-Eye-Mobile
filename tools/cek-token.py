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
    sempit daripada isi berkas."""
    if not teks:
        return False
    if POLA.search(teks):
        return True
    tanpa_spasi = re.sub(r"\s+", "", teks)
    for sumber in (teks, tanpa_spasi):
        for m in POLA_KANDIDAT_B64.finditer(sumber):
            if _cocok_b64(m.group(0)):
                return True
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
