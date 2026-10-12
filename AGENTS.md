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
- **Pure Filter-List Evaluation & Kotlin LRU Decision Cache**: All ad, tracker, popunder, and clickjack blocking decisions are driven 100% by filter lists (54 compiled Brave filter lists + EasyList) in native `adblock-rust`. High-performance thread-safe in-memory LRU Decision Caches (`requestDecisionCache` with 8,192 entries and `checkUrlCache` with 4,096 entries) in `AdBlockEngine.kt` ensure `<0.01µs` repeat lookups in Kotlin memory without crossing the JNI boundary.
- **On-Demand Filter Fetching & First-Run Seeding (`FilterListManager`)**: Missing filter lists are downloaded on-demand into `filter_cache/` when toggled in Content Filters settings or during app startup via `FilterListManager.ensureDefaultFiltersCached()`. Downloaded rules are merged and compiled into `onyx_filters.bin` and the active in-memory Rust engine, scaling rule coverage from base EasyList (~35,500) to full multi-list protection (~135,000+ rules).
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

### 4.2. Media & Playback Subsystem (`MediaPlaybackManager` & `MediaPlaybackBridge`)
- **Partitioned Media Cookies & CDN Stream Playback (Brave Parity)**: Automatically permits third-party cookies for media subresources, video stream CDNs (such as HLS `.m3u8` playlists and segments), and embedded player iframes (e.g. `ravok.buzz`, `fireplayer.stream`), preventing HTTP 403 Forbidden on token/cookie-authenticated streams.
- **Brave `userHitPause` Architecture**: Monkey-patches `HTMLMediaElement.prototype.pause` and `play` to distinguish user taps (touch/click within 600ms) from background or visibility change auto-pauses. Automatically resumes background playback if paused without user intent.
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
- **Media Playback Lifecycle Guarding**: `MediaPlaybackService` tracks `@Volatile var isServiceRunning: Boolean` ensuring `updateState`, `updateProgress`, and `updateMetadata` never invoke `startForegroundService` when the service is inactive or media is paused, eliminating Android 12+ `ForegroundServiceStartNotAllowedException` crashes.
- **Notification Action Foreground Service Intents**: `DownloadNotificationHelper` and `MediaPlaybackService` build action PendingIntents via `PendingIntent.getForegroundService()` on Android 8.0+ (`Build.VERSION_CODES.O`), avoiding background service start exceptions when actions (Pause, Resume, Retry) are tapped from the notification shade.
- **Find-in-Page UI & Regex Escaping**: `FindInPageController` hooks `setRegexFindListener` to update match indicators in real-time, while `RegexFindBridge` quotes query literals via `JSONObject.quote()` and guarantees bidirectional highlight clearing via `webView.clearMatches()`.
- **Content Filter HTTP Redirect Resilience**: `FilterListManager.downloadFilterList()` supports up to 5 redirect hops across schemes and 3xx codes with base URL resolution, preventing mirror update failures.
- **SQLCipher KeyStore Recovery Assurance**: `AppDatabase.buildEncryptedDatabase()` eagerly invokes `openHelper.writableDatabase` so any KeyStore reset or passphrase discrepancy triggers recovery catch blocks immediately during database creation.
- **Non-Destructive Navigation & Tab State Preservation**: Hardware back presses at root history minimize/finish the browser activity without wiping the active tab URL from the database. Centralized `setActiveTab` in `TabManager` guarantees `lastActiveTabId` is kept synchronized across tab creation, closure, undos, and switching.
- **Reliable Clear Data on Exit**: Uses `NonCancellable` IO coroutine scope in `onDestroy` so database clearing never gets cancelled when the activity finishes, coupled with complete purging of WebView cache, cookies, WebStorage, HTTP authentication credentials, and form data.
- **Omnibox Quick Site Shields Access & Thread-Safe Counters**: Address bar SSL lock icon provides 1-tap direct access to `SiteShieldBottomSheetDialog`, while `OnyxWebView.pageBlockedCount` utilizes an `AtomicInteger` to prevent background thread increment races.
- **SSL Downgrade Protection**: Removed silent HTTP downgrades on SSL handshake errors, ensuring any certificate issues route to the secure SSL warning interstitial.

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
- **Omnibox Page Info & Permissions Panel (`SiteShieldBottomSheetDialog`)**: Address bar SSL lock icon provides instant access to connection security verification (valid HTTPS certificate status), per-site hardware permissions (Camera, Microphone, Location with 3-state selector: Allowed, Blocked, Ask every time), a 1-tap "Reset permissions" button that clears remembered hardware decisions, and a "Clear data for this site" action.
- **Selective Per-Origin Storage & Cookie Purging (`SiteDataManager`)**: Completely purges cookies, origin `WebStorage` (localStorage, IndexedDB), and HTTP authentication credentials strictly matching the target domain and parent root domain across both normal and isolated incognito profiles without invalidating sessions on other open tabs.
- **Modern 3-Option Web Permission Prompts**: Features standard 3-option prompts ("Always allow", "Allow this time", "Block") supporting session-isolated grants (`remember = false`) that do not persist across browser restarts.
- **Global Site Settings Activity (`SiteSettingsActivity`)**: Accessible via Settings → Privacy & Security → Site settings. Manages global policies ("Ask before accessing" vs "Blocked globally") for Camera, Microphone, and Location, provides granular Allowed/Blocked domain management sheets with 1-tap revocation (`SitePermissionCategorySheet`), and an "All sites" hub (`AllSitesSheet`) with real-time domain search and site data purging.
- **Downloads Categorical Filter Badge Counts**: Real-time item counters dynamically rendered inside categorical filter chips (`All (N)`, `APKs (N)`, `Images (N)`, `Videos (N)`, `Documents (N)`).

### 4.8. Dynamic Accent Color & Theme Customization Subsystem (`AccentColorManager`)
- **Balanced 8-Preset Palette & Two-Tier Color Engine**:
  - *Golden Luminance Balancing*: Presets are calibrated to match the exact luminance/saturation sweet spot of signature Coral Crimson `#DC4B64` (~58% lightness, ~68-90% saturation): Crimson `#DC4B64`, Coral Orange `#FF5C38`, Sunset Amber `#F59E0B`, Emerald Mint `#10C888`, Ocean Cyan `#06B6D4`, Electric Blue `#2F80ED`, Cyber Violet `#8B5CF6`, Neon Rose `#EC4899`.
  - *Tier 1 (Universal Theme Overlays)*: 8 pre-compiled `ThemeOverlay.Onyx.Accent.*` overlays providing `colorPrimary`, `colorSecondary`, and `colorPrimaryContainer` (`#26[HEX]`) applied dynamically in Activity `onCreate` before view inflation.
  - *Tier 2 (Precision Dynamic Tinting & Custom Hex)*: Accepts arbitrary 6-digit hex color codes (`#RRGGBB`). Euclidean distance matching maps custom colors to the closest preset theme overlay for base Android widgets, while `AccentColorManager` directly tints active tab strokes/headers (`TabsAdapter`), Tab Switcher FAB & pill drawables (`TabSwitcherBottomSheet`), home search widget avatars, popup menus, and bottom sheets.
- **Universal Dynamic Attribute Resolution**: All 40 layout XML files and vector/shape drawables (`bg_circle_danger`, `bg_chip_suggestion`, `bg_qr_frame`, `bg_tab_pill_selected`, `ic_check`) reference `?attr/colorPrimary` and `?attr/colorPrimaryContainer` rather than static `@color/primary`, ensuring 100% of UI elements immediately adapt when accent colors change.
- **Web Template & Error Page Integration**: Dynamically replaces `#DC4B64` with `preferences.accentColorHex` in `error_page.html` CSS variables (`--link-color`, `--primary-btn-bg`) and `LocalFileLoader` document styles so internal browser error and document pages harmonize with the selected theme.
- **Unified Appearance Sheet (`ThemePickerSheet`)**: Accessible via Settings → Appearance. Features real-time theme mode tinting, active swatch stroke outlines and checkmark indicators, live custom hex preview, and non-destructive on-dismiss recreation synchronization.
- **Settings Canvas & System Bar Inset Harmonization (`settings_canvas_bg`)**: Calibrated dark canvas background to refined Matte Charcoal `#161616` (replacing legacy reddish `#0B0305`), providing smooth neutral dark elevation behind `#202020` card containers. Edge-to-edge system bars across all canvas settings activities (`SettingsActivity`, `DownloadSettingsActivity`, `AccessibilitySettingsActivity`, `VideoSettingsActivity`, `SiteSettingsActivity`) dynamically track theme night mode via `WindowInsetsControllerCompat.isAppearanceLightStatusBars` and `isAppearanceLightNavigationBars`, ensuring system icons remain visible in both Light and Dark themes without double-padding conflicts.
- **High-Contrast `colorOnPrimary` Calibration**: Configured high-contrast `colorOnPrimary = #000000` for bright accent presets (Sunset Amber `#F59E0B`, Emerald Mint `#10C888`, Ocean Cyan `#06B6D4`) and dynamic luminance checking (`ColorUtils.calculateLuminance`) across buttons (`ThemePickerSheet`, `UserAgentPickerSheet`, `BookmarksActivity` FAB, clear history dialog), guaranteeing crisp readability and WCAG compliance across all presets and custom hex colors.
- **User Agent Spoofer Theme Synchronization (`UserAgentPickerSheet`)**: Completely removed hardcoded blue styles from custom UA badges, input outlines, apply buttons, and template selection checkmarks, mapping all interactive highlights to `AccentColorManager.getAccentColor()` and `?attr/colorPrimary`.

### 4.9. Bookmarks & Bookmarklets Subsystem (Chrome & Brave Parity)
- **Bookmarklet Execution Engine (`MainActivity.executeBookmarklet`)**: Evaluates `javascript:...` bookmarklets natively on the active tab via `webView.evaluateJavascript(script, null)` without page navigation or search redirection. Strips scheme, decodes percent-encoded sequences while preserving literal `+` operators, and displays execution feedback toast.
- **Omnibox & Suggestion Routing (`SearchController` & `SuggestionsAdapter`)**: Detects `javascript:` bookmark suggestions, renders them with `< / >` code icon (`ic_code`) and `"Bookmarklet (JavaScript)"` badge, and prevents `https://` prefixing or search engine queries when executed from omnibox or suggestion list.
- **Modern Bookmark Management (`BookmarksActivity` & `BookmarksAdapter`)**:
  - *Real-Time Search*: Live search bar querying SQLite Room database (`searchAllBookmarksFlow`) with instant filtering.
  - *Add Bookmark FAB & Clipboard Pre-fill*: Floating action button supporting manual bookmark and bookmarklet creation, pre-populating clipboard links or scripts.
  - *Contextual Actions*: Menu features Open in new tab, Open in incognito (both hidden for bookmarklets), Copy link / Copy script, Share, Edit (BottomSheet with multiline script support), and Delete.
  - *Visual Parity & Favicons*: Distinctive `< / >` icon for scripts, file icon for local documents, and real website favicons loaded asynchronously via `FaviconManager`.

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

- **Current Release**: `v1.0.277`
- **Build Status**: Production signed APKs published across all ABIs (`arm64-v8a`, `armeabi-v7a`, `universal`, `x86_64`) on GitHub Releases.
- **Feature Tracking**: Complete feature statuses and technical verification notes are maintained in `FEATURES.md`.
