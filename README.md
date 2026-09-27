<div align="center">

<img src="art/logo.png" alt="Onyx Browser Logo" width="128" height="128" style="border-radius: 28px;" />

# Onyx Browser

A fast, private, and lightweight browser for Android.

[![Build & Release](https://github.com/abidhasansojib/onyx-browser/actions/workflows/build.yml/badge.svg)](https://github.com/abidhasansojib/onyx-browser/actions/workflows/build.yml)
[![Latest Release](https://img.shields.io/github/v/release/abidhasansojib/onyx-browser?color=blue&label=Release)](https://github.com/abidhasansojib/onyx-browser/releases/latest)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84.svg?logo=android&logoColor=white)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](LICENSE)

<br />

[**Download APK**](https://github.com/abidhasansojib/onyx-browser/releases/latest) • [**Full Features**](FEATURES.md) • [**Report an Issue**](https://github.com/abidhasansojib/onyx-browser/issues)

</div>

---

## About

Onyx is a clean and fast web browser for Android with built-in adblocking, background media playback, and on-device privacy.

---

## Features

- **Adblocking** – Blocks ads, popups, and trackers with Brave's native engine
- **Background Play & PiP** – Audio and video keep playing in the background, with Picture-in-Picture
- **Fast & Light** – Instant startup, low memory usage, and no background bloat
- **Private** – On-device encrypted bookmarks and history with zero tracking
- **Themes** – Google Light, Google Dark, and pure AMOLED black modes
- **Tools** – Passkeys, external download manager handoff, and QR scanner

---

## Download

Get the latest APK from [**GitHub Releases**](https://github.com/abidhasansojib/onyx-browser/releases/latest):

- **Most phones (recommended):** `arm64-v8a`
- **Older phones (32-bit):** `armeabi-v7a`
- **Emulators / PC:** `x86_64`
- **Not sure?** Grab the `universal` build

---

## Built With

- **Kotlin & Native Views** – Lightweight Android UI with ViewBinding
- **Rust NDK** – Brave's native `adblock-rust` engine
- **SQLCipher** – Local database encryption
- **CameraX** – Fast on-device QR scanning

---

## Building from Source

1. Clone the repository:
   ```bash
   git clone --recursive https://github.com/abidhasansojib/onyx-browser.git
   ```
2. Build via GitHub Actions:
   - Go to the **Actions** tab in your GitHub fork.
   - Choose **Build Onyx Browser** and click **Run workflow**.
   - Your compiled APKs will be ready under Releases and Artifacts.

---

## Credits

- [Brave Software](https://brave.com) – for `adblock-rust` and adblocking filter lists
- [Lucide Icons](https://lucide.dev) – for clean UI vectors
- [SQLCipher](https://www.zetetic.net/sqlcipher/) – for database encryption

---

## License

Onyx Browser is open-source under the [GNU General Public License v3.0 (GPL-3.0)](LICENSE).
