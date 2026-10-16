# AGENTS.md — Onyx Browser Engineering Guide & Operational Mandates

> **CRITICAL DIRECTIVE FOR ALL AI MODELS & AGENTS**:  
> Read this entire document before inspecting, modifying, or executing any task on this codebase. All rules defined herein are absolute, strictly enforced, and take precedence over default assistant behavior.

---

## 1. Overview & Technology Stack

**Onyx Browser** (`com.onyx.browser`) is a fast, lightweight, and privacy-oriented Android browser targeting Min SDK 26 (Android 8.0+) and Target SDK 35 (Android 15).

### Core Stack
- **Native Android UI**: Built with Kotlin and XML Views using ViewBinding (Jetpack Compose is intentionally avoided for fast startup and minimal memory usage).
- **Hardened Chromium WebView**: Android System WebView hardened with Chromium multi-profile isolation, scriptlet injection, and network-level interception.
- **Adblocking Subsystem**: Brave's `adblock-rust` engine compiled via Android NDK into `libadblock_bridge.so` across all 4 ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `universal`). FlatBuffers binary caching (`onyx_filters.bin`) compiles and evaluates 54 Brave content filter lists.
- **Media Engine**: Foreground `MediaPlaybackService` with MediaSession lockscreen integration, Brave-style background keep-alive (`userHitPause`), and non-destructive Picture-in-Picture isolation.
- **Storage & Security**: SQLCipher AES-256 encrypted Room database, AndroidX Credential Manager for passkeys/WebAuthn, and CameraX + ML Kit for QR scanning.

---

## 2. Mandatory Operational Rules for All Agents

### RULE 1: Local Building Strictly Forbidden
- **Prohibited Operations**: Executing compilation or build commands locally on this Linux machine (including `./gradlew`, `cargo`, `rustc`, `cmake`, `ninja`, `make`, `docker build`, NDK compilation, or running Gradle daemons) is **STRICTLY FORBIDDEN**.
- **Reason**: The host system is a lightweight mobile Linux environment with strict CPU, RAM, and thermal constraints.
- **Permitted Operations**: Lightweight CLI utilities, file editing, Python/Bash scripting, code analysis, Git operations, and GitHub CLI (`gh`) are fully permitted.

### RULE 2: Explicit User Build Gate & Build Type Clarification
- **Default State**: Do **NOT** trigger GitHub Actions CI/CD workflows, compile code, or trigger remote builds during regular conversation.
- **Build Type Clarification**: When the user says **"build app now"** without specifying the build type, you **MUST ask the user** whether they want a **Release** or **Debug** build before triggering any workflow. Never guess or trigger prematurely.
- **Specific Build Commands**:
  - If the user specifies **"build release app"** (or chooses Release), build **Release only** (`gh workflow run build.yml -f build_type=Release`).
  - If the user specifies **"build debug app"** (or chooses Debug), build **Debug only** (`gh workflow run build.yml -f build_type=Debug`).
  - The `Both` option is permanently removed from the build workflow and prohibited.

### RULE 3: Autonomous Remote Build & Auto-Fix Loop
When triggered for a build (after user confirms or explicitly requests Release or Debug):
1. **Push & Trigger**: Ensure all changes are committed and pushed to `origin main`, then trigger the workflow:
   - **Release**: `gh workflow run build.yml -f build_type=Release`
   - **Debug**: `gh workflow run build.yml -f build_type=Debug`
2. **Monitor Execution**: Track the active workflow run to completion using `schedule` timers and `gh run view <run_id>`. Do NOT poll in a busy while-loop; use the `schedule` tool.
3. **If Build Succeeds**:
   - **Release Build**:
     - **DO NOT download artifacts to the device**.
     - Release APKs are published directly to GitHub Releases.
     - Report the release version tag, changelog, and GitHub release download links to the user.
   - **Debug Build**:
     - **Download artifact to device ONLY for Debug builds**:
       ```bash
       gh run download <run_id> -n Onyx-Browser-Debug-APK --dir /storage/emulated/0/Download
       ```
     - Save the APK in `/storage/emulated/0/Download/` and report its local path and size to the user.
4. **If Build Fails (CRITICAL AUTO-REPAIR REQUIREMENT)**:
   - Retrieve the failed step logs via `gh run view --log-failed --job=<job_id>`.
   - Analyze the compiler error, Kotlin syntax error, resource collision, or ProGuard/R8 exception.
   - Apply the necessary code fixes directly in the repository.
   - Commit the fix, push to `origin main`, re-trigger the workflow, and repeat until 100% successful.

### RULE 4: Mandatory Documentation Maintenance
- Whenever a feature is added, a bug is fixed, an architectural decision is made, or a workflow is completed, update this `AGENTS.md` and `FEATURES.md` before concluding the turn.

### RULE 5: Keep Everything Simple, Minimal & Effective
- All documentation, README files, UI copy, and explanations must remain simple, minimal, and effective.
- Avoid hyperbolic AI buzzwords, repetitive explanations, and emoji clutter. Write like an experienced software engineer.

### RULE 6: Feature Tracking via `FEATURES.md`
- `FEATURES.md` is the single source of truth for tracking browser capabilities and verification status (`[x]` Working, `[-]` Known Issue, `[ ]` Planned).

### RULE 7: No Internal Agent Artifacts in Git
- Never commit internal agent scaffolding, plan files, design specs, or scratch directories (`docs/superpowers/`, `.superpowers/`, scratch scripts) into the repository.

### RULE 8: Golden Milestone Immutability
- AI agents are **strictly forbidden** from automatically bumping or declaring a "Golden Milestone / Role Model" reference in `AGENTS.md` or `FEATURES.md` unless explicitly commanded by the user.

---

## 3. Directory Layout & Architecture Map

```text
onyx-browser/
├── .github/
│   ├── ISSUE_TEMPLATE/       # Structured YAML issue forms (bug reports & feature requests)
│   └── workflows/
│       ├── build.yml         # Rust NDK compile, Lucide sync, Gradle Release/Debug APKs
│       └── sync_upstream.yml # 6-hour automated Brave filter lists sync workflow
├── app/
│   ├── src/main/
│   │   ├── assets/           # easylist_rules.txt, eruda.min.js, font assets
│   │   ├── java/com/onyx/browser/
│   │   │   ├── MainActivity.kt     # SingleTask primary activity, toolbar, and window coordinator
│   │   │   ├── OnyxApplication.kt  # Startup lifecycle, SQLCipher init, Safe Browsing setup
│   │   │   ├── data/
│   │   │   │   ├── filter/         # FilterListManager (54 Brave filter lists compiler)
│   │   │   │   ├── local/          # Room Database, DAOs, and SQLCipher key provider
│   │   │   │   ├── model/          # TabItem, HistoryItem, BookmarkItem, ShortcutItem
│   │   │   │   └── preferences/    # BrowserPreferences (StateFlow reactive settings)
│   │   │   ├── media/              # MediaPlaybackService and MediaPlaybackBridge
│   │   │   ├── nativebridge/       # AdBlockEngine JNI bindings to libadblock_bridge.so
│   │   │   ├── ui/                 # ViewBinding presentation layer (tabs, settings, search, menu)
│   │   │   └── web/                # Hardened Chromium WebView subsystem, client, and bridges
│   │   └── res/                    # Google Theme styles (Light/Dark/AMOLED), layouts, vectors
│   └── build.gradle.kts            # App dependencies, NDK configuration, deterministic signing
├── rust_engine/                    # Native Rust NDK Bridge (libadblock_bridge.so)
└── scripts/                        # Icon and filter list synchronization automation
```

---

## 4. Core Subsystems & Implementation Guidelines

### 4.1. Ad-Blocking Subsystem (`OnyxWebViewClient` & `AdBlockEngine`)
- **Pure Filter-List Evaluation & Kotlin LRU Decision Cache**: All ad, tracker, popunder, and clickjack blocking decisions are driven 100% by filter lists (54 compiled Brave filter lists + EasyList) in native `adblock-rust`. High-performance thread-safe in-memory LRU Decision Caches (`requestDecisionCache` with 8,192 entries and `checkUrlCache` with 4,096 entries) in `AdBlockEngine.kt` ensure `<0.01µs` repeat lookups in Kotlin memory without crossing the JNI boundary. Cache entries are automatically invalidated via `AdBlockEngine.clearDecisionCache()` whenever shields, blocking levels, or whitelists are modified in `BrowserPreferences`.
- **Dynamic Blocking Level & Zero Stale Baking (`AdBlockDocumentStart.kt`)**: Blocking level is queried dynamically at document start via `window.OnyxShieldBridge.getBlockingLevelForDomain()` rather than statically baking blocking levels into `WebViewCompat.addDocumentStartJavaScript`. Aggressive consent banner rules reside in `#onyx-aggressive-banners` and are dynamically attached or detached via `updateCosmeticBannerStyles(lvl)`, ensuring instant reversion when toggled back to Standard.
- **On-Demand Filter Fetching & First-Run Seeding (`FilterListManager`)**: Missing filter lists are downloaded on-demand into `filter_cache/` when toggled in Content Filters settings or during app startup via `FilterListManager.ensureDefaultFiltersCached()`. Downloaded rules are merged and compiled into `onyx_filters.bin` and the active in-memory Rust engine, scaling rule coverage from base EasyList (~35,500) to full multi-list protection (~135,000+ rules).
- **Filter Compilation Signature & Concurrency Locks**: `FilterListManager` records `compiledFilterSignature` to ensure changed filter subscriptions trigger binary recompilation on startup. Background updates use an `AtomicBoolean` mutex to prevent concurrent file conflicts, and `recompileFilters` executes in `NonCancellable` context to prevent corruption during activity teardown.
- **Video Ad Cosmetic Selector Preservation (`AdBlockDocumentStart.kt`)**: Refined `safeSelectors` allows selectors with ad, banner, sponsor, promo, overlay, popup, and preroll keywords while protecting bare `<video>`/`<audio>` tags and generic media containers, restoring cosmetic ad hiding on streaming sites.
- **Scoped Auth & Login Context Bypass**: Confines `/login/` deactivations and Meta auth parameter bypasses strictly to authentication/challenge domains, ensuring normal websites with `/login/` in their paths retain active shields and ad-blocking.
- **Scoped Social Tracker Evaluation**: Third-party Facebook SDK scripts (`connect.facebook.net`, `graph.facebook.com`) on non-Meta websites undergo native adblock filter evaluation unless the user is actively in an OAuth flow or on a Meta context.
- **Static CNAME Uncloaking (`AdBlockDomainManager`)**: Resolves cloaked first-party subdomains (e.g. Adobe Omniture, Criteo, Branch, AppsFlyer) to their real third-party tracker hosts before rule evaluation.
- **Surrogate Redirect (`$redirect`) & URL Rewrite (`$rewrite`) Handling**: Evaluates `$redirect` surrogate targets and `$rewrite` query/URL cleaning rules natively. Script resources serve safe JavaScript stubs (`google-analytics_analytics.js`, `googletagmanager_gtm.js`, `adsbygoogle.js`, `noopjs`) with HTTP 200 OK so web applications do not crash with `TypeError`. Rewritten URLs (e.g., query stripping / tracking parameter removal) are redirected or served as synthesized safe data.
- **CSP Directives Extraction**: Native bridge exposes `getCspDirectivesNative` to query `engine.get_csp_directives(&request)` and enforce Content Security Policies declared by filter rules.
- **Document-Start Synchronous Injection (`OnyxShieldBridge`)**: Pulls domain-specific cosmetic CSS and Brave scriptlets synchronously via `OnyxShieldBridge` at `document_start` before initial DOM parsing begins. Injects `<style id="onyx-domain-cosmetic">` to eliminate layout flicker and executes Brave scriptlets across all main frames and subframes before any inline or external scripts run.
- **Full Brave Procedural Filter Engine**: Complete port of Brave's `procedural_filters.ts` featuring all 15 procedural operators (`css-selector`, `has-text`, `min-text-length`, `matches-attr`, `matches-property`, `matches-css`, `matches-css-before`, `matches-css-after`, `matches-media`, `matches-path`, `upward`, `xpath`, `has`, `not`, `contains`) with fast-path optimizations for media queries and URL paths, coupled with action operators (`style`, `remove`, `remove-attr`, `remove-class`).
- **Response Synthesis (`createBlockedResponse`)**:
  - *Standard Mode (Spoofing OFF)*: Returns HTTP 403 Forbidden with CORS headers for third-party ad resources.
  - *Adblocker Spoofing Mode (Spoofing ON)*: Synthesizes type-aware safe HTTP 200 OK stubs with CORS headers (1×1 transparent PNG for images, empty JS for scripts, empty CSS for stylesheets, HTML comment for subframes) to bypass aggressive anti-adblock detection walls.
- **Stealth Client Proxies (`AdBlockDocumentStart.kt`)**: Injects early proxies for `window.fetch` and `XMLHttpRequest` rejecting blocked URLs with `net::ERR_BLOCKED_BY_CLIENT` while completing standard W3C `readyState` and progress events. Defuses anti-adblock honeypot elements dynamically when Adblocker Spoofing is active.
- **Custom Cosmetic Filtering**: Injects `#onyx-user-custom-blocked` at `document_start` using `BrowserPreferences` domain index, preventing layout flicker and permanently hiding user-blocked elements.
- **Video Player Clickjack Defuser & Popunder Neutralizer**: Injects early styles in subframes/media embeds suppressing `#overlay` and click-jack div layers, freezes `window.abyssConfig.popups = []`, and proxies `window.open` in subframes to return safe mock Window objects, disarming popunders before user taps register.
- **Hardened Popup Ad Navigation Interceptor**: Evaluates `isPopupAd()` across `handleUrlLoading`, `shouldInterceptRequest`, `onPageStarted`, and `onPageFinished`. Popup tabs attempting navigation to unauthorized third-party ad networks (e.g. `decafeligiblyhad.com`, `ng88b.com`) are immediately aborted and silently closed, preserving user focus and playback state on the parent page.

### 4.2. Media & Playback Subsystem (`MediaPlaybackManager`, `MediaPlaybackBridge`, and `MediaPlaybackService`)
- **Transparent Video Poster (`OnyxWebChromeClient`)**: Overrides `getDefaultVideoPoster()` to return a cached 1×1 transparent ARGB_8888 bitmap, universally removing Android WebView's default dark-gray box with embedded black play arrow (`ic_media_embed_play.png`) across all websites.
- **Muted Looping & Decorative Visual Filtering (`isQualifyingMedia`)**: Excludes muted looping decorative animations (`elem.loop && (elem.muted || elem.volume === 0)`) and muted videos without user controls from qualifying media. Background hero visuals (e.g., GitHub, Stripe) play silently without triggering foreground notifications, MediaSession takeovers, or AudioFocus races.
- **Strictly Guarded PiP Restoration & Resize Protection**: `restoreVideoFromPipScript` execution in `onResume()` and `onWindowFocusChanged()` is strictly guarded by `justExitedPip || isCurrentlyInPip` in `MainActivity`. The script itself verifies `__onyx_pip_style` and active PiP DOM targets before dispatching `resize` events, completely eliminating synthetic resize loops that cause responsive navigation components (e.g. GitHub toolbar) to oscillate between dropdown and button rows.
- **Clean Foreground/Background Media Service Handshake**: In the foreground, Android System WebView retains 100% native control over playback and MediaSession without focus collisions. `MediaPlaybackService` is launched strictly upon background transition (`onPause` when media is active) or screen-lock, and is dismissed upon `onResume()`, resolving video auto-pause issues on start when background playback is enabled.
- **Partitioned Media Cookies & CDN Stream Playback (Brave Parity)**: Automatically permits third-party cookies for media subresources, video stream CDNs (such as HLS `.m3u8` playlists and segments), and embedded player iframes (e.g. `ravok.buzz`, `fireplayer.stream`), preventing HTTP 403 Forbidden on token/cookie-authenticated streams.
- **Brave `userHitPause` Architecture & Auto-Resume**: Direct port of Brave's `MediaBackgroundingScript.js`. Sets `HTMLMediaElement.prototype.userHitPause = true` only upon genuine user touch gestures (`window.__onyx_last_user_touch < 1500`) or explicit bridge pause commands, and `false` on `play`. When web pages or OS blur events trigger auto-pause while in the background without explicit user intent (`!element.userHitPause`), Onyx automatically re-invokes `play()` to sustain seamless background playback.
- **Universal DOM Preservation & Scoped YouTube Visibility Filtering**: `document.addEventListener` filtering for `visibilitychange`, `ytcfg` Picture-in-Picture flags, and fullscreen video workarounds are strictly scoped to YouTube domains (matching Brave's `YouTubeScriptInjectorTabHelper`). For universal websites, standard DOM events, `document.visibilityState`, `window.onblur`, and focus events remain 100% untouched and native in the foreground, preventing carousels, timers, video tabs, and modern React components from breaking. In background mode, `Document.prototype.visibilityState` dynamically returns `'visible'` only when media is active.
- **Native WebView Audio Focus Integration**: Android System WebView internally manages OS Audio Focus directly with Android's audio server. Service-level AudioFocus requests are avoided to prevent focus collisions that prematurely pause active WebView video players.
- **Audio Becoming Noisy Broadcast Protection (Brave Parity)**: Registers a system `BroadcastReceiver` for `AudioManager.ACTION_AUDIO_BECOMING_NOISY`. When wired headphones are unplugged or Bluetooth audio disconnects during media playback, the service immediately pauses playback and updates lockscreen notification controls to prevent loud public audio blaring.
- **MediaSession Integration**: Intercepts `navigator.mediaSession.setActionHandler` to route lockscreen, notification, and Bluetooth playback controls to the website's handlers, falling back to YouTube `#movie_player` or DOM elements.
- **Picture-in-Picture (PiP) Isolation**: Traverses Shadow DOM and composed ancestor trees to remove CSS transforms and clipping constraints, isolating active `<video>` elements non-destructively without hiding sibling DOM nodes.

### 4.3. Chromium Multi-Profile Isolation (Normal vs Incognito)
- **Profile Partitioning**: `OnyxWebView` assigns profile-scoped cookie jars and WebStorage via `ProfileStore.getOrCreateProfile("incognito")` and `WebViewCompat.setProfile()`. Normal and incognito tabs have 100% isolated cookies, localStorage, IndexedDB, and cache.
- **Deferred Cookie Configuration**: `applyCookieSettings()` is executed strictly after profile assignment, preventing incognito tabs from binding to the default profile.
- **Automated Lifecycle Purge**: Closing all incognito tabs triggers `TabManager.purgeIncognitoProfile()`, wiping all incognito cookies, deleting WebStorage data, and destroying the incognito profile partition.
- **Universal OAuth Grace Period**: Third-party cookies are temporarily permitted on the active profile during authentication and CAPTCHA handshakes (Google, Apple, Microsoft, Meta, Arkose), ensuring logins succeed in both normal and incognito modes.

### 4.4. Anti-Bot, CAPTCHA & Authentication Integrity (Arkose Labs & Meta Parity)
- **`fbsbx.com` & Challenge Domain Isolation**: Meta serves Arkose MatchKey / FunCaptcha challenges inside an iframe hosted at `https://www.fbsbx.com/captcha/arkose/iframe/` connecting to `meta-api.arkoselabs.com`. All document-start polyfills and monitoring scripts (`DevToolsManager`, `WebGLCompatibilityBridge`, `OnyxTouchBridge`, `MediaPlaybackManager`, `DesktopModeManager`) comprehensively exempt `fbsbx.com`, `facebook.net`, `fbcdn.net`, and challenge paths (`/captcha/`, `/checkpoint/`, `/fc/`).
- **Window Hash (`wh`) Reflection Masking (`ChromeEnvironmentBridge`)**: Arkose Labs computes a cryptographic Window Hash (`wh = MurmurHash3-128(Object.getOwnPropertyNames(window).sort().join('|'))`). Injected Android Java bridge properties (`OnyxShieldBridge`, `PasskeyWebAuthnBridge`, etc.) and `__onyx*` variables are hidden from `Object.getOwnPropertyNames`, `Object.keys`, and `Reflect.ownKeys` on `window` and `window.__proto__` using native-masked functions (`function () { [native code] }`), ensuring the resulting hash matches 100% authentic mobile Chrome.
- **Console & WebGL Prototype Protection**: Anti-bot engines verify `console.log.toString() === "function log() { [native code] }"` and profile WebGL/Canvas rendering via `HTMLCanvasElement.prototype.getContext`. DevTools console pre-buffering and WebGL shims are bypassed on auth and challenge frames to prevent bot detection flags.
- **Touch Dynamics & Micro-Timing Preservation**: Touch event listeners on `document` with capture phase are suppressed in challenge iframes, eliminating DOM traversal and Java bridge IPC latency so Arkose's sensor telemetry records authentic human touch gesture dynamics.
- **Cross-Site Cookie Assurance**: `OnyxWebViewClient.shouldInterceptRequest` automatically sets and flushes third-party cookies on active auth/CAPTCHA resources, preventing dropped session cookies between `fbsbx.com` and `facebook.com`.

### 4.5. Subsystem Reliability & Hardening
- **Media Playback Lifecycle Guarding**: `MediaPlaybackService` tracks `@Volatile var isServiceRunning: Boolean` ensuring `updateState`, `updateProgress`, and `updateMetadata` never invoke `startForegroundService` when the service is inactive or media is paused, eliminating Android 12+ `ForegroundServiceStartNotAllowedException` crashes. Explicit `MediaPlaybackService.stop(applicationContext)` in `MainActivity.onDestroy()` and `onTaskRemoved()` in `MediaPlaybackService` guarantee zero orphaned foreground services, held wake locks, or sticky notifications when the app is swiped away or closed.
- **Notification Action Foreground Service Intents**: `DownloadNotificationHelper` and `MediaPlaybackService` build action PendingIntents via `PendingIntent.getForegroundService()` on Android 8.0+ (`Build.VERSION_CODES.O`), avoiding background service start exceptions when actions (Pause, Resume, Retry) are tapped from the notification shade.
- **Find-in-Page UI & Regex Escaping**: `FindInPageController` hooks `setRegexFindListener` to update match indicators in real-time, while `RegexFindBridge` quotes query literals via `JSONObject.quote()` and guarantees bidirectional highlight clearing via `webView.clearMatches()`.
- **Content Filter HTTP Redirect Resilience**: `FilterListManager.downloadFilterList()` supports up to 5 redirect hops across schemes and 3xx codes with base URL resolution, preventing mirror update failures.
- **SQLCipher KeyStore Recovery Assurance**: `AppDatabase.buildEncryptedDatabase()` eagerly invokes `openHelper.writableDatabase` so any KeyStore reset or passphrase discrepancy triggers recovery catch blocks immediately during database creation.
- **Non-Destructive Navigation & Tab State Preservation**: Hardware back presses at root history minimize/finish the browser activity without wiping the active tab URL from the database. Centralized `setActiveTab` in `TabManager` guarantees `lastActiveTabId` is kept synchronized across tab creation, closure, undos, and switching.
- **Reliable Clear Data on Exit**: Uses `NonCancellable` IO coroutine scope in `onDestroy` so database clearing never gets cancelled when the activity finishes, coupled with complete purging of WebView cache, cookies, WebStorage, HTTP authentication credentials, and form data.
- **Omnibox Quick Site Shields Access & Thread-Safe Counters**: Address bar SSL lock icon provides 1-tap direct access to `SiteShieldBottomSheetDialog`, while `OnyxWebView.pageBlockedCount` utilizes an `AtomicInteger` to prevent background thread increment races.
- **SSL Downgrade Protection**: Removed silent HTTP downgrades on SSL handshake errors, ensuring any certificate issues route to the secure SSL warning interstitial.
- **Privileged Error Bridge Scoping (`OnyxErrorBridge`)**: Restricted all JavaScript-invoked error actions (`reload`, `goBack`, `goHome`, `search`, `openSettings`, `openNetworkSettings`, `checkWaybackMachine`, `openShields`, `allowSiteShield`, `openDownloads`, `proceedSsl`) to genuine synthetic error pages, eliminating malicious web-based setting launches or arbitrary site-shield whitelisting.
- **WebAuthn / Passkey Authentic Origin Enforcement (`PasskeyWebAuthnBridge`)**: Derives effective registration/authentication origins strictly from genuine top-level WebView context and enforces origin matching, preventing cross-origin passkey spoofing.
- **Intent Scheme URL Hardening (`OnyxWebViewClient.handleIntentScheme`)**: Enforces `Intent.CATEGORY_BROWSABLE`, strips dangerous URI permission grant flags (`FLAG_GRANT_READ_URI_PERMISSION`, `FLAG_GRANT_WRITE_URI_PERMISSION`), and blocks untrusted web content from targeting internal Onyx Browser application components.
- **Renderer Crash Self-Healing (`onRenderProcessGone` & `handleRendererCrash`)**: When Chromium renderer processes crash or are terminated by the OS, `OnyxWebViewClient` informs `TabActionCallback.onRendererCrash()`, cleanly evicting the destroyed instance from `TabManager.webViewPool` and automatically reloading the active tab with a fresh WebView without leaving the user with an unresponsive blank screen.
- **Asynchronous Broadcast Receiver Execution (`goAsync`)**: Wrapped SQLite database updates in `DownloadCompleteReceiver` and multi-profile incognito destruction in `IncognitoNotificationReceiver` with `goAsync()` and `pendingResult.finish()`, guaranteeing operations complete even under low-memory background transitions.
- **Large Heap & Blob/Data URI Memory Safety**: Configured `android:largeHeap="true"` in `AndroidManifest.xml`, capped base64 data URI handling at 64MB, and added defensive `OutOfMemoryError` guards in `DownloadHandler.handleDataUriDownload`.
- **Synchronized HTTPS Upgrade Preferences**: Harmonized `isHttpsUpgradeEnabled` and `httpsUpgradeMode` in `BrowserPreferences` so toggling HTTPS upgrade in Site Shields immediately propagates to `OnyxWebViewClient` network routing.
- **Warm Browser Resumption (`moveTaskToBack`)**: Root back button press transitions the browser via `moveTaskToBack(true)` instead of `finish()`, preserving warm WebView instances, cache, and tab state in RAM for instant resumption.
- **Accurate Tracking Parameter Stripping (`stripTrackingParams`)**: Operates directly on raw `encodedQuery`, stripping tracking tokens while perfectly preserving duplicate query keys (`?tag=1&tag=2`) and valueless flags (`?preview`) without converting them to `=null`.
- **Touch Dynamics & Element Picker Resilience**: Tracked touch displacement in `ElementPickerManager` to prevent accidental element selection during page scrolling and suppressed coarse-pointer synthetic clicks to eliminate toggle oscillation.
- **Clipboard Snooping Guard & Privacy Cache (`SearchController`)**: Checked `ClipDescription` MIME types before reading clipboard content and implemented a 10-second suggestion cache, permanently eliminating continuous system clipboard toasts on Android 12+.
- **Streaming Filter Compilation (`FilterListManager`)**: Sized `StringBuilder` capacity up front and streamed filter files with an 8KB buffer, eliminating intermediate string allocations across 54 lists and freeing buffer memory early.
- **Asynchronous Package Installation (`ApkInstallerHelper`)**: Offloaded APK intent preparation and content URI file copying to `Dispatchers.IO`, preventing main thread freezes and ANRs on large APK installations.

### 4.6. Web Permissions & HTML File Chooser Subsystem
- **HTML File Chooser (`onShowFileChooser`)**: Handled via `OnyxWebChromeClient.onShowFileChooser` and `MainActivity.handleShowFileChooser`. Strict Chromium and Brave parity:
  - *Direct Camera / Video Capture*: When `isCaptureEnabled` is declared or `capture` is present on the input, launches `MediaStore.ACTION_IMAGE_CAPTURE` (or `ACTION_VIDEO_CAPTURE`) directly. Checks runtime `CAMERA` permission via dedicated `fileChooserCameraPermissionLauncher` before launching, and queries all camera packages with flag 0 to grant `FileProvider` read/write URI permissions without falling back to gallery.
  - *Direct Gallery / Media Picker*: When `isCaptureEnabled` is false and media types are requested (`image/*`, `video/*`), directly launches the Android Photo Picker (`MediaStore.ACTION_PICK_IMAGES` on Android 13+ or backported Photo Picker, with `ACTION_GET_CONTENT` fallback) without wrapping in an ambiguous action chooser or presenting camera options.
  - *Generic File / Document Picker*: Directly launches the system document picker for generic files or documents (`*/*`, PDFs, etc.).
  - *Safe Callback & Memory*: Uses thread-safe atomic `SafeValueCallback` to prevent Chromium JNI assertions, cleans up zero-byte temporary files on cancellation, and preserves thumbnail bitmaps as fallback.
- **Web Media & Sensor Permissions (`onPermissionRequest` & `onGeolocationPermissionsShowPrompt`)**: Displays explicit Material 3 confirmation dialogs per web origin (`promptWebPermission`) for Camera, Microphone, and Location before delegating to Android OS runtime permissions. Caches per-domain decisions in `BrowserPreferences` to prevent racing prompts on rapid `getUserMedia` calls.
- **System Dialog Lifecycle Guarding (`isSystemDialogOrChooserPending`)**: Bypasses `webView.onPause()` during system permission prompts and file picker sheets across both `onPause()` and `onStop()`, and guarantees `webView.onResume()` before granting permissions, eliminating Chromium `JniAndroid$UncaughtExceptionException` native crashes in `VideoCaptureCamera2`.
- **Renderer Crash Resilience (`onRenderProcessGone`)**: Overrides `onRenderProcessGone` in `OnyxWebViewClient` returning `true` to isolate sandboxed renderer or camera HAL faults, preventing process death.
- **Package Visibility & Permissions**: Manifest declares Android 13+ media permissions (`READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`, `READ_MEDIA_VISUAL_USER_SELECTED`), optional camera features (`camera`, `autofocus`, `front`), and camera/video capture intent filters inside `<queries>` for Android 11+ compatibility.

### 4.7. Per-Site Permissions & Site Data Management Subsystem
- **Omnibox Page Info & Permissions Panel (`SiteShieldBottomSheetDialog`)**: Address bar SSL lock icon provides instant access to connection security verification with interactive SSL Certificate inspection dialog (displaying Common Name, Organization, Issuer, Validity dates, and encryption status), card-grouped per-site permissions (Camera, Microphone, Location, and Notifications with 3-state selector: Allowed, Blocked, Ask every time) with interactive indicators and dividers, per-site Ad & Tracker Blocking Level (Standard / Aggressive) with fallback to global default, 3-state per-site Desktop Site toggle (`layoutDesktopMode`), synchronized Script Blocking (updating both network-level interception and WebSettings JavaScript permission), Custom Blocked Elements inspector with 1-tap reset, a 1-tap "Reset permissions" button that clears remembered hardware/notification decisions, recompiles native adblock filters, and dynamically refreshes all panel controls, "Clear data for this site", and direct switches for Google Sign-In prompts and "Forget this site when closed" (Auto-shred), all styled with `@color/settings_card_bg` and `@color/settings_canvas_bg` for complete light/dark theme consistency.
- **3-State Desktop Site Architecture (`BrowserPreferences` & `OnyxWebView`)**: Supports true per-domain 3-state desktop mode: Desktop (`desktopDomains`), Mobile (`mobileDomains`), and Default (`getEffectiveDesktopMode`). Users can force desktop on specific sites when global desktop is off, and force mobile on specific sites when global desktop is on. 3-dot menu toggle, shields panel switch, and `SingleSiteSettingsSheet` dynamically reflect and toggle between effective modes without leaving orphaned overrides.
- **Dual-List Auto-Shred & Keep Protection (`TabManager` & `BrowserPreferences`)**: Provides both `autoShredDomains` (sites to shred on tab close) and `autoKeepDomains` (sites to exempt and keep even if global autoclear on close is active). When closing tabs, `TabManager.autoclearTabData()` respects `isSiteAutoKeepEnabled()`, preventing accidental session destruction on whitelisted sites.
- **Safe Ephemeral Web Permission Prompts (`WebPermissionBottomSheetDialog` & `MainActivity`)**: Web permission prompts distinguish between explicit "Block" taps (`permanently = true`) and touch-outside / dismiss actions (`permanently = false`). Dismissals deny the ephemeral request without writing a permanent block to SharedPreferences.
- **Synchronized Cosmetic Filter Compilation (`FilterListManager`)**: Resetting site permissions, clearing custom elements, or wiping site data across `SingleSiteSettingsSheet`, `AllSitesSheet`, `SiteShieldBottomSheetDialog`, and `SiteSettingsActivity` automatically triggers asynchronous native filter recompilation (`FilterListManager.recompileFilters`), ensuring deleted cosmetic rules are evicted from native memory.
- **RFC 6265 Compliant Scheme & Scope Cookie Erasure (`SiteDataManager`)**: Completely purges cookies across both `https://` and `http://` schemes, with and without `Secure` attributes, and across both leading-dot and host-only domain scopes, coupled with origin `WebStorage` (localStorage, IndexedDB) and HTTP authentication credentials strictly matching the target domain across normal and incognito profiles.
- **19 Streamlined Essential Site Settings Categories (`SiteSettingsActivity`)**: Accessible via Settings → Privacy & Security → Site settings. Built to match mobile Chromium and Brave with box-tile icons, dynamic count badges (reflecting allowed and blocked overrides including desktop and kept sites), and organized into clean, functional sections without desktop-only clutter:
  - *Permissions*: Camera, Microphone, Location, Notifications, Motion & light sensors (Brave default: blocked to prevent gyroscope/accelerometer fingerprinting), Clipboard (protecting `navigator.clipboard.read/readText`), Google Sign-In (blocking third-party One-Tap prompts), Screen Wake Lock (display sleep prevention control per-site), and WebRTC IP Handling (local LAN host candidate leak protection).
  - *Content*: JavaScript, Pop-ups & redirects, Autoplay, Images, Zoom, Insecure content (Mixed Content), Sound (per-domain audio muting), Automatic downloads (requiring explicit confirmation before file downloads).
  - *Advanced (Brave Parity)*: Cookies and site data (with `CookieModePickerSheet`), Desktop site (global toggle + per-domain Desktop/Mobile rules), Protected content (DRM / EME media identifier control), and Auto-shred on tab close (with Keep exceptions).
  - *Pruned Dead Desktop APIs*: Dropped 14 non-existent desktop APIs in Android System WebView (WebUSB, Web Serial, WebHID, Web MIDI, WebXR Hand Tracking, Idle Detection, Local Font Enumeration, Web Bluetooth, PaymentRequest API, File System Access API, WebXR VR, Web NFC, Window Management API, and Storage Access API), eliminating >3,300 lines of dead code, document-start JavaScript shim overhead, unused string resources, and orphaned drawables.
  - *Exception Management*: "+ Add site exception" button in `SitePermissionCategorySheet` allowing manual domain addition with Allow and Block rules for all categories including Desktop and Auto-shred.
  - *All Sites Hub (`AllSitesSheet`)*: Real-time domain search inspecting all configured permission categories for each site's summary line, preventing false "Default permissions" labels, with 1-tap site data and rule reset.
  - *Single Site Settings Hub (`SingleSiteSettingsSheet`)*: Interactive bottom sheet opened by tapping any domain in "All sites", enabling granular inspection/toggling of Brave Shields (Shields Up / Shields Down / Default), Ad & Tracker Blocking Level (Standard / Aggressive / Default), Custom Blocked Elements counter and reset, 3-state Desktop Mode (Desktop / Mobile / Default), 3-state Auto-Shred (Forget on close / Keep data / Default), and all standard permission categories, with automatic filter recompilation on reset.
  - *Clear All Site Data*: Comprehensive "Clear all site data" in `SiteSettingsActivity` purging `WebStorage.deleteAllData()`, `CookieManager.removeAllCookies()`, incognito multi-profile storage, custom element rules, and resetting all per-site exceptions.
- **W3C Web Notifications API Bridge (`OnyxNotificationBridge`)**: Exposes standard `window.Notification` and `Notification.requestPermission()` polyfills to web pages, wired to per-site preferences and Android 13+ `POST_NOTIFICATIONS` runtime permissions, masked from anti-bot / Arkose CAPTCHA window property enumeration.
- **Explicit Allowed Pop-up Handling (`MainActivity.onCreateWindowCallback`)**: Respects explicit per-site Allow Pop-ups permission (`isWebPopupsAllowed(parentDomain) == true`), while enforcing global popup blocking policies when unset.
- **Downloads Categorical Filter Badge Counts**: Real-time item counters dynamically rendered inside categorical filter chips (`All (N)`, `APKs (N)`, `Images (N)`, `Videos (N)`, `Documents (N)`).

### 4.8. Theme & Display Customization Subsystem
- **Streamlined Display Theme Picker (`ThemePickerSheet`)**: Accessible via Settings → Appearance. Features clean display mode selection (System Default, Dark, Light) with real-time selection indicator tinting and non-destructive on-dismiss recreation. Removed complex accent color preset swatches and custom hex palette input from the UI to keep settings minimal, clean, and fast.
- **Tab Switcher & Tab Menu Full Light/Dark Parity (`TabSwitcherBottomSheet`)**:
  - *DayNight Theme Architecture*: Uses `Theme.OnyxBrowser.TabSwitcher` inheriting `Theme.Material3.DayNight.NoActionBar` instead of hardcoded platform black fullscreen styles.
  - *Light Theme Canvas & Card Rendering*: In Light mode, renders a Google-standard `#F1F3F4` canvas background, crisp `#FFFFFF` tab cards with `#F8F9FA` headers, `#E8EAED` pill containers, `#DADCE0` borders, `#202124` high-contrast typography, and `#5F6368` close/unselected icons. In Dark mode, retains Quetta-inspired `#131314` canvas, `#1D1E20` cards, and `#E8EAED` text.
  - *Theme-Aware Tab Menu Popup (`popup_tab_switcher_menu.xml`)*: Switched background to `@color/dialog_search_engine_bg` (`#FFFFFF` in light, `#282A2D` in dark), `@color/popup_menu_stroke` border, and `@color/tab_text_primary` icons/text, ensuring 100% theme consistency when opened.
  - *Dynamic System Bar Inset Control*: `onStart` evaluates `resources.configuration.uiMode` to dynamically set `isAppearanceLightStatusBars` and `isAppearanceLightNavigationBars` (`!isNightMode`), rendering dark status bar and navigation icons on light surfaces.
- **Settings Canvas & System Bar Inset Harmonization (`settings_canvas_bg`)**: Calibrated dark canvas background to refined Matte Charcoal `#161616` (replacing legacy reddish `#0B0305`), providing smooth neutral dark elevation behind `#202020` card containers. Edge-to-edge system bars across all canvas settings activities (`SettingsActivity`, `DownloadSettingsActivity`, `AccessibilitySettingsActivity`, `VideoSettingsActivity`, `SiteSettingsActivity`) dynamically track theme night mode via `WindowInsetsControllerCompat.isAppearanceLightStatusBars` and `isAppearanceLightNavigationBars`, ensuring system icons remain visible in both Light and Dark themes without double-padding conflicts.
- **Settings Scroll Header Elevation & Lift-on-Scroll Lock**: Locked `AppBarLayout` and `MaterialToolbar` across all settings activities (`SettingsActivity`, `DownloadSettingsActivity`, `AccessibilitySettingsActivity`, `VideoSettingsActivity`, `SiteSettingsActivity`) to disable Material 3 lift-on-scroll elevation tinting (`app:liftOnScroll="false"`, `app:elevation="0dp"`, `app:statusBarForeground="@null"`, `isLiftOnScroll = false`, `setLiftable(false)`, `setLifted(false)`, `stateListAnimator = null`, `outlineProvider = null`). The top header background and tint are statically pinned to `@color/settings_canvas_bg` (`#161616`), permanently eliminating the purple/gray tint shift during scrolling and preserving continuous visual consistency with the resting canvas.

### 4.9. Bookmarks & Bookmarklets Subsystem (Chrome & Brave Parity)
- **Bookmarklet Execution Engine (`MainActivity.executeBookmarklet`)**: Evaluates `javascript:...` bookmarklets natively on the active tab via `webView.evaluateJavascript(script, null)` without page navigation or search redirection. Strips scheme, decodes percent-encoded sequences while preserving literal `+` operators, and displays execution feedback toast.
- **Omnibox & Suggestion Routing (`SearchController` & `SuggestionsAdapter`)**: Detects `javascript:` bookmark suggestions, renders them with `< / >` code icon (`ic_code`) and `"Bookmarklet (JavaScript)"` badge, and prevents `https://` prefixing or search engine queries when executed from omnibox or suggestion list.
- **Modern Bookmark Management (`BookmarksActivity` & `BookmarksAdapter`)**:
  - *Real-Time Search*: Live search bar querying SQLite Room database (`searchAllBookmarksFlow`) with instant filtering.
  - *Add Bookmark FAB & Clipboard Pre-fill*: Floating action button supporting manual bookmark and bookmarklet creation, pre-populating clipboard links or scripts.
  - *Contextual Actions*: Menu features Open in new tab, Open in incognito (both hidden for bookmarklets), Copy link / Copy script, Share, Edit (BottomSheet with multiline script support), and Delete.
  - *Visual Parity & Favicons*: Distinctive `< / >` icon for scripts, file icon for local documents, and real website favicons loaded asynchronously via `FaviconManager`.

### 4.10. External Application Intent Handoff (`OpenInAppPromptDialog`)
- **Theme-Integrated Open in App Prompt**: Styled with `@color/dialog_clear_data_bg` (Google Light white `#FFFFFF` / Google Dark `#282A2D`), 24dp corner radius, and `?attr/colorOutline` border matching all native Onyx dialogs.
- **Dynamic Package Manager Icon & Title Resolution**: Queries Android's `PackageManager` via `targetIntent` to extract native app icons (`resolveInfo.loadIcon` / `getApplicationIcon`) and labels ("Open in Telegram?"). If no specific app icon is resolved, falls back to Lucide `ic_external` with `?attr/colorPrimary` tint inside a 56×56dp squircle tile (`bg_box_tile`). Raw package name strings are sanitized to clean generic titles if unresolvable.
- **Target URL & Destination Inspection Card**: Surfaces the target deep-link or website URL with middle ellipsizing inside `targetAppCard` for total user transparency before launching an external process.
- **Material 3 Tonal Button Hierarchy & Safe Dismissal**: Paired "Stay in Onyx" (`@color/btn_cancel_bg`) and "Open app" (primary with `ic_external` icon) buttons, with `onCancel` safely routing to the webpage fallback if the user dismisses the dialog via hardware back or touch outside.
- **Rotation Resilience & Safe Lifecycle Display**: Intent and app name are preserved across device configuration changes via `arguments: Bundle`. `showSafely()` verifies `!manager.isStateSaved` and prevents duplicate dialog stacking, eliminating `IllegalStateException`.
- **Pre-Flight App Verification & Same-Domain Loop Prevention**: `promptOrLaunchApp` and `tryOpenAppForHttpLink` verify that an external handler is actually installed (`isExternalAppInstalledForIntent`) before prompting. Internal page clicks within supported domains (e.g. browsing inside `youtube.com` or `reddit.com`) are exempt from prompts, and uninstalled schemes route seamlessly to web fallbacks or the Play Store.
- **Bounded Memory LRU**: Bounded 100-entry synchronized LRU cache manages `bypassAppInterceptUrls` to permanently prevent session memory leaks.

---

## 5. Architectural Coding Standards for AI Agents

1. **WebView Thread Boundaries**:
   - `shouldInterceptRequest` executes on Chromium's background network threads. Never access UI elements, touch `view.url`, or execute synchronous UI methods from this callback.
   - Rely on `@Volatile currentPageUrl` tracked via `onPageStarted` and request headers (`Referer`, `Origin`).

2. **Vector Drawables & UI Styling**:
   - Always sanitize vector assets to static `#FFFFFFFF` base colors and tint dynamically via `app:tint` or `setTint()`. Dynamic `?attr/...` inside vector XML paths causes crashes on older Android versions.
   - Use standard `sans-serif-medium` font family across themes for clean, legible typography.

3. **Lifecycle & Memory Management**:
   - Explicitly detach, stop (`stopLoading()`), and destroy (`destroy()`) WebViews when tabs are closed to prevent native memory leaks.
   - Inactive tabs must invoke `webView.onPause()` to halt background JavaScript timers and conserve CPU and battery.

4. **Security & Data Isolation**:
   - Guard `WebView.setWebContentsDebuggingEnabled(true)` strictly by `if (BuildConfig.DEBUG)` in `OnyxApplication.kt`.
   - User CA certificates in `network_security_config.xml` are restricted strictly to `<debug-overrides>`.
   - All persistent Room database operations must route through SQLCipher AES-256 encryption.

---

## 6. Current Status & Release Information

- **Current Release**: `v1.0.290`
- **Build Status**: Production signed APKs published across all ABIs (`arm64-v8a`, `armeabi-v7a`, `universal`, `x86_64`) on GitHub Releases.
- **Feature Tracking**: Complete feature statuses and technical verification notes are maintained in `FEATURES.md`.

### v1.0.290 Changes
- **Open in App Dialog Redesign**:
  - Re-architected `dialog_open_in_app_prompt.xml` and `OpenInAppPromptDialog.kt` to match Google Material 3 theming (`@color/dialog_clear_data_bg`, `?attr/colorOutline`, 24dp rounded corners).
  - Dynamically extracts high-resolution native app icons (`PackageManager.resolveActivity`/`getApplicationIcon`) and labels ("Open in Telegram?", "Open in YouTube?"), with Lucide `ic_external` fallback inside a 56×56dp squircle tile (`bg_box_tile`).
  - Added target destination card (`targetAppCard`) with deep-link/URL middle ellipsizing, alongside balanced "Stay in Onyx" (`@color/btn_cancel_bg`) and "Open app" buttons.
- **Media Engine Hardening & Video Placeholder Cleanup**:
  - Overrode `getDefaultVideoPoster()` to eliminate Android WebView's gray video box with black play arrow.
  - Guarded PiP layout restoration in `MainActivity` and DOM script, eliminating synthetic `resize` oscillations on responsive sites like GitHub.
  - Cleaned up foreground/background media handshake, deferring `MediaPlaybackService.start(...)` until actual background transition or screen-lock so videos do not auto-pause when background playback is active.

### v1.0.289 Changes
- **Site Settings Streamlining & Dead Desktop APIs Purge**:
  - Dropped 14 dead desktop-only APIs (WebUSB, Web Serial, WebHID, Web MIDI, WebXR Hand Tracking, Idle Detection, Local Font Enumeration, Web Bluetooth, PaymentRequest API, File System Access API, WebXR VR, Web NFC, Window Management API, and Storage Access API), eliminating >3,300 lines of dead code, unused string resources, orphaned drawables, and document-start JavaScript shim overhead.
  - Streamlined `SiteSettingsActivity` to 19 active mobile categories with dynamic allowed/blocked override count badges.
  - Retained Screen Wake Lock (`CATEGORY_WAKE_LOCK`) and WebRTC IP Handling (`CATEGORY_WEBRTC`) with clear UI controls.
  - Cleaned up lingering summary calls in `AllSitesSheet` and wired active status for Wake Lock and WebRTC.

### v1.0.288 Changes
- **Android Audio Focus Management (`AudioManager`)**:
  - `MediaPlaybackService` dynamically acquires and manages Android Audio Focus via `AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)` with `AudioAttributes.USAGE_MEDIA` and `delayedFocusGain = true`. Safely handles `AUDIOFOCUS_LOSS`, `AUDIOFOCUS_LOSS_TRANSIENT`, and `AUDIOFOCUS_GAIN` (auto-resuming playback after calls or alarms conclude).
- **Audio Becoming Noisy Protection (Brave Parity)**:
  - Registers `AudioManager.ACTION_AUDIO_BECOMING_NOISY` receiver to automatically pause media and abandon audio focus when headphones or Bluetooth devices disconnect.
- **Hardware Audio Playback Watchdog (`AudioPlaybackCallback`)**:
  - Registers `AudioManager.AudioPlaybackCallback` (Android 8.0+ / API 26+) to monitor physical audio mixing and release background wake locks when audio streams finish.
- **Hardened `userHitPause` Keep-Alive**:
  - Distinguishes real user gestures (touch/click within 1000ms or explicit notification actions) from background script auto-pauses in `MediaPlaybackManager.kt`.
- **Navigation Desktop Mode Resolution**:
  - Resolved `hostKey` reference in `applyUserAgentForUrl` via `prefs.cleanDomain(urlString)`.

