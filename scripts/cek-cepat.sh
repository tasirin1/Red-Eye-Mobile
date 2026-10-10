#!/usr/bin/env bash
# Cek cepat pra-push tanpa toolchain Android (lihat AGENTS.md).
# Gagal cepat sebelum push agar hemat menit CI.
set -euo pipefail
gagal=0
# Kemampuan rg diuji eksekusi nyata (command -v menipu: shim mati tetap "ada").
if rg --version >/dev/null 2>&1; then ADA_RG=1; else ADA_RG=0; fi
# 1. Path vektor harus 0.x (lint InvalidVectorPath menggagalkan build).
# Tiga tingkat: rg PCRE2, grep -P, grep -E portabel — selalu memeriksa,
# tak pernah gagal-tertutup karena perkakas hilang.
if [ "$ADA_RG" = 1 ] && rg --pcre2 -n '' app/src/main/res/drawable >/dev/null 2>&1; then
  if rg -n --pcre2 '(?<![0-9])\.-?[0-9]' app/src/main/res/drawable 2>/dev/null | grep -q .; then
    echo "GAGAL: path vektor tanpa nol depan (pakai 0.9 bukan .9)"; gagal=1;
  fi
elif grep -R -P '' . >/dev/null 2>&1; then
  if grep -R -n -P '(?<![0-9])\.-?[0-9]' app/src/main/res/drawable 2>/dev/null | grep -q .; then
    echo "GAGAL: path vektor tanpa nol depan (pakai 0.9 bukan .9)"; gagal=1;
  fi
elif grep -R -E -n '(^|[^0-9])\.-?[0-9]' app/src/main/res/drawable 2>/dev/null | grep -q .; then
  echo "GAGAL: path vektor tanpa nol depan (pakai 0.9 bukan .9)"; gagal=1;
fi
# 2. ID layout portrait vs landscape wajib sama persis (bila dir land ada).
# Mesin kerja BusyBox sering tanpa rg: pakai rg bila ada, grep -o bila tidak.
if [ -d app/src/main/res/layout-land ]; then
ekstrak_id() {
  if [ "$ADA_RG" = 1 ]; then rg -o 'android:id="@\+id/[A-Za-z0-9_]+"' "$1" | sort -u;
  else grep -o 'android:id="@+id/[A-Za-z0-9_]*"' "$1" | sort -u; fi
}
for f in app/src/main/res/layout/*.xml; do
  land="app/src/main/res/layout-land/$(basename "$f")"
  if [ -f "$land" ]; then
    if ! cmp -s <(ekstrak_id "$f") <(ekstrak_id "$land"); then echo "GAGAL: ID $f != $land"; gagal=1; fi
  fi
done
fi
# 3. Jangan commit binary/APK/secret ke repo (kredensial hanya via SetupActivity).
# Termasuk keystore agar password tak bocor ke repo.
if git diff --cached --name-only | grep -E -q '\.(apk|aab|ap_|dex|jks|keystore|p12|pfx)$|local\.properties$|\.env$'; then
  echo "GAGAL: binary/APK/secret ikut staged (keystore|p12|local.properties|.env dilarang)"; gagal=1;
fi
# 4. Token bot asli jangan ikut staged (definisi regex TOKEN_REGEX dikecualikan:
# baris berkurung siku tak diperiksa karena token asli tak mengandungnya).
if git diff --cached -U0 | grep -E '^\+' | grep -v '^+++' | grep -v '\[' | grep -E -q '[0-9]{6,}:[A-Za-z0-9_-]{35,}'; then
  echo "GAGAL: kemungkinan token bot asli ikut staged (hanya via input SetupActivity)"; gagal=1;
fi
exit $gagal
