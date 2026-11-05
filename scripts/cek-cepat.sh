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
# Eksponen bertanda + ikut dikenali (issue #1059): "1.5e+3.5"
# sama-sama bilangan baru yang diawali titik.
# Cakupan dipersempit ke nilai android:pathData saja (issue
# #1174): menerapkan pola ke seluruh teks drawable menangkap
# atribut float sah lain (mis. android:alpha=".5").
# Ekstraksi nilai pathData (issue #1185): berkas diratakan
# dulu (newline -> spasi) agar nilai multi-baris tertangkap,
# dan pola menerima petik tunggal serta spasi di sekitar =
# — tiga bentuk XML sah yang sebelumnya lolos senyap.
# Komentar XML dibuang SEBELUM perataan (issue #1203):
# teks komentar multi-baris yang berbentuk atribut pathData
# palsu tak boleh membentuk pola utuh sesudah diratakan.
sapu_komentar_xml() {
  awk '
    { baris = $0; hasil = ""
      while (length(baris) > 0) {
        if (dalam) {
          e = index(baris, "-->")
          if (e == 0) { baris = "" }
          else { baris = substr(baris, e + 3); dalam = 0 }
        } else {
          b = index(baris, "<!--")
          if (b == 0) { hasil = hasil baris; baris = "" }
          else { hasil = hasil substr(baris, 1, b - 1); baris = substr(baris, b + 4); dalam = 1 }
        }
      }
      print hasil
    }'
}
if [ "$ADA_RG" = 1 ] && rg --pcre2 -n '' app/src/main/res/drawable* >/dev/null 2>&1; then
  if cat app/src/main/res/drawable*/*.xml 2>/dev/null | sapu_komentar_xml | tr '\n' ' ' | rg --pcre2 -o --no-filename 'android:pathData\s*=\s*("[^"]*"|\x27[^\x27]*\x27)' | rg -q --pcre2 '(?<![0-9])\.-?[0-9]|[.eE][-+]?[0-9]*\.[0-9]'; then
    echo "GAGAL: path vektor tanpa nol depan (pakai 0.9 bukan .9)"; gagal=1;
  fi
elif grep -R -P '' . >/dev/null 2>&1; then
  if cat app/src/main/res/drawable*/*.xml 2>/dev/null | sapu_komentar_xml | tr '\n' ' ' | grep -o -P 'android:pathData\s*=\s*("[^"]*"|\x27[^\x27]*\x27)' | grep -q -P '(?<![0-9])\.-?[0-9]|[.eE][-+]?[0-9]*\.[0-9]'; then
    echo "GAGAL: path vektor tanpa nol depan (pakai 0.9 bukan .9)"; gagal=1;
  fi
elif cat app/src/main/res/drawable*/*.xml 2>/dev/null | sapu_komentar_xml | tr '\n' ' ' | grep -o -E 'android:pathData[[:space:]]*=[[:space:]]*("[^"]*"|'"'"'[^'"'"']*'"'"')' | grep -q -E '(^|[^0-9])\.-?[0-9]|[.eE][-+]?[0-9]*\.[0-9]'; then
  echo "GAGAL: path vektor tanpa nol depan (pakai 0.9 bukan .9)"; gagal=1;
fi
# 2. ID layout portrait vs landscape wajib sama persis (bila dir land ada).
# Mesin kerja BusyBox sering tanpa rg: pakai rg bila ada, grep -o bila tidak.
if [ -d app/src/main/res/layout-land ]; then
ekstrak_id() {
  # Petik tunggal, spasi/TAB sekitar =, dan atribut terbelah
  # baris ikut sah (issue #1201 + #1217, kelas #1185): berkas
  # diratakan dulu, keluaran dinormalkan (petik, spasi, TAB
  # dibuang) agar ID sama tak gagal palsu & beda tak lolos.
  # Komentar XML disapu dulu (issue #1233, sama seperti titik 1
  # sejak #1203): android:id palsu di dalam komentar pada satu
  # sisi tak boleh menggagalkan pasangan yang sebenarnya sama.
  if [ "$ADA_RG" = 1 ]; then sapu_komentar_xml < "$1" | tr '\n' ' ' | rg --pcre2 -o 'android:id\s*=\s*("@\+id/[A-Za-z0-9_]+"|\x27@\+id/[A-Za-z0-9_]+\x27)' | tr -d "\"' \011" | sort -u;
  else { sapu_komentar_xml < "$1" | tr '\n' ' ' | grep -o -E 'android:id[[:space:]]*=[[:space:]]*"@\+id/[A-Za-z0-9_]+"'; sapu_komentar_xml < "$1" | tr '\n' ' ' | grep -o -E "android:id[[:space:]]*=[[:space:]]*'@\+id/[A-Za-z0-9_]+'"; } | tr -d "\"' \011" | sort -u; fi
}
for f in app/src/main/res/layout/*.xml; do
  land="app/src/main/res/layout-land/$(basename "$f")"
  if [ -f "$land" ]; then
    if ! cmp -s <(ekstrak_id "$f") <(ekstrak_id "$land"); then echo "GAGAL: ID $f != $land"; gagal=1; fi
  fi
done
fi
# Arah sebaliknya (issue #1039): layout berkualifikasi tanpa
# kembaran portrait adalah varian yatim — AAPT tetap
# mengemasnya tetapi tidak pernah tampil. Berjalan walau
# folder kualifikasi baru ada satu berkas (blok di atas
# terlewat diam-diam bila foldernya belum ada). Berlaku
# untuk SEMUA kualifikasi layout (issue #1062): -land,
# -sw600dp, -v26, -night, -port, dan kombinasi mereka.
for lf in app/src/main/res/layout-*/*.xml; do
  [ -e "$lf" ] || continue
  if [ ! -f "app/src/main/res/layout/$(basename "$lf")" ]; then
    echo "GAGAL: layout berkualifikasi yatim tanpa kembaran portrait: $lf"; gagal=1;
  fi
done
# 3. Jangan commit binary/APK/secret ke repo (kredensial hanya via SetupActivity).
# Termasuk keystore agar password tak bocor ke repo.
# --diff-filter=d (issue #1290): penghapusan staged berkas
# terlarang adalah remediasi — jangan digagalkan.
if git diff --cached --name-only --diff-filter=d | grep -iE '\.(apk|aab|ap_|dex|jks|keystore|p12|pfx|pem|key)$|local\.properties$|((^|/)\.env(\.[^/]*)?|\.env|(^|/)\.envrc(\.[^/]*)?)$' >/dev/null; then
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
if git ls-files -z | tr '\0' '\n' | grep -iE '\.(apk|aab|ap_|dex|jks|keystore|p12|pfx|pem|key)$|local\.properties$|((^|/)\.env(\.[^/]*)?|\.env|(^|/)\.envrc(\.[^/]*)?)$' >/dev/null; then
  echo "GAGAL: binary/APK/secret terlacak di repo (keystore|p12|local.properties|.env dilarang)"; gagal=1;
fi
# 5b. Nama berkas berkarakter kontrol (issue #1202): newline
# di tengah ekstensi mengalahkan pencocokan akhiran poin 3/5.
# Buang semua bita yang sah (cetak ASCII + bita tinggi UTF-8);
# sisa apa pun berarti ada karakter kontrol di sebuah nama.
sisa_kontrol=$( { git ls-files -z; git diff --cached --name-only -z; } | tr -d '\000' | LC_ALL=C tr -d '\040-\176' | LC_ALL=C tr -d '\200-\377' | wc -c )
if [ "$sisa_kontrol" -gt 0 ]; then
  echo "GAGAL: nama berkas mengandung karakter kontrol (newline/tab di nama dilarang)"; gagal=1;
fi
pohon_token=$(git grep -l -E '[0-9]{6,}:[A-Za-z0-9_-]{35,}' -- . 2>/dev/null || true)
if [ -n "$pohon_token" ]; then
  echo "GAGAL: kemungkinan token bot asli terlacak di repo pada: $pohon_token (berkas biner dilaporkan per nama — deret byte mirip token; hanya via input SetupActivity)"; gagal=1;
fi
# 5b. Berkas tak terlacak tak terlihat git grep/diff — pindai isinya juga.
tak_lacak_token=""
while IFS= read -r -d '' f; do
  [ -n "$f" ] || continue
  if grep -q -E '[0-9]{6,}:[A-Za-z0-9_-]{35,}' -- "$f" 2>/dev/null; then tak_lacak_token="$f"; break; fi
done < <(git ls-files --others --exclude-standard -z 2>/dev/null || true)
if [ -n "$tak_lacak_token" ]; then
  echo "GAGAL: kemungkinan token bot asli di berkas tak terlacak $tak_lacak_token (hanya via input SetupActivity)"; gagal=1;
fi
# 5c. Berkas ignored juga dipindai (issue #706): token di sana menunggu
# git add -f. Lewati hanya direktori build sungguhan di akar/modul app.
# Wajib mulai kosong (issue #866): sebelumnya diisi $f sisa loop 5b,
# jadi pesan GAGAL bisa menuduh berkas tak terlacak sebagai berkas
# terabaikan walau daftar ignored kosong.
ign_token=""
while IFS= read -r -d '' f; do
  [ -n "$f" ] || continue
  case "$f" in
    # Direktori sampah hanya dikecualikan di lokasi sungguhan
    # (issue #895): pola */ sebelumnya mengecualikan komponen di
    # tengah path mana pun (docs/__pycache__/ lolos).
    build/*|app/build/*|.gradle/*|app/.gradle/*|__pycache__/*|tools/__pycache__/*) continue ;;
  esac
  if grep -q -E '[0-9]{6,}:[A-Za-z0-9_-]{35,}' -- "$f" 2>/dev/null; then ign_token=$f; break; fi
done < <(git ls-files --others -i --exclude-standard -z 2>/dev/null || true)
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
  b64_ign=$(git ls-files --others -z -i --exclude-standard 2>/dev/null | grep -zv -E '^(build/|app/build/|\.gradle/|app/\.gradle/|__pycache__/|tools/__pycache__/)' | python3 tools/cek-token.py --berkas 2>/dev/null || true)
  if [ -n "$b64_ign" ]; then
    echo "GAGAL: kemungkinan token bot asli (base64/terbelah) di berkas terabaikan $b64_ign (hanya via input SetupActivity)"; gagal=1;
  fi
fi
# 5e. Token di PESAN commit yang belum ter-push (issue #769): diff dan
# pohon tak memuat pesan commit, padahal token di sana hidup permanen di
# riwayat. Tanpa upstream, periksa commit yang belum ada di remote TUJUAN origin (issue #1469 cadangan; #1486: remote lain tak mengecualikan).
if [ -f tools/cek-token.py ]; then
  pesan_token=$( (git log @{u}..HEAD --format=%B 2>/dev/null || git log --branches --not --remotes=origin -200 --format=%B 2>/dev/null) | python3 tools/cek-token.py --teks-pesan 2>/dev/null || true)
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
# Daftar nama disimpan NUL-pasti di berkas sementara (issue
# #1156): variabel bash tak bisa memuat NUL dan gabungan
# newline membelah nama ber-newline menjadi fragmen yang
# lolos pindai. Segmen riwayat kini --name-only -z juga.
daftar_tmp=$(mktemp)
{ git diff --cached --name-only -z 2>/dev/null; git ls-files -z 2>/dev/null; git ls-files --others --exclude-standard -z 2>/dev/null; git ls-files --others -i --exclude-standard -z 2>/dev/null; git log --format= --name-only -z -m HEAD 2>/dev/null; } | LC_ALL=C sort -zu > "$daftar_tmp"
while IFS= read -r -d '' f; do
  [ -n "$f" ] || continue
  gabung=$(printf '%s' "$f" | tr -d '/')
  if printf '%s' "$f" | grep -q -E '[0-9]{6,}:[A-Za-z0-9_-]{35,}' || printf '%s' "$gabung" | grep -q -E '[0-9]{6,}:[A-Za-z0-9_-]{35,}'; then nama_token="$f"; break; fi
done < "$daftar_tmp"
# Bentuk terbelah pada nama (issue #896): regex literal di atas
# tak melihat token yang terbelah titik/pemisah lain di dalam
# nama — gerbang utama menangkapnya lewat pemindai bersama yang
# memadatkan pemisah. Satu panggilan massal sebagai pemicu; loop
# per-nama hanya berjalan bila massal positif, agar vonis tetap
# menyebut berkasnya dan jalur umum tetap cepat.
if [ -z "$nama_token" ] && [ -f tools/cek-token.py ]; then
  daftar_gabung=$(tr '\0' '\n' < "$daftar_tmp" | tr -d '/')
  if { tr '\0' '\n' < "$daftar_tmp"; printf '%s\n' "$daftar_gabung"; } | python3 tools/cek-token.py --teks 2>/dev/null | grep -q DITEMUKAN; then
    while IFS= read -r -d '' f; do
      [ -n "$f" ] || continue
      gabung=$(printf '%s' "$f" | tr -d '/')
      if printf '%s' "$gabung" | python3 tools/cek-token.py --teks 2>/dev/null | grep -q DITEMUKAN; then nama_token="$f"; break; fi
    done < "$daftar_tmp"
  fi
fi
# Nama ber-newline tak terjangkau pemicu massal (masukannya
# membaca fragmen): nilai subset itu per-nama tanpa syarat.
if [ -z "$nama_token" ] && [ -f tools/cek-token.py ]; then
  while IFS= read -r -d '' f; do
    case "$f" in *$'\n'*) ;; *) continue ;; esac
    gabung=$(printf '%s' "$f" | tr -d '/')
    if printf '%s' "$gabung" | python3 tools/cek-token.py --teks 2>/dev/null | grep -q DITEMUKAN; then nama_token="$f"; break; fi
  done < "$daftar_tmp"
fi
if [ -n "$nama_token" ]; then
  echo "GAGAL: kemungkinan token bot asli pada NAMA berkas $nama_token (hanya via input SetupActivity)"; gagal=1;
fi
# 6. Anotasi ganda dalam satu blok (mis. dua @Volatile) = galat kompilasi Kotlin.
#    Literal karakter ('(', ')') dikupas seperti literal string
#    agar kurungnya tak mengacaukan pelacak kedalaman (issue #1072).
#    Komentar blok /* */ dilacak antar baris dan anotasi use-site
#    (@field:X) dibaca sebagai satu nama (issue #1061).
#    Koma di luar argumen anotasi memisahkan deklarasi (issue #1060):
#    parameter konstruktor/fungsi sebaris yang beranotasi sama
#    adalah deklarasi berbeda, bukan anotasi ganda.
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
#    Pengupasan kini SATU lintasan per karakter (issue #1115,
#    #1116, #1117): string/char/raw-string dan komentar // dan
#    /* */ dikenali dalam urutan baca yang benar — // di dalam
#    string atau komentar blok tidak lagi memotong sisa baris,
#    penutup */ di awal baris menutup blok lalu sisa baris tetap
#    dipindai, dan isi raw string """...""" dilewati sebagai
#    teks (bukan kode) sehingga tak memicu positif palsu.
#    Komentar blok dilacak KEDALAMANNYA (issue #1134): Kotlin
#    membolehkan komentar bersarang — penutup terdalam tidak
#    mengakhiri blok luar. Untuk .java kedalaman dimatikan
#    (issue #1145): komentar Java TIDAK bersarang — penutup
#    pertama mengakhiri blok, sisanya kode.
loop_dup=""
while IFS= read -r -d '' f; do
  [ -n "$f" ] || continue
  isjava=0; case "$f" in *.java) isjava=1 ;; esac
  d=$(awk -v java="$isjava" '
{
  line = ""
  i = 1; n = length($0)
  while (i <= n) {
    if (inraw) {
      if (substr($0, i, 3) == "\"\"\"") { inraw = 0; i += 3 } else i++
      continue
    }
    if (inblock) {
      if (substr($0, i, 2) == "/*") { if (!java) blockdepth++; i += 2 }
      else if (substr($0, i, 2) == "*/") { blockdepth--; if (blockdepth <= 0) { inblock = 0; blockdepth = 0 } i += 2 }
      else i++
      continue
    }
    c = substr($0, i, 1)
    if (c == "\"") {
      if (substr($0, i, 3) == "\"\"\"") { inraw = 1; i += 3; continue }
      i++
      while (i <= n) {
        dd = substr($0, i, 1)
        if (dd == "\\") { i += 2; continue }
        i++
        if (dd == "\"") break
      }
      line = line "\"\""
      continue
    }
    if (c == "\047") {
      i++
      while (i <= n) {
        dd = substr($0, i, 1)
        if (dd == "\\") { i += 2; continue }
        i++
        if (dd == "\047") break
      }
      line = line "\047\047"
      continue
    }
    if (c == "/" && substr($0, i + 1, 1) == "/") break
    if (c == "/" && substr($0, i + 1, 1) == "*") { inblock = 1; blockdepth = 1; i += 2; continue }
    line = line c
    i++
  }
  if (line ~ /^[[:space:]]*$/) next
  cnt=0; hascode=0; d0=depth; i=1; n=length(line); while (i<=n) { c=substr(line, i, 1); if (depth>0) { if (c=="(") depth++; else if (c==")") depth--; i++; continue } if (c=="@" && substr(line, i+1, 1) ~ /[A-Za-z_]/) { j=i+1; nm=""; while (j<=n && substr(line, j, 1) ~ /[A-Za-z0-9_]/) { nm=nm substr(line, j, 1); j++ } cnt++; if (substr(line, j, 1)==":" && substr(line, j+1, 1) ~ /[A-Za-z_]/) { nm=nm ":"; j++; while (j<=n && substr(line, j, 1) ~ /[A-Za-z0-9_]/) { nm=nm substr(line, j, 1); j++ } } names[cnt]="@" nm; i=j; while (i<=n && substr(line, i, 1) ~ /[[:space:]]/) i++; if (i<=n && substr(line, i, 1)=="(") { depth=1; i++ } continue } if (c==",") { for (k=1; k<=cnt; k++) { if (names[k] in seen) { print FNR; exit } seen[names[k]]=1 } cnt=0; split("", names); split("", seen); hascode=1; i++; continue } if (c !~ /[[:space:]]/) hascode=1; i++ } if (cnt==0 && !hascode && depth==0 && d0==0) { split("", seen); next } for (k=1; k<=cnt; k++) { if (names[k] in seen) { print FNR; exit } seen[names[k]]=1 } if (hascode) split("", seen) }' "$f" 2>/dev/null || true)
  if [ -n "$d" ]; then loop_dup="$f:$d"; break; fi
done < <(git ls-files -z '*.kt' '*.kts' '*.java' 2>/dev/null || true)
if [ -n "$loop_dup" ]; then
  echo "GAGAL: anotasi ganda berturut-turut (tak repeatable) di $loop_dup"; gagal=1;
fi
rm -f "$daftar_tmp"
exit $gagal
