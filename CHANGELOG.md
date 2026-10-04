## [Unreleased]

### Fixed
- `MonitoringService.sendToTelegram` kontrak "handled": gagal yang sudah diantre (`queueOnFail`) lapor `true` agar kursor SMS/call maju dan worker yang antar; tak lagi kirim ganda loop + worker. `sendFitted` antre pecahan gagal lalu lapor handled.
- `/ping` ikut deteksi initial-sync macet (`initialStuck`) seperti watchdog agar klaim sehat jujur dan restart ikut pulihkan sync.
- Revive monitoring yang gagal start tak lagi diam: `SetupActivity` + `NotificationForwarderService` (revive + wake-restart) tulis `Log.w` agar kegagalan batasan background Android 12+ terpantau.

## [1.6.69] - 2026-10-04

### Fixed
- `SetupActivity.sendStatusNow` bedakan hasil kirim: `429` antre dengan backoff, `401`/`403` dan `400` chat-hilang set error + antre, `400` permanen lain tak diantre agar tak racuni `MessageQueue`.
- `MonitoringService` loop monitoring ikut `flushPendingAudio`/`flushPendingPhotos` tiap interval; `/flush` ikut flush media agar audio `KEPT` tak tersangkut sampai `/record` berikutnya.
- `BootReceiver` reset `photoPausedUntil` hanya saat reboot beneran; update paket (`MY_PACKAGE_REPLACED`) tak lagi hapus jeda `/pause`.
- `/ring` tolak saat `recordBusy`, `/record` tolak saat `ringBusy` agar mic tak merekam nada alarm.
- `NotificationForwarderService` jalur overflow pakai label app (`overflowLabel`), bukan package mentah, agar `/lastnotif` konsisten.

## [1.6.68] - 2026-10-04

### Fixed
- `MonitoringService` hapus flag memori `initialSyncStarted` yang write-only; gerbang nyata hanya prefs.
- `MonitoringService.restartAllLoops` ikut pulihkan initial-sync bila idle dan belum done.

## [1.6.67] - 2026-10-04

### Fixed
- `MonitoringService.sendAudioFile` rethrow `CancellationException` seperti `sendPhotoFile` agar cancel `/record` abort saat upload.
- `/log` kirim crash versi plain (strip tag) agar tak round-trip `400`.
- `/notif` pakai `lowercase(Locale.ROOT)` konsisten dengan handler lain.
- Loop watchdog ikut deteksi initial-sync macet dan reset flag agar sync periodik pulih.
- `SpeedMonitorActivity` session counter saturasi, bukan bagi-dua.

## [1.6.66] - 2026-10-04

### Fixed
- `CrashReporter.flushPending` kirim plain-text dan fallback pakai `fitted` agar laporan >4096 char tak loop `400`.
- `SetupActivity` clear-credentials ikut reset `pendingSmsOwner`.
- `MessageScheduler.scheduleMessageSendNext` pakai `KEEP` agar retry tak menumpuk chain `APPEND`.
- `NotificationForwarderService` stop dead-write `wakeUpdateId` persisten tiap poll (offset sudah ikut `lastUpdateId`).

## [1.6.65] - 2026-10-04

### Fixed
- `MonitoringService` wake-offset forwarder disatukan ke `lastUpdateId+1` agar `getUpdates` tak saling hapus antrean server; perintah tak lagi hilang saat service mati.
- `MonitoringService` owner auto-learn hanya untuk pesan perintah (`/`), DM privat biasa tak lagi klaim ownership.
- `MonitoringService` `handleCallbackQuery` dedup `callback_query.id` (cap 200) agar tap inline tak bisa di-replay.
- `MonitoringService` `sendFitted` antre ulang hanya pecahan gagal, bukan seluruh pesan, agar retry tak duplikat.
- `MonitoringService` `400` ganda-kegagalan kirim notif drop terlihat, bukan hilang diam.
- `/sms` tolak `*`/`#`; `/smsconfirm` hanya bisa dikonfirmasi peminta (`pendingSmsOwner`).
- `MessageQueue` counter expiry dipisah (`expiredDrops`) dari overflow; `SendMessageWorker` laporkan keduanya terpisah.
- `SetupActivity` `TOKEN_REGEX` dilonggarkan ke bentuk dasar agar token valid format baru tak ditolak; validasi akhir tetap via tes koneksi.
- `CameraService` `stopBackgroundThread` `join` 2000 ms jadi 500 ms agar thread IO tak tertahan.

## [1.6.64] - 2026-10-04

### Fixed
- `MonitoringService` `numberMatches`/`numbersEqualFast` samakan gate sufiks >= 9 digit dengan `SmsRepository`/`CallLogRepository`; hapus `idVariants` mati agar filter akhir `/history` tak bobol privasi sufiks.
- `MonitoringService` `rememberOwner` picu `registerBotCommands` saat owner pertama dipelajari agar menu owner langsung aktif tanpa restart.
- `SendMessageWorker` `sendDropNotice` kirim `parseMode` null agar sampel tak sebabkan `400` hilang diam.
- `MonitoringService` `sendFitted`/`sendToTelegram` antre sekali via `queueOnFail` agar part gagal-auth tak gandakan antrean.
- `MessageQueue` hitung drop kedaluwarsa ke `overflowDrops` agar `prune`/`sendDropNotice` terpantau.
- `MonitoringService` `pollTelegramCommands` persist offset sebelum handle (`finally` tetap) agar crash tak replay perintah non-idempoten.
- `BootReceiver` lewati thread + IO untuk `USER_PRESENT`/`USER_UNLOCKED` saat throttle 60 dtk atau service jalan.
- `SetupActivity` `sendStatusNow` antre via `MessageQueue` saat kirim direct gagal agar status tak hilang.

## [1.6.63] - 2026-10-04

### Fixed
- `SetupActivity` status tampilkan baris Owner + petunjuk DM privat pertama agar pengguna grup-only tak terkunci diam.
- `AdminReceiver` `onDisabled` antre peringatan Telegram agar owner tahu proteksi uninstall mati.
- `backup_rules.xml`/`data_extraction_rules.xml` buang eksklusi basi (`secure_prefs_fallback`, `message_queue`); nama nyata (`secure_prefs`, `encrypted_queue`) tetap dikecualikan.
- `admin_description` jujur hanya klaim policy `force-lock` sesuai `device_admin.xml`.
- `/stop` sebut forward notifikasi ikut pause agar tak dikira hanya data.
- `recordAndSendAudio`: cancel usai kirim sukses antre notif sukses, bukan menelannya; berkas parsial tetap dihapus.
- `BootReceiver` cek paket update via equals `schemeSpecificPart`, bukan `contains`.
- `ParentalMonitorApp` tak lagi hapus channel notifikasi agar setelan pengguna dihormati; fallback `/history` `200` jadi `100` baris agar hemat di DB besar.

## [1.6.62] - 2026-10-04

### Fixed
- `MonitoringService` `handleCallbackQuery`: tap tombol inline dianggap kini (`0L`) agar menu tak kedaluwarsa 15 menit; cabang `MUTATING` identik yang mati diruntuhkan; drop diam tetap jawab `answerCallback` agar spinner tak gantung.
- `MonitoringService` `pollTelegramCommands`: `400` yang menyebut offset selalu reset `lastUpdateId` ke `0`; guard `cur < 0` yang tak pernah tercapai dibuang dan `bad request` umum tak lagi me-reset offset.
- `/ping` polos keluar dari `SENSITIVE_COMMANDS` agar selaras menu publik; sub-perintah `camera`/`location` tetap digate `/photo` dan `/location` khusus owner.
- `CallLogRepository`/`SmsRepository` `numbersEqualFast`: cocok sufiks `endsWith` hanya untuk query >= 9 digit; di bawah itu wajib cocok persis (+ varian `628`/`08`) agar `/history` tak menarik nomor asing.
- `MonitoringService` `sendSmsPending`: timeout proporsional per part (60 dtk + 30 dtk tiap part tambahan); kirim parsial dilaporkan (`x/y` part) agar tak retry buta dan duplikat SMS.
- `SetupActivity` Save/Test yang tergusur aksi lebih baru tampilkan toast `setup_superseded`, tidak lagi gugur diam.
- `CallLogRepository`/`SmsRepository`: coba param URI `limit` sebelum fallback full-scan agar OEM tanpa `LIMIT` di `sortOrder` tetap hemat memori.
- `CrashReporter` `buildReport`: potong laporan aman tanpa membelah entity HTML.

## [1.6.61] - 2026-10-04

### Fixed
- `app/build.gradle` `assertReleaseKeystore` jadi peringatan saat tag tanpa keystore; build tetap jalan dan workflow menandai `debugsigned` agar CI tag tak merah.
- `SetupActivity` `hasAllPermissions` kecualikan `POST_NOTIFICATIONS` seperti `MainActivity`; tolak notifikasi tak lagi blokir aktifkan monitoring.
- `SetupActivity` Save dan Test saling batalkan via seq silang; aksi terakhir menang, tulis basi tak menimpa pengaturan baru.
- `MessageScheduler` tambah `scheduleMessageSendCoalesced` (`KEEP`) untuk cooldown auth 30 mnt; `scheduleMessageSendNext` tetap `APPEND` untuk `retryAfter`/backoff.
- `authBlocked` (`MonitoringService`, `SendMessageWorker`) dan guard flush `CrashReporter` hormati `400` seperti `401`/`403`.
- `README.md` perbaiki nama artefak tanpa keystore (`redeye-debugsigned-release.apk`, tag `redeye-vX.Y.Z-debugsigned-release.apk`) sesuai workflow.
- `photoPausedElapsed` buang jeda basi melebihi `480` mnt (elapsed reset saat reboot) agar OEM boot telat tak salah-pause foto.
- `SendMessageWorker` `sendChunked` berhenti di gagal pertama agar tak boros kuota dan minim duplikat saat retry.
- `AndroidManifest.xml` `tools:targetApi` `35` selaras `targetSdk`.
- `SpeedMonitorActivity` reskala sesi per-counter agar rasio down/up tak miring.

## [1.6.60] - 2026-10-04

### Fixed
- `PreferencesManager` `saveCoreConfig`/`saveTestCredentials` reset `lastUpdateId`/`wakeUpdateId` dan bersihkan wake ping saat token/chat ganti agar perintah bot baru tak terlewat offset basi.
- `handleCallbackQuery` samakan fallback tanggal sensitif ke `1L` agar tombol inline basi expired seperti perintah mutasi.
- `BootReceiver` reset `photoPausedUntil` hanya saat boot nyata (`BOOT_COMPLETED`/`QUICKBOOT_POWERON`/`MY_PACKAGE_REPLACED`), tidak lagi saat `USER_UNLOCKED`/`USER_PRESENT` agar `/pause` tak gugur tiap buka kunci.
- `MainActivity` hapus jalan pintas setup via tombol operator; kode rahasia hanya `1234` + `=` agar kalkulator berperilaku normal.
- `TelegramClient` naikkan dispatcher ke `6`/`4` dan pool ke `6`, `TelegramMediaClient` ke `4`/`2` agar long-poll ganda tak memblokir `sendMessage`/`sendPhoto`.
- `MessageScheduler` `scheduleMessageSendNext` pakai `APPEND` agar `retryAfter` rate-limit dihormati, tidak ter-coalesce oleh `KEEP`.
- `MonitoringService` `stopMonitoring`/`handleFgsTimeout` reset `ringBusy`/`recordBusy`/`smsBusy`/`audioFlushBusy`/`photoFlushBusy` agar tak macet-busy.
- `lastSyncTime` hanya untuk sync SMS/panggilan; hapus tulis saat kirim audio/foto sukses dan forward notif agar `/status` `Last sync` jujur.
- `sendToTelegram` tampilkan notifikasi lokal `Chat not found (400)` via `postAuthFailureReminder` agar chat hilang terlihat tanpa buka Setup.
- `checkAndSendNewData` kirim per chunk 10 dan majukan kursor progresif agar sukses parsial tak duplikat full 100.
- `NotificationForwarderService` baca `BIG_TEXT`/`TEXT_LINES`/`SUB_TEXT` bila `TEXT` kosong; spam-filter tak lagi catat tiap drop ke `/lastnotif` agar history legit tak kegusur.
- `CameraService` tulis foto via `.tmp` + rename atomik dan prune `.tmp` basi agar flush tak baca file setengah tulis.

## [1.6.59] - 2026-10-04

### Fixed
- Tambah import `TelegramMediaClient` yang hilang di `MonitoringService` sehingga build `v1.6.57`/`v1.6.58` gagal kompilasi.
- `NotificationForwarderService` catat `/ping` yang ia konsumsi; `MonitoringService` lewati restart ganda untuk ping tersebut namun tetap kirim pong.
- `checkAndSendNewData` majukan kursor hanya bila kirim sukses agar history tak hilang saat gagal.
- `BootReceiver` reset jeda foto juga saat `USER_UNLOCKED`/`USER_PRESENT` agar clock elapsed basi tak salah-pause foto.
- `flushPendingPhotos` patuhi blokir auth seperti flush audio agar blokir 30 menit tak diperpanjang sendiri.
- Initial sync ambil history per halaman (s.d. 500) dan kirim kabar singkat saat perangkat kosong agar tak sunyi.
- `handleFgsTimeout` reset kamera dan watchdog agar tak macet-busy.
- `lastSmsSendAt` hanya saat SMS terkonfirmasi agar upaya gagal bisa langsung diulang.
- Tulis prefs Setup dan baca kredensial service pindah ke background agar tak ANR.
- Menu bot: default hanya `/ping`+`/help`, menu penuh hanya via scope chat owner.
- `CrashReporter` lewati flush saat auth diblokir.
- Drop forwarder di atas cap tetap tercatat di `/lastnotif`.
- `lastSyncTime` hanya untuk sync data/media, tak lagi ikut pong dan balasan perintah.

## [1.6.58] - 2026-10-04

### Fixed
- `CrashReporter` tulis crash tanpa IO `boot_meta` di crash-thread; umur monotonic disematkan di baris pertama berkas.
- `SetupActivity` wipe kredensial ikut reset kursor SMS/panggilan, sync, foto, dan flag initial sync agar setup baru tak lewatkan history.
- `SetupActivity` save/test pakai nomor generasi agar job lama yang kalah tak menimpa pengaturan baru.
- `NotificationForwarderService` batasi antrean serial 64 agar burst notif tak OOM; kursor wake diselaraskan ke `lastUpdateId` utama agar `/ping` tak dieksekusi ganda.
- `SendMessageWorker` backoff gagal 60 detik dan retry terjadwal coalesce (`KEEP`) agar pesan baru tak tertahan di belakang antrean gagal.
- `backup_rules.xml`/`data_extraction_rules.xml` kecualikan `boot_meta` agar throttle basi tak ikut restore.
- `BootReceiver` kembalikan fallback `USER_PRESENT` untuk OEM telat boot dengan throttle 60 detik.
- `TelegramApi`/`TelegramMediaClient` pakai satu connection pool dan batas dispatcher 4/3 + 2/1 agar flush foto tak pacu 429.
- `/ping` khusus owner agar pong/delay tak bocorkan liveness ke anggota grup; daftar `/help` viewer disesuaikan.
- Varian nomor `62`/`0` dibatasi seluler (`628`/`08`) agar `626...` tak false-positive; banding tanpa alokasi list per baris.
- Initial sync lewati banner `Monitoring started` saat tak ada history baru agar restart tak dobel kirim.

## [1.6.57] - 2026-10-04

### Fixed
- `MonitoringService` sinkronkan dedup `updateId` dan eviksi seluruh kelebihan 300.
- `SetupActivity` hapus kredensial ikut reset SMS pending, hash perintah, dan offset update.
- Pencocokan nomor lintas format ID (`0812` vs `62812`) di `MonitoringService`, `SmsRepository`, `CallLogRepository`.
- `/ring` gagal cepat bila suara alarm null tanpa menahan busy.
- `NotificationForwarderService` serialkan antrean via dispatcher tunggal agar urutanFIFO terjaga.
- `SendMessageWorker` tunda 5 menit saat gagal agar tak hot-loop.
- Initial sync majukan kursor hanya saat chunk terkirim.
- `BootReceiver` hapus `USER_PRESENT` agar tak bisa di-spoof.
- Media Telegram pisah klien agar upload tak blokir polling.
- `/status`, `/battery`, `/uptime`, `/storage` owner-only dan `/help` difilter per peran.
- `SetupActivity` batalkan job save/test sebelumnya agar tak balapan.
- `CrashReporter` umur laporan pakai monotonic elapsed kebal skew jam.

## [1.6.56] - 2026-10-04

### Fixed
- `TelegramApi` naikkan limit dispatcher ke 6/4 agar dua long-poll `getUpdates` tak memblokir `sendMessage`/`sendPhoto`.
- `MonitoringService` cegah replay perintah mutasi: reset offset 400 hanya bila `lastUpdateId` negatif plus dedup 300 `updateId` terakhir.
- `ParentalMonitorApp` samakan importance channel ke `IMPORTANCE_MIN` dan recreate bila existing beda; channel resume matikan badge.
- `backup_rules.xml`/`data_extraction_rules.xml` kecualikan `file` `crash_pending.txt` dari backup.
- `SetupActivity` pindah baca token/chat terenkripsi ke `Dispatchers.IO`; input awal dikosongkan lalu diisi background.
- `CrashReporter` hapus file saat monitoring opt-out dan prune laporan >7 hari.
- `/apps`, `/log`, `/version` masuk `SENSITIVE_COMMANDS` wajib owner.
- Kalkulator: desimal kecil tak lagi jadi `0` (fallback eksponen) dan secret `1234` tetap terbuka via tombol operator.
- `NotificationForwarderService` lepas `fwdMutex` dari jalur HTTP agar burst notif tak head-of-line blocking.
- `BootReceiver`/`BootRestartWorker` throttle persisten via `boot_meta` dan thread bernama daemon.

## [1.6.55] - 2026-10-04

### Fixed
- Gerbang perintah tiga lapis di `MonitoringService`: mutasi dan baca sensitif (`/photo`, `/location`, `/lastcalls`, `/lastsms`, `/lastnotif`, `/contacts`, `/history`) wajib owner; baca jinak tetap boleh dari chat terkonfigurasi.
- Perintah berstempel masa depan kini dibalas petunjuk cek jam perangkat, bukan sekadar expired.
- Field cache kredensial/config di `MonitoringService`/`NotificationForwarderService` dijadikan `@Volatile` agar rotasi token dan owner terlihat lintas thread.

### Changed
- Artefak workflow: varian release tanpa keystore bernama `redeye-release-debugsigned.apk` agar tidak dikira rilis resmi bertanda (build tag tidak berubah karena keystore wajib).

## [1.6.54] - 2026-10-04

### Fixed
- `NotificationForwarderService.pollWakeOnce` kini owner-aware (terima `ownerUserId`, pelajari dari DM pribadi) dan berhenti polling bila monitoring dimatikan/belum consent; sebelumnya owner grup terkunci dari `/ping` wake dan token dipakai polling tiap 25 detik meski user opt-out.
- Hapus pong ganda wake-loop: forwarder hanya restart service, balasan pong tunggal dari `MonitoringService`.
- `MessageQueue.readLocked` prune pesan kedaluwarsa juga di jalur cache; sebelumnya basi >7 hari ikut dicoba kirim sampai restart.
- `/ping` tanpa argumen hanya restart loop bila pemanggil owner; sebelumnya viewer grup bisa flapping loop.
- `SmsRepository`/`CallLogRepository` early-return bila query tanpa digit (hindari `LIKE '%%'`).
- `CameraService.getCameraId` tidak lagi fallback ke kamera sembarang; lensa tak ditemukan kini error eksplisit.
- `MainActivity` tidak lagi mensyaratkan `POST_NOTIFICATIONS` untuk autostart; tolak notifikasi tak lagi mematikan monitoring diam-diam.
- `SendMessageWorker` jeda antar-chunk 500 ms jadi 1000 ms agar retry limit lebih jarang picu duplikat.

## [1.6.53] - 2026-10-04

### Fixed
- Perintah Telegram di `chatId` grup diperbaiki total: sebelumnya owner terkunci (ID user tak pernah sama dengan ID grup) dan tombol inline mati diam-diam. Kini ada `ownerUserId` yang dipelajari dari DM pribadi pertama, perintah mutasi wajib owner, perintah baca boleh dari chat terkonfigurasi, dan tombol dari non-owner dibalas penolakan eksplisit.
- `PreferencesManager.upgradeToPersistent` tidak lagi menghilangkan tulis yang datang saat migrasi memori-ke-enkripsi (delta-copy pasca-swap); listener kredensial kini disimpan di registry dan didaftarkan ulang ke store baru sehingga tetap hidup setelah upgrade.
- `MessageScheduler.scheduleMessageSend` pakai `ExistingWorkPolicy.APPEND` agar pemicu saat worker berjalan tidak terbuang (sebelumnya `KEEP` + jendela cek-akhir worker bisa macetkan antrean).
- `NotificationForwarderService` memanggil `tryRestorePersistent` saat start agar antrean pulih dari store terenkripsi, bukan terjebak volatile.
- `SmsRepository`/`CallLogRepository` normalisasi digit sebelum `LIKE`, fallback ke 200 riwayat bila prefilter kosong, dan short-code <7 digit dicocokkan eksak (sebelumnya selalu kosong).
- `NetworkUtils.isNetworkAvailable` hanya mensyaratkan `NET_CAPABILITY_INTERNET`; sebelumnya `VALIDATED` menahan kirim selamanya di captive portal/VPN setengah-valid.
- `MonitoringService.fetchLocation` menilai umur lokasi via `elapsedRealtimeNanos` (kebal skew jam dinding) dengan fallback wall-clock di API lama.
- `MonitoringService`/`SendMessageWorker` pengupas tag fallback hanya untuk tag sungguhan (`</?[a-zA-Z][^>]*>`); sebelumnya teks seperti `<3` ikut terbuang.
- `CrashReporter.buildReport` tidak lagi membaca token dari encrypted prefs di thread crash (risiko ANR/crash sekunder); redaksi mengandalkan regex pola token.
- `MainActivity` memakai `applicationContext` di thread background agar tidak menahan referensi Activity yang sudah destroy.
- `SpeedMonitorActivity` mereskala sesi (bagi-dua) saat mendekati batas `Long` alih-alih macet permanen di plafon.
- `SetupActivity` hapus kredensial ikut mereset `ownerUserId`; ganti `chatId` di `saveCoreConfig`/`saveTestCredentials` mengulang pembelajaran owner.
- `/ping camera` hanya me-restart loop kamera bila pemanggil owner; sebelumnya anggota grup bisa memicunya sebelum penolakan `/photo`.

## [1.6.52] - 2026-10-03

### Fixed
- `MonitoringService` meneruskan `senderOk` pada dispatch internal `/ping camera|location` ke `/photo`/`/location`; tanpa ini perintah owner ditolak gerbang owner-only (regresi `v1.6.51`).

## [1.6.51] - 2026-10-03

### Fixed
- `MonitoringService` membersihkan `ringPrevVolume`/`ringSavedAt` basi (>12 jam) saat start; sebelumnya nilai kedaluwarsa menumpuk tanpa pernah dibersihkan.
- `SendMessageWorker` menjadwalkan retry terbatas (maks 5 percobaan, tiap 30 menit) bila antrean tak kosong tapi kredensial belum dikonfigurasi; sebelumnya worker selesai diam-diam tanpa retry.

## [1.6.50] - 2026-10-03

### Fixed
- Perintah Telegram kini owner-only seluruhnya: gerbang `senderOk` di `MonitoringService.handleTelegramCommandInner` berlaku untuk semua perintah (sebelumnya hanya 15 perintah mutasi), dan `handleCallbackQuery` mengabaikan tombol inline dari non-owner; sebelumnya anggota grup bisa menjalankan `/photo`, `/location`, `/lastcalls`, `/lastsms`, `/status`, dll. bila `chatId` berupa ID grup.
- `SetupActivity.updateStatus` menampilkan `monitoring_paused` saat jeda `/stop` aktif; sebelumnya status tetap `Monitoring active` karena service masih `isRunning`.
- `NotificationForwarderService` dipindah ke proses utama (hapus `android:process=":wake"` di `AndroidManifest.xml`); sebelumnya dua proses baca/tulis `EncryptedSharedPreferences` dan `MessageQueue` yang sama tanpa sinkronisasi lintas proses.
- `MonitoringService.startForegroundImmediate` mencatat `Log.w` saat gagal; sebelumnya exception ditelan diam-diam.
- Hapus `ACTION_STOP_MONITORING` yang dead code (didefinisikan dan di-handle tapi tidak pernah dikirim; stop resmi via `stopService()` dan jeda via `/stop`).
- `MonitoringService.fetchLocation` menambahkan override `LocationListener.onStatusChanged` yang hilang, memperbaiki `AbstractMethodError` di API 24-28 (mis. OPPO CPH1853 API 27): kode dikompilasi dengan `compileSdk` 35 di mana metode itu punya implementasi default, tapi di runtime lama masih abstrak sehingga sistem crash saat dispatch perubahan status provider.
- `MonitoringService.onCreate`/`onStartCommand` memanggil `startForeground` segera saat masuk sebelum percabangan/IO prefs, memperbaiki `RemoteServiceException: Context.startForegroundService() did not then call Service.startForeground()` di API 27 (mis. OPPO CPH1853) pada jalur `STOP`, restart sticky dengan intent null saat monitoring nonaktif, dan return dini kredensial sebelum foreground sempat dipasang.

## [1.6.49] - 2026-09-29

### Changed
- Notifikasi foreground kini polos: judul `notification_title` menjadi `RedEye Monitor` dengan teks `Monitoring active`, channel menjadi `Monitoring` / `Monitoring Alerts`, ketukan notifikasi membuka `SetupActivity`, dan loop teks kecepatan live tiap 5 detik dihapus (`speedTitle`, `startSpeedTracking`, `refreshSpeedNotification`, `stopSpeedTracking`).

## [1.6.48] - 2026-09-29

### Fixed
- `MonitoringService` tegakkan `senderOk` untuk 15 perintah mutasi (`/lock`, `/ring`, `/sms`, `/smsconfirm`, `/record`, `/stop`, `/resume`, `/pause`, `/photointerval`, `/syncinterval`, `/camera`, `/notif`, `/restart`, `/flush`, `/clearqueue`); sebelumnya anggota grup bisa menjalankannya dan tombol inline `Stop`/`Resume`/`Pause 60` lolos tanpa cek pengirim.
- `TimeFmt` ganti `ThreadLocal.withInitial` (API 26+) ke subclass `ThreadLocal` agar tidak `NoSuchMethodError` di `minSdk` 24-25.

## [1.6.47] - 2026-09-29

### Fixed
- `gradle.properties` batalkan `org.gradle.configuration-cache` karena `assertReleaseKeystore` memakai Groovy closure yang tidak didukung configuration cache (build `v1.6.47` pertama gagal di CI).
- `SetupActivity.updateStatus` tidak lagi klaim `Monitoring active` saat service mati; status kini murni `isMonitoringEnabled && MonitoringService.isRunning`.
- `PreferencesManager.saveCoreConfig` selalu reset `credentialError`/`credentialErrorAt` tiap simpan; sebelumnya test koneksi sukses atau simpan ulang kredensial yang sama tidak membuka blokir `authBlocked()` hingga 30 menit.
- `MainActivity.onCreate` menjalankan `startMonitoringInBackground` di background thread; sebelumnya baca/tulis encrypted prefs (IO keystore) dipanggil via `runOnUiThread` sehingga berisiko ANR.

### Changed
- `TimeFmt` pakai `ThreadLocal` `SimpleDateFormat` agar tidak alokasi ulang pola tiap format waktu.
- `NetworkUtils.parseRetryAfter` fast-path `null`/tanpa `retry_after` agar tidak parse Gson sia-sia.
- `AppPermissions.requiredPermissions` pakai array statis per SDK agar tidak alokasi array tiap cek izin.
- `SpeedMonitorActivity` cache string format sesi + unavailable agar loop 1 detik tidak lookup resource.
- `gradle.properties` aktifkan build paralel, caching, configuration-cache, dan Kotlin incremental agar build CI lebih cepat.
- `MonitoringService` cache `PendingIntent` dan judul notifikasi kecepatan agar loop 5 detik tidak mengulang IPC `getActivity` dan lookup resource.
- `checkAndSendNewData` baca penanda SMS/call-log sekali per siklus ke variabel lokal, memangkas baca dekripsi berulang ke encrypted prefs.
- Regex split perintah di-hoist ke companion agar `Pattern` tidak dikompilasi ulang per perintah.

## [1.6.46] - 2026-09-29

### Added
- `/ping` kombinasi target: `/ping camera` membangunkan loop kamera lalu jepret foto, `/ping location` mengirim lokasi terkini; tanpa argumen tetap pong + wake; argumen lain dibalas usage.
- Label tombol inline Telegram ke bahasa Inggris (`Photo`, `Location`, `Calls`, `Front`, `Back`, `Pause 60 min`, `Resume`, `Battery`, `Status`).

### Removed
- Perintah `/wake` (dan alias `/bangun`) dihapus; fungsinya sudah bergabung ke `/ping` yang sekaligus membangunkan loop/service.

### Changed
- `/ping` gabung dengan wake: di proses utama ikut menghidupkan ulang loop yang mati, di proses `:wake` ikut menyalakan `MonitoringService` + watchdog + antrean bila service utama mati.
- Seluruh perintah Telegram ke bahasa Inggris: usage `/photo`, `/camera`, `/sms`, `/contacts`, `/history`, teks `/help`, menu `BotCommand`, dan semua balasan error/status; argumen `depan`/`belakang` tetap diterima sebagai alias `front`/`back`.

## [1.6.45] - 2026-09-29

### Changed
- `TelegramClient` batasi OkHttp `Dispatcher` (`maxRequests = 4`, `maxRequestsPerHost = 2`) dan `ConnectionPool` (2 koneksi, keep-alive 1 menit) agar thread/buffer/socket idle tidak menumpuk di kedua proses.
- `NotificationForwarderService` pangkas batas LRU `lastSent`/`pkgHits`/`groupSeen` 200 ke 100 dan evict entri basi `dropNoticeAt` agar cache notifikasi tidak tumbuh tanpa batas.

### Added
- Perintah `/bangun` dan `/ping` yang hidup di proses `:wake` `NotificationForwarderService`: polling ringan mandiri tiap 25 detik, `/bangun` membangunkan `MonitoringService` + watchdog + antrean walau proses utama dibunuh LMK, `/ping` dijawab langsung dari proses `:wake`; `/camera` dan `/location` sengaja tidak dipertahankan.
- `NotificationForwarderService` berjalan di `android:process=":wake"` agar tidak ikut mati saat proses utama kehabisan memori; offset wake (`wake_update_id`) terpisah dari `last_update_id` agar tidak mencuri perintah lain.
- `MonitoringService` tangani `/bangun` saat masih hidup: restart semua loop + jadwalkan watchdog dan pengiriman antrean.
- Guard lintas-proses via `ActivityManager.getRunningServices` agar polling `/bangun` hanya jalan saat `MonitoringService` benar-benar mati, menghindari `409` conflict ganda dengan polling utama.

## [1.6.44] - 2026-09-29

### Fixed
- `MonitoringService` restart sendiri saat `onTaskRemoved` bila monitoring masih aktif, agar swipe-task tidak membunuh polling `/location`, `/camera`, `/ping`.
- `MonitoringService.onDestroy` jadwalkan ulang `BootRestartWorker` dan watchdog bila monitoring masih aktif, agar kill LMK memulihkan perintah tanpa tunggu 15 menit.
- `NotificationForwarderService` jadi watchdog: `onListenerConnected` dan `onNotificationPosted` hidupkan ulang `MonitoringService` bila flag masih aktif, memakai jalur listener yang diprioritaskan sistem.

## [1.6.43] - 2026-09-27

### Fixed
- `MonitoringService` loop perintah dan watchdog kebal `Throwable` agar tak ada error yang membunuh polling; loop hanya berhenti saat dibatalkan sistem.
- `MonitoringService` kirim notifikasi lokal throttled 6 jam saat token ditolak `401`/`403` agar pemilik bisa pulihkan via `SetupActivity` meski Telegram mati total.
- `MonitoringService.pollTelegramCommands` retry sekali dengan kredensial fresh saat `401`/`403` agar cache token basi tidak memblokir perintah 30 menit; error hanya ditandai bila token fresh tetap ditolak.
- `MonitoringService.pollTelegramCommands` tangani `409` conflict dan `429` rate-limit dengan backoff agar polling pulih sendiri tanpa hammering API.
- Perintah Telegram no respond di grup/channel: auth polling kini terima `chat.id` selain `from.id`, callback inline terima `chat.id` grup, dan model `TelegramUpdate` tangani `channel_post`/`edited_channel_post` yang sebelumnya diam-diam dibuang.
- Perintah no respond 30 menit setelah sekali gagal kirim: `authBlocked()` di `MonitoringService` dan `SendMessageWorker` kini hanya blokir untuk `401`/`403`; error `400` chat-missing tidak lagi mematikan polling perintah.
- `PreferencesManager` upgrade in-place (`upgradeToPersistent`) agar referensi lama di `MainActivity`, `SetupActivity`, dan service tetap valid; `refreshInstance` tidak lagi mengganti singleton dan tidak menghapus `credential_error` prematur, hanya saat kedaluwarsa 30 menit atau clock skew.
- `MonitoringService.onCreate` pindahkan `refreshInstance` ke background thread agar tidak deadlock di main thread dan kredensial terisi ulang via `refreshCreds`.
- `MessageQueue` prune pesan kedaluwarsa 7 hari juga pada jalur volatil `addMessage`/`addMessages` agar perilaku konsisten dengan `readLocked`.
- `CameraService` pastikan `Image` selalu ditutup walau `saveImage` gagal agar kamera tidak bocor.
- `SetupActivity.updateStatus` dasarkan toggle pada `monitoring_enabled`, bukan flag statis `isRunning`, agar status benar setelah process death.
- `NotificationForwarderService` samakan jalur banjir (`pendingPosts > 32`) dengan `handlePosted`: cache label dan catat `groupSeen` agar dedup grup konsisten.
- `SpeedMonitorActivity` clamp akumulasi sesi agar tidak overflow `Long` pada sesi sangat panjang.
- `.github/workflows/build.yml` sempitkan `concurrency.group` per `github.ref` agar push ke `main` tidak membatalkan run PR.

## [1.6.42] - 2026-09-27

### Fixed
- `MonitoringService` tolak seluruh perintah Telegram yang pengirimnya bukan owner (`senderOk` wajib untuk baca maupun mutasi) agar anggota grup tidak bisa menarik data via `chat.id`.
- `MonitoringService` lindungi foto yang sedang ditulis: `flushPendingPhotos` lewati file aktif dan file berumur < 10 detik, `prunePhotoCache`/`pruneAudioCache` kecualikan file aktif agar tidak kirim foto parsial atau hapus rekaman berjalan.
- `PreferencesManager.refreshInstance` salin ulang snapshot hingga stabil (maks 3x) agar tulisan yang berpacu saat migrasi memory ke encrypted tidak hilang.
- `MainActivity` satukan init kalkulator DEBUG/RELEASE yang duplikat; refresh prefs dan start monitoring kembali ke UI thread dengan guard `isFinishing`/`isDestroyed`.
- `MainActivity.setOperator` reset `justCalculated` saat chaining agar history tampil `5 +` bukan ekspresi basi.
- `MonitoringService.startMonitoring` pastikan juga `loopWatchdogJob` dan `speedJob` hidup sebelum early-return agar loop mati tidak lolos dari pemulihan.
- `/ring` tampilkan usage saat argumen invalid, bukan diam-diam berdering 15 detik.
- `CrashReporter` kirim ulang laporan crash sebagai plain text saat HTML ditolak 400 agar bukti crash tidak dibuang.
- `isPremiumSmsNumber` persempit ke short code dan prefix `1900` agar nomor internasional seperti `+976` tidak ikut diblokir.
- Notifikasi speed pakai `setOnlyAlertOnce(true)` agar update tiap 5 detik tidak flicker.

## [1.6.41] - 2026-09-27

### Fixed
- `MonitoringService` wajibkan `senderOk` untuk seluruh mutasi (`/lock`, `/ring`, `/sms`, `/smsconfirm`, `/record`, `/stop`, `/resume`, `/pause`, `/photointerval`, `/syncinterval`, `/camera`, `/notif`, `/restart`, `/flush`, `/clearqueue`) agar anggota grup tidak bisa ubah state via `chatOk`.
- `SendMessageWorker` validasi ulang token/chat per pesan dan abort bila berubah agar ganti bot di Setup tidak kirim antrean ke chat lama; drop notice hanya bila kredensial masih sama.
- `PreferencesManager` buat getter fail-safe ke default agar keystore invalid tidak crash loop di `SetupActivity`/`MainActivity`.
- `PreferencesManager`, `MessageQueue`, `MainActivity`, `SetupActivity`, dan `ParentalMonitorApp` pindahkan init crypto `EncryptedSharedPreferences` ke background agar `onCreate`/`clearCredentials` tidak ANR.
- `BootReceiver` reset `photoPausedUntil` hanya untuk boot sungguhan (`BOOT_COMPLETED`, `QUICKBOOT_POWERON`, `MY_PACKAGE_REPLACED`) agar `/pause` tidak gugur saat `USER_PRESENT`/`USER_UNLOCKED`.
- `NetworkUtils` wajibkan `NET_CAPABILITY_VALIDATED` agar tunnel VPN tanpa internet tidak dianggap online dan membakar retry antrean.
- `SetupActivity` minta konfirmasi dialog sebelum long-press Save menghapus kredensial agar hapus tak sengaja tidak matikan monitoring dan buang antrean.
- `/history` samakan batas ke min 7 digit agar konsisten dengan saring repo dan `numberMatches`.
- `MonitoringService` lewati restore volume `ringPrevVolume` saat dering aktif agar restart service tidak bisukan `/ring` yang berjalan.
- `restartAllLoops` abaikan pemicu ganda dalam 10 detik agar watchdog dan command manual tidak churn job.
- `sendInitialData` kirim pesan partial jujur saat sebagian chunk gagal, bukan klaim complete.
- `MessageQueue` tidak lagi hitung expiry TTL 7 hari sebagai overflow agar notif drop penuh tidak campur.
- Watchdog kamera service 35 detik jadi 45 detik agar timeout 30 detik `CameraService` yang pegang kegagalan, bukan ganda.
- `SmsRepository` dan `CallLogRepository` kembalikan daftar kosong untuk query digit < 7 agar `/history` tidak membocorkan nomor orang lain.
- `SetupActivity.clearCredentials()` tampilkan `setup_credentials_cleared` agar hapus kredensial tidak terlihat seperti simpan berhasil.
- `MainActivity` hapus kode izin mati (`permissionLauncher`, `backgroundPermissionLauncher`, flag prompt, redirect Settings) agar stealth kalkulator tidak bocor.
- `MonitoringService` reset `lastUpdateId` saat `getUpdates` `400` berbasis offset agar polling tidak macet selamanya.
- `SetupActivity.sendStatusNow()` samakan expiry skew jam dengan `authBlocked` agar status blokir konsisten.
- `strings.xml` perbaiki teks `setup_bad_interval` dari klaim clamped menjadi invalid karena input di luar rentang ditolak.
- `MainActivity` simpan dan pulihkan state kalkulator saat rotasi agar entri tidak reset ke `0`.
- `MainActivity` mode stealth tidak lagi meminta izin atau membuka Settings otomatis; kekurangan izin hanya dicatat agar dialog tidak bocor ke anak, pemberian izin lewat `SetupActivity`.
- `BootReceiver` dan `BootRestartWorker` kedaluwarsakan backoff kredensial 30 menit secara konsisten, tidak hanya saat jam mundur.
- `BootReceiver` selalu reset `photoPausedUntil` saat boot agar jeda foto berbasis `elapsedRealtime` yang basi tidak bertahan 120 menit setelah reboot.
- `CrashReporter` pertahankan laporan crash saat `400` berarti chat hilang / bot diblokir agar tidak hilang sebelum terkirim.
- `NotificationForwarderService` pindahkan cek prefs dan `PackageManager` jalur spam ke coroutine IO agar binder thread tidak ANR.
- Ekstrak daftar izin ke `utils/AppPermissions.kt` agar `MainActivity` dan `SetupActivity` tidak divergen.
- `MonitoringService.handleCallbackQuery` teruskan `sentAtSec` dari `message.date` dan tolak tanpa tanggal agar tombol inline lama `/stop`, `/resume`, `/pause` kedaluwarsa; batas 5 menit diperluas ke seluruh mutasi.
- `SetupActivity.testConnection` tidak lagi tulis `credentialError` untuk probe `400`/`401`/`403` agar token/chat uji beda tidak blokir `SendMessageWorker` 30 menit.
- `SetupActivity.saveSettings` pindahkan `saveCoreConfig` ke `Dispatchers.IO` agar tap Save tidak ANR.
- `SetupActivity.testConnection` guard `isFinishing`/`isDestroyed` di blok `Main` agar rotasi/tutup saat `IO` tidak crash.
- `MonitoringService.ringDevice` pakai `TYPE_ALARM` di `STREAM_ALARM` agar dering nyaring dan restore volume cocok.
- `MonitoringService.checkAndSendNewData`/`sendInitialData` majukan watermark walau pesan antre agar satu data tidak ganda via worker dan loop periodik.

### Added
- Proteksi branch `main`: wajib status `build` hijau (strict), tanpa force-push/hapus branch, resolusi konversi wajib.
- Template PR/issues (`.github/pull_request_template.md`, `.github/ISSUE_TEMPLATE/`), `.github/CODEOWNERS`, dan `.github/dependabot.yml` untuk update Actions mingguan.

### Changed
- `.github/workflows/build.yml` abaikan perubahan docs (`**.md`, `LICENSE`) agar edit dokumentasi tidak memicu build.

## [1.6.40] - 2026-09-26

### Fixed
- `SendMessageWorker` hitung exception per-pesan sebagai gagal dan lanjut agar satu pesan beracun tidak memblokir antrean di belakangnya.
- `SetupActivity.sendStatusNow()` hormati backoff kredensial 30 menit agar tap berulang tidak memperpanjang blokir tanpa batas.
- `SetupActivity` tampilkan status jalan dari flag dan `MonitoringService.isRunning` agar service mati tidak terlihat aktif.
- `BootRestartWorker` kirim laporan crash hanya setelah cek consent agar stop pengguna dihormati.
- `MessageQueue` hapus entry korup saat parse gagal agar tidak di-parse ulang tiap baca.
- `SetupActivity` ganti `Toast` pesan exception mentah dengan `msg_action_failed` generik.

## [1.6.39] - 2026-09-26

### Changed
- `.github/workflows/build.yml` pakai satu grup `concurrency` global agar hanya satu run `Build APK` berjalan dalam satu waktu; run baru membatalkan run lama lintas `ref`.

### Fixed
- `.github/workflows/build.yml` step `Build APKs` coba ulang sampai 3 kali dengan jeda bertingkat agar gagal transient `429 Too Many Requests` dari Maven Central tidak langsung merah.
- `MonitoringService` lacak `smsJob` dan batalkan di `stopMonitoring()`/`handleFgsTimeout()` agar kirim SMS tidak yatim lalu ganda saat retry.
- `SendMessageWorker.sendChunked()` berhenti di part pertama yang kena `429`/`401`/`403` agar tidak memperparah rate-limit dan auth-error.
- `MonitoringService` kirim notice kedaluwarsa saat command basi, dan command destruktif (`/lock`, `/ring`, `/sms`, `/smsconfirm`, `/record`) kedaluwarsa dalam 5 menit.
- `MonitoringService` polling command tiap 10-30 detik agar `/lock`/`ring` lebih responsif, dan `handleFgsTimeout()` ikut batalkan `ringJob`/`recordJob`/`smsJob`.
- `MainActivity` hapus `Toast` auto-start monitoring agar mode stealth kalkulator tidak bocor ke anak.
- `SetupActivity.reviveMonitoringIfNeeded()` hanya start service bila seluruh izin + background location granted.
- `NotificationForwarderService` pulihkan `prefsRef` ke encrypted saat keystore pulih agar tidak terjebak di `MemoryPrefs` volatile.
- `SmsRepository`/`CallLogRepository` saring hasil `LIKE` di memori dengan aturan `endsWith` 7+ digit agar `/history` tidak narik nomor orang lain.
- `SpeedMonitorActivity.onResume()` selalu reset baseline agar trafik selama pause tidak menggembungkan sesi.

## [1.6.38] - 2026-09-26

### Fixed
- `NotificationForwarderService.forwardLocked()` queue 400 saat `isChatMissing()` seperti jalur teks, hormati `retry_after` 429 via `scheduleMessageSendNext()`, dan catat `pkgRecord()` hanya setelah kirim sukses agar retry tidak mempercepat spam-filter.
- `SendMessageWorker` perlakukan exception pemrosesan sebagai transient (`break` tanpa `registerFailures()`) dan lewati `registerFailures()` saat offline agar antrean tidak drop permanen setelah 5 run.
- `MessageQueue.tryRestorePersistent()` prune expiry 7 hari pada merge volatile agar pesan basi tidak lolos ke persisten.
- `MonitoringService.sendAudioFile()` perlakukan 400 chat-hilang sebagai `KEPT` + `credentialError` seperti foto; `pruneAudioCache()`/`prunePhotoCache()` antrekan notice drop saat buang media tak terkirim.
- `MonitoringService` terima command owner via `from.id` atau `chat.id` agar perintah via grup tetap jalan seperti `handleCallbackQuery()`.
- `MonitoringService.fetchLocation()` fallback berurutan `GPS_PROVIDER` lalu `NETWORK_PROVIDER` masing-masing timeout 20 detik bila fix pertama gagal.
- `MonitoringService.sendSmsPending()` pakai `sentAction` unik `nanoTime`+`UUID` dan `requestCode` acak `UUID` dengan `FLAG_CANCEL_CURRENT` agar dua `/sms` cepat tidak tabrakan konfirmasi 60 detik.
- `SpeedMonitorActivity.onResume()` pertahankan baseline `onSaveInstanceState()` dan seed baseline hanya bila kosong agar sampel pertama tidak selalu skip.
- `MainActivity` simpan/restore `permissionAsked`/`backgroundAsked`/`settingsRedirected` via `onSaveInstanceState()` agar rotasi tidak picu `permissionLauncher` ganda.
- `SmsRepository`/`CallLogRepository` coba `LIMIT` SQL sebelum fallback tanpa `LIMIT` agar OEM tetap batasi kursor dan loop `result.size < limit` tidak baca seluruh tabel.
- `CrashReporter` batasi laporan akhir `<=4000` char setelah `Html.escape()` dan hapus file pending saat 400 agar tidak retry selamanya.
- `PreferencesManager` publikasikan `KEY_CAMERA_INTERVAL`/`KEY_MONITORING_PAUSED`/`KEY_PHOTO_PAUSED_UNTIL`/`KEY_SYNC_INTERVAL`/`KEY_USER_DISABLED`/`KEY_USER_CONSENTED`; listener `MonitoringService`/`NotificationForwarderService` pakai konstanta bukan literal.
- `TelegramIncomingMessage` tambah field `from` agar cek owner via grup tersedia di polling command.
- `CameraService` pakai `SurfaceTexture(0)` untuk dummy metering agar tidak crash `NoSuchMethodError` di API 24-25 (`SurfaceTexture(boolean)` hanya ada sejak API 26).
- `MessageScheduler.scheduleMessageSendNext()` pakai `ExistingWorkPolicy.APPEND` agar follow-up dari dalam `SendMessageWorker` tidak digugurkan `KEEP` saat worker masih running.
- `NotificationForwarderService` terapkan dedup 30 detik dan spam-filter per-paket di jalur burst `pendingPosts > 32`; cek `credentialError` sebelum direct-send agar 401 tidak diulang tiap notifikasi.
- `MonitoringService.sendToTelegram()` dan `SendMessageWorker` perlakukan 400 `chat not found`/`bot was blocked` sebagai auth-error (queue + backoff) bukan drop permanen.
- `SetupActivity.testConnection()` ikut simpan interval sync/kamera via `saveCoreConfig`; `saveSettings()` tolak interval di luar `1-1440`/`0-60` dengan `setup_bad_interval` tanpa clamp diam-diam.
- `SetupActivity.clearCredentials()` stop `MonitoringService` dan kosongkan `MessageQueue` agar data antrean lama tidak terkirim ke chat baru; `stopMonitoringConfirmed()` pakai `stopService()` langsung.
- `SetupActivity` minta pengecualian baterai via `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` per paket dengan fallback ke halaman settings.
- `MainActivity` pasang listener tombol kalkulator null-safe agar varian layout tanpa satu ID tidak crash; hapus log RELEASE mati.
- `CrashReporter.buildReport()` samarkan pola mirip token via regex walau token prefs tak terbaca.
- `MessageQueue` hitung `overflowDrops` juga di jalur volatile dan merge-restore agar notice drop akurat.
- `PreferencesManager.putAllValues()` dukung `Set<String>` via `putStringSet` agar migrasi snapshot tidak drop diam-diam.
- `BootRestartWorker.postResumeReminder()` throttle 3 jam agar watchdog tidak spam notifikasi resume.
- Listener prefs pakai konstanta `KEY_BOT_TOKEN`/`KEY_CHAT_ID`/`KEY_NOTIF_FORWARD`/`KEY_MONITORING_ENABLED` bukan literal.
- `MonitoringService` majukan offset `getUpdates` di `finally` setelah update diproses agar command gagal tidak hilang permanen.
- `MonitoringService.handleCallbackQuery()` terima `from.id` atau `message.chat.id` agar tombol inline tetap jalan di grup.
- `SetupActivity` set status monitoring sebelum start/stop service agar prefs konsisten saat start background ditolak; minta background location langsung bila foreground sudah granted.
- `MessageQueue` hitung drop overflow (`consumeOverflowDrops()`), `SendMessageWorker` kirim notice drop saat flush sukses agar kehilangan antrean terlihat.
- `MainActivity` arahkan ke Settings sekali (`redirectToSettingsOnce()`) bila izin tetap ditolak setelah diminta.
- `MonitoringService.numberMatches()` samakan akhiran hanya untuk 7+ digit agar nomor berbeda tidak tertukar.
- `activity_setup.xml` masker field token via `textPassword` agar token gagal tes tidak terlihat jelas.
- `MainActivity` minta izin runtime sekali per proses (`permissionAsked`/`backgroundAsked`) agar dialog tidak spam tiap `onCreate()`.
- `BootReceiver` reset `photoPausedUntil` saat boot agar jeda foto berbasis `elapsedRealtime()` tidak memanjang melewati reboot.
- `MonitoringService` hapus heartbeat file yang tak terpakai (`touchHeartbeat()`/`heartbeatFresh()`) agar loop tidak tulis IO sia-sia.
- `MessageScheduler.scheduleMessageSend()` pakai `ExistingWorkPolicy.KEEP` agar burst antrean tidak menumpuk worker; worker lanjutan sudah dijadwalkan bila antrean tersisa.
- `NotificationForwarderService` teruskan notifikasi via `forwardToTelegram()` saat `pendingPosts` penuh, bukan drop; backpressure `inFlight` + antrean persisten menampung sisanya.
- `PreferencesManager.refreshInstance()` migrasi seluruh snapshot termasuk status error kredensial agar backoff auth tidak ikut terhapus.
- `SpeedMonitorActivity.onResume()` cegah double-post sampler dan reset baseline agar trafik saat pause tidak jadi spike; total sesi tetap dipertahankan.
- `SmsRepository`/`CallLogRepository` hapus fallback sort `LIMIT` spesifik provider agar tidak error di OEM; andalkan query bundle dan cap di memori.
- `MessageQueue.addMessage()`/`addMessages()` salin via `toMutableList()` sebelum mutasi agar tidak aliasing `cached`.
- `PreferencesManager` hapus `resetCorruptKeystore()` yang tak terpakai agar tidak ada wipe keystore/prefs tak disengaja.
- `SetupActivity` tambah clear kredensial via long-press `setupSaveButton` (`clearCredentials()`), field kosong tak lagi ambigu dengan mask `STORED_MASK`.
- `MonitoringService.redactToken()` samarkan pola mirip token via regex bila token belum termuat agar log tidak bocor.
- `CameraService.stopBackgroundThread()` lewati `join()` di main thread agar `onDestroy()`/`forceReset()` tidak ANR.
- `SpeedMonitorActivity.onResume()` tidak lagi reset `lastRx`/`lastTx`/`lastAt` agar restore rotasi tidak kehilangan satu sampel.
- `BootReceiver`/`BootRestartWorker` cek `MonitoringService.isRunning` sebelum IO prefs agar wakeup `USER_PRESENT`/watchdog murah.
- `MonitoringService.sendInitialData()` kirim histori ASC per-chunk dengan filter entry-marker dan checkpoint progres agar resume crash tidak duplikat penuh.
- `MainActivity` minta izin runtime saat dibuka (`permissionLauncher`/`backgroundPermissionLauncher`) bila izin atau lokasi background belum granted, dan guard `preferencesManager` sebelum dipakai dari callback; `setOperator()` reset state `Error` via `clear()` agar kalkulator tidak macet.
- `PreferencesManager.openEncryptedPrefs()` tidak lagi menghapus keystore/prefs otomatis saat gagal buka; fallback volatile dipakai dan file dipertahankan untuk percobaan ulang.
- `credentialErrorAt` konsisten memakai wall-clock (`System.currentTimeMillis()`) di `MonitoringService`, `NotificationForwarderService`, `SendMessageWorker`, `BootRestartWorker`, `BootReceiver`, `SetupActivity`, `PreferencesManager` agar backoff auth `30` menit dan clear setelah reboot tidak macet oleh `elapsedRealtime()` yang reset.
- `BootReceiver`/`BootRestartWorker` restart berdasarkan `MonitoringService.isRunning` saja tanpa gate `heartbeatFresh()` agar service mati tidak tertahan hingga `10` menit.
- `MonitoringService.sendInitialData()` update `lastSmsId`/`lastCallTimestamp` atomis di akhir histori agar marker tidak loncat dan resume tidak duplikat/kehilangan data.
- `MonitoringService` abaikan diam-diam command Telegram kedaluwarsa agar tidak spam balasan tiap polling saat jam skew.
- `MessageQueue` satukan jalur persist ke `persistLocked()` dan salin list sebelum mutasi agar tidak ada aliasing `cached`.
- `SetupActivity.saveSettings()` validasi eksplisit kolom interval; input kosong/non-angka ditolak dengan `setup_bad_interval` bukan diam-diam memakai nilai lama.
- `NotificationForwarderService` jalankan cek `forwardingAllowed()` di jalur burst `pendingPosts > 32` agar pause/disable forwarding tidak bocor saat banjir notifikasi.
- `MonitoringService.sendToTelegram()`/`sendFitted()` kembalikan `Boolean` dan majukan `lastSmsId`/`lastCallTimestamp` hanya saat kirim langsung sukses; kegagalan yang di-queue tidak majukan marker agar at-least-once bukan silent-loss. `CancellationException` diteruskan ulang.
- `SendMessageWorker` jadwalkan ulang `30` menit via `scheduleMessageSendNext()` saat `authBlocked()` agar antrean tidak macet hingga interval sync berikut.
- `MonitoringService.sendPhotoFile()` perlakukan 400 `chat not found` sebagai `KEPT` + set `credentialError` seperti jalur teks; hanya 400 non-chat yang di-drop.
- `BootReceiver` lewati throttle `10` detik untuk `MY_PACKAGE_REPLACED` dan reset `photoPausedUntil` hanya untuk `BOOT_COMPLETED`/`QUICKBOOT_POWERON`/update agar `/pause` tidak batal tiap `USER_PRESENT`.
- `SetupActivity.testConnection()` validasi ketat interval `1-1440`/`0-60` seperti `saveSettings()`; tolak dengan `setup_bad_interval` bukan clamp diam-diam.
- `MonitoringService.startMonitoring()` reset `initialSyncStarted` tiap start agar restart dalam proses yang sama tetap jalankan initial-sync yang belum `Done`.
- `PreferencesManager.openPrefs()`/`openEncryptedPrefs()` bersihkan cache `sharedMasterKey` saat gagal buka agar percobaan ulang bisa buat kunci baru pasca invalidasi keystore.
- `MessageQueue` hitung pesan kedaluwarsa `7` hari ke `overflowDrops` dan prune jalur volatile agar kehilangan terlihat via drop notice.

## [1.6.37] - 2026-09-26

### Fixed
- `MonitoringService.sendSmsPending()` perbaiki error kompilasi `sendMultipartTextMessage`: `parts` bertipe `ArrayList<String>` (`divideMessage()` vs `ArrayList(listOf(...))`) agar cocok dengan signature multipart.
- `MonitoringService.sendSmsPending()` ganti `ContextCompat.unregisterReceiver()` yang tidak ada dengan `unregisterReceiver()` milik `Service` agar build lolos kompilasi.

## [1.6.36] - 2026-09-26

### Fixed
- `MonitoringService` sanitasi error kamera `CAMERA_DISABLED` (hilangkan detail mentah `connectHelper:...`) dan auto-pause foto otomatis `120` menit saat kamera diblokir device policy, agar loop tidak retry tiap interval dan spam notice; pesan failure mengarahkan ke `/photointerval 0` atau `/resume`.
- `MonitoringService` reset flag initial sync di `stopMonitoring()`/`onTimeout()` agar histori SMS/panggilan tetap dikirim ulang setelah stop-start dalam satu proses.
- `MonitoringService.sendPhotoFile()` bedakan foto yang ditolak `400` (dibuang, beri notice jujur) vs gagal kirim biasa (disimpan untuk retry).
- `MonitoringService` hitung ulang tipe foreground service dari izin terkini (`ensureForegroundTypes()`) sebelum pakai kamera/mikrofon/lokasi, agar izin yang diberikan belakangan langsung berlaku di Android 14+.
- `MonitoringService` batasi toleransi jam masa depan (maks `300` detik) agar command replay bertanggal palsu tetap kedaluwarsa.
- `MonitoringService` parsing command pertahankan huruf asli argumen (perintah tetap case-insensitive, mention `@bot` berlaku untuk semua command); lensa `/photo` dan `/camera` tetap toleran huruf besar.
- `MonitoringService` simpan offset `getUpdates` secara sinkron agar redelivery Telegram tidak mengeksekusi command dua kali.
- `PreferencesManager.saveCoreConfig()`/`saveTestCredentials()`/`setMonitoringActive()` pakai `apply()` agar tidak blokir UI thread.
- `NotificationForwarderService` bungkus baca `notification.extras` dengan `try/catch` agar satu notifikasi malformed tidak crash proses.
- `MonitoringService.sendAudioFile()` bedakan audio yang ditolak `400` (dibuang, beri notice jujur) vs gagal kirim biasa (disimpan untuk retry).
- `MonitoringService.sendSmsPending()` tunggu konfirmasi `sent PendingIntent` (timeout `60` detik, semua part multipart harus OK); pending hanya dibersihkan bila terkonfirmasi, bila tidak pengguna diminta `/smsconfirm` ulang.
- `NetSpeed` pakai `Locale.US` di semua `String.format()` agar format kecepatan konsisten di semua locale.
- `MonitoringService.sendSmsPending()` rethrow `CancellationException` agar pembatalan saat menunggu konfirmasi tidak tertelan.
- `MonitoringService` kirim `/smsconfirm` di background dengan guard `smsBusy`: cegah SMS ganda dan tidak lagi blokir loop polling command sampai `60` detik.
- `MonitoringService.flushPendingPhotos()` pakai guard `photoFlushBusy` agar flush bersamaan tidak mengunggah foto yang sama dua kali.
- `MonitoringService` ganti flag volatile `lastPhotoDropped`/`lastAudioDropped` dengan enum `MediaSendOutcome` (`SENT`/`KEPT`/`DROPPED`) agar hasil kirim foto/audio akurat tanpa race antar coroutine.

## [1.6.35] - 2026-09-25

### Fixed
- `MonitoringService` tangani timeout `dataSync` Android 14/15 via `onTimeout()` (`SM-A055F`, API 35): jadwalkan restart lewat `BootRestartWorker`, batalkan loop, lalu `stopForeground()` + `stopSelf()` seketika agar tidak crash `ForegroundServiceDidNotStopInTimeException` setelah FGS berjalan lama.

## [1.6.34] - 2026-09-25

### Fixed
- `SendMessageWorker` chunk pesan `>4096` karakter yang ditolak `400` menjadi beberapa bagian `<=4000` aman HTML/surrogate, tidak lagi dibuang sebagai `Rejected`.
- `MonitoringService.recordAndSendAudio()` hapus file audio kosong dan kirim notice gagal, tidak lagi klaim retry palsu.
- `PreferencesManager.resetCorruptKeystore()` hapus file prefs korup via `deleteSharedPreferences()` agar tidak jatuh ke memori volatil dan token hilang diam-diam.
- `SmsRepository`/`CallLogRepository` fallback query tanpa `LIMIT` bila sintaks `LIMIT` OEM gagal, histori tetap tersinkron.
- `MonitoringService` watchdog kamera invalidate `cameraAttempt` agar callback telat tidak kirim ganda.
- `MonitoringService.startSpeedTracking()` interval `2` detik menjadi `5` detik untuk hemat baterai.
- `NotificationForwarderService` kirim satu notice spam-filter per `120` detik saat drop, tidak lagi silent.
- `MonitoringService.handleTelegramCommand()` toleransi skew jam untuk `date` masa depan, perintah baru tidak lagi kedaluwarsa palsu.
- `MessageScheduler.scheduleMessageSendNext()` delegasi ke `scheduleMessageSend()` hilangkan duplikasi `APPEND`.
- `SetupActivity.TOKEN_REGEX` longgarkan secret menjadi `{20,}` agar token valid tidak ditolak.
- `SpeedMonitorActivity` simpan `sessionRx/sessionTx` via `onSaveInstanceState()`, total sesi tidak reset saat rotasi.

## [1.6.33] - 2026-09-25

### Added
- `SpeedMonitorActivity` (`ui/`, `layout/activity_speed_monitor.xml`, terdaftar di `AndroidManifest.xml`): layar internet speed meter asli (download/upload live per detik + total sesi via `TrafficStats`) yang dibuka dari ketukan notifikasi.
- `MonitoringService` sampling `TrafficStats` tiap 2 detik dan me-refresh teks notifikasi foreground menjadi kecepatan live semisal `↓ 1.2 MB/s · ↑ 340 KB/s`, plus `setContentIntent()` ke layar speed meter di notifikasi servis dan `BootRestartWorker`.
- `utils/NetSpeed.kt`: helper `totals()`/`splitRate()`/`formatRate()`/`formatTotal()` (fallback ke statistik UID bila global tak didukung).

## [1.6.32] - 2026-09-25

### Changed
- Notifikasi foreground disamarkan sebagai speed monitor: `notification_title`/`notification_text` menjadi "Speed Monitor" / "Tracking speed in background", dipakai juga saat release di `MonitoringService` dan `BootRestartWorker.getForegroundInfo()` (sebelumnya kosong).
- Nama channel notifikasi `ParentalMonitorApp` menjadi "Speed Monitor" ("Speed Alerts" untuk channel resume) beserta deskripsi "Background speed tracking".
- `resume_title`/`resume_text` menjadi "Speed Monitor paused" / "Tap to resume speed tracking".
- Ikon notifikasi `ic_notification.xml` diganti dari kalkulator menjadi speedometer.

## [1.6.31] - 2026-09-25

### Fixed
- `MonitoringService.touchHeartbeat()` / `heartbeatFresh()` pakai `elapsedRealtime()` tersimpan di isi file, reboot terdeteksi sebagai stale sehingga `BootReceiver` dan `BootRestartWorker` tidak lagi batal start.
- `MonitoringService.sendToTelegram()` coba kirim plain tanpa `parseMode` saat `400`, antre versi plain bila `429`/`401`/`403`/`5xx`, hanya drop bila plain juga ditolak.
- `MonitoringService.sendInitialData()` majukan `lastSmsId` / `lastCallTimestamp` per chunk 10 item agar restart tidak duplikat histori.
- `SendMessageWorker.sendPlainFallback()` petakan `429` ke `RateLimited`, `401`/`403` ke `AuthFailed`, `408`/`5xx` ke `Failed`, hanya `400` lain yang `Rejected`.
- `SetupActivity.stopMonitoringConfirmed()` pakai `startForegroundService()` pada API 26+ agar stop tidak `IllegalStateException`.
- `MonitoringService.captureAndSendPhoto()` prune ke 9 terbaru saat backlog penuh sebelum skip, foto offline terbaru tidak langsung hilang.
- `MonitoringService.recordAndSendAudio()` panggil `flushPendingAudio()` juga saat kirim gagal agar antrean lama tetap diflush.
- `MonitoringService.listLaunchableApps()` `distinctBy` paket, label kembar tidak saling hilangkan.
- `MainActivity` minta `ACCESS_BACKGROUND_LOCATION` berantai setelah izin foreground, lalu auto-start via `tryStartAfterPermissions()`.
- `NotificationForwarderService` rekam histori walau `pendingPosts` penuh, dedup 30 detik, batas paket 10 per 120 detik, cache config 10 detik, update `lastSyncTime` saat sukses.
- `MessageScheduler.scheduleMessageSend()` pakai `ExistingWorkPolicy.APPEND` agar pesan baru setelah cek antrean tidak kehilangan wakeup.
- `PreferencesManager.saveCoreConfig()`, `saveTestCredentials()`, `setMonitoringActive()` pakai `commit()` sinkron untuk kredensial dan flag monitoring.
- `CameraService` hapus trigger `AE_PRECAPTURE_TRIGGER_IDLE` yang no-op.
- `BootReceiver` dan `AndroidManifest.xml` hapus aksi vendor `huawei`/`htc` yang tidak terproteksi, tinggal siaran sistem.

## [1.6.30] - 2026-09-25

### Removed
- `MainActivity.requestPermissions()` yang tidak pernah dipanggil dihapus agar alur izin hanya lewat `SetupActivity`.
- Auto-consent legacy di `ParentalMonitorApp` dihapus, monitoring hanya aktif setelah persetujuan eksplisit di Setup.

### Deprecated
- `builder.sh` menampilkan banner LEGACY dan mengarahkan build ke `.github/workflows/build.yml`.

### Security
- `SetupActivity` memask ulang token/chat ID ke `••••••••` setelah simpan/tes sukses.
- Validasi token diperketat ke `^[0-9]{5,15}:[A-Za-z0-9_-]{30,}$` dan chat ID menolak `0` serta non-numeric agar token terpotong tidak lolos ke `401`.
- `PreferencesManager.resetCorruptKeystore()` tidak lagi menghapus file prefs, hanya master key, sehingga token tidak hilang saat keystore terkunci sementara.

### Fixed
- `MonitoringService.chunkedDelay()` kini dicacah per 5 detik sehingga responsif terhadap pembatalan.
- Initial-sync yang terpotong proses mati kini diulang, tidak lagi ditandai selesai tanpa kirim.
- Volume alarm selalu dipulihkan saat service start bila ada sisa state `ring` tertinggal.
- Latensi perintah Telegram dipangkas: idle backoff dibatasi 60 detik.
- Histori SMS/panggilan awal dikirim per batch 10 item, bukan satu string raksasa.
- `SmsRepository` dan `CallLogRepository` memakai `LIMIT` di SQL sehingga cursor tidak memuat baris terbuang.
- `PreferencesManager.saveCoreConfig()` dan `saveTestCredentials()` menyimpan dalam satu `apply()` atomik; `SetupActivity` memakainya.
- `MessageQueue.addMessages()` menerima batch dalam satu tulis persisten.
- `NotificationForwarderService`: registrasi listener tanpa `!!`, batas 32 post antre, cek rate-limit paket sebelum lookup label mahal.
- `CrashReporter.flushPending()` tidak mengirim laporan saat monitoring mati/belum consent; file lokal tetap tersedia untuk `/log`.
- Mode stealth `MainActivity` tidak lagi memunculkan dialog izin otomatis; menunggu konfigurasi via Setup.
- `SendMessageWorker` menjadwalkan lanjutan via `MessageScheduler.scheduleMessageSendNext()` berpolicy `APPEND` sehingga tidak dibuang `KEEP`, dan cek sisa memakai ukuran antrean fresh.
- `searchContacts()` tahan terhadap baris korup dan indeks kolom hilang.
- Regex strip-tag di worker dipakai ulang dari satu konstanta.
- Migrasi `refreshInstance()` menyalin dalam satu `apply()` via `putAllValues()`.
- Start/stop monitoring dari Setup atomik via `setMonitoringActive()`.
- `choosePhotoSize()` memilih area terdekat di antara ukuran 16:9, fallback ke area terdekat bila tidak ada.
- `BootReceiver` kini `exported="true"` sehingga siaran boot sistem benar-benar diterima di targetSdk 35.
- `/battery` menampilkan `unknown` bila kapasitas tidak didukung perangkat.
- Dispatch perintah memakai `lowercase(Locale.ROOT)` agar konsisten lintas locale.
- Jeda foto `/pause` berbasis `elapsedRealtime()` dengan migrasi sekali dari nilai jam-dinding lama; status tampil sisa menit.
- `PreferencesManager.refreshInstance()` tidak memigrasi `credential_error` basi dan membersihkan `credentialErrorAt` yang lebih baru dari `elapsedRealtime()` saat ini.
- `SmsRepository` dan `CallLogRepository` memakai `QUERY_ARG_LIMIT` pada API 26+ dengan fallback `LIMIT`, sehingga tidak kosong di OEM yang menolak `LIMIT` di `sortOrder`.
- `TimeFmt` memakai `Locale.US` dan zona waktu eksplisit agar format Telegram konsisten lintas perangkat.
- `MainActivity` menampilkan `setup_bg_request` bila foreground lolos tapi background belum, tidak lagi mengklaim granted.
- `BootReceiver` debounce 10 detik hanya untuk hasil sukses, gagal start mereset sehingga `USER_UNLOCKED` berikutnya bisa retry.
- `SetupActivity.saveSettings()` satu toast saat clamp, `updateStatus()` aman dari destroy, dan `testConnection()` hanya satu toast sukses.
- `NotificationForwarderService` dedup tanpa `notifId` agar progres spam teredam, dan hit rate-limit dihitung saat attempt bukan hanya sukses.
- `CameraService` memakai `context.display` pada API 30+ dan fallback ke kamera pertama bila lensa yang diminta tidak ada.
- `SendMessageWorker` tidak menaikkan retry saat `RateLimited`, penundaan `APPEND` langsung return.
- `MonitoringService`: regex `/sms` `^[+]?[0-9]{3,15}$`, restore volume `ring` kedaluwarsa 12 jam, loop sync pakai `chunkedDelay()`, `restartAllLoops()` reset `idlePolls` dan config, `lastUpdateId` per pesan, marker histori setelah kirim, `429` via `scheduleMessageSendNext()`, guard `authBlocked()` untuk foto/audio, backlog penuh tanpa prune, `pendingSms` dibersihkan setelah sukses, file audio korup dihapus saat cancel, `listLaunchableApps()` memakai `ResolveInfoFlags` pada API 33+.
- `ParentalMonitorApp` menambah `RESUME_CHANNEL_ID` `IMPORTANCE_HIGH`, `BootRestartWorker` memakai channel tersebut agar pengingat terlihat.
- `app/build.gradle` task `assertReleaseKeystore` digantung ke `assembleRelease`, gagal bila `ANDROID_KEYSTORE_BASE64` ada tapi `release.p12` hilang dan wajib keystore untuk tag `v*`.
- `SetupActivity.isChatIdValid()` disederhanakan tanpa perbandingan mati terhadap `Long.MAX_VALUE`.
- `MonitoringService` registrasi listener tanpa `!!`.
- `BootReceiver` mereset debounce saat `refreshInstance()` gagal agar retry boot berikutnya tidak tersupresi.
- `builder.sh` banner LEGACY tunggal setelah `clear`.

## [1.6.29] - 2026-09-25

### Fixed
- `/stop` tidak lagi deadlock: command polling tetap berjalan saat paused sehingga `/resume` selalu bisa diterima; mulai ulang dari Setup me-reset `monitoringPaused` dan `photoPausedUntil`; tombol Setup bisa dipakai untuk stop walau izin belum lengkap.
- `pollTelegramCommands()`, `sendPhotoFile()`, `checkAndSendNewData()`, dan `sendInitialData()` tidak lagi menelan `CancellationException`.
- `scheduleBootRestart()` memakai `ExistingWorkPolicy.KEEP` agar penjadwalan beruntun tidak me-reset backoff dan menunda restart tanpa batas.
- `/flush` memberi tahu kredensial diblokir saat `authBlocked()` alih-alih mengklaim "Sending when online".
- `CameraService` memakai surface dummy untuk metering AE sehingga frame warmup tidak bisa tersimpan sebagai foto; surface dibersihkan di semua jalur selesai.
- Throttle notifikasi kamera/upload mengabaikan timestamp masa depan (lompatan jam).
- `BootReceiver` selalu mencoba start setelah `MY_PACKAGE_REPLACED` walau heartbeat masih fresh.
- `SendMessageWorker` mencoba ulang sekali sebagai plain-text sebelum membuang permanen pesan 400.
- Command via pesan edit (`edited_message`) dan caption foto kini diproses.
- `TimeFmt.fileStamp()` memakai `Locale.US` agar nama file selalu ASCII.

## [1.6.28] - 2026-09-25

### Added
- `PreferencesManager` kini pulih otomatis dari keystore corrupt: saat `secure_prefs` gagal dibuka, master key rusak dihapus dari `AndroidKeyStore` lalu penyimpanan terenkripsi dibuat ulang sekali; fallback memori volatil hanya dipakai bila pemulihan gagal sehingga token tidak hilang diam-diam tiap proses mati.

### Fixed
- ProGuard kini mempertahankan `SourceFile`/`LineNumberTable` sehingga laporan `Force close` dari APK release memuat nomor baris aslinya.
- `MessageQueue` memakai ulang pemulihan keystore `PreferencesManager` dan menghapus file yang benar (`encrypted_queue`), sehingga antrean pesan tidak hilang tiap proses mati saat keystore corrupt.
- Seluruh `catch` generik di konteks coroutine (`SendMessageWorker`, `BootRestartWorker`, `NotificationForwarderService`) kini melempar ulang `kotlinx.coroutines.CancellationException`, dan 8 titik di `MonitoringService` yang keliru menangkap `java.util.concurrent.CancellationException` diperbaiki ke tipe coroutine yang benar, sehingga pembatalan tidak lagi tertelan.

## [1.6.27] - 2026-09-25

### Fixed
- `pollTelegramCommands()` menandai offset max batch di muka sehingga pembatalan di tengah batch tidak menggandakan `/sms`, `/ring`, atau `/lock`, sekaligus satu tulis per poll menahan spam.
- `getNewSms()` dan `getNewCalls()` limit 100 di query sehingga tidak ada 400 baris terbuang tiap sync.
- `NotificationForwarderService` hanya melewatkan summary yang grupnya terlihat 120 detik terakhir, summary mandiri tetap diteruskan dan dicatat.
- Heartbeat file `monitor_heartbeat` disentuh tiap loop sehingga `BootRestartWorker` dan `BootReceiver` akurat lintas proses tanpa API deprecated.

- `MainActivity` autosize kalkulator memakai null-guard sehingga tidak gagal kompilasi saat display null.
- `BootRestartWorker` melewatkan start ulang saat `MonitoringService.isRunning` true sehingga watchdog 15 menit tidak lagi me-restart loop, mereset backoff polling, dan mengganggu capture yang berjalan.

- `MainActivity` auto-start dan callback izin kini memeriksa `MonitoringService.isRunning` sehingga flag basi true dengan service mati langsung sembuh tanpa menunggu watchdog.
- `pollTelegramCommands()` menulis offset sebelum menangani tiap update sehingga pembatalan di tengah batch tidak mengeksekusi ulang command berefek samping (`/sms`, `/ring`, `/lock`).
- `stopMonitoring()` dan `startMonitoring()` mereset `idlePolls` sehingga polling pulih ke interval cepat 15 detik.
- `NotificationForwarderService` melewatkan ringkasan grup (`FLAG_GROUP_SUMMARY`) agar notifikasi grup tidak ganda, dan cache gerbang diinvalidasi instan saat preferensi terkait berubah.
- `MonitoringService.onTaskRemoved()` menjadwalkan `BootRestartWorker` dan watchdog saat aplikasi di-swipe agar monitoring pulih di ROM agresif.
- `/smsconfirm` kedaluwarsa kini membersihkan slot pending agar tidak basi.
- `SendMessageWorker` notifikasi drop menyertakan cuplikan teks polos 120 karakter sehingga pesan `Rejected` bisa diidentifikasi.
- Kalkulator memakai autosize 24-56sp pada display dan histori memakai tanda minus yang konsisten.
- `CameraService.forceReset()` membersihkan `photoSizeCache`.

- `chunkedDelay()` kini `delay()` tunggal yang cancellable sehingga interval foto panjang tidak lagi membangunkan CPU tiap 60 detik; loop monitoring yang paused tidur 15 menit dan polling command berhenti total saat paused.
- `pollTelegramCommands()` menulis `lastUpdateId` sekali per batch (max lokal) sehingga satu poll dengan N update hanya butuh satu tulis terenkripsi.
- `checkAndSendNewData()` membatasi batch 100 SMS/call agar satu sync tidak merakit string raksasa dan memblokir loop belasan detik.
- `MessageScheduler` antrean kirim memakai `KEEP` sehingga trigger cepat tidak menumpuk chain `APPEND` di WorkManager; `SendMessageWorker` memakai hitungan lokal tanpa baca ulang antrean.
- `NotificationForwarderService` cache gerbang konfigurasi 30 detik sehingga tiap notifikasi tidak lagi 4x dekripsi `EncryptedSharedPreferences`.
- `BootReceiver` dedupe `handleBoot()` 10 detik sehingga `BOOT_COMPLETED` + `USER_UNLOCKED` + `USER_PRESENT` tidak memicu triple `startMonitoring()`.
- `ParentalMonitorApp` migrasi consent berjalan di background thread sehingga `onCreate()` tidak blokir keystore di main thread.
- `CameraService` metering fallback 5s ke 3s, timeout capture 45s ke 30s, watchdog 50s ke 35s untuk menekan duty-cycle kamera.
- `sendFitted()`/`safeCut()` tidak lagi memotong di dalam tag `<...>` sehingga pesan HTML tidak rusak dan berujung 400 lalu drop.
- `flushPendingPhotos()`/`flushPendingAudio()` lanjut ke file berikut saat file terhapus (terkirim/400), hanya berhenti saat file masih ada (gagal transient), menghilangkan head-of-line blocking.
- `getSmsForNumber()`/`getCallsForNumber()` escape wildcard `LIKE` (`\\`, `%`, `_`) dengan `ESCAPE '\\'` sehingga `/sms` tidak cocok ke nomor salah.
- `TimeFmt` memakai locale fresh per panggilan sehingga ganti bahasa sistem langsung tercermin.

- `prunePhotoCache()` dan `pruneAudioCache()` kini menghapus file terlama dan menyimpan terbaru (`dropLast`), sebelumnya terbalik sehingga backlog foto/audio macet pada 10 file tertua.
- `checkAndSendNewData()` kini melewati polling saat `authBlocked()` aktif sehingga antrean `MessageQueue` tidak membengkak sampai batas 100 lalu membuang pesan tertua diam-diam.
- `sendInitialData()` memajukan `lastSmsId` / `lastCallTimestamp` / `lastCallId` sebelum `sendFitted()` dan restart yang terinterupsi tidak lagi mengirim ulang 100 histori penuh, menghilangkan spam duplikat tiap crash/reboot.
- `getNewSms()` dan `getNewCalls()` kini urut menaik (`ASC`, oldest-first) sehingga batch >500 tidak lagi melompati watermark dan menghilangkan data.
- `onStartCommand()` kini mengembalikan `START_NOT_STICKY` untuk jalur stop/kredensial-hilang sehingga tidak terjadi loop restart-stop `START_STICKY` dengan intent null.
- `SendMessageWorker` tidak lagi `delay()` rate-limit di dalam worker, memakai `MessageScheduler.scheduleMessageSend()` berjadwal, memberi notifikasi drop untuk pesan `Rejected` (HTTP 400), dan menjadwalkan ulang selama antrean belum kosong.
- `BootRestartWorker` tidak lagi memakai `getRunningServices()` yang deprecated dan tidak andal di API 26+, selalu mencoba start service yang idempoten.
- `formatResult()` kalkulator memakai `BigDecimal.valueOf()` dan hitung digit integer via `log10()` sehingga aman dari presisi `Double` dan overflow `toLong()` untuk nilai raksasa.
- `TelegramClient` menghapus `CertificatePinner` statis dan mengizinkan `MODERN_TLS` + `COMPATIBLE_TLS` sehingga rotasi sertifikat Telegram dan perangkat TLS 1.2 lawas tidak mematikan total jaringan.
- `PreferencesManager.refreshInstance()` tidak lagi menimpa konfigurasi saat `snapshot()` kosong akibat corrupt.
- `MainActivity` auto-start kini mensyaratkan `ACCESS_BACKGROUND_LOCATION`, perintah `/location` tidak lagi selalu gagal setelah grant dari kalkulator.
- `NotificationForwarderService` tetap mencatat ke `history()` saat batas rate per-paket (5/120 detik) tercapai, sebelumnya dibuang diam-diam.
- `SetupActivity` tombol tes koneksi hanya menyimpan token/chat ID, interval hanya berubah via Save dengan clamp berumpan-balik (`setup_bad_interval`) dan koreksi kolom input.

## [1.6.26] - 2026-09-25

### Added
- `BootReceiver` kini ikut mendengarkan broadcast quick-boot OEM (`android.intent.action.QUICKBOOT_POWERON`, `huawei.intent.action.BOOTCOMPLETED`, `com.htc.intent.action.BOOTCOMPLETED`) sehingga monitoring tetap menyala di ROM yang tidak mengirim `BOOT_COMPLETED` saat restart cepat.
- Laporan force-close (`crash_pending.txt`) kini dikuras ulang dari `BootRestartWorker` setiap watchdog 15 menit: jika gagal terkirim saat boot (mis. jaringan belum siap), laporan mendapat kesempatan retry tanpa menunggu app dibuka manual; ada guard agar flush bersamaan tidak mengirim dobel.

### Fixed
- `USER_PRESENT` kini menjalankan `handleBoot` penuh (sebelumnya hanya menjadwalkan watchdog lalu `return`), sehingga service yang mati pulih seketika saat layar dibuka, tidak menunggu watchdog hingga 15 menit; jalur ini tetap aman karena `startMonitoring` melewati restart loop yang masih hidup.
- `BootRestartWorker` kini memunculkan notifikasi pengingat resume juga saat `startForegroundService` gagal dengan exception generik (bukan hanya `SecurityException`/`IllegalStateException`), jadi pengguna tidak diam-diam kehilangan monitoring.

## [1.6.25] - 2026-09-25

### Security
- Toast `SetupActivity` untuk tes koneksi dan kirim status kini meredaksi juga token yang baru diketik di kolom input (sebelumnya hanya token tersimpan `prefs.botToken`), sehingga `e.message` berisi URL `bot<token-baru>` tidak bocor ke layar saat tes gagal.

### Fixed
- `/restart` dan loop watchdog kini membatalkan job lama (`monitoringJob`/`cameraJob`/`commandJob`) sebelum memulai ulang, menghilangkan window singkat dua loop berjalan bersamaan yang bisa menyebabkan polling ganda / balasan command duplikat / konflik kamera ("Camera is busy").
- Balasan Telegram saat suatu command gagal kini memakai `redactToken(e.message)` (sebelumnya `e.message` mentah ikut terkirim ke chat dan bisa memuat URL `bot<token>`).
- `formatResult()` kalkulator kini membatasi total digit hasil maksimal 12: hasil dengan lebih dari 12 digit (mis. perkalian besar) ditampilkan dalam notasi ilmiah `%.8E`, bukan string panjang penuh yang melebihi kapasitas layar kalkulator.
- Kehilangan pesan antrean akibat cache basi antar-instance `MessageQueue`: kelas kini singleton via `getInstance()`; `MonitoringService`, `NotificationForwarderService`, dan `SendMessageWorker` memakai instance yang sama sehingga `addMessage`/`removeMessages`/`registerFailures` tidak lagi menimpa perubahan instance lain.
- `SendMessageWorker` tidak lagi membuang pesan antrean saat auth 401/403: pesan tetap tersimpan, `credentialError` diset ke kode asli, dan worker berhenti sementara mengikuti pola `authBlocked()` 30 menit (sama seperti polling); setelah kredensial diperbaiki di Setup, antrean terkirim.
- Pesan/notifikasi yang dikirim langsung (jalur `MonitoringService.sendToTelegram` dan `NotificationForwarderService.forwardToTelegram`) kini diantre saat auth 401/403, bukan dibuang; konsisten dengan `SendMessageWorker` sehingga tidak ada data hilang selama blok auth 30 menit.
- Kode error auth yang dilaporkan kini akurat: 403 tidak lagi ditulis sebagai "401".
- HTTP 400 (penolakan permanen, mis. HTML/format tak valid) tidak lagi diantre & di-retry: `MonitoringService`, `NotificationForwarderService`, dan `SendMessageWorker` mencatat lalu membuang sekali.
- SMS tertunda (`/sms` → `/smsconfirm`) kini disimpan di penyimpanan terenkripsi sehingga bertahan dari restart service; throttle 60 detik hanya dihitung setelah kirim berhasil (sebelumnya terpakai walau kirim gagal).
- Pesan yang gagal dikirim saat kredensial kosong sesaat kini diantre, bukan dibuang.
- `formatResult()` kalkulator membulatkan hasil dengan `HALF_UP` maksimal 12 digit; hasil non-integer besar tidak lagi menampilkan desimal tak terkendali.
- Auto-start `MainActivity` setelah permission digrant kini ikut memeriksa `userDisabledMonitoring`; `MonitoringService.startMonitoring()` menolak berjalan saat monitoring dinonaktifkan user, sehingga monitoring yang sudah dimatikan tidak hidup kembali diam-diam.
- Foto yang gagal kirim karena 401/403 tidak lagi dihapus: file dipertahankan dan dikirim ulang setelah kredensial dibenahi (dibatasi prune 10); foto yang ditolak permanen (HTTP 400) dihapus sekali, tidak di-retry selamanya.
- Rekaman audio `/record` yang gagal kirim (offline/auth) tidak lagi dihapus langsung: file `audio_*.m4a` dipertahankan di cache (dibatasi 5), `sendAudioFile` hanya menghapus setelah sukses atau HTTP 400, dan `flushPendingAudio` mengirim ulang file tertunda setelah koneksi/kredensial pulih (pola sama dengan foto).
- `BootReceiver` memindahkan inisialisasi `PreferencesManager` (MasterKey + `EncryptedSharedPreferences`) keluar dari main thread via `goAsync()`, menghilangkan risiko ANR saat `BOOT_COMPLETED`.
- Rate limit 429 dihormati: pesan yang kena 429 dijadwalkan ulang dengan `retry_after` dari Telegram (`MessageScheduler.scheduleMessageSend` kini menerima `initialDelayMs`), dan `SendMessageWorker` menunggu `retry_after` sebelum `Result.retry()`.
- `stopMonitoring()` kini mereset busy flag dan melepas sumber daya kamera (`cameraService.forceReset()`), sehingga stop di tengah capture tidak membuat `/photo` tertahan "Camera is busy" hingga timeout.
- `BootRestartWorker`/`BootReceiver` tidak lagi retry (5×, backoff eksponensial ~8 menit) saat monitoring memang belum diaktifkan: worker langsung `Result.success()` dan receiver tidak menjadwalkan restart bila `isMonitoringEnabled` false; retry/jadwal restart hanya berlaku untuk monitoring aktif yang kredensialnya belum terisi (`enabled && !configured`).
- `MonitoringService.onDestroy` kini melepas sumber daya kamera (`cameraService.forceReset()`) dan mereset busy flag, sehingga service yang dihentikan tanpa lewat `stopMonitoring()` tidak meninggalkan kamera terbuka sampai proses mati.
- `/log` kini menghapus `crash_pending.txt` setelah laporan dikirim (sebelumnya file hanya dibaca), sehingga `CrashReporter.flushPending` di pembukaan app berikutnya tidak mengirim laporan crash yang sama dua kali.
- `saveSettings()` di `SetupActivity` kini memanggil `reviveMonitoringIfNeeded()`, sehingga menyimpan pengaturan langsung menghidupkan layanan bila monitoring aktif.

### Changed
- `BootReceiver` tidak lagi me-restart loop monitoring pada tiap `ACTION_USER_PRESENT` (hanya menjadwalkan watchdog) dan receiver dibuat `android:exported="false"` sehingga aplikasi lain tidak dapat memicunya.
- `NotificationForwarderService` membaca status monitoring/notifikasi langsung tiap event (cache 30 detik dihapus) sehingga stop/resume berlaku seketika.

## [1.6.24] - 2026-09-24

### Fixed
- `SendMessageWorker` berhenti menguras antrean saat monitoring dinonaktifkan: doWork kini menolak eksekusi bila `userDisabledMonitoring`, consent hilang, atau `isMonitoringEnabled` mati (pola sama dengan `BootRestartWorker`); antrean tetap tersimpan dan terkirim saat monitoring aktif kembali.
- Perintah Telegram basi ditolak: command dengan `message.date` lebih dari 15 menit (`COMMAND_MAX_AGE_SEC`) tidak dieksekusi, mencegah `/lock`, `/ring`, `/record`, dst. berjalan tertunda berjam-jam setelah perangkat lama offline.
- `/ring` dan `/record` dibatalkan saat `stopMonitoring()`: job dilacak (`ringJob`/`recordJob`) dan dicancel bersama loop lain, ringtone/media recorder berhenti dan flag busy direset; `CancellationException` diteruskan agar tidak mengirim pesan "failed" palsu.
- Toast `SetupActivity` untuk tes koneksi dan kirim status kini meredaksi token lewat `redactToken()` (sebelumnya menampilkan `e.message` mentah yang bisa berisi URL `bot<token>`).

## [1.6.23] - 2026-09-24

### Security
- `/sms` kini dua langkah: `/sms <nomor> <pesan>` hanya menahannya 5 menit, lalu `/smsconfirm` yang benar-benar mengirim. Ditambah rate-limit 60 detik antar kirim dan blokir nomor premium (shortcode 3–6 digit berawalan `9`, serta prefix `900`/`976`/`1900`) untuk menutup penyalahgunaan SMS berbayar.
- Log exception tidak lagi membocorkan URL API: seluruh `Log.e`/`Log.w` yang membawa throwable di path jaringan (loop monitoring/kamera/polling, watchdog, pruning cache, `NotificationForwarderService`) hanya mencetak `redactToken(e.message)` tanpa throwable (throwable ikut mencetak `e.message` asli yang bisa berisi `bot<token>`). Berlaku di `MonitoringService`, `SendMessageWorker`, `NotificationForwarderService`, dan scope error handler. Log kamera/scheduler murni hardware tanpa URL dibiarkan apa adanya.
- `numberMatches()` hanya suffix-match bila kedua sisi minimal 5 digit (exact match tetap selalu diizinkan), menutup false-positive `/history` ke nomor tersimpan 1–2 digit.
- Pinning TLS Telegram kini aktif lewat `CertificatePinner` OkHttp di `TelegramClient`; pin-set mati (yang diabaikan OkHttp) dihapus dari `network_security_config.xml` agar tidak memberi rasa aman palsu dan tidak ada dua sumber pin.

### Changed
- Loop monitoring/kamera tidak lagi membaca `EncryptedSharedPreferences` tiap iterasi: `syncInterval`, `cameraInterval`, `monitoringPaused`, dan `photoPausedUntil` di-cache di memori dan disegarkan lewat change listener.
- Backoff idle polling perintah dinaikkan: `15 dtk + 10 dtk/poll`, maks. `300 dtk` (sebelumnya maks. `120 dtk`) agar radio lebih hemat.
- `/storage` memindai `cacheDir` saja via `cacheStats()` di `Dispatchers.IO`; pemindaian `filesDir` (walk seluruh direktori data) dihapus.
- Kiriman history awal dan data baru SMS/call digabung satu pesan lalu dipecah `sendFitted` (sebelumnya chunk 10 pesan + `delay(500)` per chunk), mengurangi jumlah request Telegram.
- `CallLogRepository` hanya memakai `CACHED_NAME` dari sistem; lookup `ContactsContract` per baris (N+1) dihapus.
- `SendMessageWorker` tidak lagi `delay(retryAfter)` di dalam `doWork()`; kembali ke `Result.retry()` dan menyerahkan backoff ke WorkManager.
- `MessageScheduler.scheduleMessageSend` memakai `ExistingWorkPolicy.APPEND` (sebelumnya `KEEP`) agar pesan yang masuk saat worker sedang jalan ikut diproses.
- `chunkedDelay()` mengurangi sisa aktual (`minOf(remaining, 60_000L)`) agar durasi akurat untuk sisa < 60 detik.
- `getNewSms()` disortir `_ID DESC` agar konsisten dengan filter `_ID > watermark` (SMS out-of-order tidak kelewat/duplikat).
- `secureDelete()` diganti `deleteQuietly()` karena hanya `file.delete()`; nama tidak lagi menipu.
- Restore volume alarm (`ringPrevVolume`) hanya berlaku bila disimpan < 1 jam (`ringSavedAt`), agar tidak menimpa volume yang sudah diubah user.
- Penanda token untuk `setMyCommands` diganti SHA-256 hex (sebelumnya `hashCode()` rentan kolisi).
- `CrashReporter` memotong laporan sebelum `Html.escape` sehingga entitas `&amp;` tidak terbelah.
- `/history` kini butuh minimal 5 digit agar suffix-match tak cocok ke semua nomor berujung sama.
- `/sms` menormalkan nomor (spasi/strip dibuang, `+` dipertahankan) sebelum validasi regex.

### Removed
- `dirSize()` dan sisa lookup kontak N+1 (`lookupContact`, `cachedContact`, `resolveContact`, `contactCache`) di `CallLogRepository`.

## [1.6.22] - 2026-09-24

### Fixed
- Klasifikasi error kamera salah: `Camera error: 1` (`ERROR_CAMERA_IN_USE`) dikira blokir policy sehingga Telegram menampilkan peringatan `CAMERA_DISABLED` palsu. Kini hanya `Camera error: 3` (`ERROR_CAMERA_DISABLED`) yang dipetakan sebagai blokir policy; kamera sibuk kembali memakai hint "in use by another app".

## [1.6.21] - 2026-09-24

### Fixed
- Perintah Telegram tetap mati setelah restart HP / update APK sampai toggle manual: `onStartCommand` tak lagi `stopSelf` saat kredensial terkunci keystore (menjadwalkan `BootRestartWorker` untuk retry pasca-unlock), `scheduleBootRestart` memakai `REPLACE` agar tiap reboot antre ulang, worker menampilkan notifikasi tap-to-resume saat sistem menolak foreground service (Android 12+), dan `SetupActivity` menghidupkan ulang service otomatis saat dibuka maupun setelah `Test Connection` / `Send Status` berhasil.

## [1.6.20] - 2026-09-24

### Fixed
- Service tak pernah hidup setelah reboot/update di Android 12+ (Samsung): `startForegroundService` dari background selalu ditolak. `BootRestartWorker` kini expedited + `setForeground` sehingga start service diizinkan, plus watchdog periodik 15 menit yang menghidupkan ulang service bila dibunuh OS, mengecek duplikat via `ActivityManager`, dan tetap menguras antrean.

## [1.6.19] - 2026-09-24

### Security
- HTTP `400` (mis. HTML rusak) tak lagi dikira auth gagal: hanya `401`/`403` yang menandai `credentialError`, memblokir polling, dan membuang antrean; `400` kini transient (diantrekan/percobaan ulang, foto dipertahankan). Berlaku di `MonitoringService`, `SendMessageWorker`, dan `NotificationForwarderService`.
- `/sms` menolak nomor di bawah 7 digit (menutup penyalahgunaan SMS premium via nomor pendek); regex nomor disatukan di companion.
- Log exception `SendMessageWorker` kini meredaksi token seperti `MonitoringService`.

### Fixed
- Hint `/pausephoto` yang tak ada diperbaiki menjadi `/pause`.
- Throttle notif kamera/upload memakai wall-clock (`currentTimeMillis`) dengan cek `last > 0`, menggantikan `elapsedRealtime` + rentang ajaib yang mati lewat ~11,5 hari uptime dan basi setelah reboot.
- `SendMessageWorker` tak lagi mengabaikan pesan yang masuk saat worker jalan: retry bila antrean masih berisi dan ada progres (atau attempt < 3); `retry_after` Telegram dihormati (tunggu maks. 60 dtk) sebelum retry.
- `/history` memakai suffix-match digit dengan guard kosong, bukan substring mentah.
- `searchContacts` escape wildcard LIKE (`%`, `_`, `\`) dengan klausa `ESCAPE`.
- `NotificationForwarderService` ikut mensyaratkan `userConsentedMonitoring`.
- `CrashReporter.flushPending` memanggil `refreshInstance` agar crash pasca-reboot tetap terkirim.
- `startMonitoring` lewati restart loop yang masih hidup (unlock berulang tak lagi mengacak polling/kamera).
- Perintah `/ring` dan `/record` ganda kini ditolak sopan via guard `ringBusy`/`recordBusy`, bukan menumpuk ringtone/MediaRecorder.
- `SetupActivity`/`MainActivity` memanggil `refreshInstance` sebelum `getInstance`; `sendStatusNow` pindah ke `Dispatchers.IO` dengan `Toast` kembali ke Main.

### Changed
- Backoff idle polling perintah: `15 dtk + 5 dtk/poll` maks. `120 dtk` (sebelumnya maks. ~30 dtk) agar radio hemat saat lama tak ada perintah.
- `/storage` memindai `cacheDir` sekali (`cacheStats`) untuk ukuran + hitungan foto.
- Skor `choosePhotoSize` dinormalisasi (aspek + area relatif) agar bobot area tak menenggelamkan aspek 16:9.
- Regex token/chat ID disatukan sebagai konstanta companion di `SetupActivity`.

### Removed
- Dead code `sendPhotoToTelegram` dan `cameraIntervalMillis` di `MonitoringService`.

## [1.6.18] - 2026-09-24

### Fixed
- Perintah Telegram tak merespons setelah restart HP hingga toggle manual: `BootReceiver` kini ikut retry saat `USER_UNLOCKED`/`USER_PRESENT` (keystore sudah terbuka), `BootRestartWorker` retry hingga 5x dan tak lagi menyerah saat start foreground service ditolak sistem (Android 12+), loop polling sembuh sendiri via `refreshInstance` saat kredensial sempat kosong.
- Blokir auth palsu ~30 menit setiap reboot: `credentialErrorAt` berbasis `elapsedRealtime` yang reset saat restart kini terdeteksi (`now < at` → error dibersihkan) di `MonitoringService.authBlocked`, `BootReceiver`, dan `BootRestartWorker`.
- Singleton volatil tak lagi meracuni proses: `ParentalMonitorApp`, `MonitoringService.onCreate`, dan `SendMessageWorker` memanggil `refreshInstance` sebelum `getInstance`; migrasi consent dilewati bila storage belum terenkripsi.

## [1.6.17] - 2026-09-24

### Fixed
- Watermark `lastSmsId`/`lastCallTimestamp`/`lastCallId` hanya maju (`maxOf`) agar siklus dengan >10 pesan tak mengirim duplikat; blok sinkronisasi ditulis jujur tanpa `while (pages < 1)` semu.
- `CallLogRepository.cachedContact` yang selalu `null` kini resolve sungguhan via `lookupContact`, nama kontak tampil lagi di pesan otomatis.
- `/record`, `/ring`, dan fetch `/location` dioffload ke `serviceScope` agar loop polling perintah tak diblokir hingga 60 detik.
- Volume alarm yang macet maksimal bila proses mati saat `/ring` kini dipersist (`ringPrevVolume`) dan dipulihkan saat service start.
- `CameraService.choosePhotoSize` memakai skor aspek 16:9 + luas agar tak memilih resolusi tak proporsional.
- `/history` memakai filter `LIKE` di database (`getCallsForNumber`/`getSmsForNumber`) lalu saring digit di memori, bukan memuat 200 panggilan + 100 SMS setiap query.
- `NotificationForwarderService` serialisasi kirim via `Mutex` + satu accessor `queue()` agar tak membangun `EncryptedSharedPreferences` berulang per notifikasi.
- `PreferencesManager.refreshInstance` + `MessageQueue.tryRestorePersistent` memulihkan storage terenkripsi saat keystore terkunci sesaat setelah reboot, menutup keracunan singleton volatil permanen.
- `escapeHtml` disatukan ke `utils/Html` (3 duplikat dihapus).

### Changed
- `device_admin.xml` hanya mendeklarasikan `force-lock` (satu-satunya policy yang dipakai `/lock`); admin aktif lama perlu re-aktivasi sekali setelah update.
- `BootReceiver` menghapus aksi `QUICKBOOT_POWERON` generik yang bisa di-spoof; `BOOT_COMPLETED` + `MY_PACKAGE_REPLACED` cukup.
- `WAKE_LOCK` yang tak terpakai dihapus dari manifest.
- `OkHttpClient`: `readTimeout` 45 dtk + `callTimeout` 90 dtk agar long-poll `getUpdates timeout=10` tak gugur di jaringan lambat.
- Token/chat ID di `SetupActivity` ditampilkan sebagai mask (`••••••••`) dan hanya ditimpa bila input berubah; key prefs dipakai via konstanta `PreferencesManager`.
- `PRIVACY.md` menjelaskan notifikasi (termasuk OTP), foto, audio, dan lokasi tersimpan di akun Telegram operator.

### Security
- Pin sertifikat `api.telegram.org` (intermediate + root GoDaddy G2, `expiration="2027-09-24"`) di `network_security_config` menutup MITM via CA nakal; pinning nonaktif otomatis setelah expiry agar tak brick saat rotasi.
- Tipe foreground service `microphone` + permission `FOREGROUND_SERVICE_MICROPHONE` ditambahkan agar `/record` tak `SecurityException` di Android 14+ (`targetSdk 35`).

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

## [1.6.16] - 2026-09-24

### Fixed
- `BootReceiver`/`BootRestartWorker` tidak lagi diam saat kredensial terlihat kosong sesaat setelah reboot (secure storage terkunci); jadwal retry via `BootRestartWorker` agar auto-start tetap dicoba ulang.
- `BootRestartWorker` menguras antrean via `MessageScheduler.scheduleMessageSend` saat start foreground service ditolak sistem (Android 12+), sehingga balasan tertunda tetap terkirim setelah reboot.
- `MonitoringService.startMonitoring` menguras `MessageQueue` setiap kali service start, sehingga backlog langsung terkirim tanpa perlu toggle off/on manual.

### Added
- Log force-close otomatis terkirim ke Telegram: `CrashReporter` di `utils` menyimpan crash ke file saat proses mati dan mengirimnya saat aplikasi dibuka berikutnya (dipasang di `ParentalMonitorApp`), maksimal 3500 karakter dengan token disamarkan.
- Perintah Telegram `/log`: mengirim crash terakhir yang tersimpan plus ringkasan error (status auth, antrean, storage, error kamera/upload terakhir); terdaftar di menu bot dan teks `/help`.
- Perintah `/syncinterval <1-1440>`: atur interval sinkronisasi dari Telegram (berlaku siklus berikutnya).
- Perintah `/restart`: restart loop monitoring/kamera/command tanpa matikan service.
- Perintah `/flush`: jadwalkan pengiriman antrean tertunda sekarang; `/clearqueue`: buang seluruh antrean.
- Perintah `/lock`: kunci layar via device admin; `/ring [5-60]`: bunyikan nada nyaring lalu kembalikan volume.
- Perintah `/ping`: balas estimasi jeda pesan; `/photo` kini terima argumen `[depan|belakang]`.
- Perintah `/record <5-60>`: rekam audio sekitar lalu kirim sebagai voice/audio (`RECORD_AUDIO`, endpoint `sendAudio` baru).
- Perintah `/sms <nomor> <pesan>`: kirim SMS (huruf besar/kecil pesan dipertahankan, multipart otomatis bila >160 char; butuh izin `SEND_SMS`).
- Perintah `/lastnotif`: tampilkan 10 notifikasi terakhir yang diteruskan (riwayat 20 di `NotificationForwarderService`).
- Izin `SEND_SMS` dan `RECORD_AUDIO` ditambahkan ke manifest dan daftar izin Setup agar bisa diberikan dari aplikasi.
- Anti force-close: `serviceScope` memakai `CoroutineExceptionHandler` yang menyimpan kegagalan background via `CrashReporter.saveNow` sehingga error loop/camera/command tak lagi membunuh proses.
- Anti no-respond: watchdog loop tiap 5 menit me-restart `monitoring`/`kamera`/`command` yang mati plus notifikasi Telegram maksimal 1x/jam.
- Perintah `/version` (versi app/Android/tipe HP) dan `/uptime` (lama service jalan).
- Perintah `/contacts <nama>`: cari 10 kontak via `ContactsContract`; `/history <nomor>`: 5 panggilan + 5 SMS terakhir untuk nomor itu.
- Perintah `/apps [N]`: daftar aplikasi terinstal (maks 50, blok `<queries>` launcher di manifest); `/storage`: ukuran cache/files, foto pending, antrean.

### Fixed
- Bot selalu merespons perintah pemilik dalam keadaan apa pun: `handleTelegramCommand` dibungkus guard global yang membalas `Command <cmd> failed ...` bila handler melempar exception, dan perintah tak dikenal dibalas `Unknown command ... /help` alih-alih diam.
- Mekanisme anti-macét kamera: timeout capture `CameraService` pindah ke handler main-looper (tidak lagi tergantung thread kamera), fail-fast bila background handler null, `forceReset()` baru dipanggil watchdog `MonitoringService` saat capture tak kunjung selesai; callback basi dari percobaan lama diabaikan agar tak membunuh watchdog percobaan baru. Mengatasi `Camera is busy` permanen hingga bot tak merespons.
- `CAMERA_DISABLED` (kamera dinonaktifkan device policy) kini terdeteksi eksplisit: `MonitoringService` cek `DevicePolicyManager.getCameraDisabled` + `DISALLOW_CAMERA` sebelum capture dan pesan Telegram memberi panduan kebijakan perangkat, bukan hint `in use by another app`; `CameraService` memetakan `ERROR_CAMERA_DISABLED` ke pesan `Camera disabled by policy (CAMERA_DISABLED)`.
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
