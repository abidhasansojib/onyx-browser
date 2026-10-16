# Onyx Browser — Architecture & Core Subsystems

This document provides a comprehensive technical guide to Onyx Browser's internal architecture, native subsystems, Chromium WebView hardening, and privacy engines.

---

## 1. High-Level Architecture Overview

Onyx Browser (`com.onyx.browser`) is built with a native Android presentation layer, a native Rust content-filtering engine, and a hardened Android System WebView rendering engine.

```
┌─────────────────────────────────────────────────────────────┐
│                    Native Android UI Layer                  │
│       Activities, ViewBinding XML, Material 3 Theming       │
├──────────────────────────────┬──────────────────────────────┤
│      Browser State & Data    │       Media & Background     │
│   SQLCipher Encrypted Room   │   Foreground Media Service   │
│   StateFlow Preferences      │   MediaSession Lockscreen    │
├──────────────────────────────┴──────────────────────────────┤
│                 Hardened Chromium WebView                   │
│   Multi-Profile Isolation (Normal / Incognito Partitioning)  │
│   Document-Start Scriptlets & Anti-Bot Masking Bridges       │
│   Network-Level Request Interception (shouldInterceptRequest)│
├─────────────────────────────────────────────────────────────┤
│               Native Rust Content Blocking Engine           │
│   `libadblock_bridge.so` (adblock-rust compiled via NDK)    │
│   FlatBuffers Binary Rule Cache (`onyx_filters.bin`)        │
│   Kotlin LRU Decision Cache (<0.01µs repeat lookups)        │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Core Subsystems

### 2.1. Ad-Blocking Subsystem (`OnyxWebViewClient` & `AdBlockEngine`)

- **Pure Filter-List Evaluation & Kotlin LRU Decision Cache**:
  - All ad, tracker, popunder, and clickjack blocking decisions are driven 100% by filter lists (54 compiled Brave filter lists + EasyList) evaluated in native `adblock-rust`.
  - High-performance thread-safe in-memory LRU Decision Caches (`requestDecisionCache` with 8,192 entries and `checkUrlCache` with 4,096 entries) in `AdBlockEngine.kt` ensure `<0.01µs` repeat lookups in Kotlin memory without crossing the JNI boundary.
  - Cache entries are automatically invalidated via `AdBlockEngine.clearDecisionCache()` whenever shields, blocking levels, or whitelists are modified in `BrowserPreferences`.

- **Dynamic Blocking Level & Zero Stale Baking (`AdBlockDocumentStart.kt`)**:
  - The blocking level is queried dynamically at document start via `window.OnyxShieldBridge.getBlockingLevelForDomain()` rather than statically baking blocking levels into `WebViewCompat.addDocumentStartJavaScript`.
  - Aggressive consent banner rules reside in `#onyx-aggressive-banners` and are dynamically attached or detached via `updateCosmeticBannerStyles(lvl)`, ensuring instant reversion when toggled back to Standard.

- **On-Demand Filter Fetching & First-Run Seeding (`FilterListManager`)**:
  - Missing filter lists are downloaded on-demand into `filter_cache/` when toggled in Content Filters settings or during app startup via `FilterListManager.ensureDefaultFiltersCached()`.
  - Downloaded rules are merged and compiled into `onyx_filters.bin` and the active in-memory Rust engine, scaling rule coverage from base EasyList (~35,500) to full multi-list protection (~135,000+ rules).
  - Filter signatures (`compiledFilterSignature`) trigger binary recompilation on startup when subscriptions change. Background updates use an `AtomicBoolean` mutex to prevent concurrent file conflicts, and `recompileFilters` executes in a `NonCancellable` context.

- **Video Ad Cosmetic Selector Preservation**:
  - Refined `safeSelectors` preserves selectors with ad, banner, sponsor, promo, overlay, popup, and preroll keywords while protecting bare `<video>`/`<audio>` tags and generic media containers, restoring cosmetic ad hiding on streaming sites.

- **Scoped Auth & Login Context Bypass**:
  - Confines `/login/` deactivations and Meta auth parameter bypasses strictly to authentication/challenge domains, ensuring normal websites with `/login/` in their paths retain active shields and ad-blocking.

- **Scoped Social Tracker Evaluation**:
  - Third-party Facebook SDK scripts (`connect.facebook.net`, `graph.facebook.com`) on non-Meta websites undergo native adblock filter evaluation unless the user is actively in an OAuth flow or on a Meta context.

- **Static CNAME Uncloaking (`AdBlockDomainManager`)**:
  - Resolves cloaked first-party subdomains (e.g. Adobe Omniture, Criteo, Branch, AppsFlyer) to their real third-party tracker hosts before rule evaluation.

- **Surrogate Redirect (`$redirect`) & URL Rewrite (`$rewrite`) Handling**:
  - Evaluates `$redirect` surrogate targets and `$rewrite` query/URL cleaning rules natively. Script resources serve safe JavaScript stubs (`google-analytics_analytics.js`, `googletagmanager_gtm.js`, `adsbygoogle.js`, `noopjs`) with HTTP 200 OK so web applications do not crash with `TypeError`.
  - Rewritten URLs (e.g., query stripping / tracking parameter removal) are redirected or served as synthesized safe data.

- **CSP Directives Extraction**:
  - Native bridge exposes `getCspDirectivesNative` to query `engine.get_csp_directives(&request)` and enforce Content Security Policies declared by filter rules.

- **Document-Start Synchronous Injection (`OnyxShieldBridge`)**:
  - Pulls domain-specific cosmetic CSS and Brave scriptlets synchronously via `OnyxShieldBridge` at `document_start` before initial DOM parsing begins. Injects `<style id="onyx-domain-cosmetic">` to eliminate layout flicker and executes Brave scriptlets across all main frames and subframes before any inline or external scripts run.

- **Full Brave Procedural Filter Engine**:
  - Complete port of Brave's `procedural_filters.ts` featuring all 15 procedural operators (`css-selector`, `has-text`, `min-text-length`, `matches-attr`, `matches-property`, `matches-css`, `matches-css-before`, `matches-css-after`, `matches-media`, `matches-path`, `upward`, `xpath`, `has`, `not`, `contains`) with fast-path optimizations for media queries and URL paths, coupled with action operators (`style`, `remove`, `remove-attr`, `remove-class`).

- **Response Synthesis (`createBlockedResponse`)**:
  - *Standard Mode (Spoofing OFF)*: Returns HTTP 403 Forbidden with CORS headers for third-party ad resources.
  - *Adblocker Spoofing Mode (Spoofing ON)*: Synthesizes type-aware safe HTTP 200 OK stubs with CORS headers (1×1 transparent PNG for images, empty JS for scripts, empty CSS for stylesheets, HTML comment for subframes) to bypass aggressive anti-adblock detection walls.

- **YouTube & Streaming Video Ad Defuser**:
  - `AdBlockDocumentStart.kt` injects an active defuser via `MutationObserver` and periodic checks monitoring `#movie_player`, `.html5-video-player`, and `ytm-mobile-player` for `.ad-showing` / `.ytp-ad-showing`.
  - Instantly mutes video, boosts playback rate to 16x, fast-forwards to end of ad, auto-clicks skip buttons (`.ytp-ad-skip-button`, `.ytp-ad-skip-button-modern`), and hides overlay banners.
  - Requests matching VAST, VMAP, IMA SDK, or video ad tags return valid empty VAST XML (`<?xml version="1.0" encoding="UTF-8"?><VAST version="3.0"/>`) with HTTP 200 OK. HTML5 video players (JWPlayer, VideoJS, FluidPlayer) skip ads immediately without triggering player error screens or halting stream playback.

- **Trusted Types CSP Immunity for Brave Scriptlets**:
  - On sites serving `require-trusted-types-for 'script'` CSP (such as YouTube), `Function()` and `eval()` throw `TypeError`. Both `AdBlockDocumentStart.kt` and `OnyxWebViewClient.kt` create an early Trusted Types policy (`onyx-shield-policy`) with fallback to DOM `<script>` injection, ensuring `json-prune`, `set`, and all Brave scriptlets execute reliably without being dropped.

---

### 2.2. Media & Playback Subsystem (`MediaPlaybackManager`, `MediaPlaybackBridge`, and `MediaPlaybackService`)

- **Transparent Video Poster (`OnyxWebChromeClient`)**:
  - Overrides `getDefaultVideoPoster()` to return a cached 1×1 transparent ARGB_8888 bitmap, universally removing Android WebView's default dark-gray box with embedded black play arrow (`ic_media_embed_play.png`) across all websites.

- **Muted Looping & Decorative Visual Filtering (`isQualifyingMedia`)**:
  - Excludes muted looping decorative animations (`elem.loop && (elem.muted || elem.volume === 0)`) and muted videos without user controls from qualifying media. Background hero visuals play silently without triggering foreground notifications, MediaSession takeovers, or AudioFocus races.

- **Strictly Guarded PiP Restoration & Resize Protection**:
  - `restoreVideoFromPipScript` execution in `onResume()` and `onWindowFocusChanged()` is strictly guarded by `justExitedPip || isCurrentlyInPip` in `MainActivity`.
  - The script itself verifies `__onyx_pip_style` and active PiP DOM targets before dispatching `resize` events, completely eliminating synthetic resize loops that cause responsive navigation components (e.g. GitHub toolbar) to oscillate between dropdown and button rows.

- **Clean Foreground/Background Media Service Handshake**:
  - In the foreground, Android System WebView retains 100% native control over playback and MediaSession without focus collisions.
  - `MediaPlaybackService` holds both `PARTIAL_WAKE_LOCK` and `WifiLock` (low latency / high performance) to completely prevent CPU suspension and Wi-Fi chip sleep during long background playback sessions with screen off. Separate PendingIntent request codes prevent SystemUI action collisions.
  - `MediaPlaybackService` is launched on background transition or tab-switch away from an active media tab, and `outgoingWebView.resumeTimers()` maintains JavaScript decoding on detached WebViews.

- **Partitioned Media Cookies & CDN Stream Playback (Brave Parity)**:
  - Automatically permits third-party cookies for media subresources, video stream CDNs (such as HLS `.m3u8` playlists and segments), and embedded player iframes (e.g. `ravok.buzz`, `fireplayer.stream`), preventing HTTP 403 Forbidden on token/cookie-authenticated streams.

- **Brave & Firefox `userHitPause` Architecture & Auto-Resume**:
  - Sets `HTMLMediaElement.prototype.userHitPause = true` only upon genuine user player touch interactions (`window.__onyx_last_player_touch < 2500`) or explicit bridge pause commands (`__onyx_allow_explicit_pause`), and `false` on `play`.
  - When web pages, tab switches, or OS blur events trigger auto-pause without explicit user intent (`!element.userHitPause`), Onyx automatically re-invokes `play()` and suppresses bridge pause callbacks to sustain seamless playback.

- **Universal Page Visibility Interception & YouTube Keep-Alive**:
  - Capturing `visibilitychange` and `webkitvisibilitychange` listeners intercept visibility events with `stopImmediatePropagation()` before website scripts receive them.
  - `Document.prototype.visibilityState` returns `'visible'` and `Document.prototype.hidden` returns `false` when background playback is active.
  - YouTube mobile tracks user inactivity via `window._lact`; Onyx refreshes `_lact` every 30 seconds to prevent "Video paused. Continue watching?" interruptions, and automatically re-binds media listeners on SPA page transitions (`yt-navigate-finish`, `yt-page-data-updated`).

- **Audio Becoming Noisy Broadcast Protection (Brave Parity)**:
  - Registers a system `BroadcastReceiver` for `AudioManager.ACTION_AUDIO_BECOMING_NOISY`. When wired headphones are unplugged or Bluetooth audio disconnects during media playback, the service immediately pauses playback and updates lockscreen notification controls.

- **Picture-in-Picture (PiP) Isolation**:
  - Traverses Shadow DOM and composed ancestor trees to remove CSS transforms and clipping constraints, isolating active `<video>` elements non-destructively without hiding sibling DOM nodes.

---

### 2.3. Chromium Multi-Profile Isolation (Normal vs Incognito)

- **Profile Partitioning**:
  - `OnyxWebView` assigns profile-scoped cookie jars and WebStorage via `ProfileStore.getOrCreateProfile("incognito")` and `WebViewCompat.setProfile()`. Normal and incognito tabs have 100% isolated cookies, localStorage, IndexedDB, and cache.

- **Deferred Cookie Configuration**:
  - `applyCookieSettings()` is executed strictly after profile assignment, preventing incognito tabs from binding to the default profile.

- **Automated Lifecycle Purge**:
  - Closing all incognito tabs triggers `TabManager.purgeIncognitoProfile()`, wiping all incognito cookies, deleting WebStorage data, and destroying the incognito profile partition.

- **Universal OAuth Grace Period**:
  - Third-party cookies are temporarily permitted on the active profile during authentication and CAPTCHA handshakes (Google, Apple, Microsoft, Meta, Arkose), ensuring logins succeed in both normal and incognito modes.

---

### 2.4. Anti-Bot, CAPTCHA & Authentication Integrity (Arkose Labs & Meta Parity)

- **`fbsbx.com` & Challenge Domain Isolation**:
  - Meta serves Arkose MatchKey / FunCaptcha challenges inside an iframe hosted at `https://www.fbsbx.com/captcha/arkose/iframe/` connecting to `meta-api.arkoselabs.com`.
  - All document-start polyfills and monitoring scripts (`DevToolsManager`, `WebGLCompatibilityBridge`, `OnyxTouchBridge`, `MediaPlaybackManager`, `DesktopModeManager`) comprehensively exempt `fbsbx.com`, `facebook.net`, `fbcdn.net`, and challenge paths (`/captcha/`, `/checkpoint/`, `/fc/`).

- **Window Hash (`wh`) Reflection Masking (`ChromeEnvironmentBridge`)**:
  - Arkose Labs computes a cryptographic Window Hash (`wh = MurmurHash3-128(Object.getOwnPropertyNames(window).sort().join('|'))`).
  - Injected Android Java bridge properties (`OnyxShieldBridge`, `PasskeyWebAuthnBridge`, etc.) and `__onyx*` variables are hidden from `Object.getOwnPropertyNames`, `Object.keys`, and `Reflect.ownKeys` on `window` and `window.__proto__` using native-masked functions (`function () { [native code] }`), ensuring the resulting hash matches 100% authentic mobile Chrome.

- **Console & WebGL Prototype Protection**:
  - Anti-bot engines verify `console.log.toString() === "function log() { [native code] }"` and profile WebGL/Canvas rendering via `HTMLCanvasElement.prototype.getContext`. DevTools console pre-buffering and WebGL shims are bypassed on auth and challenge frames to prevent bot detection flags.

- **Touch Dynamics & Micro-Timing Preservation**:
  - Touch event listeners on `document` with capture phase are suppressed in challenge iframes, eliminating DOM traversal and Java bridge IPC latency so Arkose's sensor telemetry records authentic human touch gesture dynamics.

- **WebAuthn Level 3 Passkeys & Google OAuth Compatibility**:
  - WebAuthn passkey polyfill (`PasskeyWebAuthnBridge.kt`) routes through Android `CredentialManager` with `CREDENTIAL_MANAGER_SET_ORIGIN` privilege.
  - Implements W3C WebAuthn Level 3 static methods (`PublicKeyCredential.parseCreationOptionsFromJSON`, `parseRequestOptionsFromJSON`, `getClientCapabilities`) and spec-compliant `toJSON()` serialization for `@github/webauthn-json` and modern WebAuthn libraries.
  - `ChromeEnvironmentBridge.kt` masks native Android WebView fingerprints while polyfilling `navigator.userAgentData.brands` with standard Chrome/Chromium brands, preventing Google Sign-In from throwing `403: disallowed_useragent`.
  - Passkey injection is enabled across all login domains (e.g. GitHub, Google, Microsoft), omitting only Meta's internal challenge frames (`fbsbx.com`).

---

### 2.5. Subsystem Reliability & Process Lifecycle Hardening

- **Media Playback Lifecycle Guarding**:
  - `MediaPlaybackService` tracks `@Volatile var isServiceRunning: Boolean` ensuring `updateState`, `updateProgress`, and `updateMetadata` never invoke `startForegroundService` when the service is inactive or media is paused, eliminating Android 12+ `ForegroundServiceStartNotAllowedException` crashes.
  - Explicit `MediaPlaybackService.stop(applicationContext)` in `MainActivity.onDestroy()` and `onTaskRemoved()` in `MediaPlaybackService` guarantee zero orphaned foreground services, held wake locks, or sticky notifications when the app is swiped away or closed.

- **WebView Renderer Process Crash Self-Healing**:
  - `OnyxWebViewClient.onRenderProcessGone` traps renderer process terminations and out-of-memory kills, preventing application crashes.
  - Delegates to `TabActionCallback.onRendererCrash(tabId)`, which notifies `TabManager.handleRendererCrash(tabId)` to evict the dead WebView instance and instantiate a fresh WebView with restored state.

- **BroadcastReceiver Asynchronous Execution (`goAsync`)**:
  - Handlers performing Room database writes, Scoped Storage lookups, or profile purges (`DownloadCompleteReceiver`, `IncognitoNotificationReceiver`) invoke `val pendingResult = goAsync()` and execute work inside `CoroutineScope(Dispatchers.IO).launch { try { ... } finally { pendingResult.finish() } }`.

- **Download Memory Safety & Data URI Protection**:
  - `DownloadHandler` enforces a 64MB memory boundary on Base64 `data:` URIs and defensive `OutOfMemoryError` handling, preventing large media payloads from exceeding process heap limits.
  - RFC 5987 / RFC 6266 filename resolution correctly extracts UTF-8 encoded names and classifies generic stream headers (`application/octet-stream`) as neutral to avoid stripping `.zip`, `.apk`, or `.pdf` extensions.

- **Zero Unwanted Tab Auto-Creation on Close**:
  - Closing the last tab or executing "Close all tabs" sets active tab to `null` and empties `_normalTabs` without automatically generating an unwanted placeholder tab.
  - `MainActivity.observeTabs` smoothly transitions to `showHomeScreen()` with badge count "0", `TabSwitcherBottomSheet` renders the clean "No tabs open" empty state, and new tabs are created strictly on explicit user intent (tapping "+", entering a URL/search, or clicking a shortcut).

---

### 2.6. Permissions, Site Settings & Storage Management

- **Web Permissions Flow (`WebPermissionBottomSheetDialog`)**:
  - Replaces generic system dialogs with a modern Material 3 bottom sheet providing HTTPS security indicators, origin display, and 3-state decisions: "Always allow", "Allow this time" (ephemeral session grant), and "Block".
  - Manages Camera, Microphone, Geolocation, and W3C Web Notifications (`OnyxNotificationBridge`).

- **Per-Site Security & Permissions Hub (`SiteSettingsActivity`)**:
  - Centralized site settings dashboard with card-grouped Allowed/Blocked lists, "+ Add site exception" manual rule entry, and per-domain inspector (`SingleSiteSettingsSheet`) for granular toggles and cookie/WebStorage clearing via `SiteDataManager`.

- **Encrypted Local Storage (`OnyxDatabase`)**:
  - SQLCipher AES-256 encrypted Room database encrypts tabs, bookmarks, history, shortcuts, and download metadata at rest with master keys managed by `KeyStoreManager`.

---

### 2.7. Universal In-Page Webpage Translation Engine

- **CSP-Immune Native DOM Translation**:
  - Evaluates translations directly within the page DOM without external script tags that violate Content Security Policies.
  - Supports 45+ languages across search engines and web applications, observing dynamic DOM additions via `MutationObserver` and providing 0ms in-place restoration to original content without triggering page reloads.

---

### 2.8. Open in App Intent Routing (`OpenInAppPromptDialog`)

- **Dynamic App Resolution & Theming**:
  - Resolves target intents via `PackageManager.resolveActivity` to display native app icons and human-readable application labels ("Open in Telegram?", "Open in YouTube?").
  - Includes a target destination card with middle-ellipsized URLs/deep links and provides safe fallback routing to web alternatives or Google Play when external apps are absent.
