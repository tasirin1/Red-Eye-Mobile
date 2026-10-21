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

pasang_kait() {
    cat > "$1" <<'HOOK'
#!/bin/sh
cd "$(git rev-parse --show-toplevel)" || exit 1
ZERO="0000000000000000000000000000000000000000"
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
done
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

if [ -f "$HOOK_DST" ] && ! grep -q "verifikasi-path.py" "$HOOK_DST" 2>/dev/null; then
    BAK="$HOOK_DST.bak-$(date +%Y%m%d%H%M%S)"
    cp "$HOOK_DST" "$BAK"
    echo "Kait lama disalin ke: $BAK"
fi
pasang_kait "$HOOK_DST"

if [ -x "$HOOK_DST" ]; then
    echo "Kait pre-push terpasang dan bisa dieksekusi: $HOOK_DST"
    if [ -n "$TERKONFIGURASI" ]; then
        echo "  (dipakai Git lewat core.hooksPath = $TERKONFIGURASI)"
    fi
    exit 0
fi

if [ -n "$TERKONFIGURASI" ]; then
    echo "PERINGATAN: kait tak bisa dieksekusi di $HOOK_DST" >&2
    echo "  Cadangan: workflow Verifikasi Path di CI tetap memeriksa tiap push;" >&2
    echo "  atau jalankan manual sebelum commit: python3 tools/verifikasi-path.py" >&2
    exit 1
fi

HOOK_FALLBACK_DIR="${GIT_HOOKS_DIR:-$HOME/.githooks}/$(basename "$ROOT")"
mkdir -p "$HOOK_FALLBACK_DIR"
pasang_kait "$HOOK_FALLBACK_DIR/pre-push"
git config core.hooksPath "$HOOK_FALLBACK_DIR"
if [ -x "$HOOK_FALLBACK_DIR/pre-push" ]; then
    echo "Kait pre-push terpasang via core.hooksPath: $HOOK_FALLBACK_DIR"
else
    echo "PERINGATAN: kait tak bisa dieksekusi di mana pun." >&2
    echo "  Cadangan: workflow Verifikasi Path di CI tetap memeriksa tiap push;" >&2
    echo "  atau jalankan manual sebelum commit: python3 tools/verifikasi-path.py" >&2
fi
