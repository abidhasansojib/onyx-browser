# AGENTS.md — Operational Rules & Engineering Guide

> **Directive**: Read and follow this document before modifying or executing tasks in this repository. These rules take precedence over default assistant behavior.

---

## 1. Project Stack

**Onyx Browser** (`com.onyx.browser`) — Android browser targeting **Min SDK 26** (Android 8.0) and **Target SDK 35** (Android 15).

- **UI**: Jetpack Compose + Material 3 (`OnyxTheme`, Compose BOM `2024.10.01`, Kotlin 2.0.21) for screens, settings, dialogs, and bottom sheets. XML ViewBinding is retained strictly for `MainActivity` (toolbar, `BrowserWebView`, and CameraX hosting) and platform `View` components (`ScriptCodeEditorView` via `AndroidView`).
- **Web Engine**: Android System WebView + AndroidX WebKit (`ProfileStore` multi-profile incognito isolation, `addDocumentStartJavaScript`, `WebMessageListener`, `UserAgentMetadata`).
- **Adblock**: Brave `adblock-rust` compiled via NDK into `libadblock_bridge.so` (`AdBlockEngine`) with FlatBuffers binary cache (`onyx_filters.bin`) and an in-memory LRU decision cache.
- **Persistence**: SQLCipher AES-256 encrypted Room database (`AppDatabase`) and domain-scoped preferences (`BrowserPreferences` + `AppearancePreferences`, `ShieldPreferences`, `MediaAndDownloadPreferences`, `SearchPreferences`, `UpdatePreferences`).
- **Background Sync**: Unified `UpdateScheduler` (`UpdateTask.APP`, `UpdateTask.FILTERS`, `UpdateTask.USERSCRIPTS`) on AndroidX WorkManager.

Further documentation:
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — Subsystem specifications and data flow
- [docs/WEBVIEW.md](docs/WEBVIEW.md) — WebView hardening, bridges, and lifecycle
- [docs/FEATURES.md](docs/FEATURES.md) — Feature tracker and status
- [CONTRIBUTING.md](CONTRIBUTING.md) & [SECURITY.md](SECURITY.md)

---

## 2. Mandatory Operational Rules

### RULE 1: No Local Builds
- **Forbidden**: Never run `./gradlew`, `cargo`, `rustc`, `cmake`, `ninja`, `make`, `docker`, or Gradle daemons locally. The workspace runs in a resource-constrained Linux environment inside Android.
- **Allowed**: File edits, Python/Bash scripts, lightweight static verification, Git, and GitHub CLI (`gh`).

### RULE 2: Explicit User Build Gate
- Do **not** trigger GitHub Actions builds during normal conversation.
- If the user says **"build app now"** without specifying a type, **ask** whether they want **Release** or **Prerelease**.
- Trigger commands (only after explicit request or confirmation):
  - **Release**: `gh workflow run build.yml -f build_type=Release --ref main`
  - **Prerelease**: `gh workflow run build.yml -f build_type=Prerelease --ref main`
  - Debug builds are permanently removed.

### RULE 3: Autonomous Remote Build & Auto-Fix Loop
When a build is requested:
1. Commit and push all changes to `origin main`, then trigger `build.yml`.
2. Monitor the run using the `schedule` tool and `gh run view <run_id>` (never poll in a shell loop).
3. **On Success**: Report the version tag, changelog, and release download links. Do not download APK artifacts locally unless asked.
4. **On Failure**: Fetch logs via `gh run view --log-failed --job=<job_id>`, fix the root cause locally, commit, push, and re-trigger until the build succeeds.

### RULE 4: Documentation & Feature Tracking
- Keep documentation concise, technical, and free of filler or marketing language.
- Update `docs/FEATURES.md` (`[x]` Working, `[-]` In Progress, `[ ]` Planned) and `docs/ARCHITECTURE.md` / `AGENTS.md` when features or architecture change.
- Never commit scratch scripts, plan files, or agent artifacts.

### RULE 5: Golden Milestone Immutability
- **Baseline Reference**: Tag **`reference`** (commit `9a2ade9`). Never bump or redeclare the Golden Milestone tag unless explicitly instructed by the user.

### RULE 6: Naming & Package Conventions (No Brand Prefix)
- **Never prefix** classes, interfaces, composables, functions, files, or drawables with `Onyx` (use `BrowserWebView`, `BrowserApplication`, `AppTopBar`, `AppAlertDialog`, `UserScriptBridge`).
- **Exception**: Exposed JavaScript DOM interfaces (`window.OnyxShieldBridge`, `window.onyxFormBridge`, `window.onyxBlobBridge`, `window.onyxTouchBridge`, `window.onyxGM`, `_OnyxPasskeyBridge`) and theme wrapper `OnyxTheme` keep their existing names for script/UI compatibility.
- Use standard architectural suffixes:
  - `*Coordinator` — Lifecycle/navigation orchestrators (`TabCoordinator`, `NavigationCoordinator`, `BrowserMenuCoordinator`, `WebChromeCoordinator`, `WebContextMenuCoordinator`, `FileChooserCoordinator`, `PageExportCoordinator`)
  - `*Controller` — Bound UI/view controllers (`ToolbarLayoutController`, `SearchController`, `FindInPageController`, `HomeScreenController`, `PipController`)
  - `*BottomSheet` — Modal sheets (`MenuBottomSheet`, `SiteShieldBottomSheet`, `WebPermissionBottomSheet`, `ClearOnExitBottomSheet`, `InstallUserScriptBottomSheet`)
  - `*Bridge` — WebView JS bridges (`ShieldBridge`, `FormBridge`, `BlobBridge`, `TouchBridge`, `NotificationBridge`, `PasskeyWebAuthnBridge`, `RegexFindBridge`, `UserScriptBridge`)
  - `*Preferences` — Domain preference stores (`AppearancePreferences`, `ShieldPreferences`, `MediaAndDownloadPreferences`, `SearchPreferences`, `UpdatePreferences`)
  - `*Repository` — Data repositories (`HistoryRepository`, `BookmarkRepository`, `DownloadRepository`, `SitePermissionRepository`, `SearchSuggestionRepository`)

---

## 3. Pre-Commit Static Verification

Because local compilation is disabled, verify every change before committing:
1. **Syntax & Balance**: Check balanced braces `{}`, parentheses `()`, and quotes across modified Kotlin, Java, JS, and XML files.
2. **Imports & Call Sites**: Verify package headers match directory paths and all referenced symbols/imports exist across `:app`, `:core:common`, `AndroidManifest.xml`, `app/proguard-rules.pro`, and `app/src/test/`.
3. **Resources**: Pair new colors in `res/values/colors.xml` and `res/values-night/colors.xml`. Protect runtime-resolved resources in `res/raw/keep.xml` (`isShrinkResources = true`).
4. **Conventional Commits**: Use `<type>(<scope>): <concise summary>`.

---

## 4. Directory Map

```text
onyx-browser/
├── .github/workflows/          # build.yml (NDK + unit tests + Release/Prerelease APKs), sync_upstream.yml
├── app/
│   ├── schemas/                # Room KSP exported schemas
│   ├── proguard-rules.pro      # R8 keep rules (JNI, bridges, UpdateScheduler$UpdateWorker)
│   └── src/
│       ├── main/
│       │   ├── assets/         # easylist_rules.txt, eruda.min.js, scripts/ (WebAuthn, media, adblock, userscript_wrapper.js)
│       │   ├── java/com/onyx/browser/
│       │   │   ├── MainActivity.kt & BrowserApplication.kt
│       │   │   ├── autofill/       # AutofillHelper (AutofillManager & Credential Manager)
│       │   │   ├── data/           # AppDatabase, DAOs, filter/FilterListManager, model/, preferences/, repository/
│       │   │   ├── di/             # AppContainer, DefaultAppContainer, AppViewModelFactory
│       │   │   ├── download/       # DownloadEngine, DownloadHandler, DownloadForegroundService
│       │   │   ├── incognito/      # IncognitoNotificationHelper, IncognitoNotificationReceiver
│       │   │   ├── media/          # MediaPlaybackService, MediaPlaybackBridge
│       │   │   ├── nativebridge/   # AdBlockEngine JNI bindings
│       │   │   ├── privacy/        # ClearOnExitService, ClearDataOnExitHelper
│       │   │   ├── ui/             # browser/, components/, downloads/, home/, menu/, settings/, tabs/, theme/, viewmodel/
│       │   │   ├── update/         # AppUpdateManager, ReleaseFeedParser, AppUpdateDownloader, AppUpdatePromptHelper, AppUpdateNotifier, UpdateScheduler
│       │   │   ├── userscript/     # UserScriptManager, UserScriptMetadata, UserScriptUrlMatcher, UserScriptBridge, UserScriptNotificationHelper
│       │   │   └── web/            # BrowserWebView, BrowserWebViewClient, AssetScriptLoader, adblock/, bridge/, error/, interceptor/, navigation/, translate/
│       │   └── res/
│       └── test/               # JUnit 4 + MockK unit tests
├── core/common/                # DispatcherProvider, ResultState
├── docs/                       # ARCHITECTURE.md, WEBVIEW.md, FEATURES.md
├── gradle/libs.versions.toml   # Gradle Version Catalog
├── rust_engine/                # Rust NDK crate (libadblock_bridge.so)
└── scripts/                    # bootstrap_dev.sh, update_filter_lists.sh, generate_release_notes.py
```

---

## 5. Core Subsystem Guardrails

1. **Compose & Material 3 Theming**:
   - `OnyxTheme` resolves dynamic accent color via `AccentColorManager.getAccentColor(context)` and sets `surfaceTint = Color.Transparent`.
   - Enforce `tonalElevation = 0.dp` on all Compose sheets, dialogs, cards, and menus so dark surfaces remain `#202020` and light surfaces remain `#F7F7F8` without primary-color tint bleed.
   - All `DialogFragment` / `BottomSheetDialogFragment` classes must provide a public no-arg / `@JvmOverloads` constructor and bind `BackHandler` in searchable/editable Compose activities.
2. **Database & Privacy**:
   - All persistent Room tables use SQLCipher AES-256 (`AppDatabase`). Never store sensitive browsing or credential data in unencrypted files.
   - `ClearDataOnExitHelper` purges selected categories synchronously across Back exit, Recents swipe-away (`ClearOnExitService`), background task kill (`hasPendingExitClearance`), and cold-start recovery.
3. **WebView & Interception**:
   - `shouldInterceptRequest` runs on background I/O threads: only read lock-free in-memory caches (`SitePermissionRepository`, `AdBlockEngine` LRU cache), return `EmptyInputStream` with `Content-Length: 0` and pre-allocated CORS headers for blocked subresources, and never return synthetic 3xx `WebResourceResponse` redirects.
   - `@JavascriptInterface` methods run on Chromium's `JavaBridge` thread: marshal onto the main thread before reading `WebView` properties (`webView.url`, `evaluateJavascript`).
   - Prefer `WebViewCompat.addWebMessageListener` for asynchronous bridges (`TouchBridge`, `FormBridge`, `BlobBridge`).
4. **WebAuthn & Anti-Bot**:
   - `PasskeyWebAuthnBridge` injects on login domains (excluded on `fbsbx.com` / Meta challenge frames), passes verified web `origin` + `clientDataHash` to Android `CredentialManager`, injects canonical unescaped `clientDataJSON`, and returns `false` for `isConditionalMediationAvailable()`.
   - `ChromeEnvironmentBridge` masks bridge properties from `window` reflection on challenge origins and provides Chrome `navigator.userAgentData` brands so Google OAuth never fails with `disallowed_useragent`.
5. **Media & Picture-in-Picture**:
   - Audio focus belongs strictly to Chromium WebView's media session; `MediaPlaybackService` must never request its own `AUDIOFOCUS_GAIN`.
   - Never pause media in `onPause()`; `onStop()` decides based on background playback preference.
   - `PipController` arms `setAutoEnterEnabled(true)` on Android 12+ only while video is playing on the active foreground tab, clamps aspect ratios to `[0.5, 2.0]`, and distinguishes PiP expand from 'X' close via `pendingPipCloseCheck`.
6. **Userscripts**:
   - The userscript runtime wrapper lives in `app/src/main/assets/scripts/userscript_wrapper.js` (loaded via `AssetScriptLoader` and filled in a single pass by `UserScriptManager`); edit the JS asset directly rather than building JS strings in Kotlin.
   - `UserScriptMetadata` parses `@inject-into` (`auto`, `page`, `content`) and `@sandbox` (`default`, `raw`, `JavaScript`, `DOM`) to resolve `isIsolatedWorld`. Isolated scripts execute inside a `Proxy`-backed sandbox `window` while `unsafeWindow` points to the host page window.
   - `UserScriptBridge.xhr` enforces `@connect` rules and shows a per-script, per-domain first-use confirmation dialog (Always Allow, Allow Once, Deny) with decisions persisted via `UserScriptManager`.
7. **Updates & Periodic Work**:
   - All periodic background jobs route through `UpdateScheduler` and `UpdateTask` (`APP`, `FILTERS`, `USERSCRIPTS`), with state stored in `UpdatePreferences` (`BrowserPreferences.updates`).
   - App updates, filter lists, and userscripts default to a 12-hour check interval. Filter and userscript refresh buttons use `UpdateActionButton` (tap to update now; long-press to choose 6h, 12h, 24h, 2d, or disable).
   - Versioning follows the rolling odometer formula from GitHub Actions `RUN_NUM`: `MAJOR = 1 + (RUN_NUM / 1000)`, `MINOR = (RUN_NUM / 100) % 10`, `PATCH = RUN_NUM % 100`.
