#!/usr/bin/env python3
"""Fuzzer gerbang token — alat UJI-DIRI untuk tools/cek-token.py
dan kanal belah tools/verifikasi-path.py. Bukan bagian dari
gerbang produksi; tidak dipanggil cek-cepat/verifikasi-path.

Latar: perbaikan gerbang selama ini reaktif — audit menemukan
satu kelas lolos (#1832 komposisi dekoder, #1833 belahan tak
seimbang, #1837 byteArrayOf, #1838 fragmen tengah pendek), lalu
gerbang ditambal. Fuzzer ini membalik arahnya: ia membangkitkan
sendiri matriks penghindaran dari token kontrol sintetis —
encode berlapis, sisipan pemisah, belahan lintas permukaan,
ejaan byteArrayOf — lalu menuntut vonis yang sesuai doktrin:

  - "tertuduh": wajib tertangkap; lolos = LOLOS (regresi/celah
    baru) dan membuat kode keluar 1.
  - "bersih":   wajib tidak tertuduh; tertuduh = POSITIF PALSU
    dan membuat kode keluar 1.
  - "batas":    di luar cakupan desain yang terdokumentasi
    (mis. tiga lapis dekode #1802/#1813, pemadatan di permukaan
    pesan #1425, fragmen tengah < 8 #1838). Lolosnya dicatat
    sebagai info; tertangkapnya dicatat sebagai bonus — dua-
    duanya bukan kegagalan. Batas hanya boleh dipindah ke
    "tertuduh" dengan merujuk issue/doktrin yang mengubahnya.

Jalankan: python3 tools/fuzz-gerbang.py [--seed N]
Deterministik untuk seed yang sama (bawaan 140).
"""

import base64
import importlib.util
import os
import random
import sys
import urllib.parse

_AKAR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _muat(nama, relatif):
    spec = importlib.util.spec_from_file_location(
        nama, os.path.join(_AKAR, relatif))
    modul = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(modul)
    return modul


cek_token = _muat("cek_token_fuzz", os.path.join("tools", "cek-token.py"))
verifikasi = _muat("verifikasi_fuzz",
                   os.path.join("tools", "verifikasi-path.py"))

# CONTOH-TOKEN-MULAI
_KP = "123456789:AAEaaBB-cc"
_EK = "_ddEEFFggHHiiJJkkLLmmNNooPPq"
# CONTOH-TOKEN-SELESAI
TOKEN = _KP + _EK
ENC = base64.urlsafe_b64encode(TOKEN.encode()).decode().rstrip("=")


def _persen(teks):
    return urllib.parse.quote(teks, safe="")


def _entitas_des(teks):
    return "".join("&#%d;" % ord(c) for c in teks)


def _entitas_hex(teks):
    return "".join("&#x%x;" % ord(c) for c in teks)


def _esc_u(teks):
    return "".join("\\u%04x" % ord(c) for c in teks)


def _esc_x(teks):
    return "".join("\\x%02x" % ord(c) for c in teks)


def _b64(teks):
    return base64.b64encode(teks.encode()).decode()


def _b64url(teks):
    return base64.urlsafe_b64encode(teks.encode()).decode().rstrip("=")


def _hex(teks):
    return teks.encode().hex()


def _b32(teks):
    return base64.b32encode(teks.encode()).decode()


def _b32k(teks):
    return base64.b32encode(teks.encode()).decode().lower()


ENKODER = {
    "persen": _persen,
    "entitas": _entitas_des,
    "entitashex": _entitas_hex,
    "escape-u": _esc_u,
    "escape-x": _esc_x,
    "b64": _b64,
    "b64url": _b64url,
    "hex": _hex,
    "b32": _b32,
    "b32kecil": _b32k,
}

# Kasus: (keluarga, nama, permukaan, harapan, muatan)
# permukaan "belah": muatan = (nama_branch, [pesan, ...])


def gen_encode():
    kasus = []
    for nama, fn in ENKODER.items():
        s = fn(TOKEN)
        for permukaan in ("berkas", "pesan"):
            kasus.append(("encode1", nama, permukaan, "tertuduh", s))
    kasus.append(("encode1", "polos", "berkas", "tertuduh", TOKEN))
    kasus.append(("encode1", "polos", "pesan", "tertuduh", TOKEN))
    # Dua lapis: pasangan terurut berlainan dari enkoder kuat.
    kuat = ["persen", "entitas", "escape-u", "b64", "hex", "b32"]
    for luar in kuat:
        for dalam in kuat:
            s = ENKODER[luar](ENKODER[dalam](TOKEN))
            for permukaan in ("berkas", "pesan"):
                kasus.append(("encode2", luar + "(" + dalam + ")",
                              permukaan, "tertuduh", s))
    # Tiga lapis: di luar batas desain dua lapis (#1802/#1813).
    for nama, s in [
        ("b64(b64(persen))", _b64(_b64(_persen(TOKEN)))),
        ("hex(b64(b64))", _hex(_b64(_b64(TOKEN)))),
        ("b64(hex(b64(persen)))", _b64(_hex(_b64(_persen(TOKEN))))),
    ]:
        for permukaan in ("berkas", "pesan"):
            kasus.append(("encode3-batas", nama, permukaan, "batas", s))
    return kasus


SISIPAN = {
    "spasi": " ",
    "titik": ".",
    "kurung": ")(",
    "interp": "${x}",
    "kutip-plus": "\" + \"",
    "format-zwsp": "\u200b",
    "format-bom": "\ufeff",
    "newline": "\n",
}


def _sisip(teks, sisipan, rng, jumlah=2):
    posisi = sorted(rng.sample(range(1, len(teks)), jumlah))
    hasil = []
    lalu = 0
    for p in posisi:
        hasil.append(teks[lalu:p])
        hasil.append(sisipan)
        lalu = p
    hasil.append(teks[lalu:])
    return "".join(hasil)


def gen_sela(rng):
    kasus = []
    # Doktrin permukaan pesan (#1425): TANPA pemadatan — hanya
    # buang-spasi untuk pola mentah (#1764) dan buang aksara
    # format (#1800) yang berlaku di sana; sisipan lain pada
    # pesan = batas desain. Permukaan berkas memadatkan.
    doktrin_pesan = {
        "spasi": "tertuduh",
        "format-zwsp": "tertuduh",
        "format-bom": "tertuduh",
        "newline": "tertuduh",
        "titik": "batas",
        "kurung": "batas",
        "interp": "batas",
        "kutip-plus": "batas",
    }
    for nama, sisipan in SISIPAN.items():
        for dasar_nama, dasar in (("polos", TOKEN), ("b64", _b64(TOKEN))):
            s = _sisip(dasar, sisipan, rng)
            kasus.append(("sela", nama + "/" + dasar_nama, "berkas",
                          "tertuduh", s))
            harap = doktrin_pesan[nama] if dasar_nama == "polos" else (
                "tertuduh" if nama in ("format-zwsp", "format-bom")
                else "batas")
            kasus.append(("sela", nama + "/" + dasar_nama, "pesan",
                          harap, s))
    return kasus


def gen_belah():
    kasus = []
    # (1) Tepi-tepi: kepala di ujung branch, ekor di awal pesan.
    for k in (5, 8, 11, 16, 26, 40, 57):
        kasus.append(("belah", "tepi-tepi/" + str(k), "belah",
                      "tertuduh", ("wip/" + ENC[:k], [ENC[k:]])))
    # (2) Kepala di TENGAH pesan, ekor di tepi branch: mekanisme
    # larian berlantai 8 (#1838); < 8 = batas.
    for k in (5, 7, 11, 20):
        harap = "tertuduh" if k >= 8 else "batas"
        kasus.append(("belah", "tengah-tepi/" + str(k), "belah",
                      harap, ("wip/" + ENC[k:],
                              ["prosa biasa " + ENC[:k] + " lanjutan"])))
    # (3) Kepala di tepi branch, ekor di TENGAH pesan.
    for k in (26, 31, 40):
        kasus.append(("belah", "tepi-tengah/" + str(k), "belah",
                      "tertuduh", ("wip/" + ENC[:k],
                                   ["prosa " + ENC[k:] + " selesai"])))
    kasus.append(("belah", "tepi-tengah/57", "belah", "batas",
                  ("wip/" + ENC[:57], ["prosa " + ENC[57:] + " selesai"])))
    # (5) Permukaan TAG: kepala di nama tag, ekor di pesan;
    # kepala di anotasi tag (tengah), ekor di tepi branch;
    # belahan nama-tag x anotasi-tag lain.
    kasus.append(("belah", "tag-pesan/26", "belah", "tertuduh",
                  ("", [ENC[26:]], ("v" + ENC[:26], "anotasi bersih"))))
    kasus.append(("belah", "anotasi-tengah/11", "belah", "tertuduh",
                  ("wip/" + ENC[11:], [],
                   ("v9", "catatan " + ENC[:11] + " selesai"))))
    kasus.append(("belah", "tag-tag/31", "belah", "tertuduh",
                  ("", [], ("v" + ENC[:31], "anotasi bersih"),
                   ("v8", ENC[31:] + " catatan"))))
    kasus.append(("belah", "tripel-pesan/30-16-16", "belah", "tertuduh",
                  ("", [ENC[:30], ENC[30:46], ENC[46:]])))
    kasus.append(("belah", "tripel-campur/22-20-22", "belah", "tertuduh",
                  ("wip/" + ENC[:22], [ENC[22:42]],
                   ("v9", ENC[42:] + " catatan"))))


    # (4) Dua fragmen tengah di dua pesan berbeda.
    for k in (11, 26):
        kasus.append(("belah", "tengah-tengah/" + str(k), "belah",
                      "tertuduh", ("", ["prosa " + ENC[:k] + " lanjut",
                                        "catatan " + ENC[k:] + " selesai"])))
    kasus.append(("belah", "tengah-tengah/5", "belah", "batas",
                  ("", ["prosa " + ENC[:5] + " lanjut",
                        "catatan " + ENC[5:] + " selesai"])))
    return kasus


def _eja_byte(teks):
    return "byteArrayOf(" + ", ".join(
        "0x%02x" % b for b in teks.encode()) + ")"


def _eja_byte_des(teks):
    return "byteArrayOf(" + ", ".join(
        str(b) for b in teks.encode()) + ")"


def gen_byte():
    kasus = []
    for nama, s in [
        ("polos", TOKEN),
        ("persen", _persen(TOKEN)),
        ("b64", _b64(TOKEN)),
        ("hex", _hex(TOKEN)),
        ("b64(persen)", _b64(_persen(TOKEN))),
    ]:
        kasus.append(("bytearray", nama, "berkas", "tertuduh", _eja_byte(s)))
    kasus.append(("bytearray", "b64(b64)-batas", "berkas", "batas",
                  _eja_byte(_b64(_b64(TOKEN)))))
    for nama, s in [
        ("desimal-polos", TOKEN),
        ("desimal-persen", _persen(TOKEN)),
        ("desimal-b64", _b64(TOKEN)),
        ("desimal-hex", _hex(TOKEN)),
    ]:
        kasus.append(("bytearray", nama, "berkas", "tertuduh",
                      _eja_byte_des(s)))
    kasus.append(("bytearray", "desimal-jinak", "berkas", "bersih",
                  _eja_byte_des("teks jinak biasa saja tanpa rahasia")))
    btok = TOKEN.encode()
    for potong in (9, 23, 39):
        campuran = "byteArrayOf(" + ", ".join(
            "0x%02x" % x for x in btok[:potong]) + ", " + ", ".join(
            str(x) for x in btok[potong:]) + ")"
        kasus.append(("bytearray", "campuran-%d" % potong, "berkas",
                      "tertuduh", campuran))
    des = lambda bts: ", ".join(str(x) for x in bts)
    kasus.append(("bytearray", "segmen-komentar", "berkas", "tertuduh",
                  "byteArrayOf(" + des(btok[:24]) + " /* jeda */ , "
                  + des(btok[24:]) + ")"))
    kasus.append(("bytearray", "segmen-tobyte", "berkas", "tertuduh",
                  "byteArrayOf(" + ", ".join(
                      str(x) + ".toByte()"
                      for x in _persen(TOKEN).encode()) + ")"))
    return kasus


def gen_jinak():
    kasus = []
    prosa = ("perbaikan ini menyelesaikan masalah pada layanan "
             "pemantau dan penulisan jurnal media yang tertunda")
    for permukaan in ("berkas", "pesan"):
        kasus.append(("jinak", "prosa", permukaan, "bersih", prosa))
        kasus.append(("jinak", "b64-prosa", permukaan, "bersih",
                      _b64(prosa)))
    kasus.append(("jinak", "sha1", "berkas", "bersih",
                  "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b"))
    kasus.append(("jinak", "sha256", "berkas", "bersih",
                  "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822c"
                  "d15d6c15b0f00a08"))
    # Bentuk insiden #1425: pesan mengutip galat berisi angka
    # dan titik dua — wajib tetap bersih di permukaan pesan.
    kasus.append(("jinak", "kutip-galat-ci", "pesan", "bersih",
                  "error: build gagal di langkah 12345678: task "
                  "':app:compileDebugKotlin' keluar dengan kode 1"))
    kasus.append(("jinak", "bytearray-jinak", "berkas", "bersih",
                  _eja_byte("teks jinak biasa saja tanpa rahasia")))
    kasus.append(("jinak", "belah-jinak", "belah", "bersih",
                  ("uji-" + "x" * 30, ["catatan " + "y" * 30 + " selesai"])))
    return kasus


def vonis_belah(muatan):
    branch, pesan_list = muatan[0], muatan[1]
    tag_list = [t for t in muatan[2:] if t]

    def palsu(*args):
        gabung = " ".join(args)
        if "refs/heads" in gabung:
            return branch
        if "refname:short" in gabung and "refs/tags" in gabung:
            return "\n".join(
                nama + "\ttag\t" + "c" * 40 + "\t" + "d" * 40
                for nama, _ in tag_list)
        if "%(contents)" in gabung:
            for nama, anotasi in tag_list:
                if "refs/tags/" + nama in gabung:
                    return anotasi
            return ""
        return ""

    jalan_asli = verifikasi.jalan
    verifikasi.jalan = palsu
    verifikasi._cek_tag_sudah_jalan = False
    try:
        gagal = []
        return bool(verifikasi._cek_pesan_tag(gagal, list(pesan_list)))
    finally:
        verifikasi.jalan = jalan_asli


def jalankan(seed=140):
    """Jalankan semua kasus; kembalikan dict hasil mentah
    (dipakai main() dan uji regresi suite gerbang)."""
    rng = random.Random(seed)
    kasus = (gen_encode() + gen_sela(rng) + gen_belah()
             + gen_byte() + gen_jinak())
    per_keluarga = {}
    lolos = []
    palsu_positif = []
    batas = {"lolos": 0, "tertangkap": 0}
    for keluarga, nama, permukaan, harapan, muatan in kasus:
        if permukaan == "berkas":
            tertuduh = cek_token.mengandung_token(muatan)
        elif permukaan == "pesan":
            tertuduh = cek_token.mengandung_token_pesan(muatan)
        else:
            tertuduh = vonis_belah(muatan)
        stat = per_keluarga.setdefault(
            keluarga, {"kasus": 0, "benar": 0, "lolos": 0, "fp": 0})
        stat["kasus"] += 1
        if harapan == "tertuduh":
            if tertuduh:
                stat["benar"] += 1
            else:
                stat["lolos"] += 1
                lolos.append((keluarga, nama, permukaan, muatan))
        elif harapan == "bersih":
            if tertuduh:
                stat["fp"] += 1
                palsu_positif.append((keluarga, nama, permukaan, muatan))
            else:
                stat["benar"] += 1
        else:
            if tertuduh:
                batas["tertangkap"] += 1
            else:
                batas["lolos"] += 1
    return {"per_keluarga": per_keluarga, "lolos": lolos,
            "palsu": palsu_positif, "batas": batas,
            "total": len(kasus)}


def main():
    seed = 140
    if "--seed" in sys.argv:
        seed = int(sys.argv[sys.argv.index("--seed") + 1])
    hasil = jalankan(seed)
    per_keluarga = hasil["per_keluarga"]
    lolos = hasil["lolos"]
    palsu_positif = hasil["palsu"]
    batas = hasil["batas"]
    print("FUZZ GERBANG — seed", seed, "| total kasus:", hasil["total"])
    for keluarga in sorted(per_keluarga):
        st = per_keluarga[keluarga]
        print("  %-14s kasus %3d | benar %3d | LOLOS %d | FP %d"
              % (keluarga, st["kasus"], st["benar"], st["lolos"], st["fp"]))
    print("  batas desain : lolos %d (sesuai doktrin) | "
          "tertangkap %d (bonus)"
          % (batas["lolos"], batas["tertangkap"]))
    for keluarga, nama, permukaan, muatan in lolos:
        print("LOLOS:", keluarga, nama, permukaan,
              repr(str(muatan))[:140])
    for keluarga, nama, permukaan, muatan in palsu_positif:
        print("POSITIF-PALSU:", keluarga, nama, permukaan,
              repr(str(muatan))[:140])
    if lolos or palsu_positif:
        print("HASIL: GAGAL — ada lolos/positif palsu di cakupan doktrin")
        return 1
    print("HASIL: LOLOS — semua kasus doktrin tertuduh/bersih sesuai")
    return 0


if __name__ == "__main__":
    sys.exit(main())
