# ==============================================================================
# Onyx Browser - Production R8 / ProGuard Optimization Configuration
# ==============================================================================

# General Optimization Attributes
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-dontusemixedcaseclassnames

# ------------------------------------------------------------------------------
# 1. Android Manifest Components & Core Lifecycle
# ------------------------------------------------------------------------------
# Android OS requires un-obfuscated class names to instantiate manifest components
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgent
-keep public class * extends androidx.fragment.app.Fragment
-keep public class * extends androidx.lifecycle.ViewModel

# Core Application & Activities
-keep class com.onyx.browser.OnyxApplication { *; }
-keep class com.onyx.browser.MainActivity { *; }
-keep class com.onyx.browser.ui.** { *; }

# ------------------------------------------------------------------------------
# 2. Native JNI & Ad-Blocking Engine (Brave Rust Bridge)
# ------------------------------------------------------------------------------
# Preserve all native methods across the entire project
-keepclasseswithmembernames class * {
    native <methods>;
}

# Preserve the AdBlockEngine singleton, its exact package and all methods/fields
-keep class com.onyx.browser.nativebridge.** { *; }
-keepclassmembers class com.onyx.browser.nativebridge.** {
    <fields>;
    <methods>;
}

# Preserve AdBlock domain manager and start script helpers
-keep class com.onyx.browser.web.AdBlock* { *; }
-keepclassmembers class com.onyx.browser.web.AdBlock* {
    *;
}

# ------------------------------------------------------------------------------
# 3. WebView JavaScript Interfaces & WebAuthn / Passkey Bridge
# ------------------------------------------------------------------------------
# Preserve JavascriptInterface annotation and all methods annotated with it
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Preserve PasskeyWebAuthnBridge and its public methods called from JavaScript
-keep class com.onyx.browser.web.PasskeyWebAuthnBridge { *; }
-keepclassmembers class com.onyx.browser.web.PasskeyWebAuthnBridge {
    public <methods>;
}

# Preserve custom WebView and WebChrome clients
-keep class com.onyx.browser.web.OnyxWebView { *; }
-keep class com.onyx.browser.web.OnyxWebViewClient { *; }
-keep class com.onyx.browser.web.OnyxWebChromeClient { *; }

# ------------------------------------------------------------------------------
# 4. Room Database & SQLCipher Encryption
# ------------------------------------------------------------------------------
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class * implements androidx.room.RoomDatabase { *; }
-keep class * extends androidx.room.migration.Migration { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class com.onyx.browser.data.local.** { *; }
-keepclassmembers class com.onyx.browser.data.local.** { *; }
-keep class com.onyx.browser.data.model.** { *; }
-keepclassmembers class com.onyx.browser.data.model.** { *; }
-keep class **_Impl { *; }
-dontwarn androidx.room.**

# SQLCipher native bindings and database classes
-keep class net.sqlcipher.** { *; }
-keepclassmembers class net.sqlcipher.** { *; }
-keepclasseswithmembernames class net.sqlcipher.** {
    native <methods>;
}
-dontwarn net.sqlcipher.**
-keep class net.zetetic.** { *; }
-dontwarn net.zetetic.**

# ------------------------------------------------------------------------------
# 5. AndroidX Security & Google Tink Cryptography (for EncryptedSharedPreferences)
# ------------------------------------------------------------------------------
-keep class androidx.security.crypto.** { *; }
-keepclassmembers class androidx.security.crypto.** { *; }
-keep class com.google.crypto.tink.** { *; }
-keepclassmembers class com.google.crypto.tink.** { *; }
-dontwarn androidx.security.crypto.**
-dontwarn com.google.crypto.tink.**

# ------------------------------------------------------------------------------
# 6. AndroidX WebKit
# ------------------------------------------------------------------------------
-keep class androidx.webkit.** { *; }
-dontwarn androidx.webkit.**

# ------------------------------------------------------------------------------
# 7. Credential Manager & Passkeys (WebAuthn / FIDO2)
# ------------------------------------------------------------------------------
-keep class androidx.credentials.** { *; }
-keepclassmembers class androidx.credentials.** { *; }
-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.fido.** { *; }
-keep class com.google.android.gms.tasks.** { *; }
-dontwarn androidx.credentials.**
-dontwarn com.google.android.gms.**

# ------------------------------------------------------------------------------
# 8. ML Kit Barcode Vision & CameraX
# ------------------------------------------------------------------------------
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.vision.** { *; }
-dontwarn com.google.mlkit.**
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ------------------------------------------------------------------------------
# 9. Kotlin Coroutines & ViewBinding
# ------------------------------------------------------------------------------
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-keep class com.onyx.browser.databinding.** { *; }
-keep class * implements androidx.viewbinding.ViewBinding {
    public static * inflate(...);
    public static * bind(...);
}

# ------------------------------------------------------------------------------
# 10. Custom Views & Material Components for XML Layout Inflation
# ------------------------------------------------------------------------------
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**
-dontwarn androidx.appcompat.widget.**
