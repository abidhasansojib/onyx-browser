# 🚀 Onyx Browser — Comprehensive Feature Guide

Welcome to the detailed feature manual for **Onyx Browser** (`com.onyx.browser`). This document provides an exhaustive breakdown of Onyx Browser's architecture, privacy engines, media capabilities, security systems, and user interface paradigms.

---

## 📑 Table of Contents
1. [🛡️ Dual-Tier Ad-Blocking & Privacy Shields](#1-dual-tier-ad-blocking--privacy-shields)
2. [🎬 Advanced Video & Media Suite](#2-advanced-video--media-suite)
3. [📥 Download Management & External Downloader Integration](#3-download-management--external-downloader-integration)
4. [🔑 Passkeys, WebAuthn & Password Autofill](#4-passkeys-webauthn--password-autofill)
5. [🔍 Omnibox, Smart Search & QR Scanner](#5-omnibox-smart-search--qr-scanner)
6. [🖼️ Context Menu & Multi-Engine Reverse Image Search](#6-context-menu--multi-engine-reverse-image-search)
7. [🎨 Pure Google Themes & Material 3 Box-Type UI](#7-pure-google-themes--material-3-box-type-ui)
8. [📑 Tab Switcher & Time-Range Data Cleaning](#8-tab-switcher--time-range-data-cleaning)
9. [🔒 Local Encryption & Security Hardening](#9-local-encryption--security-hardening)
10. [🛠️ Developer Tools & Web Utilities](#10-developer-tools--web-utilities)

---

## 1. 🛡️ Dual-Tier Ad-Blocking & Privacy Shields

Onyx Browser features an ultra-low-latency native ad-blocking engine powered by Brave's **`adblock-rust`**, compiled into native Android shared libraries (`libadblock_bridge.so`) across all major ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`).

```
[ Web Resource Request ]
           │
           ▼
[ OnyxWebViewClient.shouldInterceptRequest ]
           │
           ├──► [ Domain Whitelist Check (In-memory cached) ] ──► Allowed
           ├──► [ Native JNI: AdBlockEngine.shouldBlock ]
           │          │
           │          ├──► Allowed ──► Direct Network Fetch
           │          └──► Blocked ──► Content-Type Safe Response:
           │                             • Image: 1×1 Transparent PNG (200 OK + CORS)
           │                             • Script: Empty JS Stream (200 OK + CORS)
           │                             • Stylesheet: Empty CSS Stream (200 OK + CORS)
           │                             • Subframe: Empty HTML Comment (200 OK)
           │                             • XHR/Fetch: 200 OK Empty Stream
           └──► [ Universal Cosmetic CSS & Scriptlet Defusers Injected ]
```

### Key Capabilities:
- **Rust NDK High Performance**: High-throughput rule matching via Bloom filters and token buckets executing in native C/Rust speed.
- **Two Protection Tiers**:
  - **Standard Protection**: Blocks tracking scripts, banner ads, tracking beacons, video interstitials, and coin miners.
  - **Aggressive Protection**: Aggressively eliminates OEM telemetry (Xiaomi, Huawei, Samsung, Vivo, Oppo), cookie consent popups (OneTrust, Cookiebot, TrustArc), third-party widgets, and behavioral heatmaps.
- **54 Brave Android Content Filters**:
  - Full catalog of 54 official Brave filter subscriptions including Cookie Notice, Annoying Distractions, Mobile App Promos, Anti-AI suggestions, YouTube Shorts/Playables/Recommendations, URL Tracking Parameters, and 35 regional language rulesets.
  - Automatic background updates compiling lists into binary FlatBuffers (`onyx_filters.bin`).
- **Resilient Blocked Responses**: Unlike primitive blockers that throw raw `HTTP 403 Forbidden` errors (which break streaming video players and crash JavaScript promises), Onyx serves type-aware 200 OK stubs with CORS headers, preserving page rendering integrity.
- **Document-Start Scriptlet Injection**: Intercepts `window.fetch`, `window.XMLHttpRequest`, and `window.WebSocket` at `document_start` before page scripts parse or execute.
- **Anti-Fingerprinting**: Spoofs generic `en-US` language headers, overrides `navigator.languages`, and normalizes canvas/audio signatures.
- **Social Media Tracker Stripper**: Dedicated toggles to strip embedded trackers from Facebook, Twitter/X, and LinkedIn.

---

## 2. 🎬 Advanced Video & Media Suite

Onyx Browser introduces a streaming-grade media architecture modeled after Brave's `brave-core` browser engine, delivering seamless background playback, true video-only Picture-in-Picture, and bidirectional media control synchronization.

### 🎧 Background Playback Keep-Alive
- **`userHitPause` State Machine**: Intercepts `HTMLMediaElement.prototype.pause` and `play` calls to distinguish between genuine user pauses and background visibility suppression. When a streaming site attempts to pause audio/video upon tab switching or screen lock, Onyx automatically overrides and resumes playback.
- **Document Visibility Spoofing**: Patches `Document.prototype.visibilityState` to permanently report `"visible"` and `hidden = false`.
- **Visibility Listener Filtering**: Monkey-patches `document.addEventListener` at `document_start` to intercept and discard `visibilitychange` and `webkitvisibilitychange` handlers registered by websites.
- **Patched YouTube `ytcfg` Experiment Flags**: Automatically neutralizes YouTube's internal `html5_picture_in_picture_blocking_*` experiment flags.
- **Android MediaSession Service (`MediaPlaybackService`)**: Persistent Android Foreground Service with lockscreen controls, notification artwork, title/artist metadata, and playback timeline progress.

### 🖼️ True Video-Only Picture-in-Picture (PiP)
- **Shadow DOM Penetration**: Streaming sites (such as YouTube and custom HTML5 web components) encapsulate the `<video>` element inside nested ShadowRoots. Onyx's PiP isolation script traverses the composed ancestor path, removing CSS `transform`, `contain`, `filter`, and `clip-path` constraints up to `<html>`.
- **Letterbox Centering**: Zeroes out browser container padding during PiP transitions and centers the video inside pure `#000000` bounds.
- **Aspect-Ratio-Corrected `setSourceRectHint`**: Directs the Android Window Manager to crop strictly to the video viewport coordinates, eliminating address bars, controls, and surrounding page chrome.
- **Interactive PiP Actions**: Android PiP window includes Previous, Play/Pause, and Next/Forward media transport controls.

### 🎛️ Floating Video Control Pill
- When any HTML5 video is detected on the page, an elevated, draggable floating control pill appears:
  1. **📥 Download**: Instant one-tap video stream detection and download.
  2. **🎧 Headphone Button**: One-tap toggle for Background Playback with live active color tinting.
  3. **📺 PiP Button**: Direct invocation of isolated Picture-in-Picture mode.
- Supports drag-and-drop repositioning anywhere along the screen bounds and persists across pause or buffering states.

### 🔄 Streaming Player API Synchronization
- Integrates with standard W3C `navigator.mediaSession.setActionHandler` (`play`, `pause`, `seekto`, `seekforward`, `seekbackward`, `nexttrack`, `previoustrack`).
- Directly bridges with YouTube's `#movie_player` JavaScript API (`playVideo()`, `pauseVideo()`, `seekTo()`, `nextVideo()`), ensuring Bluetooth headsets, smartwatches, and Android lockscreen controls stay in exact sync with web players.

---

## 3. 📥 Download Management & External Downloader Integration

Onyx combines a clean in-app downloader with smart hand-off to dedicated external Android download managers.

### Features:
- **Stream Verification**: Automatically performs non-destructive HTTP `HEAD` / partial `GET` (`bytes=0-1`) requests prior to prompting, reporting file size, MIME type, and catching HTTP 403 Forbidden or Widevine DRM restrictions before download initiation.
- **Full Session & Header Forwarding**: Extracts and passes session `Cookie` (from `CookieManager`), `User-Agent`, and `Referer` headers to downloaders to ensure authenticated downloads (cloud drives, private forums) succeed seamlessly.
- **External Downloader Smart Dispatch**:
  - Scans for installed download engines (**1DM**, **1DM+**, **1DM Lite**, **ADM**, **ADM Pro**, **FDM**, **Download Navi**, **Aria2**).
  - If a single external manager is installed: Launches directly with full parameters and forwarded headers.
  - If multiple managers are detected: Displays a clean `SelectDownloaderBottomSheet` selector.
- **Configurable Download Policies**: Set default behavior in Video Settings (Ask Every Time, Always In-App Downloader, or Always External Downloader).

---

## 4. 🔑 Passkeys, WebAuthn & Password Autofill

Experience modern, passwordless authentication with zero compromises.

### Features:
- **AndroidX Credential Manager**: Integrated with `androidx.credentials:credentials:1.3.0` and Google Play Services Auth.
- **WebAuthn Bridge (`PasskeyWebAuthnBridge`)**: Polyfills standard W3C `window.PublicKeyCredential`, `navigator.credentials.create()`, and `navigator.credentials.get()` into the WebView, translating JSON/Base64URL requests into native Android credential requests.
- **Autofill Provider Support**: Fully compatible with **Google Password Manager**, **Bitwarden**, **1Password**, **Dashlane**, and hardware **YubiKeys**.
- **Deterministic Keystore Signing**: Build pipeline signs release APKs with deterministic keystore configuration, ensuring consistent cryptographic app signatures across updates so saved Passkeys never break.

---

## 5. 🔍 Omnibox, Smart Search & QR Scanner

### Features:
- **Dedicated Full-Page Search Mode**: Tapping the address bar smoothly transitions into a distraction-free search experience with soft keyboard autofocus.
- **Active Webpage Card**: Quick action buttons beneath the search bar:
  - **🔗 Share Link**: Native Android share sheet.
  - **📋 Copy URL**: Instant clipboard copy with visual feedback.
  - **✏️ Edit URL**: Populates search bar with the current URL for instant customization.
- **Real-Time Search Suggestions**:
  - Blended suggestions querying OpenSearch APIs across 6 search engines (**Brave**, **Google**, **DuckDuckGo**, **Bing**, **Startpage**, **Yahoo**) alongside local encrypted browsing history.
  - Query append buttons (`ic_insert_query`) to customize search queries before submission.
- **Custom Search Engines with Keyword Shortcuts**:
  - Add and manage custom search engines with `%s` query parameters.
  - Keyword search shortcuts (e.g. typing `w quantum computing` searches directly on Wikipedia).
- **CameraX + ML Kit QR Scanner**: Modern camera overlay scanning URLs, Wi-Fi credentials, and contact codes directly from the search bar.

---

## 6. 🖼️ Context Menu & Multi-Engine Reverse Image Search

Long-pressing any link or image triggers Onyx's Material 3 context sheet:
- **Live Image Thumbnail Preview**: 170dp interactive image card with tap-to-expand badge.
- **Fullscreen Image Inspector (`ImagePreviewDialog`)**: Zoomable inspection with high-res save and share actions.
- **Multi-Engine Reverse Image Search**:
  - **Google Lens**
  - **TinEye**
  - **Yandex Images**
  - **Bing Visual Search**
- **Link Quick Tools**: Open in New Tab, Open in Background, Copy Link Address, Download Linked File.

---

## 7. 🎨 Pure Google Themes & Material 3 Box-Type UI

Designed strictly with classic Android XML ViewBinding (zero Jetpack Compose overhead) for instantaneous cold starts and fluid 120Hz scrolling.

### Three Theme Modes:
1. **Google Dark Mode** (`#202124`): Authentic Google Dark palette matching Chrome and google.com (`#303134` surface, `#8AB4F8` Google Blue 300 accent; avoids eye-straining pure `#000000`).
2. **Google Light Mode** (`#FFFFFF`): Clean Google Light theme with `#DFE1E5` borders and `#1A73E8` Google Blue 600 accents.
3. **System Default**: Automatically syncs with Android OS system dark theme toggles.

### UI Refinements:
- **Material 3 Box Containers**: Elevated box cards with rounded corners housing dynamic shortcut grids and browser tools.
- **Responsive Layout**: Adapts gracefully across compact phones (`max-width: 540dp`), foldables, and large Android tablets (`max-width: 760dp`).
- **Edge-to-Edge Navigation**: Dynamic `WindowInsetsCompat` handling ensuring content never clips behind status or gesture navigation bars.

---

## 8. 📑 Tab Switcher & Time-Range Data Cleaning

### Features:
- **Material 3 Tab Grid**: Visual preview cards displaying live website favicons, domain titles, and tab close buttons with swipe-to-dismiss gestures.
- **Normal & Incognito Segmented Pill**: Smooth toggle between isolated normal tabs and private sessions.
- **Battery & CPU Throttling**: Inactive tabs invoke `webView.onPause()` to halt background JavaScript timers and conserve device battery; active tab invokes `webView.onResume()`.
- **Chrome-Style Clear Browsing Data Dialog**:
  - Selectable time ranges: **Last 15 minutes**, **Last hour**, **Last 24 hours**, **Last 7 days**, **Last 4 weeks**, and **All time**.
  - Live preview calculating history items, open tabs that will close, and cookies/cache storage size.

---

## 9. 🔒 Local Encryption & Security Hardening

- **SQLCipher AES-256 Storage**: SQLite database holding tabs, history, bookmarks, and download records is encrypted with SQLCipher (`net.zetetic:android-database-sqlcipher`).
- **Hardened WebView Sandboxing**:
  - Third-party cookies blocked by default (`CookieManager.setAcceptThirdPartyCookies(false)`).
  - File scheme access restricted (`allowFileAccess = false`, `allowContentAccess = true`).
  - Geolocation, microphone, and camera access gated by explicit user consent dialogs.
- **Safe External Link Dispatch**: `launchMode="singleTask"` prevents duplicate activity stacks and safely dispatches external app links (`tg://`, `whatsapp://`, `mailto:`, `intent://`).

---

## 10. 🛠️ Developer Tools & Web Utilities

- **Offline Eruda Developer Console**: Bundled `eruda.min.js` provides mobile web developers with an interactive DOM inspector, JavaScript console, network monitor, and local storage viewer without connecting to a desktop PC.
  - **Console & Error Pre-Buffering**: Hooks `console.log/warn/error/info/debug` and `window.onerror` at `document_start` so all startup errors prior to opening DevTools are preserved and replayed.
  - **In-Memory Caching**: Eliminates repeated asset reads, providing instant sub-millisecond console initialization.
  - **Theme Synchronization**: Automatically adopts Dark or Light theme matching the user's active browser palette.
  - **Session Persistence**: Maintains active DevTools across in-tab page navigations and reloads.
- **Remote WebContents Debugging (Chrome DevTools)**:
  - Enabled via `WebView.setWebContentsDebuggingEnabled(true)`, allowing full desktop Chrome/Edge DevTools inspection (`chrome://inspect`) over USB/ADB with live DOM tree editing, network waterfall graphs, timeline profiling, and JavaScript breakpoints.
- **Logcat Console Forwarding**: Web console outputs automatically format with line number and source URL under the `[OnyxDevTools]` Logcat tag.
- **Webpage Translation**: In-app Google Web Translate bar supporting 20 languages with target language persistence.
- **In-Page Text Finder**: Interactive search bar with real-time match counts (`X/Y`) and next/previous match highlighting.
- **Desktop Site Toggle**: Instant user-agent switching to request desktop-rendered web pages.
- **Add to Home Screen**: Pins native progressive web app (PWA) launcher shortcuts with website titles and high-res favicons via `ShortcutManagerCompat`.

