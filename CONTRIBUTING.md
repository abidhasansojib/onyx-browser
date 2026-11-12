# Contributing to Onyx Browser

Thank you for contributing to **Onyx Browser**. This guide covers workstation setup, repository architecture, coding standards, and pull request guidelines.

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
- **Python 3 & curl** (for asset & filter synchronization scripts)

---

## 2. Quick Setup

Clone the repository with submodules and run the bootstrap script:

```bash
git clone --recurse-submodules https://github.com/abidhasansojib/onyx-browser.git
cd onyx-browser
./scripts/bootstrap_dev.sh
```

`./scripts/bootstrap_dev.sh` automates:
1. Submodule initialization (`external/adblock-rust`, `external/adblock-lists`).
2. Symlinks and Brave filter assets synchronization.
3. Lucide icon vector conversion (`./scripts/fetch_icons.sh`).
4. Bundled EasyList and Brave filter list compilation (`./scripts/update_filter_lists.sh`).
5. Native NDK compilation of `libadblock_bridge.so` into `app/src/main/jniLibs/` (pass `--skip-rust` to skip when working purely on Kotlin/JVM code).

---

## 3. Module & Package Architecture

The project consists of two Gradle modules and a native Rust engine:

| Component | Responsibility |
| :--- | :--- |
| `:app` | Android application module (`com.onyx.browser`): Native XML UI, ViewModels, hardened Chromium WebView, SQLCipher database, and userscript engine. |
| `:core:common` | Multi-module utilities (`DispatcherProvider`, `DefaultDispatcherProvider`, and `OnyxResult`). |
| `rust_engine/` | Native Rust NDK bridge (`libadblock_bridge.so`) compiling Brave `adblock-rust` with JNI bindings for `AdBlockEngine`. |
| `gradle/libs.versions.toml` | Centralized Gradle Version Catalog for dependencies and plugins. |

### `:app` Package Organization

```text
com.onyx.browser
├── MainActivity.kt        # Primary activity, toolbar coordinator, and window orchestration
├── OnyxApplication.kt     # App entry point, DI container init, SQLCipher setup
├── autofill/              # System AutofillManager & Credential Manager passkey helpers
├── data/                  # Room database, SQLCipher keys, models, repositories, preferences
├── di/                    # Dependency injection (AppContainer, ViewModelFactory)
├── download/              # DownloadEngine, DownloadHandler, notifications, background service
├── incognito/             # IncognitoNotificationReceiver, isolated profile lifecycle
├── media/                 # Foreground MediaPlaybackService and MediaPlaybackBridge
├── nativebridge/          # AdBlockEngine JNI interface to libadblock_bridge.so
├── privacy/               # ClearOnExitService and ClearDataOnExitHelper
├── ui/                    # Presentation layer
│   ├── browser/           # UI coordinators (*Coordinator) and view controllers (*Controller)
│   ├── settings/          # Settings screens partitioned by domain (shields/, site/, userscript/, appearance/)
│   └── viewmodel/         # UDF StateFlow ViewModels (BrowserViewModel, HistoryViewModel, etc.)
├── userscript/            # Tampermonkey/Violentmonkey engine, metadata parser, and GM_* bridge
└── web/                   # Hardened Chromium WebView subsystem
    ├── adblock/           # Document-start adblock scripts, domain uncloaking, and DSD hydration fix
    ├── bridge/            # @JavascriptInterface bridges (Passkey, Shield, Form, Blob, Touch, Notification)
    ├── error/             # Synthetic HTTPS error pages and error bridges
    ├── interceptor/       # Chain-of-responsibility request interceptors (HTTPS, AdBlock, Sanitizer)
    ├── navigation/        # External scheme and app intent navigation handler
    └── translate/         # CSP-immune in-page DOM translation engine
```

---

## 4. Architectural Conventions & Suffix Standards

To keep the codebase predictable across the team, adhere to these standards:

1. **Native Views Over Compose**:
   - Use Android XML Views with ViewBinding.
   - Do not introduce Jetpack Compose dependencies.

2. **Standardized Class Suffixes**:
   - **`*Coordinator`**: For lifecycle, navigation, and dialog orchestrators (e.g., `BrowserMenuCoordinator`, `WebContextMenuCoordinator`, `NavigationCoordinator`, `TabCoordinator`).
   - **`*Controller`**: For bound View/UI state controllers (e.g., `ToolbarLayoutController`, `SearchController`, `FindInPageController`, `PipController`).
   - **`*BottomSheet`**: For all `BottomSheetDialogFragment` implementations (e.g., `MenuBottomSheet`, `SiteShieldBottomSheet`, `ClearOnExitBottomSheet`).
   - **`*Bridge`**: For `@JavascriptInterface` bridges in `web/bridge/` (e.g., `ShieldBridge`, `FormBridge`, `BlobBridge`, `PasskeyWebAuthnBridge`). *Note: Internal JS window interface names such as `window.OnyxShieldBridge` remain unchanged for script compatibility.*

3. **Performance on Interception Threads**:
   - `shouldInterceptRequest` executes on Chromium's background I/O threads. Never run blocking disk or database operations here. Always query lock-free in-memory caches (e.g., `SitePermissionRepository`).

4. **Persistence & Cryptography**:
   - All database records pass through SQLCipher AES-256 encryption via `OnyxDatabase`.
   - Never write sensitive browsing records or authentication secrets to unencrypted storage.

---

## 5. Development Workflow & Testing

### Running Unit Tests

Run the JVM test suite across `:core:common` and `:app`:

```bash
./gradlew :core:common:test testReleaseUnitTest --no-daemon
```

All pull requests must pass the unit test suite before merge.

### Conventional Commit Messages

Follow the conventional commit format with appropriate scopes:
- `feat(adblock): add support for $rule`
- `fix(navigation): handle custom intent scheme`
- `refactor(ui): extract address bar controller`
- `test(interceptor): add unit tests for url sanitizer`
- `docs: update subsystem architecture guide`

### Pull Request Checklist

Before submitting a PR:
- [ ] Code compiles cleanly and adheres to `.editorconfig` (4-space indentation for Kotlin/XML).
- [ ] Unit tests pass via `./gradlew testReleaseUnitTest`.
- [ ] Color resources are paired in `res/values/colors.xml` and `res/values-night/colors.xml`.
- [ ] Any new architectural changes are reflected in `AGENTS.md` and `docs/`.
