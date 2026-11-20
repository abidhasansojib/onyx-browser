# Onyx Browser — Features & Roadmap

Single source of truth for Onyx Browser capabilities and status.

- [Architecture & Core Subsystems](ARCHITECTURE.md)
- [Android WebView Engineering Guide](WEBVIEW.md)

> **Golden Milestone Baseline**: Tag **`reference`** (commit `9a2ade9`).

**Status Legend**: `[x]` Working • `[-]` In Progress • `[ ]` Planned

---

## 1. Privacy, Security & Content Blocking

- `[x]` **Native Rust Adblock Engine**: Brave `adblock-rust` compiled via Android NDK across `arm64-v8a`, `armeabi-v7a`, `x86_64`, and `universal` ABIs with privileged scriptlets (`PermissionMask::from_bits(u8::MAX)`), `$csp` enforcement, `$popup`/`$popunder` matching, FlatBuffers binary caching (`onyx_filters.bin`), and lock-free Kotlin LRU decision caches.
- `[x]` **54 Content Filter Lists & Auto-Update**: Built-in regional and privacy filter lists managed by `FilterListManager` and updated every 12h by default via `UpdateScheduler` (`UpdateTask.FILTERS`). Long-pressing the refresh button in Content Filters opens `UpdateActionButton` to pick 6h, 12h, 24h, 2 days, or disable auto-update.
- `[x]` **Dual-Tier Blocking & CNAME Uncloaking**: Standard and Aggressive blocking levels with dynamic document-start evaluation and dual-pass CNAME uncloaking (`AdBlockDomainManager`).
- `[x]` **Cosmetic, Procedural & Scriptlet Filtering**: Synchronous pre-parse CSS injection, 15-operator procedural filter engine (`adblock_procedural_engine.js`), Brave scriptlets with Trusted Types CSP compatibility, and `DsdHydrationFix` to prevent blank Declarative Shadow DOM pages.
- `[x]` **Streaming Video Ad & Clickjack Defusal**: Capture-phase clickjack trap neutralization, cross-domain ad redirect blocking, `google-ima.js` surrogate support, empty `<VAST version="3.0"/>` XML synthesis, and HTML5 video ad auto-skipping while preserving `worker-src blob:` MSE workers.
- `[x]` **Service Worker Interception**: Background Service Worker request filtering and surrogate redirection (`AdBlockServiceWorkerHelper`).
- `[x]` **In-Page Element Picker (Zapper)**: Visual element selector to hide page elements and persist custom CSS rules per domain.
- `[x]` **Per-Site Shields & Unified Script Control**: Domain-scoped shields, HTTPS upgrades, and bidirectional synchronization between Shields "Block Scripts" and Site Settings "JavaScript".
- `[x]` **Granular Clear Data & Clear on Exit**: `ClearBrowsingDataDialog` and `ClearOnExitBottomSheet` supporting independent selection of History, Open Tabs, Cookies & Site Data, and Cache across manual clearing, Back exit, Recents swipe-away (`ClearOnExitService`), and cold-start recovery.
- `[x]` **Network Privacy Pipeline**: `HttpsUpgradeInterceptor`, `AuthBypassInterceptor`, `AdBlockRequestInterceptor`, `PrivacyUrlSanitizer` (strips `utm_*`, `fbclid`, `gclid`, `msclkid`, etc.), WebRTC IP leak protection, Social SSO blocking, Google Safe Browsing, and DNS-over-HTTPS (Cloudflare, Google, NextDNS, Quad9, AdGuard, custom).
- `[x]` **SQLCipher AES-256 Storage Encryption**: Encrypted Room database (`AppDatabase`) for tabs, history, bookmarks, downloads, userscripts, and site permissions, backed by Android KeyStore master keys.
- `[x]` **User-Agent Switcher**: 12 desktop, mobile, tablet, and crawler presets plus custom User-Agent input.

---

## 2. Userscript Engine (Tampermonkey & Violentmonkey Compatible)

- `[x]` **Document-Start Renderer Injection**: Scripts are wrapped with `assets/scripts/userscript_wrapper.js` (cached via `AssetScriptLoader`) and injected via `WebViewCompat.addDocumentStartJavaScript` across main frames and subframes (`@noframes` supported) with full CSP and Trusted Types immunity.
- `[x]` **Metadata & Pattern Matching**: Parses `// ==UserScript==` headers (`@name`, `@namespace`, `@version`, `@match`, `@include`, `@exclude`, `@exclude-match`, `@run-at`, `@grant`, `@connect`, `@require`, `@resource`, `@noframes`, `@unwrap`, `@inject-into`, `@sandbox`, `@updateURL`, `@downloadURL`, `@icon`, and localized tags).
- `[x]` **Sandbox Hardening (`@inject-into`, `@sandbox`, `@connect` Prompt)**:
  - Supports `@inject-into` (`auto`, `page`, `content`) and `@sandbox` (`default`, `raw`, `JavaScript`, `DOM`).
  - Isolated scripts run inside a `Proxy`-backed sandbox `window` so globals do not leak onto the page while `unsafeWindow` accesses the host window.
  - First-use confirmation dialog per `(scriptId, domain)` on `GM_xmlhttpRequest` calls (Always Allow, Allow Once, Deny) persisted via `UserScriptManager`.
- `[x]` **Full `GM_*` & `GM.*` API Suite**: `GM_info`, `GM_getValue`, `GM_setValue`, `GM_deleteValue`, `GM_listValues`, batch `GM_getValues`/`GM_setValues`/`GM_deleteValues`, tab-scoped `GM_getTab`/`GM_saveTab`/`GM_getTabs`, `GM_addValueChangeListener`, `GM_removeValueChangeListener`, `GM_xmlhttpRequest` (including binary `blob`/`arraybuffer` and session cookies), `GM_download`, `GM_addStyle`, `GM_addElement`, `GM_registerMenuCommand`, `GM_unregisterMenuCommand`, `GM_openInTab`, `GM_setClipboard`, `GM_notification`, `GM_getResourceText`, `GM_getResourceURL`, and `GM_log`.
- `[x]` **120 FPS Code Editor (`UserScriptEditorActivity`)**: Native `ScriptCodeEditorView` + `LineNumberGutterView` with `OverScroller` fling physics, Android 11+ `setInitialSurroundingSubText` IME optimization, viewport-cached possessive-regex syntax highlighting (`UserScriptSyntaxHighlighter`), undo/redo (`EditHistoryTracker`), word-wrap toggle, Go to Line, and Find & Replace.
- `[x]` **One-Tap Web Install & Auto-Updates**: Intercepts `.user.js` links on GreasyFork, GitHub, and OpenUserJS with `InstallUserScriptBottomSheet`. Background update checks run via `UpdateScheduler` (`UpdateTask.USERSCRIPTS`, default 12h, configurable 6h/12h/24h/2d/off by long-pressing the refresh button).

---

## 3. Multi-Profile, Authentication & Incognito

- `[x]` **Chromium Multi-Profile Partitioning**: Physical session separation via AndroidX `ProfileStore` (`incognito` profile) with automated purge on session close and `IME_FLAG_NO_PERSONALIZED_LEARNING` keyboard privacy.
- `[x]` **Biometric Incognito Lock**: Optional fingerprint/face authentication via `BiometricPrompt`.
- `[x]` **WebAuthn Passkeys & Password Managers**: FIDO2 / WebAuthn Level 3 passkeys (`PasskeyWebAuthnBridge`) with verified origin + `clientDataHash`, canonical `clientDataJSON` injection, W3C `PasswordCredential`, and Android `AutofillManager` integration (`AutofillHelper`).
- `[x]` **Anti-Bot Reflection Masking**: `ChromeEnvironmentBridge` masks bridge properties from window reflection on challenge origins and provides authentic Chrome `navigator.userAgentData` brands.

---

## 4. Media, Audio & Video Playback

- `[x]` **Background Playback & Foreground Service**: Uninterrupted audio/video playback with Page Visibility interception, YouTube `_lact` keep-alive, `MediaPlaybackService` lockscreen controls, W3C `setPositionState` progress, high-res artwork resolution, and headphone disconnect pause.
- `[x]` **Picture-in-Picture (PiP)**: Stylesheet-only active video isolation, Android 12+ `setAutoEnterEnabled` + Android 8–11 `onUserLeaveHint` transitions, clamped `[0.5, 2.0]` aspect ratios, and deterministic expand-vs-close lifecycle handling (`PipController`).
- `[x]` **Battery & CPU Tab Governor**: Suspends background tabs while keeping timers active only on the media-playing tab.

---

## 5. Tabs, Navigation & Search

- `[x]` **Compose Visual Tab Switcher (`TabSwitcherBottomSheet`)**: Grid preview cards with live snapshots, favicons, bidirectional swipe-to-dismiss with spring physics and undo, normal/incognito segmented control, and `CloseAllTabsDialog`.
- `[x]` **Tab Groups & Idle Tab Auto-Archive**: Named, color-coded tab groups (`TabGroupItem`, `TabGroupDao`, Room v9 `MIGRATION_8_9`) with horizontal group filter chips, collapsible group section headers, in-group `+` tab creation, 8-color group editor, ungrouping, and full group closure with batch undo. Inactive background tabs exceeding the configurable auto-archive threshold (Never, 1 day, 3 days, 7 days [default], 14 days, or 30 days; configurable in the Tab Switcher and Settings > Accessibility & Gestures) are automatically hibernated and moved to **Archived Tabs** (`ArchivedTabsBottomSheet`) with one-tap restore, Restore All, and Close All with undo.
- `[x]` **RAM-Aware LRU WebView Pool (`TabCoordinator`)**: Scales live WebViews (3–5) by device memory class, hibernating inactive tabs to disk (or memory for incognito) and responding to `onTrimMemory`.
- `[x]` **Popup & Window Integrity**: User-gesture gated `onCreateWindow`, accurate `window.close()` tab targeting, transient popup retirement on download/intent handoff, and adjacent tab selection on close.
- `[x]` **Omnibox, Clipboard & Scanner**: Low-latency multi-engine suggestions (`SearchSuggestionRepository`), custom search engines with keyword triggers, zero-toast Android 12+ lazy clipboard suggestions with eye-reveal toggle, voice search, reverse image search, and CameraX + ML Kit QR/barcode scanner.

---

## 6. Downloads & Web Tools

- `[x]` **Multi-Threaded Downloader & External Handoff**: Native pause/resume download engine (`DownloadEngine`), POST form download replay (`FormBridge`), `DownloadPromptBottomSheet`, external downloader handoff (1DM, ADM, FDM), multi-select batch actions, and SHA-256/MD5 checksum inspector (`DownloadDetailsSheet`).
- `[x]` **Page Export (`PageExportCoordinator`)**: Save as original source, Web Archive (`.mht`), headless PDF (`.pdf`), or system print.
- `[x]` **In-Page Translation & DevTools**: CSP-immune 45+ language DOM translation engine, regex Find in Page, eTLD+1 persistent Desktop Mode with Client Hints synchronization, and bundled offline Eruda DevTools.
- `[x]` **Channel-Aware In-App Updater**: Unified Release/Prerelease update checker (`AppUpdateManager`, `ReleaseFeedParser`) with 12-hour background checks (`UpdateTask.APP`), status bar notification (`AppUpdateNotifier`), redesigned update dialog (`AppUpdatePromptHelper`), and rolling odometer versioning.

---

## 7. UI & Theming

- `[x]` **Jetpack Compose & Material 3 (`OnyxTheme`)**: Compose UI across settings, hubs (Bookmarks, History, Downloads, UserScripts), menus, and dialogs with dynamic accent color, `tonalElevation = 0.dp` neutral surfaces, Light/Dark/AMOLED themes, Force Dark Web Contents, Top/Bottom address bar placement, scroll auto-hide, 76dp pull-to-refresh, drag-to-reorder homepage shortcuts, and adaptive launcher icons.

---

## 8. Roadmap

- `[-]` **WebGL Shader Compatibility**: Ongoing polyfills for complex WebGL shaders on older mobile GPUs.
