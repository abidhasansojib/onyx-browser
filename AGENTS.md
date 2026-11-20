# AGENTS.md — Onyx Browser AI Agent Operational Mandates & Instructions

> **CRITICAL DIRECTIVE FOR ALL AI MODELS & AGENTS**:  
> Read this entire document before inspecting, modifying, or executing any task on this codebase. All rules defined herein are absolute, strictly enforced, and take precedence over default assistant behavior.

---

## 1. Project Overview & Technology Stack

**Onyx Browser** (`com.onyx.browser`) is a fast, lightweight, and privacy-oriented Android browser targeting Min SDK 26 (Android 8.0+) and Target SDK 35 (Android 15).

- **UI Framework**: Modern Jetpack Compose and Material 3 (`androidx.compose`) on the `main` branch with Compose BOM `2024.10.01` and Kotlin 2.0.21 compiler plugin; dual ViewBinding interop preserved for high-performance web and camera container embedding.
- **Engine**: Android System WebView hardened with Chromium multi-profile isolation, scriptlet injection, and network-level interception.
- **Content Blocking**: Brave `adblock-rust` engine compiled via Android NDK into `libadblock_bridge.so` across all 4 ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`).
- **Database**: SQLCipher AES-256 encrypted Room database.
- **Dependencies**: Centralized Gradle Version Catalog (`gradle/libs.versions.toml`).

For complete subsystem specifications and technical architecture, see:
- [Architecture & Core Subsystems](docs/ARCHITECTURE.md)
- [Android WebView Engineering Guide](docs/WEBVIEW.md)
- [Features & Roadmap](docs/FEATURES.md)
- [Contributing & Setup Guide](CONTRIBUTING.md)

---

## 2. Mandatory Operational Rules for All Agents

### RULE 1: Local Building Strictly Forbidden
- **Prohibited Operations**: Executing compilation or build commands locally on this Linux machine (including `./gradlew`, `cargo`, `rustc`, `cmake`, `ninja`, `make`, `docker build`, NDK compilation, or running Gradle daemons) is **STRICTLY FORBIDDEN**.
- **Reason**: The host system is a lightweight mobile Linux environment running inside Android with strict CPU, RAM, and thermal headroom.
- **Permitted Operations**: Lightweight CLI utilities, file editing, Python/Bash scripting, lightweight static checks, Git operations, and GitHub CLI (`gh`) are fully permitted.

### RULE 2: Explicit User Build Gate & Build Type Clarification
- **Default State**: Do **NOT** trigger GitHub Actions CI/CD workflows, compile code, or trigger remote builds during regular conversation.
- **Build Type Clarification**: When the user says **"build app now"** without specifying the build type, you **MUST ask the user** whether they want a **Release** or **Prerelease** build before triggering any workflow. Never guess or trigger prematurely.
- **Specific Build Commands**:
  - If the user specifies **"build release app"** (or chooses Release), build **Release only** (`gh workflow run build.yml -f build_type=Release`).
  - If the user specifies **"build prerelease app"** (or chooses Prerelease), build **Prerelease only** (`gh workflow run build.yml -f build_type=Prerelease`).
  - Debug builds are permanently removed and prohibited.

### RULE 3: Autonomous Remote Build & Auto-Fix Loop
When triggered for a build (after user confirms or explicitly requests Release or Prerelease):
1. **Push & Trigger**: Ensure all changes are committed and pushed to `origin main`, then trigger the workflow:
   - **Release**: `gh workflow run build.yml -f build_type=Release --ref main`
   - **Prerelease**: `gh workflow run build.yml -f build_type=Prerelease --ref main`
2. **Monitor Execution**: Track the active workflow run to completion using `schedule` timers and `gh run view <run_id>`. Do NOT poll in a busy while-loop; use the `schedule` tool.
3. **If Build Succeeds**:
   - **Release Build**:
     - Released to GitHub Releases as a full release.
     - Release notes are automatically categorized via `scripts/generate_release_notes.py` (Features, Bug Fixes, Shields, Performance, and Upstream).
     - DO NOT download artifacts to the device unless explicitly requested.
     - Report the release version tag, changelog, and GitHub release download links to the user.
   - **Prerelease Build**:
     - Released to GitHub Releases marked as a **Pre-release** (`applicationId` has `.prerelease` suffix).
     - Release notes are automatically categorized via `scripts/generate_release_notes.py`.
     - DO NOT download artifacts to the device unless explicitly requested.
     - Report the prerelease tag, changelog, and GitHub release download links to the user.
4. **If Build Fails (CRITICAL AUTO-REPAIR REQUIREMENT)**:
   - Retrieve the failed step logs via `gh run view --log-failed --job=<job_id>`.
   - Analyze the compiler error, Kotlin syntax error, resource collision, or ProGuard/R8 exception.
   - Apply the necessary code fixes directly in the repository.
   - Commit the fix, push to `origin main`, re-trigger the workflow, and repeat until 100% successful.

### RULE 4: Mandatory Documentation Maintenance
- Whenever a feature is added, a bug is fixed, an architectural decision is made, or a refactoring is executed, update this `AGENTS.md` and `docs/FEATURES.md` before concluding the turn.
- Deep subsystem details and technical architecture must be maintained in `docs/ARCHITECTURE.md`.
- WebView engineering standards must be maintained in `docs/WEBVIEW.md`.

### RULE 5: Keep Everything Simple, Minimal & Effective
- All documentation, README files, UI copy, and explanations must remain simple, minimal, and effective.
- Avoid hyperbolic AI buzzwords, repetitive explanations, and emoji clutter. Write like an experienced software engineer.

### RULE 6: Feature Tracking via `docs/FEATURES.md`
- `docs/FEATURES.md` is the single source of truth for tracking browser capabilities and verification status (`[x]` Working, `[-]` Known Issue, `[ ]` Planned).

### RULE 7: No Internal Agent Artifacts in Git
- Never commit internal agent scaffolding, plan files, design specs, or scratch directories (`docs/superpowers/`, `.superpowers/`, scratch scripts) into the repository.

### RULE 8: Golden Milestone Immutability
- **Current Golden Milestone / Role Model Reference**: Tag **`reference`** (commit `9a2ade9`) — verified working across WebAuthn passkeys (assertion signature verification with matching clientDataJSON, registration, autofill & password managers), adblocking & clickjack defusal, native video tap-to-show-controls, background audio/video playback, Picture-in-Picture (inline, fullscreen, cross-origin iframe, expand & 'X' close lifecycle), full-page appearance settings with refined "Address Bar position" dual-choice selector, robust clear-data-on-exit across minimize and Recents dismissal, unified context menu media downloads, and 12-hour automated upstream synchronization. Use this commit as the baseline reference if any future change regresses these subsystems.
- AI agents are **strictly forbidden** from automatically bumping or declaring a "Golden Milestone / Role Model" reference in `AGENTS.md` or `docs/FEATURES.md` unless explicitly commanded by the user.

### RULE 9: Professional Naming Standards (Strict Anti-Brand Prefix Rule)
- **Mandate**: NEVER prefix new or refactored classes, interfaces, composables, functions, files, or drawables with the brand name `Onyx`.
- **Reason**: The root package namespace (`com.onyx.browser.*`) already provides complete domain scoping. Prepending brand names to internal types (e.g., `BrowserWebView`, `AppTopBar`, `BrowserApplication`, `OnyxDialog`, `PreferenceItem`, `DownloadEngineManager`) is redundant Hungarian-style notation and considered amateurish.
- **Convention**: Always use clean, professional, Domain-Driven Design (DDD) naming conforming to idiomatic Android and Kotlin standards (e.g., `BrowserWebView`, `AppTopBar`, `BrowserApplication`, `AppDialog`, `PreferenceItem`, `DownloadEngineManager`). Follow architectural naming conventions seen in production mobile browsers (Chromium, Firefox Fenix, Brave Android).
- **Reference**: Detailed migration paths and find-and-replace recipes are maintained in `FILE_RENAMING_GUIDE.txt`.

---

## 3. Step-by-Step Instructions for Working in this Project

### 3.1 Operating in the Host Environment
- **Hardware Profile**: The workspace runs on a mobile Linux environment inside Android. CPU cycles, RAM, and thermal headroom are limited.
- **Forbidden Commands**: Never run `./gradlew`, `cargo`, `rustc`, `cmake`, `ninja`, `make`, `docker`, or any daemon processes locally.
- **Allowed Tools**: Use Python 3, Bash, standard Unix utilities (`grep`, `sed`, `find`, `awk`), Git, and GitHub CLI (`gh`).
- **Memory Conservation**: Do not load massive files entirely into memory in scripts; process line-by-line where possible. Keep Git trees clean and synchronized with `origin/main`.

### 3.2 Pre-Commit Static Verification Protocol
Since local Gradle builds cannot be run, agents must perform rigorous static verification before committing:

1. **Syntax & Bracket Balance Validation**:
   - Inspect all modified or newly created Kotlin/Java files to verify balanced braces (`{}`), parentheses (`()`), and quotes.
   - Run lightweight Python syntax check scripts to parse files and confirm no unclosed tokens or malformed constructs exist.
2. **Package & Import Cross-Referencing**:
   - Verify that the `package` header in every file matches its directory structure.
   - Grep the repository to confirm that all imported classes and methods exist and match their expected signatures.
   - When renaming or moving a class, check all call sites:
     - Kotlin/Java source files in `:app` and `:core:common`.
     - Layout XML files (`tools:context`, custom views).
     - `AndroidManifest.xml` (activities, services, broadcast receivers).
     - `app/proguard-rules.pro` (`-keep` rules).
     - Test files in `app/src/test/`.
3. **XML Resource Validation**:
   - Ensure every view ID referenced via ViewBinding exists in the respective layout XML.
   - Always pair new color resources across `res/values/colors.xml` and `res/values-night/colors.xml`.
   - Use theme attributes (`?attr/colorControlNormal`, `?attr/colorPrimary`) for icon tints and surface backgrounds rather than hardcoded hex colors.
4. **JavaScript Bridge & Interface Safety**:
   - Renaming a Kotlin bridge class (e.g., `ShieldBridge`, `FormBridge`, `BlobBridge`) must **never** modify the exposed JavaScript interface name registered on the DOM (`window.OnyxShieldBridge`, `window.onyxFormBridge`, `window.onyxBlobBridge`, `window.onyxTouchBridge`, `_OnyxPasskeyBridge`).
   - Retain backward-compatible `typealias` declarations if external callers or test suites reference previous class names.
5. **Git Hygiene**:
   - Inspect changes before staging using `git diff` and `git status`.
   - Never stage scratch files, temporary Python scripts, or artifacts.
   - Use standard conventional commit format: `<type>(<scope>): <concise message>`.

### 3.3 Component & Class Naming Conventions (Professional Naming Standard)

> [!IMPORTANT]
> **Strict Anti-Prefix Rule**: NEVER prefix new or refactored classes, composables, functions, or files with `Onyx`. Use descriptive, domain-specific names (`BrowserWebView`, `BrowserApplication`, `AppTopBar`, `PreferenceItem`, `CardContainer`, `DownloadEngineManager`).
> Prepending marketing/brand names to internal symbols within the `com.onyx.browser.*` namespace is amateurish. All future contributions must adhere to clean Domain-Driven Design (DDD).

Follow standardized architectural suffixes across all modules:

| Component Type | Suffix Standard | Examples |
| :--- | :--- | :--- |
| **Lifecycle & Navigation Orchestrators** | `*Coordinator` | `BrowserMenuCoordinator`, `WebContextMenuCoordinator`, `NavigationCoordinator`, `TabCoordinator`, `PageExportCoordinator`, `FileChooserCoordinator`, `WebChromeCoordinator` |
| **Bound UI / View State Controllers** | `*Controller` | `ToolbarLayoutController`, `SearchController`, `FindInPageController`, `HomeScreenController`, `PipController` |
| **Modal Sheets** | `*BottomSheet` | `MenuBottomSheet`, `SiteShieldBottomSheet`, `WebPermissionBottomSheet`, `ClearOnExitBottomSheet`, `InstallUserScriptBottomSheet`, `ThemePickerBottomSheet`, `UserAgentPickerBottomSheet`, `ManageShortcutsBottomSheet` |
| **JavaScript Bridges** | `*Bridge` | `ShieldBridge`, `FormBridge`, `BlobBridge`, `TouchBridge`, `NotificationBridge`, `PasskeyWebAuthnBridge`, `RegexFindBridge` |
| **Domain Preferences** | `*Preferences` | `AppearancePreferences`, `ShieldPreferences`, `MediaAndDownloadPreferences`, `SearchPreferences` |
| **Data Repositories** | `*Repository` | `HistoryRepository`, `BookmarkRepository`, `DownloadRepository`, `SitePermissionRepository` |

### 3.4 Packaging & Directory Standards
Organize all new and existing classes into their dedicated domain packages:

- **`com.onyx.browser.web.bridge`**: All `@JavascriptInterface` bridge classes and WebMessage channel handlers (`BlobBridge`, `ChromeEnvironmentBridge`, `FormBridge`, `NotificationBridge`, `PasskeyWebAuthnBridge`, `RegexFindBridge`, `ShieldBridge`, `TouchBridge`, `WebGLCompatibilityBridge`).
- **`com.onyx.browser.web.adblock`**: Web-layer adblocking, scriptlet injection, domain alias manager, service worker helper, and hydration fixes (`AdBlockDocumentStart`, `AdBlockDomainManager`, `AdBlockServiceWorkerHelper`, `DsdHydrationFix`).
- **`com.onyx.browser.web.interceptor`**: `RequestInterceptor` chain implementations (`RealInterceptorChain`, `HttpsUpgradeInterceptor`, `AuthBypassInterceptor`, `AdBlockRequestInterceptor`, `PrivacyUrlSanitizer`).
- **`com.onyx.browser.web.navigation`**: External URL scheme and intent routing (`ExternalNavigationHandler`).
- **`com.onyx.browser.web.translate`**: In-page translation bridge and scriptlets.
- **`com.onyx.browser.download`**: Download engine, service, notification helper, handlers, and download receivers (`DownloadEngine`, `DownloadForegroundService`, `DownloadHandler`, `DownloadCompleteReceiver`).
- **`com.onyx.browser.autofill`**: System `AutofillManager` and Credential Manager integration (`AutofillHelper`).
- **`com.onyx.browser.privacy`**: Privacy lifecycle cleanup services and helpers (`ClearOnExitService`, `ClearDataOnExitHelper`).
- **`com.onyx.browser.ui.browser`**: Coordinators and controllers managing browser UI, tabs, navigation, toolbar, and overlays.
- **`com.onyx.browser.ui.settings.shields`**: Shields, blocking level, filter list, HTTPS, DoH, and clear-on-exit settings.
- **`com.onyx.browser.ui.settings.site`**: Site settings, per-site permissions, and all-sites management.
- **`com.onyx.browser.ui.settings.userscript`**: Userscript manager, editor, install bottom sheet, and list adapter.
- **`com.onyx.browser.ui.settings.appearance`**: Theme picker, address bar customization, and visual display settings.

---

## 4. Directory Layout & Architecture Map

```text
onyx-browser/
├── .github/
│   ├── ISSUE_TEMPLATE/             # Structured YAML issue forms (bug reports & feature requests)
│   ├── PULL_REQUEST_TEMPLATE.md    # Standardized Pull Request review & privacy/security checklist
│   └── workflows/
│       ├── build.yml               # Rust NDK compile, Lucide sync, CI unit test gate, Gradle Release/Prerelease APKs
│       └── sync_upstream.yml       # Automated Brave adblock-rust native engine watchdog & notification workflow
├── app/
│   ├── schemas/                    # Exported Room database schemas (room.schemaLocation)
│   ├── src/main/
│   │   ├── assets/                 # easylist_rules.txt, eruda.min.js, scripts/ (WebAuthn, media, procedural JS), font assets
│   │   ├── java/com/onyx/browser/
│   │   │   ├── MainActivity.kt     # SingleTask primary activity, toolbar, and window coordinator
│   │   │   ├── BrowserApplication.kt  # Startup lifecycle, DI container, SQLCipher init, Safe Browsing setup
│   │   │   ├── autofill/           # AutofillHelper system Autofill & Credential Manager domain utility
│   │   │   ├── data/               # FilterListManager, Room DB, DAOs, repository/, preferences/ domain stores
│   │   │   ├── di/                 # AppContainer, DefaultAppContainer, AppViewModelFactory
│   │   │   ├── download/           # DownloadEngine, DownloadHandler, DownloadNotificationHelper, DownloadForegroundService
│   │   │   ├── incognito/          # IncognitoNotificationReceiver, isolated profile manager
│   │   │   ├── media/              # MediaPlaybackService and MediaPlaybackBridge
│   │   │   ├── nativebridge/       # AdBlockEngine JNI bindings to libadblock_bridge.so
│   │   │   ├── privacy/            # ClearOnExitService, ClearDataOnExitHelper privacy lifecycle cleanup
│   │   │   ├── ui/                 # ViewBinding presentation layer
│   │   │   │   ├── browser/        # Coordinators (*Coordinator) & View state controllers (*Controller)
│   │   │   │   ├── common/         # Common UI dialogs and pickers
│   │   │   │   ├── downloads/      # Download list UI and sheets
│   │   │   │   ├── home/           # Homepage shortcuts and quick actions
│   │   │   │   ├── menu/           # MenuBottomSheet, SiteShieldBottomSheet, ImageSearchPickerBottomSheet, ElementPickerManager
│   │   │   │   ├── settings/       # Settings activities partitioned by feature domain
│   │   │   │   │   ├── appearance/ # AppearanceActivity, AddressBarSettingsActivity, ThemePickerBottomSheet
│   │   │   │   │   ├── shields/    # ShieldsActivity, ContentFiltersActivity, filter sheets
│   │   │   │   │   ├── site/       # SiteSettingsActivity, SingleSiteSettingsBottomSheet, AllSitesBottomSheet
│   │   │   │   │   └── userscript/ # UserScriptsActivity, UserScriptEditorActivity, UserScriptAdapter
│   │   │   │   ├── tabs/           # TabSwitcherBottomSheet, ClearBrowsingDataDialog
│   │   │   │   └── viewmodel/      # UDF ViewModels (BrowserViewModel, HistoryViewModel, BookmarksViewModel, DownloadsViewModel)
│   │   │   ├── update/             # AppUpdateManager, AppUpdatePromptHelper, AppUpdateDownloader (in-app updater)
│   │   │   ├── userscript/         # Tampermonkey/Violentmonkey engine, metadata parser, matcher, bridge
│   │   │   └── web/                # Hardened Chromium WebView subsystem
│   │   │       ├── adblock/        # AdBlockDocumentStart, AdBlockDomainManager, AdBlockServiceWorkerHelper, DsdHydrationFix
│   │   │       ├── bridge/         # BlobBridge, FormBridge, PasskeyWebAuthnBridge, ShieldBridge, TouchBridge, etc.
│   │   │       ├── interceptor/    # RequestInterceptor, RealInterceptorChain, AdBlockRequestInterceptor, HttpsUpgradeInterceptor
│   │   │       ├── navigation/     # ExternalNavigationHandler (scheme/intent resolution)
│   │   │       └── translate/      # CSP-immune in-page DOM translation engine
│   │   └── res/                    # Google Theme styles (Light/Dark/AMOLED), layouts, vectors
│   ├── src/test/                   # JUnit 4, MockK, and Coroutines unit test suite
│   └── build.gradle.kts            # App dependencies, NDK configuration, Room KSP schema export, deterministic signing
├── core/
│   └── common/                     # Shared multi-module utilities (DispatcherProvider, ResultState)
├── docs/                           # Documentation assets (logo.png), FEATURES.md, ARCHITECTURE.md, WEBVIEW.md
├── gradle/
│   └── libs.versions.toml          # Centralized Gradle Version Catalog for dependencies and plugins
├── rust_engine/                    # Native Rust NDK Bridge (libadblock_bridge.so)
├── scripts/                        # bootstrap_dev.sh, icon sync, and filter list synchronization automation
├── .editorconfig                   # Cross-IDE formatting rules (Kotlin, Rust, XML, YAML, TOML, Markdown)
├── CONTRIBUTING.md                 # Open-source contributor setup, module architecture, and PR guidelines
└── SECURITY.md                     # Security architecture overview and vulnerability reporting policy
```

---

## 5. Architectural & Coding Mandates for AI Agents

1. **Native Views Over Compose**:
   - Strictly write native Android XML Views and ViewBinding.
   - Do NOT introduce Jetpack Compose dependencies or `@Composable` components.
2. **Resource & Color Consistency**:
   - Always pair color resources between `res/values/colors.xml` and `res/values-night/colors.xml`.
   - Use theme attributes (`?attr/colorControlNormal`, `?attr/colorPrimary`) for icon tints and surface backgrounds.
   - Compose theming (`OnyxTheme`) must dynamically resolve `accentColor` via `AccentColorManager.getAccentColor(context)` by default to ensure settings-driven accent colors propagate across all Compose screens and dialogs without hardcoding `#FF5C38`.
   - Tab Switcher UI Consistency: Symmetrical top bar with 26dp search button (`ic_search`), 44dp segmented control featuring identical 38dp x 38dp squircle buttons for normal mode (tab count inside 20dp box with 5dp corners) and incognito mode (clean incognito silhouette without tab count badges). Tab cards in `TabSwitcherBottomSheet` are sized at 230dp height with 18dp rounded corners, 42dp single-line header with asynchronous favicon loading (`rememberFavicon`), top-aligned snapshot previews, and bidirectional swipe-to-dismiss gestures with fluid spring physics, dynamic alpha fade, and velocity fling handling. Bottom action bar blends seamlessly with the canvas background (avoiding elevated surface shelf artifacts) and utilizes solid 44dp x 44dp rounded squircles (`surfaceVariant` container background) for secondary actions (`ic_delete` for clear browsing data/history, `ic_tab_close` for close all tabs) alongside a 48dp circular accent-tinted FAB for new tabs (`ic_add` with white tint).
   - Resource shrinking (`isShrinkResources = true`) is enabled for Release and Prerelease builds. All native icons (`ic_*`), background shapes (`bg_*`), vector silhouettes (`vector_*`), and manifest configs (`res/xml/*`) are strictly protected via `res/raw/keep.xml` (`tools:shrinkMode="safe"`); never delete or rename keep rules that safeguard runtime resources.
3. **Database & Persistence Security**:
   - All persistent Room database operations must route through SQLCipher AES-256 encryption via `OnyxDatabase`.
   - Never write sensitive browsing records or authentication data into plain SQLite or unencrypted SharedPreferences.
4. **Lifecycle & Coroutine Safety**:
   - Always bind UI-related coroutines to `viewLifecycleOwner.lifecycleScope` with `repeatOnLifecycle`.
   - Database writes and critical cleanup actions must use `NonCancellable` context to prevent corruption on sudden dismissals.
5. **Git Operations & Clean Commits**:
   - Keep commit messages concise and conventional (e.g. `feat(adblock): ...`, `fix(tabs): ...`, `docs: ...`).
   - Work cleanly on `main` and keep the tree synchronized with `origin/main`.
6. **Rolling Odometer Versioning Scheme**:
   - `MAJOR.MINOR.PATCH` increments automatically from GitHub Actions run counter (`RUN_NUM`):
     - `MAJOR = 1 + (RUN_NUM / 1000)`
     - `MINOR = (RUN_NUM / 100) % 10`
     - `PATCH = RUN_NUM % 100`
   - Patch increments 0..99, rolling over to Minor at 100. Minor increments 0..9, rolling over to Major after 9 (e.g., `1.0.99` -> `1.1.0`, `1.9.99` -> `2.0.0`).
7. **Authentication, Autofill & WebAuthn Bridge Compatibility**:
   - WebAuthn polyfills (`PasskeyWebAuthnBridge` in `com.onyx.browser.web.bridge`) must be injected on all login domains (e.g., GitHub, Google, Microsoft) and omitted strictly on Meta's internal challenge frames (`fbsbx.com`) and Meta auth domains.
   - `@JavascriptInterface` methods in `PasskeyWebAuthnBridge` execute on Chromium's background `JavaBridge` thread and must NEVER read `WebView` properties (`webView.url`) off the Main UI thread (`mainHandler.post`).
   - `PublicKeyCredential.isConditionalMediationAvailable()` must return `Promise.resolve(false)` (and hold `mediation: 'conditional'` requests passively until aborted) so login pages do not auto-launch modal `CredentialManager` sheets on page load.
   - `PasskeyWebAuthnBridge` must pass verified web `origin` + `clientDataHash` (never falling back to `origin = null` native app credentials), construct canonical unescaped `clientDataJSON` matching `clientDataHash` and inject it into assertion and registration responses (overriding provider placeholder or package-name client data so Relying Party SHA-256 hash checks verify against authenticator signatures), normalize all Base64 fields to canonical unpadded Base64URL, extract CBOR `attestationObject` metadata (`authenticatorData`, DER SPKI `publicKey`, `publicKeyAlgorithm`), and support W3C `PasswordCredential` (`GetPasswordOption` / `CreatePasswordRequest`) alongside Android `AutofillManager` (`AutofillHelper.getActivePasswordManager` via `autofillServiceComponentName` in `com.onyx.browser.autofill`).
   - `_OnyxPasskeyBridge` must never be deleted from the window or overwritten with `undefined` on login pages.
   - `navigator.userAgentData` must polyfill authentic Chrome/Chromium brands without leaking `"Android WebView"` to ensure Google Sign-In and OAuth flows succeed without `403 disallowed_useragent` blocks.
8. **Adblock Engine & Network Interception Standards**:
   - `adblock-rust` rule parsing in `rust_engine/src/lib.rs` must include full `PermissionMask::from_bits(u8::MAX)` permissions so privileged scriptlets (`trusted-set-cookie`, `trusted-replace-fetch-response`, etc.) are compiled and injected.
   - Resource type classification in `BrowserWebViewClient` and `AdBlockServiceWorkerHelper` must use case-insensitive header lookups (`Accept`, `Sec-Fetch-Dest`, `Sec-Fetch-Mode`, `Ping-To`, `Range`) to support HTTP/2 and HTTP/3 multiplexed requests.
   - Dual-pass checking must be performed for CNAME uncloaked requests: test uncloaked destination first, and if allowed, test the original alias to catch rules explicitly targeting cloaked first-party domains.
   - `$csp` directives from `AdBlockEngine.getCspDirectives` must be injected early at document start via `<meta http-equiv="Content-Security-Policy">`, stripping `<meta>`-invalid directives (`sandbox`, `frame-ancestors`, `report-uri`) and rewriting `worker-src 'none'` to `worker-src blob:` so `https:` ServiceWorkers remain blocked while MSE `blob:` Web Workers (`hls.js`, `dash.js`) can demux video streams.
   - Never pre-define a global `window.google.ima` stub on all pages; let blocked `ima3.js` scripts return HTTP `403` (`onerror` fallback), `$redirect=google-ima.js` rules serve Brave's full IMA surrogate, and `@@||imasdk.googleapis.com` exception rules load normally.
   - Synthetic HTTP `200 OK` VAST `<VAST version="3.0"/>` XML and `$redirect` surrogate responses must echo `Access-Control-Allow-Origin: <Origin>` with `Access-Control-Allow-Credentials: true` when an `Origin` header is present, and never return synthetic 3xx `WebResourceResponse` redirects from `shouldInterceptRequest` (which Android WebView does not follow).
   - Subresource Interception Allocations & Fast-Path Optimization: `BrowserWebViewClient` and `AdBlockServiceWorkerHelper` must supply explicit `Content-Length: 0` (or exact byte size) on intercepted subresource responses, use the stateless `EmptyInputStream` singleton for 0-byte responses, and pre-allocate immutable wildcard CORS header maps (`EMPTY_403_WILDCARD_HEADERS`, `EMPTY_JS_WILDCARD_HEADERS`, etc.) to eliminate garbage collection churn and immediately terminate Blink's network subresource pipes without waiting for stream EOF.
   - Never block authentication endpoints (Google Sign-In, Meta, Microsoft, Apple), bot challenges (Cloudflare Turnstile, Arkose, reCAPTCHA, hCaptcha), or media streaming segments.
   - Clickjack defuser CSS/listeners and `defuseGeneralVideoAds` in `AdBlockDocumentStart` must never match generic `overlay` / `player-overlay` classes, intercept `pointerdown` on video players, synthetically toggle `video.play()` / `video.pause()`, or match non-ad player containers/controls (`.vjs-skip-button`), preserving native playback and tap-to-show-controls behavior across YouTube and HTML5 players.
   - Declarative Shadow DOM (DSD) Progressive Hydration Safety: On `onPageFinished`, `DsdHydrationFix` must execute to remove `dsd-pending` attributes from document roots and open shadow roots so pages whose hydration scripts were blocked by ad/tracker filters never remain stuck with `[dsd-pending]{display:none}` blank screens.
   - Background Filter List Synchronization: `FilterUpdateWorker` manages daemon-free background filter rule updates via WorkManager (`JobScheduler`). It must use `NetworkType.CONNECTED` to support updates across all network types (Wi-Fi and Cellular), schedule dynamically using `ExistingPeriodicWorkPolicy.UPDATE` when the user updates the interval in `ContentFiltersActivity`, write `onyx_filters.bin` atomically using a temporary file (`.tmp`) to prevent corrupted FlatBuffers caches on sudden worker process death, and never compete with `BrowserApplication.onCreate` startup initialization.
9. **Media Playback & Picture-in-Picture Standards**:
   - Audio focus is owned by the Chromium WebView media session. `MediaPlaybackService` must NOT request its own audio focus (a second `AUDIOFOCUS_GAIN` in the same process makes WebView pause and causes a pause/resume fight). The service provides the `mediaPlayback` foreground lifecycle, MediaSession, notification, wake/Wi-Fi locks and the noisy-disconnect pause.
   - Media Session Position & Prototype Mutations: `media_playback_observer.js` intercepts `navigator.mediaSession.setPositionState` to relay live progress to `OnyxMediaBridge.onMediaProgress` (for Web Audio and MSE players like Spotify Web), wraps `window.MediaMetadata.prototype` property setters (`title`, `artist`, `album`, `artwork`) to catch in-place track mutations in SPAs, normalizes relative artwork URLs against `document.baseURI`, picks highest-resolution artwork via W3C `sizes` pixel-area calculation (`artworkArea`), and falls back through OpenGraph, Twitter card, Apple touch icon, and favicons.
   - `MediaPlaybackService` supports both network HTTP/HTTPS and inline Base64 `data:image/` artwork URLs, and throttles progress-driven notification rebuilds (5s service-side, 3s bridge-side except on user seek/scrub jumps) to prevent binder IPC jank.
   - Media is never paused in `onPause()` (PiP entry, dialogs, multi-window). `onStop()` decides: keep the media tab alive when background play is on, otherwise pause media explicitly (`WebView.onPause()` alone does not stop HTML5 audio).
   - Background auto-resume of non-user pauses is decided natively in `MediaPlaybackBridge.handleMediaPaused` only while the app is stopped, never during calls, and is rate-limited (3 per 15s). Touch-intent heuristics are ignored while backgrounded.
   - Service commands use `startService()` once the service is running; `startForegroundService()` only for the first start. After a track ends in background the service stays foreground (paused) for playlist continuation.
   - Picture-in-Picture on Android 12+ (API 31+) must dynamically arm `setAutoEnterEnabled(true)` strictly when video (never audio-only) is actively playing on the displayed foreground tab (`PipController.isActiveTabVideoPlaying`) and disarm on pause, tab switch, or outgoing internal activity launches. On Android 8-11 (or 12+ when auto-enter is not armed, tracked by `PipController.isAutoEnterArmed`), `onUserLeaveHint()` handles Auto-PiP transitions.
   - PiP exit resolution (`PipController.pendingPipCloseCheck`) must deterministically distinguish expanding PiP back to the browser (`onResume` clears flag, playback continues) from dismissing PiP via 'X' (`onStop` without `onResume` while screen is interactive marks `isExplicitUserPause = true` and pauses media).
   - DOM video isolation (`isolateVideoForPipScript`) must style strictly the single active `[data-onyx-pip-video="true"]` element via stylesheet rules without clobbering inline styles or promoting secondary `<video>` / helper `<iframe>` nodes, and `hideCustomFullscreenVideo()` must never remove `fullscreenControlsOverlay` from `fullscreenCustomViewContainer`.
   - All `setPictureInPictureParams()` calls must be shielded against OEM framework IPC exceptions (`safeSetPictureInPictureParams`), and aspect ratios must be clamped within `[0.5, 2.0]`.
   - Never recreate `MainActivity` on system theme changes while in PiP (`maybeDeferNightModeRecreate` with `uiMode` in `android:configChanges`).
   - Lockscreen playlist transitions must enforce a 2000ms debounce grace gap on `onMediaEnded` before tearing down foreground services.
   - Battery governor must suspend non-playing WebViews with `onPause()` while keeping JavaScript timers active exclusively on the media-playing tab.
10. **Navigation, Downloads & JavaScript Execution Standards**:
   - JavaScript pseudo-URLs (`javascript:...`) clicked in web pages or bookmarked must execute directly via `evaluateJavascript()` in `shouldOverrideUrlLoading`, bypassing Chromium top-level navigation restrictions on script URLs.
   - Synthetic URL schemes (`javascript:`, `about:`, `data:`, `blob:`, `file:///android_asset/`, `file:///android_res/`) must be classified as synthetic in `BrowserWebView.isSyntheticOrDataUrl` so they never clobber active page state, user agents, or history.
   - POST form download triggers (e.g. `javascript:document.getElementById('id').submit();` or download buttons submitting forms via POST) must be captured at document-start via `FormBridge` / `FormPostRegistry`, matched on `DownloadListener`, and executed via HTTP POST in `DownloadEngine` with accurate `Origin`, `Referer`, and `Content-Type` headers.
   - Download triggers spawning blank popup tabs (`target="_blank"`) must retire the unpromoted popup tab once the download is enqueued, preventing zombie tabs.
   - Context Menu & Media Downloader Unified Routing: Tap-and-hold context menu actions ("Save video", "Save image", "Download link") and image preview dialogs must never bypass Onyx's internal downloader or dispatch directly to Android's system `DownloadManager`. All download actions route through `DownloadHandler.handleDownload` to strictly honor user preferences in Settings > Downloads (Ask before download / `DownloadPromptBottomSheet`, Onyx internal multi-threaded downloader, or external downloader handoff). The download asking menu (`DownloadPromptBottomSheet`) is built in Compose matching the canonical layout and settings theme (40dp circular icon header with title, subtitle, and close button; Card 1 with 48dp extension badge, editable filename field with clear button, file size pill badge, and MIME label; Card 2 with web icon, domain title, copy URL button, URL preview, and forwarded session cookies/headers status badge; 50dp primary default download button, 50dp outlined external downloader button, and cancel button).
   - Desktop Mode, Canonical Domain Persistence & Network-Level Client Hints: Follows professional mobile browser standards with canonical eTLD+1 scoping (`getCanonicalDesktopKey`) and auto-fitting viewport scale. Toggling Desktop Site in the 3-dot menu or Site Shield persists the preference across the entire registrable base domain (e.g. toggling desktop mode on `google.com` persists and applies across new tabs, `news.google.com`, `images.google.com`, etc.). Seamlessly translates mobile subdomains (`resolveDesktopUrl` and `resolveMobileUrl`: `m.youtube.com` <-> `www.youtube.com`, `m.facebook.com` <-> `www.facebook.com`, `en.m.wikipedia.org` <-> `en.wikipedia.org`, etc.) on toggle. New tabs opened from a parent tab inherit explicit desktop state. Synchronizes network-level Client Hints via `WebSettingsCompat.setUserAgentMetadata` (`Sec-CH-UA-Mobile: ?0` for desktop, `?1` for mobile, `Sec-CH-UA-Platform: "Windows"`, and sanitizing `"Android WebView"` brands) before initial HTTP requests, with layout viewport pinned to 980px and dynamic overview zoom-out scaling (`initial-scale = screenWidth / 980`) resetting on reload to prevent horizontal overflow.
   - Popup Window (`onCreateWindow` / `target="_blank"`) & External Link Promotion: New tabs spawned from web links (`window.open`, `target="_blank"`) start with `url = ""` while Chromium asynchronously begins loading the destination. `displayPopupTab` passes the target URL (`targetUrl`) to `tabManager.updateTabUrlAndTitle`, updates the tab state before selection, and `displayTab` / `showWebView` verify `webView.url`, `webView.pendingMainFrameUrl`, or `webView.isPopupTab` before checking `tab.url`, preventing legitimate web link popups (e.g. Facebook post links, OAuth popups) from prematurely triggering `showHomeScreen()` and detaching the loading WebView.
   - Professional Tab Lifecycle & Navigation Integrity:
     - Accurate `window.close()` Targeting: `onCloseWindowCallback` retrieves `(closingWebView as? BrowserWebView)?.tabId` to close the specific tab that invoked `window.close()`, never closing `tabManager.activeTab.value` indiscriminately to avoid terminating foreground reading tabs during background OAuth or auxiliary window closures.
     - Adjacent Tab Selection on Tab Close: When closing an active tab without a live `parentTab`, `TabCoordinator.closeTab` selects the adjacent tab (`updated[originalIndex.coerceIn(0, updated.size - 1)]`) rather than jumping across the entire tab strip to `updated.last()`.
     - Non-Destructive Tab Switching: `showWebView` reloads tabs only when an explicit `forceUrl` is supplied or when a tab was not yet loaded (`!isCurrentLoaded`), never reloading live tabs when switching back and forth, strictly preserving DOM state, form entries, SPA client state, and scroll positions.
     - Transient Popup Retirement: Popup tabs created via `target="_blank"` or script navigations that initiate file downloads or hand off external app schemes (`tg://`, `whatsapp://`, `intent://`, `market://`) without existing navigation history (`!canGoBack()`) are automatically closed upon handoff, returning the user to the originating page without leaving blank white tabs.
     - Scoped Main-Frame Ad Blocking: `isPopupAd` queries Brave adblock engine `$main_frame` rules strictly in Aggressive blocking mode (aligning with Brave's `DomainBlockingType::kNone` in Standard mode), eliminating false-positive tab closures on legitimate user clicks that encounter affiliate or analytics redirects.
     - Synthetic Error Page Protocol Parity: `WebErrorHandler` maintains explicit definitions for all canonical Chromium network error codes, including `net::ERR_INVALID_RESPONSE` and `net::ERR_INVALID_CHUNKED_ENCODING`, preventing protocol or malformed server response errors from degrading into generic `ERR_FAILED` pages.
    - Download List, Multi-Selection & Action Standards: In `DownloadsActivity`, download items render in a borderless, clean Material list with subtle dividers and rich file status information (formatted file size, relative date/time, and status prefixes for deleted/failed/cancelled/paused states with dimmed opacity). Tap-and-hold (long-press) activates Multi-Selection Mode (`selectedIds`) supporting batch file deletion, batch sharing (`ACTION_SEND_MULTIPLE`), and select/deselect all actions. Clicking the 3-dot overflow menu opens `DownloadItemOptionsMenuSheet` (Material 3 bottom sheet) offering Open/Install/Play, Open in file manager, Share file, Download again, Copy link, Open website, Rename file, View details, and Delete/Remove from list. File details route to `DownloadDetailsSheet` with full file metadata, real-time asynchronous SHA-256/MD5 computation, live checksum comparison verification, and one-tap copy shortcuts.
11. **UI Presentation & Toolbar Customization Standards**:
   - Full-page settings activities must use native `CoordinatorLayout` + `MaterialToolbar` with edge-to-edge system insets and avoid floating dialogs for primary preference screens.
   - Address bar button customization (`isHomeReplacedWithRefresh`) must update dynamically in `onResume()` and `updateAddressBarDisplay()` without requiring activity recreation.
   - Address bar position customization (`isAddressBarAtBottom`: Top vs. Bottom): Configured in `Settings > Appearance > Address bar customization` under `Address Bar position` via a floating dual-choice card with phone silhouette graphics, "Top"/"Bottom" text labels, dynamic accent-tinted selection badges, and spring physics micro-interactions (`PathInterpolator(0.22f, 1f, 0.36f, 1f)`). In `MainActivity`, dynamically adjusts `ConstraintSet` for `topBar`, `topBarDivider`, `progressBar`, `contentContainer`, `searchOverlay`, and `findInPageBar`, alongside system window insets, keyboard (IME) bottom offsets, and auto-hide scroll animations without requiring activity recreation or disrupting active tabs.
12. **Userscript Engine & GM_* Bridge Standards**:
   - Parse Tampermonkey / Violentmonkey metadata headers (`// ==UserScript==` ... `// ==/UserScript==`: `@name`, `@version`, `@match`, `@include`, `@exclude`, `@run-at`, `@grant`, `@connect`, `@require`, `@resource`, `@noframes`, `@unwrap`, `@updateURL`, `@downloadURL`) with localized tag support (`@name:zh-CN`).
   - Store script code bodies and external dependencies on the file system (`context.filesDir/userscripts/<id>.user.js`, `<id>.requires.js`, `<id>.resources.json`) for 100% offline startup and zero network latency, bypassing SQLite blob limits.
   - Inject scripts via direct V8 evaluation (`(0, eval)(_code)`) immune to site Content Security Policy (CSP) and Trusted Types restrictions. In isolated execution mode, bridge `@require` UMD library exports onto `window` and `unsafeWindow` and monitor script execution timeouts. Support `@noframes`, `@unwrap`, and dispatch SPA URL navigation events (`onyx:urlchange`).
   - Support `GM_getResourceText`, `GM_getResourceURL`, `GM_addValueChangeListener`, `GM_removeValueChangeListener`, alongside automated background update checks via AndroidX `WorkManager` (`UserScriptUpdateWorker` every 7 days). Script editor features high-performance native-backed monospace code editing with `ScrollingMovementMethod`, non-blocking line and character counting, delete/save workflows, and full JSON backup exports.
   - Pull-Down-to-Refresh: Configured at a fluid 76dp trigger distance, dynamic progress view offset (`updateSwipeRefreshOffset`), Material 3 & Dark Theme dynamic colors (`colorSurface` / `colorPrimary`), expanded 50% top-zone touch gating, and multi-touch pinch-to-zoom disarm hooks.
   - One-tap web install interception on GreasyFork, GitHub, and OpenUserJS via `InstallUserScriptBottomSheet`. Displays metadata, author, version, target sites, and requested GM permissions alongside a high-contrast cross-origin network warning banner (`@connect` domains, Lucide `ic_alert_triangle`, clear explanatory text), navigation insets, and distinct Material 3 actions (filled `[+ Install]`, tonal `[View Code]`, text `[Cancel]`). Tapping `[View Code]` seamlessly opens the full-screen script editor (`UserScriptEditorActivity`) where the user can inspect, edit, and save/install the script. To prevent toolbar collisions on long script names, the editor toolbar uses a single horizontally weighted `LinearLayout` ensuring the title ellipsoids without squeezing or overlapping the Save/Delete action buttons, a hairline divider above the editor canvas, line-1 scroll reset, and non-intrusive focus. Large scripts are protected against Android GPU texture limits via a bounded viewport container with `ScrollingMovementMethod` and in-memory companion transfer to bypass Binder IPC limits (`TransactionTooLargeException`).
   - Inject `@run-at document-start` scripts at `onPageStarted`, `@run-at document-end` scripts at `onPageFinished`, and `@run-at document-idle` scripts via a post-load debounce (`500ms`) with DOM `requestIdleCallback` hydration scheduling to prevent UI stutter and missing elements on SPA client-side hydration (React/Vue/Angular). Scripts execute using a Tampermonkey-standard selective isolation architecture: unprivileged `@grant none` scripts run directly in the host Page World with standard `unsafeWindow === window` for complete compatibility with website internals (YouTube `ytplayer`/`ytcfg`, framework states), while privileged `@grant GM_*` scripts execute within an isolated lexical prototype sandbox (`Object.create(unsafeWindow)`) with authentic `unsafeWindow` and non-enumerable bridge masking (`__onyxGM`, `onyxGM`, `__onyxInjectedScripts`), deduplicated against repeated single-page application navigation via `window.__onyxInjectedScripts`.
   - Native `@JavascriptInterface` bridge (`UserScriptBridge` registered at `window.onyxGM`) exposes scoped key-value storage (`GM_getValue`, `GM_setValue`, `GM_deleteValue`, `GM_listValues`), `@connect`-validated background networking (`GM_xmlhttpRequest`), file & media downloads (`GM_download` / `GM.download` hooked into `DownloadEngineManager` with progress, completion, and abort callbacks), clipboard (`GM_setClipboard`), notifications (`GM_notification`), dynamic DOM element injection (`GM_addStyle`, `GM_addElement` resilient to early `document-start` execution via `MutationObserver` fallback), tab spawning (`GM_openInTab` restricted to `http`/`https`), and dynamic page menu commands (`GM_registerMenuCommand`, `GM_unregisterMenuCommand`). Token sessions bind target URLs deterministically at injection time (`ScriptSession(scriptId, targetUrl)`), eliminating calls to `WebView.getUrl()` on Chromium's background `JavaBridge` thread. Bridge lifecycle unregisters download listeners and cancels coroutine scopes on `BrowserWebView.destroySafely()` to prevent memory leaks. In-page script injection protects against missing early DOM roots at `document-start` via `MutationObserver` and handles non-standard UTF-8 encoding via `TextDecoder`. Grants are matched case-insensitively, omitted match/include patterns default to all web URLs, and `GM.info` is provided across both Page World and isolated execution scopes. Script identity accounts for `(namespace, name)` to prevent cross-author overwrite collisions.
   - Script management UI is centralized in `Settings > Privacy & Security > Site settings > Scripts` with a top-right `+` button (New script editor, Import from URL, Import from file) and `Update` button.
13. **WebMessageListener & Bridge IPC Standards**:
   - Modern asynchronous bridges (`TouchBridge`, `FormBridge`, `BlobBridge` in `com.onyx.browser.web.bridge`) must use `WebViewCompat.addWebMessageListener` when `WebViewFeature.WEB_MESSAGE_LISTENER` is supported, falling back to `@JavascriptInterface`.
   - WebMessage channel ports (`window.onyxTouchBridge`, `window.onyxFormBridge`, `window.onyxBlobBridge`) do not leak Java reflection objects on `window`, eliminating anti-bot detection signatures.
   - Synchronous APIs (such as `ShieldBridge.isUrlBlocked` registered at `window.OnyxShieldBridge`) and W3C WebAuthn passkeys (`_OnyxPasskeyBridge`) must maintain direct `@JavascriptInterface` contracts as required by their blocking or specification requirements.
14. **System Memory Pressure & Lifecycle Optimization (`ComponentCallbacks2.onTrimMemory`)**:
   - `BrowserApplication`, `MainActivity`, and `TabCoordinator` must handle system memory trimming callbacks (`ComponentCallbacks2.onTrimMemory` and `onLowMemory`).
   - On `TRIM_MEMORY_UI_HIDDEN`, tab switcher preview bitmaps are evicted from memory (saved WebP snapshots remain on disk), active tab states are written to disk, and non-active idle tabs older than 15 minutes are hibernated.
   - On `TRIM_MEMORY_RUNNING_CRITICAL` or `TRIM_MEMORY_COMPLETE`, temporary in-memory rule and CNAME caches in `AdBlockEngine` (`clearDecisionCache()`) and `AdBlockDomainManager` (`clearCache()`) are evicted, bitmap thumbnail caches are purged, and non-active normal background tabs (strictly excluding any tab actively playing background media) are safely hibernated with their state persisted to disk.
15. **Chromium Runtime Async Pre-Warming (`WebViewCompat.startUpWebView`)**:
   - `BrowserApplication.onCreate()` initiates asynchronous Chromium pre-warming via `WebViewCompat.startUpWebView(context, startUpConfig, outcomeReceiver)` on a dedicated background worker executor.
   - Offloads `libwebviewchromium.so` native loading, IPC daemons, and V8 engine boot from the UI thread, eliminating cold-boot startup hitches.
16. **Data Clearance & Exit Lifecycle Standards**:
   - 'Clear data on exit' (`isClearOnExitEnabled`) executes comprehensively across all exit vectors: Back navigation at root/homepage (`finishAffinity()`), Recent Apps swipe-away (`ClearOnExitService.onTaskRemoved` and `MediaPlaybackService.onTaskRemoved` with `stopWithTask="false"`), minimizing/backgrounding to Recents (`onStop`), and `MainActivity.onDestroy()`.
   - Granular Exit & Browsing Data Selection: "Clear Browsing Data" dialog (`ClearBrowsingDataDialog`) is implemented in Jetpack Compose matching the canonical layout and settings theme (24dp rounded surface container, 1dp outline border, 21sp bold title, 13sp bold "Time range" label, 16dp rounded `surfaceVariant` dropdown selector, 16dp rounded `surfaceVariant` options card grouping 4 granular options with 22dp leading icons [`ic_history`, `ic_tabs`, `ic_shield_lock`, `ic_broom`], 15sp bold titles with live preview counts, 12sp subtitles with live host/tab examples, subtle dividers, trailing checkboxes, and dual 48dp pill buttons for Cancel and Clear Data) with independent selection checkboxes preventing accidental session loss when clearing recent history. In Shields settings, "Clear Data on Exit" expands into `ClearOnExitSheet` / `ClearOnExitBottomSheet` allowing granular configuration (`isClearOnExitTabsEnabled`, `isClearOnExitHistoryEnabled`, `isClearOnExitCookiesEnabled`, `isClearOnExitCacheEnabled`), enabling users to keep open tabs while wiping cookies, cache, and history. When `isClearOnExitTabsEnabled` is false, `saveAllTabStates()` and tab restoration are preserved while history, cookies, and cache are purged.
   - All clearances execute synchronously via `ClearDataOnExitHelper`: Room database history (`clearAllHistory()`) and tabs (`clearNormalTabs()`) via `runBlocking(Dispatchers.IO)`, active and hibernated tabs closed without undo, Chromium cookies flushed across default and `MULTI_PROFILE` profiles (`removeAllCookies` with CountDownLatch synchronization + `flush()`), `WebStorage` and `WebViewDatabase` purged, and thumbnail/preview/state (`tab_states`) directories wiped from disk.
   - When `isClearOnExitEnabled && isClearOnExitTabsEnabled` is true, tab state saving on `onStop`, `onSaveInstanceState`, and `onDestroy` (`saveAllTabStates()`) is omitted, `hasPendingExitClearance = true` is synchronously committed to disk in `onStop()`, cleared on foreground resumption (`onStart()`), and enforced across cold launches in `BrowserApplication.onCreate()` and `MainActivity.onCreate()` before restoring tabs, guaranteeing complete data purge even when OEM task managers (MIUI/HyperOS, OneUI) kill background processes without calling `onTaskRemoved()`.
17. **UDF ViewModels, Repository Layer, Domain Preferences, DI Container, Interceptor Pipeline & LRU WebView Pool**:
   - Presentation screens (`MainActivity`, `HistoryActivity`, `BookmarksActivity`, `DownloadsActivity`) use Unidirectional Data Flow (UDF) `StateFlow` state holders (`BrowserViewModel`, `HistoryViewModel`, `BookmarksViewModel`, `DownloadsViewModel`) instantiated via `AppViewModelFactory` with constructor-injected repositories (`HistoryRepository`, `BookmarkRepository`, `DownloadRepository`). In `HistoryActivity`, history entries are categorized by day ("Today", "Yesterday", and formatted dates) with expandable/collapsible Material 3 cards, item counts, and live cached circular website favicons (`rememberFavicon`), while the toolbar clear action opens the granular `ClearBrowsingDataDialog`.
   - `MainActivity` delegates UI and browser feature domains across focused controllers in `com.onyx.browser.ui.browser`: `ToolbarLayoutController`, `BrowserMenuCoordinator`, `NavigationCoordinator`, `WebChromeCoordinator`, `HomeScreenController`, `FileChooserCoordinator`, `WebContextMenuCoordinator`, and `SettingsNavigator`.
   - `ExternalNavigationHandler` (`com.onyx.browser.web.navigation`) isolates external scheme resolution (`intent://`, `market://`, `mailto:`, `tel:`, app deep-link prompts, and cross-domain betting/ad redirect defense) from `BrowserWebViewClient`.
   - Dependencies and plugin versions are centrally managed via the Gradle Version Catalog (`gradle/libs.versions.toml`), while external contributors can bootstrap submodules, patches, symlinks, icons, filter lists, and `cargo-ndk` builds with `./scripts/bootstrap_dev.sh` (documented in `CONTRIBUTING.md`, `SECURITY.md`, `.editorconfig`, and `.github/PULL_REQUEST_TEMPLATE.md`).
   - `BrowserPreferences` composes cohesive domain preference stores (`AppearancePreferences`, `ShieldPreferences`, `MediaAndDownloadPreferences`, `SearchPreferences`) while preserving full backward-compatible delegation.
   - Large injected JavaScript payloads (`passkey_webauthn_polyfill.js`, `media_playback_observer.js`, `adblock_procedural_engine.js`) reside in `app/src/main/assets/scripts/` and are loaded/cached in memory via `AssetScriptLoader` (`ConcurrentHashMap`).
   - Network interception in `BrowserWebViewClient` routes through the Chain-of-Responsibility `RequestInterceptor` pipeline (`RealInterceptorChain`, `HttpsUpgradeInterceptor`, `AuthBypassInterceptor`, `AdBlockRequestInterceptor`, and `PrivacyUrlSanitizer`).
   - Per-site permissions and domain exceptions are persisted in the SQLCipher-encrypted `site_permissions` Room table (`SitePermissionEntity`, `MIGRATION_7_8`) with exported KSP schema definitions (`room.schemaLocation` -> `app/schemas`) and cached in $O(1)$ lock-free `ConcurrentHashMap` sets inside `SitePermissionRepository` for zero-blocking reads on WebView interception threads.
   - `TabCoordinator` enforces a RAM-aware LRU WebView pool governor (`maxLiveWebViews` of 3–5 live instances scaled by `ActivityManager.MemoryInfo`), hibernating least-recently-used non-media background tabs to disk (or `incognitoStateBundles` in-memory for private tabs).
   - CI builds enforce an automated unit test gate (`./gradlew :core:common:test testReleaseUnitTest --no-daemon`) in `.github/workflows/build.yml` prior to APK assembly.
   - Never allocate raw unmanaged `CoroutineScope(Dispatchers.IO)` instances inside UI controllers; always use `lifecycleScope` / `viewModelScope` with `NonCancellable` for critical persistence writes or a named `SupervisorJob` scope for singleton managers.
18. **Pull-to-Refresh & Gesture Conflict Defusal Standards**:
   - `SwipeRefreshLayout` in `MainActivity` wraps `webViewContainer` with a 120dp distance-to-trigger.
   - **Synchronous Top-Zone Touch Gating**: `onChildScrollUpCallback` inspects `webView.touchDownY` against `maxTopTouchPx` (top 33% of viewport, min 120dp). Gestures initiated in the lower two-thirds of the screen (e.g. inner feeds, modals, code blocks, or floating panels) immediately return `true`, blocking `SwipeRefreshLayout` from intercepting the drag.
   - **DevTools (Eruda) Safety Gate**: When DevTools is expanded (`isDevToolsExpanded = true`), `onChildScrollUpCallback` blocks refresh synchronously, and `swipeRefreshLayout.isEnabled` is toggled off while shown. Eruda's `window.eruda._devTools` `show`/`hide` events and monkey-patched toggle methods dynamically dispatch `ptr` bridge messages, and `TouchBridge` checks `isErudaExpanded()` directly on `touchmove`/`touchend` with self-healing `touchstart` reconciliation so pull-to-refresh is instantly restored when DevTools is minimized to its floating entry button.
   - **Shadow DOM & `composedPath()` Traversal**: `TouchBridge` inspects `e.composedPath()` to traverse Shadow DOM boundaries, detect inner scrollable elements (`overflowY` in `scroll`/`auto` with `scrollHeight > clientHeight`), check `scrollTop > 0` on `touchstart` directly, and exempts standard web pages (`facebook.com`, `instagram.com`, `threads.net`, `/login/`) from early returns so SPAs and form flows are protected from accidental page reloads.
19. **Android Fragment Recreation & Compose Back Navigation Standards**:
   - Subclasses of `BottomSheetDialogFragment` and `DialogFragment` must declare `@JvmOverloads constructor(...)` with safe fallback default values (or provide a public zero-argument constructor). This prevents fatal reflection crashes (`Fragment.InstantiationException`) when secondary activities without `configChanges` (Settings, History, Bookmarks, Downloads, Download Prompt) or process recreations restore fragments.
   - Compose Lazy components (`items(tabs, key = { it.id })`) must key transient item gesture states (`dragOffsetX`, `cardAlpha`, `cardWidthPx`, `isDismissingTab`, `settleJob`) to item identity (`remember(tab.id) { ... }`) to prevent state bleed and gesture corruption across recycled layout slots.
   - Compose activities featuring searchable toolbars, multi-selection, or code editors (`HistoryActivity`, `BookmarksActivity`, `DownloadsActivity`, `UserScriptEditorActivity`) must bind `BackHandler` hooks to intercept Android system back button presses and edge gestures before exiting the activity, protecting users from accidental data loss and closing open searches gracefully.
20. **Material 3 Surface Tint & Tonal Elevation Standards**:
   - In Compose Material 3, applying `tonalElevation > 0.dp` causes surfaces to blend `colorScheme.surface` with `colorScheme.primary` (Onyx accent `#FF5C38`), dynamically generating an unwanted reddish-brown tint (`#352622`) over dark surfaces (`#202020`) and salmon/peach over light surfaces (`#F7F7F8`).
   - All `ColorScheme` palettes (`DarkColorScheme`, `AmoledColorScheme`, `LightColorScheme`) and `OnyxTheme` enforce `surfaceTint = Color.Transparent` to prevent Material 3 from injecting accent color tinting into elevated surfaces, dialogs, cards, and modal bottom sheets.
   - All bottom sheets (`DownloadPromptBottomSheet`, `WebPermissionBottomSheet`, `SelectDownloaderBottomSheet`, `ContextMenuBottomSheet`, `MenuBottomSheet`, `ThemePickerBottomSheet`, etc.), dialogs (`ClearBrowsingDataDialog`, `OpenInAppPromptDialog`, `EditShortcutDialog`), and menus explicitly enforce `tonalElevation = 0.dp` so dark mode surfaces remain pure `#202020` (`DarkSurface`) and light mode surfaces remain pure `#F7F7F8` (`LightSurface`).
21. **3-Dot Bottom Sheet Menu Design & Theme Consistency Standards**:
   - `MenuBottomSheet` replicates the visual layout and hierarchy of the canonical reference designs (`a.jpg` for Homepage and `b.jpg` for Webpage) while strictly conforming to the app's standard charcoal dark theme (`DarkBackground = #161616`, `DarkSurfaceVariant = #2C2C2C`, `DarkOutline = #2C2D30`) with `isAmoled = false` rather than pure black `#000000`.
   - **Quick Action Header**: Top drag handle (pill) and 4 evenly-weighted quick action buttons (Bookmarks, History, Downloads, Share) rendered directly on the sheet canvas without boxed squircle containers.
   - **Homepage Layout**: Displays the "Set as default browser" banner card (when Onyx is not default, verified reliably via `RoleManager.isRoleHeld(ROLE_BROWSER)` and `PackageManager.resolveActivity`), followed by flat menu rows for New Tab, New Incognito Tab, and Settings.
   - **Webpage Layout**: Displays the rounded `WebsiteHeaderCard` (domain name with globe icon, circular shield lock button, and circular share button), followed by flat menu rows for New Tab, New Incognito Tab, Translate with trailing gear button to select target language, Find in Page, Desktop Site with `Switch`, Save this page, Developer tools with subtitle, optional Block element / Add to home screen, divider, and Settings.
   - Transparent bottom sheet root window background in `onStart()` ensures smooth 24dp top corner radius without background clipping.
22. **App Redirect & External Navigation Prompt Standards (`OpenInAppPromptDialog`)**:
   - `OpenInAppPromptDialog` presents an ergonomic, centered modal dialog for external scheme navigations (`intent://`, `tg://`, `whatsapp://`, store intents) and deep link handoffs.
   - **Hero Badge & Hierarchy**: Features a centered 56dp x 56dp squircle container (`RoundedCornerShape(16.dp)`) with a high-res resolved app icon bitmap or `ic_external`, followed by a centered bold title (`open_in_specific_app_title`), subtitle description, and rounded target URL preview card.
   - **Balanced Pill Action Buttons**: Displays two 48dp pill buttons (`RoundedCornerShape(24.dp)`) with equal 1f weights: "Stay in Onyx" on the left (`surfaceVariant` container with outline border) and "Open app" on the right (solid `primary` accent with `ic_external` leading icon).
   - **Lifecycle Safety**: Binds `onDismiss` and `onCancel` to `onStayInOnyx` fallback routines, eliminating dead-ends and allowing WebView navigation to proceed seamlessly when users tap outside or dismiss the dialog. Enforces `tonalElevation = 0.dp`, `isAmoled = false`, and transparent dialog window backgrounds.
23. **Unified Stadium-Pill Search Box & Top App Bar Standards (`SearchBox`, `SearchTopAppBar`)**:
   - Secondary hubs (`DownloadsActivity`, `HistoryActivity`, `BookmarksActivity`) implement a unified, roundish stadium-pill search box (`SearchBox`, `SearchTopAppBar` in `com.onyx.browser.ui.components`).
   - **Form Factor & Geometry**: Uses a 44dp height pill container (`RoundedCornerShape(24.dp)`) with `surfaceVariant` background (`#2C2C2C` in dark mode, `#E8EAED` in light mode), subtle outline border (`outline` at 50% alpha), and zero tonal elevation (`tonalElevation = 0.dp`), completely replacing legacy squarish 4dp `OutlinedTextField` elements.
   - **Ergonomics & Layout**: Features a 20dp leading search icon, vertically-centered single-line text and localized placeholder ("Search downloads…", "Search history…", "Search bookmarks…"), cursor tinted to `colorScheme.primary`, instant auto-focus via `FocusRequester` when entering search mode, and a trailing 28dp circular clear (`X`) button when the search query is non-empty.
   - **Horizontal Alignment & Action Isolation**: During active search (`isSearchActive = true`), secondary top bar actions (e.g. system downloads folder button or clear browsing data button) are cleanly hidden, allowing the pill search box to span the full width from the back navigation arrow to the right edge with a balanced 12dp end margin.
