## [Unreleased]

### Fixed
- Teks `/hematdata` kini menyatakan pengecualian media perintah langsung — janji penahanan tidak lagi tanpa syarat (issue #370): balasan "nyala" dan teks bantuan sebelumnya menyatakan foto/video/rekaman ditahan saat pakai data seluler tanpa pengecualian apa pun (daftar pengecualian di bantuan hanya menyebut SMS/lokasi/notifikasi, terbaca sebagai daftar tuntas), padahal media dari permintaan langsung (`/photo`, `/screenshot`, `/record`, `/recordvideo`) selalu dikirim seketika — penahanan hanya berlaku di ketiga jalur flush dan pada tangkapan foto otomatis. Pengecualian rancangan itu sebelumnya hanya hidup di klarifikasi CHANGELOG lama yang merujuk README, dan klarifikasi README-nya sendiri sudah hilang, sehingga tidak ada teks yang bisa dibaca pengguna yang menyatakannya: pemilik yang menyalakan hemat kuota untuk melindungi kuota kecil lalu mengirim `/recordvideo` di data seluler akan mendapati media terbesar justru terkirim memakai kuota yang dikira dilindungi, atau menguji dengan `/photo` dan menyimpulkan fitur rusak karena foto tiba seketika. Kedua teks kini menyebut pengecualiannya secara eksplisit (yang ditahan adalah media otomatis; permintaan langsung tetap dikirim segera), sejajar klarifikasi cakupan yang dulu terdokumentasi. Perilaku pengiriman tidak berubah sama sekali — yang diperbaiki hanya janjinya. (Vonis gugur putaran-50 atas kandidat ini terbalik sesudah audit-atas-audit putaran-51: kelasnya sama dengan temuan teks/janji #347/#360/#363.)


## [1.6.553] - 2026-10-30

### Fixed
- Kode OTP alfanumerik tidak lagi lolos dari samaran (issue #369): keputusan "notifikasi ini OTP, samarkan" diambil dari kata kuncinya, tetapi `NotificationPrivacy.mask` sebelumnya hanya menyamarkan grup digit murni — kode campuran huruf-digit (mis. `X7K9P2`) tidak cocok dengan pola digit sama sekali dan terkirim utuh terbaca ke Telegram, dan rangkaian digit berpemisah tak beraturan (`123  456`, `123 - 456`) juga lolos karena pola hanya menoleransi satu pemisah antar-digit. `mask` kini — hanya dipanggil pada teks yang sudah diputuskan OTP — juga menyamarkan token campuran huruf-digit berpanjang lazim kode (4–10 aksara, mengandung ≥1 huruf dan ≥1 digit) ke token tetap yang sama, dan pola grup digit menoleransi pemisah berurutan tak beraturan. Penjaga arah sebaliknya terkunci uji JVM: kata murni huruf tanpa digit tidak pernah disamarkan, kalimat biasa tanpa kode tetap utuh, dan token campuran lebih panjang dari 10 aksara (ID transaksi dsb.) tidak disamarkan sebagian. Cakupan numerik murni dari perbaikan #368 tidak berubah hasilnya.
- Bentuk samaran OTP tidak lagi membocorkan panjang & struktur kode persis (issue #368): `NotificationPrivacy.mask` sebelumnya mengganti tiap digit dengan satu `•` dan mempertahankan pemisah internal, sehingga `"123456"` menjadi `"••••••"` dan `"123 456"` menjadi `"••• •••"` — siapa pun yang bisa membaca chat Telegram pemilik (pratinjau notifikasi, cadangan chat, layar terbuka) mengetahui panjang persis dan pola pengelompokan kode (4=PIN, 6=OTP standar, 8=token), petunjuk paling berguna untuk mempersempit tebakan pada jendela masa berlaku kode. Seluruh grup digit yang cocok kini diganti satu token berpanjang tetap (`••••`) tanpa memelihara pemisah internal; teks di luar grup digit tidak berubah, dan grup yang sama yang muncul dua kali menghasilkan token yang sama. Uji JVM yang mengunci bentuk lama diperbarui di commit yang sama (nilai harapan dibuktikan lewat pemetaan manual karakter-demi-karakter).
- Keberhasilan dering kini hanya diklaim bila dering terverifikasi bisa terdengar (issue #367): `ringDevice` sebelumnya menaikkan volume alarm ke maks di dalam try/catch kosong tanpa baca-balik, tidak pernah memeriksa filter interupsi Jangan Ganggu di seluruh repo, dan melaporkan "Berdering" serta "Ring finished." tanpa syarat — pada perangkat DND hening total atau yang menahan volume alarm, HP tetap senyap sementara pemilik menerima dua klaim sukses (untuk fitur pencari HP hilang, klaim sukses palsu adalah kegagalan terburuknya); jalur fallback `Ringtone` juga hanya mencoba `play()` berulang dan tak pernah menyimpulkan kegagalan walau `isPlaying` tak pernah `true` selama durasi penuh. Tiga lapis verifikasi kini ditambahkan: (a) sesudah penaikan, volume alarm dibaca balik — bila tak mencapai maks, pesan mulai dering menyebutkannya jujur (terbaca sekian dari maks sekian) alih-alih klaim polos; (b) filter interupsi kini dibaca (`NotificationManager.currentInterruptionFilter` — membaca tidak butuh akses kebijakan) dan bila `INTERRUPTION_FILTER_NONE` (hening total) dering dibatalkan dengan laporan bahwa dering tak akan terdengar, bukan "Berdering"; (c) jalur fallback melacak apakah pemutar pernah sungguh berbunyi dan melaporkan "Gagal berdering (suara alarm tak pernah terdengar…)" bila tidak pernah, bukan teks selesai. Jalur sehat tidak berubah: perangkat normal (volume terbaca maks, filter bukan hening total, pemutar berbunyi) menghasilkan teks dan perilaku persis seperti sebelumnya, dan kegagalan membaca balik/filter diperlakukan sebagai tak terverifikasi, bukan sebagai kegagalan.
- Dering yang sedang berjalan kini bisa dihentikan dari Telegram lewat `/stop` (issue #366): sebelumnya tidak ada perintah Telegram yang bisa menghentikan dering yang sudah berbunyi — handler `/stop` hanya memasang `monitoringPaused`, dan satu-satunya pembatalan `ringJob` di luar durasinya adalah `teardownJobs` (layanan berhenti dari Setup/`onDestroy`), sementara `/ring` & `/hilang` baru selama dering berjalan ditolak CAS — sehingga dering hanya bisa berhenti dengan menunggu durasinya habis (maks 60 detik) atau mematikan seluruh layanan. Handler `/stop` kini ikut membatalkan `ringJob` bila memang sedang aktif; pembatalan itu aman karena `finally` `ringDevice` melepaskan pemutar dan memulihkan volume pada `CancellationException`, dan balasan `/stop` menyebutkan penghentian dering hanya bila dering sungguh sedang berjalan. Perilaku `/stop` tanpa dering berjalan tidak berubah sama sekali.


## [1.6.552] - 2026-10-30

### Fixed
- Pra-filter query kontak tidak lagi melewatkan kontak berformat (issue #365): pra-filter `isKnownContact` sebelumnya menuntut 7 digit ekor nomor muncul **kontinu** di kolom `NUMBER` mentah (`LIKE '%4567890%'`), sehingga kontak tersimpan dengan pemisah di dalam ekornya — format umum hasil sinkron, mis. `+62 812-3456-7890` — tidak pernah terambil dan tidak pernah mencapai verifikasi sadar-format `contactNumberMatches`; nomor yang sebenarnya dikenal terlabel persisten "🆕 Nomor baru" (dan hasil `false`-nya ikut ter-cache) serta menggelembungkan skor `/waspada`. Pola LIKE kini disisipi `%` antar-digit ekor (`%4%5%6%7%8%9%0%`): digit ekor harus muncul berurutan dengan pemisah (atau digit lain) apa pun di antaranya, tetap selektif, dan merupakan superhimpunan pola lama — terbukti lewat uji SQLite independen bahwa semua yang lolos pola lama tetap lolos, hasil untuk kontak tak berformat identik, dan nomor tak terkait tetap tersisih. `contactNumberMatches` tetap penentu akhir, sehingga hasil `false` kini hanya disimpan sesudah pra-filter longgar ini pun tak menemukan kandidat terverifikasi. Situs kembar `searchContacts` memakai mekanisme yang sama persis (pola ekor kontinu yang sama terhadap `NUMBER` mentah, verifikasi akhir yang sama) dan diperbaiki dengan pola yang sama dalam commit ini.
- Cache klasifikasi kontak kini segar terhadap perubahan kontak (issue #364): `isKnownContact` sebelumnya menyimpan hasil query — termasuk `false` (nomor asing) — dalam cache 6 jam yang hanya disegarkan oleh umur, tanpa pengamat perubahan apa pun di seluruh repo, sehingga kontak yang baru disimpan tetap terlabel "🆕 Nomor baru (tak ada di kontak)" dan berbobot asing di skor `/waspada` sampai 6 jam, dan kontak yang dihapus tetap dianggap dikenal selama itu — label & skor disajikan sebagai fakta kini tanpa penanda kebasian. Layanan kini mendaftarkan `ContentObserver` pada URI nomor telepon `ContactsContract` (dengan keturunan) selama layanan hidup yang menginvalidasi cache pada perubahan kontak apa pun, dilepas rapi di `onDestroy`; cache juga diinvalidasi setiap layanan mulai sebagai jaring pengaman, dan pendaftaran dibungkus try/catch sehingga kegagalannya (lingkungan/binder) tidak mengganggu layanan — jendela umur 6 jam tetap menjadi batas terakhir. Perilaku bawaan gagal tidak berubah: kegagalan query tetap menghasilkan "dikenal" yang tidak disimpan ke cache.
- Kegagalan definitif semua bagian SMS kini dilaporkan sebagai gagal, bukan "timeout total — bisa jadi sudah sampai" (issue #363): penerima hasil `sendSmsPending` menyelesaikan penantian begitu `okCount + failCount >= expected`, jadi bila radio menjawab gagal untuk SEMUA bagian, penyelesaian itu terjadi seketika dan definitif — tetapi cabang laporan hanya membedakan terkirim / sebagian / sisanya, dan keadaan sisanya selalu memakai teks ketidakpastian timeout, padahal `failCount` (penghitung yang sama yang menyelesaikan penantian itu) memegang sinyal pembedanya dan tidak pernah dibaca logika laporan. Cabang laporan kini membedakan tiga keadaan secara eksplisit: semua bagian OK (terkirim), `failCount == expected` (gagal definitif — dilaporkan "semua bagian ditolak radio … pasti belum sampai, aman untuk /smsconfirm lagi tanpa risiko dobel"), dan sisanya (timeout sungguhan / sebagian bagian belum menjawab — teks ketidakpastian lama dipertahankan). Semantik selain teks identik dengan kasus timeout: stage tetap ditahan untuk coba lagi dan `lastSmsSendAt` tidak dicatat — radio memang sudah menjawab, tetapi tidak ada yang terkirim sehingga tak ada jeda kirim yang perlu dijaga, dan upaya berikutnya tetap lewat `/smsconfirm` eksplisit.
- Owner hasil restore dari Setup kini ditulis dalam commit kredensial yang sama — tak ada lagi tulis terpisah yang hasilnya ditelan (issue #362): ketiga situs simpan Setup sebelumnya memanggil `saveCredentials` dulu, lalu menulis owner pulihan lewat commit terpisah `setOwnerIdSync` di `applyPendingRestoreOwner` — fungsi yang mengabaikan nilai kembali `Boolean`-nya dan menolkan penanda pulihan tanpa syarat, sehingga kegagalan commit owner membuat owner hasil restore hilang senyap (tak ada coba ulang karena penanda sudah terhapus) sementara UI tetap menampilkan "Tersimpan". Ketiga situs (simpan terverifikasi, simpan offline, dan Tes→Simpan) kini meneruskan `pendingRestoreOwnerId` sebagai `ownerIdOverride` ke `saveCredentials` — pola atomik #359: owner ditulis di dalam commit kredensial yang sama, dan penanda pulihan hanya dibersihkan (fungsi ganti nama `clearPendingRestore`, tanpa tulis owner lagi) sesudah commit itu berhasil; bila commit gagal, penanda bertahan sehingga restore bisa dicoba lagi dan jalur gagal yang sudah ada melaporkan kegagalannya dengan jujur. Perilaku situs tanpa restore tertunda persis seperti sebelumnya: `ownerIdOverride` hanya diteruskan bila `pendingRestoreOwnerId > 0`, selain itu `null` (tanpa penulisan owner tambahan, owner tetap dinolkan `putCredentialState` seperti rancangan Setup).
- Awal hari untuk fakta buka-kunci pertama & ringkasan pemakaian kini memakai kalender, bukan offset zona tetap (issue #361): `startOfTodayMs` sebelumnya menurunkan tengah malam setempat dari offset zona pada saat sekarang — pada hari transisi DST offset itu berbeda 1 jam dari offset tengah malam sebenarnya, sehingga pada hari spring-forward awal hari terhitung 1 jam terlalu awal (23:00 hari sebelumnya) dan pada hari fall-back 1 jam terlalu akhir (01:00), sementara baris laporan tetangga di digest yang sama sudah memakai helper kalender eksak — satu laporan bisa memakai dua awal hari berbeda. Kedua pemanggil (`firstUnlockTodayMs` dan `usageSummaryScan` di digest harian) kini memakai helper yang sama dengan jalur tetangga, `A11yUsageMath.dayStartWallMillis` (terkunci uji JVM), dan fungsi lama dihapus. Pada zona tanpa DST hasilnya identik milidetik dengan rumus lama, jadi perubahan perilaku hanya terjadi tepat pada hari transisi DST. Dengan perbaikan ini kelas "-24 jam tetap untuk hari kalender" tertutup: inventaris putaran-48 menyatakan semua situs lain sudah kalender eksak atau jendela bergulir yang memang benar.


## [1.6.551] - 2026-10-30

### Fixed
- Nasib PIN akses pada restore kini dinyatakan eksplisit di teks pembuka & pesan sukses (issue #360): PIN akses selalu tercakup cadangan (`SettingsBackup` mengekspor & mengimpornya), tetapi tidak pernah disebut dalam cakupan restore — teks pembuka `/pulihkan` dan pesan sukses hanya mendaftar token, Chat ID, owner ID, interval sync, dan interval foto — sehingga dua arah sama senyapnya: cadangan tanpa PIN yang dipulihkan ke perangkat ber-PIN membiarkan PIN lokal lama berlaku tanpa penjelasan (pemilik mengira konfigurasi sumber berlaku penuh), dan cadangan ber-PIN mengubah PIN perangkat mengikuti sumber tanpa satu kata pun. Teks pembuka kini mendaftar PIN akses beserta hasil akhirnya di kedua arah, dan pesan sukses kini menyertakan satu baris hasil PIN yang sebenarnya: "PIN akses diganti dari cadangan." bila cadangan membawa PIN, atau "PIN akses tidak ada di cadangan — PIN perangkat ini tetap dipakai." bila tidak. Perilaku penerapan PIN tidak berubah (PIN hanya diterapkan bila cadangan membawanya) — yang berubah hanya kejujuran cakupannya.
- Owner hasil restore kini ditulis dalam commit kredensial yang sama — klaim sukses tidak lagi bisa mendahului kenyataan (issue #359): penulisan ulang owner sesudah restore sebelumnya adalah commit terpisah (`setOwnerIdSync`) yang nilai kembali `Boolean`-nya diabaikan pemanggil (fungsi itu menelan pengecualiannya sendiri menjadi `false`, sehingga try/catch pemanggil tidak pernah menangkap apa pun), sementara pesan sukses menyatakan owner sudah diganti tanpa syarat — kegagalan commit owner atau proses yang mati di antara kedua commit menyisakan kredensial baru aktif dengan owner 0, dan dengan PIN aktif antrean pekerja tertahan senyap oleh gerbang `ownerLoginPending` sampai `/login`. Pilihan perbaikan yang diambil adalah opsi akar (a): `targetOwner` (dari `RestoreRules.ownerFallback` atas owner cadangan/chat baru/owner lama — ketiganya sudah terbaca sebelum simpan) kini dihitung sebelum `saveCredentials` dan ditulis di dalam commit kredensial yang sama lewat parameter opsional baru `ownerIdOverride` di `PreferencesManager.saveCredentials`. Bila commit itu gagal, jalur yang sudah ada melaporkan "Gagal menyimpan setelan baru. Setelan lama tetap dipakai." — tidak ada lagi klaim sukses yang bisa mendahului kenyataan, dan jendela dua-commit hilang. Perilaku pemanggil `saveCredentials` lain tidak berubah: parameter itu bawaan `null` (tanpa penulisan owner tambahan), sehingga alur Setup mempertahankan rancangannya — perubahan kredensial di Setup tetap menolkan owner lewat `putCredentialState` seperti sebelumnya.
- Catch umum perekaman audio kini melaporkan sebab kegagalan & mencatatnya ke log (issue #358): catch umum `recordAndSendAudio` sebelumnya menghapus berkas lalu mengirim persis "⚠️ Gagal merekam." — sebabnya tidak disertakan dan tidak dicatat di mana pun, sehingga kegagalan tersering (mikrofon sedang dipegang aplikasi lain/panggilan sehingga `prepare()`/`start()` melempar) terlihat seperti kerusakan misterius yang berulang tanpa jejak diagnosis. Pesan ke pemilik kini menyertakan sebab tersanitasi mengikuti pola jalur foto/video ("⚠️ Gagal merekam: " + `sanitizedCameraError`), dan sebab yang sama dicatat ke log dengan token teredaksi (`redactToken`). Catch `SecurityException` yang sudah spesifik tidak diubah.
- Perekaman audio kini sadar panggilan telepon di tengah rekam (issue #357): `recordAndSendAudio` sebelumnya hanya `delay` selama durasi penuh tanpa kesadaran panggilan apa pun — receiver telepon hanya meneruskan keadaan `IDLE` ke layanan dan penangan `ACTION_CALL_STATE` hanya memicu pemeriksaan umum — sehingga panggilan yang menimpa perekaman berjalan tidak pernah diketahui perekaman: pada sebagian perangkat hasilnya tetap terfinalisasi, terkirim, dan dilaporkan "Rekaman terkirim" seolah rekaman normal padahal isinya tertimpa jalur telepon. Masa rekam kini berjalan dalam irisan 500 mdtk dan memeriksa keadaan telepon perangkat (`TelephonyManager.callState`, hanya bila izin `READ_PHONE_STATE` ada; kegagalan baca dianggap tidak ada panggilan agar tidak memalsukan interupsi) di awal tiap irisan: bila `RINGING`/`OFFHOOK` terdeteksi, perekaman dihentikan saat itu juga, berkas sebagian dibuang (menyamai pola cabang stop-gagal yang membuang berkas tak final, dan menghindari pengiriman audio masa panggilan yang isinya tak terprediksi), dan pemilik menerima laporan jujur bahwa rekaman dihentikan karena panggilan masuk. Penanda `recordBusy`/`recorderActive`/`recordingKind` tetap dibersihkan oleh jalur `finally` yang sama seperti sebelumnya. Gerbang awal perintah (termasuk penolakan saat dering fitur /ring) dan jalur video tidak diubah.
- Kunci hari logis pemeriksaan jam malam/sekolah sebelum pukul 05:00 kini diturunkan lewat kalender, bukan mundur tetap 24 jam (issue #356): `checkCurfew` sebelumnya menghitung kunci penanda anti-duplikat (`curfewAlertDate`/`schoolAlertDate`) sebagai `todayKey(now - 24 jam)` untuk jam logis "masih kemarin" — pada jendela 00:00–01:00 sesudah hari spring-forward di zona DST, titik itu jatuh di dua hari lalu, sehingga penanda bisa menekan peringatan jam malam yang seharusnya terkirim, atau sebaliknya kunci berubah di tengah malam logis yang sama sesudah pukul 01:00 dan membiarkan satu peringatan duplikat lewat. Cabang sebelum pukul 05:00 kini memakai awal hari kemarin kalender lewat helper yang sama dengan perbaikan #354/#355 (`A11yUsageMath.previousDayStartWallMillis`, terkunci uji JVM termasuk jendela sesudah spring-forward); cabang pukul 05:00 ke atas tidak berubah. Pada zona tanpa DST hasilnya identik dengan rumus lama.
- Kunci "kemarin" untuk susulan digest malam kini diturunkan lewat kalender, bukan mundur tetap 24 jam (issue #355): `maybeSendNightDigest` sebelumnya menghitung kunci susulan sebagai `todayKey(now - 86_400_000L)` — pada zona waktu DST, pukul 00:00–01:00 sesudah hari spring-forward (hari 23 jam), titik itu jatuh di dua hari lalu, sehingga bila penanda `nightDigestDate` berisi kunci yang salah itu (keadaan normal sesudah digest dua hari lalu terkirim dan digest kemarin terlewat), `DigestSchedule.nightCatchUpDue` menilai susulan belum perlu dikirim dan digest malam yang terlewat tertunda sampai ±1 jam, sampai kunci terhitung benar sesudah pukul 01:00. Kunci kemarin kini berasal dari awal hari kemarin kalender lewat helper yang sama dengan perbaikan #354 (`A11yUsageMath.previousDayStartWallMillis`), terkunci uji JVM baru untuk jendela 00:00–01:00 sesudah spring-forward America/New_York (nilai harapan dibuktikan lewat perhitungan zona waktu independen: rumus lama memberi kunci dua-hari-lalu pada jendela itu). Pada zona tanpa DST hasilnya identik dengan rumus lama — kemarin kalender adalah hari yang sama dengan yang ditunjuk mundur-24-jam dari titik mana pun di hari ini — jadi perubahan perilaku hanya terjadi tepat pada jendela transisi DST itu. Situs `todayKey` lain dan jendela bergulir 24 jam yang memang berlabel bergulir tidak diubah.


## [1.6.550] - 2026-10-30

### Fixed
- Awal jendela "kemarin" pada sorotan ringkasan harian kini dihitung dengan kalender, bukan mundur tetap 24 jam (issue #354): `digestHighlights` sebelumnya membuka jendela kemarin di `dayStart - 24 * 3_600_000L` — pada hari transisi DST angka itu meleset satu jam (hari 23 jam: jendela mulai 1 jam sebelum kemarin benar-benar mulai sehingga ikut menghitung jam terakhir dua-hari-lalu; hari 25 jam: jendela mulai 1 jam sesudah awal kemarin sehingga jam pertamanya hilang), dan perbandingan "kemarin" di sorotan ikut miring. Awal kemarin kini berasal dari helper kalender baru `A11yUsageMath.previousDayStartWallMillis` (awal hari ini dikurangi satu hari kalender), terkunci uji JVM termasuk kedua transisi DST America/New_York (nilai harapan dibuktikan lewat perhitungan zona waktu independen). Pada zona tanpa DST hasilnya identik milidetik dengan rumus lama — awal hari kalender dikurangi satu hari kalender selalu tepat 24 jam dari awal hari ini — jadi perubahan perilaku hanya terjadi tepat pada hari transisi DST. Jendela lain tidak diubah.
- Agregasi UsageStats rentang hari-ini kini dihitung sekali per pembangunan ringkasan harian (issue #353): satu `sendDailyDigest` sebelumnya menjalankan empat agregasi `queryAndAggregateUsageStats` — jendela 24 jam bergulir, rentang hari-ini di `digestHighlights`, rentang kemarin, lalu rentang hari-ini yang sama persis dihitung ulang di `todayFacts` (lewat `recordDailyFacts`) — dan karena dihitung dua kali pada dua saat berbeda, sorotan dan fakta harian bahkan bisa memakai potret yang tak sama. Baris hari-ini kini dibawa `UsageDayData` sebagai nilai malas dari satu penyedia: dihitung pada akses pertama (profil "hanya bila dibutuhkan" dipertahankan — cabang aksesibilitas di `digestHighlights` tetap tidak memicunya) dan dipakai ulang oleh `digestHighlights`, `todayFacts`, dan `recordDailyFacts`, sehingga ketiganya membaca potret yang sama persis. Jendela 24 jam bergulir dan jendela kemarin tidak berubah pada perbaikan ini; jalur `/waspada` yang memanggil `todayFacts` di luar rantai digest tetap menghitung tepat satu kali seperti sebelumnya.
- Ringkasan 6 jam kini menghitung SMS & panggilan tanpa membaca isi barisnya (issue #352): `maybeSendDigest` sebelumnya membaca sampai 200 SMS berproyeksi penuh (termasuk `BODY`) dan 200 panggilan penuh hanya untuk mengambil `.size`-nya — nilai yang tampil pun jenuh di "200+". Kedua repository kini punya metode hitung murah (`SmsRepository.countSmsSince` / `CallLogRepository.countCallsSince`) yang meniru pola `countContacts`: kueri lewat `ContentQuery` yang sama (seleksi, urutan, dan batas 200 yang sama diterapkan di sumber) berproyeksi `_ID` saja, lalu membaca `cursor.count`. Semantik tampilan dipertahankan persis: hitungan dibatasi 200 di sumber sehingga nilai <200 tampil eksak, =200 dan >200 sama-sama tampil "200+" (dengan catatan "(dari 200 terakhir)" untuk SMS) seperti sebelumnya, dan jalur kegagalannya sejajar dengan jalur lama — `SecurityException` tetap lolos ke penangkap pemanggil yang menampilkan "?", sedangkan kegagalan umum menghasilkan hitungan 0 persis seperti daftar kosong pada jalur lama.
- Penghitung frekuensi nomor tidak lagi memindai seluruh hari untuk segelintir nomor, dan tidak lagi membaca isi SMS (issue #351): `freqForNumbers(null)` sebelumnya dipanggil dari tiga tempat — pemeriksaan SMS baru berkala, riwayat per-nomor, dan `/lastsms` — dan varian `null` menghitung frekuensi SEMUA nomor dari 200 SMS + 200 panggilan hari ini berproyeksi penuh (termasuk kolom `BODY`), padahal peta hasilnya hanya pernah dibaca untuk nomor yang sedang ditampilkan (`formatSmsMessage` hanya mencari kunci dari alamat daftar yang diformat, dan sudah punya pola himpunan-eksak di jalur cadangannya). Ketiga pemanggil kini melewatkan himpunan alamat/nomor yang benar-benar ditampilkan — halaman SMS baru pada jalur berkala, daftar panggilan+SMS pada riwayat nomor, dan 5 SMS pada `/lastsms` — menyamai pola jalur cadangan yang sudah ada; nilai hitungan untuk nomor yang ditampilkan identik karena penghitungan per nomor tidak bergantung himpunan kunci (pindai baris, seleksi, urutan, dan batas 200 yang sama; himpunan hanya menyaring kunci mana yang disimpan). Selain itu jalur frekuensi kini membaca SMS lewat proyeksi ringan tanpa `BODY` (metode baru `SmsRepository.getRecentSmsSinceLight`, memakai mode `light` yang sudah ada dan terpakai oleh pencarian riwayat nomor; fungsi ini hanya membaca alamat, jadi hasilnya tak berubah), sementara sisi panggilan dipertahankan (proyeksinya memang tak memuat kolom teks berat). Tak ada pemanggil varian `null` yang tersisa; varian itu dipertahankan sebagai kemampuan umum fungsi, bukan lagi jalur boros mana pun.
- Penanda jenis perekaman video kini hidup selama perekaman sungguhan, bukan seumur coroutine pemulainya (issue #350): `handleRecordVideoCommand` memasang `recordingKind = "video"` lalu meluncurkan coroutine yang memanggil `recordAndSendVideo` — fungsi non-suspend yang kembali seketika sesudah perekaman *dimulai* (penyelesaiannya berjalan lewat callback CameraService) — dan `finally` coroutine itu langsung menolkan penanda, padahal kamera masih merekam sampai 180 detik ke depan (jalur audio tidak kena: `recordAndSendAudio` suspend dan menjangkau perekaman+pengiriman, jadi penandanya memang hidup selama rekaman). Selama perekaman video nyata, gerbang ramah `/photo` (`recordingKind == "video"`) praktis mati — `/photo` lolos ke mesin upaya foto dan baru ditolak CAS CameraService sebagai laporan kegagalan "Gagal memotret: Kamera sedang dipakai…" alih-alih pemberitahuan ramah "Sedang merekam video", dan pesan sibuk `/record` menyebut jenis yang salah (membaca penanda yang sudah kosong). Siklus hidup penanda untuk video kini dimiliki titik penyelesaian yang sama dengan `recordBusy`/`recorderActive`: `finally` coroutine pemulai dihapus, dan penanda dinolkan di keenam penjaga awal `recordAndSendVideo` (perekaman tak pernah mulai), jalur antrean-video-penuh, reset watchdog perekaman, `finally` coroutine unggah pada callback `onVideoTaken`, callback `onError`, tangkapan sinkron `captureVideo`, tangkapan pelarian di coroutine pemulai (perekaman tak pernah terprakarsai), dan `teardownJobs` — cabang callback upaya basi sengaja tidak menolkan karena upaya yang lebih baru memiliki penandanya. Jalur audio tidak diubah.
- Petunjuk "layar statis/mati" pada kegagalan tangkap layar tidak lagi menempel pada kegagalan berikutnya yang sebabnya berbeda (issue #349): penanda kegagalan-tanpa-frame (`shotNoFrameFailAt`, ditambah #348) sebelumnya hanya pernah ditulis dan tidak pernah dinolkan, sehingga `screenshotFailureHint` memilih teksnya murni dari kebaruan penanda (≤120 detik) — kegagalan apa pun dalam jendela itu (bingkai tiba tetapi `planes` kosong, sesi gagal dibuat, penolakan `SecurityException`) ikut diberi teks "sistem tidak mengirim bingkai apa pun (layar mungkin statis atau mati)" yang mengarahkan diagnosis ke sebab yang salah. Penanda kini dinolkan pada tiga titik: saat tangkapan berhasil, saat kegagalan tercatat sebagai penolakan sistem (`SecurityException`), dan saat kegagalan hasil-null tidak mencap penanda baru selama panggilan itu (pemanggil membandingkan nilai penanda sebelum dan sesudah `takeScreenshotFrame` — satu-satunya penulis penanda adalah titik tanpa-frame di dalam fungsi itu, jadi nilai yang tak berubah membuktikan kegagalan terakhir bukan tanpa-frame). Petunjuk "layar statis/mati" kini hanya terpilih bila kegagalan terakhir yang dilaporkan memang kegagalan tanpa-frame.


## [1.6.549] - 2026-10-30

### Fixed
- Tangkap layar jalur proyeksi tidak lagi gagal palsu pada layar statis dengan sesi pakai-ulang (issue #348): `takeScreenshotFrame` sebelumnya menguras frame terakhir dari `ImageReader` dan membuangnya, lalu hanya menunggu 2 detik untuk frame BARU — padahal virtual display `AUTO_MIRROR` hanya menerima frame saat konten layar berubah, sehingga pada layar yang benar-benar diam frame yang dikuras itu adalah satu-satunya frame yang tersedia dan `/screenshot` kedua dan seterusnya gagal berulang selama layar tidak berubah, lengkap dengan petunjuk kegagalan yang menyalahkan izin yang sebenarnya masih berlaku. Frame hasil kurasan kini disimpan sebagai cadangan: frame baru tetap didahulukan (dan frame cadangan ditutup bila frame baru tiba), tetapi bila tak ada frame baru sampai batas jendela, frame cadangan itulah yang dipakai (ditutup lewat `finally` yang sama seperti frame biasa). Bila tetap tidak ada frame sama sekali, kegagalan itu kini dicatat sebagai kegagalan-tanpa-frame dan `screenshotFailureHint` menyatakannya dengan jujur — sesi tercatat masih aktif tetapi sistem tidak mengirim bingkai apa pun (layar mungkin statis/mati), sarannya membangunkan layar dulu — bukan lagi vonis izin yang kemungkinan sudah tak berlaku.
- Mengosongkan kolom PIN akses kini menghapus PIN juga lewat jalur Tes→Simpan di Setup (issue #347): jalur Simpan langsung selalu menulis nilai kolom hasil resolusi (`prefs.accessPin = accessPin`, kolom kosong = PIN terhapus — sesuai teks bantuan "Kosongkan untuk memakai kode pairing"), tetapi `persistTestSettings` di jalur Tes→Simpan memiliki penjaga `keepPin` yang diam-diam mempertahankan PIN lama setiap kali kolom kosong, sehingga hasilnya bergantung tombol yang ditekan: PIN yang dikira pemilik sudah dicabut tetap aktif, baris status tidak pernah menawarkan kode pairing (kode pairing dikosongkan selama PIN dianggap aktif), dan `/login` tetap menerima PIN itu. Penjaga itu tidak mungkin terpicu oleh kolom yang tak disentuh — kolom tak tersentuh berisi `STORED_MASK` yang ter-resolve ke PIN tersimpan (atau PIN pulihan tertunda) sebelum sampai ke `persistTestSettings`, jadi nilai kosong di sana hanya bisa berarti pengosongan yang disengaja. Penjaga `keepPin` kini dihapus (tak ada pembaca lain) dan jalur Tes→Simpan menulis PIN hasil resolusi tanpa syarat, persis jalur Simpan langsung; perilaku kolom tak tersentuh tidak berubah karena resolusinya tetap menghasilkan PIN tersimpan.
- Sisa pesan raksasa (>4000 aksara) yang sedang dipecah tidak lagi hilang dari jalur kirim bila flush batch terbatalkan (issue #346): item raksasa sebelumnya dikonsumsi dari daftar flush (`index++`) sebelum dipecah, dan penyelamatan `flushBatch` hanya menyelamatkan potongan gabungan yang sedang terakumulasi (`cur`) plus item yang belum tersentuh — sisa pecahan item yang sedang diproses (termasuk pecahan yang sedang terbang, yang sejak #345 memang tidak diantrekan sendiri oleh `forwardLocked` dalam konteks batch) tidak diantrekan, tidak kembali ke buffer, dan tidak tercatat sebagai drop; isinya hanya selamat di riwayat `/lastnotif`. `flushBatch` kini melacak sisa belum terkirim dari item yang sedang dipecah (`splitRest` + hash itemnya, diperbarui sebelum tiap pemanggilan kirim dan sesudah tiap potongan maju): pada pembatalan, sisa itu ikut diselamatkan ke antrean persisten di antara `cur` dan item sisanya, dan hash item dicap hanya sesudah antrean sungguh menerima (konsisten #343 — pecahan yang sudah terkirim sebelum pembatalan memang terkirim, sisanya terantre, jadi seluruh item berakhir terkirim-atau-terantre); pada galat non-batal, sisa yang sama dikembalikan ke kepala buffer bersama hash-nya untuk dipecah ulang oleh flush berikutnya.
- Potongan batch notifikasi tidak lagi terantre dua kali bila flush dibatalkan di tengah kirim (issue #345): tangkapan `CancellationException` di `forwardLocked` sebelumnya mengantrekan teks yang sedang dikirim lalu melempar ulang, dan penyelamatan pembatalan `flushBatch` mengantrekan potongan yang sama sekali lagi (penghitung potongan belum sempat dinolkan karena pengecualian merambat dari `flushCur`), sehingga sesudah layanan pulih `SendMessageWorker` mengirim batch gabungan yang sama dua kali. Pemilik penyelamatan kini tunggal: `forwardLocked` menerima parameter `queueOnCancel` (bawaan `true` — perilaku lama untuk pemanggilan langsung, yang pemanggilnya menghitung pembatalan sebagai terkirim persis karena antrean mandiri ini), sedangkan ketiga situs pemanggilan di dalam `flushBatch` (potongan gabungan dan kedua pemanggilan pecahan pesan raksasa) melewatkan `queueOnCancel = false` sehingga pada pembatalan hanya penyelamatan `flushBatch` yang mengantrekan — tepat satu kali. Perilaku cap dedup #343 tidak berubah: penyelamatan batch tetap mencap hash hanya sesudah antrean persisten sungguh menerima.


## [1.6.548] - 2026-10-30

### Fixed
- Cabang error unduhan restore kini menutup `errorBody` sebelum kembali (issue #344): pada respons HTTP gagal di `handleRestoreDocument`, `download.body()` bernilai null dan body error hanya terjangkau lewat `download.errorBody()` — cabang itu kembali tanpa menutupnya, sehingga koneksi yang mendasari tidak dikembalikan ke `ConnectionPool` bersama (maks 4 idle, dipakai semua lalu lintas Telegram lewat `SharedNet`) dan tertahan sampai direbut paksa OkHttp; sesi restore yang gagal berulang (maks 3 percobaan) bisa menahan sampai 3 slot dari satu sesi. Cabang error kini menutup `download.errorBody()` secara best-effort dalam try/catch sebelum `return`, menyamai kepatuhan cabang tetangganya (jalur `contentLength` menutup `body.close()`, jalur baca memakai `byteStream().use`). Tidak ada perubahan perilaku selain pelepasan koneksi.
- Cap dedup persisten notifikasi kini hanya ditulis pada titik pesan sungguh terkirim atau sungguh terantre — gerbang PIN-tanpa-owner tidak lagi meracuni dedup #335 (issue #343): cap identitas (`markForwardedId`) sebelumnya ditulis SEBELUM lapisan kirim di dua titik (ekor `emitPost` dan cabang cadangan prioritas saat pembatas laju menjatuhkan jalur biasa), padahal `forwardLocked` memiliki satu cabang penolakan senyap — PIN akses terpasang tetapi owner belum teradopsi (`ownerUserId == 0`) — yang membuang pesan TANPA mengantrekannya, satu-satunya cabang seperti itu (cabang gagal lain selalu mengantrekan). Cap persistennya telanjur tertulis, sehingga ketika listener mem-posting ulang notifikasi yang masih aktif sesudah owner teradopsi, posting ulang itu ditelan cek duplikat dan notifikasi hilang permanen tanpa pernah terkirim — kelas hilang-senyap yang justru dilindungi #335. `forwardLocked` kini mengembalikan hasil: `true` bila pesan terkirim atau diterima antrean persisten, `false` hanya untuk gerbang PIN-tanpa-owner dan untuk penolakan permanen 400 (yang sudah tercatat lewat pemberitahuan drop). Para pemanggil mencap hanya pada hasil `true`: jalur prioritas dari kembalian `forwardLocked` langsung (pembatalan coroutine dihitung terkirim karena `forwardLocked` mengantrekan pesannya sebelum melempar ulang), cabang cadangan prioritas hanya sesudah `addMessage` mengembalikan `true`, dan jalur batch kini membawa hash identitas bersama teksnya di dalam buffer — `flushBatch` mencap seluruh hash satu potongan gabungan hanya bila potongan itu terkirim/terantre, pesan raksasa yang dipecah hanya bila SEMUA pecahannya berhasil, jalur luapan buffer dan penyelamatan buffer (`salvageBatch`) mencap sesudah antrean persisten menerima, sisa flush yang terbatalkan dicap hanya bila antrean sungguh menerimanya, dan sisa non-batal dikembalikan ke buffer bersama hash-nya untuk dicap oleh flush berikutnya. Cap cabang cadangan yang kegagalan `addMessage`-nya ditelan utuh juga tertutup oleh pemindahan yang sama. Perilaku dedup jalur bahagia tidak berubah: posting ulang notifikasi yang masih aktif tetap tertelan, dan notifikasi yang diperbarui sumbernya (`postTime` baru) tetap diteruskan.


## [1.6.547] - 2026-10-30

### Fixed
- Label OTP SMS kini memakai sumber kebenaran OTP bersama `NotificationPrivacy` (issue #342): `smsTag` sebelumnya memakai salinan lokal yang lebih lemah (`OTP_KEYWORDS` 10 kata dengan substring mentah + `OTP_CODE_REGEX` `\b\d{4,8}\b`), sementara penopeng notifikasi memakai salinan yang lebih keras di `utils/NotificationPrivacy` (16 kata dengan batas kata + pola digit berkelompok) — konten yang sama dinilai berbeda menurut permukaan: SMS "Your PIN code is 123456" dari pengirim alfanumerik panjang tidak berlabel "🔑 Kemungkinan OTP" padahal konten identik sebagai notifikasi ditopeng sebagai OTP. Salinan lokal dihapus (nama tercatat di `tools/bekas-hapusan.txt`); label kini memakai `NotificationPrivacy.isOtp` untuk kata (daftar bersama yang lebih lengkap: kini termasuk "pin", "code", "verify", "kode akses", "one time") dan `NotificationPrivacy.hasCode` baru untuk pola digit bersama `DIGIT_GROUPS`, sehingga kode digit terpisah ("123 456", "123-456") kini ikut terdeteksi. Gerbang pengirim dipertahankan dan didokumentasikan di sini sebagai keputusan: label tetap mensyaratkan kode terdeteksi DAN (pengirim berdigit ≤6 — pengirim kode pendek khas OTP — ATAU kata OTP bersama hadir di badan), persis struktur gerbang sebelumnya. Konsekuensi batas kata yang jujur: kata OTP yang terkubur di dalam kata yang lebih panjang (mis. "kodenya") tidak lagi dihitung dengan sendirinya — menyamai semantik penopeng notifikasi; SMS OTP kanonik ("Kode OTP Anda 123456", "Your PIN code is 123456") berlabel persis seperti sebelumnya.
- Teks notifikasi kini digabung & di-lowercase satu kali per notifikasi di jalur penerusan (issue #339): untuk satu notifikasi, gabungan judul+badan yang sama sebelumnya dibangun dan di-lowercase hingga 3× (penilaian OTP saat ekstraksi, `lowText` prioritas, dan argumen pindai kata pantau yang di-lowercase ulang di dalam `AppWatch.hit`) dan dipindai kata pantau 2× dengan penggabungan ulang di tiap konsumen — semua di dispatcher serial `fwdSerial` sehingga biayanya mengantrekan notifikasi berikutnya. Gabungan mentah (`judul + "\n" + badan`) kini dihitung satu kali di titik masuk penilaian dan diteruskan sebagai parameter: `emitPost` menurunkan versi lowercase-nya satu kali untuk keputusan prioritas (pindai kata pantau memakai varian baru `AppWatch.hitLowered` yang menerima teks yang sudah di-lowercase — `AppWatch.hit` sendiri kini mendelegasikan ke varian itu sehingga vonisnya identik), dan gabungan teks termasking beserta lowercase-nya dihitung satu kali untuk kedua situs pembangun pesan. Semantik tiap konsumen dipertahankan persis: gabungan yang dinilai `NotificationPrivacy.isOtp` tetap gabungan mentah pra-masking yang sama (dihitung satu kali di titik ekstraksinya), gabungan berpisah spasi untuk `PRIORITY_CODE_REGEX` tetap dipertahankan sebagai gabungan tersendiri (pemisah `\n` akan mengubah kecocokan kode yang melintasi batas judul/badan), dan urutan keputusan tidak diubah (penilaian prioritas tetap mendahului keputusan drop pembatas laju, seperti sebelumnya).
- `AppWatch.hit` tidak lagi membayar alokasi penuh pada pindaian kata pantau yang tak menghasilkan hit (issue #338): `wordMatches` sebelumnya membangun `ArrayList` batang baru (9 elemen) plus substring untuk SETIAP pasangan kata × kata pantau, dan `hit` selalu memecah seluruh teks snapshot (±41 KB) menjadi daftar kata sebelum memeriksa apa pun — ribuan alokasi sesaat per pindai pada jalur pindai 3 detik yang hidup sepanjang hari, mayoritas untuk teks yang tak memuat kata pantau sama sekali. `wordMatches` kini bekerja murni dengan perbandingan sub-rentang (`regionMatches` atas konstanta awalan/akhiran statis yang sudah ada) tanpa satu pun alokasi per panggilan, dan `hit` memakai jalan pintas syarat-perlu yang murah: untuk kata pantau alfanumerik, kecocokan batas kata apa pun mensyaratkan kata pantau muncul sebagai substring teks (batang adalah sub-rentang kata, dan kata adalah sub-rentang teks — arah ini dibuktikan dari aturan `wordMatches` sendiri), jadi teks yang tak memuat substring kata pantau dilewati tanpa memecah kata sama sekali; pemecahan kata kini malas (hanya saat kata pantau pertama lolos gerbang substring) dan dipakai ulang antar kata pantau. Hasil pencocokan identik dengan semantik #330: dibuktikan lewat porting 1:1 logika lama vs baru atas seluruh korpus uji yang ada plus kasus tepi (imbuhan ganda "memperjudikan" tetap ✗, kata ulang bertanda hubung "bully-bully" tetap ✓, "keamanan" tetap ✓ untuk "aman") dengan 0 selisih, dan kasus tepi itu kini terkunci sebagai uji JVM.
- Sidik foto tertahan kini disimpan bersama nama berkasnya — scan penyembuhan tidak lagi men-decode ulang seluruh foto tertahan pada setiap foto baru (issue #337): entri tertahan sebelumnya hanya menyimpan sidik (Long) tanpa asosiasi berkas, sehingga setiap foto baru yang tiba selama ada foto tertahan membayar decode JPEG dua tahap atas SELURUH berkas tertahan hanya untuk memeriksa berkas mana yang masih ada. Format entri di kunci prefs yang sama kini diperluas menjadi `namaBerkas=sidik` (logika murni baru `utils/HeldPhotos`, teruji JVM dengan nilai harapan yang dibuktikan lewat porting 1:1 rancangan tertulis): sidik dihitung sekali di titik penahanan dan disimpan bersama nama berkasnya, titik flush memeriksa keanggotaan lewat nama berkas tanpa decode, dan pemangkasan cache membuang entri lewat nama berkas. Scan penyembuhan kini hanya memeriksa keberadaan berkas untuk entri bernama. Migrasi toleran: entri format lama (sidik saja) diperlakukan seperti sebelumnya pada satu siklus scan transisi — decode terakhir dijalankan khusus untuk entri warisan itu dan entri yang berkasnya masih hidup langsung ditulis ulang menjadi entri bernama, sehingga siklus berikutnya bersih dari decode; entri warisan yang gagal di-decode lengkap dipertahankan (arah konservatif #322 utuh: daftar berkas gagal dibaca = tidak ada pemangkasan sama sekali, token entri yang tak dikenal formatnya dipertahankan apa adanya dan tak pernah dipangkas). Nama metode lama yang tergantikan tercatat di `tools/bekas-hapusan.txt`.
- Penahanan rangkuman grup notifikasi tidak lagi bocor di tepi jendela, urutan, dan jalur darurat (issue #336): penahanan sebelumnya hanya bekerja bila anak grup ter-emit <120 detik sebelumnya di jalur normal — rangkuman yang diposting ulang >120 detik sesudah anak terakhir diteruskan sendirian mengulang isi yang sudah terkirim, rangkuman tidak pernah mencap kehadirannya sendiri, dan rangkuman yang melewati jalur squeeze antrean-penuh kehilangan status rangkumannya sama sekali. Pemeriksaan penahanan kini tinggal di kepala `emitPost` sehingga berlaku sama di semua jalur, jalur squeeze meneruskan `isSummary`/`groupKey` (dan penanda grup untuk anak), dan rangkuman yang lolos ikut mencap `groupSeen` untuk grupnya sehingga posting ulang rangkuman dalam jendela yang sama ikut tertahan. Untuk posting ulang yang lebih lambat, ada penanda persisten terpisah (`notif_group_forwarded`, maks 50 grup, hash kunci grup + cap jam dinding, ditulis sinkron hanya pada titik anak grup sungguh diteruskan): rangkuman tertahan bila anak grupnya pernah diteruskan dalam 30 menit terakhir, berapa pun urutan dan jalur kedatangannya. Keputusan rancangan & batasnya dicatat jujur: penanda ini membedakan "rangkuman yang anaknya sudah ter-emit" dari "rangkuman yatim" — rangkuman yang anaknya tidak pernah diteruskan (mis. penerusan sempat nonaktif) tidak pernah tertahan oleh penanda persisten, hanya oleh jendela 120 detik di memori. Penahanan dua arah penuh (menahan ANAK yang datang sesudah rangkuman ter-emit) sengaja TIDAK diambil: anak yang datang sesudah rangkuman umumnya isi baru yang tidak termuat dalam teks rangkuman, dan varian urutan rangkuman-dulu pada pengiriman ulang listener sudah tertutup dedup identitas persisten #335. Penanda memakai jam dinding (satu-satunya jam yang bertahan lintas kematian proses); perubahan jam perangkat yang ekstrem dapat memperpendek/memperlambat jendela 30 menit itu satu kali, tanpa pernah menelan notifikasi anak.
- Dedup notifikasi kini mengenali identitas notifikasi lintas kematian proses (issue #335): satu-satunya gerbang anti-ganda sebelumnya adalah hash isi + jendela 10 detik yang hidup hanya di memori proses, sehingga proses yang mati (LMK di HP RAM kecil) lalu listener tersambung ulang membuat sistem mengirim ulang semua notifikasi yang masih aktif di shade dan tiap notifikasi lama diteruskan lagi ke Telegram sebagai pesan baru. Identitas notifikasi (`sbn.key` + `postTime`) kini diteruskan dari `onNotificationPosted` ke jalur emit dan hash-nya (SHA-256 atas kunci + `postTime`) disimpan dalam himpunan persisten terbatas (maks 100 entri, eviksi tertua, `forwarded_notif_ids` di `PreferencesManager`, ditulis sinkron mengikuti pola penanda persisten NFS yang ada dan di-seed sekali per proses di `adoptPrefs`). Keputusan rancangan: identitas hanya dicap pada titik notifikasi sungguh diteruskan (kirim langsung, prioritas terkunci, atau antre cadangan saat pembatas laju menjatuhkan jalur biasa) — notifikasi yang gugur oleh pembatas laju tidak dicap, jadi pengiriman ulangnya di kesempatan lain tetap bisa diteruskan; notifikasi yang sungguh diperbarui aplikasi sumber membawa `postTime` baru sehingga tetap diteruskan sebagai pembaruan. Jendela dedup isi 10 detik di memori tetap dipertahankan untuk posting ulang cepat dalam proses yang sama, dan jalur tanpa identitas (kunci kosong) berperilaku persis seperti sebelumnya.
- Layanan penerus notifikasi kini memakai parser & pencocokan kata pantau bersama `AppWatch` (issue #334): NFS sebelumnya menjalankan parser sendiri (`watchKeywordList` — pemisah hanya `,`/spasi/`;`, tanpa batas 10 kata, tanpa batas panjang 64 huruf, tanpa `distinct`) dan pencocokan substring mentah, menyimpang dua arah dari permukaan lain: notifikasi jinak yang memuat kata pantau sebagai potongan kata lain ("otomatis" vs "mati") berlabel "⚠️ Watch:" DAN terpromosi prioritas (lolos penundaan hemat-data, dievakuasi paling akhir), sementara kata >64 huruf yang dibuang semua permukaan lain tetap dihormati NFS dan kata dengan pemisah tab/baris-baru dari raw pulih-cadangan terparse berbeda hingga hit terlewat. Parser lokal itu dihapus (nama tercatat di `tools/bekas-hapusan.txt`); label dan keputusan prioritas kini memanggil `AppWatch.parseKeywords` + `AppWatch.hit` — satu semantik kata pantau di semua permukaan, termasuk batas 10 kata/64 huruf/`distinct`/pemisah `\s+` yang kini berlaku juga di NFS. Cache parse yang dikunci string mentah dipertahankan di sekeliling pemanggilan `AppWatch`, sehingga daftar yang tak berubah tidak di-parse ulang.
- Penghitung kata pantau di laporan kini memakai pencocokan yang sama dengan label SMS live (issue #333): penghitung "Kena pantau" di ringkasan harian, "Watch hit 24 jam" di `/waspada`, dan penghitung laporan 7 hari sebelumnya masih memakai substring mentah (`lower.contains`) atas daftar `watchList()` yang sama, padahal label SMS live sudah memakai `AppWatch.hit` yang sadar batas kata sejak #330 — kelas positif-palsu #330 dengan demikian tetap hidup di permukaan laporan: SMS berisi "otomatis"/"matematika"/"matikan" dengan kata pantau "mati" terhitung kena pantau di laporan tetapi tidak berlabel "⚠️ Watch:" saat diteruskan, dan di `/waspada` tiap hit bernilai 25 poin sehingga satu-dua SMS jinak sudah menggelembungkan skor & vonis. Ketiga penghitung kini memanggil `AppWatch.hit` atas daftar yang sama, sehingga angka laporan dan label live konsisten: untuk kata pantau alfanumerik himpunan substring adalah superset ketat dari himpunan batas kata, jadi hitungan hanya bisa turun ke nilai yang benar (tidak pernah naik), dan kata pantau non-alfanumerik mempertahankan fallback substring di dalam `AppWatch.hit` sendiri. Skor & vonis `/waspada` mengikuti hitungan baru secara alami.

### Removed
- Variabel lokal mati `privateOwner` di jalur gerbang perintah dihapus (issue #341): nilai itu dihitung lalu tidak pernah dibaca (sensus token seluruh `app/src`: 0 rujukan selain baris definisinya), sementara predikat yang nyaris sama dihitung ulang sebagai `isPrivate` beberapa baris di bawahnya dan `isPrivate` itulah yang benar-benar dipakai jalur gerbang. Kode mati di jalur sensitif ini mengaburkan pembacaan — tampak seolah ada dua konsep privat yang berbeda padahal satu tak terpakai. Tidak ada perubahan perilaku; perhitungan `isPrivate` tetap.
- API publik mati `PreferencesManager.setPendingNotifDropsAndBatchSync` dihapus (issue #340): metode ini sisa era #315/#321 yang sudah tergantikan `setDropNoticeStateSync` sebagai penulis atomik keadaan drop-notice yang benar-benar dipakai; sensus seluruh repo menunjukkan tepat satu kemunculan (definisinya sendiri, tanpa pemanggil di kode produksi maupun uji). Penghapusan mencegah perbaikan masa depan memilih penulis yang salah dari dua nama yang mirip dan mengira jalurnya teruji. Namanya tercatat di `tools/bekas-hapusan.txt` mengikuti pola repo. Tidak ada perubahan perilaku.


## [1.6.546] - 2026-10-30

### Fixed
- Alert kata pantau kini memuat potongan konteks di sekitar kata yang cocok (issue #332): alert sebelumnya hanya menyebut label aplikasi, kata yang cocok, dan waktu — padahal yang dipindai adalah seluruh teks yang TAMPIL di aplikasi terpantau, bukan hanya yang diketik anak — sehingga owner tidak punya cara membedakan apakah kata itu diketik anak, dikirim lawan bicara, atau sekadar muncul di artikel/iklan yang sedang dibuka (risiko salah atribusi, diperbesar oleh pencocokan substring lama #330). Alert kini menyertakan baris "Konteks: " berisi potongan terbatas dari teks node asal kata itu — dibangun `AppWatch.contextSnippet` (murni, teruji JVM): kata permukaan yang sungguh cocok (termasuk bentuk berimbuhannya, mis. "BERJUDI" untuk kata pantau "judi", dengan huruf asli dipertahankan) dicari pada teks node asalnya, lalu dipotong ±40 aksara di sekitarnya, spasi/baris baru diruntuhkan menjadi satu baris, tepi yang terpotong ditandai "…", dan hasilnya di-escape HTML sama seperti label/kata saat ini sebelum dikirim. Batasan privasi tidak mundur: snapshot yang mengandung field kata sandi tetap dibuang seluruhnya di hulu sehingga tidak pernah ada potongan darinya, paket sensitif tetap dikecualikan, dan potongan hanya berasal dari teks yang memang memicu kata pantau di aplikasi yang owner daftarkan sendiri. Bila potongan tak ditemukan (pertahanan berlapis; jalur normal selalu menemukannya karena pencocokan yang sama), alert terkirim dalam bentuk lama tanpa baris konteks. Nilai harapan potongan dibuktikan lewat porting 1:1 (teks pendek utuh, teks panjang terpotong tepat 89 aksara bertanda "…", baris baru diruntuhkan, tanpa-cocok menghasilkan null) dan terkunci uji JVM `potonganKonteksDiSekitarKataCocok`.
- Node teks panjang kini dipindai sebagian, tidak lagi dilewati utuh dari pindaian kata pantau (issue #331): `collectSnapshot` di `A11yMonitor` sebelumnya hanya mengumpulkan teks node (dan `contentDescription`) yang panjangnya ≤ 160 aksara — teks yang lebih panjang dibuang SELURUHNYA dari himpunan pindaian, bukan dipotong, sehingga gelembung pesan tunggal yang panjang (umum di aplikasi chat, yang justru paling lazim dipantau) yang memuat kata pantau di aksara ke-200 tidak pernah mencapai pencocokan: tidak ada alert dan tidak ada jejak apa pun bahwa pindaian melewatkannya. Teks node dan deskripsi kini dipotong ke 512 aksara pertama (`SCAN_TEXT_MAX_CHARS`) lalu tetap dikumpulkan — 512 dipilih sebagai kenaikan terbatas dan terukur dari batas lama: batas jumlah BFS tidak berubah sama sekali (maksimal 160 node / 80 teks per snapshot, jeda pindai 3 detik per paket tetap), sehingga biaya snapshot terburuk naik sebatas ±3x panjang teks yang sudah dibatasi jumlahnya, dan perilaku untuk teks ≤ 512 aksara tidak berubah. Keterbatasan dicatat jujur: kata pantau yang hanya muncul SESUDAH aksara ke-512 pada satu node teks tetap tidak terdeteksi (sama seperti keterbatasan cakupan lain fitur ini); kata di awal sampai tengah pesan panjang kini terdeteksi. Snapshot yang sama dipakai jalur proteksi tamper (`TamperGuard`), yang kini juga melihat 512 aksara pertama teks panjang — arahnya tetap protektif dan pemicunya yang sudah ada (label tombol pendek) tidak berubah.
- Pencocokan kata pantau kini sadar batas kata dengan toleransi imbuhan — kata umum yang sekadar memuat kata pantau tak lagi memicu alert palsu (issue #330): `AppWatch.hit` sebelumnya substring mentah (`lower.contains`), sehingga kata pantau "mati" cocok di dalam "otomatis", "matikan", dan "matematika", dan "judi" cocok di dalam nama "Judika" — layar aplikasi terpantau yang menampilkan tombol "Matikan" atau teks "otomatis" mengirim alert "Kata pantau" untuk kejadian yang sama sekali tidak diawasi, dan alert palsu berulang menggerus kepercayaan owner pada fitur ini. Pencocokan kini per kata: kata teks harus sama persis dengan kata pantau, atau kata pantau sebagai batang dengan SATU awalan lazim (`memper-`, `ber-`, `ter-`, `per-`, `me-`, `di-`, `pe-`, `ke-`) dan/atau SATU akhiran lazim (`-nya`, `-an`, `-i`, `-s`, `-ing`, `-ed`) menempel, atau batang diikuti pemanjangan huruf terakhir kata pantau ("judiii") — sehingga "berjudi", "judinya", "perjudian", "kematian", dan "dibully" tetap cocok, sedangkan kemunculan di tengah kata tanpa batas imbuhan yang sah ("otomatis", "matematika", "Judika") tidak. Keputusan yang disengaja dan terkunci uji: akhiran `-kan` TIDAK ditoleransi, karena contoh issue persisnya adalah teks UI "Matikan" — konsekuensinya keluarga kausatif yang sama ("mematikan", "dimatikan") juga tidak lagi cocok dengan "mati"; kata dalam bentuk dasar/berimbuhan lain ("mati", "kematian") tetap terdeteksi. Kata khas "judol" tidak berubah perilakunya sebagai kata pantau tersendiri, dan kata pantau "judi" kini tidak ikut menyala pada "judol" (kata yang berbeda — contoh bawaan aplikasi memang memasang "judol" sebagai kata pantau sendiri). Kata pantau yang memuat aksara selain huruf/digit (mis. ejaan leet atau bertanda hubung) mempertahankan pencocokan substring lama persis, karena morfologi batas kata tidak berlaku padanya. Nilai harapan tiap kasus dibuktikan lewat porting 1:1 aturan (27 kasus, 0 beda) dan terkunci uji JVM baru di `AppWatchTest`. Konsumen label SMS `watchHit` di `MonitoringService` memakai fungsi yang sama sehingga ikut semantik baru secara otomatis; jalur alert aksesibilitas juga. (Penghitung "Kena pantau" pada laporan mingguan adalah permukaan terpisah yang tidak diubah di sini.)
- Veto rujukan foto lama tidak lagi menelan permintaan foto BARU yang memuat kata kerja pengambilan tegas (issue #329, sisa #324 arah positif-palsu): veto `isPhotoReference` dari #324 menyala oleh satu token `fotonya`/`kemarin` di mana pun dalam kalimat tanpa memeriksa kata kerja, sehingga permintaan jepretan baru yang tulus — "jepret fotonya sekarang", "ambil fotonya sekarang", sampai "foto kemarin jelek, ambil foto baru sekarang" ("kemarin" hanya latar) — ikut ditolak menjadi bukan perintah: tidak ada foto diambil dan tidak ada penjelasan ke owner. Veto kini gugur bila kalimat memuat kata kerja pengambilan tegas (`jepret`/`potret`/`ambil` beserta varian imbuhan yang dikenali `tokenMatches`, mis. `ambilkan`) atau frasa "foto baru" — kata kerja semacam itu hanya bisa berarti mengambil foto baru, sehingga kalimatnya bukan rujukan. Keputusan yang disengaja dan terkunci uji: token `kirim` TIDAK mengesampingkan veto, karena "kirim" sama ambigu dengan mengirim ulang foto yang sudah ada ("kirim fotonya sekarang dong" tetap tertolak — arah gagal-amannya dipertahankan; jalur tegasnya adalah kata kerja pengambilan atau perintah `/photo`). Seluruh frasa uji lama mempertahankan vonisnya dan frasa sasaran #324 tanpa kata kerja tegas tetap tertolak — dibuktikan lewat porting 1:1 logika parser (0 beda pada korpus lama; tepat lima frasa issue yang berbalik menjadi `/photo`) dan terkunci uji JVM baru `parsePhotoReferenceVetoYieldsToCaptureVerbs` plus tiga frasa sasaran tambahan di `parseRejectsReferencesToExistingPhoto`.

### Security
- Link pairing tidak lagi menjadi kredensial bearer <redacted> yang dianjurkan untuk dibagikan (issue #328): link `t.me/<bot>?start=<chatId>_<kode 6 digit>` berfungsi sebagai kredensial adopsi owner — akun Telegram mana pun yang membukanya dan menekan Start lebih dulu diadopsi sebagai owner penuh — tetapi teks dialog di Setup justru berbunyi "Bisa juga dibagikan." tanpa peringatan apa pun, kode berlaku 24 jam dan tidak digugurkan sesudah adopsi berhasil (jalur adopsi `/start` memakai `setOwnerIdSync` yang tidak menyentuh kode), dan handler `/start` itu sendiri memanggil `ensureOwnerPairCode()` yang MENCETAK kode baru bila belum ada — sehingga kode yang tersebar tetap menjadi jalur adopsi tanpa PIN kapan pun owner kosong (mis. sesudah `/logout` pemegang link lama bisa mengadopsi ulang dalam sisa jendela 24 jam, padahal login normal mensyaratkan PIN). Tiga perbaikan dalam satu akar masalah: (a) teks dialog `setup_pair_link_body` di kedua locale kini menyatakan link bersifat rahasia — siapa pun yang membukanya lebih dulu menjadi pemilik bot — dan melarang berbagi ke siapa pun (anjuran berbagi dihapus; satu-satunya `%1$s` dipertahankan); (b) kode pasangan digugurkan segera sesudah adopsi berhasil — jalur sukses `rememberOwner` di cabang pairing memanggil `clearOwnerPairCodeSync()` baru di `PreferencesManager` yang menolkan kode dan cap waktunya dalam satu commit, sehingga link lama mati pada saat adopsi terjadi; (c) jalur `/start` tidak lagi mencetak kode: ia membaca kode yang sudah ada lewat `currentOwnerPairCode()`, sehingga hanya kode yang dibuat dari tindakan Setup (`copyPairingLink`) yang pernah berlaku — kode kosong/kedaluwarsa berarti pairing ditolak. Alur sah tidak berubah: pengguna membuat link di Setup, membukanya dari akunnya sendiri, adopsi berhasil dan kode gugur; untuk menautkan ulang sesudah `/logout` pengguna membuat link baru dari Setup. Yang memang tidak pernah bocor tetap tercatat: token bot dan PIN akses tidak ada di dalam link, dan pembuatan link tetap ditolak selama PIN akses aktif.


## [1.6.545] - 2026-10-30

### Fixed
- Fix segar jalur jam malam kini dievaluasi pagarnya saat itu juga (issue #327): `checkCurfew` adalah satu-satunya dari enam situs `rememberSentLatLon` yang tidak berpasangan evaluasi pagar — fix segar yang diambil fetch jam malam diingat sebagai posisi terakhir, tetapi `checkGeofence` tidak dipanggil, sehingga pelintasan batas yang pertama kali terlihat oleh jalur ini baru terdeteksi saat fallback pagar mengevaluasi posisi cache (gerbang 15 menit; cache diterima sampai umur 30 menit) atau patroli berikutnya: alert transisinya tertunda selama itu dan menit tiba/pergi pertama hari itu (yang dicap `recordFenceFact` pada waktu deteksi, bukan waktu fix) bergeser sampai ±15 menit dari waktu pelintasan sebenarnya. Satu baris ditambahkan menyamai kelima situs saudara persis: `checkGeofence(fix.latitude, fix.longitude, fix.accuracy)` dipanggil tepat sesudah `rememberSentLatLon` pada cabang fix segar `checkCurfew` (pola argumen dan bungkus try/catch yang sama), sehingga pelintasan terdeteksi dan tercap pada waktu fix-nya. Jalur cache (fix ≤30 menit yang sudah pernah dievaluasi saat segar) tidak berubah.
- Alert transisi pagar kini punya cooldown 20 menit per pagar — ayunan GPS di tepi radius tidak lagi menjadi spam Telegram (issue #326): transisi pagar sebelumnya dihasilkan satu fix yang melintasi ambang dan setiap balikan keadaan langsung menjadi pesan Telegram tersendiri, tanpa penanda waktu/cooldown apa pun (bandingkan `checkVehicle` yang sudah punya cooldown 20 menit) — perangkat yang diam di dekat tepi lingkaran pagar, dengan jarak sebenarnya dalam ±ayunan GPS dari radius, membalik keadaan pada tiap cek (patroli, fallback 15 menit, perintah lokasi, `checkVehicle`) dan mengirim "masuk area"/"keluar area" bergantian sepanjang malam. Kini ada penanda alert persisten per pagar (`homeAlertAt`/`schoolAlertAt` di `PreferencesManager`, mengikuti pola `lastVehicleAlertAt`; dinolkan oleh penulis gabungan konfigurasi pagar bersama state, sehingga pagar yang dipasang ulang mulai dengan jam alert bersih), dan keputusan kirim diambil di dalam blok `fenceLock` yang sama dengan penulisan state: pesan alert ditahan bila alert sebelumnya untuk pagar yang sama masih dalam jendela `FENCE_ALERT_COOLDOWN_MS` (20 menit, sama seperti kendaraan), sedangkan transisi keadaan dan pencatatan fakta harian (`recordFenceFact`, transisi pertama per arah) berjalan normal tanpa ikut tertahan — flap tetap tidak merusak fakta, dan alert pertama sesudah jeda panjang tidak pernah tertahan (penanda 0 selalu lolos, pola gerbang yang sama dengan kendaraan). Mekanisme jemput satu-kali-pakai tidak disentuh.
- Adopsi keadaan awal pagar kini digerbang akurasi yang sama seperti transisi normal (issue #325): `GeofenceLogic.transition` sebelumnya mengadopsi keadaan awal (cabang state belum dikenal, yang terjadi tiap konfigurasi pagar dipasang/diubah karena `setHomeFence`/`setSchoolFence` menolkan state) TANPA syarat akurasi, sedangkan transisi normal mensyaratkan akurasi ≤ radius — satu fix jaringan kasar pertama sesudah konfigurasi (akurasi ribuan meter; `bestFix` memilih akurasi terkecil tanpa plafon mutlak) yang menempatkan perangkat di sisi batas yang salah meracuni keadaan secara senyap, dan fix akurat berikutnya menghasilkan alert transisi palsu ("keluar area" padahal tidak pernah masuk, atau sebaliknya) yang untuk pagar Rumah juga tercatat oleh `recordFenceFact` sebagai fakta tiba/pergi pertama hari itu. Adopsi kini hanya terjadi bila akurasi fix memenuhi syarat yang sama persis dengan cabang transisi (akurasi tak diketahui negatif selalu lolos; akurasi di atas radius menunda adopsi — state tetap tak dikenal sampai ada fix yang layak, tanpa alert). Uji `GeofenceLogicTest` yang mengunci perilaku lama diperbarui di commit yang sama sesuai aturan repo (uji adopsi berganti nama dan nilai harapannya dibuktikan dari logika baru: akurasi 10.000 m pada radius 200 dan akurasi 51 m pada radius 50 kini menunda adopsi, akurasi tepat di radius tetap mengadopsi).
- Kalimat yang merujuk foto yang SUDAH ada tidak lagi memicu jepretan foto BARU (issue #324): maksud `/photo` di `IntentParser` sebelumnya mencapai skor penuh hanya dari kombinasi "kata benda foto + satu token sopan/waktu generik" — `fotonya` cocok dengan kata kunci `foto` lewat aturan imbuhan dan `diambil` cocok dengan `ambil` — sehingga kalimat yang membicarakan foto lama ditafsirkan sebagai perintah mengambil foto baru saat itu juga: "mana fotonya anak sekarang", "foto yang diambil kemarin dong", dan "fotonya kemarin bagus, kirim ulang dong" (owner meminta kirim ulang foto kemarin; yang terjadi jepretan baru, kamera HP anak menyala pada momen yang tidak diminta). Maksud `/photo` kini punya veto rujukan-lampau yang hanya berlaku untuk maksud itu sendiri: kalimat yang memuat penanda rujukan ke foto yang sudah ada — token `kemarin`, token `fotonya` (bentuk definit "fotonya"), bentuk pasif `diambil`, atau frasa "kirim ulang" — tidak lagi dinilai untuk `/photo`, apa pun token generik pendampingnya. Veto ini sengaja sempit: ia tidak mensyaratkan kata kerja pengambilan (permintaan tulus tanpa kata kerja seperti "foto sekarang dong" dan "tolong foto" tetap `/photo` persis seperti sebelumnya), tidak menyentuh maksud lain yang sah memakai kata yang sama (`/layar` dan `/jejak` memang memakai "kemarin" untuk riwayat), dan seluruh frasa uji lama mempertahankan vonisnya — dibuktikan lewat porting 1:1 logika parser (0 beda pada semua frasa lama) dan terkunci uji JVM baru `parseRejectsReferencesToExistingPhoto`.
- Varian negasi gaul tidak lagi lolos dari penjaga negasi parser perintah bahasa bebas (issue #323): janji desain fitur ini adalah "kalimat bernegasi selalu ditolak" (issue #80 memperluas penjaga ke bentuk berimbuhan persis karena kebocoran semacam ini menjalankan perintah melawan kata owner sendiri), tetapi himpunan `NEGATIONS` di `IntentParser` hanya memuat ejaan baku — varian yang paling umum dipakai justru tidak tercakup: `ga` (bentuk pendek paling umum dari "tidak"), `ngga`/`engga` (lebih pendek dari `nggak`/`enggak` sehingga aturan awalan tidak bisa menolong), `gausah`, `kagak`, dan singkatan `jgn`. Kalimat larangan dengan ejaan itu dieksekusi sebagai perintah positif ("ga usah kirim foto" mengambil dan mengirim foto; "engga usah kirim screenshot" mengambil screenshot). Keenam varian kini menjadi anggota himpunan sebagai token PERSIS — "ga" sengaja TIDAK menjadi aturan awalan (kata seperti "gaji"/"gadis" harus tetap aman, dan terbukti aman: aturan awalan hanya berlaku untuk kata negasi ≥4 huruf yang menjadi awalan token). Terkunci uji JVM baru `parseRejectsColloquialNegations` (9 frasa pemicu issue semua ditolak) plus kasus penjaga positif-palsu di daftar positif ("gadis kecil itu kirim foto dong" tetap terparse `/photo`); non-regresi seluruh frasa uji lama dibuktikan lewat porting 1:1 logika parser (0 beda sebelum dan sesudah perubahan pada semua frasa lama).
- Scan penyembuhan-diri hash foto tertahan tidak lagi membuang entri berkas yang masih hidup hanya karena analisis sidiknya gagal sesaat (issue #322, sisa #318): pindaian di `handleCapturedPhoto` yang memangkas himpunan `heldPhotoHashes` menjadi entri yang berkasnya terbukti ada sebelumnya menelan kegagalan `PhotoFingerprint.analyze` per berkas (`try/catch` kosong) — berkas tertahan yang gagal dianalisis sesaat (galat baca IO sesaat; jalur gagal biasa `analyze` adalah nilai balik khusus, bukan lemparan) tidak menyumbang sidik ke himpunan "hidup", sehingga entrinya diperlakukan sebagai berkas mati dan dibuang permanen dari himpunan, padahal berkasnya masih ada: berkas itu kehilangan pelacakan dua arah (tidak lagi ikut pembanding dedup selama tertahan, dan saat akhirnya terkirim lewat flush tidak dipromosikan menjadi baseline karena gerbang promosi membaca himpunan yang sudah terbuang), sehingga foto mirip berikutnya bisa terkirim sebagai duplikat. Kegagalan analisis per berkas kini menandai pindaian sebagai tidak lengkap dan penyembuhan dilewati seluruhnya (himpunan utuh), menyamai arah konservatif jalur `listFiles` gagal total yang sudah ada; berkas yang sungguh hilang tetap membersihkan entrinya pada pindaian lengkap berikutnya, persis seperti sebelumnya.
- Rekonsiliasi batch drop notifikasi kini berjalan sedini mungkin, tidak lagi kalah balapan dari worker pengirim (issue #321, sisa #315): mekanisme identitas batch dari #315 menutup celah mati-keras hanya bila notice batch masih berada di antrean saat rekonsiliasi berjalan, tetapi rekonsiliasi sebelumnya hanya dipanggil dari kepala `flushDropNotice` — dan pemicu flush pertama proses baru selalu tertunda 30 detik — sedangkan layanan utama menjadwalkan `SendMessageWorker` tanpa jeda awal pada tiap start, sehingga worker bisa mengirim dan menghapus notice batch lebih dulu; rekonsiliasi lalu tidak menemukannya, total `pendingNotifDrops` tidak dikoreksi, dan flush yang sama melaporkan jumlah drop yang sama sekali lagi. Rekonsiliasi kini dijalankan secara sinkron pada adopsi prefs pertama tiap proses (`adoptPrefs`, jalur yang sama dengan penyemaian penghitung di `onCreate`, sebelum penjadwalan apa pun) bila penanda batch tertunda ada, dan flush awal proses baru dalam keadaan itu berjalan tanpa jeda 30 detik (sisa yang belum terlaporkan dilaporkan segera, bukan menunggu). Urutan bahagia tidak berubah. Batas yang diterima dan dicatat jujur: penutupan ini tetap bersyarat proses NFS sempat tercipta sebelum worker mengosongkan antrean — bila sistem belum membuat instance layanan listener sama sekali saat worker menang, rekonsiliasi tertunda ke pembuatan layanan berikutnya dan duplikasi pada jendela sempit itu masih mungkin; arah gagalnya tetap melapor (bukan hilang), searah semantik at-least-once repo.


## [1.6.544] - 2026-10-30

### Fixed
- Query riwayat SMS/panggilan non-strict tidak lagi mengembalikan hasil parsial senyap sebagai lengkap bila iterasi kursor gagal di tengah (issue #320): di `querySms` (`SmsRepository`) dan `queryCalls` (`CallLogRepository`), `moveToNext()` sebelumnya berada pada kondisi loop di luar try per-baris — kesalahan per-baris memang ditangkap dan dilewati dengan benar, tetapi kegagalan saat MEMAJUKAN kursor (mis. kegagalan CursorWindow pada basis data provider yang korup sebagian) jatuh ke catch luar yang pada mode non-strict menelannya dan mengembalikan daftar parsial seolah jendela lengkap, sehingga konsumen (ringkasan 6 jam, hitungan digest harian/mingguan, penghitung begadang, frekuensi) mencetak angka pasti untuk hitungan yang diam-diam tidak lengkap, melewati kanal kegagalan jujur yang sudah ada ("?" pada ringkasan, daftar kosong pada digest). Kegagalan memajukan kursor kini dicatat selama iterasi dan DILEMPAR ulang sesudah loop selesai (kursor tetap ditutup oleh `use`), menyamai semantik jalur strict — kesalahan per-baris tetap ditangkap dan dilewati persis seperti sebelumnya, dan perilaku query sehat tidak berubah satu baris pun. Seluruh konsumen terverifikasi menangani lemparan itu lewat kanal jujurnya yang sudah ada (ringkasan 6 jam: -1 → "?"; digest & penghitung begadang & `freqForNumbers`: try per-sumber → kosong; `/history`: try per-pencarian → kosong); dua handler yang sebelumnya memanggil tanpa penjaga — `/lastcalls` dan `/lastsms` — kini menangkapnya dan membalas "⚠️ Gagal membaca …. Coba lagi." alih-alih diam.
- Lencana "🔁 N x hari ini" tidak lagi menjumlahkan semua pengirim tanpa digit menjadi satu angka (issue #319): `freqForNumbers` di `MonitoringService` membangun himpunan saringnya dengan syarat kunci ≥ 7 digit, dan dua penghitung frekuensi saudara di berkas yang sama (digest harian dan mingguan) juga mensyaratkan hal yang sama — tetapi loop hitung `freqForNumbers` sendiri tidak, sehingga pada jalur utama berkunci-null semua alamat tanpa digit (ID pengirim alfanumerik yang sangat umum di Indonesia seperti "BCA", "OVO", "DANA", "TELKOMSEL", "GoPay", serta fallback "Unknown") runtuh ke kunci kosong yang sama dan terjumlah lintas pengirim: total gabungan ≥ 3 membuat SETIAP SMS dari pengirim mana pun dalam kelompok itu tampil berlencana "🔁 N x hari ini" dengan N gabungan, terbaca sebagai frekuensi satu pengirim. Kedua loop hitung (SMS dan panggilan) kini melewati baris berkunci < 7 digit, persis syarat saudara-saudaranya; jalur berkunci-saring tidak berubah (kuncinya memang sudah ≥ 7), dan nomor sungguhan ≥ 7 digit terhitung persis seperti sebelumnya.
- Semua foto tertahan hemat-kuota kini terlacak dedup-nya, bukan hanya yang terakhir (issue #318, sisa sekeluarga #311 arah sebaliknya): perbaikan #311 memisahkan hash tertahan dari baseline terkirim, tetapi hash tertahan itu hanya SATU slot — selama satu masa jaringan meteran beberapa foto otomatis berbeda adegan bisa tertahan bersama di cache (batas 10 berkas), dan foto tertahan kedua menimpa slot foto pertama, sehingga foto pertama kehilangan pelacakan dua arah: adegannya tidak lagi ikut pembanding dedup selama masih tertahan (anti-banjir salinan #311 hanya menjangkau satu berkas), dan saat akhirnya terkirim lewat flush ia tidak dipromosikan menjadi baseline (gerbang promosi hanya mencocokkan slot kini), sehingga foto berikutnya yang mirip adegan itu bisa terkirim lagi sebagai duplikat. Hash tertahan kini berupa himpunan persisten (`heldPhotoHashes`, dibatasi `MAX_PHOTO_KEPT` mengikuti batas berkas tertahan, entri tertua terbuang lebih dulu; nilai slot tunggal warisan dari versi sebelumnya digabungkan ke himpunan saat dibaca dan slot lama dinolkan saat himpunan ditulis): cabang tahan menambahkan hash berkasnya ke himpunan, pembanding dedup menekan foto yang mirip baseline ATAU hash tertahan mana pun — dengan penyembuhan-diri yang digeneralisasi per entri (himpunan dipangkas menjadi entri yang berkasnya terbukti masih ada di cache, dihitung dari satu pindaian sidik per berkas), flush mempromosikan entri berkas yang sungguh terkirim menjadi baseline dan mengeluarkannya dari himpunan (pada DROPPED hanya dikeluarkan), dan prune mengeluarkan entri milik berkas yang dibuangnya. Perilaku satu berkas tertahan tidak berubah. Helper `heldPhotoFileAlive` yang tergantikan dihapus dan didaftarkan ke `tools/bekas-hapusan.txt`.
- Dua angka drop untuk banjir notifikasi yang sama kini berlabel terbedakan jelas (issue #317): untuk satu banjir yang sama aplikasi menyimpan dua pembukuan drop yang akurat untuk himpunannya masing-masing tetapi berlabel nyaris sinonim — ringkasan riwayat di `/lastnotif` berbunyi "N notifikasi gugur (antrean penuh)" padahal ia hanya menghitung kejadian saturasi ganda dalam jendela geser 60 detik terakhir dan hidup di memori saja, sedangkan notice Telegram berbunyi "N notifikasi dibuang (ditolak/antrean penuh)" padahal ia total kumulatif semua sebab (terdesak overflow, saturasi, ditolak permanen) yang persisten sampai ter-flush — owner tidak punya cara tekstual mendamaikan dua angka yang tampak bertentangan itu. Teks ringkasan riwayat kini menyebut jendelanya ("… notifikasi gugur dalam 60 detik terakhir (antrean penuh)") dan teks notice Telegram menegaskan sifat kumulatifnya ("Total N notifikasi dibuang …", diikuti konteks "Terakumulasi sejak …" dari perbaikan #316 bila cap waktunya ada). Himpunan dan angka kedua sisi tidak berubah sama sekali — yang diperbaiki hanya kejelasan beda cakupannya. Teks notice tetap dibangun satu-satunya oleh pembangun yang sama, sehingga pencocokan identitas batch #315 tidak terpengaruh.
- Notice drop notifikasi kini membawa konteks waktu dan flush yang gagal-antre menjadwalkan ulang dirinya sendiri (issue #316): penghitung drop persisten `pendingNotifDrops` sebelumnya tidak membawa cap waktu apa pun — flush yang gagal-antre memang mengembalikan hitungan, tetapi tidak ada job flush baru di dalam proses yang sama (notice menunggu drop berikutnya atau hidup layanan berikutnya), dan akumulasi yang tertahan lama (mis. akses notifikasi dicabut berhari-hari) terkirim pada hidup berikutnya dengan redaksi identik seperti kejadian barusan, sehingga owner bisa mengira ada banjir terbuang saat ini padahal kejadiannya lama. Cap waktu drop pertama yang belum terlaporkan kini dipersist bersama penghitungnya dalam tulisan prefs yang sama (`pendingNotifDropsSince`: dicap saat akumulasi mulai dari nol, di-seed bersama penghitung, ditulis oleh job cermin persist, salvage `onDestroy`, dan tulisan keadaan akhir flush — termasuk identitas batch #315 yang kini turut membawa cap waktu itu agar pencocokan teks rekonsiliasinya tetap persis), dan teks notice menyebutkannya: "… Terakumulasi sejak dd.MM.yyyy HH:mm:ss." (bentuk `TimeFmt.full` yang sama dengan teks owner lain; untuk akumulasi warisan tanpa cap waktu, teks lama dipertahankan apa adanya). Flush yang gagal mengantre kini menjadwalkan percobaan ulang 60 detik kemudian di dalam proses yang sama memakai pola job tunda yang sudah ada, dan berhenti dengan sendirinya saat flush berhasil atau layanan mati. Angka dan sebab drop tidak berubah — yang ditambah hanya konteks waktunya dan kepastian percobaan ulangnya.
- Laporan jumlah drop notifikasi tidak lagi bisa ganda bila proses mati keras tepat di celah flush (issue #315): `flushDropNotice` di `NotificationForwarderService` sebelumnya mengantrekan notice ke `MessageQueue` (persisten) lebih dulu, baru menolkan `pendingNotifDrops` di prefs — kematian proses di antara keduanya membuat proses berikutnya men-seed total lama yang sama dan mem-flush ulang, sehingga owner menerima "N notifikasi dibuang" yang sama dua kali untuk satu kejadian (arahnya gagal-aman, tetapi jumlah laporannya salah). Flush kini beridentitas batch persisten: sebelum mengantre, flush mencap penanda batch (`jumlah:capWaktu`) ke prefs; sesudah antre, total sisa dan penghapusan penanda ditulis dalam SATU commit atomik. Pada flush pertama tiap proses, penanda sisa dari proses yang mati direkonsiliasi: antrean diperiksa — notice batch itu masih ada (teks + cap waktu pembuatan cocok) berarti jumlahnya sudah terlaporkan, sehingga seed dikurangi tepat sebesar batch itu dan penanda dibersihkan; notice tidak ditemukan berarti batch belum sempat terantre, sehingga penanda dibersihkan dan jumlahnya dilaporkan oleh flush yang sedang berjalan. Arah gagal tetap melapor (bukan hilang): kegagalan antre tetap mengembalikan hitungan seperti sebelumnya, dan kegagalan commit akhir membuat penanda bertahan untuk direkonsiliasi proses berikutnya.


## [1.6.543] - 2026-10-30

### Fixed
- Penghapusan trail dari Setup tidak lagi bisa dibatalkan penulisan lokasi yang sedang berjalan (issue #314): penulis trail layanan `rememberSentLatLon` melakukan baca-ubah-tulis di bawah `prefsRmwLock`, tetapi pengosongan `trail24` pada alur hapus kredensial di `SetupActivity` tidak mengambil kunci itu, sehingga urutan layanan membaca raw lama → Setup mengosongkan → layanan menulis gabungan lama+titik baru bisa menghidupkan lagi trail yang baru dihapus (satu proses yang sama — murni kunci yang tidak dipakai pihak Setup). Objek kunci itu kini menjadi milik bersama di `PreferencesManager` (`prefsRmwLock`; `MonitoringService` merujuknya lewat getter delegasi sehingga seluruh situs `synchronized` layanannya memakai objek yang sama persis), dan pengosongan trail di Setup dibungkus `synchronized(prefs.prefsRmwLock)`. Cakupan hanya pengosongan trail, sesuai issue.
- Pemangkasan cap 300 titik trail tidak lagi senyap — digest kini menyatakan cakupan nyata bila cap memotong jendela 24 jam (issue #313): penulis trail di `rememberSentLatLon` sebelumnya menyaring waktu 24 jam lalu membuang kelebihan di atas 300 titik tanpa mencatat apa pun, padahal cap itu terjangkau pada hari berat (interval patroli minimum 5 menit saja menghasilkan 288 titik/hari, ditambah fix pagar dan perintah lokasi), sehingga baris "Jarak 24 jam", "Diam …", dan "Di sekolah: …" di digest malam dihitung dari sisa jendela yang lebih muda tetapi labelnya tetap "24 jam". Penulis kini mencatat cap waktu titik terbaru yang dibuang oleh pemangkasan-jumlah selagi titik itu masih di dalam jendela (`trailTrimmedFrom`, ditulis dalam editor atomik yang sama dengan trail-nya lewat `recordSentLocation`, dan dinolkan otomatis bila trail terdeteksi kosong — mis. sesudah penghapusan dari Setup), dan blok jejak di digest menambahkan satu baris penanda "⚠ Jejak terpotong cap 300 titik — dihitung dari titik sejak HH:MM" (jam titik tertua yang tersisa) HANYA bila titik yang dibuang itu masih berada di dalam jendela 24 jam saat digest dibuat. Pada hari tanpa pemangkasan-jumlah, label dan semua angka digest tidak berubah sama sekali.
- "Jarak 24 jam" di digest tidak lagi menggelembung oleh fix lokasi kasar (issue #312): titik trail sebelumnya tidak menyimpan akurasi fix sama sekali — `bestFix` memilih akurasi terkecil tanpa plafon mutlak dan fallback memakai fix berapa pun akurasinya — sehingga satu fix jaringan yang meleset jauh (mis. 3 km saat GPS tak dapat sinyal) tercatat sebagai titik pasti dan `TrailStats.distanceMeters` menambahkan jarak sebelumnya→fix-kasar→berikutnya (±2× besar lesetan, ±6 km untuk lesetan 3 km) ke total jarak, tanpa penanda apa pun. Format titik trail kini menyimpan akurasi dalam meter sebagai medan ketiga (toleran-baca: titik lama tanpa medan itu tetap terurai dan diperlakukan sebagai akurasi tak diketahui, dan titik tak diketahui tetap dihitung penuh — riwayat lama tidak dikosongkan), dan `trailDigestLines` kini menyaring titik lewat fungsi murni baru `TrailStats.dropCoarse` sebelum ketiga statistik trail (`distanceMeters`, `longestStay`, `millisInside`) menghitung: titik yang akurasinya DI ATAS plafon 1.000 m dibuang — plafon yang sama dengan preferensi fallback `lastKnownFix` yang sudah ada (fix ≤1.000 m didahulukan) — sehingga segmen menjembatani titik kasar (tetap dijaga celah waktu maksimum) alih-alih singgah padanya. Ketiga statistik menerima daftar tersaring yang sama dengan alasan yang sama: titik kasar yang sama juga memutus rangkaian diam (`longestStay`) dan membalik klasifikasi dalam/luar pada interpolasi durasi di sekolah (`millisInside`). Pesan lokasinya sendiri tidak berubah — fix kasar tetap terkirim dengan label akurasinya seperti sekarang; yang berubah hanya perlakuan titiknya di statistik jejak. Terkunci uji JVM baru di `TrailStatsTest` (titik kasar di tengah & di ujung segmen dibuang, akurasi tepat di plafon tetap dihitung, akurasi tak diketahui tidak dibuang, titik kasar tidak memutus diam dan tidak membalik durasi-di-dalam; nilai harapan dibuktikan lewat porting 1:1 logika barunya).
- Sidik dedup foto tidak lagi menunjuk foto yang tidak pernah diterima owner ("foto hantu") sesudah foto tertahan hemat-kuota gugur (issue #311): cabang tahan hemat-kuota di `sendCapturedPhoto` sebelumnya mencap baseline dedup (`lastPhotoHash`) pada foto yang baru *ditahan*, belum terkirim — cap itu bermaksud sah (mencegah antrean tertahan terisi salinan adegan yang sama), tetapi baseline tidak membedakan "tertahan" dari "terkirim", sehingga bila berkas tertahan kemudian gugur sebelum sampai ke owner (ter-prune saat cache penuh, atau digolongkan DROPPED saat flush), foto otomatis berikutnya yang mirip adegan yang sama terus dibuang diam-diam sebagai "duplikat" tanpa batas waktu dan tanpa pesan. Kini ada hash tertahan yang terpisah (`heldPhotoHash`, persisten mengikuti pola penyimpanan baseline): cabang tahan mencap hash tertahan, bukan baseline; pembanding dedup menekan foto yang mirip baseline terkirim ATAU mirip hash tertahan yang berkasnya terbukti masih ada di cache; saat flush mengirim berkas tertahan itu, hash-nya dipromosikan menjadi baseline dan hash tertahan dinolkan; saat berkas tertahan ter-prune atau DROPPED saat flush, hash tertahan dinolkan sehingga adegan itu bebas lagi — dan pembanding dedup juga menolkan hash tertahan yang berkasnya sudah tidak ditemukan di cache, sehingga jalur penghapusan lain (mis. `/bersihkan`) tidak bisa meninggalkan hantu. Jalur SENT (cap sesudah terkirim) dan KEPT (tidak mencap) tidak berubah, dan maksud anti-banjir salinan selama masa tahan tetap tercapai.
- Drop jalur auth/rate-limit kini dilaporkan ulang dengan sebab yang benar, tidak lagi menyamar sebagai overflow antrean (issue #310): `authDropped` di `handleAuthOrRate` — pesan yang dibuang karena sudah mentok percobaan pada jalur auth/rate-limit — sebelumnya dikembalikan lewat `restoreOverflowDrops` bila antre pemberitahuan langsungnya gagal (satu-satunya penghitung yang tersedia sebelum #309), sehingga run berikutnya melaporkannya sebagai "paling lama di antrean (antrean penuh saat offline)" dan owner bisa salah diagnosis (menyalahkan koneksi/offline, bukan kredensial/rate-limit). Jumlah itu kini dicatat ke penghitung khusus habis-percobaan dari perbaikan #309: bila pemberitahuan langsung terantre, jumlah yang sama dikonsumsi dari penghitung itu (kelebihan sisa laporan lama dikembalikan, jadi tidak ada laporan ganda dan sisa lama tidak ikut terhapus); bila antre gagal, jumlahnya tinggal di penghitung habis-percobaan dan dilaporkan run berikutnya lewat `reportDrops` dengan teks sebab aslinya, "setelah percobaan maksimal". Teks pemberitahuan langsung jalur auth/rate tidak berubah.
- Seluruh komponen laporan drop di worker kirim kini failure-atomic, bukan hanya yang sudah berpenghitung (issue #309, sisa sekeluarga #305 di fungsi yang sama): perbaikan #305 membuat tiga komponen `reportDrops` (kedaluwarsa/overflow/prioritas) dikembalikan ke penghitung persistennya bila `sendDropNotice` gagal total, tetapi dua komponen lain dari notice yang sama — "setelah percobaan maksimal" (pesan habis percobaan yang dihapus `registerWithBudget`) dan "ditolak (HTTP 4xx)" (pesan yang dihapus loop drain) — hanya hidup sebagai angka lokal run itu, sehingga kegagalan total notice membuat keduanya lenyap permanen dari pembukuan dan tidak pernah dilaporkan di run mana pun. `MessageQueue` kini punya dua penghitung persisten baru (`exhaustedDrops`/`rejectedDrops`) mengikuti pola persis tiga penghitung yang ada (pasangan take/restore simetris, persist bersama, penggabungan mode volatil, dan pembersihan `clearQueue` yang sama); komponen habis-percobaan dicatat ke penghitungnya oleh `reportDrops` tepat sesudah `registerTransientFailures` membuangnya, dan komponen ditolak dicatat oleh loop drain tepat sesudah penghapusannya berhasil. `reportDrops` kini mengambil dan — bila notice gagal total — mengembalikan KELIMA komponen, sehingga seluruh isi notice selamat ke laporan run berikutnya. Teks notice dan urutan bahagia (notice terkirim/terantre) tidak berubah, dan komponen yang sudah dilaporkan sukses tidak tersisa di penghitung (tidak ada hitung ganda).


## [1.6.542] - 2026-10-30

### Fixed
- Label tombol menu jejak kini jujur terhadap isi datanya (issue #308, sisa terakhir kelas label-"hari ini"-vs-jendela-bergulir): tombol "🦶 Jejak hari ini" di menu Lokasi membuka `trailList()` yang menyaring simpanan jejak `trail24` dengan jendela 24 jam bergulir, sehingga titik lokasi kemarin malam (mis. 23.30 kemarin) ikut tampil lewat pintu berlabel "hari ini". Label tombol kini "🦶 Jejak 24 jam"; penyaringan data, simpanan jejak, dan teks balasan (yang memang tidak mengklaim hari apa pun) tidak berubah. Dengan ini kelas label-"hari ini"-vs-jendela-bergulir dinyatakan TERTUTUP: seluruh permukaan teks ke owner sudah terinventaris pada audit putaran-37 (17 permukaan — 15 cocok/berlabel jujur, 2 sisa adalah #307 dan issue ini) dan tidak ada lagi label hari kalender di atas hitungan jendela bergulir.
- Sorotan digest kini sungguh membandingkan hari kalender hari ini vs kemarin, bukan dua blok bergulir 24 jam (issue #307, sisa kelas label-hari-vs-jendela sesudah #298/#303): `digestHighlights` di `MonitoringService` sebelumnya mengambil sisi "hari ini" dari baris UsageStats 24 jam bergulir dan sisi "kemarin" dari blok [now−48 jam, now−24 jam], padahal teks sorotannya berbunyi "naik/turun N% dari kemarin" dan "kemarin hanya …" — digest malam (±21.00) membuat blok "kemarin" memuat ±3 jam malam dua hari lalu dan membuang kemarin 21.00–24.00, sehingga persen perubahan layar dan lonjakan aplikasi teratas bisa meleset nyata, bahkan berlawanan arah, dari hari kalender yang owner kira. Kedua sisi kini memakai jendela hari kalender (hari ini sejak `A11yUsageMath.dayStartWallMillis`, kemarin [dayStart−24 jam, dayStart)), mengikuti pola `todayFacts` dari perbaikan #298; teks sorotan dan label "Layar 24 jam" untuk tampilan ringkasan tidak berubah, dan jalur A11y tidak berubah perilaku (nilai kemarinnya tetap 0 sehingga kandidat perbandingan tetap tertahan penjaga). Logika murni `DigestHighlights.build` tidak berubah, sehingga uji JVM-nya tetap mengunci perilaku yang sama.
- Sisa potongan pesan panjang kini mewarisi flag prioritas pesan asalnya sehingga ekor salinan data tidak kehilangan perlindungan eviction (issue #306): `sendChunked` di `SendMessageWorker` sebelumnya mengantre-ulang sisa potongan yang belum terkirim (jalur deadline, pembatalan, rate-limit, auth, gagal transien, dan ditolak) lewat `addMessage` tanpa argumen prioritas, dan tanda tangan `sendMessage`/`sendChunked` memang tidak menerima prioritas sama sekali — salinan data SMS/panggilan yang sengaja diantrekan sebagai prioritas agar tahan eviction (desain #95: kursor sudah maju, salinan antrean satu-satunya jalur kirim) kehilangan perlindungannya tepat pada ekornya bila drain gagal di tengah potongan, sehingga ekor itu menjadi kandidat eviction non-prioritas saat antrean dibanjiri offline. `doWorkInternal` kini meneruskan `queuedMessage.priority` lewat `sendMessage` ke `sendChunked`, dan keenam situs antre-ulang sisa memakai flag itu; pesan non-prioritas tetap non-prioritas dan urutan pengiriman tidak berubah.
- Pelaporan drop di worker kirim kini failure-atomic sehingga angka pesan terbuang tidak hilang senyap (issue #305): `reportDrops` di `SendMessageWorker` sebelumnya mengambil penghitung drop persisten (kedaluwarsa/overflow/prioritas) lewat `take*Drops()` — yang langsung menolkan penghitung dan memersist nol itu — baru kemudian memanggil `sendDropNotice`, dan hasil antre cadangan di dalam `sendDropNotice` maupun di kedua situs pemberitahuan jalur auth/rate di `handleAuthOrRate` diabaikan; proses yang mati di antara pengambilan dan pengiriman, atau kirim langsung gagal sekaligus antre cadangan gagal, membuat angka drop hilang permanen dan run berikutnya melaporkan mulai dari 0. Kini mengikuti pola `flushMsgDropNotice` di `MonitoringService`: `sendDropNotice` melaporkan keberhasilan (terkirim langsung atau sungguh terantre), penghitung yang sudah diambil dikembalikan ke `MessageQueue` lewat API tambah-balik persisten baru (`restoreExpiredDrops`/`restoreOverflowDrops`/`restorePriorityDrops`, satu per jenis, mengikuti pola penghitung yang ada) bila kedua jalur gagal, dan kedua situs `handleAuthOrRate` mengembalikan jumlah drop-nya ke penghitung overflow antrean bila antre pemberitahuannya gagal — drop tetap terhitung pada laporan run berikutnya. Urutan bahagia (notice terkirim/terantre) tidak berubah dan tidak ada pelaporan ganda pada jalur sukses.


## [1.6.541] - 2026-10-30

### Fixed
- PIN akses hasil restore tidak lagi hilang diam-diam bila proses mati di antara restore dan simpan (issue #304): PIN restore sebelumnya hanya hidup di penampung statis dalam proses (`RestorePinHolder`), sedangkan owner restore dipulihkan dari bundle — kematian proses di jendela itu membuat Simpan menerapkan owner hasil restore tetapi PIN jatuh ke PIN tersimpan lama (di perangkat baru: kosong), tanpa pesan apa pun, sehingga konfigurasi tersimpan tanpa gerbang `/login` yang dipercaya pengguna sudah aktif. Kini ada bendera boolean `pendingRestorePinExpected` yang ikut disimpan ke bundle (PIN-nya sendiri tetap TIDAK pernah ditulis ke bundle): bendera disetel saat restore membawa PIN, dan kedua jalur simpan (Simpan dan Tes→Simpan) MENOLAK dengan pesan jelas meminta impor ulang backup bila kolom PIN masih bermasker, bendera aktif, tetapi PIN restore tidak lagi tersedia di penampung — keadaan campuran tidak bisa lagi tersimpan senyap. Jalur normal (restore lalu simpan dalam proses yang sama, termasuk sesudah rotasi) tidak berubah; bendera dibersihkan pada simpan sukses, restore baru, hapus kredensial, dan mulai segar.
- Lencana "🔁 N x hari ini" kini sungguh menghitung hari kalender, bukan 24 jam bergulir (issue #303, kelas label-vs-jendela yang sama dengan #298): `freqForNumbers` di `MonitoringService` sebelumnya memakai jendela `now - 24 jam`, sehingga pesan dan panggilan dari kemarin malam ikut terhitung sebagai "hari ini" sampai 24 jam berlalu dan angka frekuensi — sinyal kewaspadaan untuk nomor asing — terbaca membengkak (mis. 3 SMS pukul 23.00 kemarin + 1 SMS pagi ini tampil "🔁 4 x hari ini"). Jendela kini sejak awal hari kalender (`A11yUsageMath.dayStartWallMillis`, pola yang sama dengan perbaikan #298); hitungan tetap gabungan SMS + panggilan dan label tidak berubah, dan kedua konsumen peta ini — daftar SMS dan `/history` — mewarisi definisi baru.
- Cache konfigurasi penerusan notifikasi tidak lagi terbaca "segar" padahal belum pernah diisi, sehingga penerusan tidak mati total selama 10 detik pertama sejak boot (issue #302, kelas penanda-0 arah terbalik dari #299): `ensureCfgCache` di `NotificationForwarderService` sebelumnya hanya me-refresh bila `elapsedRealtime() - cfgCacheAt >= 10_000`, padahal `cfgCacheAt` mulai dari 0 dan `elapsedRealtime` adalah uptime — selama uptime di bawah 10 detik selisih itu selalu di bawah ambang, cache kosong dengan nilai awal `cfgCacheEnabled = false` dipakai apa adanya, dan `handlePosted` keluar sebelum mencatat riwayat: notifikasi pada jendela itu tidak diteruskan ke Telegram dan tidak tercatat di `/lastnotif`. Kesegaran kini mensyaratkan cache pernah terisi (penanda `cfgCacheFilled`): refresh pertama selalu terjadi pada panggilan pertama, invalidasi dari listener prefs membersihkan penanda itu (selain menolkan `cfgCacheAt`) sehingga refresh berikutnya terpaksa pada semua uptime, dan jendela kesegaran 10 detik untuk cache yang sudah terisi tidak berubah.

## [1.6.540] - 2026-10-29

### Fixed
- Durasi sesi pemakaian aplikasi yang sedang berjalan tidak lagi hilang seluruhnya bila proses dibunuh di tengah sesi statis (issue #301): sesi berjalan `A11yMonitor` sebelumnya hanya hidup di memori — akru dan persist digerakkan event, tidak ada persist berkala berbasis waktu dan tidak ada penanda sesi-terbuka — sehingga pembunuhan paksa proses (LMK/OEM) saat anak memakai satu aplikasi yang jarang memancarkan event aksesibilitas menghilangkan seluruh durasi sejak persist sukses terakhir dari total harian dan `nightMillis`, karena `flush()` hanya berjalan dari `onDestroy` yang tidak terpanggil pada pembunuhan proses. Kini ada penanda sesi-terbuka persisten (paket + pasangan waktu mulai dinding/monoton) yang ditulis dalam satu editor gabungan di `PreferencesManager` setiap sesi mulai/berganti/dilanjutkan, dibersihkan setiap sesi selesai, dan disegarkan ke garis depan akru setiap persist total berhasil — sehingga penanda selalu menunjuk tepat rentang yang belum masuk total tersimpan. Pada hidup berikutnya penanda dipulihkan secara KONSERVATIF: sesi hanya dilanjutkan (dan rentangnya diakru lewat mekanisme akru biasa) bila boot terbukti sama (`BootClock.sameBoot`), hari kalendernya sama, dan paket sesi terbukti masih menjadi paket latar depan saat layanan aksesibilitas tersambung; pada semua keadaan lain penanda dibuang tanpa akru. Prinsipnya: total harian tidak boleh melebihi durasi nyata — keraguan selalu berarti tidak mengkreditkan, bukan menebak.
- Hitungan ringkasan "N notifikasi gugur" tidak lagi direset ke 1 bila record ringkasannya tergusur dari riwayat (issue #300): `recordDrop` di `NotificationForwarderService` sebelumnya mensyaratkan record ringkasan masih ada di riwayat berkapasitas 20 sebagai syarat penggabungan dalam jendela 60 detik — banjir notifikasi, kondisi yang justru menyebabkan drop itu sendiri, memutar riwayat dengan cepat sehingga record tergusur dan hitungan menimpa ke 1 serta muncul record ringkasan kedua. Keputusan hitung kini murni dari keadaan penghitung tersendiri (`dropSummaryAt` + `dropSummaryCount`): dalam jendela geser 60 detik hitungan selalu bertambah berapa pun isi riwayat, dan record riwayat hanya cerminan tampil yang diganti pada tempatnya. Teks dan format ringkasan di `/lastnotif` tidak berubah selain hitungannya yang kini benar.
- Dedup penerusan notifikasi tidak lagi menelan notifikasi pertama dalam 10 detik pertama sejak boot (issue #299): penilaian duplikat di `NotificationForwarderService` membaca penanda kosong sebagai "baru saja terkirim" (`prev = lastSent[key] ?: 0L` lalu `now - prev < 10_000`), padahal `now` adalah `elapsedRealtime` — selama uptime perangkat di bawah 10 detik, konten yang belum pernah terkirim pun dinilai duplikat, hanya dicatat ke riwayat, dan tidak diteruskan ke Telegram. Penilaian kini mensyaratkan `prev > 0` — pola yang sama yang sudah dipakai berkas ini dan `A11yMonitor` untuk penanda monoton — sehingga konten tanpa penanda tidak pernah dianggap duplikat; jendela dedup 10 detik untuk konten yang sungguh sudah terkirim tidak berubah.
- Definisi `screenMin` pada fakta harian disatukan menjadi hari kalender di kedua jalur sehingga baseline pola tidak tercemar dan atribusi anomali tidak bergeser sehari (issue #298): `todayFacts` sebelumnya mengambil total layar dari baris UsageStats 24 jam bergulir yang dipakai bersama teks digest — episode layar malam hari X (mis. 21.00–24.00) tidak masuk fakta hari X tetapi membengkakkan hari X+1 sementara `nightMin`-nya tercatat di hari X, nilai rekaman hari yang sama bergantung pada jam digest terakhir dipanggil, dan median riwayat mencampur definisi bergulir dengan definisi hari kalender jalur A11y bila jalur izin berganti. `todayFacts` kini menghitung total UsageStats lewat jendela sejak awal hari kalender (`A11yUsageMath.dayStartWallMillis`) — definisi yang sama dengan akrual A11y — dengan urutan prioritas sumber yang tidak berubah (UsageStats dulu, A11y sebagai cadangan). Teks digest dan sorotan tidak berubah sama sekali: baris 24 jam bergulir tetap dipakai untuk tampilan ("Layar 24 jam") dan perbandingan sorotan; yang disatukan hanya definisi rekaman fakta hariannya.
- Cabang SCREEN pada penilaian pola kini mensyaratkan nilai layar hari ini terukur sehingga `/waspada` tidak bisa mencetak baris perbandingan layar palsu (issue #297): `PatternBaseline.assess` adalah satu-satunya cabang yang tidak memeriksa `today.screenMin >= 0` — empat cabang lain (NIGHT, FIRST, ARRIVE, DEPART) semuanya mensyaratkannya — sehingga bila `todayFacts` kosong (Usage Access mati dan baris A11y hari ini kosong) tetapi hari ini sudah punya rekaman placeholder dari transisi pagar (`screenMin = -1`), fallback `sendWaspada` memakai placeholder itu sebagai "hari ini" dan bagian "Dibanding pola biasanya" mencetak "Layar 0 m — biasanya ±…" seolah anak tidak memakai HP sama sekali, padahal nilainya tidak terukur. Cabang SCREEN kini memakai syarat yang sama dengan cabang lain; perilaku untuk nilai terukur tidak berubah. Terkunci uji JVM baru di `PatternBaselineTest` (hari ini tak terukur dengan riwayat valid: tidak ada temuan SCREEN, temuan lain tetap muncul).


## [1.6.539] - 2026-10-29

### Fixed
- Penanda atribusi uji anti-mati kini hanya ditulis jalur jam bangun sehingga laporan `/cekmati` tidak bisa mengklaim watchdog terbukti padahal penghidupnya broadcast perubahan jam (issue #296): `Liveness.handleWakeUp` adalah badan bangun bersama untuk dua pintu masuk — `LivenessReceiver` (jam bangun watchdog) dan `TimeChangeReceiver` (perubahan jam/zona/tanggal) — dan penulisan `liveTestByAlarm = true` di dalamnya tidak membedakan sumber, sehingga layanan yang dihidupkan perubahan jam di tengah uji ikut menandai "dipulihkan jam bangun" dan laporan menyatakan anti-mati bekerja padahal jam bangun belum terbukti. `handleWakeUp` kini menerima parameter `fromAlarm` (bawaan `false`): `LivenessReceiver` meneruskan `true`, `TimeChangeReceiver` `false`, dan penanda atribusi hanya ditulis bila panggilan berasal dari jam bangun. Fungsi bangun-ulang layanan tetap dipakai bersama kedua receiver, penjaga `liveTestPending` dan teks laporan tidak berubah; angka detik pemulihan memang selalu jujur — yang diperbaiki hanya atribusi jalurnya.
- Penghitung "gagal lokasi beruntun" kini direset juga pada keberhasilan perintah lokasi sehingga catatan kesehatan tidak bisa mengklaim kegagalan berturut-turut yang tidak pernah terjadi (issue #295): penghitung persisten `locFailCount` sebelumnya hanya dinolkan oleh keberhasilan jalur berkala (`patrolRound`, `checkCurfew`) dan sesudah dilaporkan, padahal cabang gagal ketiga handler perintah (`/hilang`, `/darurat`, `/location`) ikut menaikkannya — kegagalan perintah yang diselingi keberhasilan tetap menumpuk sampai catatan "lokasi gagal N x beruntun" dari pemeriksaan kesehatan berkala terkirim walau tidak pernah ada dua kegagalan berurutan. Cabang sukses ketiga handler kini memanggil `resetLocFailCount()` — simetris dengan patroli dan curfew — sedangkan teks catatan dan ambang lapor (≥5) tidak berubah.


## [1.6.538] - 2026-10-29

### Fixed
- Triple keadaan uji anti-mati kini ditulis & dibersihkan atomik sehingga laporan `/cekmati` tidak bisa salah vonis dari rekaman campuran (issue #294, kelas pasangan kunci prefs ditulis tak atomik): `liveTestPending`, `liveTestStartedElapsed`, dan `liveTestByAlarm` adalah satu rekaman semantik tetapi sebelumnya ditulis flag-lebih-dulu lewat tiga `apply()` terpisah di `runLivenessSelfTest` dan dibersihkan juga lewat tiga `apply()` terpisah di `reportLivenessSelfTest` — kematian proses eksternal di jendela antar-setter meninggalkan rekaman campuran persisten yang lolos dari kedua penjaga (nilai basi positif dalam boot yang sama), sehingga detik pemulihan bisa dihitung dari titik awal uji sebelumnya (vonis "gagal >10 menit" palsu) atau klaim jalur pemulih bisa memakai `byAlarm` basi. `PreferencesManager` kini menyediakan penulis gabungan `beginLiveTest` (ketiga kunci dalam satu editor) dan `clearLiveTest` (pembersihan ketiga kunci dalam satu editor) yang dipakai kedua fungsi. Penulisan satuan `liveTestByAlarm = true` oleh jam bangun di `Liveness` sengaja tetap satuan: itu satu `apply()` pada satu kunci dari rekaman yang sudah lengkap, sehingga tidak bisa merobek rekaman dengan sendirinya — rantai kebasian `byAlarm` berasal dari penulisan awal dan pembersihan yang tak atomik, yang kini keduanya atomik (penulisan awal juga menolkan `byAlarm` dalam editor yang sama, jadi sisa apa pun terhapus pada setiap awal uji). Vonis dan teks laporan uji tidak berubah.
- Pasangan jeda foto kini ditulis atomik sehingga jeda tidak bisa berlabel sumber yang salah (issue #293, kelas pasangan kunci prefs ditulis tak atomik): `photoPausedUntil` dan `photoPauseSource` sebelumnya ditulis lewat dua setter terpisah yang masing-masing `apply()` sendiri di lima situs (`handlePauseCommand`, `handleResumeCommand`, reset migrasi penanda jeda, `autoPausePhotosOnPolicyBlock`, `resumePhotosAfterPolicyLift`), padahal loop kamera membacanya sebagai satu kesatuan — proses yang mati di antara kedua penulisan meninggalkan pasangan robek permanen: jeda kebijakan 120 menit berlabel bukan-admin (jalur cek pencabutan blokir tak pernah jalan, foto terjeda sampai kedaluwarsa walau blokir sudah dicabut) atau jeda manual owner berlabel ADMIN basi (langsung dibatalkan loop kamera disertai pesan "blokir dicabut" yang menyesatkan). `PreferencesManager` kini menyediakan penulis gabungan `setPhotoPause` (batas jeda + sumber dalam satu editor, mengikuti pola `setHomeFence` dari #289) yang dipakai kelima situs; tulis satu-kunci yang memang disengaja (penyegaran batas jeda saat jeda admin berjalan, migrasi nilai batas jeda lama, penaikan sumber ke ADMIN saat sudah terjeda, reset teardown di Setup) tetap satuan karena satu `apply()` tidak bisa merobek pasangan. Urutan nilai dan teks pesan tidak berubah.
- Klasifikasi konflik-diri polling 409 tidak lagi salah selama 60 detik pertama sesudah boot (issue #292): `lastLoopRestartAt` — penanda yang dipakai cabang 409 `pollTelegramCommands` untuk membedakan konflik diri dari konflik asing — sebelumnya hanya dicap di `restartAllLoops()`, sehingga sejak layanan mulai sampai restart pertama nilainya masih 0 dan `now409 - 0 < 60_000` selalu benar selama uptime perangkat di bawah 60 detik; semua 409 pada jendela itu terbaca konflik diri (backoff 60 detik, bukan 300 detik) dan percobaan `clearStaleWebhook` — satu-satunya jalur pemulihan aktif konflik 409 — dilewati, sehingga pemulihan konflik asing tertunda sampai 409 berikutnya sesudah uptime melewati 60 detik. Penanda kini juga dicap di `startMonitoring()` tepat saat loop pertama diluncurkan, dengan semantik "restart terakhir atau mulai pertama, mana yang terbaru"; jendela 60 detik dan logika backoff tidak berubah.


## [1.6.537] - 2026-10-29

### Fixed
- Gerbang coba-ulang pembersihan webhook basi kini memakai jam monoton (issue #291, kelas gerbang jeda pendek berbasis jam dinding): pada konflik polling 409 bukan-diri, percobaan ulang `clearStaleWebhook` — satu-satunya jalur pemulihan aktif — sebelumnya digerbang penanda jam dinding (`System.currentTimeMillis()`), sehingga koreksi jam mundur (NTP besar atau jam diatur manual) sesudah percobaan pertama membengkakkan gerbang 10 menit seukuran lompatan dan perintah Telegram tertahan jauh lebih lama dari seharusnya, padahal backoff polling di jalur yang sama sudah berbasis `elapsedRealtime`. Penanda kini memakai jam monoton yang sama (`webhookClearAtElapsed`, memakai cap `now409` yang sudah dihitung di cabang itu); penanda lama berbasis jam dinding tidak dipakai lagi, sehingga percobaan pertama sesudah pembaruan berjalan tanpa sisa gerbang lama. Interval 10 menit tidak berubah.
- Pasangan penghitung liveness kini ditulis atomik sehingga angka diagnostik tidak bisa meleset permanen (issue #290, kelas pasangan kunci prefs ditulis tak atomik): `livenessKills` dan `livenessKillsUnreported` sebelumnya dinaikkan lewat dua `apply()` terpisah di `Liveness.noteServiceStart`, dan direset bersama `livenessReportAt` juga lewat dua `apply()` terpisah sesudah laporan kebangkitan terantre — proses yang mati di antara keduanya meninggalkan selisih permanen sebesar satu (satu pembunuhan tak pernah terlaporkan, atau hitungan lama terlaporkan ulang). `PreferencesManager` kini menyediakan penulis gabungan `incrementLivenessKills()` (menaikkan kedua penghitung dalam satu editor dan mengembalikan hitungan belum-terlaporkan yang baru) dan `markLivenessReported()` (mencap waktu lapor sekaligus menolkan penghitung belum-terlaporkan dalam satu editor); angka dan ambang signifikansi laporan tidak berubah.
- Konfigurasi geofence Rumah/Sekolah kini ditulis atomik sehingga pagar tidak bisa aktif di titik campuran (issue #289, kelas konfigurasi multi-kunci prefs ditulis tak atomik): handler `/rumah` dan `/sekolah` sebelumnya menulis lat, lon, radius, dan state lewat empat setter terpisah yang masing-masing `apply()` sendiri, padahal ketiganya dibaca sebagai satu konfigurasi oleh `checkGeofence` — pembacaan berkala di sela penulisan bisa menilai titik campuran (lat baru + lon lama) dan memicu transisi serta peringatan tiba/keluar palsu, dan proses yang mati di tengah urutan tulis meninggalkan pasangan campuran tersimpan permanen walau owner sudah menerima konfirmasi pagar aktif. `PreferencesManager` kini menyediakan penulis gabungan `setHomeFence`/`setSchoolFence` (satu editor untuk lat, lon, radius, dan state) yang dipakai handler, mengikuti pola penulis gabungan yang sudah ada (`setLastAliveMarker`, `setA11yUsage`); jalur mematikan pagar juga memakai penulis gabungan `clearHomeFence`/`clearSchoolFence` dengan semantik yang sama (radius 0 menonaktifkan pagar). Perilaku perintah dan teks konfirmasi tidak berubah.
- Usia denyut pada laporan `/alive` tidak lagi menyesatkan sesudah reboot (issue #288, sisa kelas penanda monoton persisten #275): `aliveReportText()` menghitung usia denyut terakhir dengan pola elapsed-didahulukan — selama penanda `lastAliveElapsed` terisi, cabang jam dinding `lastAliveAt` tidak pernah terjangkau dan tidak ada validasi boot — sehingga sesudah reboot selisih elapsed bisa negatif dan laporan menampilkan "Denyut terakhir: ?" padahal usia jam dinding yang benar tersedia, atau angkanya mengecil karena masa HP mati tidak terhitung; cabang elapsed kini hanya dipakai bila pasangan wall+elapsed tersimpan terbukti satu boot dengan pasangan kini lewat `BootClock.sameBoot` — pola yang sama dengan perbaikan status Setup pada #286 — selain itu usia dihitung dari jam dinding `lastAliveAt`. Tampilan pada boot yang sama tidak berubah.


## [1.6.536] - 2026-10-29

### Fixed
- Penjaga boot penuh 10 menit di `BootReceiver` tidak lagi menyala lintas reboot (issue #287, kelas penanda monoton persisten #275): penjaga `last_full_boot_elapsed` sebelumnya tidak memuat syarat boot-yang-sama yang dimiliki penjaga saudaranya `last_handle_elapsed` tepat di atasnya, sehingga sesudah reboot selisih `nowBoot - lastFullBoot` menjadi negatif — selalu lebih kecil dari 10 menit — dan setiap `handleBoot` yang tiba saat layanan sudah berjalan keluar tanpa `markHandled` selama uptime boot baru belum melampaui cap elapsed boot lama; penjaga kini mensyaratkan `nowBoot >= lastFullBoot` menyamai penjaga saudaranya, sedangkan perilaku dedup dalam boot yang sama tidak berubah.
- Usia "terakhir aktif" di status Setup tidak lagi menyesatkan sesudah reboot (issue #286, kelas penanda monoton persisten #275): perhitungan sebelumnya selalu mendahulukan cabang `elapsedRealtime` setiap kali penanda `lastAliveElapsed` terisi, tanpa validasi boot — penanda itu persisten tetapi jam monoton ter-reset tiap reboot, sehingga sesudah reboot selisihnya negatif lalu dijepit ke nol dan status menampilkan "0 menit lalu" walau layanan terakhir hidup berjam-jam sebelum reboot (atau angkanya mengecil karena masa HP mati tidak terhitung); cabang elapsed kini hanya dipakai bila pasangan wall+elapsed tersimpan terbukti satu boot dengan pasangan kini lewat `BootClock.sameBoot` dari #285, selain itu usia dihitung dari jam dinding `lastAliveAt` seperti cabang cadangan yang sudah ada. Tampilan pada boot yang sama tidak berubah, dan nilai tampil tetap dijepit tidak negatif.
- Deteksi "layanan dibunuh" pada liveness tidak lagi menghitung reboot sebagai kill (issue #285, kelas penanda monoton persisten #275): `Liveness.noteServiceStart` sebelumnya menghitung masa mati murni dari selisih `elapsedRealtime` kini terhadap penanda `lastAliveElapsed` yang persisten lintas reboot, dengan satu-satunya gerbang `nowElapsed > prevElapsed` — padahal jam monoton ter-reset tiap reboot, sehingga boot lama yang singkat disusul mulai layanan yang terlambat di boot baru terbaca sebagai masa mati belasan menit dan bisa menaikkan penghitung kill serta memicu laporan kebangkitan palsu; hitungan kill dan laporan kini hanya berjalan bila pasangan wall+elapsed tersimpan terbukti berasal dari boot yang sama lewat pembanding murni baru `BootClock.sameBoot` (jam boot diturunkan sebagai wall − elapsed dari pasangan tersimpan dan pasangan kini, toleransi 120 detik — pola yang sama dengan penjaga kesegaran antrean di `MessageQueue`), dan penanda basi lintas boot cukup ditimpa penanda baru tanpa dihitung sebagai kill. Logika murni `BootClock` terkunci uji JVM (`BootClockTest`: boot sama, geser jam dinding kecil, tepi toleransi, skenario boot-singkat-mulai-terlambat, reboot sesudah uptime panjang, penanda kosong); perilaku hitungan kill dalam boot yang sama tidak berubah.
- Bungkus situs panggil geofence di cek kendaraan tidak lagi menelan pembatalan (issue #284, sisa terakhir rantai CE): `checkVehicle` memanggil `checkGeofence` lewat `try { … } catch (_: Exception) { }` tanpa klausa pembatalan, sehingga `CancellationException` yang sejak #280 diteruskan keluar dari `checkGeofence` tertelan di situs panggil ini dan pembungkus `runPeriodicCheck` pada cek berkala terakhir itu tidak pernah menerimanya — iterasi berhenti di suspensi tak-terbungkus berikutnya, bukan di titik pembatalan; bungkus itu kini melempar ulang `CancellationException` sebelum catch umumnya — pola yang sama dengan #272/#277 — sedangkan perilaku gagal biasa (kegagalan geofence dari jalur kendaraan tetap ditelan senyap) tidak berubah. Dengan ini inventaris penelanan pembatalan pada kedua belas cek berkala dan jalur perintah tertutup.
- Pendaftaran menu perintah bot tidak lagi mencatat pembatalan sebagai kegagalan registrasi (issue #283, sisa #281 lapis fungsi-bantuan): `registerBotCommands` membungkus seluruh badannya dengan `catch (e: Exception)` tanpa klausa pembatalan, sehingga `CancellationException` dari kedua panggilan jaringan `setMyCommands` tertangkap catch itu lebih dulu — klausa lempar-ulang yang dipasang #281 di pemanggil `maybeRetryBotCommands` tidak pernah terjangkau dari jalur ini, dan pembatalan ikut memasang `commandsRegFailAt` serta log "Command menu registration error" sehingga cooldown coba-ulang 1 jam terpicu oleh pembatalan, bukan oleh kegagalan jaringan; catch terluar fungsi ini kini melempar ulang `CancellationException` sebelum catch umumnya — pola yang sama dengan #272/#277/#281 — sedangkan perilaku kegagalan biasa (termasuk pemasangan `commandsRegFailAt` dan log untuk kegagalan sungguhan) tidak berubah.

## [1.6.535] - 2026-10-29

### Fixed
- Pembangkit laporan perintah `/waspada` dan `/minggu` tidak lagi menelan pembatalan di catch selimut badannya sendiri (issue #282, kelas CE jalur perintah): `sendWaspada` dan `sendWeeklyDigest` membungkus seluruh badannya dengan `catch (_: Exception)` tanpa klausa pembatalan, sehingga `CancellationException` dari pengiriman laporan (`sendToTelegram`, yang melempar ulang pembatalan di lapisannya sendiri) tertelan dan catch dispatch perintah — yang melempar ulang pembatalan — tidak pernah menerimanya dari kedua handler ini; handler berakhir "normal" alih-alih berhenti sebagai batal, dan catch yang sama bahkan mencoba mengirim pesan gagal pada job yang sudah batal. Catch terluar kedua fungsi kini melempar ulang `CancellationException` sebelum catch umumnya — pola yang sama dengan #272/#277 — sedangkan perilaku kegagalan biasa (termasuk pesan "gagal dibuat") tidak berubah.
- Cek berkala pendaftaran ulang perintah bot tidak lagi menelan pembatalan di catch selimut badannya sendiri (issue #281, sisa #271 lapis badan): `maybeRetryBotCommands` adalah satu-satunya cek berkala yang badannya sendiri masih membungkus seluruh badan dengan `catch (_: Exception) { }` tanpa klausa pembatalan sesudah #277, sehingga `CancellationException` dari satu-satunya titik suspensinya — panggilan jaringan `registerBotCommands()` — tertelan, fungsi kembali "normal" ke pembungkus `runPeriodicCheck`, dan sisa cek iterasi itu tetap terpanggil sebelum loop berhenti di suspensi tak-terbungkus berikutnya; catch terluar fungsi ini kini melempar ulang `CancellationException` sebelum catch umumnya — pola yang sama dengan #272/#277 — sedangkan perilaku kegagalan biasa tidak berubah. Dengan ini inventaris penelanan pembatalan lapis badan pada kedua belas cek berkala tertutup.
- Fungsi geofence bersama tidak lagi menelan pembatalan di catch selimut badannya sendiri (issue #280, sisa kelas CE lapis fungsi-bantuan): `checkGeofence` membungkus seluruh badannya dengan `catch (_: Exception) { }` kosong tanpa klausa pembatalan, sehingga `CancellationException` dari kedua pengiriman geofence di dalamnya (peringatan jemput dan peringatan masuk/keluar area, keduanya lewat `sendToTelegram` yang melempar ulang pembatalan di lapisannya sendiri) tertelan sebelum keluar fungsi — rantai lempar-ulang pembatalan terpotong di titik bersama ini untuk banyak pemanggil sekaligus (klausa #272 di `checkGeofenceFallback` tidak pernah menerima pembatalan yang lahir dari pengiriman geofence, dan `checkVehicle`, `patrolRound`, serta handler perintah membungkus panggilan ini dengan catch generik masing-masing); catch terluar `checkGeofence` kini melempar ulang `CancellationException` sebelum catch umumnya — pola yang sama dengan #272/#277/#279 — sedangkan perilaku kegagalan biasa tidak berubah.
- Pembangkit ringkasan harian tidak lagi menelan pembatalan di catch selimut badannya sendiri (issue #279, sisa #277 lapis fungsi-bantuan): `sendDailyDigest` membungkus seluruh badannya dengan `catch (_: Exception)` tanpa klausa pembatalan, sehingga `CancellationException` dari satu-satunya titik suspensi pengirimannya (`sendToTelegram`, yang melempar ulang pembatalan di lapisannya sendiri) mati di catch badan itu — klausa lempar-ulang yang dipasang #277 di pemanggil berkala `maybeSendNightDigest` praktis tidak pernah menerima pembatalan dari rantai suspensi utamanya, dan pada jalur manual (`/digest`) catch yang sama bahkan mencoba mengirim pesan "Ringkasan harian gagal dibuat." pada job yang sudah batal; catch terluar `sendDailyDigest` kini melempar ulang `CancellationException` sebelum catch umumnya — pola yang sama dengan #272/#277 — sedangkan perilaku kegagalan biasa (termasuk pesan gagal untuk jalur manual dan nilai kembalian `false`) tidak berubah.

## [1.6.534] - 2026-10-29

### Fixed
- Pencocokan merek untuk panduan autostart tidak lagi gugur di locale Turki/Azeri (issue #278): `AutoStart.candidates()` sebelumnya menurunkan huruf `Build.MANUFACTURER`/`Build.BRAND` dengan `lowercase()` tanpa Locale, yang mengikuti locale perangkat — di locale Turki/Azeri huruf `I` menjadi `ı` tanpa titik (`"XIAOMI"` → `"xıaomı"`, `"HUAWEI"` → `"huaweı"`) sehingga tidak sama dengan kunci ASCII peta kandidat (`xiaomi`, `huawei`, `vivo`, `iqoo`, `meizu`, `nokia`, `redmi`) dan `open()` jatuh ke halaman info aplikasi generik, mengalahkan panduan autostart khusus merek; kedua pemanggilan kini memakai `lowercase(Locale.ROOT)`. `uppercase()` di `manufacturerLabel()` sengaja tidak diubah karena murni teks label tampilan (kapitalisasi huruf pertama nama merek untuk user) dan tidak dipakai untuk pencocokan apa pun. Sekalian, gerbang `cek_locale_baru` di `tools/verifikasi-path.py` diperluas: baris kode baru berbentuk `lowercase()`/`uppercase()` tanpa argumen kini juga ditolak seperti pola lama `toLowerCase()`/`toUpperCase()` — gerbang ini hanya memindai baris tambah pada diff, jadi pemakaian lama yang bukan baris baru tidak ikut terflag.
- Empat cek berkala tidak lagi menelan pembatalan di catch selimut badannya sendiri (issue #277, sisa #271 lapis badan-fungsi): `maybeSendNightDigest`, `checkQuietHealth`, `checkBegadang`, dan `checkAccessibilityHealth` sejak #271 dipanggil lewat `runPeriodicCheck` yang melempar ulang `CancellationException`, tetapi keempatnya membungkus seluruh badannya dengan `catch (_: Exception) { }` tanpa klausa pembatalan, sehingga pembatalan yang lahir dari titik suspensi di dalam badan (pengiriman Telegram/digest yang melempar ulang pembatalan di lapisannya sendiri) mati di catch badan dan pembungkus loop tidak pernah menerimanya; catch terluar keempat fungsi kini melempar ulang `CancellationException` sebelum catch umumnya — pola yang sama dengan #272 — dan instans sempit di `checkSilentLink` (panggilan `healSilentLink` yang kegagalannya dibaca sebagai `false`) juga meneruskan pembatalan, bukan mengubahnya menjadi "pemulihan gagal". Perilaku kegagalan biasa keempat cek dan `checkSilentLink` tidak berubah, dan loop tetap berhenti di iterasi yang sama seperti sebelumnya — kini berhenti tepat di titik pembatalan.
- Ekor pesan panjang tidak lagi bisa hilang senyap saat pengiriman dibatalkan di jeda antar-potongan (issue #276): cabang `CancellationException` di `sendChunked` (`SendMessageWorker`) sebelumnya membuang nilai kembalian `addMessage` untuk sisa potongan lalu menghapus pesan asal tanpa syarat — satu-satunya cabang yang tidak memeriksa hasil antre-ulang — sehingga bila sisa terusir eviksi antrean jenuh, potongan awal sudah terkirim tetapi ekornya hilang permanen tanpa tercatat gugur di mana pun; hasil antre-ulang kini diperiksa seperti cabang lain (`RateLimited`/`AuthFailed`/`Failed`/`Rejected` dan cabang tenggat): pesan asal hanya dihapus dan pengiriman lanjutan hanya dijadwalkan bila sisa benar-benar terantre sebagai pesan baru, sedangkan bila antre-ulang gagal pesan asal tetap di antrean untuk percobaan berikutnya — lalu pembatalan tetap dilempar ulang seperti sebelumnya.
- Redam 3 jam pengingat setup BootRestartWorker kini bertahan lintas reboot (issue #275): penanda persisten di `boot_meta` sebelumnya menyimpan nilai `elapsedRealtime` — jam monoton yang ter-reset ke nol tiap reboot — dan penjaganya mensyaratkan jam kini tidak lebih kecil dari penanda, sehingga sesudah reboot pemeriksaan persisten selalu terlewati dan pengingat yang baru tampil sesaat sebelum restart bisa tampil lagi semenit sesudah boot berikutnya; penanda persisten kini memakai jam dinding (`currentTimeMillis`) sehingga redam berlaku sama dalam satu boot maupun lintas reboot, sedangkan redam memori dalam satu proses tidak berubah. Jam dinding yang mundur sedikit (penanda sampai 3 jam di masa depan) dianggap redam masih berlaku agar pengingat tidak beruntun, penanda yang jauh di masa depan (jam pernah meloncat liar) diabaikan dan ditimpa ulang, dan penanda lama berbasis `elapsedRealtime` otomatis tertimpa nilai jam dinding pada penayangan berikutnya.


## [1.6.533] - 2026-10-29

### Fixed
- Alert "admin perangkat dimatikan" kini tak bisa hilang senyap saat penyimpanan belum siap (issue #274): di dalam lambda `runReceiver` pada `AdminReceiver.onDisabled`, hanya `refreshInstance` yang terbungkus try, sedangkan `getInstance` beserta pemeriksaan konfigurasi dan pengiriman alert berjalan tanpa pelindung — exception apa pun dari jalur itu (mis. prefs terenkripsi belum bisa dibuka sesaat sesudah boot) lolos ke catch `runReceiver` yang menelan tanpa log, sehingga alert tamper paling kritis aplikasi ini hilang total tanpa jejak di build release; isi lambda sesudah `refreshInstance` kini terbungkus try/catch ber-`Log.e`, sedangkan perilaku sukses (pemeriksaan dan pesan yang dikirim) tidak berubah.
- Pembungkus Boolean yatim pada cek izin liveness dihapus agar kontrak tiga keadaan menjadi satu-satunya jalur (issue #273, sisa desain #269): `Liveness.allowed()` memetakan hasil `allowedCheck()` menjadi Boolean sehingga `READ_FAILED` runtuh menjadi `false` — persis ambiguitas "gagal membaca prefs tak terbedakan dari tidak diizinkan" yang dihapus #269 — tetapi fungsi itu tidak mempunyai satu pun pemanggil (`handleWakeUp` sudah memakai `allowedCheck()` dengan `when` tiga keadaan); pembungkusnya kini dihapus, sehingga pemanggil masa depan tidak bisa menghidupkan kembali bug #269 lewat jalur yang tampak resmi.
- Lempar-ulang pembatalan di empat pemanggil `fetchLocation` kini benar-benar keluar dari fungsinya (issue #272, sisa #261 lapis fungsi): `checkGeofenceFallback`, `checkCurfew`, `handleAdminAlert`, dan `handleUnlockFailed` sejak #261 melempar ulang `CancellationException` dari catch dalamnya, tetapi catch selimut `catch (_: Exception) { }` yang membungkus seluruh badan keempat fungsi itu menelannya kembali (CE adalah subclass `Exception`), sehingga fungsi berakhir "normal" dan — untuk dua cek berkala — pembungkus `runPeriodicCheck` dari #265/#271 tidak pernah menerima pembatalan itu; catch terluar keempat fungsi kini melempar ulang `CancellationException` sebelum catch umumnya, sedangkan perilaku kegagalan biasa (lokasi gagal menghasilkan `null` dan alur fallback yang sama) tidak berubah.
- Pembungkus anti-telan pembatalan kini berlaku untuk semua cek berkala di loop pemantau (issue #271, sisa #265): `runPeriodicCheck` sebelumnya hanya membungkus `checkCurfew()` dan `checkGeofenceFallback()`, sedangkan sepuluh cek lain (`maybeSendHeartbeat`, `checkBatteryAlert`, `maybeSendDigest`, `maybeSendNightDigest`, `maybeRetryBotCommands`, `checkBegadang`, `checkQuietHealth`, `checkAccessibilityHealth`, `checkSilentLink`, `checkVehicle`) tetap memakai pembungkus inline `try { … } catch (_: Exception) { }` yang menelan `CancellationException` dari titik suspensi masing-masing cek, sehingga coroutine yang sudah dibatalkan tetap menjalankan sisa cek dalam iterasi yang sama sampai titik suspensi tak terbungkus berikutnya; kesepuluh cek itu kini dipanggil lewat `runPeriodicCheck` yang sama — exception biasa tetap tertelan senyap seperti sebelumnya, sedangkan pembatalan berhenti seketika di titiknya. Catatan: cek yang juga menelan `CancellationException` di catch internal badannya sendiri (mis. `maybeRetryBotCommands`) tidak berubah oleh langkah ini, karena penelanan itu terjadi di dalam badan cek sebelum pembungkus loop terlibat.


## [1.6.532] - 2026-10-29

### Fixed
- Logika titik awal hari untuk pemecah sesi tengah malam A11y kini terkunci uji JVM (issue #270): `dayStartWallMillis` sebelumnya adalah metode `private` di dalam `A11yMonitor` tanpa satu pun uji, padahal hanya memakai `java.util.Calendar`; fungsi itu kini dipindahkan apa adanya ke `A11yUsageMath` (pola ekstraksi #214/#242, tanpa perubahan perilaku — blok rollover memanggil fungsi yang sama) dan `A11yUsageMathTest` menambah uji kasus tepi berzona UTC: siang biasa, akhir hari, tepat tengah malam, satu milidetik sebelum/sesudah tengah malam, epoch nol, dan nilai negatif.
- Kegagalan baca sesaat pada cek izin liveness tidak lagi membatalkan rantai alarm secara permanen (issue #269): `allowed()` sebelumnya menelan semua exception dari getter prefs menjadi `false` yang tak terbedakan dari "monitoring memang tidak diizinkan", dan `handleWakeUp()` menanggapinya dengan membatalkan alarm liveness tanpa jadwal pengganti — justru saat service mati dan alarm itu satu-satunya penghidup berkala; cek kini tiga keadaan (`allowedCheck`): hasil "tidak diizinkan" yang sah tetap membatalkan alarm seperti sebelumnya, sedangkan kegagalan baca menjadwalkan ulang alarm dan kembali (sama seperti cabang gagal refresh/getInstance), dengan `Log.e` dari #259 tetap tercatat di titik gagalnya.
- Bendera peredam log kegagalan restore kini ikut direset saat keluar dari mode volatile lewat reset penyimpanan (issue #268, sisa #256): `restoreFailLogged` sebelumnya hanya direset oleh keberhasilan `tryRestorePersistent`, sehingga bila perangkat keluar dari mode volatile lewat `reopenAfterStorageReset` yang berhasil, episode volatile berikutnya bisa berjalan tanpa satu pun `Log.e` kegagalan restore; jalur sukses buka ulang itu kini mereset bendera yang sama, sehingga kegagalan pertama tiap episode selalu berjejak seperti maksud #246/#256.
- `clearQueue()` kini ikut mereset pelacak penghitung drop yang sudah terpersist (issue #267, sisa #254): pembersihan sebelumnya menolkan penghitung memori dan kunci drop di disk tetapi membiarkan pelacak `persisted*Drops` memegang total lama, sehingga drop sah yang tercatat sesudah pembersihan tetapi belum terpersist bisa terpotong habis oleh penyesuaian flip volatile berikutnya (`memori − pelacak lama` terjepit ke nol) dan hilang dari laporan; pelacak kini dinolkan bersamaan dengan penghitung dan kunci disk.
- Restore antrean tidak lagi menghitung ganda penghitung drop bila antrean tersimpan memuat pesan kedaluwarsa (issue #266, sisa #254): `readLocked()` di dalam `tryRestorePersistent` sebelumnya mencatat kedaluwarsa dengan persist aktif sebelum riwayat disk dimuat, sehingga total disk lama tertimpa penghitung memori masa volatile lalu ditambahkan kembali oleh `loadDropCountsLocked(add = true)`; pencatatan drop selama fase baca restore kini hanya menandai kotor tanpa mempersist — persist tunggal sesudah pemuatan riwayat yang menulis total gabungan yang benar — sedangkan perilaku `readLocked()` di jalur normal tidak berubah.
- Pembatalan coroutine dari cek berkala kini berhenti seketika di loop pemantau (issue #265, sisa #261): `checkCurfew()` dan `checkGeofenceFallback()` melempar ulang `CancellationException` dari `fetchLocation()` sejak #261, tetapi pembungkus selimut `try { … } catch (_: Exception) { }` per-cek di `startPeriodicLoops()` menelannya karena CE adalah subclass `Exception`, sehingga sisa siklus tetap berjalan sampai titik suspensi tak terbungkus berikutnya; kedua cek itu kini dipanggil lewat pembungkus yang melempar ulang CE sebelum catch umum, sedangkan penelanan exception biasa untuk kedua cek itu tidak berubah.


## [1.6.531] - 2026-10-29

### Fixed
- Cache label/launchable `A11yMonitor` tidak lagi menyimpan hasil kegagalan PackageManager dan kini diinvalidasi saat paket berubah (issue #264): `isLaunchable` dan `labelFor` sebelumnya menulis hasil fallback dari catch exception (`false`/nama paket) ke cache permanen, dan `PackageChangeReceiver` hanya membersihkan cache milik `MonitoringService`; kini hanya hasil resolusi yang benar-benar berhasil yang disimpan ke cache (fallback tetap dipakai untuk panggilan itu), dan receiver juga memanggil `A11yMonitor.invalidatePackageCache(pkg)` untuk setiap perubahan paket, termasuk penggantian/update.
- Sesi pemakaian A11y yang melewati tengah malam kini dipecah di batas hari (issue #263): rollover sebelumnya menyelesaikan seluruh sesi ke peta usage hari lama lebih dulu, sehingga porsi sesudah pukul 00:00 ikut terhitung sebagai kemarin dan hari baru kekurangan porsi yang sama; saat rollover kini sesi diakru hanya sampai tengah malam ke hari lama, disimpan, lalu sesi paket yang sama dilanjutkan mulai tengah malam ke peta hari baru — sesi yang tidak melewati tengah malam dan jalur muat pertama tidak berubah.
- Summary grup notifikasi tidak lagi tertelan pada 2 menit pertama sesudah boot (issue #262): pemeriksaan penekanan summary sebelumnya memakai `seenAt = 0L` untuk grup yang belum pernah terlihat pada proses ini, dan karena `now` adalah `elapsedRealtime()` yang juga mulai dari nol saat boot, summary pertama selalu dinilai "baru saja terlihat" dan dibuang ke riwayat; kunci yang absen kini dibedakan dari yang benar-benar terlihat — grup yang belum pernah terlihat diteruskan normal, sedangkan ambang 120 detik tetap berlaku untuk grup yang anaknya benar-benar baru terlihat.
- Tujuh pemanggil `fetchLocation` kini melempar ulang `CancellationException` sebelum catch umumnya (issue #261, lanjutan #250): bungkus `catch (_: Exception) { null }` di jalur patroli, hilang, darurat, geofence fallback, curfew, peringatan admin, dan gagal buka kunci sebelumnya menelan pembatalan coroutine yang sudah diteruskan `fetchLocation`, sehingga coroutine yang dibatalkan melanjutkan sisa alur "lokasi gagal" sampai titik suspensi berikutnya; kini pembatalan berhenti segera di pemanggil, sedangkan kegagalan lokasi biasa tetap menghasilkan `null` dan alur fallback yang sama seperti sebelumnya.
- Perintah `/pantauapp` tidak lagi membayar dua pindai launcher penuh per token nama selama PackageManager gagal menetap (issue #260, sisa #249): hasil pindai bersama yang `null` sebelumnya tak terbedakan dari "belum memindai", sehingga `resolveAppPackage` memindai sekali lagi di dalam dirinya untuk token yang sama; token kini dinilai terhadap daftar kosong bila pindai yang baru saja dicoba gagal — tanpa memindai ulang — sedangkan snapshot bersama tetap `null` sehingga token berikutnya tetap mencoba memindai lagi seperti maksud #249, dan hasil sukses/kosong sungguhan tidak berubah.
- Jalur kegagalan terancang `Liveness.handleWakeUp` kini berjejak di log release (issue #259, sisa #246): kegagalan refresh/getInstance prefs, kegagalan exception di dalam cek `allowed()`, dan penolakan start foreground service sebelumnya semuanya diubah menjadi `return`/`false` + fallback internal tanpa log, sehingga `Log.e` di `TimeChangeReceiver` praktis tak terjangkau; ketiga titik telan itu kini menulis `Log.e` masing-masing (satu log per sebab per pemanggilan), sedangkan hasil `allowed()` yang memang `false` secara sah tetap senyap dan alur fallback-nya tidak berubah.


## [1.6.530] - 2026-10-29

### Fixed
- Kegagalan start monitoring dari `MainActivity` kini terlog di build release (issue #258, sisa #246): catch di dalam `startMonitoringInBackground` yang menelan penolakan start foreground service sebelumnya hanya mencatat bila build DEBUG, dan catch terluar fungsi itu senyap, sehingga `Log.e` pemanggil tidak pernah terpicu; kedua titik telan itu kini menulis `Log.e` (titik start menyertakan exception-nya), sedangkan alur pemulihan `scheduleBootRestart()` tidak berubah.
- Kegagalan upgrade penyimpanan saat startup `MainActivity` kini terlog di build release (issue #257, sisa #246): `refreshInstance` melaporkan kegagalannya lewat nilai balik `false`, bukan exception, sehingga `Log.e` pemanggil sebelumnya tidak pernah terpicu; pemanggil kini memeriksa nilai balik itu dan menulis `Log.e` bila upgrade ke penyimpanan terenkripsi tidak selesai, sementara jalur exception tetap berjejak seperti sebelumnya.
- `Log.e` kegagalan restore `MessageQueue` kini diredam per episode volatile (issue #256, sisa #246): stack trace lengkap hanya ditulis pada kegagalan pertama sejak antrean masuk mode volatile, dan bendera peredam disetel ulang sesudah restore berhasil, sehingga gangguan penyimpanan yang panjang tidak lagi memenuhi logcat dengan satu stack trace yang sama untuk setiap panggilan API antrean — jejak kegagalan pertama tetap ada untuk diagnosis.
- `ensureOwnerPairCode()` tidak lagi mengembalikan kode lama yang sudah tidak sah bila `commit()` gagal dengan nilai `false` tanpa exception (issue #255, sisa #245): hasil Boolean commit kini diperiksa mengikuti pola `ensureRestoreCodeSecret` di berkas yang sama — commit yang tidak tersimpan (false maupun exception) membuat fungsi mengembalikan string kosong, sehingga Setup tidak lagi menampilkan kode kedaluwarsa seolah kode baru yang akan ditolak diam-diam saat verifikasi pairing.
- Flip volatile `MessageQueue` tidak lagi menghapus kenaikan penghitung drop yang belum terpersist (issue #254, sisa #244): nilai terakhir yang terbukti tersimpan di disk kini dilacak terpisah, dan saat masuk mode volatile — baik dari kegagalan baca maupun dari kegagalan buka ulang sesudah reset penyimpanan — penghitung hanya dikurangi sebesar porsi yang sudah tercermin di disk, sehingga perlindungan anti-hitung-ganda #244 tetap utuh tetapi kenaikan yang hanya ada di memori bertahan dan ikut terlaporkan; laporan selama jendela volatile kini membaca kenaikan itu, bukan selalu nol.
- Pengampunan umur mutasi untuk perintah kontrol yang menyelesaikan tunggu kesopanan kini dibatasi sebesar durasi tunggu aktual (issue #253, sisa #247): stempel segar sebelumnya menggantikan tanggal pesan sepenuhnya, sehingga keterlambatan pengiriman sebelum dispatch ikut terhapus sampai batas gerbang umum 900 detik; titik ukur kini digeser hanya sebanyak detik yang benar-benar dihabiskan menunggu gerbang (tanggal pesan + durasi tunggu, dibatasi waktu kini), sehingga umur pra-tunggu tetap dihitung seperti biasa dan jalur adopsi login #231 tidak berubah.


## [1.6.529] - 2026-10-29

### Fixed
- Label aplikasi kosong dari `usageRowsBetween` tidak lagi masuk `appLabelCache` apa adanya (issue #248, sisa #237): label hasil resolusi kini dinormalkan menjadi nama paket dengan `.ifEmpty { pkg }` sebelum ditulis ke cache, sama seperti `appLabelFor`, sehingga ringkasan pemakaian dan pembaca cache lain tidak lagi menerima nama aplikasi kosong.
- Snapshot launcher yang gagal dipindai sesaat tidak lagi terkunci sebagai daftar kosong untuk seluruh perintah `/pantauapp` (issue #249, sisa #238): `launchableAppPairs()` kini mengembalikan `null` khusus untuk kegagalan query, berbeda dari daftar kosong yang benar-benar sah; pemanggil membiarkan snapshot bersama tetap `null` sehingga token nama berikutnya mencoba memindai lagi, sedangkan hasil kosong sungguhan tetap dipakai ulang seperti sebelumnya.
- `CancellationException` pada `fetchLocation` tidak lagi ditelan oleh catch umum dan diubah menjadi lokasi basi (issue #250, sisa #239): penantian timeout dan kedua catch lapisan luar kini melempar ulang `CancellationException` mengikuti pola coroutine standar, sehingga pembatalan pemanggil benar-benar menghentikan rantai lokasi; kegagalan biasa tetap jatuh ke fallback `allowStale` seperti sebelumnya.
- Logika pemindaian aplikasi launcher yang terduplikasi kini disatukan ke satu fungsi inti (issue #251): `scanLaunchableAppPairs()` menjadi satu-satunya tempat yang membangun intent launcher, memilih cabang `ResolveInfoFlags` menurut SDK, memetakan label→paket, membuang duplikat paket, dan mengurutkan; `launchableAppPairs()` memakai hasilnya langsung (termasuk semantik `null` saat gagal dari #249), sedangkan `listLaunchableAppsFresh()` hanya menurunkan daftar label dari pasangan yang sama dan tetap mengembalikan daftar kosong saat gagal. Kesetaraan kedua bentuk keluaran dibuktikan dari pembacaan kode lama/baru dan simulasi urutan dedup-sort yang sama.
- Gerbang `tools/verifikasi-path.py` tidak lagi fail-open bila berkas strings wajib hilang (issue #252): helper baca kini membedakan `None` untuk berkas yang tidak bisa dibuka dari string kosong untuk berkas yang memang kosong, pembaca umum memakai pembungkus teks yang mempertahankan perilaku lama, dan kedua cek terjemahan/paritas memakai daftar strings wajib yang sama—`values/strings.xml` atau `values-in/strings.xml` yang hilang kini menghasilkan `GAGAL` dan exit 1. Terbukti pada salinan fixture: penghapusan masing-masing berkas membuat gerbang gagal, sedangkan pohon utuh dan salinan yang dipulihkan tetap lolos.

## [1.6.528] - 2026-10-29

### Fixed
- Pemulihan antrean korup tidak lagi menimpa cadangan baik dengan blob korup itu sendiri (issue #243): penulisan hasil pemulihan kini mengganti isi utama dan `BAK` dengan JSON hasil pemulihan yang sama, sedangkan rotasi cadangan pada jalur tulis biasa tidak berubah.
- Penghitung drop tidak lagi terhitung ganda sesudah kegagalan baca yang mengubah antrean menjadi volatile (issue #244): penghitung yang sudah tercermin dari disk di-nol-kan tepat saat flip volatile, sehingga restore yang kemudian memuat nilai disk dengan penjumlahan hanya menambahkan drop yang benar-benar terjadi selama mode volatile; jalan masuk volatile dari kegagalan inisialisasi tetap memakai restore penjumlahan seperti sebelumnya.
- `ensureOwnerPairCode()` tidak lagi mengembalikan kode pairing yang belum tersimpan bila `commit()` melempar (issue #245): jalur exception kini diperlakukan sama seperti commit yang gagal—fungsi membaca ulang nilai dari penyimpanan dan mengembalikan string kosong bila tidak ada kode tersimpan yang bisa dibaca, bukan kode baru yang tak akan pernah cocok saat verifikasi.
- Kegagalan kritis di dalam blok `runCritical` kini berjejak di lapis pemanggil, bukan hanya di `Background` (issue #246, sisa #241): restore `MessageQueue` (init, `ensureRestored`, dan badan restore itu sendiri), `refreshInstance` `MonitoringService` saat `onCreate`/`onStartCommand`, tiga langkah startup `MainActivity`, antrean laporan kebangkitan `Liveness`, dan `handleWakeUp` dari `TimeChangeReceiver` sekarang menulis `Log.e` pada catch internalnya. Catch bawahan yang memang best-effort sesudah pekerjaan utama (penjadwalan kirim dan penulisan penanda liveness) tetap senyap agar log tidak dibanjiri exception lanjutan yang rutin.
- Perintah kontrol owner yang benar-benar menunggu di gerbang kesopanan kini menerima stempel umur segar yang sama seperti penunggu adopsi (issue #247, sisa #235/#231): sesudah tunggu kesopanan selesai atau tenggatnya dicapai, `adoptionDoneSec` disetel hanya bila pengirim memang owner yang sah dan masih owner saat diperiksa ulang, sehingga umur mutasi dihitung dari selesainya antrean buatan aplikasi sendiri; perintah yang tidak menunggu dan pengirim lain tidak mendapat kelonggaran apa pun.
## [1.6.527] - 2026-10-29

### Fixed
- Predikat keputusan restore `RingVolume` diekstrak menjadi fungsi murni dan terkunci uji JVM (issue #242): keputusan memulihkan volume dering sebelumnya hidup menyatu dengan `AudioManager` di `restoreStuck`, sehingga cabang tepinya — jam mundur membuat `savedAt` seolah di masa depan (umur negatif gugur dari rentang 0..12 jam dan hasil akhir bergantung klausul volume-kini) — tak terjangkau uji dan bisa bergeser diam-diam pada refactor berikutnya, kelas risiko yang sama yang ditutup untuk kalkulator lewat `CalculatorState` (#214). Predikat itu kini `RingVolume.isRestorable(previous, savedAt, now, currentVolume, maxVolume)` tanpa sentuhan Android, `restoreStuck` memanggilnya dengan nilai yang sama seperti sebelumnya (perilaku tidak berubah), dan `RingVolumeTest` mengunci 16 kombinasi termasuk umur tepat batas 12 jam, umur negatif karena jam mundur, dan klausul volume-kini yang menuntut `maxVolume` positif — nilai harapan dibuktikan lebih dulu lewat porting Python 1:1 dari predikat asal.
- Catch internal `Background.runCritical` kini mencatat log (issue #241, sisa kelas #195/#211): lapis bersama itu sebelumnya menelan semua exception dari blok pemanggilnya tanpa satu baris log pun, padahal 9 situs pemanggilnya (inisialisasi/restore `ParentalMonitorApp`, `MessageQueue`, `AdminReceiver`, `TimeChangeReceiver`, `MonitoringService`, `MainActivity`, `Liveness`) menjalankan pekerjaan kritis yang kegagalannya tak terdiagnosis di lapangan; perbaikan kelas ini pada putaran sebelumnya baru menutup titik panggil di `ParentalMonitorApp`. Catch itu kini menulis `Log.e` (nama kelas exception + pesan + tumpukan) di satu titik bersama yang menutup semua pemanggil. Kesembilan blok pemanggil sudah diperiksa: semuanya blok inisialisasi/restore yang exception lolosnya adalah kegagalan sungguhan, tidak ada pemanggil yang sengaja mengandalkan kesenyapan `runCritical` untuk exception yang memang diharapkan — perilaku selain penambahan log ini tidak berubah.
- Agregasi catatan gugur `NotificationForwarderService` tidak lagi putus oleh catatan asli yang menyela (issue #240, sisa #228): `recordDrop()` sebelumnya hanya menyatukan gugur baru bila entri terakhir riwayat persis entri ringkasan `(gugur)`, jadi pada banjir campuran (gugur, gugur, notif asli tercatat, gugur, gugur) lahir dua entri ringkasan pecahan berisi hitungan kecil yang mengecilkan total gugur pada jendela yang sama dan memenuhi riwayat `/lastnotif`. Penyatuan kini berbasis jendela waktu seperti sebelumnya (60 detik dari gugur terakhir) tetapi entri ringkasannya dicari di seluruh riwayat lewat penanda aplikasinya, lalu entri itu diperbarui hitungannya dan dipindahkan ke ekor — satu jendela banjir campuran kini menghasilkan tepat satu entri ringkasan dengan total yang benar.
- `CancellationSignal` permintaan lokasi API 30+ kini terhubung ke nasib Job coroutine penunggu (issue #239): pembatalan sinyal sebelumnya hanya digantungkan pada `invokeOnCompletion` deferred hasil lokasi, padahal pembatalan coroutine penunggu (timeout 20 detik induk, atau pembatalan tugas eksplisit) tidak pernah menyelesaikan deferred itu — `signal.cancel()` tidak pernah dipanggil dan permintaan `getCurrentLocation` tetap terdaftar di `LocationManager` lama sesudah pemanggil menyerah, bisa membangunkan callback ke proses yang sudah tidak menunggu. Sinyal kini juga dibatalkan di blok `finally` penantian hasil, yang berjalan pada semua jalan keluar penantian: selesai normal, exception, dan pembatalan Job oleh timeout/pembatal `coroutineScope` — berdampingan dengan `removeUpdates` jalur fallback yang sudah ada di `finally` yang sama.

## [1.6.526] - 2026-10-29

### Fixed
- Daftar aplikasi launcher kini dipindai sekali per perintah `/pantauapp` untuk semua token nama (issue #238): `resolveAppPackage` sebelumnya memanggil `launchableAppPairs()` — kueri seluruh aplikasi launcher + `loadLabel` per aplikasi + sortir — secara segar untuk setiap token nama, jadi perintah tambah/hapus dengan 5 nama membayar 5 pindaian penuh yang identik. `updateAppWatchPackages` kini menghitung daftar pasangan itu sekali secara malas (hanya bila ada token nama non-paket) dan meneruskannya ke `resolveAppPackage` untuk semua token berikutnya; token berbentuk nama paket tidak menyentuh pindaian sama sekali, dan pemanggil lain `resolveAppPackage` mempertahankan perilaku lama lewat parameter bawaan kosong.
- `usageRowsBetween` tidak lagi meng-cache hasil fallback resolusi label yang gagal (issue #237, sisa #226): jalur baris pemakaian sebelumnya tetap menulis cache walau `getApplicationLabel` melempar exception dan label jatuh ke nama paket mentah, sehingga kegagalan PackageManager sesaat membekukan label mentah itu sebagai entri otoritatif — termasuk bagi `appLabelFor`, yang sendiri sengaja tidak meng-cache kegagalan. Penulisan cache kini hanya terjadi bila resolusi label benar-benar berhasil, persis seperti `appLabelFor`; baris pemakaian untuk panggilan itu tetap memakai nama paket sebagai label tampil sementara, tetapi panggilan berikutnya mencoba resolusi ulang dan pulih begitu PackageManager sehat.
- `appLabelCache` `MonitoringService` kini diinvalidasi saat paket berubah (issue #236, sisa #226): cache label + status launchable sebelumnya hanya dibersihkan menyeluruh bila melewati 300 entri, jadi aplikasi yang berganti nama lewat update, di-uninstall, atau dipasang ulang terus dilaporkan dengan label/status lama selama umur service. Cache itu kini milik bersama proses (companion) dengan satu titik invalidasi `invalidateAppLabel(pkg)`, dan `PackageChangeReceiver` memanggilnya untuk setiap perubahan paket yang diterimanya — termasuk siaran penggantian (update) yang selama ini dilewati receiver lebih awal untuk keperluan pemberitahuan — sehingga entri paket terkait dibuang dan dibaca ulang segar pada pemakaian berikutnya. Jalur panas pemakai cache tidak berubah dan tidak membayar apa pun tambahan.
- Perintah kontrol owner baru tidak lagi menyalip penunggu adopsi lama dari pengirim yang sama (issue #235, sisa #224): gerbang giliran adopsi sebelumnya hanya mengurutkan sesama penunggu — perintah kontrol dari pengirim yang sudah owner (loginnya baru saja selesai) melewati gerbang sama sekali dan langsung mengambil kunci, sehingga dalam sisa putaran `delay(50)` terakhir penunggu, pasangan seperti `/lock` lama lalu `/unlock` baru bisa tereksekusi terbalik. Pendaftaran penunggu kini menyimpan pasangan nomor update + pengirim, dan perintah kontrol yang bukan penunggu memeriksa gerbang sebelum mengambil kunci: selama masih ada penunggu terdaftar dari pengirim yang sama dengan nomor update lebih kecil, ia menunggu tanpa memegang kunci apa pun (putaran 50 ms yang sama, di bawah tenggat adopsi yang sama; lewat tenggat ia lanjut seperti sebelumnya dengan catatan log, jadi tidak ada penahanan baru tanpa batas dan tidak ada jalan buntu — penunggu tetap bisa mengambil kuncinya dan melepas pendaftaran di blok `finally`-nya). Perintah dari pengirim lain dan perintah non-kontrol tidak terpengaruh.
- Teks penolakan tenggat penunggu adopsi kini membedakan penyebabnya (issue #234, sisa #224): pesan "login belum selesai terlalu lama, pastikan /login berhasil" sebelumnya dikirim untuk semua kehabisan tenggat loop tunggu adopsi, padahal tenggat juga bisa habis karena penunggu mengantre di belakang eksekusi sesama penunggu sesudah login lama berhasil — owner yang salah didiagnosis itu terdorong mengirim `/login` lagi yang justru menambah beban antrean. Penolakan kini memeriksa keadaan owner saat tenggat habis: bila pengirim sudah owner, teks menyatakan login sebenarnya sudah berhasil dan yang lama adalah antrean perintahnya; teks lama hanya dipakai bila login memang belum berhasil.
- `join()` perintah non-kontrol ke job login kini bertenggat (issue #233, sisa rantai #223): sebelumnya perintah non-kontrol yang mengikuti `/login` menunggu job login selesai tanpa batas — fase tunggu kunci login bisa 900 detik ditambah seluruh I/O `handleOwnerLogin` — sehingga job dispatch menggantung belasan menit untuk perintah yang hampir pasti gugur juga di gerbang umur, dan offset update tertahan selama itu. Penantian kini dibatasi `withTimeoutOrNull(ADOPTION_WAIT_DEADLINE_MS)`, tenggat yang sama dengan rantai adopsi perintah kontrol: lewat tenggat, perintah diperlakukan seperti login yang tak selesai — ditolak berjejak (log + pemberitahuan ke pengirim), tidak dieksekusi, dan job dispatch selesai normal sehingga offset update tuntas dipersistensikan.
- Satu kegagalan login kini menghasilkan tepat satu pemberitahuan penolakan (issue #232, sisa #223): sebelumnya, bila job login gugur oleh tenggat kuncinya, jalur login mengirim pemberitahuan penolakannya sendiri, tetapi perintah pengikut (kontrol lewat loop adopsi, dan perintah mutasi/sensitif non-kontrol lewat `join()`) tetap lanjut ke gerbang owner/umur di dalam kunci dan ditolak sekali lagi di sana — owner menerima dua pesan penolakan untuk kegagalan yang sama dan bisa mengira perintahnya yang gagal, bukan loginnya. Pengikut kini memeriksa hasil login yang ditunggunya: bila job login terbukti selesai tanpa mengadopsi pengirim (pengirim tetap bukan owner), perintah dilewati diam-diam dengan catatan log saja, karena kegagalan itu sudah diberitahukan oleh jalur login. Pengikut yang loginnya berhasil tidak berubah, dan penolakan oleh tenggat tunggu pengikut sendiri (login masih berjalan) tetap diberitahukan seperti sebelumnya.

## [1.6.525] - 2026-10-29

### Fixed
- Anggaran umur mutasi 300 detik untuk perintah yang menunggu adopsi login kini dihitung dari selesainya adopsi, bukan dari tanggal pesan (issue #231): gerbang umur di `handleTelegramCommandInner` sebelumnya menghitung umur perintah mutasi dari `messageDate` asli, padahal loop tunggu adopsi (#215/#224) memang sengaja menunda eksekusi perintah kontrol pengikut sampai login selesai — login yang berhasil tetapi lambat (I/O jaringan `handleOwnerLogin`, ditambah antrean sesama penunggu) mudah menghabiskan seluruh anggaran 300 detik, sehingga perintah seperti `/lock` ditolak "kedaluwarsa" tepat sesudah lolos adopsi, pada momen yang paling butuh cepat. Jalur dispatch kini mencatat waktu selesainya adopsi (`adoptionDoneSec`) hanya bila pengirim terbukti mengikuti job login yang sama (`followsLogin` atas snapshot job login) DAN login itu terbukti berhasil (job selesai dan pengirim sudah owner saat penantian berakhir); umur mutasi untuk perintah itu dihitung dari titik tersebut. Perintah yang bukan pengikut login tidak pernah menerima nilai ini (parameter bawaan 0, jalur callback/ping tidak berubah) sehingga tetap dinilai dari tanggal pesan persis seperti sebelumnya, dan pengikut yang loginnya gagal tidak menjadi owner sehingga tidak menerima nilai ini dan tetap tertolak oleh gerbang yang sama seperti sebelumnya.

## [1.6.524] - 2026-10-29

### Fixed
- Perlakuan `prefs == null` di dua jalur persist `A11yMonitor` kini sama (issue #230, sisa #219): jalur snapshot `writePersistSnapshot` sebelumnya mencatat `prefs == null` sebagai kegagalan persist (`notePersistFailure()` menaikkan rentetan backoff sampai maksimum 320 detik), sedangkan `persistLocked` pulang diam — padahal `prefs` di berkas ini hanya diisi lewat `attach()`/`getInstance` yang dicoba ulang secara malas (baris 98–111 dan 297), jadi null berarti penyimpanan terenkripsi belum siap, bukan percobaan tulis yang gagal. Selama jendela belum-siap itu, backoff semu bisa terakumulasi dan persist non-paksa pertama sesudah prefs pulih ikut tertahan tanpa alasan tulis yang nyata. Jalur snapshot kini pulang diam persis seperti `persistLocked`; rentetan backoff hanya naik oleh kegagalan `setA11yUsage` yang sungguhan.
- Pencatatan notifikasi gugur tidak lagi menjalankan `commit()` prefs sinkron di thread listener (issue #229, sisa #220): `noteDroppedNotification()` sebelumnya menulis hitungan persisten secara sinkron pada thread pemanggilnya — untuk pintu pertama pemanggilnya coroutine latar, tetapi pintu kedua memanggilnya langsung dari jalur sinkron `onNotificationPosted`, jadi banjir notifikasi gugur berarti puluhan commit terenkripsi per detik di thread listener notifikasi. Penulisan persisten kini dikerjakan job latar tergabung (`dropPersistJob`, dijaga `dropNoticeLock` yang sama): job menulis snapshot hitungan terbaru, berputar sekali lagi hanya bila hitungan berubah selama ia berjalan, lalu berhenti dan membersihkan dirinya di bawah kunci yang sama sehingga tidak ada gugur yang terlewat tanpa job penerus. Kompromi yang disadari: bila proses mati mendadak tepat di tengah banjir, gugur yang tiba sesudah commit latar terakhir yang sempat selesai bisa belum terpersistensikan (jendela sebesar satu lintasan commit di thread latar, bukan lagi nol seperti jalur sinkron pintu kedua) — hitungan di memori, pemberitahuan terthrottle 30 detik, persist akhir `flushDropNotice`, dan persist `onDestroy` tidak berubah, dan penyeedingan ulang saat service hidup kembali tetap membaca nilai persisten terakhir.
- Catatan gugur pintu kedua `NotificationForwarderService` kini diringkas berkala, tidak lagi menggerus riwayat `/lastnotif` (issue #228, sisa #220): sebelumnya setiap notifikasi yang gugur di pintu kedua menambahkan satu entri kosong ke riwayat tampil yang hanya menampung 20 entri, jadi banjir ≥20 notifikasi gugur menghapus seluruh riwayat asli dan `/lastnotif` tinggal menampilkan baris gugur berulang. Entri gugur kini diagregasi oleh `recordDrop()`: gugur pertama membuka satu entri ringkasan, dan gugur-gugur berikutnya dalam jendela 60 detik dari gugur terakhir hanya memperbarui hitungan pada entri yang sama — satu banjir berapapun besarnya menghabiskan tepat satu slot riwayat, dan owner tetap bisa melihat berapa notifikasi yang gugur. Catatan pintu pertama (dengan judul/isi asli) tidak berubah.
- Tugas susulan penyegaran BAK `MessageQueue` kini punya bendera penggabungan (issue #227, sisa #217): sebelumnya setiap episode BAK-tertinggal dari jalur baca main thread mengantrekan satu tugas executor baru, dan tugas yang redundan tetap membayar dua pembacaan blob penuh (dekripsi) sambil memegang `persistMutex` di thread persist tunggal — menunda persist asli yang menunggu kunci yang sama. Tugas susulan kini dijaga `bakSyncQueued` dengan pola yang sama seperti `persistQueued` pada `schedulePersistLocked`: episode-episode yang tumpang tindih berbagi satu tugas, karena tugas itu membaca ulang blob utama yang hidup saat ia berjalan sehingga satu lintasan cukup menyamakan BAK untuk semuanya. Bendera dilepas saat tugas selesai atau gagal diantrekan, jadi episode berikutnya tetap bisa menjadwalkan tugas baru.

## [1.6.523] - 2026-10-29

### Fixed
- `appLabelFor` kini memakai `appLabelCache` yang sudah ada (issue #226): sebelumnya setiap panggilan menembak PackageManager (`getApplicationInfo` + `getApplicationLabel`, dua panggilan IPC) padahal berkas yang sama sudah memelihara cache label yang dipakai jalur `usageRowsBetween` — dan pemanggil `appLabelFor` berbentuk loop atas banyak paket (daftar pantau, token pantau, ringkasan aplikasi paling sering dibuka), jadi satu perintah bisa memicu puluhan IPC berulang untuk paket yang sama antarperintah. Fungsi itu kini membaca cache lebih dulu dan mengisinya dengan cara yang persis sama seperti jalur usage (termasuk bendera launchable yang dihitung dengan cara yang sama dan penjaga ukuran cache yang sama), sehingga kedua jalur berbagi entri yang konsisten. Kesegaran label sama seperti jalur usage yang sudah berjalan: label boleh basi sesaat sampai cache dibersihkan penjaga ukurannya — tidak ada mekanisme invalidasi baru.
- Timeout unggah video hasil rekaman kini konstanta bernama sendiri (issue #225, sisa kelas #221): batas unggah per berkas video yang baru direkam sebelumnya masih literal telanjang `600_000L` — satu-satunya sisa kelas #221 di jalur kirim media — padahal nilainya yang sama-sama 600 detik bermakna beda dari tenggat flush `MEDIA_FLUSH_DEADLINE_MS` maupun tenggat wake lock `MEDIA_UPLOAD_GRACE_MS`. Literal itu kini `VIDEO_UPLOAD_TIMEOUT_MS` di blok konstanta media, sejajar dengan saudara audionya `AUDIO_UPLOAD_TIMEOUT_MS`; nilai dan perilaku tidak berubah.
- Urutan dua perintah kontrol berurutan sesudah adopsi login kini dijamin nomor update (issue #224): perintah kontrol pra-adopsi yang mengikuti `/login` sebelumnya sama-sama lepas dari loop tunggu adopsi oleh perubahan keadaan yang sama lalu berlomba merebut `controlLock`, sehingga pasangan bermakna seperti `/pause` lalu `/resume` bisa tereksekusi terbalik dari niat owner. Perintah yang menunggu adopsi kini mendaftar ke gerbang giliran bersama (`adoptionWaiters`, terurut nomor update): sesudah adopsi selesai, hanya penunggu bernomor terkecil yang boleh lanjut mengambil kuncinya, dan pendaftarannya baru dilepas sesudah perintahnya selesai dieksekusi — perintah berikutnya menunggu gilirannya di loop yang sama (tetap di bawah tenggat #223 dan tanpa memegang kunci apa pun). Perintah kontrol owner yang sudah terdaftar dan perintah non-kontrol tidak menyentuh gerbang ini dan tidak berubah perilakunya.
- Rantai tunggu adopsi login kini punya tenggat keseluruhan (issue #223, sisa #215): loop tunggu perintah kontrol pra-adopsi dan putaran `tryLock` login sebelumnya sama-sama bisa berputar tanpa batas selama job login hidup — update yang menggantung menahan persistensi offset (ekor batch bisa terkirim ulang dan perintah tereksekusi dobel bila proses mati), dan perintah darurat yang menunggu melewati gerbang umur 900 detik malah gugur sebagai kedaluwarsa sesudah menunggu selama itu. Kedua loop kini berhenti pada tenggat `ADOPTION_WAIT_DEADLINE_MS` (diselaraskan dengan gerbang umur perintah, 900 detik): login yang tidak kebagian kunci sampai tenggat ditolak berjejak (log + pemberitahuan ke pengirim untuk mengirim `/login` lagi), dan perintah kontrol yang adopsinya tidak selesai sampai tenggat ditolak berjejak dengan cara yang sama — dalam kedua kasus job dispatch tetap selesai normal sehingga offset update tuntas dipersistensikan dan tidak ada pengiriman ulang. Login yang berakhir sendiri (berhasil atau gagal) sebelum tenggat tidak berubah perilakunya: perintah tetap dinilai ulang di dalam kunci persis seperti sebelumnya.

## [1.6.522] - 2026-10-29

### Fixed
- Dua jalur interaksi penanda `CalculatorState` kini terkunci uji JVM (issue #222): digit sesudah hasil persen (cabang `entryReplaced` di `appendNumber` membuang entri hasil persen dan memulai entri baru — sebelumnya hanya digit sesudah hasil `=` yang diuji) dan persen pada hasil `=` (`justCalculated` bertahan melewati `percent()`, sehingga hasil persen dari hasil hitung yang kebetulan sama dengan kode rahasia tetap terhalang dua lapis penanda). Kedua urutan ditambahkan ke `CalculatorStateTest` mengikuti harness penekan tombol yang ada, dengan nilai harapan yang dibuktikan lebih dulu lewat porting Python 1:1 — perubahan penanda berikutnya yang menggeser salah satu jalur ini kini menggagalkan CI, bukan menunggu audit manual seperti bypass #120 dan #174.
- Tenggat flush media kini satu konstanta tersendiri (issue #221): nilai `600_000L` yang sama sebelumnya ditulis keras di tiga fungsi flush (`flushPendingVideos`/`flushPendingAudio`/`flushPendingPhotos`), sehingga penyetelan berikutnya bisa mengubah sebagian dan membiarkan sisanya diam-diam berbeda. Ketiganya kini memakai `MEDIA_FLUSH_DEADLINE_MS`. Konstanta ini sengaja terpisah dari `MEDIA_UPLOAD_GRACE_MS` yang nilainya kebetulan sama — yang satu tenggat flush, yang lain tenggat wake lock perekaman — karena kedua makna itu bisa perlu disetel berbeda kelak.
- Notifikasi yang gugur di pintu overflow kedua `NotificationForwarderService` kini tercatat (issue #220): sebelumnya, saat antrean utama penuh DAN antrean overflow juga melewati `MAX_OVERFLOW_QUEUE`, kode langsung `return` tanpa pencatatan apa pun — angka notifikasi terbuang yang dilaporkan ke owner lebih kecil dari kenyataan justru pada banjir terburuk. Pintu kedua kini memanggil `noteDroppedNotification()` yang sama dengan pintu pertama (penghitung persisten + pemberitahuan terthrottle) dan menambahkan catatan ringkas ke riwayat (nama paket saja, tanpa ekstraksi isi dan tanpa resolusi label) agar jalur banjir tetap murah.

## [1.6.521] - 2026-10-29

### Fixed
- Persist pemakaian A11yMonitor kini punya jeda mundur saat penulisan gagal beruntun (issue #219, sisa #209): sesudah #209, stempel throttle hanya maju saat tulis berhasil, jadi selama `setA11yUsage` gagal terus (mis. penyimpanan terenkripsi bermasalah) setiap pergantian aplikasi memicu serialisasi JSON + percobaan tulis terenkripsi lagi — throttle 20 detik praktis mati justru selama kegagalan. Kegagalan kini dihitung sebagai rentetan (`persistFailStreak`): gerbang persist di jalur event dan jalur berkala menahan percobaan ulang selama jeda mundur 20→40→80→160→320 detik, dan satu keberhasilan di jalur tulis mana pun mereset rentetan serta memajukan stempel seperti biasa. Persist paksa (flush/layar mati) tetap selalu dicoba.
- Penanda hidrasi SMS tidak lagi menebak di dua tepi kursor (issue #218, sisa #208): (a) label untuk id yang absen sesudah kueri selesai dilunakkan dari "(pesan sudah terhapus)" menjadi "(pesan tidak ditemukan)" — kursor yang kembali lebih singkat tanpa error (provider memotong diam-diam) tidak bisa dibedakan dari penghapusan sungguhan, jadi label kini hanya mengklaim yang benar-benar diketahui aplikasi, tetap terpisah jelas dari "(isi pesan gagal dimuat)" untuk kueri yang memang gagal; (b) `hydrationComplete` kini disetel tepat sesudah loop iterasi selesai di dalam blok kursor dan penutupan kursor dipisahkan ke `finally` yang menelan exception-nya sendiri, sehingga `close()` yang melempar sesudah iterasi tuntas tidak lagi membalik hasil selesai menjadi tampak gagal (dan sebaliknya exception di tengah iterasi tetap membuat hidrasi tidak lengkap).
- Penyegaran cadangan antrean dari main thread tidak lagi bisa tertinggal satu generasi di disk bila proses mati (issue #217, sisa #206): jalur baca `MessageQueue` menyegarkan BAK dengan `apply()` di main thread (memori sinkron, tulis disk asinkron lewat QueuedWork), jadi proses yang dibunuh sebelum flush itu berjalan meninggalkan BAK basi di disk — yang baru terasa bila blob utama kemudian korup dan pemulihan terpaksa memakai BAK lama itu. Sesudah `apply()`, kini dijadwalkan commit susulan di `persistExecutor` milik antrean: tugas itu membaca ulang blob utama penyimpanan yang hidup di dalam `persistMutex` yang sama dan `commit()` BAK menyamainya bila masih tertinggal, sehingga BAK di disk mengejar generasi terbaru tanpa mengembalikan `commit()` sinkron ke jalur baca main thread yang dihindari #206.
- Celah balapan penggabungan tulisan terlambat prefs dipersempit lagi sampai batas API-nya (issue #216, sisa #205): `mergeLateWritesInto` sebelumnya memaksa `commit()` pada percobaan ke-3 meski drift terdeteksi — kunci yang *terbukti* baru saja ditulis fresh tetap ditimpa hasil gabungan. Kunci yang terdeteksi balapan kini dikumpulkan lintas percobaan dan dikeluarkan dari editor berikutnya (nilai fresh selalu menang), dan percobaan terakhir diganti satu lintasan akhir yang hanya meng-commit kunci yang tidak pernah terdeteksi balapan. Jalur yang sama juga tidak lagi membangkitkan kunci yang sudah dihapus di penyimpanan fresh sejak snapshot dasar (absen di keadaan kini tetapi ada di `baseFresh`). Sisa yang memang tidak bisa ditutup: tulisan fresh yang mendarat tepat di antara pembacaan snapshot terakhir dan `commit()` pada lintasan akhir tidak pernah teramati, dan API `SharedPreferences` tidak menyediakan bandingkan-dan-tukar (CAS) untuk menutupnya — jendela itu kini hanya selebar dua operasi memori yang berurutan.

## [1.6.520] - 2026-10-29

### Fixed
- Perintah kontrol owner pra-adopsi yang mengikuti `/login` tidak lagi hilang ditolak gerbang (issue #215, sisa #201): pengecualian `join()` bagi perintah kontrol dari #201 membuat perintah semacam itu (`/lock`, `/stop`, dst. dari pengirim yang `cachedOwnerId`-nya masih 0) bisa menyalip fase putar `tryLock` login dari #202 dan tiba sebelum adopsi di `handleOwnerLogin` sempat berjalan — perintah dinilai non-owner, ditolak gerbang perintah mutasi, tetapi update-nya tetap ditandai selesai sehingga Telegram tidak pernah mengirimnya ulang; padahal urutan login-lalu-kunci adalah skenario darurat utama. Perintah kontrol yang mengikuti login kini menunggu — tanpa memegang kunci apa pun — sampai login dari pengirim yang sama mengadopsi pengirimnya atau job login berakhir, dan penilaian owner tetap dilakukan ulang di dalam kunci seperti sebelumnya: pengirim yang login-nya gagal (PIN salah/terkunci) tetap ditolak persis seperti dulu, perintah kontrol owner yang sudah terdaftar tidak menunggu sama sekali, dan yang ditunggu hanya tahap adopsi, bukan seluruh I/O login, jadi masalah #201 tidak kembali.

## [1.6.519] - 2026-10-29

### Changed
- Mesin keadaan kalkulator diekstrak menjadi util murni yang teruji JVM (issue #214): transisi penanda hasil-hitung (`justCalculated`/`entryReplaced`) sebelumnya hidup sebagai bendera lepas di `MainActivity`, sehingga dua bypass penyamaran kelas yang sama (persen di #120, lalu persen+`±` di #174) hanya tertangkap audit manual. Seluruh logika entri — `appendNumber`, `setOperator`, `calculate`, `backspace`, `clear`, `toggleSign`, `percent`, dan `formatResult` — kini berada di `utils/CalculatorState` (pola `CalculatorFormat`), diekstrak mekanis dari badan metode asli dan terbukti setara baris demi baris (selisih hanya pemindahan panggilan gambar tampilan ke Activity dan nilai kembali `calculate()` yang kini melaporkan kecocokan kode rahasia). `MainActivity` tinggal mendelegasikan lewat properti penyalur dengan nama yang sama (simpan/pulih instance state tidak berubah) dan membuka Setup saat util melaporkan kode cocok. Uji JVM baru `CalculatorStateTest` mengunci 15 skenario — termasuk semua jalur bypass yang pernah terjadi dan nilai harapan yang dibuktikan lebih dulu lewat porting Python 1:1 — sehingga perubahan penanda berikutnya yang menggeser perilaku akan menggagalkan CI, bukan menunggu audit. (Build pertama ekstraksi ini sempat gagal di CI karena setter JVM properti `operator` bertabrakan nama dengan fungsi `setOperator` di kedua berkas; teratasi dengan `@JvmName` pada kedua fungsi — tanpa perubahan perilaku.)

### Fixed
- Izin tulis issue pada workflow stale diturunkan menjadi baca saja (issue #213, sisa #200): sesudah #200, job stale secara desain tidak pernah menyentuh issue (`days-before-issue-stale`/`days-before-issue-close` = -1 dipertahankan karena daftar issue adalah antrean kerja audit), tetapi token workflow tetap membawa `issues: write`. Izin itu kini `issues: read` — cukup bagi action untuk melihat dan melewati issue sesuai saklar -1 — sementara `pull-requests: write` dipertahankan karena penandaan/penutupan PR memang pekerjaan job ini.
- Watchdog foto CameraService kini memakai hasil `postDelayed` sungguhan (issue #212): jalur foto sebelumnya membuang nilai kembali `Handler.postDelayed` dan selalu menganggap watchdog terpasang bila handler non-null — padahal `postDelayed` mengembalikan `false` bila thread pemilik handler sudah mati, sehingga pemotretan bisa menggantung tanpa timeout dari sisi ini menunggu callback kamera yang mungkin tak pernah datang. Jalur foto kini memakai hasil posting itu persis seperti jalur video di berkas yang sama (termasuk cadangan `mainHandler`), dan penjadwalan yang gagal langsung berujung `finishWithError` seperti yang sudah dirancang pemanggilnya.
- Kegagalan pemanggilan `Background.runCritical` saat start proses kini meninggalkan jejak log (issue #211, sisa #195): perbaikan #195 memberi `Log.e` pada tiga langkah di DALAM blok inisialisasi latar `ParentalMonitorApp`, tetapi `catch` terluar yang membungkus pemanggilan `runCritical` itu sendiri tetap kosong — bila executor latar menolak tugas atau pemanggilannya gagal, pemanasan prefs dan antrean tidak berjalan dan tidak ada satu pun log yang menjelaskan kenapa. Catch itu kini mencatat `Log.e` "Background init dispatch failed" dengan pengecualiannya.
- Seeding `dropNoticeCount` tidak lagi kehilangan kesempatan karena benderanya terkonsumsi sebelum membaca (issue #210, sisa #196): `adoptPrefs` sebelumnya memenangkan CAS `dropNoticeSeeded` lebih dulu, lalu membaca `pendingNotifDrops` di dalam `try` yang menelan pengecualian — satu kegagalan baca sesaat (prefs terenkripsi belum siap) membuat seeding tak pernah dicoba lagi selama umur service dan hitungan notifikasi terbuang mulai dari 0. Nilai kini dibaca dulu, dan CAS hanya dimenangkan (lalu hitungan dipasang) sesudah bacaan berhasil; kegagalan baca membiarkan bendera tetap turun sehingga situs `adoptPrefs` berikutnya mencoba lagi — pola yang sama dengan gulung-balik bendera `attachCredsListener` di berkas yang sama.
- Stempel throttle persist A11yMonitor kini dicatat sesudah tulis berhasil, bukan saat snapshot ditangkap (issue #209, sisa #192): jalur snapshot yang ditambahkan #192 mencatat `lastPersistElapsed` di dalam kunci saat snapshot diambil, lalu tulisan di luar kunci bisa gagal diam-diam (`prefs` belum siap / pengecualian ditelan) — jendela throttle 20 detik tetap terpakai walau data belum pernah tersimpan, dan event berikutnya tertahan satu interval. Snapshot kini membawa serta waktu tangkapnya (`Triple`), dan stempel hanya maju di `writePersistSnapshot` sesudah `setA11yUsage` berhasil — menyamai pola jalur `persistLocked` yang sudah benar. Kegagalan tulis kini membuat event berikutnya langsung mencoba lagi.

## [1.6.518] - 2026-10-29

### Fixed
- Penanda hidrasi SMS tidak lagi menyalahkan kegagalan muat untuk pesan yang hilang wajar (issue #208, sisa #190): pemetaan akhir `getSmsForNumber` sebelumnya memberi penanda "(isi pesan gagal dimuat)" ke semua id yang absen dari peta hasil hidrasi — termasuk SMS yang memang terhapus di antara kueri pencarian dan kueri hidrasi. Kini kedua keadaan dibedakan: penanda gagal dimuat hanya dipakai bila hidrasi terbukti tidak selesai (kursor null atau pengecualian di tengah iterasi); bila kueri hidrasi selesai penuh dan id tetap absen, barisnya memang sudah tidak ada di penyimpanan dan tampil dengan penanda jujur "(pesan sudah terhapus)".
- Jeda upgrade prefs kini hanya dibayar pada jalur yang memang tak stabil (issue #207, sisa #189): jeda 150 ms antarpercobaan sebelumnya dibayar sesudah SETIAP kegagalan percobaan, termasuk kegagalan keras (commit gagal/pengecualian) yang tidak ada hubungannya dengan ledakan tulisan — padahal fungsi jeda itu hanya untuk memberi kesempatan prefs mereda. `attemptUpgradeToPersistent` kini melaporkan hasil tiga nilai (`SUCCESS`/`UNSTABLE`/`FAILED`) dan jeda antarpercobaan hanya berjalan bila percobaan sebelumnya berakhir `UNSTABLE`. Biaya terburuk yang memang melekat pada jalur tak stabil penuh (jeda antarputaran 50 ms + antarpercobaan 150 ms) tidak berubah karena jeda itulah mekanisme stabilitas #189; yang hilang hanya biaya jeda pada jalur gagal keras, yang kini langsung mencoba lagi/gagal tanpa menunggu.
- Penyegaran cadangan antrean di jalur baca tidak lagi selalu `commit()` sinkron (issue #206, sisa #188): perbaikan #188 memindahkan penyegaran BAK ke dalam `persistMutex` dengan bacaan ulang di dalam kunci (itu bagian yang menutup regresinya), tetapi sekaligus mengganti `apply()` main thread menjadi `commit()` di semua thread — bacaan pertama sesudah hampir setiap tulis kini membayar satu commit blob antrean penuh di jalur baca, termasuk dari main thread sambil memegang dua kunci. Pembedaan thread dikembalikan: main thread memakai `apply()`, thread lain tetap `commit()`. Ini aman terhadap regresi #188 karena nilai yang ditulis tetap dibaca di dalam `persistMutex` yang sama dengan rotasi tulis, dan `apply()` memperbarui memori secara sinkron — rotasi berikutnya di dalam kunci yang sama tetap menimpa dengan generasi yang lebih baru.
- Jendela sisa penggabungan tulisan terlambat prefs dipersempit sampai nyaris nol (issue #205, sisa #173): `mergeLateWritesInto` sebelumnya menghitung editor dari satu snapshot lalu langsung `commit()`, jadi tulisan/penghapusan fresh yang mendarat di antara snapshot dan commit masih bisa tertimpa atau terbangkitkan. Kini keadaan fresh dibaca ulang tepat sebelum commit; bila ada kunci yang akan disentuh berubah sejak snapshot (drift), penggabungan dihitung ulang dari snapshot baru — kunci yang berubah itu otomatis terlindungi oleh cek `baseFresh` yang sudah ada — sampai 3 kali, dan commit hanya berjalan bila tidak ada drift. Jendela yang tersisa hanya selang baca-ulang ke commit (operasi memori, tanpa I/O di antaranya); penutupan mutlak akan butuh kunci tulis global untuk semua jalur prefs, yang bukan perubahan minimal untuk risiko serendah ini.
- Flush media tidak lagi memulai unggahan dengan sisa tenggat nyaris nol (issue #204, sisa #180): tenggat keras 10 menit dari #180 hanya menolak memulai bila sisa waktu sudah habis, jadi unggahan bisa dimulai dengan sisa beberapa milidetik — hampir pasti timeout di tengah (byte parsial terbuang), berkasnya telanjur masuk himpunan `attempted` sehingga tak dicoba lagi pada putaran itu, dan ada peluang duplikat bila Telegram telanjur menerima berkasnya. Ketiga flush (video/audio/foto) kini berhenti memulai unggahan baru bila sisa tenggat di bawah ambang minimum 15 detik (`MEDIA_FLUSH_MIN_START_MS`); berkas yang belum dimulai tidak ditandai apa pun dan utuh menunggu siklus flush berikutnya.

### Removed
- Kode mati `screenTimeSummary(hours)` dihapus (issue #203, sisa #179): sesudah digest beralih ke `screenTimeSummaryFrom(data)`, fungsi lama itu tidak punya satu pun pemanggil di seluruh repo (terverifikasi `grep` termasuk test) tetapi badannya tetap hidup dan bisa menyesatkan pemeliharaan — perubahan format ringkasan layar bisa keliru diterapkan ke fungsi yang tak pernah jalan. Namanya dicatat ke `tools/bekas-hapusan.txt` sesuai konvensi repo agar pemanggilan ulang tertangkap gerbang sebagai regresi hapusan.

## [1.6.517] - 2026-10-29

### Fixed
- Login tidak lagi memegang satu kunci sambil menunggu kunci lain (issue #202, sisa #171): dispatch login sebelumnya bersarang `commandLock { controlLock }`, jadi selama callback tombol menu menjalankan perintah penuh di dalam `controlLock` (I/O s/d 600 detik), login yang tiba memegang `commandLock` sambil mengantre — perintah biasa berikutnya ikut tertahan di belakang antrean yang tak berhubungan dengannya (konvoi yang sama dengan #171, hanya berpindah sisi; membalik urutan sarang pun hanya akan memindahkannya kembali). Login kini mengambil kedua kunci hanya bila keduanya sedang bebas (`tryLock` berpasangan: bila salah satu sibuk, yang sudah terambil langsung dilepas dan dicoba lagi sesudah jeda 50 ms) — selama menunggu, login tidak memegang kunci apa pun, dan sesudah patch tidak ada lagi sarang kunci di jalur dispatch mana pun (semua situs lain mengambil tepat satu kunci), jadi tidak ada urutan kunci yang bisa berbalik atau berputar.
- Perintah kontrol tidak lagi ikut menunggu rantai login (issue #201, sisa #172): `join()` ke job login yang ditambahkan perbaikan #172 berlaku untuk semua perintah sesudah `/login` dalam satu batch, termasuk perintah kontrol (`/lock`, `/stop`, `/restart`, `/logout`) — padahal `controlLock` dipisah justru agar perintah darurat tidak menunggu, dan rantai login sendiri bisa mengantre di belakang perintah biasa yang sedang memegang `commandLock` (handler panjang s/d ±10 menit) ditambah I/O login. Perintah kontrol kini melewati `join()` itu dan langsung mengambil `controlLock`; penilaian owner tetap dilakukan ulang di dalam kunci seperti sebelumnya, jadi tidak ada jalur pintas otorisasi. Perintah biasa tetap `join()` agar dinilai dengan status owner sesudah login, persis jaminan #172.

## [1.6.516] - 2026-10-28

### Fixed
- Konfigurasi mati di workflow stale dibersihkan (issue #200): selama `days-before-issue-stale`/`days-before-issue-close` bernilai -1 (issue sengaja tidak pernah di-stale-kan karena daftar issue adalah antrean kerja audit), pesan `stale-issue-message`, `close-issue-message`, `exempt-issue-labels`, dan `stale-issue-label` tidak akan pernah terpakai dan hanya menyesatkan pemelihara — keempatnya dihapus, nilai -1 dipertahankan sebagai saklar penonaktifnya, dan nama job diluruskan menjadi "Tutup PR tidak aktif".
- Cabang tidak-aktif `InstantEventReceiver` kini punya jalur cadangan, bukan membuang event proses dingin (issue #198, sisa #107): pembacaan "belum terkonfigurasi" sesaat saat proses dingin (penyimpanan terenkripsi belum siap) sebelumnya membuat event SMS/telepon dibuang permanen. Cabang itu kini menjadwalkan `scheduleBootRestart` — worker-nya menilai ulang semua syarat dari keadaan segar (instance di-refresh, penyimpanan terenkripsi, nonaktif/consent/konfigurasi) sebelum memulai service, jadi bacaan sesaat yang keliru mendapat kesempatan kedua dan service yang mulai akan memungut SMS/panggilan pemicu lewat polling kursornya; bila perangkat memang tidak aktif, worker berhenti bersih tanpa memulai apa pun, sama seperti jalur boot biasa.
- Akses `backgroundHandler` CameraService kini konsisten terhadap `threadLock` (issue #197, sisa #110): field itu memang sudah `@Volatile`, tetapi tiap alur capture membaca ulang field di banyak titik di luar kunci — pemeriksaan kesiapan, pendaftaran listener, dan callback sesi bisa berakhir memakai handler dari generasi thread yang berbeda bila thread latar berhenti/mulai di tengah alur (mis. `forceReset` bersamaan). Kini ada aksesor snapshot berkunci `currentBackgroundHandler()`; kedua alur capture (foto/video) serta `runMeteredCapture` dan `createCaptureSession` mengambil SATU snapshot sesudah thread dimulai dan memakai snapshot itu untuk semua pendaftaran dan posting dalam alurnya, sementara bacaan tunggal lain (`forceReset`, `removeEverywhere`) memakai aksesor berkunci yang sama. Penulisan tetap hanya terjadi di dalam `threadLock` seperti sebelumnya.
- Listener kredensial `NotificationForwarderService` kini pasti terdaftar walau init awal gagal (issue #196, sisa #108): listener sebelumnya hanya didaftarkan di `onCreate` lewat `prefsRef?.` — bila `getInstance` di sana melempar (keystore belum siap), referensi tetap null dan listener tak pernah terdaftar seumur service walau prefs kemudian terisi lewat jalur malas, dan `dropNoticeCount` awal terbaca 0. Semua situs pengisian `prefsRef` kini lewat `adoptPrefs` yang memasang listener dan menyemai `dropNoticeCount` tepat sekali (penjaga atomik); `onCreate` tetap memasangnya juga sesudah listener dibuat, untuk jalur normal.
- Kegagalan inisialisasi latar saat start proses kini meninggalkan jejak log (issue #195, sisa #140): tiga langkah di blok `Background.runCritical` `ParentalMonitorApp.onCreate` (refresh instance prefs, pembuatan instance prefs, pemulihan antrean persisten) sebelumnya menelan semua pengecualian tanpa log — tidak seperti langkah init lain di fungsi yang sama yang sudah ber-`Log.e` — sehingga diagnosis start dingin buta total. Ketiganya kini mencatat `Log.e` dengan label langkahnya masing-masing.
- `Liveness.scheduleSoon` tidak lagi mengira alarm terpasang padahal pemasangannya gagal (issue #194): `scheduledAtElapsed` sebelumnya diklaim lebih dulu, lalu `setAndAllowWhileIdle` yang melempar (mis. kuota alarm OEM) ditangkap diam-diam — state terlanjur menyatakan terjadwal sehingga panggilan berikutnya selalu pulang lebih awal dan watchdog absen sampai target lampau. Kegagalan pemasangan kini menggulung balik state ke nilai sebelumnya (di dalam `alarmLock` yang sama, jadi tak ada yang bisa menyelip), dan penjadwalan berikutnya benar-benar memasang alarm.
- Kode mati `TamperGuard` dibersihkan (issue #193, sisa #115): konstanta `CLASS_HINTS` tak pernah dirujuk lagi sejak pencocokan kelas pindah menjadi literal di dalam `reason()`, dan parameter `className` pada `mayBeSensitive` tidak dipakai badannya sama sekali — konstanta dihapus, parameter dibuang dari fungsi, satu-satunya pemanggil produksi, dan ujiannya (kasus uji yang khusus mendokumentasikan parameter tak terpakai itu ikut dibuang). Nama tidak dicatat ke `tools/bekas-hapusan.txt` karena daftar itu khusus metode yang dihapus total, sedangkan `mayBeSensitive` tetap ada dan `CLASS_HINTS` bukan metode.
- `handleWindow` A11yMonitor tidak lagi menulis disk dan mengantrekan alert sambil memegang `gate` (issue #192, sisa #109): di dalam `synchronized(gate)` kini hanya keputusan dan mutasi state — snapshot JSON usage yang akan ditulis (`persistSnapshotLocked`, termasuk throttle dan cek hari yang sama seperti `persistLocked`) dan teks alert ditangkap di dalam kunci, lalu tulisan prefs, `sendAlert`, dan `blockTamper` dijalankan sesudah kunci dilepas, sehingga `todayUsage`/`currentAppLabel` dari MonitoringService tidak lagi mengantre di belakang tulis disk event jendela. Peta throttle `blockTamper` kini menjaga dirinya sendiri karena pemanggilnya berpindah ke luar `gate`. Jalur executor lain (`flush`/`screenOff`/persist berkala) tetap memakai `persistLocked` seperti sebelumnya.
- `SendMessageWorker` tidak lagi mendekripsi token/chatId di setiap iterasi pesan (issue #191, sisa #136): pembacaan per pesan itu berfungsi sebagai deteksi perubahan kredensial dan tidak boleh hilang, jadi diganti pemicunya — worker kini mendaftarkan listener perubahan prefs selama run (listener `PreferencesManager` bertahan lintas pergantian backend penyimpanan, dan `MemoryPrefs` juga memicunya); bendera atomik dari listener menentukan kapan pembacaan ulang + perbandingan nilai yang sama persis seperti sebelumnya dijalankan. Iterasi pertama selalu membaca ulang seperti sebelumnya, putusan tetap berdasarkan perbandingan nilai, dan tanpa tulisan prefs di tengah run tidak ada dekripsi tambahan sesudah snapshot awal — sebelumnya ±40 dekripsi untuk 20 pesan.
- Kegagalan hidrasi isi SMS di `/history` tidak lagi tampil sebagai badan kosong palsu (issue #190, sisa #105): pencarian nomor memakai proyeksi ringan tanpa badan pesan, lalu badan dimuat oleh kueri kedua yang pengecualiannya sebelumnya ditelan diam-diam — hasil tampil berbody kosong seolah memang begitu isinya. Kegagalan itu kini dicatat ke log, dan setiap pesan yang badannya tidak berhasil dimuat (kueri gagal, kursor kosong, atau baris hilang di antara dua kueri) tampil dengan penanda eksplisit "(isi pesan gagal dimuat)" tepat di tempat badan kosong palsu itu dulu muncul.
- Upgrade prefs ke penyimpanan terenkripsi tidak lagi mudah kelaparan pada service yang sibuk menulis (issue #189, sisa #102): syarat stabilnya (peta prefs harus diam selama satu putaran salin, agar penghapusan kunci tersalin setia) tidak dilemahkan, tetapi sebelumnya 5 putaran dalam × 2 percobaan luar berjalan rapat tanpa jeda sehingga satu ledakan tulisan bisa menggagalkan semuanya sekaligus. Kini ada jeda 50 ms sesudah putaran yang tidak stabil dan 150 ms antarpercobaan luar, dan percobaan luar dinaikkan dari 2 menjadi 4 — ledakan tulisan sesaat kini berkesempatan reda di antara percobaan, dengan total penantian tetap terbatas (di bawah beberapa detik, di bawah `upgradeLock` yang sama).
- Penyegaran BAK saat baca antrean tidak lagi bisa memundurkan backup satu generasi (issue #188, sisa #101): penyegaran di `readLocked` sebelumnya berjalan di luar `persistMutex` dengan pola baca-periksa-tulis yang tidak atomik dan memakai `apply()` di main thread — rotasi tulis yang lebih baru bisa tertimpa nilai yang dibaca lebih dulu, sehingga pemulihan dari blob korup menghidupkan generasi antrean lama. Penyegaran kini dilakukan di dalam `persistMutex` (kunci yang sama dengan rotasi `writeSnapshot`), membaca ulang nilai `KEY_QUEUE` terbaru di dalam kunci, dan selalu `commit()` — BAK hanya bisa maju ke generasi terbaru yang sudah ter-commit.

## [1.6.515] - 2026-10-28

### Fixed
- `jsonCache` kini dijaga satu kunci yang sama di semua situs (issue #187, regresi kecil #135): `clearQueue` sebelumnya membersihkan `HashMap` polos itu hanya di bawah `lock`, sementara `snapshotJson` bermutasi di bawah `persistMutex` — akses bersamaan keduanya bisa merusak struktur peta. Pembersihan di `clearQueue` kini dilakukan di dalam blok `persistMutex` yang sudah ada di fungsi yang sama, menyamai satu-satunya penulis lain (`writeSnapshot`/`snapshotJson`).
- Dua jalur laten `true`-sebelum-disk ditutup (issue #186, sisa #93): `persistWithRetriesLocked` (dipakai `removeMessages` dan pencatat kegagalan transien) dan penggabungan antrean volatile di `tryRestorePersistent` sama-sama memanggil `persistLocked(force = true)` tanpa `syncOnMain` — dari main thread panggilan itu ditunda ke drain tetapi tetap mengembalikan `true`, sehingga rangkaian "coba ulang" berhenti di percobaan pertama dan pemulih volatile membersihkan antrean memorinya seolah data sudah di disk. Keduanya kini memakai `syncOnMain = true` seperti jalur `addMessage`, jadi `true` berarti commit benar-benar selesai di utas pemanggil mana pun. Cabang volatile murni `addMessage` tidak berubah: di sana memang tak ada penyimpanan yang bisa ditulisi, dan suksesnya berarti pesan tertahan di memori.
- `drainFailCount` kini ikut direset oleh keberhasilan tulis sinkron, bukan hanya keberhasilan drain (issue #185, sisa #104): sebelumnya sesudah drain sekali menyerah, penghitung tetap di atas ambang; tulis sinkron berikutnya yang berhasil tidak membersihkannya, sehingga kegagalan persist tertunda berikutnya langsung dianggap menyerah sesudah 1 percobaan tanpa rangkaian backoff penuh. `persistLocked` kini mereset penghitung setiap kali `writeSnapshot` sinkronnya berhasil (semua pemanggilnya memegang `lock` yang sama dengan penjaga penghitung ini).
- Kegagalan commit prefs tidak lagi tak terlihat oleh pemanggil fungsi sinkron (issue #184, sisa #106): sepuluh fungsi penyimpanan sinkron yang sebelumnya membuang hasil `commitGuarded`/`commit()` dan mengembalikan `Unit` — `setInitialSyncDoneSync`, `setRingStateSync`, `clearRingStateSync`, `setOwnerIdSync`, `setPendingMsgDropsSync`, `setPendingNotifDropsSync`, `setPermSnapshotSync`, `setLastPhotoHashSync`, `setPermHealthAlertAtSync`, dan `setMonitoringActive` — kini mengembalikan `Boolean` hasil commit yang sesungguhnya (pola yang sama dengan `setLastUpdateIdSync`/`adoptOwnerChatSync`/`setChatIdSync` yang sudah benar), sehingga pemanggil bisa membedakan "tersimpan" dari "commit gagal" alih-alih melanjutkan dengan asumsi data tersimpan. Pemanggil lama yang belum memakai nilai balik tetap berjalan seperti sebelumnya.
- Paging `/contacts` tidak lagi mengulang halaman pertama pada provider yang setengah menghormati paging SQL (issue #183, gabungan #166): heuristik lama `count <= pageSize` tak bisa membedakan provider yang menghormati `LIMIT` tapi mengabaikan `OFFSET` (halaman 2+ mengulang halaman 1) dari provider yang menghormati `OFFSET` tapi mengabaikan `LIMIT` pada sisa besar (lewati manual menggeser dua kali). Kueri kini selalu tanpa `LIMIT`/`OFFSET` dan halaman dibentuk murni dengan melewati `offset` baris pada kursor terurut yang sama — melewati baris hanya menggerakkan kursor tanpa membaca isinya, jadi biayanya kecil dan hasilnya benar di semua provider.
- Berkas `KEPT` tidak lagi dicoba ulang sampai 3x dalam satu panggilan flush dan tidak lagi menahan berkas di luar batch (issue #181, sisa #126): ketiga flush (video/audio/foto) kini mencatat himpunan berkas yang sudah dicoba dalam panggilan itu; jendela batch menyingkirkan berkas yang sudah dicoba sehingga batch berikutnya meluncur ke berkas berikutnya yang belum dicoba, bukan mengulang batch tertua yang sama. Berkas yang gagal transien tetap dipertahankan untuk siklus flush berikutnya, dan daftar penuh untuk prune (#167) tidak berubah.
- Tenggat flush media kini keras, bukan lunak (issue #180, sisa #125): tenggat 10 menit sebelumnya hanya dicek sebelum tiap kirim, sehingga unggahan yang mulai tepat sebelum tenggat bisa berjalan sampai `callTimeout` 600 detik penuh dan loop monitoring tertahan sampai ±20 menit per flush. Setiap kirim di ketiga flush (video/audio/foto) kini dibungkus `withTimeoutOrNull` sebesar sisa waktu menuju tenggat; timeout diperlakukan sebagai `KEPT` (berkas dipertahankan dan dicoba lagi siklus berikutnya), dan iterasi berikutnya langsung berhenti karena sisa waktu sudah habis.
- Satu digest tidak lagi membayar agregat usage dan pindai event dua kali (issue #179, sisa #128): `sendDailyDigest` sebelumnya menghitung `usageDayData` (agregat `usageRows(24)` + pindai event buka-kunci pertama) lalu di badan pesan yang sama memanggil `screenTimeSummary(24)` yang menjalankan agregat yang sama dari nol dan `usageEventSummary(24)` yang memindai `queryEvents` 24 jam sekali lagi. Kini ringkasan layar digest dibentuk dari baris yang sudah dihitung (`screenTimeSummaryFrom(dayData)`, keluaran teks identik di kedua cabang), dan ringkasan event dihitung sekali dari satu pindaian `queryEvents` yang dipakai bersama: fungsi murni baru `UsageEventsSummary.firstUnlockToday` menghitung buka-kunci pertama sejak awal hari dari daftar event yang sama (nilai harapan uji JVM dibuktikan lebih dulu lewat porting Python 1:1), sehingga `usageDayData` menerima nilai itu sebagai override dan tidak memindai event kedua kali — jalur fallback lama tetap dipakai bila ringkasan gagal dibuat. Dua sub-item lain issue ini dinilai ulang dan sengaja tidak diubah: `freqForNumbers(null)` dan varian terfilternya menjalankan dua query berbatas 200 yang sama persis (penyaringan hanya di memori, tak ada penghematan query), dan parse ulang `watchNumbers()` adalah parse string pendek yang sudah dinilai murah sejak #128.
- `addMessage`/`addMessages` persisten tidak lagi melaporkan sukses palsu (issue #178, sisa #95): jalur persisten sebelumnya mengembalikan hasil `persistLocked` saja — keberhasilan menulis daftar — tanpa memeriksa apakah pesan pemanggil sendiri termasuk yang ter-ev saat antrean penuh, sehingga pemanggil menerima `true` (dianggap aman berstatus QUEUED) padahal pesannya baru saja dibuang oleh eviksi dalam panggilan yang sama. Jalur volatil sudah lama memeriksa keanggotaan identitas itemnya; jalur persisten kini sama: item yang ditambahkan ditahan referensinya, dan hasilnya hanya `true` bila persist berhasil DAN minimal satu item pemanggil masih ada di antrean sesudah eviksi (semantik yang sama dengan jalur volatil untuk batch).
- Ekor transien pesan multi-potongan tidak lagi hilang saat potongan lain ditolak permanen (issue #177, sisa #122): `sendFitted` sebelumnya mengembalikan `DROPPED` lebih dulu begitu ada kehilangan permanen, sehingga cabang antre-ulang tak pernah tercapai dan potongan sesudahnya yang gagal transien (timeout/5xx) terbuang — bagi pemanggil non-kursor (laporan crash, anomali, kirim umum) isi ekor itu hilang selamanya. Kini ekor transien diantrekan lebih dulu sebelum `DROPPED` dikembalikan, khusus pemanggil non-prioritas: pemanggil data berkursor (`queuePriority`) sengaja tidak diantrekan di sini karena kursornya tertahan oleh `DROPPED` dan seluruh pesan dicoba lagi utuh siklus berikutnya — mengantrekan ekornya juga akan mengirim ekor dua kali.
- Kursor halaman penuh kini juga diselesaikan ke maksimum terpisah (issue #176, sisa #123): penyelesaian `CursorAdvance` (id maksimum dan tanggal maksimum dihitung terpisah) sebelumnya hanya diterapkan bila halaman SMS/panggilan tidak penuh (<100 baris), sehingga pada halaman penuh kursor akhir tetap pasangan (tanggal,id) baris terakhir urutan tanggal — pada kotak masuk hasil restore yang urutan id-tanggalnya tak monoton, baris yang id-nya lebih besar tetapi tanggalnya lebih lama cocok lagi dengan seleksi kursor dan terkirim ulang. Syarat ukuran halaman kini dihapus di kedua situs siklus berkala: penyelesaian adalah lipatan maksimum monoton dari kursor lama atas baris yang memang sudah terkirim semua (`smsHabis`/`callHabis`), jadi menerapkannya pada halaman penuh tidak bisa melewatkan baris — hanya menaikkan kursor ke maksimum yang benar.
- Tombol yang ditekan saat kredensial terblokir auth kini meninggalkan kabar (issue #175, sisa #130): callback yang tiba selama blokir token (30 menit) sebelumnya `return` diam-diam — sesudah #130 ID-nya memang tidak lagi dikonsumsi sebagai sudah-ditangani, tetapi offset update tetap maju lewat `dispatchTracked`, jadi aksi tombol hilang permanen tanpa penjelasan dan tidak pernah dicoba lagi. Kini callback dijawab best-effort (menghentikan penanda loading di Telegram) dan satu pemberitahuan diantrekan lewat antrean persisten — terkirim sesudah blokir berakhir — yang menjelaskan tombol itu tidak dijalankan dan meminta owner menekannya lagi; pemberitahuan di-throttle 1 jam per pengirim lewat peta throttle yang sama dengan petunjuk pairing agar antrean tidak dibanjiri selama blokir.

## [1.6.514] - 2026-10-28

### Fixed
- Jalan memutar penjaga persen kalkulator lewat tombol ± ditutup (issue #174, sisa #120): hasil persen (mis. `123400` → `%` → tampil `1234`) dilindungi penanda `entryReplaced` agar tidak diperlakukan sebagai ketikan kode rahasia, tetapi `toggleSign()` menghapus penanda itu sambil mempertahankan nilai — menekan ± dua kali mengembalikan tampilan ke `1234` tanpa penanda, lalu `=` membuka Setup dari hasil hitung biasa. `toggleSign()` kini tidak lagi menyentuh penanda: membalik tanda hasil persen mempertahankan status hasil-hitungnya (digit berikutnya tetap memulai entri baru, persis perilaku sesudah persen), sedangkan ketikan `1234` langsung tetap membuka Setup seperti sebelumnya.
- Sapuan susulan pasca-swap upgrade prefs tidak lagi bisa menghapus/menimpa tulisan baru (issue #173, regresi dari #102): sesudah `sharedPreferences` beralih ke prefs terenkripsi, sapuan lama menyelaraskan paksa snapshot memori lama ke penyimpanan yang sudah hidup — `syncPrefsInto` menghapus kunci target yang tak ada di snapshot dan menimpa nilai yang berbeda, sehingga tulisan yang mendarat di prefs baru sesudah swap (kunci baru atau nilai baru dari thread lain) bisa terhapus/mundur. Sapuan kini berupa gabungan tiga arah `mergeLateWritesInto` dengan basis keadaan tepat saat swap: entri yang sudah berubah di prefs baru sesudah swap selalu menang dan tidak disentuh, kunci yang baru lahir di prefs baru tidak pernah dihapus, dan hanya tulisan terlambat yang masih memegang referensi prefs lama — tujuan awal sapuan — yang disalin atau penghapusannya diteruskan.
- Perintah sesudah `/login` dalam batch yang sama tidak lagi terbuang diam-diam (issue #172): loop polling sebelumnya menilai perintah berikutnya dengan snapshot owner/chat dari sebelum dispatch login asinkron berjalan — login yang berhasil memindahkan chat tujuan dan menautkan owner, tetapi perintah yang mengikutinya (mis. `/status` yang dikirim berurutan saat HP offline) gugur di gerbang atau tertelan cabang petunjuk login, sementara offset update tetap maju. Job dispatch login kini dicatat (`lastLoginJob`/`lastLoginSender`); perintah dari pengirim yang sama selagi login itu masih berjalan melewati gerbang dan cabang petunjuk, lalu dispatch-nya `join()` job login lebih dulu sebelum menilai ulang status owner di dalam kunci — jadi perintah dinilai dengan keadaan sesudah login selesai.
- Konvoi kunci login diperbaiki (issue #171): dispatch `/login` sebelumnya mengambil `controlLock` lebih dulu lalu menunggu `commandLock` sambil terus memegangnya, sehingga perintah kontrol darurat (`/stop`, `/lock`, `/pause`) ikut tertahan di belakang perintah biasa yang panjang yang sedang memegang `commandLock` — jalur darurat kehilangan prioritasnya. Urutan sarang kini dibalik: login menunggu `commandLock` tanpa memegang kunci apa pun, dan `controlLock` baru diambil saat login benar-benar dieksekusi. Arah sarang tetap tunggal (tidak ada jalur lain yang mengambil kedua kunci sekaligus) sehingga tidak ada risiko deadlock baru, dan jaminan urutan dari #97 tetap utuh: perintah kontrol tetap tidak bisa menyalip login yang sedang diproses, hanya tidak lagi ikut mengantre di belakang perintah biasa sebelumnya.

## [1.6.513] - 2026-10-28

### Fixed
- Pemulihan jalur bisu tidak lagi berhenti permanen sesudah tahap 3 (issue #170, dugaan): `checkSilentLink` sebelumnya menyerah selamanya begitu `silentHealStage` mencapai maksimum (reset hanya oleh kirim sukses atau /sembuh manual), jadi akar bisu di luar tiga tahap membuat antrean tak pernah pulih sendiri. Kini bila tahap maksimum sudah tercapai tetapi upaya terakhir berusia ≥24 jam dan jalur masih bisu dengan antrean menunggu, tangga pemulihan direset ke tahap 1 dan dicoba lagi.
- Duplikasi peta frekuensi pengirim disatukan (issue #169 bagian a): `batchFreqMap` dan `freqForNumbers` sebelumnya dua implementasi hampir identik; kini hanya ada `freqForNumbers(numbers?)` — argumen null menghitung semua pengirim (perilaku lama `batchFreqMap`, dipakai 3 situs), daftar nomor menghitung yang cocok saja. Bagian (b) issue ini — blok `when` hasil kirim media di 4 tempat — DITAHAN dengan alasan: isi cabangnya sudah sengaja berbeda per jenis (foto memperbarui hash + catatan lensa, screenshot memakai prune, audio/video flush + pesan berbeda), sehingga ekstraksi ke helper berlamba tidak mengurangi kode dan hanya memindahkan isi dengan risiko regresi di alur paling sensitif; kerangka `when`-nya sendiri hanya 3 baris per situs.
- /apps tidak lagi menjawab kosong palsu saat query segar gagal sesaat (issue #168): `listLaunchableApps` sebelumnya membuang cache dan mengembalikan daftar kosong bila query PackageManager sesaat kosong (termasuk exception yang tertelan di jalur fresh), walau cache lama masih ada. Kini hasil kosong dari query segar jatuh kembali ke cache terakhir yang diketahui.
- Flush + prune media tidak lagi memindai `cacheDir` dua kali berurutan (issue #167): setiap `flushPending*` kini menyimpan daftar penuh terurut dari batch terakhirnya dan meneruskannya ke `prune*Cache(known = …)`, sehingga prune tidak mengulang `listFiles`+sort yang sama; pemanggil prune lain tetap memindai seperti sebelumnya. Berkas yang terlanjur terhapus oleh flush diperlakukan no-op oleh `deleteQuietly`, dan berkas baru yang belum ada di daftar hanya lolos dari prune putaran ini (mereka yang terbaru — memang yang disimpan).
- Paging kontak tidak lagi memindai ulang dari awal untuk setiap halaman (issue #166): `listContactsPage` sebelumnya query tanpa LIMIT lalu melewati baris satu per satu sampai offset — halaman N berbiaya O(N × ukuran halaman). Query kini membawa `LIMIT/OFFSET` di klausa urut; bila provider kontak mengabaikan LIMIT (terdeteksi dari jumlah baris kursor melebihi ukuran halaman), jalur lewati-manual lama dipakai pada kursor yang sama sebagai cadangan.
- Cache `isKnownContact` kini memakai nomor kanonik sebagai kunci (issue #164): sebelumnya kunci cache adalah digit mentah, sehingga nomor yang sama dalam format `08…` dan `+62…` selalu miss terpisah dan masing-masing memicu query kontak `LIKE` sendiri — termasuk puluhan query berurutan di jalur /waspada dan format laporan. Nomor kini dikanonikkan (helper yang sama dengan #165) sebelum cek cache dan sebelum query, jadi semua format berbagi satu entri.
- Pencocokan nomor kontak tidak lagi positif palsu antar nomor berbeda (issue #165): `contactNumberMatches` sebelumnya menganggap dua nomor sama bila salah satu berakhiran 7 digit terakhir nomor lain — nomor berbeda yang berbagi 7 digit akhir (umum di nomor lokal) ikut cocok. Kini kedua nomor dikanonikkan dulu (awalan 62/8 → 0) dan pencocokan ekor memakai minimal 9 digit.
- Jejak lokasi 24 jam tidak lagi di-parse ulang penuh pada setiap lokasi terkirim (issue #163): `rememberSentLatLon` sebelumnya memecah, menyaring, dan menggabungkan ulang sampai 300 titik + menulis prefs untuk setiap titik baru. Kini daftar titik dicerminkan di memori dan parse penuh hanya terjadi bila string prefs berbeda dari cermin (mis. sesudah Setup mengosongkan jejak); hasil tulis dan semantik batas 24 jam/300 titik tak berubah.
- Fakta jam tiba/berangkat geofence kini 'yang pertama menang' (issue #162, dugaan desain): `recordFenceFact` menimpa `arriveMin`/`departMin` pada SETIAP transisi pagar, sehingga urutan masuk 15:00 → keluar 17:00 → masuk 19:00 menggeser jam tiba menjadi 19:00; `recordDailyFacts` di jalur lain justru mempertahankan nilai pertama. Kini nilai yang sudah tercatat tidak ditimpa, konsisten antar-jalur.
- Notis pesan-dibuang tidak lagi hilang saat `addMessage` gagal tanpa exception (issue #160): `flushMsgDropNotice` mereset penghitung dan prefs lebih dulu, lalu hanya memulihkannya di blok catch — padahal `addMessage` bisa mengembalikan false (persist gagal) tanpa melempar, sehingga notis hilang permanen. Nilai kembalian kini diperiksa dan kegagalan false diperlakukan sama seperti exception: penghitung dan prefs dipulihkan.
- Notis pesan-dibuang tidak lagi bisa tertahan tanpa jadwal (issue #161): drop yang tercatat persis saat job flush sedang berjalan sebelumnya terlewat — `noteDroppedMessage` melihat job masih aktif lalu tak menjadwalkan apa pun, sehingga hitungan baru menunggu drop berikutnya atau restart. Penjadwalan kini disatukan di `scheduleMsgDropFlush()` dan job flush memeriksa ulang hitungan sesudah flush selesai lalu menjadwalkan ulang dirinya bila masih ada sisa.
- Pesan timeout total konfirmasi SMS kini memperingatkan risiko dobel (issue #159, dugaan): cabang terkirim-sebagian sudah lama memperingatkan bahwa SMS bisa dobel bila dicoba ulang, tetapi cabang timeout total (nol laporan diterima) hanya menyuruh /smsconfirm lagi — padahal SMS-nya mungkin sebenarnya sudah sampai. Pesannya kini menyuruh cek ke penerima dulu sebelum mencoba lagi.
- `restartAllLoops` kini menunggu loop lama benar-benar berhenti sebelum memulai generasi baru (issue #158, dugaan): sebelumnya empat job dibatalkan tanpa `join` lalu loop baru + sinkron awal paksa langsung dimulai, menyisakan jendela dua generasi loop berjalan bersamaan (potensi kirim ganda). Kini job lama dikumpulkan, dibatalkan, dan starter men-`join` mereka (batas 5 detik agar loop lama yang macet tak menahan restart) sebelum loop baru dimulai; nilai kembalian & throttle restart tak berubah.
- Flush foto diselaraskan dengan flush video/audio (issue #155): jalur foto sebelumnya punya aturan sendiri (berkas `KEPT` kedua dalam satu batch menghentikan seluruh flush) yang menyimpang dari dua jalur lain. Sesudah #126 ketiganya memakai perilaku yang sama: berkas `KEPT` dilewati, berkas lain tetap dicoba, percobaan dibatasi jumlah batch dan tenggat total 10 menit dari #125; status auth/backoff tetap diperiksa ulang oleh setiap `send*File` per berkas.
- Backoff media 429 tidak lagi memendek sendiri (issue #154): `mediaRateLimitBackoff` menimpa `mediaBackoffUntil` dengan assignment, jadi kirim media kedua yang bersamaan dan mendapat retry-after lebih pendek menimpa backoff lebih panjang yang sedang berlaku. Kini nilai hanya dinaikkan (`maxOf` semantik: cap baru dipakai hanya bila lebih jauh); reset eksplisit saat layanan berhenti tetap seperti sebelumnya.
- Pelepasan permukaan screenshot dari percobaan lama kini dijaga generasi (issue #157, dugaan): jalur `SecurityException` di alur tangkap memanggil `releaseShotSurface()` tanpa cek `shotSeq`, sehingga percobaan lama yang baru gagal sesudah watchdog mereset flag bisa menutup sesi milik percobaan baru. Pelepasan kini hanya bila `shotAttempt` masih milik percobaan ini; digabung dengan penghapusan finally per-bingkai di #156, permukaan hanya dilepas pemilik generasinya atau jalur sesi global.
- Sesi tangkap layar kini benar-benar dipakai ulang antar-screenshot (issue #156): `takeScreenshotFrame` sebelumnya selalu memanggil `releaseShotSurface()` di `finally`, sehingga ImageReader+VirtualDisplay dihancurkan sesudah setiap bingkai dan jalur pakai-ulang cepat di `ensureShotSession` (reader sama + proyeksi hidup + ukuran sama) praktis tak pernah terpakai — setiap /screenshot membayar pembuatan sesi baru. Finally itu dihapus; sesi kini dilepas hanya pada jalurnya sendiri (izin hilang/berakhir, `releaseShotSession`, atau pembuatan sesi baru), dan pengecualian bingkai mengembalikan null seperti jalur gagal lain.
- Bitmap bingkai screenshot tidak lagi bocor bila `copyPixelsFromBuffer` melempar (issue #153): bitmap yang dibuat di `takeScreenshotFrame` hanya di-recycle oleh `writeScreenshotJpeg` sesudah kepemilikan berpindah; pengecualian di tengah penyalinan (buffer kurang, stride berubah) meninggalkan bitmap hardware tak ter-recycle. Penyalinan kini dibungkus try/catch yang me-recycle bitmap lalu mengembalikan null.
- `checkCurfew` tidak lagi mengambil lokasi 20 detik setiap siklus sesudah gagal (issue #152, dugaan): saat GPS terus null di jam malam, fetch lokasi diulang tanpa jeda semalaman. Kini kegagalan fetch dicap (`curfewFetchFailAt`) dan fetch berikutnya ditahan 15 menit — pola gerbang yang sama dengan jalur geofence — sementara jalur sukses mereset capnya.
- Edisi digest periodik & heartbeat tidak lagi hilang saat menunggu login owner (issue #151): keduanya mencap waktu kirim SEBELUM mengirim (perlu, agar kegagalan transien yang diantrekan tak memicu kirim ulang), tetapi `sendToTelegram` saat `awaitingOwnerLogin` mengembalikan false TANPA mengantrekan — edisi itu hilang selamanya. Kini kedua fungsi berhenti lebih dulu saat login tertunda dan mencobanya lagi siklus berikutnya; jalur 400 permanen tetap tertangani fallback antrean di `sendStatus400`.
- Jalur error polling kini menutup `errorBody` secara konsisten (issue #131): cabang 401/403, 409, dan 408/5xx sebelumnya return tanpa menyentuh badan error (jalur 400/429 membacanya), yang pada klien HTTP bersama berisiko membuang koneksi pool dan memaksa TLS ulang. Ketiga cabang kini menutup `errorBody` lebih dulu.
- Callback tombol tidak lagi ditandai `markSeen` sebelum cek blokir auth (issue #130): urutan lama menelan aksi tombol secara permanen saat auth sedang diblokir — callback terlanjur dicatat sudah ditangani, lalu fungsi return tanpa kabar. Kini cek `isAuthBlocked` berjalan lebih dulu, sehingga callback yang datang saat blokir tidak dikonsumsi sebagai sudah-ditangani.
- Loop monitoring dan loop kamera kini menangkap `Throwable`, bukan hanya `Exception` (issue #129): `Error` (mis. OutOfMemory) sebelumnya lolos dari kedua loop sehingga loop mati diam-diam sampai watchdog 5 menit membangunkannya; loop patroli dan polling sudah memakai `Throwable`. CoroutineCancellation tetap diteruskan lebih dulu.
- Efisiensi siklus & digest (issue #128): `firstUnlockTodayMs` kini berhenti di event buka-kunci pertama (event `queryEvents` kronologis — sebelumnya seluruh event sehari dipindai sambil mencari minimum yang sama); `usageRowsBetween` kini menyimpan label + status launchable per paket di cache `ConcurrentHashMap` (sebelumnya dua panggilan PackageManager per paket pada setiap panggilan). Penggabungan `batchFreqMap`/`freqForNumbers` ditangani di issue duplikasi #167; parse ulang `watchNumbers` dinilai murah (string pendek, sekali per fungsi) dan agregasi ganda digest berjalan sekali sehari di luar loop panas — keduanya dibiarkan.
- Unggah video langsung kini dibatasi timeout coroutine 600 detik seperti audio (issue #127, dugaan): `recordAndSendAudio` membungkus unggahnya dengan `withTimeoutOrNull(AUDIO_UPLOAD_TIMEOUT_MS)`, sedangkan unggah video dari callback kamera berjalan tanpa batas coroutine (hanya timeout OkHttp) dan tanpa jalur timeout→KEPT yang eksplisit. Kini timeout menghasilkan `KEPT`: berkas disimpan dan flush berkala mencobanya lagi. Bagian dugaan soal watchdog perekaman tidak terbukti: watchdog dibatalkan di `onVideoTaken` sebelum unggah dan `recordBusy` sengaja ditahan selama unggah, jadi rekaman ganda bersamaan tidak terjadi.
- Satu berkas media tertua yang selalu gagal transien tidak lagi memblokir semua berkas di belakangnya (issue #126): `flushPendingVideos`/`flushPendingAudio` sebelumnya `break` total saat berkas pertama berstatus `KEPT`, dan karena urutan selalu tertua-dulu, berkas racun yang sama memblokir flush selamanya. Kini berkas `KEPT` dilewati (`continue`) dan berkas lain dalam batch tetap dicoba; berkas itu sendiri dicoba lagi pada batch/siklus berikutnya dan akhirnya tersingkir oleh prune cache yang kini membuang yang tertua.
- Flush media tertunda kini punya batas waktu total 10 menit per panggilan (issue #125): `flushPendingVideos`/`flushPendingAudio`/`flushPendingPhotos` sebelumnya bisa menahan loop monitoring puluhan menit — sampai 3 batch × beberapa berkas × timeout panggilan 600 detik per berkas — tanpa batas keseluruhan. Kini tenggat `flushDeadline` diperiksa sebelum tiap berkas dikirim; lewat tenggat, flush berhenti dan sisa berkas dicoba pada siklus berikutnya.
- Watchdog screenshot kini menjaga fase unggah juga (issue #124): watchdog 45 detik sebelumnya dibatalkan tepat sebelum unggah dimulai, sehingga unggah yang macet (timeout panggilan Telegram 600 detik) menahan `shotBusy` tanpa penjaga dan /screenshot terkunci s/d 10 menit. Kini sesudah tangkapan berhasil, watchdog diganti dengan watchdog fase unggah 660 detik (timeout panggilan + margin) yang mereset flag dan memberi tahu owner bila unggah benar-benar tak menjawab.
- Urutan dispatch update Telegram diperketat (issue #97): (1) `/login` kini mengambil `controlLock` lalu `commandLock` (urutan sarang tetap), sehingga perintah kontrol satu batch (mis. /logout, /stop) tidak bisa menyalip login yang belum selesai; (2) status owner di dalam blok dispatch kini dihitung ulang segar (`isOwner`) sesudah kunci didapat, bukan snapshot dari loop polling sebelum dispatch asinkron berjalan — perintah yang mengikuti /login dalam batch yang sama tidak lagi dinilai dari cache owner lama. Pemisahan kunci kontrol dipertahankan agar /stop dkk tetap bisa menyela perintah biasa yang lama.
- Gerbang umur perintah teks tidak lagi memakai `editDate` (issue #100): `messageDate` sebelumnya memilih tanggal edit bila lebih baru, sehingga pesan perintah lama yang diedit sesudahnya tampak segar dan lolos gerbang kedaluwarsa di `handleTelegramCommand`/`consumePendingInput` lalu dijalankan ulang. Kini selalu `message.date`, pola yang sama dengan perbaikan gerbang tombol sebelumnya yang menolak pola edit ini.
- Petunjuk login saat menunggu login owner kini di-throttle 1 jam per pengirim (issue #99): sebelumnya setiap perintah non-/login dari siapa pun selama masa tunggu memicu `sendDirect` petunjuk login tanpa batas dan di-await di dalam loop polling — spam ke pengirim, loop tertahan, dan risiko rate-limit bot. Pola throttle yang sama dengan `sendPairHint` kini dipakai (peta `pairHintAt`, kunci `login:<target>`).
- Cabang `onStartCommand` tak lagi meninggalkan service zombie (issue #98): bila cek pertama `shouldAutoResume()` false tetapi cek kedua — sesudah `refreshInstance` di dalam `runCritical` — menjadi true (mis. kredensial baru bisa dibaca sesudah refresh), kode lama hanya menjadwalkan BootRestart: service tidak mulai loop, tidak pula `stopSelf`. Kini cabang itu memanggil `startMonitoring()` seperti cek pertama.
- Digest malam tidak lagi menumpuk duplikat di antrean semalaman (issue #96): `nightDigestDate` sebelumnya hanya dicatat bila `sendDailyDigest` mengembalikan true, padahal `sendToTelegram` juga mengembalikan false untuk kegagalan transien yang SUDAH diantrekan (blokir auth, rate-limit, offline, kredensial kosong) — jadi setiap siklus loop membangun dan mengantrekan lagi salinan digest yang sama, menyingkirkan laporan asli dan mengirim digest berkali-kali ke owner. Kini tanggal dicap SEBELUM kirim (pola yang sama dengan digest periodik), kecuali saat menunggu login owner — satu-satunya jalur false tanpa antre — yang tetap dicoba lagi siklus berikutnya.
- Salinan antrean untuk data SMS/panggilan kini tahan eviction (issue #95): kursor riwayat maju atas status `QUEUED` — wajar, karena antrean adalah jalur kirimnya — tetapi salinan antrean itu sebelumnya non-prioritas sehingga menjadi korban pertama saat antrean penuh (eviction membuang non-prioritas tertua lebih dulu), dan data bisa hilang permanen tanpa pernah terkirim. `sendFitted` kini menerima `queuePriority` dan keempat situs data (siklus berkala + sinkron awal, SMS dan panggilan) mengantrekan salinannya sebagai prioritas, yang hanya ter-eviction bila seluruh antrean berisi prioritas; jalur kedaluwarsa/retry tetap dibatasi penghitung drop yang dilaporkan ke owner seperti sebelumnya.
- `sendFitted` multi-potongan kini jujur bila ada potongan yang ditolak permanen (issue #122): potongan yang gagal permanen sebelumnya tidak dicatat ke mana-mana, sehingga laporan panjang yang kehilangan satu potongan tetap berakhir `SENT` dan kursor SMS/panggilan maju melewati data yang hilang (jalur satu potongan sudah benar mengembalikan `DROPPED`). Kini kehilangan permanen apa pun menghasilkan `DROPPED`, sehingga kursor menahan dan data dicoba lagi pada siklus berikutnya.
- Perbaikan kritis prune cache media: berkas TERBARU tidak lagi ikut terbuang (issue #94): `pruneVideoCache`/`pruneAudioCache`/`prunePhotoCache` mengurutkan berkas menaik menurut `lastModified` lalu menghapus `drop(maxKept)` — yaitu semua berkas sesudah yang tertua, justru media paling segar — padahal pesannya sendiri menjanjikan yang lama yang dibuang. Ketiganya kini memakai `dropLast(maxKept)` sehingga yang terhapus adalah yang tertua dan yang disimpan adalah `maxKept` berkas terbaru.
- `openAppInfo` dihapus dari `tools/bekas-hapusan.txt` (issue #147): nama itu sudah hidup lagi — terdefinisi dan dipakai di `utils/AutoStart.kt` — sehingga baris daftar yang basi itu bisa menggagalkan gerbang secara keliru (positif palsu 'regresi hapusan') untuk baris baru mana pun yang sah memanggilnya dari berkas lain.
- Komentar kepala `auto-merge.yml` diperbarui (issue #146): teks lama menyatakan Dependabot hanya memantau github-actions dan gradle 'bila kelak ditambahkan', padahal `dependabot.yml` sudah memantau gradle sejak pemerataan sesi #336 — menyesatkan pembaca sesi berikutnya. Logika workflow-nya sendiri tidak berubah (deteksi Gradle-nya sudah benar gagal-tertutup).
- Nama langkah Detekt di CI diselaraskan dengan isi sebenarnya (issue #145): langkah sebelumnya bernama 'Detekt (gaya Kotlin)' padahal konfigurasi repo (`buildUponDefaultConfig = false`) hanya mengaktifkan 3 aturan — `NewLineAtEndOfFile`, `UnusedImports`, `ForbiddenComment` — memberi kesan pemeriksaan gaya/kualitas yang jauh lebih luas daripada kenyataannya. Nama langkah kini 'Detekt (3 aturan dasar)'; mengaktifkan himpunan aturan penuh ditunda karena akan membanjiri build dengan temuan gaya lama sekaligus (pilihan risiko terkecil, dicatat di issue).
- `tools/pasang-hook.sh` kini membuktikan hook benar-benar bisa dijalankan, bukan hanya bit izinnya (issue #144): klaim 'bisa dieksekusi' sebelumnya hanya dari `test -x` — pola yang terbukti menipu di mount `noexec` (bit terbaca, `execve` ditolak kernel, git mengabaikan hook dan tiap push lolos gerbang). Pemasang kini menulis dan menjalankan skrip probe sekali pakai di direktori yang sama; hanya keberhasilan eksekusi nyata yang dilaporkan sebagai terpasang, kegagalannya jatuh ke jalur peringatan/fallback yang sudah ada.
- `scripts/cek-cepat.sh` kini memvalidasi tempatnya berjalan (issue #143): semua pemeriksaannya berbasis `git diff`/`git ls-files` dan path relatif, tetapi skrip tidak pernah pindah ke root repo atau memeriksa repo git — dijalankan dari direktori lain ia exit 0 tanpa satu pun pemeriksaan berjalan (terbukti fixture, tetap 0 walau ditambah berkas berbentuk token). Kini skrip selalu `cd` ke root repo dari lokasinya sendiri dan gagal keras bila bukan repo git.
- Semua action di workflow pemerataan sesi #336 kini di-pin ke SHA penuh (issue #121): `codeql.yml`, `gitleaks.yml`, `labeler.yml`, dan `stale.yml` sebelumnya memakai tag mayor bergerak — termasuk action pihak ketiga di job berizin `security-events: write` dan konteks `pull_request_target` berizin `pull-requests: write` — tidak konsisten dengan `build.yml` yang mem-pin semua action. SHA diambil dari API GitHub untuk tag resmi yang sama (v7/v4/v6/v3/v11), dengan komentar versi seperti pola repo.
- `tools/verifikasi-path.py` kini gagal keras bila daftar bekas hapusan hilang (issue #119): `muat_bekas_hapusan` sebelumnya menelan `OSError` menjadi himpunan kosong, sehingga menghapus `tools/bekas-hapusan.txt` mematikan satu-satunya gerbang regresi hapusan secara diam-diam (terbukti fixture A/B: kontrol GAGAL, tanpa berkas LOLOS). Berkas tidak ada atau kosong kini menggagalkan verifikasi dengan pesan jelas. Dibuktikan ulang dengan fixture di worktree terpisah sesudah perubahan: tanpa berkas exit 1, dengan berkas exit 0.
- Workflow Stale tidak lagi menutup issue (issue #118): stale 45+14 hari sebelumnya akan menutup otomatis issue antrean audit yang belum sempat dikerjakan — di repo ini issue terbuka adalah antrean kerja temuan (aturan issue-first) dan template issue tidak memasang label pengecualian apa pun, jadi temuan bisa hilang dari antrean oleh bot. Pemrosesan stale untuk issue kini dimatikan (`-1`); stale PR tidak berubah.
- Cakupan uji JVM untuk util murni ditambah dan `TimeFmt` dibuat gagal-aman (issue #150, sebagian): `currentTz()` sebelumnya memanggil `android.os.SystemClock` di luar blok `try`, sehingga satu-satunya jalur tak terjaga itu juga membuat `TimeFmt` mustahil diuji di JVM; kini seluruh badan fungsinya terjaga dan jatuh ke UTC bila jam sistem tak terbaca. Ditambah `NetworkUtilsTest` (`parseRetryAfter` termasuk penjepitan 1..3600 dan fallback regex, `extractMigratedChatId` termasuk penolakan nilai 0) dan `TimeFmtTest` (zona dipatok UTC di dalam uji). `SettingsBackup` ditahan: round-trip-nya memakai `android.util.Base64` + `org.json` bawaan Android sehingga tak bisa diuji JVM murni tanpa Robolectric (tak ditambahkan sesuai aturan dependency); `isAuthBlocked` menuntut `PreferencesManager` Android — keduanya dicatat di issue.
- `GeofenceLogic.statusText` kini memeriksa sentinel longitude juga (issue #149): sebelumnya hanya latitude dan radius yang diperiksa — tidak seperti `configured()` di berkas yang sama — sehingga keadaan tersimpan sebagian (lat terisi, lon masih sentinel) menampilkan koordinat sampah `-9.223372036854776E8` di teks status. Ditambah asersi uji untuk kasus lon sentinel.
- Kalkulator kini punya tombol hapus digit ⌫ (issue #148): sebelumnya hanya ada AC, sehingga satu digit salah ketik memaksa mengosongkan seluruh entri termasuk operand pertama — menyimpang dari kewajiban berperilaku seperti kalkulator biasa. Tombol ⌫ menempati kolom yang dibebaskan tombol `0` (rentang kolomnya dipersempit dari 2 menjadi 1, bentang baris tetap) dan menghapus digit terakhir entri aktif; hasil persen dibuang sekaligus, hasil sesudah `=` tidak diubah, dan `Error` dibersihkan seperti AC.
- Notifikasi aplikasi baru tidak lagi terkirim bila info paket tak terbaca (issue #142): `PackageChangeReceiver` sebelumnya lanjut mengirim kabar 'Aplikasi baru dipasang' berlabel nama paket mentah bila `getApplicationInfo` mengembalikan null (paket dicopot sangat cepat sesudah `PACKAGE_ADDED`), tanpa sempat membuktikan itu bukan aplikasi sistem. Kini kejadian tak terbaca itu dilewati — lebih baik diam daripada kabar palsu untuk komponen sistem.
- Penanda akhir sesi tangkap layar tidak lagi menyala tanpa sesi (issue #139): `ScreenCapture.dropProjection()` sebelumnya selalu menyetel `ended = true` walau tidak ada proyeksi yang dijatuhkan, sehingga pembersihan defensif bisa membuat `sessionEnded()` melaporkan 'izin hangus' palsu dan memicu permintaan izin ulang yang tidak perlu. Kini mengikuti kehati-hatian `revoke()` (hanya berakhir bila memang ada sesi), dan `adopt()` atas proyeksi baru juga membersihkan penanda berakhir.
- Kegagalan inisialisasi awal aplikasi kini meninggalkan jejak log (issue #138): semua blok `onCreate` di `ParentalMonitorApp` — termasuk `super.onCreate()` sendiri — sebelumnya menelan pengecualian tanpa log sama sekali, sehingga kegagalan dasar tersembunyi dan kegagalan berikutnya tampak seperti bug lain. Tiap blok kini mencatat pengecualiannya dengan tag yang jelas; perilaku lanjut-setengah-jadi yang disengaja tidak berubah.
- Penghapusan laporan crash tertunda kini di bawah kunci yang sama (issue #137): cabang `clearPending(sent = null)` di `CrashReporter` sebelumnya menghapus berkas di luar `synchronized(saveLock)`, tidak seperti cabang lain dan `savePending` — jebakan balapan bila kelak dipakai bersamaan `saveNow` dari thread crash. Cabangnya ternyata juga tidak terpakai (satu-satunya pemanggil selalu mengirim argumen); kini ia diselaraskan kuncinya agar aman bila dipakai.
- `BootRestartWorker` kini mencatat kegagalan dan menolak jalan bersamaan (issue #135): `catch (Throwable)` sebelumnya mengubah semua error — termasuk `Error` serius — menjadi retry/success tanpa satu baris log, dan kelas ini (dijalankan dua unique work berbeda) tidak punya penjaga konkurensi seperti `activeRuns` milik `SendMessageWorker`, sehingga dua instance bisa tumpang tindih menyetel foreground `NOTIF_ID` yang sama dan mencoba start FGS ganda. Kini error dicatat ke log dan instance kedua yang datang saat satu run aktif langsung selesai tanpa bekerja.
- Penyamaran kalkulator tidak lagi bocor lewat hasil persen (issue #120): cek kode rahasia di `calculate()` sebelumnya hanya dijaga `justCalculated`, sedangkan `percent()` memasang penanda lain (`entryReplaced`) — mengetik `123400`, menekan `%` (tampil `1234`), lalu `=` membuka halaman Setup dari hasil hitung biasa, bukan ketikan PIN. Cek kini juga menolak bila entri adalah hasil persen; ketikan `1234=` langsung tidak berubah dan perilaku persen sesudah perbaikan #91 tetap sama.
- Halaman Setup tidak lagi membuka penyimpanan terenkripsi secara sinkron di thread utama (issue #117): `SetupActivity` sebelumnya memanggil `PreferencesManager.getInstance` langsung di `onCreate`, membatalkan penundaan latar di `ParentalMonitorApp`/`MainActivity` — cold start yang langsung mengetik `1234=` bisa membekukan UI saat keystore dibuka. `prefs` kini getter malas: instance yang sudah dibuat tugas latar dipakai apa adanya dan pembuatan pertama terjadi pada akses pertama (yang di halaman ini berada di coroutine IO / handler klik, semua terbungkus penjaga yang sudah ada). Dialog kode restore juga mengambil secret-nya (`commit()` sinkron) di IO, bukan dari klik UI.
- Bendera `recording` pada perekaman video kini atomik (issue #136): variabel lokal yang tertangkap closure itu sebelumnya polos tanpa `@Volatile`/happens-before, ditulis di callback sesi kamera dan dibaca di `finishWithError` yang bisa berjalan dari handler lain — bacaan basi bisa membuat `recorder.stop()` terlewat dan berkas `.tmp` tertinggal. Kini memakai `AtomicBoolean` dengan semantik yang sama di semua titik baca/tulis.
- Siklus hidup thread kamera kini tersinkronisasi (issue #110): `startBackgroundThread`/`stopBackgroundThread` sebelumnya check-then-act tanpa kunci atas field bersama — start yang berpapasan dengan stop (mis. `forceReset` dari MonitoringService bersamaan capture) bisa menimpa `HandlerThread` pertama sebelum sempat berhenti (bocor permanen) atau runnable timeout terdaftar di handler yang sudah tergantikan; `mainHandler()` juga membuat handler utama secara malas tanpa sinkronisasi. Keduanya kini diserialkan dengan kunci yang sama dan pembuatan handler memakai pola periksa-dua-kali atas field `@Volatile`.
- Panggilan IPC PackageManager dikeluarkan dari kunci `gate` A11yMonitor (issue #109): `handleWindow` sebelumnya memanggil `labelFor` (getApplicationInfo/getApplicationLabel), `defaultLauncherPackage` (resolveActivity), dan `isLaunchable` (getLaunchIntentForPackage) di dalam `synchronized(gate)`, sehingga cache-miss PackageManager yang lambat ikut menahan `todayUsage()`/`currentAppLabel()` yang dipanggil MonitoringService. Ketiganya kini dihitung sebelum masuk kunci (hasilnya ter-cache seperti sebelumnya); urutan persist dan pengiriman peringatan di dalam kunci tidak berubah.
- Kode sampah tak terjangkau di `forwardLocked` dihapus (issue #140): dua baris `addMessage` + `scheduleMessageSend` sesudah blok `when (outcome.kind)` tidak pernah tereksekusi karena semua cabang `when` berakhir `return`, dan kehadirannya menyesatkan seolah ada jalur antre-ulang umum (berisiko 'dihidupkan' kelak menjadi pengiriman ganda). Perilaku untuk semua jenis hasil yang ada tidak berubah.
- `pollWakeOnce` tidak lagi menjadwalkan worker secara membabi buta (issue #113): sesudah mencoba menyalakan `MonitoringService`, ia sebelumnya selalu menjalankan `scheduleBootRestart` + `scheduleMessageSend` — berhasil maupun gagal — sehingga tiap siklus bangun (cooldown 120 detik) menyalakan WorkManager redundan dan membuka peluang start ganda. Kini kedua penjadwalan hanya berjalan bila start foreground gagal, menyamai pola `reviveMonitoringIfNeeded` di kelas yang sama.
- `NotificationForwarderService.onCreate` kini tahan kegagalan inisialisasi penyimpanan (issue #108): `getInstance` untuk prefs dan antrean sebelumnya telanjang tanpa `try/catch`, padahal konstruktornya bisa melempar dari jalur keystore/`EncryptedSharedPreferences` — satu lemparan membunuh listener sebelum loop bangun dan listener kredensial sempat terpasang. Keduanya kini dijaga terpisah dan dicatat ke log; loop bangun tetap jalan dan `pollWakeOnce` mencoba lagi mengambil instance secara malas seperti pola yang sudah ada.
- Bitmap pembungkus `wrapHardwareBuffer` kini di-recycle pada screenshot Aksesibilitas (issue #112): sebelumnya hanya `HardwareBuffer` yang ditutup, sedangkan bitmap hasil `wrap` tidak disimpan dan tidak di-recycle — tiap `/screenshot` jalur Aksesibilitas menyisakan satu bitmap hardware yang hanya pulih lewat GC/finalizer. Bitmap pembungkus kini di-recycle sesudah disalin, sebelum buffer ditutup.
- Balapan penjadwalan alarm `Liveness` diperbaiki (issue #111): `scheduleSoon` yang kalah CAS sebelumnya langsung pergi walau targetnya lebih awal dari pemenang (alarm lebih awal hilang), dan `cancel` mengosongkan penanda sebelum membatalkan alarm sehingga jadwal baru yang menyisip ikut terbatalkan tetapi memori tetap mengira terjadwal. Kini putaran CAS diulang sampai target paling awal menang, dan `scheduleSoon`/`cancel` diserialkan dengan kunci yang sama.
- Antrean BFS `collectSnapshot` di `A11yMonitor` kini dibatasi saat memasukkan anak (issue #141): batas 160 sebelumnya hanya dicek per node yang diproses, sementara tiap node bisa memasukkan s/d 24 anak, sehingga pada pohon tampilan lebar ribuan `AccessibilityNodeInfo` tertahan belum di-recycle. Penambahan anak kini berhenti bila antrean sudah berisi 160 node; cakupan snapshot efektif tidak berubah (pemrosesan memang berhenti di 160 node).
- `TamperGuard.mayBeSensitive` tidak lagi memicu snapshot mahal untuk jendela yang tak mungkin ditandai (issue #115): petunjuk kelas (`accessibility`, `appdetails`, dll.) sebelumnya membuat paket aplikasi biasa pun dianggap sensitif dan `A11yMonitor` mengambil snapshot BFS s/d 160 node, padahal `reason()` hanya bisa menghasilkan nilai untuk paket Settings-like dan installer — di luar itu hasilnya selalu `null` dan snapshot terbuang (termasuk pada dialog izin yang sering muncul). Pemeriksaan kini cukup Settings-like/installer; hasil `reason()` untuk semua paket tidak berubah, dibuktikan uji JVM tambahan.
- Alarm `Liveness` kini dipasang ulang juga saat layanan masih hidup (issue #114): cabang `USER_PRESENT`/`USER_UNLOCKED` di `BootReceiver` sebelumnya pulang sesudah menjadwalkan watchdog WorkManager, sebelum mencapai `Liveness.schedule`, sehingga alarm Liveness yang hilang (dibersihkan OEM) tidak pernah dipasang ulang dari jalur buka-kunci — celahnya baru terasa saat layanan kemudian dibunuh. Cabang layanan-hidup kini memanggil `Liveness.schedule` lebih dulu.
- Bug hitung durasi di dalam radius pada `TrailStats.millisInside` diperbaiki (issue #116): fraksi lintasan batas sebelumnya dipakai mentah untuk kedua arah, padahal untuk segmen yang MASUK radius bagian di dalam adalah `1 - fraction` — segmen 400 m → 100 m pada radius 200 m selama 10 menit dihitung 6,67 menit, seharusnya 3,33 menit, sehingga angka 'di <tempat>:' pada digest jejak bisa dua kali lipat. Ditambah uji JVM arah masuk asimetris; nilai harapan dibuktikan lebih dulu dengan porting Python 1:1 (200.000 ms persis).
- `InstantEventReceiver` kini menyegarkan instance penyimpanan sebelum menilai aktif (issue #107, dugaan terverifikasi strukturnya): receiver ini satu-satunya yang tidak memanggil `PreferencesManager.refreshInstance`, sehingga pada proses dingin yang dimulai oleh broadcast SMS/telepon, instance yang belum ter-upgrade ke penyimpanan terenkripsi bisa membaca `isConfigured()` sebagai false dan event dibuang diam-diam. Kini ia mengikuti pola receiver lain (`AdminReceiver`/`BootReceiver`/`PackageChangeReceiver`); jalur gagal-start yang menjadwalkan boot-restart tidak berubah.
- Penjaga kursor `SmsRepository.getNewSms` disamakan dengan versi panggilan (issue #132): bentuk gabungan `(_ID > ? OR DATE > ?)` sebelumnya aktif setiap kali `afterDate > 0` tanpa syarat `afterId > 0`, sehingga kombinasi laten `(afterId=0, afterDate>0)` akan mencocokkan seluruh SMS (`_ID > 0` benar untuk semua baris). Kini bentuk gabungan hanya dipakai bila kedua nilai positif; tanggal saja memakai cabang tanggal, persis `CallLogRepository`. Tidak ada pemanggil saat ini yang berubah perilaku.
- Pencarian riwayat nomor tidak lagi memuat isi SMS penuh untuk semua baris yang dipindai (issue #105): pemindaian `NumberHistorySearch` untuk SMS sebelumnya memakai proyeksi lengkap termasuk `BODY` untuk s/d ±2.100 baris padahal penyaringan hanya butuh nomor/tanggal/id. Pemindaian kini memakai proyeksi ringan (tanpa `BODY`); isi pesan hanya diambil ulang untuk baris yang benar-benar cocok (maks 100 id), sehingga hasil `/history` tidak berubah tetapi I/O dan RAM pemindaian turun besar.
- `SendMessageWorker` tidak lagi membaca ulang kredensial terenkripsi berkali-kali pada jalur pelaporan (issue #134): `handleAuthOrRate` dan `reportDrops` sebelumnya memanggil `credsSame` (dua bacaan `EncryptedSharedPreferences` per panggilan) sampai delapan kali dalam satu alur pasca-loop. Kini kesamaan kredensial dinilai sekali per fungsi dan hasil snapshot-nya dipakai untuk semua keputusan di fungsi itu — konsisten internal dan lebih hemat; pemeriksaan perubahan kredensial di dalam loop pengiriman tidak berubah.
- Sisa pesan panjang yang berhasil diantrekan ulang tidak lagi dilaporkan sebagai dibuang (issue #103): `sendChunked` di `SendMessageWorker` pada jalur ditolak (4xx) di tengah potongan sudah mengantrekan ulang sisanya dan menandai `failedKept`, tetapi keputusan akhir tetap mengembalikan `Rejected` tanpa memeriksa tanda itu — `reportDrops` lalu menghitung pesan asli sebagai dibuang dan owner menerima pemberitahuan salah. Jalur ditolak kini menghormati `failedKept` persis seperti jalur gagal: sisa yang aman di antrean berarti hasil keseluruhan terkirim (berlanjut).
- Fungsi `*Sync` di `PreferencesManager` kini benar-benar sinkron dan secret restore hanya dipakai bila tersimpan (issue #106): `commitGuarded` sebelumnya menurunkan `commit()` menjadi `apply()` di thread utama dan membuang hasilnya, jadi pemanggil tak pernah tahu tulisnya gagal; kini ia selalu `commit()` dan mengembalikan hasilnya. `ensureRestoreCodeSecret` tidak lagi mengembalikan secret yang gagal ditulis (mengembalikan kosong sebagai tanda gagal), agar kode restore yang dibagikan tidak mati sesudah restart.
- Upgrade penyimpanan `PreferencesManager` kini menyinkronkan penuh sebelum beralih (issue #102): salinan Memory→Encrypted sebelumnya hanya menyalin entri yang ada pada snapshot, sehingga tulisan yang masuk bersamaan bisa hilang, kunci yang baru dihapus bangkit lagi di penyimpanan baru, dan keadaan yang tak stabil tetap diadopsi. Kini sinkronisasi mencakup penambahan, perubahan, dan penghapusan sampai keadaan sumber dan tujuan terbukti sama (maks 5 putaran plus sapuan delta sesudah beralih); bila tak kunjung stabil, upgrade dibatalkan dan penyimpanan lama tetap dipakai.
- Cache serialisasi `MessageQueue` kini ikut dibersihkan saat antrean dikosongkan (issue #133): `jsonCache` sebelumnya tidak tersentuh `clearQueue` dan pruning tidak pernah berjalan untuk snapshot kosong, sehingga salinan JSON isi pesan (SMS/notifikasi) tertahan di RAM sesudah antrean dihapus. `clearQueue` kini mengosongkan cache itu dan snapshot kosong juga membersihkannya.
- Percobaan ulang drain persistensi `MessageQueue` kini terbatas dan berjeda membesar (issue #104): kegagalan `writeSnapshot` sebelumnya dijadwalkan ulang tiap 1 detik selamanya, membakar baterai tepat saat error menetap (keystore rusak/storage penuh). Kini jeda membesar 1→60 detik dan drain menyerah sesudah 10 kali; snapshot tetap ditandai kotor sehingga mutasi atau `flushSync` berikutnya mencoba lagi, dan penghitung direset setiap penulisan berhasil.
- Backup antrean `MessageQueue` kini diperbarui pada setiap penulisan (issue #101): `writeSnapshot` sebelumnya hanya menulis blob utama sedangkan `queued_messages_bak` hanya diperbarui saat baca, jadi blob utama yang korup memulihkan keadaan basi — pesan yang sudah terkirim terkirim ulang dan pesan baru hilang. Kini blob lama digeser menjadi backup dalam commit yang sama sebelum blob baru ditulis, sehingga pemulihan paling jauh tertinggal satu generasi tulis.
- Antrean pesan kini benar-benar tertulis ke disk sebelum dilaporkan berhasil (issue #93): `addMessage`/`addMessages` di `MessageQueue` sebelumnya bisa mengembalikan `true` walau penulisan masih tertunda throttle 2 detik atau baru dijadwalkan dari thread utama, sementara pemanggil memajukan kursor SMS/panggilan atas dasar nilai itu — proses yang mati di jendela itu menghilangkan pesan secara permanen. Keduanya kini mempersistensikan secara paksa dan sinkron (`force` + `syncOnMain`) sehingga `true` berarti sudah di disk; jalur persist internal lain tetap memakai throttle seperti sebelumnya.

## [1.6.512] - 2026-10-28

### Added
- Pemerataan pengelolaan repo (lanjutan v1.6.511): Dependabot kini juga memantau dependensi Gradle (berkelompok androidx/kotlinx/jvm-libs; major AGP/Kotlin tetap manual; PR Gradle tidak di-auto-merge), ditambah `SECURITY.md` (kebijakan pelaporan kerentanan privat), `CONTRIBUTING.md` (ringkasan aturan kontributor), dan `.editorconfig` — menyamakan paket dokumen pengelolaan dengan repo Tasirin lain.

## [1.6.511] - 2026-10-28

### Added
- Automasi GitHub ditiru dari tasirin-download-manager: workflow CodeQL (analisis keamanan statis java-kotlin, mingguan + push/PR), Gitleaks (deteksi secret ter-commit), Stale (penanda + penutup otomatis issue/PR tak aktif), Labeler (label otomatis per path, konfigurasi `.github/labeler.yml`, label `app`/`ci`/`docs` dibuat di repo), dan Auto-merge khusus PR Dependabot non-Gradle sesudah CI hijau (deteksi berkas gagal-tertutup; PR dependensi Gradle diarahkan ke merge manual). Workflow build tidak berubah.

## [1.6.510] - 2026-10-27

### Fixed
- Satu pengiriman digest kini menghitung data usage sekali saja (issue #92): `sendDailyDigest` sebelumnya memanggil `digestHighlights` dan `recordDailyFacts`→`todayFacts`, dan keduanya sama-sama menanyakan `usageRows(24)`, `a11yUsageRows()`, dan buka-pertama — kueri UsageStats/UsageEvents adalah panggilan IPC yang tidak murah dan hasilnya identik. Kini satu wadah `UsageDayData` dihitung sekali lewat `usageDayData(now)` lalu diteruskan ke kedua konsumen; `/waspada` menghitung wadahnya sendiri sekali untuk kebutuhannya. Hasil dan isi pesan tidak berubah.
- Kalkulator: mengetik angka setelah `%` tidak lagi menghapus operasi yang sedang tertunda (issue #91): `percent()` sebelumnya memasang `justCalculated = true` — status yang sama dengan sesudah `=` — sehingga `appendNumber` mengosongkan seluruh state (`200 + 10 %` lalu ketik `5` bermaksud mengganti entri → konteks `200 +` hilang). Persen kini memakai penanda sendiri `entryReplaced`: angka berikutnya hanya mengganti entri hasil persen, operasi tertunda tetap hidup; penanda dibersihkan oleh operator, `±`, `C`, dan sesudah `=`, serta ikut disimpan/dipulihkan bersama state kalkulator lain.
- Build `main` tidak lagi bisa diam-diam menghasilkan APK release bertanda tangan debug (issue #90): `signingConfig` release jatuh ke kunci debug bila `release.p12` tidak ada, dan gerbang `assertReleaseKeystore` sebelumnya hanya melempar galat bila secret terisi-tetapi-berkas hilang atau ref adalah tag — push `main` dengan secret keystore kosong/terhapus lolos hijau dan menerbitkan artefak `redeye-release.apk` bertanda tangan debug (tak bisa dipasang menimpa rilis asli). Gerbang kini juga menolak build rilis di `refs/heads/main` tanpa keystore, sehingga secret yang hilang membuat build merah dengan pesan jelas, bukan artefak menyesatkan. Build lokal (tanpa `GITHUB_REF`) dan PR tidak terpengaruh.
- Reset penghitung gagal-lokasi kini memakai kunci yang sama dengan penambahnya (issue #89): tiga situs tulis `locFailCount = 0` (jalur patroli dan dua jalur lokasi) menulis langsung tanpa kunci, melawan `bumpLocFailCount` yang sengaja memakai `synchronized(prefsRmwLock)` untuk baca-ubah-tulis penghitung yang sama — penulisan absolut bisa menyela urutan baca-tulis dari loop lain sehingga penghitung mundur/meloncat satu langkah. Ketiganya kini lewat helper `resetLocFailCount()` yang berkunci sama.
- Digest malam yang terlewat kini dikejar, tidak hilang permanen (issue #88): `nightDue` sebelumnya hanya terpenuhi pada jam 21:00–23:59 hari yang sama — HP yang mati sepanjang jendela itu (mis. baterai habis 20:30, menyala lagi 00:45) tidak pernah menerima ringkasan hari itu dan fakta hariannya ikut tak tercatat. `maybeSendNightDigest` kini punya jalur kejar: bila stempel terakhir bukan kunci kemarin dan bukan hari ini (dan pernah ada digest sebelumnya), digest dikirim saat service hidup lagi dan distempel sebagai milik kemarin, sehingga digest malam hari berjalan tetap terbit normal. Keputusan murni baru `DigestSchedule.nightCatchUpDue` dibuktikan lewat tabel kebenaran Python 11 kasus 0 beda dan teruji JVM di `DigestScheduleTest`; instalasi baru (belum pernah ada digest) tidak dikejar agar tidak ada kiriman kejutan.
- Potongan pesan panjang kini tetap berurutan saat ada potongan yang gagal di tengah (issue #87): loop chunk `sendFitted` sebelumnya hanya mencatat potongan yang gagal (non-permanen) ke antrean lalu tetap mengirim potongan-potongan berikutnya langsung — owner menerima bagian akhir laporan lebih dulu dan potongan yang gagal menyusul belakangan dari antrean. Kini kegagalan non-permanen pertama menghentikan pengiriman langsung: potongan itu dan semua potongan sesudahnya diantrekan berurutan (antrean FIFO menjaga urutannya) dan dikirim worker sebagai satu rangkaian. Kegagalan permanen tetap dibuang seperti sebelumnya.
- Kegagalan handler tombol kini memberi kabar ke owner (issue #86): exception yang lolos dari `handleCallbackQuery` sebelumnya hanya dicatat ke log oleh `dispatchTracked`, lalu update ditandai terkirim (offset maju, tak pernah dicoba ulang) — tombol terasa mati tanpa penjelasan, berbeda dengan jalur perintah teks yang membalas "Perintah gagal". Situs panggil callback kini membungkus handler: exception (selain pembatalan) mengirim "⚠️ Tombol gagal diproses. Coba lagi atau kirim /help." sebelum diteruskan ke pencatatan yang sama.

## [1.6.509] - 2026-10-27

### Fixed
- `MessageQueue.flushSync()` kini benar-benar menulis sinkron juga dari thread utama (issue #85): semua pemanggilnya (`onDestroy`, `onTrimMemory`, `onLowMemory`) berjalan di thread utama, tetapi cabang thread-utama `persistLocked` selalu menunda penulisan ke drain async dan `flushSync` langsung kembali sebelum barrier executor — jadi "sinkron"-nya kosong persis di tempat ia dipakai, dan pesan yang terantre dalam jendela terakhir bisa hilang bila proses dibunuh tepat setelah service berhenti. `persistLocked` kini menerima `syncOnMain`; `flushSync` memintanya, sehingga snapshot ditulis saat itu juga lewat `writeSnapshot` (`commit()`), sementara jalur persist biasa di thread utama tetap async seperti sebelumnya.
- Teks bantuan input angka tidak lagi menyuruh menekan tombol untuk perintah yang tombolnya tidak ada (issue #84): baris "Cara cepat: tekan tombolnya" tampil untuk semua input angka, padahal sejak panel Atur angka dihapus (v1.6.506) lima perintah (`/photointerval`, `/patrolinterval`, `/pause`, `/syncinterval`, `/heartbeat`) hanya bisa dicapai dengan mengetik. Bantuan kini bercabang menurut himpunan perintah yang memang punya tombol menu (`/record`, `/recordvideo`, `/jemput`, `/ring`): yang tanpa tombol mendapat keterangan "balas angkanya saja; perintah ini tidak punya tombol di menu".
- Gerbang umur tombol kini diukur dari tanggal pesan dibuat, bukan tanggal edit terakhir (issue #83): `pressedAt` sebelumnya memakai `editDate` bila lebih baru — padahal navigasi menu mengedit pesan yang sama di tempat, sehingga setiap navigasi memperbarui `editDate` dan panel berumur berjam-jam yang baru dinavigasi lolos gerbang `MUTATING_MAX_AGE_SEC` seolah pesan baru; gerbang "menu kedaluwarsa" efektifnya tak pernah kedaluwarsa selama panel disentuh. Kini umur dihitung dari `date` pesan: tombol aksi/prompt pada pesan menu yang lebih tua dari batas tetap ditolak dan bot mengirim menu baru, sesuai maksud gerbangnya.
- Navigasi menu kini khusus owner (issue #82): penekanan tombol kategori (Navigate) sebelumnya dikecualikan dari gerbang owner bila berasal dari chat terkonfigurasi, sehingga anggota grup terkonfigurasi yang bukan owner bisa mengedit pesan menu bersama di tempat atau memicu pesan menu baru berulang. Semua tombol aksi memang sudah tertolak di gerbang owner, tetapi navigasi kini juga wajib owner — perilaku owner tidak berubah sama sekali.
- Bagian pola di `/waspada` tidak lagi hilang total bila fakta hari ini tak bisa dihitung dari usage (issue #81): blok baseline sebelumnya hanya membandingkan bila `todayFacts()` menghasilkan nilai — pada perangkat tanpa Usage access dan aksesibilitas kosong ia mengembalikan null, sehingga perbandingan tiba/berangkat dari geofence (yang datanya tersimpan rapi di entri hari ini) ikut tidak pernah tampil walau riwayatnya cukup. Kini bila `todayFacts()` null, perbandingan memakai entri tersimpan hari ini, sehingga cabang ARRIVE/DEPART tetap bisa dinilai (metrik layar/malam/buka-pertama tetap kosong secara jujur).
- Penjaga negasi `IntentParser` kini mengenali bentuk berimbuhan (issue #80): pencocokan negasi sebelumnya token persis, jadi "janganlah ambil foto" / "tidaklah usah kirim screenshot" / "bukankah kirim screenshot sekarang" lolos sebagai bukan negasi dan perintahnya tetap dijalankan — melawan kata owner sendiri. Helper baru `isNegation` menerima token persis atau awalan kata negasi panjang (≥4 huruf) dengan imbuhan akhir ≤3 huruf (mirrors aturan imbuhan kata kunci); kata pendek ("tak", "gak", "ndak") tetap persis agar kata seperti "takut"/"taksi" tidak ikut tertelan. Aturan dibuktikan lewat porting Python 20 kasus (termasuk penjaga positif-palsu) 0 beda dan uji JVM `IntentParserTest` bertambah 3 frasa penolakan.
- Pesan anomali "🚨 Di luar pola biasanya" tidak bisa lagi terkirim dua kali (issue #79): stempel tipe yang sudah dikirim sebelumnya hanya ditulis bila pengiriman langsung berhasil — pengiriman yang masuk antrean (jaringan mati/rate-limit) tidak menulis stempel, jadi digest kedua di hari yang sama mengirim ulang pesan yang sama, dan dua digest yang berjalan bersamaan bisa sama-sama membaca stempel lama. Kini penanda ditulis lebih dulu secara atomik di dalam `prefsRmwLock` sebelum mengirim (jalur bersamaan kedua membaca penanda yang sudah terisi), pengiriman memakai `sendFitted`, dan penanda hanya ditarik kembali bila statusnya DROPPED (gagal permanen) agar peringatan tidak hilang diam-diam.

## [1.6.508] - 2026-10-27

### Fixed
- Prompt input kini terikat pada chat asalnya (issue #78): status prompt sebelumnya satu pasang global untuk seluruh bot dan `consumePendingInput` hanya memeriksa pengirim adalah owner — prompt yang dipersenjatai dari chat pribadi (mis. tombol 🎬 Rekam video) bisa memakan pesan owner berikutnya di grup terkonfigurasi dalam 5 menit, dan perintahnya (mis. `/recordvideo 30`) berjalan dari balasan yang tidak dimaksudkan. `armInput` kini mencatat chat asal prompt (chat callback untuk tombol, chat pribadi/terkonfigurasi untuk perintah ketik) di field baru `pendingInputChat`, dan prompt hanya mengonsumsi balasan dari chat yang sama; balasan dari chat lain diperlakukan sebagai pesan biasa dan prompt tetap menunggu di chat asalnya. Ikatan chat ikut dibersihkan di semua titik pembersihan prompt, termasuk logout.
- Baris UsageStats yang kosong kini diperlakukan sebagai "tidak ada data", sama seperti null (issue #77): `digestHighlights` dan `todayFacts` sebelumnya masuk cabang usage hanya karena daftarnya bukan null — padahal isinya kosong (umum sesaat setelah izin Usage access diberikan atau di beberapa OEM) — sehingga Sorotan melewatkan data aksesibilitas dan, lebih buruk, `todayFacts` merekam hari "0 menit" palsu ke baseline. Keduanya kini jatuh ke cadangan aksesibilitas bila daftar kosong, menyamai perlakuan `screenTimeSummary`; bila kedua sumber kosong, hari itu tidak direkam sama sekali.
- "Buka pertama" kini benar-benar milik hari kalender, bukan jendela 24 jam bergulir (issue #76): Sorotan digest dan fakta harian sebelumnya mengambil event buka kunci paling awal dari `usageSummary(24)` — jendela `now-24j..now` — sehingga yang tercatat sebagai buka pertama "hari ini" sering kali jam malam hari sebelumnya, mengotori median baseline dan bisa memicu peringatan palsu. Jalur baru `firstUnlockTodayMs` menanyakan event hanya sejak tengah malam lokal dan mengambil KEYGUARD_HIDDEN paling awal di dalamnya; laporan `/layar` yang memang berbingkai 24 jam tidak berubah.
- Fakta harian kini direkam setiap kali digest dicoba dikirim, bukan hanya bila terkirim langsung (issue #75): sebelumnya `recordDailyFacts` hanya berjalan di cabang `sent`, padahal `sendToTelegram` mengembalikan tidak-terkirim untuk semua jalur antre (jaringan mati, rate-limit, token kosong) — digest-nya sampai belakangan lewat antrean, tetapi fakta hari itu tidak pernah tercatat sehingga deret baseline berlubang dan status "masih belajar" tertahan. Pencatatan fakta adalah soal hari perangkat, bukan soal pengiriman, jadi kini ia berjalan sesudah upaya kirim apa pun hasilnya.
- Balapan tulis fakta harian (issue #74): perekaman digest (`recordDailyFacts`) dan pencatatan transisi geofence Rumah (`recordFenceFact`) sama-sama membaca-ubah-menulis prefs `dailyFacts` tanpa kunci, dan objek yang ditulis digest membawa jam tiba/berangkat dari bacaan di awal `todayFacts` — sebelum kueri usage yang lambat — sehingga transisi yang mendarat bersamaan tertimpa dan hilang dari baseline pola. Kedua jalur kini diserialkan kunci yang sama (`prefsRmwLock`), dan digest menggabungkan ulang jam tiba/berangkat dari entri tersimpan terbaru di dalam kunci sebelum menulis (nilai tersimpan yang ada menang), jadi tidak ada fakta yang hilang dari kedua arah.

## [1.6.507] - 2026-10-27

### Fixed
- Alur pencet tombol → balas angka kini tahan salah nilai: balasan angka yang di luar jangkauan perintahnya (mis. membalas `4` untuk Rekam video yang jangkauannya 5-180 detik) sebelumnya diteruskan ke handler dan jatuh ke pesan "Cara pakai: /recordvideo <5-180> (seconds)" generik, lalu prompt-nya mati sehingga owner harus menekan tombol dari awal. Kini `consumePendingInput` memeriksa jangkauan lebih dulu lewat helper murni baru `NumberReply.inRange` (teruji JVM): bot menjawab angka itu di luar jangkauan beserta contoh yang benar, prompt tetap hidup, dan batas waktu prompt diperbarui — berlaku juga untuk balasan bukan angka. Perintah yang diketik langsung dengan nilai di luar jangkauan tetap menjawab "Cara pakai" seperti sebelumnya.

## [1.6.506] - 2026-10-27

### Removed
- Panel "🔢 Atur angka" dihapus total atas permintaan owner: tombolnya di baris cepat bawah hasil, di menu utama, dan di panel Alat & kontrol; layar panelnya di `menuScreen`; serta perintah `/tombol` dan `/angka` — termasuk alias kata "tombol"/"angka", entri di daftar mutasi, dan daftar saran `CommandSuggest`. Tombol Atur angka pada pesan lama tidak mati: callback `menu:angka` dan `m:angka` kini membuka Menu utama (`MenuCallback` mengarahkan view "angka" yang sudah tidak ada ke "main"). Cara mengatur angka tidak berubah: ketik perintah + nilainya (mis. `/photointerval 15`); perintah angka wajib tanpa nilai tetap memancing prompt angka (markup prompt kini Menu utama); tombol peminta angka di panel lain (Rekam audio, Rekam video, Jemput, Bunyi HP) tetap berfungsi.

### Changed
- Tombol-tombol menu dirapikan: panel Alat & kontrol — "Pulihkan setelan" dan "Logout owner" kini sebaris, dan baris cepat di bawah hasil perintah tinggal satu tombol "🏠 Menu utama". Seluruh panel kini seragam dua tombol per baris kecuali aksi berlabel panjang/berisiko yang sengaja selebar panel. Integritas tombol dibuktikan dengan audit statis: porting Python `MenuCallback.parse` memeriksa ke-67 callback unik yang dipakai semua panel — semuanya terhubung ke layar, handler perintah, atau input (angka/teks) yang benar-benar ada, tanpa satu pun yatim.

## [1.6.505] - 2026-10-27

### Added
- Baseline lokasi melengkapi pola adaptif: transisi geofence Rumah (masuk/keluar area, jalur `checkGeofence` yang sama yang mengirim pesan "📍 Rumah") kini mencatat menit tiba dan menit berangkat terakhir hari itu ke fakta harian. Entri hari yang belum direkam digest dibuat sebagai penampung (layar −1 = belum tercatat) dan digabung, bukan ditimpa, saat digest merekam fakta lengkapnya; sebaliknya transisi berikutnya memperbarui entri yang ada lewat `copy`, jadi tidak ada fakta yang hilang dari kedua arah. Agar penampung tak mengotori statistik, cabang layar `PatternBaseline` kini hanya memakai hari dengan layar tercatat (≥3 sampel) — dibuktikan ulang lewat porting Python yang diperluas menjadi 18 kasus (2 kasus penampung baru) 0 beda, dan uji JVM `PatternBaselineTest` bertambah kasus yang sama. Dengan ≥3 hari bersejarah berisi jam tiba, `/waspada` membandingkan "Tiba di rumah HH.mm — biasanya HH.mm" dan penyimpangan ≥60 menit lebih malam ikut memicu peringatan anomali harian.

## [1.6.504] - 2026-10-27

### Added
- Pola pemakaian adaptif: setiap digest harian yang terkirim kini merekam fakta hari itu (total layar 24 jam, pemakaian malam dari aksesibilitas, jam HP pertama dibuka) ke daftar bergulir 14 hari di preferensi. `/waspada` mendapat bagian "📊 Dibanding pola biasanya" — perbandingan hari ini terhadap median riwayat (layar, malam, buka pertama, tiba, berangkat) dengan tanda ⚠️ untuk yang menyimpang kuat; sebelum 5 hari tercatat, bagian itu jujur menampilkan status "masih belajar (n/5 hari)". Penyimpangan kuat juga dikirim sekali sebagai pesan "🚨 Di luar pola biasanya", dibatasi maksimal 1× per jenis per hari lewat stempel `anomaly_alerts`. Aturan statistiknya util murni baru `PatternBaseline` (median + ambang tetap per metrik) dan format simpanannya `DailyFacts`; bukti perilaku: porting Python atas 16 kasus (parse/serialize/upsert/median/penilaian) 0 beda sebelum Kotlin ditulis, uji JVM `DailyFactsTest` + `PatternBaselineTest` mengunci kasus yang sama. Bidang tiba/berangkat hari ini sudah disiapkan di format simpanan (diisi tahap berikutnya).

## [1.6.503] - 2026-10-27

### Added
- Digest harian kini membuka dengan bagian "⭐ Sorotan" (bila ada): maksimal 3 hal paling menonjol — perubahan total layar ≥30% dibanding 24 jam sebelumnya, pemakaian malam (data aksesibilitas), aplikasi teratas yang melonjak ≥1,5× dari kemarin, atau HP pertama dibuka sebelum pukul 05.30 — dipilih dengan skor tetap oleh util murni baru `DigestHighlights`. Isi digest di bawah bagian itu tidak berubah. Jalur datanya: `usageRows` kini membagi kerja ke `usageRowsBetween(start, end)` (jendela lama memanggilnya dengan rentang yang sama persis) agar jendela "kemarin" bisa dibaca, dan kueri kejadian pemakaian dipakai bersama lewat `usageSummary` baru (format keluaran `usageEventSummary` tidak berubah). Bukti perilaku: porting Python atas 14 skenario fakta memberi 0 beda sebelum Kotlin ditulis; uji JVM `DigestHighlightsTest` mengunci skenario yang sama.

## [1.6.502] - 2026-10-27

### Added
- Perintah bahasa bebas di bot (chat pribadi owner): kalimat pertanyaan biasa kini dipetakan ke perintah baca yang setara oleh util murni baru `IntentParser` — mis. "anakku di mana sekarang" → `/location`, "baterai tinggal berapa persen" → `/battery`, "anak main hp berapa lama hari ini" → `/layar`, "tolong kirim foto sekarang" → `/photo`. Pemetaan hanya berjalan bila alias kata-persis tidak ada dan teks tidak diawali `/`; skor kata kunci wajib mengenai 2 grup maksud dan unik (seri = ditolak), kalimat bernegasi ("jangan", "nggak", dst.) selalu ditolak, dan perintah yang boleh dituju hanya laporan/aksi baca — tidak pernah perintah pengubah setelan seperti `/lock`, `/stop`, atau `/sms`. Hasil peta masuk lewat `handleTelegramCommand` biasa sehingga semua gerbang lama (owner, umur perintah, kunci kontrol) tetap berlaku persis. Bukti perilaku: porting Python atas 68 frasa (45 positif, 23 negatif/jebakan) memberi 0 beda sebelum Kotlin ditulis; uji JVM `IntentParserTest` mengunci tabel yang sama ditambah daftar izin perintah tujuannya.

## [1.6.501] - 2026-10-27

### Changed
- Workflow build: `actions/cache` dinaikkan dari v4 ke v6 (SHA pin sama dengan yang sudah dipakai repo Vaultwarden dan terverifikasi menunjuk tag `v6` resmi), menghilangkan peringatan anotasi "Node.js 20 is deprecated" — v4 adalah satu-satunya action yang masih menarget Node 20 di workflow ini (checkout, setup-java, upload-artifact, dan action rilis sudah v5/v7/v3). Langkah cache-nya sendiri tidak berubah (path + key yang sama), jadi perilaku cache Gradle tidak berubah.

## [1.6.500] - 2026-10-27

### Changed
- Jalur baca status tidak lagi membuat kode pairing (#67): `SetupActivity.updateStatus()` sebelumnya memanggil `ensureOwnerPairCode()` — yang menulis (commit) ke preferences terenkripsi — hanya agar baris status bisa menampilkan kode. Kini status membaca lewat penolong baca-saja baru `PreferencesManager.currentOwnerPairCode()`: kode tersimpan ditampilkan hanya bila formatnya sah dan belum kedaluwarsa 24 jam (aturan kesegaran yang sama seperti jalur pairing, termasuk kasus lama tanpa stempel waktu); kode yang sudah kedaluwarsa tidak lagi ditampilkan basi. Pembuatan kode tetap terjadi di titik kebutuhan sebenarnya: tombol tautan pairing di Setup dan penanganan `/start` di `MonitoringService`. Konstanta `PAIR_CODE_REGEX` yang tak lagi terpakai ikut dihapus.
- Cabang mulai-service `SDK >= O ? startForegroundService : startService` kini satu penolong bersama `ServiceStart.startForeground(context, intent)` (#66): 15 situs yang tadinya menyalin cabang yang sama persis (BootRestartWorker, Liveness, BootReceiver, InstantEventReceiver, AdminReceiver ×2, ShotConsentActivity, SetupActivity ×2, MainActivity ×2, FgsWakeActivity, NotificationForwarderService ×2, MonitoringService) kini memanggil penolong itu dengan context dan intent yang sama seperti sebelumnya. Satu situs sengaja TIDAK disatukan: jalur revive di `SetupActivity` punya penanganan gagal khusus (jadwalkan boot-restart lalu lempar ulang) yang tak dimiliki cabang polos. Kembaran `openAppInfo` di `SetupActivity` juga dihapus — perilakunya identik dengan `openAppSettings` (layar detail aplikasi + toast yang sama), dan satu-satunya pemanggilnya kini memakai `openAppSettings`.

### Changed
- Regex format jam jam-malam kini satu sumber (#73): pola `^([01]\d|2[0-3]):[0-5]\d$` yang tadinya diduplikasi persis di `CurfewLogic.TIME_REGEX` (privat) dan `MonitoringService.CURFEW_REGEX` (sisa ekstraksi tahap-7) kini hanya hidup di `CurfewLogic`, dibuka lewat validator publik baru `CurfewLogic.isValidTime(text)`; situs validasi argumen perintah di `MonitoringService` memakainya dan `CURFEW_REGEX` dihapus. Uji `validTimeFormat` baru di `CurfewLogicTest` mengunci formatnya (termasuk `24:00`, `9:30`, dan `21:60` yang tertolak).
- Rumus jam-lokal kini satu sumber (#72): `DigestSchedule.localHour` sebelumnya memakai pembagian terpotong yang bisa menghasilkan jam negatif untuk jumlah waktu+offset negatif, sementara `A11yUsageMath.localHour` memakai floor-mod yang selalu 0–23. Kini `DigestSchedule.localHour` mendelegasikan ke `A11yUsageMath.localHour` (offset `Long`→`Int` aman: semua pemanggil meneruskan offset zona waktu yang memang berasal dari `Int`). Kesetaraan dibuktikan lewat port Python: 5.727 pasangan (waktu × offset −12j..+14j) dengan jumlah ≥ 0 memberi hasil identik, termasuk 6 kasus terkunci `DigestScheduleTest`; satu-satunya wilayah beda adalah jumlah negatif (stempel pra-1970, tak terjangkau produksi).

### Fixed
- `SmsNumbers.isPremium` kini mengecualikan nomor darurat pada semua varian (#71): sebelumnya pengecualian hanya memeriksa digit mentah, sedangkan uji premiumnya memakai varian internasional/lokal — akibatnya nomor seperti `0112` terklasifikasi premium padahal `isEmergency("0112")` benar, dan karena pemanggil di `MonitoringService` memeriksa premium lebih dulu, perintah `/sms 0112 …` tertolak dengan pesan alasan yang salah (pengiriman tetap terblokir di kedua jalur). Pengecualian kini memeriksa ketiga varian persis seperti `isEmergency`; persamaan redundan `local == "1900"` yang sudah tercakup `startsWith("1900")` ikut dihapus. Uji baru `emergencyVariantsAreNotPremium` mengunci `0112`/`00911`/`0118`/`00112` sebagai bukan-premium (nilai harapan dibuktikan dulu lewat port Python atas 15 kasus: hanya keluarga varian-darurat itu yang berubah, hasil lain identik).

### Removed
- Sumber daya bool yatim `shot_a11y_service_enabled` dihapus (#70): `res/values/bools.xml` hanya berisi bool itu, dirujuk satu-satunya oleh `AndroidManifest.xml`, dan tidak pernah ditimpa varian/flavor mana pun (repo ini memang tanpa flavor). Manifest kini menulis `android:enabled="true"` secara langsung pada `ShotAccessibilityService` — nilai efektifnya sama persis — dan berkas `bools.xml` yang kosong guna itu dihapus.

### Fixed
- Dialog kode restore kini memakai padding berbasis dp (#69): kedua tampilannya (`TextView` info dan `Switch` mode ketat) sebelumnya memakai `setPadding(32, 9, 32, 9)` piksel mentah sehingga bantalan menyusut relatif di layar berkerapatan tinggi; kini angka yang sama dikonversi lewat `resources.displayMetrics.density` seperti dialog PIN di berkas yang sama — proporsi tampilan yang dimaksud penulis dipertahankan, hanya satuannya yang benar.
- `SetupActivity.saveSettings` tidak lagi menelan ketikan interval yang tidak valid (#68): sebelumnya masukan bukan-angka pada kolom interval sinkron/kamera diam-diam diganti nilai tersimpan lalu lolos cek rentang, sehingga toast "tersimpan" muncul padahal ketikan pengguna diabaikan. Kini aturannya sama persis dengan `testConnection`: kolom yang diisi wajib ter-parse sebagai angka dan berada dalam rentang (sinkron 1–1440, kamera 0–60) atau penyimpanan dibatalkan dengan toast `setup_bad_interval`; kolom kosong tetap berarti "pakai nilai tersimpan".
- Gerbang `tools/verifikasi-path.py`: detektor metode-terhapus tidak lagi salah tangkap pada fragmen pernyataan alur kendali — baris hapus seperti `else startService(intent)` (cabang ekspresi-tunggal) sebelumnya terbaca sebagai "metode `startService` terhapus" karena slot "tipe"-nya terisi kata `else`; kini slot yang seluruhnya hanya kata alur kendali (`KATA_ALUR`: `else`/`if`/`while`/…) dilewati. Terverifikasi pada 6 kasus sintetis: deklarasi asli (`private fun …`, `override fun …`, gaya Java `static …`) tetap tertangkap, fragmen `else …`/`if …` dan panggilan bereceiver tidak lagi tertangkap.
- `A11yMonitor.defaultLauncherPackage` tidak lagi menahan paket launcher selamanya (#65): hasil resolusi kini diberi masa berlaku 10 menit lalu di-resolusi ulang, sehingga bila pengguna mengganti launcher bawaan, paket launcher lama tidak terus diperlakukan sebagai launcher (yang mengotori klasifikasi waktu-layar). Hasil kosong (gagal resolusi) tetap tidak di-cache dan dicoba lagi pada panggilan berikutnya, seperti sebelumnya.

## [1.6.499] - 2026-10-27

### Fixed
- Tulis preferences hitungan notifikasi-dibuang tidak lagi dilakukan di dalam `dropNoticeLock` (#64): ketiga situs di `NotificationForwarderService` (`onDestroy`, `noteDroppedNotification`, `flushDropNotice`) kini mengambil snapshot nilai di dalam kunci lalu menulisnya (commit prefs terenkripsi sinkron) di luar kunci, sehingga utas lain yang menunggu kunci yang sama tidak ikut tertahan selama IO penyimpanan. Nilai yang ditulis adalah snapshot pada momen yang sama seperti sebelumnya.

### Changed
- `MonitoringService.isKnownContact` kini memakai cache tingkat service (#63): hasil query provider kontak per nomor disimpan (maksimum 512 entri, disegarkan tiap 6 jam), sehingga nomor yang sama tidak lagi ditanyakan berulang ke provider pada setiap siklus sinkron / halaman riwayat / halaman status. Hanya hasil definitif yang disimpan — jawaban fail-open `true` karena pengecualian atau kursor null tidak masuk cache, jadi kegagalan sesaat tidak membeku jadi "dikenal".
- Pembungkus dispatch update Telegram kini satu penolong bersama (#62): pola `launch + handled + tangkap-ganda + finally finishUpdateTrack` yang tadinya ditulis tiga kali di `pollTelegramCommands` (callback, login, perintah) dipindah ke `MonitoringService.dispatchTracked(updateId, logTag, block)`; ketiga situs kini hanya menyerahkan badan kerjanya (termasuk kunci perintahnya masing-masing) dan tag log-nya, dengan pesan log serta semantik penanda handled yang sama.
- Long-poll `getUpdates` kini punya klien sendiri, `TelegramPollClient` (#60): sebelumnya polling 30 detik berbagi dispatcher klien teks (`maxRequestsPerHost = 4`), sehingga satu slot selalu terisi permanen dan hanya menyisakan tiga untuk lalu lintas kirim/kontrol. Klien poll membawa dispatcher sendiri (2 per host) dengan timeout yang sama persis dengan klien teks; kedua situs panggil di loop polling `MonitoringService` kini memakainya. Dampak latensi sebelumnya berstatus dugaan (belum terukur); perubahan ini memisahkan anggaran konkurensinya secara struktural.
- `BASE_URL` Telegram kini satu konstanta bersama di `SharedNet` (#61): literal `"https://api.telegram.org/"` yang tadinya dideklarasikan dua kali (di `TelegramClient` dan `TelegramMediaClient`) diangkat ke `SharedNet.BASE_URL` dan kedua klien memakainya. Dua tumpukan Retrofit/OkHttp tetap terpisah dengan sengaja karena profil timeout-nya memang berbeda (teks 90 detik vs media 600 detik).

### Fixed
- `BootRestartWorker`: penjadwalan kirim pesan pada cabang service-sedang-berjalan kini terbungkus try/catch seperti pemanggilan tetangganya (#59) — sebelumnya satu-satunya panggilan tanpa pelindung di `doWorkInternal`, sehingga pengecualian saat menjadwalkan lolos ke penangkap luar dan memicu retry worker sampai 5 kali (menjalankan ulang flush laporan crash dan penjadwalan liveness) padahal keadaan sebenarnya sehat.

### Changed
- Kondisi gerbang "PIN aktif, owner belum login" di `SendMessageWorker` kini satu penolong bersama (#58): `ownerLoginPending()` dipakai oleh `guardPreconditions` dan oleh pemeriksaan ulang di tengah loop kirim, dan idiom jadwal-ulang 30 menit yang tadinya ditulis di tiga cabang kini lewat `rescheduleIn30Min(...)` (syarat antre-tidak-kosong dipertahankan per cabang lewat parameter). Aksi tiap titik tidak berubah: gerbang awal menjadwal ulang, pemeriksaan tengah loop berhenti.

### Fixed
- Throttle penanda kirim-ok `SendMessageWorker` kini benar-benar bekerja lintas run (#57): `lastSendOkNoteAt` dan `healStageCleared` dipindah dari field instance ke companion object — WorkManager membuat instance worker baru setiap eksekusi, sehingga throttle 30 detik dan penanda tahap-heal sebelumnya selalu mulai dari nol di setiap run. Semantik di dalam satu run tidak berubah.

### Changed
- Pola commit sinkron coba-dua-kali di `PreferencesManager` kini satu penolong bersama (#56): enam setter kursor/id (`setLastUpdateIdSync`, `setSmsCursorSync`, `setCallCursorSync`, `resetSmsCursorSync`, `resetCallCursorSync`, `setLastSmsSendAtSync`) yang tadinya masing-masing menulis `commit() || commit()` yang identik kini membangun editornya lewat `commitWithRetry(...)`; semantik persis dipertahankan (percobaan kedua hanya bila commit pertama mengembalikan false, pengecualian -> false).
- Persistensi antrean pesan kini memakai cache serialisasi per pesan (#55): `MessageQueue.writeSnapshot` tidak lagi menjalankan Gson atas seluruh antrean pada setiap flush — JSON tiap pesan yang tidak berubah dipakai ulang dari cache (kunci id + kesetaraan data class), hanya pesan baru/berubah yang diserialisasi, dan string JSON akhir yang ditulis ke preferences identik dengan hasil `gson.toJson(snapshot)` sebelumnya. Throttle 2 detik, penundaan main-thread, dan drain berversi yang sudah ada tidak berubah.

## [1.6.498] - 2026-10-27

### Changed
- Klasifikasi respons kirim Telegram kini satu sumber bersama (#54): `utils/TelegramSendPolicy.classify` memutuskan jenis hasil (terkirim / rate-limit + retry-after / auth gagal / chat migrasi / chat hilang / hak terbatas / parse-entities / transient / ditolak) untuk `MonitoringService.sendToTelegram` + `sendPlainFallback` dan `NotificationForwarderService.forwardLocked` + fallback-nya; keempat jalur tinggal menjalankan aksinya masing-masing (antre, antre terjadwal, buang, tandai error kredensial) berdasarkan jenis itu. Dua penyimpangan lama diselaraskan: penerus notifikasi kini juga membersihkan error kredensial tersimpan sesudah kirim berhasil (sebelumnya hanya jalur service), dan 400 "can't parse entities" kini juga memicu fallback teks polos di penerus notifikasi (sebelumnya hanya bila unescape mengubah teks; mode parse NFS memang HTML sehingga kesalahan ini terjangkau). Pembacaan badan error 429 di jalur utama service kini terjaga try/catch seperti jalur lain. `SendMessageWorker` mempertahankan aliran `SendOutcome` sendiri. Uji JVM `TelegramSendPolicyTest` mengunci tabel klasifikasinya.
- `BootRestartWorker` kini memakai `FgsTypes.base(...)` untuk tipe foreground dasar (#53): salinan lokal `fgsType()` yang tertinggal dari ekstraksi tahap 5 issue #7 dihapus (namanya dicatat di `tools/bekas-hapusan.txt`), sehingga kebijakan tipe FGS dasar hanya punya satu sumber kebenaran.
- Penanganan rate-limit Telegram di `SendMessageWorker` kini satu penolong bersama (#52): blok 429 (baca badan error -> `parseRetryAfter` -> `RateLimited`) yang tadinya ditulis tiga kali untuk jalur utuh/per-chunk/plain-fallback dipindah ke `rateLimited(...)`, dan URL `sendMessage` yang dirangkai di empat tempat kini lewat `sendMessageUrl(...)`. Baris log "Rate limited, retrying after ..." yang sebelumnya hanya ada di jalur utuh kini berlaku di ketiga jalur; hasil akhir tiap jalur tidak berubah.
- Pola baca preferences disatukan ke lima penolong privat di `PreferencesManager` (#51): 105 getter properti yang tadinya menulis tangan pola `try { getX(kunci, bawaan) } catch { bawaan }` kini memanggil `prefString`/`prefLong`/`prefInt`/`prefBoolean`/`prefFloat`, sehingga perilaku "bawaan berlaku senyap bila penyimpanan melempar" hidup di satu tempat per jenis. Inventaris (kunci, jenis, nilai bawaan) seluruh getter dibuktikan identik sebelum dan sesudah migrasi — tidak ada nilai bawaan yang berubah.
- Strategi pencarian riwayat per nomor kini satu implementasi bersama (#50): tahap eksak -> fuzzy -> pindai berhalaman 4x500 dengan cutoff 365 hari yang tadinya ditulis dua kali penuh di `SmsRepository` dan `CallLogRepository` dipindah ke `repository/NumberHistorySearch.kt` generik; kedua repository tinggal mengikat kolom, fungsi query, dan pengambil nomor/tanggal/id masing-masing. Kedua badan lama dibuktikan identik baris demi baris sesudah pemetaan (kolom, fungsi query, penapis, dan kepala loop `repeat(4)` -> `for` yang indeksnya memang tidak dipakai) sebelum digabung, jadi perilaku pencarian tidak berubah. Logika `getNewSms`/`getNewCalls` tetap terpisah per provider karena struktur cabang kursornya memang berbeda.
- Verifikasi kredensial Telegram di Setup kini satu jalur bersama (#49): urutan `getMe` -> `getChat` -> resolusi chat `@username` dari respons yang sama dipindah ke `SetupActivity.verifyCredentials` dengan hasil bertipe `VerifyOutcome` (sukses / gagal HTTP per tahap / error jaringan / basi). Jalur Simpan dan jalur Tes tinggal bereaksi terhadap hasilnya — toast, penanda error kredensial, jalur simpan-offline Simpan, dan kebiasaan Tes yang berbeda antara gagal `getMe` (berhenti) dan gagal `getChat` (tetap menyegarkan status) dipertahankan persis seperti sebelumnya.

### Fixed
- Perbaikan kompilasi susulan untuk #50: pemanggilan fungsi query di `NumberHistorySearch` kini memakai argumen posisional — Kotlin tidak mengizinkan argumen bernama pada nilai bertipe fungsi (lambda), hanya pada deklarasi fungsi; urutan dan nilai argumen tidak berubah.
- Gerbang `tools/verifikasi-path.py`: detektor "metode dihapus" tidak lagi mengekstrak nama dari baris panggilan berindentasi murni (slot "tipe" pada polanya bisa dipenuhi spasi indentasi saja, sehingga panggilan pustaka baku seperti `repeat(4) {` pada baris yang dihapus terbaca sebagai metode proyek yang hilang dan menggagalkan pemeriksaan). Pola kini mewajibkan slot tipe berakhir pada karakter kata; definisi metode sungguhan tetap terdeteksi.
- PIN akses hasil impor backup tidak lagi terpakai diam-diam pada penyimpanan berikutnya (#48): PIN tertunda restore kini dibersihkan setiap kali halaman Setup dibuka segar (tanpa state tersimpan), sejalan dengan owner tertunda restore yang memang per-instance. Sebelumnya PIN backup yang batal disimpan bertahan di holder statis seumur proses dan menggantikan PIN tersimpan saat kolom PIN bermasker disimpan.

## [1.6.497] - 2026-10-27
### Changed
- Penyelesaian teks tampilan domain terekstrak (#7, tahap 9): teks nama tipe FGS pindah dari `MonitoringService.fgsTypesText` ke `FgsTypes.describe`, dan inti format status pagar pindah ke `GeofenceLogic.statusText` (pembungkus `fenceStatusText` di service tetap membaca preferences seperti sebelumnya). Nilai keluaran dibuktikan sama dengan porting Python untuk seluruh kombinasi bit tipe dan kasus pagar (koordinat contoh, penanda belum diatur, radius 0).

### Added
- Kasus uji `describe` pada `FgsTypesTest` dan `statusText` pada `GeofenceLogicTest`.

## [1.6.496] - 2026-10-27
### Changed
- Pemisahan hash SHA-256 (#7, tahap 8): fungsi `sha256Hex` (hash sidik kredensial untuk registrasi perintah bot) pindah verbatim dari `MonitoringService` ke `utils/Sha256Hex.kt` sebagai `Sha256Hex.hex`, beserta konstanta `HEX_DIGITS` yang hanya dipakainya; satu-satunya situs panggil kini memakai util itu. Badan fungsi identik baris demi baris dengan aslinya (dibangun skrip dari teks berkas lama).

### Added
- Uji JVM `Sha256HexTest` dengan vektor SHA-256 baku (string kosong, "abc", dan bentuk string sidik kredensial).

## [1.6.495] - 2026-10-27
### Changed
- Pemisahan logika jam malam (#7, tahap 7): perhitungan jendela jam malam (termasuk sifat bawaan bahwa jam malam 05:00–11:59 tidak pernah aktif) dan aturan jam sekolah pindah dari `MonitoringService.checkCurfew` ke fungsi murni `utils/CurfewLogic.kt` (`inWindow`, `schoolHours`); predikat pagar rumah/sekolah kini memakai `GeofenceLogic.configured`, dan rumus jam lokal di `hourOf`/`isNightTime`/`checkBegadang` disatukan ke `DigestSchedule.localHour`. Baca preferences, pengambilan lokasi, dan titik kirim tetap di service; kesetaraan perilaku dibuktikan dengan tabel kebenaran (4.568 kombinasi masukan, 0 beda antara logika lama dan baru).

### Added
- Uji JVM `CurfewLogicTest` untuk jendela jam malam malam-hari/dini-hari, kasus jam malam pagi yang tidak pernah aktif, string jam tidak sah, dan aturan jam sekolah.

## [1.6.494] - 2026-10-27
### Changed
- Pemisahan laporan izin (#7, tahap 6): perhitungan masker izin (termasuk aturan warisan bit lokasi-latar/notifikasi dari masker tersimpan), daftar label izin yang hilang, dan teks inventaris izin pindah dari `MonitoringService` ke fungsi murni `utils/PermReport.kt` (`mask`, `missingLabels`, `inventoryText`); label tetap milik service dan diteruskan sebagai parameter. Pembacaan status izin mentah dan titik kirim tetap di service; kesetaraan perilaku dibuktikan dengan tabel kebenaran (13.312 kombinasi masukan, 0 beda antara logika lama dan baru).

### Added
- Uji JVM `PermReportTest` untuk semantik masker izin (termasuk kasus warisan masker tersimpan `-1`), urutan label yang hilang, dan bentuk teks inventaris.

## [1.6.493] - 2026-10-27
### Changed
- Pemisahan keputusan tipe FGS (#7, tahap 5): perhitungan tipe foreground service (tipe dasar per SDK, gabungan izin kamera/mikrofon/lokasi/proyeksi, tangga penurunan tipe, dan cooldown bangun kamera) pindah dari `MonitoringService` ke fungsi murni `utils/FgsTypes.kt` (`base`, `compute`, `ladder`, `hasCamera`, `wakeCooldownMs`). Pemanggilan `startForeground`, tulis state, dan aktivitas bangun tetap di service; kesetaraan perilaku dibuktikan dengan tabel kebenaran (134 kombinasi masukan, 0 beda antara logika lama dan baru).

### Added
- Uji JVM `FgsTypesTest` untuk tipe dasar per SDK, gabungan izin, urutan tangga penurunan tipe, dan batas cooldown bangun kamera.

## [1.6.492] - 2026-10-27
### Changed
- Pemisahan keputusan jadwal digest (#7, langkah desain kedua): putusan kirim/tunggu/reset digest berkala 6 jam dan aturan digest malam (jam lokal + sekali per tanggal mulai 21:00) pindah dari `MonitoringService.maybeSendDigest`/`maybeSendNightDigest` ke fungsi murni `utils/DigestSchedule.kt` (`periodicDecision`, `localHour`, `nightDue`). Baca/tulis preferences dan titik kirim tetap di service pada struktur yang sama (termasuk tulis stempel sebelum kirim); kesetaraan perilaku dibuktikan dengan tabel kebenaran (118 kombinasi masukan, 0 beda antara logika lama dan baru).

### Added
- Uji JVM `DigestScheduleTest` untuk putusan digest berkala (batas tepat 6 jam, stempel masa depan), jam lokal lintas offset zona waktu (termasuk batas 21:00 Jakarta), dan aturan digest malam.

## [1.6.491] - 2026-10-27
### Changed
- Pemisahan keputusan geofence (#7, langkah desain pertama sesudah jalur verbatim habis): syarat transisi masuk/keluar area, syarat kena jemput, dan predikat pagar aktif pindah dari `MonitoringService.checkGeofence`/`checkGeofenceFallback` ke fungsi murni `utils/GeofenceLogic.kt` (`transition`, `jemputHit`, `configured`). Baca/tulis preferences, kunci `fenceLock`, dan titik kirim peringatan tetap di service pada struktur yang sama; kesetaraan perilaku dibuktikan dengan tabel kebenaran (757 kombinasi masukan, 0 beda antara logika lama dan baru).

### Added
- Uji JVM `GeofenceLogicTest` untuk mesin transisi geofence (adopsi state tanpa gerbang akurasi, perubahan tergerbang akurasi <= radius), batas kena jemput, dan predikat konfigurasi pagar.

## [1.6.490] - 2026-10-27
### Changed
- Tahap 4 pemecahan `MonitoringService.kt` (#7): `sparkline` pindah ke `utils/Sparkline.kt` (`render`) dengan bodi identik, dipakai digest mingguan untuk grafik aktivitas per jam. Inti geofence/digest selebihnya terikat state bersama (preferences, kunci pagar, pengiriman pesan) dan tidak dipindah verbatim — menunggu desain pemisahan state.

### Added
- Uji JVM `SparklineTest` untuk grafik batang aktivitas (skala terhadap puncak, nilai nol dan negatif, pola 24 jam).

## [1.6.489] - 2026-10-27
### Changed
- Tahap 3 pemecahan `MonitoringService.kt` (#7): `editDistance`, `transposedEquals`, `suggestCommand` beserta himpunan `KNOWN_COMMANDS` pindah ke `utils/CommandSuggest.kt` dengan bodi identik, dipakai jalur perintah tak dikenal untuk saran ejaan perintah.

### Added
- Uji JVM `CommandSuggestTest` untuk jarak edit, deteksi transposisi satu tukar, dan perilaku saran perintah (batas panjang, perintah persis tanpa saran, dan seri yang dimenangkan urutan himpunan).

## [1.6.488] - 2026-10-27
### Fixed
- Gerbang `tools/verifikasi-path.py` kini mengenali rujukan metode `::nama` (#41): nama yang sudah dihapus (daftar `bekas-hapusan.txt`) yang dihidupkan lagi sebagai method reference tidak lagi lolos dari cek baris baru maupun pendeteksian pemanggil metode yang dihapus.
- Cek resource `tools/verifikasi-path.py` kini mengupas komentar XML sebelum memindai (#42): definisi di dalam `<!-- -->` tidak lagi memicu "definisi ganda" palsu, menutupi acuan yatim sungguhan, atau mengganggu sinkronisasi terjemahan dan paritas format.
- Cek anotasi ganda `scripts/cek-cepat.sh` kini membandingkan baris yang sudah dirapikan indentasinya (#43): dua anotasi tak-repeatable yang sama pada kedalaman indentasi berbeda tidak lagi lolos.
- Pola ekstensi terlarang `scripts/cek-cepat.sh` kini tak peka huruf besar/kecil (#44), selaras `verifikasi-path.py`: berkas seperti `KUNCI.P12` kini tertangkap di cek staged maupun pohon terlacak.

## [1.6.487] - 2026-10-27
### Fixed
- Lima throttle notifikasi berbasis jam dinding kini tahan jam mundur (#32), mengikuti pola yang ditambahkan #21 ke digest: stempel tersimpan yang berada di masa depan direset ke sekarang dan notifikasi dilewati siklus ini — mencakup notifikasi hemat kuota (`dataSaverHoldNow`, sebelumnya justru mengirim pada `now <= last`), alert baterai, kegagalan kamera, kegagalan unggah media, dan kesehatan aksesibilitas.
- Listener foto `CameraService` kini didaftarkan pada `ImageReader` lokal milik sesi itu sendiri (#33), bukan pada field bersama yang dibaca di luar kunci sesi — sesi yang digantikan di tengah setup tidak lagi bisa menempelkan listenernya ke reader sesi baru.
- `chooseVideoSize` kini memilih ukuran terkecil sebagai jalan terakhir bila tak ada ukuran ≤720p (#34), bukan yang terbesar — perangkat yang hanya mengekspos ≥1080p tidak lagi merekam pada resolusi maksimal.
- Penjaga aktivasi diseragamkan di semua pintu masuk (#35): `InstantEventReceiver` kini juga memeriksa persetujuan pemantauan dan status jeda; `Liveness.allowed` serta jalur bangun `NotificationForwarderService` kini juga memeriksa status jeda — monitoring yang dijeda tidak lagi dibangunkan oleh pintu-pintu itu.
- `AdminReceiver.onPasswordFailed` kini punya jalur cadangan antrean pesan (#36) seperti `onDisabled`/`onDisableRequested`: bila memulai service gagal, peringatan PIN salah tetap diantrekan ke `MessageQueue` agar tidak hilang.
- Callback `SetupActivity.askPin` kini dijaga bendera selesai (#45): menekan Batal tidak lagi memanggil callback dua kali (tombol negatif + `OnCancelListener`).

## [1.6.486] - 2026-10-27
### Fixed
- `/login <PIN>` yang terketik di chat grup kini dicoba dihapus dari grup dan pengirim diperingatkan bahwa PIN terlihat anggota grup (#30); sebelumnya pesan berisi PIN menetap di riwayat grup selama owner belum tertaut, sehingga anggota grup mana pun bisa memakai PIN itu untuk tertaut sebagai owner lebih dulu.
- Pesan berisi PIN kini dihapus sebelum cek lockout/throttle login (#39), bukan hanya di jalur yang sampai ke verifikasi — PIN yang dikirim saat login terkunci tidak lagi menetap di riwayat chat.
- Jalur petunjuk pairing dan petunjuk login kini menandai `offsetDeferred` sebelum `finishUpdateTrack` (#40), sehingga blok `finally` loop polling tidak memanggilnya dua kali untuk update yang sama.
- Umur pesan hasil edit kini memakai `edit_date` bila lebih baru (#37), selaras jalur callback: perintah yang diedit tidak lagi ditolak kedaluwarsa dari tanggal kirim asli.
- Angka Delay pada `/ping` kini dihitung dari waktu server Telegram (`serverNowSec`) (#38), sisa terakhir pola jam lokal vs tanggal pesan dari perbaikan #9.

## [1.6.485] - 2026-10-27
### Fixed
- Jalur gagal `MessageQueue.drainPersist()` tidak lagi meninggalkan snapshot kotor tanpa penulis (#31): tulis yang gagal kini memulihkan snapshot lalu menjadwalkan drain ulang tertunda 1 detik pada executor persist yang sama, sehingga pesan tidak hilang/duplikat bangkit hanya karena proses mati sebelum mutasi antrean berikutnya.
- Deadline 429 tersimpan di `NetworkUtils.noteRateLimited` kini dibaca-dibandingkan-ditulis di bawah kunci yang sama (#46): dua komponen yang menerima 429 bersamaan tidak lagi bisa saling menimpa dengan deadline yang lebih pendek.
- Keluarga fungsi `*Sync` di `PreferencesManager` (`setLastUpdateIdSync`, `setSmsCursorSync`, `setCallCursorSync`, `resetSmsCursorSync`, `resetCallCursorSync`, `setLastSmsSendAtSync`) kini mengembalikan hasil `commit()` dan mencoba sekali lagi bila tulis pertama gagal (#47), mengikuti pola `setChatIdSync` — kegagalan durable tidak lagi tertelan diam-diam.

## [1.6.484] - 2026-10-27
### Fixed
- Kursor riwayat SMS/panggilan tidak lagi melewatkan baris selamanya (#29): kemajuan kursor kini mengikuti pasangan baris terakhir yang terkonsumsi dalam urutan (tanggal, id) selama pengambilan/pengiriman, dan hanya diselesaikan ke maksimum per bidang bila seluruh baris yang cocok sudah terkonsumsi (`utils/CursorAdvance`). Sebelumnya maksimum id dan tanggal diambil terpisah dari halaman yang sama, sehingga baris hasil restore/migrasi di sudut kedua kursor tersaring keluar permanen.

### Added
- Uji JVM `CursorAdvanceTest` yang mensimulasikan pengambilan + kemajuan kursor (kasus sudut restore, banjir restore, kursor diagonal).

## [1.6.483] - 2026-10-25
### Changed
- Tahap 2 pemecahan `MonitoringService.kt` (#7): `photoStats` dan `hammingDistance` pindah ke `utils/PhotoFingerprint.kt` (`analyze`, `hammingDistance`) dengan bodi identik, dipakai jalur foto untuk deteksi gelap dan foto duplikat.

### Added
- Uji JVM `PhotoFingerprintTest` untuk jarak Hamming sidik jari foto.

## [1.6.482] - 2026-10-25
### Changed
- Enam helper murni dipindah keluar dari `MonitoringService.kt` tanpa mengubah perilaku (bodi identik): `durationText`, `formatBytes`, `distanceMeters`, `isCameraPolicyError` ke `utils/ReportFormat.kt`; `isEmergencySmsNumber`/`isPremiumSmsNumber` ke `utils/SmsNumbers.kt`. Tahap pertama pemecahan kelas raksasa (#7).

### Added
- Uji JVM `ReportFormatTest` dan `SmsNumbersTest` untuk helper yang dipindah.

## [1.6.481] - 2026-10-25
### Changed
- `MessageQueue.addMessage`/`addMessages` tidak lagi memaksa serialisasi + `commit()` seluruh antrean tiap pesan; perubahan dalam jendela 2 detik digabung ke satu tulis asinkron, sementara tulis sinkron tetap dilakukan minimal tiap 2 detik sebagai jangkar durabilitas.

### Fixed
- `tools/bekas-hapusan.txt` mencatat metode uji yang diganti nama agar penjaga regresi tetap akurat.
- Rilis otomatis menemukan APK lagi setelah `mapping.txt` dikeluarkan dari artifact (`release-assets/redeye-*.apk`).

## [1.6.480] - 2026-10-25
### Fixed
- Uji `NotificationPrivacyTest` disesuaikan dengan penyamaran deretan 13 digit atau lebih, sehingga build 1.6.478/1.6.479 tidak lagi gagal di tahap unit test.

## [1.6.479] - 2026-10-25
### Fixed
- Teks fallback "tak ada jaringan" memakai resource terlokalisasi, dan footer kesehatan locale Indonesia menyebut label tombol yang benar.
- Guard `fieldsLoaded` di Setup kini benar-benar menahan Simpan/Tes/link pairing sampai field termuat; job status lama dibatalkan sebelum job baru agar tidak menumpuk.
- Perintah mutasi dari chat grup kini dibalas petunjuk `/login <PIN>`/private chat, tidak lagi diam tanpa penjelasan.
- Foto tidak lagi diblokir selama rekaman audio/unggahannya; pesan sibuk menyebut jenis rekaman yang benar dan unggah audio dibatasi 5 menit agar tidak menahan kamera sampai timeout.

### Security
- Distribusi Gradle diverifikasi SHA-256 sebelum diekstrak, cache memakai kunci berisi checksum, dan `distributionSha256Sum` ditambahkan di wrapper.
- `mapping.txt` tidak lagi diunggah ke artifact publik `apks`.

## [1.6.478] - 2026-10-25
### Fixed
- Backoff `429` disimpan di preferensi, sehingga pembatasan laju Telegram tetap dihormati setelah proses aplikasi mati atau perangkat reboot.
- Surface metering kamera mengonsumsi frame lewat `updateTexImage`, sehingga buffer tidak menumpuk dan AE/AWB lebih stabil.
- Tombol menu yang meminta input (`AskInput`) ikut kedaluwarsa setelah 5 menit seperti tombol perintah lain.
- Snapshot aksesibilitas dibaca di thread executor, bukan di thread utama accessibility.
- `tools/verifikasi-path.py` gagal saat `--rentang` menunjuk revisi yang tidak ada, alih-alih memeriksa seluruh `app/` lalu LOLOS.
- Penyamaran digit di notifikasi kini mencakup deretan 13 digit atau lebih (nomor kartu/NIK/rekening).
- Ringkasan: `digestAt` di masa depan tidak lagi mematikan throttle, dan `/digest` manual memperbarui penanda agar ringkasan otomatis tidak langsung menyusul.
- Geofence fallback menandai percobaan walau fix GPS gagal (tidak lagi mencoba tiap siklus), dan cek jam malam/sekolah tidak mengambil lokasi bila alert hari itu sudah terkirim.
- `/bersihkan` menerima `media`, `foto`, `video`, atau `rekaman` dan menghapus sesuai jenisnya.

## [1.6.477] - 2026-10-25
### Fixed
- `SendMessageWorker` berhenti di tengah antrean tanpa membersihkan pesan yang sudah terkirim saat PIN aktif dan owner belum login; pesan terkirim tidak lagi dikirim ulang.
- `ShotConsentActivity` mengabaikan permintaan izin kedua saat dialog MediaProjection masih berjalan dan memperbarui timer pengaman, sehingga dialog izin tidak terbuka ganda.
- Media (foto/video/rekaman) tidak lagi dihapus saat owner belum login; berkas disimpan dan dikirim setelah login. Laporan berkas yang dibuang tidak lagi menyebut "ditolak Telegram (400)" untuk semua kasus.
- PIN backup naik dari 6 digit menjadi minimal 10 karakter dan menerima huruf, sehingga blob berisi token bot lebih tahan brute force; restore tetap menerima PIN legacy 4 digit.
- Dua bagian `[1.6.4]` duplikat di changelog digabung.

## [1.6.476] - 2026-10-25
### Fixed
- Offset `getUpdates` tidak lagi membeku: update yang sudah pernah ditangani tidak masuk daftar pending, dan jalur balasan `/start`/login menutup pelacakan update, sehingga perintah lama tidak dijalankan ulang setelah restart.
- Umur perintah dihitung memakai waktu server Telegram (header `Date`) alih-alih jam perangkat, sehingga jam perangkat yang meleset tidak lagi menolak semua perintah.
- Tombol Aksesibilitas di Setup memberi tenggang 2 menit pada proteksi tamper, sehingga halaman Aksesibilitas bisa dibuka tanpa alert palsu.
- Mengosongkan PIN akses kini benar-benar menghapus PIN; PIN hasil restore ditampilkan sebagai tersimpan dan dipertahankan sampai diubah.
- Tombol Tes tidak lagi langsung menyimpan setelan: setelah tes berhasil muncul konfirmasi Simpan.
- Job Simpan memakai urutan bersama antar-rotasi, sehingga hasil simpan lama tidak bisa menimpa setelan yang lebih baru.
- Penyiapan sesi kamera memeriksa ulang nomor sesi di dalam kunci sebelum memasang `ImageReader`/surface, dan sesi video yang digantikan melepas recorder serta berkas sementaranya.

## [1.6.475] - 2026-10-24
### Fixed
- Migrasi chat ID hanya dianggap berhasil jika commit SharedPreferences memastikan ID baru tersimpan.


## [1.6.474] - 2026-10-24
### Fixed
- Penghapusan pesan terkirim dan pencatatan batas retry antrean mencoba ulang persistensi; penghapusan gagal dilaporkan dan retry yang belum tersimpan tidak lagi dihitung sebagai pesan terbuang.


## [1.6.473] - 2026-10-24
### Fixed
- Peringatan notifikasi yang dibuang tetap tersimpan sampai enqueue antrean berhasil; kegagalan enqueue, flush yang sedang berjalan, dan penghentian service tidak lagi menghapus penghitungnya.


## [1.6.472] - 2026-10-24
### Fixed
- Respons 401/403 dari permintaan yang memakai kredensial lama tidak lagi memblokir kredensial baru.


## [1.6.471] - 2026-10-24
### Removed
- Hapus `SettingsBackup.looksLikeBackup()` yang sudah tidak memiliki pemanggil; pemeriksaan backup memakai `looksStructurallyValid()`.

## [1.6.470] - 2026-10-24
### Fixed
- Deduplikasi notifikasi memakai SHA-256 dengan input berpanjang eksplisit, sehingga teks berbeda dengan `hashCode()` yang sama tidak lagi dianggap notifikasi duplikat.

## [1.6.469] - 2026-10-24
### Fixed
- `/history` kini menyebut pencarian belum lengkap saat 2.000 entri terbaru habis dipindai tanpa hasil, agar hasil kosong tidak dikira pasti tak ada riwayat.
### Changed
- Pola pencarian fuzzy nomor kini dibangun di satu fungsi bersama, bukan duplikat di repository SMS dan panggilan.
- Pemeriksa metode terhapus mengabaikan pernyataan `return`, sehingga pemanggilan fungsi library seperti `emptyList()` tidak salah dianggap sebagai deklarasi yang dihapus.
### Removed
- Isi lama `builder.sh` dan `builder.bat` yang tak terjangkau setelah perintah berhenti dihapus; kedua skrip tetap menolak menjalankan alur build lama.

## [1.6.468] - 2026-10-24
### Changed
- Pemindaian aksesibilitas melepaskan node yang sudah dibaca dan node sisa pada Android sebelum API 33.

## [1.6.467] - 2026-10-24
### Fixed
- Penghapusan antrean mencoba format kosong cadangan jika penghapusan SharedPreferences gagal, dan perintah Setup/Telegram kini melaporkan bila antrean belum dapat dipastikan kosong.

## [1.6.466] - 2026-10-24
### Fixed
- Pemulihan antrean tidak lagi menggandakan penghitung pesan terbuang ketika pembacaan antrean gagal atau penyimpanan belum berhasil dipulihkan.

## [1.6.465] - 2026-10-23
### Fixed
- Membuka penyimpanan terenkripsi kini dicoba tiga kali dengan `MasterKey` baru sebelum jatuh ke mode volatil, sehingga kegagalan Keystore sesaat (misalnya saat boot) tidak langsung membuat kredensial tampak hilang.
- Membuka Setup atau menyimpan kredensial dari mode volatil kini mencoba membuka ulang penyimpanan terenkripsi tanpa menghapus data (dua percobaan dengan `MasterKey` baru), jadi kunci yang sempat tak tersedia bisa pulih tanpa mengorbankan antrean dan pengaturan.
- Reset penyimpanan terenkripsi dipisah ke jalur eksplisit `resetEncryptedStorage()` yang hanya dipakai tombol hapus kredensial; bila reset gagal, Setup melaporkannya terpisah dan tidak lagi menampilkan "kredensial dihapus" seolah semuanya beres.
### Added
- Status Setup dan laporan galat Telegram menampilkan alasan teknis kegagalan Keystore (jenis exception dan pesan singkat) agar kasus per perangkat bisa didiagnosis.
### Changed
- Pesan "penyimpanan aman tak tersedia" kini menyebut langkah perbaikan: simpan ulang, atau tahan tombol Simpan untuk mereset penyimpanan terenkripsi.

## [1.6.464] - 2026-10-23
### Changed
- Rilis GitHub kini gagal tegas bila pola APK tidak menemukan berkas (`fail_on_unmatched_files`), sehingga tag tanpa APK tidak lagi menghasilkan rilis kosong.
- README menyebut perilaku retry jaringan workflow yang sebenarnya (satu kali ulang, total dua percobaan), bukan 3x.

## [1.6.463] - 2026-10-23
### Fixed
- `/login` dan `/start` tanpa argumen tidak lagi menahan loop polling: penanganannya dijalankan di coroutine dengan `commandLock`, dan offset baru diselesaikan setelah login selesai.
- Callback tombol menu tidak lagi memegang `controlLock` selama `answerCallback`, `editMenuMessage`, atau pengiriman pesan; lock hanya dipegang saat eksekusi perintah dan `armInput`.
- Worker antrean pesan kini punya tenggat total 8 menit dan tenggat per pesan chunk; sisa pesan dijadwalkan ulang sehingga tidak melewati batas ±10 menit WorkManager.
- Rantai `scheduleMessageSendNext` dibatasi `MAX_OUTSTANDING_SEND` dengan reservasi atomik; slot dikembalikan bila enqueue gagal sehingga antrean tidak tumbuh tanpa batas.

## [1.6.462] - 2026-10-23
### Fixed
- Antrean pesan mode volatil kini melaporkan pesan yang benar-benar masuk antrean, sehingga `sendFitted` tidak lagi menganggapnya `DROPPED`, tidak mengulang kursor SMS, dan worker langsung dijadwalkan.
- Pesan kedaluwarsa tidak lagi dipertahankan setelah reboot: setiap pesan menyimpan penanda boot, dan pemeriksaan umur memakai waktu dinding untuk pesan dari boot sebelumnya.
- Restore lewat Telegram memakai validasi struktur file (`looksStructurallyValid`), sehingga file rusak tidak lagi dihitung sebagai PIN salah dan menghabiskan tiga percobaan.
- Pencarian riwayat untuk nomor pendek tidak lagi menjalankan `LIKE '%...%'` ke seluruh tabel; pencarian fuzzy hanya untuk nomor minimal 10 digit.
- Menyimpan pengaturan dengan kolom PIN kosong tidak lagi menghapus PIN akses yang tersimpan; perilakunya kini sama dengan tombol Tes.
- Hapus kredensial ikut membersihkan jejak lokasi 24 jam, titik rumah/sekolah, log audit, kata pantau, dan nomor pantau milik pemilik lama.

## [1.6.461] - 2026-10-23
### Fixed
- `/sms` tidak lagi menggabungkan nomor dengan kata pertama pesan saat pesan diawali angka; pemisah nomor/pesan dipilih dari token pertama yang sudah lengkap (minimal 7 digit), dan logikanya dipindah ke `SmsCommandParser` dengan tes unit.
- Sinkron awal tidak lagi dianggap selesai saat riwayat gagal dibaca: `ContentQuery` meneruskan galat pada mode `strict`, sehingga `initialSyncDone` baru diset setelah SMS dan panggilan benar-benar terbaca.
- Fallback storage rusak saat boot kini tercapai: `BootRestartWorker` memeriksa `isStorageEncrypted` sebelum consent, dan `BootReceiver` menjadwalkan worker (bukan membatalkan watchdog) saat storage tidak terenkripsi.
- Offset update tidak lagi macet permanen setelah satu kegagalan: `UpdateOffsetTracker` melacak update gagal per-id dan melanjutkan offset setelah update itu berhasil diulang; update yang sudah terlihat tidak lagi menyelesaikan offset lebih awal.
- Polling `getUpdates` yang sukses tidak lagi menghapus blokir kredensial sisi kirim; blokir 30 menit hanya pulih lewat kirim sukses, `sweepAuthBlock`, atau penggantian kredensial.

## [1.6.460] - 2026-10-22
### Fixed
- Warna tombol kalkulator kembali sesuai palet: tombol angka putih, tombol fungsi peach, dan tombol sama dengan hijau tua berteks putih. Tombol kini memakai `androidx.appcompat.widget.AppCompatButton` dan `backgroundTint` dinonaktifkan, sehingga drawable ripple kustom tidak lagi ditimpa `colorPrimary` dari tema Material.

## [1.6.459] - 2026-10-22
### Fixed
- PIN akses hasil restore backup tidak lagi ditulis ke `savedInstanceState` (yang bisa disimpan sistem ke disk); PIN ditahan sementara di memori proses sehingga rotasi layar tetap melanjutkan restore tanpa membocorkan PIN.
- `clearCredentials()` kini juga menonaktifkan Device Admin aplikasi, sehingga hak admin tidak tertinggal aktif setelah kredensial dihapus.
- `tools/verifikasi-path.py` membaca keluaran `git diff --check` dengan benar (exit 2 bukan berarti kosong) dan memeriksa rentang `--rentang` yang dipakai CI; spasi berlebih tidak lagi lolos diam.

## [1.6.458] - 2026-10-22
### Fixed
- `sendFitted()` kini membedakan `SENT`/`QUEUED`/`DROPPED`: pesan yang ditolak permanen oleh Telegram (400) tidak lagi dianggap terkirim, sehingga kursor SMS/panggilan dan laporan crash tidak maju/terhapus tanpa pengiriman.
- Offset update Telegram hanya disimpan untuk urutan pembaruan yang benar-benar selesai ditangani (lewat `UpdateOffsetTracker` baru + tes unit); pembatalan service di tengah handler tidak lagi melewati perintah yang belum diproses.
- Galat sisi kirim seperti "chat not found" (400) tidak lagi memblokir `getUpdates` selama 30 menit; hanya 401/403 yang menahan polling.
- `MessageQueue.flushSync()` tidak lagi menunggu barrier persist hingga 2 detik di main thread; penulisan tetap dijadwalkan ke executor latar sehingga service berhenti tanpa risiko ANR.
- Sesi tangkap layar `MediaProjection` melepas `VirtualDisplay`/`ImageReader` setelah setiap pengambilan (termasuk jalur gagal/timeout), sehingga surface tidak menumpuk sampai service dimatikan.
- Watchdog periodik `BootRestartWorker` tidak lagi berhenti permanen setelah 5 kegagalan beruntun; hasil akhirnya `success` agar jadwal 15 menit tetap berjalan.

## [1.6.457] - 2026-10-22
### Fixed
- Loop patroli ikut di-restart saat status jeda monitoring berubah, sehingga `/resume` langsung memicu patroli berikutnya alih-alih menunggu sisa interval lama (bisa sampai 3 jam).

## [1.6.456] - 2026-10-22
### Fixed
- `MessageScheduler` memakai `REPLACE` saat ada retry tertunda dan tidak ada worker yang sedang berjalan, sehingga pesan mendesak tidak lagi menunggu backoff hingga 15 menit; bila worker masih berjalan, rantai tetap diserialkan agar tidak ada kiriman ganda.
- `InstantEventReceiver` membaca preferensi dan memulai service di executor (`goAsync`), sehingga `onReceive` tidak lagi melakukan I/O di thread utama.
- `CameraService` membersihkan berkas sementara di thread latar saat dibuat, bukan di thread utama.
- Radius `/home` dan `/school` dibulatkan ke meter terdekat, tidak lagi dipotong `toInt()` (mis. 500,9 m tidak menjadi 500 m).

## [1.6.455] - 2026-10-22
### Fixed
- Build `1.6.454` gagal dikompilasi: `SmsRepository.smsDateForId()` kini memakai `sortOrder` non-null yang diterima `ContentQuery`, dan blok dispatch perintah memberi nilai `Unit` eksplisit agar `if` tidak dipakai sebagai ekspresi.
- `A11yMonitor` memindahkan pengambilan snapshot pemantau kata ke executor, sehingga penelusuran node aksesibilitas tidak lagi membebani thread utama.
- `testConnection` tidak menghapus PIN akses tersimpan saat kolom PIN dibiarkan kosong; PIN hanya berubah lewat simpan eksplisit atau hapus kredensial.
- `AppWatch` membatasi panjang kata kunci 3–64 karakter agar masukan sangat panjang tidak membebani pencocokan.
### Changed
- `.gitignore` dibersihkan dari pola duplikat (`*.class`, `.DS_Store`).
- `SYNC_INTERVAL` yang bukan angka atau di luar 1–1440 kini memberi peringatan di log build sebelum memakai nilai bawaan.

## [1.6.454] - 2026-10-22
### Fixed
- Kursor SMS/panggilan kini memakai `_ID` **dan** `DATE`: baris baru dengan id lebih kecil (restore/gabung basis data) tetap terkirim, dan kursor tanggal disimpan/diinisialisasi dari pesan terakhir yang sudah diproses.
- Restore backup memeriksa struktur blob (magic + base64 + panjang minimum) sebelum meminta PIN, sehingga file rusak tidak lagi dianggap "PIN salah" dan menghabiskan percobaan.
- `clearCredentials()` dan `saveSettings()` berjalan di scope aplikasi, jadi rotasi layar atau activity ditutup tidak memotong proses simpan/hapus kredensial di tengah jalan.
- Offset update Telegram baru disimpan setelah perintah selesai dijalankan (teks maupun callback), sehingga perintah tidak hilang bila proses berhenti saat handler berjalan; update yang belum selesai akan dikirim ulang, bukan dilewati.

## [1.6.453] - 2026-10-22
### Fixed
- `checkVehicle()` tidak lagi mengirim kabar kendaraan saat monitoring dijeda (`/stop`), konsisten dengan `checkGeofenceFallback()`.
- `checkGeofenceFallback()` baru mencatat waktu cek setelah fix lokasi didapat, jadi kegagalan GPS tidak membuat geofence buta selama 15 menit.
- `ensureForegroundTypes()` mencoba ulang tipe FGS yang pernah ditolak setelah 5 menit, sehingga kamera latar pulih sendiri setelah izin diberikan tanpa restart proses.
- `NotificationForwarderService` mengosongkan riwayat notifikasi saat logout atau hapus kredensial, jadi owner baru tidak bisa membaca `/lastnotif` milik sesi sebelumnya.
- Input angka/teks dari tombol menu memakai kunci sendiri saat dipasang dan dikonsumsi, sehingga balasan cepat tidak lagi diambil perintah lain atau diproses dua kali.
- `CrashReporter` memakai umur baru setelah laporan lama dipotong, tidak lagi memakai umur laporan tertua sehingga laporan yang masih baru tidak terhapus sebelum terkirim.
- `cek-cepat.sh` membaca seluruh pipeline tanpa `grep -q`, sehingga `pipefail` + `SIGPIPE` tidak lagi membuat pemeriksaan berkas/token lolos diam pada diff besar.
- `verifikasi-path.py` melewati direktori `build`, `.gradle`, dan `__pycache__` saat memindai, jadi berkas hasil build tidak dianggap sumber repo.

## [1.6.452] - 2026-10-22
### Fixed
- `PreferencesManager.upgradeToPersistent()` tidak lagi menghapus kunci terenkripsi yang tidak ada di snapshot memori; bila snapshot kosong, data terenkripsi yang ada dipakai apa adanya sehingga kredensial/pengaturan tidak terhapus saat migrasi.
- `MessageQueue.clearQueue()` menaikkan versi persist dan menyerialkan penghapusan dengan penulis latar, sehingga antrean lama tidak muncul kembali setelah dibersihkan.
- `MessageQueue.flushSync()` tetap menunggu barrier persist di main thread (batas 2 detik), jadi tulisan terakhir tidak hilang saat service berhenti.
- `MessageQueue.filterFresh()` hanya memakai cadangan "clock jump" bila stempel waktu monoton tidak valid; pesan yang benar-benar kedaluwarsa setelah offline lama kini dibuang.
- `/sms` memilih nomor valid terpanjang, sehingga `0812 3456 7890 Halo` tidak lagi terpotong menjadi nomor `0812 3456`.
- Watchdog loop tidak lagi me-restart monitoring di tengah flush media karena detak loop diperbarui setiap berkas selesai dikirim.

## [1.6.451] - 2026-10-22
### Fixed
- `TrailStats.millisInside()` menghitung sebagian durasi pada segmen yang melintasi batas radius (interpolasi jarak), tidak lagi membuang seluruh segmen hanya karena salah satu ujung di luar radius.
- Pengingat "monitoring berhenti"/masalah token memakai kanal `IMPORTANCE_DEFAULT`, sehingga muncul di laci notifikasi dan tidak lagi tersembunyi di kanal `IMPORTANCE_MIN`.

## [1.6.450] - 2026-10-22
### Fixed
- Sinkron awal tidak lagi menandai selesai saat riwayat SMS/panggilan gagal dibaca: `SmsRepository`/`CallLogRepository` melaporkan kegagalan query ke pemanggil sinkron, retry 30 menit dipasang, dan probe pemotongan halaman juga dihitung gagal.
- `PreferencesManager.upgradeToPersistent()` mengulang pencocokan snapshot sampai stabil dan menyusul selisih setelah pertukaran penyimpanan, sehingga tulisan yang datang di tengah migrasi tidak hilang.
- `PreferencesManager.recoverEncryptedStorage()` membuka ulang penyimpanan pada instance yang sama, jadi referensi yang sudah dipegang service (mis. `NotificationForwarderService.prefsRef`) tidak lagi menunjuk preferensi yang sudah dihapus.
- `NotificationForwarderService`: jalur luapan notifikasi dibatasi 8 korutin bersamaan agar lonjakan notifikasi tidak membuat antrean korutin tak terbatas.

### Changed
- `SetupActivity`: tautan pemasangan (pairing) dan backup membaca preferensi terenkripsi di thread latar, bukan di main thread.

## [1.6.449] - 2026-10-22
### Fixed
- `/sms` kini menerima nomor yang ditulis dengan spasi/tanda pisah (mis. `+62 812-3456`); pesan dipisahkan setelah nomor valid pertama.
- `/contacts 0` atau nomor halaman di luar 1–999 dijawab dengan rentang halaman, tidak lagi dicari sebagai nama kontak.
- Tombol menu "Bersihkan media 30h" diperbaiki menjadi "30 hari" agar sesuai perintah `/bersihkan media <hari>`.
- `/digest`, `/waspada`, dan `/minggu` mengirim pesan gagal bila laporan tidak bisa dibuat, tidak lagi diam.
- `BootRestartWorker` tidak lagi memasang notifikasi foreground saat monitoring memang dimatikan/belum dikonfigurasi.
- `RingVolume` tidak memulihkan volume alarm lama saat jam perangkat mundur (umur negatif).
- `MessageScheduler`: `scheduleMessageSendNext()` tidak lagi mereset penghitung antrean kirim yang masih berjalan, sehingga batas 3 work tidak bisa dilewati.
- `A11yMonitor`: proteksi tamper ikut berhenti saat monitoring dijeda, dan paket yang tertunda tidak lagi hilang saat layar menyala dalam keadaan terkunci.
- `UsageEventsSummary` menghitung "HP dibuka" per sesi aplikasi (peristiwa resume berurutan dari paket yang sama tidak dihitung ganda).

## [1.6.448] - 2026-10-22
### Fixed
- `SetupActivity`: data restore yang menunggu (owner ID + PIN akses) dan blob backup tidak lagi hilang saat layar diputar atau proses dipulihkan; keduanya disimpan di `onSaveInstanceState`.
- Penulisan berkas backup dipindah ke thread latar; toast sukses/gagal muncul di thread utama setelah penulisan selesai.

### Changed
- `saveSettings()` dan `testConnection()` memakai interval/PIN yang sudah dimuat saat layar dibuka, sehingga tidak lagi membaca preferensi terenkripsi di main thread.

## [1.6.447] - 2026-10-22
### Changed
- `MessageQueue`: penulisan antrean dari main thread tidak lagi menyerialkan JSON di thread itu; snapshot dikirim ke satu thread latar dengan urutan terjaga, dan penulisan beruntun digabung menjadi satu tulis terakhir.
- `SendMessageWorker`: pesan terkirim dihapus dari antrean per 10 pesan (bukan per 1), mengurangi penulisan ulang seluruh antrean.
- `SetupActivity.updateStatus()` membaca empat kunci preferensi langsung lewat getter, tidak lagi menyalin seluruh isi preferensi.
- `PreferencesManager.appendAudit()` memakai `apply()` (tidak memblokir thread pemanggil dengan `commit()`).

## [1.6.446] - 2026-10-22
### Fixed
- Watchdog tidak lagi me-restart loop patroli saat jeda tidur lebih panjang dari `patrolInterval` (mis. monitoring dijeda, foto dijeda, atau jam malam): `patrolCycleMs` kini mengikuti durasi tidur yang benar-benar dipakai.
- Backup: toast "selesai" hanya muncul bila berkas benar-benar ditulis; `openOutputStream` yang mengembalikan `null` kini dilaporkan sebagai gagal.
- `/hilang` tetap mengirim lokasi + foto walau perangkat sedang berdering (ketukan kedua tidak lagi berhenti tanpa kiriman apa pun).
- `InstantEventReceiver`: kegagalan `startForegroundService` kini menjadwalkan `scheduleBootRestart` alih-alih hilang senyap.
- `CameraService`: `onOpened`/`onConfigured` memeriksa ulang nomor sesi di dalam `captureLock`, sehingga `forceReset()` yang menyela tidak meninggalkan `CameraDevice`/sesi kamera yang bocor; jalur rekaman video juga memberi error langsung saat sesinya digantikan.

## [1.6.445] - 2026-10-21
### Added
- `/status` kini menampilkan jenis jaringan + penanda VPN, status baterai/charging, identitas SIM yang terpasang, jumlah buka-kunci gagal (bila admin perangkat aktif dan angkanya > 0), serta status izin `RECEIVE_SMS`/`READ_PHONE_STATE`.
- `/ringkas` menyertakan statistik pemakaian 6 jam terakhir: jumlah HP dibuka, jam buka pertama, dan aplikasi yang paling sering dibuka.

### Changed
- Laporan buka-kunci gagal dibatasi maksimal 3 per jam (selain jeda 2 menit antar-laporan) supaya chat tidak dibanjiri.

## [1.6.444] - 2026-10-21
### Added
- `InstantEventReceiver` menangkap `SMS_RECEIVED` dan `PHONE_STATE` (saat panggilan berakhir), lalu memicu pemeriksaan data langsung dengan jeda aman 15 detik — kabar SMS/panggilan tidak lagi menunggu `syncInterval`.
- Deteksi kartu SIM berubah: identitas SIM (`simOperator` + negara) disimpan di preferensi `sim_identity` dan dibandingkan tiap siklus data; perubahan dikirim ke Telegram.
- Izin opsional `RECEIVE_SMS` dan `READ_PHONE_STATE` diminta bersama izin lain di Setup, tetapi tidak memblokir monitoring bila ditolak.

### Changed
- `checkAndSendNewData()` dibungkus `Semaphore(1)` (`dataCheckLock`) supaya pemeriksaan dari siaran dan dari loop utama tidak berjalan bersamaan (mencegah kabar ganda).

## [1.6.442] - 2026-10-21
### Added
- Ringkasan harian memuat statistik perjalanan dari jejak 24 jam: jarak tempuh, lokasi diam terlama (bila ≥45 menit, disertai tautan peta), dan durasi di area sekolah bila geofence sekolah aktif.
- Ringkasan harian memuat statistik pemakaian perangkat dari `UsageStatsManager.queryEvents`: jumlah HP dibuka + jam buka pertama, serta 3 aplikasi yang paling sering dibuka.
- `/apps all` (atau `/apps semua`) mendaftar seluruh aplikasi terpasang, termasuk yang tidak punya ikon peluncur, lengkap dengan nama paket dan penanda `[nonaktif]`.
- `AdminReceiver.onPasswordFailed`: laporan "Buka kunci gagal (#n)" berisi lokasi + foto, paling sering sekali per 2 menit dan hanya pada percobaan ke-1, ke-3, ke-6, dst.
- `TrailStats` (haversine murni) dan `UsageEventsSummary` beserta uji unit `TrailStatsTest` dan `UsageEventsSummaryTest`.

### Changed
- Manifest memakai `QUERY_ALL_PACKAGES` agar daftar paket lengkap terbaca di Android 11+; `/apps` tanpa argumen tetap hanya menampilkan aplikasi berikon peluncur.
- Batas celah antar-sampel statistik jejak mengikuti `patrolInterval` + 10 menit (maks 190 menit), sehingga patroli 30–180 menit tetap menghasilkan durasi sekolah dan lokasi diam yang masuk akal.

## [1.6.439] - 2026-10-21
### Fixed
- `ShotConsentActivity`: jendela izin tangkap layar menutup sendiri setelah 5 menit (tidak menggantung di Recents/task sendiri) dan menerima permintaan baru lewat `onNewIntent`, sehingga tombol izin dari Telegram tetap bereaksi saat jendela lama masih ada.

## [1.6.438] - 2026-10-21
### Fixed
- Notifikasi OTP: dedupe dan deteksi prioritas memakai teks asli, bukan teks yang sudah disamarkan; dua notifikasi berbeda dengan kode OTP berbeda tidak lagi saling membuang, dan prioritas tidak lagi terpengaruh masking. Kunci dedupe disimpan sebagai hash sehingga digit OTP tidak menetap di memori.
- Deteksi berkendara hanya memakai fix lokasi berumur ≤10 menit; fix cache 30 menit tidak lagi memicu alarm "sedang berkendara" palsu.
- `CrashReporter.pendingReport()` membaca berkas laporan di bawah kunci yang sama dengan penulisnya, sehingga tidak mungkin membaca laporan yang separuh tertulis.

### Changed
- Parser `/pantauapp` memakai `indexOfFirst` alih-alih mengompilasi regex tiap perintah.
- `tools/bekas-hapusan.txt` mencatat `pkgTryAcquire` (nama lama yang sudah diganti `pkgShouldDrop`) agar tidak muncul kembali.

## [1.6.437] - 2026-10-21
### Changed
- `/izinshot` masuk daftar perintah yang dikenal sehingga ikut muncul di bantuan/pencocokan perintah.
- Hint `/heartbeat` menulis rentang `0-48` (nilai `0` memang diterima untuk mematikan).

### Fixed
- `A11yMonitor` menghapus field `appContext` yang hanya ditulis dan tak pernah dibaca.
- `NotificationForwarderService.pkgTryAcquire` diganti nama menjadi `pkgShouldDrop` agar sesuai perilakunya (`true` = notifikasi dibuang).
- Regex `\s+` dipindah ke konstanta `WS_REGEX`, tidak lagi dikompilasi tiap perintah `/pantauapp`.
- `values/strings.xml`: `setup_clear_hint` diterjemahkan ke bahasa Inggris (sebelumnya teks Indonesia muncul di perangkat berbahasa Inggris).

## [1.6.436] - 2026-10-21
### Changed
- Build memakai AGP **8.7.3** + Gradle **8.9** (sebelumnya AGP 8.5.2 + Gradle 8.7) agar `compileSdk 35` berada dalam matriks dukungan resmi.
- CI hanya mengulang unit test/build bila log menunjukkan kegagalan jaringan; kegagalan kompilasi langsung berhenti (tidak lagi 3× menit terbuang dan penyebab tersamarkan).
- Workflow menolak tag `vX.Y.Z` yang tidak cocok dengan `versionName` di `app/build.gradle`.

### Fixed
- `scripts/cek-cepat.sh`: pemeriksaan path vektor dan token memakai `rg -q`/`git grep -q` (pola lama `... | grep -q .` membuat `rg` kena `SIGPIPE` di bawah `pipefail` sehingga pemeriksaan lolos diam-diam), dan pemindaian tree terlacak kini selalu jalan walau ada berkas staged.
- `tools/pasang-hook.sh`: dorongan cabang baru memakai rentang dari `git merge-base refs/remotes/origin/main` agar semua commit yang didorong diperiksa, bukan hanya commit terakhir.
- `tools/verifikasi-path.py`: pemisah rentang (`..` vs `...`) dipakai konsisten antara daftar berkas dan diff.

## [1.6.435] - 2026-10-21
### Fixed
- Batas 429 dari unggahan media hanya menahan pengiriman global 60 detik; sebelumnya `retry_after` media sampai 1 jam ikut mematikan polling, balasan perintah, dan teks.
- Geofence fallback memakai akurasi fix yang tersimpan (prefs baru `last_loc_acc_m`), tidak lagi menganggap akurasi "tak diketahui" sehingga transisi palsu dari posisi cache 30 menit tidak terjadi.
- `CrashReporter` hanya membuang laporan pertama yang ditolak permanen (400); laporan lain di berkas yang sama tetap disimpan untuk dikirim ulang.
- `CameraService` melepas `ImageReader`/surface miliknya bila `forceReset()` menyela setup, dan pemanggil menerima galat "sesi kamera digantikan" (tidak lagi menggantung tanpa callback).
- Loop bangun `NotificationForwarderService` 25 → 60 detik (hemat baterai, tetap memulihkan layanan yang mati).
- Tombol Tes menolak interval di luar rentang dengan pesan galat; sebelumnya nilai di luar rentang diganti diam-diam dengan nilai tersimpan lalu ikut disimpan.

## [1.6.434] - 2026-10-21
### Fixed
- Cache foto kini benar-benar membuang berkas **terlama**: `v1.6.432` memakai `dropLast()` pada daftar terurut naik sehingga yang terhapus justru foto terbaru, termasuk yang belum terkirim.
- Penolakan izin notifikasi (Android 13+) tidak lagi memblokir tombol monitoring maupun mulai-otomatis saat aplikasi dibuka: `hasAll()` hanya memeriksa izin inti, sementara izin notifikasi tetap diminta terpisah dan statusnya tetap dilaporkan.
- Kursor SMS/panggilan dipulihkan otomatis bila basis data provider berganti (mis. pabrik/hapus semua pesan): data baru yang ber-`_ID` lebih kecil dari kursor tidak lagi hilang permanen. Logika keputusan diuji lewat `CursorRepairTest`.
- Pesan yang tertahan karena PIN aktif + owner belum login dijadwalkan ulang oleh `SendMessageWorker`, tidak menggantung sampai ada pemicu lain.
- `/start` dari owner yang sudah tertaut dibalas petunjuk singkat (sebelumnya senyap).
- `/audit` mencatat "`diterima`" untuk perintah kontrol, bukan "`ok`", karena handler belum tentu berhasil.

## [1.6.433] - 2026-10-21
### Fixed
- Hook pre-push memakai rentang commit dari ref yang benar-benar didorong (termasuk tag anotasi dan cabang baru), bukan selalu membandingkan dengan `HEAD` lokal.

## [1.6.432] - 2026-10-21
### Changed
- Aksesibilitas: event dedupe per paket+kelas sehingga layar sensitif tetap diperiksa, pemakaian dipersist tiap ~20 detik, sesi panjang tidak lagi dipotong 4 jam, pergantian hari menyimpan data hari sebelumnya, dan batas kabar kata kunci disimpan lintas restart.
- Tamper: halaman dianggap sensitif "aksesibilitas" hanya bila kelas layar cocok atau nama aplikasi ikut terlihat, sehingga halaman Setelan lain tidak ikut ditutup.
- Hapus kredensial lewat tahan tombol Simpan kini meminta PIN akses bila PIN aktif; token/chat yang dikosongkan hanya memperingatkan, tidak langsung menghapus.
- Backup memakai format `REDEYE2:` dengan 210.000 iterasi PBKDF2; file `REDEYE1:` lama tetap bisa diimpor.
- Hapus `app/src/main/res/values-v30/bools.xml` yang identik dengan nilai dasar.

### Fixed
- Cache foto membuang berkas terlama, bukan terbaru, saat penuh.
- Pengiriman foto tidak lagi berhenti pada satu berkas yang ditahan (maksimal 2 per siklus).
- Jam malam menolak jam 05:00-11:59 yang sebelumnya membuat kurungan hampir sepanjang hari.
- `/ping` berulang dibalas sisa waktu tunggu, bukan diam.
- `/pantauapp status|daftar` hanya via private chat; penambahan aplikasi sensitif ditolak; aplikasi yang sudah dicopot dibersihkan dari daftar.
- `/appalert on` dan `/lindungi on` memperingatkan bila Aksesibilitas mati.
- `/bersihkan media` wajib menyebut jumlah hari.
- Patroli tetap berjalan saat mode hemat daya (interval diperpanjang) dan loop kamera tidak berputar tiap 2 menit saat kamera dimatikan.
- Geofence fallback meneruskan akurasi fix terbaru.
- `/logout` membatalkan proses restore yang masih berjalan.
- Audit hanya dicatat untuk pengirim sah dan setelah gerbang owner.
- Callback tombol memakai kunci kontrol yang sama dengan perintah kontrol.
- Wake lock video dilepas saat service berhenti; video tetap difinalisasi walau `stop()` melempar.
- Foto dan video tidak lagi berjalan bertumpuk (`recordBusy`/`cameraBusy`).
- Foto gelap otomatis memberi kabar maksimal sekali per jam.
- Rekaman yang sudah terkirim tidak lagi dilaporkan gagal saat coroutine dibatalkan.
- Antrean pesan tidak dihapus saat PIN aktif dan owner logout; sisa chunk hanya dianggap terantre bila tersimpan; pesan terkirim dihapus per satu.
- `SecurityException` dari SMS/panggilan diteruskan agar jalur coba ulang berjalan.
- `CrashReporter` membuang laporan yang ditolak permanen (400) alih-alih mengulang tiap siklus.
- `FgsWakeActivity` selalu selesai lewat jadwal di `onCreate`/`onPause`.
- `BootReceiver` tidak lagi menghapus penanda boot penuh saat menangani event.
- `AppPermissions.hasAll` ikut memeriksa izin notifikasi di Android 13+.
- `CameraService` hanya membersihkan berkas `.tmp` milik kamera dan memeriksa sesi sebelum membuat resource.
- Hook pre-push memeriksa rentang commit yang benar-benar didorong.

## [1.6.431] - 2026-10-21
### Added
- `/pantauapp on|off|status|daftar|tambah <app>|hapus <app>`: pantau kata kunci dari `/watch` di dalam aplikasi terpilih dan kirim kabar Telegram berisi aplikasi + kata + waktu.
- Pemantauan dalam aplikasi bersifat opt-in, hanya app terpilih, dan melewati layar password, banking, pembayaran, browser, serta aplikasi ini. Isi pesan tidak disimpan; kabar dibatasi 15 menit per app+kata dan maksimal 30 kabar per hari.
- Layanan Aksesibilitas kini juga menerima event perubahan konten untuk mencocokkan kata pantau; teks layar hanya dipakai di memori saat mencocokkan.

## [1.6.430] - 2026-10-21
### Added
- Proteksi tamper: layar Aksesibilitas, admin perangkat, hapus aplikasi, sumber tak dikenal, dan opsi pengembang ditutup otomatis saat dicoba dibuka; pemilik mendapat kabar Telegram.
- `/lindungi on|off|status` untuk menyalakan/mematikan proteksi tamper dan melihat status Aksesibilitas + admin perangkat.
- Pemeriksaan berkala Aksesibilitas: kalau layanan dimatikan, pemilik diberi kabar (maksimal sekali per 6 jam) dan status di `/status` + `/ringkas` menandai `MATI`.

## [1.6.429] - 2026-10-21
### Added
- Pemakaian aplikasi lewat Aksesibilitas: durasi per aplikasi, jumlah buka, pemakaian malam, aplikasi yang sedang dibuka, dan cadangan `/layar` saat izin Usage access belum ada.
- `/appalert on|off|status`: kabar saat aplikasi baru pertama kali dibuka dan saat aplikasi dibuka pada malam hari (maksimal sekali per 30 menit), plus status pemakaian hari ini.
- Layanan Aksesibilitas kini tersedia mulai Android 7 (sebelumnya hanya Android 11+) untuk pencatatan pemakaian; tangkap layar tetap khusus Android 11+.

## [1.6.428] - 2026-10-21
### Changed
- Menu command Telegram (☰) di chat owner hanya memuat `/help` dan `/ping`; perintah lain tetap berfungsi lewat teks dan pintasan tombol di `/help`.

## [1.6.427] - 2026-10-21
### Changed
- Navigasi daftar kontak memakai tombol inline **⬅️ Sebelumnya** / **Berikutnya ➡️** (`x:contacts <halaman>`); perintah teks `/contacts <halaman>` tetap diterima.

## [1.6.426] - 2026-10-21
### Added
- `/contacts` tanpa argumen (atau `/contacts <halaman>`, mis. `/contacts 2`) menampilkan seluruh kontak per halaman berisi 30 nomor, lengkap dengan navigasi halaman; `/contacts <nama|nomor>` tetap mencari seperti sebelumnya. Tombol **📇 Semua kontak** ditambahkan di menu Komunikasi. Butuh izin Kontak.

## [1.6.425] - 2026-10-21
### Fixed
- Sesi rekaman video yang digantikan watchdog `forceReset` tidak lagi meninggalkan `MediaRecorder`/`Surface`; `finishWithError` melepas sumber daya sesi walau sesi sudah digantikan.
- Notifikasi yang sedang dikirim tidak hilang saat coroutine dibatalkan (mis. service dihentikan); pesan dikembalikan ke antrean.
- Broadcast receiver memakai executor terpisah dari tugas kritis, sehingga receiver tidak menunggu unggahan panjang dan tidak saling menahan.
- Pencarian SMS dan panggilan per nomor mencocokkan varian nomor (`62...`, `0...`, `+62...`) langsung di query, tidak selalu jatuh ke pemindaian fuzzy.

### Security
- `BootReceiver`, `TimeChangeReceiver`, dan `PackageChangeReceiver` tidak diekspor; hanya sistem dan aplikasi sendiri yang dapat mengirim broadcast.

### Changed
- `README.md` dan `PRIVACY.md` diselaraskan dengan kode: PIN ikut tersimpan di backup terenkripsi dan PIN backup minimal 6 digit.

## [1.6.424] - 2026-10-21
### Fixed
- Pengiriman yang tertunda saat owner logout tidak lagi mengantrekan pesan lalu membuangnya setelah kursor data maju; `sendFitted` dan ringkasan harian berhenti sebelum mengirim selama menunggu login, sehingga data baru dikirim setelah login, bukan hilang.
- `CrashReporter` mengenali perpindahan grup ke supergroup (400 `migrate_to_chat_id`) dan memakai Chat ID baru sebelum menandai kredensial error; jalur fallback teks biasa juga.
- `SendMessageWorker` mengenali perpindahan grup pada balasan 403, tidak lagi langsung menganggap kredensial ditolak.
- Antrean pesan yang gagal dibaca dari penyimpanan terenkripsi beralih ke mode volatil dengan jadwal coba lagi, tidak lagi melempar pengecualian ke pemanggil.
- Setelah "Hapus kredensial" membuat ulang penyimpanan terenkripsi, antrean pesan dibuka ulang ke penyimpanan baru sehingga pesan berikutnya tidak hilang.
- Sisa kiriman yang diantrekan karena 429 memperbarui pembatas laju global agar jalur kirim lain ikut menunggu.

### Changed
- Pemindai token di `tools/verifikasi-path.py` dan `scripts/cek-cepat.sh` tidak lagi melewati baris berisi `Regex(`/`Pattern.compile`; mode `--semua` memindai seluruh pohon terlacak, dan rentang diff tak valid memakai diff commit teratas.
- Pemeriksaan metode hilang hanya mencocokkan deklarasi `fun`, bukan pemanggilan.
- `.gitignore` mengabaikan `__pycache__/` dan `*.pyc` hasil menjalankan verifikator.

## [1.6.423] - 2026-10-21
### Changed
- Saat PIN akses aktif dan owner sudah logout, aplikasi berhenti mengirim laporan, foto, video, rekaman, notifikasi, dan pesan antrean; bot hanya membalas dengan pengarahan `/login <PIN>`.
- Antrean pesan dibuang saat logout dan selama menunggu login, sehingga laporan lama tidak ikut terkirim setelah owner masuk lagi.
- Media yang belum terkirim dihapus saat menunggu login, bukan disimpan untuk dikirim kemudian.

## [1.6.422] - 2026-10-21
### Fixed
- Laporan kebangkitan `Liveness` baru dianggap terkirim setelah pesan benar-benar tersimpan di antrean; penjadwalan pengiriman tetap dilakukan.
- `/audit` menampilkan 50 entri terakhir sesuai judulnya.
- `/clearqueue` tidak lagi melaporkan drop lama seolah-olah akibat pembersihan antrean.
- File backup `/pulihkan` hanya dihapus setelah berhasil diunduh; kegagalan unduhan tidak lagi menghilangkan file dari chat.
- Cache sementara `screenshot_*.tmp` ikut dipangkas bersama `camera_*.tmp`.
- Tombol Tes dan Salin tautan pairing menunggu field selesai dimuat, sehingga tidak membaca nilai kosong saat halaman baru dibuka.
- Teks status langsung di Setup mengikuti bahasa aplikasi, tidak lagi hardcode "NYALA/MATI".
- Kolom token bot dan PIN akses dikecualikan dari Autofill.
- `SendMessageWorker` tidak lagi membawa `setBackoffCriteria` WorkManager yang tidak pernah terpakai.
- Pemeriksaan diff `verifikasi-path.py` di CI memakai pembanding yang valid pada push tag/ref baru.
- Aturan Detekt `RedundantSemicolon` tidak dipakai karena tidak tersedia di Detekt 1.23.7; impor `NetworkUtils` yang berlebih di `CrashReporter` dihapus.

### Changed
- Validasi awal form Simpan dan Tes disatukan lewat `resolveCredentialInput()`.
- Detekt menambah aturan `UnusedImports` dan `ForbiddenComment`.
- Semikolon berlebih di `SendMessageWorker` dan `TelegramApi` dirapikan.

### Removed
- `PreferencesManager.clearAudit()` dan `@color/black` yang tidak terpakai.

## [1.6.421] - 2026-10-20
### Fixed
- Watchdog kamera dipasang setelah `ImageReader` dan `meteringSurface` siap, sehingga timeout saat penyiapan tidak lagi meninggalkan reader/surface tanpa pemilik.
- Pemeriksaan geofence cadangan tetap berjalan walau patroli aktif tetapi sedang tidak berjalan (malam, jeda, atau mode hemat daya), sehingga alert rumah/sekolah tidak mati senyap.
- Kursor panggilan memakai `_ID` yang naik monoton, bukan jam dinding; panggilan tidak lagi hilang saat jam perangkat mundur. Sinkron awal dan penomoran bagian memakai urutan `_ID` yang sama.
- PIN akses tidak lagi dihapus diam-diam saat token atau Chat ID berubah. PIN ikut tersimpan di backup terenkripsi dan diterapkan lagi lewat restore Setup maupun `/pulihkan`.
- Backup cloud dan transfer perangkat-ke-perangkat dikecualikan lewat `android:dataExtractionRules`, sehingga prefs terenkripsi tidak tersalin tanpa kunci Keystore.
- "Hapus kredensial" kini mereset penyimpanan terenkripsi yang rusak (prefs + keyset) dan membuat ulang penyimpanan aman, tidak lagi tertahan di mode volatil.
- Unggahan foto diserialkan dengan mutex, sehingga foto hasil pemotretan baru tidak lagi diunggah paralel dengan flush foto lama.
- Polling perintah tidak lagi menunggu balasan perintah sebelumnya: command dan callback dijalankan di coroutine berurutan, sedangkan perintah kontrol (`/stop`, `/pause`, `/resume`, `/restart`, `/logout`, `/clearqueue`, `/lock`) diprioritaskan agar tetap responsif.

## [1.6.420] - 2026-10-20
### Fixed
- Kursor SMS dan panggilan tidak lagi melompati bagian yang gagal: pengiriman riwayat berhenti di bagian pertama yang gagal, sehingga bagian berikutnya tidak maju mendahului data yang belum terkirim.
- Pesan pendek yang ditolak Telegram secara permanen kini diperlakukan sama dengan pesan panjang yang ditolak permanen: dicatat sebagai pesan dibuang, tidak diulang setiap siklus.

## [1.6.419] - 2026-10-20
### Fixed
- `/logout` mengosongkan perintah input tertunda, sehingga input lama tidak bisa dieksekusi oleh sesi owner berikutnya.
- Setelah hasil `%` atau `=`, mengetik angka baru benar-benar memulai perhitungan baru tanpa membawa `previousNumber`/`operator` lama.

## [1.6.418] - 2026-10-20
### Fixed
- Notifikasi prioritas yang datang saat antrean penuh kini melewati dedupe dan batas laju yang sama seperti jalur normal, tidak lagi dikirim langsung tanpa penyaring.
- Pesan yang ditolak Telegram secara permanen (`400` setelah fallback teks polos) tidak lagi dimasukkan ke antrean; `sendFitted()` melewati antrean untuk kegagalan permanen.
- `MessageQueue.clearQueue()` mempertahankan status `persistDirty` bila `commit()` gagal, sehingga flush berikutnya tetap menghapus antrean dari penyimpanan.
- `RingVolume.restoreStuck()` hanya memulihkan catatan berumur lebih dari 12 jam bila volume alarm masih maksimum; catatan basi yang volumenya sudah diubah pengguna tetap dibuang tanpa menimpa setelan.

## [1.6.417] - 2026-10-20
### Fixed
- Tombol Simpan/Tes tidak lagi menandai kredensial tersimpan sebagai error saat token atau Chat ID yang diuji berbeda dari yang tersimpan; status error hanya diperbarui untuk kredensial yang benar-benar aktif.
- `/pulihkan` menerima Chat ID berbentuk `@username` seperti yang sudah diterima halaman Setup, sehingga backup dari Setup tidak lagi ditolak karena bentuk Chat ID.

## [1.6.416] - 2026-10-20
### Fixed
- Deteksi OTP memakai batas kata, sehingga `shopping` tidak lagi dianggap OTP dan kode berbahasa Inggris seperti `code`/`verify` ikut dikenali.
- `PreferencesManager.refreshInstance()` tidak lagi memegang monitor kelas saat mencoba upgrade penyimpanan, menghindari lock-order inversion dengan `upgradeToPersistent()` pada jalur kegagalan enkripsi.

## [1.6.415] - 2026-10-20
### Fixed
- Kursor SMS dan panggilan hanya maju setelah pesan benar-benar terkirim atau tersimpan di antrean persisten; antrean memori atau kegagalan `commit()` tidak lagi dianggap sebagai pengiriman yang aman.
- Pruning cache foto, video, dan audio kini membuang berkas paling lama lebih dulu, sesuai pesan "yang paling lama yang belum terkirim dibuang".

## [1.6.414] - 2026-10-20
### Changed
- Tombol angka kalkulator menjadi putih murni `#FFFFFFFF`; tombol `=` tetap forest green dan tombol fungsi/operasi tetap peach.

## [1.6.413] - 2026-10-20
### Fixed
- Tema global tidak lagi didominasi hijau: tombol Setup kembali peach `#E8A27A` dengan teks espresso, hijau hanya menjadi aksen; status bar Setup memakai charcoal.

## [1.6.412] - 2026-10-20
### Changed
- Tema global dan ikon launcher kini memakai palet earthy yang sama dengan kalkulator: primary forest green `#1B4D3E`, secondary peach `#E8A27A`, dan latar ikon cream; warna biru/oranye lama di Setup dan ikon dihapus.

## [1.6.411] - 2026-10-20
### Changed
- Kalkulator memakai palet earthy tanpa biru: tombol fungsi, operasi, dan titik menjadi peach `#E8A27A` dengan teks espresso, sedangkan `=` menjadi forest green `#1B4D3E`; tombol angka tetap cream.

### Removed
- Drawable dan warna utility peach lama serta warna teks operasi putih yang tidak lagi dipakai.

## [1.6.410] - 2026-10-20
### Changed
- Kalkulator disesuaikan dengan mockup final: latar krem `#F7F4EB`, layar charcoal, semua operasi biru pastel, titik peach, dan tombol `=` teal memanjang vertikal.
- Riwayat setelah `=` tidak lagi menampilkan tanda `=` agar tampil sebagai ekspresi samar di atas hasil.

### Removed
- Resource warna dan drawable oranye operasi yang tidak lagi dipakai.

## [1.6.409] - 2026-10-20
### Added
- Tombol `±` (ubah tanda) dan `%` (persen) pada kalkulator stealth; kode rahasia `1234=` dan aturan hitung tetap sama.
- Unit test `CalculatorFormat` untuk pemisah ribuan, bilangan negatif, notasi ilmiah, dan riwayat ekspresi.

### Changed
- Tampilan kalkulator memakai gaya Material 3 Expressive: kartu layar gelap, tombol pil pastel, operasi biru/oranye, tombol `=` teal, dan bayangan lembut.
- Angka pada layar dan riwayat memakai pemisah ribuan; angka setelah `=` tetap memulai entri baru dan dibatasi 12 digit.

## [1.6.408] - 2026-10-20
### Fixed
- Worker pengirim antrean tidak lagi menjadwalkan ulang dengan `REPLACE` dari dalam dirinya sendiri; jadwal lanjutan memakai `APPEND_OR_REPLACE` agar worker yang sedang berjalan tidak dibatalkan.
- Status monitoring kembali nonaktif bila layanan gagal dimulai, termasuk saat Android memblokir start foreground service; watchdog dan liveness ikut dibatalkan agar status Setup tidak menyesatkan.
- Tombol **Tes** kini menyimpan PIN akses dan owner restore tertunda seperti tombol **Simpan**, sehingga kredensial yang lolos tes tidak tertinggal tanpa PIN.
- Notifikasi prioritas tetap melewati dedupe 10 detik dan batas laju per aplikasi (20/2 menit); kelebihan prioritas masuk antrean, bukan dikirim langsung tanpa batas.
- Masking OTP mencakup kode 4–12 digit, termasuk pemisah spasi/titik/strip; angka 13 digit atau lebih dibiarkan agar nomor rekening panjang tidak terpotong salah.
- Pemeriksaan jaringan kini memakai `NET_CAPABILITY_VALIDATED`, sehingga captive portal tidak dianggap koneksi siap kirim.
- Respons `400` chat hilang ikut memblokir kirim/poll sementara, bukan disimpan sebagai kode yang tidak pernah dicek.
- Worker boot tetap mem-flush laporan crash walau layanan sudah berjalan dan early-return.
- Kolom interval sync/kamera di form dikembalikan ke nilai default saat kredensial dibersihkan.

### Security
- Build `pull_request` tidak lagi menerima keystore rilis atau kata sandinya; job build hanya punya `contents: read`, sedangkan `contents: write` dipindah ke job rilis khusus tag.
- Lockout login global dilonggarkan dari 20 percobaan/1 jam menjadi 50 percobaan/10 menit dan tidak lagi menghapus lockout per-pengirim saat lockout global aktif.

## [1.6.407] - 2026-10-20
### Added
- `/logout` dari private owner untuk mencabut sesi owner; tombol **🚪 Logout owner** juga tersedia di menu **⚙️ Alat & kontrol**.
- Unit test `OwnerAccess` untuk aturan owner ID, PIN aktif, dan fallback Chat ID.

### Changed
- Saat PIN akses aktif dan owner ID kosong, Chat ID lama tidak lagi otomatis dianggap owner; login PIN tetap wajib. Ini membuat `/logout` benar-benar mencabut akses perintah owner.
- Logout membersihkan kode pairing, hash menu, dan sesi restore yang tertunda; Chat ID tetap dipakai untuk pengiriman sampai owner baru login.

### Security
- `/logout` hanya bisa dipakai dari private chat owner dan hanya bila PIN akses sudah diisi, sehingga tidak bisa mengunci akses tanpa jalan masuk kembali.

## [1.6.406] - 2026-10-20
### Added
- PIN akses bot di Setup: saat owner belum tertaut, user lain bisa login lewat `/login <PIN>` dan langsung menjadi owner + Chat ID tujuan.
- Batas percobaan PIN (5 kali per akun, 20 kali total) dan penghapusan pesan `/login` dari chat setelah diproses.
- Unit test untuk validasi dan pencocokan PIN akses.

### Changed
- Link pairing dan `/start <chat ID> <kode>` otomatis dimatikan selama PIN akses aktif; status Setup menampilkan petunjuk `/login <PIN>`.
- `/login` muncul di menu perintah publik hanya saat owner kosong dan PIN aktif.

### Security
- PIN akses disimpan di penyimpanan terenkripsi, tidak ikut file backup, dan dihapus saat token bot diganti atau kredensial dibersihkan.

## [1.6.405] - 2026-10-20
### Added
- `/pulihkan` kini memberi alarm saat dimulai dan jeda batal **60 detik** setelah token/chat/tes kirim lolos; kirim `/pulihkan batal` selama jeda untuk mempertahankan setelan lama. Token/chat dicek ulang tepat sebelum diterapkan.
- Mode restore ketat opsional lewat Setup → **Kode restore ketat**: kode 6 digit berputar tiap 10 menit, wajib dikirim sebagai `/pulihkan <kode>`. Default mati agar restore jarak jauh tetap bisa.
- Audit perintah sensitif (`/audit`, 50 entri terakhir) untuk `/pulihkan`, `/stop`, `/sms`, `/lock`, `/hilang`, `/sembuh`, `/restart`, dan `/clearqueue`, termasuk percobaan yang ditolak.
- Tombol **Salin link pairing** di Setup yang membuat `https://t.me/<bot>?start=<chat>_<kode>`; handler `/start` menerima payload deep link selain format lama.
- Tombol **Cek kesehatan perangkat** di Setup: ringkasan status/izin dengan jalan pintas ke perbaikan izin dan anti-mati.
- Masking OTP/bank: angka 4–8 digit disamarkan saat notifikasi mengandung kata OTP/kode/verifikasi; bisa diatur lewat `/notif otp on|off|status` (default nyala).
- `/bersihkan media <1-365>` untuk menghapus cache foto/video/rekaman lokal yang lebih lama dari jumlah hari; default 30 hari.
- Unit test untuk aturan restore (`RestoreRules`), kode restore berputar, dan masking notifikasi.
- Detekt di CI dengan aturan gaya minimal (`config/detekt/detekt.yml`).

### Changed
- `/notif status` kini juga menampilkan status masking OTP.
- Menu **Alat & kontrol** mendapat tombol **🧹 Bersihkan media 30h**; menu **Info & data** mendapat tombol **🧾 Audit**.
- Pesan bantuan pairing menyebut link dari Setup, bukan hanya `/start <chat ID> <kode>`.

### Security
- Restore tidak lagi langsung menerapkan setelan setelah validasi; ada jendela batal 60 detik dan validasi ulang token/chat saat jatuh tempo.
- Mode ketat menambah faktor perangkat fisik untuk `/pulihkan`; kode hanya tampil di Setup dan tidak dikirim ke Telegram.
- Notifikasi OTP/bank tidak lagi meneruskan angka sensitif dalam bentuk asli secara default.

## [1.6.404] - 2026-10-20
### Added
- `/pulihkan`: memulihkan token bot, Chat ID, owner ID, interval sync, dan interval foto dari file `.redeyebak` yang dikirim ke bot. Bot meminta PIN, memvalidasi token (`getMe`), chat (`getChat`), dan tes kirim sebelum mengganti setelan; bila salah satu gagal, setelan lama tetap dipakai. Sesi 10 menit, PIN maksimal 3 percobaan, pesan file/PIN dihapus setelah diproses.
- Tombol **📥 Pulihkan setelan** di menu **Alat & kontrol**.

### Changed
- File backup `.redeyebak` kini menyertakan owner ID; restore lewat Setup ikut menerapkan owner saat **Save**. Backup format lama tetap terbaca (owner dianggap 0).

## [1.6.403] - 2026-10-20
### Changed
- Tombol inline Telegram dirapikan: navigasi seragam `🏠 Menu utama`, label kamera menjelaskan fungsi (`Pakai kamera depan/belakang`), `Notifikasi terakhir` dan `Sinyal hidup` lebih jelas, `Aplikasi` dipindah ke Info & data, tombol antrean/restart dikelompokkan, serta emoji kategori konsisten.

## [1.6.402] - 2026-10-20
### Fixed
- Sinkron riwayat awal tidak lagi berjalan saat offline atau token diblokir, sehingga riwayat tidak lagi membanjiri antrean 100 pesan lalu terbuang permanen; antrean data dibatasi 90 dan sisanya menyusul otomatis. Pesan "Belum ada riwayat" tidak lagi muncul saat antrean masih berisi data.
- Notifikasi masalah token/chat (401/403/400) dan notifikasi pemulihan layanan kini membuka kalkulator (kode rahasia), bukan halaman Setup langsung, sehingga pemantauan tidak bisa dimatikan orang lain tanpa kode.
- Rekaman audio ditulis ke berkas `.tmp` dan baru memakai nama `.m4a` setelah `stop()` berhasil; pembatalan tidak lagi menyimpan atau mengunggah rekaman parsial, dan sisa `.tmp` lama dibersihkan.
- Izin tangkap layar yang sudah dipakai tidak lagi tersimpan sebagai "masih aktif"; status izin di Setup sesuai kenyataan dan tidak lagi memakai token sekali pakai yang sudah hangus.
- Peringatan "sepi >48 jam" kembali hanya direset oleh data SMS/panggilan baru, tidak lagi oleh heartbeat atau laporan berkala.
- Wake lock rekaman audio/video kini bertahan selama masa unggah media (hingga 10 menit), tidak lagi habis di tengah unggahan video besar.
- Kegagalan `commit()` antrean pesan tidak lagi dianggap sukses; penulisan diulang pada `flushSync()` berikutnya, termasuk penghitung pesan yang dibuang.
- Pengecekan GPS jam sekolah tidak lagi berjalan tanpa geofence sekolah; jam malam tanpa geofence rumah juga tidak lagi memicu pengambilan lokasi.
- Menu Telegram yang diedit tidak lagi dianggap kedaluwarsa 5 menit setelah pesan aslinya; batas waktu memakai `edit_date`.
- Menekan Simpan sebelum pengaturan selesai dimuat tidak lagi memunculkan dialog hapus kredensial; muncul pemberitahuan bahwa pengaturan masih dimuat.

## [1.6.401] - 2026-10-20
### Fixed
- Halaman Setup (dibuka dari kode rahasia kalkulator) tidak lagi tampil di Recents dan tidak bisa di-screenshot/dijadikan thumbnail (`excludeFromRecents` + task terpisah + `FLAG_SECURE`), sehingga penyamaran aplikasi tidak bocor.
- Konflik polling `409` akibat webhook tertinggal kini dipulihkan otomatis lewat `deleteWebhook` (dijeda 10 menit), tidak lagi mematikan perintah Telegram secara permanen tanpa jalan keluar.
- Cursor SMS/panggilan tidak lagi macet saat satu potongan gagal permanen: potongan yang gagal masuk antrean dan cursor tetap maju, sehingga data setelahnya tidak berhenti dan tidak terkirim ganda.
- Jam malam `00:00`–`04:59` tidak lagi dianggap aktif hampir sepanjang hari; rentang dihitung dalam menit.
- Callback `MediaProjection` lama tidak lagi membongkar sesi tangkap layar yang baru (callback terikat ke projection pemiliknya).
- Rekaman audio yang sudah selesai tidak lagi dihapus saat pengiriman dibatalkan; berkas disimpan untuk dicoba otomatis. Rekaman yang `stop()`-nya gagal tidak lagi diunggah sebagai berkas rusak.
- Bot yang diblokir (`403`): perintah dibaca lagi dalam ±5 menit (sebelumnya 30 menit) dan teks notifikasi tidak lagi menyuruh `/status` yang mustahil diproses saat itu.
- Geofence rumah/sekolah kini tetap dicek tiap 15 menit walau patroli mati (memakai posisi cache atau satu perbaikan lokasi), tidak lagi diam sampai `/patrol` dinyalakan.
- `lastDataAt` diperbarui pada setiap pengiriman sukses, sehingga peringatan "sepi >48 jam" tidak lagi muncul palsu di perangkat yang rutin mengirim lokasi/foto tanpa SMS/panggilan baru.
- Sinkronisasi awal tidak lagi mengulang pesan "Pemantauan dimulai" dan "terkirim sebagian" setiap 30 menit; percobaan ulang tetap dijadwalkan bila terjadi galat di tengah proses.
- Unggah media memakai batas waktu 600 detik (tulis 300 detik) agar video hingga 48 MB punya kesempatan selesai di koneksi lambat, tidak lagi timeout lalu diulang terus.
- Screenshot ditulis ke berkas `.tmp` dan hanya diganti nama setelah `compress()` berhasil, sehingga JPEG rusak/terpotong tidak lagi terkirim.
- Pemulihan backup membaca berkas secara bertahap dengan batas 1 MB, tidak lagi memuat seluruh isi ke memori bila penyimpanan tidak melaporkan ukuran (berisiko `OutOfMemoryError`).
- Chat tujuan berupa grup: bila owner sudah tertaut, anggota lain tidak lagi bisa menekan tombol navigasi atau menjalankan perintah lewat bot.

## [1.6.400] - 2026-10-19
### Fixed
- Izin tangkap layar: sesi izin yang baru diberikan tidak lagi dibuang saat sesi lama berakhir (`ScreenCapture.dropProjection()` + `releaseShotSession()`), sehingga pengguna tidak dipaksa memberi izin ulang padahal baru saja mengizinkan.
- Tangkap layar jalur Aksesibilitas: timeout 20 detik kini benar-benar membatalkan penunggu (`withTimeout` + `cancel()`), jadi bitmap yang tiba setelah timeout benar-benar didaur ulang; perbaikan 1.6.398 sebelumnya tidak pernah aktif.
- SMS multi-bagian: hasil diputuskan berapa pun urutan laporan bagiannya; sebelumnya urutan "gagal dulu, sukses terakhir" membuat proses menggantung 45–75 detik dan menahan `smsBusy`.
- Pengingat "token/chat ID ditolak" (401/403/400) dan notifikasi watchdog loop tidak lagi dibungkam selama jam pertama sampai enam jam pertama setelah HP menyala (sentinel waktu dimulai dari 0).
- Peringatan jam malam tidak lagi terkirim dua kali untuk malam yang sama saat curfew melintasi tengah malam.
- Cooldown notifikasi kendaraan dan notifikasi "hemat kuota" tidak lagi macet berjam-jam bila jam perangkat mundur (pola penjaga disamakan dengan `maybeSendHeartbeat`).
- Laporan harian malam tidak lagi hilang sehari penuh bila penyusunannya gagal: tanggal baru ditandai setelah laporan terkirim; perbaikan sama untuk laporan begadang.
- `/home` dan `/school` menolak koordinat/radius yang salah ketik (mis. `300m` atau token ekstra) alih-alih diam-diam memakai radius bawaan 200 m.
- `/layar` menampilkan cara pakai bila argumen di luar 1–24, tidak lagi memotong diam-diam.
- `/photo <lensa>` saat monitoring dijeda tidak lagi menyimpan setelan lensa tanpa mengambil foto.
- Pesan gagal kamera dan gagal unggah berkas yang memuat teks dinamis kini di-escape sebelum dikirim, sehingga tidak lagi berisiko gagal parse (`parse_mode=HTML`).
- `/smsconfirm` membersihkan SMS tertunda yang rusak (nomor/teks kosong) sehingga tidak lagi terkunci tanpa jalan keluar.
- Laporan 7 hari tidak lagi menampilkan "Jam tersibuk: 0:00 (0 aktivitas)" saat tak ada aktivitas.
- `credentialError` dan `credentialErrorAt` ditulis atomik lewat `setCredentialError()`, tidak lagi dua tulis terpisah yang bisa membuat blokir auth terlewat saat proses mati di antara keduanya.
- Kuota berkas media: hitungan rekaman menyaring berkas yang sedang dikirim, hitungan foto/video mengabaikan berkas 0 byte, sisa `video_*.mp4.tmp` yatim dibersihkan, dan `/recordvideo` tidak lagi melaporkan "antrean penuh" saat pembersihan sedang dilewati karena pengiriman berjalan.
- Alarm liveness tidak lagi terus ditunda oleh pemanggil `scheduleSoon()` berikutnya (tenggat terdekat yang menang), sehingga penghidup layanan bisa tak pernah dijalankan saat alarm sering dijadwalkan ulang.
- `CrashReporter` mengunci penulisan berkas laporan (termasuk berkas `.tmp`), sehingga crash yang terjadi bersamaan dengan pengiriman laporan lama tidak lagi menghilangkan laporan baru.
- Antrean pesan: `flushSync()` kini bisa menuntaskan penulisan yang sebelumnya lewat `apply()` (ditandai perlu commit) dan `persistDropCountsLocked()` dipanggil di dalam kunci.
- Rotasi perangkat saat dialog izin tangkap layar tampil tidak lagi memunculkan dialog kedua dan membuang hasil izinnya.
- Setup: "Simpan Setingan" tidak lagi menolak dengan "interval salah" bila isian belum sempat dimuat (memakai nilai tersimpan), dan "Kirim Status Sekarang" tidak lagi mengantre ganda saat dibatalkan.
- `FgsWakeActivity` melepas `postDelayed` saat pause/destroy sehingga penjadwalan tidak menumpuk.
- Kalkulator (peluncur stealth): pembacaan preferensi terenkripsi dan pemeriksaan izin dipindah ke thread latar, tidak lagi membebani main thread saat aplikasi dibuka.

### Changed
- `/night`, `/patrol`, dan `/notif` menerima `nyala`/`mati` seperti `/kendara` dan `/hematdata`.
- Tema memakai `NoActionBar` agar label aplikasi tidak sempat tampil saat peluncuran (mode stealth).
- Teks bantuan Indonesia menyebut tombol "Ambil Layar" (sebelumnya "Capture Screen" yang tak ada di UI Indonesia).
- `todayKey()` memakai `TimeFmt.dayKey()` (pola `ThreadLocal`) sehingga tidak lagi mengalokasikan `SimpleDateFormat` baru setiap siklus.

### Removed
- `CameraService.currentVideoFile()` beserta cabang pemeriksaannya di `isMediaActive()` yang tak pernah cocok (berkas `.tmp` tak pernah diperiksa pembersih).
- Plugin Gradle `com.android.library` yang tidak pernah diterapkan; task `clean` memakai `layout.buildDirectory`.

## [1.6.399] - 2026-10-19
### Fixed
- Menutup dialog izin tangkap layar dengan "Batal" dari tombol Telegram "🖥️ Beri izin sekali" (`ui/ShotConsentActivity`) tidak lagi mencabut sesi izin yang sedang aktif — sejalan dengan perbaikan yang sama di halaman Setup.

## [1.6.398] - 2026-10-19
### Fixed
- Loop latar (`monitoring`, kamera, patroli, polling perintah, watchdog) kini dibuat dengan `CoroutineStart.LAZY` lalu `start()` dipanggil setelah field job ditetapkan. Sebelumnya coroutine baru bisa membaca field job lama yang belum ditimpa dan langsung berhenti sendiri, sehingga satu loop mati tanpa pesan sampai watchdog berikutnya merestart.
- Perubahan konfigurasi loop (mis. `/syncinterval`) kini langsung memotong jeda siklus yang sedang berjalan; sebelumnya interval baru baru dipakai setelah siklus lama habis (bisa berjam-jam).
- Layanan foreground tidak lagi tersisa tanpa monitoring: aksi `ACTION_LIVENESS_TEST`, `ACTION_REFRESH_FGS`, `ACTION_AFTER_SHOT_GRANT`, dan `ACTION_ADMIN_ALERT` (saat auto-resume mati) kini memanggil `stopSelfResult()`, dan peringatan admin tetap dikirim lebih dulu.
- Tangkap layar jalur Aksesibilitas: bitmap yang tiba setelah timeout 20 detik didaur ulang (sebelumnya bocor ±10–18 MB per kegagalan) dan konversi ARGB dipindah ke thread latar sehingga tak lagi membebani main thread.
- `getMediaProjection()` yang ditolak `SecurityException` kini mencabut izin tersimpan, sehingga tombol "Beri izin sekali" muncul kembali (sebelumnya `/screenshot` gagal terus tanpa tawaran izin baru).
- Pesan gagal tangkap layar tanpa izin kini ikut menyertakan tombol "🖥️ Beri izin sekali" pada jalur kegagalan umum, bukan hanya pada pesan khusus.
- Pesan 400 `can't parse entities` dari Telegram kini dicoba ulang sebagai teks biasa; sebelumnya laporan crash/diagnosa yang memuat `<` atau `&` dibuang permanen tanpa terkirim.
- SMS multi-bagian: hasil hanya diputuskan setelah semua bagian melapor, sehingga tidak lagi menyatakan "belum terkonfirmasi" padahal sebagian bagian masih terkirim (risiko SMS dobel saat pengguna mengirim ulang).
- Respons Telegram 4xx tak dikenal (mis. 404/409/451 dari halaman blokir/captive portal) tidak lagi dianggap penolakan permanen: antrean pesan dipertahankan, bukan dihapus.
- Geofence rumah/sekolah dan notifikasi "siap dijemput" diputuskan secara atomik, sehingga dua pengambilan lokasi bersamaan tidak lagi mengirim peringatan ganda.
- Menutup dialog izin tangkap layar dengan "Batal" di Setup tidak lagi mencabut sesi izin yang sedang aktif.
- `bestFix()` tetap memilih kandidat lokasi walau akurasi semua kandidat tak diketahui (sebelumnya mengembalikan `null` sehingga lokasi dianggap gagal).
- Kegagalan membuka penyimpanan terenkripsi saat layanan dibuat kini menjadwalkan liveness + boot restart sebelum berhenti, tidak lagi mematikan diri tanpa jejak.
- Penanda input tertunda (`/angka`, `/tombol`) memakai jam monotonic, sehingga perubahan jam perangkat tidak lagi membatalkan input yang masih menunggu.
- Tema memakai `windowOptOutEdgeToEdgeEnforcement` agar kalkulator dan Setup tidak tertutup status bar di Android 15.

### Changed
- Hint pairing bot berbahasa Indonesia dan tidak lagi menghabiskan kuota hint ketika token bot belum terisi.
- `/watch` melaporkan kata yang diabaikan karena melebihi 10 kata (sebelumnya hilang diam-diam).

## [1.6.397] - 2026-10-19
### Added
- Panduan blokir "Setelan terbatas" (Android 13+) di halaman Setup: tombol **Aktifkan Aksesibilitas** kini membuka dialog berisi langkah `Info aplikasi` → menu tiga titik → **Izinkan setelan terbatas** → baru nyalakan layanannya, lengkap dengan tombol pintas ke Info aplikasi dan ke setelan Aksesibilitas. Sebelumnya tombol itu langsung membuka setelan Aksesibilitas, sehingga tombol yang abu-abu tampak "diblokir" tanpa penjelasan.

### Changed
- `README.md`: langkah Aksesibilitas kini menyebut blokir setelan terbatas Android 13+ dan pemblokir kedua di sebagian HP (Oppo/Realme/OnePlus/ColorOS, sebagian Xiaomi/HyperOS): Opsi pengembang → Nonaktifkan pemantauan izin.

## [1.6.396] - 2026-10-19
### Added
- Jalur tangkap layar kedua lewat layanan Aksesibilitas (`service/ShotAccessibilityService.kt`, Android 11+): setelah diaktifkan **sekali** dari Setup (tombol "Aktifkan Aksesibilitas"), `/screenshot` memakai `takeScreenshot()` sistem sehingga izinnya **tahan HP restart**; bila jalur ini belum aktif, aplikasi tetap memakai MediaProjection seperti sebelumnya.
- Tombol inline "🖥️ Beri izin sekali" pada pesan gagal `/screenshot` tanpa izin: sekali tekan membuka dialog izin sistem dari jendela transparan (`ui/ShotConsentActivity.kt`) lalu layar langsung diambil; bila izin "tampil di atas aplikasi lain" belum ada, bot menjelaskan cara mengaktifkannya.
- Kabar otomatis sekali tiap boot nyata (`BootReceiver`) bahwa izin tangkap layar hangus karena restart + dua cara mengatasinya; hanya dikirim bila jalur Aksesibilitas belum aktif.
- Baris status "Aksesibilitas: ..." di halaman Setup dan tombol "Aktifkan Aksesibilitas" yang membuka setelan Aksesibilitas.

### Changed
- Pesan gagal tangkap layar kini menyebut jalur Aksesibilitas dan tombol izin; kegagalan saat jalur Aksesibilitas aktif punya keterangan tersendiri.
- Penulisan JPEG tangkapan layar dipusatkan di `writeScreenshotJpeg()` (dipakai jalur Aksesibilitas maupun MediaProjection).
- `README.md` + `PRIVACY.md`: menjelaskan layanan Aksesibilitas dan pengaktifannya.
- `tools/verifikasi-path.py`: `<bool>` ikut dikenali sebagai definisi resource, jadi `@bool/...` yang sah tidak lagi selalu dituduh "acuan yatim".

## [1.6.395] - 2026-10-19
### Fixed
- `/recordvideo` tidak lagi "kehilangan" video saat diminta berturut-turut: pengunci `recordBusy` kini bertahan sampai rekaman benar-benar selesai (sebelumnya dilepas dalam milidetik karena `cameraService.captureVideo()` asinkron), sehingga permintaan kedua ditolak dengan pesan "Sedang merekam" alih-alih menaikkan nomor percobaan dan membuat rekaman pertama dihapus + permintaan kedua gagal "Kamera sedang dipakai". `/record` (audio) saat video sedang merekam juga ikut ditolak, dan watchdog timeout video (`videoWatchdog`) akhirnya bisa berjalan (sebelumnya cabangnya mustahil tercapai).
- `NotificationForwarderService`: kegagalan saat kiriman ulang sebagai teks polos (mis. koneksi putus sesaat) tidak lagi ditelan `catch` lalu menjatuhkan notifikasi ke jalur buang; kini naik ke penangan luar sehingga notifikasi masuk antrean, dan tidak lagi dilaporkan "ditolak/antrean penuh" tanpa sebab.
- `NotificationForwarderService`: `OnSharedPreferenceChangeListener` didaftarkan sinkron di `onCreate` (bukan di dalam coroutine), sehingga `onDestroy` tidak bisa lagi kalah balapan dan meninggalkan listener yang tak pernah dilepas.
- `NotificationForwarderService`: `onTrimMemory()` baru menyelamatkan buffer batch notifikasi ke antrean persisten saat memori kritis, sehingga notifikasi yang menunggu jendela batch 5 detik tidak hilang bila proses dibunuh.
- `CrashReporter.clearPending()` hanya menghapus laporan yang benar-benar terkirim (memakai `keepRemainder()`), bukan seluruh berkas; crash yang tercatat saat `/log` sedang mengirim tidak lagi ikut hilang.
- `SetupActivity`: *Clear credentials* membatalkan proses simpan/tes yang masih berjalan dan menaikkan `saveSeq`/`testSeq`, sehingga token/Chat ID tidak bisa ditulis ulang (dan monitoring ikut hidup lagi) bila pengguna menghapus saat jaringan lambat.
- 403 (bot diblokir/dikeluarkan) kini ikut menghentikan polling cepat: `NetworkUtils.isPollBlocked()` memakai `isAuthBlocked()` (401 + 403), tidak lagi hanya 401.
- `consumePendingInput()` hanya dikonsumsi oleh owner, sehingga anggota grup pada chat terkonfigurasi tidak lagi bisa menghapus status "menunggu masukan" milik pemilik.
- Teks tombol menu tidak lagi menampilkan `&amp;` mentah: "Foto &amp; rekam" → "Foto & rekam" (Telegram tidak mem-`parse_mode` teks tombol inline).
- Tangkap layar: bila `registerCallback` gagal, objek `MediaProjection` dihentikan dan izin sekali-pakai di-`revoke()`, sehingga tidak meninggalkan kebocoran/token izin hangus.
- `/hilang` dan `/location` ikut menaikkan penghitung gagal lokasi (`bumpLocFailCount()`), sehingga peringatan dini "GPS gagal beruntun" (`checkQuietHealth`) bisa muncul dari dua perintah itu — sebelumnya hanya `/darurat` dan patroli.
- Penulisan `lastPhotoTime` di jalur sukses `sendPhotoFile()` dibungkus `try`, sehingga foto yang sudah terkirim tidak dikirim ulang bila setter melempar.
- Jalur auth-blocked di `sendToTelegram()` memakai `queueForRetry()`, jadi worker pengiriman ikut dijadwalkan seperti jalur gagal lainnya.
- Validasi nomor `/sms` disederhanakan ke bentuk setara (`badChar || plusCount > 1`); dua disjungsi lamanya tak terjangkau.

### Changed
- `MainActivity`: pembuatan `EncryptedSharedPreferences` (`PreferencesManager.getInstance`) tidak lagi di main thread saat cold start — baris di `onCreate` dihapus karena blok `Background.runCritical` yang sudah ada melakukannya di thread latar (tidak ada pemakaian lain sebelum assignment itu).
- `MonitoringService`: satu instance `Gson` dipakai ulang untuk `menuBarBody()` (dulu objek baru tiap kirim foto/audio/video), dan persist offset `update_id` ganda setelah eksekusi perintah mutating dihapus (blok `finally` sudah menulis nilai yang sama, dengan getter yang membaca langsung dari preferensi sehingga tidak ada commit ganda).
- `NotificationForwarderService`: penghitung notifikasi dibuang di jalur panas memakai setter async `pendingNotifDrops` (`apply()`); penulisan sinkron tetap dipakai di `flushDropNotice()`/`onDestroy`.
- `PRIVACY.md`: menyebut **video dan screenshot** sebagai data yang dikirim sesuai permintaan, dan penyebutan `BOT_TOKEN`/`CHAT_ID` diganti "diisi di halaman Setup aplikasi" (kredensial memang hanya lewat `SetupActivity`).

### Removed
- `EditMessageRequest.disable_web_page_preview` (`TelegramApi.kt`) — tidak pernah dipakai.
- Dua pemanggilan `isConfigured()` tanpa efek di `NotificationForwarderService` (sisa refactor).


## [1.6.394] - 2026-10-19
### Added
- Menu Telegram berjenjang (interaktif): `/help` (dan `/start`) kini membuka **menu utama** berisi tombol cepat (Foto, Layar, Lokasi, Status, Baterai, Darurat) plus pintu kategori — **📷 Foto & rekam**, **📞 Komunikasi**, **🗺️ Lokasi & jejak**, **📊 Info & data**, **🛡️ Pengawasan**, **⚙️ Alat & kontrol**, dan **🔢 Atur angka**. Tiap kategori punya tombol **⬅️ Kembali** ke menu utama (`m:main`), termasuk panel "🔢 Atur angka" (permintaan langsung pemilik).
- Aksi tiap tombol dikirim lewat `callback_data` baru yang diurai fungsi murni `utils/MenuCallback.kt` (diuji JVM di `MenuCallbackTest`): `m:<view>` = pindah tampilan (pesan menu yang sama **diedit di tempat** lewat `editMessageText`, tidak menumpuk pesan baru), `x:<perintah>` = jalankan perintah langsung (mis. `x:patrol on`, `x:hilang`), `i:<view>:<cmd>`/`num:<view>:<cmd>` = tanya nilai dulu, lalu balasan pengguna dipakai sebagai argumen lewat mekanisme pending-input yang sudah ada (kini `pendingInputCommand`/`armInput()`/`consumePendingInput()` dan tak lagi khusus angka — perintah teks seperti `/sms`, `/home`, `/watch`, `/jampulang`, `/contacts`, `/history`, `/layar` punya prompt + keterangan `help` sendiri).
- Baris tombol ringkas **🏠 Menu utama** + **🔢 Atur angka** menempel di setiap kiriman bot (teks, foto, video, audio) sehingga menu selalu ada di pesan paling baru — menu tak lagi "tenggelam ke atas" saat ada output. Navigasi tetap bisa lewat panel penuh; tombol baris ringkas sengaja mengirim panel baru (`menu:open`, `menu:angka`), bukan mengedit pesan berisi hasil.

### Changed
- `network/TelegramApi.kt`: `TelegramIncomingMessage` menambah `message_id` (perlu untuk mengedit pesan), `EditMessageRequest` + `editMessageText()` baru, dan `sendPhoto`/`sendAudio`/`sendVideo` menerima bagian multipart `reply_markup` agar media ikut membawa tombol menu.
- Panel lama tetap kompatibel: `callback_data` versi `v1.6.391`–`v1.6.393` (`photo`, `shot`, `camfront`, `pause60`, `num:menu`, `num:record`, …) masih dikenali lewat tabel `LEGACY` di `MenuCallback.parse()`, jadi tombol pada pesan lama tetap berfungsi.
- Tombol papan angka yang diminta nilai kini membalas panel kategori asalnya (mis. **🎙️ Rekam audio** di kategori Foto & rekam membalas panel kategori itu, bukan panel angka), dan balasan yang bukan angka tidak lagi hangus diam-diam: bot mengingatkan "Kirim angkanya saja" + contoh, sambil tetap menyimpan status tombol selama 5 menit.
- `handleHelpCommand()` mengirim teks menu utama + panel (`menuScreen("main")`); `/tombol`/`/angka` mengirim layar `angka` yang sama seperti panel kategori.


## [1.6.393] - 2026-10-19
### Changed
- Teks `/help` tidak lagi memuat daftar perintah: isinya kini hanya judul "👆 Ketuk tombol di bawah" + tombol aksi (`mainMenu()`). Blok daftar perintah (49 baris, termasuk penutup "Tanpa / juga bisa: …") dihapus dari pesan; `handleHelpCommand()` menyusut dari 59 baris isi pesan menjadi satu kali `sendToTelegram(judul, mainMenu())`.
- Deskripsi perintah `help` di daftar perintah Telegram disesuaikan: `Daftar semua perintah` → `Tombol aksi cepat`, dan versi publik (non-owner) `Daftar perintah` → `Bantuan`, supaya cocok dengan isi barunya. Daftar perintah Telegram (menu `/`) sendiri belum disentuh — rujukan singkat tiap perintah (mis. `/home lat,lon,rad|status|off`) tetap ada di sana.
- Perilaku perintah tidak berubah: `/help` dan `/start` tetap membalas tombol, dan pengirim non-owner tetap menerima pesan pendek yang lama.
- `README.md` menyesuaikan baris `/help` serta catatan alur tombol.

## [1.6.392] - 2026-10-19
### Changed
- Perintah angka tidak lagi ditampilkan sebagai perintah teks: `/photointerval`, `/patrolinterval`, `/pause`, `/syncinterval`, `/record`, `/recordvideo`, `/ring`, `/heartbeat`, dan `/jemput` dihapus dari daftar perintah Telegram (menu `/`) dan dari teks `/help` — jalan resminya lewat tombol di `/tombol` (atau tombol "🔢 Atur angka" di menu utama). Mengetiknya langsung tetap berfungsi (`/photointerval 15`, `/jemput off`, `/heartbeat 0`, `/ring 20`), jadi tidak ada kemampuan yang hilang, hanya tampilannya yang dipindah ke tombol.
- Deskripsi perintah `tombol` di daftar perintah Telegram kini menyebut cakupannya (`Atur angka: interval, jeda, rekam, bunyi, denyut, jemput`) dan baris `/help` untuk `/tombol` merinci perintah yang ada di papan tombol.
- Keterangan tombol "🏠 Jemput" menyebut varian yang tidak ada di papan tombol (`/jemput off` untuk mematikan, `/jemput` untuk status), karena baris `/help`-nya kini disembunyikan.
- `README.md` menegaskan perintah angka sengaja tidak lagi muncul di menu `/` maupun di `/help`, dan bahwa pengetikan langsungnya masih sah.

## [1.6.391] - 2026-10-19
### Added
- Perintah angka kini bisa dipakai lewat tombol Telegram. Kirim `/tombol` (alias `tombol`/`angka`), atau ketuk tombol "🔢 Atur angka" di menu utama: bot menampilkan papan tombol berisi perintah yang butuh angka (`/photointerval`, `/patrolinterval`, `/pause`, `/syncinterval`, `/record`, `/recordvideo`, `/ring`, `/heartbeat`, `/jemput`). Ketuk satu tombol, bot membalas "Kirim angkanya (rentang)" beserta nilai yang sedang berlaku, lalu balasan angka berikutnya langsung menjalankan perintah itu — balas `15` setelah menekan **Interval foto** sama dengan `/photointerval 15`.
- Keterangan tombol: setelah menekan tombol, balas `help` (atau `bantuan`/`cara`/`?`) untuk dapat penjelasan tombol itu — guna, cara pakai, rentang, dan nilai sekarang. Status tombol bertahan 5 menit (`NUMBER_INPUT_TTL_MS = 300_000`) dan batal sendiri bila kamu mengirim perintah lain (`/...`) atau balasan yang bukan angka.

### Changed
- Perintah angka yang wajib diisi (`/photointerval`, `/patrolinterval`, `/pause`, `/syncinterval`, `/record`, `/recordvideo`) tanpa argumen kini membalas prompt + papan tombol, bukan hanya teks "Cara pakai".
- `app/src/main/java/com/redeye/parentalmonitor/utils/NumberReply.kt` (baru) mengurai balasan angka murni (bulat, maks 6 digit) dan diuji di JVM lewat `NumberReplyTest`.
- `/help` menyebut `/tombol`, daftar perintah bot Telegram menambah `tombol`, `mainMenu()` menambah baris "🔢 Atur angka" (`num:menu`), dan `MUTATING_COMMANDS`/`KNOWN_COMMANDS` menambah `/tombol` + `/angka` (khusus owner).
- `README.md` mencatat alur tombol angka di tabel perintah.

## [1.6.390] - 2026-10-19
### Changed
- R8 (langkah 4, batch 5 / penutup): empat cabang terakhir yang gemuk jadi fungsi bernama: `handleJejakCommand()` (13 baris), `handleHilangCommand()` (31), `handleJemputCommand(arg)` (25), dan `handleHelpCommand(senderOk)` (75; dipakai bersama `/help` dan `/start`). Ditambah satu baris kosong antar-fungsi yang ikut terhapus saat penyisipan helper.
- Hasil akhir langkah 4: `handleTelegramCommandInner()` **1070 → 126 baris**. Isinya kini tabel dispatch (`"/photo" -> handlePhotoCommand(arg)`, …) untuk 45 perintah yang ditangani fungsi bernama, ditambah 13 perintah kecil (≤9 baris, mis. `/stop`, `/battery`, `/alive`, `/cekmati`, `/sembuh`, `/layar`, `/digest`, `/minggu`, `/waspada`) yang sengaja dibiarkan di tempatnya karena lebih mudah dibaca begitu, serta cabang `else` untuk perintah tak dikenal.
- Bukti (skrip terpisah dari skrip transformasi): pembalikan berkas baru **identik karakter demi karakter** dengan fungsi lama (15.400 karakter, 274 baris), dan badan ke-4 fungsi baru sama persis dengan badan cabang lama setelah dedent 8 spasi (13/31/25/75 baris). Tidak ada perilaku yang berubah: semua badan cabang dipindah apa adanya, dan `return` tanpa label tetap sah karena `when` adalah pernyataan terakhir fungsi induk.

## [1.6.389] - 2026-10-19
### Changed
- R8 (langkah 4, batch 4): sembilan cabang berikutnya jadi fungsi bernama: `handleContactsCommand(arg)` (19 baris), `handleAppsCommand(arg)` (18), `handleStorageCommand()` (8), `handleHistoryCommand(arg)` (27), `handleWatchCommand(arg, privateChat)` (18), `handleFenceCommand(command, arg, privateChat)` (40; dipakai bersama oleh `/home` dan `/school`), `handleDaruratCommand()` (23), `handleJampulangCommand(arg, privateChat)` (15), dan `handleWatchNomorCommand(arg, privateChat)` (17). Fungsi induk kini 274 baris (dari 1070 sebelum langkah 4 dimulai).
- Bukti (skrip terpisah dari skrip transformasi): pembalikan berkas baru **identik karakter demi karakter** dengan `handleTelegramCommandInner` lama (27.130 karakter, 468 baris), dan badan ke-9 fungsi baru sama persis dengan badan cabang lama setelah dedent 8 spasi (19/18/8/27/18/40/23/15/17 baris).

## [1.6.388] - 2026-10-19
### Changed
- R8 (langkah 4, batch 3): sepuluh cabang berikutnya jadi fungsi bernama: `handleRecordCommand(arg)` (21 baris), `handleRecordVideoCommand(arg)` (26), `handleSmsCommand(arg, senderId)` (47), `handleSmsConfirmCommand(senderId)` (31), `handleLastNotifCommand()` (17), `handleVersionCommand()` (8), `handleUptimeCommand()` (17), `handleKendaraCommand(arg)` (18), `handleHematDataCommand(arg)` (13), dan `handleHeartbeatCommand(arg)` (13). Fungsi induk kini 468 baris (dari 1070 sebelum langkah 4 dimulai).
- Bukti (skrip terpisah dari skrip transformasi): pembalikan berkas baru **identik karakter demi karakter** dengan fungsi lama (42.297 karakter, 689 baris), dan badan ke-10 fungsi baru sama persis dengan badan cabang lama setelah dedent 8 spasi (21/26/47/31/17/8/17/18/13/13 baris).

## [1.6.387] - 2026-10-19
### Changed
- R8 (langkah 4, batch 2): sepuluh cabang `when` berikutnya di `handleTelegramCommandInner()` jadi fungsi bernama: `handleLocationCommand()` (19 baris), `handleCameraCommand(arg)` (13), `handleNotifCommand(arg)` (21), `handleLogCommand()` (37), `handleSyncIntervalCommand(arg)` (11), `handleFlushCommand()` (31), `handleClearQueueCommand()` (12), `handleLockCommand()` (14), `handleRingCommand(arg)` (18), dan `handlePingCommand(arg, sentAtSec, senderOk, chatOk, senderId, privateChat)` (38). Fungsi induk kini 689 baris (dari 1070 sebelum langkah ini).
- Bukti (skrip terpisah dari skrip transformasi): pembalikan berkas baru menghasilkan teks **identik karakter demi karakter** dengan `handleTelegramCommandInner` lama (54.176 karakter, 913 baris), dan badan ke-10 fungsi baru sama persis dengan badan cabang lama setelah dedent 8 spasi (19/13/21/37/11/31/12/14/18/38 baris).

## [1.6.386] - 2026-10-19
### Changed
- R8 (langkah 4, batch 1): `MonitoringService.handleTelegramCommandInner()` yang 1070 baris mulai dipecah. Sepuluh cabang `when` yang gemuk kini jadi fungsi bernama sendiri: `handlePhotoCommand(arg)` (17 baris), `handleStatusCommand()` (34), `handleRingkasCommand()` (14), `handleLastCallsCommand()` (11), `handleLastSmsCommand()` (12), `handlePhotoIntervalCommand(arg)` (10), `handlePatrolCommand(arg)` (14), `handleNightCommand(arg)` (18), `handlePauseCommand(arg)` (8), dan `handleResumeCommand()` (9). Sisanya masih di tempatnya; tabel dispatch kini `"/photo" -> handlePhotoCommand(arg)`, jadi panjang fungsi induk turun ke 913 baris tanpa memindahkan teks perintah yang kecil.
- Badan cabang dipindah UTUH: hanya di-dedent 8 spasi agar pas di badan fungsi; tidak ada satu karakter pun yang diubah, dan `return` tanpa label tetap `return` — sah karena `when` adalah pernyataan terakhir `handleTelegramCommandInner`, jadi tidak ada kode setelahnya yang jadi terlewat.
- Bukti (skrip terpisah dari skrip transformasi): berkas baru dibalik ulang (setiap baris dispatch diganti potongan cabang aslinya) dan hasilnya **identik karakter demi karakter** dengan `handleTelegramCommandInner` lama (63.629 karakter, 1070 baris). Badan ke-10 fungsi baru juga diperiksa satu per satu: sama persis dengan badan cabang lama setelah dedent 8 (17/34/14/11/12/10/14/18/8/9 baris). Batch berikutnya akan memindahkan cabang sisanya.

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
- `MonitoringService.checkAndSendNewData` menandai `lastSmsId`/`lastCallTimestamp` per chunk dan mengambil halaman lanjutan bila backlog >500, sehingga crash tidak mengulang batch dan kelebihan data tidak hilang.
- Sync awal idempoten via `initialSyncStarted` persisten: restart saat riwayat setengah terkirim tidak mengulang 100 SMS + 100 calls.
- Perintah Telegram yang masuk saat offline tetap dieksekusi; filter basi 600 detik yang membuang diam-diam dihapus.
- `CameraService` ganti `backgroundThread!!`/`fallback!!` dengan guard aman dan `join(2_000)` ber-timeout.
- `NotificationForwarderService` kunci dedup kini menyertakan ID notifikasi dengan jendela 60 detik agar pesan sah yang berulang tidak ikut terbuang.
- `SendMessageWorker` memakai `registerFailures()` bulk satu tulis per run.

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
- Kredensial Telegram di-cache di `MonitoringService` dan status jaringan di-cache 20 detik, hilangkan puluhan dekripsi dan query per batch.
- Loop kamera tidur 5 menit saat interval 0 alih-alih bangun tiap 60 detik.
- Delay history 2000/1000ms jadi 500ms dan jeda worker 200ms jadi 100ms.
- `MessageQueue.registerFailures()` menggantikan tulis-ulang per pesan gagal.

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
