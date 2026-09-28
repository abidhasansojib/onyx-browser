# Onyx Browser — Feature Status & Documentation

This document tracks all features of Onyx Browser, their current implementation status, and concise technical summaries.

### Status Legend
- `[x]` **Working** – Fully implemented, tested, and active in release builds.
- `[-]` **Known Issue / Partial** – Implemented but has a known limitation or bug being addressed.
- `[ ]` **Planned** – Not yet implemented; on the development roadmap.

---

## Master Feature List

1. [x] Native Rust Adblock Engine (`adblock-rust` compiled via NDK for all 4 ABIs)
2. [x] 54 Brave Content Filter Lists (with FlatBuffers binary caching `onyx_filters.bin`)
3. [x] Dual-Tier Adblocking (Standard vs Aggressive shields)
4. [x] Per-Site Shields & Domain Whitelist (toggle adblocking on/off per site)
5. [x] Custom Filter Rules & Subscriptions (add custom EasyList-syntax rules & URLs)
6. [x] Type-Aware 200 OK Stubs (transparent 1×1 PNG, empty JS/CSS/subframe responses)
7. [x] Anti-Fingerprinting Protections (language spoofing, canvas/audio normalization)
8. [x] Social Tracker & Cookie Notice Stripping
9. [x] Background Audio & Video Playback (Brave `userHitPause`, visibilityState spoofing, event filtering)
10. [x] Picture-in-Picture (PiP) (True video isolation, Shadow DOM traversal, letterbox centering)
11. [x] Foreground Media Service (`MediaPlaybackService` with lockscreen controls & notification)
12. [x] MediaSession & YouTube Player API Sync (Bluetooth, smartwatch, and lockscreen sync)
13. [x] In-App Download Manager (pause, resume, progress tracking, file opening)
14. [x] External Download Manager Handoff (detects 1DM, ADM, FDM; forwards cookies & headers)
15. [x] Stream Detector (sniffs audio/video stream URLs for one-tap downloading)
16. [x] Passkeys & WebAuthn Bridge (biometric login via AndroidX Credential Manager)
17. [x] Password Autofill Support (Google Password Manager, Bitwarden, 1Password)
18. [x] Full-Page Omnibox Search Mode (soft keyboard autofocus, distraction-free overlay)
19. [x] Real-Time Multi-Engine Search Suggestions (Brave, Google, DuckDuckGo, Bing, Yahoo, Startpage)
20. [x] Custom Search Engines & Keyword Shortcuts (e.g. `w <query>` for Wikipedia)
21. [x] Smart Clipboard Suggestion Card ("Link you copied" / "Text you copied" with query insert arrow)
22. [x] One-Tap Active Webpage Card (direct reload/navigate, share sheet, copy URL, edit URL)
23. [x] CameraX + ML Kit QR Code & Barcode Scanner (scanner integrated in omnibox)
24. [x] Context Menu Bottom Sheet (open in new tab, open in background, copy link, download)
25. [x] Image Preview Inspector (tap thumbnail to zoom, save image, share image)
26. [x] Multi-Engine Reverse Image Search (Google Lens, TinEye, Yandex, Bing)
27. [x] Visual Tab Switcher (grid previews, swipe-to-dismiss, close all tabs prompt)
28. [x] Incognito / Private Browsing Mode (separate in-memory cookie jar, no history logging)
29. [x] Tab Memory Optimization (suspends JS timers on inactive tabs via `onPause()`)
30. [x] Time-Range Data Cleaning (clear 15 min, 1 hr, 24 hr, 7 days, 4 weeks, or all time)
31. [x] SQLCipher AES-256 Encrypted Database (bookmarks, history, tabs, downloads encrypted on-disk)
32. [x] Bookmarks Manager (create, edit, delete, organize folders, search)
33. [x] Browsing History Manager (search history, delete individual items, clear all)
34. [x] Homepage Shortcuts Grid (customizable tiles, favicons, drag-and-drop reordering)
35. [x] User Agent Spoofer Manager (custom UA strings, presets for Chrome, Safari, Edge, Firefox)
36. [x] Accessibility Settings (search widget toggle, webpage menu "Add to Home screen" toggle, text scaling)
37. [x] Add to Home Screen (PWA launcher shortcuts via `ShortcutManagerCompat`)
38. [x] Theme System (Google Light, Google Dark `#202124`, and pure AMOLED Black)
39. [x] Hardened WebView Sandboxing (third-party cookies enabled for web auth/CAPTCHAs, blocked in Incognito, file scheme disabled, safe intent routing)
40. [x] Offline Eruda Developer Tools (bundled mobile DOM inspector, console, network monitor)
41. [x] Console & Error Pre-Buffering (captures startup logs & JS errors before DevTools opens)
42. [x] Remote USB Debugging (Chrome DevTools `chrome://inspect` over USB/ADB)
43. [x] In-Page Text Search (find in page with match count and next/previous navigation)
44. [x] Webpage Translation Bar (Google Translate integration for 20 languages)
45. [x] Desktop Site Toggle (per-tab desktop viewport switch)
46. [-] WebGL 1/2 Complex Shader Polyfills (Evan Wallace water works; older GPUs may lack hardware float texture targets)
47. [x] Adblocker Spoofing Toggle (Settings → Privacy & Shields — when enabled, returns safe 200 OK stubs and injects `adsbygoogle`, `ga`, `gtag` stubs to bypass anti-adblock walls; **OFF by default** so adblock test sites detect standard HTTP 403 blocks and report full scores)
48. [ ] Custom Userscript Manager (Tampermonkey / Violentmonkey scriptlet support)
49. [ ] Built-in Reader Mode (distraction-free text view for articles)
50. [ ] DNS-over-HTTPS (DoH) Provider Selection (Cloudflare, Quad9, AdGuard DNS)

---

## Feature Details

### 1. Adblocking & Privacy Shields
- **Rust NDK Engine**: Compiled `adblock-rust` performs token-bucket and Bloom filter matching in native C/Rust.
- **Filter Lists**: Bundles 54 official Brave filter lists and compiles them into binary FlatBuffers (`onyx_filters.bin`) for instant startup.
- **Type-Aware Responses**: Blocked resources receive valid 200 OK responses (empty JS, 1×1 transparent PNG, or blank CSS) with CORS headers to keep page scripts and media players from crashing.
- **Two Protection Tiers**:
  - *Standard*: Blocks advertisements, tracking scripts, web beacons, and cryptominers.
  - *Aggressive*: Strips OEM telemetry, third-party widgets, and cookie consent modals.
- **Domain Whitelist**: Allows users to disable shields for individual sites directly from the toolbar menu.

### 2. Media & Playback Subsystem
- **Background Playback**: Employs Brave's `userHitPause` pattern to distinguish user pauses from background tab switches. Overrides `document.visibilityState` to remain `"visible"`, intercepts `visibilitychange` listeners, and auto-resumes suppressed playback.
- **Picture-in-Picture (PiP)**: Isolates the `<video>` element across Shadow DOM boundaries, removes CSS transforms/clipping, centers the video in black letterbox bounds, and sets source rect hints for Android Window Manager.
- **Foreground Media Service**: `MediaPlaybackService` maintains persistent lockscreen playback controls, notification artwork, track title, and MediaSession actions.
- **YouTube API Sync**: Direct integration with YouTube `#movie_player` and W3C MediaSession handlers keeps Bluetooth headsets and lockscreen buttons in sync.

### 3. Downloads & External Downloaders
- **In-App Downloads**: Native background download manager handles pause, resume, progress reporting, and system notification updates.
- **External Downloader Handoff**: Detects installed third-party downloaders (1DM, ADM, FDM) and passes download URLs along with cookies, User-Agent, and Referer headers so authenticated downloads succeed.
- **Stream Sniffer**: Monitors HTML5 video/audio elements and network requests to offer quick stream download prompts.

### 4. Authentication & Security
- **Passkeys (WebAuthn)**: Polyfills `window.PublicKeyCredential` to allow passwordless biometric authentication through AndroidX Credential Manager.
- **Autofill Compatibility**: Supports Google Password Manager, Bitwarden, 1Password, and hardware security keys.
- **Anti-Bot & Social Login Integrity**: Client hints (`navigator.userAgentData`), `window.chrome`, and automation flag spoofing ensure anti-bot engines (Arkose Labs / FunCaptcha, reCAPTCHA, Turnstile, Meta Risk Engine) recognise Onyx as an authentic mobile browser. Third-party cookies are accepted for cross-origin verification iframes during authentication flows, resolving 'Confirmation failed in captcha'.
- **SQLCipher Encryption**: Uses 256-bit AES encryption (`net.zetetic:android-database-sqlcipher`) for Room database storage containing tabs, history, and bookmarks.
- **Sandboxed WebView**: Disables third-party cookies by default in Incognito, disables `file://` scheme access, and requires explicit user permission for microphone, camera, and location.

### 5. Omnibox & Search
- **Full-Page Search Overlay**: Tapping the toolbar opens a clean search screen with suggestions and clipboard detection.
- **Multi-Engine Suggestions**: Queries OpenSearch endpoints for Brave, Google, DuckDuckGo, Bing, Yahoo, and Startpage alongside local history.
- **Custom Engines**: Add any search provider with a `%s` URL template and assign single-letter keyword shortcuts (e.g. `w query` for Wikipedia).
- **QR Code Scanner**: Integrated CameraX + Google ML Kit scanner opens scanned links directly or copies barcode data.
- **Active Webpage Card**: Quick actions beneath the search bar to reload, share, copy URL, or edit the current address without retyping.

### 6. Tab Management & Data Clearing
- **Visual Tab Grid**: Responsive card grid with live favicons, page titles, close buttons, and swipe-to-dismiss.
- **Incognito Tabs**: Isolated in-memory browsing session that leaves no history, cache, or cookies on device.
- **Resource Management**: Calls `webView.onPause()` on background tabs to halt JavaScript timers and save battery, and `webView.onResume()` on the active tab.
- **Selective Data Clearing**: Time-range selection (15 minutes, 1 hour, 24 hours, 7 days, 4 weeks, all time) with live summary of items to be removed.

### 7. Developer Tools
- **Offline Mobile DevTools**: Bundles `eruda.min.js` to provide a full DOM inspector, console, network activity log, and local storage editor without a PC.
- **Pre-Buffering**: Hooks `console.*` and `window.onerror` at `document_start` so errors occurring before opening DevTools are recorded and replayed.
- **Remote USB Debugging**: `WebView.setWebContentsDebuggingEnabled(true)` enables standard Chrome DevTools inspection (`chrome://inspect`) over ADB.
- **Logcat Output**: Pipes web console messages directly to Android Logcat with tag `[OnyxDevTools]`.

### 8. Customization & Accessibility
- **Themes**: Pure Google Dark (`#202124`), Google Light (`#FFFFFF`), and pure AMOLED Black (`#000000`).
- **User Agent Spoofer**: Select from built-in presets (Chrome Mobile, Chrome Desktop, Safari iOS, Safari macOS, Edge, Firefox) or input a custom UA string.
- **Accessibility Settings**: Toggle the Search widget, toggle the "Add to Home screen" option in the webpage 3-dot menu, adjust text scaling, and force dark mode on web content.
- **Home Screen Shortcuts**: Pin progressive web app shortcuts directly to the Android launcher via `ShortcutManagerCompat`.
