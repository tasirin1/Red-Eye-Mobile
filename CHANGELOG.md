# Changelog

Semua perubahan penting proyek ini dicatat di sini, format mengikuti [Keep a Changelog](https://keepachangelog.com/id/1.0.0/).

## [1.4.2] - 2026-09-23

### Fixed
- Layar hitam di build debug: `MainActivity` kini selalu menampilkan kalkulator bila bukan edisi parental.
- Crash restart setelah reboot di Android 12+: `BootReceiver` menangkap kegagalan start foreground service dan menjadwalkan pengurasan antrean via `WorkManager`.
- Kode rahasia kalkulator `1234=` kini hanya terbuka bila diketik persis tanpa operator; pembagian nol/infinite selalu tampil `Error`.
- Antrean offline benar-benar terkuras: `MessageScheduler` memakai `APPEND_OR_REPLACE`, worker tidak lagi menahan thread saat rate-limit 429.
- Sinkronisasi awal dibatasi 100 SMS/panggilan dan data baru di-chunk per 10 pesan agar tidak melebihi batas 4096 karakter Telegram.
- Watchdog kamera diselaraskan ke 50 detik mengikuti timeout capture 45 detik; referensi kamera yang sudah di-`close` kini di-nol-kan.
- Default interval sinkronisasi disamakan ke 5 menit mengikuti `BuildConfig` dan halaman Setup.
- `NetworkUtils` kini mensyaratkan `NET_CAPABILITY_INTERNET` agar tidak klaim online palsu.

### Security
- Logging HTTP Telegram dimatikan total agar token bot di URL tidak bocor ke logcat.
- Fallback penyimpanan plaintext (`PreferencesManager`, `MessageQueue`) kini mencatat peringatan; antrean penuh mencatat pesan yang dibuang.
- Izin tak terpakai dihapus dari `AndroidManifest.xml` (`PACKAGE_USAGE_STATS`, `WRITE_EXTERNAL_STORAGE`, `READ_EXTERNAL_STORAGE`).
- Cache foto gagal kirim dipangkas dari 20 menjadi 10 berkas.

### Changed
- `viewBinding` dimatikan karena tidak dipakai (seluruh activity memakai `findViewById`).
- `SetupActivity` memakai `TimeFmt` bersama dan meng-cache tombol status agar `updateStatus` tidak berulang `findViewById`.
- Eviksi cache kontak memakai satu entri tertua, bukan `clear` sekaligus; query SMS/call log baru dibatasi 50 baris.

## [1.4.1] - 2026-09-23

### Fixed
- Force close setelah pemberian izin di edisi Parental Control: tipe foreground service (`camera`, `location`) kini hanya dideklarasikan saat izinnya sudah diberikan (`MonitoringService`), dan service di-refresh tiap tahap izin lokasi selesai (`MainActivity`).

## [1.4.0] - 2026-09-23

### Added
- Rilis Parental Control Edition: workflow `release.yml` membangun dua varian tiap tag — standar (`redeye-*-debug.apk`, `redeye-*-release.apk`) dan parental (`parental-*-debug.apk`, `parental-*-release.apk`, `PARENTAL_UI=true`, applicationId `com.redeye.parentalcontrol`, label `Parental Control`).

## [1.3.2] - 2026-09-23

### Added
- Background location flow: `ACCESS_BACKGROUND_LOCATION` permission, `location` foreground service type (`FOREGROUND_SERVICE_LOCATION`), and staged grant in Setup and the Parental Control page (foreground first, then Allow all the time) with live foreground/background status.

## [1.3.1] - 2026-09-23

### Changed
- Parental edition is now transparent: honest notification channel and content, no silent Device Admin activation, in-app Privacy Policy (`PRIVACY.md`).

### Added
- Parental edition rebrand: `PARENTAL_UI` builds use applicationId `com.redeye.parentalcontrol`, label `Parental Control`, and a shield icon (no Calculator traces).

### Added
- `PARENTAL_UI` build flag: launcher shows a Parental Control page (grant-all-permissions only) instead of the calculator; token prefilled from `BuildConfig`. `Build APK` workflow gained a `parental_ui` dispatch input.

## [1.3.0] - 2026-09-23

### Added
- One-tap inline buttons under `/help`: photo, location, calls, SMS, front/back camera, 60-min pause, resume, battery, status.

## [1.2.0] - 2026-09-23

### Fixed
- Escape HTML in SMS/call content so messages containing `<>\&` no longer fail with HTTP 400.
- Queue retry is now scheduled on every queued message (offline included), so the offline queue actually drains.
- Service restart by the system (null intent) resumes monitoring instead of idling without foreground.
- History sync marks completion before sending, preventing duplicate resends after a restart.
- Empty photo-interval field keeps the current setting instead of silently resetting to 1.

### Changed
- Removed dead `AutoConfig` reflection and the debug-only settings UI from `MainActivity`; deleted unused `activity_main.xml`.
- `MasterKey` is created once per process and shared by `PreferencesManager` and `MessageQueue`.
- Removed duplicate `getAllSms`; `CallLogRepository` contact cache persists across queries (capped at 500).
- `NetworkUtils` simplified for `minSdk 24` (dead pre-M branch removed); redundant `printStackTrace` calls removed.
- Photo size prefers an exact match, then the smallest resolution >= 640px wide, keeping uploads small.

## [1.1.9] - 2026-09-23

### Added
- Photo interval `0` means manual-only mode: no automatic photos, capture solely via `/photo`. Settable from Setup or `/photointerval 0`.

## [1.1.8] - 2026-09-23

### Added
- New `/camera` command to switch between front and back camera (`/camera depan|belakang`); active camera shown in `/status`.

## [1.1.7] - 2026-09-23

### Added
- New Telegram commands: `/location`, `/lastcalls`, `/lastsms`, `/photointerval`, `/pause`, `/battery`, `/stop`, `/resume`; `/status` and `/help` updated.
- Location permission (`ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`) in manifest and Setup grant flow.

## [1.1.6] - 2026-09-23

### Fixed
- Fixed background-thread deadlock that froze every photo capture and left the camera stuck busy: the camera thread no longer waits on itself when stopping.
- Added a 70-second watchdog that resets the camera state if a capture never answers, so `/photo` recovers instead of reporting busy forever.

## [1.1.5] - 2026-09-23

### Fixed
- Photo capture from background now declares the camera foreground-service type on Android 10-13, where it was previously missing and the OS could refuse camera access.
- Manual `/photo` always replies with the final result (sent or failure reason) instead of stopping at `Taking photo now…`.
- Concurrent captures no longer fight over the camera; a busy camera replies to retry instead of failing silently.
- Photo size falls back to a device-supported JPEG resolution when `1280x720` is unavailable.

## [1.1.4] - 2026-09-23

### Added
- Setup buttons: Disable Battery Restriction (anti-kill) and Send Status Now (direct proof).
- Setup status line shows battery restriction state.

### Fixed
- Photo failures are now reported to Telegram (throttled) instead of logcat-only.
- Failed photos are kept and retried on the next successful send (max 3 per cycle).
- `/status` shows camera permission state and last photo time.
- Photo upload failures reported to Telegram with HTTP code + retry.

### Added
- Photo interval setting on the setup page (1–60 min, default 1).
- Two-way Telegram commands: `/photo` (capture now), `/status`, `/help` — owner chat only.

## [1.1.3] - 2026-09-23

### Fixed
- Monitoring loops no longer duplicate on restart; history dump runs once per device.
- Sync loops honor the user-configured interval; calculator clears cleanly after Error.
- Camera thread no longer leaks when no front camera; failed photos kept with cache cap.
- Telegram 429 rate limits respected with retry-after; queue IDs collision-free with bounded retries.
- Removed dead `SmsObserverService`, dead manifest receiver, and dead release-mode handler.
- Repositories deduplicated; contact lookups memoized; date formatting via shared `TimeFmt`.

### Security
- Bot token no longer logged anywhere; HTTP logging disabled in release builds.
- Offline queue stored encrypted; app backup disabled (`allowBackup=false`).
- All user-visible text (including bot messages) switched to English.

## [1.1.2] - 2026-09-22

### Changed
- APK release kini ditandatangani dengan keystore milik Tasirin (alias `tasirin`).
- Wajib uninstall v1.1.0/v1.1.1 dulu sebelum install v1.1.2 karena kunci tanda tangan berbeda.

## [1.1.1] - 2026-09-22

### Fixed
- APK release sekarang ditandatangani (keystore `PKCS12` via Secrets) sehingga bisa diinstall — sebelumnya `unsigned` dan ditolak Android.
- Tanpa keystore (build check biasa), release fallback ke debug key agar tetap terinstall untuk testing.

## [1.1.0] - 2026-09-22

### Added
- `SetupActivity` + `activity_setup.xml`: halaman utama input manual `BOT_TOKEN` / `CHAT_ID` / interval, tombol Tes Koneksi, izin, Device Admin, dan start/stop monitoring.
- `.github/workflows/build.yml`: build APK cloud (debug + release) + upload artifacts.
- `AGENTS.md`: aturan repo — larangan install SDK lokal, build selalu di GitHub Actions.
- Dukungan `lifecycle-runtime-ktx` untuk `lifecycleScope` di halaman setup.

### Changed
- `app/build.gradle`: hapus hardcode token; nilai hanya diisi dari Gradle property / env saat CI (`BOT_TOKEN`, `CHAT_ID`, `SYNC_INTERVAL`), default kosong.
- `MainActivity`: kode rahasia kalkulator `1234` + `=` diperbaiki dan sekarang membuka `SetupActivity`.
- `README.md`: alur utama diganti ke build via GitHub Actions; `builder.sh`/`builder.bat` ditandai legacy.

### Security
- Token bot contoh yang sempat ter-hardcode dihapus dari `app/build.gradle`.

## [1.0.0] - 2025-10-19

- Rilis awal: monitoring SMS / call log / kamera, integrasi bot Telegram, mode kalkulator stealth, `builder.sh` / `builder.bat` lokal.
