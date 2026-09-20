# AGENTS.md - Onyx Browser Project Operational Guide & Architecture

## 1. Project Summary & Purpose
**Onyx Browser** (`com.onyx.browser`) is a production-grade, ultra-lightweight, high-performance Android web browser engineered from scratch for modern Android devices (Min SDK 26, Target/Compile SDK 35).

### Core Goals & Tech Stack
- **Language**: Kotlin 2.x (Android App) & Rust (Ad-blocking Engine via JNI).
- **UI Paradigm**: Classic Android XML Views with ViewBinding. Strictly NO Jetpack Compose to preserve instantaneous cold starts, minimize memory consumption, and ensure optimal hardware-accelerated WebView compositing.
- **Native Ad-Blocker**: Brave's `adblock-rust` compiled to `.so` shared libraries (`libadblock_bridge.so`) across target Android ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`) via `cargo-ndk`.
- **Database**: Room Database for history, bookmarks, tabs, and downloads persistence.
- **Preferences**: AndroidX Jetpack Preferences with AMOLED Pure Black `#000000`, Light, and Material You dynamic color themes.
- **CI/CD**: Fully autonomous GitHub Actions workflow to cross-compile Rust NDK shared libraries and build Android release/debug APKs.

---

## 2. Architecture & Directory Layout
```text
onyx-browser/
├── .github/
│   └── workflows/
│       └── build.yml
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/onyx/browser/
│   │       │   ├── data/
│   │       │   │   ├── local/ (Room DB: History, Bookmarks, Downloads, Tabs)
│   │       │   │   ├── model/ (SearchEngine, TabItem, DownloadItem, HistoryItem, BookmarkItem)
│   │       │   │   └── preferences/ (BrowserPreferences)
│   │       │   ├── nativebridge/ (AdBlockEngine.kt - JNI bridge)
│   │       │   ├── ui/
│   │       │   │   ├── home/ (Homepage body, quick action row)
│   │       │   │   ├── browser/ (Tab manager, WebView container)
│   │       │   │   ├── tabs/ (TabSwitcherBottomSheetDialogFragment & adapter)
│   │       │   │   ├── menu/ (MenuBottomSheetDialogFragment)
│   │       │   │   ├── settings/ (SettingsActivity & Preferences)
│   │       │   │   ├── downloads/ (DownloadPromptDialog, DownloadsActivity)
│   │       │   │   ├── history/ (HistoryActivity & adapter)
│   │       │   │   └── bookmarks/ (BookmarksActivity & adapter)
│   │       │   ├── web/ (OnyxWebViewClient, OnyxWebChromeClient, DownloadHandler)
│   │       │   ├── MainActivity.kt
│   │       │   └── OnyxApplication.kt
│   │       ├── res/
│   │       │   ├── layout/
│   │       │   ├── values/ (themes, colors, strings, attrs - AMOLED Dark/Light/Dynamic)
│   │       │   ├── values-night/
│   │       │   └── drawable/ (SVG vector assets for engines, actions, UI)
│   │       ├── jniLibs/ (arm64-v8a, armeabi-v7a, x86_64)
│   │       └── AndroidManifest.xml
│   └── build.gradle.kts
├── rust_engine/
│   ├── Cargo.toml
│   └── src/
│       └── lib.rs
├── gradle/
│   └── wrapper/
│       ├── gradle-wrapper.jar
│       └── gradle-wrapper.properties
├── gradlew
├── gradlew.bat
├── settings.gradle.kts
├── build.gradle.kts
└── AGENTS.md
```

---

## 3. Established Project Rules & Coding Standards

### Environment & Capability Constraints
- **Local Building Strictly Forbidden**: The local host environment is NOT permitted or capable of building Android Gradle projects, compiling NDK/Rust binaries, or running heavy compilation pipelines.
- **Remote CI/CD Execution**: All compilation of Rust native libraries (`libadblock_bridge.so`) and Android APK builds (`./gradlew assembleDebug` / `assembleRelease`) MUST be executed remotely via GitHub Actions.
- **Code Quality**:
  - Null-safe, idiomatic Kotlin code with lifecycle-aware ViewBinding binding inflation and clearing.
  - Strict WebView memory leak prevention: Detach WebViews from parent layout, destroy properly in `onDestroyView()` / tab closure, remove callbacks.
  - Coroutines with `Dispatchers.IO` for disk and database access, `Dispatchers.Main` for UI updates.
  - Rust memory safety: Clean JNI boundary error handling with `catch_unwind` and fallback to unblocked if any JNI error occurs.

---

## 4. Active Tasks & Milestones
- [x] Create project structure and `AGENTS.md`.
- [x] Implement Rust native crate (`rust_engine/Cargo.toml`, `rust_engine/src/lib.rs`).
- [x] Configure root and app `build.gradle.kts`, `settings.gradle.kts`, and Gradle wrapper.
- [x] Implement Room Database, Entities, and DAOs (`data/local`, `data/model`).
- [x] Implement SharedPreferences manager (`BrowserPreferences`).
- [x] Implement Native JNI bridge (`nativebridge/AdBlockEngine.kt`).
- [x] Implement WebView client and chrome client with adblocking & cosmetic CSS injection (`web/`).
- [x] Implement UI: Top toolbar, Home body, Tab switcher, Quick menu, Downloads dialog, Settings, History, Bookmarks.
- [x] Provide high-tech vector drawables and AMOLED themes via automated Lucide icon acquisition.
- [x] Implement GitHub Actions CI/CD workflow (`.github/workflows/build.yml`).
- [x] Initialize Git repo, push to GitHub (`abidhasansojib/onyx-browser`), trigger workflow, and verify successful build.
- [x] Monitor remote CI/CD execution and verify artifact generation.
