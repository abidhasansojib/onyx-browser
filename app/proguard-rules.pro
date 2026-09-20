# Keep native methods and classes
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class com.onyx.browser.nativebridge.** { *; }

# Keep Room database entities and DAOs
-keep class androidx.room.** { *; }
-keep class com.onyx.browser.data.local.** { *; }
-keep class com.onyx.browser.data.model.** { *; }
