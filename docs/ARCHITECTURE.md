# Onyx Browser — Architecture & Core Subsystems

Technical specification of Onyx Browser's stack, architecture, native engines, WebView integration, and domain subsystems.

---

## 1. Project Stack & High-Level Architecture

**Onyx Browser** (`com.onyx.browser`) targets **Min SDK 26** (Android 8.0) and **Target SDK 35** (Android 15).

- **UI**: Jetpack Compose + Material 3 (`OnyxTheme`, Compose BOM `2024.10.01`, Kotlin 2.0.21) for screens, settings, dialogs, and bottom sheets. XML ViewBinding is retained strictly for `MainActivity` (toolbar, `BrowserWebView`, and CameraX hosting) and platform `View` components (`ScriptCodeEditorView` via `AndroidView`).
- **Web Engine**: Android System WebView + AndroidX WebKit (`ProfileStore` multi-profile incognito isolation, `addDocumentStartJavaScript`, `WebMessageListener`, `UserAgentMetadata`).
- **Adblock**: Brave `adblock-rust` compiled via NDK into `libadblock_bridge.so` (`AdBlockEngine`) with FlatBuffers binary cache (`onyx_filters.bin`) and an in-memory LRU decision cache.
- **Persistence**: SQLCipher AES-256 encrypted Room database (`AppDatabase` v9) and domain-scoped preferences (`BrowserPreferences` + `AppearancePreferences`, `ShieldPreferences`, `MediaAndDownloadPreferences`, `SearchPreferences`, `UpdatePreferences`).
- **Background Sync**: Unified `UpdateScheduler` (`UpdateTask.APP`, `UpdateTask.FILTERS`, `UpdateTask.USERSCRIPTS`) on AndroidX WorkManager.

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
- **Native Engine & Caching**: Evaluates 54 Brave filter lists + EasyList in `adblock-rust` (`PermissionMask::from_bits(u8::MAX)`) with `RegexManagerDiscardPolicy` (30s cleanup interval, 180s unused regex eviction), pre-loaded `brave-resources.json` storage (zero duplicate engine allocation), and extended uBO/AdGuard rule option normalization (`denyallow=` -> `$to=~...`, `reason=`, `~popup`, `empty`, `mp4`, `fetch`, `strict3p`, `strict1p`, `inline-script`). Repeat lookups hit lock-free Kotlin LRU caches (`requestDecisionCache` 8,192 entries, `checkUrlCache` 4,096 entries, `cosmeticPayloadCache` 128 entries) before crossing JNI.
- **Filter Synchronization**: `FilterListManager` downloads enabled lists into `filter_cache/` and compiles them atomically via `.tmp` replacement into `onyx_filters.bin`. Background updates run via `UpdateScheduler` (`UpdateTask.FILTERS`, default 12h, configurable 6h/12h/24h/2d/off via `UpdateActionButton`).
- **CNAME Uncloaking & `$removeparam` (`AdBlockDomainManager`, `BrowserWebViewClient`)**: Dual-pass evaluation tests the uncloaked target host first, then falls back to the original first-party alias. Main-frame navigations combine static tracking parameter stripping with native `adblock-rust` `$removeparam` URL rewriting (`AdBlockEngine.getRewrittenUrl`).
- **Surrogates & CSP Injection**: Serves `$redirect` surrogates (`google-ima.js`, `adsbygoogle.js`, `noopvast`, etc.) and empty `<VAST version="3.0"/>` XML with credentialed CORS headers. Injects `$csp` rules at document start via `<meta http-equiv="Content-Security-Policy">` while rewriting `worker-src 'none'` to `worker-src blob:` to preserve MSE video workers.
- **Cosmetic & Procedural Filtering (`ShieldBridge`, `AdBlockDocumentStart`)**: Single-roundtrip `ShieldBridge.getDocumentStartPayload` delivering synchronous document-start CSS (including pure-CSS `:style(...)` rules promoted via `ProceduralOrActionFilter::as_css()`), Brave scriptlet injection with Trusted Types policy support (`onyx-shield-policy`), open Shadow DOM stylesheet synchronization, 15-operator procedural filter engine (`adblock_procedural_engine.js`), capture-phase clickjack defusal, and HTML5 video ad skipping.
- **Declarative Shadow DOM Safety (`DsdHydrationFix`)**: Strips stalled `dsd-pending` attributes across root documents and open shadow roots on `onPageFinished` so blocked hydration trackers never leave pages blank.

### 2.2 Userscript Engine (`UserScriptManager`, `UserScriptMetadata`, `UserScriptBridge`)
- **Document-Start Injection**: Enabled scripts are bundled with `assets/scripts/userscript_wrapper.js` (loaded via `AssetScriptLoader` and populated in a single pass by `UserScriptManager`) and registered via `WebViewCompat.addDocumentStartJavaScript`, running prior to page scripts without `eval()` or CSP conflicts. Edit `userscript_wrapper.js` directly rather than building JS strings in Kotlin.
- **Sandbox & World Isolation**: `UserScriptMetadata` parses `@inject-into` (`auto`, `page`, `content`), `@sandbox` (`default`, `raw`, `JavaScript`, `DOM`), `@unwrap`, and `@grant` to compute `isIsolatedWorld`. Isolated scripts execute inside a `Proxy`-backed sandbox `window` (`__sandboxWindow`) while keeping `unsafeWindow` bound to the host page window.
- **`GM_*` & `GM.*` APIs**: Full synchronous and Promise-based APIs including `GM_getValue`, `GM_setValue`, `GM_deleteValue`, `GM_listValues`, batch `GM_getValues`/`GM_setValues`/`GM_deleteValues`, tab-scoped `GM_getTab`/`GM_saveTab`/`GM_getTabs`, `GM_addValueChangeListener`, `GM_removeValueChangeListener`, `GM_xmlhttpRequest` (binary `blob`/`arraybuffer` support, cookie roundtripping), `GM_download`, `GM_addStyle`, `GM_addElement`, `GM_registerMenuCommand`, `GM_openInTab`, `GM_setClipboard`, `GM_notification`, `GM_getResourceText`, and `GM_getResourceURL`.
- **First-Use `@connect` Prompt**: `UserScriptBridge.xhr` checks declared `@connect` domains and prompts the user on first cross-origin access per `(scriptId, host)` with **Always Allow**, **Allow Once**, and **Deny** options persisted via `UserScriptManager`.
- **Code Editor (`UserScriptEditorActivity`)**: 120 FPS native `ScriptCodeEditorView` with `LineNumberGutterView`, `OverScroller` fling physics, Android 11+ `setInitialSurroundingSubText` IME buffer capping, viewport-bounded regex syntax highlighting (`UserScriptSyntaxHighlighter`), undo/redo (`EditHistoryTracker`), and Find & Replace.

### 2.3 Media Playback & Picture-in-Picture (`MediaPlaybackService`, `MediaPlaybackBridge`, `PipController`)
- **Background Playback**: Page Visibility API interception (`visibilityState -> 'visible'`), YouTube `_lact` keep-alive, `userHitPause` touch-intent tracking, and background timer preservation (`resumeTimers()` on the active media tab while suspending idle tabs). Never pause media in `onPause()`; `onStop()` decides based on the background playback preference.
- **Audio Focus & Service Lifecycle**: Audio focus is managed exclusively by Chromium WebView's media session (`MediaPlaybackService` never requests competing `AUDIOFOCUS_GAIN`). The foreground service holds wake/Wi-Fi locks, handles headphone disconnect (`ACTION_AUDIO_BECOMING_NOISY`), and throttles notification progress updates.
- **Picture-in-Picture**: Stylesheet-only `[data-onyx-pip-video="true"]` isolation, Android 12+ `setAutoEnterEnabled(true)` only while video is playing on the active foreground tab, Android 8–11 `onUserLeaveHint` fallback, clamped `[0.5, 2.0]` aspect ratio updates, and `pendingPipCloseCheck` to distinguish expanding back to the browser from 'X' dismissal.

### 2.4 Multi-Profile Isolation, WebAuthn & Anti-Bot (`BrowserWebView`, `PasskeyWebAuthnBridge`, `ChromeEnvironmentBridge`)
- **Incognito Partitioning**: Uses AndroidX `ProfileStore.getOrCreateProfile("incognito")` for separate cookies, WebStorage, IndexedDB, and cache, purged via `TabCoordinator.purgeIncognitoProfileStatic()` when all incognito tabs close. Enforces `IME_FLAG_NO_PERSONALIZED_LEARNING` on private inputs.
- **WebAuthn Level 3 & Autofill**: `PasskeyWebAuthnBridge` injects on login domains (excluded on `fbsbx.com` / Meta challenge frames), marshals calls to the main thread, verifies `rpId` and origin, invokes Android `CredentialManager` with `CREDENTIAL_MANAGER_SET_ORIGIN` + `clientDataHash`, injects canonical unescaped `clientDataJSON`, returns `false` for `isConditionalMediationAvailable()`, and supports W3C `PasswordCredential` alongside `AutofillHelper`.
- **Anti-Bot Masking**: `ChromeEnvironmentBridge` hides internal bridge symbols from `window` reflection on challenge domains (`fbsbx.com`, Arkose, Turnstile) and provides clean Chrome `navigator.userAgentData` brands so Google OAuth never fails with `disallowed_useragent`.

### 2.5 Database, Tabs & Privacy (`AppDatabase`, `TabCoordinator`, `ClearDataOnExitHelper`)
- **Encrypted Persistence**: All persistent Room tables use SQLCipher AES-256 (`AppDatabase` v9 with `tabs`, `tab_groups`, `history`, `bookmarks`, `downloads`, `user_scripts`, `user_script_values`, `site_permissions`). Never store sensitive browsing or credential data in unencrypted files.
- **Tab Groups & Auto-Archive**: `TabCoordinator` manages a RAM-aware 3–5 live WebView LRU pool, color-coded Tab Groups (`TabGroupItem`, `TabGroupDao`, `MIGRATION_8_9`), and automatic archiving of idle background tabs (`tabAutoArchiveHours`: Never, 1d, 3d, 7d default, 14d, 30d) into `archivedTabs` while protecting active, incognito, blank, and media-playing tabs.
- **Clear on Exit**: `ClearDataOnExitHelper` purges selected categories synchronously across Back exit, Recents swipe-away (`ClearOnExitService`), background task kill (`hasPendingExitClearance`), and cold-start recovery.

### 2.6 Updates & Periodic Work (`AppUpdateManager`, `ReleaseFeedParser`, `UpdateScheduler`)
- **Channel-Aware App Updater**: Single parameterised flow in `AppUpdateManager` serving both `Release` and `Prerelease` channels via GitHub Atom feed (`ReleaseFeedParser`), web redirect, REST API, and HTML fallback.
- **Unified Scheduler**: `UpdateScheduler` and `UpdateTask` (`APP`, `FILTERS`, `USERSCRIPTS`) manage all periodic WorkManager jobs and foreground catch-up checks (default 12h interval), backed by `UpdatePreferences` (`BrowserPreferences.updates`). Filter and userscript refresh buttons use `UpdateActionButton` (tap to update now; long-press to choose 6h, 12h, 24h, 2d, or disable).
- **Odometer Versioning**: GitHub Actions `RUN_NUM` maps to `MAJOR = 1 + (RUN_NUM / 1000)`, `MINOR = (RUN_NUM / 100) % 10`, `PATCH = RUN_NUM % 100`.

### 2.7 UI Coordinators, ViewModels & Compose Theming
- **Compose & Material 3 Theming**: `OnyxTheme` resolves dynamic accent color via `AccentColorManager.getAccentColor(context)` and sets `surfaceTint = Color.Transparent`. Enforce `tonalElevation = 0.dp` on all Compose sheets, dialogs, cards, and menus so dark surfaces remain `#202020` and light surfaces remain `#F7F7F8` without primary-color tint bleed. All `DialogFragment` / `BottomSheetDialogFragment` classes must provide a public no-arg / `@JvmOverloads` constructor and bind `BackHandler` in searchable/editable Compose activities.
- **UDF ViewModels (`com.onyx.browser.ui.viewmodel`)**: `BrowserViewModel`, `HistoryViewModel`, `BookmarksViewModel`, and `DownloadsViewModel` constructed via `AppViewModelFactory` and `AppContainer`.
- **Browser Coordinators & Controllers (`com.onyx.browser.ui.browser`)**: `TabCoordinator`, `NavigationCoordinator`, `BrowserMenuCoordinator`, `WebChromeCoordinator`, `WebContextMenuCoordinator`, `FileChooserCoordinator`, `PageExportCoordinator`, `ToolbarLayoutController`, `SearchController`, `FindInPageController`, `HomeScreenController`, `PipController`, and `SettingsNavigator`.
- **Network Interceptors (`com.onyx.browser.web.interceptor`)**: `RealInterceptorChain` executing `HttpsUpgradeInterceptor`, `AuthBypassInterceptor`, `AdBlockRequestInterceptor`, and `PrivacyUrlSanitizer`. `AuthBypassInterceptor` exempts OAuth/captcha traffic: provider-domain requests always, but page-domain and URL-pattern matches only for navigations and same-site requests; Meta ad pixels (`pixel|an|tr.facebook.com`) are never exempt. Brave embed-exception lists (Facebook, X, LinkedIn) are enabled by default in `FilterListManager`.
- **Domain Preferences (`com.onyx.browser.data.preferences`)**: `BrowserPreferences` paired with `AppearancePreferences`, `ShieldPreferences`, `MediaAndDownloadPreferences`, `SearchPreferences`, and `UpdatePreferences`.
