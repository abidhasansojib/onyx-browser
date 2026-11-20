# Contributing to Onyx Browser

This guide covers workstation setup, module structure, coding conventions, and pull request requirements.

---

## 1. Prerequisites

To build and test Onyx Browser on Linux, macOS, or Windows (WSL2):

- **JDK 17** (e.g. Eclipse Temurin 17)
- **Android SDK** (Compile & Target SDK **API 35**, Min SDK **API 26**)
- **Android NDK r26b+** (`26.3.11579264` or newer, with `ANDROID_NDK_HOME` set)
- **Rust Toolchain & `cargo-ndk`**:
  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
  cargo install cargo-ndk
  ```
- **Python 3 & curl** (for asset and filter list scripts)

---

## 2. Quick Setup

Clone the repository with submodules and run the bootstrap script:

```bash
git clone --recurse-submodules https://github.com/abidhasansojib/onyx-browser.git
cd onyx-browser
./scripts/bootstrap_dev.sh
```

`./scripts/bootstrap_dev.sh` initializes submodules (`external/adblock-rust`, `external/adblock-lists`), syncs icons and filter lists, and compiles `libadblock_bridge.so` into `app/src/main/jniLibs/` (pass `--skip-rust` when working only on Kotlin/JVM code).

---

## 3. Module & Package Structure

| Component | Responsibility |
| :--- | :--- |
| `:app` | Android application (`com.onyx.browser`): Compose + ViewBinding UI, ViewModels, hardened WebView, SQLCipher Room DB, and userscript engine. |
| `:core:common` | Shared utilities (`DispatcherProvider`, `DefaultDispatcherProvider`, `ResultState`). |
| `rust_engine/` | Native Rust NDK bridge (`libadblock_bridge.so`) wrapping Brave `adblock-rust` for `AdBlockEngine`. |
| `gradle/libs.versions.toml` | Centralized Gradle Version Catalog. |

### `:app` Packages

```text
com.onyx.browser
├── MainActivity.kt          # Browser window host, toolbar, and coordinator wiring
├── BrowserApplication.kt    # Startup lifecycle, SQLCipher init, async Chromium pre-warm
├── autofill/                # AutofillHelper (AutofillManager & Credential Manager)
├── data/                    # AppDatabase, DAOs, filter/FilterListManager, preferences/, repository/
├── di/                      # AppContainer, DefaultAppContainer, AppViewModelFactory
├── download/                # DownloadEngine, DownloadHandler, DownloadForegroundService
├── incognito/               # IncognitoNotificationHelper, IncognitoNotificationReceiver
├── media/                   # MediaPlaybackService, MediaPlaybackBridge
├── nativebridge/            # AdBlockEngine JNI bindings
├── privacy/                 # ClearOnExitService, ClearDataOnExitHelper
├── ui/                      # Compose screens/sheets, browser coordinators/controllers, ViewModels
├── update/                  # AppUpdateManager, ReleaseFeedParser, UpdateScheduler, AppUpdateNotifier
├── userscript/              # UserScriptManager, UserScriptMetadata, UserScriptUrlMatcher, UserScriptBridge
└── web/                     # BrowserWebView, BrowserWebViewClient, AssetScriptLoader, adblock/, bridge/, interceptor/, navigation/, translate/
```

---

## 4. Coding Conventions

1. **Compose for Screens, ViewBinding for WebView Host**:
   - Use Jetpack Compose with Material 3 (`OnyxTheme`, `tonalElevation = 0.dp`) for screens, settings, dialogs, and bottom sheets.
   - Keep XML ViewBinding for `MainActivity` (WebView and camera containers) and platform `View` widgets (`ScriptCodeEditorView`).
2. **Naming & Suffixes**:
   - Do not prefix internal classes, composables, or files with `Onyx`.
   - Use standard suffixes: `*Coordinator`, `*Controller`, `*BottomSheet`, `*Bridge`, `*Preferences`, `*Repository`.
3. **WebView Threading & Interception**:
   - `shouldInterceptRequest` runs on background threads: query only lock-free in-memory caches (`SitePermissionRepository`, `AdBlockEngine`).
   - `@JavascriptInterface` methods run on the `JavaBridge` thread: post to the main thread before accessing `WebView` APIs.
4. **Encrypted Persistence**:
   - Route persistent Room data through SQLCipher (`AppDatabase`). Never write sensitive browsing data to plain storage.

---

## 5. Testing & Pull Requests

Run the unit test suite before opening a PR:

```bash
./gradlew :core:common:test testReleaseUnitTest --no-daemon
```

### Commit Format

Use conventional commits:
- `feat(userscript): support @inject-into and @sandbox`
- `fix(update): retry failed update checks`
- `refactor(update): unify background workers into UpdateScheduler`
- `docs: update architecture guide`

### PR Checklist

- [ ] Code compiles cleanly and follows `.editorconfig` (4-space indentation).
- [ ] Unit tests pass (`./gradlew :core:common:test testReleaseUnitTest`).
- [ ] New color resources are paired in `res/values/colors.xml` and `res/values-night/colors.xml`.
- [ ] Architectural or feature changes are reflected in `AGENTS.md` and `docs/`.
