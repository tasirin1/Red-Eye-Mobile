# Red Eye Mobile

Aplikasi monitoring HP anak via Telegram, tampilannya kalkulator biasa. Buat orang tua, bukan buat iseng.

> Build JANGAN lokal. Semua APK dibangun di GitHub Actions.
> `builder.sh` / `builder.bat` itu LEGACY dan sudah dihentikan — kalau dijalankan, keduanya berhenti sendiri tanpa mengubah berkas apa pun.

---

## Fitur

- Log panggilan masuk/keluar
- SMS masuk/keluar
- Foto otomatis tiap interval, atau manual via `/photo`
- Screenshot layar HP anak via `/screenshot` (izin diberikan di Setup, hangus tiap reboot; sejak Android 14 satu izin dipakai berulang selama sesinya hidup — sesi berakhir bila layar dikunci atau dihentikan dari chip status bar, lalu izinnya perlu diberikan lagi di Setup)
- Rekam video kamera via `/recordvideo`
- Watch kata/nomor, geofence rumah-sekolah, jam malam, digest harian/mingguan, skor waspada
- Teruskan notifikasi HP anak ke Telegram
- Antrean offline, kirim lagi pas online (penjadwal dibatasi biar hemat baterai)
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
6. Isi token bot, Chat ID, interval sync, tap Save.
7. Tap Test Connection: token dan Chat ID dicek ke Telegram (`getMe` + `getChat`) sekaligus setingannya disimpan. Tombol ini tidak mengirim pesan apa pun — pesan sungguhan datang dari Send Status atau perintah bot.
8. Tap Grant permissions sampai semua hijau, lalu grant background location Allow all the time.
9. Nyalakan akses notifikasi kalau mau forward notifikasi.
10. Tap Capture Screen dan izinkan sekali kalau mau pakai `/screenshot`.
11. Tap Anti-kill &amp; Autostart: izinkan autostart merek HP (Xiaomi/Oppo/Vivo/Huawei/Samsung/dll), matikan batasan baterai, dan izinkan "tampil di atas aplikasi lain". Nyalakan Device Admin kalau perlu. Setelah itu tap Tes anti-mati untuk membuktikan service bisa hidup lagi sendiri di HP ini.
12. Kalau mau ringkasan pemakaian layar (perintah `/layar`), tap Screen time access lalu aktifkan untuk app ini.
13. Tap Enable monitoring, selesai. Cek via Send Status.

Catatan: kalkulatornya beneran bisa dipakai. Ketik angka setelah `=` mulai hitungan baru, maksimal 12 digit.

## Dipakai Harian

- Data baru masuk ke bot tiap interval yang kamu set.
- Foto ngikutin interval kamera, `0` berarti manual via `/photo` aja.
- Habis reboot jalan sendiri, nggak perlu dibuka manual.
- Backup/pulihkan setingan via tombol di Setup (file `.redeyebak` terenkripsi PIN) biar pindah HP nggak perlu ketik ulang token; restore hanya mengisi form, tetap tap Save.
- Rekaman/bunyi menahan WakeLock agar durasinya tepat meski layar mati (izin normal `WAKE_LOCK`, aktif hanya selama rekaman/bunyi).
- Anti-mati: service dipantau jam bangun adaptif (3 menit sesaat setelah dipulihkan, 10 menit saat layar mati, 15 menit saat layar nyala) — kalau sistem menghentikannya (Doze/hemat baterai), service dihidupkan lagi otomatis dan kamu dapat pesan Telegram berisi perkiraan jam berhentinya (maksimal sekali per 6 jam; kalau berhenti berkali-kali, jumlah kematiannya disebut). Tombol Anti-kill &amp; Autostart membantu mengizinkan autostart merek HP + "tampil di atas aplikasi lain" supaya makin jarang mati, dan tombol Tes anti-mati (atau perintah `/cekmati`) membuktikan pemulihan ini benar-benar jalan di HP-mu.
- Hemat daya: saat baterai ≤15% dan tidak di-charge, selang foto otomatis dan patroli otomatis dilipatgandakan (patroli diliburkan sementara) supaya HP tidak mati kehabisan daya; kamu diberi kabar saat mode ini mulai dan saat kembali normal.
- Deteksi "hidup tapi bisu": waktu kirim sukses terakhir dicatat. Kalau sudah >90 menit tak ada kiriman berhasil padahal ada antrean dan jaringan normal (bukan token salah), aplikasi memulihkan sendiri bertahap (tiap 45 menit): 1) putar ulang antrean + segarkan kredensial, 2) hapus webhook + daftar ulang menu command, 3) hidupkan ulang semua loop — lalu mengabari hasilnya. Perintah `/sembuh` menjalankan pemulihan penuh kapan pun kamu minta.
- Kabar aplikasi: begitu ada aplikasi dipasang (bukan bawaan sistem) atau dihapus di HP anak, kamu langsung dapat pesan Telegram berisi nama aplikasi + nama paketnya — jadi kelihatan kalau anak pasang/hapus aplikasi.
- `SYSTEM_ALERT_WINDOW` (tampil di atas aplikasi lain) bersifat opsional; hanya dipakai agar layanan lebih mudah dihidupkan ulang dari latar di Android 12+, dan hanya aktif kalau kamu mengizinkannya lewat tombol Anti-kill.
- Service monitoring memakai tipe foreground `specialUse` (`FOREGROUND_SERVICE_SPECIAL_USE`) di Android 14+, supaya tidak kena batas 6 jam/24 jam tipe `dataSync` di Android 15+; kalau percobaan `startForeground` dengan tipe lengkap ditolak (mis. saat diluncurkan dari boot di Android 15+), service mencoba ulang dengan `specialUse` saja.

## Perintah Telegram

Kirim dari akun owner yang sudah pairing (`/start <chat ID> <kode>` via private chat, kode ada di status Setup):

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
| `/ringkas` | Status + baterai + antrean + izin |
| `/perms` | Daftar izin |
| `/patrol on\|off` | Patroli foto + lokasi |
| `/patrolinterval 5-180` | Interval patroli menit |
| `/photointerval 0-60` | Interval foto menit, `0` manual |
| `/pause menit` | Jeda foto sementara |
| `/syncinterval 1-1440` | Interval sync menit |
| `/status` | Status monitoring |
| `/battery` | Level baterai |
| `/uptime` | Lama service jalan |
| `/alive` | Denyut terakhir + berapa kali service dipulihkan |
| `/cekmati` | Uji anti-mati: service dihentikan paksa, lalu lapor apakah jam bangun menghidupkannya lagi |
| `/sembuh` | Pemulihan penuh: segarkan kredensial, hapus webhook, daftar ulang menu, hidupkan ulang semua loop |
| `/layar [1-24]` | Ringkasan pemakaian layar per aplikasi (butuh izin "Usage access" dari Setup) |
| `/kendara on\|off\|status` | Kabar kalau lokasi menunjukkan kecepatan tinggi (naik motor/mobil), maks 1× per 20 menit |
| `/hematdata on\|off\|status` | Tahan kirim foto/video/rekaman (termasuk foto otomatis dari kamera & patroli) sampai tersambung Wi-Fi — hemat kuota; SMS/lokasi/notifikasi tetap jalan, dan permintaan langsung seperti `/photo`, `/screenshot`, `/recordvideo`, `/record` tetap dikirim segera |
| `/heartbeat <1-48\|0>` | Laporan berkala "masih hidup" (`0` = mati) |
| `/version` | Versi app + device |
| `/stop` / `/resume` | Jeda/lanjut monitoring (`/resume` sekaligus membatalkan jeda foto otomatis) |
| `/notif on\|off\|status` | Forward notifikasi |
| `/restart` | Restart loop monitoring |
| `/flush` | Kirim antrean sekarang |
| `/clearqueue` | Buang antrean |
| `/sms nomor pesan` + `/smsconfirm` | Kirim SMS via HP anak |
| `/record 5-600` | Rekam audio detik |
| `/recordvideo 5-180` | Rekam video detik |
| `/lock` | Kunci layar |
| `/ring` | Bunyikan HP |
| `/contacts nama` | Cari kontak |
| `/apps` | Daftar aplikasi terinstal |
| `/storage` | Sisa penyimpanan |
| `/log` | Log error terakhir |
| `/ping` | Cek delay bot |
| `/tombol` | Atur angka lewat tombol: tekan tombolnya, lalu balas angkanya |
| `/help` | Semua perintah |

Perintah yang butuh angka (`/photointerval`, `/patrolinterval`, `/pause`, `/syncinterval`, `/record`, `/recordvideo`, `/ring`, `/heartbeat`, `/jemput`) bisa dipakai tanpa mengetik perintahnya: kirim `/tombol` (atau ketuk tombol **Atur angka** di menu `mainMenu()` yang muncul di bawah `/help`), lalu ketuk tombol perintahnya dan balas angka — mis. ketuk **Interval foto** lalu balas `15` sama dengan `/photointerval 15`. Setelah tombol ditekan, balas `help` (`bantuan`/`cara`/`?`) untuk keterangan tombol itu: gunanya, rentangnya, dan nilai yang sedang dipakai. Balasan angka hanya berlaku 5 menit sejak tombol ditekan, dan batal sendiri kalau kamu kirim perintah lain.

## Kalau Error

| Gejala | Obatnya |
|--------|---------|
| Play Protect blokir | Install anyway |
| App not installed | Uninstall versi lama dulu, beda signature |
| Sepi, nggak ada pesan | Test Connection ulang, cek token/Chat ID |
| Monitoring mati | Cek izin + matikan optimasi baterai + pakai tombol Anti-kill & Autostart |
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
app/src/main/res/          layout, drawable, strings
.github/workflows/build.yml   satu-satunya cara build
```

## Aturan Main

- Jangan build lokal pakai SDK, jangan `commit` secret.
- Ubah perilaku/build/workflow wajib catat di `CHANGELOG.md`.
- Detail aturan agen ada di `AGENTS.md`.

## Lisensi & Legal

Lisensi MIT, lihat `LICENSE`.

Khusus monitoring legal orang tua ke HP anak sendiri. Jangan buat mata-matain orang lain. Patuhi hukum setempat. Salah pakai tanggung jawab sendiri.
