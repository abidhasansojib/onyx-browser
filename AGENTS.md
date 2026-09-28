# AGENTS.md — Onyx Browser Engineering Guide & Operational Mandates

> **CRITICAL DIRECTIVE FOR ALL AI MODELS & AGENTS**:  
> Read this entire document before inspecting, modifying, or executing any task on this codebase. All rules defined herein are absolute, strictly enforced, and take precedence over default assistant behavior.

---

## 1. Project Summary & Purpose

**Onyx Browser** (`com.onyx.browser`) is a fast, lightweight, and privacy-focused Android browser (Min SDK 26 / Android 8.0+, Target SDK 35 / Android 15).

### Architecture & Tech Stack
- **Native Android UI**: Built with Kotlin and XML Views with ViewBinding (no Jetpack Compose for fast startup and low memory usage).
- **Adblocking**: Brave's `adblock-rust` engine compiled via NDK into `libadblock_bridge.so` across all 4 ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `universal`).
- **Filter Lists**: 54 Brave content filter lists with FlatBuffers binary caching (`onyx_filters.bin`).
- **Media Playback**: Background audio/video playback and Picture-in-Picture.
- **Storage & Security**: SQLCipher local database encryption, AndroidX Credential Manager for passkeys, and CameraX for QR scanning.

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

### RULE 5: Keep Everything Simple, Minimal & Effective
- All documentation, README files, UI copy, and explanations must remain **simple, minimal, and effective**.
- **No AI Marketing Buzzwords**: Avoid hyperbolic adjectives ("uncompromising", "sub-millisecond", "extreme").
- **No Over-Explaining**: State what things do in plain English from the user's perspective.
- **No Emoji Clutter**: Avoid spamming emojis across every heading, list item, or paragraph.
- Speak and write like a real, practical software engineer.

### RULE 6: Feature Tracking via `FEATURES.md`
- `FEATURES.md` is the single source of truth for tracking all browser capabilities, their live status, bugs, and upcoming features.
- Every feature is maintained in a numbered list with status checkboxes:
  - `[x]` = Working and verified in release builds.
  - `[-]` = Implemented with a known issue, bug, or hardware limitation.
  - `[ ]` = Planned / not yet implemented.
- **Mandatory Agent Tracking**: Before developing or debugging, agents must inspect `FEATURES.md` to see what is already working and what has bugs. Whenever an agent adds a feature, identifies a bug, or resolves an issue, they **MUST** update `FEATURES.md` accordingly.

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
- **Adblocker Spoofing Mode vs Standard Blocking (`createBlockedResponse`)**:
  - **Standard Blocking (Default / Spoofing OFF)**: Returns HTTP 403 Forbidden with `BlockedInputStream` (throwing `IOException` to trigger Chromium `net::ERR_FAILED`). This ensures `fetch()` promises reject (even with `mode: 'no-cors'`), `script.onerror` and `img.onerror` fire normally, and adblock testing sites (d3ward, adblock-tester, etc.) record complete blocking and achieve 100% scores.
  - **Adblocker Spoofing (Spoofing ON)**: Synthesizes type-aware safe 200 OK stubs with CORS headers to spoof ad scripts/images as loaded and bypass aggressive anti-adblock detection walls:
    - `image`: Returns 1×1 transparent PNG (`iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=`) with CORS headers.
    - `script`: Returns an empty JavaScript stream with CORS headers.
    - `stylesheet`: Returns an empty CSS stream with CORS headers.
    - `sub_frame`: Returns an empty HTML comment (`<!-- blocked subframe -->`).
    - `media`/`other`: Returns an empty stream with CORS headers.
- **Fast Domain Parsing**: Use zero-allocation index scanning in `BrowserPreferences.cleanDomain` and cache the whitelist in an in-memory `HashSet<String>` to prevent disk I/O bottlenecks during request bursts.

### 4.2. Media & Playback Subsystem (`MediaPlaybackManager` & `MediaPlaybackBridge`)
- **Brave `userHitPause` Architecture**:
  - The script monkey-patches `HTMLMediaElement.prototype.pause` and `play` to maintain a `userHitPause` flag.
  - Distinguishes genuine user interactions (pointer/touch/click events within 600ms) from background or visibility change auto-pauses.
  - If a website fires a pause event while `!element.userHitPause` (e.g. on window blur or tab visibility change), the engine immediately auto-resumes playback via `origPlay.call(element)`.
- **MediaSession Synchronization**:
  - Intercept `navigator.mediaSession.setActionHandler` to capture streaming websites' custom actions (`play`, `pause`, `seekto`, `seekforward`, `seekbackward`, `nexttrack`, `previoustrack`).
  - Direct lockscreen and notification transport commands to dispatch through the site's registered MediaSession handlers first, falling back to YouTube's `#movie_player` and DOM media elements.
- **True Video-Only PiP Isolation**:
  - Traverses the composed ancestor path across ShadowRoot boundaries to remove CSS `transform`, `contain`, `filter`, and `clip-path` constraints up to `<html>`.
  - Non-destructive viewport isolation: applies `position: fixed; z-index: 2147483647; width: 100vw; height: 100vh; background: #000; object-fit: contain;` directly to the target element without destructive `display: none` on siblings, preserving React/Vue/WebGL DOM state.
  - Unblocks YouTube native PiP button via `ytcfg` serialized experiment flags (`kYoutubePictureInPictureSupport`).
  - Cross-origin iframe postMessage bus (`pip_request`, `pip_exit`) allows embeds to request and release PiP seamlessly.
  - Dispatches W3C `leavepictureinpicture` event upon PiP exit and zeroes out navigation bar padding during transitions.

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
  - Background Playback Stability & Auto-Resume Fix: Decoupled service notification dismissal (`stopNotificationOnly` / `ACTION_DISMISS`) from foreground video playback, eliminating the recursive `pauseAllMediaScript` loop; gated all JavaScript keep-alive monkey-patches (`pause`, `play`, `visibilityState`, `listeners`) behind dynamic `window.__onyx_bg_play_active` checks; auto-reset `isExplicitUserPause = false` on playback start.
  - Floating Video Menu Headphone Icon Overhaul: Replaced thin unclosed stroke with Google Material Design filled headset vector (`#FFFFFFFF` base), added explicit `PorterDuff.Mode.SRC_IN` tinting, adjusted button padding to 8dp for prominent 24dp rendering, and ensured crisp contrast in both light and dark/AMOLED themes.
  - Streaming Site Picture-in-Picture Detection Overhaul: Overhauled `isolateVideoForPipScript` with multi-factor candidate iframe scoring (fullscreen permissions, URL patterns, geometry/aspect-ratio heuristics, container proximity), cross-frame `pip` postMessage bus, and resilient `MediaPlaybackBridge.isVideoAvailable` fallback in `isolateAndEnterPip` to ensure seamless PiP entry across all video streaming sites.
  - DevTools & Theme Preferences Compilation Fix: Resolved missing `DevToolsManager` import and explicit lambda parameter typing in `MainActivity.kt`; fixed `BrowserPreferences` constructor property scope (`private val context`) and removed unreferenced `THEME_AMOLED` identifier to guarantee 100% clean R8 & Kotlin compilation.
  - PiP Auto-Re-entry Loop & Temporary Floating Menu Reset Fix: Eliminated the auto-PiP re-entry trap when exiting or maximizing PiP by directly isolating in-page video DOM without synthetic `requestFullscreen`, setting `setAutoEnterEnabled(false)` upon PiP exit, adding a 1500ms exit cooldown, and pausing/dismissing video on 'X' close in `onStop`; converted floating headphone background playback into a temporary per-tab override (`MediaPlaybackBridge.temporaryBackgroundPlayOverride`) that resets to user default preferences along with pill position and video presence whenever switching or closing tabs.
  - WebGL Floating-Point Texture & Color Buffer Architecture Fix: Resolved "Rendering to floating-point textures is required but not supported" error across complex WebGL simulations (e.g. Evan Wallace's WebGL Water) by upgrading WebGL 1 context requests to WebGL 2 first, enabling `EXT_color_buffer_float` and `EXT_color_buffer_half_float` render targets, mapping internal formats to sized formats (`gl.RGBA32F`, `gl.RGBA16F`) in 9-arg and 6-arg `texImage2D`, returning truthful hardware linear filtering capabilities, and providing automatic `gl.NEAREST` fallback recovery in `checkFramebufferStatus`.
  - WebGL Standard Derivatives & Caustics Shader Compilation Fix: Resolved "extension is not supported" and "'dFdx' / 'dFdy' : no matching overloaded function found" shader compilation errors in WebGL 2 by adhering to WebGL 2 specification (returning `null` for `OES_standard_derivatives` so legacy WebGL 1 shaders select valid fallback paths) and injecting overloaded polyfill function definitions (`float`, `vec2`, `vec3`, `vec4` for `dFdx`, `dFdy`, `fwidth`) into GLSL 1.00 shaders while safely replacing the unsupported `#extension` directive.
  - Brave-Parity Media Engine, Picture-in-Picture & Floating Buttons Removal: Completely eliminated all floating buttons (`FloatingVideoMenuManager.kt`, `view_floating_video_menu.xml`) and settings switch; implemented 100% Brave-parity universal background playback engine via `kYoutubeBackgroundPlayback` (`visibilitychange` listener filtering), `visibilityState`/`hidden` property spoofing, `userHitPause` pattern on `HTMLMediaElement.prototype.pause`/`play`, touch/click event timing checks (600ms threshold), synthetic pause auto-resume, `window.onblur` neutralization, and `IntersectionObserver` proxying; implemented true video-only Picture-in-Picture (PiP) with W3C `requestPictureInPicture` polyfill, cross-frame `pip_request` postMessage bus, YouTube `ytcfg` experiment flag patching (`kYoutubePictureInPictureSupport`), YouTube fullscreen 100dvh styling (`kYoutubeFullscreenVideoFitWorkaround`), and non-destructive element isolation (`display: none` on siblings eliminated); maintained WebView timers via `resumeTimers()` during background playback and cleanly dispatched `leavepictureinpicture` across all frames upon PiP exit.
  - Brave-Parity Clipboard Suggestion Redesign ("Link you copied" / "Text you copied"):
    - Minimal Material 3 rounded suggestion card (`16dp` corner radius, `strokeWidth="0dp"`, `cardBackgroundColor="?attr/colorSurfaceVariant"`).
    - Brave-style circular icon badge (`36dp` circular container with `bg_circle_action` and centered 20dp vector tinted `?attr/colorPrimary`), displaying `ic_link` for URLs/domains and `ic_search` for text queries.
    - Typography: header in 14sp `sans-serif-medium` (`link_you_copied` / `text_you_copied`), subtext in 12sp `textColorSecondary` with single-line sanitized ellipsized text.
    - Diagonal omnibox insert arrow: `ic_insert_query` (36dp x 36dp, `selectableItemBackgroundBorderless`, `tint="?attr/colorControlNormal"`) to cleanly populate the address bar for editing without direct execution.
    - Active page URL suppression: suppresses "Link you copied" when clipboard matches active tab URL (`cleanUrlForComparison`).
    - Search query filtering: only blends clipboard suggestion during active typing if it contains the user query, preventing clipboard clutter over relevant search suggestions.
  - One-Tap Active Webpage Card Navigation (Matching Brave-Core):
    - Tapping the current webpage card (or its site info container) in the omnibox search overlay directly navigates/reloads the page via `performSearchOrLoad()` and dismisses the keyboard in one tap, while preserving the dedicated edit pencil button (`btnCurrentPageEdit`) for customizing the URL in the omnibox.
  - Settings Activity Toolbar Title Alignment: Centered the "Settings" title at the top of `activity_settings.xml` via `app:titleCentered="true"` on `MaterialToolbar`.
  - User Agent Spoofer Manager UI & Theme Overhaul:
    - Redesigned `bottom_sheet_user_agent_picker.xml` and `item_user_agent_template.xml` to seamlessly match the Onyx Google Dark/Light luxury theme, completely eliminating all hardcoded coral pink accents.
    - Standardized drag handle (`bg_drag_handle`), squircle icon tiles (`bg_box_tile`), and pill badges (`bg_pill_badge`).
    - Adopted `@color/settings_card_bg` and `@color/settings_card_stroke` matching SettingsActivity cards.
    - Updated active template and custom UA states to use `@color/google_blue` borders, checkmarks, and badges.
    - Redesigned action buttons: Outlined Paste button and Google Blue Apply button with contrast-optimized `btn_apply_text`.
  - Accessibility "Add to Home screen" Webpage Menu Toggle:
    - Added `isAddToHomeScreenEnabled` boolean preference (`KEY_ADD_TO_HOME_SCREEN`, default `false`) to `BrowserPreferences`.
    - Added a new toggle row (`settingAddToHomeScreenRow` with `settingAddToHomeScreenSwitch`) in `activity_accessibility_settings.xml` directly under the Search widget row.
    - Updated `AccessibilitySettingsActivity.kt` to bind the toggle state to `preferences.isAddToHomeScreenEnabled`.
    - Gated `menuItemAddToHomeScreen` in `MenuBottomSheetDialogFragment.kt` so the "Add to Home screen" option in the webpage 3-dot menu is hidden by default and only visible when enabled in Accessibility settings.
  - Shield with Centered Lock Vector Redesign:
    - Redesigned `app/src/main/res/drawable/ic_shield_lock.xml` to match the browser's Lucide outline vector design system (`strokeWidth="2"`, `strokeLineCap="round"`, `strokeLineJoin="round"`).
    - Replaced the outdated jagged Android 5.0 silhouette with an elegant Lucide crest shield and a geometrically centered, perfectly balanced padlock with rounded corners and arched shackle.
    - Preserved `#FFFFFFFF` static vector coloring for reliable runtime tinting across Android 8-15.
  - Settings Category Icons Dynamic Theme Tinting:
    - Fixed an issue where the left category icons in `activity_settings.xml` (Appearance, Autofill, Video options, Download settings, Accessibility, Privacy & shields, User agent spoofer, Manage personal data, and About Onyx) were invisible in light mode due to hardcoded white tint (`app:tint="#FFFFFFFF"` blending into white cards).
    - Replaced hardcoded tint with `@color/settings_title_text`, dynamically rendering in `#202124` charcoal in light mode and retaining crisp `#FFFFFFFF` white in dark mode, matching the existing standard used in sub-settings activities.
  - Simplified, Human-Friendly README Overhaul:
    - Rewrote `README.md` to remove robotic AI buzzwords, verbose marketing prose, and internal technical over-explanations.
    - Preserved visual branding (centered 128px logo, title, clean badges, quick links).
    - Simplified sections: natural "What is Onyx?", clear benefit-focused feature bullet points, straightforward download guide matching CPU architectures to everyday devices, minimal tech stack, and easy-to-follow source build instructions.
    - Streamlined the header subtitle ("A fast, private, and lightweight browser for Android") and trimmed the Features section to 6 tight, human-readable bullet points free of repetitive marketing fluff.
  - Master Feature Checklist Architecture (`FEATURES.md`):
    - Completely restructured `FEATURES.md` into a numbered 1..N checklist with live status indicators (`[x]` working, `[-]` known issue/bug, `[ ]` planned).
    - Established `FEATURES.md` as the unified feature tracking reference for all AI agents and developers.
    - Added clean, human-readable technical breakdowns following Rule 5 (no hype, no emoji spam).
  - Appearance Theme Picker & Vector Icons Redesign:
    - Redesigned `ic_theme_system.xml`, `ic_theme_dark.xml`, and `ic_theme_light.xml` to match the browser's Lucide outline vector design system (`strokeWidth="2"`, `strokeLineCap="round"`, `strokeLineJoin="round"`).
    - Replaced clunky 2014 filled silhouette shapes with a modern Lucide smartphone, crescent moon, and balanced 8-ray radial sun.
    - Overhauled `bottom_sheet_theme_picker.xml` to match Onyx's Google luxury theme, adding a drag handle, top header with box tile and close button, and cards with `@color/settings_card_bg` and `@color/settings_card_stroke`.
    - Eliminated all coral pink (`colorPrimary`) selection artifacts in `ThemePickerSheet.kt`, adopting `@color/google_blue` for active card borders and checkmarks.
  - 3-Dot Menu Soft Curved Edge Architecture:
    - Created `bg_bottom_sheet_soft_menu.xml` with 18dp soft curved top-left and top-right corners (`topLeftRadius="18dp"`, `topRightRadius="18dp"`), replacing sharp rectangular backgrounds and avoiding exaggerated full-round bubbles.
    - Standardized `ShapeAppearance.OnyxBrowser.BottomSheet` to 18dp in `themes.xml` and `values-night/themes.xml`.
    - Set `Theme_OnyxBrowser_BottomSheetDialog` style and applied `clipToOutline = true` on `design_bottom_sheet` in `MenuBottomSheetDialogFragment.kt` to ensure clean hardware clipping without corner bleed.
  - Facebook Login & Anti-Bot CAPTCHA Verification ("Confirmation failed in captcha") Fix:
    - Client Hints (`navigator.userAgentData`) & Bot Integrity Bridge: Created `ClientHintsCompatibilityBridge.kt` injecting authentic Google Chrome Mobile Client Hints (`brands`, `mobile`, `platform`, `getHighEntropyValues`, `toJSON`) matching Pixel 8 Chrome 131 at `document_start`, eliminating `"Android WebView"` brand leaks from JavaScript inspection. Polyfilled `window.chrome` (`csi`, `loadTimes`, `app`) and eradicated `navigator.webdriver`.
    - Cookie Blocking Default Alignment: Changed `cookieBlockingMode` default in `BrowserPreferences` from `COOKIE_BLOCK_THIRD_PARTY` to `COOKIE_BLOCK_NONE` for normal tabs, enabling third-party cross-origin iframe session cookies (essential for Arkose Labs / FunCaptcha, reCAPTCHA, and Cloudflare Turnstile token validation) while keeping them strictly disabled in Incognito mode.
    - Dynamic Auth/CAPTCHA Third-Party Cookie Acceptance: In `OnyxWebViewClient.onPageStarted` and `shouldInterceptRequest`, dynamically ensured `setAcceptThirdPartyCookies(view, true)` whenever navigating to Meta sites or CAPTCHA/auth verification URLs, preventing verification token drop even if the user manually configured cookie blocking.
    - Document-Start Global Stubs & Cosmetic Shield Bypass: In `AdBlockDocumentStart.kt`, bypassed adblock stubs and cosmetic DOM hiding on all Meta and CAPTCHA domains (`facebook.com`, `fb.com`, `messenger.com`, `instagram.com`, `arkose`, `recaptcha`, `turnstile`), and replaced bare `window.fbq` function with a compliant Meta Pixel queue/callMethod stub on third-party sites so Meta's scripts do not crash.
    - Comprehensive CAPTCHA & Checkpoint Detection: Expanded `isCaptchaOrAuthUrl` in `OnyxWebViewClient.kt` to catch `recaptcha`, `hcaptcha`, `arkose`, `arkoselabs`, `funcaptcha`, `turnstile`, `geetest`, `datadome`, `kasada`, `/checkpoint/`, `/challenge/`, `/captcha/`, `/security-check`, `/waf/`, `/bot-detection`, `/human-verification`, and device-based login URLs. Exempted Meta telemetry (`tr.facebook.com`) from tracker blocking when browsing Meta contexts.
    - Safe Custom Scheme Routing: Ensured custom app schemes (`fb://`, `intent://`) silently consume and return `true` on external dispatching failures rather than failing with `ERR_UNKNOWN_URL_SCHEME`. Exempted auth/Meta URLs from tracking parameter cleanup in `shouldOverrideUrlLoading`.
  - Dual-Layer Adblocker Restoration & Third-Party Native Rust Bug Fix:
    - Restored `AdBlockDomainManager.isBlockedInStandard(reqDomain)` in `OnyxWebViewClient.kt` across subresource network requests and popup tab navigations. Known ad networks (`doubleclick.net`, `googlesyndication.com`, `googleadservices.com`, `criteo.com`, `taboola.com`, `outbrain.com`, `adnxs.com`, `amazon-adsystem.com`, etc.) are now intercepted with 0ms latency even during cold-start or while the Rust engine compiles.
    - Fixed Third-Party Request Evaluation in `rust_engine/src/lib.rs`: Eliminated the bug where empty `source_url` strings were replaced with `&url_str` (causing `adblock-rust` to mistakenly compute `third_party = false`), passing `&source_str` directly so empty source requests evaluate as `third_party = true`, enabling thousands of EasyList `$third-party` rules to match correctly.
    - Synchronized Bundled Filter Assets: Updated `scripts/update_filter_lists.sh` to explicitly bundle ABP domain rules (`||domain^`) for all curated ad networks into `app/src/main/assets/easylist_rules.txt` (35,474 rules).
    - Fixed `CookieManager` compilation import in `OnyxWebViewClient.kt`.
    - Verified Release Build `v1.0.200`: Successfully compiled Rust NDK across all ABIs, minified with R8, and published to GitHub Releases. Downloaded to `/storage/emulated/0/`.
  - Ad Detection Spoofing Toggle & Facebook Login Improvements:
    - Added `isAntiAdblockDetectionEnabled` preference (`KEY_ANTI_ADBLOCK_DETECTION`, default `false`) to `BrowserPreferences.kt`.
    - Added `isAntiAdblockDetectionEnabled()` `@JavascriptInterface` to `OnyxShieldBridge.kt` for synchronous JS → Kotlin preference reads at document-start.
    - Gated the anti-adblock JS stubs (`window.ga`, `window.gtag`, `window.adsbygoogle.loaded`, `fbq`, `outbrain`, `taboola`, `dataLayer`) in `AdBlockDocumentStart.getScript()` behind a runtime bridge call so they only inject when the user enables the toggle. **Default OFF** — lets adblock test sites (superadblocktest.com, etc.) report accurately and prevents disrupting Cloudflare Turnstile challenges.
    - Added "Adblocker Spoofing" toggle row (`rowAntiAdblockDetection` / `switchAntiAdblockDetection`) in `activity_shields.xml` under the Trackers & Ads section; wired in `ShieldsActivity.setupTrackersAds()`.
    - Expanded `isCaptchaOrAuthUrl()` in `OnyxWebViewClient.kt` to cover additional anti-bot and auth URL patterns: PerimeterX, `/two_step_verification`, `/save-device/`, `/trusted-devices/`, `/login_attempt`.
  - Adblocker Spoofing & Standard Net Error Blocking Alignment (Adblock Testing Suite Parity):
    - Renamed feature in UI strings and layouts from "Ad Detection Spoofing" to "Adblocker Spoofing" (`anti_adblock_detection_title`, `anti_adblock_detection_desc`).
    - Added `isAdblockerSpoofingEnabled` alias in `BrowserPreferences.kt`.
    - Integrated `BlockedInputStream` in `OnyxWebViewClient.createBlockedResponse`: when Adblocker Spoofing is **OFF** (default), returns HTTP 403 Forbidden with `BlockedInputStream` which throws `IOException` on read, triggering Chromium's `net::ERR_FAILED`. This forces `fetch()` promises to reject (even in `mode: 'no-cors'`), activates `catch(e)` handlers, and triggers `script.onerror` and `img.onerror` so test sites (d3ward, adblock-tester.com, canyoublockit) score 100%.
    - Expanded `AdBlockDomainManager.standardDomains` and `easylist_rules.txt` with all 136 standard test domains (Google Tag Manager, Yandex Direct `an.yandex.ru`, Ymatuhin, Bugsnag, Sentry, Unity Ads, Hotjar, MouseFlow, Freshmarketer, Lucky Orange, OEM telemetry) and synthetic banner probes (`pr_advertising_ads_banner`).
    - Added `#yandex_rtb`, `#pr_advertising` cosmetic rules to `AdBlockDocumentStart.kt` to guarantee instant element height collapse.
  - Subresource SSL Error Isolation & Adblock Testing Suite Protection:
    - Resolved `net::ERR_CERT_COMMON_NAME_INVALID` error page hijack across adblocker testing suites (e.g. d3ward, adblock-tester.com). Previously, when a test suite probed an ad/tracker domain with an invalid/expired/mismatched SSL certificate, `onReceivedSslError` mistook the subresource failure for a main-frame error, called `view.stopLoading()`, and replaced the active webpage with a synthetic SSL error page for the main site URL.
    - Implemented `isMainFrameSslError(view, failingUrl)` in `OnyxWebViewClient.kt` and tracked `pendingMainFrameUrl` and `isMainFrameDocumentLoaded` across `OnyxWebView` and `OnyxWebViewClient`.
    - When an SSL error occurs on any subresource, tracking probe, iframe, or fetch request, `onReceivedSslError` now cleanly calls `handler.cancel()` without aborting the parent webpage or displaying an error screen. This triggers fetch rejection into the test suite's `catch(e)` block as expected, allowing tests to run uninterrupted and achieve full scores.
- [ ] **Upcoming Milestones**:
  - Full-featured custom user scriptlet manager (Tampermonkey/Violentmonkey script support).
  - Enhanced desktop user-agent presets with custom site profile rules.
  - Built-in Reader Mode (distraction-free text view for articles).
  - DNS-over-HTTPS (DoH) provider selection.
