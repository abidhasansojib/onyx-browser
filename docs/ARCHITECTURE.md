# Onyx Browser — Architecture & Core Subsystems

Technical overview of Onyx Browser's architecture, native engines, WebView hardening, and domain subsystems.

---

## 1. High-Level Architecture

```text
┌─────────────────────────────────────────────────────────────┐
│                 Presentation Layer (UI)                     │
│  Jetpack Compose + Material 3 Screens, Dialogs & Sheets     │
│  XML ViewBinding Host in MainActivity (WebView & CameraX)   │
├──────────────────────────────┬──────────────────────────────┤
│      Browser State & Data    │       Media & Background     │
│   SQLCipher Room (AppDatabase)│  MediaPlaybackService (FG)  │
│   UDF ViewModels & Prefs     │   UpdateScheduler (WorkMgr)  │
├──────────────────────────────┴──────────────────────────────┤
│                 Hardened Chromium WebView                   │
│   ProfileStore Isolation (Normal / Incognito Partitioning)  │
│   Document-Start Scriptlets, Userscripts & WebAuthn Bridge  │
│   Chain-of-Responsibility RequestInterceptor Pipeline       │
├─────────────────────────────────────────────────────────────┤
│               Native Rust Content Blocking Engine           │
│   libadblock_bridge.so (Brave adblock-rust via NDK)         │
│   FlatBuffers Binary Cache (onyx_filters.bin) + Kotlin LRU  │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. Core Subsystems

### 2.1 Content Blocking (`AdBlockEngine`, `AdBlockRequestInterceptor`, `FilterListManager`)
- **Native Engine & Caching**: Evaluates 54 Brave filter lists + EasyList in `adblock-rust` (`PermissionMask::from_bits(u8::MAX)`). Repeat lookups hit lock-free Kotlin LRU caches (`requestDecisionCache` 8,192 entries, `checkUrlCache` 4,096 entries) before crossing JNI.
- **Filter Synchronization**: `FilterListManager` downloads enabled lists into `filter_cache/` and compiles them atomically via `.tmp` replacement into `onyx_filters.bin`. Background updates run via `UpdateScheduler` (`UpdateTask.FILTERS`, default 12h, configurable 6h/12h/24h/2d/off).
- **CNAME Uncloaking (`AdBlockDomainManager`)**: Dual-pass evaluation tests the uncloaked target host first, then falls back to the original first-party alias.
- **Surrogates & CSP Injection**: Serves `$redirect` surrogates (`google-ima.js`, `adsbygoogle.js`, `noopvast`, etc.) and empty `<VAST version="3.0"/>` XML with credentialed CORS headers. Injects `$csp` rules at document start via `<meta http-equiv="Content-Security-Policy">` while rewriting `worker-src 'none'` to `worker-src blob:` to preserve MSE video workers.
- **Cosmetic & Procedural Filtering (`ShieldBridge`, `AdBlockDocumentStart`)**: Synchronous document-start CSS and Brave scriptlet injection with Trusted Types policy support (`onyx-shield-policy`), 15-operator procedural filter engine (`adblock_procedural_engine.js`), capture-phase clickjack defusal, and HTML5 video ad skipping.
- **Declarative Shadow DOM Safety (`DsdHydrationFix`)**: Strips stalled `dsd-pending` attributes across root documents and open shadow roots on `onPageFinished` so blocked hydration trackers never leave pages blank.

### 2.2 Userscript Engine (`UserScriptManager`, `UserScriptMetadata`, `UserScriptBridge`)
- **Document-Start Injection**: Enabled scripts are bundled with `assets/scripts/userscript_wrapper.js` (loaded via `AssetScriptLoader`) and registered via `WebViewCompat.addDocumentStartJavaScript`, running prior to page scripts without `eval()` or CSP conflicts.
- **Sandbox & World Isolation**: `UserScriptMetadata` parses `@inject-into` (`auto`, `page`, `content`), `@sandbox` (`default`, `raw`, `JavaScript`, `DOM`), `@unwrap`, and `@grant` to compute `isIsolatedWorld`. Isolated scripts execute inside a `Proxy`-backed sandbox `window` (`__sandboxWindow`) while keeping `unsafeWindow` bound to the host page window.
- **`GM_*` & `GM.*` APIs**: Full synchronous and Promise-based APIs including `GM_getValue`, `GM_setValue`, `GM_deleteValue`, `GM_listValues`, batch `GM_getValues`/`GM_setValues`/`GM_deleteValues`, tab-scoped `GM_getTab`/`GM_saveTab`/`GM_getTabs`, `GM_addValueChangeListener`, `GM_removeValueChangeListener`, `GM_xmlhttpRequest` (binary `blob`/`arraybuffer` support, cookie roundtripping), `GM_download`, `GM_addStyle`, `GM_addElement`, `GM_registerMenuCommand`, `GM_openInTab`, `GM_setClipboard`, `GM_notification`, `GM_getResourceText`, and `GM_getResourceURL`.
- **First-Use `@connect` Prompt**: `UserScriptBridge.xhr` checks declared `@connect` domains and prompts the user on first cross-origin access per `(scriptId, host)` with **Always Allow**, **Allow Once**, and **Deny** options.
- **Code Editor (`UserScriptEditorActivity`)**: 120 FPS native `ScriptCodeEditorView` with `LineNumberGutterView`, `OverScroller` fling physics, Android 11+ `setInitialSurroundingSubText` IME buffer capping, viewport-bounded regex syntax highlighting (`UserScriptSyntaxHighlighter`), undo/redo (`EditHistoryTracker`), and Find & Replace.

### 2.3 Media Playback & Picture-in-Picture (`MediaPlaybackService`, `MediaPlaybackBridge`, `PipController`)
- **Background Playback**: Page Visibility API interception (`visibilityState -> 'visible'`), YouTube `_lact` keep-alive, `userHitPause` touch-intent tracking, and background timer preservation (`resumeTimers()` on the active media tab while suspending idle tabs).
- **Audio Focus & Service Lifecycle**: Audio focus is managed exclusively by Chromium WebView's media session (`MediaPlaybackService` never requests competing `AUDIOFOCUS_GAIN`). The foreground service holds wake/Wi-Fi locks, handles headphone disconnect (`ACTION_AUDIO_BECOMING_NOISY`), and throttles notification progress updates.
- **Picture-in-Picture**: Stylesheet-only `[data-onyx-pip-video="true"]` isolation, Android 12+ `setAutoEnterEnabled` when video plays on the active tab, Android 8–11 `onUserLeaveHint` fallback, clamped `[0.5, 2.0]` aspect ratio updates, and `pendingPipCloseCheck` to distinguish expanding back to the browser from 'X' dismissal.

### 2.4 Multi-Profile Isolation, WebAuthn & Anti-Bot (`BrowserWebView`, `PasskeyWebAuthnBridge`, `ChromeEnvironmentBridge`)
- **Incognito Partitioning**: Uses AndroidX `ProfileStore.getOrCreateProfile("incognito")` for separate cookies, WebStorage, IndexedDB, and cache, purged via `TabCoordinator.purgeIncognitoProfileStatic()` when all incognito tabs close. Enforces `IME_FLAG_NO_PERSONALIZED_LEARNING` on private inputs.
- **WebAuthn Level 3 & Autofill**: `PasskeyWebAuthnBridge` marshals calls to the main thread, verifies `rpId` and origin, invokes Android `CredentialManager` with `CREDENTIAL_MANAGER_SET_ORIGIN`, injects canonical `clientDataJSON` matching `clientDataHash`, and supports W3C `PasswordCredential` alongside `AutofillHelper`.
- **Anti-Bot Masking**: `ChromeEnvironmentBridge` hides internal bridge symbols from `window` reflection on challenge domains (`fbsbx.com`, Arkose, Turnstile) and provides clean Chrome `navigator.userAgentData` brands.

### 2.5 Updates & Periodic Work (`AppUpdateManager`, `ReleaseFeedParser`, `UpdateScheduler`)
- **Channel-Aware App Updater**: Single parameterised flow in `AppUpdateManager` serving both `Release` and `Prerelease` channels via GitHub Atom feed (`ReleaseFeedParser`), web redirect, REST API, and HTML fallback.
- **Unified Scheduler**: `UpdateScheduler` and `UpdateTask` (`APP`, `FILTERS`, `USERSCRIPTS`) manage all periodic WorkManager jobs and foreground catch-up checks, backed by `UpdatePreferences` (`BrowserPreferences.updates`).

### 2.6 UI Coordinators, ViewModels & Preferences
- **UDF ViewModels (`com.onyx.browser.ui.viewmodel`)**: `BrowserViewModel`, `HistoryViewModel`, `BookmarksViewModel`, and `DownloadsViewModel` constructed via `AppViewModelFactory` and `AppContainer`.
- **Browser Coordinators & Controllers (`com.onyx.browser.ui.browser`)**: `TabCoordinator` (RAM-aware 3–5 live WebView LRU pool), `NavigationCoordinator`, `BrowserMenuCoordinator`, `WebChromeCoordinator`, `WebContextMenuCoordinator`, `FileChooserCoordinator`, `PageExportCoordinator`, `ToolbarLayoutController`, `SearchController`, `FindInPageController`, `HomeScreenController`, `PipController`, and `SettingsNavigator`.
- **Network Interceptors (`com.onyx.browser.web.interceptor`)**: `RealInterceptorChain` executing `HttpsUpgradeInterceptor`, `AuthBypassInterceptor`, `AdBlockRequestInterceptor`, and `PrivacyUrlSanitizer`.
- **Domain Preferences (`com.onyx.browser.data.preferences`)**: `BrowserPreferences` paired with `AppearancePreferences`, `ShieldPreferences`, `MediaAndDownloadPreferences`, `SearchPreferences`, and `UpdatePreferences`.
