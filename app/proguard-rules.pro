# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.

# Uncomment to preserve line numbers for stack trace debugging:
#-keepattributes SourceFile,LineNumberTable
#-renamesourcefileattribute SourceFile

# ── GContinuity Transport Layer ─────────────────────────────────────────────
# Keep all Packet subclasses — required for kotlinx.serialization class discriminator
-keep class org.gcontinuity.android.transport.model.Packet { *; }
-keep class org.gcontinuity.android.transport.model.Packet$* { *; }
-keep class org.gcontinuity.android.transport.model.MediaAction { *; }
-keep class org.gcontinuity.android.transport.model.InputKind  { *; }
-keepclassmembers class org.gcontinuity.android.transport.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ── kotlinx.serialization ────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class org.gcontinuity.android.**$$serializer { *; }
-keepclassmembers class org.gcontinuity.android.** {
    *** Companion;
    static ** serializer();
}

# ── WebRTC ────────────────────────────────────────────────────────────────────
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
-keep class io.github.webrtc_sdk.** { *; }

# ── Conscrypt ─────────────────────────────────────────────────────────────────
-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**

# ── OkHttp ────────────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.internal.** { *; }
-keep class okhttp3.WebSocket { *; }
-keep class okhttp3.WebSocketListener { *; }

# ── BouncyCastle (cert generation) ───────────────────────────────────────────
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# ── Hilt ─────────────────────────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-dontwarn dagger.hilt.**

# ── Error-prone annotations (transitively required by Tink / security-crypto) ─
# Generated automatically by R8 — see build/outputs/mapping/release/missing_rules.txt
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi