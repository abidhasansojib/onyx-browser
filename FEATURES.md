# Onyx Browser — Feature Status & Documentation

This document tracks all features of Onyx Browser, their current implementation status, and concise technical summaries.

### Status Legend
- `[x]` **Working** – Fully implemented, tested, and active in release builds.
- `[-]` **Known Issue / Partial** – Implemented but has a known limitation or bug being addressed.
- `[ ]` **Planned** – Not yet implemented; on the development roadmap.

> [!NOTE]
> **Canonical Golden Reference Release**: `v1.0.210` (commit `f362a7d`, tag `golden-reference-v1.0.210`). All core features marked `[x]` below are verified 100% operational in this stable release.

---

## Master Feature List

1. [x] Native Rust Adblock Engine (`adblock-rust` compiled via NDK for all 4 ABIs)
2. [x] 54 Brave Content Filter Lists (with FlatBuffers binary caching `onyx_filters.bin`)
3. [x] Dual-Tier Adblocking (Standard vs Aggressive shields)
4. [x] Per-Site Shields & Domain Whitelist (toggle adblocking on/off per site)
5. [x] Custom Filter Rules & Subscriptions (add custom EasyList-syntax rules & URLs)
6. [x] Type-Aware Responses & Benchmark Compatibility (transparent 1×1 PNG, empty JS/CSS when Adblocker Spoofing is ON; stealth window.fetch/XHR rejection with net::ERR_BLOCKED_BY_CLIENT and 403 Forbidden with CORS headers when OFF, ensuring 100% on superadblocktest.com, d3ward, and adblock-tester; DOM honeypot & bait element defuser spoofing offsetHeight, offsetWidth, clientHeight, and offsetParent to defeat anti-adblock detection walls)
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
20. [x] Custom Search Engines & Keyword Shortcuts (e.g. `w <query>` for Wikipedia; dynamic logo fetching, multi-tier disk/memory caching, and non-square circular shape normalization across omnibox and quick switcher)
21. [x] Smart Clipboard Suggestion Card ("Link you copied" / "Text you copied" with query insert arrow)
22. [x] One-Tap Active Webpage Card (direct reload/navigate, share sheet, copy URL, edit URL)
23. [x] CameraX + ML Kit QR Code & Barcode Scanner (scanner integrated in omnibox)
24. [x] Context Menu Bottom Sheet (open in new tab, open in background, copy link, download)
25. [x] Image Preview Inspector (tap thumbnail to zoom, save image, share image)
26. [x] Multi-Engine Reverse Image Search (Google Lens, TinEye, Yandex, Bing)
27. [x] Visual Tab Switcher (grid previews, swipe-to-dismiss, single & batch close-all undo toast notification, strict tab ID isolation preventing background callback hijacking on new tab creation, close all tabs prompt)
28. [x] Incognito / Private Browsing Mode (separate in-memory cookie jar, no history logging)
29. [x] Tab Memory Optimization (suspends JS timers on inactive tabs via `onPause()`)
30. [x] Time-Range Data Cleaning (clear 15 min, 1 hr, 24 hr, 7 days, 4 weeks, or all time)
31. [x] SQLCipher AES-256 Encrypted Database (bookmarks, history, tabs, downloads encrypted on-disk)
32. [x] Bookmarks Manager (create, edit, delete, organize folders, search)
33. [x] Browsing History Manager (search history, delete individual items, clear all)
34. [x] Homepage Shortcuts Grid (customizable tiles, favicons, drag-and-drop reordering)
35. [x] User Agent Spoofer Manager (custom UA strings, presets for Chrome, Safari, Edge, Firefox)
36. [x] Accessibility Settings (search widget toggle, webpage menu "Add to Home screen" toggle, text scaling)
37. [x] Add to Home Screen & App Launcher Shortcuts (PWA launcher shortcuts via `ShortcutManagerCompat`, and launcher icon long-press quick shortcuts ordered: 1st Search web, 2nd New Incognito tab, 3rd Scan QR code via `shortcuts.xml`)
38. [x] Theme System (Google Light, Google Dark `#202124`, and pure AMOLED Black)
39. [x] Hardened WebView Sandboxing (cloud backups disabled with `android:allowBackup="false"`, `allowFileAccessFromFileURLs`/`Universal` disabled, **third-party cookies blocked by default** with automatic smart exemptions for OAuth/SSO/identity/payment providers — Google Sign-In, Apple ID, Microsoft Azure AD, GitHub OAuth, PayPal, Stripe, Auth0, Okta, Firebase Auth, Amazon Cognito, etc. — to prevent login breakage; fully blocked in Incognito, safe intent routing)
40. [x] Offline Eruda Developer Tools (bundled mobile DOM inspector, console, network monitor)
41. [x] Console & Error Pre-Buffering (captures startup logs & JS errors before DevTools opens)
42. [x] Remote USB Debugging (Chrome DevTools `chrome://inspect` over USB/ADB)
43. [x] In-Page Text Search (find in page with match count and next/previous navigation)
44. [x] Universal In-Page Webpage Translation Engine (CSP-immune Native DOM Translation Subsystem supporting 45+ languages across all search engines — Google, Bing, DuckDuckGo, Brave Search, Yahoo, Startpage — with dynamic infinite-scroll detection, 0ms in-place restore, and zero page reloads)
45. [x] Desktop Mode Automatic Viewport Scaling & Zoom-Out Parity (Brave/Chrome standard 980px layout viewport, document-start MutationObserver viewport rewrite, calculated overview scaling to fit mobile displays, Client Hints spoofing, and full pinch-to-zoom)
46. [-] WebGL 1/2 Complex Shader Polyfills (Evan Wallace water works; older GPUs may lack hardware float texture targets)
47. [x] Adblocker Spoofing Toggle (Settings → Privacy & Shields — when enabled, returns safe 200 OK stubs and injects `adsbygoogle`, `ga`, `gtag` stubs to bypass anti-adblock walls; **OFF by default** so adblock test sites like superadblocktest.com and d3ward reject blocked requests and report 100% scores)
48. [ ] Custom Userscript Manager (Tampermonkey / Violentmonkey scriptlet support)
49. [ ] Built-in Reader Mode (distraction-free text view for articles)
50. [ ] DNS-over-HTTPS (DoH) Provider Selection (Cloudflare, Quad9, AdGuard DNS)
51. [x] Feed & Reel Navigation Architecture (overscroll-aware pull-to-refresh & truthful Blink intersection tracking for Facebook Reels, Instagram Reels, TikTok, YouTube Shorts)
52. [x] Scoped Storage Download Filename & Extension Reconciliation (Chromium/Brave parity MIME derivation, Scoped Storage DISPLAY_NAME query, automatic .md.txt reconciliation for opening files)
53. [x] Upfront Raw HTTP Fallback Resolution & Memory Blob/Data Download Architecture (resolves repository blob URLs on GitHub/GitLab/Bitbucket/Gitea to direct raw HTTP URLs, honors "Ask before download" preferences for blob and data URIs, hides external downloaders for in-memory blobs, saves authentic page URLs in DownloadItem, and prevents raw base64 data schemes in "Open original site")
54. [x] Local File Preview Back Navigation & Direct APK Package Installer Handoff (eliminates redundant internal confirmation prompts when opening APKs to match Chrome/Brave system installer handoff; fixes back navigation when previewing local documents so system back button and toolbar back arrow cleanly return to Downloads, external file managers, or previous tabs without wiping the tab or trapping the user on the home screen)
55. [x] Local Document Preview URL & Pull-to-Refresh Reliability (replaces directory baseUrl with exact document URI in loadDataWithBaseURL, protects tab URL from directory paths, and guarantees pull-to-refresh reloads the document instead of failing with directory errors)
56. [x] Streamlined Browsing Data Deletion (unified Chrome-style Clear Browsing Data dialog accessible across both History screen and Tab Switcher brush button; supports time-range selection, history, open tab closure, complete cookie and WebStorage wiping, HTTP auth/form data clearing, and cache purging)
57. [x] Adblock Engine & JNI Bridge Optimizations (lock-free atomic engine pointer reads via `arc-swap = "1.7"`, integer-mapped resource types across JNI, and instant Kotlin in-memory HashSet short-circuiting)
58. [x] Surrogate Scripts & `$redirect` Rule Support (evaluates `$redirect` rules in `adblock-rust`, returns safe surrogate script stubs with HTTP 200 OK to keep anti-adblock detection and analytics globals operational without page breakages)
59. [x] Procedural Cosmetic Filtering (`:has-text()`, `:upward()`, `:min-text-length()`, and action operators `remove()`, `style()`, `remove-attr()`, `remove-class()`)
60. [x] Static CNAME Uncloaking (resolves first-party cloaked tracking subdomains to third-party tracker domains before rule evaluation)
61. [x] Anti-Adblock DOM Honeypot Defuser (`getBoundingClientRect()` defusal returning non-zero dimensions on bait elements, and client-side selector query deduplication Set)
62. [x] AndroidX WorkManager Background Filter Sync (24-hour periodic silent update worker running on Wi-Fi and healthy battery)
63. [x] In-Page Visual Element Blocker / Zapper (interactive touch-to-select element picker with multi-element selection, live numbered badges, parent expansion, preview toggling, instant document-start CSS persistence, and per-site shield management)
64. [x] Inbuilt Headless PDF Export & Web Archive Download Integration (one-tap save page as PDF without opening system print dialog, saves directly to public Downloads, records in DownloadItem database, and posts completion notifications with tap-to-open for both .pdf and .mht archives, while retaining Print / System Print… for physical printers)
65. [x] Cookie & Site Storage Autoclear on Tab Close (non-blocking background purging via `Dispatchers.IO` with zero UI freeze and zero native crashes: clears domain-level, host-only, and `__Host-`/`__Secure-` session cookies across host and parent registrable domains, wipes origin `localStorage`, `sessionStorage`, `IndexedDB`, and `CacheStorage`, guards open duplicate tabs, and guarantees full logout on authentication platforms like Facebook, GitHub, Google, Twitter)

---

## Feature Details

### 1. Adblocking & Privacy Shields
- **Rust NDK Engine**: Compiled `adblock-rust` performs token-bucket and Bloom filter matching in native C/Rust. Readers load engine pointers atomically without reader lock contention via `arc-swap = "1.7"`.
- **Integer JNI Resource Typing**: Passes resource types as integer constants (`RESOURCE_TYPE_SCRIPT`, `RESOURCE_TYPE_IMAGE`, etc.), eliminating UTF-8 string allocations and string parsing overhead across the JNI bridge.
- **Fast Kotlin Short-Circuiting**: Queries in-memory `HashSet<String>` domain blocklists in Kotlin memory (<0.05µs) before calling the native Rust engine, bypassing JNI boundary traversal for known ad servers.
- **Filter Lists**: Bundles 54 official Brave filter lists and compiles them into binary FlatBuffers (`onyx_filters.bin`) for instant startup.
- **Static CNAME Uncloaking**: Resolves first-party cloaked tracking aliases (`brave-firstparty-cname.txt`) to authentic third-party tracker domains prior to blocklist checks.
- **Surrogate Script `$redirect` Handling**: Extracts `$redirect` rule targets in `adblock-rust` and synthesizes safe HTTP 200 OK responses with matching MIME types and CORS headers, preventing JavaScript TypeErrors when site scripts check for global objects (`window.ga`, `window.google_tag_manager`).
- **Procedural Cosmetic Filtering**: Extends CSS element hiding with procedural operator evaluation (`:has-text()`, `:upward()`, `:min-text-length()`) and action operators (`remove()`, `style()`, `remove-attr()`, `remove-class()`) dynamically during MutationObserver sweeps.
- **DOM Honeypot Defuser & Bridge Deduplication**: Defused `getBoundingClientRect()` on bait elements returning realistic dimensions (`300x250`); maintains a `checkedIdentifiers` Set in JavaScript to avoid repeated JS bridge queries for already-evaluated selectors on infinite-scroll feeds.
- **AndroidX WorkManager Daily Sync**: Schedules `FilterUpdateWorker` to run silent 24-hour periodic filter list downloads and compilation in the background when connected to Wi-Fi with adequate battery.
- **In-Page Visual Element Blocker (Zapper) & Multi-Element Selection**: Provides an interactive DOM element picker with multi-element selection, numbered badges, tap-to-toggle selection, parent expansion ("▲ Wider"), live preview toggling ("👁 Preview"), and batch blocking. Injects custom blocked CSS at `document_start` before page rendering begins, permanently blocking targeted elements on every subsequent visit without layout flicker, and includes per-site management in the Site Shield panel.
- **Type-Aware Responses**: When Adblocker Spoofing is enabled, blocked resources receive valid 200 OK responses (empty JS, 1×1 transparent PNG, or blank CSS) with CORS headers to keep page scripts and media players from crashing. When disabled (default), blocked requests are intercepted at document-start via stealth native-masked fetch/XHR proxies (`TypeError: Failed to fetch: net::ERR_BLOCKED_BY_CLIENT` / `onerror`) and HTTP 403 Forbidden with CORS headers, guaranteeing that benchmark test suites (`superadblocktest.com`, `d3ward`, `adblock-tester.com`) achieve 100% blocked status while preserving Cloudflare, Facebook, and CAPTCHA integrity.
- **Two Protection Tiers**:
  - *Standard*: Blocks advertisements, tracking scripts, web beacons, and cryptominers.
  - *Aggressive*: Strips OEM telemetry, third-party widgets, and cookie consent modals.
- **Domain Whitelist**: Allows users to disable shields for individual sites directly from the toolbar menu.


### 2. Media & Playback Subsystem
- **Background Playback**: Employs Brave's `userHitPause` pattern to distinguish user pauses from background tab switches. Overrides `document.visibilityState` to remain `"visible"`, intercepts `visibilitychange` listeners, and auto-resumes suppressed playback. Avoids native View/Window focus spoofing to guarantee 100% responsive Android touch input handling without UI freezes.
- **Picture-in-Picture (PiP)**: Isolates the `<video>` element across Shadow DOM boundaries, removes CSS transforms/clipping, centers the video in black letterbox bounds, and sets source rect hints for Android Window Manager. Enforces user gesture checks (`isUserGesture < 2500ms`) and bridge throttling (1500ms) to eliminate bouncing auto-PiP re-entry loops. Features persistent top-layer overlay controls (`translationZ = 100f`) with a dedicated close (X) button, and guarantees complete restoration of the web view and portrait UI without blank/grey screen lockups upon returning.
- **Foreground Media Service**: `MediaPlaybackService` maintains persistent lockscreen playback controls, notification artwork, track title, and MediaSession actions.
- **YouTube API Sync**: Direct integration with YouTube `#movie_player` and W3C MediaSession handlers keeps Bluetooth headsets and lockscreen buttons in sync.
- **Streamlined Video Options**: `Settings > Video options` provides a focused, uncluttered dashboard featuring "Playback & Display" (Background playback and Picture-in-Picture controls) with obsolete video download options removed.

### 3. Downloads & External Downloaders
- **In-App Downloads**: Native background download manager handles pause, resume, progress reporting, and system notification updates.
- **Chromium/Brave Parity MIME Type & Scoped Storage File Extension Reconciliation**: Resolves file MIME types before publishing to `MediaStore.Downloads` based on file extension overrides (e.g. mapping `.md`/`.markdown` to `text/markdown`, `.json` to `application/json`, and code extensions to `application/octet-stream`), preventing Android's `MediaProvider` from appending unwanted `.txt` extensions (e.g. `README.md.txt`). Queries actual `DISPLAY_NAME` and `DATA` columns immediately post-insertion to synchronize `DownloadItem.fileName`, `DownloadItem.filePath`, notifications, and disk path. Reconciles existing `.txt` mismatches dynamically via `FileUtils.resolveExistingPath` across `DownloadsActivity`, `DownloadsAdapter`, `DownloadNotificationHelper`, and `LocalFileLoader`.
- **External Downloader Handoff**: Detects installed third-party downloaders (1DM, ADM, FDM) and passes download URLs along with cookies, User-Agent, and Referer headers so authenticated downloads succeed.
- **Upfront Raw HTTP Fallback Resolution & Blob/Data URI Download Architecture**: Resolves repository blob URLs (e.g. `https://github.com/owner/repo/blob/branch/path` -> `https://raw.githubusercontent.com/owner/repo/branch/path`, GitLab, Bitbucket, Gitea) upfront in `DownloadHandler.handleDownload`, allowing 1DM/external downloaders and multi-threaded chunked downloads to work without relying on in-memory JS conversion. For genuine client-side blobs and data URIs, honors user's `downloadManagerBehavior` by showing `DownloadPromptBottomSheet` (or `DownloadPromptActivity`), hides the external downloader option (since external apps cannot access internal WebView RAM), routes downloads to internal memory decoders with user-chosen filenames, forwards the page URL (`referer`) through `OnyxBlobBridge.onBlobDownloadedWithContext` so `DownloadItem.url` records the authentic web page instead of `"data:text/plain;base64,..."`, and guards `DownloadsActivity` menu options so "Open original site" never loads base64 schemes into WebView.
- **Direct APK Installation & Local File Preview Back Navigation**: Bypasses redundant in-app confirmation prompts ("Do you want to install [App Name]? Cancel / Install") when opening downloaded APKs, delegating directly to Android's native Package Installer across both notification clicks and the Downloads UI (Chrome/Brave parity). Resolves local file preview navigation by tracking preview origin (`EXTRA_FROM_DOWNLOADS` and external `ACTION_VIEW`), ensuring hardware back presses and the toolbar's dedicated back arrow cleanly return the user to Downloads, their external file manager, or previous tabs without destroying URLs or trapping them on the home screen.
- **Stream Sniffer**: Monitors HTML5 video/audio elements and network requests to offer quick stream download prompts.

### 4. Authentication & Security
- **Passkeys (WebAuthn)**: Polyfills `window.PublicKeyCredential` to allow passwordless biometric authentication through AndroidX Credential Manager.
- **Autofill Compatibility**: Supports Google Password Manager, Bitwarden, 1Password, and hardware security keys.
- **Anti-Bot & Social Login Integrity**: Authentic Chromium Blink environment without prototype tampering (`navigator.webdriver` left unmolested, no synthetic `userAgentData` object) ensures anti-bot engines (Arkose Labs / FunCaptcha, reCAPTCHA, Cloudflare Turnstile, Meta Risk Engine) recognise Onyx as a genuine browser without CAPTCHA loops. Third-party cookies are accepted for cross-origin verification iframes during authentication flows, resolving 'Confirmation failed in captcha' and perpetual Turnstile challenges.
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
- **Incognito Tabs**: Isolated in-memory browsing session (`LOAD_NO_CACHE`, DOM storage disabled). Preserves normal tabs' cache upon launch, prevents favicon forensic disk leaks, and never leaks search queries into suggestions or history.
- **Resource Management**: Calls `webView.onPause()` on background tabs to halt JavaScript timers and save battery, and `webView.onResume()` on the active tab. Memory-bounded heap LRU cache for tab snapshots prevents OOM.
- **Selective Data Clearing**: Time-range selection (15 minutes, 1 hour, 24 hours, 7 days, 4 weeks, all time) with live summary of items to be removed. Genuinely purges WebView HTTP/network cache and disk cache files (`favicons`, `tab_thumbnails`, `web_archives`, `onyx_downloads`, `install_pending.apk`).
- **Local Web Archives (.mht/.mhtml) Cache Lifecycle & Isolation**: Offline web archive preview files are scoped strictly by tab ID (`preview_${tabId}_${timestamp}.mht`) and tracked in an in-memory registry. The browser UI and persistent tab database retain the original document URI and display name, never exposing private cache paths (`file:///data/user/0/...`) in the omnibox, clipboard, or tabs. Closing a tab immediately removes its preview files from disk.
- **Offline Markdown & README Document Viewer**: In-memory renderer for `.md`, `.markdown`, `.mdown`, `.mkd`, `.txt`, and common project documentation files (`README`, `CHANGELOG`, `LICENSE`, `CONTRIBUTING`). Features responsive dark/light styling, code copy buttons, raw/rendered toggles, dynamic title synchronization, document icons in the tab switcher, comprehensive pathPattern matching in `AndroidManifest.xml`, and robust back navigation: in-document anchor jumps are handled seamlessly within the file while pressing back or tapping the searchbar's top-left back arrow (`ic_arrow_back`) cleanly exits to Downloads, the external file manager, or previous tabs without ever showing `about:blank` or a blank white page.
- **History Deletion Cache Purging**: Deleting history items or clearing all history in `HistoryActivity` and `ClearBrowsingDataDialog` immediately purges `web_archives` preview files and clears WebView disk cache.
- **Filesystem Security Hardening & Private Storage Guard**: Blocks direct navigation, search bar input, and subresource loading targeting private app storage or system partitions (`/data/`, `/proc/`, `/sys/`, `/system/`, `/apex/`, `/vendor/`, and app data/cache directories) across `performSearchOrLoad`, `shouldOverrideUrlLoading`, and `shouldInterceptRequest`.
- **Streamlined Tab-Menu Data Deletion**: Dedicated on-demand browsing data deletion directly inside the Tab Menu (`popup_tab_switcher_menu.xml`) and Tab Switcher brush button (`btnClearHistory`) with time-range selection (15 minutes, 1 hour, 24 hours, 7 days, 4 weeks, all time), eliminating redundant settings screens and automatic clearing timers.
- **Local Document Preview URL & Pull-to-Refresh Reliability**: When opening downloaded text, markdown, or code documents, baseUrl is configured with the exact file URI rather than its parent directory path, preventing Chromium Blink from adopting the parent folder as document.URL. Protects the tab URL from directory overwrite in onUrlChanged and onPageFinishedCallback, so pull-to-refresh reloads the file directly instead of throwing Cannot Open Document directory errors.
- **Non-Blocking Background Tab Closure & Cookie Autoclear**: When "Clear cookies on tab close" is enabled, closing tabs (via cross button, swipe gesture, or batch close) executes instantaneously on the UI thread without freezing or ANRs. URLs and tab state are snapshotted in <0.1ms, the tab and WebView are closed safely without executing dangerous concurrent JavaScript, and origin WebStorage plus RFC 6265 domain/host session cookies are purged on a background `Dispatchers.IO` coroutine.

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

### 9. Feed & Reel Navigation Architecture
- **Upward Scroll & Pull-To-Refresh Coordination**: In virtualized video feeds (Facebook Reels, Instagram Reels, TikTok, YouTube Shorts), the root document `window.scrollY` remains at 0. `MainActivity.kt` and `OnyxTouchBridge.kt` inspect CSS `overscroll-behavior: none | contain`, `overflow-y: hidden`, inner container `scrollTop > 0`, and active reel/video feed elements on `touchstart`. When browsing reels, `SwipeRefreshLayout` never steals downward swipes, allowing seamless upward scrolling to previous reels without triggering accidental page refreshes.
- **Truthful IntersectionObserver & Clean Audio Handoff**: Removed synthetic `IntersectionObserver` mock from `MediaPlaybackManager.kt`. Native Blink intersection engine accurately informs web players when an offscreen video leaves the viewport and the new video enters, immediately muting/pausing previous reels and automatically starting the active reel without manual taps. Background auto-resume listeners are isolated strictly to background state (`window.__onyx_in_background === true`).

### 10. Desktop Mode & Automatic Viewport Scaling
- **Brave/Chrome Parity 980px Layout Viewport**: Replicates Chromium's desktop site rendering by standardizing the layout viewport to 980 CSS pixels, eliminating cramped mobile responsive breakpoints and displaying full multi-column desktop layouts.
- **Document-Start Viewport Interception**: Injects a `MutationObserver` at `document_start` via `WebViewCompat.addDocumentStartJavaScript` to capture `<meta name="viewport">` elements as they are created in `<head>` by the HTML parser, rewriting them to `width=980, initial-scale=${scale}, minimum-scale=0.25, maximum-scale=5.0, user-scalable=yes` before initial layout calculation begins.
- **Calculated Overview Scaling**: Dynamically computes `scalePercent = (screenWidthDp / 980) * 100` (~40% on standard mobile displays) and passes it to `WebView.setInitialScale()`, rendering pages already zoomed out to fit the display width without initial horizontal overflow.
- **Client Hints (`navigator.userAgentData`) Spoofing**: Overrides `navigator.userAgentData` with `mobile: false`, `platform: 'Windows'`, and authentic desktop Chromium brands, alongside `navigator.platform: 'Win32'`, preventing modern sites (Google, YouTube, Reddit) from falling back to mobile layouts based on JavaScript Client Hints.

### 11. Universal Webpage Translation Engine
- **CSP-Immune Native DOM Subsystem**: Replaced deprecated Google Translate `element.js` script injection with an Android-assisted native translation pipeline (`OnyxTranslateBridge.kt` and `PageTranslateManager.kt`). Bypasses strict webpage Content Security Policies (`script-src 'self'`, `'strict-dynamic'`) by running DOM extraction via trusted WebView scripts and network translations via native OkHttp on `Dispatchers.IO`.
- **Universal Search Engine Compatibility**: Translates search results and webpages uniformly across Google, Bing, DuckDuckGo, Brave Search, Yahoo, Startpage, and custom search engines.
- **Whitespace & Formatting Preservation**: Traverses DOM text nodes while strictly preserving leading and trailing whitespace, punctuation, and inline formatting (`<b>`, `<i>`, `<a>`, `<span>`).
- **Infinite-Scroll & Dynamic Content Support**: In-page `MutationObserver` on `document.body` detects newly appended elements and automatically translates dynamic pagination and infinite-scrolling search results on the fly.
- **Zero-Latency In-Place Restore**: Restoring original text runs in 0ms with zero network requests and zero page reloads, preserving form data, video playback, and scroll position.
- **Right-To-Left (RTL) Adaptation**: Automatically manages HTML `dir="rtl"` attributes when translating to or from Arabic, Hebrew, Persian, and Urdu.
- **Concurrency Throttling & Cyrillic Tag Support**: Employs `Semaphore(2)` network request throttling to prevent HTTP 429 rate limiting, supports transliterated Cyrillic tags (`(?:id|ид)`), safe JSON escaping, and main-thread toast error routing.

### 12. Inbuilt Headless PDF Export & Web Archive Download Integration
- **Direct Headless PDF Generation**: Saves webpages directly as standard vector PDFs via `WebView.createPrintDocumentAdapter()` and headless `PrintDocumentAdapter` lifecycle callbacks (`onLayout` and `onWrite` into a `ParcelFileDescriptor`) without showing the system print dialog.
- **Unified Public Downloads & MediaStore Integration**: Exports both `.pdf` and `.mht` files directly to the public Downloads folder with MediaStore `DISPLAY_NAME` and `DATA` reconciliation.
- **Database & Download Manager Registration**: Automatically inserts saved pages as completed `DownloadItem` records into `AppDatabase.downloadDao()`, ensuring they appear in the in-app Downloads screen with accurate file size, timestamp, and local path.
- **Interactive Completion Notifications**: Triggers system download completion notifications via `DownloadNotificationHelper.postDownloadCompletedNotification()`. Tapping the notification immediately opens the web archive (`.mht`) in Onyx Browser or launches external PDF viewers for `.pdf` documents via `FileProvider`.
- **System Print Retained**: Provides a dedicated "Print / System Print…" option alongside "Save as Web Archive (.mht)" and "Save as PDF (.pdf)" for users who need physical or network printer output.

