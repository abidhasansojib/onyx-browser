# AGENTS.md — Onyx Browser Engineering Guide & Operational Mandates

> **CRITICAL DIRECTIVE FOR ALL AI MODELS & AGENTS**:  
> Read this entire document before inspecting, modifying, or executing any task on this codebase. All rules defined herein are absolute, strictly enforced, and take precedence over default assistant behavior.

---

## 1. Project Summary & Architectural Mission

**Onyx Browser** (`com.onyx.browser`) is a production-grade, ultra-lightweight, high-performance, and privacy-first Android web browser engineered from scratch for modern Android devices (Min SDK 26 / Android 8.0+, Compile & Target SDK 35 / Android 15).

### Core Goals & Technical Philosophy
- **Zero Overhead Native Architecture**: Built with idiomatic Kotlin 2.x and classic Android XML Views with ViewBinding. **Strictly NO Jetpack Compose** to preserve sub-millisecond cold starts, eliminate UI framework overhead, minimize memory consumption, and guarantee 120Hz hardware-accelerated WebView compositing.
- **Native Rust Adblock Engine (`adblock-rust`)**: Brave's high-performance adblocking engine compiled via `cargo-ndk` into native `.so` shared libraries (`libadblock_bridge.so`) across all 4 Android ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `universal`).
- **54 Brave Content Filter Lists**: Production filter list management with background compilation into binary FlatBuffers (`onyx_filters.bin`).
- **Brave-Parity Media & Background Playback**: Streaming background audio/video keep-alive (`userHitPause`, `visibilityState` spoofing, event suppression) and true video-only Picture-in-Picture (PiP) penetrating Shadow DOM hosts.
- **Modern Standards**: Passkeys & WebAuthn via AndroidX Credential Manager, Google Password Manager integration, CameraX + ML Kit QR scanning, SQLCipher AES-256 database encryption, and multi-engine reverse image search.

---

## 2. 🔴 MANDATORY OPERATIONAL RULES FOR ALL AGENTS

### RULE 1: Local Building Strictly Forbidden
- **Prohibited Operations**: Executing compilation or build commands locally on this Linux machine (including `./gradlew`, `cargo`, `rustc`, `cmake`, `ninja`, `make`, `docker build`, NDK compilation, or running Gradle daemons) is **STRICTLY FORBIDDEN**.
- **Reason**: The host system is a lightweight agent environment not configured or resourced to run heavy mobile compilation pipelines.
- **Permitted Operations**: Lightweight CLI utilities, file editing, Python/Bash scripting, code analysis, Git operations, and GitHub CLI (`gh`) are fully permitted.

### RULE 2: Explicit User Build Gate ("build app now")
- **Default State**: Do **NOT** trigger GitHub Actions CI/CD workflows, compile code, or trigger remote builds during regular conversation.
- **Intermediate Commits**: All routine code changes, bug fixes, refactors, and documentation updates must include `[skip ci]` in the commit message to prevent accidental CI triggers:
  ```bash
  git commit -m "fix(media): resolve video bounds calculation [skip ci]"
  ```
- **The Gate Command**: Only when the user explicitly commands **"build app now"** are you permitted to initiate a CI/CD build run.

### RULE 3: Autonomous Remote Build & Auto-Fix Loop
When the user explicitly issues the command **"build app now"**, the agent must execute the following autonomous loop:
1. **Push & Trigger**: Commit all pending changes (without `[skip ci]`) and push to `origin main`, or trigger the workflow:
   ```bash
   gh workflow run build.yml -f build_type=Both
   ```
2. **Monitor Execution**: Track the active workflow run to completion using `schedule` timers and `gh run view <run_id>`. Do NOT poll in a busy while-loop; use the `schedule` tool.
3. **If Build Succeeds**:
   - Download the generated release APKs to `/storage/emulated/0/` via `gh release download`.
   - Report the release version, file sizes, and download links to the user.
4. **If Build Fails (CRITICAL AUTO-REPAIR REQUIREMENT)**:
   - **DO NOT STOP OR REPORT FAILURE TO USER WITHOUT FIXING!**
   - Retrieve the failed step logs:
     ```bash
     gh run view --log-failed --job=<job_id>
     ```
   - Analyze the compiler error, Kotlin syntax error, resource collision, or ProGuard/R8 exception.
   - Apply the necessary code fixes directly in the repository.
   - Commit the fix and push to `origin main`.
   - Re-trigger the build workflow and monitor again.
   - **Repeat this loop autonomously until a 100% successful build is achieved.**

### RULE 4: Mandatory `AGENTS.md` Maintenance
- Whenever a feature is added, a bug is fixed, an architectural decision is made, or a workflow is completed, you **MUST** update this `AGENTS.md` file before concluding the turn.

---

## 3. Directory Layout & Architecture Map

```text
onyx-browser/
├── .github/
│   ├── ISSUE_TEMPLATE/
│   │   ├── bug_report.yml        # YAML Issue Form: structured bug reports with logs & screenshots
│   │   ├── feature_request.yml   # YAML Issue Form: structured feature requests & mockups
│   │   └── config.yml            # Strict template configuration (blank_issues_enabled: false)
│   └── workflows/
│       ├── build.yml             # Native Rust NDK compile, Lucide sync, Gradle Release/Debug APKs
│       └── sync_upstream.yml     # Automated 6-hour cron sync for Brave filter lists
│
├── app/
│   ├── src/main/
│   │   ├── assets/
│   │   │   ├── easylist_rules.txt  # 35,000+ bundled Brave & uBlock filter rules (1.3 MB)
│   │   │   ├── eruda.min.js        # Offline mobile developer console
│   │   │   └── fonts/              # Typography assets
│   │   │
│   │   ├── java/com/onyx/browser/
│   │   │   ├── MainActivity.kt     # Primary activity, toolbar coordinator, PiP & insets manager
│   │   │   ├── OnyxApplication.kt  # App lifecycle, encrypted DB initialization, ServiceWorker setup
│   │   │   │
│   │   │   ├── data/               # Data & Storage Layer
│   │   │   │   ├── filter/         # FilterListManager (54 Brave filter lists compiler)
│   │   │   │   ├── local/          # Room Database, DAOs, and SQLCipher key provider
│   │   │   │   ├── model/          # TabItem, HistoryItem, BookmarkItem, ShortcutItem
│   │   │   │   ├── preferences/    # BrowserPreferences (StateFlow reactive settings & domain cache)
│   │   │   │   └── search/         # SearchSuggestionRepository & OpenSearch engine queries
│   │   │   │
│   │   │   ├── media/              # Media & Playback Subsystem
│   │   │   │   ├── FloatingVideoMenuManager.kt # Draggable floating action pill (download, bg play, PiP)
│   │   │   │   ├── MediaPlaybackBridge.kt      # Thread-safe JS/JNI bridge & video presence flags
│   │   │   │   └── MediaPlaybackService.kt     # Foreground MediaSession service for lockscreen controls
│   │   │   │
│   │   │   ├── nativebridge/
│   │   │   │   └── AdBlockEngine.kt            # JNI bindings to native libadblock_bridge.so
│   │   │   │
│   │   │   ├── ui/                 # Presentation Layer (XML ViewBinding)
│   │   │   │   ├── bookmarks/      # BookmarksActivity & BookmarksAdapter
│   │   │   │   ├── browser/        # TabManager (WebView lifecycle & tab state persistence)
│   │   │   │   ├── downloads/      # DownloadsActivity, DownloadPromptBottomSheet, 1DM handoff
│   │   │   │   ├── history/        # HistoryActivity & HistoryAdapter
│   │   │   │   ├── home/           # ShortcutsAdapter, quick action rows, drag-and-drop reordering
│   │   │   │   ├── menu/           # ContextMenuBottomSheet, ImagePreviewDialog, Reverse Image Search
│   │   │   │   ├── qr/             # QrScannerActivity (CameraX + ML Kit)
│   │   │   │   ├── search/         # SuggestionsAdapter, SearchEnginePopupMenu, keyword routing
│   │   │   │   ├── settings/       # SettingsActivity, ShieldsActivity, ContentFiltersActivity
│   │   │   │   └── tabs/           # TabSwitcherBottomSheet (Normal vs Incognito segmented pill)
│   │   │   │
│   │   │   └── web/                # Chromium WebView Subsystem
│   │   │       ├── AdBlockDocumentStart.kt   # Document-start scriptlets (CORS, fetch/XHR, CMP stubs)
│   │   │       ├── AdBlockDomainManager.kt   # 2-Tier domain rules (Standard vs Aggressive)
│   │   │       ├── AdBlockServiceWorkerHelper.kt # ServiceWorker network request interception
│   │   │       ├── MediaPlaybackManager.kt   # Brave backgrounding scripts, PiP Shadow DOM isolation
│   │   │       ├── OnyxWebChromeClient.kt    # Fullscreen video, file chooser, WebRTC permissions
│   │   │       ├── OnyxWebView.kt            # Hardened WebView, hardware layer, sandboxing
│   │   │       ├── OnyxWebViewClient.kt      # URL routing, type-aware 200 OK stubs, scheme dispatcher
│   │   │       └── PasskeyWebAuthnBridge.kt  # AndroidX Credential Manager WebAuthn bridge
│   │   │
│   │   ├── res/                    # Google Theme styles (Light/Dark/AMOLED), layouts, vectors
│   │   └── AndroidManifest.xml     # SingleTask launchMode, queries, hardware acceleration, permissions
│   │
│   └── build.gradle.kts            # App dependencies, NDK configuration, deterministic signing
│
├── external/                       # Submodules
│   ├── adblock-rust/               # Brave adblock-rust engine source
│   └── adblock-lists/              # Official Brave filter lists
│
├── rust_engine/                    # Native Rust NDK Bridge
│   ├── Cargo.toml
│   └── src/lib.rs                  # JNI exports: init, shouldBlock, getCosmeticResources
│
├── art/                            # High-res branding & visual assets
│   └── logo.png                    # 1024x1024 master icon
│
├── scripts/                        # Automation & Asset Builders
│   ├── fetch_icons.sh              # Lucide vector icon acquisition
│   ├── svg_to_vector.py            # SVG to Android Vector Drawable normalizer
│   ├── sync_upstream.sh            # Submodule sync
│   └── update_filter_lists.sh      # Bundles official Brave lists into easylist_rules.txt
│
├── README.md                       # Streamlined public documentation & download guide
├── FEATURES.md                     # Exhaustive technical feature manual
├── LICENSE                         # Official GNU General Public License v3.0 (GPL-3.0)
└── AGENTS.md                       # This document (Engineering & Operations Guide)
```

---

## 4. Deep Dive: Core Subsystems & Implementation Guidelines

### 4.1. Ad-Blocking Subsystem (`OnyxWebViewClient` & `AdBlockEngine`)
- **Type-Aware Response Synthesis**: Never return raw HTTP 403 errors when blocking resources. Websites use JavaScript promises that crash or halt rendering upon receiving HTTP error statuses. Always synthesize safe 200 OK stubs via `createBlockedResponse`:
  - `image`: Returns 1×1 transparent PNG (`iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=`) with CORS headers.
  - `script`: Returns an empty JavaScript stream with CORS headers.
  - `stylesheet`: Returns an empty CSS stream with CORS headers.
  - `sub_frame`: Returns an empty HTML comment (`<!-- blocked subframe -->`).
  - `media`/`other`: Returns an empty stream with CORS headers.
- **Fast Domain Parsing**: Use zero-allocation index scanning in `BrowserPreferences.cleanDomain` and cache the whitelist in an in-memory `HashSet<String>` to prevent disk I/O bottlenecks during request bursts.

### 4.2. Media & Playback Subsystem (`MediaPlaybackManager` & `FloatingVideoMenuManager`)
- **Video Presence vs Playback**: The floating action pill must remain visible whenever HTML5 video elements exist on the page, not solely when actively playing. Use `MediaPlaybackBridge.isVideoAvailable` (`isVideoPresent || isVideoPlaying`) to govern visibility.
- **Brave `userHitPause` Architecture**:
  - The script monkey-patches `HTMLMediaElement.prototype.pause` and `play` to maintain a `userHitPause` flag.
  - If a website fires a pause event while `!element.userHitPause` (e.g. on window blur or tab visibility change), the engine immediately auto-resumes playback via `origPlay.call(element)`.
- **MediaSession Synchronization**:
  - Intercept `navigator.mediaSession.setActionHandler` to capture streaming websites' custom actions (`play`, `pause`, `seekto`, `seekforward`, `seekbackward`, `nexttrack`, `previoustrack`).
  - Direct lockscreen and notification transport commands to dispatch through the site's registered MediaSession handlers first, falling back to YouTube's `#movie_player` and DOM media elements.
- **True Video-Only PiP Isolation**:
  - Traverses the composed ancestor path across ShadowRoot boundaries to remove CSS `transform`, `contain`, `filter`, and `clip-path` constraints up to `<html>`.
  - Zeroes out `contentContainer` navigation bar padding during PiP transitions and restores it upon exit.
  - Supplies an aspect-ratio-corrected `setSourceRectHint` so the Android Window Manager crops strictly to the video viewport.

### 4.3. Navigation, Schemes & Intent Routing (`AndroidManifest.xml`)
- **SingleTask Launch Mode**: `MainActivity` has `android:launchMode="singleTask"` to prevent duplicate activity stacks when links are clicked from external apps (WhatsApp, Telegram, Gmail, SMS).
- **External App Scheme Dispatching**:
  - Custom schemes (`tg://`, `whatsapp://`, `mailto:`, `intent://`) are dispatched directly via `context.startActivity(intent)` with `FLAG_ACTIVITY_NEW_TASK` wrapped in a clean `try ... catch (ActivityNotFoundException)`.
  - Do NOT gate intent resolution with `resolveActivity != null` on Android 11+ without explicit `<queries>` declarations.

### 4.4. Developer Tools & Remote Debugging Subsystem (`DevToolsManager` & `OnyxWebChromeClient`)
- **Remote WebContents Debugging**: `WebView.setWebContentsDebuggingEnabled(true)` enabled in `OnyxApplication`, granting desktop Chrome/Edge full DOM, Network, Profiler, and Source breakpoint access via `chrome://inspect` over USB/ADB.
- **Console & Error Pre-Buffering**: `DevToolsManager.consoleBufferScript` injected at `document_start` across all frames. Records `console.log/warn/error/info/debug`, `window.onerror`, and `unhandledrejection` events prior to DevTools initialization and replays them into Eruda's console panel upon activation.
- **In-Memory Engine Caching**: 489 KB `eruda.min.js` cached in-memory in `DevToolsManager` after first read, eliminating disk I/O latency.
- **Theme Synchronization**: Eruda dynamically adopts `Dark` or `Light` theme based on the user's active browser palette (`THEME_DARK`, `THEME_AMOLED`, `THEME_LIGHT`).
- **Session Persistence**: DevTools state (`isDevToolsActive`) preserved across page reloads and link navigations within the tab.
- **Logcat Console Forwarding**: `OnyxWebChromeClient.onConsoleMessage` pipes formatted web console output directly to Android Logcat with tag `[OnyxDevTools]`.

---

## 5. Architectural Coding Standards for AI Agents

1. **WebView Thread Safety**:
   - `shouldInterceptRequest` executes on Chromium's background thread pool. Never access `view.url`, modify Android UI views, or call synchronous WebView methods from this callback.
   - Use the `@Volatile currentPageUrl` tracked via `onPageStarted` and referer headers.
2. **Vector Drawables**:
   - Always sanitize SVG vector drawables to static `#FFFFFFFF` base colors. Dynamic theme attribute references inside `<path android:fillColor="?attr/...">` cause runtime crashes on Android 8-10.
   - Always inflate vectors in layouts using `AppCompatImageView` or `AppCompatImageButton` with `app:srcCompat`.
3. **Memory Leak Prevention**:
   - When closing tabs, WebViews must be detached from `binding.webViewContainer`, stopped via `stopLoading()`, cleared of callbacks, and destroyed via `destroy()`.
   - Inactive tabs must invoke `webView.onPause()` to halt background JavaScript timers and conserve battery; the active tab invokes `webView.onResume()`.
4. **Data Encryption**:
   - All Room database operations must route through SQLCipher with encrypted passphrase management (`SecureDatabaseKeyProvider`).

---

## 6. Development Milestones & Roadmap

- [x] **v1.0.188 — Core Architecture & Stability Milestone**:
  - Compiled native Rust NDK `adblock-rust` engine (`libadblock_bridge.so`) across all 4 ABIs.
  - 54 Brave content filter lists with binary FlatBuffers caching.
  - Type-aware 200 OK adblock response synthesis (1×1 transparent PNG, empty JS/CSS/comment stubs).
  - Brave-parity background playback (`userHitPause`, `visibilityState` spoofing, event filtering).
  - True video-only Picture-in-Picture with Shadow DOM penetration and letterbox centering.
  - Floating action pill with stream download, background play headphones toggle, and PiP controls.
  - Bidirectional MediaSession and YouTube `#movie_player` API synchronization.
  - Passkeys & WebAuthn support via AndroidX Credential Manager.
  - CameraX + ML Kit QR code scanner.
  - Multi-engine reverse image search (Google Lens, TinEye, Yandex, Bing).
  - Pure Google Dark (`#202124`), Light, and AMOLED Black theme system.
  - SQLCipher AES-256 encrypted database for tabs, history, and bookmarks.
  - Streamlined `README.md` with official branding (`art/logo.png`), comprehensive `FEATURES.md`, and official GNU GPL-3.0 `LICENSE`.
  - GitHub YAML issue forms (`bug_report.yml` and `feature_request.yml`) with strict template chooser policy.
  - Enhanced Developer Tools subsystem: remote USB debugging via `WebView.setWebContentsDebuggingEnabled(true)`, document_start console & error pre-buffering, in-memory Eruda caching, dark/light theme sync, and session persistence.
- [ ] **Upcoming Milestones**:
  - Full-featured custom user scriptlet manager (Tampermonkey/Violentmonkey script support).
  - Enhanced desktop user-agent presets with custom site profile rules.
  - P2P sync for encrypted bookmarks and history across Onyx instances.
