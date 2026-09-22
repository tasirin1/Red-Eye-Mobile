@echo off
echo [LEGACY] Skrip lokal ini dipertahankan untuk kompatibilitas saja.
echo [LEGACY] Cara resmi: build via GitHub Actions, lihat AGENTS.md dan README.md.
REM ═══════════════════════════════════════════════════════════════════════════
REM  ██████╗ ███████╗██████╗     ███████╗██╗   ██╗███████╗
REM  ██╔══██╗██╔════╝██╔══██╗    ██╔════╝╚██╗ ██╔╝██╔════╝
REM  ██████╔╝█████╗  ██║  ██║    █████╗   ╚████╔╝ █████╗  
REM  ██╔══██╗██╔══╝  ██║  ██║    ██╔══╝    ╚██╔╝  ██╔══╝  
REM  ██║  ██║███████╗██████╔╝    ███████╗   ██║   ███████╗
REM  ╚═╝  ╚═╝╚══════╝╚═════╝     ╚══════╝   ╚═╝   ╚══════╝
REM                                                         
REM  Red Eye Mobile - APK Builder Script (Windows)
REM  Automated build system for parental monitoring app
REM
REM  Developer: Akhatkulov
REM  Version: 1.0.0
REM  License: MIT
REM  
REM  📱 Build, Sign, Deploy — All in One Script
REM ═══════════════════════════════════════════════════════════════════════════

cls
echo.
echo ══════════════════════════════════════════════════════════════
echo                    RED EYE MOBILE BUILDER                     
echo ══════════════════════════════════════════════════════════════
echo   Automated APK Build System
echo   Developer: Akhatkulov
echo   Version: 1.0.0
echo ══════════════════════════════════════════════════════════════
echo.

REM ═══════════════════════════════════════════════════════════════════════════
REM  CONFIGURATION SECTION
REM ═══════════════════════════════════════════════════════════════════════════

REM Get Telegram Bot Token
echo [1/3] Telegram Bot Token ni kiriting:
echo    (Masalan: 123456789:ABCdefGHIjklMNOpqrsTUVwxyz)
set /p BOT_TOKEN="   Token: "

if "%BOT_TOKEN%"=="" (
    echo [✗] XATO: Bot Token kiritilmadi!
    pause
    exit /b 1
)

REM Get Telegram Chat ID
echo.
echo [2/3] Telegram Chat ID ni kiriting:
echo    (Masalan: 123456789)
set /p CHAT_ID="   Chat ID: "

if "%CHAT_ID%"=="" (
    echo [✗] XATO: Chat ID kiritilmadi!
    pause
    exit /b 1
)

REM Get Sync Interval
echo.
echo [3/3] Tekshirish davriyligi (daqiqalarda) [default: 60]:
set /p SYNC_INTERVAL="   Davriyligi: "
if "%SYNC_INTERVAL%"=="" set SYNC_INTERVAL=60

REM Confirm settings
echo.
echo ═══ Kiritilgan sozlamalar ═══
echo   Bot Token: %BOT_TOKEN:~0,20%...
echo   Chat ID: %CHAT_ID%
echo   Davriyligi: %SYNC_INTERVAL% daqiqa
echo ═════════════════════════════
echo.
set /p CONFIRM="Davom etasizmi? (y/n): "

if /i not "%CONFIRM%"=="y" (
    echo [✗] Bekor qilindi.
    pause
    exit /b 0
)

REM ═══════════════════════════════════════════════════════════════════════════
REM  BUILD CONFIGURATION SECTION
REM ═══════════════════════════════════════════════════════════════════════════

echo.
echo ══════════════════════════════════════════════════════════════
echo                     ⚙️  BUILD PROCESS ⚙️                      
echo ══════════════════════════════════════════════════════════════
echo.
echo [ℹ] Sozlamalar o'rnatilmoqda...

REM Backup original build.gradle
copy app\build.gradle app\build.gradle.backup >nul

REM Create AutoConfig class
echo [ℹ] AutoConfig klassi yaratilmoqda...

mkdir app\src\main\java\com\redeye\parentalmonitor\utils 2>nul

(
echo package com.redeye.parentalmonitor.utils
echo.
echo import android.content.Context
echo import com.redeye.parentalmonitor.BuildConfig
echo import com.redeye.parentalmonitor.data.PreferencesManager
echo.
echo /**
echo  * AutoConfig - Avtomatik konfiguratsiya yordamchisi
echo  * Developer: Akhatkulov
echo  * 
echo  * Build vaqtida kiritilgan sozlamalarni avtomatik qo'llaydi
echo  */
echo object AutoConfig {
echo.
echo     fun applyIfNeeded^(context: Context^) {
echo         val prefs = PreferencesManager^(context^)
echo.
echo         // Agar sozlamalar bo'sh bo'lsa, build config dan olish
echo         if ^(prefs.botToken.isEmpty^(^) ^&^& BuildConfig.BOT_TOKEN.isNotEmpty^(^)^) {
echo             prefs.botToken = BuildConfig.BOT_TOKEN
echo         }
echo.
echo         if ^(prefs.chatId.isEmpty^(^) ^&^& BuildConfig.CHAT_ID.isNotEmpty^(^)^) {
echo             prefs.chatId = BuildConfig.CHAT_ID
echo         }
echo.
echo         if ^(prefs.syncInterval == 60 ^&^& BuildConfig.SYNC_INTERVAL ^> 0^) {
echo             prefs.syncInterval = BuildConfig.SYNC_INTERVAL
echo         }
echo     }
echo.
echo     fun isPreConfigured^(^): Boolean {
echo         return BuildConfig.BOT_TOKEN.isNotEmpty^(^) ^&^& BuildConfig.CHAT_ID.isNotEmpty^(^)
echo     }
echo }
) > app\src\main\java\com\redeye\parentalmonitor\utils\AutoConfig.kt

echo [✓] AutoConfig klassi tayyor

REM Update build.gradle with BuildConfig
echo [ℹ] build.gradle yangilanmoqda...

REM Create temp file with new defaultConfig
(
echo.
echo     defaultConfig {
echo         applicationId "com.redeye.parentalmonitor"
echo         minSdk 24
echo         targetSdk 34
echo         versionCode 1
echo         versionName "1.0"
echo.
echo         testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"
echo.
echo         // Pre-configured Telegram settings
echo         buildConfigField "String", "BOT_TOKEN", "\"%BOT_TOKEN%\""
echo         buildConfigField "String", "CHAT_ID", "\"%CHAT_ID%\""
echo         buildConfigField "int", "SYNC_INTERVAL", "%SYNC_INTERVAL%"
echo     }
) > temp_config.txt

REM Create new build.gradle with updated config
powershell -Command "(Get-Content app\build.gradle) -replace '(?s)defaultConfig \{.*?\n    \}', (Get-Content temp_config.txt -Raw) | Set-Content app\build.gradle.new"
move /y app\build.gradle.new app\build.gradle >nul
del temp_config.txt

echo [✓] Build konfiguratsiya tayyor
echo.

REM ═══════════════════════════════════════════════════════════════════════════
REM  GRADLE BUILD SECTION
REM ═══════════════════════════════════════════════════════════════════════════

echo [🚀] Gradle build boshlandi...
echo.

REM Clean previous builds
echo » Oldingi buildlarni tozalash...
call gradlew.bat clean >nul 2>&1

REM Build debug APK
echo.
echo [1/2] Debug APK build qilinmoqda...
call gradlew.bat assembleDebug

if %ERRORLEVEL% neq 0 (
    echo [✗] Debug APK build xatosi!
    move /y app\build.gradle.backup app\build.gradle >nul
    pause
    exit /b 1
)

echo [✓] Debug APK tayyor!

REM Build release APK
echo.
echo [2/2] Release APK build qilinmoqda...
call gradlew.bat assembleRelease

if %ERRORLEVEL% neq 0 (
    echo [✗] Release APK build xatosi!
    move /y app\build.gradle.backup app\build.gradle >nul
    pause
    exit /b 1
)

echo [✓] Release APK tayyor!
echo.

REM ═══════════════════════════════════════════════════════════════════════════
REM  OUTPUT SECTION
REM ═══════════════════════════════════════════════════════════════════════════

REM Create output directory
if not exist "output" mkdir output

REM Copy APKs
copy app\build\outputs\apk\debug\app-debug.apk output\parental-monitor-debug.apk >nul 2>&1
copy app\build\outputs\apk\release\app-release-unsigned.apk output\parental-monitor-release.apk >nul 2>&1

echo [ℹ] Hujjatlar yaratilmoqda...

REM Create info file
(
echo ═══════════════════════════════════════════════════════════════════════════
echo   RED EYE MOBILE - Build Ma'lumotlari
echo   Developer: Akhatkulov
echo ═══════════════════════════════════════════════════════════════════════════
echo.
echo Build vaqti: %date% %time%
echo.
echo Sozlamalar:
echo -----------
echo Bot Token: %BOT_TOKEN:~0,20%...
echo Chat ID: %CHAT_ID%
echo Davriyligi: %SYNC_INTERVAL% daqiqa
echo.
echo Fayllar:
echo --------
echo 📱 parental-monitor-debug.apk   - Debug versiya (test uchun^)
echo 📱 parental-monitor-release.apk - Release versiya (IMZOLANGAN^)
echo.
echo Eslatma:
echo --------
echo ✓ Bu APK larda Telegram sozlamalari avvaldan o'rnatilgan
echo ✓ Faqat o'rnatish va ruxsatlar berish kerak
echo ✓ Device Admin avtomatik faollashtiriladi
echo ✓ Monitoring avtomatik ishga tushadi
echo.
echo O'rnatish:
echo ----------
echo 1. APK ni telefonga nusxalash
echo 2. Fayl menejerdan ochish
echo 3. "O'rnatish" tugmasini bosish
echo 4. Kalkulyatorda 1234= ni bosish (maxfiy kod^)
echo 5. Device Admin ruxsatini tasdiqlash
echo 6. Barcha ruxsatlarni berish
echo.
echo Xavfsizlik:
echo -----------
echo ⚠️  DIQQAT: Bu APK da sizning bot tokeningiz mavjud!
echo ⚠️  Bu APK ni boshqa odamlarga bermang!
echo ⚠️  Faqat o'z farzandingiz telefoniga o'rnating!
echo 🔒 Keystore'ni saqlang va zaxira nusxasini oling!
echo.
echo Qonuniylik:
echo -----------
echo ✓ Faqat voyaga yetmagan farzandingiz uchun foydalaning
echo ✓ Mahalliy qonunlarga rioya qiling
echo ✗ Katta yoshdagilarga o'rnatmang
echo.
echo ═══════════════════════════════════════════════════════════════════════════
echo Developer: Akhatkulov
echo License: MIT
echo Version: 1.0.0
echo ═══════════════════════════════════════════════════════════════════════════
) > output\INFO.txt

echo [✓] Hujjatlar tayyor

REM ═══════════════════════════════════════════════════════════════════════════
REM  RESULTS SECTION
REM ═══════════════════════════════════════════════════════════════════════════

echo.
echo ══════════════════════════════════════════════════════════════
echo               🚀 BUILD MUVAFFAQIYATLI TUGADI! ✓               
echo ══════════════════════════════════════════════════════════════
echo.
echo 📁 APK Fayllar Joyi:
echo   output\
echo     📱 parental-monitor-debug.apk
echo     📱 parental-monitor-release.apk
echo     📄 INFO.txt
echo.
echo ⚙️  Keyingi Qadamlar:
echo   1. APK ni telefonga nusxalang
echo   2. Fayl menejerdan ochib o'rnating
echo   3. Kalkulyatorda 1234= ni kiriting
echo   4. Barcha ruxsatlarni bering
echo   5. Monitoring avtomatik boshlanadi
echo.
echo ⚠️  MUHIM XAVFSIZLIK OGOHLANTIRISHI:
echo   • Bu APK da sizning bot tokeningiz mavjud!
echo   • APK ni boshqalarga BERMANG!
echo   • Faqat o'z farzandingiz telefoniga o'rnating!
echo.
echo [✓] Barcha jarayonlar muvaffaqiyatli yakunlandi!
echo.

REM Ask to restore original build.gradle
set /p RESTORE="Original build.gradle ni qaytarish kerakmi? (y/n): "

if /i "%RESTORE%"=="y" (
    move /y app\build.gradle.backup app\build.gradle >nul
    echo [✓] Original build.gradle qaytarildi
) else (
    del app\build.gradle.backup >nul 2>&1
    echo [ℹ] Yangi build.gradle saqlandi (token'lar bilan^)
)

echo.
echo ══════════════════════════════════════════════════════════════
echo   Developer: Akhatkulov ^| Red Eye Mobile v1.0.0 ^| MIT License
echo ══════════════════════════════════════════════════════════════
echo.
pause
