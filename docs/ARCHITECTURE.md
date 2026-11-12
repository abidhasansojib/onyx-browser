# Onyx Browser — Architecture & Core Subsystems

This document provides a technical guide to Onyx Browser's internal architecture, native subsystems, Chromium WebView hardening, and privacy engines.

---

## 1. High-Level Architecture Overview

Onyx Browser (`com.onyx.browser`) consists of a native Android presentation layer, a native Rust content-filtering engine, and a hardened Android System WebView rendering engine.

```text
┌─────────────────────────────────────────────────────────────┐
│                    Native Android UI Layer                  │
│       Activities, ViewBinding XML, Material 3 Theming       │
├──────────────────────────────┬──────────────────────────────┤
│      Browser State & Data    │       Media & Background     │
│   SQLCipher Encrypted Room   │   Foreground Media Service   │
│   StateFlow Preferences      │   MediaSession Lockscreen    │
├──────────────────────────────┴──────────────────────────────┤
│                 Hardened Chromium WebView                   │
│   Multi-Profile Isolation (Normal / Incognito Partitioning) │
│   Document-Start Scriptlets & Anti-Bot Masking Bridges      │
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
  - Popunder, ad, tracker, and clickjack blocking decisions are driven 100% by filter lists (54 compiled Brave filter lists + EasyList) evaluated in native `adblock-rust`.
  - In-memory LRU Decision Caches (`requestDecisionCache` with 8,192 entries and `checkUrlCache` with 4,096 entries) in `AdBlockEngine.kt` enable `<0.01µs` repeat lookups without crossing the JNI boundary.
  - Invalidation via `AdBlockEngine.clearDecisionCache()` occurs whenever shields, blocking levels, or whitelists change in `BrowserPreferences`.

- **Dynamic Blocking Level & Zero Stale Baking (`com.onyx.browser.web.adblock.AdBlockDocumentStart`)**:
  - The blocking level is queried dynamically at document start via `window.OnyxShieldBridge.getBlockingLevelForDomain()` rather than baking static levels into document-start scripts.
  - Aggressive consent banner rules reside in `#onyx-aggressive-banners` and attach or detach dynamically via `updateCosmeticBannerStyles(lvl)`.

- **On-Demand Filter Fetching & First-Run Seeding (`FilterListManager`)**:
  - Filter lists download on-demand into `filter_cache/` when toggled in Content Filters settings or during app startup via `FilterListManager.ensureDefaultFiltersCached()`.
  - Downloaded rules compile into `onyx_filters.bin` and the active in-memory Rust engine, scaling rule coverage from base EasyList (~35,500) to full multi-list protection (~135,000+ rules).
  - Background updates use an `AtomicBoolean` mutex to prevent concurrent file conflicts, with compilation executing in a `NonCancellable` context.

- **Video Ad Cosmetic Selector Preservation**:
  - `safeSelectors` preserves selectors with ad, banner, sponsor, promo, overlay, popup, and preroll keywords while protecting bare `<video>`/`<audio>` tags and generic media containers, preventing breakage on video sites.

- **Scoped Auth & Login Context Bypass**:
  - Confines `/login/` deactivations and Meta auth parameter bypasses strictly to authentication/challenge domains, ensuring normal websites with `/login/` in their paths retain active shields.

- **Static CNAME Uncloaking (`com.onyx.browser.web.adblock.AdBlockDomainManager`)**:
  - Resolves cloaked first-party subdomains (e.g. Adobe Omniture, Criteo, Branch, AppsFlyer) to their real third-party tracker hosts before rule evaluation.

- **Surrogate Redirect (`$redirect`) & URL Rewrite (`$rewrite`) Handling**:
  - Evaluates `$redirect` surrogate targets and `data:` `$rewrite` rules with credentialed-CORS headers (`Access-Control-Allow-Origin: <Origin>` + `Access-Control-Allow-Credentials: true`). Script and XHR resources serve safe surrogates (`google-analytics_analytics.js`, `googletagmanager_gtm.js`, `adsbygoogle.js`, `google-ima.js`, `noopjson`, `noopvast`, `noopjs`) with HTTP 200 OK.
  - Document-start `fetch` / `XMLHttpRequest` proxies delegate to `ShieldBridge.isUrlBlocked` first and allow `$redirect` and VAST/VMAP requests through to `OnyxWebViewClient.shouldInterceptRequest`.

- **CSP Directives Extraction & MSE Worker Preservation**:
  - Queries `engine.get_csp_directives(&request)` to enforce Content Security Policies declared by filter rules. `ShieldBridge.getCspDirectives` strips `<meta>`-invalid directives (`sandbox`, `frame-ancestors`, `report-uri`) and rewrites `worker-src 'none'` to `worker-src blob:` so in-memory MSE `blob:` Web Workers (`hls.js`, `dash.js`) continue demuxing video streams.

- **Document-Start Synchronous Injection (`com.onyx.browser.web.bridge.ShieldBridge`)**:
  - Pulls domain-specific cosmetic CSS and Brave scriptlets synchronously via `ShieldBridge` (exposed to scripts as `window.OnyxShieldBridge`) at `document_start` before initial DOM parsing. Injects `<style id="onyx-domain-cosmetic">` to eliminate layout flicker and executes Brave scriptlets across all main frames and subframes.

- **Full Brave Procedural Filter Engine**:
  - Complete port of Brave's `procedural_filters.ts` featuring all 15 procedural operators (`css-selector`, `has-text`, `min-text-length`, `matches-attr`, `matches-property`, `matches-css`, `matches-css-before`, `matches-css-after`, `matches-media`, `matches-path`, `upward`, `xpath`, `has`, `not`, `contains`) and action operators (`style`, `remove`, `remove-attr`, `remove-class`).

- **Response Synthesis (`createBlockedResponse`)**:
  - *Standard Mode*: Returns HTTP 403 Forbidden with CORS headers for third-party ad resources.
  - *Adblocker Spoofing Mode*: Synthesizes type-aware HTTP 200 OK stubs with CORS headers (1×1 transparent PNG for images, empty JS for scripts, empty CSS for stylesheets, HTML comment for subframes) to bypass anti-adblock detection walls.

- **Universal Streaming Video Ad & Clickjack Defusal Architecture**:
  - *Native `$popup` & `$popunder` Option Support*: Maps `$popup` and `$popunder` filter options to `NetworkFilterOption::Document` in `rust_engine/src/lib.rs`. EasyList and uBlock Origin popup rules are compiled into native filter lists and matched against `main_frame` and `popup` request types.
  - *Capture-Phase Clickjack Annihilation (`AdBlockDocumentStart.kt`)*: Intercepts touches on empty invisible clickjack traps (`clickjack` classes/IDs, empty `opacity: 0` max-z-index shields, empty full-player `target="_blank"` anchors) without matching player controls or synthetically toggling playback.
  - *Google IMA SDK Surrogate & Fallback Alignment*: Blocked `ima3.js` scripts return HTTP 403 (`script.onerror` fallback), `$redirect=google-ima.js` rules serve Brave's full IMA surrogate, and `@@||imasdk.googleapis.com` whitelist exceptions load normally.
  - *HTML5 Video In-Stream Ad Skipper*: Monitors active ad-playback states (`.jw-flag-ads video`, `.vjs-ad-playing video`, `.ad-showing video`, `.videoAdUi video`). Automatically mutes ad audio, accelerates playback rate to 16x, jumps to the end of ad duration, and clicks explicit ad-skip buttons.
  - *Cross-Domain Redirect Interception (`OnyxWebViewClient`)*: `shouldOverrideUrlLoading` intercepts cross-domain navigations away from media/streaming pages to known ad/betting networks.
  - *VAST/VMAP Empty XML Synthesis*: XHR/fetch requests matching VAST or VMAP ad tags return valid empty `<VAST version="3.0"/>` XML (`text/xml; charset=utf-8`) with HTTP 200 OK and CORS headers.

- **Trusted Types CSP Immunity for Brave Scriptlets**:
  - On sites serving `require-trusted-types-for 'script'` CSP (such as YouTube), `AdBlockDocumentStart.kt` and `OnyxWebViewClient.kt` create an early Trusted Types policy (`onyx-shield-policy`) with fallback to DOM `<script>` injection, ensuring scriptlets execute reliably.

- **Declarative Shadow DOM (DSD) Progressive Hydration Safety Net (`DsdHydrationFix`)**:
  - In `com.onyx.browser.web.adblock.DsdHydrationFix`, removes `dsd-pending` attributes across root documents and open shadow roots on `onPageFinished`, preventing blank pages when progressive hydration scripts are blocked by tracker filters.

---

### 2.2. Media & Playback Subsystem (`MediaPlaybackManager`, `MediaPlaybackBridge`, and `MediaPlaybackService`)

- **Transparent Video Poster (`OnyxWebChromeClient`)**:
  - Overrides `getDefaultVideoPoster()` to return a cached 1×1 transparent ARGB_8888 bitmap, removing Android WebView's default dark-gray box with embedded play arrow.

- **Muted Looping & Decorative Visual Filtering (`isQualifyingMedia`)**:
  - Excludes muted looping background animations (`elem.loop && (elem.muted || elem.volume === 0)`) from qualifying media, avoiding unnecessary foreground notifications or MediaSession takeovers.

- **Guarded PiP Restoration**:
  - `restoreVideoFromPipScript` execution in `onResume()` and `onWindowFocusChanged()` is guarded by `justExitedPip || isCurrentlyInPip` in `MainActivity`. Verifies active PiP DOM targets before dispatching resize events, eliminating responsive toolbar oscillation.

- **Foreground/Background Media Service Handshake**:
  - In foreground, WebView retains native control over playback and MediaSession.
  - `MediaPlaybackService` holds `PARTIAL_WAKE_LOCK` and `WifiLock` to prevent CPU suspension during screen-off background playback.
  - Detached WebViews receive `resumeTimers()` to maintain JavaScript audio demuxing.

- **Partitioned Media Cookies & CDN Stream Playback**:
  - Permits third-party cookies for media subresources, video stream CDNs (HLS `.m3u8` playlists and segments), and embedded player iframes, preventing HTTP 403 on token-authenticated streams.

- **`userHitPause` Intent Tracking & Auto-Resume**:
  - Sets `HTMLMediaElement.prototype.userHitPause = true` upon user player touch interactions or bridge pause commands, and `false` on `play`.
  - When web pages or OS blur events trigger auto-pause without explicit user intent (`!element.userHitPause`), Onyx re-invokes `play()` and suppresses bridge pause callbacks.

- **Universal Page Visibility Interception & YouTube Keep-Alive**:
  - Capturing `visibilitychange` listeners intercept visibility events with `stopImmediatePropagation()`.
  - `Document.prototype.visibilityState` returns `'visible'` and `Document.prototype.hidden` returns `false` when background playback is active.
  - Refreshes YouTube mobile's `window._lact` inactivity timer every 30 seconds to prevent "Video paused. Continue watching?" interruptions.

- **Audio Becoming Noisy & Audio Focus Management**:
  - `AudioManager.ACTION_AUDIO_BECOMING_NOISY` receiver pauses playback when headphones or Bluetooth audio disconnect.
  - Audio focus is managed by the Chromium WebView media session. `MediaPlaybackService` does not request its own focus, avoiding intra-process focus fighting.
  - `onPause()` never pauses media (allowing PiP and dialog transitions); `onStop()` decides whether to sustain playback based on the background play preference.

- **Picture-in-Picture (PiP) Architecture & Auto-Enter**:
  - Styles strictly the active `[data-onyx-pip-video="true"]` `<video>` or player `<iframe>` via stylesheet-only rules (`#__onyx_pip_style`), dissolving ancestor stacking contexts without altering inline styles.
  - Maps `getBoundingClientRect()` against physical `WebView` bounds for accurate `sourceRectHint` animations.
  - Arms `setAutoEnterEnabled(true)` on Android 12+ (API 31+) when video is playing on the active foreground tab, disarming on pause, tab switch, or internal child activity launches.
  - `onUserLeaveHint()` handles Android 8-11 Auto-PiP transitions.
  - `pendingPipCloseCheck` distinguishes between expanding PiP back to the browser (playback continues) and dismissing PiP via 'X' (pauses media).
  - Coerces aspect ratio strictly within `[Rational(1, 2), Rational(2, 1)]`, tracking live dimension changes via `safeSetPictureInPictureParams`.

- **Lockscreen Playlist Autoplay Grace Gap**:
  - Debounce gap of 2000ms on track completion (`onMediaEnded`) prevents premature teardown of foreground service notifications while the subsequent playlist track buffers.

- **Battery Governor**:
  - Inactive background WebViews are suspended via `onPause()` during background playback, while JavaScript timers remain active exclusively on the media-playing tab (`resumeTimers()`).

---

### 2.3. Chromium Multi-Profile Isolation (Normal vs. Incognito)

- **Profile Partitioning**:
  - `OnyxWebView` assigns profile-scoped cookie jars and WebStorage via `ProfileStore.getOrCreateProfile("incognito")` and `WebViewCompat.setProfile()`. Normal and incognito tabs maintain isolated cookies, localStorage, IndexedDB, and cache.

- **Deferred Cookie Configuration**:
  - `applyCookieSettings()` executes strictly after profile assignment, preventing incognito tabs from binding to the default profile.

- **Automated Lifecycle Purge**:
  - Closing all incognito tabs triggers `TabCoordinator.purgeIncognitoProfileStatic()`, purging cookies, deleting WebStorage, and destroying the incognito profile partition.

- **OAuth Authentication Grace Period**:
  - Third-party cookies are temporarily permitted on the active profile during authentication and CAPTCHA handshakes (Google, Apple, Microsoft, Meta, Arkose), ensuring logins succeed in both normal and incognito modes.

---

### 2.4. Anti-Bot, CAPTCHA & Authentication Integrity

- **`fbsbx.com` & Challenge Domain Isolation**:
  - Meta serves Arkose MatchKey / FunCaptcha challenges inside an iframe hosted at `https://www.fbsbx.com/captcha/arkose/iframe/` connecting to `meta-api.arkoselabs.com`.
  - All document-start polyfills and monitoring scripts (`DevToolsManager`, `WebGLCompatibilityBridge`, `TouchBridge`, `MediaPlaybackManager`, `DesktopModeManager`) exempt `fbsbx.com`, `facebook.net`, `fbcdn.net`, and challenge paths (`/captcha/`, `/checkpoint/`, `/fc/`).

- **Window Hash (`wh`) Reflection Masking (`com.onyx.browser.web.bridge.ChromeEnvironmentBridge`)**:
  - Arkose Labs computes a cryptographic Window Hash (`wh = MurmurHash3-128(Object.getOwnPropertyNames(window).sort().join('|'))`).
  - Injected Android Java bridge properties (`OnyxShieldBridge`, `PasskeyWebAuthnBridge`, etc.) and `__onyx*` variables are masked from `Object.getOwnPropertyNames`, `Object.keys`, and `Reflect.ownKeys` on `window` and `window.__proto__` using native-masked functions (`function () { [native code] }`).

- **Console & WebGL Prototype Protection**:
  - Anti-bot engines verify `console.log.toString() === "function log() { [native code] }"`. DevTools console pre-buffering and WebGL shims are bypassed on auth and challenge frames to prevent bot detection flags.

- **WebAuthn Level 3 Passkeys, PasswordCredential & Credential Manager (`com.onyx.browser.web.bridge.PasskeyWebAuthnBridge`)**:
  - Marshals `@JavascriptInterface` invocations from Chromium's `JavaBridge` thread onto the Main UI thread before reading `webView.url`, validates `rpId` domain suffixes and subframe `crossOrigin` flags, and invokes Android `CredentialManager` with `CREDENTIAL_MANAGER_SET_ORIGIN` (`origin` + `clientDataHash`).
  - Constructs canonical unescaped `clientDataJSON` matching `clientDataHash` and injects it into assertion and registration responses, ensuring Relying Party SHA-256 checks verify against authenticator signatures.
  - Normalizes Base64 fields to canonical unpadded Base64URL, extracts CBOR `attestationObject` metadata (`authenticatorData`, DER SPKI `publicKey`, `publicKeyAlgorithm`), and suppresses unsolicited page-load conditional mediation popups (`isConditionalMediationAvailable() -> false`).
  - Supports W3C `PasswordCredential` alongside `AutofillHelper.getActivePasswordManager` provider detection (`com.onyx.browser.autofill`).
  - Injects authentic Chrome brands in `navigator.userAgentData`, preventing Google Sign-In `403: disallowed_useragent` blocks.

---

### 2.5. Reliability & Process Lifecycle Hardening

- **Media Playback Lifecycle Guarding**:
  - `MediaPlaybackService` tracks `@Volatile var isServiceRunning: Boolean` ensuring state updates never invoke `startForegroundService` when the service is inactive or media is paused, eliminating Android 12+ `ForegroundServiceStartNotAllowedException` crashes.
  - Explicit `MediaPlaybackService.stop(applicationContext)` in `MainActivity.onDestroy()` and `onTaskRemoved()` in `MediaPlaybackService` prevent orphaned foreground services.

- **Renderer Crash Self-Healing**:
  - `OnyxWebViewClient.onRenderProcessGone` catches renderer process terminations and OOM kills. Delegates to `TabCoordinator.handleRendererCrash(tabId)` to evict the crashed WebView and recreate a fresh instance with restored state.

- **BroadcastReceiver Asynchronous Execution (`goAsync`)**:
  - Receivers performing Room writes or profile purges (`DownloadCompleteReceiver`, `IncognitoNotificationReceiver`) invoke `goAsync()` and dispatch work to `CoroutineScope(Dispatchers.IO)`.

- **Download Memory Safety & Data URI Protection (`com.onyx.browser.download.DownloadHandler`)**:
  - Enforces a 64MB memory ceiling on Base64 `data:` URIs with defensive `OutOfMemoryError` guards.
  - RFC 5987 / RFC 6266 filename resolution extracts UTF-8 encoded names and classifies generic stream headers (`application/octet-stream`) as neutral to preserve `.zip`, `.apk`, or `.pdf` extensions.

- **Privacy Lifecycle Cleanup (`com.onyx.browser.privacy`)**:
  - `ClearOnExitService` listens for `onTaskRemoved` upon Recent Apps swipe-away.
  - Synchronous browsing data clearance routes through `ClearDataOnExitHelper`: Room database history and normal tabs purged via `runBlocking(Dispatchers.IO)`, Chromium cookies flushed across default and multi-profiles, WebStorage/caches emptied, and thumbnail state directories wiped from disk.

- **Tab State Lifecycle**:
  - Closing the last tab or executing "Close all tabs" empties the tab list without generating an unwanted placeholder tab, transitioning smoothly to `showHomeScreen()` with badge count "0".
  - `TabCoordinator.maxLiveWebViews` dynamically scales the active WebView pool (3–5 live instances) based on device memory class (`ActivityManager.MemoryInfo`). Background tabs hibernate to disk (`tab_states/`), while incognito tabs preserve state strictly in-memory.

---

### 2.6. Permissions, Site Settings & Storage Management

- **Web Permissions Flow (`com.onyx.browser.ui.dialog.WebPermissionBottomSheet`)**:
  - Material 3 bottom sheet providing HTTPS indicators, origin display, and 3-state decisions: "Always allow", "Allow this time", and "Block".
  - Manages Camera, Microphone, Geolocation, and W3C Web Notifications (`com.onyx.browser.web.bridge.NotificationBridge`).

- **Per-Site Settings (`com.onyx.browser.ui.settings.site.SiteSettingsActivity`)**:
  - Site settings dashboard with card-grouped Allowed/Blocked lists, "+ Add site exception" rule entry, and per-domain inspector (`SingleSiteSettingsBottomSheet`) for granular toggles and storage clearing via `SiteDataManager`.

- **Encrypted Local Storage (`OnyxDatabase`)**:
  - SQLCipher AES-256 encrypted Room database encrypts tabs, bookmarks, history, shortcuts, and download metadata at rest with master keys managed by `SecureDatabaseKeyProvider`.

---

### 2.7. Webpage Translation Engine

- **CSP-Immune Native DOM Translation**:
  - Evaluates translations directly within the page DOM without injecting external script tags that violate Content Security Policies.
  - Supports 45+ languages, observing dynamic DOM additions via `MutationObserver` and providing 0ms in-place restoration without triggering page reloads.

---

### 2.8. Presentation, Coordinators, Controllers & Interceptor Pipeline

- **Unidirectional Data Flow (UDF) & ViewModels (`com.onyx.browser.ui.viewmodel`)**:
  - `BrowserUiState` defines the immutable state for browser chrome (`activeTab`, tab counts, loading state, navigation availability, shields stats, address bar position).
  - `BrowserViewModel` coordinates `TabCoordinator` state flows and `BrowserPreferences.blockedRequestsFlow` within `viewModelScope`.
  - `HistoryViewModel`, `BookmarksViewModel`, and `DownloadsViewModel` accept constructor-injected repositories from `AppContainer`.
  - `OnyxViewModelFactory` (`com.onyx.browser.di`) constructs ViewModels deterministically with repositories supplied from `AppContainer`.

- **MainActivity UI Coordinators & Controllers (`com.onyx.browser.ui.browser`)**:
  - `ToolbarLayoutController`: Address bar ConstraintSet transitions (Top vs. Bottom), Edge-to-Edge insets, and scroll auto-hide.
  - `BrowserMenuCoordinator`: 3-dot overflow menu, desktop site toggle, and page actions.
  - `NavigationCoordinator`: URL resolution, search queries, `javascript:` pseudo-URL execution, and deep links.
  - `WebChromeCoordinator`: Fullscreen video containers, permission prompts, and file chooser callbacks.
  - `HomeScreenController`: Home shortcuts, tile grid editing, and home search bar interactions.
  - `FileChooserCoordinator`: Camera capture, temporary `FileProvider` URI lifecycle, and `ActivityResultLauncher` handling.
  - `WebContextMenuCoordinator`: WebView hit-test long-press context menus, image previews, and reverse image search.
  - `PageExportCoordinator`: MHTML archives, headless PDFs, and system print service integration.
  - `TabCoordinator`: Tab lifecycle, multi-profile isolation, and LRU memory hibernation.
  - `PipController`: Picture-in-Picture auto-enter, dimension aspect ratios, and exit lifecycle.
  - `SearchController`: Omnibox suggestions, query formatting, and search transitions.
  - `FindInPageController`: In-page search bar and regex text search.
  - `SettingsNavigator`: Clean routing across domain-partitioned settings activities.

- **Chain-of-Responsibility Network Interceptors (`com.onyx.browser.web.interceptor`)**:
  - `HttpsUpgradeInterceptor`: Upgrades eligible HTTP requests to HTTPS.
  - `AuthBypassInterceptor`: Preserves OAuth, passkeys, and anti-bot challenge endpoints without adblock interference.
  - `AdBlockRequestInterceptor`: Evaluates `AdBlockEngine`, CNAME uncloaking, CSP injection, `$redirect` surrogates, and VAST synthesis.
  - `PrivacyUrlSanitizer`: Strips tracking query parameters (`utm_*`, `fbclid`, `gclid`, `msclkid`, `yclid`, `igshid`, `si`) during navigation.

- **Domain Preference Stores (`com.onyx.browser.data.preferences`)**:
  - `AppearancePreferences`: Theme mode, address bar position, home/refresh button, and font zoom scale.
  - `ShieldPreferences`: Global/per-domain shields, blocking levels, HTTPS upgrade modes, DoH endpoints, and clear-on-exit flags.
  - `MediaAndDownloadPreferences`: Background media playback, PiP toggles, download prompt preferences, and external downloaders.
  - `SearchPreferences`: Selected search engine, custom search engines, and search suggestions.

- **Contained Settings Subpackages (`com.onyx.browser.ui.settings`)**:
  - `shields/`: `ShieldsActivity`, `ContentFiltersActivity`, and shields bottom sheets.
  - `site/`: `SiteSettingsActivity`, `SingleSiteSettingsBottomSheet`, `SitePermissionCategoryBottomSheet`, `AllSitesBottomSheet`.
  - `userscript/`: `UserScriptsActivity`, `UserScriptEditorActivity`, `UserScriptAdapter`, and userscript sheets.
  - `appearance/`: `AppearanceActivity`, `AddressBarSettingsActivity`, `ThemePickerBottomSheet`.
