# AGENTS.md — Red Eye Mobile

Aturan kerja untuk semua agen/kontributor di repo ini. Lingkup: seluruh tree repo ini.

## 1. Prinsip Utama

- JANGAN install Android SDK / build APK secara lokal. Build SELALU di GitHub Actions (`.github/workflows/build.yml`).
- Di environment lokal/container: cukup edit kode, cek sintaks ringan, dan validasi XML. Tidak perlu `./gradlew`, tidak perlu `local.properties`.
- JANGAN pernah commit secret (bot token, chat ID) ke repo. `BOT_TOKEN`/`CHAT_ID` default di kode harus string kosong.

## 2. Cara Build yang Benar

1. Push ke branch / buka PR ke `main`, atau jalankan manual via Actions > Build APK > Run workflow.
2. Ambil hasil dari tab Actions > Artifacts (`app-debug`, `app-release`).
3. Secret opsional `BOT_TOKEN`, `CHAT_ID`, `SYNC_INTERVAL` diisi via GitHub Secrets — dibaca workflow sebagai env dan diteruskan ke Gradle property (`-P` / env). Tanpa secret pun build tetap sukses (nilai kosong) karena pemakaian normal memakai input manual di aplikasi.

## 3. Alur Konfigurasi Bot (Wajib)

- Sumber kebenaran: input manual dari halaman Setup di aplikasi (`SetupActivity`, dibuka via kalkulator `1234` + `=`).
- `builder.sh` / `builder.bat` adalah LEGACY — jangan dipakai untuk alur baru, jangan dikembangkan, jangan dijadikan acuan. Biarkan apa adanya kecuali memberi banner deprecasi.
- `app/build.gradle` hanya membaca token dari Gradle property / env saat CI, tidak pernah hardcode.

## 4. Struktur Kode yang Berlaku

- `ui/MainActivity.kt` — launcher kalkulator (stealth). Kode rahasia `1234=` membuka `SetupActivity`.
- `ui/SetupActivity.kt` + `layout/activity_setup.xml` — HALAMAN UTAMA untuk simpan token/chat ID, tes koneksi, izin, device admin, start/stop monitoring.
- `data/PreferencesManager.kt` — penyimpanan terenkripsi, satu-satunya tempat baca/tulis token.
- `service/`, `receiver/`, `worker/`, `repository/`, `network/` — jangan ubah perilakunya kecuali diminta eksplisit.

## 5. Aturan Patch

- Perubahan minimal dan fokus. Jangan refactor tak diminta, jangan perbaiki bug unrelated.
- Jangan tambah dependency tanpa kebutuhan nyata. `lifecycle-runtime-ktx` sudah ada untuk `lifecycleScope`.
- Jangan tambah komentar inline di kode kecuali diminta.
- Jangan tambah header lisensi/copyright.
- Jangan `git commit` / buat branch kecuali diminta eksplisit.
- Ikuti gaya kode yang ada (findViewById, Material3, tanpa ViewBinding di activity baru kecuali sudah dipakai).

## 6. Changelog & Dokumentasi

- Setiap perubahan perilaku / file build / workflow WAJIB catat di `CHANGELOG.md` format Keep a Changelog (`Added/Changed/Fixed/Deprecated/Security`).
- `README.md` adalah dokumen pengguna: dahulukan cara build via GitHub Actions, tandai `builder.sh` sebagai legacy.
- Perintah, path file, dan nama env SELALU dalam backtick saat menulis jawaban akhir.

## 7. Validasi Sebelum Selesai

- `python3 -c "import xml.dom.minidom; ..."` untuk setiap XML yang diubah (`AndroidManifest.xml`, layout).
- `grep -rn "BOT_TOKEN\|CHAT_ID"` untuk memastikan tidak ada token asli yang kebawa.
- Tidak perlu menjalankan Gradle lokal. Sebutkan di jawaban akhir bahwa build diverifikasi via GitHub Actions.
