# AGENTS.md — Operational Rules & AI Guidelines

> **Directive**: Read and follow this document before modifying files or executing tasks in this repository. These rules govern AI agent behavior and take precedence over default instructions.

---

## 1. Technical Reference & Documentation

Technical specifications, architecture details, and subsystem guardrails are maintained in dedicated engineering documents rather than in this rules file. Always read the relevant specification before modifying a subsystem:

- **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** — Tech stack, core subsystems, Compose theming, Room/SQLCipher schema, media/PiP, userscripts, and update architecture.
- **[docs/WEBVIEW.md](docs/WEBVIEW.md)** — WebView hardening, multi-profile isolation, request interception, JavaScript bridges, and lifecycle rules.
- **[docs/FEATURES.md](docs/FEATURES.md)** — Feature tracker and implementation status (`[x]` Working, `[-]` In Progress, `[ ]` Planned).
- **[CONTRIBUTING.md](CONTRIBUTING.md)** & **[SECURITY.md](SECURITY.md)** — Contribution workflow and security policies.

---

## 2. Mandatory Operational Rules

### RULE 1: No Local Builds
- **Forbidden**: Never run `./gradlew`, `cargo`, `rustc`, `cmake`, `ninja`, `make`, `docker`, or Gradle daemons locally. The workspace runs inside a resource-constrained Android Linux environment.
- **Allowed**: File edits, Python/Bash scripts, lightweight static verification, Git, and GitHub CLI (`gh`).

### RULE 2: Explicit User Build Gate
- Do **not** trigger GitHub Actions builds during normal conversation.
- If the user says **"build app now"** without specifying a build type, **ask** whether they want **Release** or **Prerelease**.
- Trigger commands (only after explicit request or confirmation):
  - **Release**: `gh workflow run build.yml -f build_type=Release --ref main`
  - **Prerelease**: `gh workflow run build.yml -f build_type=Prerelease --ref main`
  - Debug builds are permanently removed.

### RULE 3: Autonomous Remote Build & Auto-Fix Loop
When a build is requested:
1. Commit and push all changes to `origin main`, then trigger `build.yml`.
2. Monitor the run using the `schedule` tool and `gh run view <run_id>` (never poll in a shell loop).
3. **On Success**: Report the version tag, changelog, and release download links. Do not download APK artifacts locally unless asked.
4. **On Failure**: Fetch failure logs via `gh run view --log-failed --job=<job_id>`, fix the root cause locally, commit, push, and re-trigger until the build succeeds.

### RULE 4: Documentation & Feature Tracking
- Keep documentation concise, technical, and free of filler or marketing language.
- Update [docs/FEATURES.md](docs/FEATURES.md) and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) / [docs/WEBVIEW.md](docs/WEBVIEW.md) whenever features or subsystem architectures change.
- Keep `AGENTS.md` strictly focused on AI operational rules, naming conventions, verification procedures, and the directory map.
- Never commit scratch scripts, plan files, or temporary agent artifacts.

### RULE 5: Naming & Package Conventions (No Brand Prefix)
- **Never prefix** classes, interfaces, composables, functions, files, or drawables with `Onyx` (e.g., use `BrowserWebView`, `BrowserApplication`, `AppTopBar`, `AppAlertDialog`, `UserScriptBridge`).
- **Exception**: Exposed JavaScript DOM interfaces (`window.OnyxShieldBridge`, `window.onyxFormBridge`, `window.onyxBlobBridge`, `window.onyxTouchBridge`, `window.onyxGM`, `_OnyxPasskeyBridge`) and the Compose theme wrapper `OnyxTheme` retain their names for script and UI compatibility.
- Use standard architectural suffixes:
  - `*Coordinator` — Lifecycle and navigation orchestrators (`TabCoordinator`, `NavigationCoordinator`, `BrowserMenuCoordinator`, `WebChromeCoordinator`, `WebContextMenuCoordinator`, `FileChooserCoordinator`, `PageExportCoordinator`)
  - `*Controller` — Bound UI and view controllers (`ToolbarLayoutController`, `SearchController`, `FindInPageController`, `HomeScreenController`, `PipController`)
  - `*BottomSheet` — Modal bottom sheets (`MenuBottomSheet`, `SiteShieldBottomSheet`, `WebPermissionBottomSheet`, `ClearOnExitBottomSheet`, `InstallUserScriptBottomSheet`)
  - `*Bridge` — WebView JavaScript bridges (`ShieldBridge`, `FormBridge`, `BlobBridge`, `TouchBridge`, `NotificationBridge`, `PasskeyWebAuthnBridge`, `RegexFindBridge`, `UserScriptBridge`)
  - `*Preferences` — Domain preference stores (`AppearancePreferences`, `ShieldPreferences`, `MediaAndDownloadPreferences`, `SearchPreferences`, `UpdatePreferences`)
  - `*Repository` — Data repositories (`HistoryRepository`, `BookmarkRepository`, `DownloadRepository`, `SitePermissionRepository`, `SearchSuggestionRepository`)

---

## 3. Pre-Commit Static Verification

Because local compilation is disabled, verify every change before committing:
1. **Syntax & Balance**: Check balanced braces `{}`, parentheses `()`, and quotes across all modified Kotlin, Java, JS, and XML files.
2. **Imports & Call Sites**: Verify package headers match directory paths and all referenced symbols/imports exist across `:app`, `:core:common`, `AndroidManifest.xml`, `app/proguard-rules.pro`, and `app/src/test/`.
3. **Resources**: Pair new colors in `res/values/colors.xml` and `res/values-night/colors.xml`. Protect runtime-resolved resources in `res/raw/keep.xml` (`isShrinkResources = true`).
4. **Conventional Commits**: Format commit messages as `<type>(<scope>): <concise summary>`.

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
└── scripts/                    # bootstrap_dev.sh, generate_release_notes.py, notify_upstream_update.py, sync_upstream.sh, update_filter_lists.sh
```
