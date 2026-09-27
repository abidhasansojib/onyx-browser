<div align="center">

<img src="art/logo.png" alt="Onyx Browser Logo" width="128" height="128" style="border-radius: 28px;" />

# Onyx Browser

### Fast, Private, and Lightweight Android Web Browser with Native Rust Adblocking

[![Build & Release](https://github.com/abidhasansojib/onyx-browser/actions/workflows/build.yml/badge.svg)](https://github.com/abidhasansojib/onyx-browser/actions/workflows/build.yml)
[![Latest Release](https://img.shields.io/github/v/release/abidhasansojib/onyx-browser?color=blue&label=Release)](https://github.com/abidhasansojib/onyx-browser/releases/latest)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-3DDC84.svg?logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Rust NDK](https://img.shields.io/badge/Rust-NDK%20Engine-DEA584.svg?logo=rust&logoColor=white)](https://www.rust-lang.org)
[![License](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](LICENSE)

<br />

[**📥 Download Latest APK**](https://github.com/abidhasansojib/onyx-browser/releases/latest) • [**📖 Comprehensive Feature Guide**](FEATURES.md) • [**🐛 Report Bug**](https://github.com/abidhasansojib/onyx-browser/issues)

</div>

---

## 🌟 Overview

**Onyx Browser** is a modern, high-performance Android web browser engineered from scratch for security, extreme responsiveness, and uncompromising privacy. Built entirely with native Kotlin XML ViewBinding and a compiled **Rust NDK ad-blocking engine** (`adblock-rust`), Onyx eliminates bloated web cruft, intrusive trackers, and interstitial ads while consuming minimal battery and RAM.

> [!TIP]
> Looking for an in-depth technical breakdown of all capabilities, adblock benchmarks, and architecture? Check out the [**Comprehensive Features Guide (FEATURES.md)**](FEATURES.md).

---

## ✨ Key Highlights

| Feature | Description |
| :--- | :--- |
| **🛡️ Native Rust Shields** | Compiled `adblock-rust` NDK bridge with 54 Brave content filter lists, type-aware 200 OK stubs, and cosmetic filtering. |
| **🎬 Streaming Media Suite** | True video-only Picture-in-Picture (PiP), background playback keep-alive (`userHitPause`), and MediaSession lockscreen sync. |
| **🎛️ Floating Action Pill** | One-tap floating menu for stream downloads, headphone background play toggle, and PiP controls. |
| **🔑 Passkeys & WebAuthn** | Passwordless biometric sign-in via AndroidX Credential Manager, Google Password Manager, and Bitwarden. |
| **📥 Smart Downloader** | Integrated stream inspector forwarding session cookies and headers directly to **1DM**, **ADM**, or **FDM**. |
| **🔍 Smart Omnibox** | Full-page search mode, real-time multi-engine suggestions, keyword search shortcuts, and CameraX QR scanner. |
| **🎨 Google Material 3 UI** | Pure Google Dark (`#202124`), Google Light, and AMOLED Black themes designed with responsive box-type containers. |
| **🔒 Encrypted Storage** | Tabs, browsing history, and bookmarks secured on-device via **SQLCipher AES-256** encryption. |

---

## 📥 Download & Installation

Download the latest verified release APK directly from [**GitHub Releases**](https://github.com/abidhasansojib/onyx-browser/releases/latest):

| Architecture | Recommended Device | Package |
| :--- | :--- | :--- |
| **`arm64-v8a`** *(Recommended)* | Modern phones & tablets (2017+) | `Onyx-Browser-v*-arm64-v8a-release.apk` |
| **`armeabi-v7a`** | Legacy 32-bit Android devices | `Onyx-Browser-v*-armeabi-v7a-release.apk` |
| **`x86_64`** | Android emulators & Chromebooks | `Onyx-Browser-v*-x86_64-release.apk` |
| **`universal`** | All architectures bundled together | `Onyx-Browser-v*-universal-release.apk` |

---

## 🛠️ Technology Stack

- **Application Language**: Kotlin 2.x
- **UI Framework**: Classic Android XML Views with ViewBinding (Strictly zero Jetpack Compose for instantaneous cold starts and smooth 120Hz scrolling)
- **Ad-Blocking Engine**: Brave `adblock-rust` cross-compiled with `cargo-ndk`
- **Database**: Room Database with SQLCipher 256-bit encryption
- **Authentication**: AndroidX Credential Manager (`androidx.credentials`)
- **QR Code Scanning**: CameraX + Google ML Kit Barcode Scanning
- **Developer Tools**: Bundled offline Eruda JavaScript mobile inspector

---

## 🏗️ Building from Source

All official binaries are built via the autonomous GitHub Actions CI/CD workflow:

1. Fork or clone this repository:
   ```bash
   git clone --recursive https://github.com/abidhasansojib/onyx-browser.git
   ```
2. Build via GitHub Actions:
   - Navigate to the **Actions** tab in your GitHub repository.
   - Select **Build Onyx Browser** and click **Run workflow**.
   - Choose your build type (`Release`, `Debug`, or `Both`).
   - The compiled and signed APKs will be published under Releases and Workflow Artifacts.

---

## 💖 Credits & Acknowledgements

Onyx Browser is built on the shoulders of incredible open-source innovations:

- **[Brave Software](https://brave.com)**: For the high-performance native [`adblock-rust`](https://github.com/brave/adblock-rust) engine, official adblocking filter lists, and pioneering browser privacy concepts.
- **[Quetta Browser](https://www.quetta.net/)**: For aesthetic inspiration and design ideas behind the clean, modern UI layout and theme presentation.
- **[Lucide Icons](https://lucide.dev)**: For crisp, elegant vector icons adapted throughout the browser interface.
- **[SQLCipher](https://www.zetetic.net/sqlcipher/)**: For full 256-bit AES database encryption protecting local user data.

---

## 📄 License

Onyx Browser is open-source software licensed under the [GNU General Public License v3.0 (GPL-3.0)](LICENSE).

