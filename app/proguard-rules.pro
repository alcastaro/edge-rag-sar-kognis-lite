# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# --- 11.1: EncryptedSharedPreferences (security-crypto) ---
-keep class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }

# --- 11.5: SecurePrefs singleton ---
-keep class io.kognis.tactical.core.SecurePrefs { *; }

# --- ObjectBox (existing) ---
-keep class io.objectbox.** { *; }
-keepclassmembers class * { @io.objectbox.annotation.Entity <fields>; }

# --- ONNX Runtime ---
-keep class ai.onnxruntime.** { *; }

# --- LEAP SDK ---
-keep class ai.liquid.leap.** { *; }

# --- llama.cpp JNI bindings (S21) ---
# Native methods + class loaded by System.loadLibrary("kognis_llm")
-keepclasseswithmembers class * { native <methods>; }
-keepclasseswithmembernames class io.kognis.tactical.core.llm.** { *; }
-keep class io.kognis.tactical.core.llm.LlamaJni { *; }

# --- Kotlinx Serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class io.kognis.tactical.**$$serializer { *; }
-keepclassmembers class io.kognis.tactical.** {
    *** Companion;
}
-keepclasseswithmembers class io.kognis.tactical.** {
    kotlinx.serialization.KSerializer serializer(...);
}

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