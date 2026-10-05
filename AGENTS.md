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

- **Current Release**: `v1.0.259`
- **Build Status**: Production signed APKs published across all ABIs (`arm64-v8a`, `armeabi-v7a`, `universal`, `x86_64`) on GitHub Releases.
- **Feature Tracking**: Complete feature statuses and technical verification notes are maintained in `FEATURES.md`.
