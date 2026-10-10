#!/bin/sh
# Pasang kait pre-push: tiap `git push` otomatis menjalankan verifikasi path.
# Bila verifikasi GAGAL, push dibatalkan sebelum menyentuh GitHub/CI.
# Dipasang sekali per clone: sh tools/pasang-hook.sh
set -e
HOOK_DST=".git/hooks/pre-push"
HOOK_FALLBACK_DIR="${GIT_HOOKS_DIR:-$HOME/.githooks}/$(basename "$(git rev-parse --show-toplevel)")"
install_hook() {
    cat > "$1" <<'HOOK'
#!/bin/sh
cd "$(git rev-parse --show-toplevel)"
if ! python3 tools/verifikasi-path.py; then exit 1; fi
exec bash scripts/cek-cepat.sh
HOOK
    chmod +x "$1" 2>/dev/null || true
}
install_hook "$HOOK_DST"
if [ -x "$HOOK_DST" ]; then
    echo "Kait pre-push terpasang dan bisa dieksekusi: $HOOK_DST"
    exit 0
fi
mkdir -p "$HOOK_FALLBACK_DIR"
install_hook "$HOOK_FALLBACK_DIR/pre-push"
git config core.hooksPath "$HOOK_FALLBACK_DIR"
if [ -x "$HOOK_FALLBACK_DIR/pre-push" ]; then
    echo "Kait pre-push terpasang via core.hooksPath: $HOOK_FALLBACK_DIR"
else
    echo "PERINGATAN: kait tak bisa dieksekusi di mana pun."
    echo "  Cadangan: workflow Verifikasi Path di CI tetap memeriksa tiap push;"
    echo "  atau jalankan manual sebelum commit: python3 tools/verifikasi-path.py"
fi
