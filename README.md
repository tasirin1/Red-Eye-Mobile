# 📱 Red Eye Mobile

<div align="center">

**Smart parental phone monitoring app with Telegram integration**

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Platform](https://img.shields.io/badge/platform-Android-green.svg)](https://www.android.com)
[![Kotlin](https://img.shields.io/badge/language-Kotlin-blue.svg)](https://kotlinlang.org)
[![Telegram](https://img.shields.io/badge/integration-Telegram-26A5E4.svg)](https://telegram.org)

**Developer: Akhatkulov**

</div>

> **Build policy: do NOT install the Android SDK locally. Builds ALWAYS run on GitHub Actions.**
> See `AGENTS.md` for repo rules and `CHANGELOG.md` for history.

---

### 📋 Description

**Red Eye Mobile** is an Android monitoring app for parents. It lets you follow your child's phone activity via Telegram. The app disguises itself as an elegant **Calculator** and runs in stealth mode.

### ✨ Key Features

- 📞 **Call monitoring** — full incoming/outgoing call history
- 💬 **SMS monitoring** — incoming and outgoing text messages
- 📸 **Camera capture** — automatic photo every 1 minute by default, configurable `0-60` minutes or manual only via `/photo`
- 🔔 **Notification forwarding** — notifications arriving on the child's phone are sent to Telegram (deduplicated, offline queue)
- 🔔 **Telegram integration** — everything is delivered to your Telegram bot
- 🧮 **Stealth mode** — the app looks like a calculator
- 🔒 **Device Admin** — optional protection against uninstallation
- 🔄 **Auto-start** — resumes automatically after reboot
- 🌐 **Offline mode** — messages are queued when there is no internet

### 🏗️ Technology Stack

```
🔹 Kotlin — main language
🔹 Retrofit — Telegram API client
🔹 Coroutines — async operations
🔹 WorkManager — background jobs
🔹 Camera2 API — photo capture
🔹 Foreground Service — persistent monitoring
🔹 Device Admin API — uninstall protection
```

### 📦 Requirements

- **Android OS**: 7.0 (API 24) or higher
- **RAM**: 2 GB minimum
- **Storage**: 50 MB free
- **Permissions**: SMS, Call Log, Contacts, Camera, Microphone, Notifications, Location foreground + Allow all the time

---

### 🚀 Getting the APK (GitHub Actions)

1. Push to `main`, open a PR, push a `vX.Y.Z` tag, or run the workflow manually (**Actions > Build APK > Run workflow**). Every run builds both APKs at once.
2. Open the finished run and download the artifact: `apks` (contains `redeye-debug.apk`, `redeye-release.apk`).
3. For an official installable file, use the **Releases** page — each `vX.Y.Z` tag publishes signed APKs (`redeye-vX.Y.Z-debug.apk`, `redeye-vX.Y.Z-release.apk`).

Optional repository secrets (`Settings > Secrets and variables > Actions`):

| Secret | Purpose |
|--------|---------|
| `SYNC_INTERVAL` | Sync interval in minutes (default `5`) |
| `ANDROID_KEYSTORE_BASE64` | Release keystore for signed APKs |
| `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` | Keystore credentials |

Without secrets the build still succeeds — the bot is configured manually inside the app (recommended).

### 🤖 Creating a Telegram Bot

1. Open [@BotFather](https://t.me/BotFather) on Telegram and send `/newbot`.
2. Follow the steps and save the **Bot Token** (format: `<YOUR_BOT_TOKEN>`).
3. Get your numeric Telegram ID from [@userinfobot](https://t.me/userinfobot) — this is the **Chat ID**.

### 📲 Installing & Setting Up

1. Copy the APK to the phone and open it from a file manager.
2. Allow **Unknown sources** / **Install unknown apps** when asked.
3. If Play Protect warns you, tap **Details > Install anyway** (expected for this permission set — see Troubleshooting).
4. Open the **Calculator** app.
5. Type `1234` then press `=` — the **Telegram Bot Setup** page opens.
6. Enter **Bot Token**, **Chat ID**, and sync interval, then tap **Save settings**.
7. Tap **Test Connection** and confirm the message arrives in Telegram.
8. Tap **Grant all permissions** (SMS, Call Log, Contacts, Camera, Microphone, Notifications, Location), then grant background location via **Grant background location (Allow all the time)**.
9. Tap **Read Notifications** and allow notification access so incoming notifications on the child's phone are forwarded to Telegram (tap again to pause/resume forwarding).
10. Optionally tap **Disable Battery Restriction** so monitoring survives Doze, and **Enable Protection** (Device Admin).
11. Tap **Enable monitoring** — done. Use **Send Status** to verify delivery to Telegram.

> The release APK is signed, so it installs normally. If you previously installed a version signed with a different key, uninstall it first — Android rejects updates with mismatched signatures.

### 📱 Usage

After setup:

1. ✅ Monitoring starts automatically
2. 📊 New data is sent every interval you configured
3. 📸 Photos follow the camera interval you set (`0` means manual only via `/photo`)
4. 📱 Everything arrives in your Telegram bot
5. 🔄 Monitoring resumes automatically after reboot

### 🎯 What You Receive in Telegram

```
📞 CALL
Name: John Doe
Number: +998901234567
Type: Incoming
Duration: 02:35
Date: 19.10.2025 14:30

💬 SMS
From: +998901234567
Message: Hello, how are you?
Date: 19.10.2025 14:28

📸 PHOTO
19.10.2025 14:32
[JPEG image file]
```

### 🛡️ Security

- ✅ The app only sends data to the Telegram bot you configured
- ✅ Bot token is never baked into the APK, it is entered manually in Setup and stored encrypted on device
- ✅ No data is stored anywhere else
- ✅ Optional Device Admin protection prevents easy removal

### 🔧 Troubleshooting

| Issue | Solution |
|-------|----------|
| Play Protect blocks install | Details > Install anyway, or temporarily disable Play Protect scanning |
| "App not installed" | Uninstall previous version first (signature mismatch) |
| No messages in Telegram | Check token/Chat ID via Test Connection on the setup page |
| Monitoring doesn't start | Grant all permissions, disable battery optimization |
| `/location` says permission missing | Tap **Grant background location (Allow all the time)** in Setup, enable Precise location + GPS |
| Camera not working | On Android 10+, camera capture needs the app in foreground |
| Can't uninstall | Settings → Security → Device Admin → deactivate first |

### 📂 Project Structure

```
red-eye-mobile/
├── app/
│   ├── src/main/
│   │   ├── java/com/redeye/parentalmonitor/
│   │   │   ├── data/              # Models, encrypted preferences
│   │   │   ├── network/           # Telegram API client
│   │   │   ├── receiver/          # Boot / network / admin receivers
│   │   │   ├── repository/        # SMS & call-log repositories
│   │   │   ├── service/           # Monitoring & camera services
│   │   │   ├── ui/                # MainActivity (calculator), SetupActivity
│   │   │   ├── utils/             # Helpers
│   │   │   └── worker/            # WorkManager tasks
│   │   ├── res/                   # Layouts, drawables, strings (English)
│   │   └── AndroidManifest.xml
│   └── build.gradle
├── .github/workflows/
│   └── build.yml                  # Cloud build (debug + release artifacts, Release on v* tags)
├── AGENTS.md                      # Repo rules (cloud build only)
├── CHANGELOG.md                   # Release history
└── builder.sh / builder.bat       # LEGACY, do not use (kept for compatibility)
```

### 🤝 Contributing

Pull requests are welcome! For major changes, please open an issue first. Remember: no local SDK builds, no secrets in code — see `AGENTS.md`.

### 📄 License

This project is licensed under the [MIT License](LICENSE).

### ⚖️ Legal Disclaimer

⚠️ **IMPORTANT**: this software is intended for legal parental monitoring only.

- ✅ Use only on your own child's phone
- ✅ Comply with local laws
- ❌ Do not use to spy on other people
- ❌ Invasion of privacy is illegal

**The developer is not responsible for misuse.**

### 📞 Contact

- **Developer**: Akhatkulov
- **GitHub**: [github.com/akhatkulov](https://github.com/akhatkulov)
- **Telegram**: [@akhatkulov](https://t.me/akhatkulov)

### 🎉 Acknowledgments

- [Telegram Bot API](https://core.telegram.org/bots/api) — messaging integration
- [Square Retrofit](https://square.github.io/retrofit/) — HTTP client
- [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html) — async programming
- [Android Jetpack](https://developer.android.com/jetpack) — modern Android development

---

<div align="center">

**Made with ❤️ by Akhatkulov**

</div>
