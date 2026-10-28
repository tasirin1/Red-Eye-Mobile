# Kebijakan Keamanan

## Versi yang didukung

Hanya rilis terbaru yang menerima perbaikan keamanan. Rilis lama
dipertahankan sebagai riwayat, tetapi tidak di-patch.

| Versi | Didukung |
|---|---|
| Rilis terbaru (`releases/latest`) | Ya |
| Rilis lama | Tidak (perbarui dulu, lalu uji ulang) |

## Melaporkan kerentanan

Jangan buat issue publik untuk kerentanan keamanan. Gunakan fitur
**Private vulnerability reporting** di tab Security repository GitHub ini.
Sertakan:

- versi APK atau commit yang diuji;
- langkah reproduksi singkat;
- dampak yang mungkin terjadi;
- proof-of-concept bila tersedia.

Laporan akan ditinjau dan pemilik repository akan menindaklanjuti lewat
GitHub dalam waktu 7 hari kerja. Kerentanan kritis akan diprioritaskan.

## Cakupan

Termasuk namun tidak terbatas pada:

- kebocoran token bot Telegram atau chat ID dari aplikasi/penyimpanan
  terenkripsi (`PreferencesManager`);
- bypass gerbang owner pada perintah/teks/tombol bot;
- jalur pengiriman data perangkat (foto, lokasi, SMS, log) ke pihak selain
  chat owner yang terkonfigurasi;
- APK rilis yang lolos tanpa tanda tangan keystore resmi.

Di luar cakupan:

- serangan fisik setelah perangkat sudah tidak terkunci;
- laporan tanpa dampak nyata terhadap aplikasi atau datanya;
- hasil pemindaian otomatis tanpa analisis dan langkah reproduksi.

## Praktik keamanan maintainer

- **Token bot dan chat ID tidak pernah di-commit** — satu-satunya sumber
  adalah input manual di `SetupActivity`; kredensial tidak dibake ke APK
  dalam bentuk apa pun.
- Jangan pernah menyimpan keystore, password signing, atau personal
  access token di issue/PR/chat; simpan hanya di GitHub Actions Secrets
  atau password manager.
- Rilis resmi hanya dari workflow GitHub Actions (tag `vX.Y.Z`) dengan
  gerbang keystore yang menolak build rilis tanpa tanda tangan resmi.
