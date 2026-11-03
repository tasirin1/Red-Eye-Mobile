#!/usr/bin/env bash
# Cek cepat pra-push tanpa toolchain Android (lihat AGENTS.md).
# Gagal cepat sebelum push agar hemat menit CI.
set -euo pipefail
cd "$(cd "$(dirname "$0")/.." && pwd)" || { echo "GAGAL: tak bisa pindah ke root repo"; exit 1; }
if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "GAGAL: cek-cepat harus berjalan di dalam repo git (root tidak valid)"; exit 1
fi
gagal=0
# Kemampuan rg diuji eksekusi nyata (command -v menipu: shim mati tetap "ada").
if rg --version >/dev/null 2>&1; then ADA_RG=1; else ADA_RG=0; fi
# 1. Path vektor harus 0.x (lint InvalidVectorPath menggagalkan build).
# Tiga tingkat: rg PCRE2, grep -P, grep -E portabel — selalu memeriksa,
# tak pernah gagal-tertutup karena perkakas hilang.
# Bentuk kedua (issue #1041): angka yang didahului digit
# tetapi memulai bilangan baru — "1.5.5" terurai sebagai
# 1.5 lalu .5 karena bilangan hanya boleh satu titik.
# Pola tambahannya mengenali titik yang mengikuti digit
# pecahan/eksponen bilangan sebelumnya: .|e|E [-] digit* .
if [ "$ADA_RG" = 1 ] && rg --pcre2 -n '' app/src/main/res/drawable* >/dev/null 2>&1; then
  if rg -q --pcre2 '(?<![0-9])\.-?[0-9]|[.eE]-?[0-9]*\.[0-9]' app/src/main/res/drawable* 2>/dev/null; then
    echo "GAGAL: path vektor tanpa nol depan (pakai 0.9 bukan .9)"; gagal=1;
  fi
elif grep -R -P '' . >/dev/null 2>&1; then
  if grep -R -q -P '(?<![0-9])\.-?[0-9]|[.eE]-?[0-9]*\.[0-9]' app/src/main/res/drawable* 2>/dev/null; then
    echo "GAGAL: path vektor tanpa nol depan (pakai 0.9 bukan .9)"; gagal=1;
  fi
elif grep -R -q -E '(^|[^0-9])\.-?[0-9]|[.eE]-?[0-9]*\.[0-9]' app/src/main/res/drawable* 2>/dev/null; then
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
# Arah sebaliknya (issue #1039): layout-land tanpa kembaran
# portrait adalah varian yatim — AAPT tetap mengemasnya
# tetapi tidak pernah tampil. Berjalan walau folder land
# baru ada satu berkas (blok di atas terlewat diam-diam
# bila foldernya belum ada).
for lf in app/src/main/res/layout-land/*.xml; do
  [ -e "$lf" ] || continue
  if [ ! -f "app/src/main/res/layout/$(basename "$lf")" ]; then
    echo "GAGAL: layout-land yatim tanpa kembaran portrait: $lf"; gagal=1;
  fi
done
# 3. Jangan commit binary/APK/secret ke repo (kredensial hanya via SetupActivity).
# Termasuk keystore agar password tak bocor ke repo.
if git diff --cached --name-only | grep -iE '\.(apk|aab|ap_|dex|jks|keystore|p12|pfx)$|local\.properties$|((^|/)\.env(\.[^/]*)?|\.env)$' >/dev/null; then
  echo "GAGAL: binary/APK/secret ikut staged (keystore|p12|local.properties|.env dilarang)"; gagal=1;
fi
# 4. Token bot asli jangan ikut staged (tanpa pengecualian baris regex).
if git diff --cached -U0 | grep -E '^\+' | grep -v '^+++' | grep -E '[0-9]{6,}:[A-Za-z0-9_-]{35,}' >/dev/null; then
  echo "GAGAL: kemungkinan token bot asli ikut staged (hanya via input SetupActivity)"; gagal=1;
fi
# 4b. Bentuk base64/terbelah di staged (issue #765): regex literal poin 4
# tak melihatnya — uji gabungan baris tambah lewat pemindai bersama.
if [ -f tools/cek-token.py ]; then
  tambah_token=$(git diff --cached -U0 2>/dev/null | grep -E '^\+' | grep -v '^+++' | python3 tools/cek-token.py --teks 2>/dev/null || true)
  if [ -n "$tambah_token" ]; then
    echo "GAGAL: kemungkinan token bot asli (base64/terbelah) ikut staged (hanya via input SetupActivity)"; gagal=1;
  fi
fi
# 5. Pindai tracked tree selalu (bukan hanya saat index bersih).
# Menangkap secret yang terlanjur ter-commit via --no-verify walau ada perubahan staged.
if git ls-files -z | tr '\0' '\n' | grep -iE '\.(apk|aab|ap_|dex|jks|keystore|p12|pfx)$|local\.properties$|((^|/)\.env(\.[^/]*)?|\.env)$' >/dev/null; then
  echo "GAGAL: binary/APK/secret terlacak di repo (keystore|p12|local.properties|.env dilarang)"; gagal=1;
fi
pohon_token=$(git grep -l -E '[0-9]{6,}:[A-Za-z0-9_-]{35,}' -- . 2>/dev/null || true)
if [ -n "$pohon_token" ]; then
  echo "GAGAL: kemungkinan token bot asli terlacak di repo pada: $pohon_token (berkas biner dilaporkan per nama — deret byte mirip token; hanya via input SetupActivity)"; gagal=1;
fi
# 5b. Berkas tak terlacak tak terlihat git grep/diff — pindai isinya juga.
tak_lacak_token=""
while IFS= read -r f; do
  [ -n "$f" ] || continue
  if grep -q -E '[0-9]{6,}:[A-Za-z0-9_-]{35,}' -- "$f" 2>/dev/null; then tak_lacak_token="$f"; break; fi
done <<< "$(git ls-files --others --exclude-standard 2>/dev/null || true)"
if [ -n "$tak_lacak_token" ]; then
  echo "GAGAL: kemungkinan token bot asli di berkas tak terlacak $tak_lacak_token (hanya via input SetupActivity)"; gagal=1;
fi
# 5c. Berkas ignored juga dipindai (issue #706): token di sana menunggu
# git add -f. Lewati hanya direktori build sungguhan di akar/modul app.
# Wajib mulai kosong (issue #866): sebelumnya diisi $f sisa loop 5b,
# jadi pesan GAGAL bisa menuduh berkas tak terlacak sebagai berkas
# terabaikan walau daftar ignored kosong.
ign_token=""
while IFS= read -r f; do
  [ -n "$f" ] || continue
  case "$f" in
    # Direktori sampah hanya dikecualikan di lokasi sungguhan
    # (issue #895): pola */ sebelumnya mengecualikan komponen di
    # tengah path mana pun (docs/__pycache__/ lolos).
    build/*|app/build/*|.gradle/*|app/.gradle/*|__pycache__/*|tools/__pycache__/*) continue ;;
  esac
  if grep -q -E '[0-9]{6,}:[A-Za-z0-9_-]{35,}' -- "$f" 2>/dev/null; then ign_token=$f; break; fi
done <<< "$(git ls-files --others -i --exclude-standard 2>/dev/null || true)"
if [ -n "$ign_token" ]; then
  echo "GAGAL: kemungkinan token bot asli di berkas terabaikan $ign_token (hanya via input SetupActivity)"; gagal=1;
fi
# 5d. Bentuk base64/terbelah di pohon kerja (issue #765): pindai terlacak,
# tak terlacak, dan terabaikan (di luar build sungguhan) lewat pemindai
# bersama; regex literal poin 5/5b/5c tetap berjalan sebagai lapis dasar.
if [ -f tools/cek-token.py ]; then
  b64_pohon=$(git ls-files -z 2>/dev/null | python3 tools/cek-token.py --berkas 2>/dev/null || true)
  if [ -n "$b64_pohon" ]; then
    echo "GAGAL: kemungkinan token bot asli (base64/terbelah) terlacak di repo pada: $b64_pohon (hanya via input SetupActivity)"; gagal=1;
  fi
  b64_tak_lacak=$(git ls-files --others -z --exclude-standard 2>/dev/null | python3 tools/cek-token.py --berkas 2>/dev/null || true)
  if [ -n "$b64_tak_lacak" ]; then
    echo "GAGAL: kemungkinan token bot asli (base64/terbelah) di berkas tak terlacak $b64_tak_lacak (hanya via input SetupActivity)"; gagal=1;
  fi
  b64_ign=$(git ls-files --others -z -i --exclude-standard 2>/dev/null | tr '\0' '\n' | grep -v -E '^(build/|app/build/|\.gradle/|app/\.gradle/|__pycache__/|tools/__pycache__/)' | tr '\n' '\0' | python3 tools/cek-token.py --berkas 2>/dev/null || true)
  if [ -n "$b64_ign" ]; then
    echo "GAGAL: kemungkinan token bot asli (base64/terbelah) di berkas terabaikan $b64_ign (hanya via input SetupActivity)"; gagal=1;
  fi
fi
# 5e. Token di PESAN commit yang belum ter-push (issue #769): diff dan
# pohon tak memuat pesan commit, padahal token di sana hidup permanen di
# riwayat. Tanpa upstream, periksa commit terakhir saja.
if [ -f tools/cek-token.py ]; then
  pesan_token=$( (git log @{u}..HEAD --format=%B 2>/dev/null || git log -1 --format=%B 2>/dev/null) | python3 tools/cek-token.py --teks 2>/dev/null || true)
  if [ -n "$pesan_token" ]; then
    echo "GAGAL: kemungkinan token bot asli di pesan commit yang belum ter-push (hanya via input SetupActivity)"; gagal=1;
  fi
fi
# 5f. Token pada NAMA berkas (issue #818): isi bersih tak menolong
# bila namanya sendiri berpola token. Periksa nama staged, terlacak,
# tak terlacak, terabaikan, DAN nama riwayat (issue #865 — nama yang
# sudah berganti tetap hidup di riwayat; kanal ini sebelumnya hanya
# ada di verifikasi-path sehingga komentar "paritas" menyesatkan) —
# mentah dan tergabung tanpa "/".
nama_token=""
daftar_nama="$({ git diff --cached --name-only 2>/dev/null; git ls-files 2>/dev/null; git ls-files --others --exclude-standard 2>/dev/null; git ls-files --others -i --exclude-standard 2>/dev/null; git log --format= --name-status -m HEAD 2>/dev/null | cut -f2- | tr '\t' '\n'; } | sort -u)"
while IFS= read -r f; do
  [ -n "$f" ] || continue
  gabung=$(printf '%s' "$f" | tr -d '/')
  if printf '%s' "$f" | grep -q -E '[0-9]{6,}:[A-Za-z0-9_-]{35,}' || printf '%s' "$gabung" | grep -q -E '[0-9]{6,}:[A-Za-z0-9_-]{35,}'; then nama_token="$f"; break; fi
done <<< "$daftar_nama"
# Bentuk terbelah pada nama (issue #896): regex literal di atas
# tak melihat token yang terbelah titik/pemisah lain di dalam
# nama — gerbang utama menangkapnya lewat pemindai bersama yang
# memadatkan pemisah. Satu panggilan massal sebagai pemicu; loop
# per-nama hanya berjalan bila massal positif, agar vonis tetap
# menyebut berkasnya dan jalur umum tetap cepat.
if [ -z "$nama_token" ] && [ -f tools/cek-token.py ]; then
  daftar_gabung=$(printf '%s\n' "$daftar_nama" | tr -d '/')
  if { printf '%s\n' "$daftar_nama"; printf '%s\n' "$daftar_gabung"; } | python3 tools/cek-token.py --teks 2>/dev/null | grep -q DITEMUKAN; then
    while IFS= read -r f; do
      [ -n "$f" ] || continue
      gabung=$(printf '%s' "$f" | tr -d '/')
      if printf '%s' "$gabung" | python3 tools/cek-token.py --teks 2>/dev/null | grep -q DITEMUKAN; then nama_token="$f"; break; fi
    done <<< "$daftar_nama"
  fi
fi
if [ -n "$nama_token" ]; then
  echo "GAGAL: kemungkinan token bot asli pada NAMA berkas $nama_token (hanya via input SetupActivity)"; gagal=1;
fi
# 6. Anotasi ganda dalam satu blok (mis. dua @Volatile) = galat kompilasi Kotlin.
#    Baris kosong/komentar di antara dua anotasi TIDAK memutus
#    urutan (issue #907): anotasi yang sama tetap ganda walau
#    dipisah komentar. Blok = deretan baris anotasi (dengan
#    atau tanpa argumen) + anotasi sebaris pada baris
#    deklarasinya; anotasi berargumen dan bentuk sebaris
#    kini ikut diperiksa (issue #1025). Argumen yang
#    membentang multi-baris dilacak kedalaman kurungnya
#    antar baris, jadi blok tidak ter-reset di tengah
#    argumen (issue #1031); literal string dikupas dulu
#    agar kurung di dalam string tidak mengacaukan hitungan.
loop_dup=""
while IFS= read -r f; do
  [ -n "$f" ] || continue
  d=$(awk '/^[[:space:]]*$/{ next } /^[[:space:]]*(\/\/|\/\*|\*)/{ next } { line=$0; sub(/\/\/.*$/, "", line); gsub(/"([^"\\]|\\.)*"/, "\"\"", line); cnt=0; hascode=0; d0=depth; i=1; n=length(line); while (i<=n) { c=substr(line, i, 1); if (depth>0) { if (c=="(") depth++; else if (c==")") depth--; i++; continue } if (c=="@" && substr(line, i+1, 1) ~ /[A-Za-z_]/) { j=i+1; nm=""; while (j<=n && substr(line, j, 1) ~ /[A-Za-z0-9_]/) { nm=nm substr(line, j, 1); j++ } cnt++; names[cnt]="@" nm; i=j; while (i<=n && substr(line, i, 1) ~ /[[:space:]]/) i++; if (i<=n && substr(line, i, 1)=="(") { depth=1; i++ } continue } if (c !~ /[[:space:]]/) hascode=1; i++ } if (cnt==0 && !hascode && depth==0 && d0==0) { split("", seen); next } for (k=1; k<=cnt; k++) { if (names[k] in seen) { print FNR; exit } seen[names[k]]=1 } if (hascode) split("", seen) }' "$f" 2>/dev/null || true)
  if [ -n "$d" ]; then loop_dup="$f:$d"; break; fi
done <<< "$(git ls-files '*.kt' '*.java' 2>/dev/null || true)"
if [ -n "$loop_dup" ]; then
  echo "GAGAL: anotasi ganda berturut-turut (tak repeatable) di $loop_dup"; gagal=1;
fi
exit $gagal
