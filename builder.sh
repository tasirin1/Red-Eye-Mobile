#!/bin/bash

# ═══════════════════════════════════════════════════════════════════════════
#  ██████╗ ███████╗██████╗     ███████╗██╗   ██╗███████╗
#  ██╔══██╗██╔════╝██╔══██╗    ██╔════╝╚██╗ ██╔╝██╔════╝
#  ██████╔╝█████╗  ██║  ██║    █████╗   ╚████╔╝ █████╗  
#  ██╔══██╗██╔══╝  ██║  ██║    ██╔══╝    ╚██╔╝  ██╔══╝  
#  ██║  ██║███████╗██████╔╝    ███████╗   ██║   ███████╗
#  ╚═╝  ╚═╝╚══════╝╚═════╝     ╚══════╝   ╚═╝   ╚══════╝
#                                                         
#  Red Eye Mobile - APK Builder Script
#  Automated build system for parental monitoring app
#
#  Developer: Akhatkulov
#  Version: 1.0.0
#  License: MIT
#  
#  📱 Build, Sign, Deploy — All in One Script
# ═══════════════════════════════════════════════════════════════════════════

# Colors and Styling
BOLD='\033[1m'
DIM='\033[2m'
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
MAGENTA='\033[0;35m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

# Emojis
ROCKET="🚀"
CHECK="✓"
CROSS="✗"
WARN="⚠️"
INFO="ℹ"
LOCK="🔒"
PHONE="📱"
FOLDER="📁"
FILE="📄"
GEAR="⚙️"

# Configuration
CONFIG_FILE=".builder_config"
OUTPUT_DIR="output"

# Header
clear
echo "LEGACY: builder.sh sudah tidak dipakai. Build hanya via GitHub Actions (.github/workflows/build.yml)." >&2
echo ""
echo -e "${CYAN}${BOLD}══════════════════════════════════════════════════════════════${NC}"
echo -e "${CYAN}${BOLD}                   RED EYE MOBILE BUILDER                     ${NC}"
echo -e "${CYAN}${BOLD}══════════════════════════════════════════════════════════════${NC}"
echo -e "  ${DIM}Automated APK Build System${NC}"
echo -e "  ${DIM}Developer: Akhatkulov${NC}"
echo -e "  ${DIM}Version: 1.0.0${NC}"
echo -e "${CYAN}${BOLD}══════════════════════════════════════════════════════════════${NC}"
echo ""

# ═══════════════════════════════════════════════════════════════════════════
#  CONFIGURATION SECTION
# ═══════════════════════════════════════════════════════════════════════════

# Check if arguments were provided
if [ $# -eq 3 ]; then
    # Use command line arguments
    BOT_TOKEN="$1"
    CHAT_ID="$2"
    SYNC_INTERVAL="$3"
    
    # Save to config file
    echo "BOT_TOKEN=$BOT_TOKEN" > "$CONFIG_FILE"
    echo "CHAT_ID=$CHAT_ID" >> "$CONFIG_FILE"
    echo "SYNC_INTERVAL=$SYNC_INTERVAL" >> "$CONFIG_FILE"
    
    echo -e "${GREEN}${CHECK} Ma'lumotlar saqlandi${NC}"
    echo ""
    
elif [ -f "$CONFIG_FILE" ]; then
    # Load from config file
    echo -e "${CYAN}${INFO} Saqlangan sozlamalar topildi...${NC}"
    source "$CONFIG_FILE"
    
    echo ""
    echo -e "${MAGENTA}${BOLD}═══ Saqlangan sozlamalar ═══${NC}"
    echo -e "  ${CYAN}Bot Token:${NC} ${BOT_TOKEN:0:20}..."
    echo -e "  ${CYAN}Chat ID:${NC} $CHAT_ID"
    echo -e "  ${CYAN}Davriyligi:${NC} $SYNC_INTERVAL daqiqa"
    echo -e "${MAGENTA}${BOLD}═════════════════════════════${NC}"
    echo ""
    echo -e "${YELLOW}Ushbu sozlamalarni ishlatishni davom ettirasizmi? (y/n):${NC}"
    read -r USE_SAVED
    
    if [ "$USE_SAVED" != "y" ] && [ "$USE_SAVED" != "Y" ]; then
        rm -f "$CONFIG_FILE"
        echo -e "${YELLOW}${INFO} Yangi sozlamalarni kiriting...${NC}"
        echo ""
        
        # Get new values
        echo -e "${CYAN}${BOLD}1. ${NC}${YELLOW}Telegram Bot Token ni kiriting:${NC}"
        echo -e "   ${DIM}(Masalan: 123456789:ABCdefGHIjklMNOpqrsTUVwxyz)${NC}"
        read -r BOT_TOKEN
        
        if [ -z "$BOT_TOKEN" ]; then
            echo -e "${RED}${CROSS} Xato: Bot Token kiritilmadi!${NC}"
            exit 1
        fi
        
        echo ""
        echo -e "${CYAN}${BOLD}2. ${NC}${YELLOW}Telegram Chat ID ni kiriting:${NC}"
        echo -e "   ${DIM}(Masalan: 123456789)${NC}"
        read -r CHAT_ID
        
        if [ -z "$CHAT_ID" ]; then
            echo -e "${RED}${CROSS} Xato: Chat ID kiritilmadi!${NC}"
            exit 1
        fi
        
        echo ""
        echo -e "${CYAN}${BOLD}3. ${NC}${YELLOW}Tekshirish davriyligi (daqiqalarda) [default: 60]:${NC}"
        read -r SYNC_INTERVAL
        SYNC_INTERVAL=${SYNC_INTERVAL:-60}
        
        # Save new config
        echo "BOT_TOKEN=$BOT_TOKEN" > "$CONFIG_FILE"
        echo "CHAT_ID=$CHAT_ID" >> "$CONFIG_FILE"
        echo "SYNC_INTERVAL=$SYNC_INTERVAL" >> "$CONFIG_FILE"
        
        echo -e "${GREEN}${CHECK} Yangi sozlamalar saqlandi${NC}"
    fi
else
    # Interactive mode - no args and no saved config
    echo -e "${CYAN}${BOLD}1. ${NC}${YELLOW}Telegram Bot Token ni kiriting:${NC}"
    echo -e "   ${DIM}(Masalan: 123456789:ABCdefGHIjklMNOpqrsTUVwxyz)${NC}"
    read -r BOT_TOKEN
    
    if [ -z "$BOT_TOKEN" ]; then
        echo -e "${RED}${CROSS} Xato: Bot Token kiritilmadi!${NC}"
        exit 1
    fi
    
    echo ""
    echo -e "${CYAN}${BOLD}2. ${NC}${YELLOW}Telegram Chat ID ni kiriting:${NC}"
    echo -e "   ${DIM}(Masalan: 123456789)${NC}"
    read -r CHAT_ID
    
    if [ -z "$CHAT_ID" ]; then
        echo -e "${RED}${CROSS} Xato: Chat ID kiritilmadi!${NC}"
        exit 1
    fi
    
    echo ""
    echo -e "${CYAN}${BOLD}3. ${NC}${YELLOW}Tekshirish davriyligi (daqiqalarda) [default: 60]:${NC}"
    read -r SYNC_INTERVAL
    SYNC_INTERVAL=${SYNC_INTERVAL:-60}
    
    # Save config
    echo "BOT_TOKEN=$BOT_TOKEN" > "$CONFIG_FILE"
    echo "CHAT_ID=$CHAT_ID" >> "$CONFIG_FILE"
    echo "SYNC_INTERVAL=$SYNC_INTERVAL" >> "$CONFIG_FILE"
    
    echo -e "${GREEN}${CHECK} Sozlamalar saqlandi (keyingi safar avtomatik ishlatiladi)${NC}"
fi

# Confirm settings (skip if called with arguments)
if [ $# -ne 3 ]; then
    echo ""
    echo -e "${MAGENTA}${BOLD}═══ Kiritilgan sozlamalar ═══${NC}"
    echo -e "  ${CYAN}Bot Token:${NC} ${BOT_TOKEN:0:20}..."
    echo -e "  ${CYAN}Chat ID:${NC} $CHAT_ID"
    echo -e "  ${CYAN}Davriyligi:${NC} $SYNC_INTERVAL daqiqa"
    echo -e "${MAGENTA}${BOLD}═════════════════════════════${NC}"
    echo ""
    echo -e "${YELLOW}Davom etasizmi? (y/n):${NC}"
    read -r CONFIRM

    if [ "$CONFIRM" != "y" ] && [ "$CONFIRM" != "Y" ]; then
        echo -e "${RED}${CROSS} Bekor qilindi.${NC}"
        exit 0
    fi
else
    echo ""
    echo -e "${MAGENTA}${BOLD}═══ Kiritilgan sozlamalar ═══${NC}"
    echo -e "  ${CYAN}Bot Token:${NC} ${BOT_TOKEN:0:20}..."
    echo -e "  ${CYAN}Chat ID:${NC} $CHAT_ID"
    echo -e "  ${CYAN}Davriyligi:${NC} $SYNC_INTERVAL daqiqa"
    echo -e "${MAGENTA}${BOLD}═════════════════════════════${NC}"
fi

# ═══════════════════════════════════════════════════════════════════════════
#  BUILD CONFIGURATION SECTION
# ═══════════════════════════════════════════════════════════════════════════

echo ""
echo -e "${CYAN}${BOLD}══════════════════════════════════════════════════════════════${NC}"
echo -e "${CYAN}${BOLD}                    ${GEAR} BUILD PROCESS ${GEAR}                     ${NC}"
echo -e "${CYAN}${BOLD}══════════════════════════════════════════════════════════════${NC}"
echo ""
echo -e "${YELLOW}${INFO} Sozlamalar o'rnatilmoqda...${NC}"

# Backup original build.gradle
cp app/build.gradle app/build.gradle.backup

# Add buildConfigField to build.gradle
cat > temp_build_config.txt << EOF

    defaultConfig {
        applicationId "com.redeye.parentalmonitor"
        minSdk 24
        targetSdk 34
        versionCode 1
        versionName "1.0"

        testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"
        
        // Pre-configured Telegram settings
        buildConfigField "String", "BOT_TOKEN", "\\"$BOT_TOKEN\\""
        buildConfigField "String", "CHAT_ID", "\\"$CHAT_ID\\""
        buildConfigField "int", "SYNC_INTERVAL", "$SYNC_INTERVAL"
    }
EOF

# Replace defaultConfig section in build.gradle
sed -i.tmp '/defaultConfig {/,/}/d' app/build.gradle
sed -i.tmp '/namespace/r temp_build_config.txt' app/build.gradle
rm -f temp_build_config.txt app/build.gradle.tmp

echo -e "${GREEN}${CHECK} Build konfiguratsiya tayyor${NC}"

# Create auto-configure helper class
echo -e "${YELLOW}${INFO} AutoConfig klassi yaratilmoqda...${NC}"

cat > app/src/main/java/com/redeye/parentalmonitor/utils/AutoConfig.kt << 'EOF'
package com.redeye.parentalmonitor.utils

import android.content.Context
import com.redeye.parentalmonitor.BuildConfig
import com.redeye.parentalmonitor.data.PreferencesManager

/**
 * AutoConfig - Avtomatik konfiguratsiya yordamchisi
 * Developer: Akhatkulov
 * 
 * Build vaqtida kiritilgan sozlamalarni avtomatik qo'llaydi
 */
object AutoConfig {
    
    fun applyIfNeeded(context: Context) {
        val prefs = PreferencesManager(context)
        
        // Agar sozlamalar bo'sh bo'lsa, build config dan olish
        if (prefs.botToken.isEmpty() && BuildConfig.BOT_TOKEN.isNotEmpty()) {
            prefs.botToken = BuildConfig.BOT_TOKEN
        }
        
        if (prefs.chatId.isEmpty() && BuildConfig.CHAT_ID.isNotEmpty()) {
            prefs.chatId = BuildConfig.CHAT_ID
        }
        
        if (prefs.syncInterval == 60 && BuildConfig.SYNC_INTERVAL > 0) {
            prefs.syncInterval = BuildConfig.SYNC_INTERVAL
        }
    }
    
    fun isPreConfigured(): Boolean {
        return BuildConfig.BOT_TOKEN.isNotEmpty() && BuildConfig.CHAT_ID.isNotEmpty()
    }
}
EOF

echo -e "${GREEN}${CHECK} AutoConfig klassi tayyor${NC}"
echo ""

# ═══════════════════════════════════════════════════════════════════════════
#  GRADLE BUILD SECTION
# ═══════════════════════════════════════════════════════════════════════════

echo -e "${YELLOW}${WARN} LEGACY: skrip lokal ini dipertahankan untuk kompatibilitas saja.${NC}"
echo -e "${YELLOW}${WARN} Cara resmi: build via GitHub Actions (push/PR -> tab Actions -> Artifacts).${NC}"
echo -e "${YELLOW}${WARN} Lihat AGENTS.md dan README.md.${NC}"
echo ""

echo -e "${YELLOW}${BOLD}${ROCKET} Gradle build boshlandi...${NC}"
echo ""

# Clean previous builds
echo -e "${DIM}» Oldingi buildlarni tozalash...${NC}"
./gradlew clean > /dev/null 2>&1

# Build debug APK
echo -e "${CYAN}${BOLD}[1/2] Debug APK build qilinmoqda...${NC}"
./gradlew assembleDebug

if [ $? -eq 0 ]; then
    echo -e "${GREEN}${CHECK} Debug APK tayyor!${NC}"
    echo ""
else
    echo -e "${RED}${CROSS} Debug APK build xatosi!${NC}"
    # Restore backup
    mv app/build.gradle.backup app/build.gradle
    exit 1
fi

# Build release APK
echo -e "${CYAN}${BOLD}[2/2] Release APK build qilinmoqda...${NC}"
./gradlew assembleRelease

if [ $? -eq 0 ]; then
    echo -e "${GREEN}${CHECK} Release APK tayyor!${NC}"
    echo ""
else
    echo -e "${RED}${CROSS} Release APK build xatosi!${NC}"
    # Restore backup
    mv app/build.gradle.backup app/build.gradle
    exit 1
fi

# ═══════════════════════════════════════════════════════════════════════════
#  SIGNING SECTION
# ═══════════════════════════════════════════════════════════════════════════

echo -e "${CYAN}${BOLD}══════════════════════════════════════════════════════════════${NC}"
echo -e "${CYAN}${BOLD}                    ${LOCK} APK SIGNING ${LOCK}                       ${NC}"
echo -e "${CYAN}${BOLD}══════════════════════════════════════════════════════════════${NC}"
echo ""

# Create output directory
mkdir -p $OUTPUT_DIR

# Copy Debug APK to output directory
cp app/build/outputs/apk/debug/app-debug.apk "$OUTPUT_DIR/parental-monitor-debug.apk" 2>/dev/null

echo -e "${YELLOW}${INFO} Release APK imzolanmoqda...${NC}"

# Check if keystore exists, if not create it
if [ ! -f "release-key.keystore" ]; then
    echo -e "${YELLOW}${INFO} Keystore yaratilmoqda...${NC}"
    keytool -genkey -v -keystore release-key.keystore \
        -alias release -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass android123 -keypass android123 \
        -dname "CN=RedEyeMobile, OU=Development, O=Akhatkulov, L=Tashkent, S=Tashkent, C=UZ" 2>/dev/null
    echo -e "${GREEN}${CHECK} Keystore yaratildi${NC}"
fi

# Find Android SDK
if [ -z "$ANDROID_HOME" ]; then
    if [ -d "/home/me262/android-sdk" ]; then
        ANDROID_HOME="/home/me262/android-sdk"
    elif [ -d "/usr/lib/android-sdk" ]; then
        ANDROID_HOME="/usr/lib/android-sdk"
    else
        echo -e "${RED}${CROSS} Android SDK topilmadi!${NC}"
        exit 1
    fi
fi

# Find build-tools
BUILD_TOOLS_DIR=$(ls -d $ANDROID_HOME/build-tools/* 2>/dev/null | tail -1)

if [ -z "$BUILD_TOOLS_DIR" ]; then
    echo -e "${RED}${CROSS} Android build-tools topilmadi!${NC}"
    exit 1
fi

# Align APK
$BUILD_TOOLS_DIR/zipalign -v -p 4 \
    app/build/outputs/apk/release/app-release-unsigned.apk \
    "$OUTPUT_DIR/parental-monitor-release-aligned.apk" > /dev/null 2>&1

# Sign APK
$BUILD_TOOLS_DIR/apksigner sign \
    --ks release-key.keystore \
    --ks-key-alias release \
    --ks-pass pass:android123 \
    --key-pass pass:android123 \
    --out "$OUTPUT_DIR/parental-monitor-release.apk" \
    "$OUTPUT_DIR/parental-monitor-release-aligned.apk" 2>/dev/null

# Clean up temporary files
rm -f "$OUTPUT_DIR/parental-monitor-release-aligned.apk"
rm -f "$OUTPUT_DIR/parental-monitor-release.apk.idsig"

# Verify signature
if $BUILD_TOOLS_DIR/apksigner verify "$OUTPUT_DIR/parental-monitor-release.apk" > /dev/null 2>&1; then
    echo -e "${GREEN}${CHECK} Release APK imzolandi va tasdiqlandi!${NC}"
else
    echo -e "${RED}${CROSS} Imzolashda xatolik!${NC}"
    exit 1
fi

# ═══════════════════════════════════════════════════════════════════════════
#  DOCUMENTATION SECTION
# ═══════════════════════════════════════════════════════════════════════════

echo ""
echo -e "${YELLOW}${INFO} Hujjatlar yaratilmoqda...${NC}"

# Create info file
cat > "$OUTPUT_DIR/INFO.txt" << EOF
═══════════════════════════════════════════════════════════════════════════
  RED EYE MOBILE - Build Ma'lumotlari
  Developer: Akhatkulov
═══════════════════════════════════════════════════════════════════════════

Build vaqti: $(date)

Sozlamalar:
-----------
Bot Token: ${BOT_TOKEN:0:20}...
Chat ID: $CHAT_ID
Davriyligi: $SYNC_INTERVAL daqiqa

Fayllar:
--------
${PHONE} parental-monitor-debug.apk   - Debug versiya (test uchun)
${PHONE} parental-monitor-release.apk - Release versiya (IMZOLANGAN)

Eslatma:
--------
${CHECK} Bu APK larda Telegram sozlamalari avvaldan o'rnatilgan
${CHECK} Faqat o'rnatish va ruxsatlar berish kerak
${CHECK} Device Admin avtomatik faollashtiriladi
${CHECK} Monitoring avtomatik ishga tushadi

O'rnatish:
----------
1. APK ni telefonga nusxalash
2. Fayl menejerdan ochish
3. "O'rnatish" tugmasini bosish
4. Kalkulyatorda 1234= ni bosish (maxfiy kod)
5. Device Admin ruxsatini tasdiqlash
6. Barcha ruxsatlarni berish

Xavfsizlik:
-----------
${WARN} DIQQAT: Bu APK da sizning bot tokeningiz mavjud!
${WARN} Bu APK ni boshqa odamlarga bermang!
${WARN} Faqat o'z farzandingiz telefoniga o'rnating!
${LOCK} Keystore'ni saqlang va zaxira nusxasini oling!

Qonuniylik:
-----------
${CHECK} Faqat voyaga yetmagan farzandingiz uchun foydalaning
${CHECK} Mahalliy qonunlarga rioya qiling
${CROSS} Katta yoshdagilarga o'rnatmang

═══════════════════════════════════════════════════════════════════════════
Developer: Akhatkulov
License: MIT
Version: 1.0.0
═══════════════════════════════════════════════════════════════════════════
EOF

echo -e "${GREEN}${CHECK} Hujjatlar tayyor${NC}"

# ═══════════════════════════════════════════════════════════════════════════
#  RESULTS SECTION
# ═══════════════════════════════════════════════════════════════════════════

echo ""
echo -e "${GREEN}${BOLD}══════════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}${BOLD}              ${ROCKET} BUILD MUVAFFAQIYATLI TUGADI! ${CHECK}              ${NC}"
echo -e "${GREEN}${BOLD}══════════════════════════════════════════════════════════════${NC}"
echo ""
echo -e "${CYAN}${BOLD}${FOLDER} APK Fayllar Joyi:${NC}"
echo -e "  ${DIM}$OUTPUT_DIR/${NC}"
echo -e "    ${PHONE} ${GREEN}parental-monitor-debug.apk${NC}   ($(du -h "$OUTPUT_DIR/parental-monitor-debug.apk" 2>/dev/null | cut -f1))"
echo -e "    ${PHONE} ${GREEN}parental-monitor-release.apk${NC} ($(du -h "$OUTPUT_DIR/parental-monitor-release.apk" 2>/dev/null | cut -f1))"
echo -e "    ${FILE} ${CYAN}INFO.txt${NC}"
echo ""
echo -e "${YELLOW}${BOLD}${GEAR} Keyingi Qadamlar:${NC}"
echo -e "  ${CYAN}1.${NC} APK ni telefonga nusxalang"
echo -e "  ${CYAN}2.${NC} Fayl menejerdan ochib o'rnating"
echo -e "  ${CYAN}3.${NC} Kalkulyatorda ${YELLOW}1234=${NC} ni kiriting"
echo -e "  ${CYAN}4.${NC} Barcha ruxsatlarni bering"
echo -e "  ${CYAN}5.${NC} Monitoring avtomatik boshlanadi"
echo ""
echo -e "${RED}${BOLD}${WARN} MUHIM XAVFSIZLIK OGOHLANTIRISHI:${NC}"
echo -e "  ${RED}•${NC} Bu APK da sizning bot tokeningiz mavjud!"
echo -e "  ${RED}•${NC} APK ni boshqalarga BERMANG!"
echo -e "  ${RED}•${NC} Faqat o'z farzandingiz telefoniga o'rnating!"
echo -e "  ${YELLOW}•${NC} Keystore'ni backup qiling!"
echo ""
echo -e "${GREEN}${CHECK} Barcha jarayonlar muvaffaqiyatli yakunlandi!${NC}"
echo ""

# Ask if user wants to restore original build.gradle
echo -e "${YELLOW}Original build.gradle ni qaytarish kerakmi? (y/n):${NC}"
read -r RESTORE

if [ "$RESTORE" = "y" ] || [ "$RESTORE" = "Y" ]; then
    mv app/build.gradle.backup app/build.gradle
    echo -e "${GREEN}${CHECK} Original build.gradle qaytarildi${NC}"
else
    rm -f app/build.gradle.backup
    echo -e "${YELLOW}${INFO} Yangi build.gradle saqlandi (token'lar bilan)${NC}"
fi

echo ""
echo -e "${CYAN}${BOLD}══════════════════════════════════════════════════════════════${NC}"
echo -e "${DIM}  Developer: Akhatkulov | Red Eye Mobile v1.0.0 | MIT License${NC}"
echo -e "${CYAN}${BOLD}══════════════════════════════════════════════════════════════${NC}"
echo ""
