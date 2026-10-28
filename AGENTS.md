# AGENTS.md — Onyx Browser AI Agent Operational Mandates & Rules

> **CRITICAL DIRECTIVE FOR ALL AI MODELS & AGENTS**:  
> Read this entire document before inspecting, modifying, or executing any task on this codebase. All rules defined herein are absolute, strictly enforced, and take precedence over default assistant behavior.

---

## 1. Overview & Technology Stack

**Onyx Browser** (`com.onyx.browser`) is a fast, lightweight, and privacy-oriented Android browser targeting Min SDK 26 (Android 8.0+) and Target SDK 35 (Android 15).

- **UI Framework**: Native Kotlin and XML Views with ViewBinding (Jetpack Compose is intentionally avoided for fast startup and minimal memory usage).
- **Engine**: Android System WebView hardened with Chromium multi-profile isolation, scriptlet injection, and network-level interception.
- **Content Blocking**: Brave `adblock-rust` engine compiled via Android NDK into `libadblock_bridge.so` across all 4 ABIs.
- **Database**: SQLCipher AES-256 encrypted Room database.

For complete subsystem specifications and technical architecture, see:
- [Architecture & Core Subsystems](docs/ARCHITECTURE.md)
- [Android WebView Engineering Guide](docs/WEBVIEW.md)
- [Features & Roadmap](docs/FEATURES.md)

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
     - DO NOT download artifacts to the device unless explicitly requested.
     - Report the release version tag, changelog, and GitHub release download links to the user.
   - **Prerelease Build**:
     - Released to GitHub Releases marked as a **Pre-release** (`applicationId` has `.prerelease` suffix).
     - DO NOT download artifacts to the device unless explicitly requested.
     - Report the prerelease tag, changelog, and GitHub release download links to the user.
4. **If Build Fails (CRITICAL AUTO-REPAIR REQUIREMENT)**:
   - Retrieve the failed step logs via `gh run view --log-failed --job=<job_id>`.
   - Analyze the compiler error, Kotlin syntax error, resource collision, or ProGuard/R8 exception.
   - Apply the necessary code fixes directly in the repository.
   - Commit the fix, push to `origin main`, re-trigger the workflow, and repeat until 100% successful.

### RULE 4: Mandatory Documentation Maintenance
- Whenever a feature is added, a bug is fixed, an architectural decision is made, or a workflow is completed, update this `AGENTS.md` and `docs/FEATURES.md` before concluding the turn.
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
- **Current Golden Milestone / Role Model Reference**: Release **`v1.3.13`** (commit `91f5f499ff03360c127e8c6fb093b95044e8cc02`, tags `v1.3.13` and `golden-reference-v1.3.13`) — verified working across WebAuthn passkeys (assertion signature verification with matching clientDataJSON, registration, autofill & password managers), adblocking & clickjack defusal, native video tap-to-show-controls, background audio/video playback, and Picture-in-Picture (inline, fullscreen, cross-origin iframe, expand & 'X' close lifecycle). Use `v1.3.13` (`91f5f49`) as the baseline reference if any future change regresses these subsystems.
- AI agents are **strictly forbidden** from automatically bumping or declaring a "Golden Milestone / Role Model" reference in `AGENTS.md` or `docs/FEATURES.md` unless explicitly commanded by the user.


---

## 3. Directory Layout & Architecture Map

```text
onyx-browser/
├── .github/
│   ├── ISSUE_TEMPLATE/       # Structured YAML issue forms (bug reports & feature requests)
│   └── workflows/
│       ├── build.yml         # Rust NDK compile, Lucide sync, Gradle Release/Prerelease APKs
│       └── sync_upstream.yml # 6-hour automated Brave filter lists sync workflow
├── app/
│   ├── src/main/
│   │   ├── assets/           # easylist_rules.txt, eruda.min.js, font assets
│   │   ├── java/com/onyx/browser/
│   │   │   ├── MainActivity.kt     # SingleTask primary activity, toolbar, and window coordinator
│   │   │   ├── OnyxApplication.kt  # Startup lifecycle, SQLCipher init, Safe Browsing setup
│   │   │   ├── data/               # FilterListManager, Room DB, DAOs, BrowserPreferences
│   │   │   ├── media/              # MediaPlaybackService and MediaPlaybackBridge
│   │   │   ├── nativebridge/       # AdBlockEngine JNI bindings to libadblock_bridge.so
│   │   │   ├── ui/                 # ViewBinding presentation layer (tabs, settings, search, menu)
│   │   │   └── web/                # Hardened Chromium WebView subsystem, client, and bridges
│   │   └── res/                    # Google Theme styles (Light/Dark/AMOLED), layouts, vectors
│   └── build.gradle.kts            # App dependencies, NDK configuration, deterministic signing
├── docs/                           # Documentation assets (logo.png), FEATURES.md, ARCHITECTURE.md, WEBVIEW.md
├── rust_engine/                    # Native Rust NDK Bridge (libadblock_bridge.so)
└── scripts/                        # Icon and filter list synchronization automation
```

---

## 4. Architectural & Coding Mandates for AI Agents

1. **Native Views Over Compose**:
   - Strictly write native Android XML Views and ViewBinding.
   - Do NOT introduce Jetpack Compose dependencies or `@Composable` components.
2. **Resource & Color Consistency**:
   - Always pair color resources between `res/values/colors.xml` and `res/values-night/colors.xml`.
   - Use theme attributes (`?attr/colorControlNormal`, `?attr/colorPrimary`) for icon tints and surface backgrounds.
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
   - WebAuthn polyfills (`PasskeyWebAuthnBridge`) must be injected on all login domains (e.g., GitHub, Google, Microsoft) and omitted strictly on Meta's internal challenge frames (`fbsbx.com`) and Meta auth domains.
   - `@JavascriptInterface` methods in `PasskeyWebAuthnBridge` execute on Chromium's background `JavaBridge` thread and must NEVER read `WebView` properties (`webView.url`) off the Main UI thread (`mainHandler.post`).
   - `PublicKeyCredential.isConditionalMediationAvailable()` must return `Promise.resolve(false)` (and hold `mediation: 'conditional'` requests passively until aborted) so login pages do not auto-launch modal `CredentialManager` sheets on page load.
   - `PasskeyWebAuthnBridge` must pass verified web `origin` + `clientDataHash` (never falling back to `origin = null` native app credentials), construct canonical unescaped `clientDataJSON` matching `clientDataHash` and inject it into assertion and registration responses (overriding provider placeholder or package-name client data so Relying Party SHA-256 hash checks verify against authenticator signatures), normalize all Base64 fields to canonical unpadded Base64URL, extract CBOR `attestationObject` metadata (`authenticatorData`, DER SPKI `publicKey`, `publicKeyAlgorithm`), and support W3C `PasswordCredential` (`GetPasswordOption` / `CreatePasswordRequest`) alongside Android `AutofillManager` (`AutofillHelper.getActivePasswordManager` via `autofillServiceComponentName`).
   - `_OnyxPasskeyBridge` must never be deleted from the window or overwritten with `undefined` on login pages.
   - `navigator.userAgentData` must polyfill authentic Chrome/Chromium brands without leaking `"Android WebView"` to ensure Google Sign-In and OAuth flows succeed without `403 disallowed_useragent` blocks.
8. **Adblock Engine & Network Interception Standards**:
   - `adblock-rust` rule parsing in `rust_engine/src/lib.rs` must include full `PermissionMask::from_bits(u8::MAX)` permissions so privileged scriptlets (`trusted-set-cookie`, `trusted-replace-fetch-response`, etc.) are compiled and injected.
   - Resource type classification in `OnyxWebViewClient` and `AdBlockServiceWorkerHelper` must use case-insensitive header lookups (`Accept`, `Sec-Fetch-Dest`, `Sec-Fetch-Mode`, `Ping-To`, `Range`) to support HTTP/2 and HTTP/3 multiplexed requests.
   - Dual-pass checking must be performed for CNAME uncloaked requests: test uncloaked destination first, and if allowed, test the original alias to catch rules explicitly targeting cloaked first-party domains.
   - `$csp` directives from `AdBlockEngine.getCspDirectives` must be injected early at document start via `<meta http-equiv="Content-Security-Policy">`, stripping `<meta>`-invalid directives (`sandbox`, `frame-ancestors`, `report-uri`) and rewriting `worker-src 'none'` to `worker-src blob:` so `https:` ServiceWorkers remain blocked while MSE `blob:` Web Workers (`hls.js`, `dash.js`) can demux video streams.
   - Never pre-define a global `window.google.ima` stub on all pages; let blocked `ima3.js` scripts return HTTP `403` (`onerror` fallback), `$redirect=google-ima.js` rules serve Brave's full IMA surrogate, and `@@||imasdk.googleapis.com` exception rules load normally.
   - Synthetic HTTP `200 OK` VAST `<VAST version="3.0"/>` XML and `$redirect` surrogate responses must echo `Access-Control-Allow-Origin: <Origin>` with `Access-Control-Allow-Credentials: true` when an `Origin` header is present, and never return synthetic 3xx `WebResourceResponse` redirects from `shouldInterceptRequest` (which Android WebView does not follow).
   - Never block authentication endpoints (Google Sign-In, Meta, Microsoft, Apple), bot challenges (Cloudflare Turnstile, Arkose, reCAPTCHA, hCaptcha), or media streaming segments.
   - Clickjack defuser CSS/listeners and `defuseGeneralVideoAds` in `AdBlockDocumentStart` must never match generic `overlay` / `player-overlay` classes, intercept `pointerdown` on video players, synthetically toggle `video.play()` / `video.pause()`, or match non-ad player containers/controls (`.vjs-skip-button`), preserving native playback and tap-to-show-controls behavior across YouTube and HTML5 players.
   - Declarative Shadow DOM (DSD) Progressive Hydration Safety: On `onPageFinished`, `DsdHydrationFix` must execute to remove `dsd-pending` attributes from document roots and open shadow roots so pages whose hydration scripts were blocked by ad/tracker filters never remain stuck with `[dsd-pending]{display:none}` blank screens.
9. **Media Playback & Picture-in-Picture Standards**:
   - Audio focus is owned by the Chromium WebView media session. `MediaPlaybackService` must NOT request its own audio focus (a second `AUDIOFOCUS_GAIN` in the same process makes WebView pause and causes a pause/resume fight). The service provides the `mediaPlayback` foreground lifecycle, MediaSession, notification, wake/Wi-Fi locks and the noisy-disconnect pause.
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
   - Synthetic URL schemes (`javascript:`, `about:`, `data:`, `blob:`, `file:///android_asset/`, `file:///android_res/`) must be classified as synthetic in `OnyxWebView.isSyntheticOrDataUrl` so they never clobber active page state, user agents, or history.
   - POST form download triggers (e.g. `javascript:document.getElementById('id').submit();` or download buttons submitting forms via POST) must be captured at document-start via `OnyxFormBridge` / `FormPostRegistry`, matched on `DownloadListener`, and executed via HTTP POST in `DownloadEngine` with accurate `Origin`, `Referer`, and `Content-Type` headers.
   - Download triggers spawning blank popup tabs (`target="_blank"`) must retire the unpromoted popup tab once the download is enqueued, preventing zombie tabs.
   - Desktop Mode & Network-Level Client Hints: In addition to setting `userAgentString` and DOM `navigator.userAgentData` polyfills, `DesktopModeManager` synchronizes network-level Client Hints via `WebSettingsCompat.setUserAgentMetadata` (`Sec-CH-UA-Mobile: ?0` for desktop, `?1` for mobile, `Sec-CH-UA-Platform`, and sanitizing `"Android WebView"` brands) so initial HTTP requests accurately reflect desktop mode before any HTML/JS is delivered.
11. **UI Presentation & Toolbar Customization Standards**:
   - Full-page settings activities must use native `CoordinatorLayout` + `MaterialToolbar` with edge-to-edge system insets and avoid floating dialogs for primary preference screens.
   - Address bar button customization (`isHomeReplacedWithRefresh`) must update dynamically in `onResume()` and `updateAddressBarDisplay()` without requiring activity recreation.
   - Omnibox Address Alignment: The address bar hint and host URL are centered horizontally in browsing mode (`Gravity.CENTER`) and dynamically switch to start/left alignment (`Gravity.START or Gravity.CENTER_VERTICAL`) when entering search mode for natural text input.

