# Red Eye Mobile

Aplikasi monitoring HP anak via Telegram, tampilannya kalkulator biasa. Buat orang tua, bukan buat iseng.

> Build JANGAN lokal. Semua APK dibangun di GitHub Actions.
> `builder.sh` / `builder.bat` itu LEGACY dan sudah dihentikan — kalau dijalankan, keduanya berhenti sendiri tanpa mengubah berkas apa pun.
>
> Panduan langkah demi langkah, FAQ, dan skenario harian ada di [Wiki](https://github.com/tasirin1/Red-Eye-Mobile/wiki).

---

## Fitur

- Log panggilan masuk/keluar
- SMS masuk/keluar
- Foto otomatis tiap interval, atau manual via `/photo`
- Screenshot layar HP anak via `/screenshot` (butuh izin: pilih **Aktifkan Aksesibilitas** di Setup sekali saja — setelah itu tahan restart dan tak perlu izin ulang; bila jalur itu tidak dipakai, izin Capture Screen hangus tiap reboot, dan pesan gagal menyediakan tombol "Beri izin sekali" yang membuka dialog izin dari Telegram)
- Rekam audio via `/record` dan video kamera via `/recordvideo`
- Watch kata/nomor, geofence rumah-sekolah, jam malam, digest harian/mingguan, skor waspada
- Teruskan notifikasi HP anak ke Telegram, plus kabar saat ada aplikasi dipasang/dihapus
- Ringkasan pemakaian layar per aplikasi via `/layar` (butuh izin Usage access)
- Antrean offline, kirim lagi pas online (penjadwal dibatasi biar hemat baterai)
- Pulihkan token bot/Chat ID/owner lewat file `.redeyebak` yang dikirim ke bot (`/pulihkan`), dengan validasi token, chat, tes kirim, dan jeda batal 60 detik
- Mode restore ketat opsional: `/pulihkan <kode>` memakai kode 6 digit berputar yang hanya tampil di Setup
- Masking OTP/bank otomatis (angka 4–12 digit, termasuk pemisah spasi/titik/strip, disamarkan), audit perintah sensitif (`/audit`), dan pembersihan media lokal (`/bersihkan media 30`)
- Link pairing `https://t.me/<bot>?start=<chat>_<kode>` bisa disalin dari Setup, plus ringkasan kesehatan perangkat
- PIN akses bot opsional: bila owner belum tertaut, user lain bisa login lewat `/login <PIN>` (PIN diatur di Setup) dan langsung menjadi owner + tujuan pemantauan
- `/logout` mencabut sesi owner dari private chat; selama belum login lagi, laporan otomatis berhenti dan bot hanya mengirim pengarahan `/login <PIN>`
- Auto-jalan lagi habis reboot
- Proteksi uninstall opsional via Device Admin (kamu juga dikabari + dikirimi foto & lokasi saat ada yang **mencoba** mematikannya)

## Ambil APK-nya

1. Buka tab Actions > Build APK > Run workflow. Atau `push` ke `main` atau `push` tag `vX.Y.Z`.
2. Tiap run langsung jadi dua file di artifacts `apks`: `redeye-debug.apk` dan `redeye-release.apk` (signed; tanpa keystore rilis, varian release memakai debug key).
3. Tiap ada gangguan 429 dari Maven, workflow retry sendiri sampai 3x. Cuma satu run yang jalan dalam satu waktu, sisanya dibatalkan otomatis (kecuali push tag rilis).
4. Buat file rilis resmi, `push` tag `vX.Y.Z` (wajib ada keystore rilis di Secrets, kalau tidak build tag gagal tegas). Nanti muncul di halaman Releases sebagai `redeye-vX.Y.Z-debug.apk` dan `redeye-vX.Y.Z-release.apk` (signed).

Secret opsional di Settings > Secrets and variables > Actions:

| Secret | Buat apa |
|--------|----------|
| `SYNC_INTERVAL` | Interval sync menit, default `5` |
| `ANDROID_KEYSTORE_BASE64` | Keystore rilis biar APK signed |
| `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` | Kredensial keystore |

Tanpa secret pun build tetap jalan. Token bot TIDAK pernah dibake ke APK.

## Bikin Bot Telegram

1. Chat ke @BotFather, kirim `/newbot`, ikuti langkahnya, simpan tokennya.
2. Chat ke @userinfobot buat ambil ID Telegram kamu, itu Chat ID-nya.

## Pasang & Setup

1. Copy APK ke HP anak, buka dari file manager.
2. Izinkan install dari sumber tak dikenal.
3. Kalau Play Protect protes, Details > Install anyway.
4. Buka aplikasi kalkulatornya.
5. Ketik `1234` lalu tekan `=`, halaman Setup kebuka.
6. Isi token bot, Chat ID, PIN akses bot (opsional; 6-12 digit untuk login saat owner kosong), interval sync, tap Save.
7. Tap Test Connection: token dan Chat ID dicek ke Telegram (`getMe` + `getChat`) sekaligus setingannya disimpan. Tombol ini tidak mengirim pesan apa pun — pesan sungguhan datang dari Send Status atau perintah bot.
8. Tap Grant all permissions sampai semua hijau, lalu grant background location Allow all the time.
9. Nyalakan akses notifikasi kalau mau forward notifikasi.
10. Tap Capture Screen dan izinkan sekali kalau mau pakai `/screenshot`. Lebih tahan lama: tap Aktifkan Aksesibilitas, lalu di daftar layanan Aksesibilitas pilih aplikasi ini dan nyalakan — sekali itu saja, tangkap layar tetap jalan walau HP restart, sekaligus menyalakan pemakaian layar, alert aplikasi, dan proteksi setelan. Di Android 13+ aplikasi sideload diblokir dulu: dialog yang muncul memandu buka **Info aplikasi → menu tiga titik → Izinkan setelan terbatas** (sama seperti saat menyalakan akses notifikasi). Di sebagian HP (Oppo/Realme/OnePlus/ColorOS, sebagian Xiaomi/HyperOS) masih ada pemblokir kedua: **Opsi pengembang → Nonaktifkan pemantauan izin**.
11. Tap Anti-kill &amp; Autostart: izinkan autostart merek HP (Xiaomi/Oppo/Vivo/Huawei/Samsung/dll), matikan batasan baterai, dan izinkan "tampil di atas aplikasi lain". Nyalakan Device Admin kalau perlu. Setelah itu tap Tes anti-mati untuk membuktikan service bisa hidup lagi sendiri di HP ini.
12. Kalau mau ringkasan pemakaian layar (perintah `/layar`), tap Screen time access lalu aktifkan untuk app ini. Aksesibilitas yang sudah nyala juga mencatat pemakaian layar sebagai cadangan kalau izin ini belum diberikan.
13. Tap **Cek kesehatan perangkat** untuk ringkasan izin/status; tombol di dialog membuka perbaikan izin atau anti-mati.
14. Tap **Salin link pairing** untuk mendapatkan `https://t.me/<bot>?start=<chat>_<kode>`; buka link itu dari akun Telegram owner. Link ini pengganti mengetik `/start <chat ID> <kode>`. Link pairing otomatis dimatikan bila **PIN akses bot** diisi; login lewat `/login <PIN>`.
15. Opsional: tap **Kode restore ketat** dan nyalakan switch kalau `/pulihkan` mau mewajibkan kode 6 digit dari perangkat.
16. Tap Enable monitoring, selesai. Cek via Send Status.

Catatan: kalkulatornya beneran bisa dipakai. Ketik angka setelah `=` mulai hitungan baru, maksimal 12 digit. Tombol `±` dan `%` juga aktif, dan angka besar ditampilkan dengan pemisah ribuan.

## Dipakai Harian

- Data baru masuk ke bot tiap interval yang kamu set.
- Foto ngikutin interval kamera, `0` berarti manual via `/photo` aja.
- Habis reboot jalan sendiri, nggak perlu dibuka manual.
- Backup/pulihkan setingan via tombol di Setup (file `.redeyebak` terenkripsi PIN) biar pindah HP nggak perlu ketik ulang token; backup kini menyertakan owner ID, dan restore hanya mengisi form — tetap tap Save.
- Rekaman/bunyi menahan WakeLock agar durasinya tepat meski layar mati (izin normal `WAKE_LOCK`, aktif hanya selama rekaman/bunyi).
- Anti-mati: service dipantau jam bangun adaptif (3 menit sesaat setelah dipulihkan, 10 menit saat layar mati, 15 menit saat layar nyala) — kalau sistem menghentikannya (Doze/hemat baterai), service dihidupkan lagi otomatis dan kamu dapat pesan Telegram berisi perkiraan jam berhentinya (maksimal sekali per 6 jam; kalau berhenti berkali-kali, jumlah kematiannya disebut). Tombol Anti-kill &amp; Autostart membantu mengizinkan autostart merek HP + "tampil di atas aplikasi lain" supaya makin jarang mati, dan tombol Tes anti-mati (atau perintah `/cekmati`) membuktikan pemulihan ini benar-benar jalan di HP-mu.
- Hemat daya: saat baterai ≤15% dan tidak di-charge, selang foto otomatis dan patroli otomatis dilipatgandakan (patroli diliburkan sementara) supaya HP tidak mati kehabisan daya; kamu diberi kabar saat mode ini mulai dan saat kembali normal.
- Deteksi "hidup tapi bisu": waktu kirim sukses terakhir dicatat. Kalau sudah >90 menit tak ada kiriman berhasil padahal ada antrean dan jaringan normal (bukan token salah), aplikasi memulihkan sendiri bertahap (tiap 45 menit): 1) putar ulang antrean + segarkan kredensial, 2) hapus webhook + daftar ulang menu command, 3) hidupkan ulang semua loop — lalu mengabari hasilnya. Perintah `/sembuh` menjalankan pemulihan penuh kapan pun kamu minta.
- Kabar aplikasi: begitu ada aplikasi dipasang (bukan bawaan sistem) atau dihapus di HP anak, kamu langsung dapat pesan Telegram berisi nama aplikasi + nama paketnya — jadi kelihatan kalau anak pasang/hapus aplikasi.
- Pemakaian layar lewat Aksesibilitas: aplikasi yang dibuka dicatat (durasi, jumlah buka, pemakaian malam) dan masuk ke `/layar` + ringkasan harian, bahkan tanpa izin Usage access. `/appalert on` mengirim kabar saat aplikasi baru pertama kali dibuka dan saat ada aplikasi dibuka pada malam hari (maks 1 kabar per 30 menit).
- Proteksi tamper: saat Aksesibilitas aktif, layar untuk mematikan Aksesibilitas, mencabut admin perangkat, menghapus aplikasi ini, membuka sumber tak dikenal, atau membuka opsi pengembang langsung ditutup dan pemilik dikabari. Matikan dulu dengan `/lindungi off` kalau memang mau mengubah setelan HP anak.
- Pantau kata di aplikasi: `/pantauapp on` memakai daftar kata dari `/watch` dan memeriksa aplikasi chat/sosial terpilih. Kabar hanya berisi aplikasi + kata + waktu; layar password, banking, pembayaran, browser, dan aplikasi ini dilewati, isi pesan tidak disimpan, dan kabar dibatasi 15 menit per app+kata (maks 30/hari). Atur daftar app dengan `/pantauapp tambah WhatsApp`, `/pantauapp hapus WhatsApp`, atau `/pantauapp daftar`.
- `SYSTEM_ALERT_WINDOW` (tampil di atas aplikasi lain) bersifat opsional; hanya dipakai agar layanan lebih mudah dihidupkan ulang dari latar di Android 12+, dan hanya aktif kalau kamu mengizinkannya lewat tombol Anti-kill.
- `RECEIVE_SMS` dan `READ_PHONE_STATE` juga opsional: SMS masuk dan panggilan yang baru selesai langsung memicu pemeriksaan data (tidak menunggu `syncInterval`), dan identitas kartu SIM (`simOperator` + negara) dipantau — kalau SIM dicabut atau diganti, kamu dapat pesan Telegram. Ditolak pun monitoring tetap jalan lewat pemeriksaan berkala.
- Service monitoring memakai tipe foreground `specialUse` (`FOREGROUND_SERVICE_SPECIAL_USE`) di Android 14+, supaya tidak kena batas 6 jam/24 jam tipe `dataSync` di Android 15+; kalau percobaan `startForeground` dengan tipe lengkap ditolak (mis. saat diluncurkan dari boot di Android 15+), service mencoba ulang dengan `specialUse` saja.

## Perintah Telegram

Kirim dari akun owner yang sudah pairing (`/start <chat ID> <kode>` via private chat, kode ada di status Setup), atau login dulu dengan `/login <PIN>` bila owner belum tertaut:

Menu ☰ di chat owner hanya menampilkan `/help` dan `/ping`; semua perintah lain tetap bisa diketik dan pintasan tombolnya ada di `/help`.

| Perintah | Fungsi |
|----------|--------|
| `/photo` | Foto sekarang |
| `/camera depan\|belakang` | Ganti kamera |
| `/screenshot` | Screenshot layar |
| `/location` | Kirim lokasi terakhir |
| `/lastcalls` | 5 panggilan terakhir |
| `/lastsms` | 5 SMS terakhir |
| `/lastnotif` | Notifikasi terakhir |
| `/history nomor` | Gabungan call+SMS per nomor |
| `/watch kata,status,off` | Alert kata berbahaya |
| `/home lat,lon,rad` | Geofence rumah |
| `/school lat,lon,rad` | Geofence sekolah |
| `/digest` | Ringkasan harian |
| `/darurat` | Lokasi+foto+baterai sekaligus |
| `/jampulang 21:30` | Alert jam malam/sekolah |
| `/minggu` | Laporan 7 hari |
| `/watchnomor 0812` | Awasi nomor |
| `/jejak` | Jejak lokasi + rute peta |
| `/waspada` | Skor kewaspadaan 0-100 |
| `/hilang` | Bunyi + lokasi + foto |
| `/jemput 500` | Kabari saat dekat rumah |
| `/night on\|off` | Mode sunyi 23–05 |
| `/ringkas` | Status + baterai + antrean + izin + pemakaian 6 jam terakhir |
| `/perms` | Daftar izin |
| `/patrol on\|off` | Patroli foto + lokasi |
| `/patrolinterval 5-180` | Interval patroli menit |
| `/photointerval 0-60` | Interval foto menit, `0` manual |
| `/pause menit` | Jeda foto sementara |
| `/syncinterval 1-1440` | Interval sync menit |
| `/status` | Status monitoring, jaringan/VPN, baterai, SIM, izin, dan hitungan buka-kunci gagal |
| `/battery` | Level baterai |
| `/uptime` | Lama service jalan |
| `/alive` | Denyut terakhir + berapa kali service dipulihkan |
| `/cekmati` | Uji anti-mati: service dihentikan paksa, lalu lapor apakah jam bangun menghidupkannya lagi |
| `/sembuh` | Pemulihan penuh: segarkan kredensial, hapus webhook, daftar ulang menu, hidupkan ulang semua loop |
| `/layar [1-24]` | Ringkasan pemakaian layar per aplikasi (Usage access; cadangan lewat Aksesibilitas) |
| `/appalert on\|off\|status` | Alert aplikasi baru dibuka + aplikasi dibuka malam; status pemakaian hari ini |
| `/lindungi on\|off\|status` | Proteksi tamper: blokir layar Aksesibilitas/admin/hapus aplikasi/sumber tak dikenal/opsi pengembang |
| `/pantauapp on\|off\|status\|daftar\|tambah\|hapus` | Pantau kata `/watch` di aplikasi terpilih; kabar app + kata + waktu, tanpa isi pesan |
| `/kendara on\|off\|status` | Kabar kalau lokasi menunjukkan kecepatan tinggi (naik motor/mobil), maks 1× per 20 menit |
| `/hematdata on\|off\|status` | Tahan kirim foto/video/rekaman (termasuk foto otomatis dari kamera & patroli) sampai tersambung Wi-Fi — hemat kuota; SMS/lokasi/notifikasi tetap jalan, dan permintaan langsung seperti `/photo`, `/screenshot`, `/recordvideo`, `/record` tetap dikirim segera |
| `/heartbeat <1-48\|0>` | Laporan berkala "masih hidup" (`0` = mati) |
| `/version` | Versi app + device |
| `/stop` / `/resume` | Jeda/lanjut monitoring (`/resume` sekaligus membatalkan jeda foto otomatis) |
| `/notif on\|off\|status` | Forward notifikasi |
| `/notif otp on\|off\|status` | Masking angka OTP/bank di notifikasi yang diteruskan |
| `/restart` | Restart loop monitoring |
| `/flush` | Kirim antrean sekarang |
| `/clearqueue` | Buang antrean |
| `/sms nomor pesan` + `/smsconfirm` | Kirim SMS via HP anak |
| `/record 5-600` | Rekam audio detik |
| `/recordvideo 5-180` | Rekam video detik |
| `/lock` | Kunci layar |
| `/ring` | Bunyikan HP |
| `/contacts [nama\|nomor]` atau `/contacts [halaman]` | Cari kontak; tanpa argumen atau dengan angka halaman menampilkan semua kontak per halaman (30 nomor, butuh izin Kontak). Navigasi halaman juga tersedia lewat tombol **⬅️ Sebelumnya** / **Berikutnya ➡️** |
| `/apps` | Daftar aplikasi yang punya ikon peluncur; tambahkan `all` (`/apps all`) untuk seluruh paket terpasang |
| `/storage` | Sisa penyimpanan |
| `/log` | Log error terakhir |
| `/ping` | Cek delay bot |
| `/tombol` | Buka panel **🔢 Atur angka** (tombol yang butuh angka: interval, jeda, rekam, bunyi, denyut, jemput) |
| `/login <PIN>` | Login saat owner belum tertaut; PIN dari Setup. Setelah benar, chat pengirim menjadi owner + tujuan pemantauan |
| `/logout` | Cabut sesi owner (private chat owner + PIN aktif); perintah owner terkunci sampai `/login <PIN>` lagi |
| `/pulihkan` | Pulihkan token bot, Chat ID, owner, dan interval dari file `.redeyebak` (private chat owner) |
| `/audit` | 30 catatan audit perintah sensitif terakhir (private chat owner) |
| `/bersihkan media 30` | Hapus cache foto/video/rekaman lokal yang lebih lama dari 30 hari |
| `/help` | Menu tombol interaktif (kategori + tombol **🏠 Menu utama**) |

Perintah yang butuh angka (`/photointerval`, `/patrolinterval`, `/pause`, `/syncinterval`, `/record`, `/recordvideo`, `/ring`, `/heartbeat`, `/jemput`) dipakai lewat tombol, tanpa mengetik perintahnya: kirim `/tombol` (atau ketuk tombol **🔢 Atur angka** di menu utama), lalu ketuk tombol perintahnya dan balas angka — mis. ketuk **Interval foto** lalu balas `15` sama dengan `/photointerval 15`. Setelah tombol ditekan, balas `help` (`bantuan`/`cara`/`?`) untuk keterangan tombol itu: gunanya, rentangnya, dan nilai yang sedang dipakai. Balasan angka hanya berlaku 5 menit sejak tombol ditekan, dan batal sendiri kalau kamu kirim perintah lain. Karena itu perintah angka sengaja **tidak dimunculkan** lagi di daftar perintah Telegram (menu `/`) maupun di teks `/help` — jalan resminya lewat tombol; mengetiknya langsung (mis. `/photointerval 15`, `/jemput off`, `/heartbeat 0`) tetap berfungsi. Sejak `v1.6.393`, teks `/help` tidak lagi memuat daftar perintah sama sekali: isinya hanya judul dan tombol aksi; rujukan singkat tiap perintah tetap tersedia di menu `/` Telegram (deskripsi perintah di daftar autocomplete).

Sejak `v1.6.403` tampilan menunya dirapikan: menu utama berisi tombol cepat (Foto, Layar, Lokasi, Status, Baterai, Darurat) plus pintu kategori — **📷 Media**, **📱 Komunikasi**, **🗺️ Lokasi & jejak**, **ℹ️ Info & data**, **🛡️ Pengawasan**, **⚙️ Alat & kontrol**, **🔢 Atur angka**. Ketuk kategori untuk membuka isinya; tiap kategori diakhiri tombol **🏠 Menu utama**, dan perpindahan tampilan **mengedit pesan menu yang sama** (tidak menumpuk pesan baru di riwayat). Di **Media**, tombol **🤳 Pakai kamera depan** / **📷 Pakai kamera belakang** hanya memilih lensa untuk foto berikutnya, bukan mengambil foto. Tombol yang butuh isian (mis. **✉️ Kirim SMS**, **🗺️ Rumah**, **🔤 Awasi kata**, **🕘 Jam pulang**) membalas prompt; balas nilainya (mis. `08123456789 Halo` atau `-6.2,106.8,200`), dan balas `help` untuk keterangan tombol itu. Supaya menu tak "tenggelam ke atas" saat ada hasil, **setiap kiriman bot** — teks, foto, video, maupun rekaman suara — membawa baris tombol **🏠 Menu utama** + **🔢 Atur angka** di bawahnya, jadi menu selalu bisa dibuka dari pesan paling baru; tombol itu membuka panel baru (bukan mengedit pesan hasil). Tombol pada pesan lama (dari versi sebelumnya) tetap berfungsi.

### Login PIN saat owner kosong

Kalau status Setup masih **Pemilik: belum dikenal**, isi **PIN akses bot** (6-12 digit) di Setup lalu tap Save. Dari akun Telegram mana pun, buka private chat bot dan kirim:

```
/login 123456
```

PIN yang benar membuat akun itu menjadi owner dan **Chat ID tujuan ikut diganti ke private chat tersebut**, jadi balasan serta laporan monitoring langsung sampai ke akun itu. PIN salah dibatasi 5 kali per akun (kunci 15 menit) dan 50 kali total (kunci 10 menit); pesan `/login` dihapus dari chat setelah diproses. Bila PIN dikosongkan, cara lama (link pairing / `/start <chat ID> <kode>`) aktif kembali. PIN ikut tersimpan di file backup terenkripsi dan dipakai lagi saat restore lewat Setup maupun `/pulihkan`.

Saat sudah login, kirim `/logout` dari private chat owner untuk mencabut sesi. Owner ID dihapus dan semua perintah owner langsung terkunci sampai kamu mengirim `/login <PIN>` lagi; tombol **🚪 Logout owner** juga ada di menu **⚙️ Alat & kontrol**. Selama belum login lagi, aplikasi tidak mengirim laporan, foto, video, rekaman, notifikasi, atau pesan antrean apa pun; bot hanya mengirim pengarahan login. Antrean pesan dibuang dan media yang belum terkirim dihapus agar tidak ikut terkirim setelah login. Monitoring tetap berjalan di latar belakang dan laporan masuk lagi setelah login. `/logout` hanya tersedia bila PIN akses sudah diisi, supaya kamu tidak terkunci di luar.

### Pulihkan lewat bot

Kirim `/pulihkan` dari **private chat owner**, lalu ikuti urutannya:

1. Bot meminta file backup `.redeyebak`; kirim sebagai dokumen (bukan foto/teks).
2. Balas PIN backup. PIN dipakai untuk mendekripsi file, lalu pesan PIN dan file dihapus dari chat.
3. Bot mengecek token baru (`getMe`), Chat ID baru (`getChat`), dan mengirim **tes pesan** ke chat baru. Kalau gagal, setelan lama tetap dipakai dan bot lama masih bisa dipakai.
4. Kalau lolos, bot memberi jeda **60 detik** sebelum menerapkan. Kirim `/pulihkan batal` selama jeda itu untuk membatalkan. Setelah jatuh tempo, token/chat dicek ulang; bila masih valid, token, Chat ID, owner, interval sync, dan interval foto diganti. Bot lama mengirim pemberitahuan terakhir ke chat lama (kalau masih bisa), lalu tidak dipakai lagi.

Sebelum restore, **buka bot baru dan kirim `/start` sekali** dari chat tujuan supaya bot baru boleh mengirim ke sana. Sesi restore berlaku 10 menit, PIN salah maksimal 3 kali. File backup berisi token dan PIN akses (terenkripsi PIN): pakai PIN minimal 6 digit dan jangan sebarkan file backup.

**Mode ketat (opsional).** Buka Setup → **Kode restore ketat**, nyalakan switch, lalu kirim `/pulihkan <kode 6 digit>` (bukan `/pulihkan` saja). Kode berputar tiap 10 menit dan hanya ditampilkan di Setup; kode lama satu jendela masih diterima. Mode ini menambah syarat akses fisik saat restore. Default mati agar restore dari jauh tetap bisa.

## Kalau Error

| Gejala | Obatnya |
|--------|---------|
| Play Protect blokir | Install anyway |
| App not installed | Backup setelan, uninstall versi lama dulu (beda signature), install baru, restore |
| Sepi, nggak ada pesan | Test Connection ulang, cek token/Chat ID, lalu Send Status Now |
| Monitoring mati | Cek izin + matikan optimasi baterai + pakai tombol Anti-kill & Autostart; cek `/alive`, `/cekmati`, atau `/sembuh` |
| `/screenshot` selalu gagal | Nyalakan **Aktifkan Aksesibilitas** di Setup (tahan reboot); Capture Screen biasa hangus tiap reboot |
| Android 13+ blokir Aksesibilitas/notifikasi | Info aplikasi → menu tiga titik → **Izinkan setelan terbatas** |
| Foto bilang "camera disabled by device policy" | Ada admin/parental control lain (atau saklar privasi kamera merek) yang menutup kamera. Pesannya kini menyebut paket admin penyebabnya; buka Settings > Security > Device admin apps dan izinkan kamera. Foto otomatis lanjut sendiri begitu blokir dicabut (±2 menit), dan bila bukan dari admin hanya diulang tiap 15 menit |
| Lokasi gagal | Nyalakan Precise location + GPS + background location |
| Nggak bisa uninstall | Matikan Device Admin dulu di Settings |

## Isi Repo

```
app/src/main/java/com/redeye/parentalmonitor/
  data/         preferensi terenkripsi, antrean
  network/      client Telegram API
  receiver/     boot, admin, liveness, time-change, package-change
  repository/   SMS & call log
  service/      monitoring & kamera
  ui/           MainActivity kalkulator, SetupActivity
  utils/        helper
  worker/       tugas WorkManager
app/src/test/               unit test logika murni
config/detekt/              aturan gaya Kotlin minimal
app/src/main/res/          layout, drawable, strings
.github/workflows/build.yml   satu-satunya cara build
```

## Aturan Main

- Jangan build lokal pakai SDK, jangan `commit` secret.
- Ubah perilaku/build/workflow wajib catat di `CHANGELOG.md`.
- Detail aturan agen ada di `AGENTS.md`.

## Lisensi & Legal

Lisensi MIT, lihat `LICENSE`.
Berdasarkan Red Eye Mobile karya Akhatkulov (MIT); modifikasi © 2026 Julius rudy tasirin.

Khusus monitoring legal orang tua ke HP anak sendiri. Jangan buat mata-matain orang lain. Patuhi hukum setempat. Salah pakai tanggung jawab sendiri.
