# Onyx Browser — Features & Roadmap

This document tracks all features of Onyx Browser, their current implementation status, and active development roadmap.

For deep technical specifications, JNI bindings, and subsystem implementations, refer to:
- [Architecture & Core Subsystems](ARCHITECTURE.md)
- [Android WebView Engineering Guide](WEBVIEW.md)

### Status Legend
- `[x]` **Working** – Fully implemented, verified, and active in release builds.
- `[-]` **Partial / In Progress** – Implemented with ongoing refinements.
- `[ ]` **Planned** – On the roadmap for future releases.

---

## 1. Privacy, Security & Ad-Blocking

- `[x]` **Native Rust Adblock Engine**: High-performance Brave `adblock-rust` compiled via Android NDK across all 4 ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `universal`) with full privileged scriptlet permissions (`PermissionMask::from_bits(u8::MAX)`), CSP directive enforcement (`$csp`), and popup rule classification.
- `[x]` **54 Brave Content Filter Lists**: Evaluates Brave shields rules with pre-compiled FlatBuffers binary caching (`onyx_filters.bin`).
- `[x]` **Dual-Tier Adblocking & CNAME Uncloaking**: Standard vs Aggressive shields with instant dynamic reversion at document start, plus dual-pass CNAME uncloaking matching both original alias and uncloaked destination.
- `[x]` **Procedural & Cosmetic Filtering**: Complete 15-operator procedural engine (`:has-text()`, `:upward()`, `:min-text-length()`, `remove()`, etc.) and pre-parse synchronous CSS injection.
- `[x]` **Anti-Adblock Defuser & Benchmark Compatibility**: 100% pass rate on adblock test sites; type-aware response synthesis and DOM honeypot defusal.
- `[x]` **Service Worker Ad & Tracker Interception**: Background Service Worker fetch interception with full resource typing, case-insensitive header parsing, and surrogate redirection.
- `[x]` **YouTube & Streaming Video Ad Defuser**: Auto-skips ads, mutes, fast-forwards, and defuses video ad overlays at document-start; VAST/VMAP empty XML synthesis for HTML5 players.
- `[x]` **Brave Scriptlet Injection with Trusted Types CSP Immunity**: Executes Brave scriptlets (`json-prune`, `set`, `trusted-set-cookie`, etc.) reliably even on strict CSP origins with cached `__onyx_tt_policy` reuse.
- `[x]` **In-Memory LRU Decision Cache**: Sub-microsecond (<0.01µs) repeated URL and request decisions cached in Kotlin memory without JNI crossing.
- `[x]` **In-Page Visual Element Blocker (Zapper)**: Interactive element picker with touch selection, preview toggles, and persistent CSS hiding rules.
- `[x]` **Per-Site Shields & Exceptions**: Easily toggle adblocking, HTTPS upgrades, and script rules per domain from the omnibox shield icon.
- `[x]` **Unified Script Blocking & JavaScript Control**: Shields "Block Scripts" and Site Settings "JavaScript" are fully synchronized bidirectionally at both global and per-domain levels.
- `[x]` **Cookie & Site Storage Autoclear on Tab Close**: Standardized "Clear data on tab close" lifecycle and exit-time cookie/WebStorage purging.
- `[x]` **Social Media SSO Shield**: Comprehensive third-party authentication blocking (Google Sign-In, Facebook logins, Twitter, LinkedIn embeds).
- `[x]` **WebRTC IP Leak Protection**: WebRTC IP handling toggleable in both Shields & Privacy and Site Settings.
- `[x]` **DNS-over-HTTPS (DoH)**: Built-in support for Cloudflare, Google, NextDNS, and custom DoH resolver endpoints.
- `[x]` **SQLCipher AES-256 Storage Encryption**: All bookmarks, history, tabs, and downloads are encrypted on-disk with master keys in Android KeyStore.
- `[x]` **Safe Browsing & SSL Defense**: Hardened SSL error interstitials and Google Safe Browsing integration.

---

## 2. Multi-Profile & Incognito Browsing

- `[x]` **Chromium Multi-Profile Partitioning**: True physical session isolation using `ProfileStore.getOrCreateProfile("incognito")` and `WebViewCompat.setProfile()`.
- `[x]` **Ephemeral Storage & Automated Purge**: Closing all incognito tabs instantly destroys cookies, localStorage, IndexedDB, and partition caches.
- `[x]` **Biometric Incognito Protection**: Protect private tabs behind fingerprint or face authentication using AndroidX `BiometricPrompt`.
- `[x]` **Universal OAuth & Auth Grace Period**: Maintains strict cookie isolation while granting smart grace periods during authentication flows (Google, Apple, Meta, GitHub, Microsoft).
- `[x]` **Anti-Bot Window Hash (`wh`) Reflection Masking**: Hides Android Java bridges and internal properties from prototype reflection, matching authentic Chrome telemetry.
- `[x]` **WebAuthn Passkeys & Identity Provider Compatibility**: Full FIDO2 / WebAuthn Level 3 passkey support via Android CredentialManager with `CREDENTIAL_MANAGER_SET_ORIGIN` on GitHub, Google (`accounts.google.com`), and WebAuthn platforms. Authentic Chrome `userAgentData` masking removes embedded WebView signatures, enabling seamless Google Login and passkey authentication.
- `[x]` **Context-Aware Incognito Navigation**: Long-pressing a link while browsing in incognito mode opens "Open in new tab" inside a new incognito tab while omitting the redundant "Open in incognito tab" option, matching standard privacy browser ergonomics.

---

## 3. Media, Audio & Video Playback

- `[x]` **Background Audio & Video Playback**: Full background playback with Brave & Firefox Page Visibility interception, player touch intent tracking, non-user pause suppression across tab switches & screen lock, Wi-Fi & WakeLock sleep prevention, and YouTube inactivity (`_lact`) keep-alive.
- `[x]` **Picture-in-Picture (PiP) Isolation**: Non-destructive video detachment with letterbox centering and resize-loop protection.
- `[x]` **Foreground Media Service**: `MediaPlaybackService` with lockscreen notification controls, Wi-Fi lock, progress tracking, and MediaSession sync. Progress-triggered notification rebuilds are throttled (5 s service-side, 3 s bridge-side) to prevent main-thread binder IPC jank during active playback. Notification uses `CATEGORY_TRANSPORT`, `setSilent(true)`, and `PRIORITY_DEFAULT` for correct MediaStyle display across all OEMs.
- `[x]` **Universal Transparent Video Poster**: Replaces default gray video box and play icon with a transparent poster for seamless video loading.
- `[x]` **Audio Becoming Noisy Broadcast Receiver**: Automatically pauses playback when headphones are disconnected or Bluetooth disconnects.
- `[x]` **Stream Detector**: Automatically sniffs downloadable audio/video streams from websites.

---

## 4. Tabs & Navigation Architecture

- `[x]` **Visual Tab Switcher**: High-performance grid preview cards with live snapshots and domain labels.
- `[x]` **Swipe-to-Dismiss & Batch Undo**: Dismiss tabs with gestures; restore closed tabs with single or batch undo notifications.
- `[x]` **Zero Unwanted Tab Auto-Creation**: Closing the last tab cleanly transitions to the home screen without forcing an empty placeholder tab.
- `[x]` **Non-Destructive Navigation**: Hardware back button at history root minimizes the browser rather than terminating processes or losing tab state.
- `[x]` **Tab Memory Optimization**: Background tabs suspend JavaScript timers via `onPause()` to conserve RAM and battery.

---

## 5. Search & Omnibox Experience

- `[x]` **Full-Page Omnibox Search Mode**: Distraction-free search interface with automatic keyboard focus.
- `[x]` **Real-Time Multi-Engine Suggestions**: Fast query suggestions from Brave, Google, DuckDuckGo, Bing, Yahoo, and Startpage.
- `[x]` **Custom Search Engines & Keyword Shortcuts**: Add arbitrary search engines with keyword triggers (e.g., `w <query>` for Wikipedia) and cached circular logos.
- `[x]` **Smart Clipboard Suggestions**: Proactively surfaces copied URLs and search queries with one-tap insertion.
- `[x]` **Integrated CameraX + ML Kit Scanner**: Rapid QR code and barcode scanning directly from the address bar.
- `[x]` **Multi-Engine Reverse Image Search**: Long-press any image to search via Google Lens, TinEye, Yandex, or Bing.

---

## 6. Downloads & File Management

- `[x]` **In-App Multi-Threaded Download Manager**: Native downloader supporting pause, resume, progress notifications, and direct file opening.
- `[x]` **External Downloader Handoff**: Seamlessly detects and routes downloads to 1DM, ADM, or FDM with complete cookie and header forwarding.
- `[x]` **RFC 5987 / RFC 6266 Accurate Filename Resolution**: Correctly parses UTF-8 Content-Disposition headers and neutralizes generic `.bin` overrides.
- `[x]` **Headless PDF Export & Web Archive (.mht) Saving**: One-tap page saving directly to public Downloads without showing printer dialogs.
- `[x]` **Direct APK Package Installer**: Seamless installation handoff for downloaded APKs without redundant confirmation prompts.
- `[x]` **Categorical Filter Chips**: Filter downloads by All, APKs, Images, Videos, and Documents with live item counts.

---

## 7. Web Tools & Page Features

- `[x]` **In-Page Text Search**: Fast "Find in Page" with match count, highlighting, and next/previous controls.
- `[x]` **Universal In-Page Webpage Translation Engine**: CSP-immune native DOM translation supporting 45+ languages with live `MutationObserver` updates.
- `[x]` **Desktop Mode with Adaptive Viewport Scaling**: 980px layout viewport, Client Hints spoofing, and full pinch-to-zoom.
- `[x]` **Offline Eruda Developer Tools**: Bundled mobile DOM inspector, JavaScript console, network monitor, and startup log pre-buffering.
- `[x]` **Bookmarks & Bookmarklets Engine**: Create, edit, and organize bookmarks with live search and direct `javascript:...` bookmarklet execution.
- `[x]` **Smart "Open in App" Intent Prompt**: Material 3 dialog resolving native app icons and labels for deep links (`tg://`, `intent://`, etc.).
- `[x]` **Channel-Aware In-App Updater & Rolling Odometer Versioning**: Dual release channels (Release vs Pre-release) checking GitHub Releases / Pre-releases with zero-rate-limit public Atom feed fallback. Automatic rolling odometer versioning (`MAJOR.MINOR.PATCH`) increments patch up to 99 before advancing minor, and minor up to 9 before advancing major.
- `[x]` **Day-by-Day Grouped Browsing History with Website Favicons**: Grouped history cards by calendar days ("Today", "Yesterday", and formatted dates) with expandable/collapsible chevron controls and dynamic circular website favicon/logo resolution with fallback placeholder badges.

---

## 8. UI, Theming & Accessibility

- `[x]` **Theme Modes & Web Dark Mode**: Seamless switching between System, Dark, and Light themes, with algorithmic Force Dark Web Contents toggleable directly in the Theme Picker and Accessibility settings.
- `[x]` **Auto-Hide Address Bar on Scroll (Scroll-to-Hide)**: Address bar smoothly slides out of view when scrolling down to maximize screen space and slides back into view when scrolling up (with reserved status bar space preventing web content overlap), with dedicated toggle in Accessibility settings.
- `[x]` **Material 3 Site Settings & Permission Dialogs**: Clean bottom-sheet dialogs for Camera, Microphone, Location, and Notification permissions.
- `[x]` **Configurable Pull-to-Refresh**: Density-scaled pull sensitivity with user disable toggle.
- `[x]` **Homepage Shortcuts**: Customizable tile grid with drag-and-drop reordering and cached high-resolution favicons.

---

## 9. Roadmap & Planned Enhancements

- `[-]` **WebGL 1/2 Complex Shader Polyfills**: Ongoing compatibility improvements for complex WebGL shaders on older mobile GPUs.
- `[ ]` **Custom Userscript Manager**: Support for user-installed Tampermonkey / Violentmonkey scriptlets.
- `[ ]` **Built-in Reader Mode**: Distraction-free article reader with typography and theme customization.
