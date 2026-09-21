# 🌐 Onyx Browser (`com.onyx.browser`)

[![Build & Release](https://github.com/abidhasansojib/onyx-browser/actions/workflows/build.yml/badge.svg)](https://github.com/abidhasansojib/onyx-browser/actions/workflows/build.yml)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-blue.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple.svg)](https://kotlinlang.org)
[![Rust](https://img.shields.io/badge/Rust-NDK%20Native%20Engine-orange.svg)](https://www.rust-lang.org)
[![License](https://img.shields.io/badge/License-GPL%20v3-green.svg)](LICENSE)

**Onyx Browser** is a production-grade, ultra-fast, privacy-first Android web browser engineered from scratch for modern mobile devices. Combining native Kotlin with a compiled **Rust NDK ad-blocking engine** (Brave's `adblock-rust`), Onyx delivers instant cold starts, uncompromised tracking protection, encrypted on-disk storage, and a refined Google/Brave-inspired Material 3 user experience.

---

## ✨ Key Features

### 🛡️ Dual-Tier Shields & Ad-Blocking Engine
- **Compiled Rust NDK Subsystem**: High-performance JNI bridge (`libadblock_bridge.so`) compiling `adblock-rust` for 4 target ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`).
- **95%–100% Score on d3ward Adblock Test**:
  - **Standard Tier**: Blocks banner ads, analytics, tracking beacons, and ad servers.
  - **Aggressive Tier**: Neutralizes OEM telemetry (Xiaomi, Huawei, Samsung, Vivo, Oppo), consent management banners (OneTrust, Cookiebot, TrustArc), affiliate networks, and behavioral heatmaps.
- **Auto-Updating Filter Lists**: Daily background synchronization supporting 16+ core community blocklists (EasyList, EasyPrivacy, uBlock Filters, AdGuard Base, Fanboy Annoyance) plus Brave upstream lists.
- **Cosmetic Filtering & Anti-Circumvention**: Injects DOM-start CSS rules and JavaScript stubs to collapse empty ad placeholders and bypass anti-adblock detection.

### 🔑 Passkeys & WebAuthn Integration
- Native support for passwordless authentication via the **AndroidX Credential Manager** API.
- Injects standard W3C `navigator.credentials.create()` and `navigator.credentials.get()` bridges to authenticate with Google Password Manager, Bitwarden, 1Password, or YubiKeys.
- **Deterministic Keystore Signing**: Hardened CI pipeline ensures consistent release certificate fingerprints across builds, preventing passkey signature mismatch errors.

### 🖼️ Modern Context Menu & Image Tools
- **Compact Card Design**: Brave-inspired header card featuring live website favicons, domain title, clean URL, and inline quick-action buttons (**Share**, **Copy**, and **Edit**).
- **Edit in Address Bar**: Tapping the Edit button automatically populates and focuses the search bar with soft keyboard opened.
- **Image Preview**: 170dp thumbnail card with tap-to-expand badge, plus a full-screen **ImagePreviewDialog** for high-resolution inspection, sharing, and saving.
- **Reverse Image Search**: Instant multi-engine visual search supporting **Google Lens**, **TinEye**, **Yandex Images**, and **Bing Visual Search**.

### 🔍 Smart Search Bar & Scanner
- **Dynamic QR Code Scanner**: Hidden during normal browsing to maintain a clean address bar; automatically appears beside voice search when tapping the search bar to enter text. Powered by **CameraX** and **Google ML Kit Barcode Scanning**.
- **Search Engine Switcher**: One-tap popup menu to switch between Google, Brave Search, DuckDuckGo, Bing, Yahoo, and Startpage.
- **Debounced Suggestions**: Real-time multi-engine search suggestions with an encrypted offline query cache.

### 📱 Reorderable Homepage & Modern Navigation
- **Customizable Top Sites Grid**: Drag-and-drop shortcut reordering using Android `ItemTouchHelper` with automatic persistent JSON storage.
- **Quick Action Bar**: Flat circular action buttons for Bookmarks, History, Downloads, and Shortcuts Manager with drag-and-drop ordering.
- **Material 3 Tab Switcher**: Segmented toggle between **Normal** and **Incognito** tabs with dual-column preview cards and swipe-to-dismiss.
- **Safe Intent Routing**: Manifest-configured `singleTask` launch mode correctly receives and handles links opened from WhatsApp, Telegram, Gmail, and external apps.

### 🔒 Privacy & Local Encryption
- **SQLCipher Encrypted Database**: All browsing history, saved bookmarks, open tabs, and download logs are encrypted with AES-256 (`net.zetetic:android-database-sqlcipher`).
- **Cookie Policy Engine**: 3 modes — Allow All, Block 3rd-Party Cookies, or Block All Cookies.
- **Anti-Fingerprinting**: Spoofs generic `en-US` language headers and overrides `navigator.languages` to prevent device tracking.
- **Smart Download Routing**: Forward session cookies and user-agent headers directly to internal or external download managers (**1DM**, **1DM+**, **ADM**, **FDM**).

---

## 🏗️ Architecture & Directory Layout

```text
onyx-browser/
├── .github/workflows/
│   ├── build.yml                 # Autonomous CI: Rust NDK compile, Lucide sync, APK packaging
│   └── sync_upstream.yml         # Daily Brave filter list auto-sync
├── app/
│   ├── src/main/java/com/onyx/browser/
│   │   ├── MainActivity.kt       # Primary browser controller & toolbar coordinator
│   │   ├── OnyxApplication.kt    # Application entry point & encrypted DB initialization
│   │   │
│   │   ├── data/                 # Data Layer
│   │   │   ├── filter/           # FilterListManager (dynamic rule compiler & downloader)
│   │   │   ├── local/            # Room DAOs, entities & SQLCipher SecureDatabaseKeyProvider
│   │   │   ├── model/            # Data models (ShortcutItem, TabItem, HistoryItem, BookmarkItem)
│   │   │   ├── preferences/      # BrowserPreferences (StateFlow reactive settings)
│   │   │   └── search/           # SearchSuggestionRepository
│   │   │
│   │   ├── nativebridge/         # AdBlockEngine.kt (JNI interface to Rust libadblock_bridge)
│   │   │
│   │   ├── ui/                   # Presentation Layer
│   │   │   ├── bookmarks/        # Bookmarks manager activity & list adapter
│   │   │   ├── browser/          # TabManager (tab session lifecycle)
│   │   │   ├── downloads/        # DownloadsActivity, DownloadPromptBottomSheet, 1DM handoff
│   │   │   ├── history/          # HistoryActivity & HistoryAdapter
│   │   │   ├── home/             # Homepage shortcuts, quick actions & drag-to-reorder
│   │   │   ├── menu/             # ContextMenuBottomSheet, ImagePreviewDialog, ImageSearchPicker
│   │   │   ├── qr/               # QrScannerActivity (CameraX + ML Kit)
│   │   │   ├── search/           # Search suggestions & engine picker
│   │   │   ├── settings/         # SettingsActivity, ShieldsActivity, ContentFiltersActivity
│   │   │   └── tabs/             # TabSwitcherBottomSheet (Normal / Incognito toggle)
│   │   │
│   │   └── web/                  # Web Subsystem
│   │       ├── OnyxWebView.kt            # Hardened WebView with security policies
│   │       ├── OnyxWebViewClient.kt      # Network interception, AMP redirect & tracker stripper
│   │       ├── OnyxWebChromeClient.kt    # Fullscreen video, file chooser, progress tracking
│   │       ├── AdBlockDocumentStart.kt   # Early script injection (anti-fingerprint & CMP stubs)
│   │       ├── AdBlockDomainManager.kt   # 2-Tier domain blocklist (Standard vs Aggressive)
│   │       └── PasskeyWebAuthnBridge.kt  # AndroidX Credential Manager bridge
│   │
│   ├── src/main/res/             # Google Theme styles (Light/Dark/System), layouts, vectors
│   └── build.gradle.kts          # Dependencies, NDK configuration & deterministic release signing
│
├── keystore/
│   └── release.keystore          # Deterministic release signing key for stable Passkey signatures
├── rust_engine/                  # Native Rust crate (adblock-rust JNI bridge)
│   ├── Cargo.toml
│   └── src/lib.rs
└── scripts/                      # Build automation scripts (fetch_icons.sh, update_filter_lists.sh)
```

---

## 🛠️ Tech Stack & Dependencies

| Component | Technology / Library | Purpose |
|---|---|---|
| **Language** | Kotlin 2.0 / Java 17 | Core application logic |
| **Native Engine** | Rust 2021 / `cargo-ndk` | High-speed adblock filtering via JNI (`libadblock_bridge.so`) |
| **WebView Engine** | AndroidX WebKit 1.12.1 | Modern hardware-accelerated web rendering |
| **Encrypted Database**| SQLCipher 4.5.4 + Room 2.6.1 | 256-bit AES encrypted local data storage |
| **Barcode / QR** | Google ML Kit Barcode 17.3.0 + CameraX 1.3.4 | Ultra-fast on-device QR code scanning |
| **Credentials** | AndroidX Credential Manager 1.3.0 | WebAuthn / FIDO2 Passkey authentication |
| **Asynchronous I/O** | Kotlin Coroutines & StateFlow | Non-blocking background network and DB operations |
| **Design System** | Google Material Components 1.12.0 | Material 3 themes, bottom sheets, dialogs, and toggles |

---

## 🚀 Building & Releasing

### Automated CI/CD (Recommended)
Onyx Browser uses an automated GitHub Actions pipeline ([`.github/workflows/build.yml`](.github/workflows/build.yml)) to compile native Rust libraries for all Android architectures and assemble both **Release** and **Debug** APKs:

1. Push or merge changes to the `main` branch.
2. Go to **Actions** ➔ **Build Onyx Browser**.
3. Download the signed **`Onyx-Browser-APK`** bundle directly from the workflow artifacts.

### Remote Keystore Configuration (Optional)
By default, the repository contains a persistent release keystore ([`keystore/release.keystore`](keystore/release.keystore)) to guarantee consistent signing fingerprints. To sign with your own private production credentials, configure the following secrets in **GitHub Repository Settings ➔ Secrets and variables ➔ Actions**:

- `KEYSTORE_BASE64`: Base64 string of your private `.keystore` (`base64 -w 0 your.keystore`).
- `KEYSTORE_PASSWORD`: Keystore store password.
- `KEY_ALIAS`: Key alias name.
- `KEY_PASSWORD`: Key password.

---

## 📄 License

This project is licensed under the [GNU General Public License v3.0](LICENSE).
Rust ad-blocking components incorporate code from Brave Software licensed under MPL-2.0.
