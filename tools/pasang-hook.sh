#!/bin/sh
# Pasang kait pre-push: tiap `git push` otomatis menjalankan verifikasi path.
# Bila verifikasi GAGAL, push dibatalkan sebelum menyentuh GitHub/CI.
# Dipasang sekali per clone: sh tools/pasang-hook.sh
set -e
ROOT="$(git rev-parse --show-toplevel)"
TERKONFIGURASI="$(git config --get core.hooksPath || true)"

# core.hooksPath boleh absolut, relatif terhadap root repo, atau lewat ~/.
direktori_kait() {
    case "$1" in
        "~"/*) printf '%s/%s' "$HOME" "${1#\~/}" ;;
        /*) printf '%s' "$1" ;;
        *) printf '%s/%s' "$ROOT" "$1" ;;
    esac
}

# Tentukan kait lama yang harus DIRANTAI (issue #839): kait asing tak
# lagi digusur diam-diam — ia disalin ke .bak bertanda waktu dan
# kait baru menjalankannya lebih dulu pada tiap push. Bila tujuan
# sudah berisi kait kita, rantai yang tercatat di dalamnya
# dipertahankan. Hasil: path kait lama (kosong bila tak ada),
# dicetak ke stdout; keterangan ke stderr.
siapkan_rantai() {
    if [ -f "$1" ] && ! grep -q "verifikasi-path.py" "$1" 2>/dev/null; then
        BAK="$1.bak-$(date +%Y%m%d%H%M%S)"
        if cp "$1" "$BAK" 2>/dev/null; then
            # Salinan wajib bisa-dijalankan (issue #861): cp dari kait
            # asal yang tak eksekutabel menghasilkan salinan yang
            # penjaga -x kait baru lewati diam-diam selamanya,
            # sementara pengumuman menyebutnya dirantai.
            chmod +x "$BAK" 2>/dev/null || true
            if [ -x "$BAK" ]; then
                echo "Kait lama ditemukan; ia DIRANTAI (tetap dijalankan lebih dulu): $BAK" >&2
            else
                echo "PERINGATAN: salinan kait lama tidak bisa dibuat bisa-dijalankan; rantai TIDAK akan berjalan: $BAK" >&2
            fi
            printf '%s' "$BAK"
        fi
    elif [ -f "$1" ]; then
        LAMA="$(sed -n 's/^K_LAMA="\(.*\)"$/\1/p' "$1" 2>/dev/null | head -n 1)"
        if [ -n "$LAMA" ] && [ -f "$LAMA" ]; then
            printf '%s' "$LAMA"
        elif [ -n "$LAMA" ]; then
            # Rantai putus harus bersuara (issue #867): berkas rantai
            # terhapus membuat kait lama berhenti jalan diam-diam,
            # dan pasang ulang membuang catatannya tanpa jejak.
            echo "PERINGATAN: kait lama yang dirantai sudah tidak ada ($LAMA) — rantai dibuang; kait lama itu tidak berjalan lagi sejak berkasnya hilang." >&2
        fi
    fi
}

pasang_kait() {
    {
        printf '#!/bin/sh\n'
        printf 'cd "$(git rev-parse --show-toplevel)" || exit 1\n'
        printf 'K_LAMA="%s"\n' "$2"
    } > "$1"
    cat >> "$1" <<'HOOK'
ZERO="0000000000000000000000000000000000000000"
# Masukan refs disimpan sekali: dibagikan ke kait lama (rantai) dan
# ke pemeriksaan kita, karena stdin hanya bisa dibaca satu kali.
MASUKAN="$(cat)"
if [ -n "$K_LAMA" ] && [ -x "$K_LAMA" ]; then
    # Teruskan stdin apa adanya (issue #862): masukan kosong harus
    # tiba sebagai stdin kosong, bukan satu baris kosong semu dari
    # printf yang selalu menambah newline.
    if [ -n "$MASUKAN" ]; then
        printf '%s\n' "$MASUKAN" | "$K_LAMA" "$@" || exit $?
    else
        "$K_LAMA" "$@" < /dev/null || exit $?
    fi
fi
TMPREF="$(mktemp)" || exit 1
trap 'rm -f "$TMPREF"' EXIT
printf '%s\n' "$MASUKAN" > "$TMPREF"
status=0
ada=0
ke_commit() {
    git rev-parse --verify "$1^{commit}" 2>/dev/null
}
while read -r local_ref local_sha remote_ref remote_sha; do
    [ -n "$local_sha" ] || continue
    [ "$local_sha" = "$ZERO" ] && continue
    ujung="$(ke_commit "$local_sha")"
    [ -n "$ujung" ] || continue
    ada=1
    awal="$(ke_commit "$remote_sha")"
    if [ -n "$awal" ]; then
        python3 tools/verifikasi-path.py --rentang "$awal..$ujung" || status=1
    else
        pangkal="$(git merge-base refs/remotes/origin/main "$ujung" 2>/dev/null || true)"
        if [ -n "$pangkal" ]; then
            python3 tools/verifikasi-path.py --rentang "$pangkal..$ujung" || status=1
        else
            python3 tools/verifikasi-path.py --rentang "$ujung~1..$ujung" || status=1
        fi
    fi
done < "$TMPREF"
if [ "$ada" -eq 0 ]; then
    python3 tools/verifikasi-path.py || status=1
fi
if [ "$status" -ne 0 ]; then exit 1; fi
exec bash scripts/cek-cepat.sh
HOOK
    chmod +x "$1" 2>/dev/null || true
}

HOOK_DST=""
if [ -n "$TERKONFIGURASI" ]; then
    # Git hanya menjalankan kait di direktori core.hooksPath; pasang di sana,
    # bukan di .git/hooks yang akan diabaikan.
    HOOK_DIR="$(direktori_kait "$TERKONFIGURASI")"
    if [ ! -d "$HOOK_DIR" ]; then
        echo "PERINGATAN: core.hooksPath = $TERKONFIGURASI belum ada;"
        echo "  selama direktori itu tak ada, Git melewati SEMUA kait di sana."
        mkdir -p "$HOOK_DIR"
        echo "  Direktori dibuat: $HOOK_DIR"
    fi
    HOOK_DST="$HOOK_DIR/pre-push"
else
    HOOK_DST="$ROOT/.git/hooks/pre-push"
fi

RANTAI="$(siapkan_rantai "$HOOK_DST")"
pasang_kait "$HOOK_DST" "$RANTAI"

if [ -x "$HOOK_DST" ]; then
    PROBE="$(dirname "$HOOK_DST")/.probe-exec-$$"
    if printf '#!/bin/sh\nexit 0\n' > "$PROBE" 2>/dev/null && chmod +x "$PROBE" 2>/dev/null && "$PROBE" >/dev/null 2>&1; then
        rm -f "$PROBE"
        echo "Kait pre-push terpasang dan terbukti bisa dieksekusi: $HOOK_DST"
        if [ -n "$TERKONFIGURASI" ]; then
            echo "  (dipakai Git lewat core.hooksPath = $TERKONFIGURASI)"
        fi
        exit 0
    fi
    rm -f "$PROBE"
    echo "PERINGATAN: bit eksekusi terbaca tetapi menjalankan berkas ditolak (mount noexec?): $HOOK_DST" >&2
fi

if [ -n "$TERKONFIGURASI" ]; then
    echo "PERINGATAN: kait tak bisa dieksekusi di $HOOK_DST" >&2
    echo "  Cadangan: workflow Verifikasi Path di CI tetap memeriksa tiap push;" >&2
    echo "  atau jalankan manual sebelum commit: python3 tools/verifikasi-path.py" >&2
    exit 1
fi

HOOK_FALLBACK_DIR="${GIT_HOOKS_DIR:-$HOME/.githooks}/$(basename "$ROOT")"
mkdir -p "$HOOK_FALLBACK_DIR"
RANTAI_FB="$(siapkan_rantai "$HOOK_FALLBACK_DIR/pre-push")"
pasang_kait "$HOOK_FALLBACK_DIR/pre-push" "$RANTAI_FB"
PROBE="$HOOK_FALLBACK_DIR/.probe-exec-$$"
if [ -x "$HOOK_FALLBACK_DIR/pre-push" ] && printf '#!/bin/sh\nexit 0\n' > "$PROBE" 2>/dev/null && chmod +x "$PROBE" 2>/dev/null && "$PROBE" >/dev/null 2>&1; then
    rm -f "$PROBE"
    git config core.hooksPath "$HOOK_FALLBACK_DIR"
    echo "Kait pre-push terpasang via core.hooksPath: $HOOK_FALLBACK_DIR"
    exit 0
fi
rm -f "$PROBE"
echo "PERINGATAN: kait tak bisa dieksekusi di mana pun." >&2
echo "  core.hooksPath TIDAK diarahkan ke direktori mati; kait lama (jika ada) tetap berlaku." >&2
echo "  Cadangan: workflow Verifikasi Path di CI tetap memeriksa tiap push;" >&2
echo "  atau jalankan manual sebelum commit: python3 tools/verifikasi-path.py" >&2
exit 1
