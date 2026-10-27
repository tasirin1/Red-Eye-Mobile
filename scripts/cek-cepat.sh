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
  if rg -q --pcre2 '(?<![0-9])\.-?[0-9]' app/src/main/res/drawable 2>/dev/null; then
    echo "GAGAL: path vektor tanpa nol depan (pakai 0.9 bukan .9)"; gagal=1;
  fi
elif grep -R -P '' . >/dev/null 2>&1; then
  if grep -R -q -P '(?<![0-9])\.-?[0-9]' app/src/main/res/drawable 2>/dev/null; then
    echo "GAGAL: path vektor tanpa nol depan (pakai 0.9 bukan .9)"; gagal=1;
  fi
elif grep -R -q -E '(^|[^0-9])\.-?[0-9]' app/src/main/res/drawable 2>/dev/null; then
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
if git diff --cached --name-only | grep -iE '\.(apk|aab|ap_|dex|jks|keystore|p12|pfx)$|local\.properties$|\.env$' >/dev/null; then
  echo "GAGAL: binary/APK/secret ikut staged (keystore|p12|local.properties|.env dilarang)"; gagal=1;
fi
# 4. Token bot asli jangan ikut staged (tanpa pengecualian baris regex).
if git diff --cached -U0 | grep -E '^\+' | grep -v '^+++' | grep -E '[0-9]{6,}:[A-Za-z0-9_-]{35,}' >/dev/null; then
  echo "GAGAL: kemungkinan token bot asli ikut staged (hanya via input SetupActivity)"; gagal=1;
fi
# 5. Pindai tracked tree selalu (bukan hanya saat index bersih).
# Menangkap secret yang terlanjur ter-commit via --no-verify walau ada perubahan staged.
if git ls-files -z | tr '\0' '\n' | grep -iE '\.(apk|aab|ap_|dex|jks|keystore|p12|pfx)$|local\.properties$|\.env$' >/dev/null; then
  echo "GAGAL: binary/APK/secret terlacak di repo (keystore|p12|local.properties|.env dilarang)"; gagal=1;
fi
if git grep -q -I -E '[0-9]{6,}:[A-Za-z0-9_-]{35,}' -- . 2>/dev/null; then
  echo "GAGAL: kemungkinan token bot asli terlacak di repo (hanya via input SetupActivity)"; gagal=1;
fi
# 6. Anotasi ganda berturut-turut (mis. dua @Volatile) = galat kompilasi Kotlin.
loop_dup=""
while IFS= read -r f; do
  [ -n "$f" ] || continue
  d=$(awk '/^[[:space:]]*@[A-Za-z_]+[[:space:]]*$/{ line=$0; gsub(/^[[:space:]]+/, "", line); gsub(/[[:space:]]+$/, "", line); if (line==prev) { print FNR; exit } prev=line; next } { prev="" }' "$f" 2>/dev/null || true)
  if [ -n "$d" ]; then loop_dup="$f:$d"; break; fi
done <<< "$(git ls-files '*.kt' '*.java' 2>/dev/null || true)"
if [ -n "$loop_dup" ]; then
  echo "GAGAL: anotasi ganda berturut-turut (tak repeatable) di $loop_dup"; gagal=1;
fi
exit $gagal
