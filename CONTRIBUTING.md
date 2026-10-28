# Contributing — Red-Eye-Mobile

Terima kasih sudah mau berkontribusi! Panduan singkat ini menjaga repo tetap
rapi dan rilis selalu hijau. **Untuk AI yang mengelola repo: baca
[AGENTS.md](AGENTS.md) dulu — dokumen ini aturan ringkas untuk kontributor.**

## Alur kerja

1. **Build SELALU di GitHub Actions** — jangan install Android SDK / build
   APK lokal. Di lokal cukup edit kode, cek sintaks ringan, validasi XML.
2. **Setiap perubahan perilaku / file build / workflow wajib entri
   `CHANGELOG.md`** (Keep a Changelog, seksi `[Unreleased]`).
3. **Setiap perbaikan yang selesai langsung dirilis**: commit + push ke
   `main`, bump versi di `app/build.gradle`, pindahkan entri `[Unreleased]`
   ke seksi versi baru, commit, push tag `vX.Y.Z`. Pengecualian docs-only
   (`*.md`/`LICENSE`) cukup commit + push.
4. Perubahan minimal dan fokus — jangan refactor tak diminta, jangan
   perbaiki bug yang tidak terkait.

## Sebelum push

```bash
python3 tools/verifikasi-path.py   # verifikasi path & acuan
bash scripts/cek-cepat.sh          # cek cepat pra-push
```

Pasang kait pre-push sekali per clone: `sh tools/pasang-hook.sh`.

## Keamanan

- **Jangan pernah commit secret** (token bot, chat ID, keystore) — token
  hanya dari input manual `SetupActivity`. Lihat [SECURITY.md](SECURITY.md).

## PR Dependabot

- Update GitHub Actions di-auto-merge setelah CI hijau (workflow
  Auto-merge).
- Update dependensi Gradle **tidak** di-auto-merge — ditangani manual
  setelah build CI hijau.

## Label otomatis & issue/PR lama

- Workflow `Labeler` memberi label otomatis per path (`app`, `ci`, `docs` —
  lihat `.github/labeler.yml`).
- Workflow `Stale` menandai issue/PR tidak aktif (issue 45 hari, PR 21
  hari) dan menutupnya bila 14 hari tidak ada respons.
