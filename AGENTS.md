# AGENTS.md — Red Eye Mobile

Aturan kerja untuk semua agen/kontributor di repo ini. Lingkup: seluruh tree repo ini.

## 1. Prinsip Utama

- JANGAN install Android SDK / build APK secara lokal. Build SELALU di GitHub Actions (`.github/workflows/build.yml`).
- Di environment lokal/container: cukup edit kode, cek sintaks ringan, dan validasi XML. Tidak perlu `./gradlew`, tidak perlu `local.properties`.
- DILARANG menginstal apa pun yang berat di lokal (emulator/system-image, NDK, SDK tambahan dan sejenisnya) — jangan unduh ratusan MB–GB ke mesin kerja untuk alasan apa pun, termasuk "cuma emulator".
- Di awal tiap pekerjaan, cek program lokal yang terinstal (`command -v` + `--version`; mis. `rg`, `fd`, `fzf`, `bat`, `eza`, `jq`, `file`, `lsof`, `htop`, `git`, `python3`, `bash`, `gh`) lalu manfaatkan semaksimal mungkin: `rg` untuk cari teks, `fd` untuk cari berkas, `jq` untuk JSON, sisanya sesuai kebutuhan. Tetap tanpa `./gradlew` dan tanpa toolchain Android lokal.
- JANGAN pernah commit secret (bot token, chat ID) ke repo. Kredensial bot tidak dibake ke APK dalam bentuk apa pun; satu-satunya sumber adalah input manual `SetupActivity`.
- Kontinuitas antar-sesi: di awal sesi jalankan `git -C /root/memori-agents-ai pull --ff-only`, lalu baca `/root/memori-agents-ai/SOUL.md` + `/root/memori-agents-ai/Red-Eye-Mobile.md` + `git status --short` + `git log --oneline -5` di repo ini; di akhir sesi update file memori itu, lalu commit + push ke repo memori (`memory: ...`).

## 2. Cara Build yang Benar

- Satu-satunya workflow adalah `.github/workflows/build.yml`. Setiap run membangun kedua APK standar sekaligus: `redeye-debug.apk` dan `redeye-release.apk`.
- Pemicu: push ke `main` / buka PR ke `main`, push tag `v*`, atau manual via Actions > Build APK > Run workflow. Run yang tersalip otomatis dibatalkan (`concurrency`; push tag rilis dikecualikan agar tak terpotong).
- Ambil hasil dari tab Actions > Artifacts (`apks`).
- Push tag `vX.Y.Z` otomatis menerbitkan GitHub Release berisi kedua APK berversi (`redeye-vX.Y.Z-debug.apk`, `redeye-vX.Y.Z-release.apk`).
- Secret opsional `SYNC_INTERVAL` diisi via GitHub Secrets — dibaca workflow sebagai env dan diteruskan ke Gradle property (`-P` / env). Kredensial bot tidak lewat CI sama sekali; konfigurasi selalu input manual di aplikasi.

## 3. Alur Konfigurasi Bot (Wajib)

- Sumber kebenaran: input manual dari halaman Setup di aplikasi (`SetupActivity`, dibuka via kalkulator `1234` + `=`).
- `builder.sh` / `builder.bat` adalah LEGACY — jangan dipakai untuk alur baru, jangan dikembangkan, jangan dijadikan acuan. Keduanya kini berhenti sendiri (banner + `exit`) sebelum menulis apa pun, karena alur lamanya menulis token bot ke `app/build.gradle` yang di-track; jangan diaktifkan ulang.
- `app/build.gradle` hanya membaca token dari Gradle property / env saat CI, tidak pernah hardcode.

## 4. Struktur Kode yang Berlaku

- `ui/MainActivity.kt` — launcher kalkulator (stealth). Kode rahasia `1234=` membuka `SetupActivity`. Kalkulator WAJIB berperilaku seperti kalkulator biasa (angka setelah `=` memulai entri baru, desimal dibulatkan, maksimal 12 digit).
- `ui/SetupActivity.kt` + `layout/activity_setup.xml` — HALAMAN UTAMA untuk simpan token/chat ID, tes koneksi, izin, device admin, start/stop monitoring.
- `data/PreferencesManager.kt` — penyimpanan terenkripsi, satu-satunya tempat baca/tulis token.
- `service/`, `receiver/`, `worker/`, `repository/`, `network/` — jangan ubah perilakunya kecuali diminta eksplisit.

## 5. Aturan Patch

- Perubahan minimal dan fokus. Jangan refactor tak diminta, jangan perbaiki bug unrelated.
- Jangan tambah dependency tanpa kebutuhan nyata. `lifecycle-runtime-ktx` sudah ada untuk `lifecycleScope`.
- Jangan tambah komentar inline di kode kecuali diminta.
- Jangan tambah header lisensi/copyright.
- Jangan `git commit` / buat branch kecuali diminta eksplisit atau diwajibkan alur rilis `§8`.
- Mode perbaiki = maraton: kerjakan temuan satu per satu sampai habis; tiap putaran cek antrian CI (`gh run list`), bila ada gagal build dahulukan perbaikannya baru lanjut ke temuan lain.
- Audit menyeluruh = sub-agen paralel + verifikasi mandiri: pecah jadi 2–5 sub-agen per area, lalu verifikasi sendiri tiap klaim berat langsung ke kode (`rg`/`sed`) sebelum masuk laporan/patch — klaim sub-agen bukan fakta sampai terbukti. Aturan maraton + sub-agen ini berlaku lintas repo (versi kanonik: `SOUL.md` di memori agen).
- Setiap temuan audit dicatat sebagai issue dulu: hasil audit (mode baca-saja) tidak langsung diperbaiki — tiap temuan yang lolos verifikasi dibuatkan satu issue GitHub (`gh issue create`, satu issue = satu temuan) berisi `file:baris`, kenapa berbahaya, skenario pemicu, level, dan ronde audit. Perintah "perbaiki/lanjut" mengambil antrean dari issue terbuka (level terberat dulu, lalu nomor tertua); commit menyebut `Closes #N` dan issue ditutup setelah perbaikan di-push (temuan yang digugurkan ditutup dengan alasan). Bila Issues dimatikan/`gh` tak ada, catat daftar bernomor di file memori repo. Versi kanonik: `SOUL.md` di memori agen.
- Ikuti gaya kode yang ada (findViewById, Material3, tanpa ViewBinding — fitur dimatikan di `app/build.gradle`).

## 6. Changelog & Dokumentasi

- Setiap perubahan perilaku / file build / workflow WAJIB catat di `CHANGELOG.md` format Keep a Changelog (`Added/Changed/Fixed/Removed/Deprecated/Security`), dengan seksi `[Unreleased]` untuk perubahan yang belum dirilis.
- `README.md` adalah dokumen pengguna: dahulukan cara build via GitHub Actions, tandai `builder.sh` sebagai legacy.
- Perintah, path file, dan nama env SELALU dalam backtick saat menulis jawaban akhir.

## 7. Validasi Sebelum Selesai

- `python3 -c "import xml.dom.minidom; ..."` untuk setiap XML yang diubah (`AndroidManifest.xml`, layout).
- `grep -rn "BOT_TOKEN\|CHAT_ID"` untuk memastikan tidak ada token asli yang kebawa.
- `python3 tools/verifikasi-path.py` + `bash scripts/cek-cepat.sh` wajib LOLOS sebelum push; pasang kait pre-push sekali per clone via `sh tools/pasang-hook.sh`.
- Tidak perlu menjalankan Gradle lokal. Sebutkan di jawaban akhir bahwa build diverifikasi via GitHub Actions.

## 8. Rilis Setiap Perbaikan

- Setiap perbaikan/bugfix yang selesai LANGSUNG `commit` + `push` ke `main` tanpa menunggu perintah eksplisit per langkah.
- Lalu LANGSUNG rilis: bump `versionCode`/`versionName` di `app/build.gradle`, pindahkan entri `[Unreleased]` ke seksi versi baru di `CHANGELOG.md` (format `## [X.Y.Z] - YYYY-MM-DD`), commit, `push` tag `vX.Y.Z` — workflow otomatis build + terbitkan GitHub Release.
- Pengecualian docs-only: perubahan yang hanya menyentuh `*.md`/`LICENSE` (CI otomatis skip via `paths-ignore`) cukup commit + push, tanpa bump/tag rilis.
