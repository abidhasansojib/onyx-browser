# ==============================================================================
# Onyx Browser - Production R8 / ProGuard Optimization Configuration
# ==============================================================================

# General Optimization Attributes
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-dontusemixedcaseclassnames
-repackageclasses 'com.onyx.browser.obf'
-allowaccessmodification

# ------------------------------------------------------------------------------
# 1. Native JNI & Ad-Blocking Engine (Brave Rust Bridge)
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
-keep class com.onyx.browser.web.AdBlockDomainManager { *; }
-keep class com.onyx.browser.web.AdBlockDocumentStart { *; }

# ------------------------------------------------------------------------------
# 2. WebView JavaScript Interfaces & WebAuthn / Passkey Bridge
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
# 3. Room Database & SQLCipher Encryption
# ------------------------------------------------------------------------------
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class com.onyx.browser.data.local.** { *; }
-keep class com.onyx.browser.data.model.** { *; }

# SQLCipher native bindings
-keep class net.zetetic.** { *; }
-dontwarn net.zetetic.**

# ------------------------------------------------------------------------------
# 4. AndroidX WebKit & Security
# ------------------------------------------------------------------------------
-keep class androidx.webkit.** { *; }
-dontwarn androidx.webkit.**
-keep class androidx.security.crypto.** { *; }

# ------------------------------------------------------------------------------
# 5. Credential Manager & Passkeys (WebAuthn / FIDO2)
# ------------------------------------------------------------------------------
-keep class androidx.credentials.** { *; }
-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.fido.** { *; }
-dontwarn androidx.credentials.**
-dontwarn com.google.android.gms.**

# ------------------------------------------------------------------------------
# 6. ML Kit Barcode Vision & CameraX
# ------------------------------------------------------------------------------
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.vision.** { *; }
-dontwarn com.google.mlkit.**
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ------------------------------------------------------------------------------
# 7. Kotlin Coroutines & ViewBinding
# ------------------------------------------------------------------------------
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-keep class com.onyx.browser.databinding.** { *; }

# Custom Views for XML Layout Inflation
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
