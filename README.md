# Red Eye Mobile

Aplikasi monitoring HP anak via Telegram, tampilannya kalkulator biasa. Buat orang tua, bukan buat iseng.

> Build JANGAN lokal. Semua APK dibangun di GitHub Actions.
> `builder.sh` / `builder.bat` itu LEGACY, jangan dipakai lagi.

---

## Fitur

- Log panggilan masuk/keluar
- SMS masuk/keluar
- Foto otomatis tiap interval, atau manual via `/photo`
- Teruskan notifikasi HP anak ke Telegram
- Antrean offline, kirim lagi pas online
- Auto-jalan lagi habis reboot
- Proteksi uninstall opsional via Device Admin

## Ambil APK-nya

1. Buka tab Actions > Build APK > Run workflow. Atau `push` ke `main`, buka PR ke `main`, atau `push` tag `vX.Y.Z`.
2. Tiap run langsung jadi dua file: `redeye-debug.apk` dan `redeye-release.apk` di artifacts `apks`.
3. Tiap ada gangguan 429 dari Maven, workflow retry sendiri sampai 3x. Cuma satu run yang jalan dalam satu waktu, sisanya dibatalkan otomatis.
4. Buat file rilis resmi, `push` tag `vX.Y.Z`. Nanti muncul di halaman Releases sebagai `redeye-vX.Y.Z-debug.apk` dan `redeye-vX.Y.Z-release.apk`.

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
7. Tap Test Connection, pastikan pesan masuk ke Telegram.
8. Tap Grant permissions sampai semua hijau, lalu grant background location Allow all the time.
9. Nyalakan akses notifikasi kalau mau forward notifikasi.
10. Matikan batasan baterai biar nggak dimatikan Doze, nyalakan Device Admin kalau perlu.
11. Tap Enable monitoring, selesai. Cek via Send Status.

Catatan: kalkulatornya beneran bisa dipakai. Ketik angka setelah `=` mulai hitungan baru, maksimal 12 digit.

## Dipakai Harian

- Data baru masuk ke bot tiap interval yang kamu set.
- Foto ngikutin interval kamera, `0` berarti manual via `/photo` aja.
- Habis reboot jalan sendiri, nggak perlu dibuka manual.

## Perintah Telegram

Kirim dari chat owner yang Chat ID-nya disimpan di Setup:

| Perintah | Fungsi |
|----------|--------|
| `/photo` | Foto sekarang |
| `/camera depan\|belakang` | Ganti kamera |
| `/location` | Kirim lokasi terakhir |
| `/lastcalls` | 5 panggilan terakhir |
| `/lastsms` | 5 SMS terakhir |
| `/lastnotif` | Notifikasi terakhir |
| `/history nomor` | Gabungan call+SMS per nomor |
| `/photointerval 0-60` | Interval foto menit, `0` manual |
| `/pause menit` | Jeda foto sementara |
| `/syncinterval 1-1440` | Interval sync menit |
| `/status` | Status monitoring |
| `/battery` | Level baterai |
| `/uptime` | Lama service jalan |
| `/version` | Versi app + device |
| `/stop` / `/resume` | Jeda/lanjut monitoring |
| `/notif on\|off\|status` | Forward notifikasi |
| `/restart` | Restart loop monitoring |
| `/flush` | Kirim antrean sekarang |
| `/clearqueue` | Buang antrean |
| `/sms nomor pesan` + `/smsconfirm` | Kirim SMS via HP anak |
| `/record 5-60` | Rekam audio detik |
| `/lock` | Kunci layar |
| `/ring` | Bunyikan HP |
| `/contacts nama` | Cari kontak |
| `/apps` | Daftar aplikasi terinstal |
| `/storage` | Sisa penyimpanan |
| `/log` | Log error terakhir |
| `/ping` | Cek delay bot |
| `/help` | Semua perintah |

## Kalau Error

| Gejala | Obatnya |
|--------|---------|
| Play Protect blokir | Install anyway |
| App not installed | Uninstall versi lama dulu, beda signature |
| Sepi, nggak ada pesan | Test Connection ulang, cek token/Chat ID |
| Monitoring mati | Cek izin + matikan optimasi baterai |
| Lokasi gagal | Nyalakan Precise location + GPS + background location |
| Nggak bisa uninstall | Matikan Device Admin dulu di Settings |

## Isi Repo

```
app/src/main/java/com/redeye/parentalmonitor/
  data/         preferensi terenkripsi, antrean
  network/      client Telegram API
  receiver/     boot, network, admin
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
