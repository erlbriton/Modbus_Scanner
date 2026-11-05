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
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
#---------------------------------------------------------------------------------------------------

# ==========================================
# ПРАВИЛА ДЛЯ БИБЛИОТЕК И КОДА
# ==========================================

# --- usb-serial-for-android ---
# Библиотека использует рефлексию для поиска драйверов (CP210x, CH340 и т.д.)
# по VID/PID. Если R8 переименует классы, библиотека не найдёт драйвер,
# и USB-связь с контроллером сломается.
-keep class com.hoho.android.usbserial.driver.** { *; }

# --- Kotlinx Serialization (Навигация) ---
# Мы используем типобезопасную навигацию (@Serializable data object/class).
# R8 должен сохранить сгенерированные сериализаторы для наших маршрутов.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.intmash.modbus_scanner.**$$serializer { *; }
-keepclassmembers class com.intmash.modbus_scanner.** {
    *** Companion;
}
-keepclasseswithmembers class com.intmash.modbus_scanner.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Наши data-классы ---
# Сохраняем имена полей для классов, которые могут участвовать
# в сериализации или рефлексии (на всякий случай, чтобы не сломать UI).
-keepclassmembers class com.intmash.modbus_scanner.data.** {
    <fields>;
}