<div align="center">

<img src="art/logo.png" alt="Onyx Browser Logo" width="128" height="128" style="border-radius: 28px;" />

# Onyx Browser

### Fast, private, and lightweight Android browser with built-in adblocking

[![Build & Release](https://github.com/abidhasansojib/onyx-browser/actions/workflows/build.yml/badge.svg)](https://github.com/abidhasansojib/onyx-browser/actions/workflows/build.yml)
[![Latest Release](https://img.shields.io/github/v/release/abidhasansojib/onyx-browser?color=blue&label=Release)](https://github.com/abidhasansojib/onyx-browser/releases/latest)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-3DDC84.svg?logo=android&logoColor=white)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](LICENSE)

<br />

[**Download APK**](https://github.com/abidhasansojib/onyx-browser/releases/latest) • [**Full Features**](FEATURES.md) • [**Report an Issue**](https://github.com/abidhasansojib/onyx-browser/issues)

</div>

---

## What is Onyx?

Onyx is a clean, minimal, and fast web browser for Android. It has Brave's adblocking engine built right in, blocks annoying popups and trackers out of the box, and stays light on your battery and RAM.

---

## Features

- **Built-in Adblocker** – Blocks video ads, banners, popups, and trackers using Brave's native adblocking engine.
- **Background Play & PiP** – Keep playing audio or video when switching apps or locking your screen, with full Picture-in-Picture support.
- **Fast & Smooth** – Opens instantly and scrolls smoothly without background bloat or lag.
- **Privacy by Default** – Encrypted history and bookmarks stored only on your phone, with zero tracking or telemetry.
- **Clean Theme** – Matches your phone with Google Light, Google Dark, and true AMOLED Black modes.
- **Passkeys & Autofill** – Works seamlessly with Google Password Manager, Bitwarden, and biometric passkeys.
- **External Downloads** – Catch video streams and easily hand off downloads to apps like 1DM or ADM.
- **QR Code Scanner** – Scan QR codes straight from the search bar with your camera.

---

## Download

Get the latest APK from [**GitHub Releases**](https://github.com/abidhasansojib/onyx-browser/releases/latest):

| Architecture | Best For | Package File |
| :--- | :--- | :--- |
| **`arm64-v8a`** | Most modern Android phones & tablets *(Recommended)* | `Onyx-Browser-*-arm64-v8a-release.apk` |
| **`armeabi-v7a`** | Older 32-bit Android phones | `Onyx-Browser-*-armeabi-v7a-release.apk` |
| **`x86_64`** | PC emulators & Chromebooks | `Onyx-Browser-*-x86_64-release.apk` |
| **`universal`** | Works on any device *(Larger file size)* | `Onyx-Browser-*-universal-release.apk` |

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
