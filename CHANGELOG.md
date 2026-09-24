# Changelog

Semua perubahan penting proyek ini dicatat di sini, format mengikuti [Keep a Changelog](https://keepachangelog.com/id/1.0.0/).

## [1.6.7] - 2026-09-24

### Fixed
- `SetupActivity.testConnection` selalu menandai `credentialError` saat `400`/`401`/`403` termasuk install segar; tulis prefs pindah ke `Dispatchers.IO`.
- Sync awal yang terpotong kini dilanjutkan ulang, bukan ditandai selesai; `initialSyncJob` dibatalkan saat monitoring dimatikan.
- Backfill `userConsentedMonitoring` di `ParentalMonitorApp` agar pengguna lama tetap auto-start setelah reboot.
- Loop `monitoring`/`kamera`/`command` guard identitas job agar restart cepat tak ganda.
- `chunkedDelay` tanpa lantai 60 detik agar jeda foto pendek tak overshoot.
- Throttle notif kamera dan kuota `pkgHits` pakai `elapsedRealtime`.
- `checkAndSendNewData` dibatasi 1 halaman per tipe per siklus agar interval sinkronisasi dihormati.
- `NotificationForwarderService` cache kredensial + listener; `MainActivity` ikut meminta lokasi foreground.
- Perbaiki compile error `ensureActive` tanpa receiver di `chunkedDelay` (`v1.6.6` merah karenanya).


### Fixed
- `MonitoringService.shouldAutoResume`, `BootReceiver`, `BootRestartWorker` mensyaratkan `userConsentedMonitoring` agar reboot tak melewati persetujuan.
- Loop `monitoring`/`kamera`/`command` melempar ulang `CancellationException` agar `restartCameraLoop` tak tertelan.
- `SetupActivity.testConnection` menguji kredensial input dulu dan hanya menyimpan token saat sukses; token bagus tak lagi tertimpa token gagal.
- Backlog foto naik ke 10 (`pendingPhotoCount`, `flushPendingPhotos`, `prunePhotoCache` FIFO) agar antrean offline terkuras.
- Polling `getUpdates` kembali `timeout=10` (long-poll) untuk hemat radio.
- `SendMessageWorker` baca token/chat sekali per run; `redactToken` pakai cache `sendCreds`.
- `NotificationForwarderService` cache konfigurasi 30 detik + status jaringan 20 detik agar banjir notifikasi tak membombardir keystore.
- Restart loop kamera tunggal via listener `camera_interval`/`monitoring_paused`/`photo_paused_until`; panggilan eksplisit ganda di handler dihapus.
- `MemoryPrefs.apply` memberitahu listener agar fallback volatil tetap refresh kredensial.

## [1.6.6] - 2026-09-24

### Added
- Flag `PreferencesManager.userConsentedMonitoring` sebagai syarat auto-start agar jalur `MainActivity` tak melewati dialog persetujuan.
- Rate cap notifikasi per paket (5 per 120 detik) plus serialisasi `Mutex` di `NotificationForwarderService` agar banjir notifikasi tak menjadi spam.
- Peringatan Telegram sekali per proses saat secure storage fallback volatil aktif di `MonitoringService`.
- Mapping R8 (`redeye-mapping.txt`) ikut diunggah sebagai artifact di `.github/workflows/build.yml` untuk triase crash rilis.

### Changed
- `MonitoringService.sendInitialData` menandai `lastSmsId`/`lastCallTimestamp` per chunk terkirim, bukan di muka.
- `MonitoringService.checkAndSendNewData` tak lagi menyentuh `lastSyncTime`; hanya kiriman sukses via `sendToTelegram` yang memutakhirkannya.
- Polling `getUpdates` memakai `timeout=0` alih-alih `timeout=10` agar radio tak ditahan tiap siklus.
- Loop kamera tidur hingga 30 menit saat interval 0 atau jeda foto, tetap 5 menit saat monitoring dijeda via remote.
- `SendMessageWorker` membatasi 20 kiriman per run dan `Result.retry()` bila sisa masih ada.
- `MessageQueue` mutasi in-place pada list cached agar tak ada salinan ganda per tulis.
- `SetupActivity.updateStatus` membaca prefs terenkripsi di `Dispatchers.IO`.
- `NetworkUtils.isNetworkAvailable` mengakui transport `VPN`.
- Validasi token `SetupActivity` diperketat dari 10+ ke 20+ karakter.
- Channel notifikasi selalu `Monitoring Service`, label `System Service` yang menyerupai OS dihapus.
- Deskripsi device admin menjelaskan eksplisit policy lock/watch/password/wipe.
- `DeviceAdminReceiver.kt` diganti nama menjadi `AdminReceiver.kt` mengikuti nama class.

### Fixed
- `MonitoringService` loop kamera dipecah `chunkedDelay` 60 detik plus `restartCameraLoop` saat `camera_interval`/`monitoring_paused`/`photo_paused_until` berubah dan `/photointerval 0` restart loop agar perintah Telegram berlaku maksimal 60 detik.
- `MonitoringService.sendCreds` refresh instan via `SharedPreferences` listener saat `bot_token`/`chat_id` berubah, menutup window kredensial lama di luar refresh 5 menit.
- `MonitoringService.prunePhotoCache` mempertahankan foto tertua (FIFO) agar antrean offline tak membuang kiriman paling awal.
- `NotificationForwarderService` rate cap per paket hanya dihitung saat kirim langsung sukses, antrean offline tak memakan kuota.

- `MonitoringService.pollTelegramCommands` mencatat `credentialError` untuk `400`/`401`/`403` dan mundur 5 menit saat auth diblokir.
- `MonitoringService.sendPhotoFile` drop file + set `credentialError` untuk `400`/`401`/`403` tanpa notifikasi gagal berulang.
- `BootReceiver` tak lagi menjadwalkan `scheduleMessageSend` yang salah saat restart gagal; hanya `scheduleBootRestart`.
- `SendMessageWorker` return `Result.success()` saat belum konfigurasi agar antrean tak yatim.
- `SetupActivity.testConnection`/`sendStatusNow` menulis dan menghapus `credentialError` sesuai hasil.

### Security
- Token di path URL adalah syarat API Telegram; `network_security_config` system-only tetap menutup MITM CA pengguna, sisa risiko CA terpaksa dicatat sebagai residual.
- Foto `cacheDir` plaintext dan fallback volatil adalah trade-off ketersediaan yang didokumentasikan; fallback kini memicu peringatan visible.

## [1.6.5] - 2026-09-24

### Added
- Dialog persetujuan eksplisit di `SetupActivity` sebelum monitoring aktif mencakup SMS, call log, lokasi background, kamera, dan notifikasi.
- Status kredensial di `SetupActivity` (`Auth: FAILED`) dari `PreferencesManager.credentialError` agar token salah terlihat pengguna.
- `app/src/main/res/xml/network_security_config.xml` hanya percaya system CA dan larang cleartext, rujukan via `AndroidManifest.xml`.

### Changed
- `MessageScheduler.scheduleMessageSend` memakai `ExistingWorkPolicy.KEEP` agar pesan offline coalesce ke satu worker penguras antrean.
- `MessageScheduler.scheduleBootRestart` backoff 10 detik jadi 30 detik; `BootRestartWorker` maksimal 1 retry.
- `TelegramResponse.result` dari `Any?` jadi `JsonElement?` agar Gson tak alokasi `LinkedTreeMap`.
- `app/build.gradle` release `minifyEnabled true` + `shrinkResources true` dengan keep rules Gson/Retrofit di `proguard-rules.pro`.
- `app/src/main/res/xml/device_admin.xml` memakai policy nyata (`limit-password`, `watch-login`, `force-lock`, `wipe-data`).
- `MonitoringService.registerBotCommands` di-cache per hash token via `PreferencesManager.commandsTokenHash`.
- `CameraService.saveImage` tulis ber-buffer 8KB chunk alih-alih satu `ByteArray` penuh.

### Fixed
- `SendMessageWorker.doWork` kembalikan `Result.retry()` saat sisa transient masih di antrean agar tak macet; `401`/`400`/`403` di-drop tanpa retry via `SendOutcome.AuthFailed`.
- `MonitoringService.sendToTelegram` dan `NotificationForwarderService.forwardToTelegram` tak mengantre untuk `401`/`400`/`403`, sebaliknya set `credentialError` yang tampil di Setup.
- `BootRestartWorker` menyerah anggun (`Result.success`) untuk `ForegroundServiceStartNotAllowedException`/`SecurityException`/`IllegalStateException` di Android 12+ tanpa bakar baterai.
- `NotificationForwarderService.forwardToTelegram` muat `PreferencesManager` sinkron bila `prefsRef` null dan mengantre pesan bila storage gagal, hapus return diam.
- `SetupActivity.toggleMonitoring` stop path dibungkus try/catch dan start dipindah ke `startMonitoringConfirmed()` di balik dialog persetujuan.

### Security
- Trust anchor sistem saja menutup MITM via CA terinstal pengguna; token tetap di path URL sesuai API Telegram dan tak di-log.
- Status storage volatil (`volatile (keystore unavailable)`) tampil di Setup agar fallback keystore rusak tak berhenti diam-diam.

## [1.6.4] - 2026-09-24

### Fixed
- `MonitoringService.checkAndSendNewData` menandai `lastSmsId`/`lastCallTimestamp` per chunk dan mengambil halaman lanjutan bila backlog >500, sehingga crash tidak mengulang batch dan kelebihan data tidak hilang.
- Sync awal idempoten via `initialSyncStarted` persisten: restart saat riwayat setengah terkirim tidak mengulang 100 SMS + 100 calls.
- Perintah Telegram yang masuk saat offline tetap dieksekusi; filter basi 600 detik yang membuang diam-diam dihapus.
- `CameraService` ganti `backgroundThread!!`/`fallback!!` dengan guard aman dan `join(2_000)` ber-timeout.
- `NotificationForwarderService` kunci dedup kini menyertakan ID notifikasi dengan jendela 60 detik agar pesan sah yang berulang tidak ikut terbuang.
- `SendMessageWorker` memakai `registerFailures()` bulk satu tulis per run.

### Changed
- Kredensial Telegram di-cache di `MonitoringService` dan status jaringan di-cache 20 detik, hilangkan puluhan dekripsi dan query per batch.
- Loop kamera tidur 5 menit saat interval 0 alih-alih bangun tiap 60 detik.
- Delay history 2000/1000ms jadi 500ms dan jeda worker 200ms jadi 100ms.
- `MessageQueue.registerFailures()` menggantikan tulis-ulang per pesan gagal.

## [1.6.3] - 2026-09-24

### Security
- Log `Log.i`/`Log.d` di-gate `BuildConfig.DEBUG` di `MonitoringService`, `CameraService`, `MainActivity`, `DeviceAdminReceiver`, `NotificationForwarderService`. Log `w`/`e` dipertahankan untuk diagnostik.
- Auth callback Telegram kini wajib `from.id`; fallback `message.chat.id` dihapus.
- `secureDelete()` disederhanakan jadi hapus langsung dengan mengandalkan filesystem terenkripsi, hapus anggapan overwrite aman di flash.

### Fixed
- `MessageQueue.clearQueue()` NPE diperbaiki dengan akses null-safe plus bersihkan antrean volatil.
- Race sync awal ditutup via `initialSyncRunning`: loop periodik dilewati selama sync awal berjalan sehingga riwayat tak ganda.
- Loop polling `getUpdates` ditulis ulang tanpa `return@let`; tiap update tetap memajukan `lastUpdateId` via `finally`.
- Validasi `SetupActivity` dilonggarkan ke token 10+ karakter dan pesan error `chatId` memakai `setup_bad_chat` baru di `strings.xml`.
- `CameraService` ganti `imageReader!!` dengan guard aman dan nama file foto memakai `UUID` anti tabrakan.
- `NotificationForwarderService` pre-warm `PreferencesManager`/`MessageQueue` di `onCreate` IO agar binder thread tak kena dekripsi.
- `SendMessageWorker.SendOutcome.RateLimited` jadi object tanpa field mati.
- Hapus trik SQL `LIMIT` di `SmsRepository`/`CallLogRepository` yang rawan `SQLiteException` OEM; batas tetap ditegakkan di loop Java.
- `MainActivity.formatResult` memakai `floor` plus batas aman `9e15` agar bilangan bulat besar tak terpotong presisi float.

### Changed
- Polling command hemat baterai: tunda 60 detik saat belum konfigurasi atau tanpa jaringan, 15 detik hanya saat online.
- Lookup kontak massal dihapus dari `queryCalls`; bulk memakai cache saja dan resolusi `PhoneLookup` hanya untuk 5 panggilan terakhir via `resolveContact()`.

### Security
- Fallback plaintext dihapus: `PreferencesManager` dan `MessageQueue` kini memakai memori volatil saat `EncryptedSharedPreferences` gagal, file `secure_prefs_fallback` dan `message_queue` lama dihapus. Token dan isi pesan tak lagi tertulis plaintext.
- Otorisasi callback Telegram diperketat: `TelegramCallbackQuery` kini membawa `from.id` (`TelegramUser`) dan `MonitoringService` memverifikasi pengirim, bukan `message.chat.id`.
- Validasi `SetupActivity` diperketat: token wajib pola digit + `:` + 20+ karakter, `chatId` wajib numerik.
- `TelegramClient` dipaksa `MODERN_TLS` dengan timeout 15 detik.
- Foto dihapus aman via `secureDelete()` (overwrite + delete), cache dipangkas ke 3 file, path absolut tak lagi di-log.
- Signing release gagal cepat bila `app/release.p12` ada tanpa `KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`, cegah sign debug diam-diam.

### Fixed
- `SendMessageWorker` tak lagi `delay()` berdetik-detik saat `429`; langsung `Result.retry()` dengan backoff WorkManager.
- `MonitoringService` menjalankan `startPeriodicLoops()` sebelum sync awal agar loop tak tertahan kirim riwayat.
- `sendFitted()` memakai `safeCut()` agar tak memotong surrogate pair dan entity HTML di batas 4000 karakter.
- `MessageScheduler` memakai `ExistingWorkPolicy.APPEND` agar antrean tak terbuang.
- `BootReceiver` tak lagi menjadwalkan `BootRestartWorker` ganda saat start sukses.

### Changed
- Query `SmsRepository` dan `CallLogRepository` mendorong `LIMIT` ke SQL (`sortOrder LIMIT n`), bukan filter di loop Java.
- `MessageQueue` memakai `apply()` async sebagai pengganti `commit()` sinkron.
- `CameraService.choosePhotoSize()` disederhanakan ke jarak terdekat 1280x720; log berisik di-gate `BuildConfig.DEBUG`.
- `MainActivity` init tombol digit via loop dan log di-gate `BuildConfig.DEBUG`.

## [1.6.2] - 2026-09-24

### Security
- Bake kredensial mati dicabut total: field `BuildConfig.BOT_TOKEN`/`CHAT_ID` dihapus dari `app/build.gradle`, env `BOT_TOKEN`/`CHAT_ID` dihapus dari `.github/workflows/build.yml`. Token kini hanya hidup di penyimpanan terenkripsi via input `SetupActivity`.

### Fixed
- Antrean tak lagi macet saat pesan masuk kala worker jalan (`MessageScheduler` pakai `APPEND_OR_REPLACE`).
- Duplikat riwayat di start pertama hilang: loop monitoring/kamera baru jalan setelah sync awal selesai (`startPeriodicLoops()`, guard `initialSyncStarted`).
- Foto miring diperbaiki: `JPEG_ORIENTATION` dihitung dari `SENSOR_ORIENTATION` + rotasi layar (rumus depan/belakang), buffer `ImageReader` naik ke 2 untuk repeating metering.
- `SetupActivity.toggleMonitoring()` set flag ON hanya bila service benar-benar jalan, gagal start tampilkan `msg_service_failed`.
- Throttle notif error dipisah: gagal upload pakai `lastUploadErrorNotice` sendiri, tak lagi menekan notif gagal capture.
- `sendFitted()` tak lagi kirim string kosong di batas 4000 karakter.

## [1.6.1] - 2026-09-24

### Fixed
- Foto kamera depan blank diperbaiki (`CameraService`): jepretan kini didahului pemanasan metering AE via repeating request + tunggu konvergen (fallback 5 detik), frame pemanasan dibuang dan hanya frame still yang disimpan.

## [1.6.0] - 2026-09-24

### Removed
- Edisi Parental Control dihapus total: flag `PARENTAL_UI`, varian paket `com.redeye.parentalcontrol`, `activity_parental.xml`, ikon parental, prefill token dari `BuildConfig`, dan job build parental di `.github/workflows/build.yml`. Setiap run kini hanya membangun `redeye-debug.apk` dan `redeye-release.apk`.

### Fixed
- Anti force-close: `MessageScheduler.scheduleMessageSend()`/`scheduleBootRestart()` tak lagi melempar (`IllegalStateException` WorkManager ditangkap, return `Boolean`), sehingga `BootReceiver`, `MonitoringService`, dan `NotificationForwarderService` aman dipanggil dari kondisi boot dingin.
- Fallback `startForeground()` di `MonitoringService` kini 3-arg `dataSync`-only agar tidak ikut melempar di API 29+ (`RemoteServiceException` bila service tak jadi foreground).
- `NotificationForwarderService` pindah kerja berat (prefs terenkripsi, antrean, label aplikasi) dari binder thread ke coroutine IO + pre-warm saat listener tersambung, hilangkan ANR tiap notifikasi.
- Auto-start setelah restart HP diperbaiki (`BootReceiver`, `BootRestartWorker`, `MessageScheduler`, `MonitoringService`, `AndroidManifest.xml`): tambah aksi `QUICKBOOT_POWERON`, verifikasi paket `MY_PACKAGE_REPLACED`, retry via WorkManager saat `startForegroundService` diblokir Android 12+, dan samakan syarat resume (`isMonitoringEnabled`, `isConfigured`, `!userDisabledMonitoring`).
- `PreferencesManager` kini singleton via `getInstance()` dengan `applicationContext`; fallback plaintext dipisah ke `secure_prefs_fallback` agar tidak menimpa file terenkripsi.
- `MessageQueue` pakai `applicationContext`, tulis `commit()` sinkron agar antrean tidak hilang saat crash, plus `removeMessages()` bulk agar `SendMessageWorker` tidak tulis ulang XML per pesan.
- `SendMessageWorker` hormati `retry_after` Telegram (tunda lalu `retry`), hapus antrean prematur saat `runAttemptCount >= 5`, dan kirim bulk.
- `CallLogRepository` cache kontak jadi LRU 500 + sinkron; `SmsRepository` pakai `Telephony.Sms.CONTENT_URI`.
- `NotificationForwarderService` pakai ulang `PreferencesManager`/`MessageQueue` dan dedupe LRU 200 tanpa `clear()` massal.
- `CameraService` tolak capture konkuren via guard atomik dan cegah duplikat background thread.
- `MonitoringService` `startForeground()` dibungkus fallback agar tidak crash, log error diredaksi via `redactToken`.
- `backup_rules.xml`/`data_extraction_rules.xml` kecualikan `secure_prefs_fallback`, `message_queue`, `encrypted_queue` dari backup.

## [1.5.0] - 2026-09-23

### Added
- Perintah Telegram `/notif on|off|status` untuk hidup/mati dan cek status penerusan notifikasi (`MonitoringService`, terdaftar di `setMyCommands` dan `/help`).

## [1.4.9] - 2026-09-23

### Fixed
- Warning `getApplicationLabel` non-null di `NotificationForwarderService` dihilangkan; kembali nol warning Kotlin.

## [1.4.8] - 2026-09-23

### Added
- Penerusan notifikasi HP anak ke Telegram via `NotificationForwarderService` (dedupe 5 menit, antre offline, hormati pause/disable; tombol `Read Notifications` + status di Setup dan `/status`).
- Menu perintah resmi Telegram via `setMyCommands`; notifikasi selesai sync memuat snapshot status kini.

## [1.4.7] - 2026-09-23

### Fixed
- Anotasi Node 20 hilang: `checkout` v4 ke v5, `upload-artifact` v4 ke v7, `action-gh-release` v2 ke v3 (semua Node 24, pin SHA).
- Runner di-pin ke `ubuntu-24.04` agar notice migrasi `ubuntu-latest` hilang dan build deterministik.

## [1.4.6] - 2026-09-23

### Fixed
- Sisa warning import deprecated hilang via `@file:Suppress("DEPRECATION")` di `PreferencesManager`/`MessageQueue`.

## [1.4.5] - 2026-09-23

### Fixed
- Nol warning Kotlin: `when` tanpa `val` tak terpakai di `SendMessageWorker`, tanpa `!!` di `CameraService`.
- API deprecated platform (`EncryptedSharedPreferences`/`MasterKey`, `createCaptureSession`, `requestSingleUpdate`) ditandai `@Suppress("DEPRECATION")`; migrasi ditunda karena butuh refactor async.
- `setup-java` v4 (deprecated) naik ke v5 dengan pin SHA.

## [1.4.4] - 2026-09-23

### Security
- Perintah Telegram basi (>10 menit) diabaikan; `lastUpdateId` dimajukan setelah diproses; token disamarkan dari log error.
- Status Setup menampilkan `Storage: encrypted/plaintext`; baterai memakai settings umum yang ramah kebijakan Play.

### Fixed
- Jalur parental menghormati `userDisabledMonitoring`; `initialSyncDone` di akhir sync; pesan panjang dipecah per baris.
- Watchdog kamera per-attempt; `BootReceiver` menangani `MY_PACKAGE_REPLACED`; polling idle 60 detik saat belum dikonfigurasi.
- `getNewSms`/`getNewCalls` dibatasi 500 baris; cursor berhenti baca setelah `limit`.

### Changed
- `MessageScheduler` memakai `ExistingWorkPolicy.KEEP`; `parseRetryAfter` tunggal di `NetworkUtils`; `escapeHtml` satu pass.

## [1.6.8] - 2026-09-24

### Fixed
- `ic_notification.xml` buang `android:tint="?attr/..."` yang membuat inflate ikon gagal dan `startForeground` force close saat monitoring dinyalakan.
- `MonitoringService.startMonitoring` batal grasi (`stopSelf`) bila notifikasi gagal dibangun atau `startForeground` dua kali gagal, plus fallback versi-guarded agar API 24-28 tak kena overload 3-arg.


### Fixed
- Polling Telegram memakai kredensial cache dan interval adaptif 15-30 detik saat idle, tanpa dekripsi ulang tiap poll.
- `CameraService.choosePhotoSize` di-cache per camera ID.
- `NotificationForwarderService` cache label aplikasi per package.
- Watchdog kamera kini satu `Job` yang dibatalkan saat capture selesai, bukan coroutine 50 detik yang menumpuk.
- `MessageQueue` memakai `Gson` shared dan `TypeToken` sekali buat.
- `cachedContact` satu lookup map.

### Changed
- Delay split `sendFitted` dan flush foto 1000ms jadi 500ms.

## [1.6.4] - 2026-09-24

### Fixed
- `device_admin.xml` tidak lagi meminta policy `force-lock`/`wipe-data` yang tak pernah dipakai.
- Disable monitoring kini bertahan: buka kalkulator tidak lagi mengaktifkan ulang secara diam-diam (`userDisabledMonitoring`).
- Kode rahasia `1234=` tidak picu dari hasil hitungan dan membersihkan riwayat display.
- `LIMIT` di `sortOrder` SMS/call log dihapus; batas ditegakkan di kode via `take` agar tak tergantung perilaku provider.
- Dedupe panggilan memakai `(DATE, _ID)` dengan `lastCallId` sehingga panggilan se-milidetik tak lolos.
- Nama file foto memakai sufiks milidetik agar tak saling menimpa dalam detik yang sama.
- `app/build.gradle`/`build.gradle`/wrapper/workflow naik ke `targetSdk`/`compileSdk` 35, AGP 8.5.2, Gradle 8.7.
- Jalur parental (`MainActivity.refreshParentalMonitoring`) kini menghormati `userDisabledMonitoring` dan tidak start ulang service yang dimatikan pengguna.
- `initialSyncDone` baru diset setelah history terkirim penuh; kegagalan di tengah tidak lagi menandai sync selesai.
- `lastUpdateId` Telegram baru dimajukan setelah update diproses sehingga perintah tak hilang saat handler crash.
- Pesan >4000 char dipecah per baris (`sendFitted`) sehingga tag HTML tak terpotong dan tak memicu 400 berulang.
- Watchdog kamera terikat ke attempt (`cameraAttempt`) sehingga capture basi tak mereset capture baru yang sedang jalan.
- `getRecentSms`/`getAllCalls` berhenti baca cursor setelah `limit` tercapai, tak lagi memuat seluruh inbox ke memori.
- `BootReceiver` menangani `MY_PACKAGE_REPLACED` (filter terpisah + `userDisabledMonitoring`) agar monitoring restart setelah update.
- Polling perintah Telegram melambat ke 60 detik saat bot belum dikonfigurasi agar hemat baterai.
- `getNewSms`/`getNewCalls` dibatasi 500 baris agar lonjakan data setelah offline lama tak membebani memori.

### Security
- `security-crypto` alpha `1.1.0-alpha06` naik ke stabil `1.1.0`; Retrofit `2.11.0` dengan OkHttp `4.12.0` eksplisit.
- Seluruh action GitHub di-pin ke SHA penuh.
- Field mati `SmsData.read` dan kolom `READ` dihapus dari proyeksi query.
- Perintah Telegram basi (>10 menit, field `date`) diabaikan untuk mencegah replay; token disamarkan dari log error (`redactToken`).
- Status Setup menampilkan `Storage: encrypted/plaintext` (`PreferencesManager.isStorageEncrypted`) agar fallback plaintext ketahuan.
- Permintaan baterai memakai `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` yang ramah kebijakan Play.

### Changed
- `gradle.properties`: `enableJetifier=false`, `nonTransitiveRClass=true` untuk build lebih cepat.
- `MessageScheduler` memakai `ExistingWorkPolicy.KEEP` agar worker antrean tak menumpuk dan mengirim ganda.
- `parseRetryAfter` disatukan di `NetworkUtils`; `escapeHtml` satu pass tanpa alokasi berantai.

## [Unreleased]

### Added
- Log force-close otomatis terkirim ke Telegram: `CrashReporter` di `utils` menyimpan crash ke file saat proses mati dan mengirimnya saat aplikasi dibuka berikutnya (dipasang di `ParentalMonitorApp`), maksimal 3500 karakter dengan token disamarkan.

### Fixed
- `MonitoringService` tidak lagi menghitung `chunked(10)` berulang; hasil chunk dipakai ulang.
- Kalkulator langsung menampilkan operator di riwayat saat tombol ditekan dan tetap menunjukkan angka sebelumnya, bukan `0` (`MainActivity`).
- Lokasi basi ditolak: `getLastKnownLocation` yang berumur di atas 2 menit diabaikan dan diambil fix baru.
- Rate-limit 429 tidak lagi membekukan loop monitoring; pesan langsung antre dan dijadwalkan via `WorkManager`.
- `BuildConfig.SYNC_INTERVAL` kini benar-benar dipakai sebagai default interval (`PreferencesManager`, `SetupActivity`).
- Field mati `SmsData.read` dihapus; `SYNC_INTERVAL` non-numerik di `app/build.gradle` jatuh kembali ke `5`.
- Force close saat Enable monitoring lalu OK pada dialog persetujuan bila izin lokasi background belum diberikan: `SetupActivity.toggleMonitoring` kini berhenti dan meminta izin dulu, dan `MonitoringService` hanya memakai tipe foreground `location` bila lokasi background sudah granted sehingga `startForeground` tak lagi melempar `SecurityException`.
- Force close `Unable to create service MonitoringService` (R8 menghapus signature generik `TypeToken` di build rilis): `MessageQueue` kini memakai `Array<QueuedMessage>::class.java` tanpa `TypeToken`, plus rule ProGuard `-keep class * extends com.google.gson.reflect.TypeToken`.

### Security
- Prompt Device Admin otomatis tiap buka aplikasi dihapus dari `MainActivity`; aktivasi hanya via tombol di `SetupActivity`.
- Action GitHub pihak ketiga di-pin ke SHA (`checkout`, `setup-java`, `upload-artifact`, `action-gh-release`).
- Interceptor HTTP logging yang mati dicabut total beserta dependensinya; antrean membuang pesan kedaluwarsa (>7 hari).
- Status Telegram kini melaporkan izin background location secara eksplisit.

### Changed
- Jeda antar pesan worker 500ms menjadi 200ms agar antrean lebih cepat terkuras.

## [1.4.3] - 2026-09-23

### Changed
- Sistem pengelolaan repo: `AGENTS.md` diselaraskan dengan workflow tunggal (artifact `apks`, rilis otomatis via tag `v*`, aturan kalkulator normal, `viewBinding` mati), dan `.gitignore` mengabaikan direktori `apks/`.

### Changed
- Workflow disatukan: satu file `.github/workflows/build.yml` membangun keempat APK sekaligus (standar debug+release, parental debug+release) dalam sekali jalan, mengunggah satu artifact `apks`, dan menerbitkan Release otomatis saat tag `v*`. `release.yml` dan input manual `build_type`/`parental_ui` dihapus; keystore hanya di-decode bila secret tersedia agar build fork/PR tetap jalan. `README.md` diperbarui mengikuti alur baru.

### Fixed
- Kalkulator kini berperilaku seperti kalkulator biasa: angka setelah `=` memulai entri baru, desimal panjang dibulatkan 8 digit, hasil bulat besar tidak overflow, tombol `.` di awal menjadi `0.`, input dibatasi 12 digit, operator beruntun aman setelah `Error`, dan simbol minus tampil `−` sesuai tombol (`MainActivity`).

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
