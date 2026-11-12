<div align="center">

<img src="docs/logo.png" alt="Onyx Browser Logo" width="128" height="128" style="border-radius: 28px;" />

# Onyx Browser

A fast, private, and lightweight browser for Android.

[![Build & Release](https://github.com/abidhasansojib/onyx-browser/actions/workflows/build.yml/badge.svg)](https://github.com/abidhasansojib/onyx-browser/actions/workflows/build.yml)
[![Latest Release](https://img.shields.io/github/v/release/abidhasansojib/onyx-browser?color=blue&label=Release)](https://github.com/abidhasansojib/onyx-browser/releases/latest)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84.svg?logo=android&logoColor=white)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](LICENSE)

<br />

[**Download APK**](https://github.com/abidhasansojib/onyx-browser/releases/latest) • [**Features**](docs/FEATURES.md) • [**Architecture**](docs/ARCHITECTURE.md) • [**Contributing**](CONTRIBUTING.md) • [**Security**](SECURITY.md) • [**Report an Issue**](https://github.com/abidhasansojib/onyx-browser/issues)

</div>

---

## About

Onyx is a clean and fast web browser for Android with built-in adblocking, background media playback, and on-device privacy.

---

## Features

- **Adblocking** – Blocks ads, popups, and trackers with Brave's native engine
- **UserScripts** – Built-in Tampermonkey & Violentmonkey engine to run, import, and edit custom scripts
- **Background Play & PiP** – Audio and video keep playing in the background, with Picture-in-Picture
- **Private & Secure** – On-device encrypted storage, per-site shields, and zero tracking
- **Fast & Lightweight** – Instant startup, low memory usage, and no background bloat
- **Themes** – Google Light, Google Dark, and pure AMOLED black modes
- **Smart Tools** – Passkeys, external download manager handoff, and built-in QR scanner

---

## Download

Get the latest APK from [**GitHub Releases**](https://github.com/abidhasansojib/onyx-browser/releases/latest):

- **Most phones (recommended):** `arm64-v8a`
- **Older phones (32-bit):** `armeabi-v7a`
- **Emulators / PC:** `x86_64`
- **Not sure?** Grab the `universal` build

---

## Built With & Architecture Highlights

- **Kotlin & Native Views** – Fast startup and low memory footprint with XML ViewBinding, UDF `StateFlow` ViewModels, and modular UI coordinators (`ToolbarLayoutDelegate`, `BrowserMenuCoordinator`, `NavigationCoordinator`)
- **Rust NDK (`libadblock_bridge.so`)** – Brave's native `adblock-rust` engine with FlatBuffers binary rule caching and a Chain-of-Responsibility `RequestInterceptor` pipeline
- **Hardened Chromium WebView** – Multi-Profile Incognito partitioning, WebAuthn Level 3 Passkey bridge, `ExternalNavigationHandler` intent routing, and Tampermonkey/Violentmonkey Userscript engine
- **SQLCipher AES-256** – Full on-device Room database encryption (`OnyxDatabase`, `SitePermissionRepository`) backed by Android KeyStore master keys
- **CameraX & ML Kit** – Fast on-device QR and barcode scanning

See [Architecture & Core Subsystems](docs/ARCHITECTURE.md) and [WebView Engineering Guide](docs/WEBVIEW.md) for full technical details.

---

## Building from Source

### Option 1: Local Workstation Build (Linux / macOS / WSL2)

Prerequisites: **JDK 17**, **Android SDK (API 35)**, **Android NDK r26b+**, and **Rust stable** + `cargo-ndk`.

```bash
git clone --recurse-submodules https://github.com/abidhasansojib/onyx-browser.git
cd onyx-browser
./scripts/bootstrap_dev.sh
./gradlew :core:common:test testReleaseUnitTest assembleRelease
```

See [CONTRIBUTING.md](CONTRIBUTING.md) for full setup instructions and development guidelines.

### Option 2: GitHub Actions CI Build

1. Fork this repository on GitHub.
2. Go to the **Actions** tab in your GitHub fork.
3. Choose **Build Onyx Browser** and click **Run workflow**.
4. Your compiled APKs will be ready under **Releases** and **Artifacts**.

---

## Credits

- [Brave Software](https://brave.com) – for `adblock-rust` and adblocking filter lists
- [Lucide Icons](https://lucide.dev) – for clean UI vectors
- [SQLCipher](https://www.zetetic.net/sqlcipher/) – for database encryption

---

## License

Onyx Browser is open-source under the [GNU General Public License v3.0 (GPL-3.0)](LICENSE).
