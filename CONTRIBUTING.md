# Contributing to Onyx Browser

Thank you for contributing to **Onyx Browser**. This guide covers workstation prerequisites, repository setup, module architecture, and pull request standards.

---

## 1. Prerequisites

To build and test Onyx Browser on a local Linux, macOS, or Windows (WSL2) workstation, ensure the following tools are installed:

- **JDK 17** (Eclipse Temurin 17 recommended)
- **Android SDK** (Compile/Target SDK **API 35**, Min SDK **API 26**)
- **Android NDK r26b+** (`26.3.11579264` or newer, with `ANDROID_NDK_HOME` configured)
- **Rust Stable Toolchain** + **`cargo-ndk`**:
  ```bash
  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
  cargo install cargo-ndk
  ```
- **Python 3** & **`curl`** (used by icon and filter list sync scripts)

---

## 2. Quick-Start Local Setup

Clone the repository with submodules and run the bootstrap script:

```bash
git clone --recurse-submodules https://github.com/abidhasansojib/onyx-browser.git
cd onyx-browser
./scripts/bootstrap_dev.sh
```

What `./scripts/bootstrap_dev.sh` does:
1. Initializes and updates Git submodules (`external/adblock-rust`, `external/adblock-lists`).
2. Applies `patches/adblock-rust.patch` idempotently.
3. Verifies symlinks (`rust_engine/adblock-rust` and `app/src/main/assets/brave-lists`) and syncs `brave-resources.json`.
4. Fetches and converts Lucide vector drawables (`./scripts/fetch_icons.sh`).
5. Compiles bundled EasyList + Brave filter rules (`./scripts/update_filter_lists.sh`).
6. Compiles `libadblock_bridge.so` into `app/src/main/jniLibs/` if `cargo-ndk` is installed (pass `--skip-rust` to skip native compilation when working only on Kotlin/JVM unit tests).

---

## 3. Architecture & Module Map

Onyx Browser is structured as a multi-module Gradle project with centralized version catalogs (`gradle/libs.versions.toml`):

| Module / Directory | Responsibility |
| :--- | :--- |
| `:app` | Primary Android application module (`com.onyx.browser`): Native XML ViewBinding UI, UDF ViewModels, Coordinators, hardened Chromium WebView engine, SQLCipher Room database, and Userscript engine. |
| `:core:common` | Shared multi-module utilities (`DispatcherProvider`, `DefaultDispatcherProvider`, and `OnyxResult` functional wrappers). |
| `rust_engine/` | Native Rust NDK bridge (`libadblock_bridge.so`) wrapping Brave's `adblock-rust` with JNI bindings for `AdBlockEngine.kt`. |
| `gradle/libs.versions.toml` | Centralized Gradle Version Catalog for all AndroidX, Kotlin, Room, SQLCipher, and test dependencies. |

### Key Subsystems in `:app`
- **UDF ViewModels (`com.onyx.browser.ui.viewmodel`)**: `BrowserViewModel`, `HistoryViewModel`, `BookmarksViewModel`, and `DownloadsViewModel` expose immutable `StateFlow` states and are constructed via `OnyxViewModelFactory` and `AppContainer`.
- **UI Coordinators & Delegates (`com.onyx.browser.ui.browser`)**: Focused controllers keep `MainActivity` lean:
  - `ToolbarLayoutDelegate` (Top/Bottom address bar `ConstraintSet`, Edge-to-Edge insets, scroll auto-hide)
  - `BrowserMenuCoordinator` (3-dot overflow menu, page actions, desktop site toggle, userscript menu commands)
  - `NavigationCoordinator` (URL resolution, search queries, `javascript:` execution, intent routing)
  - `WebChromeCoordinator`, `HomeScreenController`, `FileChooserCoordinator`, `WebContextMenuController`, `SettingsNavigator`, `PipController`
- **Network Interception Pipeline (`com.onyx.browser.web.interceptor`)**: Chain-of-Responsibility `RequestInterceptor` pipeline (`RealInterceptorChain`, `HttpsUpgradeInterceptor`, `AuthBypassInterceptor`, `AdBlockRequestInterceptor`, `PrivacyUrlSanitizer`).
- **External Navigation (`com.onyx.browser.web.navigation`)**: `ExternalNavigationHandler` decouples custom scheme resolution (`intent://`, `market://`, `mailto:`, `tel:`, external app prompts, and ad/betting redirect defense) from `OnyxWebViewClient`.
- **Encrypted Repositories (`com.onyx.browser.data.repository`)**: `SitePermissionRepository` ($O(1)$ lock-free `ConcurrentHashMap` cache backed by SQLCipher Room `SitePermissionEntity`), `HistoryRepository`, `BookmarkRepository`, and `DownloadRepository`.

For deep architectural specifications, read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and [docs/WEBVIEW.md](docs/WEBVIEW.md).

---

## 4. Extending Onyx Browser

### Adding a Network `RequestInterceptor`
1. Create a class in `app/src/main/java/com/onyx/browser/web/interceptor/` implementing `RequestInterceptor`:
   ```kotlin
   class CustomHeaderInterceptor : RequestInterceptor {
       override fun intercept(chain: RequestInterceptor.Chain): WebResourceResponse? {
           val request = chain.request
           // Inspect request and either return a synthetic WebResourceResponse or proceed:
           return chain.proceed(request)
       }
   }
   ```
2. Keep hot-path execution non-blocking (never perform disk or database I/O on Chromium's `shouldInterceptRequest` thread; use lock-free in-memory caches like `SitePermissionRepository`).
3. Register the interceptor in the `OnyxWebViewClient` pipeline and add unit tests in `app/src/test/java/com/onyx/browser/web/interceptor/InterceptorPipelineTest.kt`.

### Adding a UI Coordinator
1. Place new browser UI coordinators in `app/src/main/java/com/onyx/browser/ui/browser/`.
2. Pass narrow callbacks or `StateFlow` collectors rather than exposing mutable activity internals.
3. Bind coroutines to `lifecycleScope` / `viewModelScope` and use `NonCancellable` for critical persistence operations.

---

## 5. Running Unit Tests

Run the JVM unit test suite across `:core:common` and `:app`:

```bash
./gradlew :core:common:test testReleaseUnitTest --no-daemon
```

All pull requests and CI builds must pass the unit test gate cleanly.

---

## 6. Coding Standards & Pull Request Guidelines

- **Native Views Only**: Write native Android XML layouts with ViewBinding. Do not introduce Jetpack Compose dependencies.
- **Formatting**: Follow `.editorconfig` (4-space indentation for Kotlin/Java/Rust/XML, 2-space indentation for YAML/JSON/TOML/Markdown, UTF-8, LF line endings).
- **Theme Parity**: Always pair color definitions across `res/values/colors.xml` and `res/values-night/colors.xml`.
- **Conventional Commits**: Use concise conventional commit messages:
  - `feat(adblock): ...`
  - `fix(webview): ...`
  - `refactor(ui): ...`
  - `test(interceptor): ...`
  - `docs: ...`
- **Pull Requests**: Fill out `.github/PULL_REQUEST_TEMPLATE.md` completely, detailing the affected subsystem, verification steps, and security/privacy checklist.
