# Onyx Browser (`com.onyx.browser`)

Production-grade, ultra-lightweight, high-performance Android web browser engineered from scratch for modern Android devices (Min SDK 26, Target/Compile SDK 35).

## Highlights
- **Classic Android XML Views & ViewBinding**: Zero Jetpack Compose overhead for instantaneous cold starts, minimal memory footprint, and low-latency hardware-accelerated WebView compositing.
- **Native Ad-Blocking Engine**: Brave's `adblock-rust` compiled into native shared libraries (`libadblock_bridge.so`) across target Android architectures (`arm64-v8a`, `armeabi-v7a`, `x86_64`) via Rust NDK and JNI.
- **Cosmetic Element Hiding**: Injects dynamic element-hiding CSS directly via DOM mutation to collapse empty ad placeholders.
- **AMOLED Pure Black `#000000` & Material You**: Full support for true dark mode, clean light mode, and dynamic Android 12+ themes.
- **Multi-Tab & Incognito Architecture**: Seamless segmented tab switcher with dual-column card views, swipe-to-dismiss gestures, and ephemeral private sessions.
- **Smart Download Routing**: Option to route downloads directly to external download managers (1DM, ADM, FDM) or the internal multi-threaded downloader.
- **Autonomous CI/CD**: Full GitHub Actions workflow cross-compiling Rust NDK binaries and assembling debug & release APKs.

## Architecture
```text
onyx-browser/
├── .github/workflows/build.yml   # Autonomous Rust NDK & Gradle CI/CD
├── app/
│   ├── src/main/java/com/onyx/browser/
│   │   ├── data/                 # Room DB (History, Bookmarks, Downloads, Tabs) & Preferences
│   │   ├── nativebridge/         # AdBlockEngine.kt - JNI bindings
│   │   ├── ui/                   # Classic XML UI controllers (Home, Tabs, Menu, Settings, etc.)
│   │   ├── web/                  # OnyxWebViewClient, ChromeClient, DownloadHandler
│   │   ├── MainActivity.kt
│   │   └── OnyxApplication.kt
│   ├── src/main/res/             # AMOLED Themes, layouts, vector drawables
│   └── build.gradle.kts
├── rust_engine/                  # Native Rust crate with Brave adblock & JNI
│   ├── Cargo.toml
│   └── src/lib.rs
└── settings.gradle.kts
```
