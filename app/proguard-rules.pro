# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
-renamesourcefileattribute SourceFile

# Retrofit
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement

-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-keep class com.redeye.parentalmonitor.data.models.** { *; }
# Dulu selimut: -keep class network.** { *; } — membatalkan
# obfuskasi+susut untuk seluruh paket termasuk logika klien
# TelegramClient/Media/Poll (issue #1292, keluarga #1195).
# Kebutuhan nyatanya hanya DTO Gson + antarmuka Retrofit:
# antarmuka sudah dijaga aturan interface @retrofit2.http di
# atas, jadi di sini cukup anggota DTO-nya — field
# @SerializedName dan konstruktornya — sedangkan kelas klien
# dan logika bebas dikaburkan/disusutkan.
-keepclassmembers class com.redeye.parentalmonitor.network.** {
    @com.google.gson.annotations.SerializedName <fields>;
    <init>(...);
}
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
# Nama kelas komponen dipertahankan (dirujuk manifest,
# WorkManager menyimpan nama kelas pekerja di basis datanya,
# dan kewaspadaan nama lain), tetapi anggota kelas boleh
# dikaburkan dan disusutkan (issue #1195): keep selimut
# sebelumnya membatalkan obfuskasi +-18.700 baris logika inti.
-keepnames class com.redeye.parentalmonitor.service.**
-keepnames class com.redeye.parentalmonitor.worker.**
-keepnames class com.redeye.parentalmonitor.receiver.**
# AppUsage dipersistensikan sebagai JSON Gson dengan nama
# field sebagai kunci (A11yMonitor): nama field-nya wajib utuh.
-keepclassmembers class com.redeye.parentalmonitor.service.A11yMonitor$AppUsage {
    <init>();
    <fields>;
}

# Log rilis distrip (issue #1639): semua panggilan
# android.util.Log dibuang dari build rilis. Beberapa
# situs membawa throwable utuh yang tumpukan jejaknya
# bisa memuat konteks internal, dan pada aplikasi
# anti-maling logcat adalah permukaan baca nyata lewat
# ADB/akses fisik/root. Build debug tidak terpengaruh.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}
