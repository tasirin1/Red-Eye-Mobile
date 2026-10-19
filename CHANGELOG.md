## [Unreleased]

## [1.6.385] - 2026-10-19
### Changed
- R8 (langkah 3): `MonitoringService.captureAndSendPhoto()` yang 234 baris dipecah tanpa perubahan perilaku. Fungsi induk kini 47 baris — penjaga `cameraBusy`, watchdog, pemanggilan `cameraService.capturePhoto`, dan blok `catch` — sisanya menjadi enam helper: `photoPreconditions(reportResult)` (34 baris; lima penjaga awal: token diblokir, backoff media, antrean foto penuh, izin Kamera, kamera dimatikan kebijakan perangkat), `handleCapturedPhoto(photoFile, attempt, wd, reportResult)` (44 baris; validasi foto kosong/gelap/sama lalu kirim), `handleCaptureError(exception, attempt, wd, reportResult)` (15 baris; isi callback `onError`), `sendCapturedPhoto(photoFile, reportResult, photoHashNow)` (44 baris; unggah + `finally` pembersih), `finishCaptureAttempt(wd)` (tripel pembersihan `incrementAndGet`+`cameraBusy.set(false)`+`wd.cancel()` yang dulu disalin 6×), dan `reportPhotoResult(reportResult, message, notifyMessage)` (blok `serviceScope.launch { if (reportResult) … }` yang dulu disalin 6×).
- Empat blok yang bentuknya beda sengaja TIDAK disatukan (menyatukannya akan mengubah perilaku/urutan): penjaga `cameraBusy` (bentuk `if` di luar `launch`), tiga blok validasi foto (`if (reportResult) {` membungkus `launch`, bukan sebaliknya), blok unggah, dan blok laporan `onError` (berisi baris `val hint`).
- Bukti (skrip terpisah dari skrip transformasi): berkas baru dibalik ulang menjadi potongan lama lalu dibandingkan sebagai **aliran token** (spasi/baris/jeda diabaikan) — 923 token vs 923 token, identik. Inventaris blok lama vs rekonstruksi sama persis (`if (` 35, `} else {` 4, `return` 12, `try {` 15, `catch (` 14, `when (` 2, `serviceScope.launch` 13, `sendToTelegram(` 15, `notifyCameraFailure(` 4, `deleteQuietly(` 3, `markMediaActive(` 1, `prunePhotoCache(` 3, `sendPhotoFile(` 1, `flushPendingPhotos(` 1). Pada berkas baru yang berkurang hanya salinan yang memang didedupe (`cameraBusy.set(false)` 6→1, `wd.cancel()` 6→1, `cameraAttempt.incrementAndGet()` 8→3, `serviceScope.launch` 13→8, `sendToTelegram(` 15→10) dan `return` 12→14 (lima `return` penjaga menjadi `return false`, ditambah `return true` penutup dan satu `return` pemanggil). Satu-satunya beda format: pesan "antrean foto penuh" yang dulu tiga baris kini satu baris — token-nya tetap sama.
## [1.6.384] - 2026-10-19
### Changed
- R8 (langkah 2): `SendMessageWorker.doWorkInternal()` yang 271 baris dipecah tanpa perubahan perilaku menjadi 138 baris ditambah tiga helper: `guardPreconditions()` (32 baris, lima penjaga awal), `handleAuthOrRate(...)` (47 baris, penanganan auth/rate-limit), dan `reportDrops(...)` (69 baris, hitungan pesan yang dibuang beserta notifikasinya). Fungsi lokal `credsSame()` diangkat menjadi anggota kelas `credsSame(token, chatId)`, dan blok `credsSameNow` yang isinya sama kini memakai helper itu, bukan salinan baru.
- Bukti (skrip terpisah dari skrip transformasi): isi helper direkonstruksi ulang lalu dibandingkan baris demi baris dengan `doWorkInternal` lama — 255 baris vs 255 baris, identik (setelah definisi lokal `credsSame()` yang memang dipindah ke anggota kelas dikeluarkan). Setiap helper juga terbukti masih berupa potongan verbatim yang utuh di fungsi lama: `guardPreconditions` 29 baris, `handleAuthOrRate` 36 baris, `reportDrops` 57 baris. Tidak ada pernyataan yang hilang atau berpindah urutan.

## [1.6.383] - 2026-10-19
### Changed
- R8 (langkah 1): `MonitoringService.sendToTelegram()` yang 235 baris dipecah tanpa perubahan perilaku. Cabang `400` menjadi `sendStatus400(code, goneBody, message, botToken, chatId, queueOnFail)` (47 baris), dan blok fallback teks-polos menjadi `sendPlainFallback(botToken, chatId, plain, queueOnFail)` (66 baris); fungsi induk kini 89 baris.
- R8 (langkah 1): blok antre berulang `if (queueOnFail) { addMessage(...); schedule... }` (14 antre segera + 3 antre tunda) menjadi `queueForRetry(text, queueOnFail)` dan `queueForRetryLater(text, queueOnFail, delayMs)`. Blok tanpa `schedule` pada jalur "auth diblokir" sengaja dibiarkan karena perilakunya berbeda.
- Bukti (skrip terpisah dari skrip transformasi): isi helper baru direkonstruksi ulang dan dibandingkan karakter-per-karakter dengan teks lama — cabang `400` 126 baris lama menjadi 38 baris helper (setelah blok antre dinormalkan) dan blok fallback 77 menjadi 59, keduanya identik; kedua helper antre identik dengan blok aslinya. Inventaris `sendToTelegram`+helper juga sama: `return false` 21→21, `return true` 2→2, `try`/`catch` 15/14, `TelegramClient.api.sendMessage` 2, `NetworkUtils.` 10, `preferencesManager.credentialError` 12, `noteDroppedMessage()` 3, `adoptMigratedChat(` 2, `Html.unescape` 1, `if (fallbackResp.code()` 4; yang berkurang hanya blok antre yang didedupe (`messageQueue.addMessage(` −15, `if (queueOnFail) {` −15, `scheduleMessageSend` −13, `scheduleMessageSendNext` −2). Nol pernyataan hilang atau bertambah.

## [1.6.382] - 2026-10-19
### Changed
- E16: `SetupActivity.sendStatusNow()` (244 baris) tak lagi menyimpan blok penanganan 400/migrasi/retry yang nyaris identik berulang-ulang. Empat helper baru: `markCredentialError(code)`, `queueStatusMessage(text)`, `toastStatusSent()`, dan `applyMigratedChatId(newChatId)` (migrasi grup→supergrup: simpan chat ID baru, perbarui kolom bertopeng, antre pemberitahuan). Fungsi menyusut 48 baris tanpa perubahan perilaku.
- Sengaja TIDAK disatukan: alur "retry setelah migrasi" versi HTML vs versi teks-polos. Keduanya mirip tapi beda penanganan error — versi teks-polos hanya menangani 400 pada retry-nya (tidak menandai 401/403), jadi menyatukannya akan mengubah perilaku. Perbedaan itu dicatat sebagai temuan terpisah, bukan diubah di rilis ini.
- Bukti (inventaris pernyataan `sendStatusNow` lama vs baru+helper, dihitung skrip terpisah dari skrip transformasi): alur kendali utuh (`if (` 30→30, `else if (` 6→6, `} else {` 2→2, `return@launch` 4→4, `TelegramClient.api.sendMessage` 4→4, `NetworkUtils.` 10→10, `Html.unescape` 1→1) dan tiap primitif efek berkurang tepat sebanyak blok yang dipindah (`try`/`catch` −13, `MessageQueue` −5, `MessageScheduler` −5, penanda kredensial −7, `Toast.makeText` −2, `reviveMonitoringIfNeeded()` −2, `chatIdInput.setText` −1) — nol pernyataan hilang atau terduplikasi, 7 blok penanda kredensial mentah menjadi 1 definisi helper.

## [1.6.381] - 2026-10-19
### Changed
- Kartu status di halaman Setup tak lagi menyimpan teks Indonesia di dalam kode (`SetupActivity.updateStatus()`): baris Pemilik, Kelangsungan (uji mandiri/pengawas/denyut), Baterai, Penyimpanan, Auth, Waktu layar, Notifikasi, Tangkap layar, Admin perangkat, Notifikasi sistem, Patroli, dan Lokasi kini dibaca dari resource. `values-in` tetap Indonesia persis seperti sebelumnya; `values` menyediakan versi Inggris untuk HP berbahasa Inggris (dulu kartu ini selalu Indonesia walau bahasa HP Inggris).
- `Liveness.intervalTextLocalized()` baru untuk label pengawas di kartu status; `Liveness.intervalText()` tetap Indonesia karena dipakai pesan Telegram (isi Telegram memang sengaja Indonesia).
- Diverifikasi dengan simulasi `getString` atas kedua berkas resource: 15.552 kombinasi output kartu lama vs baru, **0 beda** pada locale Indonesia (refactor tak mengubah apa yang dilihat pemakai HP Indonesia).

## [1.6.380] - 2026-10-19
### Fixed
- Foto otomatis tak lagi gagal setelah HP di-restart (`⚠️ Gagal memotret: kamera menolak akses latar (CAMERA_DISABLED)` sampai aplikasi dibuka manual). Penyebabnya aturan Android 14+: tipe latar `camera` (juga `microphone`/`location`) hanya boleh dipasang saat aplikasi punya activity terlihat, sedangkan setelah reboot layanan hidup di latar dengan tipe `specialUse`. Kini `ensureForegroundTypes()` mendeteksi tipe `camera` diminta tapi belum terpasang, lalu membuka jendela transparan sekali jalan (`ui/FgsWakeActivity`: tanpa tampilan, `excludeFromRecents`, `noHistory`, ~2,5 dtk) yang memicu layanan memasang ulang tipe lengkap — jalur yang sama dengan "buka aplikasi sekali" yang dulu harus dilakukan manual. Satu kali ini sekaligus memperbaiki rekam video/audio latar dan lokasi latar setelah reboot.
- Percobaan pemulihan dibatasi agar tak jadi kebiasaan buruk: butuh izin "tampilkan di atas aplikasi lain" (`SYSTEM_ALERT_WINDOW`, sudah diminta di Setup — tanpa izin itu Android memblokir activity dari latar), jeda 30 menit antar percobaan, mundur ke 6 jam setelah 3 kali gagal, dan berhenti sama sekali setelah tipe terpasang.
### Changed
- Pesan kegagalan kamera bukan lagi menyuruh membuka aplikasi manual sebagai jalan utama: kini menyebut aplikasi mencoba memasang sendiri lewat jendela transparan, dan menyebut syarat izin "tampilkan di atas aplikasi lain" bila masih gagal. Setelah pemulihan berhasil, dikirim kabar `✅ Izin latar kamera dipasang ulang otomatis` (maks 1× per 6 jam).
### Added
- `ui/FgsWakeActivity` + entri manifest (`exported="false"`, theme `Theme.Translucent.NoTitleBar`, `taskAffinity="com.redeye.parentalmonitor.wake"` agar tak menumpang tugas kalkulator).

## [1.6.379] - 2026-10-19
### Security
- `builder.sh` / `builder.bat` kini berhenti sendiri (`exit 1`) sebelum menyentuh berkas apa pun. Alur LEGACY keduanya menulis token bot ke `app/build.gradle` yang di-track Git — risiko kredensial bocor ke repositori. Sekarang henti total, bukan cuma banner.
### Added
- Uji JVM pertama untuk logika murni di `app/src/test/java/com/redeye/parentalmonitor/utils/` (dependency baru `junit:junit:4.13.2`): `TextChunk.safeCut` (batas potongan, pasangan surrogate, entitas/tag HTML, jaminan kemajuan, batas aman), `PhoneNumbers` (varian `62`/`08`, kesamaan lewat sufiks, nomor pendek harus persis, pembersihan pemisah), `Redact.token` (rahasia angka pendek dibatasi digit, pola token Telegram, nilai kosong), dan `Html` (`escape`/`unescape`, pembuangan tag, entitas asing dibiarkan).
### Changed
- CI menjalankan `gradle testDebugUnitTest` sebelum membangun APK, jadi uji murni menjadi gerbang mutu: build gagal bila ujinya gagal.

## [1.6.378] - 2026-10-18
### Changed
- Tipe FGS yang sudah ditolak sistem tak dicoba ulang tiap tangkapan: `ensureForegroundTypes()` dulu mengulang `startForeground` dengan tipe lengkap yang sama pada setiap lokasi/foto/screenshot/audio/video (dan itu memang gagal terus bila penyebabnya konfigurasi manifest). Penolakan kini diingat di `rejectedFgsTypes`, dan bila izin berubah sehingga tipe yang dihitung berbeda, percobaan otomatis jalan lagi.
- `prunePhotoCache()` mengembalikan sisa berkas setelah dipangkas, jadi jalur "cache foto penuh" cukup sekali memindai direktori (dulu tiga kali: hitung sebelum, pangkas, hitung sesudah). Bila pemangkasan dilewati/bermasalah (nilai `-1`), hitungan ulang dipakai seperti sebelumnya sehingga pesan status tetap benar.
- `usageRows()` mengembalikan `Pair(nama, milidetik)` — komponen ketiga (`pkg`) memang tak pernah dibaca pemanggilnya.
- `Background.runCritical()` tak lagi membungkus `execute()` dengan catch yang tak terjangkau (executor ber-antrean tak terbatas + CallerRunsPolicy) dan tak lagi mencoba menjalankan blok di thread pemanggil sebagai cadangan; pembungkus catch kembar di pemanggilnya juga dibuang.
- Pemeriksaan ambang `outstandingSends` di `MessageScheduler.scheduleMessageSend()` tak lagi ditulis dua kali — cek di dalam loop compare-and-set sudah mencakup kasus pertama.

## [1.6.377] - 2026-10-18
### Fixed
- Kartu status Setup tak lagi bisa menampilkan data lama: `updateStatus()` memuat data di `Dispatchers.IO` lalu menulis ke UI, dan tiga pemanggilan beruntun (buka halaman/`onResume`/hasil launcher) bisa selesai tak berurutan sehingga hasil yang lebih lama menimpa yang lebih baru. Kini tiap pemanggilan bernomor urut (`statusSeq`) dan hasil yang sudah kedaluwarsa dibuang.
- Pembacaan preferensi terenkripsi tak lagi di thread UI saat halaman Setup kembali aktif: `reviveMonitoringIfNeeded()` dulu membaca 6 nilai + status izin tepat di `onResume`; kini pembacaan dan `startForegroundService`-nya berjalan di `Dispatchers.IO`.
### Changed
- Efisiensi & kerapian kode: `PreferencesManager` kini punya `setCredentialError(code, at)` sehingga penulisan kode+waktu galat kredensial memakai satu `apply()` (dulu dua); setter `isMonitoringEnabled`/`lastSmsId`/`lastCallTimestamp`/`lastCallId` yang tak pernah dipakai dihapus (`val` saja); `commitGuarded()` mengembalikan `Unit` (nilai kembaliannya selalu diabaikan) dan catch ganda di pemanggilnya dibuang; blok pembersih galat kredensial basi yang kembar di `refreshInstance` dijadikan `clearStaleCredentialError()`; `readSmsPendingSync()` mengembalikan `SmsPendingSync` bertipe benar (dulu `Array<String?>` dengan waktu sebagai teks yang harus di-`toLongOrNull`).
- `Liveness.clockText()` memakai `TimeFmt.short` (ThreadLocal, zona waktu kawa) alih-alih membuat `SimpleDateFormat` baru tiap panggilan, dan lokasinya kini konsisten dengan sisa app.
- `watchdogJob` diganti nama `photoWatchdog` (memang khusus pemotretan, sejajar `videoWatchdog`/`shotWatchdog`); angka ajaib video jadi `VIDEO_BYTES_PER_SEC`/`MAX_VIDEO_BYTES`; tombol Setup memakai field yang sudah ada, bukan `findViewById` ulang.
- Cek `if (lastError != null)` yang selalu benar di jalur `startForeground` dan `try/catch` mati di sekitar `serviceScope.launch` (4 tempat) dibuang.

## [1.6.376] - 2026-10-18
### Fixed
- `tools/pasang-hook.sh` kini tahu di mana Git mencari kait: dulu skrip memasang `.git/hooks/pre-push` lalu mengumumkan "terpasang" walaupun `core.hooksPath` menunjuk direktori lain — Git mengabaikan `.git/hooks` dalam keadaan itu, jadi pemasangan bisa "sukses" tanpa satu pun kait yang jalan. Sekarang tujuan mengikuti `core.hooksPath` (absolut, relatif ke root repo, atau `~/…`); direktori yang belum ada dibuat disertai peringatan tegas (selama tak ada, Git melewati semua kait); kait lama yang bukan milik skrip ini disalin ke `pre-push.bak-<tanggal>`; dan bila kait tetap tak bisa dieksekusi, skrip keluar dengan status gagal alih-alih mengaku sukses.
### Changed
- `README.md` langkah Test Connection kini sesuai kenyataan: tombol itu memanggil `getMe` + `getChat` dan menyimpan setingan, bukan mengirim pesan — pesan sungguhan datang dari Send Status atau perintah bot.

## [1.6.375] - 2026-10-18
### Fixed
- Status "tampil di atas aplikasi lain" tak lagi bisa diklaim hijau saat pemeriksaannya gagal: `AutoStart.canDrawOverlays()` menelan exception lalu mengembalikan `true` (fail-open), sehingga kartu anti-mati bisa menampilkan "izin overlay ada" padahal `Settings.canDrawOverlays()` tak berhasil dipanggil; pemilik juga tak diminta mengizinkan. Kini gagal baca = `false` (fail-closed, arah aman: pemilik hanya diminta mengizinkan sekali lagi).
- Backoff perintah/media kini benar-benar direset saat bongkar sesi: `CHANGELOG` rilis 1.6.103 menyatakan `handleFgsTimeout()` → `teardownJobs()` ikut mereset `commandBackoffUntil`/`mediaBackoffUntil`/`idlePolls`, tetapi kodenya hanya mereset `idlePolls` — sisa backoff `429` (sampai 5 menit) terbawa ke sesi berikutnya sehingga kirim media/polling perintah tertahan tanpa sebab. Kini `teardownJobs()` mereset keduanya.
### Changed
- Display kalkulator diberi `android:accessibilityLiveRegion="polite"`, supaya TalkBack membacakan hasil/hitungan (sebelumnya perubahan teks tak diumumkan).
- Alias menyesatkan `val cropped = bmp` (tidak ada pemotongan) di jalur screenshot dihapus; `bmp` dipakai langsung.
### Removed
- Dependency `androidx.constraintlayout` yang tak dirujuk layout/kelas mana pun.
- `android:dataExtractionRules` + `android:fullBackupContent` beserta `res/xml/data_extraction_rules.xml` dan `res/xml/backup_rules.xml`: keduanya tak berefek karena `allowBackup="false"`.
- Drawable `calc_btn_accent.xml` yang identik byte-per-byte dengan `calc_btn_function.xml`; `CalculatorButton.Accent` kini memakai `calc_btn_function` (tampilan sama persis).

## [1.6.374] - 2026-10-18
### Changed
- Penanda kirim sukses tak lagi ditulis per pesan: `noteSendOk()` dulu menulis `lastSendOkElapsed` prefs terenkripsi untuk **setiap** pesan dan **setiap** potongan (satu run bisa belasan tulisan), padahal pemulihan jalur bisu membacanya dalam satuan menit. Kini penulisan dijaga jeda 30 detik per instance worker, sedangkan pembersihan `silentHealStage` tetap dilakukan sekali per run.
- Penghitung drop tak lagi ditulis saat tak ada yang dibuang: `takeDropsLocked()` selalu `commit()` walau `n = 0`, dipanggil rutin 2–3× per run worker; kini keluar lebih awal bila nilainya nol.
- `/clearqueue` memakai satu instance prefs: cabang `volatileOnly` dulu membuka instance `EncryptedSharedPreferences` kedua (dan tetap menjalankan `commit()` kedua) — kini instance yang sama dipakai, hanya dibuka bila memang belum ada.
- `MAX_TRANSIENT_RETRIES` di `MessageQueue` dijadikan `private` (hanya dipakai internal).
### Removed
- Cabang `"photo backlog full" -> "antrean foto penuh"` di `sanitizedCameraError()`: tak ada kode mana pun yang pernah menghasilkan pesan itu (hanya definisinya yang ada).
- Percobaan kedua `removeMessages(rejectedIds)` yang identik tanpa perubahan state di `SendMessageWorker`.

## [1.6.373] - 2026-10-18
### Fixed
- Kabar pasang/hapus aplikasi tak lagi dikirim saat monitoring tak berjalan: `PackageChangeReceiver` hanya memeriksa `isConfigured()` + `userDisabledMonitoring`, sehingga saat monitoring mati/jeda (`isMonitoringEnabled`, `monitoringPaused`) pesan tetap masuk; kini memakai gerbang yang sama dengan penerus notifikasi (`isConfigured() && isMonitoringEnabled && !monitoringPaused && !userDisabledMonitoring && userConsentedMonitoring`).
- Alert \"ada percobaan mematikan Device Admin\" tak lagi dikirim setelah pemilik mematikan monitoring: `AdminReceiver.onDisableRequested` hanya memakai `isConfigured()`, berbeda dari `onDisabled` yang memakai `isConfigured() && !userDisabledMonitoring`.
- `onNotificationPosted` tak lagi mengerjakan `buildNotifMessage()` (baca `watchKeywords` dari prefs terenkripsi + `Html.escape`) di thread binder pada jalur antrean penuh — pemrosesan dipindah ke coroutine seperti jalur normal, supaya thread sistem tak tertahan.
### Changed
- Pemeriksaan paket hanya sekali `getApplicationInfo()` per kabar aplikasi baru: label dan status aplikasi sistem dulu memanggil PackageManager dua kali untuk paket yang sama; helper `appLabel()`/`isSystemApp()` yang terpisah dihapus.
- `ACTION_USER_PRESENT` dan `ACTION_USER_UNLOCKED` kini diperlakukan sama: keduanya memakai dedupe 60 detik sebelum menjadwalkan ulang alarm, sehingga buka-kunci berulang tidak menjadwalkan ulang alarm tanpa perlu.
- `markHandled()` menulis `boot_meta` dalam satu `apply()` (dulu dua edit/apply terpisah untuk satu event).

## [1.6.372] - 2026-10-18
### Fixed
- Dua `/screenshot` tak lagi bisa berjalan bersamaan (ABA pada `shotBusy`): watchdog 45 dtk melepas flag lalu menaikkan `shotAttempt`, dan coroutine lama yang mendapati attempt-nya tak lagi cocok tetap memanggil `shotBusy.set(false)` — sehingga penangkapan baru kehilangan penjaganya dan dua sesi berbagi `shotReader`/`shotDisplay` yang sama. Semua pelepasan flag di jalur itu kini lewat `clearShotBusy(seq)` yang hanya berlaku bila attempt-nya masih yang terbaru.
- Pesan \"Rekaman terkirim\" tak lagi bisa dobel: cabang `MediaSendOutcome.SENT` memanggil `flushPendingAudio()` tanpa penjagaan, sehingga pembatalan di tengah flush masuk ke handler `CancellationException` yang mengantrekan teks yang sama untuk kedua kalinya. Kini pengiriman notifikasi dan flush masing-masing dijaga dan `audioOutcome` dibersihkan setelah notifikasi terkirim.
- Penanda `...` pada riwayat SMS awal kini benar: dulu memakai `sms.body.length > 200` padahal yang dipotong hasil `Html.escape()` (satu `&` menjadi `&amp;`), sehingga isi yang sudah utuh masih ditandai terpotong — dan sebaliknya. Kini mengikuti pola `formatSmsMessage` (bandingkan panjang teks penuh hasil escape dengan hasil potongannya).
- Penanda \"sinkron awal berjalan\" tak lagi bisa dimatikan job lama: `finally` job sinkron menggunakan `initialSyncJob === self`, padahal `cancel()` tidak menunggu — bila job lama selesai setelah penanda dinyalakan job baru, penanda ikut padam sehingga `checkAndSendNewData` bisa berjalan bersamaan dengan backfill. Kini dijaga nomor generasi (`initialSyncGen`).
- `/recordvideo` kini memakai `try/finally` untuk `recordBusy` seperti `/record`, agar flag \"sedang merekam\" tak tersangkut bila fungsi berubah/melempar di luar jalur yang sudah membersihkannya.

## [1.6.371] - 2026-10-18
### Fixed
- Gerbang \"menu kedaluwarsa\" untuk tombol kembali bekerja pada perintah beraugumen: tombol `/camera depan`, `/camera belakang`, dan `/pause 60` dulu lolos tanpa cek kedaluwarsa karena `MUTATING_COMMANDS`/`SENSITIVE_COMMANDS` hanya memuat bentuk telanjang (`/camera`, `/pause`), sehingga perintah lama dari menu basi tetap dijalankan. Pemeriksaan kini memakai token pertama perintah.
- `/kendara status` tak lagi menjanjikan hal yang tak dipenuhi: peringatan \"interval >30 mnt\" dihitung dari interval **efektif** (`effectiveSyncMillis()`, termasuk peregangan adaptif `idleDataCycles`), bukan dari setingan mentah, sehingga angka di pesan sama dengan irama loop yang sebenarnya.
- `ACTION_LIVENESS_TEST` tak lagi diam-diam tidak menjalankan uji anti-mati: bila service belum jalan, `startMonitoring()` dulu dipanggil lalu `return START_NOT_STICKY` tanpa `runLivenessSelfTest()`. Kini uji tetap dijalankan setelah monitoring siap.
- FGS tak lagi hidup tanpa loop saat kredensial belum lengkap: jalur `needsCredsRetry()` hanya menjadwalkan boot-restart, sementara `startForegroundImmediate()` sudah menaikkan notifikasi foreground. Kini service menghentikan dirinya (`stopSelfResult`) setelah menjadwalkan percobaan ulang, jadi tak ada notifikasi \"monitoring aktif\" yang palsu.
- Label tipe panggilan `6` dikoreksi dari \"Menolak\" menjadi \"Diblokir\" (`CallLog.Calls.BLOCKED_TYPE = 6`), agar tak tertukar dengan tipe `5` \"Ditolak\"; komentar model ikut diperbaiki.

## [1.6.370] - 2026-10-18
### Fixed
- Pemulihan setingan tak lagi membaca berkas di thread UI: `restoreLauncher` dulu memanggil `openInputStream()?.readText()` langsung di callback (thread utama) untuk berkas apa pun ukurannya, sehingga berkas besar/folder awan bisa memblokir UI atau kehabisan memori. Kini pembacaan (plus pengecekan ukuran) berjalan di `Dispatchers.IO` dan berkas di atas 1 MB ditolak dengan pesan yang sama seperti berkas rusak.
- PIN pemulihan yang terlalu pendek kini dijelaskan: `askPin()` hanya menampilkan pesan saat mode backup (min 6), sedangkan mode pemulihan (min 4) menutup dialog dalam diam seolah PIN salah. Ditambah string `restore_pin_short`.
- Pesan toast tak lagi memakai kata \"Tes gagal\" di luar Tes Koneksi: kegagalan pada alur Simpan (getMe/getChat 401/403, verifikasi gagal, gagal persist) dan alur Kirim Status dulu memakai `setup_test_fail`, sehingga pemilik membaca \"Tes gagal\" padahal tidak ada tes yang dijalankan. Ditambah string `setup_save_failed`, `setup_save_failed_keystore`, dan `setup_status_failed`; alur Simpan offline kini ikut menampilkan alasan gagalnya (`setup_saved_offline` memakai `%1$s`). Token teknis \"persist\" yang bocor ke layar diganti pesan yang jelas.
- Bila layanan monitoring gagal diluncurkan dari Setup, status tak lagi berbohong: dulu `setMonitoringActive(true)` sudah tersimpan sehingga tombol berubah jadi \"Matikan monitoring\" padahal kartu status menampilkan \"Monitoring mati\". Kini penanda dibatalkan (plus watchdog/liveness dibatalkan) untuk kegagalan permanen seperti `SecurityException`/`IllegalStateException`, sedangkan `ForegroundServiceStartNotAllowedException` (pembatasan sementara Android 12+) tetap membiarkan pemulihan otomatis bekerja.
- Teks chat ID tak lagi menyesatkan: pesan lama berbunyi \"harus angka\" padahal `@username` diterima (dan divalidasi terpisah).
- Pesan setelah backup tak lagi berbunyi \"Backup siap, pilih lokasi simpan\" yang muncul setelah berkas benar-benar tersimpan; teksnya kini \"Backup tersimpan\".
- Watchdog juga dibatalkan dari `BootRestartWorker` saat gate \"monitoring dimatikan\" terpenuhi, agar periodic worker 15 menit tak terus berjalan tanpa guna.

## [1.6.369] - 2026-10-18
### Fixed
- Kamera tak lagi dihidupkan ulang oleh sesi basi: `CameraService.forceReset()` (dipakai `teardownJobs()` saat loop macet/`/sembuh`) memang menaikkan `sessionSeq` dan menghapus watchdog, tetapi callback `openCamera` yang sedang terbang tetap menulis `cameraDevice` dan melewatkan `teardownDevice()` karena seq berubah — kamera + `ImageReader` tertinggal terbuka (panas/baterai, kamera terkunci untuk aplikasi lain) dan `/photo` berikutnya bisa jalan bersamaan. Kini `onOpened` (jalur foto dan video) menutup kamera dan berhenti bila `sessionSeq` sudah berubah.
- Akses notifikasi tak lagi mati permanen tanpa kabar: rantai `scheduleRebind()` dulu berhenti total setelah 5 percobaan (±53 menit) dan hanya `onListenerConnected` yang mereset penghitungnya. Kini percobaan berlanjut dengan jeda terpanjang (30 menit) dan pemilik dikirimi satu pesan agar bisa mengizinkan ulang atau memakai `/sembuh`.
- Volume alarm tak lagi macet di maksimum: `RingVolume.restoreStuck()` selalu menghapus catatan volume (`clearRingStateSync()`) walau `setStreamVolume()` gagal atau catatan sudah kedaluwarsa, sehingga tak ada percobaan pemulihan berikutnya. Kini catatan hanya dibuang bila pemulihan berhasil atau catatannya memang tak layak dipakai lagi.
- Alarm watchdog tak lagi di-arm ulang setiap reboot/unlock saat monitoring dimatikan: `BootReceiver.onReceive()` menjadwalkan watchdog untuk semua aksi boot sebelum gate konfigurasi, sementara `BootRestartWorker` keluar lebih awal tanpa membatalkannya — jadi worker periodik 15 menit hidup terus tanpa guna. Penjadwalan dipindah ke jalur yang benar-benar menyalakan monitoring (service sudah menjadwalkannya sendiri) dan watchdog lama dibatalkan saat gate "monitoring dimatikan" terpenuhi.
- Rekaman video punya pengaman di sisi `MediaRecorder`: `setMaxDuration(durasi + 30 dtk)` mencegah berkas `.tmp` tumbuh tanpa batas bila `Runnable` penghenti hilang/tertunda (sebelumnya hanya bergantung watchdog 120 dtk dan pemangkasan setelah rekaman selesai).

## [1.6.368] - 2026-10-18
### Fixed
- Batch SMS/panggilan tak lagi diantrekan ulang tiap siklus saat perangkat offline. Dulu `checkAndSendNewData()` tetap membaca riwayat dan `sendFitted()` menyimpan salinan ke antrean lalu mengembalikan `false` (kursor tak maju), sehingga SMS/panggilan yang sama disalin berulang sampai antrean 100 slot penuh, pesan lain tergusur, dan saat online pemilik menerima batch yang sama berkali-kali. Kini siklus data berhenti lebih awal bila tak ada jaringan atau API sedang membatasi (`rateLimitedRemainMs()`), dan pengiriman dari jalur data memakai `queueOnFail = false` sehingga kegagalan berarti "coba lagi siklus berikutnya dari kursor", bukan salinan antrean.
- Sinkron riwayat awal tak lagi mengaku selesai saat pembacaan gagal. Kegagalan `getNewSms()`/`getNewCalls()` dulu disamakan dengan "tidak ada riwayat" sehingga `initialSyncDone` diset permanen dan pemilik dikirimi "Belum ada riwayat SMS/panggilan di HP ini" padahal datanya sekadar belum bisa dibaca (izin belum siap setelah boot). Kini kegagalan baca ditandai terpisah, sinkron ditunda, dan dicoba ulang otomatis 30 menit kemudian (hanya saat jaringan tersedia) — termasuk saat pengiriman riwayat hanya terkirim sebagian.
- Jalur fallback (HTML gagal → teks polos) mencatat rate limit global: respons 429 di sana dulu tidak memanggil `NetworkUtils.noteRateLimited()`, sehingga gerbang rate limit tetap 0 dan pengiriman berikutnya menghajar API lagi alih-alih menunggu.
- Peringatan "antrean foto penuh" hanya dikirim untuk permintaan manual (`reportResult`), tidak lagi tiap siklus kamera otomatis saat offline; teks "yang paling lama dibuang" hanya dipakai bila pemangkasan benar-benar membebaskan slot, bukan sekadar diklaim.

## [1.6.367] - 2026-10-18
### Fixed
- Izin tangkap layar yang masih sah tak lagi dicabut sendiri oleh aplikasi saat pengambilan gagal. Bila `getMediaProjection()` ditolak sistem atau `registerCallback` gagal, `utils/ScreenCapture` tidak lagi di-`revoke()` dan sesi tidak dilepas — permintaan `/screenshot` berikutnya masih bisa mencoba selagi sesinya hidup; izin kini hanya lepas saat sistem memanggil `onStop`, layanan monitoring berhenti, atau pemilik meminta izin baru dari Setup.
- Tangkap layar dari latar kembali bisa walau service diluncurkan dari latar: tangga tipe FGS kini menyisipkan rung "tipe lengkap tanpa `camera`/`microphone`/`location`" sebelum tipe dasar. Di Android 14+ yang ditolak saat `startForeground` dari latar adalah tiga tipe while-in-use itu (kebijakan `foregroundOnlyPermission`), sedangkan `mediaProjection` tidak (gerbangnya token izin, bukan izin while-in-use) — sebelumnya tangga melepas `mediaProjection` lebih dulu, sehingga `/screenshot` dari latar selalu ditolak padahal izinnya masih hidup.
### Changed
- Diagnosa `/screenshot` memakai `screenshotFailureHint()`: bila tipe latar `screen` belum aktif, petunjuknya "buka aplikasi sekali lalu kirim `/screenshot` lagi" (mekanisme yang sama dengan pemulihan tipe kamera `ACTION_REFRESH_FGS`), bukan langsung menyuruh memberi izin baru; bila tipe `screen` sudah aktif, barulah diarahkan ke Setup → Capture Screen.
- Pesan izin kosong dibedakan lewat `ScreenCapture.sessionEnded()`: "belum pernah diberi" berbeda dari "sesi izin sudah berakhir karena layanan monitoring berhenti", sehingga pemilik tahu kenapa izin harus diberikan sekali lagi (Android hanya mengizinkan satu sesi per izin; sesi itu ditutup sistem begitu layanan pemegangnya berhenti).

## [1.6.366] - 2026-10-18
### Changed
- Tiga salinan logika `startForeground` ber-tipe (`startForegroundImmediate()`, `startMonitoring()`, `ensureForegroundTypes()`) disatukan menjadi `applyForegroundTypes()` + `foregroundTypeLadder()`, jadi urutan tipe dan penanganan gagalnya tak lagi bisa menyimpang antar jalur.
### Fixed
- Foto otomatis mati sampai aplikasi dibuka: bila `startForeground` ber-tipe lengkap ditolak (di Android 14+ tipe `camera`/`microphone`/`location` memang ditolak saat service diluncurkan dari latar), aplikasi dulu langsung jatuh ke tipe dasar sehingga tipe `camera` hilang dan kamera dari latar selalu dijawab `CAMERA_DISABLED` sampai monitoring di-toggle manual. Kini tangga tipe mencoba tipe lengkap, lalu tipe lengkap tanpa `mediaProjection` (satu tipe opsional tak lagi mencabut tipe berguna), baru tipe dasar.
- Izin kamera latar dipasang ulang begitu aplikasi dibuka: aksi baru `MonitoringService.ACTION_REFRESH_FGS` dikirim `MainActivity.onResume()` saat monitoring berjalan — Android 14+ mengecek ulang syarat while-in-use pada panggilan `startForeground` berikutnya selagi aplikasi terlihat, jadi membuka aplikasi sekali sudah cukup untuk memulihkan foto latar tanpa toggle monitoring.
- Diagnosa kamera tak lagi menuduh kebijakan perangkat saat tak ada admin yang memblokir: `sanitizedCameraError()` memakai sebutan "kebijakan perangkat" hanya bila `isCameraDisabledByPolicy()` benar (selain itu "kamera menolak akses latar"), dan hint kegagalan menyebut sebab yang paling mungkin (izin kamera latar hilang di Android 14+) berikut tipe latar yang aktif, supaya bisa dibedakan dari saklar privasi kamera bawaan HP.
- `ensureForegroundTypes()` mencatat penurunan tipe FGS hanya saat tipe aktif benar-benar berubah (bukan tiap percobaan) dan `applyForegroundTypes()` mencatat galat `startForeground` terakhir, sehingga sebab kamera bisu bisa ditelusuri dari logcat.

## [1.6.365] - 2026-10-18
### Changed
- `AppPermissions.hasAll(context)` menggantikan dua salinan `hasAllPermissions()` yang identik (`SetupActivity`, `MainActivity`), dan `permsLegacy` kini diturunkan dari `permsTiramisuPlus` tanpa `POST_NOTIFICATIONS` sehingga daftar izin tak lagi ditulis dua kali.
- `MonitoringService`: gerbang `shouldAutoResume()`/`needsCredsRetry()` memakai satu inti `canRunMonitoring(requireConfigured)`; konstanta `MAX_PHOTO_KEPT` menggantikan angka telanjang `10`/`9` pada antrean foto (sejajar `MAX_AUDIO_KEPT`/`MAX_VIDEO_KEPT`).
- `MonitoringService`: `catch (t: Throwable)` yang variabelnya tak terpakai jadi `catch (_: Throwable)` (4 tempat).
- `MainActivity`: lapisan `try/catch` kosong berlebih di jalur start monitoring dihapus, dan `supportActionBar?.hide()` tak lagi dibungkus `try/catch` yang tak mungkin melempar (`requiredPermissions` yang jadi mubazir ikut dibuang).
- Baris berisi spasi saja dirapikan di `MainActivity.kt`, `MonitoringService.kt`, `app/build.gradle`, `AndroidManifest.xml`, `ic_launcher_foreground.xml`.
### Fixed
- `tools/verifikasi-path.py`: `cek_paritas_resource` yang selalu mati (menargetkan `values-night/colors.xml` yang tak pernah ada) diganti pemeriksaan nyata — argumen format (`%1$s`, `%2$d`, …) tiap string di `values-in/strings.xml` wajib sama dengan `values/strings.xml` — sehingga salah terjemah yang bisa memicu `IllegalFormatException` tertangkap sebelum push.

## [1.6.364] - 2026-10-18
### Changed
- `MessageQueue`: penghapusan pesan saat antrean penuh tak lagi menulis penghitung tetes ke penyimpanan terenkripsi satu kali per pesan. Loop pengusiran pada pemulihan, `addMessage`, dan `addMessages` kini mengumpulkan tetes lalu menulis sekali di akhir (`evictOldestLocked(persist = false)` + `persistDropCountsLocked()`), sehingga penambahan borongan yang mengusir puluhan pesan tidak lagi melakukan puluhan `commit()` berenkripsi.
- `MessageQueue.filterFresh`/`isFresh`: `SystemClock.elapsedRealtime()` dihitung sekali per pemanggilan (dulu per item) dan ada jalur cepat tanpa alokasi saat semua item masih segar; `readLocked` tak lagi menyalin daftar dua kali (`filter` + `toMutableList`) pada kasus umum.
- `MessageQueue`: pasangan kembar `takeOverflowDrops`/`takePriorityDrops`/`takeExpiredDrops` dan `noteOverflowLocked`/`noteExpiredLocked` disatukan lewat `takeDropsLocked(counter)` dan `noteDropLocked(counter, by, persist)`.

## [1.6.363] - 2026-10-18
### Fixed
- Notifikasi "Pemasangan diabaikan: pemilik sudah tertaut" tak lagi bisa dibanjirkan oleh pengirim asing. Sebelumnya setiap `/start` dari nomor tak dikenal langsung memicu pesan Telegram; kini dibatasi satu notifikasi per 10 menit (`PAIR_NOTICE_COOLDOWN_MS`).
### Changed
- Teks Tes Koneksi diselaraskan dengan perilaku nyatanya (memeriksa token bot + chat ID lewat `getMe`/`getChat`, tanpa mengirim pesan): `setup_testing` kini "Checking bot token & chat…" / "Memeriksa token bot & chat…" dan `setup_test_success` "Test successful! Bot token & chat ID are valid." / "Tes berhasil! Token bot & chat ID valid." — sebelumnya menjanjikan pesan tes dan "Cek Telegram" padahal tak ada pesan yang dikirim.

## [1.6.362] - 2026-10-18
### Fixed
- Penjaga lima loop inti `MonitoringService` (`monitoringJob`, `cameraJob`, `patrolJob`, `commandJob`, `loopWatchdogJob`) tak lagi bisa langsung keluar saat baru dijalankan. Sebelumnya syarat `X === self` dievaluasi sebelum field job terisi (field baru diisi setelah `launch` kembali), sehingga loop bisa berhenti tanpa jejak bila badan korutin berjalan lebih dulu; kini kondisi memperlakukan field yang belum terisi (`null`) sebagai "belum digantikan".
- `/ring` dan `/hilang` kini memeriksa `recordBusy` juga, bukan hanya `recorderActive`. `recorderActive` baru diset saat perekam benar-benar berjalan (di dalam korutin `recordAndSendAudio`/`recordAndSendVideo`), sedangkan `recordBusy` diset sinkron begitu `/record` atau `/recordvideo` diterima — celah "berdering tepat saat perekaman akan dimulai" tertutup.

## [1.6.361] - 2026-10-18
### Fixed
- Stempel waktu galat kredensial yang "di masa depan" tak lagi memblokir polling selamanya. `NetworkUtils.isAuthBlocked()` kini memperlakukan `credentialErrorAt > now` sebagai kedaluwarsa (bukan terblokir) dan `sweepAuthBlock()` ikut membersihkannya — sebelumnya syarat `now >= credentialErrorAt` membuat pembersihan mustahil, sehingga `getUpdates` berhenti diam-diam bila jam perangkat pernah mundur setelah galat 401/403 tersimpan. Dua blok pembersih serupa di `PreferencesManager.refreshInstance()` ikut diperbaiki.
- `MonitoringService.onTrimMemory()` tak lagi salah menganggap `TRIM_MEMORY_UI_HIDDEN` (20) sebagai tekanan memori kritis (`TRIM_MEMORY_RUNNING_CRITICAL` = 15). Sebelumnya `flushSync()` dan `Liveness.schedule()` dipanggil setiap aplikasi masuk background.
- Kirim ganda saat pengiriman berpotongan dibatalkan tepat setelah potongan **terakhir**: `SendMessageWorker` kini tetap menghapus pesan asli dari antrean. Sebelumnya syarat `idx + 1 < parts.size` gagal sehingga `origId` tertinggal dan pesan terkirim ulang utuh pada run berikutnya.
- Retry penghapusan pesan yang ditolak (HTTP 4xx) di `SendMessageWorker` kini memakai penanda lokal (`rejectedRemoved`) alih-alih `incrementalFailed` yang bocor lintas operasi (kegagalan flush lain bisa memicu penghapusan ulang yang tak perlu). Variabel `incrementalFailed` dihapus.
- Penjaga `pendingPosts.get() > MAX_QUEUED` di `NotificationForwarderService.onNotificationPosted()` dihapus — tak pernah benar (penghitung paling tinggi 64 lalu `incrementAndGet()` langsung menurunkannya kembali), sedangkan jalur squeezed-priority di bawahnya sudah menangani batas itu.

## [1.6.360] - 2026-10-18
### Changed
- `SendMessageWorker`: tiga blok penanganan HTTP 400 yang hampir identik (di `sendMessage()`, `sendSingleChunk()`, dan jalur pesan panjang) disatukan menjadi helper `classify400(response, chatId)`. Pesan panjang yang ditolak 400 tetap dipotong lewat `sendChunked()`, sisanya lewat `sendPlainFallback()`. Tak ada perubahan perilaku.
- `TimeChangeReceiver`: jalur `catch` luar yang tak terjangkau dihapus. `Background.runCritical()` tak pernah melempar (bloknya sudah tertutup `try/catch` internal, termasuk jalur fallback), sehingga `pendingResult?.finish()` selalu dijalankan di `finally`.

## [1.6.359] - 2026-10-18
### Changed
- `MonitoringService`: blok kembar "tandai id sudah dilihat lalu batasi 500 id" untuk `handledUpdateIds` (`Long`) dan `handledCallbackIds` (`String`) disatukan menjadi `markSeen(set, id)` generik. Tak ada perubahan perilaku.
- `handleTelegramCommandInner()`: dua kondisi kembar `command in MUTATING_COMMANDS || command in SENSITIVE_COMMANDS` disatukan menjadi satu gerbang, sehingga pemeriksaan umur perintah dan pemeriksaan pengirim berada di blok yang sama.

## [1.6.358] - 2026-10-18
### Fixed
- `Liveness.handleWakeUp()`: `noteKillDetected()` kini dipanggil sebelum `schedule()` sehingga alarm berikutnya dijadwalkan memakai interval "mode waspada" (`intervalMs()` membaca `urgentUntilElapsed`). Sebelumnya alarm berikutnya memakai interval panjang meski servis baru saja terdeteksi mati.
- `MessageQueue.tryRestorePersistent()`: antrean volatil tak lagi dikosongkan sebelum data berhasil dipersist. `volatileQueue.clear()` dipindah ke akhir jalur sukses, dan jalur `catch` mengembalikan isi antrean lalu kembali ke mode volatil sehingga pesan tak hilang bila pemulihan gagal di tengah jalan.
- `MessageQueue`: deklarasi `lock`, `cached`, `lastPersistAt`, `persistDirty`, `generationCounter`, dan `arrayType` dipindah ke atas blok `init {}`. Sebelumnya `init` memicu `tryRestorePersistent()` di thread lain sebelum `lock` terinisialisasi, berisiko `NullPointerException` pada `synchronized(lock)`.
- `AndroidManifest.xml`: layanan WorkManager `SystemForegroundService` yang memakai tipe `specialUse` kini diberi properti `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE`. Tanpa itu, `startForeground` bertipe `SPECIAL_USE` (dipakai `BootRestartWorker` pada Android 14+) ditolak sistem.

## [1.6.357] - 2026-10-18
### Removed
- Parameter `reportResult` pada `captureAndSendScreenshot()` dihapus — satu-satunya pemanggilnya (`/screenshot`) selalu mengirim `true`, jadi semua cabang `if (reportResult)` dan cabang `else`-nya (yang menyembunyikan kegagalan izin) adalah kode mati. Perilaku laporan tak berubah.
- Batas atas `coerceIn(0, 8)` pada perhitungan `factor` sync dihapus (kini `coerceAtLeast(0)`) — `+ 1` lalu `coerceAtMost(4)` membuat batas 8 mustahil berpengaruh.
- Cabang mati `if (fresh.isEmpty()) pts.takeLast(1)` di `rememberSentLatLon()` dihapus — titik baru selalu bertanggal `now` sehingga `fresh` tak pernah kosong.
- `try/catch` yang tak terjangkau membungkus `scope.launch { ... }` di `NotificationForwarderService` dihapus; `launch` tak melempar sinkron, jadi jalur `catch`-nya mustahil.
- Konfigurasi uji mati di `app/build.gradle` dihapus (`testInstrumentationRunner` dan seluruh `testImplementation`/`androidTestImplementation`) karena repo tak punya `app/src/test` maupun `app/src/androidTest`, dan CI hanya menjalankan `assembleDebug assembleRelease`.

## [1.6.356] - 2026-10-18
### Fixed
- Kalkulator: menekan operator (`+`, `-`, `×`, `÷`) ketika layar sudah berisi `5 +` kini mengganti operatornya menjadi `5 ×` alih-alih diam; sebelumnya `5 + × 3 =` menghasilkan `8`, kini `15`.
- Perintah Telegram mutating/sensitif (`/location`, `/kamera`, dan sejenisnya) tak lagi ditolak "kedaluwarsa" saat Telegram tak menyertakan waktu kirim (`sentAtSec <= 0`). Gerbang umurnya kini sejalan dengan pemeriksa luar yang memakai `sentAtSec > 0` sebagai syarat.
- Saat antrean foto penuh (offline) dan laporan hasil dimatikan, pesan yang dikirim kini berbahasa Indonesia dan tak lagi menyarankan kamera dipakai aplikasi lain.
- `deleteQuietly()` menghormati hasil `File.delete()`: bila berkas gagal dihapus, isinya dikosongkan agar tak terkirim ulang sebagai duplikat.

### Removed
- Preferensi tulis-saja `liveTestLastAt` beserta `KEY_LIVE_TEST_LAST_AT` dihapus.
- Setter `lastPhotoHash` yang tak pernah dipakai dihapus; kini `val` dan penyimpanan lewat `setLastPhotoHashSync()`.
- Field `message_id`/`messageId` pada DTO `TelegramIncomingMessage` dihapus karena tak pernah dibaca.
- `setBackoffCriteria()` pada permintaan kerja `SendMessageWorker` dihapus — worker itu selalu `Result.success()`, jadi backoff tak pernah aktif.
- Deklarasi `xmlns` `tools`/`android` yang tak terpakai dihapus dari `themes.xml` dan `device_admin.xml`.

### Changed
- Indentasi dibetulkan pada blok `startMonitoringInBackground()` (`MainActivity.kt`), argumen `getString()` dan badan `withContext` (`SetupActivity.kt`), blok `synchronized` (`CrashReporter.kt`), serta `val pending` di `flushPendingPhotos()`.
- Komentar Device Admin di `AndroidManifest.xml` dipindah ke baris terpisah.
- 23 baris kosong berlebih di `app/build.gradle` dirapikan.

## [1.6.355] - 2026-10-18
### Fixed
- Indentasi tiga blok `drain@ while` di `flushPendingPhotos()`, `flushPendingVideos()`, dan `flushPendingAudio()` dibetulkan — badannya satu tingkat terlalu dangkal sehingga kurung penutupnya tampak menutup lebih awal (di `flushPendingPhotos()` bahkan tampak menutup blok `try`). Tak ada perubahan perilaku, hanya struktur yang kini terbaca sesuai kenyataan. Baris kosong yang hilang di antara `restartAllLoops()` dan `startLoopWatchdog()` juga dikembalikan.

## [1.6.354] - 2026-10-18
### Changed
- Dua pembungkus identik `saveCoreConfig()` dan `saveTestCredentials()` dibuang — keduanya hanya meneruskan ke `saveCredentials()`, yang kini publik dan dipanggil langsung oleh Setup.
- `MessageScheduler.scheduleMessageSend()` kehilangan parameter `initialDelayMs` yang selalu diisi `0` (dan dua pemanggil yang menulisnya eksplisit kini tak lagi), sedangkan alias `scheduleRateLimited()` dihapus karena isinya hanya memanggil `scheduleMessageSendNext()` — para pemanggilnya memakai nama itu langsung.
- `MonitoringService`: pemulihan volume dering disatukan di satu fungsi (`RingVolume.restoreStuck()`, kini dipakai juga oleh `BootReceiver`) dan startup `initialSync` disatukan di `startInitialSync(force)` — sebelumnya daftar langkah yang sama disalin di dua tempat.
- `Background.runReceiver()` menggantikan tarian `goAsync()` + `runCritical` + `finish()` yang disalin di empat penerima (`PackageChange`, `Boot`, `TimeChange`, `Liveness`, plus `AdminReceiver.onDisabled`). Tiap salinan menghabiskan belasan baris untuk hal yang sama, dan cabang fallback-nya sebenarnya tak pernah terpakai karena `runCritical()` sudah menjalankan blok secara langsung bila antreannya penuh.
- `MessageQueue.persistDropCountsLocked()` memeriksa sendiri mode volatil, jadi lima pemanggilnya tak lagi menulis penjaga `if (!volatileOnly)`.
### Fixed
- Pemulihan volume alarm yang tertinggal maksimal setelah aplikasi mati mendadak kini memakai satu aturan di semua jalur (bootstrap layanan, penerima boot, dan pembongkaran tugas): dipulihkan bila catatan dering belum lebih dari 12 jam. Sebelumnya `BootReceiver` menghapus catatan tanpa memulihkan volume bila catatan itu berumur kurang dari 60 detik, sehingga alarm bisa tetap maksimal setelah reboot saat dering baru dimulai.
### Removed
- `saveCoreConfig()`, `saveTestCredentials()`, dan `scheduleRateLimited()` — dicatat di `tools/bekas-hapusan.txt`.

## [1.6.353] - 2026-10-18
### Changed
- `SendMessageWorker.adoptMigratedChat()` tak lagi salinan keempat dari logika yang sama — kini memanggil `NetworkUtils.adoptMigratedChat()` (satu-satunya sumber yang sudah dipakai layanan pemantau dan penerus notifikasi), dan teks pengumuman grup ditingkatkan dipakai dari `NetworkUtils.MIGRATED_CHAT_NOTICE` yang tadinya disalin ulang di sana.
- `NotificationForwarderService`: dua gerbang konfigurasi yang identik (batas cache 10 detik di `forwardingAllowed()` dan di `handlePosted()`) disatukan ke `ensureCfgCache()`/`refreshCfgCache()`.
- `Liveness.intervalText()` tak lagi menyalin ulang tiga cabang `intervalMs()` beserta angka "3/15/10 mnt" yang ditulis manual — teksnya kini diturunkan dari nilai yang sama, jadi tak bisa lagi menyimpang dari konstanta sebenarnya. Bunyi pesannya tetap sama.
- `MonitoringService`: URL `getUpdates` yang ditulis dua kali identik (poll biasa + ulang setelah token disegarkan) kini dibuat lewat `updatesUrl()`; `selfConflict` dan `quietRestart` di blok `409` yang ekspresinya sama persis kini satu nilai.
### Fixed
- `/jejak` tak lagi memotong jejak 24 jam di 24 titik tanpa alasan — pemotongan itu membuat jumlah titik di judul menyesatkan. Kini semua titik dalam 24 jam (maks 300 sesuai batas penyimpanan) dihitung, sedangkan 10 terakhir tetap yang ditampilkan; judulnya berbunyi "👣 Jejak (N titik tersimpan, M terakhir)" dan daftar 10 terakhir dihitung sekali (dulu dua kali).

## [1.6.352] - 2026-10-18
### Changed
- `formatSmsMessage()` tak lagi memicu satu kueri per nomor. Bila nomor pengirim tak ada di peta frekuensi 24 jam, tiap nomor dulu memanggil `todayCountFor()` yang memindai ulang 200 SMS + 200 panggilan; kini seluruh nomor di pesan itu dipindai sekali lewat `freqForNumbers()` dengan kunci 10 digit terakhir yang sama seperti peta utama (`batchFreqMap()`). Sekitar 4000 pembacaan baris untuk 10 SMS tanpa nomor yang cocok jadi 400.
- `NotificationForwarderService`: daftar kata kunci pantau diparsing sekali per nilai (`watchKeywordList()`) tanpa `Pattern`/regex. Sebelumnya string yang sama dipecah, di-trim, dan di-lowercase ulang untuk setiap notifikasi — bahkan dua kali per notifikasi (`isPriorityNotif()` lalu `buildNotifMessage()`).
- Workflow `Build APK`: direktori Gradle 8.7 disimpan di cache `actions/cache` (kunci tetap `gradle-8.7-bin`), jadi distribusi ±130 MB itu tak lagi diunduh dan diekstrak setiap run. Unduhan hanya terjadi saat cache kosong.
### Removed
- `todayCountFor()`, digantikan `freqForNumbers()`; dicatat di `tools/bekas-hapusan.txt`.

## [1.6.351] - 2026-10-18
### Fixed
- Audio dan video yang ditolak Telegram dengan kode selain `400`/`413` kini melaporkan kegagalannya seperti foto — "⚠️ Unggah audio/video gagal (HTTP …). Akan dicoba otomatis." Sebelumnya kedua jalur itu diam saja: berkas terus dicoba berulang tanpa pemberitahuan apa pun. Kegagalan tak terduga saat mengirim audio juga kini tercatat di log (dulu hanya foto dan video yang dicatat).
### Changed
- Tiga jalur kirim media (`sendPhotoFile()`, `sendAudioFile()`, `sendVideoFile()`) kini berbagi satu penilai penolakan (`classifyMediaRejection()`) dan satu pengatur jeda `429` (`mediaRateLimitBackoff()`) — sebelumnya tiga salinan hampir identik yang sudah saling menyimpang (mis. audio membaca `errorBody()` dua kali, penandaan `401`/`403` dan `400` berbeda urutan log). Sekitar 120 baris terpangkas.
- `notifyPhotoSendFailure()` digeneralisasi jadi `notifyMediaSendFailure(jenis, detail)`; pesan untuk foto tetap sama.
- `CameraService`: `getCameraCharacteristics()` tak lagi dipanggil 3-6 kali per sesi potret/rekam — hasilnya di-cache per ID kamera lewat `characteristicsOf()` (nilai ini tak berubah selama aplikasi hidup).
- `CameraService`: pembongkaran perangkat kamera disatukan di `teardownDevice()` — dipakai jalur sukses foto, `cleanup()`, dan lewat itu `forceReset()`; sebelumnya daftar yang sama disalin di dua tempat. Pembersihan callback yang ditulis tiga kali berulang di lima tempat kini memakai `removeEverywhere()`.
### Removed
- Metode `notifyPhotoSendFailure()` (digantikan `notifyMediaSendFailure()`), dicatat di `tools/bekas-hapusan.txt`.

## [1.6.350] - 2026-10-18
### Fixed
- Di Android 11+, permintaan izin "Lokasi sepanjang waktu" dari halaman Setup tak lagi dijalankan lewat dialog izin biasa yang diabaikan sistem. Dua jalur yang masih memakainya (tombol "Berikan semua izin" dan tombol mulai monitoring) kini diarahkan ke halaman Setelan aplikasi seperti jalur yang sudah benar di berkas yang sama, jadi pengguna tidak lagi berputar-putar tanpa bisa memberi izin. Kedua jalur itu disatukan ke satu fungsi `requestBackgroundLocation()`.
- Nama kanal notifikasi tak lagi bocorkan penyamaran kalkulator. Sebelumnya kanal tampil sebagai "Monitoring" dan "Monitoring Alerts" berbahasa Inggris di Setelan > Notifikasi, padahal aplikasinya bernama "Kalkulator" dan seluruh teks lain sudah ikut bahasa perangkat. Kini memakai string `channel_name`, `channel_name_alerts`, dan `channel_desc` (Inggris + Indonesia).
- Judul notifikasi cadangan `BootRestartWorker` tak lagi "Monitoring"; mengikuti `notification_title` yang sama dengan notifikasi utama.

## [1.6.349] - 2026-10-18
### Changed
- `NotificationForwarderService`: pipa `pkg` yang mati di jalur batch notifikasi dibuang seluruhnya. Nilainya hanya disimpan lalu diteruskan ke `forwardLocked()` yang memang tak menerimanya, jadi `batchBuf` kini berisi `String` biasa (dulu `Pair<String, String>`) dan sisa `curPkg`/`leftover` yang mengikutinya ikut hilang.
- `SendMessageWorker`: syarat `!credsChanged` dibuang dari dua pemeriksaan karena selalu bernilai benar — `credsChanged` sudah `return Result.success()` lebih dulu.
- `MonitoringService`: `foregroundTypes` jadi `val` (tak pernah ditulis ulang) dan `baseForegroundType()` dipanggil sekali di jalur cadangan `startForeground()`. Pembungkus `try/catch` di sekitar penugasan `commandsRegFailAt` (tiga tempat) dan `commandsTokenHash` dibuang karena penugasan biasa tak bisa melempar.
- `PreferencesManager.putCredentialState()`: kondisi kembar disatukan — `changed` yang sebenarnya `tokenChanged || chatChanged` dulu dihitung terpisah lalu dibandingkan lagi dengan urutan terbalik.
- `BootReceiver`: penulisan `last_handle_elapsed` ganda dihapus; `markHandled()` sudah menulis nilai yang sama di kedua jalur yang menambahkannya lagi.
- `MainActivity`: pemakaian `BuildConfig` disamakan (15 tempat masih memakai nama panjang padahal sudah diimpor), dan log `"DEBUG mode"` yang tak berisi apa-apa dibuang.
- `README.md`: daftar isi `receiver/` diperbaiki menjadi `boot, admin, liveness, time-change, package-change` — dulu tertulis `network`, padahal receiver itu tak pernah ada.
### Fixed
- `NotificationForwarderService`: `dropRestored` yang selalu `false` saat dibaca dibuang; sisa penghitung "notifikasi dibuang" yang tersimpan di preferensi kini langsung dinolkan begitu dibaca, sehingga restart layanan dalam 30 detik tak menghitungnya dua kali.
- Indentasi sisa refactor dirapikan: badan `else` "SMS belum terkonfirmasi" (menjorok 8 spasi), cabang `413` di `sendAudioFile()` beserta `return` di bawahnya, dan baris `if (...) {                try {` yang menyatu di `registerBotCommands()`.
### Removed
- Entri `run` (tanpa nama kelas) di `tools/bekas-hapusan.txt` — entri itu akan menggagalkan push untuk baris baru mana pun yang memanggil sesuatu bernama `run(`, padahal aslinya cuma `Background.run` yang sudah tercatat lebih spesifik.
- Parameter mati di `tools/verifikasi-path.py`: `basis_desc` pada `cek_xml()` dan `berubah` pada `cek_pola_berisiko()`.

## [1.6.348] - 2026-10-18
### Fixed
- Penjaga lima loop layar utama (`monitoring`, kamera, patroli, polling perintah, watchdog loop) tak lagi bisa menilai dirinya sebelum penugasannya selesai. Sebelumnya kondisi `while` membaca field job yang baru ditulis sesudah `launch`; karena scope loop memakai `Dispatchers.IO`, badan korutin bisa berjalan di thread lain lebih dulu, melihat nilai lama/kosong, lalu keluar tanpa jejak — seluruh pemantauan berhenti senyap sampai watchdog. Tiap loop kini menyimpan job-nya sendiri (`val self = coroutineContext[Job]`).
- Jejak lokasi 24 jam kembali ditulis di dalam kunci `prefsRmwLock`. Sejak jejak dipusatkan ke `recordSentLocation()` (v1.6.345), penulisan berada di luar kunci sehingga dua pengirim lokasi bersamaan (patroli vs perintah) bisa saling menimpa: satu titik jejak hilang dan "lokasi terakhir" jadi milik penulis terakhir.
- Watchdog pemotretan kamera yang gagal tak lagi membatalkan watchdog milik pemotretan berikutnya. `cameraBusy` dilepas lebih dulu, jadi pemotretan baru bisa masuk dan menugaskan `watchdogJob` baru; `catch` lama membatalkannya lewat field bersama. Kini yang dibatalkan job sesi itu sendiri.
- Teardown kamera (`CameraService`) diikat ke sesinya. Callback basi dari sesi yang sudah di-reset paksa (watchdog 45 detik timeout) tak lagi menutup kamera/thread latar milik sesi baru — baik di jalur sukses foto, jalur gagal foto, maupun jalur gagal video. Saat sesi sudah tak berlaku, pembersihan perangkat dilewati karena `forceReset()` sudah melakukannya.
- Penghitung "pesan dibuang" tak lagi hilang saat antrean pulih dari mode volatil. `loadDropCountsLocked()` dulu menimpa penghitung di memori dengan nilai dari disk, padahal selama mode volatil (keystore gagal sesaat) drop dihitung di memori tanpa ditulis. Kini nilainya digabung, bukan diganti.
- Pemangkasan antrean saat pemulihan dari mode volatil memakai aturan yang sama dengan jalur normal: pesan prioritas dipertahankan bila masih ada pesan biasa, dan pem- buangan pesan prioritas ikut tercatat di penghitung `priorityDrops`.
- Pengingat kredensial lewat notifikasi tak lagi menghabiskan jatah 6 jamnya saat izin `POST_NOTIFICATIONS` belum ada (notifikasi tak akan tampil, jadi percobaan itu tak dihitung).
- Pesan gagal lokasi kini juga mencoba perbaikan terakhir (sampai 24 jam) ketika seluruh provider (`GPS`/jaringan) sama-sama dilaporkan nonaktif, bukan langsung menyerah.
- Log pesan yang ditolak permanen oleh Telegram pada jalur cadangan (HTML dilepas) tak lagi selalu tertulis "(400)". Kode aslinya ikut dicatat, dan laporan ke pemilik berbunyi "(permanen)" karena kodenya bisa 404/409/413 — bukan hanya 400.
### Changed
- `MessageQueue.flushSync()` hanya menulis penghitung pesan terbuang bila memang berubah (dulu selalu menulis tiga nilai ke penyimpanan terenkripsi setiap kali dipanggil).

## [1.6.347] - 2026-10-18
### Fixed
- Perintah `/location` (juga `/darurat`, `/hilang`, dan peringatan percobaan mematikan Device Admin) tak lagi gagal total hanya karena HP tak kunjung mendapat perbaikan GPS baru dalam batas tunggu dan tak ada perbaikan tersimpan yang masih segar (≤2 menit). Bila perbaikan langsung gagal, kini dipakai perbaikan terakhir dari provider GPS/jaringan/pasif sampai umur 24 jam, disertai keterangan "⚠️ Data lama: N menit/jam lalu". Titik lama itu tidak dicatat ke jejak 24 jam dan tidak dipakai cek geofence, supaya tak memicu alarm palsu.
- Pesan gagal lokasi tak lagi menuduh "Nyalakan Lokasi/GPS" padahal GPS menyala. Pesannya kini menyebut sebab yang benar-benar terdeteksi: "Lokasi/GPS di HP sedang mati", izin lokasi hanya "Perkiraan" (bukan "Presisi"), izin "Lokasi sepanjang waktu" belum ada, atau "GPS belum dapat perbaikan — coba lagi di tempat terbuka".
### Changed
- Saat izin lokasi hanya "Perkiraan" (tanpa `ACCESS_FINE_LOCATION`), permintaan lokasi tidak lagi memaksa provider GPS yang pasti ditolak sistem; provider jaringan saja yang dipakai.
- Batas tunggu perbaikan GPS baru dinaikkan dari 15 ke 20 detik.
- `/status` menampilkan baris "Lokasi presisi" untuk membedakan izin "Presisi" dari "Perkiraan".
### Removed
- `isFreshLocation()` — mati setelah pembacaan cache lokasi dipusatkan di `lastKnownFix()`; namanya dicatat di `tools/bekas-hapusan.txt`.

## [1.6.346] - 2026-10-18
### Changed
- Logika "chat pindah ke supergrup" disatukan jadi `NetworkUtils.adoptMigratedChat()`; sebelumnya isinya (±30 baris) diduplikasi hampir persis di `MonitoringService` dan `NotificationForwarderService`, rawan berbeda diam-diam saat salah satu diperbaiki. Teks pemberitahuannya kini bersumber tunggal di `NetworkUtils.MIGRATED_CHAT_NOTICE`.
- `NotificationForwarderService.emitPost()` tak lagi menerima parameter `notifySpam` yang selalu bernilai `true` di semua pemanggil.
- `forwardLocked()` tak lagi mengembalikan `Boolean` yang diabaikan seluruh pemanggilnya.
- Cabang "antrean penuh" di `onNotificationPosted()` disederhanakan: pemeriksaan ganda yang membuat blok `scope.launch` di dalamnya praktis tak pernah tereksekusi (kini hanya mungkin lewat balapan jarang) dibuang; notifikasi saat antrean penuh selalu lewat `recordOverflow`. Fungsi `historyAllowed()` yang tinggal dipakai blok itu ikut dihapus.
### Fixed
- `README.md`: perintah rekam audio tertulis `/recordaudio`, padahal perintah aslinya `/record` (`/recordaudio` tak dikenal bot).

## [1.6.345] - 2026-10-18
### Changed
- Foto kamera tak lagi di-decode empat kali. `photoLuma()` dan `photoHash()` (masing-masing membaca dimensi + men-decode bitmap) disatukan jadi `photoStats()` yang men-decode sekali lalu mengembalikan (kecerahan, sidik) sekaligus — dipakai untuk cek gelap dan cek duplikat pada berkas yang sama.
- Jejak lokasi 24 jam ditulis ke penyimpanan sekali saja per titik: `lastLocLatE6`/`lastLocLonE6`/`lastLocAt`/`trail24` kini satu transaksi lewat `PreferencesManager.recordSentLocation()` (dulu empat tulis terpisah ke preferensi terenkripsi), dan pemangkasan ke 300 titik memakai salinan `subList` sekali jalan (dulu `removeAt(0)` berulang).

## [1.6.344] - 2026-10-18
### Fixed
- Rekaman video kini ditulis ke berkas sementara `video_*.mp4.tmp` lalu diganti nama menjadi `video_*.mp4` hanya setelah perekaman berhenti dengan benar. Sebelumnya `MediaRecorder` menulis langsung ke berkas akhir, sehingga rekaman yang terpotong (proses aplikasi mati/force-stop di tengah rekaman) meninggalkan berkas `.mp4` yang tetap dianggap sah dan ikut diunggah antrean video. Berkas sementara sudah disapu otomatis saat `CameraService` dibuat.
- `CrashReporter` tak lagi mengantre ulang laporan yang barusan terkirim: bila ada laporan baru masuk selagi pengiriman berlangsung, yang disimpan hanya bagian baru (ditambah sisa yang belum terkirim), bukan seluruh isi berkas termasuk laporan lama yang sudah dikirim.

## [1.6.343] - 2026-10-18
### Fixed
- Kode `403` Telegram tak lagi diperlakukan sama dengan `401`. `403` berarti bot diblokir/dikeluarkan dari chat (token tetap benar), jadi pesannya kini jujur: notifikasi menjadi "Bot diblokir/dikeluarkan dari chat (403)", dan `/flush` berbunyi "bot diblokir atau dikeluarkan dari chat — buka Telegram lalu buka blokir/undang ulang bot". Sebelumnya keduanya menyuruh "Perbaiki token di Setup", menyesatkan karena tokennya memang benar.
- Saat hanya `403` yang terjadi, polling perintah tak lagi ditahan 30 menit (`NetworkUtils.isPollBlocked` kini khusus `401`), sehingga setelah bot dibuka blokirnya perintah langsung terbaca lagi. Pengiriman tetap mundur 30 menit (backoff) seperti semula.
- Notifikasi peringatan kredensial memakai ID `5` (dulu `4`) supaya tak bentrok dengan peringatan "penyimpanan aman tak tersedia" dari `BootRestartWorker` yang memakai ID `4` — dulu keduanya saling menimpa.

## [1.6.342] - 2026-10-18
### Changed
- Notifikasi foreground layanan disatukan: tiga tempat (`startForegroundImmediate`, `startMonitoring`, dan `ensureForegroundTypes`) membangun builder notifikasi yang nyaris kembar; kini semuanya memakai satu helper `buildForegroundNotification()`. Perilaku tak berubah — kanal tetap senyap (`setSilent`), dan `setContentIntent` tetap aman bila `setupTapIntent()` gagal.
### Removed
- Penanda preferensi `initial_sync_started` beserta properti `initialSyncStarted` dan `setInitialSyncStartedSync()`: nilainya ditulis sekali lalu hanya dipakai untuk memutuskan menulis dirinya sendiri, sedangkan gerbang sinkronisasi awal selama ini hanya memakai `initial_sync_done`. Nama fungsinya dicatat di `tools/bekas-hapusan.txt`.

## [1.6.341] - 2026-10-18
### Removed
- Parameter `pkg` pada `NotificationForwarderService.forwardLocked()` — tak pernah dipakai di badan fungsi (yang dipakai hanya `msgCount`); keempat pemanggil disesuaikan.
- Parameter `notifId` pada `NotificationForwarderService.handlePosted()` beserta variabel lokal `val notifId = sbn.id` — nilainya tak pernah dibaca.
- `putExtra("of", parts.size)` pada `PendingIntent` SMS bertahap — hanya `"part"` yang dibaca penerima, `"of"` tak pernah dipakai.
- Instal paket CI `platforms;android-34` dan `build-tools;34.0.0` — proyek memakai `compileSdk`/`targetSdk` 35, jadi keduanya tak terpakai dan hanya memperlambat unduhan.
### Changed
- `app/src/test/` tak lagi diabaikan `.gitignore`: `app/build.gradle` sudah lama mendeklarasikan `testImplementation` (JUnit dll.), tetapi direktori ujinya diabaikan sehingga penjaga "cakupan uji" di `tools/verifikasi-path.py` tak pernah bisa berjalan. Kini berkas uji bisa dilacak dan penjaga aktif begitu uji ada.
- Guard token di `scripts/cek-cepat.sh` dipersempit pengecualiannya: dulu **semua** baris yang memuat kata `Regex`/`Pattern` dilewati sehingga token asli di baris semacam itu lolos tanpa diperiksa; kini hanya definisi pola (`Regex(` / `Pattern.compile`) yang dikecualikan.

## [1.6.340] - 2026-10-18
### Fixed
- Perintah darurat `/hilang` (dan `/ring`) tak lagi diam-diam melewati dering saat perekaman sedang **diunggah**: pemeriksaan "sedang merekam" kini memakai penanda `recorderActive` yang menyala hanya selama perekaman benar-benar berjalan (audio: `recorder.start()` sampai `recorder.stop()`; video: selama `captureVideo` berjalan), bukan `recordBusy` yang tetap ditahan sampai unggahan selesai (bisa menit-an di koneksi lambat). Gating rekaman baru tak berubah: `/record` dan `/recordvideo` tetap ditolak selama unggahan berlangsung.
- Ganti token/chat ID lewat Setup kini ikut me-reset kursor riwayat SMS (`last_sms_id`) dan panggilan (`last_call_timestamp`/`last_call_id`), selaras dengan reset penuh di `SetupActivity`. Sebelumnya hanya `initial_sync_done` yang dihapus, sehingga sinkronisasi awal berjalan ulang dari kursor lama dan mengirim pesan keliru "Belum ada riwayat SMS/panggilan di HP ini" ke chat baru padahal riwayatnya penuh.

## [1.6.339] - 2026-10-18
### Fixed
- Loop kamera tak lagi me-restart dirinya sendiri tanpa henti saat kamera diblokir kebijakan perangkat (device admin). Cabang jeda menulis `photoPausedUntil` dengan stempel waktu baru setiap 2 menit, dan tulis itu memicu listener preferensi yang memanggil `restartCameraLoop()` — job kamera dibatalkan tepat saat sedang menunggu, lalu iterasi berikutnya menulis lagi. Kini nilai ditulis sekali saja saat jeda dimulai; selama masih terjeda cache disinkronkan tanpa menulis preferensi, sehingga badai cancel/launch, tulis SharedPreferences, dan panggilan binder `DevicePolicyManager` berhenti.

## [1.6.338] - 2026-10-17
### Fixed
- Ikon bulat `@mipmap/ic_launcher_round` juga mendapat cadangan untuk Android 7.0–7.1: rilis sebelumnya hanya `@mipmap/ic_launcher` yang diberi padanan non-v26, sedangkan `android:roundIcon` tetap menunjuk berkas yang hanya ada di `mipmap-anydpi-v26`. Kini ada `mipmap-anydpi/ic_launcher_round.xml` dengan isi sama.
### Added
- Guard permanen di `tools/verifikasi-path.py` (`cek_ikon_launcher()`): bila `minSdk < 26` dan ada berkas di `res/mipmap-anydpi-v26/`, setiap nama wajib punya padanan di folder `mipmap` tanpa kualifikasi versi — supaya ikon launcher tak lagi hilang diam-diam di Android 7.0–7.1 saat ikon adaptive ditambah atau diganti.

## [1.6.337] - 2026-10-17
### Fixed
- Ikon peluncur untuk Android 7.0–7.1 (API 24–25): `android:icon` menunjuk `@mipmap/ic_launcher`, tetapi satu-satunya berkasnya ada di `mipmap-anydpi-v26` (adaptive icon yang hanya dipakai API 26+), sehingga di API 24–25 ikon aplikasi tak punya gambar yang cocok. Ditambahkan `mipmap-anydpi/ic_launcher.xml` (layer-list: `@color/ic_launcher_background` + `@drawable/ic_launcher_foreground`) sebagai cadangan; di API 26+ adaptive icon tetap yang dipakai.
### Changed
- `calc_btn_accent.xml` kini merujuk `@color/calc_ripple` dan `@color/calc_btn_function` seperti tombol kalkulator lainnya, bukan menulis ulang nilai `#40FFFFFF`/`#FFFF9F0A` — supaya perubahan tema warna tidak meninggalkan satu tombol yang berbeda.
- `values/strings.xml` diberi deklarasi `<?xml version="1.0" encoding="utf-8"?>` agar seragam dengan berkas resource lain.
### Added
- Workflow `Build APK` kini benar-benar berjalan saat ada PR ke `main` seperti yang sudah tertulis di `AGENTS.md`/`README.md`: ditambahkan pemicu `pull_request` (dengan `paths-ignore` yang sama) dan rentang verifikasi memakai `${{ github.event.pull_request.base.sha || github.event.before || '' }}` karena `github.event.before` kosong pada event PR.

## [1.6.336] - 2026-10-17
### Changed
- `NotificationForwarderService`: pembacaan judul/isi notifikasi (TITLE → TEXT → BIG_TEXT → TEXT_LINES → SUB_TEXT) yang sebelumnya ditulis tiga kali hampir identik — jalur "antrean sudah penuh", jalur "baru saja penuh", dan `handlePosted()` — kini disatukan dalam satu helper `notifTitleText()`. Efek samping kecil: jalur "baru saja penuh" ikut membaca `EXTRA_SUB_TEXT` seperti dua jalur lain, sehingga notifikasi yang hanya mengisi sub-teks tidak lagi dianggap kosong di jalur yang jarang terpakai itu.

## [1.6.335] - 2026-10-17
### Removed
- `MessageQueue.removeMessage(id)` — tak punya satu pun pemanggil (yang dipakai `removeMessages(daftar)`); namanya dicatat di `tools/bekas-hapusan.txt`.
- Kunci preferensi `last_send_ok_at` (`KEY_LAST_SEND_OK_AT`) — nilainya ditulis setiap `setLastSendOkMarker()` dipanggil tetapi tak pernah dibaca; parameter `wallAt` yang jadi mubazir ikut hilang sehingga signature-nya kini `setLastSendOkMarker(elapsedAt)`.
- Parameter `streamKey` pada `NotificationForwarderService.emitPost()` — tak pernah dipakai di badan fungsi.
- Blok reset `ringBusy`, `recordBusy`, `smsBusy`, `cameraBusy` di `MonitoringService.onCreate()` beserta komentarnya: keempatnya `AtomicBoolean` yang selalu `false` pada instans baru, jadi blok itu tak pernah mengubah apa pun.
### Changed
- `chunkedDelay()` di `MonitoringService` diganti nama menjadi `delayMs()`: isinya memang `delay()` biasa tanpa pemotongan, sehingga nama lamanya menyesatkan sekarang ada `pauseAwareDelay()` di sebelahnya.
- Notifikasi foreground layanan kini selalu senyap (`setShowWhen(false)`, tanpa suara/getaran): dulu ada cabang DEBUG/RELEASE yang isinya sama tetapi berkomentar "DEBUG: tampilkan detail", sehingga `setShowWhen` dan silent hanya berlaku di build rilis.
- `CameraService` menutup `CaptureSession`, `CameraDevice`, dan `ImageReader` satu per satu dengan penjagaan sendiri: dulu tiga `close()` tanpa penjagaan sehingga satu lemparan dapat menggagalkan sisa pembersihan (`stopBackgroundThread()` dan `finishWithPhoto()` tak jalan → penanda `cameraBusy` tersangkut sampai watchdog 45 detik).
- `TimeFmt`: elvis mati pada `ThreadLocal` (`fullHolder.get() ?: SimpleDateFormat(...)` dan dua saudaranya) dihapus — `initialValue()` selalu mengembalikan nilai, jadi cabang `?:` itu tak pernah terpakai.

## [1.6.334] - 2026-10-17
### Fixed
- Kredensial hilang karena penyimpanan aman (keystore) tak tersedia kini berkabar jelas: bila `EncryptedSharedPreferences` gagal dibuka, `PreferencesManager` diam-diam beralih ke penyimpanan memori, sehingga token/chat kosong, `isConfigured()` menjadi `false`, dan pemantauan berhenti tanpa kabar — pesan "mode cadangan" ke Telegram pun tak bisa terkirim justru karena kredensialnya kosong. Kini `BootRestartWorker` (watchdog 15 menit) menampilkan notifikasi lokal khusus "Penyimpanan aman tak tersedia — kredensial tak bisa disimpan, jadi pemantauan berhenti; ketuk untuk membuka Setup dan simpan ulang" (`storage_fallback_title`/`storage_fallback_text`, Inggris + Indonesia) di kanal yang sama dengan pengingat "Layanan dijeda".
### Changed
- Pengingat Setup dari `BootRestartWorker` dirapikan: `postResumeReminder()` dan pengingat baru itu berbagi satu helper `postSetupReminder()` (judul, teks, penanda, dan id notifikasi sebagai parameter), dengan pembatas 3 jam per jenis pengingat — dulu satu variabel statis bersama yang bisa saling menutup.

## [1.6.333] - 2026-10-17
### Fixed
- Perubahan `retryCount` dan pembuangan pesan di antrean kini langsung ditulis ke penyimpanan: `MessageQueue.registerWithBudget()` memakai `persistLocked()` tanpa `force`, sehingga bila penulisan terakhir terjadi kurang dari 2 detik sebelumnya perubahan itu hanya ditandai `persistDirty` dan menunggu `flushSync()`. Di `SendMessageWorker` pemanggilan `flushSync()` justru terjadi SEBELUM `registerTransientFailures()`, jadi bila proses dibunuh setelahnya (umum di perangkat hemat baterai) kenaikan percobaan dan pembuangan pesan yang sudah mentok hilang — pesan yang semestinya dibuang kembali ke antrean dan menunggu siklus berikutnya. Penulisan pada jalur ini kini dipaksa (`force = true`).

## [1.6.332] - 2026-10-17
### Fixed
- Penanda "jalur kirim hidup" kini juga disegarkan kiriman media: sebelumnya hanya kiriman teks yang memanggil `noteSendOk()` (`MonitoringService` dan `SendMessageWorker`), sehingga saat foto/video/rekaman berhasil diunggah penanda `last_send_ok` tetap tua dan `checkSilentLink()` bisa menuduh "jalur kirim tampak bisu" — laporan palsu terkirim plus pemulihan bertahap dijalankan (putar ulang antrean, hapus webhook, hidupkan ulang semua loop) padahal jalur sehat. Kini `sendPhotoFile()`, `sendVideoFile()`, dan `sendAudioFile()` juga memanggil `noteSendOk()` saat berhasil.
- Tingkat pemulihan `checkSilentLink()` hanya dinaikkan setelah upaya pemulihannya benar-benar dijalankan: dulu `silentHealStage` dan `silentHeals` naik SEBELUM `healSilentLink()` dipanggil, jadi bila upaya itu gagal (mis. `registerBotCommands()` melempar) satu tahap habis tanpa pernah dikerjakan dan laporan "pemulihan tahap N sudah saya jalankan" menjadi keliru. Bila upaya gagal, laporan tidak dikirim dan tingkat tidak naik (jarak antar-upaya tetap dijaga `silentHealAt`).

## [1.6.331] - 2026-10-17
### Fixed
- `/start <chatId> <kode>` tidak lagi menjawab "Pemasangan diabaikan: pemilik sudah tertaut." saat sebenarnya belum ada pemilik: syaratnya dulu `senderId != cachedOwnerId.toString()`, dan `cachedOwnerId` masih `0` (pemasangan sedang dikunci karena terlalu banyak percobaan salah) sehingga pembandingnya `"0"` yang tak akan pernah sama dengan id Telegram mana pun — pesannya menyesatkan dan menutup peluang pemasangan sah. Kini pesan itu hanya dikirim bila benar-benar sudah ada pemilik lain.
- `BootRestartWorker` (watchdog 15 menit) memeriksa dulu apakah `MonitoringService` sudah berjalan sebelum memanggil `setForeground()`: dulu notifikasi foreground dinyalakan setiap 15 menit hanya untuk dibatalkan beberapa milidetik kemudian ketika layanan ternyata sudah jalan — selain berkedip di bar notifikasi, pola itu juga menghabiskan kuota peluncuran foreground service yang dibatasi sistem sejak Android 12.

## [1.6.330] - 2026-10-17
### Removed
- Sisa fitur "wake ping" yang sudah mati: penulis daftar id-nya (`addWakePingIds`, `setWakeUpdateIdSync`) sudah dibuang lebih dulu, tetapi pembacanya masih tertinggal — `wakePingSeen()`, `removeWakePingId()`, `clearWakePingIds()`, `readWakePingIds()`, `writeWakePingIds()`, kunci `KEY_WAKE_PING_IDS` di `PreferencesManager`, pemanggilannya di `SetupActivity`, serta parameter `wakeSeen` yang diteruskan ke `handleTelegramCommand()`/`handleTelegramCommandInner()` dan syarat `!wakeSeen` pada `/ping`. Karena daftar itu tak pernah terisi, `wakeSeen` selalu `false` sehingga kode tersebut hanya menyesatkan (tampak seolah ada penanda ping yang membangunkan aplikasi). Tidak ada perubahan perilaku — `/ping` memang selalu boleh menjalankan ulang loop dengan pembatas `pingRestartDue()`. Nama metode yang dihapus dicatat di `tools/bekas-hapusan.txt`.

## [1.6.329] - 2026-10-17
### Fixed
- `/hematdata on` kini benar-benar menahan foto otomatis: sebelumnya hanya media yang sudah masuk antrean (`flushPendingPhotos()`, `flushPendingVideos()`, `flushPendingAudio()`) yang ditahan, sedangkan foto hasil pemotretan otomatis (interval kamera & patroli) tetap dikirim langsung lewat `sendPhotoFile()` walau sedang memakai data seluler — bertentangan dengan janji di `README.md`. Kini foto otomatis juga ditahan; berkasnya tetap tersimpan di cache dan ikut terkirim otomatis begitu tersambung Wi-Fi. Sidik foto ikut dicatat saat ditahan agar pemotretan berikutnya yang sama tetap terdeteksi duplikat dan tidak menumpuk di antrean.
### Changed
- Cakupan `/hematdata` diperjelas di `README.md`: foto otomatis dan foto patroli ikut ditahan, sedangkan permintaan langsung seperti `/photo`, `/screenshot`, `/recordvideo`, `/recordaudio` tetap dikirim segera supaya jawaban perintah tidak terasa "hilang".

## [1.6.328] - 2026-10-17
### Fixed
- Berkas media yang sedang dipakai tak lagi saling menimpa penandanya: `MonitoringService` dulu menyimpan satu slot per jenis (`activePhotoFile`, `activeVideoFile`, `activeShotFile`, `activeAudioFile`), jadi saat pengiriman satu berkas masih berjalan sementara berkas lain dengan jenis sama mulai diproses, penanda yang lama tertimpa dan berkas lama bisa ikut terkirim dua kali oleh `flushPendingPhotos()`, atau ikut terbuang oleh pembersihan cache. Penanda kini disimpan dalam satu himpunan berkas aktif (`activeMediaFiles`) yang sanggup menampung banyak berkas sekaligus.
- Video yang sedang direkam kini dihitung aktif sejak `MediaRecorder` mulai menulis, bukan baru setelah rekaman selesai: `CameraService.currentVideoFile()` dipakai pengecekan aktif di `MonitoringService`, sehingga berkas video setengah jadi tidak mungkin keburu dikirim atau dihapus pembersihan cache.

## [1.6.327] - 2026-10-17
### Fixed
- `/stop` dan `/resume` kini langsung berlaku: sebelumnya jeda hanya memperbarui konfigurasi (`refreshLoopConfig()`) sementara loop data sudah tertidur di `delay()` sampai siklus sinkronisasi habis — dengan `/syncinterval 1440` efeknya baru terasa sampai 24 jam kemudian, jadi panggilan/SMS/foto masih terkumpul (atau masih tertahan) jauh setelah perintah dijawab. Loop data sekarang menunggu dalam potongan 30 detik dan langsung menyadari perubahan status jeda, dua arah.

## [1.6.326] - 2026-10-17
### Fixed
- Riwayat notifikasi tidak lagi terisi saat pemantauan dijeda: `NotificationForwarderService` masih memanggil `record(...)` untuk notifikasi yang masuk saat `monitoringPaused` (atau saat pemantauan belum aktif), padahal jalur lain di berkas yang sama memakai penjaga `historyAllowed()` yang menolak jeda. Akibatnya judul dan isi notifikasi (mis. kode OTP/bank) masa jeda masih bisa dibaca lewat `/lastnotif` walau `/stop` menjanjikan pemantauan berhenti. Kini saat pemantauan tidak aktif/jeda, notifikasi tak dicatat sama sekali.
- Izin tangkap layar tidak lagi dicabut karena kegagalan sesaat: bila penyesuaian ukuran sesi (`VirtualDisplay.resize()`/`setSurface()`) gagal, `/screenshot` berikutnya masih memakai sesi yang sama (dulu `releaseShotSession()` langsung dijalankan sehingga izin hilang dan status Setup berubah `off` tanpa sebab nyata). Sebaliknya, kegagalan saat membuat `VirtualDisplay` pada token baru — termasuk error non-`SecurityException` — kini selalu melepas sesi + izin, supaya permintaan berikutnya meminta izin segar alih-alih mengulang `createVirtualDisplay` kedua pada token yang sama (dilarang sejak Android 14).

## [1.6.325] - 2026-10-17
### Fixed
- `MY_PACKAGE_REPLACED` tak pernah sampai ke `BootReceiver`: aksi itu dulu berada dalam satu `<intent-filter>` bersama `<data android:scheme="package"/>` (sejak `v1.6.190`), padahal broadcast itu tidak membawa URI/MIME sehingga seluruh filter gagal uji data dan `onReceive()` tak pernah dipanggil. Akibatnya tiap aplikasi selesai di-update, pemantauan tidak dihidupkan tepat waktu — hanya menunggu alarm kelangsungan hidup (3–15 menit) atau reboot berikutnya. Filter kini dipisah: `MY_PACKAGE_REPLACED` berdiri sendiri, `PACKAGE_RESTARTED` tetap bersama `<data>`.
### Added
- Penjaga baru di `tools/verifikasi-path.py`: gagal bila ada `<intent-filter>` yang mencampur broadcast tanpa URI/MIME (`MY_PACKAGE_REPLACED`, `BOOT_COMPLETED`, `USER_PRESENT`, `USER_UNLOCKED`, `QUICKBOOT_POWERON`) dengan `<data>` — regresi jenis ini sudah pernah lolos sekali, sekarang tertangkap sebelum push.

## [1.6.324] - 2026-10-17
### Fixed
- `/screenshot` tidak lagi cuma berhasil sekali per izin: sesi `MediaProjection` (`VirtualDisplay` + `ImageReader`) kini dipertahankan dan dipakai ulang antar pengambilan. Sejak `targetSdk` 34 (Android 14+), `createVirtualDisplay()` hanya boleh dipanggil **sekali** per instance `MediaProjection` dan panggilan kedua melempar `SecurityException`; karena kode rilis sebelumnya melepas `VirtualDisplay`/`ImageReader` tiap pengambilan, pengambilan kedua selalu mencoba membuat `VirtualDisplay` baru di proyeksi yang sama — lemparan itu tertelan di dalam `ensureShotSession()` sehingga `/screenshot` berikutnya selalu dijawab "⚠️ Tangkap layar gagal…" walau izinnya masih hidup dan status Setup tetap `granted`.
### Changed
- Perubahan ukuran/orientasi layar tidak lagi memaksa sesi dibuat ulang (itu juga butuh `createVirtualDisplay()` kedua): `VirtualDisplay` yang hidup di-`resize()` dan `ImageReader` baru dipasang ke surface yang sama. Bila penyesuaian itu gagal, sesi dan izin dilepas agar status Setup jujur dan `/screenshot` meminta izin ulang.
- Sesi tangkap layar hanya dilepas saat sesinya benar-benar berakhir (sistem memanggil `onStop`, izin dicabut/di-`grant` ulang dari Setup, atau layanan mati), bukan tiap pengambilan. Antrean `ImageReader` yang penuh menghentikan penulisan frame saat menganggur, jadi tidak ada penangkapan layar berjalan terus saat tak dipakai.

## [1.6.323] - 2026-10-17
### Security
- PIN backup baru wajib **6 digit** (sebelumnya 4). Berkas backup memuat token bot, dan PIN adalah satu-satunya pelindung isinya; dengan 4 digit angka hanya ada 10.000 kemungkinan sehingga brute-force offline sangat murah—terlebih berkasnya bisa ikut tersalin ke penyimpanan cloud. Pemulihan tetap menerima PIN lama minimal 4 digit, jadi berkas backup yang sudah ada tidak terkunci.

### Changed
- Dialog PIN: PIN backup yang kurang dari 6 digit kini diberi pesan "PIN terlalu pendek", bukan membatalkan tanpa penjelasan. Petunjuk isian juga dibedakan antara isian backup (min 6) dan pemulihan (min 4).

## [1.6.322] - 2026-10-17
### Removed
- Kode mati: cabang penjaga owner yang tak pernah tercapai (perintah mutating/sensitif sudah dikembalikan lebih dulu di atasnya), variabel alias `startHead`/`startArg`, parameter `startId` yang tak dipakai di `handleFgsTimeout`, variabel `errorBody` yang tak dipakai di `sendVideoFile`, pemeriksaan `wm == null` ganda, saringan `!name.endsWith(".sent")` yang tak pernah benar (tidak ada kode yang me-rename ke `.sent`) beserta komentarnya, dan inisialisasi `volatileOnly` yang selalu langsung ditimpa blok `init`.
- Gaya kosong `CalculatorButton.Function` (layout kalkulator kini memakai `CalculatorButton` langsung) dan `drawable-night/calc_btn_accent.xml` yang isinya identik dengan versi harinya.

### Changed
- `chunkedDelay` tidak lagi memotong jeda menjadi langkah 5 detik; `delay` sudah bisa dibatalkan seketika, jadi potongan itu hanya menambah bangun-bangun sia-sia.
- Panggilan `startLoopWatchdog()` ganda di `startMonitoring` dihapus (panggilan tanpa syarat di akhir fungsi sudah mencakupnya; satu-satunya jalan keluar lebih awal hanya saat layanan berhenti).
- Ambang penghitung `idleDataCycles` diturunkan 8 → 3 karena pengali interval sudah mentok di 4 sejak nilai 3 (nilai 4–8 tidak punya efek).
- Baris status di halaman Setup memakai `?android:attr/textColorSecondary` sehingga tetap terbaca saat sistem memakai mode gelap (sebelumnya `darker_gray` di atas latar gelap).
- `calc_display_stroke` tidak lagi sama persis dengan `calc_display_bg`, jadi garis tepi layar kalkulator terlihat.
- `PackageChangeReceiver` dan `AdminReceiver` tidak lagi mengirim laporan saat pemantauan memang sedang dimatikan owner.
- `BootReceiver` tidak lagi menghapus stempel "boot penuh sudah ditangani" saat preferensi gagal dibuka (stempel itu dipakai untuk menahan duplikat boot berikutnya), dan duplikasi penulisan `last_handle_elapsed` yang menulis ulang nilai yang sama dihapus.

## [1.6.321] - 2026-10-17
### Changed
- Daftar nomor diawasi kini dibaca sekali per laporan/per pesan lalu dipakai ulang, bukan diparse ulang untuk setiap SMS dan panggilan. Pada laporan 200 SMS + 200 panggilan, pemotongan/pemfilteran string preferensi itu sebelumnya terjadi ratusan kali dalam satu perulangan.
- Hitungan "berulang hari ini" per nomor di dalam satu laporan SMS kini dihitung sekali per nomor (memo per batch) alih-alih dua kueri riwayat SMS + panggilan untuk setiap pesan dengan nomor yang sama.
- Pemeriksaan jaringan pada pengiriman foto disamakan dengan video/audio (`hasNetwork()` di awal), agar percobaan unggah di kondisi tanpa jaringan tidak menghabiskan kuota percobaan/backoff sebelum benar-benar ada jaringan.
- `BootRestartWorker` tidak lagi mencoba ulang tanpa batas saat `doWork` gagal berulang (maks 5 percobaan seperti jalur lain di worker itu).

## [1.6.320] - 2026-10-17
### Fixed
- Laporan panjang yang dipecah per 4000 karakter tidak lagi kehilangan potongan yang gagal terkirim saat pengiriman dibatalkan di tengah jalan (mis. layanan dihentikan sistem). Sebelumnya hanya potongan *setelah* potongan terakhir yang berhasil yang diselamatkan, sedangkan potongan yang gagal terkirim (berada sebelum indeks terakhir) ikut hilang; kini keduanya masuk antrean tahan-mati.
- Laporan crash tertunda: umur laporan tidak lagi ter-reset setiap kali laporan baru disatukan ke berkas yang sama (stempel waktu tertua dipertahankan), sehingga laporan yang benar-benar tua tetap dibuang sesuai batas 7 hari.
- Penulisan berkas laporan crash tidak lagi menghapus berkas asli lebih dulu sebelum penggantinya berhasil; bila `rename` gagal, isi asli tetap utuh (sebelumnya berkas asli hilang dan sisa data hanya tertinggal di `.tmp` yang tidak pernah dibaca).
- Backup pengaturan: bila blob backup hilang (mis. proses aplikasi dibunuh saat dialog simpan terbuka sehingga callback kembali tanpa blob), kegagalan kini diberitahukan lewat pesan, bukan diam tanpa pesan.
- Pemilih berkas **Restore** kini juga menerima MIME `*/*`, sehingga berkas `.redeyebak` tetap bisa dipilih walau penyimpanan melaporkan MIME selain `application/octet-stream`.

## [1.6.319] - 2026-10-17
### Fixed
- Balapan tulis-baca jejak lokasi: titik `/jejak` (`trail24`) dan penghitung kegagalan lokasi (`locFailCount`) kini dikunci, sehingga sapuan patroli dan permintaan `/location` yang berjalan bersamaan tidak lagi saling menimpa (titik jejak hilang / penghitung tak pernah mencapai ambang peringatan). Keduanya berjalan di `Dispatchers.IO` yang multi-thread.
- Laporan otomatis "monitoring hidup lagi" hanya menandai sudah-terkirim dan memasang cooldown 6 jam setelah pesannya benar-benar masuk antrean; sebelumnya penandanya ditulis lebih dulu, jadi bila antrean gagal laporannya hilang permanen.
- Pengaturan mulai/hentikan monitoring tidak lagi menulis penyimpanan terenkripsi langsung di main thread: `setMonitoringActive` memakai `commitGuarded` (commit di luar main thread, `apply` bila terpaksa di main thread). Setup juga menyalakan status monitoring **sebelum** memulai layanan, sehingga layanan tidak sempat menolak start karena masih membaca status "dimatikan".

## [1.6.318] - 2026-10-17
### Fixed
- Penerusan notifikasi tidak lagi kehilangan notifikasi yang sedang dikirim saat layanannya dihentikan sistem. Sebelumnya `onDestroy` menyelamatkan isi buffer **sebelum** scope dibatalkan, sehingga sisa batch yang sedang menunggu jaringan baru dikembalikan ke buffer setelah penyelamatan berjalan — lalu hilang bersama prosesnya. Kini sisa batch saat pembatalan langsung dipindah ke antrean pesan yang tahan mati (`MessageQueue`) dan dijadwalkan ulang.
- Tombol **Matikan monitoring** di Setup kini selalu benar-benar menghentikan layanan. Sebelumnya `stopService` berada di dalam blok yang menyerah lebih dulu bila activity sudah `isFinishing`/`isDestroyed` (mis. owner menekan Back segera setelah menekan tombol), jadi status tersimpan "mati" tetapi layanan dan loop pemantauan tetap berjalan.

## [1.6.317] - 2026-10-17
### Fixed
- Balasan perintah tak lagi hilang saat perintah itu sendiri menjalankan ulang loop. Sebelumnya `/restart`, `/sembuh`, `/ping` (saat loop dijalankan ulang), pemberitahuan watchdog, dan laporan pemulihan "jalur kirim tampak bisu" dikirim dari coroutine yang **sedang dibatalkan** oleh `restartAllLoops()` sehingga balasannya dibuang diam-diam — owner melihat perintah seperti tidak dijawab padahal aksinya berjalan. Kini balasan dikirim lewat job terpisah (`replyLater`) yang tidak ikut dibatalkan.

## [1.6.316] - 2026-10-17
### Removed
- Tombol **Sembunyikan ikon launcher** beserta seluruh pendukungnya (`activity-alias` `.ui.Launcher`, tile Quick Settings `SetupTileService`, preferensi `launcher_icon_hidden`, string terkait) dibatalkan. Sebabnya: sejak Android 10 ikon tak benar-benar hilang — `LauncherAppsService` menyuntikkan entri "Info Aplikasi" bila app meminta izin dan masih punya activity launcher yang default-aktif di manifest (`activityInfo.enabled` tidak ikut berubah saat `setComponentEnabledSetting(DISABLED)`), sehingga ikon sisa malah membuka Setelan. Mematikannya butuh API `@SystemApi` (`setSyntheticAppDetailsActivityEnabled`) atau ikon yang tak ada sejak instal.

## [1.6.315] - 2026-10-17
### Added
- Tombol **Sembunyikan ikon launcher** di Setup: ikon app (alias launcher) dinonaktifkan tanpa menghapus app dan tanpa mematikan pemantauan, lalu bisa ditampilkan lagi dari tombol yang sama. Ada dialog konfirmasi yang menjelaskan cara membuka Setup lagi. Dua pintu masuk pengganti: **tile Quick Settings** bernama sama dengan app ("Calculator", `SetupTileService`) di panel notifikasi, dan notifikasi pemantauan yang menempel. Keadaan disembunyikan disimpan (`launcher_icon_hidden`) dan disinkronkan ulang tiap halaman Setup dibuka (tahan reinstall/pembaruan). Kode dial rahasia sengaja TIDAK dipakai karena sejak Android 11 broadcast `SECRET_CODE` tidak lagi sampai ke app pihak ketiga.

## [1.6.314] - 2026-10-17
### Changed
- Konsistensi bahasa pesan Telegram (tahap 3 dari 3 — sisanya): alasan kegagalan kamera/rekaman, laporan status/ringkas/ping, riwayat SMS & panggilan, pesan awal pemantauan, lencana keyboard inline (menu), perintah `/patrol`, `/night`, `/syncinterval`, `/contacts`, `/history`, `/pause`, `/record`, pesan patroli/malam, peringatan admin perangkat, kartu Status di Setup, label penerusan notifikasi (Notifikasi/Apl/Judul/Isi), laporan antrean pesan dibuang, serta bahan pesan upgrade grup supergrup kini berbahasa Indonesia. Tanpa perubahan logika.

## [1.6.313] - 2026-10-17
### Changed
- Konsistensi bahasa pesan Telegram (tahap 2 dari 3 — izin & perintah): pesan izin/Setup (kamera, mikrofon, SMS, kontak, notifikasi), `/lock`, `/flush`, pembersihan antrean, `/sms` beserta konfirmasinya, `/notif`, laporan pesan ditolak Telegram (400), serta pesan video yang tertunda kini berbahasa Indonesia. Tanpa perubahan logika.

## [1.6.312] - 2026-10-17
### Changed
- Konsistensi bahasa pesan Telegram (tahap 1 dari 3 — media): pesan foto, tangkap layar, video, rekaman suara, berdering, batas antrean berkas, serta petunjuk blokir kamera yang sebelumnya berbahasa Inggris kini berbahasa Indonesia, mis. `Photo rejected by Telegram (400) — file empty/unsupported, discarded.` → `Foto ditolak Telegram (400) — berkas kosong/tak didukung, dibuang.` Tanpa perubahan logika.

## [1.6.311] - 2026-10-17
### Changed
- Efisiensi penulisan antrean: `flushSync` kini hanya menulis ulang antrean bila memang ada perubahan yang belum tersimpan. Sebelumnya tiap pemanggil — pekerja pengiriman, penerus notifikasi, penanganan memori rendah — menulis ulang seluruh JSON antrean walau penambahan/penghapusan pesan barusan sudah tersimpan, sehingga terjadi penulisan ganda ke penyimpanan terenkripsi.

## [1.6.310] - 2026-10-17
### Changed
- Efisiensi pemindaian berkas: pemeriksaan "hemat kuota" (jaringan terukur + jumlah media tertahan) kini dihitung sekali lalu dipakai ulang dalam jendela 15 detik. Dulu ketiga penguras (foto, video, rekaman) memindai isi direktori cache secara berulang pada putaran loop yang sama. Kabar "N foto/video/rekaman ditahan" tetap maksimal sekali per 6 jam.
### Fixed
- Penghitung media tertahan tak lagi ikut menghitung rekaman yang sedang berjalan (`activeAudioFile`), sehingga angka yang dilaporkan dan keputusan penahanan hemat kuota tidak lagi kelebihan satu berkas.

## [1.6.309] - 2026-10-17
### Changed
- Efisiensi: layanan sistem yang sering dipakai (baterai, statistik pemakaian layar, lokasi, konektivitas, audio, daya, kebijakan perangkat, proyeksi layar, jendela) kini diambil sekali lalu dipakai ulang, alih-alih memanggil `getSystemService` berulang di jalur yang sering berjalan — loop pemantauan, patroli, laporan baterai, `/battery`, `/lock`, `/ring`, kamera, dan tangkap layar. Pemanggilan `getSystemService` di `MonitoringService` turun dari 22 titik menjadi 11 (9 di antaranya deklarasi simpanan, 2 sisanya jalur jarang: notifikasi & SMS). Perilaku tak berubah: bila layanan sistem tak tersedia, tiap jalur tetap memakai nilai cadangan yang sama seperti sebelumnya.

## [1.6.308] - 2026-10-17
### Fixed
- Perbaikan build: anotasi `@Volatile` yang tak sengaja ditulis dua kali di `MonitoringService` membuat kompilasi galat, sehingga tag `v1.6.305`–`v1.6.307` gagal dibangun dan tidak menerbitkan rilis. Seluruh perubahan sejak `v1.6.304` (thread/ANR, pengerasan izin & worker, efisiensi & kerapian) baru terbit di versi ini.
### Added
- `scripts/cek-cepat.sh` kini memeriksa anotasi ganda berturut-turut (mis. dua `@Volatile`) pada berkas Kotlin/Java, supaya galat kompilasi sejenis tertangkap sebelum push.

## [1.6.307] - 2026-10-17
### Changed
- Efisiensi & kerapian: penghitung sepi (`idleDataCycles`) berhenti di 8 sesuai batas pemakaiannya (dulu bisa naik sampai 100 tanpa guna); `/uptime` menghitung durasi dari jam monoton (`elapsedRealtime`) sehingga tidak kacau bila jam HP diubah; indentasi blok `forceReset()` pada kamera dan `markHandled()` di penerima boot dirapikan.
### Removed
- Helper nomor telepon tak terpakai (`PhoneNumbers.matches` dan `matchesNormalized`) dihapus — semua pemanggil memakai `matchesPrecomputed`.

## [1.6.306] - 2026-10-17
### Fixed
- Worker pemulihan setelah reboot memakai mekanisme ulang-coba bawaan WorkManager (`Result.retry()`) dengan backoff eksponensial yang memang sudah disiapkan, alih-alih menjadwalkan ulang pekerjaan lalu mengaku sukses — backoff di kode dulu tidak pernah berlaku.
- Setup: izin notifikasi yang ditolak permanen tidak lagi memaksa halaman Pengaturan terbuka (pemantauan tetap jalan tanpa izin itu); di Android 11 ke atas permintaan lokasi latar tidak lagi memanggil dialog yang selalu ditolak, melainkan langsung mengarahkan ke Pengaturan.
- Pesan "ditolak Telegram (400)" diperjelas: untuk foto, tangkapan layar, audio, dan video kini menyebut "berkas kosong/tak didukung"; untuk pesan teks menyebut "kosong atau permanen tak valid" — sebelumnya mudah disalahartikan sebagai masalah token/chat.
- `NetworkUtils.isPollBlocked` tidak lagi menyalin isi `isAuthBlocked` (kini mendelegasikan) agar aturan blokir sementara 30 menit hanya ada di satu tempat.

## [1.6.305] - 2026-10-17
### Fixed
- Screenshot: bila proses tangkap layar dibatalkan sistem (mis. layanan berhenti di tengah jalan), penanda "sedang menangkap" kini ikut dibersihkan — dulu bisa tersangkut menyala sehingga `/screenshot` berikutnya dijawab "masih berjalan".
- Rekaman video: batas waktu (timeout) kamera kini berjalan di thread latar kamera, bukan thread utama. Menghentikan perekam saat timeout bisa makan waktu dan dulu berisiko membuat aplikasi tak responsif (ANR) saat kamera lambat ditutup.
- Penanda lintas-thread di layanan pemantauan (berkas foto aktif, tugas latar/dering/rekaman/SMS, watchdog foto & video) kini `@Volatile` agar perubahannya terlihat antar-thread dan watchdog tidak tertinggal.

## [1.6.304] - 2026-10-17
### Fixed
- `/jemput` melaporkan dengan jujur: bila radius tersimpan tetapi geofence `/home` sedang mati, statusnya kini "tersimpan tapi TIDAK aktif" (dulu selalu bilang "aktif" walau tak akan pernah berbunyi).
- Deteksi berkendara (`/kendara`) tidak lagi memakai sampel lokasi berakurasi buruk (lebih dari 80 m) sebagai pembanding kecepatan berikutnya; `/kendara status` juga memberi catatan bila interval lokasi lebih dari 30 menit (perhitungan kecepatan dari jarak/waktu tidak jalan) dan teks bantuan menyebut syarat itu.
- Kabar baterai lemah kini juga terkirim saat baterai tepat di ambang 15% (dulu pemeriksaan memakai `<` sehingga ambangnya sendiri terlewat).
- `/layar` membedakan tiga kondisi: izin Usage access belum aktif, data gagal dibaca, atau memang belum ada pemakaian — sebelumnya izin dan kegagalan baca sama-sama tampil sebagai "izin belum aktif".
- Laporan `/status` hanya menyebut "sepi adaptif" bila interval memang sedang direntangkan (dulu selalu menyebut walau perentangan tak pernah berlaku untuk interval 30 menit ke atas).

## [1.6.303] - 2026-10-17
### Fixed
- Penghitung pesan terbuang (antrean penuh/kedaluwarsa/prioritas) kini benar-benar dimuat saat aplikasi mulai: blok inisialisasi `MessageQueue` dulu berjalan sebelum variabel penghitungnya siap, sehingga pemuatan gagal diam-diam dan angka drop di laporan selalu 0.
- Pelapor crash: baris pemeriksaan kredensial yang terduplikasi dirapikan, dan variabel sisa yang tak dipakai dibuang.
### Removed
- Kode mati dibersihkan: sisa fitur "wake poll" yang tak terpakai (`wakeUpdateId`, `wakeBackoffUntil`, `wakeOffsetFail`, `wakePingRate`, `lastWakePollAt` beserta klausa self-conflict `409` yang selalu bernilai salah), helper tak terpakai (`lastSendOkAt`, `setWakeUpdateIdSync`, `touchSmsPendingSync`, `addWakePingIds`), serta warna `purple_*`/`teal_*` yang tak dipakai (berkas `values-night/colors.xml` yang isinya sama persis dihapus agar warna malam ikut `values/colors.xml`).

## [1.6.302] - 2026-10-17
### Fixed
- Penyimpanan aman: proses migrasi penyimpanan (`upgradeToPersistent`) kini memeriksa hasil penulisan (`commit`) dan tidak lagi menandai "terenkripsi" bila penulisan gagal. Penanda internal yang ditulis dari jalur UI/notifikasi (status sinkron awal, status nada dering, jumlah notifikasi terbuang) memakai `apply()` sehingga tidak memblokir thread utama. Pesan gagal simpan kredensial juga diperjelas: bila penyimpanan aman (keystore) tidak tersedia di HP, owner langsung diberi tahu kredensial tidak bisa disimpan permanen (sebelumnya hanya "Test failed: persist").

## [1.6.301] - 2026-10-17
### Fixed
- Pemilihan lokasi kini memakai fix **paling akurat**, bukan yang pertama tersedia. Dari sekian sinyal yang aktif (GPS + jaringan) aplikasi mengumpulkan hasilnya, berhenti begitu ada fix akurat (≤50 m), lalu memilih yang akuratnya paling kecil — termasuk pada daftar lokasi cache (dulu jaringan selalu dicoba lebih dulu tanpa membandingkan akurasi). Akibatnya `/location`, `/darurat`, `/hilang`, patroli, dan pemeriksaan geofence tidak lagi memakai taksiran jaringan yang bisa meleset ratusan meter, sehingga kabar masuk/keluar rumah/sekolah tidak lagi sering tertahan penjaga akurasi.

## [1.6.300] - 2026-10-17
### Added
- Hemat kuota: perintah `/hematdata on|off|status` (alias `hematdata`/`hematkuota`/`kuota`). Saat nyala dan HP sedang memakai data seluler (jaringan *metered*), pengiriman foto/video/rekaman yang mengantre ditahan di cache dulu dan langsung dikirim otomatis begitu tersambung Wi-Fi/jaringan tak berkuota — SMS, lokasi, notifikasi, dan chat teks tidak terpengaruh. Saat penahanan pertama dalam 6 jam, owner dikabari sekali (`📶 Hemat kuota: N foto/video/rekaman ditahan`). Deteksi jaringan lewat `ConnectivityManager`/`NetworkCapabilities` (`NET_CAPABILITY_NOT_METERED`).

## [1.6.299] - 2026-10-17
### Added
- Deteksi naik kendaraan: kalau lokasi menunjukkan kecepatan tinggi (≥30 km/jam, maks 200 km/jam agar lompatan GPS tidak dianggap nyata) dua sampel berturut-turut saat kecepatan dihitung sendiri dari jarak/waktu — atau sekali saja kalau HP memberi kecepatan hardware — owner dapat kabar `🚗 Sepertinya sedang berkendara` berisi kecepatan + lokasi + baterai. Maksimal satu kabar per 20 menit; hanya memakai lokasi cache (`getLastKnownLocation`, tanpa menyalakan GPS tambahan), jadi tidak menambah boros baterai. Bisa dimatikan dengan `/kendara off` (alias `kendara`/`berkendara`/`kendaraan`).

## [1.6.298] - 2026-10-17
### Added
- Ringkasan screen-time: perintah `/layar [1-24]` (alias `layar`/`screentime`) melaporkan total pemakaian layar + 6 aplikasi paling lama dipakai, dan ringkasan harian (`sendDailyDigest`) menyisipkan satu baris "Layar 24 jam: … · teratas …". Sumber data `UsageStatsManager` (`utils/UsageAccess.kt` untuk cek izin), izin opsional `PACKAGE_USAGE_STATS` yang diaktifkan pemilik HP lewat tombol **Screen time access** di Setup (`Settings.ACTION_USAGE_ACCESS_SETTINGS`) — tanpa izin itu perintah menjawab dengan panduan singkat, dan fitur lain tetap jalan seperti biasa. Hanya aplikasi yang punya ikon peluncur yang dihitung, aplikasi sendiri & proses sistem diabaikan.

## [1.6.297] - 2026-10-17
### Added
- Kabar saat Device Admin **hendak** dimatikan: `AdminReceiver.onDisableRequested()` kini tidak hanya menampilkan peringatan ke pemakai HP, tapi juga langsung mengirim pesan ke owner **sebelum** tombol "Nonaktifkan" ditekan, lalu meminta `MonitoringService` (`ACTION_ADMIN_ALERT`) mengambil lokasi + foto saat itu juga — jadi momen paling krusial (percobaan mencabut proteksi) tidak lagi lewat tanpa jejak.

## [1.6.296] - 2026-10-17
### Added
- Kabar aplikasi baru: tiap ada aplikasi **dipasang** (yang bukan bawaan sistem) atau **dihapus** di HP anak, owner langsung dapat pesan Telegram berisi nama aplikasi + nama paketnya (`📦 Aplikasi baru dipasang` / `🗑️ Aplikasi dihapus`). Memakai pemberitahuan bawaan Android (`PACKAGE_ADDED`/`PACKAGE_REMOVED`) lewat `receiver/PackageChangeReceiver.kt`, tanpa izin tambahan; pembaruan aplikasi (`EXTRA_REPLACING`) dan aplikasi bawaan ROM diabaikan agar tidak berisik.

## [1.6.295] - 2026-10-17
### Added
- Deteksi "hidup tapi bisu": waktu kirim sukses terakhir dicatat (dari `sendToTelegram` dan worker antrean). Bila sudah >90 menit tidak ada kiriman berhasil padahal antrean berisi dan jaringan normal tanpa token bermasalah, aplikasi memulihkan sendiri bertahap tiap 45 menit — tahap 1 putar ulang antrean + segarkan kredensial, tahap 2 hapus webhook + daftar ulang menu command, tahap 3 hidupkan ulang semua loop — lalu mengabari hasilnya (maksimal 3 tahap per episode, direset begitu ada kiriman sukses).
- Perintah `/sembuh` (alias `sembuh`/`pulih`): pemulihan penuh atas permintaan — segarkan kredensial, hapus webhook, daftar ulang menu command, pasang ulang jam bangun + watchdog + worker, minta ulang akses notifikasi, hidupkan ulang semua loop, lalu kirim laporan apa saja yang dikerjakan.
- Baris "Jalur kirim" di `/alive`: berapa lama sejak kiriman sukses terakhir (OK/BISU) + berapa kali pemulihan otomatis dijalankan.
### Changed
- `registerBotCommands` menerima `force` sehingga bisa didaftar ulang paksa saat pemulihan (sebelumnya selalu berhenti di penanda hash), dan `TelegramApi` menambah `deleteWebhook` — jalur pemulihan untuk kasus webhook tertinggal (penyebab klasik `getUpdates` 409/command tak masuk).

## [1.6.294] - 2026-10-17
### Added
- Uji mandiri anti-mati: perintah `/cekmati` (alias `cekmati`/`ujimati`) dan tombol **Tes anti-mati** di Setup. Servis sengaja menghentikan dirinya sendiri (seperti dibunuh sistem), hanya jam bangun (watchdog) yang boleh menghidupkannya lagi ±45 detik kemudian, lalu owner menerima laporan berapa detik servis hidup kembali — plus catatan bila yang menghidupkan bukan jam bangun, bila gagal (>10 menit), atau bila uji terputus reboot. Jadi keandalan anti-mati terbukti di HP masing-masing, bukan cuma di CI.
- Baris `Liveness` di Setup menyebut hasil uji terakhir (`self-test 42s` / `running` / `not run`).
### Changed
- Teks panduan Anti-kill menyebut rentang pemulihan 3–15 menit (bukan "tiap 10 menit") dan mengajak menjalankan uji anti-mati.

## [1.6.293] - 2026-10-17
### Added
- Hemat daya pintar: saat baterai ≤15% dan HP tidak di-charge, selang foto otomatis dan patroli otomatis dilipatgandakan (patroli diliburkan sementara) agar HP tidak mati kehabisan daya; pesan peringatan baterai menyebutkan perlambatan ini, dan begitu di-charge atau kembali di atas 15% owner menerima kabar "baterai kembali normal" sekali per kejadian.
### Changed
- Jam liveness kini adaptif lewat `Liveness.intervalMs()`: **3 menit** selama satu jam pertama setelah layanan terbunuh (mode waspada, karena OEM sedang agresif), **10 menit** saat layar mati (kematian paling sering terjadi di sini), dan **15 menit** saat layar menyala (layanan sedang aktif) — hemat baterai tanpa mengorbankan kecepatan pemulihan.
- `/alive` dan baris `Liveness` di Setup menampilkan selang watchdog yang sedang berlaku (mode waspada / layar nyala / layar mati) dan menandai saat mode hemat daya aktif.

## [1.6.292] - 2026-10-17
### Added
- `receiver/TimeChangeReceiver.kt`: pemicu ulang saat jam/zona/tanggal HP diubah (`TIME_SET`, `TIMEZONE_CHANGED`, `DATE_CHANGED`) — memasang ulang jam liveness dan, bila layanan ternyata sudah mati, langsung menghidupkannya lagi. Jadi setelan jam yang berubah pun menjadi kesempatan pemulihan, bukan celah.
- Penanda "terakhir hidup" berbasis waktu monoton (`last_alive_elapsed`) dan penghitung pemulihan yang belum dilaporkan (`liveness_kills_unreported`) di `PreferencesManager`.
### Changed
- Jam liveness memakai `AlarmManager.ELAPSED_REALTIME_WAKEUP` (waktu monoton) alih-alih `RTC_WAKEUP` (jam dinding): mengubah jam/zona HP tidak lagi menggeser atau menghilangkan jadwalnya.
- Deteksi kematian layanan dihitung dari waktu monoton, bukan jam dinding, sehingga mengubah jam HP tidak lagi memicu laporan "mati sejak pukul X" yang keliru; reboot (waktu monoton kembali dari nol) juga tidak lagi dianggap kematian.
- Laporan pemulihan lebih hemat sekaligus lebih informatif: kematian ≥3 menit dihitung (`liveness_kills`), tetapi pesan Telegram hanya dikirim bila layanan berhenti ≥15 menit **atau** sudah 3× berhenti sejak laporan terakhir (tetap maksimal sekali per 6 jam) — teksnya menyebut "Sudah berhenti N× sejak laporan terakhir".
- Receiver liveness menyegarkan penanda "terakhir hidup" setiap jam bangun selama layanan benar-benar hidup, supaya lama mati terukur tepat walau selang sinkronisasi data diperpanjang.
- `/alive` dan baris `Liveness` di Setup memakai waktu monoton untuk "denyut terakhir ... lalu" (tidak lagi salah saat jam HP diubah) dan menampilkan jumlah pemulihan yang belum dilaporkan.

## [1.6.291] - 2026-10-17
### Added
- Diagnosa blokir kamera: pesan kegagalan foto/video kini menyebut **paket admin penyebabnya** (dari `getActiveAdmins()` + `getCameraDisabled(admin)`), bukan lagi pesan umum "another admin app".
- Sumber jeda foto ikut disimpan (`photo_pause_source`): jeda karena blokir admin (120 menit, dipantau tiap 2 menit) dibedakan dari blokir tak dikenal/HAL (ulangan tiap 15 menit).
### Changed
- Foto otomatis kini **pulih sendiri begitu blokir kamera dicabut**: selama jeda-blokir-admin, siklus kamera memeriksa status tiap 2 menit; begitu `getCameraDisabled` kembali normal, jeda dibatalkan dan owner diberi kabar "✅ Camera block lifted — automatic photos resumed." (sebelumnya harus menunggu penuh 120 menit). Selama masih diblokir, jeda diperpanjang tanpa percobaan kamera dan tanpa notifikasi berulang.
- `/resume` kini benar-benar membatalkan jeda foto otomatis seperti yang dijanjikan petunjuk pesannya (sebelumnya hanya melanjutkan monitoring, jeda foto tetap berjalan).
- Blokir kamera yang tidak berasal dari admin perangkat memakai jeda pendek 15 menit (bukan 120 menit) karena tak ada yang bisa dicabut di Settings.

## [1.6.290] - 2026-10-17
### Changed
- `NotificationForwarderService` memulihkan akses notifikasi dengan backoff bertingkat (30 detik → 2 → 5 → 15 → 30 menit, maksimal 5 percobaan, direset saat tersambung lagi) dan `requestRebind` kini dipanggil di main thread seperti yang diwajibkan API; sebelumnya hanya satu percobaan ulang 60 detik dari thread latar.
- `MonitoringService` menangani `onTrimMemory`/`onLowMemory`: saat memori kritis antrean pesan ditulis ke disk (`flushSync`) dan jam liveness dipasang lebih dulu, jadi kalau proses dibunuh sistem setelahnya pemulihan tetap cepat dan antrean tidak hilang.
- `BootReceiver` menambah pemicu ulang `USER_PRESENT` (HP dibuka/unlock) dan `PACKAGE_RESTARTED` (hanya untuk paket sendiri), sehingga monitoring dihidupkan lagi saat HP mulai dipakai, bukan cuma saat boot; pesan pemberitahuannya dibuat umum karena tak selalu berasal dari reboot.
- Catatan: jalur gagal `onStartCommand` tetap `START_NOT_STICKY` (bukan diubah ke sticky) karena pemulihan sudah dipegang jam liveness 10 menit + `BootRestartWorker` + pemicu boot/unlock, dan sticky-restart pada `onCreate` yang gagal berisiko loop restart yang dibatasi sistem (dan tak berefek setelah `stopSelf()`).

## [1.6.289] - 2026-10-17
### Added
- Perintah `/alive`: laporan denyut — status, uptime, denyut terakhir, berapa kali service dipulihkan, watchdog, tipe foreground yang benar-benar aktif, dan level baterai.
- Perintah `/heartbeat &lt;1-48|0&gt;`: laporan "masih hidup" berkala (default mati) yang dihitung dari stempel `heartbeat_at`, jadi owner bisa memilih menerima kabar rutin tanpa harus meminta `/alive` manual.
- Baris `Liveness` di halaman Setup: status hidup/mati, denyut terakhir berapa menit lalu, jumlah pemulihan, dan setelan heartbeat.

## [1.6.288] - 2026-10-17
### Added
- `utils/AutoStart.kt` + tombol `Anti-kill & Autostart` di Setup: dialog panduan berisi langkah anti-mati (autostart merek, pembatasan baterai, "tampil di atas aplikasi lain") dengan status hidup/mati untuk dua yang bisa dideteksi. Tombol autostart membuka layar setelan khusus merek (Xiaomi/Redmi/Poco, Oppo/Realme, Vivo/iQOO, Huawei/Honor, Samsung, OnePlus, Asus, Meizu, Letv, Nokia) dengan daftar kandidat komponen per merek dan jatuh ke halaman Info Aplikasi bila tak ada yang cocok; izin "tampil di atas aplikasi lain" dibuka lewat `ACTION_MANAGE_OVERLAY_PERMISSION`.
- Izin `SYSTEM_ALERT_WINDOW` (opsional, hanya dibuka lewat dialog Anti-kill): membantu Android 12+ mengizinkan layanan dinyalakan kembali dari latar saat watchdog menghidupkannya.
### Changed
- Wizard anti-kill muncul otomatis sekali setelah monitoring dinyalakan bila pembatasan baterai masih aktif atau izin "tampil di atas aplikasi lain" belum diberikan, supaya langkah anti-mati tidak terlewat.

## [1.6.287] - 2026-10-17
### Added
- `utils/Liveness.kt` + `receiver/LivenessReceiver.kt`: jam bangun (watchdog) berbasis `AlarmManager.setAndAllowWhileIdle` — tanpa izin khusus dan tetap berbunyi saat Doze — yang dipasang tiap ~10 menit. Bila `MonitoringService` sudah mati padahal monitoring masih aktif, receiver menghidupkannya kembali lewat `startForegroundService` (dan jatuh ke `BootRestartWorker` bila sistem melarang start foreground dari latar), lalu memasang jam berikutnya. Ini jalur pemulihan cepat yang tidak bergantung kuota WorkManager.
- Penanda "terakhir hidup" (`last_alive_at`), penghitung pemulihan (`liveness_kills`), dan stempel laporan (`liveness_report_at`) di `PreferencesManager`. Bila layanan hidup lagi setelah berhenti ≥15 menit, owner otomatis menerima pesan Telegram berisi perkiraan jam berhenti dan lamanya (maksimal sekali per 6 jam), jadi kematian layanan tidak lagi senyap.
### Changed
- Notifikasi foreground memakai `setForegroundServiceBehavior(FOREGROUND_SERVICE_IMMEDIATE)` sehingga layanan langsung dipromosikan (tidak menunggu ~10 detik) di Android 12+; berlaku untuk tiga builder di `MonitoringService` dan builder di `BootRestartWorker`.
- Jam liveness dipasang saat boot (`BootReceiver`), saat worker restart berjalan (`BootRestartWorker`), saat layanan dimulai/dipindah/dimatikan sementara (`startMonitoring`, `onTaskRemoved`, `onDestroy`, timeout FGS), dan saat monitoring dinyalakan dari Setup; dibatalkan (plus penanda dihapus) saat monitoring dimatikan atau kredensial dibersihkan, supaya tidak "hidup sendiri" setelah owner mematikannya.

## [1.6.286] - 2026-10-17
### Fixed
- `NotificationForwarderService.flushBatch` tidak lagi membuang notifikasi yang belum terkirim bila job dibatalkan/dihentikan di tengah: batch diambil dari `batchBuf` lalu dikosongkan di awal, sehingga item sisa (dan potongan teks yang sedang disusun) hilang senyap karena `salvageBatch` hanya menyelamatkan `batchBuf` yang sudah kosong. Kini item yang belum terkirim dikembalikan ke depan `batchBuf` saat `flushBatch` berhenti tak normal, sehingga bisa dicoba lagi/diselamatkan.
- `MessageQueue.flushSync` menulis snapshot ke preferensi tanpa memegang `lock`, membuka jendela clobber: penulisan snapshot lama (kadang `commit()`) bisa menimpa antrean yang lebih baru yang ditulis thread lain. Penulisan sekarang dilakukan di dalam `lock` agar urut dengan mutasi antrean.
- `MessageQueue.persistDropCountsLocked` memakai `commit()` sinkron yang bisa berjalan di main thread (mis. dari `BroadcastReceiver` lewat `addMessage`), berisiko ANR; kini `apply()` saat di main thread dan `commit()` di luar main thread.
- String default (Inggris) `setup_saved_offline` di `values/strings.xml` masih berbahasa Indonesia; kini berbahasa Inggris (`values-in` tetap Indonesia).

## [1.6.285] - 2026-10-17
### Fixed
- Backup yang dibuat di Android 8.0+ (API 26+, kunci diturunkan dengan `PBKDF2WithHmacSHA256`) kini bisa dipulihkan di Android 7.0–7.1 (API 24–25): perangkat API 24–25 tak punya `SecretKeyFactory` untuk PBKDF2-HMAC-SHA256 sehingga `deriveKey(modern=true)` dulu jatuh ke PBKDF2-HMAC-SHA1 dan tag GCM tidak pernah cocok (restore selalu gagal). Kini bila algoritma itu tak tersedia, kunci diturunkan dengan implementasi PBKDF2-HMAC-SHA256 murni (`Mac` HmacSHA256, RFC 2898, kata sandi UTF-8) yang menghasilkan kunci identik dengan jalur platform, sehingga backup modern tetap bisa dibuka di perangkat lama.

- `CameraService` tidak lagi membocorkan kamera saat `onOpened` datang terlambat: bila watchdog/timeout sudah memanggil `finishWithError` → `cleanup()` (yang menutup kamera dan mengosongkan `cameraDevice`), callback `onOpened` yang menyusul dulu tetap menyetel `cameraDevice = camera` dan membuat sesi capture, sehingga kamera baru tak pernah ditutup dan `finishWithPhoto`/`finishWithVideo` bisa terpanggil setelah selesai. Kini `onOpened` (jalur foto maupun video) menutup kamera yang datang bila permintaan sudah selesai (`done`).

## [1.6.284] - 2026-10-17
### Fixed
- Tombol `Test` di Setup tidak lagi membuang perubahan interval: `Test` membatalkan job `Save` yang sedang berjalan (menaikkan `saveSeq`) tetapi hanya menyimpan token + chat ID, sehingga hasil verifikasi `Save` yang tertunda — termasuk `syncInterval`/`cameraInterval` yang baru diketik — tidak pernah ditulis. `Test` kini menyimpan token, chat ID, dan kedua interval sekaligus (memakai `saveTestCredentials` yang tersedia; `saveTestIdentity` yang hanya menulis token+chat dihapus) dengan rentang valid `1..1440`/`0..60` dan jatuh ke nilai tersimpan bila kolom interval tidak valid.

## [1.6.283] - 2026-10-17
### Fixed
- `MonitoringService` tidak lagi bisa dijalankan tanpa inti siap: bila `startForegroundImmediate()` gagal di `onCreate` (mis. diluncurkan dari boot di Android 15+), inisialisasi `smsRepository`/`callLogRepository`/`messageQueue`/`cameraService` dihentikan, lalu `onStartCommand` tetap dipanggil dan — bila percobaan foreground kedua berhasil — langsung memakai field `lateinit` yang belum diinisialisasi (crash `UninitializedPropertyAccessException`). Kini ada penanda `coreInitialized` dan `onStartCommand` berhenti (`START_NOT_STICKY`) sampai inti benar-benar siap.
- Tipe foreground service yang benar-benar aktif tidak lagi salah dicatat: jalur cadangan `startForeground(..., baseForegroundType())` di `startForegroundImmediate()` dan `ensureForegroundTypes()` kini menulis `appliedFgsTypes`, sehingga `ensureForegroundTypes()` tidak berulang kali mencoba tipe lengkap yang memang ditolak (dan tetap turun ke tipe dasar bila tipe lengkap ditolak).
- Perbaikan build: `ensureForegroundTypes()` menyusun notifikasi di luar `try` sehingga variabelnya tetap terlihat di blok `catch` (build `v1.6.283` sempat gagal kompilasi, tag dipindahkan ke commit ini).

## [1.6.282] - 2026-10-17
### Fixed
- `/screenshot` benar-benar berfungsi: token izin `MediaProjection` tidak lagi disimpan ke preferensi berbentuk string URI (yang membuang `IBinder` token sehingga `getMediaProjection` selalu mengembalikan `null` dan status Setup tetap "Screenshot: granted" walau `/screenshot` tak pernah berhasil). Izin kini dipegang di memori proses (`utils/ScreenCapture`), `MediaProjection` diambil sekali dan dipakai ulang, `VirtualDisplay`/`ImageReader` hanya dilepas per pengambilan (projection tetap hidup), dan izin baru dicabut saat sesi dihentikan sistem, service mati, atau pengguna meminta izin ulang — sehingga `/screenshot` berikutnya tidak lagi memanggil `getMediaProjection` kedua kali (`SecurityException` di Android 14+) dan status Setup selalu jujur.
### Removed
- Penyimpanan izin screenshot ke `PreferencesManager` (`screenshotResultCode`, `screenshotData`, `saveScreenshotConsentSync`, `clearScreenshotConsentSync`, `hasScreenshotConsent`) beserta pembersihan izin di `BootReceiver`; izin screenshot sekarang hanya hidup selama proses aplikasi.

## [1.6.281] - 2026-10-17
### Fixed
- Semua balasan "Cara pakai …", menu `/help`, dan `/notif` memuat placeholder `<…>` literal di dalam pesan `parseMode` HTML; Telegram menolaknya dengan HTTP 400 (`can't parse entities: Unsupported start tag`) sehingga placeholder berawalan huruf (mis. `<number>`, `<on|off>`) dihapus oleh fallback teks polos (pesan tampil tanpa parameter, mis. "Cara pakai: /sms") dan placeholder berawalan non-huruf (mis. `<0-60>`, `<5-180>`) membuat pesannya di-drop total tanpa fallback (karena `Html.unescape` tak mengubah apa pun). Placeholder kini dikirim sebagai entitas `&lt;`/`&gt;` sehingga lolos parser HTML Telegram dan tampil utuh sebagai `<…>`; teks `/watch` yang memuat "Diabaikan (<3 huruf)" juga diperbaiki.

## [1.6.280] - 2026-10-17
### Changed
- `/screenshot` tidak lagi sekali pakai per izin di Android 14+: sesi `MediaProjection` (projection + `VirtualDisplay` + `ImageReader`) disimpan dan dipakai ulang untuk `/screenshot` berikutnya selama izin sama, lalu dilepas otomatis 10 menit setelah pengambilan terakhir — juga dilepas saat izin hilang, orientasi/ukuran layar berubah, sesi dihentikan sistem (`onStop`), atau service mati. Sebelumnya token izin `MediaProjection` hanya boleh dipakai sekali (`getMediaProjection` kedua melempar `SecurityException`) sehingga `/screenshot` gagal total dan izin dicabut.
### Fixed
- Frame screenshot tidak lagi bisa basi: satu frame lama dibuang dari buffer sebelum menunggu frame baru, jadi yang dikirim adalah gambar terkini (bukan frame sisa sesi sebelumnya).

## [1.6.279] - 2026-10-17
### Changed
- Tipe foreground service kini memakai `specialUse` (Android 14+, dengan izin `FOREGROUND_SERVICE_SPECIAL_USE` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`) alih-alih `dataSync` yang dibatasi 6 jam per 24 jam di Android 15+: `MonitoringService` dan `SystemForegroundService` milik `BootRestartWorker` memakai helper `baseForegroundType()`/`fgsType()`, dan `startForeground` dicoba ulang dengan tipe dasar itu bila tipe lengkap ditolak (mis. tipe `dataSync|camera|microphone|mediaProjection` dilarang saat diluncurkan dari `BOOT_COMPLETED` di Android 15+).
### Fixed
- `MonitoringService` tidak lagi dibiarkan hidup tanpa foreground saat semua percobaan `startForeground` gagal: `startForegroundImmediate()` mengembalikan hasil dan service `stopSelf()` (setelah menjadwalkan boot restart + watchdog), sehingga tidak lagi kena `RemoteServiceException` "did not then call `Service.startForeground()`" (crash tiap boot di Android 15+).

## [1.6.278] - 2026-10-17
### Fixed
- `/screenshot` kini mendaftarkan `MediaProjection.Callback` sebelum `createVirtualDisplay()` sesuai syarat sejak `targetSdk` 34 (Android 14): tanpa callback, `createVirtualDisplay()` selalu melempar `IllegalStateException` di Android 14+ sehingga screenshot tidak pernah berhasil sama sekali. Callback juga melepas `VirtualDisplay`/`ImageReader` saat sesi dihentikan sistem dan di-`unregister` di blok `finally`.

## [1.6.277] - 2026-10-17
### Fixed
- Backup/restore (`SettingsBackup`) gagal total di Android 7.0–7.1 (API 24–25) karena `PBKDF2WithHmacSHA256` baru tersedia sejak API 26: kini kunci diturunkan dengan SHA256 bila ada dan otomatis jatuh ke `PBKDF2WithHmacSHA1` bila tidak, sedangkan impor mencoba SHA256 lebih dulu lalu SHA1 agar backup lama (API 24–25) tetap bisa dipulihkan di perangkat baru.

## [1.6.276] - 2026-10-16
### Fixed
- `/recordvideo` tidak lagi melepas flag `recordBusy` seketika setelah perekaman dimulai: flag tetap terkunci selama video direkam sehingga `/recordvideo` kedua dan `/ring`/`/hilang` bersamaan tertahan, dan watchdog timeout video (forceReset kamera + lepas WakeLock) berfungsi kembali.
- `flushPendingPhotos` benar-benar memangkas cache foto sesudah flush; pemangkasan sebelumnya selalu no-op karena dipanggil saat flag flush masih aktif, kini konsisten dengan audio/video.

## [1.6.275] - 2026-10-16
### Fixed
- Build v1.6.274 gagal diperbaiki: setter `PreferencesManager` kembali memakai `.apply()` (perubahan ke `.commit()` dibatalkan).
- Dua konsumen getUpdates (MonitoringService + NotificationForwarderService) tidak lagi bertabrakan dengan HTTP 409; NotificationForwarderService tidak lagi polling getUpdates, hanya memicu start service saat kondisi terpenuhi.
- Offset Telegram ditandai diproses SESUDAH perintah dieksekusi, bukan sebelum, mencegah hilangnya perintah saat gagal setengah jalan.
- isAuthBlocked dan isPollBlocked sekarang konsisten: keduanya tidak memperlakukan HTTP 400 sebagai auth-blocked.
- Race condition TOCTOU pada konfirmasi SMS (/smsconfirm): pending SMS sekarang dibaca secara atomik via PreferencesManager.readSmsPendingSync() di bawah smsPendingLock.

## [1.6.274] - 2026-10-16
### Fixed
- Percobaan memindahkan seluruh setter `PreferencesManager` ke `.commit()` — build CI gagal, dibatalkan pada `[1.6.275]`.

## [1.6.273] - 2026-10-16
### Fixed
- SetupActivity tidak lagi menghapus input kredensial yang belum disimpan saat aktivitas direkreasi (putar layar / proses berhenti).
- Hapus kode mati (unreachable START_STICKY) di MonitoringService.onStartCommand.

## [1.6.272] - 2026-10-16
### Changed
- Tema aplikasi ikut logo: primer biru `#2196F3`, sekunder oranye `#FF9800` (ungu hilang).
- Kalkulator gaya iOS: hitam, tombol lingkaran (angka abu gelap, C abu terang, operator oranye), display tanpa kartu.

## [1.6.271] - 2026-10-16
### Changed
- Kalkulator ikut palet logo: latar navy gelap, display slate `#37474F`, angka abu-biru `#607D8B`, fungsi oranye `#FF9800`, samadengan hijau `#4CAF50` — siang/malam sama.
- Kalkulator dipadatkan (padding, tombol, huruf mengecil) + nama aplikasi ikut bahasa HP (Indonesia: Kalkulator).
- Halaman Setup tanpa top bar + judul ganda — konten langsung status + form.
- Kalkulator tanpa top bar sistem — full kalkulator + stealth.
### Removed
- Pemicu `pull_request` di workflow build — CI hanya jalan saat push ke `main`, push tag `v*`, atau manual.

## [1.6.270] - 2026-10-16
### Fixed
- Teks drop-notif `onDestroy` forwarder jujur (rejected/overflow), samakan jalur utama.
- Backup ekspor isi form yang valid (bukan prefs basi) — ketik baru langsung Backup tanpa Save tetap benar.
### Removed
- Override `onSupportNavigateUp()` yatim di Setup (tak terjangkau sejak ActionBar di-hide).
### Changed
- README: PR tak lagi picu CI (ikut hapus trigger).

## [1.6.269] - 2026-10-16
### Fixed
- WakeLock video dilepas saat watchdog timeout (tak digantung 120 dtk).
- Notif konflik 409 tetap terkirim saat ambiguitas wake-loop (hanya restart-sendiri yang sunyi) — poller liar tak luput.
- Transisi geofence masuk/keluar abaikan fix kasar (gate akurasi = radius), cegah flip + spam ganda.
- Hapus staging kedaluwarsa/diblokir kini berguard-ID, tak bisa sapu staging baru.

## [1.6.268] - 2026-10-16
### Added
- Backup/restore setingan via file `.redeyebak` terenkripsi PIN (AES-GCM + PBKDF2): tombol Backup/Restore di Setup; restore mengisi form, Save tetap verifikasi.

## [1.6.267] - 2026-10-16
### Fixed
- Penolakan izin permanen kini jelaskan dulu via Toast sebelum lempar ke Settings (tak lagi pindah diam-diam).

## [1.6.266] - 2026-10-16
### Fixed
- Test Connection kini bedakan token salah format vs chat ID salah format (seperti Save), tak lagi semua jadi "bad token".

## [1.6.265] - 2026-10-16
### Fixed
- Video rekaman kini bawa hint orientasi (sensor + rotasi layar, sama seperti foto) — tak lagi miring di Telegram saat HP tegak.

## [1.6.264] - 2026-10-16
### Fixed
- Blob antrean korup tak lagi memusnahkan semua pesan unsent: salinan backup otomatis dipakai + sembuh ke depan; penghapusan antrean ikut bersihkan backup.

## [1.6.263] - 2026-10-16
### Fixed
- Staging SMS satu-slot kini atomik (`synchronized`): kirim-berhasil tak lagi bisa menghapus staging baru yang masuk di tengah jalan.

## [1.6.262] - 2026-10-16
### Fixed
- `/recordvideo` durasi yang pasti melebihi 48 MB ditolak di depan (dengan saran detik aman), bukan setelah menunggu rekaman 3 menit.

## [1.6.261] - 2026-10-16
### Fixed
- `/jejak` benar-benar 24 jam: jejak diprune berdasar umur (bukan 24 titik), patroli 5 menit tak lagi menghapus jejak pagi (cap darurat 300 titik).

## [1.6.260] - 2026-10-16
### Fixed
- Interval adaptif tak lagi direset oleh data yang gagal terkirim — saat offline, retry melambat hemat baterai/kuota, bukan mengetat tiap siklus.

## [1.6.259] - 2026-10-16
### Fixed
- Notifikasi terbuang saat burst (>64 antre) kini dihitung + dilaporkan ke owner (notif agregat), tak lagi hilang diam-diam di history volatil.

## [1.6.258] - 2026-10-16
### Fixed
- Geofence rumah/sekolah + `/jemput` kini dievaluasi tiap lokasi segar (`/location`, `/darurat`, `/hilang`), tak hanya saat patroli — hanya kabari saat status berubah, tanpa spam.

## [1.6.257] - 2026-10-16
### Fixed
- Rekaman audio/video + bunyi kini menahan WakeLock (batas-waktu otomatis) agar durasi tepat dan pengiriman susulan tak tertunda Doze; izin normal `WAKE_LOCK` ditambah ke manifest.

## [1.6.256] - 2026-10-16
### Fixed
- `/hilang` tak lagi ditolak total saat rekaman jalan: bunyi dilewati (hindari konflik audio), lokasi + foto tetap dikirim — bukti darurat tak pernah gagal total.

## [1.6.255] - 2026-10-16
### Fixed
- Worker yang dibatalkan (`REPLACE` tiba saat kirim) kini menghapus ID yang sudah terkirim sebelum mati — tak ada lagi pesan ganda di Telegram setelah rate-limit/backoff.

## [1.6.254] - 2026-10-16
### Fixed
- SMS yang gagal total (nol part terkirim) tak lagi memicu cooldown 60 dtk — retry `/smsconfirm` langsung bisa jalan; throttle tetap berlaku bila sebagian/seluruh part terkirim (cegah duplikat).

## [1.6.253] - 2026-10-16
### Fixed
- Offset perintah yang gagal tersimpan (disk penuh/keystore) tak lagi diam: polling mundur 60 dtk (fail-closed) alih-alih mengeksekusi perintah yang tak bisa dikonfirmasi lalu memutar ulang massal setelah restart.

## [1.6.252] - 2026-10-16
### Fixed
- Saat kredensial diblokir, pesan tetap diantre tapi tak lagi memicu worker yang pasti gagal (hemat baterai; antrean terkirim otomatis setelah token diperbaiki via revive/start).

## [1.6.251] - 2026-10-16
### Fixed
- Polling `getUpdates` kini hormati flood-wait global 429 (tidur sampai masa rate-limit habis), tak lagi memperpanjang pemblokiran Telegram saat antrean kirim sedang ditahan.

## [1.6.250] - 2026-10-16
### Fixed
- `/restart`/watchdog tak lagi membisukan perintah 5 menit: 409 dalam 60 dtk setelah restart dianggap konflik-sendiri (backoff 60 dtk, tanpa notif "another client"); 409 murni eksternal tetap 300 dtk + notif.

## [1.6.249] - 2026-10-16
### Fixed
- `/jemput` tak lagi melucuti oleh fix lokasi buruk: notif + disarm hanya bila akurasi fix setidaknya seketat radius jemput; fix kasar (mis. jaringan ±1500 m) menunda, bukan memicu palsu.

## [1.6.248] - 2026-10-16
### Fixed
- Polling perintah tak lagi mati selamanya saat Telegram menolak offset (400): 2x gagal hanya backoff, gagal ke-3 beruntun resync offset ke 0 (sembuh sendiri; perintah basi hasil replay tetap ditolak guard kedaluwarsa).

## [1.6.247] - 2026-10-16
### Fixed
- `SendMessageWorker`: pesan yang ditolak permanen Telegram (HTTP 4xx, mis. format salah) langsung di-drop + notif drop, tidak lagi masuk retry transient yang menahan jendela 20 pesan dan buang kuota/baterai.

## [1.6.246] - 2026-10-16
### Fixed
- Perbaiki error kompilasi v1.6.245: `DisplayManager.DISPLAY_ID_PRIMARY`/`DISPLAY_ID_DEFAULT` tidak ada di SDK; pakai `Display.DEFAULT_DISPLAY`.

## [1.6.245] - 2026-10-16
### Fixed
- Kamera foto miring di landscape: rotasi JPEG via DisplayManager, bukan context.display (service tidak punya display).
- getUpdates error 400 berisi "offset" tidak lagi reset lastUpdateId ke 0; update lama tidak diproses ulang setelah restart.
- NotificationForwarderService batas antre pakai MAX_QUEUED (64), konsisten; tidak drop paksa di 32.
- isConfigured() tidak tergantung enkripsi menyala; token + chat ID cukup untuk monitoring.
- Ganti token tidak lagi menghapus watch keywords/numbers, geofence, dan cursor SMS/panggilan.

## [1.6.244] - 2026-10-16
### Fixed
- Stop service tak lagi berisiko mematikan start baru yang datang susulan.

## [1.6.243] - 2026-10-16
### Fixed
- Masa jeda rate-limit tak lagi hangus saat loop restart (tak memancing 429 berulang).

## [1.6.242] - 2026-10-16
### Fixed
- Pesan masuk langsung ditulis tahan-mati (tak hilang bila proses mati sedetik kemudian).

## [1.6.241] - 2026-10-15
### Fixed
- Worker restart coba lagi berbatas saat storage terkunci (tak langsung menyerah; jalur utama unlock tetap lewat receiver).

## [1.6.240] - 2026-10-15
### Fixed
- Laporan crash berbarengan kini digabung (tak ada yang dibuang); hapus/tulis berkas tertunda hanya bila isi tak berubah (laporan baru selamat, kirim ganda terbatas).

## [1.6.239] - 2026-10-15
### Fixed
- Error fatal worker tak lagi disamarkan jadi sukses (diteruskan ke WorkManager: backoff eksponensial + terlihat di diagnostik).

## [1.6.238] - 2026-10-15
### Fixed
- Blob antrean rusak langsung dibuang (tak dibaca ulang selamanya, storage tak bocor).

## [1.6.237] - 2026-10-15
### Fixed
- Batal kirim pesan panjang kini dihormati (sisa antre tetap terjaga, worker lama berhenti, tak tumpang-tindih).

## [1.6.236] - 2026-10-15
### Fixed
- Notifikasi prioritas tak lagi macet permanen (kirim langsung di jalur serial, tanpa tunggu diri sendiri).

## [1.6.235] - 2026-10-15
### Fixed
- Berkas terlalu besar (413) dibuang dengan kabar (foto/video/audio; antrean tak macet).
- Watchdog screenshot menaikkan generasi (basi dibuang, flag tak dicuri).
- Hash foto hanya disimpan saat terkirim (adegan tertunda tak ditekan).
- Rekam audio tolak saat backlog penuh (selaras foto/video).
- Reset kursor SMS/call sinkron; wake-ping lock konsisten; `clearQueue` tanpa tulis-lomba.

## [1.6.234] - 2026-10-15
### Fixed
- Perintah purba (>1 jam, mis. replay resync) dibuang diam-diam; yang basi (<15 mnt) tetap dibalas.

## [1.6.233] - 2026-10-15
### Fixed
- Job bunyi/rekam lama di-cancel sebelum referensi ditimpa (restore volume tak balapan).

## [1.6.232] - 2026-10-15
### Fixed
- Angka perintah rentang-tegas (`/photointerval`, `/patrolinterval`, `/pause`, `/syncinterval`, `/ring`): di luar rentang ditolak, bukan diam-diam dijepit.
- `/history` batasi 7–20 digit (pola LIKE raksasa tertutup).

## [1.6.231] - 2026-10-15
### Fixed
- `forceReset()` hentikan + rilis perekam video aktif dan buang parsialnya (mic tak gantung, tanpa mp4 yatim).
- `onListenerDisconnected()` jadwalkan 1x coba-ikat ulang 60 dtk bila kena throttle (forward tak mati lama).

## [1.6.230] - 2026-10-15
### Fixed
- 400 permanen benar tak antre (log kini jujur; worker memang langsung buang).
- Parsing perintah satu splitter (`\s+` di tahap-1 dan `/sms`); edit pesan diproses (bukan hilang diam-diam).
- `/smsconfirm` tahan jam-mundur (pending tak dihapus skew).
- `AdminReceiver` `refreshInstance()` sebelum baca (peringatan admin tak hilang saat keystore blip).

## [1.6.229] - 2026-10-15
### Fixed
- Pairing: kode rotasi 24 jam + lockout global 1 jam setelah 10 tebak gagal (throttle per-pengirim tetap).
- Polling perintah tak lagi ikut auth-block chat-400 (`isPollBlocked` khusus 401/403; kirim tetap diblokir).

## [1.6.228] - 2026-10-15
### Fixed
- Simpan identitas tolak mode volatil (gagal jujur + toast, bukan toast saved palsu).
- Status Setup pakai `isConfigured()` (selaras toggle/revive; volatil = belum valid).

## [1.6.227] - 2026-10-15
### Fixed
- Tombol `=` kalkulator merentang 3 baris (sel kosong kanan-bawah tertutup).
- Judul `# Changelog` + presisi docs (pemicu `main`-only, pengecualian tag di `concurrency`).

## [1.6.226] - 2026-10-15
### Fixed
- `/smsconfirm` tolak jam-maju/mundur seperti `/sms` (pending tak hangus palsu / tak abadi).
- `onDestroy` batalkan `serviceScope` sebelum `super.onDestroy()` (selaras `NotificationForwarderService`, tanpa job bocor).

## [1.6.225] - 2026-10-15
### Fixed
- Log error/warning release dibungkus `BuildConfig.DEBUG` (8 titik: kalkulator, admin, scheduler) — tak bocor ke logcat.
- Consent screenshot basi auto-hapus saat sistem menolak token (`SecurityException`) — status Setup jujur `off`, bukan `granted` palsu.

## [1.6.224] - 2026-10-15
### Fixed
- Kolom interval Setup kosong dari XML sehingga default `BuildConfig.SYNC_INTERVAL` (CI) tampil dan tak tertimpa `5`/`1`.
- `/ring`, `/record`, `/recordvideo`, `/hilang` kunci flag sendiri dulu sebelum cek lawan (adu audio tertutup).
- `toggleMonitoring` tawarkan izin notifikasi (Android 13+) sebelum dialog persetujuan.
- `cek-cepat.sh` pindai tracked tree saat tak ada staged (cek secret tak lagi buta di CI).

## [1.6.223] - 2026-10-15
### Fixed
- Konfigurasi volatil tak lagi dianggap tersimpan (`isConfigured` syarat storage terenkripsi; gagal keystore = terlihat belum tersimpan, bukan monitoring mati diam-diam).
- Hapus pesan antrean tulis sinkron di luar main-thread (duplikat kirim ulang pasca-crash menyempit).

## [1.6.222] - 2026-10-15
### Changed
- Workflow disederhanakan: verifikasi-path jadi job pertama `build.yml` (build nunggu lolos); berkas workflow lama dihapus.

## [1.6.221] - 2026-10-15
### Fixed
- Perbaiki gagal kompilasi v1.6.220: helper izin ganda (sudah ada).

## [1.6.220] - 2026-10-15
### Fixed
- Status Setup tak hilang saat jaringan lempar exception.
- Flag monitoring ON hanya setelah service benar jalan.
- Histori notif hormati opt-out (disable = tak catat).
- Menu bot retry tiap jam bila gagal daftar.
- Lensa fallback dikabari ke owner.
- `/lastsms`, `/lastcalls`, `/history` hemat query batch.
- Digest tulis cap 200 data.

## [1.6.219] - 2026-10-15
### Fixed
- Flush media kuras tuntas (maks 3 batch): backlog tak lagi dipangkas prune sebelum dicoba.
- FGS gagal start jadwalkan boot-restart (tak diam).
- Ganti identitas reset throttle/sync/foto/digest/begadang/jemput.
- Auth-blocked tak dihitung sepi (sync tak merenggang sia-sia).

## [1.6.218] - 2026-10-15
### Fixed
- `/pause` jujur (manual tetap bisa); `/resume` tak hapus jeda terjadwal; menu basi grup tak spam owner.
- Izin SMS/call dicek di `/lastcalls`, `/lastsms`, `/history`.
- Callback tanpa sender dijawab; tombol Stop di menu; alias + `/start` di help/menu.
- Sekolah 08–15 (teks); jejak prune 24 jam; jemput butuh fence nyala; digest manual tak tekan malam.

## [1.6.217] - 2026-10-15
### Fixed
- Ganti identitas reset offset update + initial-sync + wake-ping (bot baru tak tuli).
- Boot fast-path ikut jadwalkan watchdog; umur-restore negatif tak hapus state.
- Jalur cepat notif ikut baca TEXT_LINES.

## [1.6.216] - 2026-10-15
### Fixed
- Watch keyword + echo + status di-escape HTML (anti 400).
- Command echo di-escape; Setup redact ikut chat ID.
- Worker cek kredensial tiap pesan (tutup jendela 4 pesan basi).
- Boot generic-fail ikut jadwalkan kirim antrean.

## [1.6.215] - 2026-10-15
### Fixed
- Watchdog revive bila mati; kredensial anti-torn-read; job `@Volatile`.
- Squeeze hormati jeda/opt-out; wake 401 backoff 5 mnt.
- Kamera: seq dalam lock; fallback hapus semua handler; sapu mp4 kosong.
- Premium 976/900 prefix; hitung frekuensi normalisasi digit; sweep tahan jam mundur.

## [1.6.214] - 2026-10-15
### Fixed
- Toast validasi Setup aman dari activity mati (6 titik).
- Guard boot 10 mnt hanya bila service jalan (reboot ganda tetap revive).
- Verifikasi-path jalan juga di PR (gagal-cepat sebelum build).
- `gradlew` executable.

## [1.6.213] - 2026-10-15
### Fixed
- Penjadwal: hitung ganda counter diperbaiki (gagal enqueue kembalikan hitungan).
- Boot retry auth dibatasi 5x (andalkan watchdog 15 mnt).
- Callback: dedup dulu, jawab sekali.
- Replay: offset di-pre-claim untuk perintah mutating.
- Polling 5xx/408 backoff 60 detik.
- Squeeze prioritas jalan background (anti ANR).
- Watch keyword di-escape.
- Wake 400-offset reset setelah 3x gagal.
- Ganti identitas reset kursor/SMS pending/ring/screenshot/drop.

## [1.6.212] - 2026-10-15
### Fixed
- HTC QUICKBOOT didukung lagi (manifest + receiver).
- Ring-restore boot hanya hapus state bila restore sukses.
- `cancelBootRestart` baru; dipanggil saat Clear + Stop; `patrolInterval` ikut reset saat Clear.
- Screenshot-notice boot hanya bila terkonfigurasi.
- Stage SMS kabari bila teks beda; pending aman dari jam mundur.
- Test koneksi murni cek jaringan (validasi interval hanya di Save).
- Guard start anti macet (`finally`).

## [1.6.211] - 2026-10-15
### Fixed
- Perbaiki gagal kompilasi v1.6.210: `capturing` tak ada di service (buang), `markHandled` companion terima `context` (7 call site).

## [1.6.210] - 2026-10-15
### Fixed
- **MessageQueue** corrupt JSON tidak lagi hapus pesan asli (recovery tanpa overwrite).
- **ringBusy/recordBusy** stuck true permanen → reset di `onCreate` service.
- **SMS BroadcastReceiver** race: registerReceiver sekarang SEBELUM sendMultipartTextMessage.
- **Media prune/flush race**: `.sent` rename protected, prune skip saat flush jalan.
- **BootRestartWorker** auth-blocked → `Result.retry()` bukan `success()`.
- **CrashReporter** monitoring disabled tetap kirim crash report (hanya skip kalau token/chat kosong).
- **MessageScheduler** counter race: atomic compare-and-set untuk `outstandingSends`.
- **ContentQuery**: error non-SecurityException sekarang di-log, tidak ditelan.
- **MessageQueue volatileOnly**: sekarang selalu coba encrypted prefs dulu, fallback volatileOnly bila gagal.
- **PreferencesManager**: `openPrefs` tidak lagi return `MemoryPrefs` di main thread.
- **NotificationForwarderService**: `prefsRef` & `queueRef` diinit synchronously di `onCreate`.
- **MainActivity**: debug build tidak lagi `return` early, monitoring tetap jalan.
- **BootReceiver**: debounce tambahan 10 menit (`last_full_boot_elapsed`) cegah duplicate OEM >60dtk.

## [1.6.209] - 2026-10-15
### Fixed
- Command auth bypass di private chat: perintah mutating/sensitif sekarang butuh owner meski di private chat.
- SMS BroadcastReceiver race: registerReceiver sekarang SEBELUM sendMultipartTextMessage.
- Watchdog sekarang memonitor commandJob & patrolJob via beat timestamps.
- MessageScheduler counter race: atomic compare-and-set untuk outstandingSends.
- ContentQuery: error non-SecurityException sekarang di-log, tidak ditelan.
- MessageQueue volatileOnly: sekarang selalu coba encrypted prefs dulu, fallback volatileOnly bila gagal.
- PreferencesManager: openPrefs tidak lagi return MemoryPrefs di main thread.
- NotificationForwarderService: prefsRef & queueRef diinit synchronously di onCreate.
- MainActivity: debug build tidak lagi return early, monitoring tetap jalan.
- BootReceiver: debounce tambahan 10 menit (last_full_boot_elapsed) cegah duplicate OEM >60dtk.
- sendFitted >4000 chars: fallback plain per-chunk.
- sendSmsPending: timeout per-part, pending dibuang bila nomor premium/darurat, throttle gagal total 60dtk.
- Media prune: rename .sent pada sukses, prune hanya non-.sent.
- Worker Rejected: di-requeue seperti Failed + retryCount+1.
- sendInitialData: cursor hanya maju saat chunk sukses.
- CrashReporter fallback: klasifikasi error + retry passif.
- CrashReporter reentrancy guard.
- BootReceiver: companion object brace tertutup.

## [1.6.208] - 2026-10-15
### Fixed
- Perbaiki gagal kompilasi sejak v1.6.207: variabel `try` tak terlihat di `finally` (video/audio progress), dan `when` CrashReporter yang buang nilai `delivered`.

## [1.6.207] - 2026-10-15
### Fixed
- Prune cache tak lagi hapus file media yang BELUM terkirim (rename `.sent` pada sukses, prune hanya non-sent).
- Fallback plain 400 non-chat/non-rights sekarang di-queue (tak lagi drop silent di MonitoringService, SendMessageWorker, CrashReporter).
- Worker `Rejected` kini di-requeue (sama seperti `Failed`).
- `sendInitialData` aman: cursor hanya maju saat chunk sukses, restart = resume tanpa duplikat.
- CrashReporter fallback klasifikasi error + retry passif aman.

## [1.6.206] - 2026-10-15
### Fixed
- `/smsconfirm` validasi ulang izin + blokir premium/darurat; gagal total ikut throttle 60 detik; job lama dibatalkan.
- Stage `/sms` baru kabari pending lama yang dibuang.
- README: tabel perintah lengkap (46) + pairing + koreksi screenshot per-reboot.

## [1.6.205] - 2026-10-14
### Fixed
- `/hilang` tak blokir perintah 60 detik: bunyi jalan background + cegah tumpang.
- Begadang dievaluasi 1x/hari (sebelumnya query tiap siklus 05–11).
- `/waspada` cache kontak per nomor + abaikan skor luar-rumah bila posisi basi.
- Header `/jejak` jujur (10 terakhir).

## [1.6.204] - 2026-10-14
### Added
- `/jejak`: 24 titik lokasi terakhir + tautan rute peta.
- `/waspada`: skor 0–100 dari watch hit, nomor baru, begadang, luar rumah.
- `/hilang`: bunyi 60 detik + lokasi + foto sekaligus.
- `/jemput <meter>|off`: kabari sekali saat dekat rumah (butuh `/home`).
- Alert begadang otomatis (aktivitas 23–05 >10, pagi 1x/hari).
- Grafik 24 jam di `/minggu`.

## [1.6.203] - 2026-10-14
### Fixed
- Penjadwal kirim dibatasi (maks 3 antre + 1 jalan): offline lama tak lagi numpuk puluhan run WorkManager; run yang antre menguras semua + sembuh-sendiri 10 menit + reset tiap ganti proses.

## [1.6.202] - 2026-10-14
### Fixed
- Revive hormati lokasi latar (tak revive pincang; sentinel yang mengabari).
- Toast status aman dari activity mati; start monitoring anti double-tap; toggle notif debounce 2 detik.
- Kabar reboot-boot gagal start maks 1x/jam (tak ganda saat unlock beruntun).

## [1.6.201] - 2026-10-14
### Fixed
- Initial-sync selesai hanya bila benar tuntas; gagal → coba lagi saat start berikut (kursor sudah maju, tanpa kirim ulang).
- Flush media tak lagi prune saat nol progres (offline + backlog tak hapus file belum terkirim).
- Sisa chunk gagal ikut budget retry (`+1`), racun berhenti di 20x; pesan prioritas anti-gusur + drop-notice sebut prioritas.
- Save offline bisa bila kredensial tak berubah (interval saja).
- Ganti token/chat reset aturan pemilik lama (watch, geofence, curfew, digest, lokasi, health).
- Status tampilkan Device admin; boot abaikan umur-restore negatif (jam mundur).

## [1.6.200] - 2026-10-14
### Fixed
- Tombol inline selamat dari dateless-expiry: callback tanpa tanggal kini dapat menu baru (bukan "kedaluwarsa" untuk semua tombol).
- `checkCurfew` jemput posisi sendiri bila basi >30 menit (patroli mati/malam tak lagi bikin curfew diam); tanggal alert curfew vs sekolah dipisah.
- Rincian sensitif (`/home`, `/school`, `/jampulang`, `/watch`, `/watchnomor` status) hanya via private chat; penolakan non-owner di grup kini senyap.
- State geofence korup sembuh diam-diam tanpa alert palsu.
- Digest/mingguan hitung watch sekali per pesan; `/watch` sebut kata yang dibuang (<3 huruf).
- 4 field tambah `@Volatile`; "Foto dijeda … mnt".

## [1.6.199] - 2026-10-14
### Fixed
- `CameraService` anti-curi flag: nomor generasi sesi — callback basi pasca-`forceReset` tak lagi matikan capture baru.
- Lensa fallback jujur: tanpa lensa yang diminta pakai depan/belakang lain (bukan eksternal) + log; tmp disapu saat init.
- `clearCredentials` tuntas: sapu file kamera/video/audio/screenshot, hapus menu global walau belum pairing, interval kembali default.
- Izin notifikasi pakai launcher sendiri (tak lagi picu Settings); status refresh saat screenshot ditolak; device-admin gagal ada toast.
- Kalkulator: histori ikut reset dari Error.
- Forwarder ikut sensor chat ID di log.
- Verifier token tutup lubang `listOf("id:token")` (kecuali baris definisi regex).
- `safeCut` fallback mundur cari batas aman; `unescape` tambah `&nbsp;` + numerik umum.

## [1.6.198] - 2026-10-14
### Fixed
- `/sms` tolak nomor darurat eksplisit (`112`/`110`/`119` dkk, termasuk format `00`/`0`-prefixed).
- Update edit (`edited_message`) diabaikan: edit pesan tak lagi eksekusi-ulang perintah mutating; set `NO_REPLAY_COMMANDS` mati dibuang.
- Perintah mutating/sensitif tanpa stempel waktu kini ditolak kedaluwarsa (tutup bypass via update malformed).
- `/ring` batal bila volume awal tak terbaca (tak ada lagi max permanen).
- `/ping` ikut awasi `patrolJob` seperti watchdog.
- `isAuthBlocked` tahan jam mundur (blokir tetap saat `now < at`).
- `/history` coba cocok-persis berindeks dulu sebelum pindai fuzzy (SMS + call log).
- Crash-report dikirim sesudah service coba start (restart boot tak tertahan 90 detik).
- Pengingat tampil bila penyimpanan volatil (token bisa hilang saat reboot).

## [1.6.197] - 2026-10-14
### Fixed
- Prioritas dicek sebelum dedup 10 detik: OTP/bank identik beruntun tetap terkirim.
- `removeMessages`/`removeMessage` persist paksa + `clearQueue` commit: kirim-ganda/hilang saat kill dalam jendela 2 detik tertutup.
- Expiry tahan lompat jam: prune yang gugurkan >50% antrean sekaligus dibatalkan (dugaan jam maju).
- `sendChunked` batal antar-part: original dihapus setelah sisa diantrekan (urutan aman-ganda), id diteruskan dari worker.
- `lastLocAt` ditulis tiap fix valid (bukan hanya saat pindah) agar `checkCurfew` tak buta saat HP diam.
- Test koneksi tak lagi timpa interval (`saveTestIdentity` khusus token+chat).
- Blokir auth tak lagi gugur saat jam mundur (`now >= errAt` wajib).
- Migrasi chat-id supergroup tulis sinkron (`setChatIdSync`) di worker + 2 service.
- `onTaskRemoved` selamatkan batch notif (sebelumnya hanya `onDestroy`).
- Boot berisik: kabar antrean bila service gagal start + kabar izin screenshot hangus pasca-reboot.

## [1.6.196] - 2026-10-14
### Fixed
- Alias tanpa-slash kini khusus private chat owner; obrolan grup tak lagi picu balasan bot (berisik + bocor `/help`/jam malam ke grup). Jalur `/` tetap seperti semula. Gate tunggal di poll-loop, resolusi ganda di inner dibuang.
- `/watchnomor` cocok lintas format untuk 7–9 digit via aturan ekor-7 (sebelumnya butuh exact/sufiks ≥10 digit).
- Sentinel sepi 12 jam jadi 48 jam agar HP sunyi normal tak di-alert tiap 12 jam.
- Jam malam bungkus tengah malam (`h<5` bila curfew sore/malam); cek sekolah Senin–Jumat 08–16 dan didokumentasikan di `/help` + `/jampulang status`.
- `/digest` manual tandai tanggal digest agar tak ganda dengan auto 21:00.
- `checkCurfew` lewati posisi basi >6 jam (`lastLocAt` baru, ditulis tiap `rememberSentLatLon`).
- `checkAndSendNewData` hitung frekuensi + kontak sekali per batch (sebelumnya 3 query per item).
- Indonesia: `/location`, `/camera`, `/version`, `/storage`, `/apps`, `/log`, sinkron awal, tipe SMS/panggilan; label fence `Rumah/Sekolah`.

## [1.6.195] - 2026-10-14
### Added
- `/darurat` (owner): lokasi + foto + baterai + antrean sekaligus, tembus jeda monitoring.
- `/jampulang HH:MM|off` (owner): alert 1x/hari bila lewat jam malam masih di luar rumah; plus alert jam sekolah (Senin–Jumat 08:00–15:00 di luar area sekolah).
- `/minggu` (owner): laporan 7 hari (top kontak, jam tersibuk, begadang 23–05, watch hit) + tombol menu.
- `/watchnomor nomor,status,off` (owner): label `🚨 Nomor diawasi` di SMS/panggilan + hitung di digest/mingguan.
- Tombol menu di `/status`, `/ringkas`, `/digest`, `/minggu`.
- Bahasa Indonesia: menu bot, `/help`, `/status`, SMS/panggilan, patroli, dan pesan umum.

## [1.6.194] - 2026-10-14
### Added
- `/watch kata1,kata2|status|off` (owner): alert kata berbahaya di SMS + notif prioritas langsung + label `⚠️ Watch`.
- Geofence `/home` + `/school` (`lat,lon,radius|status|off`): alert sekali saat masuk/keluar area, ikut patrol lokasi.
- Nomor tak dikenal: label `🆕 Nomor baru` + frekuensi `🔁 Nx hari ini` di SMS/panggilan.
- `/digest`: ringkasan harian 24 jam (SMS/panggilan, kontak teratas, watch hit, antrean, baterai); otomatis tiap jam 21:00+.
- Alias bahasa alami: `dimana`, `foto`, `ding`, `kunci`, `baterai`, `bantu`, dsb tanpa `/`.
- Sentinel sepi: alert bila data sepi >12 jam, lokasi gagal 5x beruntun, atau antrean ≥50 (throttle 12 jam).

## [1.6.193] - 2026-10-14
### Fixed
- Menu bot: tambah `/patrolinterval` (owner) + `/start` (publik); hash registrasi ikut `versionCode` agar menu baru benar-benar terdaftar saat upgrade (sebelumnya di-skip diam-diam).
- Buang string mati `status` + `last_sync`.

## [1.6.192] - 2026-10-14
### Fixed
- Hapus peta callback mati (~15 baris, hasil dibuang) — satu sumber kebenaran di `handleCallbackQuery`.
- Baterai + digest jalan walau monitoring di-pause (kesehatan perangkat tetap dikabari).

## [1.6.191] - 2026-10-14
### Fixed
- `upgradeToPersistent`: flag `storageEncrypted` paling akhir (gagal delta bisa retry); `ensureOwnerPairCode` terkunci + cek manual tanpa regex.
- `SetupActivity`: deny screenshot hapus consent lama; hapus menu global + chat saat clear; guard double-tap start/stop; hasil device-admin dibaca; double-launch screenshot digate.
- `BootRestartWorker`: throttle reminder dicatat sesudah notify terkirim.
- Buang mati: `workerRunning`, pool `Background.run` (+ catat di `bekas-hapusan.txt`).

## [1.6.190] - 2026-10-14
### Fixed
- `MY_PACKAGE_REPLACED` tambah `<data scheme=package>` — restart habis-update jalan instan (sebelumnya buta, hanya watchdog).
- Worker: pesan cap-20 yang gugur di run-auth/rate-limit ikut kabar antrean (tak lagi sunyi).

## [1.6.189] - 2026-10-14
### Fixed
- `CameraService`: tmp foto dibersihkan bila tulis gagal; `camera`/`session` ditutup di semua callback error; `rec.stop()` gagal = error (bukan sukses); urutan rilis recorder-dulu; pesan record bawa cause.
- `SetupActivity`: `clearCredentials` ikut cancel worker; `configChanges` cegah recreate+dialog-bocor saat rotasi; deny-permanen diarahkan ke Settings.
- `MonitoringService`: jarak gagal hitung = tak dikenal (bukan miliaran meter).

## [1.6.188] - 2026-10-14
### Fixed
- `NotificationForwarderService`: drop-notice `onDestroy` + `flushSync`; prioritas yang tercekik overload ikut antrean; semua 400 wake picu backoff; revive tanpa gate semua-izin (service jalan degraded + sentinel kabari, kegagalan start tetap via worker).

## [1.6.187] - 2026-10-14
### Fixed
- Balasan saran typo escape input (`Html.escape`) — ketikan `<tag>` tak lagi sebabkan 400-drop + counter menyesatkan; `/version` escape field Build OEM.
- `addWakePingIds` atomik di bawah kunci (tak ada update hilang).

## [1.6.186] - 2026-10-14
### Fixed
- `startMonitoring`: restore antrean + pulihkan volume pindah background (tak lagi block main thread).
- Throttle jam-masa-depan: baterai/digest/izin/SMS/pending-SMS tak lagi bisu bila cap tersimpan di masa depan (jam mundur) — fail-open.
- `BootReceiver.lastHandleAt` tulis di bawah kunci; `cachedPatrol*` + `backgroundHandler` volatile.
- Bersih-bersih: hapus string `service_running` + style `CalculatorButton.Equals` mati; cabut izin `MODIFY_AUDIO_SETTINGS` yang tak dipakai.

## [1.6.185] - 2026-10-14
### Fixed
- `onStartCommand` tak lagi refresh prefs di main thread (potensi ANR): keputusan cepat pakai instance aktif, refresh + cek ulang jalan background.
- `rememberOwner` verifikasi tulis disk dulu sebelum isi cache (tak ada owner hantu).
- Dedup callback hanya catat id yang lolos gate (id asing tak lagi mengusir entri legit).
- `/contacts` toleran format nomor (7 digit ekor + cocok lintas-format, presisi dijaga di kode).
- Digest tulis "SMS" (sebelumnya "SMS masuk" padahal hitung semua).
- Initial-sync sukses reset idle adaptif (tak langsung merenggang).
- Hint pairing hanya bila owner belum ada.
- `/uptime` tak negatif saat jam mundur.

## [1.6.184] - 2026-10-14
### Fixed
- `NotificationForwarderService`: timer batch uzur-ulang bila ada item tertinggal (`scheduleBatchLocked`); notifikasi prioritas yang kena spam-throttle diantrekan (tak lagi hilang), bukan dikirim langsung.
- `SetupActivity`: `configMutex` serialkan persist Save-vs-Test (pemenang selalu yang terbaru, tanpa last-writer acak); migrasi supergroup `sendStatusNow` hanya bila config tak berubah di tengah jalan.
- `PreferencesManager`: `upgradeToPersistent` delta-pass kedua (tulis saat komit lambat tak hilang); `saveCredentials` jujur `false` bila `commit` gagal; migrasi tipe asing di-log.
- `CameraService`: `captureLock` serialkan entry capture vs `forceReset` (tak ada lagi dua sesi berebut device); `backgroundThread` volatile.
- `MonitoringService`: hash foto nol dinormalkan (bingkai polos ikut cek duplikat).

## [1.6.183] - 2026-10-14
### Added
- Ringkasan berkala tiap 6 jam (SMS/panggilan masuk, antrean, baterai) + peringatan baterai < 15% (throttle 24 jam).
- Notifikasi prioritas (OTP, panggilan tak terjawab, bank/dompet) lewati batch langsung terkirim; filter spam tetap berlaku.
- Lokasi hemat: patrol tak kirim ulang koordinat bila < 100 m dari terakhir; `/location` manual sebut jarak dari laporan terakhir.
- Perintah owner `/ringkas` (status + baterai + antrean + izin sekaligus; menu bot + `/help`).
- SMS pintar: label "Kemungkinan OTP"/"Kemungkinan promo" di tiap SMS.

## [1.6.182] - 2026-10-14
### Added
- Mode malam: 23.00–05.00 waktu perangkat, foto otomatis + patrol lokasi berhenti sendiri; SMS/panggilan/notifikasi/perintah tetap jalan. Perintah owner `/night on|off` (menu bot + `/help`), `/status` tampilkan status malam.

## [1.6.181] - 2026-10-14
### Added
- Koreksi salah ketik perintah: perintah tak dikenal dapat saran "Maksudnya /status?" via jarak edit (Levenshtein + tertukar huruf tetangga), ambang ketat untuk perintah pendek agar tak asal tebak.

## [1.6.180] - 2026-10-14
### Added
- Jaga kamera pintar: bingkai gelap (luma < 12) dilewati + foto duplikat (aHash 8×8, Hamming ≤ 6 vs foto terakhir `lastPhotoHash`) dilewati; manual `/photo` dapat kabar singkat, otomatis/patrol senyap. Berlaku untuk semua jalur foto.

## [1.6.179] - 2026-10-14
### Added
- Hemat baterai adaptif (tanpa seting manual): interval sinkronisasi data merenggang otomatis saat sepi (maks 4× interval user atau 30 mnt) dan langsung rapat lagi saat ada SMS/panggilan baru; watchdog ikut siklus efektif. Foto otomatis dilewati saat perangkat sepi + layar mati (3 siklus kosong); `/photo` manual dan perintah lain tak terpengaruh. `/status` tampilkan status adaptif.

## [1.6.178] - 2026-10-14
### Fixed
- Tanpa izin tak ada lagi upaya foto/rekam + tak ada media blank: `recordAndSendAudio`/`recordAndSendVideo` cek mic di awal (video juga cek policy kamera + auto-pause); `onPhotoTaken` tolak file kosong; `sendPhotoFile`/`sendVideoFile`/`sendAudioFile` buang file kosong sebagai `DROPPED`; 3 loop flush lewati file kosong diam-diam.

## [1.6.177] - 2026-10-14
### Added
- Sentinel izin pintar: ingat snapshot tiap izin (`permSnapshot`) — lapor transisi "Izin hilang" saat dicabut dan "Izin kembali" saat diberikan lagi (tanpa throttle, karena berbasis transisi); pengingat gabungan 24 jam tetap untuk yang belum kembali; laporan inventaris penuh sekali saat baseline. Bit dependen dibekukan (lokasi latar ikut lokasi, akses notifikasi ikut sakelar `/notif`) agar tak ada alert palsu.
- Perintah owner `/perms` (sensitif + freshness 300 dtk, terdaftar di menu bot + `/help`): daftar checklist ✅/❌ 10 titik izin.

## [1.6.176] - 2026-10-14
### Added
- Sentinel izin di `MonitoringService.checkPermissionHealth` (tiap siklus sinkronisasi): cek 10 titik (baca/kirim SMS, log panggilan, kontak, kamera, mikrofon, lokasi + lokasi latar, akses notifikasi, baterai tanpa batas); bila ada yang dicabut, kirim peringatan gabungan ke owner, throttle 24 jam (`permHealthAlertAt`). Pencabutan diam-diam (termasuk auto-reset Android bila app lama tak dibuka) kini ketahuan, bukan sunyi.

## [1.6.175] - 2026-10-14
### Fixed
- `NotificationForwarderService`: spam-throttle tak lagi memanggil `noteDroppedNotification` (notice "Spam filter" khusus sudah cukup; tak ada lagi peringatan "rejected by Telegram (400)" yang salah atribusi).
- `SendMessageWorker`: `failedIds` yang terkumpul sebelum 429/auth dalam satu run ikut `registerTransientFailures` (budget 20 tak lagi telat satu run).
- `MessageQueue.registerWithBudget`: `maxRetry` hanya dari pesan yang dipertahankan (pesan yang di-drop tak lagi menggerek backoff s/d 480 dtk).
- `CrashReporter`: 400 chat-missing catat `credentialError` (flush tak lagi coba buta tiap launch); tulis sisa laporan atomik via tmp+rename (`writePendingRest`).
- `MainActivity`: 4 log rilis (`not configured`, izin, lokasi, gagal start) kini hanya di build debug.

## [1.6.174] - 2026-10-14
### Fixed
- `PhoneNumbers.suffixMatch` tolak sisi pendek <10 digit: short-code (mis. `7890`) tak lagi diakui sebagai nomor target 10+ digit.
- `ContentQuery` bedakan `SecurityException` (log `permission denied`, tanpa fallback buta) dari error OEM lain; `SmsRepository`/`CallLogRepository` `limit.coerceIn(1,200)` + log saat pindai 4 halaman terpotong.
- `Background` pool `run()` pakai `CallerRunsPolicy` (gantikan `DiscardPolicy` yang buang diam-diam).
- `CameraService.startBackgroundThread` lewati `join(500)` di main thread, pola sama dengan `stopBackgroundThread`.
- `MonitoringService`: hapus cabang dead `NO_REPLAY` di callback; expiry callback ikut `SENSITIVE_COMMANDS` (menu basi >300 dtk untuk `/photo`/`/location`/`/screenshot` ditolak); SMS multipart hitung sekali per part (`confirmedParts`); gagal persist offset kini `Log.w` (tak lagi diam).
- `NotificationForwarderService`: fallback plain 400 rights-limited ikut antre ulang; `onDestroy` `flushSync()` usai salvage batch; drop spam-filter ikut agregat drop-notice.
- `MessageScheduler.scheduleRateLimited` selalu via `scheduleMessageSendNext` (`REPLACE` tunggal), tak lagi cabang pada flag volatil `workerRunning`.
- `SetupActivity`/`PreferencesManager`: `saveCoreConfig`/`saveTestCredentials` kembalikan Boolean; toast `setup_test_fail(persist)` bila `commit()` gagal + cek ulang seq usai persist (tutup balapan save-vs-test).

## [1.6.173] - 2026-10-14
### Fixed
- `MessageQueue.isFresh` fallback ke wall-clock saat `elapsedRealtime` reset (reboot): antrean offline tak lagi dibuang massal sebagai expired.
- `Background.runCritical` (executor khusus antrean tak terbatas + `CallerRunsPolicy`): tugas restore prefs/antrean dan `BootReceiver`/`AdminReceiver` tak lagi bisa dibuang diam-diam oleh `DiscardPolicy`.
- `PreferencesManager.setMonitoringActive` tulis `commit()` sinkron agar service langsung baca nilai baru.
- `SetupActivity.saveSettings` batal simpan + toast `setup_test_fail` saat verifikasi `getMe`/`getChat` gagal jaringan (offline tak lagi simpan kredensial unverifikasi); `clearCredentials` reset juga `monitoringPaused`/`photoPausedUntil`/`patrolEnabled`.
- `PhoneNumbers` gerbang sufiks 7 ke 10 digit + fallback `SmsRepository`/`CallLogRepository` 10 ke 4 halaman (cegah salah atribusi + pindai 5000 baris).
- `MonitoringService.NO_REPLAY_COMMANDS` dipersempit ke perintah tak-idempoten (`/sms`, `/smsconfirm`, `/ring`, `/record`, `/recordvideo`, `/lock`); tap tombol inline non-mutasi tak lagi kedaluwarsa oleh tanggal menu; cap dedup update 300 ke 500 + callback 200 ke 500.
- `MonitoringService.teardownJobs` pulihkan volume alarm bila `ringBusy`; SMS parsial pertahankan pending (bukan clear) + `PendingIntent` per-part bawa extra `part`/`of`.
- `NotificationForwarderService.forwardLocked` fallback plain kini antre ulang + jadwal saat `429`/`408`/`5xx` (bukan drop 400); reset offset wake pakai `lastUpdateId` segar; dedup tanpa `notifId`; jalur burst ikut notif spam throttled.
- `SendMessageWorker.sendChunked` sisa chunk antre ulang dengan `retryCount` asal (budget 20 tak lagi reset).
- `ParentalMonitorApp` channel resume jadi `IMPORTANCE_MIN` senyap (catatan: channel lama di perangkat existing baru berubah setelah install ulang).
- `.github/workflows/build.yml` artefak `apks` ikut sertakan `mapping.txt` rilis agar crash ter-deobfuscate.
- Kalkulator tombol `C` pakai `@color/calc_accent_text` + `drawable-night/calc_btn_accent.xml` agar ikut tema malam; komentar manifest Uzbek jadi Indonesia; koreksi klaim `QUICKBOOT` `1.6.170` (penanganan dipertahankan).

## [1.6.172] - 2026-10-11
### Fixed
- `MonitoringService.onCreate` warm-up prefs via pool bersama `Background` gantikan `Thread` mentah per start.
- `AdminReceiver.onDisabled` via pool bersama `Background` gantikan `Thread` daemon mentah per broadcast.
- `MonitoringService.handleTelegramCommand` hitung `redactToken` sekali per command gagal (sebelumnya 2×: log + balasan).
- `PhoneNumbers.matchesPrecomputed` + `SmsRepository`/`CallLogRepository`: `altVariant(want)` dihitung sekali per query, bukan per baris (500 baris × 10 halaman).
- `SmsRepository`/`CallLogRepository` ambil oldest via `page.last()` (query sudah `DATE DESC, _ID DESC`) gantikan pindai `minWithOrNull` O(n) per halaman.

## [1.6.171] - 2026-10-11
### Fixed
- `CameraService.stopBackgroundThread` lewati `join(500)` saat di main thread: watchdog timeout kamera jalan di `MainLooper`, join + `close()` binder di sana bisa bekukan UI hingga 500 ms.
- `ParentalMonitorApp`/`MainActivity` warm-up prefs via pool bersama `Background` gantikan `Thread` mentah per start.
- `MessageScheduler.scheduleMessageSendNext` buang `Constraints` lokal mati (yang dipakai `netConstraints` bersama).

## [1.6.170] - 2026-10-11
### Fixed
- `MonitoringService.sha256Hex` tanpa `String.format` per byte: tabel hex + `CharArray`, gantikan 32× alokasi `Formatter`.
- `MonitoringService` parse perintah Telegram sekali jalan (`head`/`command`/`arg` dari `rawText`); pindai `substringBefore`/`lowercase` ganda per update dihapus.
- `MonitoringService.takeScreenshotFrame` satu bitmap saat `rowStride` ber-padding: salin baris-per-baris ke buffer padat, gantikan bitmap `rowWidth` + `createBitmap` crop kedua.
- `MonitoringService.fetchLocation` pakai `getCurrentLocation` + `CancellationSignal` di API 30+, fallback `requestSingleUpdate` di bawahnya.
- `BootReceiver` jalan via pool bersama `Background` gantikan `Thread` mentah per broadcast; penanganan HTC `QUICKBOOT_POWERON` dipertahankan untuk kompatibilitas ROM lama.
- `SetupActivity` cache `saveButton` agar `findViewById(R.id.setupSaveButton)` sekali, bukan dua kali.
- `AndroidManifest.xml` komentar izin diterjemahkan ke Bahasa Indonesia.
- `MonitoringService.smsReqSeq` seed via `ThreadLocalRandom` penuh gantikan truncasi 28-bit.

## [1.6.169] - 2026-10-10
### Fixed
- `tools/pasang-hook.sh` cadangan ke direktori exec-capable via `core.hooksPath` saat `.git/hooks` di penyimpanan noexec (mis. `/storage/emulated`), agar kait pre-push benar-benar jalan; badan kait `cd` ke root repo dulu.

## [1.6.168] - 2026-10-10
### Fixed
- `Redact.token` tanpa alokasi `Regex` per secret: batas digit dicek manual via `indexOf`, fallback token dilewati bila tanpa `:`.
- `Html.unescape` satu pass (`StringBuilder` + cocokkan 7 entitas) gantikan 8× pindai string; `tagStripRegex` jadi privat.
- `PhoneNumbers.numbersEqualFast` tanpa alokasi 2 `MutableList`: banding sufiks langsung 4 kombinasi.
- `Background` pool ber-timeout (inti 0, maks 2, `keep-alive` 60 dtk, antrean 64, thread daemon) agar tak tahan 2 thread selamanya di Android 7+.
- `NetworkUtils.isChatMissing`/`isRightsLimited` tanpa `lowercase()` + 8× `contains`: pakai `contains(ignoreCase=true)` hubung singkat; 2 pola redundan dibuang.
- `MonitoringService` validasi `/sms` satu loop (digit/`+`/pemisah benign) gantikan 7× pindai `contains`/`any`/`count`.
- `NotificationForwarderService` wake `/ping` kumpulkan `pingIds` dalam loop utama; pindai `filter`+`lowercase` kedua dihapus (filter numerik `freshLast` dipertahankan).
- `SmsRepository`/`CallLogRepository` pola fuzzy via `StringBuilder` (tanpa `toCharArray().joinToString`) dan normalisasi digit sekali per query.
- `TelegramApi` satu `ConnectionPool(4)` bersama untuk chat + media gantikan 2 pool (6+4 koneksi idle).

## [1.6.167] - 2026-10-10
### Fixed
- `MessageQueue.persistLocked` koalesensi tulis antrean (maks 1× per 2 dtk, flush paksa di `flushSync`) agar burst pesan tak memicu serialisasi + enkripsi O(n²).
- `TimeFmt` cache `TimeZone.getDefault()` per 60 dtk agar tiap caption/pesan tak menanggung clone + synchronized.
- `PreferencesManager.ensureOwnerPairCode` pakai satu `SecureRandom` bersama; `sendSmsPending` pakai satu UUID per SMS.
- Regex dikompilasi sekali di konstanta: `CMD_SPLIT_REGEX` untuk `/start`, `PAIR_CODE_REGEX`, `CONNECT_HELPER_REGEX`, tiga pola `NetworkUtils`.
- `Html.unescape` gantikan 7× rantai `replace` di 6 call site; `TAG_STRIP_REGEX` duplikat dibuang.
- `SmsRepository`/`CallLogRepository` normalisasi digit nomor sekali per query, bukan per baris; baris inbox notifikasi via sequence.
- `MessageScheduler` pakai satu `Constraints` bersama; thread mentah diganti pool bersama `Background`.
- Cabang mati `isFreshLocation` pra-API 24 dibuang.

### Removed
- `MonitoringService.clearOwnerMenuScope` mati tanpa pemanggil; 19 string warisan UI lama; `@file:Suppress("DEPRECATION")` tanpa API deprecated di `PreferencesManager`/`MessageQueue`.

### Added
- Sistem verifikasi pengelolaan repo port dari `tasirin-vaultwarden-host`: workflow `verifikasi-path.yml` (gagal cepat sebelum build APK), `tools/verifikasi-path.py` (acuan yatim, XML, manifest, paritas resource, aturan repo, hapusan salah, pindai secret), `scripts/cek-cepat.sh` (vektor `0.x`, binary/secret staged), dan kait pre-push `tools/pasang-hook.sh`.
- `values-night/colors.xml` dilengkapi 7 warna tema/ikon yang sebelumnya hanya di `values/` agar paritas siang-malam verifier terpenuhi (nilai identik, tanpa perubahan tampilan).

## [1.6.166] - 2026-10-09
### Fixed
- `NotificationForwarderService` catat overflow via cache/scope background agar thread listener tak kena query `PackageManager`.
- `NotificationForwarderService.reviveMonitoringIfNeeded` gate izin seperti entry lain; tanpa izin hanya jadwalkan boot restart.
- Wake `/ping` throttle global di samping per-sender agar rotasi pengirim tak bangunkan service beruntun.
- `NotificationForwarderService.pollWakeOnce` backoff 60 dtk untuk respons gagal tak terduga.
- Drop-restore forwarder teruskan `CancellationException` agar cancel scope tepat waktu.

## [1.6.165] - 2026-10-09
### Fixed
- `SetupActivity.clearCredentials` cabut scope menu owner lama via `deleteMyCommands` dan reset `lastUpdateId` agar setup ulang bersih.
- `MonitoringService.sendPairHint` throttle per-sender agar satu pemindai tak membungkam petunjuk pairing owner sah.
- `MonitoringService.notifyCameraFailure`/`notifyPhotoSendFailure` kebal skew jam mundur.
- `MonitoringService.isCameraPolicyError` buang cabang mati `Camera error: 3`.
- `MonitoringService.formatSmsMessage` indikator potong teks bandingkan panjang hasil escape.

## [1.6.164] - 2026-10-09
### Fixed
- `MonitoringService.sendSmsPending` hapus staging SMS bersyarat (`clearSmsPendingIf`) agar SMS baru yang di-stage saat pengiriman lama berjalan tak ikut terhapus.
- `SetupActivity.testConnection` lempar ulang `CancellationException` agar probe basi yang dibatalkan tak memunculkan toast gagal palsu.
- `SetupActivity.updateStatus` hanya buat kode pairing saat sudah konfigurasi dan owner belum dikenal.
- `NotificationForwarderService.flushBatch` teruskan `pkg` asal batch agar atribusi paket tak hilang.

## [1.6.163] - 2026-10-10
### Fixed
- Gagal build `v1.6.162`: kembalikan `}` penutup `if (!usePlayer)` dan ekor jalur `MediaPlayer` (`Ringing` + delay + `finished`) yang ikut terpotong patch ringtone.
- `MonitoringService.searchContacts` gate limit `Bundle` ke API 30+ dan cari juga kolom nomor.
- `MonitoringService.captureAndSendPhoto` hapus file callback basi agar tak ada JPEG yatim.
- `MonitoringService.sendFitted` antre sisa chunk saat cancel agar ekor balasan tak hilang.
- Pairing `/start` hanya balas petunjuk bila ID chat cocok agar pemindai acak tak dapat oracle.
- `MonitoringService.startMonitoring` samakan restore volume macet untuk umur negatif seperti `BootReceiver`.
- `MonitoringService` cache kredensial `@Volatile` + `rememberOwner` terkunci agar atomik.
- `NotificationForwarderService` selalu catat riwayat saat overflow walau pause/nonaktif.
- `BootReceiver` persistensi `boot_meta` di jalur dini `USER_UNLOCKED`.

## [1.6.162] - 2026-10-10
### Fixed
- `SendMessageWorker.sendChunked` antre sisa part saat cancel antar-part agar retry tak kirim ganda.
- `MonitoringService.recordAndSendAudio` panggil `recorder.stop()` di jalur cancel sebelum `release()`.
- Timeout upload media diperlonggar (`writeTimeout` 180 dtk, `callTimeout` 300 dtk) agar video panjang lolos di uplink lambat.
- `MonitoringService.ringDevice` fallback `Ringtone` di-loop selama durasi, bukan bunyi sekali.
- `MessageQueue.isFresh` utamakan elapsed monotonik agar lompatan jam tak membekukan atau menghanguskan antrean.
- `SetupActivity.saveSettings` verifikasi `getMe`/`getChat` sebelum simpan; offline tetap bisa simpan.
- `SetupActivity.TOKEN_REGEX` wajibkan secret 35+ karakter agar token terpotong ditolak di awal.
- `NotificationForwarderService.handlePosted` catat riwayat walau monitoring pause/nonaktif.
- `CameraService` hapus watchdog/timeout juga dari `mainHandler` di semua jalur selesai.

## [1.6.161] - 2026-10-09
### Fixed
- `MonitoringService.takeScreenshotFrame` skala `densityDpi` proporsional dan guard `planes`/`pixelStride`/`rowWidth` agar tak crash saat rasio stride tak cocok.
- `MonitoringService.pollTelegramCommands` retry juga saat `chatId` berubah, bukan hanya saat token berubah.
- `BootReceiver` tangani aksi HTC `com.htc.intent.action.QUICKBOOT_POWERON` agar auto-start jalan di ROM tersebut.
- `ContentQuery` pakai argumen limit `Bundle` hanya di API 30+ agar tak kembalikan kursor full-table di API 26-29.
- `MessageScheduler.scheduleMessageSend` pakai `APPEND_OR_REPLACE` agar rantai WorkManager tak tumbuh tanpa batas.
- `CrashReporter` pertahankan timestamp elapsed asli saat tulis sisa laporan dan potong gabungan aman-surrogate agar sisa tak hidup selamanya.
- `MainActivity` jadwalkan `scheduleBootRestart` saat start stealth gagal agar monitoring tetap coba pulih.
- `SendMessageWorker.sendDropNotice` selalu antre notice prioritas walau antrean penuh agar info drop tak hilang diam-diam.
- Fallback plain (`MonitoringService`, `SetupActivity`, `NotificationForwarderService`, `SendMessageWorker`, `CrashReporter`) unescape `&apos;` agar teks tak tampil mentah.
- `TextChunk.safeCut` hanya mundur untuk entity ber-`;` agar teks ber-`&` biasa tak terpotong sia-sia.
- `Redact` batasi fallback token ke sufiks 35+ karakter agar teks normal tak ikut disensor.
- `PreferencesManager.snapshot` salin defensif `Set` agar set hidup tak bocor antar snapshot dan migrasi.

## [1.6.160] - 2026-10-06
### Fixed
- Luruskan teks backlog penuh foto/video (`oldest unsent discarded`) agar sesuai perilaku prune yang mempertahankan file terbaru.
- Redam konflik polling 409 saat serah-terima wake-loop: deteksi konflik internal via timestamp poll, backoff 60 dtk, dan tanpa notif user.
- `NotificationForwarderService` catat waktu wake-poll terakhir agar loop utama bisa bedakan konflik internal vs klien luar.
- `CameraService.getCameraId` fallback ke lensa lain bila lensa yang diminta tak ada di perangkat.
- `captureVideo` set 24fps hanya bila rentang fps perangkat mendukung agar `prepare()` tak gagal.
- `SetupActivity.updateStatus` baca kode pairing tanpa tulis bila sudah valid agar jalur baca bebas efek-samping.
- `sendInitialData` prob satu halaman ekstra di batas 500 item agar status `partially sent` hanya muncul bila benar ada sisa.
- Kalkulator hitung tanda minus dalam anggaran 12 digit agar hasil negatif tak meluber dari display.

## [1.6.159] - 2026-10-06
### Fixed
- `MonitoringService.takeScreenshotFrame` parse consent URI dengan flag `URI_INTENT_SCHEME` (fallback parse biasa) agar `/screenshot` tak selalu gagal.
- `SendMessageWorker.sendChunked` hentikan loop saat part kena 400 agar tak antre sisa tumpang-tindih dan kirim duplikat.
- `CallLogRepository.getCallsForNumber` prefilter `LIKE` tak sensitif separator, menyusul perbaikan yang sama di SMS.
- `/log` hapus laporan crash tersimpan hanya jika `sendFitted` sukses agar laporan tak hilang saat offline.
- `NotificationForwarderService` kunci dedup sertakan ID notifikasi agar notifikasi beda berteks identik tak saling menelan.
- Lengkapi unescape `&quot;`, `&#39;`, `&#x27;` di fallback plain `CrashReporter`, `SetupActivity.sendStatusNow`, `NotificationForwarderService`, `SendMessageWorker`.
- `BootRestartWorker.doWork` jadwalkan ulang saat throwable tak terduga agar antrean tak macet diam-diam.
- `MonitoringService.rememberOwner` buang kode mati agar niat single-owner latch jelas.
- `/battery` pakai safe-cast agar ROM aneh kembalikan `unknown` bukan `Command failed`.
- `AdminReceiver.onDisabled` antre alarm hanya jika bot terkonfigurasi agar worker tak reschedule tiap 30 menit selamanya.

## [1.6.158] - 2026-10-06
### Fixed
- `MonitoringService` kursor SMS/panggilan (`checkAndSendNewData`, `sendInitialData`) hanya maju saat kirim sukses agar pesan gagal tak ditandai sudah-dilihat.
- `MessageQueue.clearQueue` juga hapus simpanan disk saat mode volatil agar antrean lama tak muncul lagi setelah restore.
- `MainActivity` jalur stealth tak lagi panggil `setMonitoringActive(true)` agar jeda pengguna tak terbuka diam-diam.
- `SmsRepository.getSmsForNumber` prefilter `LIKE` tak sensitif separator (`%d%i%g%i%t%`) agar `/history` konsisten.
- Toleransi timestamp perintah di masa depan dilonggarkan ke 900 dtk agar jam HP sedikit lambat tak menolak perintah valid.
- Fallback plain 400 lengkapi unescape `&quot;`, `&#39;`, `&#x27;` agar teks tak tampil mentah.
- `PreferencesManager.upgradeToPersistent` migrasi satu snapshot terkunci agar tulis konkuren tak hilang atau bangkit lagi.
- `MemoryPrefs` salin `StringSet` saat simpan dan baca agar mutasi luar tak merusak map.
- `SendMessageWorker` jadwalkan ulang 60 dtk saat throwable tak terduga agar antrean tak macet diam-diam.
- `MonitoringService.onStartCommand` refresh prefs persisten sebelum menyerah agar restart sistem dengan prefs memori tak matikan monitoring.
- `CameraService.stopBackgroundThread` selalu `join` dari thread lain agar tak ada thread yatim.
- `BootReceiver` anggap `ringAge` negatif (jam mundur) sebagai macet agar volume alarm tetap dipulihkan.
- `AndroidManifest.xml` tambah aksi `QUICKBOOT_POWERON` HTC agar auto-start jalan di ROM tersebut.
- `SetupActivity` minta `POST_NOTIFICATIONS` sekali non-blokir agar pengingat resume tampil tanpa menghalangi monitoring.

## [1.6.157] - 2026-10-06
### Fixed
- `/recordvideo` jalan di background job seperti `/record` agar polling command tak macet sampai 3 menit selama perekaman video.

## [1.6.156] - 2026-10-06
### Fixed
- Samakan batas flood-wait jadi 3600 dtk di semua backoff (`pollTelegramCommands`, pengirim audio/video/foto/screenshot, wake-poll forwarder) agar `retry_after` panjang tak di-retry kepagian.

## [1.6.155] - 2026-10-06
### Fixed
- `SmsRepository`/`CallLogRepository` semua query `DATE DESC` tambah tiebreak `_ID DESC` agar urutan SMS/panggilan deterministik saat timestamp sama.

## [1.6.154] - 2026-10-06
### Fixed
- `PhoneNumbers.numbersEqualFast` wajibkan nomor pendek utuh jadi suffix, bukan cuma ekor 10 digit, agar riwayat `/lastsms`/`/lastcalls` tak tertukar antar nomor beda area.
- `NetworkUtils.parseRetryAfter`/`noteRateLimited` hormati `retry_after` sampai 3600 dtk agar flood-wait panjang tak di-retry kepagian.
- `MonitoringService` `baseCode` SMS pakai `floorMod` agar `requestCode` tak negatif setelah `smsReqSeq` overflow.
- Percobaan pairing `/start` dibatasi satu per 30 dtk per pengirim agar kode 6 digit tak bisa di-brute force cepat.
- `AndroidManifest.xml` tambah `MODIFY_AUDIO_SETTINGS` agar restore volume alarm `BootReceiver` tak gagal diam-diam.
- `app/build.gradle` clamp `SYNC_INTERVAL` ke `1..1440` agar secret CI yang salah ketik tak bikin loop 0 menit.
- `.github/workflows/build.yml` `cancel-in-progress` dinonaktifkan untuk tag agar build rilis tak dibatalkan push susulan.

## [1.6.153] - 2026-10-06
### Fixed
- `BootRestartWorker.getForegroundInfo` dibungkus guard dengan notifikasi fallback agar panggilan langsung `WorkManager` tak gagal tak tertangani.
- `SendMessageWorker.messageQueue` jadi `lazy` agar alokasi antrean terjadi di dalam `doWork` yang guarded, bukan saat konstruksi worker.
- `BootReceiver.onReceive` bungkus `goAsync` agar `IllegalStateException` tak men-crash receiver sebelum `handleBoot` jalan.
- `AdminReceiver.onDisableRequested` bungkus `getString` dengan fallback hardcoded agar callback sistem tak crash.
- `patrolRound` pakai safe-cast `as? BatteryManager` agar ronde patrol tak mati sia-sia di ROM kustom.
- Cabang `/battery` bungkus `getIntProperty` seperti situs kembarnya agar konsisten anti-crash.
- `MainActivity.openSetupPage` bungkus `Toast` di blok `catch` agar kegagalan toast saat teardown tak men-crash aplikasi.

## [1.6.152] - 2026-10-06
### Fixed
- `SetupActivity.isBatteryExempt` dan `activateDeviceAdmin` dibungkus guard agar `PowerManager`/`DevicePolicyManager` kustom tak sebabkan crash dari halaman Setup.
- `NetworkUtils.isNetworkAvailable` pakai safe-cast `as?` plus `try/catch` agar `ConnectivityManager` null/kustom kembalikan `false` bukan crash.
- `isPremiumSmsNumber` kecualikan nomor darurat (`112`, `911`, `999`, `110`, `000`, `111`, `118`, `119`) dan hapus prefix `809` dari daftar premium agar SMS darurat tak diblokir dan nomor Dominika tak ditandai premium.
- `Redact.token` pakai word-boundary untuk secret digit pendek agar chat ID tak menyensor substring dalam angka lain.
- `SmsData`/`CallData` petakan tipe provider 3-6/4-7 (`Draft`/`Outbox`/`Failed`/`Queued`, `Voicemail`/`Rejected`/`Refused`/`Answered elsewhere`) agar label riwayat akurat.
- `NO_REPLAY_COMMANDS` hapus duplikat `/smsconfirm` yang sudah ada di `MUTATING_COMMANDS`.
- Truncate teks notifikasi/SMS escape-dulu-potong-kemudian agar entitas HTML tak terbelah dan pesan Telegram tetap valid.

## [1.6.151] - 2026-10-06
### Fixed
- `ParentalMonitorApp` bungkus `onCreate` + `createNotificationChannel` dengan guard dan null-check `NotificationManager` agar startup tak NPE.
- `MonitoringService.onStartCommand` tak pernah lempar: dispatch dibungkus `try` dan selalu kembalikan `START_STICKY`/`START_NOT_STICKY`.
- `MonitoringService.onCreate` inisialisasi repo/antrean/kamera dibuat graceful: gagal init jadwalkan restart via `MessageScheduler.scheduleBootRestart` lalu `stopSelf`, bukan crash.
- `MonitoringService` listener pref dan `setupTapIntent` di `startMonitoring` dibungkus guard agar callback sistem tak fatal.
- `SendMessageWorker`/`BootRestartWorker` tangkap `Throwable` di batas `doWork` dan kembalikan `Result.success()` agar antrean tetap utuh tanpa crash proses.
- `SetupActivity` pasang `CoroutineExceptionHandler` di semua `lifecycleScope.launch` IO agar pengecualian tak terduga tak merobohkan aplikasi.
- `CameraService` semua callback `StateCallback` kamera dijamin memanggil `finishWithError` agar flag busy tak macet selamanya.
- `AdminReceiver.onDisabled` bungkus `Toast` dengan guard.

## [1.6.150] - 2026-10-06
### Fixed
- `AndroidManifest.xml` set `NotificationForwarderService` ke `exported="true"` agar sistem bisa bind listener dan forward notifikasi jalan lagi.
- `MainActivity.formatResult` hitung digit integer via string desimal, bukan `log10`, agar pangkat 10 tak off-by-one.
- `BootReceiver` hanya reset izin screenshot saat boot nyata, bukan tiap `USER_UNLOCKED`, agar consent tak hangus setiap buka kunci.
- `SetupActivity.reviveMonitoringIfNeeded` fallback ke `MessageScheduler.scheduleBootRestart` saat `startForegroundService` ditolak dari background (Android 12+).
- `CrashReporter.savePending` sinkron plus tulis atomik via file `.tmp` + rename agar dua crash bersamaan tak saling timpa.
- `CameraService.stopBackgroundThread` `quitSafely` dulu sebelum nol-kan handler agar callback basi tak jalan setelah cleanup.

## [1.6.149] - 2026-10-06
### Fixed
- `PhoneNumbers` ganti guard prefix eksak dengan aturan suffix/selisih panjang agar beda kode negara tetap cocok tanpa false-positive sesama panjang.
- `SmsRepository`/`CallLogRepository` perdalam fallback scan `5x500` jadi `10x500` agar `/history` menemukan nomor terformat lama.
- `NotificationForwarderService` catat hit spam walau sedang throttled agar sliding window 120 detik tetap geser.
- `SendMessageWorker` longgarkan guard notice drop `>=90` jadi `>=100` agar info drop tak hilang saat antrean penuh.

## [1.6.148] - 2026-10-06
### Fixed
- `CrashReporter` hormati koordinator 429 bersama dan tandai `credentialError` saat 401/403.
- `MessageQueue.flushSync` pakai `apply` di main thread dan pulihkan persisten bila volatile di background.
- Notice drop antrean sebut potensi gap riwayat SMS/call beserta perintah cek ulang.
- `SetupActivity` tawarkan dialog clear saat field dikosongkan padahal kredensial tersimpan.
- `buildReport` potong entity HTML dengan aman via `safeTake`.

## [1.6.147] - 2026-10-06
### Fixed
- Wake-ping dedup pakai String koma tunggal, bukan StringSet yang tak didukung EncryptedSharedPreferences.
- `PhoneNumbers` wajibkan prefix sama bila kedua nomor >10 digit agar beda negara tak dicocokkan.
- Note menu `/help` hanya antre bila chunk ber-markup yang gagal.
- `ContentQuery` anggap limit negatif sebagai tanpa batas, bukan `LIMIT 0`.
- `TextChunk.safeCut` tak pernah kembalikan 0 untuk `max` positif agar pemotong tak loop selamanya.
- `AdminReceiver.onDisabled` pakai `goAsync` agar notice anti-uninstall tak hilang.

## [1.6.146] - 2026-10-06
### Fixed
- `BootReceiver` memakai jam elapsed tunggal untuk throttle penanganan boot agar tidak tercampur wall-clock.
- Koordinator 429 `NetworkUtils` memakai `elapsedRealtime` agar perubahan jam tidak merusak backoff.
- `CameraService.finishWithPhoto` membatalkan callback timeout di handler utama seperti varian video.
- Throttle notice gagal kamera/upload fail-closed saat jam mundur agar tidak spam Telegram.
- `SendMessageWorker` menunda hapus pesan 400-rejected sampai kredensial terverifikasi sama agar tidak hilang saat token berganti.
- `SetupActivity` menolak simpan `chat_id` `@username` yang tak teresolvensi walau offline.
- Jendela batch notifikasi 15 dtk jadi 5 dtk agar lebih sedikit yang hilang saat proses mati.

## [1.6.145] - 2026-10-06
### Fixed
- `MonitoringService.teardownJobs` mereset counter percobaan kamera/video/foto ke 0 agar sesi berikutnya tidak langsung menyerah akibat backoff basi.
- `MonitoringService.startMonitoring` memanggil `refreshLoopConfig` juga pada jalur early-return agar perubahan interval patroli/sinkron langsung berlaku.
- `CameraService.finishWithVideo` membatalkan callback timeout/stop yang tertunda agar tidak ada error semu setelah video sukses.
- `CameraService.runMeteredCapture` memakai fallback handler utama bila background handler nol agar foto tidak menggantung.
- `MessageQueue.ensureRestored` memicu restore asinkron dari main thread agar antrean volatile akhirnya pulih persisten.
- `NotificationForwarderService.onDestroy` hanya mengenolkan counter persisten setelah antrean sukses, dan mengembalikannya bila gagal.
- `BootRestartWorker` memakai jam elapsed tunggal untuk throttle pengingat resume agar tidak tercampur wall-clock.
- `SendMessageWorker.sendChunked` mengantrekan ulang sisa part yang belum dicoba saat satu part ditolak Telegram agar tidak hilang.

## [1.6.144] - 2026-10-06

### Fixed
- Pairing owner kini butuh `/start <chat ID> <kode pairing>` dengan kode acak per perangkat tampil di status Setup; kredensial baru merotasi kode.
- `getUpdates` 409 kini backoff 5 menit dengan notice sejam sekali agar tak polling sia-sia.
- Unggah foto/audio/video dan poll `getUpdates` ikut koordinator 429 bersama.
- `clearCredentials` tak lagi reset `lastUpdateId`/`wakeUpdateId` agar tak replay update basi; ganti token tak reset kursor update.
- `/history` kosong kini sebut pencarian mencakup roughly 12 bulan terakhir.
- Aturan backup kecualikan keyset `EncryptedSharedPreferences` agar konsisten dengan ciphertext.
- Status Setup tampilkan izin notifikasi sistem dan kode pairing owner.

## [1.6.143] - 2026-10-06

### Fixed
- `BootReceiver` terima `MY_PACKAGE_REPLACED` tanpa data URI dan hapus `USER_PRESENT` mati agar restart sehabis update jalan lagi.
- Loop monitoring/kamera/patrol jeda 10 dtk tiap error agar tak hot-spin saat gagal persisten.
- `ContentQuery` fallback tanpa `LIMIT` mentah agar sinkron SMS/CallLog tetap jalan di API 24-25.
- `NotificationForwarderService.reviveMonitoringIfNeeded()` pindah ke coroutine agar tak ada IO kripto di thread callback notifikasi.
- `MonitoringService.teardownJobs()` kini batalkan `msgDropJob` agar notice drop tak bangkit setelah stop.
- `MessageScheduler` tambah `cancelMessageSend()`/`cancelWatchdog()`; stop dari Setup batalkan kerja latar, enable jadwalkan watchdog lagi.
- Preview SMS parsial potong setelah `Html.escape` dan teksnya jujur bahwa retry bisa duplikat.
- Timeout fix lokasi 25 dtk jadi 15 dtk agar `/location` dan patrol lebih responsif.
- Koordinator 429 bersama di `NetworkUtils` (`noteRateLimited`/`rateLimitedRemainMs`) dipakai `MonitoringService`, `SendMessageWorker`, dan `NotificationForwarderService` agar backoff Telegram dihormati lintas pengirim.

## [1.6.142] - 2026-10-06

### Fixed
- `PreferencesManager.upgradeToPersistent()` kini menangkan tulis memori saat migrasi ke encrypted prefs agar token/flag tak hilang pada race upgrade.
- `SendMessageWorker` baca `errorBody()` sekali per respons lalu pakai ulang untuk `isChatMissing`/`isRightsLimited`/`parseRetryAfter` agar `retry_after` dan `migrate_to_chat_id` tak hilang.
- `MessageScheduler.scheduleMessageSend()` pakai `APPEND` agar pekerjaan rate-limit (`REPLACE`) tak tertimpa antrean biasa.
- `TextChunk.safeCut()` fallback tetap aman-surrogate saat window penuh tag/entity.
- `PhoneNumbers` cocokkan ekor 10 digit agar nomor beda dengan sufiks sama tak salah cocok.
- `NetworkUtils.isNetworkAvailable()` cukup butuh `NET_CAPABILITY_INTERNET` agar antrean tak macet di captive-portal.
- `CrashReporter` pakai umur minimum wall/monotonik agar jam maju tak hapus laporan baru.
- `AndroidManifest.xml`: hapus `USER_PRESENT` dari `BootReceiver` dan hapus `<data scheme="package">` pada `MY_PACKAGE_REPLACED` agar restart sehabis update benar-benar jalan.
- `MainActivity.formatResult()` pakai `"%.5E"` agar notasi eksponen tetap dalam 12 digit.
- `BootRestartWorker.postResumeReminder()` cek izin notifikasi sebelum throttle 3 jam agar reminder tak hilang sia-sia.
- `SetupActivity.updateStatus()` tampilkan status tombol screenshot (`granted/off`) agar grant ulang pasca-reboot jelas.

## [1.6.141] - 2026-10-06

### Fixed
- Cuplikan riwayat notifikasi (`/lastnotif`) dan pratinjau SMS terkirim sebagian kini lewat `safeCut()` agar emoji tak terbelah jadi surrogate yatim.

## [1.6.140] - 2026-10-06

### Fixed
- `SetupActivity` samarkan chat ID hasil migrasi supergroup di jalur plain-retry (`STORED_MASK`) agar ID mentah tak terlihat di layar.
- Potong cuplikan SMS/notifikasi kini lewat `TextChunk.safeCut()` agar emoji tak terbelah jadi surrogate yatim yang bisa bikin Telegram 400.

## [1.6.139] - 2026-10-06

### Fixed
- `CameraService.captureVideo` gagal cepat saat watchdog timeout tak bisa dijadwalkan agar `MediaRecorder` yatim tak merekam tanpa batas.
- Jadwal penghenti rekaman video kini cek hasil `postDelayed` dengan fallback eksplisit agar stop tak hilang diam-diam di thread mati.

## [1.6.138] - 2026-10-06

### Fixed
- `CameraService` gagal cepat saat watchdog timeout tak bisa dijadwalkan agar kunci `capturing` tak terkunci permanen.
- Expiry antrean `MessageQueue` pakai dual-clock (`elapsedAt` monotonik + wall-clock) agar tahan skew jam.
- Nama artefak rilis tanpa keystore tetap `redeye-release.apk` sesuai `AGENTS.md` §2.

## [1.6.137] - 2026-10-06

### Fixed
- Kembalikan variabel `pending` di `sendStatusNow()` yang dipakai blok `catch` untuk antrekan fallback; penghapusannya merusak build `1.6.134`-`1.6.136`.

## [1.6.136] - 2026-10-06

### Fixed
- Kunci patroli kini ikut refresh cache loop agar `/patrol on` langsung jalan tanpa restart service.
- `restartAllLoops()` batalkan `patrolJob` agar `/restart` tak gandakan putaran patroli.
- Putaran patroli hormati jeda foto (`/pause` dan blokir kebijakan kamera).
- Watchdog dan cek start kini awasi `patrolJob` agar kematian diam-diam terdeteksi.
- Counter drop antrean jadi field instance agar tak saling timpa antar instance.

## [1.6.135] - 2026-10-06

### Added
- Mode patroli otomatis: putaran foto + lokasi + baterai berkala via `/patrol <on|off>` dan `/patrolinterval <5-180>`, status tampil di `/status`, menu bot, dan halaman Setup.

## [1.6.134] - 2026-10-06

### Fixed
- `SetupActivity` tampilkan `STORED_MASK` setelah migrasi chat ID grup ke supergroup agar ID mentah tak terlihat di layar.
- Hapus variabel `pending` mati di `sendStatusNow()`.
- `TextChunk.safeCut()` kembalikan 0 untuk `max` non-positif agar tak ada `IndexOutOfBounds`.
- `BootRestartWorker` tak lagi jadwalkan kirim antrean saat belum konfigurasi karena worker langsung success tanpa kredensial.

## [1.6.133] - 2026-10-06

### Fixed
- `ContentQuery` fallback/provider lama kini menambahkan `LIMIT` pada `sortOrder` agar batas query SMS/call-log tetap berlaku di bawah API 26 dan tak memuat seluruh inbox.
- `PhoneNumbers.numbersEqualFast()` hanya samakan 9 digit terakhir agar nomor berbeda dengan sufiks sama tak salah atribusi.
- `takeScreenshotFrame()` jadi `suspend` dan memakai `delay()` agar polling frame tak blokir thread IO.
- `BootReceiver` lewati debounce wall-clock saat boot segar (`elapsedRealtime` < 120 detik) agar start monitoring setelah reboot tak ter-skip.
- Redaksi log kini sertakan `chatId` di `MonitoringService`, `CrashReporter`, dan `SendMessageWorker` selain token bot.
- `startForegroundImmediate()` hitung tipe foreground di depan dan `onCreate()` inisialisasi prefs dulu agar tak ada upgrade tipe tertunda di Android 14.

## [1.6.132] - 2026-10-06

### Fixed
- Gagal build `CallLogRepository.kt:32`: `getAllCalls()` memakai `safeLimit` tak terdefinisi, kini `limit.coerceIn(1, 200)`; query LIKE `getCallsForNumber()` memakai `safeLimit` agar tak over-read.
- `BootReceiver` hanya menghapus consent screenshot saat boot/unlock, bukan tiap `USER_PRESENT`, agar `/screenshot` tak selalu minta consent ulang.
- Dedup callback inline revert ke `query.id` agar double-tap sah pada tombol sama tak terbuang.
- Hapus pre-commit offset `NO_REPLAY_COMMANDS` yang redundan karena blok `finally` sudah commit setelah eksekusi.
- `answerCallback` tetap mencoba menjawab walau auth-blocked agar spinner Telegram tak menggantung sia-sia.
- `CameraService` video: penjadwalan stop fallback ke main handler, stop runnable juga dihapus dari watchdog, dan berkas video basi dihapus saat capture gagal.
- `NotificationForwarderService`: restore hitungan drop idempoten via flag dan filter `/ping` memakai `lastUpdateId` terbaru agar ping valid tak terbuang.

## [1.6.131] - 2026-10-06

### Fixed
- Pairing owner `/start` tak lagi menimpa owner yang sudah tertaut; upaya susulan diabaikan dan dilaporkan ke chat owner.
- Dedup callback inline pakai kunci komposit pengirim+data+pesan+tanggal agar replay tombol tak eksekusi ulang perintah mutating.
- Potong SMS selalu via `divideMessage()` agar teks unicode/emoji terbelah benar; langkah `requestCode` SMS naik ke 1 juta agar pengiriman konkuren tak tabrakan.
- `sendDropNotice()` batal antre ulang saat antrean >= 90 agar gagal kirim notifikasi tak memicu storm drop.
- Throttle balasan `/ping` diselaraskan ke 120 detik mengikuti cooldown wake forwarder.
- Izin screenshot dihapus saat boot karena grant `MediaProjection` mati saat reboot; status tak lagi klaim granted basi.
- Poll frame screenshot dipangkas ke ~1,2 detik agar tak menahan thread IO.
- Hitungan drop antrean dipersist sinkron agar selamat dari kill proses.
- Migrasi prefs merambatkan penghapusan kunci yang terjadi di celah migrasi.
- Fallback `ContentQuery` tanpa rangkai `LIMIT` SQL; batas ditegakkan di kode.
- Validasi token dilonggarkan dan simpan `@username` offline dipertahankan mentah untuk di-resolve saat online.
- `MainActivity` baca ulang prefs sebelum cek konfigurasi agar rotasi tak pakai instance basi.
- Nama file audio disufiks UUID agar dua rekaman tak saling timpa.


## [1.6.130] - 2026-10-06

### Fixed
- `upgradeToPersistent()` drain susulan hanya isi kunci yang hilang agar tulisan baru ke penyimpanan terenkripsi tak tertimpa nilai basi.
- `sendChunked()` tak lagi antre ulang saat chunk pertama gagal agar penghitung retry `registerTransientFailures` tetap jalan dan batas retry dihormati.
- `flushPending()` hapus laporan crash kedaluwarsa (>7 hari) sebelum cek token/izin agar berkas basi tak menumpuk saat belum konfigurasi atau monitoring mati.
- `scheduleMessageSend()` pakai `APPEND_OR_REPLACE` dan koersi delay agar antrean WorkManager tak menumpuk tak terbatas.
- `ContentQuery` dan `getSmsForNumber()`/`getCallsForNumber()` koersi limit dan batasi hasil agar `LIMIT` negatif dan scan tak terkendali tertutup.
- `SetupActivity` abaikan aksi save/test basi secara diam-diam tanpa toast `superseded` ganda.
- `BootReceiver` sinkronkan debounce `lastHandleAt` agar broadcast ganda tak memicu start ganda.
- `CameraService` pakai ukuran preview yang didukung untuk permukaan metering dan timeout di background handler agar tak gagal sesi di sebagian device dan tak bocor thread.


## [1.6.129] - 2026-10-06

### Fixed
- `upgradeToPersistent()` kini drain kedua setelah swap agar tulisan di celah migrasi tak hilang.

## [1.6.128] - 2026-10-06

### Fixed
- Wake `/ping` forwarder kini selaras scope jawab: anggota grup hanya bisa membangunkan saat owner belum pairing; setelah pairing wajib pengirim owner.
- Efek samping wake (start service + jadwal antrean) di-throttle 2 menit per pengirim agar spam ping tak memicu restart berulang.

## [1.6.127] - 2026-10-06

### Fixed
- Nama file video/screenshot kini bersufiks UUID agar rekaman baru tak menimpa file pending yang belum terkirim dalam detik yang sama.
- `takeScreenshotFrame` menghapus file parsial saat kompresi gagal agar JPEG korup tak ikut terkirim.
- Scope menu perintah ex-owner dihapus via `deleteMyCommands` saat owner berganti.

## [1.6.126] - 2026-10-06

### Fixed
- `MessageQueue.clearQueue()` kini juga menghapus penghitung drop persisten (`KEY_OVERFLOW_DROPS`/`KEY_EXPIRED_DROPS`) agar notifikasi drop basi tak muncul hantu setelah restart proses.

## [1.6.125] - 2026-10-06

### Fixed
- `/ping` kini punya cooldown restart 5 menit (`PING_RESTART_COOLDOWN_MS`): ping beruntun tak bisa memicu restart loop berulang, klaim "Loops restarted" hanya muncul saat restart benar-benar jalan.
- `/ping camera` hanya me-restart loop kamera saat loop-nya memang mati; saat sehat langsung ambil foto tanpa mereset jadwal interval.

## [1.6.124] - 2026-10-06

### Fixed
- `/ping` hanya dijawab untuk owner (non-owner diam, selaras wake forwarder), rate-limit 30 detik per pengirim tanpa amplifikasi balasan.
- `/ping` masuk `NO_REPLAY_COMMANDS` agar redelivery pasca-crash tak memicu ulang aksi `camera`/`location`.

## [1.6.123] - 2026-10-06

### Fixed
- `README.md` selaras kebijakan keystore: build tag tanpa keystore gagal tegas, varian `debugsigned` hanya untuk build branch/PR.
- `data_extraction_rules.xml` tambah cermin `device-transfer` agar token/antrean tak ikut migrasi perangkat.
- Plain-fallback `sendStatusNow` decode entities selaras semua jalur kirim.

## [1.6.122] - 2026-10-06

### Fixed
- `/log` kirim laporan crash apa adanya via `sendFitted` (sudah HTML valid + chunk aman), bukan `Html.escape` ganda yang menampilkan tag literal.

## [1.6.121] - 2026-10-06

### Fixed
- Menu perintah owner Telegram kini memakai scope wire `"chat"` (bukan nama kelas) agar registrasi tak selalu `400`.
- Drop notifikasi oversize (`>4000` char) kini terhitung per chunk, bukan diam-diam hilang.
- Plain-fallback forwarder decode entities (`&lt;`/`&gt;`/`&amp;`) selaras jalur utama.
- `registerWithBudget` cabang volatil prune kedaluwarsa dulu seperti jalur persisten.
- `saveImage` sertakan cause asli saat gagal simpan.
- `reviveMonitoringIfNeeded` jalan degraded tanpa background location, selaras `MainActivity`.

## [1.6.120] - 2026-10-06

### Fixed
- `MessageQueue` auto-restore penyimpanan terenkripsi di luar main thread agar antrean tak terjebak volatil dan pesan tak hilang saat proses mati.
- `PreferencesManager.upgradeToPersistent` snapshot tulisan susulan sebelum ganti backend agar tak ada kredensial hilang dalam window migrasi.
- Replay perintah mutating Telegram (`/sms`, `/ring`, `/lock`, dsb) dipre-claim via `NO_REPLAY_COMMANDS` seluas `MUTATING_COMMANDS` agar redelivery tak eksekusi ganda.
- Batas umur perintah mutating dinamai `MUTATING_MAX_AGE_SEC` dan dipakai di semua jalur (`handleTelegramCommand`, inner, callback) tanpa ubah perilaku.
- `MainActivity` menjadwalkan `BootRestartWorker` saat belum konfigurasi/izin kurang agar retry tetap jalan senyap.
- `SendMessageWorker` tak lagi menghapus ID generasi lama setelah `clearQueue` (`queueCleared`).
- `TelegramMediaClient` memakai pool koneksi sendiri agar long-poll perintah tak starvation oleh unggah media.
- Build tag `v*` tanpa keystore rilis kini gagal tegas, bukan rilis debug-signed diam-diam.

## [1.6.119] - 2026-10-05

### Fixed
- Riwayat `/lastnotif` jalur spam-filter kini mencatat label aplikasi ter-resolve, bukan fallback nama paket cache.
- Laporan `/log` kini di-escape HTML utuh alih-alih strip-tag agar stack trace berisi generics tak termutilasi; tetap aman di `parseMode` HTML + pemotongan via `sendFitted`.

## [1.6.118] - 2026-10-05

### Fixed
- Batal saat unggah audio gagal-transien (`/restart` di tengah retry) tak lagi menghapus rekaman: file `KEPT` dipertahankan untuk retry + antre notifikasi jujur, selaras jalur foto/video.

## [1.6.117] - 2026-10-05

### Fixed
- Callback tak berizin ikut `answerCallbackQuery` sebelum return agar spinner Telegram pemencet tak gantung 30 detik; tanpa bocor info apa pun.
- `TOKEN_REGEX` `SetupActivity` longgarkan ke 5+ digit (selaras fallback `Redact`) agar bot ID lama yang valid tak ditolak saat tes/simpan; validasi server via `getMe` tetap jadi penentu.

## [1.6.116] - 2026-10-05

### Fixed
- Wake-loop forwarder (`pollWakeOnce`) tangani `400`-offset seperti loop utama (resync ke `lastUpdateId` + backoff 30 detik) dan `401`/`403` (set `credentialError`) agar polling tak wedged selamanya.
- Kalkulator: kode rahasia `1234=` mensyaratkan `!justCalculated` agar hasil hitung `1234` lalu tekan `=` lagi tak membuka `SetupActivity`.
- Ganti token membersihkan dedup `updateId`/`callbackId` in-memory agar perintah awal bot baru tak ter-skip karena tabrakan numerik.
- Plain-fallback decode entities (`&lt;`/`&gt;`/`&amp;`) di `MonitoringService`/`SendMessageWorker`/`CrashReporter` agar teks polos tak tampil `&amp;` literal.
- Capture foto/screenshot/video/audio cek `mediaBackoffUntil` dulu agar tak membakar kamera/mic saat `429` rate-limited.
- `/history` 7-9 digit boleh suffix-match (threshold `10` jadi `7`) agar pencarian pendek tetap menemukan nomor.
- `/clearqueue` jujur drop-counter: ambil `overflow`/`expired` sebelum clear dan sebut di konfirmasi.
- `parseRetryAfter` fallback regex dari deskripsi `retry after N` agar `429` tanpa objek `parameters` tetap backoff benar.

## [1.6.115] - 2026-10-05

### Fixed
- Drop-notice forwarder tak lagi ganda pasca-restart: `onDestroy` ikut persist nol ke `pendingNotifDrops` (seperti `flushMsgDropNotice`), `onCreate` berikut tak lagi mengantre notice kedua yang identik.
- Wake-loop forwarder (`pollWakeOnce`) hormati auth-block seperti loop utama — token/chat ditolak tak lagi memicu polling `getUpdates` tiap 25 detik selamanya.

## [1.6.114] - 2026-10-05

### Fixed
- Screenshot pakai guard generasi (`shotAttempt`, pola `cameraAttempt`/`videoAttempt`): `teardownJobs` menginvalide generasi + hasil basi pasca-`/restart` dibuang (file dihapus, tak dikirim ganda), watchdog basi tak lagi mereset flag capture baru.
- Drop-notice forwarder jujur untuk notif panjang: tiap potongan >4000 char berbobot 0, dihitung 1 bila ada potongan yang ditolak permanen — kegagalan parsial tak lagi senyap, retry transien tak ikut dihitung drop.
- `emitPost` menandai `groupSeen` di awal (sebelum dedup/spam-return) agar summary grup 120 detik ikut teredam oleh konten yang diredam, bukan cuma yang diteruskan.
- `getUpdates` offset-400 resync dari nol (gate kedaluwarsa perintah mencegah replay tereksekusi) agar polling perintah tak wedged selamanya.
- Staging `/sms` sadar owner: owner sama boleh menimpa pending-nya sendiri, pending owner lain tetap dikunci requester; staging ikut throttle 60 detik seperti `/smsconfirm`.

## [1.6.113] - 2026-10-05

### Fixed
- Fast-path forwarder (`pendingPosts>32`) ikut meredam summary grup berulang 120 detik seperti jalur normal (`groupSeen`), plus paritas `trackGroup`; riwayat tetap tercatat, pause tetap senyap.
- `activeShotFile` diberi `@Volatile` seperti pelacak in-flight lain (tulis thread `serviceScope`, baca thread loop).
- Drop-notice forwarder saat `onDestroy` ikut `scheduleMessageSend` agar tak stranded menunggu pemicu lain.

## [1.6.112] - 2026-10-05

### Fixed
- Foto vs screenshot tak lagi berbagi pelacakan in-flight: jalur screenshot pakai `activeShotFile` sendiri (flush/prune/hitung antre-UA mengecualikan keduanya) agar `/photo` + `/screenshot` berbarengan tak double-upload atau terprune saat unggah.
- Notif drop batch dihitung jujur: `flushBatch` kelompokkan per pesan (≤4000) dan teruskan jumlahnya ke `forwardLocked`/`noteDroppedNotification`, satu batch 400-an pesan lapor sesuai isi bukan +1.
- Laporan crash menghormati pause: `CrashReporter.flushPending` tak mengirim saat `monitoringPaused` (tetap pending hingga resume/kedaluwarsa 7 hari).
- `isNetworkAvailable` mensyaratkan `NET_CAPABILITY_VALIDATED` agar captive portal tak membakar budget 20 retry worker ke arah drop.
- Paging `/history` anti-lewat: `getSmsForNumber`/`getCallsForNumber` memakai kursor komposit `(DATE, _ID)` + sort deterministik agar baris se-timestamp di batas page tak terlewati.

## [1.6.111] - 2026-10-05

### Fixed
- Fast-path forwarder (`pendingPosts>32`) tak lagi menggugurkan riwayat saat forwarding mati: gate `historyAllowed()` + record sebelum gate forward, selaras jalur normal + overflow `v1.6.110`; pause tetap senyap.
- `/clearqueue` menghentikan in-flight worker: counter generasi di `MessageQueue` (`clearQueue()` menaikkan, worker abort bila berubah) agar sisa snapshot ≤20 pesan tak terkirim sesudah clear; akuntansi drop gagal/reject kedaluwarsa dilewati pasca-clear.
- Wake-loop forwarder tak lagi mangkir pasca-ganti token/chat: listener kredensial mereset `wakeUpdateId=-1` (+ invalide cache config) agar offset tak raksasa selamanya.
- `REPLACE` 429 jalur langsung tak lagi membunuh worker mid-loop (duplikat ≤5): flag `workerRunning` di `MessageScheduler`, fungsi `scheduleRateLimited()` pakai `APPEND` bila worker jalan dan `REPLACE` bila idle; pemanggil worker-internal tetap `REPLACE`.

### Changed
- `saveCoreConfig`/`saveTestCredentials` di `PreferencesManager` digabung ke satu fungsi privat `saveCredentials` (sebelumnya identik baris-per-baris).

## [1.6.110] - 2026-10-05

### Fixed
- `sendStatusNow` di `SetupActivity` menegakkan blokir auth bila retry pasca-migrasi tetap 400 + chat hilang (tanpa id migrasi lanjutan), agar backoff 30 menit tak tertunda satu siklus.
- Cek `isChatMissing`/`isRightsLimited`/`parseRetryAfter` foto/video memakai body penuh (`errorFull`) — `retry_after` di ujung JSON tak lagi terpotong `take(200)`.
- `MessageScheduler.scheduleMessageSendNext()` pakai `REPLACE` (sebelumnya identik `APPEND` dengan kirim biasa) agar retry tertunda tak menumpuk rantai worker.
- Konfirmasi `/clearqueue` ikut antre (`queueOnFail=true`) agar tetap sampai saat auth-blocked/offline.
- Forwarder: dedup/spam/record/build/forward disatukan ke `emitPost()` (fast-path + jalur normal), duplikat logika hilang; duplikat fast-path kini ikut tercatat di riwayat.
- Riwayat `/lastnotif` tetap dicatat saat forwarding dimatikan/belum konfigurasi (`historyAllowed()` + record sebelum gate forward); pause tetap senyap.

## [1.6.109] - 2026-10-05

### Fixed
- Notif "Group upgraded" tak lagi spam: `adoptMigratedChat()` di `MonitoringService`/`SendMessageWorker`/`NotificationForwarderService` hanya mengantre notif bila nilai `chatId` tersimpan benar berubah; panggilan berikutnya dalam satu batch (termasuk worker dengan `chatId` lokal basi) tetap ditangani tanpa notif ganda.
- Migrasi gaya lama tertangani: `NetworkUtils.extractMigratedChatId()` punya fallback regex (`"migrate_to_chat_id": ...` mentah + `New chat id: ...` di deskripsi) agar error 400 tanpa objek `parameters` tetap diadopsi, bukan freeze 30 menit.
- `sendStatusNow` di `SetupActivity` ikut adopsi migrasi: 400 berisi id baru langsung update `chatId` + bersihkan error + retry sekali dengan id baru (field input ikut diperbarui), bukan set `credentialError=400` yang membekukan semua.
- SMS parsial tak hilangkan konteks: notif kini sertakan nomor tujuan + pratinjau 120 karakter teks asal (escape HTML) agar sisa yang belum terkirim bisa direkonstruksi manual.
- `teardownJobs()` tak lagi berlubang: ikut batalkan `shotWatchdog`, reset `shotBusy`/`videoFlushBusy`, invalide `cameraAttempt`/`videoAttempt` agar `/screenshot` tak tolak "still running" hingga 45 detik pasca-restart.
- Cap video adil: `pendingVideoCount()`/`pendingPhotoCount()` tak hitung file in-flight (`activeVideoFile`/`activePhotoFile`) agar 2 antre + 1 rekam tak menolak rekaman baru.

## [1.6.108] - 2026-10-05

### Fixed
- Gagal build `v1.6.106`/`v1.6.107` (`Unresolved reference: windowManager` di `takeScreenshotFrame`): `Service` tak punya properti `windowManager`, kini via `getSystemService(WINDOW_SERVICE)`.
- Video offline tak lagi stranded: loop periodik dan `/flush` ikut memanggil `flushPendingVideos()`; guard backlog video (maks 3, tertua dipangkas seperti foto) agar cache tak tumbuh tanpa batas saat offline.
- `/storage` (`cacheStats`) ikut menghitung `video_*.mp4` yang selama ini tak terlihat padahal terbesar.
- Migrasi grup ke supergrup ditangani otomatis: `migrate_to_chat_id` dari body 400 diparse (`NetworkUtils.extractMigratedChatId`), `chatId` diadopsi + error auth dibersihkan + notifikasi antre di jalur teks `MonitoringService`/`SendMessageWorker`/`NotificationForwarderService`, file media `KEPT` untuk retry (owner dipertahankan, menu bot tak perlu daftar ulang).
- SMS parsial tak lagi duplikat: pending langsung dibersihkan setelah sebagian part terkirim, `/smsconfirm` ulang tak mengirim ulang part yang sudah sampai.
- `/clearqueue` jujur: pesan menyebut update SMS/panggilan yang sudah ditandai terlihat tak akan dikirim ulang.
- `restartAllLoops()` mengembalikan status + ikut menghidupkan ulang `loopWatchdogJob`; `/restart` hanya klaim sukses bila benar restart (throttle 10 dtk lapor eksplisit).
- Balap Save-vs-Test di Setup dikeraskan via cross-cancel (`saveJob`/`testJob` saling batalkan, sekuens invalidasi sudah simetris).

## [1.6.107] - 2026-10-05

### Added
- Perintah `/recordvideo <5-180>` (detik) merekam video kamera + audio via Camera2 + `MediaRecorder` (maks 720p, ~1-2 Mbps) lalu mengirim sebagai video Telegram: file `video_*.mp4` ikut antre offline/flush/prune (maks 3), guard 48 MB, watchdog + `videoAttempt` anti-callback basi, tombol + entri bot menu + baris `/help`.

### Changed
- Batas `/record` dibuka dari 5-60 detik menjadi 5-600 detik (10 menit); argumen di luar rentang kini ditolak dengan pesan usage, bukan diam-diam dijepit.

## [1.6.106] - 2026-10-05

### Added
- Perintah `/screenshot` menangkap layar perangkat via MediaProjection lalu mengirim sebagai foto: izin diberikan sekali lewat tombol Capture Screen di Setup (`MediaProjectionManager.createScreenCaptureIntent`), token izin disimpan terenkripsi di `PreferencesManager`, tipe FGS `mediaProjection` ditambahkan ke manifest + `ensureForegroundTypes`, tombol menu Screenshot + entri bot menu + baris `/help`, file `screenshot_*.jpg` ikut antre offline/flush/prune seperti foto kamera.

## [1.6.105] - 2026-10-05

### Fixed
- `NO_REPLAY_COMMANDS` disempitkan ke `/smsconfirm` saja agar `/ring`/`/record`/`/lock` bisa retry via offset `finally` bila kill di tengah, bukan hilang diam-diam; duplikat SMS tetap dicegah via pre-commit.
- Perintah sensitif (`/photo`, `/location`, `/lastsms`, `/history`, `/contacts` dkk) kedaluwarsa 300 dtk seperti mutating (sebelumnya 900 dtk) di `handleTelegramCommandInner` dan umur tombol menu, agar redelivery pasca-restart tak eksekusi ulang exfil.
- `CrashReporter.flushPending` menyimpan sisa laporan bila gabungan melebihi 4000 karakter, bukan menghapus seluruh file setelah kirim potongan pertama.
- `resolveStored` hanya memetakan mask `STORED_MASK`, field kosong tetap kosong sehingga Save dengan token/chat kosong ditolak validasi, bukan diam-diam memakai kredensial lama.
- `sendPairHint` throttle 1 jam dipersist wall-clock (`last_pair_hint_wall` di `boot_meta`) agar tahan restart, bukan hanya memori.
- Kunci throttle `postResumeReminder` diganti ke `last_resume_wall` (baca fallback `last_resume_elapsed` lama) agar nama sesuai isi wall-clock.

## [1.6.104] - 2026-10-05

### Fixed
- `pollTelegramCommands` tak lagi mereset offset ke 0 saat 400 offset; offset dipertahankan + backoff 30 dtk agar tak replay update lama.
- Pairing `/start <chat ID>` bisa rotasi owner tanpa Clear; owner lama digantikan bila arg cocok.
- `MessageQueue` memuat counter drop saat init jalur persistent langsung, bukan hanya saat restore.
- `scheduleMessageSend` memakai `APPEND` agar jadwal immediate tak dibuang saat chain `APPEND` tertunda.
- `flushBatch` tak lagi strip HTML seluruh batch; fallback plain diserahkan per-pesan ke `forwardLocked` seperti `sendFitted`.
- Ambang split diseragamkan 4000 di `sendToTelegram` dan `SendMessageWorker` agar konsisten dengan `sendFitted`/`sendChunked`.
- `MainActivity` memanggil `isFinishing`/`isDestroyed` via `runOnUiThread` agar cek lifecycle di main thread.
- `sendStatusNow` memakai kode fallback efektif untuk Toast sehingga 401/403 tak dilaporkan sebagai 400.
- Fallback `/history` memindai mundur per-halaman 500 baris (maks 5 halaman/365 hari) agar nomor lama tak terlewat.
- Rotasi token mereset `ownerUserId` sehingga owner wajib pairing ulang sebagai konfirmasi.

## [1.6.103] - 2026-10-05

### Fixed
- `SendMessageWorker` melaporkan drop kedaluwarsa/overflow walau tak ada pesan terkirim dalam satu run.
- `sendPairHint` memakai token segar `PreferencesManager`, bukan cache `sendCreds()` 5 menit.
- Hapus `isMainRunning()` duplikat di forwarder; cukup satu cek `MonitoringService.isRunning`.
- `sendStatusNow` mencatat `credentialError` bila fallback plain 400 juga 401/403 atau chat hilang.
- `forwardLocked` mencatat `credentialError` dan antre ulang bila fallback plain 400 juga auth/chat hilang, bukan drop diam-diam.
- `CrashReporter` memakai `Html.tagStripRegex` yang sama untuk fallback plain.
- `clearCredentials`/`clearQueue` mereset counter drop agar notifikasi basi tak muncul pasca-clear.

## [1.6.102] - 2026-10-05

### Fixed
- Menu perintah bot didaftarkan ulang saat token diganti sehingga rotasi token tak meninggalkan menu basi.
- `MainActivity` memakai `setMonitoringActive(true)` agar flag, consent, dan pause konsisten; hapus `secretStage` mati dan sederhanakan `onSaveInstanceState`.
- Hapus budget retry mati (`MAX_RETRIES`/`registerFailures`/`incrementRetry`); satu-satunya jalur aktif `registerTransientFailures` (budget 20).
- Balasan `Unknown command` hanya untuk owner; perintah tak dikenal dari anggota grup didiamkan agar tak bisa dipakai spam grup.
- Jalur overflow forwarder memeriksa `forwardingAllowed()` sebelum mencatat riwayat, sama seperti jalur normal.
- `saveSettings` menampilkan `setup_no_network` saat resolve `@username` gagal karena offline, bukan `setup_bad_chat`.
- `answerCallback` membaca token segar dari `PreferencesManager`, bukan cache `sendCreds()` 5 menit.
- `BootRestartWorker` memakai jeda eksplisit 30 menit + `success` saat belum konfigurasi, bukan `retry` tanpa backoff.

## [1.6.101] - 2026-10-05

### Fixed
- Watchdog loop mencakup siklus panjang (`syncInterval` s.d. 1440 mnt, kamera s.d. 60 mnt) agar stuck tetap terdeteksi.
- Wake-loop forwarder melambat ke 5 mnt saat monitoring di-pause; restart pemulihan `/resume` tetap jalan.
- SMS terkirim parsial ikut mengunci jeda 60 dtk agar retry tak membanjiri duplikat.
- Hapus parameter mati `alreadyRetried` di pengirim audio/foto.
- Cache daftar aplikasi ditandai `@Volatile` agar konsisten antar-thread.
- `/start` pairing yang salah format dibalas petunjuk langsung ke pengirim (throttle 1 jam).
- `onTaskRemoved` hanya menjadwalkan worker bila monitoring memang aktif.
- Fallback `/history` diperluas ke 365 hari/500 baris.
- Owner disimpan sinkron (`commit`) agar crash tak menghilangkan pairing yang offset-nya sudah maju.

## [1.6.100] - 2026-10-05

### Fixed
- Kalkulator stealth `1234` + `=` langsung membuka Setup (satu tekanan), bukan dua tekanan.
- Owner via `/start` wajib pairing `/start <chat ID>` di chat privat sehingga orang asing yang DM duluan tak bisa mengambil alih owner.
- `chatId` `@username` di-resolve ke ID numerik via `getChat` saat Save/Test sehingga gate perintah runtime tetap cocok.
- `/sms` menolak `,;`, huruf (`p/w`), dan `+` salah posisi agar nomor tak terpotong diam-diam ke penerima salah.
- Offset `lastUpdateId` hanya persist-duluan untuk `/smsconfirm`, `/ring`, `/record`, `/lock`; perintah staging tetap persist-sesudah agar crash tak menghilangkan perintah.
- `getUpdates` meminta `channel_post`/`edited_channel_post` sesuai handler yang sudah ada.
- Sisa batch notifikasi di `onDestroy` dipersist sinkron ke antrean, bukan via daemon `Thread`.
- `BootReceiver` memakai cap 12 jam yang sama untuk restore volume dering + clear state basi.
- `SendMessageWorker` tetap menjadwalkan ulang antrean saat belum dikonfigurasi, bukan yatim setelah 5 percobaan.
- Laporan crash dipertahankan saat monitoring mati (dihapus hanya setelah terkirim/kedaluwarsa 7 hari).
- Validasi Setup dilonggarkan realistis (`TOKEN_REGEX` 20+ char, chat ID minimal 4 digit).
- `addMessages()` mendukung cap prioritas yang sama dengan `addMessage()`.

## [1.6.99] - 2026-10-05

### Fixed
- Build `v1.6.97`/`v1.6.98` yang gagal (`compileDebugKotlin`/`compileReleaseKotlin`: `Type mismatch: CoroutineScope but Context was expected` di `MonitoringService.kt:444`) diperbaiki: `CrashReporter.flushPending(this)` di dalam `serviceScope.launch` diganti `this@MonitoringService`.

## [1.6.98] - 2026-10-05

### Fixed
- `startMonitoring()` memakai guard `compareAndSet` + `initialSyncLock` + cek identitas job sehingga dua start beradu (boot + worker + revive) tak meluncurkan dua `sendInitialData()` konkuren.
- `handleCallbackQuery()` mengizinkan `ownerOk` lewat gate dan menjawab callback dulu sebelum cek duplikat/kedaluarsa agar tombol inline tak muter selamanya di DM owner.
- Offset `lastUpdateId` dipersist sebelum eksekusi perintah mutatif (pesan dan tombol) sehingga mati di tengah tak me-replay perintah mutatif.
- `upgradeToPersistent()` memakai `commit()` (bukan dua `apply()`) agar copy terenkripsi tak parsial bila mati di tengah.
- `SendMessageWorker` melewati `registerTransientFailures()` bila kredensial berubah (`credsChanged`/`credsSame()`) agar gagal lawan token basi tak membakar budget retry.
- `sendFitted()` mempertahankan HTML per potongan via `TextChunk.safeCut()` (sama seperti worker) alih-alih membuang tag untuk pesan >4000.
- Validasi `chat_id` Setup menerima username `@channel` (`@` + 5-32 alfanumerik/`_`) karena API Telegram mendukungnya.
- `handleFgsTimeout()` memanggil `teardownJobs()` sehingga `commandBackoffUntil`/`mediaBackoffUntil`/`idlePolls` ikut direset dan kirim media tak tertahan pasca-restart.
- `isChatMissing()` tak lagi menyamakan `rights` (`not enough rights`/`have no rights`/`need administrator rights`) dengan chat hilang; kategori baru `isRightsLimited()` antre/keep tanpa freeze 30 menit dan tanpa label Auth FAILED.
- Counter drop-notice (`msgDropCount`/`dropNoticeCount`) dipersist ringan (`pendingMsgDrops`/`pendingNotifDrops`) + flush terjadwal saat restart; catatan batas: kill paksa di jeda milidetik antara increment dan `commit` masih bisa menghilangkan satu hitungan.

### Changed
- `getUpdates` memakai `allowed_updates=[message, edited_message, callback_query]` + `limit` (50 service / 20 forwarder) agar tak terima semua tipe update.
- `cacheStats()` hanya membaca top-level `cacheDir.listFiles()` untuk `/storage`, bukan `walkTopDown` seluruh cache.
- Deque spam per-paket (`pkgHits`) dicap 30 entri dalam jendela 2 menit agar burst tak membesar tanpa batas.
- Tombol Test Setup memakai probe `getMe` + `getChat` tanpa kirim pesan spam ke chat.

## [1.6.97] - 2026-10-05

### Fixed
- Konfirmasi `/restart` dan `/syncinterval` dikirim sebelum `restartAllLoops()` agar tak hilang karena cancel `commandJob` diri sendiri.
- `scheduleMessageSendNext()` memakai `APPEND` (bukan `KEEP`) sehingga retry-after `429` tak gugur saat worker sudah antre.
- Pending SMS gagal tak lagi memperbarui timestamp (`reopenSmsPending` dihapus; jendela 5 menit berjalan sejak staging) sehingga `/sms` tak terkunci permanen.
- Drop-notice `400` di `sendToTelegram()` digabung per 30 detik + flush saat destroy (`noteDroppedMessage`).
- `/clearqueue` mengirim konfirmasi tanpa `queueOnFail` sehingga antrean benar-benar kosong saat offline.
- `restartAllLoops()` me-reset `initialSyncRunning` lalu memulai ulang sync awal hanya bila yang lama benar-benar terinterupsi.
- `BootReceiver` dan `clearCredentials()` memakai `clearRingStateSync()`; kredensial-clear juga membersihkan state ring.
- `/record` menolak saat `ringBusy` (simetri dengan `/ring` vs `recordBusy`).

### Changed
- Timer batch forwarder 15 detik berjalan di luar lajur serial (`withContext(fwdSerial)` hanya untuk flush) sehingga burst notifikasi tak tertahan.
- Batas tunggu konfirmasi SMS 30 dtk + 15 dtk/part (dulu 60 + 30) agar `smsBusy` tak dipegang sampai 150 dtk.
- `sendChunked()` worker mempertahankan HTML per potongan (fallback plain per chunk sudah ada).
- Drop-notice worker digabung satu pesan (`expired; overflow; retries; rejected`).
- `CrashReporter.flushPending()` dipanggil tiap siklus monitoring (murah bila tak ada file crash).
- Klaim audit gugur: `429` worker tak membakar budget retry (`RateLimited` tak menyentuh `failedIds`).

## [1.6.96] - 2026-10-05

### Fixed
- Volume dering tak lagi macet maksimal: `ringPrevVolume`/`ringSavedAt` ditulis sinkron via `setRingStateSync`/`clearRingStateSync` (`PreferencesManager`), jadi mati proses di tengah dering tetap bisa dipulihkan jaring pengaman 12 jam.
- `429` audio/foto tak lagi menahan `/record`: `sendAudioFile`/`sendPhotoFile` memakai backoff non-blokir ala polling perintah (`mediaBackoffUntil` + `scheduleMessageSendNext`), file dipertahankan untuk retry terjadwal.
- `initialSyncDone`/`initialSyncStarted` ditulis sinkron (`setInitialSyncDoneSync`/`setInitialSyncStartedSync`), jadi mati tepat setelah "sync complete" tak mengulang histori penuh.
- `/sms` menolak staging baru bila pending aktif belum kedaluwarsa (5 menit), alih-alih menimpa diam-diam.
- Drop-notice notifikasi digabung: reject `400` dihitung (`dropNoticeCount`) lalu satu notice agregat per 30 detik + saat destroy, alih-alih satu notice per notifikasi.
- `sendStatusNow` mengantre status diagnostik sebagai prioritas agar tak tergusur antrean penuh.
- Rekursi tak berujung `noteOverflowLocked`/`noteExpiredLocked` diperbaiki (`addAndGet`).

### Changed
- `MessageQueue.persistLocked` memakai `apply()` + `flushSync()` eksplisit di titik kritis (`clearQueue`, `SendMessageWorker`, `MonitoringService.onDestroy`).
- `ringDevice()` memakai jeda durasi penuh sekali + replay-on-completion via `MediaPlayer.setOnCompletionListener` (fallback `Ringtone`), alih-alih bangun tiap 1 detik.
- Fallback pencarian nomor SMS/panggilan memakai filter waktu di provider (`getRecentSmsSince`/`getCallsSince` dengan `DATE >= ?` + `LIMIT`).
- `checkAndSendNewData` mengandalkan `LIMIT 100` provider tanpa `take(100)` di memori.

## [1.6.95] - 2026-10-05

### Fixed
- Build `v1.6.94` yang gagal diperbaiki: string `</pre>` rusak di `CrashReporter.kt` dan `selects.onAwait` yang tak dikenal diganti pemenang lomba via `invokeOnCompletion` + timeout (tanpa impor baru, listener kalah tetap dibersihkan).

## [1.6.94] - 2026-10-05

### Fixed
- Build `v1.6.93` yang gagal (`Redact.kt` smart-cast nullable) diperbaiki; impor `selects` dipertegas.
- Laporan crash dikirim sebagai `HTML` (bukan tag mentah) dengan potongan sadar-tag.
- `isChatMissing` mengenal tendangan/blokir grup ("bot was kicked", "not a member", "rights") agar tak dibuang sebagai reject.
- Test koneksi yang gagal `401/403` kini merekam blokir kredensial seperti jalur monitoring.
- `sendStatusNow` mencoba fallback plain saat `400` non-chat-missing sebelum mengantre.
- Fallback `/history` dibatasi 90 hari terakhir agar tak full-scan provider.

### Changed
- `safeTake`/`batchCut`/potongan inline disatukan ke `utils/TextChunk.kt`; token masking memakai `utils/Redact.kt`.
- Pencocokan nomor disatukan ke `utils/PhoneNumbers.kt`; query paginasi disatukan ke `utils/ContentQuery.kt`.
- Sweep blokir auth disatukan ke `NetworkUtils.sweepAuthBlock()` (5 lokasi).
- `formatStatusTime` dihapus; pemanggil memakai `TimeFmt.full` langsung.

### Removed
- Duplikat `altVariant`/`numbersEqualFast`/`filter*` di kedua repo; `batchCut`; blok reset-expiry yang tersebar.

## [1.6.93] - 2026-10-05

### Fixed
- Backoff `429` ikut direset saat teardown: `/restart` tak lagi bikin polling diam sampai 5 menit.
- Retry transien (`408`/`5xx`/timeout) dapat budget sendiri 20x (`registerTransientFailures`) + backoff eksponensial 60 dtk–15 mnt; outage singkat tak lagi membuang antrean.
- Hitungan drop (`overflow`/`expired`) dipersist di store antrean + dibaca ulang saat restore; reboot tak lagi menghilangkan kabar drop.
- `sendChunked` berhenti di part pertama yang direject Telegram agar status drop konsisten dengan yang terkirim.
- `/record` boleh jalan saat dering berbunyi; kopling `ringBusy` yang artifisial dihapus.
- `getLastKnownLocation` lanjut ke provider berikut saat satu provider melempar `SecurityException`.
- `fetchLocation` menunggu fix tercepat via `select` + timeout, bukan busy-poll tiap 500 ms.
- Jeda antar-part dirasionalkan (`sendFitted` 500→300 ms, worker 1000→500 ms).

### Changed
- `safeCut`/`splitChunk` dan `redactToken` disatukan ke `utils/TextChunk.kt` + `utils/Redact.kt`.
- Label aplikasi `/apps` dicache 10 menit agar tak `queryIntentActivities` + sort tiap panggilan.
- `scheduleMessageSendCoalesced` yang identik dihapus; pemanggil memakai `scheduleMessageSendNext`.
- Komentar manifest diselaraskan ke Inggris.

### Removed
- Kode mati: `numberMatches`/`altVariant`/`numbersEqualFast` di `MonitoringService`, `putAllValues` di `PreferencesManager`, consume drop statis duplikatif di `MessageQueue`.

## [1.6.92] - 2026-10-05

### Fixed
- Polling perintah tak lagi beku sampai 5 menit saat `429` Telegram: backoff non-blokir (`commandBackoffUntil`) seperti wake-loop forwarder.
- Menu inline tak lagi hilang saat retry: potongan gagal yang diantrekan menyertakan petunjuk `Tap /help untuk menampilkan tombol menu`.
- Callback dari pengirim asing dibuang tanpa `answerCallbackQuery`: cegah amplifikasi API via `callback_query` unik; callback owner dihormati saat blokir auth.
- Owner auto-learn hanya untuk `/start` persis (dengan/tanpa argumen), varian `/start@botlain` ditolak.
- `wakeUpdateId` ditulis sinkron (`setWakeUpdateIdSync`): matinya proses tak picu wake `/ping` ganda.
- Worker menjadwalkan ulang kirim antrean saat kredensial berganti di tengah flush.
- Kunci dedup boot diganti `last_handle_wall` (migrasi dari `last_handle_elapsed`) agar basis jam dinding eksplisit.
- `saveCoreConfig`/`saveTestCredentials` naik ke persistent store dulu lalu `commit` sinkron: Save/Test dari UI tak hilang bila proses mati.
- `/sms` menolak premium format IDD Amerika `011...` (`0111900`) seperti format `00`.
- Tipe foreground `LOCATION` ditambah untuk izin lokasi foreground-only, bukan hanya bila background granted.

## [1.6.91] - 2026-10-05

### Fixed
- Restore volume alarm satu aturan 12 jam di semua jalur (`onCreate`, `startMonitoring`, `BootReceiver`) — volume basi berumur hari tak lagi dipulihkan.
- `sendChunked` antrekan sisa part saat `429`/auth di tengah chunk: prefix tak ganda, suffix tak hilang.
- State SMS kritis (`pending`, `lastSmsSendAt`) tulis sinkron (`commit`) — matinya proses tak picu SMS ganda.
- Tombol Test ikut simpan interval sync/kamera, bukan cuma token/chat ID.
- `sendToTelegram` return `false` saat antre/gagal — selaras `sendFitted` (`true` = terkirim).
- `saveCoreConfig`/`saveTestCredentials` pakai helper `putCredentialState` yang sama, cegah divergen.
- `/apps` arg bukan-angka tampilkan usage, bukan diam-diam list 30.
- `/storage` tampilkan hitungan foto dan audio terpisah agar byte cache tak disalahbaca.
- Autostart kalkulator tak lagip mensyaratkan background location — selaras jalur boot, lokasi terdegradasi dicatat di log.
- Cursor SMS/call (`lastSmsId`, `lastCallTimestamp/Id`) tulis sinkron per part — riwayat tak ganda saat retry.

## [1.6.90] - 2026-10-05

### Fixed
- Spam filter notifikasi atomik saat antre (`pkgTryAcquire`): hitung di waktu cek, bukan saat kirim sukses — burst di atas 32 antrean tak lagi lolos dari batas 10 per 2 menit.
- Wake-loop forwarder mundur saat `409`/`429` Telegram (`wakeBackoffUntil` 60 detik / `retry_after`), hentikan hantaman tiap 25 detik saat long-poll ganda konflik.
- `/sms` tolak premium luar negeri (UK `70`/`90`/`118`, US `1-900`, `809`) termasuk format `00`, bukan hanya pola ID.
- Validasi chat ID Setup wajib minimal 5 digit agar typo 3-4 digit tertolak di UI sebelum kena blokir `400` 30 menit.
- Kalkulator `1234` + `=` pertama berperilaku normal (angka berikut mulai entri baru) dan tahap secret tak bertahan lewat rotasi.
- Volume alarm dipulihkan saat service dibuat bila proses mati mid-ring (`ringPrevVolume` tersisa), bukan stuck max.
- Callback query duplikat/gagal-auth tak lagi panggil `answerCallbackQuery` — cegah amplifikasi API via replay.
- `/location` dan owner-link `/start` dijalankan sekuensial (bukan fire-and-forget) agar offset Telegram commit sesudah hasil terkirim.
- Scheduler antrean selalu `KEEP`: retry tertunda tak lagi cancel worker yang sedang jalan.
- Crash basi kedaluwarsa benar sesudah reboot (umur pakai wall-clock bila monotonic negatif).

## [1.6.89] - 2026-10-05

### Fixed
- Pending SMS dikonsumsi sebelum transmit dan dibuka lagi hanya saat gagal, cegah SMS dobel saat reboot di jendela kirim.
- Batch notifikasi diamankan ke antrean saat service dimatikan agar tak hilang.
- Foto/audio yang kena rate-limit menunggu jeda server lalu coba sekali lagi, bukan pasrah ke siklus berikutnya.
- Flush background melapor saat berkas ditolak Telegram, sama seperti jalur manual.
- Tombol inline basi menerbitkan menu segar, bukan sekadar pesan expired.
- Loop macet-tapi-hidup terdeteksi via heartbeat dan direstart watchdog.
- `AdminReceiver` pindah kerja antrean ke background thread.
- `mapping.txt` tak lagi diunggah ke artifacts.
- Antrean korup meninggalkan kabar drop, bukan hilang diam.
- Kabar drop/alert dapat slot cadangan 5 di antrean penuh agar tak gusur data.

## [1.6.88] - 2026-10-05

### Fixed
- Offset perintah Telegram disimpan sesudah perintah dijalankan (sebelumnya sebelum), cegah perintah hilang saat proses mati.
- `sendFitted`/`sendToTelegram` berhenti di awal saat kredensial diblokir lalu antre sekali, cegah hantaman 401 berulang.
- Pesan HTML panjang dipecah sebagai teks polos (`sendFitted`, `flushBatch`, `sendChunked`), cegah tag tak berpasangan dan putaran fallback 400.
- Riwayat `/lastnotif` tetap dicatat untuk notifikasi duplikat dan ringkasan grup yang disaring.
- Lokasi NETWORK dan GPS diminta paralel dengan batas total 25 detik (sebelumnya seri 40 detik).
- `/sms` wajib 7-15 digit dan semua short code <= 6 digit ditolak sebagai premium.
- Validasi token Setup wajib secret 30+ karakter agar typo tertolak di UI.
- Kode rahasia kalkulator menjadi `1234` + `=` dua kali; `1234` + `=` pertama berperilaku normal.
- Antrean persisten ditulis sinkron (`commit`) agar tak hilang/duplikat saat proses mati.
- Toast superseded palsu sesudah simpan/tes dihapus; cek versi hanya sebelum persist.

## [1.6.87] - 2026-10-05

### Fixed
- `MonitoringService.searchContacts` gagal kompilasi (referensi `uri`/`projection`/`escaped` hilang saat refactor query Bundle di `v1.6.85`); deklarasi dikembalikan agar build hijau.

## [1.6.86] - 2026-10-04

### Fixed
- `NotificationForwarderService.forwardLocked` gagal kompilasi (bare `return` dalam `(): Boolean` + 2 panggilan kurang arg `pkg`); kini `return false` + batch teruskan `""` agar kompilasi hijau.
- `MonitoringService.sendFitted` jalur pendek probe-then-queue (`sendToTelegram` tanpa antre lalu antre sekali) agar tak balap ukuran antrean lintas-thread forwarder/service.
- `NotificationForwarderService.flushBatch` potong batch pakai `batchCut` aman-surrogate/entity/tag, bukan hard-cut 4000.
- `NotificationForwarderService.forwardLocked` 400 non-chat antrekan notice drop terlihat, bukan hilang diam seperti `MonitoringService`.
- `flushBatch` catat spam sekali per batch sukses via outer `pkgRecord` (batch teruskan `pkg` kosong), cegah hitung ganda inner+outer.
- `isMainRunning` delegasi ke `MonitoringService.isRunning`, buang `getRunningServices` deprecated yang tak andal.
- `MonitoringService.sendToTelegram` notice drop 400 selalu diantre walau `queueOnFail` false agar probe pendek tak menelan bukti drop.

## [1.6.85] - 2026-10-04

### Fixed
- `SendMessageWorker.sendDropNotice` gagal kompilasi (outer `catch` pakai `text` dalam-scope `try`); struktur ditulis ulang agar fallback antrean tetap jalan. Proyek tak bisa build sejak `v1.6.83` — temuan kritis.
- `sendFitted` jalur pendek kini samakan semantik split: return `false` bila pesan masuk antrean (cek tumbuhnya antrean), sehingga `else break` offline bekerja untuk semua ukuran pesan.
- Worker `sendChunked`: sisa part yang belum terkirim diantrekan sebagai pesan baru dan pesan asli dianggap terkirim — tanpa duplikat, tanpa hilang saat retry.
- Reset `LAST_UPDATE_ID`/`WAKE_UPDATE_ID` hanya bila token bot berubah (bukan tiap simpan config), cegah eksekusi ganda perintah pasca re-save.
- `sendStatusNow` antrekan teks status untuk semua 400, bukan hanya chat-missing.
- Scheduler delay>0 pakai `REPLACE` agar rantai retry 429 coalesce jadi satu, bukan APPEND tak terbatas.
- `/history` buang filter ganda service (repo sudah filter); `searchContacts` pakai `QUERY_ARG_LIMIT` 10; eviksi `wakePingIds` buang ID terkecil dulu.

## [1.6.84] - 2026-10-04

### Fixed
- `sendFitted` kembalikan `false` saat kirim langsung gagal (walau sudah antre) sehingga loop `checkAndSendNewData`/`sendInitialData` berhenti di chunk pertama yang gagal, bukan hantam 10 chunk saat offline; kursor tetap maju karena salinan sudah antre.
- `sendToTelegram` teruskan `replyMarkup`/`queueOnFail` ke `sendFitted` (tombol dipasang di chunk terakhir), sebelumnya selalu dibuang.
- Owner auto-learn hanya via `/start` chat privat + notifikasi ke grup config; wake-loop forwarder tak lagi klaim owner dari `/ping` privat (bangun privat sebelum link tetap lewat `/ping` grup).
- Polling perintah saat pause dipercepat 5 mnt ke 60 dtk agar `/resume` responsif.
- `fetchLocation` dahulukan NETWORK sebelum GPS agar `/location` cepat di dalam ruangan.
- Notifikasi terpotong spam-filter tetap dicatat ke `/lastnotif` (klaim notice kini benar); dedup exact-match dipersingkat 30 dtk ke 10 dtk agar OTP kirim-ulang tak tertelan.
- Fallback `/history` SMS/panggilan 500 ke 200 entri; suffix-match nomor diperketat 9 ke 10 digit.
- `sendInitialData` deteksi paginasi terpotong (5 page penuh) dan kirim pesan partial jujur, bukan klaim complete.
- Wake-loop pakai offset `max(mainLast, wakeUpdateId)+1` agar tak fetch ulang backlog yang sama tiap 25 dtk saat service utama mati.
- `TOKEN_REGEX` Setup wajib `6+` digit id dan `20+` karakter secret agar typo tertolak di UI.

## [1.6.83] - 2026-10-04

### Fixed
- `/ring` putar ulang nada sampai durasi habis (sebelumnya hanya bunyi sekali).
- Pause foto wall-clock dipertahankan saat reboot (hapus reset paksa di `BootReceiver`); volume alarm stuck dipulihkan ke nilai awal saat boot.
- Throttle `restartAllLoops` dilewati untuk jalur watchdog agar loop mati segera restart.
- `sendDropNotice` gagal kirim fallback ke antrean + jadwal worker.
- Capture kamera pasang `CaptureCallback` agar gagal langsung `onError`, bukan gantung sampai watchdog.

## [1.6.82] - 2026-10-04

### Fixed
- Long-poll `getUpdates` `timeout=10` jadi `30` di loop perintah dan wake-loop agar handshake HTTPS ~3x lebih jarang.
- Polling perintah saat monitoring pause diperlambat 60 dtk ke 5 mnt.
- Forwarder gabungkan notifikasi dalam jendela 15 dtk jadi 1x POST (pecah per 4000 karakter); hapus cap `inFlight` yang tergantikan batch.
- `SendMessageWorker` persist antrean tiap 5 pesan terkirim + flush akhir, bukan per pesan.
- Wake-loop persist `wakeUpdateId` tiap siklus dan tolak `/ping` wake berumur >900 dtk.
- `photoPausedUntil` pindah ke wall-clock + migrasi nilai lama sehingga pause selamat dari reboot.

## [1.6.81] - 2026-10-04

### Fixed
- SMS multipart pakai `FLAG_UPDATE_CURRENT` + requestCode monotonik (`smsReqSeq`) agar part tak saling membatalkan saat dua `/smsconfirm` berurutan.
- `isPremiumSmsNumber` blokir `1900` eksplisit di semua panjang; short-code sah awalan `9` tak lagi tertolak massal.
- Fallback `/history` SMS dan panggilan diperlebar 100 ke 500 entri agar nomor format silang (`+62812` vs `0812`) tetap ketemu.
- Tes koneksi Setup simpan kredensial via `saveTestCredentials` (token/chat saja), interval tak lagi tertimpa.
- `SendMessageWorker` kosongkan `credentialError` hanya bila kredensial tak berubah mid-batch.
- `CrashReporter` gabung laporan crash bertumpuk (append + cap), bukan timpa.
- Cap kewarasan `photoPausedUntil` dilonggarkan 480 ke 1440 mnt agar pause policy 120 mnt + migrasi wall-clock tak ter-reset prematur.
- `MessageScheduler` pakai `APPEND` saat ada `initialDelayMs` agar jeda rate-limit tak dibuang `KEEP`.
- `CameraService.startBackgroundThread` `join` thread lama sebelum ganti, cegah thread bocor.

## [1.6.80] - 2026-10-04

### Removed
- Import mati: `android.database.Cursor` (`CallLogRepository.kt`) dan `CoroutineScope`/`Dispatchers`/`launch` (`CameraService.kt`); hanya bersih-bersih warning kompiler.

## [1.6.79] - 2026-10-04

### Removed
- Fungsi mati: `PreferencesManager.putValue` (setter generik tanpa pemanggil) dan `SmsRepository.numbersEqual` (wrapper tanpa pemanggil); tanpa perubahan perilaku.

## [1.6.78] - 2026-10-04

### Changed
- Helper Telegram terpusat: `isChatMissing` (5 file), `authBlocked` 401/403/400 (service + worker), dan `tagStripRegex` kini milik `NetworkUtils`/`Html`; `scheduleMessageSendCoalesced` delegasi ke `scheduleMessageSendNext`; tanpa perubahan perilaku.
- `SendMessageWorker` lewati hapus bulk akhir bila hapus inkremental semua sukses, hemat satu persist antrean tiap run.

### Removed
- Resource mati: `purple_200`, `green_success`, `red_error`, `msg_fill_all_debug`, `queue_pending`.

## [1.6.77] - 2026-10-04

### Changed
- `SendMessageWorker` cek kredensial ulang tiap 5 pesan (bukan tiap pesan) sehingga batch 20 hanya ~10 baca prefs terenkripsi, bukan ~40; deteksi ganti kredensial mid-batch tetap jalan via cek berkala + `AuthFailed`.

## [1.6.76] - 2026-10-04

### Changed
- `MessageScheduler.scheduleMessageSend` pakai `KEEP` (bukan `APPEND`) agar trigger offline yang menumpuk digabung, bukan antre berantai; worker kuras antrean + jadwal ulang sendiri bila sisa, jadi tak ada pesan tertahan.

## [1.6.75] - 2026-10-04

### Fixed
- `SendMessageWorker` hapus pesan terkirim/ditolak inkremental per pesan agar kill di tengah batch 20 tak kirim ulang duplikat; `removeMessages` akhir dipertahankan sebagai jaring pengaman.

### Removed
- Hapus `SpeedMonitorActivity` unreachable beserta `NetSpeed`, `activity_speed_monitor.xml`, entri manifest, dan 3 string terkait yang mati sejak tap notif pindah ke kalkulator.

## [1.6.74] - 2026-10-04

### Added
- Tema terang/gelap otomatis ikut sistem: warna kalkulator dan layar speed pindah ke resource `calc_*` dengan varian `values-night`, tombol/display/ripple menyesuaikan.

## [1.6.73] - 2026-10-04

### Changed
- Samarkan permukaan terlihat: notif persisten jadi `Calculator` / `Service running` dengan tap ke kalkulator (bukan halaman setup); ikon status bar jadi glif kalkulator netral; teks jeda jadi `Service paused` / `Tap to open settings`. Alert auth dan reminder tetap tap ke setup agar owner bisa bertindak.

## [1.6.72] - 2026-10-04

### Fixed
- `handleCallbackQuery` teruskan tanggal pesan tombol (`query.message?.date`) ke gate freshness agar tap inline basi expired seperti perintah teks (900 dtk umum, 300 dtk mutasi) dan tak replayable selamanya.

## [1.6.71] - 2026-10-04

### Fixed
- `BootReceiver` throttle persisten pindah ke wall-clock + lewati debounce saat guard masih nol agar restart langsung pasca-reboot tak gugur (sebelumnya `elapsedRealtime` yang reset saat reboot + delta negatif lawan nilai boot lama menahan start, pulih hanya via watchdog 15 mnt).
- `BootRestartWorker.postResumeReminder` akar sama: throttle persisten wall-clock agar notif buka Setup tak bungkam tepat saat FGS ditolak pasca-reboot.
- `NotificationForwarderService.forwardLocked` coba plain-fallback (strip tag) dulu saat `400` non-chat-hilang seperti `MonitoringService` sebelum drop permanen.
- `CrashReporter` potong laporan pakai batas aman surrogate + entity di `buildReport` dan `flushPending` agar tak belah entity/surrogate.

## [1.6.70] - 2026-10-04

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
